package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ksi.rc.EdgeRuntimeState

/**
 * Host tests for the receipt-time latch (edge-operation contract §5):
 * the latched time advances only on a NEW RUNNING/DEGRADED report and
 * freezes while the link is lost — render time never ticks it.
 */
class RuntimeConfirmLatchTest {

    @Test
    fun latches_once_per_new_report_not_per_render() {
        val latch = RuntimeConfirmLatch()
        var clock = 0
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 100L, true) { "t${++clock}" })
        // Re-render with the unchanged report: same timestamp, no new clock read.
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 100L, true) { "t${++clock}" })
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 100L, true) { "t${++clock}" })
        assertEquals(1, clock)
        // A genuinely new report advances the latch.
        assertEquals("t2", latch.observe(EdgeRuntimeState.RUNNING, 200L, true) { "t${++clock}" })
        assertEquals(2, clock)
    }

    @Test
    fun link_loss_freezes_the_latched_time() {
        val latch = RuntimeConfirmLatch()
        var clock = 0
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 100L, true) { "t${++clock}" })
        // Link down with the same report: frozen, clock untouched.
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 100L, false) { "t${++clock}" })
        // Even a NEW report while the link is down must not advance: the RC
        // cannot confirm receipt over a dead link.
        assertEquals("t1", latch.observe(EdgeRuntimeState.RUNNING, 200L, false) { "t${++clock}" })
        assertEquals(1, clock)
    }

    @Test
    fun non_confirming_or_identity_less_reports_latch_nothing() {
        val latch = RuntimeConfirmLatch()
        var clock = 0
        assertNull(latch.observe(EdgeRuntimeState.STARTING, 100L, true) { "t${++clock}" })
        assertNull(latch.observe(EdgeRuntimeState.RUNNING, null, true) { "t${++clock}" })
        assertNull(latch.observe(null, 100L, true) { "t${++clock}" })
        assertEquals(0, clock)
        assertNull(latch.lastConfirmedUtc)
        assertEquals("t1", latch.observe(EdgeRuntimeState.DEGRADED, 300L, true) { "t${++clock}" })
    }
}
