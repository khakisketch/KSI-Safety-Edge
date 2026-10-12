package ksi.msdk

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * Minimal H.264 -> Surface display decoder (A9-3, DEVICE gate) for the live
 * view. It renders the SAME stream the recorder muxes; a busy/broken
 * decoder never blocks the reader thread (frames are dropped, the stream
 * counter and the recorder stay independent).
 *
 * Honesty unchanged: this is display only — `frameMappingVerified` stays
 * false until the exposure-based frame<->observation mapping is measured on
 * device, so no detection is ever drawn at video coordinates.
 */
class H264SurfaceDecoder private constructor(
    private val codec: MediaCodec,
) {
    private var released = false
    private val bufferInfo = MediaCodec.BufferInfo()

    fun feed(frame: EncodedVideoFrame): Boolean {
        if (released) return false
        return try {
            val index = codec.dequeueInputBuffer(0L)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return false
                buffer.clear()
                if (buffer.capacity() < frame.data.size) {
                    // Frame larger than the codec buffer: drop it (never
                    // queue a truncated access unit).
                    codec.queueInputBuffer(index, 0, 0, frame.pts, 0)
                } else {
                    buffer.put(frame.data)
                    codec.queueInputBuffer(index, 0, frame.data.size, frame.pts, 0)
                }
            }
            drain()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0L)
            if (index < 0) return
            codec.releaseOutputBuffer(index, true)
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
            // best effort
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // best effort
        }
    }

    companion object {
        fun create(surface: Surface, width: Int, height: Int): H264SurfaceDecoder? {
            if (width <= 0 || height <= 0) return null
            return try {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
                val codec = MediaCodec.createDecoderByType(MIME_TYPE)
                codec.configure(format, surface, null, 0)
                codec.start()
                H264SurfaceDecoder(codec)
            } catch (_: Exception) {
                null
            }
        }

        private const val MIME_TYPE = "video/avc"
    }
}
