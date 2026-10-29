package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.MissionController
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests for the live-status unit: the 1s retransmission timer path
 * calls `MissionController.pollTick` (off the UI thread, bounded, only
 * while a session is in flight), and a real drone-link drop reaches the
 * controller as session-interruption evidence via the installed
 * `LinkLossForwarder` — a regained link is not evidence of anything.
 */
class RcSessionLiveStatusTest {

    private var counter = 0

    private inner class Fixture {
        val link = FakeDroneLink()
        var nowNs = 0L
        val session = RcSession(
            storeDir = Files.createTempDirectory("rc-live-status").toFile(),
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = link,
            coreConfig = { null },
            clockNs = { nowNs },
        )
        val flightId = "00000000-0000-4000-8000-0000000000f1"
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )

        init {
            session.attachController(bundle, flightId)
        }

        fun edgeBytes(type: String, bootId: String?, payload: com.fasterxml.jackson.databind.node.ObjectNode): ByteArray {
            counter += 1
            val env = Mop.makeEnvelope(
                msgId = Mop.newMsgId("edge-test", flightId, type, counter.toString()),
                channel = "CONTROL_RELIABLE",
                type = type,
                flightId = flightId,
                edgeBootId = bootId,
                seq = null,
                ackOf = null,
                payload = payload,
                sentMonotonicNs = 1_000_000L,
            )
            return Mop.json.writeValueAsBytes(env)
        }

        fun feed(type: String, bootId: String?, payload: com.fasterxml.jackson.databind.node.ObjectNode) {
            session.onLinkBytes(edgeBytes(type, bootId, payload))
        }

        fun driveToPending(bootId: String = "00000000-0000-4000-8000-0000000000e1") {
            val controller = session.controller!!
            controller.prepare()
            feed(
                "MISSION_PREPARED", bootId,
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            )
            assertEquals(MissionState.PREPARED, controller.state)
            controller.apply()
            feed(
                "MISSION_APPLIED", bootId,
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            )
            assertEquals(MissionState.APPLIED, controller.state)
            controller.startSession()
            val startMsgId = controller.endpoint.retransmissions()
                .first { it.get("type").asText() == "SESSION_START" }
                .get("msg_id").asText()
            feed(
                "ACK", null,
                Mop.json.createObjectNode().put("ack_of", startMsgId),
            )
            assertEquals(MissionState.SESSION_PENDING, controller.state)
        }

        fun boundStatus(extra: com.fasterxml.jackson.databind.node.ObjectNode = Mop.json.createObjectNode()): com.fasterxml.jackson.databind.node.ObjectNode {
            extra.put("applied_bundle_sha256", bundle.get("bundle_sha256").asText())
            // The install binding the controller adopts: the hash the Edge
            // would compute for OUR bundle (never the bundle digest itself).
            ksi.rc.EffectiveConfigHash.expectedFor(bundle)?.let {
                extra.put("effective_config_hash", it)
            }
            return extra
        }

        fun confirmStop() {
            val controller = session.controller!!
            controller.stopSession()
            val stopMsgId = controller.endpoint.retransmissions()
                .first { it.get("type").asText() == "SESSION_STOP" }
                .get("msg_id").asText()
            feed(
                "ACK", null,
                Mop.json.createObjectNode().put("ack_of", stopMsgId),
            )
            // Bound to our install (digest + install hash): a foreign or
            // digest-less report stays display-only and never stops us.
            feed(
                "MISSION_STATUS", null,
                boundStatus(Mop.json.createObjectNode().put("running", false)),
            )
            assertEquals(MissionState.STOPPED, controller.state)
        }

