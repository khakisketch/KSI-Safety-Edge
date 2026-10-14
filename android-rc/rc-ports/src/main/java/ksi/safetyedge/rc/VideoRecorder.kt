package ksi.safetyedge.rc

import java.io.File

/**
 * A9/H1 seam for RC proxy recording. The MSDK implementation will encode
 * the incoming video stream into low-bitrate segmented files (per the
 * mission's `recording_policy.segment_seconds`) and hand each finalized
 * segment to [ksi.rc.SegmentIngestor] — the storage/upload path that is
 * implemented and tested now (manifest, hashes, COMMITTED-gated sync,
 * retention).
 *
 * There is deliberately NO fake implementation: a fake cannot record real
 * video, and nothing in this app may present synthetic footage as flight
 * evidence. Until the MSDK recorder exists, flights have no proxy segments
 * — which the UI states honestly rather than hiding.
 */
interface VideoRecorder {
    enum class State { IDLE, RECORDING, ERROR }

    val state: State

    /** Actual samples accepted by the writer, not the request to start.
     *  Null means this recorder cannot report progress. */
    val writtenFrameCount: Long? get() = null

    /** Write/finalize failures persist for the current recording session
     *  even when a later segment recovers. Null = not measured. */
    val hasWriteFailure: Boolean? get() = null

    /** Start recording for a flight (segment rollover per mission policy). */
    fun start(flightId: String)

    /** Stop and finalize the current segment; returns every completed
     *  segment file of this recording session (caller ingests them). */
    fun stop(): List<File>
}
