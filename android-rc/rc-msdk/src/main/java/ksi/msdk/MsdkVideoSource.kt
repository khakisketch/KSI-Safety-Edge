package ksi.msdk

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.video.channel.VideoChannelState
import dji.v5.common.video.channel.VideoChannelType
import dji.v5.common.video.interfaces.IVideoChannel
import dji.v5.common.video.interfaces.StreamDataListener
import dji.v5.common.video.interfaces.VideoChannelStateChangeListener
import dji.v5.manager.datacenter.video.VideoStreamManager
import ksi.safetyedge.rc.VideoSource

/**
 * [VideoSource] over the DJI v5 video stream API (A9), using the RECOMMENDED
 * channel-based interface (`VideoStreamManager` + `IVideoChannel`):
 * - stream sources are discovered via `getAvailableStreamSources()`;
 * - the primary channel is started with `startChannel(source, cb)`;
 * - raw stream frames arrive on `StreamDataListener.onReceive(IVideoFrame)`
 *   (data/SPS/PPS/PTS/seq) — counting them is honest frame evidence;
 * - channel state drives our link state (ON / SOCKET_ON / CLOSE).
 *
 * A9-3 fan-out (display + recording are INDEPENDENT consumers):
 * - [attachSurface] wires a live display decoder ([H264SurfaceDecoder]);
 *   without a surface the frames are still counted and recorded;
 * - [setFrameSink] hands encoded frames to the recorder — a display
 *   failure never stops recording and vice versa.
 *
 * Overlay honesty is unchanged: [frameMappingVerified] stays false until
 * the exposure-based frame<->observation mapping is measured on device.
 */
class MsdkVideoSource(private val context: Context) : VideoSource {
    private var listener: VideoSource.Listener? = null

    @Volatile
    override var state: VideoSource.State = VideoSource.State.DISCONNECTED
        private set
    override val isSimulated: Boolean = false
    override val frameMappingVerified: Boolean = false

    private var channel: IVideoChannel? = null

    @Volatile
    private var frameCount = 0L

    @Volatile
    var lastFrameUptimeMs: Long = 0L
        private set

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var decoder: H264SurfaceDecoder? = null

    @Volatile
    private var frameSink: VideoFrameSink? = null

    @Volatile
    private var lastDecoderAttemptMs = 0L

    // Lazy: no SDK listener objects until connect() runs (construction must
    // not require SDK runtime classes — host tests / app start).
    private val streamDataListener by lazy {
        StreamDataListener { frame ->
            frameCount += 1
            lastFrameUptimeMs = SystemClock.elapsedRealtime()
            listener?.onFrame(frameCount)

            val encoded = EncodedVideoFrame(
                data = frame.data,
                pts = frame.pts,
                seq = frame.seqNumber,
                isKeyFrame = frame.isIFrame,
                width = frame.width,
                height = frame.height,
                fps = frame.fps,
                sps = frame.sps,
                pps = frame.pps,
            )
            // Display path (only with an attached surface): the decoder is
            // created lazily on the first frame, which carries the size.
            // Creation failures retry at most once per second so a broken
            // codec can never burn the SDK callback thread per frame
            // (review C63 #11).
            val target = surface
            if (target != null) {
                var active = decoder
                if (active == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDecoderAttemptMs >= DECODER_RETRY_MS) {
                        lastDecoderAttemptMs = now
                        active = H264SurfaceDecoder.create(target, frame.width, frame.height)
                        decoder = active
                    }
                }
                active?.feed(encoded)
            }
            // Recording path: independent of the display surface. A recorder
            // failure must never kill the SDK callback thread (C63 #3).
            try {
                frameSink?.onEncodedFrame(encoded)
            } catch (_: Exception) {
                // The recorder already records its own error state.
            }
        }
    }

    private val channelStateListener by lazy {
        VideoChannelStateChangeListener { _, newState ->
            when (newState) {
                VideoChannelState.ON -> setState(VideoSource.State.CONNECTED)
                VideoChannelState.SOCKET_ON -> setState(VideoSource.State.CONNECTING)
                VideoChannelState.CLOSE ->
                    setState(
                        if (state == VideoSource.State.DISCONNECTED) {
                            VideoSource.State.DISCONNECTED
                        } else {
                            VideoSource.State.NO_SIGNAL
                        },
                    )
                else -> Unit
            }
        }
    }

    override fun setListener(listener: VideoSource.Listener?) {
        this.listener = listener
    }

    /** Wires (or detaches with null) the live display surface. */
    fun attachSurface(target: Surface?) {
        surface = target
        if (target == null) {
            val active = decoder
            decoder = null
            active?.release()
        }
    }

    /** Wires (or detaches) the encoded-frame consumer (the recorder). */
    fun setFrameSink(sink: VideoFrameSink?) {
        frameSink = sink
    }

    override fun connect() {
        if (state != VideoSource.State.DISCONNECTED) return
        setState(VideoSource.State.CONNECTING)
        try {
            val manager = VideoStreamManager.getInstance()
            val target = manager.getAvailableVideoChannel(
                VideoChannelType.PRIMARY_STREAM_CHANNEL,
            ) ?: run {
                setState(VideoSource.State.NO_SIGNAL)
                return
            }
            val source = manager.getAvailableStreamSources().firstOrNull()
                ?: run {
                    // TODO(device): stream-source selection policy (camera
                    // position/type) is confirmed on the RC.
                    setState(VideoSource.State.NO_SIGNAL)
                    return
                }
            target.addStreamDataListener(streamDataListener)
            target.addVideoChannelStateChangeListener(channelStateListener)
            target.startChannel(source, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // Channel ON arrives via the state listener; nothing to
                    // claim here.
                }

                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    lastError = error.description()
                    setState(VideoSource.State.NO_SIGNAL)
                }
            })
            channel = target
        } catch (_: LinkageError) {
            // SDK runtime missing (Helper bootstrap not installed): honest
            // NO_SIGNAL instead of a crash. connect() is expected to run
            // only after the link reports REGISTERED (app connect order).
            setState(VideoSource.State.NO_SIGNAL)
        } catch (_: Exception) {
            // Device-gate path: the SDK may be unregistered/unavailable.
            setState(VideoSource.State.NO_SIGNAL)
        }
    }

    override fun disconnect() {
        val target = channel
        channel = null
        if (target != null) {
            try {
                target.removeStreamDataListener(streamDataListener)
                target.removeVideoChannelStateChangeListener(channelStateListener)
                target.closeChannel(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {}
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {}
                })
            } catch (_: LinkageError) {
                // best-effort close
            } catch (_: Exception) {
                // best-effort close
            }
        }
        val active = decoder
        decoder = null
        active?.release()
        setState(VideoSource.State.DISCONNECTED)
    }

    @Volatile
    var lastError: String? = null
        private set

    private fun setState(next: VideoSource.State) {
        if (state == next) return
        state = next
        listener?.onState(next)
    }

    companion object {
        /** Decoder create/configure/start retry interval on failure. */
        private const val DECODER_RETRY_MS = 1000L
    }
}
