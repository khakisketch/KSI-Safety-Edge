package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session-command retransmission + legacy/modern compatibility pins
 * (contract §5/§9):
 *
 * - a SESSION_START/STOP lost on the wire is retried under the SAME msg_id
 *   (the Edge dedups by msg_id) and converges without a second session;
 * - a late/duplicate START ACK after a STOP was sent never resurrects a
 *   START-pending state;
 * - the historic-vs-corrected MISSION_STATUS `running` bool (see
 *   contracts/mop vs edge/tests/fixtures) is session-neutral: a report of
 *   either value with no session in flight changes nothing;
 * - a STOP pending through non-STOPPED modern states stays pending — only
 *   an exact `runtime_state == STOPPED` (or the legacy `running == false`
 *   fallback) confirms the stop.
 */
class MissionSessionRetransmitTest {
    private inner class Fixture(val bundle: ObjectNode = MissionFixture.emptyBundle()) {
        val store = InMemoryRcStore()
        var now = 0L
        val controller = MissionController("flt-1", bundle, store, { now })
        val edge = FakeEdge("flt-1", { now })

        fun pump(
            rounds: Int = 4,
            dropToRc: (ObjectNode) -> Boolean = { false },
            dropToEdge: (ObjectNode) -> Boolean = { false },
        ) {
            now += 1
            ksi.rc.pump(controller.endpoint, edge, rounds, dropToRc, dropToEdge)
        }

        fun prepareAndApply() {
            controller.prepare()
            pump()
            assertEquals(MissionState.PREPARED, controller.state)
            controller.apply()
            pump()
            assertEquals(MissionState.APPLIED, controller.state)
        }

        fun startIds(): List<String> =
            controller.endpoint.retransmissions()
                .filter { it.get("type").asText() == "SESSION_START" }
                .map { it.get("msg_id").asText() }

        fun stopIds(): List<String> =
            controller.endpoint.retransmissions()
                .filter { it.get("type").asText() == "SESSION_STOP" }
                .map { it.get("msg_id").asText() }
    }

    /** Real installable bundle so modern STATUS reports bind (see [MissionRuntimeTest]). */
    private fun realBundle(): ObjectNode {
        val zone = MissionFixture.zoneRevision("site-1", "zone-1", "zr-1", null)
        val snapshot = MissionFixture.snapshot(
            "site-1", "mission-1", "rev-1", "flt-1", listOf(zone),
        )
        val policy = MissionFixture.policy("site-1", listOf("zr-1"))
        return MissionFixture.bundle("site-1", snapshot, policy, "bundle-1")
    }

    private fun boundStatus(
        f: Fixture,
        runtimeState: String,
        running: Boolean,
        processed: Long = 12,
    ) {
        val hash = EffectiveConfigHash.expectedFor(f.bundle)
        val payload = Mop.json.createObjectNode().apply {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("edge_boot_id", "boot-1")
            put("running", running)
            put("runtime_state", runtimeState)
            put("runtime_epoch", "epoch-1")
            put("config_applied", true)
            if (hash != null) put("effective_config_hash", hash)
            put("processed_frame_count", processed)
            putArray("degraded_reasons")
            putObject("input_state").put("engine", "ok").put("video", "ok")
        }
        val env = Mop.makeEnvelope(
            Mop.newMsgId("status-review", runtimeState, f.now.toString()),
            "CONTROL_RELIABLE", "MISSION_STATUS", "flt-1",
            "boot-1", 1, null, payload, f.now,
        )
        f.controller.handle(env, payload)
    }

    private fun legacyStatus(f: Fixture, running: Boolean) {
        val payload = Mop.json.createObjectNode().apply {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("application_id", "app-1")
            put("edge_boot_id", "boot-1")
            put("effective_edge_seq", 7L)
            put("running", running)
        }
        val env = Mop.makeEnvelope(
            Mop.newMsgId("legacy-status", running.toString(), f.now.toString()),
            "CONTROL_RELIABLE", "MISSION_STATUS", "flt-1",
            "boot-1", 3, null, payload, f.now,
        )
        f.controller.handle(env, payload)
    }

    @Test
    fun session_start_loss_retries_same_id_and_converges_without_resession() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        val startId = f.startIds().single()

        // Total loss toward the Edge: retries keep the SAME msg_id, the
        // state never pretends the Edge is running, and no second session
        // command is ever emitted.
        f.pump(rounds = 3, dropToEdge = { true })
        assertEquals(1, f.controller.endpoint.pendingCount())
        assertEquals(listOf(startId), f.startIds())
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertFalse(f.controller.sessionActive)

        // A repeated start while unacked is a no-op, not a second session.
        f.controller.startSession()
        assertEquals(listOf(startId), f.startIds())

