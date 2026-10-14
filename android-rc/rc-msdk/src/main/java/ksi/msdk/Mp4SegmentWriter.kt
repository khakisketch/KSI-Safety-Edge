package ksi.msdk

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * MediaMuxer-backed writer for ONE H.264 segment (A9-3, DEVICE gate).
 *
 * Start gating (review C63 #1): the track is created from the frame's own
 * SPS/PPS. Recording can begin mid-GOP, so a frame without CSD does NOT
 * poison the segment — [SegmentRecorderCore] waits before calling [write]
 * for the first keyframe that carries SPS/PPS; the segment then starts at a
 * keyframe, which is also better for playback. Only a real muxer failure
 * marks the writer failed.
 *
 * Failure handling (review C63 #8): the muxer is assigned as soon as it is
 * constructed; an addTrack/start failure releases it instead of leaking it.
 *
 * Each access unit is written as one sample at the stream PTS.
 * TODO(device): confirm the PTS unit against a playable file; a wrong unit
 * shows up as a broken timeline, not as lost data.
 */
class Mp4SegmentWriter(private val path: String) : VideoSegmentWriter {
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun write(frame: EncodedVideoFrame): Boolean {
        if (failed) return false
        if (!started) {
            val sps = frame.sps
            val pps = frame.pps
            if (sps == null || pps == null || sps.isEmpty() || pps.isEmpty() ||
                frame.width <= 0 || frame.height <= 0
            ) {
                // No codec config yet: wait (drop pre-config frames) instead
                // of failing the whole segment window.
                return false
            }
            val muxer = try {
                MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (_: Exception) {
                failed = true
                return false
            }
            this.muxer = muxer
            try {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, frame.width, frame.height)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                if (frame.fps > 0) {
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, frame.fps)
                }
                trackIndex = muxer.addTrack(format)
                muxer.start()
            } catch (_: Exception) {
                failed = true
                this.muxer = null
                try {
                    muxer.release()
                } catch (_: Exception) {
                    // best effort
                }
                return false
            }
            started = true
        }
        val active = muxer ?: return false
        return try {
            bufferInfo.set(
                0,
                frame.data.size,
                frame.pts,
                if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
            active.writeSampleData(trackIndex, ByteBuffer.wrap(frame.data), bufferInfo)
            true
        } catch (_: Exception) {
            failed = true
            false
        }
    }

    override fun close(): Boolean {
        val active = muxer ?: return false // never started: no file to trust
        muxer = null
        return try {
            if (!failed) {
                active.stop()
            }
            active.release()
            !failed
        } catch (_: Exception) {
            try {
                active.release()
            } catch (_: Exception) {
                // best effort
            }
            false
        }
    }

    companion object {
        private const val MIME_TYPE = "video/avc"
    }
}
