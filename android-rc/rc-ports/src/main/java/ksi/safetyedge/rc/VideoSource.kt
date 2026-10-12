package ksi.safetyedge.rc

/**
 * Live-video boundary (A6). The DJI MSDK v5 implementation lands with H1
 * (same interface); [FakeVideoSource] is a bench state-machine source that
 * produces NO frames — a fake cannot prove video, so it says so.
 *
 * Overlay rule: frames and observations are different domains until the
 * frame<->observation mapping is VERIFIED (H1, exposure-based). Until then
 * [frameMappingVerified] is false and the UI must not draw detections at
 * video coordinates — it lists them as data instead.
 */
interface VideoSource {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, NO_SIGNAL }

    interface Listener {
        fun onState(state: State)

        /** Frames are counted, never invented: fake sources stay at 0. */
        fun onFrame(frameCount: Long)
    }

    val state: State
    val isSimulated: Boolean

    /** True only after the H1 frame<->observation mapping is verified. */
    val frameMappingVerified: Boolean

    fun setListener(listener: Listener?)
    fun connect()
    fun disconnect()
}

/** Bench double: state machine only, no frames, overlays never allowed. */
class FakeVideoSource : VideoSource {
    private var listener: VideoSource.Listener? = null
    override var state: VideoSource.State = VideoSource.State.DISCONNECTED
        private set
    override val isSimulated: Boolean = true
    override val frameMappingVerified: Boolean = false

    override fun setListener(listener: VideoSource.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (state != VideoSource.State.DISCONNECTED) return
        setState(VideoSource.State.CONNECTING)
        // No MSDK pipe exists on the bench: connecting completes into a
        // source that delivers NO frames (the UI shows frames: 0).
        setState(VideoSource.State.CONNECTED)
    }

    override fun disconnect() {
        setState(VideoSource.State.DISCONNECTED)
    }

    private fun setState(next: VideoSource.State) {
        state = next
        listener?.onState(next)
    }
}
