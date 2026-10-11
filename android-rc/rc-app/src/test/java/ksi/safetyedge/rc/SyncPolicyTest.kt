package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.SyncOutcome

/** A8 retry policy: terminal outcomes stop auto-sync; recoverable/transient retry. */
class SyncPolicyTest {
    @Test
    fun terminal_outcomes_stop_and_transient_outcomes_retry() {
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(SyncOutcome.COMPLETE),
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

    @Test
    fun partial_is_split_by_cause_and_bounded() {
        // Recoverable: local bytes may still arrive -> bounded retries.
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor(
                SyncOutcome.PARTIAL, attempt = 0,
                partialCause = SyncOutcome.RECOVERABLE_PENDING,
            ),
        )
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor(
                SyncOutcome.PARTIAL,
                attempt = SyncPolicy.MAX_PARTIAL_RETRIES - 1,
                partialCause = SyncOutcome.RECOVERABLE_PENDING,
            ),
        )
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(
                SyncOutcome.PARTIAL,
                attempt = SyncPolicy.MAX_PARTIAL_RETRIES,
                partialCause = SyncOutcome.RECOVERABLE_PENDING,
            ),
        )
        // Server-side missing despite complete local data: retry cannot
        // heal it -> stop and stay visible for a human.
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(
                SyncOutcome.PARTIAL, attempt = 0,
                partialCause = SyncOutcome.SERVER_MISSING,
            ),
        )
        // Unclassified PARTIAL stays conservative until the bound.
        assertEquals(
            SyncPolicy.Decision.RETRY,
            SyncPolicy.decisionFor(SyncOutcome.PARTIAL, attempt = 0),
        )
        assertEquals(
            SyncPolicy.Decision.DONE,
            SyncPolicy.decisionFor(
                SyncOutcome.PARTIAL,
                attempt = SyncPolicy.MAX_PARTIAL_RETRIES,
            ),
        )
    }

    @Test
    fun skip_requires_synced_artifacts_and_no_pending_opinions() {
        // Fully delivered: skip is correct.
        assertTrue(SyncPolicy.shouldSkipSync(3, true, 0))
        // A late operator opinion keeps the sync alive even when every
        // artifact is synced (user review 2026-10-11 regression).
        assertFalse(SyncPolicy.shouldSkipSync(3, true, 1))
        // Unsynced artifacts: never skip.
        assertFalse(SyncPolicy.shouldSkipSync(3, false, 0))
        // Artifact-less flights were never skipped by this path.
        assertFalse(SyncPolicy.shouldSkipSync(0, false, 0))
        assertFalse(SyncPolicy.shouldSkipSync(0, true, 0))
    }
}
