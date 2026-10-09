package ksi.safetyedge.rc

/**
 * Drone-link boundary (A4). The RC app talks to the aircraft only through
 * this interface: outbound MOP bytes go out via [send], inbound bytes come
 * back through [Listener.onBytes], and [state] reports the link itself.
 *
 * Implementations: [FakeDroneLink] (dev/demo, in this module) and — once
 * the RC Plus 2 + MSDK key are available (H1 gate) — a DJI MSDK v5
 * implementation that bridges MSDK data channels to raw MOP bytes.
 * Nothing in rc-core knows which implementation is attached.
 */
interface DroneLink {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onBytes(bytes: ByteArray)
        fun onState(state: State)
    }

    val state: State
    /** True for bench/demo links; the MSDK implementation reports false. */
    val isSimulated: Boolean
    /** Aircraft connection, INDEPENDENT of the link state.
     *  null = the link cannot report it (Fake/dev link) — shown as
     *  "unknown", never guessed. */
    val aircraftConnected: Boolean?
    fun setListener(listener: Listener?)
    fun connect()
    fun disconnect()

    /** Best-effort send; returns false when not connected (bytes stay in
     *  the rc-core outbox for retry — the link never drops them silently). */
    fun send(bytes: ByteArray): Boolean
}

/**
 * Dev/demo link: scripted state transitions with no real radio behind them.
 * The demo evidence feed ([DemoFeed]) is separate: it builds real MOP
 * envelopes that travel the real ingest path, and is always labeled
 * SIMULATED in the UI.
 */
class FakeDroneLink : DroneLink {
    private var listener: DroneLink.Listener? = null
    override var state: DroneLink.State = DroneLink.State.DISCONNECTED
        private set
    override val isSimulated: Boolean = true
    override val aircraftConnected: Boolean? = null

    val sentBytes = mutableListOf<ByteArray>()

    override fun setListener(listener: DroneLink.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (state != DroneLink.State.DISCONNECTED) return
        setState(DroneLink.State.CONNECTING)
        // No real handshake yet (MSDK wiring is H1-gated); the dev link
        // comes up immediately so screens can be exercised end to end.
        setState(DroneLink.State.CONNECTED)
    }

    override fun disconnect() {
        setState(DroneLink.State.DISCONNECTED)
    }

    override fun send(bytes: ByteArray): Boolean {
        if (state != DroneLink.State.CONNECTED) return false
        sentBytes.add(bytes)
        return true
    }

    private fun setState(next: DroneLink.State) {
        state = next
        listener?.onState(next)
    }
}
