package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live runtime status (edge-operation contract §5): the RC polls
 * MISSION_STATUS while a session is in flight so the displayed state is
 * live, not only the moment of a response. The poll is bounded, idle
 * states emit nothing, and a query never advances the mission state
 * machine (queries are idempotent).
 */
class MissionStatusPollTest {
    private inner class Fixture {
        val store = InMemoryRcStore()
        var now = 0L
        val bundle: ObjectNode = makePollBundle()
        val controller = MissionController("flt-1", bundle, store, { now })
        val edge = FakeEdge("flt-1", { now })

        fun pump(rounds: Int = 4) {
            now += 1
            ksi.rc.pump(controller.endpoint, edge, rounds)
        }

        fun prepareAndApply() {
            controller.prepare()
            pump()
            assertEquals(MissionState.PREPARED, controller.state)
            controller.apply()
            pump()
            assertEquals(MissionState.APPLIED, controller.state)
        }

        fun queriesInOutbox(): List<ObjectNode> =
            controller.endpoint.outbox.filter { it.get("type").asText() == "MISSION_STATUS_QUERY" }
    }

    private fun makePollBundle(): ObjectNode {
        return MissionFixture.emptyBundle()
    }

    @Test
    fun no_queries_when_idle() {
        val f = Fixture()
        // IDLE: nothing in flight.
        assertFalse(f.controller.pollTick(f.now))
        assertTrue(f.controller.endpoint.outbox.isEmpty())

        f.controller.prepare()
        f.pump()
        assertFalse(f.controller.pollTick(f.now))

        f.controller.apply()
        f.pump()
        // APPLIED but no session started: still idle from the poll's view.
        assertFalse(f.controller.pollTick(f.now))
        assertTrue(f.queriesInOutbox().isEmpty())
        assertEquals(MissionState.APPLIED, f.controller.state)
    }

    @Test
    fun queries_emitted_periodically_while_session_in_flight() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 3
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)

        val interval = MissionController.DEFAULT_STATUS_INTERVAL_NS
        assertEquals(2_000_000_000L, interval)

        // First tick sends immediately (no previous poll this session).
        assertTrue(f.controller.pollTick(f.now))
        assertEquals(1, f.queriesInOutbox().size)
        val firstId = f.queriesInOutbox().single().get("msg_id").asText()

        // Gated by the interval: silence until it elapses. The first query
        // is still unacked: coalescing holds it to one pending envelope
        // (same id — retries reuse it, no new query is minted).
        assertFalse(f.controller.pollTick(f.now))
        assertFalse(f.controller.pollTick(f.now + interval - 1))
        assertEquals(1, f.queriesInOutbox().size)
        assertEquals(firstId, f.queriesInOutbox().single().get("msg_id").asText())

        // The pending query is ACKed in flight; the next interval tick sends
        // a fresh one (new id once acknowledged).
        f.pump()
        assertEquals(0, f.queriesInOutbox().size)
        assertTrue(f.controller.pollTick(f.now + interval))
        assertEquals(1, f.queriesInOutbox().size)

        // A custom interval is honored too.
        assertFalse(f.controller.pollTick(f.now + interval, intervalNs = 10L * interval))
        f.pump()
        assertTrue(f.controller.pollTick(f.now + 11L * interval, intervalNs = 10L * interval))

        // Confirmed RUNNING keeps polling.
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.pollTick(f.now + 20L * interval))

        // After a confirmed STOP the flight is over: silence again.
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.pollTick(f.now + 30L * interval))
        assertFalse(f.controller.pollTick(f.now + 40L * interval))
    }

    @Test
    fun poll_never_advances_the_state_machine() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 1
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        val before = f.controller.state
        val transitionsBefore = f.controller.transitions.size

        f.controller.pollTick(f.now)
        f.controller.pollTick(f.now + MissionController.DEFAULT_STATUS_INTERVAL_NS)
        assertEquals(before, f.controller.state)
        assertEquals(transitionsBefore, f.controller.transitions.size)

        // Draining the queries changes nothing either: a status round-trip
        // that reports the same execution is not a state transition.
        f.pump()
        assertEquals(before, f.controller.state)
        assertEquals(transitionsBefore, f.controller.transitions.size)
    }

    @Test
    fun rejected_mission_emits_no_queries() {
        val f = Fixture()
        f.edge.corruptPrepare = true
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.REJECTED, f.controller.state)
        assertFalse(f.controller.pollTick(f.now))
        assertTrue(f.queriesInOutbox().isEmpty())
    }

    @Test
    fun long_offline_stretch_queues_exactly_one_query() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 3
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)

        // The link is down: nothing is drained or ACKed. Thirty interval
        // ticks must still leave exactly one pending query envelope —
        // no unbounded outbox, no retransmission backlog.
        val interval = MissionController.DEFAULT_STATUS_INTERVAL_NS
        assertTrue(f.controller.pollTick(0L))
        for (k in 1..30) {
            assertFalse(
                f.controller.pollTick(k * interval),
                "tick $k must coalesce onto the pending query",
            )
        }
        assertEquals(1, f.queriesInOutbox().size)
        assertEquals(
            1,
            f.controller.endpoint.retransmissions()
                .count { it.get("type").asText() == "MISSION_STATUS_QUERY" },
        )

        // The link returns: the single pending query goes out (and is
        // ACKed), and the next interval tick sends the latest — exactly
        // one fresh envelope, no stale backlog.
        f.pump()
        assertEquals(0, f.queriesInOutbox().size)
        assertTrue(f.controller.pollTick(31L * interval))
        assertEquals(1, f.queriesInOutbox().size)
    }

    @Test
    fun poll_never_blocks_start_or_stop() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        f.pump() // START ACK → SESSION_PENDING
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        // A query is pending (unacked); session commands still send and
        // converge independently of it.
        assertTrue(f.controller.pollTick(f.now))
        assertEquals(1, f.queriesInOutbox().size)
        val commandsBefore = f.controller.endpoint.outbox.size
        f.controller.stopSession()
        assertEquals(
            commandsBefore + 1, f.controller.endpoint.outbox.size,
            "STOP must send even with a query pending",
        )
        assertEquals(
            "SESSION_STOP",
            f.controller.endpoint.outbox.last().get("type").asText(),
        )
    }

    @Test
    fun stale_query_does_not_resurrect_a_closed_session() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 3
        f.controller.startSession()
        f.pump()
        assertTrue(f.controller.pollTick(f.now))

        // The session closes while the query is still unacked.
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)

        // The closed session emits no new queries, and the stale reply —
        // reflecting the stopped Edge — changes nothing: no resurrection
        // to RUNNING, no cleared close.
        assertFalse(f.controller.pollTick(f.now + 100L))
        assertEquals(true, f.controller.executionFacts().stopConfirmed)
        assertEquals(MissionState.STOPPED, f.controller.state)

        // A fresh cycle starts with a clean poll slate: the first tick
        // sends immediately again once the new session is pending.
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump()
        f.controller.startSession()
        f.pump() // START ACK → SESSION_PENDING
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertTrue(f.controller.pollTick(f.now + 200L))
    }
}
