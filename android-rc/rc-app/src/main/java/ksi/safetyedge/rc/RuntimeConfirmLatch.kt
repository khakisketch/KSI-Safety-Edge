package ksi.safetyedge.rc

import ksi.rc.EdgeRuntimeState

/**
 * Receipt-time latch for the last confirmed Edge execution report
 * (edge-operation contract §5).
 *
 * Pure Kotlin, no Android: host-tested. Advances ONLY on a NEW
 * RUNNING/DEGRADED report — identity is the report's
 * [ksi.rc.EdgeRuntimeStatus.observedAtMonotonicNs], not render time — so a
 * re-render with an unchanged report latches nothing and the time never
 * advances. While the link is lost the frozen timestamp is returned
 * untouched: the RC never ticks "current time" as a confirmation and never
 * estimates normality.
 */
class RuntimeConfirmLatch {
    private var lastObservedNs: Long? = null
    var lastConfirmedUtc: String? = null
        private set

    /**
     * Returns the latched RC receipt time of the last confirmed execution
     * report (null when none yet). [nowUtc] runs only when a genuinely new
     * confirming report arrives.
     */
    fun observe(
        state: EdgeRuntimeState?,
        observedAtNs: Long?,
        linkUp: Boolean,
        nowUtc: () -> String,
    ): String? {
        if (!linkUp) return lastConfirmedUtc
        if ((state == EdgeRuntimeState.RUNNING || state == EdgeRuntimeState.DEGRADED) &&
            observedAtNs != null && observedAtNs != lastObservedNs
        ) {
            lastObservedNs = observedAtNs
            lastConfirmedUtc = nowUtc()
        }
        return lastConfirmedUtc
    }
}
