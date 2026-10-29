package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the site-first worker contract (Critic High1): the
 * pre-upload gate maps to a DISTINCT wait state — never the SKIPPED
 * bucket — and wait-state flights requeue instead of settling.
 *
 * `SyncWorker.doWork` itself needs the WorkManager runtime (Robolectric
 * skips on this host), so these pin the pure pieces it is built from:
 * [preUploadGateState], [shouldRequeueFlight], and the WAIT ≠ SKIPPED
 * state vocabulary. The wiring (`doWork` checks the gate first,
 * `enqueuePending` ORs the wait into requeue) is reviewed, not executed.
 */
class SyncWorkerGateTest {

    @Test
    fun wait_state_is_distinct_from_skipped() {
        assertEquals("SITE_SYNC_WAIT", SyncWorker.WAIT_STATE)
        assertNotEquals(
            "a site-first wait must never read as a settled skip",
            "SKIPPED",
            SyncWorker.WAIT_STATE,
        )
    }

    @Test
    fun gate_reason_maps_to_wait_and_null_proceeds() {
        assertEquals(
            SyncWorker.WAIT_STATE,
            preUploadGateState(PendingSiteRegistration.UPLOAD_WAIT_REASON),
        )
        assertNull("no gate reason proceeds to the spec", preUploadGateState(null))
    }

    @Test
    fun site_wait_requeues_even_when_nothing_else_does() {
        assertTrue(
            "wait-state retries after the site resolves",
            shouldRequeueFlight(
                hasCompleteUnsynced = false,
                hasPendingOpinion = false,
                hasPendingResponses = false,
                siteSyncWait = true,
            ),
        )
        assertFalse(
            "nothing to do and no wait settles",
            shouldRequeueFlight(false, false, false, false),
        )
    }

    @Test
    fun existing_requeue_reasons_are_unchanged() {
        assertTrue(shouldRequeueFlight(true, false, false, false))
        assertTrue(shouldRequeueFlight(false, true, false, false))
        assertTrue(shouldRequeueFlight(false, false, true, false))
        assertTrue(shouldRequeueFlight(true, true, true, true))
    }
}