        // Link restored: the retried command lands, the ACK converges.
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertTrue(f.edge.running)
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertNull(f.controller.executionFacts().runtimeConfirmed, "legacy running bool is not execution proof")
        assertNull(f.controller.executionFacts().frameProcessingObserved, "legacy running bool is not frame evidence")
    }

    @Test
    fun session_stop_loss_retries_same_id_then_confirms() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertEquals(0, f.controller.endpoint.pendingCount())

        f.controller.stopSession()
        val stopId = f.stopIds().single()

        // Total loss toward the RC (STOP + its ACK + statuses all dropped):
        // the stop retries under the same id, the state stays RUNNING
        // (no unconfirmed stop is ever claimed), and a repeated stop is a
        // no-op.
        f.pump(rounds = 3, dropToRc = { true })
        assertEquals(1, f.controller.endpoint.pendingCount())
        assertEquals(listOf(stopId), f.stopIds())
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        f.controller.stopSession()
        assertEquals(listOf(stopId), f.stopIds())

        // Link restored: the retried STOP hits the Edge's dedup (already
        // handled — no fresh ACK is issued for a duplicate here), so the
        // ACK never comes back and recovery converges through reconcile,
        // like any lost reply (contract §2). The legacy `running == false`
        // fallback confirms the stop (no typed receipt, so no clean-close
        // proof — legacy compat, never more).
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertNull(
            f.controller.executionFacts().stopConfirmed,
            "legacy bool ends the protocol state but proves no clean close",
        )
    }

    @Test
    fun late_start_ack_after_stop_does_not_resurrect_start_pending() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        val startId = f.startIds().single()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        val startAcksBefore = f.controller.transitions.count {
            it.reason == "SESSION_START acked; edge running state unconfirmed"
        }
        assertEquals(1, startAcksBefore)

        // STOP sent (still RUNNING until its ACK): a replayed ACK for the
        // long-honored START must not resurrect a START-pending state.
        f.controller.stopSession()
        val dupAck = Mop.makeEnvelope(
            Mop.newMsgId("rc", "flt-1", "dup", "ACK", startId),
            "CONTROL_RELIABLE", "ACK", "flt-1", "boot-1", null, startId,
            Mop.json.createObjectNode().put("ack_of", startId), f.now,
        )
        val outcome = f.controller.endpoint.onRawEnvelope(dupAck.toString())
        assertTrue(outcome is Inbound.Acked)
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertEquals(
            startAcksBefore,
            f.controller.transitions.count {
                it.reason == "SESSION_START acked; edge running state unconfirmed"
            },
            "no second START-pending transition",
        )
    }

    @Test
    fun historic_status_running_true_without_session_changes_nothing() {
        val f = Fixture()
        f.prepareAndApply()
        val applicationsBefore = f.store.applicationsFor("flt-1").size

        // The historic fixture's 5th envelope: `running: true` right after
        // APPLY, before any SESSION_START. Without a pending session this is
        // diagnostics only — it must not activate or fabricate anything.
        legacyStatus(f, running = true)
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertFalse(f.controller.sessionReady)
        assertEquals(applicationsBefore, f.store.applicationsFor("flt-1").size)
        assertTrue(f.controller.lastStatus?.get("running")?.asBoolean() == true)
    }

    @Test
    fun corrected_status_running_false_without_session_changes_nothing() {
        val f = Fixture()
        f.prepareAndApply()
        val applicationsBefore = f.store.applicationsFor("flt-1").size

        // The corrected pin says `running: false` at the same point. Same
        // observable outcome: the bool correction is session-neutral.
        legacyStatus(f, running = false)
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertFalse(f.controller.sessionReady)
        assertEquals(applicationsBefore, f.store.applicationsFor("flt-1").size)
        assertTrue(f.controller.lastStatus?.get("running")?.asBoolean() == false)
    }

    @Test
    fun stop_pending_through_non_stopped_modern_states_stays_pending() {
        val f = Fixture(realBundle())
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 12
        f.edge.inputState = mapOf("engine" to "ok", "video" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)

        f.controller.stopSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertTrue(f.controller.sessionActive)

        // Neither CONFIG_APPLIED nor STARTING is a clean stop: the pending
        // STOP rides through them with the session still marked active.
        boundStatus(f, runtimeState = "CONFIG_APPLIED", running = true)
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        boundStatus(f, runtimeState = "STARTING", running = true)
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertTrue(f.controller.sessionActive)

        // Only the exact STOPPED report confirms.
        boundStatus(f, runtimeState = "STOPPED", running = false)
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertEquals(true, f.controller.executionFacts().stopConfirmed)
    }
}
