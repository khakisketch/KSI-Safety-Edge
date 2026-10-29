package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Link-loss evidence (contract §5/§6): a real drop becomes
 * session-interruption evidence while the session is open or pending, and
 * is NOT counted after the session closed — for both [noteLinkLost] and
 * the [noteLinkSnapshot] entry point the app's link monitor calls.
 */
class SessionLinkLossTest {
    private inner class Fixture {
        val store = InMemoryRcStore()
        var now = 0L
        val bundle: ObjectNode = makeLossBundle()
        val controller = MissionController("flt-1", bundle, store, { now })
        val edge = FakeEdge("flt-1", { now })

        fun pump(rounds: Int = 4) {
            now += 1
            ksi.rc.pump(controller.endpoint, edge, rounds)
        }

        fun prepareApplyAndStart() {
            controller.prepare()
            pump()
            controller.apply()
            pump()
            controller.startSession()
            pump()
            assertEquals(MissionState.SESSION_PENDING, controller.state)
        }
    }

    private fun makeLossBundle(): ObjectNode {
        return MissionFixture.emptyBundle()
    }

    @Test
    fun snapshot_loss_counts_while_pending_snapshot_regain_counts_nothing() {
        val f = Fixture()
        // No session in flight: neither a drop nor a regain is evidence.
        f.controller.noteLinkSnapshot(true)
        assertFalse(f.controller.linkInterruptedDuringSession)
        f.controller.noteLinkSnapshot(false)
        assertFalse(f.controller.linkInterruptedDuringSession)

        f.prepareApplyAndStart()
        // A regained link is not session evidence.
        f.controller.noteLinkSnapshot(false)
        assertFalse(f.controller.linkInterruptedDuringSession)
        // A real drop while pending is.
        f.controller.noteLinkSnapshot(true)
        assertTrue(f.controller.linkInterruptedDuringSession)
        assertTrue(f.controller.executionFacts().linkInterrupted)
    }

    @Test
    fun snapshot_loss_counts_while_running() {
        val f = Fixture()
        f.prepareApplyAndStart()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 2
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)

        f.controller.noteLinkSnapshot(true)
        assertTrue(f.controller.linkInterruptedDuringSession)
    }

    @Test
    fun drop_after_close_is_not_counted() {
        val f = Fixture()
        f.prepareApplyAndStart()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 2
        f.controller.reconcile()
        f.pump()
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)

        // A drop after the session closed is a new fact about the link,
        // not an interruption of the closed session.
        f.controller.noteLinkLost()
        assertFalse(f.controller.linkInterruptedDuringSession)
        f.controller.noteLinkSnapshot(true)
        assertFalse(f.controller.linkInterruptedDuringSession)
        assertFalse(f.controller.executionFacts().linkInterrupted)
    }

    @Test
    fun interruption_flag_clears_on_next_session() {
        val f = Fixture()
        f.prepareApplyAndStart()
        f.controller.noteLinkLost()
        assertTrue(f.controller.linkInterruptedDuringSession)

        // Close, then start a fresh cycle: the old interruption does not leak.
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump()
        f.controller.startSession()
        assertFalse(f.controller.linkInterruptedDuringSession)
    }
}
