package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A2 mission-controller contracts (ledger §9, user 2026-10-09):
 * envelope-ACK vs apply-ACK separation, STATUS_UNKNOWN reconciliation,
 * late/stale response rejection without fabricated records, dedup across
 * restarts, same-msg_id retry, reconnect resync, diagnostic-only non-matching
 * status, unknown-type ACK parity.
 */
class MissionControllerTest {
    private inner class Fixture {
        val store = InMemoryRcStore()
        var now = 0L
        val bundle: ObjectNode = makeBundle()
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
    }

    private fun makeBundle(): ObjectNode {
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", "bundle-1")
        bundle.put("site_id", "site-1")
        bundle.put("bundle_sha256", CanonicalJson.digestExcluding(bundle, "bundle_sha256"))
        return bundle
    }

    @Test
    fun happy_path_prepare_apply_session() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)

        f.controller.apply()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        val app = assertNotNull(f.controller.lastApplication)
        assertEquals("bundle-1", app.get("bundle_id").asText())
        assertEquals(f.bundle.get("bundle_sha256").asText(),
            app.get("bundle_sha256").asText())
        assertEquals("boot-1", app.get("edge_boot_id").asText())
        assertEquals(true, app.get("applied").asBoolean())
        assertEquals(1, f.store.applicationsFor("flt-1").size)

        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state,
            "a command ACK does not confirm the Edge is running")
        assertFalse(f.controller.sessionActive)
        assertEquals(
            Uuid5.of(Mop.NAMESPACE, "session|flt-1|boot-1").toString(),
            f.controller.sessionId,
        )
        assertTrue(f.edge.running)

        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertEquals(true, f.controller.edgeReportedRunning)

        f.controller.stopSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state,
            "stop is not confirmed by its ACK either")
        assertTrue(f.controller.sessionActive, "edge still reported running")

        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertEquals(false, f.controller.edgeReportedRunning)
        assertFalse(f.edge.running)
    }

    @Test
    fun session_state_never_exaggerates_beyond_edge_reports() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump()
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertFalse(f.controller.sessionActive)

        // The Edge ACKed the start but reports it is NOT running (e.g. its
        // analysis failed to start): the RC must stay pending, not RUNNING.
        f.edge.running = false
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state,
            "start stays unconfirmed while the edge reports not running")
        assertFalse(f.controller.sessionActive)
        assertEquals(false, f.controller.edgeReportedRunning)
    }

    @Test
    fun envelope_ack_does_not_advance_apply_state_then_reconcile() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        // The APPLIED reply is lost; its envelope ACK still arrives.
        f.pump(dropToRc = { it.get("type").asText() == "MISSION_APPLIED" })
        assertEquals(MissionState.APPLYING, f.controller.state,
            "envelope ACK must not mark the mission applied")
        assertEquals(0, f.store.applicationsFor("flt-1").size)

        f.controller.reconcile()
        assertEquals(MissionState.STATUS_UNKNOWN, f.controller.state)
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertEquals(1, f.store.applicationsFor("flt-1").size)
    }

    @Test
    fun late_applied_after_reconcile_is_ignored_not_fabricated() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump(dropToRc = { it.get("type").asText() == "MISSION_APPLIED" })
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertEquals(1, f.store.applicationsFor("flt-1").size)

        // The Edge never got an ACK for its APPLIED; the retry now arrives.
        f.pump()
        assertEquals(1, f.store.applicationsFor("flt-1").size,
            "late APPLIED must not fabricate a second application")
        assertEquals(1, f.controller.ignoredStale.size)
        assertTrue(f.controller.ignoredStale[0].reason.contains("no pending apply"))
        assertEquals(MissionState.APPLIED, f.controller.state)
    }

    @Test
    fun duplicate_applied_redelivery_is_deduped() {
        val f = Fixture()
        f.edge.duplicateNextType = "MISSION_APPLIED"
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertEquals(1, f.store.applicationsFor("flt-1").size)
    }

    @Test
    fun rejected_prepare_is_terminal_until_retry() {
        val f = Fixture()
        f.edge.corruptPrepare = true
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.REJECTED, f.controller.state)
        assertTrue(f.controller.lastRejectionReasons.isNotEmpty())
        assertEquals(0, f.store.applicationsFor("flt-1").size)

        f.edge.corruptPrepare = false
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
    }

    @Test
    fun dedup_survives_endpoint_restart() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
        val preparedRaw = f.edge.sentHistory.first { it.contains("MISSION_PREPARED") }

        // Restart: a new controller/endpoint over the SAME durable store.
        val restarted = MissionController("flt-1", f.bundle, f.store, { f.now })
        val outcome = restarted.endpoint.onRawEnvelope(preparedRaw)
        assertTrue(outcome is Inbound.Duplicate,
            "processed msg_ids must survive an RC restart")
        assertEquals(1, f.store.processedMsgIds().size)
    }

    @Test
    fun retry_reuses_same_msg_id() {
        val f = Fixture()
        f.controller.prepare()
        val firstId = f.controller.endpoint.retransmissions()[0]
            .get("msg_id").asText()
        f.pump(rounds = 2, dropToEdge = { true }) // nothing reaches the Edge
        assertEquals(1, f.controller.endpoint.pendingCount())
        assertEquals(firstId, f.controller.endpoint.retransmissions()[0]
            .get("msg_id").asText(), "retries reuse the same msg_id")
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
    }

    @Test
    fun reconnect_resync_path() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump(dropToRc = { true }) // full link loss toward the RC
        assertEquals(MissionState.APPLYING, f.controller.state)
        f.controller.reconcile()
        assertEquals(MissionState.STATUS_UNKNOWN, f.controller.state)
        f.pump() // link restored
        assertEquals(MissionState.APPLIED, f.controller.state)
    }

    @Test
    fun non_matching_status_is_diagnostic_only() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.reconcile() // no pending apply: must not mark ready
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
        assertEquals(0, f.store.applicationsFor("flt-1").size)
        assertNotNull(f.controller.lastStatus)
    }

    @Test
    fun unknown_type_is_acked_with_error_outcome() {
        val f = Fixture()
        val payload = Mop.json.createObjectNode().put("x", 1)
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("somewhere"),
            channel = "EVENT_RELIABLE",
            type = "WEIRD_TYPE",
            flightId = "flt-1",
            edgeBootId = "boot-1",
            seq = 1,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 1L,
        )
        val outcome = f.controller.endpoint.onRawEnvelope(env.toString())
        assertTrue(outcome is Inbound.Failed)
        assertEquals(1, f.controller.endpoint.ackedCount,
            "unknown types are ACKed (terminal) like the harness")
    }
}
