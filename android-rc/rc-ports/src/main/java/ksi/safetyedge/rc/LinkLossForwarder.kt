package ksi.safetyedge.rc

/**
 * Forwards real drone-link drops to a session-evidence sink (Unit B of the
 * 2026-10-21 edge-operation contract §5/§6: a link drop while a session is
 * open or pending is session-interruption evidence).
 *
 * Fires [onLinkLost] exactly on CONNECTED -> lost transitions (a CONNECTING
 * startup or a repeated lost state is not a new drop). Whether the drop
 * counts as a session interruption is the sink's decision — e.g.
 * `MissionController.noteLinkLost` counts it only while a session is
 * active or pending, and never after the session closed.
 *
 * The callback runs on the link-reader thread that delivered the state:
 * keep [onLinkLost] fast (a flag set, never I/O) and let the app own any
 * thread handoff.
 */
class LinkLossForwarder(
    monitor: LinkStateMonitor,
    private val onLinkLost: () -> Unit,
) {
    init {
        var wasLost = monitor.snapshot().lost
        monitor.addStateCallback {
            val nowLost = monitor.snapshot().lost
            if (nowLost && !wasLost) onLinkLost()
            wasLost = nowLost
        }
    }
}
