package ksi.msdk

/**
 * One MP4 segment being written (A9-3). The writer owns the muxer; the
 * caller ([SegmentRecorderCore]) owns naming/rotation and treats the
 * segment as evidence ONLY when [close] returns true.
 */
interface VideoSegmentWriter {
    /** Append one encoded frame. False = this segment cannot be trusted. */
    fun write(frame: EncodedVideoFrame): Boolean

    /** Finalize the file. False = the segment must NOT be published. */
    fun close(): Boolean
}
