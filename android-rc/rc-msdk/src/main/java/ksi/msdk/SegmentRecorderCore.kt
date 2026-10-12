package ksi.msdk

import java.io.File
import ksi.safetyedge.rc.SegmentNames

/**
 * Segment rotation core (A9-3), host-tested: naming, rollover per
 * [SegmentRotationPolicy], crash safety and evidence honesty. The device
 * writer ([Mp4SegmentWriter]) and the frame source live behind seams, so
 * this logic runs on the JVM in tests.
 *
 * Thread contract (review C63 #2/#3): every public method is synchronized —
 * the DJI stream callback drives [onFrame] while the UI thread calls [stop]
 * at flight end; no state may be read/written concurrently.
 *
 * Evidence contract (review C63 #5): a segment is published as `.mp4` only
 * when the writer closed successfully AND no frame write inside it reported
 * failure ([VideoSegmentWriter] documents `write() == false` as "cannot be
 * trusted").
 *
 * Crash safety: every segment is written to `<name>.mp4.tmp` and renamed to
 * `<name>.mp4` only after finalization succeeded. A process death (or a
 * writer failure) leaves a `.tmp` that is never presented as evidence —
 * [stop] returns only finalized `.mp4` files that really exist.
 *
 * Rotation semantics: a segment rolls over on the FIRST frame whose elapsed
 * time is >= segment_seconds past the current segment start. A failed open
 * does NOT end the recording: the next rotation boundary attempts a fresh
 * segment (review C63 #4), so a transient open failure costs one segment
 * window instead of the rest of the flight.
 */
class SegmentRecorderCore(
    private val policy: SegmentRotationPolicy,
    private val writerFactory: (File) -> VideoSegmentWriter,
) {
    private var dir: File? = null
    private var writer: VideoSegmentWriter? = null
    private var activeTmp: File? = null
    private var activeFinal: File? = null
    private var segmentIndex = 0L
    private var segmentStartMs = 0L
    private val finalized = mutableListOf<File>()
    private var stopped = false
    private var currentWriteFailed = false

    var failedSegments = 0
        private set
    var lastError: String? = null
        private set

    @get:Synchronized
    val isRecording: Boolean
        get() = writer != null

    /** Opens the next free segment under [directory]. False = nothing opened.
     *
     *  Existing files (finalized `.mp4` or crash-left `.tmp` leftovers) are
     *  never overwritten: a restarted recording continues the numbering of
     *  its flight directory (review C63 residual: restart resume, and
     *  re-recording the same flight). */
    @Synchronized
    fun start(directory: File, elapsedMs: Long): Boolean {
        dir = directory
        finalized.clear()
        failedSegments = 0
        lastError = null
        segmentIndex = nextFreeIndex(directory)
        segmentStartMs = elapsedMs
        stopped = false
        currentWriteFailed = false
        return openSegment()
    }

    private fun nextFreeIndex(directory: File): Long {
        val names = directory.list() ?: return 0L
        var maxIndex = -1L
        for (name in names) {
            val base = if (name.endsWith(".tmp")) name.dropLast(4) else name
            val index = SegmentNames.parseIndex(base) ?: continue
            if (index > maxIndex) maxIndex = index
        }
        return maxIndex + 1
    }

    /** Appends one frame; rotates first when this frame crossed the boundary. */
    @Synchronized
    fun onFrame(frame: EncodedVideoFrame, elapsedMs: Long): Boolean {
        if (stopped) return false
        val segmentMs = policy.segmentSeconds * 1000L
        val boundaryDue = elapsedMs - segmentStartMs >= segmentMs
        if (writer == null) {
            // A previous segment failed to open: retry only at the NEXT
            // rotation boundary, so a broken writer cannot cause a per-frame
            // open storm (review C63 #4).
            if (!boundaryDue) return false
            segmentIndex += 1
            segmentStartMs = elapsedMs
            if (!openSegment()) {
                failedSegments += 1
                return false
            }
        } else if (boundaryDue) {
            closeSegment() // keeps recording even when one segment failed
            segmentIndex += 1
            segmentStartMs = elapsedMs
            if (!openSegment()) {
                failedSegments += 1
                return false
            }
        }
        val current = writer ?: return false
        return try {
            val written = current.write(frame)
            if (!written) currentWriteFailed = true
            written
        } catch (exc: Exception) {
            currentWriteFailed = true
            lastError = "write failed: ${exc.message}"
            false
        }
    }

    /** Finalizes the current segment and returns the evidence-ready files. */
    @Synchronized
    fun stop(): List<File> {
        stopped = true
        if (writer != null) closeSegment()
        return finalized.filter { it.isFile }
    }

    private fun openSegment(): Boolean {
        val directory = dir ?: return false
        if (!directory.isDirectory && !directory.mkdirs()) {
            lastError = "cannot create segment dir: $directory"
            return false
        }
        val name = policy.fileName(segmentIndex)
        val finalFile = File(directory, name)
        val tmp = File(directory, "$name.tmp")
        activeFinal = finalFile
        activeTmp = tmp
        currentWriteFailed = false
        writer = try {
            writerFactory(tmp)
        } catch (exc: Exception) {
            lastError = "writer open failed: ${exc.message}"
            null
        }
        if (writer == null) {
            activeFinal = null
            activeTmp = null
            return false
        }
        return true
    }

    private fun closeSegment() {
        val active = writer ?: return
        writer = null
        val tmp = activeTmp
        val finalFile = activeFinal
        activeTmp = null
        activeFinal = null
        val writeFailed = currentWriteFailed
        currentWriteFailed = false
        val closed = try {
            active.close()
        } catch (exc: Exception) {
            lastError = "close failed: ${exc.message}"
            false
        }
        if (!closed || writeFailed || tmp == null || finalFile == null) {
            // A failed muxer OR an untrusted frame means the bytes cannot be
            // presented: the .tmp stays on disk for diagnosis and is never
            // renamed to evidence (review C63 #5).
            failedSegments += 1
            if (closed && (tmp == null || finalFile == null)) {
                lastError = "segment bookkeeping missing"
            }
            return
        }
        if (!tmp.isFile || tmp.length() <= 0L) {
            failedSegments += 1
            lastError = "empty segment output"
            return
        }
        if (!tmp.renameTo(finalFile)) {
            failedSegments += 1
            lastError = "finalize rename failed: ${finalFile.name}"
            return
        }
        finalized.add(finalFile)
    }
}
