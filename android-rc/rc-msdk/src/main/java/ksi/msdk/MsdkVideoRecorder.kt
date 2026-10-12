package ksi.msdk

import android.content.Context
import java.io.File
import ksi.safetyedge.rc.VideoRecorder

/**
 * Segment-file rotation policy (pure, host-tested). Segment length comes
 * from the mission's `recording_policy.segment_seconds`; file names sort
 * chronologically so the uploader's segment_index mapping is deterministic.
 */
class SegmentRotationPolicy(private val segmentSeconds: Int = 300) {
    init {
        require(segmentSeconds >= 1) { "segmentSeconds must be >= 1" }
    }

    fun segmentIndexFor(elapsedMs: Long): Long {
        require(elapsedMs >= 0) { "elapsedMs must be >= 0" }
        return elapsedMs / (segmentSeconds * 1000L)
    }

    fun fileName(index: Long): String = "seg-%05d.mp4".format(index)
}

/**
 * [VideoRecorder] for the RC (A9). Session layout and rotation policy are
 * implemented; the encoded writer (MediaMuxer fed by the IVideoFrame data)
 * is attached on device — until then [stop] returns only files that
 * REALLY exist, so nothing synthetic is ever presented as footage.
 */
class MsdkVideoRecorder(private val context: Context) : VideoRecorder {
    override var state: VideoRecorder.State = VideoRecorder.State.IDLE
        private set

    private var sessionDir: File? = null

    private val baseDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    override fun start(flightId: String) {
        sessionDir = File(baseDir, "segments/$flightId").apply { mkdirs() }
        state = VideoRecorder.State.RECORDING
    }

    override fun stop(): List<File> {
        state = VideoRecorder.State.IDLE
        // TODO(device): finalize the open segment here once the writer is
        // attached; return only truly written .mp4 files.
        return sessionDir?.listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
            ?.sortedBy { it.name } ?: emptyList()
    }
}
