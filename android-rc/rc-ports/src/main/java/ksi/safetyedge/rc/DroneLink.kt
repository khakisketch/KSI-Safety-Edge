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

    /**
     * Which physical path carries these bytes — "AIRCRAFT" for the product
     * path (MSDK data channel), "TEST_TCP" for the development link.
     *
     * WHY: the test link carries REAL Edge bytes over a different physical
     * path. The UI must be able to say which path it is, so nobody reads a
     * LAN-linked session as the aircraft path.
     */
    val linkKind: String get() = "AIRCRAFT"
    /** Aircraft connection, INDEPENDENT of the link state.
     *  null = the link cannot report it (Fake/dev link) — shown as
     *  "unknown", never guessed. */
    val aircraftConnected: Boolean?

    /**
     * D3 — DJI SDK 단계의 사실 (연결 3단 진단).
     *
     * 기본값은 **앱이 SDK를 모르는 경우**다: 키 미설치(앱 키 없음),
     * 초기화 미확인. MSDK 구현은 실제 읽은 값으로 덮어쓴다.
     * "확인 불가"와 "미연결"은 다른 사실이므로 null을 0/false로 바꾸지 않는다.
     */
    val sdkKeyPresent: Boolean get() = false

    /** true = SDK 등록까지 성공. false/기본값 = 초기화되지 않았거나 실패. */
    val sdkInitialised: Boolean get() = false

    /** SDK 단계의 마지막 오류(없으면 null). */
    val sdkError: String? get() = null

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
