package ksi.msdk

import android.content.Context
import android.os.SystemClock
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
 * channel-based interface (`VideoStreamManager` + `IVideoChannel`) rather
 * than the legacy decoded-callback pattern:
 * - stream sources are discovered via `getAvailableStreamSources()`;
 * - the primary channel is started with `startChannel(source, cb)`;
 * - raw stream frames arrive on `StreamDataListener.onReceive(IVideoFrame)`
 *   (getData/getSeqNumber/getPts) — counting them is honest frame evidence;
 * - channel state drives our link state (ON / SOCKET_ON / CLOSE).
 *
 * Overlay honesty is unchanged: [frameMappingVerified] stays false until
 * the exposure-based frame<->observation mapping is measured on device.
 */
class MsdkVideoSource(private val context: Context) : VideoSource {
    private var listener: VideoSource.Listener? = null
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

    // Lazy: no SDK listener objects until connect() runs (construction must
    // not require SDK runtime classes — host tests / app start).
    private val streamDataListener by lazy {
        StreamDataListener { frame ->
            // TODO(device): feed raw frames (frame.data/seq/PTS) to the
            // recorder once segment encoding is wired; counting alone never
            // claims video.
            frameCount += 1
            lastFrameUptimeMs = SystemClock.elapsedRealtime()
            listener?.onFrame(frameCount)
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
            } catch (_: Exception) {
                // best-effort close
            }
        }
        setState(VideoSource.State.DISCONNECTED)
    }

    var lastError: String? = null
        private set

    private fun setState(next: VideoSource.State) {
        if (state == next) return
        state = next
        listener?.onState(next)
    }
}
