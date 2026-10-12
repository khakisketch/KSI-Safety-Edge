package ksi.msdk

/**
 * One encoded access unit from the MSDK video channel (A9-3): raw stream
 * bytes + the metadata the display decoder and the MP4 writer need. SPS/PPS
 * may be present only on key frames — callers keep the last non-empty set.
 *
 * `pts` is the stream timestamp (unit must be confirmed on device before any
 * playable-file claim; see MsdkVideoRecorder).
 */
class EncodedVideoFrame(
    val data: ByteArray,
    val pts: Long,
    val seq: Long,
    val isKeyFrame: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sps: ByteArray?,
    val pps: ByteArray?,
)

/** Receives encoded frames from a video source (implemented by the recorder). */
interface VideoFrameSink {
    fun onEncodedFrame(frame: EncodedVideoFrame)
}