        fun queryIds(): List<String> =
            session.controller!!.endpoint.retransmissions()
                .filter { it.get("type").asText() == "MISSION_STATUS_QUERY" }
                .map { it.get("msg_id").asText() }
    }

    @Test
    fun timer_tick_issues_no_query_while_no_session_is_in_flight() {
        val f = Fixture()
        f.link.connect()
        f.nowNs = 10_000_000_000L
        f.session.retransmitTick()
        assertEquals(0, f.queryIds().size)
        assertEquals(MissionState.IDLE, f.session.controller!!.state)
    }

    @Test
    fun timer_tick_polls_while_pending_and_never_advances_the_state_machine() {
        val f = Fixture()
        f.driveToPending()
        f.link.connect()
        val interval = MissionController.DEFAULT_STATUS_INTERVAL_NS

        // First tick sends the one pending query.
        f.nowNs = interval
        f.session.retransmitTick()
        assertEquals(1, f.queryIds().size)
        val first = f.queryIds().single()

        // Coalesced: while the query is still unacknowledged, further
        // ticks (even past the interval) send nothing new — the retry
        // loop owns the pending envelope under the SAME msg_id.
        f.session.retransmitTick()
        assertEquals(listOf(first), f.queryIds())
        f.nowNs = 5 * interval
        f.session.retransmitTick()
        assertEquals(listOf(first), f.queryIds())

        // The Edge ACKs the query: the next tick sends a FRESH query with
        // a new id (the old one is honored, never re-sent as new).
        f.feed(
            "ACK", null,
            Mop.json.createObjectNode().put("ack_of", first),
        )
        f.nowNs = 6 * interval
        f.session.retransmitTick()
        assertEquals(1, f.queryIds().size)
        assertTrue(f.queryIds().single() != first)
        assertEquals(MissionState.SESSION_PENDING, f.session.controller!!.state)
    }

    @Test
    fun timer_tick_stops_polling_after_the_session_closed() {
        val f = Fixture()
        f.driveToPending()
        f.link.connect()
        f.nowNs = MissionController.DEFAULT_STATUS_INTERVAL_NS
        f.session.retransmitTick()
        val before = f.queryIds()
        assertEquals(1, before.size)

        f.confirmStop()

        // Closed: no NEW query is ever sent (the still-unacked earlier
        // query simply stays pending — silence, not a fresh poll).
        f.nowNs = 10 * MissionController.DEFAULT_STATUS_INTERVAL_NS
        f.session.retransmitTick()
        assertEquals(before, f.queryIds())
    }

    @Test
    fun foreign_status_report_is_display_only_and_never_stops() {
        val f = Fixture()
        f.driveToPending()
        f.link.connect()
        f.session.controller!!.stopSession()

        // No binding at all: display-only, never a stop.
        f.feed(
            "MISSION_STATUS", null,
            Mop.json.createObjectNode().put("running", false),
        )
        assertEquals(MissionState.SESSION_PENDING, f.session.controller!!.state)

        // Foreign install digest: display-only, never a stop.
        f.feed(
            "MISSION_STATUS", null,
            Mop.json.createObjectNode()
                .put("running", false)
                .put("applied_bundle_sha256", "ffffffff-ffff-4fff-bfff-ffffffffffff")
                .put("effective_config_hash", "0".repeat(64)),
        )
        assertEquals(MissionState.SESSION_PENDING, f.session.controller!!.state)
    }

    @Test
    fun real_link_drop_is_session_evidence_regain_is_not() {
        val f = Fixture()
        f.link.connect()
        assertFalse(f.session.controller!!.linkInterruptedDuringSession)

        f.driveToPending()
        // A real CONNECTED -> lost drop while pending counts.
        f.link.disconnect()
        assertTrue(f.session.controller!!.linkInterruptedDuringSession)
        assertTrue(f.session.controller!!.executionFacts().linkInterrupted)

        // Re-gaining the link is not evidence of anything.
        f.link.connect()
        assertTrue(f.session.controller!!.linkInterruptedDuringSession)
    }

    @Test
    fun drop_after_close_is_not_counted() {
        val f = Fixture()
        f.link.connect()
        f.driveToPending()
        f.link.disconnect()
        assertTrue(f.session.controller!!.linkInterruptedDuringSession)

        f.link.connect()
        f.confirmStop()
        assertFalse(f.session.controller!!.linkInterruptedDuringSession)

        f.link.disconnect()
        assertFalse(f.session.controller!!.linkInterruptedDuringSession)
        assertFalse(f.session.controller!!.executionFacts().linkInterrupted)
    }

    @Test
    fun no_session_in_flight_means_no_evidence() {
        val f = Fixture()
        f.link.connect()
        f.link.disconnect()
        assertFalse(f.session.controller!!.linkInterruptedDuringSession)
    }

    @Test
    fun recording_start_anchor_captures_the_known_boot() {
        val f = Fixture()
        assertEquals(null, f.session.recordingStartBootFor(f.flightId))
        f.session.noteRecordingStart(f.flightId)
        // No boot known yet: anchor records null, never invents one.
        assertEquals(null, f.session.recordingStartBootFor(f.flightId))
        f.driveToPending()
        f.session.noteRecordingStart(f.flightId)
        assertEquals(
            f.session.controller!!.activeBootId,
            f.session.recordingStartBootFor(f.flightId),
        )
    }
}
