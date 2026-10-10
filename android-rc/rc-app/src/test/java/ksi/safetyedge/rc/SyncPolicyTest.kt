package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test
import ksi.rc.SyncOutcome

/** A8 retry policy: terminal outcomes stop auto-sync; unreachable/failed retry. */
class SyncPolicyTest {
    @Test
    fun terminal_outcomes_stop_and_transient_outcomes_retry() {
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(SyncOutcome.COMPLETE),
        )
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(SyncOutcome.PARTIAL),
        )
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(SyncOutcome.NO_DATA),
        )
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor(SyncOutcome.UNREACHABLE),
        )
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor(SyncOutcome.FAILED),
        )
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor("SOMETHING_NEW"),
        )
    }
}
