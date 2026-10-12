package ksi.msdk

import android.content.Context
import android.os.SystemClock
import java.io.File
import ksi.safetyedge.rc.SegmentNames
import ksi.safetyedge.rc.VideoRecorder

/**
 * Segment-file rotation policy (pure, host-tested). Segment length comes
 * from the mission's `recording_policy.segment_seconds`; file names sort
 * chronologically so the uploader's segment_index mapping is deterministic.
 */
class SegmentRotationPolicy(val segmentSeconds: Int = 300) {
    init {
        require(segmentSeconds >= 1) { "segmentSeconds must be >= 1" }
    }

    fun segmentIndexFor(elapsedMs: Long): Long {
        require(elapsedMs >= 0) { "elapsedMs must be >= 0" }
        return elapsedMs / (segmentSeconds * 1000L)
    }

    fun fileName(index: Long): String = SegmentNames.fileName(index)
}

/**
 * [VideoRecorder] for the RC (A9-3, DEVICE gate): real MP4 segments via
 * [Mp4SegmentWriter], rotated by [SegmentRecorderCore]. Frames arrive from
 * [MsdkVideoSource] through [VideoFrameSink] (wired per flavor by
 * PlatformLinks).
 *
 * Evidence rules: segments finalize as `.mp4` only after the muxer closed
 * successfully; a killed process leaves `.tmp` files which are NEVER
 * evidence — [stop] returns only finalized files that really exist.
 */
class MsdkVideoRecorder(private val context: Context) :
    VideoRecorder, VideoFrameSink {
    override var state: VideoRecorder.State = VideoRecorder.State.IDLE
        private set

    /** Mission `recording_policy.segment_seconds`, applied before start.
     *  Mutation while RECORDING is ignored with a recorded reason (review
     *  residual: the policy is snapshotted into the core at start). */
    var segmentSeconds: Int = 300
        set(value) {
            if (state == VideoRecorder.State.RECORDING) {
                lastError = "segmentSeconds ignored while recording"
                return
            }
            field = value
        }

    var lastError: String? = null
        private set

    // Written by the UI thread (start/stop) and read by the DJI callback
    // thread; SegmentRecorderCore synchronizes its own state (C63 #2).
    @Volatile
    private var core: SegmentRecorderCore? = null
    private var startedElapsedMs = 0L
    private val baseDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    override fun start(flightId: String) {
        if (state == VideoRecorder.State.RECORDING) {
            lastError = "already recording; stop() first"
            return
        }
        val directory = File(baseDir, "segments/$flightId")
        val core = SegmentRecorderCore(SegmentRotationPolicy(segmentSeconds)) { tmp ->
            Mp4SegmentWriter(tmp.absolutePath)
        }
        startedElapsedMs = SystemClock.elapsedRealtime()
        if (!core.start(directory, 0L)) {
            lastError = core.lastError ?: "segment start failed"
            state = VideoRecorder.State.ERROR
            return
        }
        this.core = core
        lastError = null
        state = VideoRecorder.State.RECORDING
    }

    override fun onEncodedFrame(frame: EncodedVideoFrame) {
        val active = core ?: return
        if (state != VideoRecorder.State.RECORDING) return
        val elapsed = SystemClock.elapsedRealtime() - startedElapsedMs
        try {
            if (!active.onFrame(frame, elapsed)) {
                lastError = active.lastError ?: "segment write failed"
                // One bad segment never stops the recording; its `.tmp`
                // output stays out of evidence (SegmentRecorderCore).
            }
        } catch (exc: Exception) {
            // Never propagate into the SDK callback thread (review C63 #3).
            lastError = "recorder error: ${exc.message}"
        }
    }

    override fun stop(): List<File> {
        val active = core ?: run {
            state = VideoRecorder.State.IDLE
            return emptyList()
        }
        core = null
        state = VideoRecorder.State.IDLE
        val files = active.stop()
        if (active.failedSegments > 0) {
            lastError =
                "${active.failedSegments} segment(s) failed: ${active.lastError ?: "?"}"
        }
        return files
    }
}
