package ksi.safetyedge.rc

/**
 * Drone-link state exposure for the UI (Unit B of the 2026-10-21
 * edge-operation contract §5): "communication lost + last confirmed
 * execution time" without assuming normality.
 *
 * The monitor installs itself as the link's [DroneLink.Listener] and
 * records every state transition and every received byte with a monotonic
 * timestamp. Reads are volatile (safe from any thread, including the UI);
 * the link's reader thread is never blocked and never polled — updates
 * arrive via the listener callbacks, and downstream forwarding is a plain
 * synchronous call the delegate must keep fast (post to a UI thread
 * instead of working here).
 *
 * Link loss is reported as [LinkSnapshot.lost] (state != CONNECTED) with
 * [LinkSnapshot.lastActivityMonotonicNs] as the last confirmed activity.
 * A lost link is never "normal": consumers must show the lost state plus
 * the recorded last-activity time, not a guessed current state.
 */
class LinkStateMonitor(
    private val clock: () -> Long = System::nanoTime,
) : DroneLink.Listener {

    /** Downstream byte/state consumer (the app's transport pump). */
    @Volatile
    var downstream: DroneLink.Listener? = null

    @Volatile
    var state: DroneLink.State = DroneLink.State.DISCONNECTED
        private set

    /** Monotonic ns of the last state transition. */
    @Volatile
    var lastStateChangeMonotonicNs: Long = clock()
        private set

    /** Monotonic ns of the last received byte (or state change). Null when
     *  no activity has been observed yet. */
    @Volatile
    var lastActivityMonotonicNs: Long? = null
        private set

    private val stateCallbacks = mutableListOf<(DroneLink.State) -> Unit>()

    /** Install on a link; previously installed listeners keep working via
     *  [downstream]. Call once (re-attaching moves the callbacks). */
    fun attach(link: DroneLink, downstream: DroneLink.Listener? = null) {
        this.downstream = downstream
        link.setListener(this)
    }

    /** UI callbacks run on the link-reader thread: keep them fast. */
    @Synchronized
    fun addStateCallback(callback: (DroneLink.State) -> Unit) {
        stateCallbacks.add(callback)
    }

    fun snapshot(): LinkSnapshot = LinkSnapshot(
        state = state,
        lost = state != DroneLink.State.CONNECTED,
        lastStateChangeMonotonicNs = lastStateChangeMonotonicNs,
        lastActivityMonotonicNs = lastActivityMonotonicNs,
    )

    override fun onBytes(bytes: ByteArray) {
        lastActivityMonotonicNs = clock()
        downstream?.onBytes(bytes)
    }

    override fun onState(state: DroneLink.State) {
        val now = clock()
        this.state = state
        lastStateChangeMonotonicNs = now
        lastActivityMonotonicNs = now
        val callbacks: List<(DroneLink.State) -> Unit>
        synchronized(this) { callbacks = stateCallbacks.toList() }
        callbacks.forEach { it(state) }
        downstream?.onState(state)
    }
}

/**
 * UI-facing link facts: connected vs lost plus the last confirmed
 * activity instant (monotonic ns, same domain as [LinkStateMonitor]'s
 * clock). Compare instants only within one monitor.
 */
data class LinkSnapshot(
    val state: DroneLink.State,
    /** True unless CONNECTED. A lost link shows this plus the last
     *  activity time — never a guessed "normal" state. */
    val lost: Boolean,
    val lastStateChangeMonotonicNs: Long,
    val lastActivityMonotonicNs: Long?,
)
