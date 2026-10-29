package ksi.safetyedge.rc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests that replay a REAL [RcSession] from the SAME store directory
 * (restart recovery, contract §6 closeout): persisted UNRESOLVED close-outs
 * and open start intents keep a recreated session locked; only an observed
 * STOPPED completes them. Artifact failure never ends or enqueues; retry
 * after repair ends cleanly with all required artifacts present.
 */
class RcSessionRestartTest {

    private var counter = 0

    private inner class Fixture(val storeDir: File = Files.createTempDirectory("rc-restart").toFile()) {
        var link = FakeDroneLink()
        var session = newSession()
        var now = "2026-10-10T07:00:00Z"
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val bootId = "00000000-0000-4000-8000-0000000000e1"
        var flightId: String? = null

        init {
            // Production invariant: a bundle is always digest-verified AND
            // saved before it is pinned — restart re-attach depends on it.
            session.store.saveMissionBundle(bundle)
        }

        fun newSession(): RcSession = RcSession(
            storeDir = storeDir,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { now },
            link = link,
            coreConfig = { null },
        )

        fun restart(): RcSession {
            link = FakeDroneLink()
            session = newSession()
            return session
        }

        fun edgeBytes(flightId: String, type: String, payload: com.fasterxml.jackson.databind.node.ObjectNode): ByteArray {
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

        fun boundStatus(extra: com.fasterxml.jackson.databind.node.ObjectNode = Mop.json.createObjectNode()): com.fasterxml.jackson.databind.node.ObjectNode {
            extra.put("applied_bundle_sha256", bundle.get("bundle_sha256").asText())
            ksi.rc.EffectiveConfigHash.expectedFor(bundle)?.let {
                extra.put("effective_config_hash", it)
            }
            return extra
        }
    }

    @Test
    fun unresolved_closeout_locks_recreated_session_until_stopped() {        val f = Fixture()
        val record = f.session.startFlightFromBundle(f.bundle)
        f.flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", f.bundle.get("bundle_sha256").asText()),
            ),
        )
        controller.apply()
        f.session.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            ),
        )
        controller.startSession()

        // Recording-only close: unresolved, flight stays active.
        val unresolved = f.session.persistUnresolvedCloseout(
            f.flightId!!, "STOPPED 미확인 — 미확인 종료",
            RcSession.SegmentIngest(0, 0, emptyList()), null,
        )
        assertEquals(CloseoutRecord.UNRESOLVED, unresolved.outcome)

        // Simulate a process restart: the controller re-attaches from the
        // stored bundle but stays IDLE — unknown restored as unknown,
        // never auto-resumed, never RUNNING.
        val restarted = f.restart()
        assertEquals(MissionState.IDLE, restarted.controller!!.state)
        assertEquals(
            CloseoutRecord.UNRESOLVED,
            restarted.latestCloseout(f.flightId!!)!!.outcome,
        )
        // Locked: no clean end without an observed STOPPED.
        assertNull(restarted.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null))
        assertTrue(restarted.store.activeFlight() != null)

        // A fresh observation session on the same flight is still blocked.
        try {
            restarted.startFlightFromBundle(f.bundle)
            throw AssertionError("new flight must stay blocked")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("already active"))
        }
    }

    @Test
    fun prepared_but_unrecorded_restart_leaves_no_phantom_and_reprepares() {
        // A9-4 device finding: force-stop after PREPARED arrived resumes
        // MainActivity with the store intact but the preparation gone
        // (in-memory only) — no phantom flight, and re-prepare is allowed.
        val f = Fixture()
        val provisional = f.session.prepareBundle(f.bundle)
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", f.bundle.get("bundle_sha256").asText()),
            ),
        )
        assertEquals(MissionState.PREPARED, f.session.controller!!.state)

        val restarted = f.restart()
        assertNull("no controller without a stored ACTIVE flight", restarted.controller)
        assertNull(restarted.activeFlight)
        assertNull(restarted.pendingFlightId)
        assertTrue(restarted.store.flights().isEmpty())

        // Re-prepare is allowed with a fresh provisional id (the abandoned
        // one never resurrects).
        val provisional2 = restarted.prepareBundle(f.bundle)
        assertTrue(provisional2.isNotBlank())
        assertTrue(
            "abandoned preparation must not resurrect: $provisional vs $provisional2",
            provisional2 != provisional,
        )
        restarted.controller!!.prepare()
        assertEquals(MissionState.PREPARING, restarted.controller!!.state)
    }

    @Test
    fun open_start_intent_blocks_restarted_close_until_stopped() {
        val f = Fixture()
        val record = f.session.startFlightFromBundle(f.bundle)
        f.flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", f.bundle.get("bundle_sha256").asText()),
            ),
        )
        controller.apply()
        f.session.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            ),
        )
        // Intent persisted BEFORE the session starts; crash before any close.
        f.session.requestSessionStart()
        assertTrue(f.session.hasOpenStartIntent(f.flightId!!))

        // Restart: controller IDLE, intent stands, no close-out yet.
        val restarted = f.restart()
        assertTrue(restarted.hasOpenStartIntent(f.flightId!!))
        assertNull(
            "unknown after restart is not a clean close",
            restarted.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null),
        )
        assertTrue(restarted.store.activeFlight() != null)

        // Recovery STOP from the IDLE controller is permitted (explicit
        // operator recovery, not an automatic flight): the stop sends, the
        // Edge ACKs, and a bound STOPPED completes the close.
        val stop = restarted.requestEdgeStop()
        assertTrue(stop.sessionOpen)
        val restartedController = restarted.controller!!
        val stopMsgId = restartedController.endpoint.retransmissions()
            .first { it.get("type").asText() == "SESSION_STOP" }
            .get("msg_id").asText()
        restarted.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "ACK",
                Mop.json.createObjectNode().put("ack_of", stopMsgId),
            ),
        )
        restarted.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_STATUS",
                f.boundStatus(Mop.json.createObjectNode().put("running", false)),
            ),
        )
        assertEquals(MissionState.STOPPED, restartedController.state)
        // A legacy bool is only a protocol indication, not proof the
        // Manifold processing loop actually stopped.
        assertNull(restarted.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null))
        assertNotNull(restarted.activeFlight)
        restarted.onLinkBytes(
            f.edgeBytes(
                f.flightId!!, "MISSION_STATUS",
                f.boundStatus(Mop.json.createObjectNode()
                    .put("running", false)
                    .put("runtime_state", "STOPPED")
                    .put("config_applied", true)),
            ),
        )
        val done = restarted.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null)
        assertTrue(done != null)
        assertEquals(CloseoutRecord.CLEAN, done!!.closeout.outcome)
        assertNull(restarted.store.activeFlight())
        assertFalse(restarted.hasOpenStartIntent(f.flightId!!))
    }

    @Test
    fun never_started_flight_closes_as_explicit_draft() {
        val f = Fixture()
        val record = f.session.startFlightFromBundle(f.bundle)
        val flightId = record.get("flight_id").asText()
        // No session activity, no intent, no Edge boots: explicitly draft.
        val done = f.session.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null)
        assertTrue(done != null)
        assertEquals(CloseoutRecord.DRAFT, done!!.closeout.outcome)
        assertNull(f.session.store.activeFlight())
    }

    @Test
    fun artifact_failure_blocks_end_and_enqueue_until_repaired() {
        val f = Fixture()
        val record = f.session.startFlightFromBundle(f.bundle)
        val flightId = record.get("flight_id").asText()
        f.session.controller!!.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", f.bundle.get("bundle_sha256").asText()),
            ),
        )
        val bootId = f.bootId

        // Fail the artifact write once: the close must not end or enqueue.
        var failuresLeft = 1
        val realAnnounce = f.session.announceCloseout
        f.session.announceCloseout = { r, b, fl, bo ->
            if (failuresLeft-- > 0) throw java.io.IOException("disk full (test fault)")
            realAnnounce(r, b, fl, bo)
        }
        try {
            f.session.persistUnresolvedCloseout(
                flightId, "STOPPED 미확인 — 미확인 종료",
                RcSession.SegmentIngest(0, 0, emptyList()), null,
            )
            throw AssertionError("expected the artifact failure to propagate")
        } catch (exc: java.io.IOException) {
            assertTrue(exc.message!!.contains("disk full"))
        }
        // Close-out record persisted (durable intent), but no artifact and
        // the flight is untouched: still active, nothing complete.
        val pending = f.session.latestCloseout(flightId)!!
        assertEquals(CloseoutRecord.UNRESOLVED, pending.outcome)
        assertTrue(f.session.store.activeFlight() != null)
        assertTrue(f.session.store.artifactsFor(flightId, bootId).isEmpty())

        // Repair the pipeline: retry persists the same basis and the
        // required artifact materializes completely.
        f.session.announceCloseout = realAnnounce
        val record2 = f.session.persistUnresolvedCloseout(
            flightId, "STOPPED 미확인 — 미확인 종료",
            RcSession.SegmentIngest(0, 0, emptyList()), null,
        )
        val artifacts = f.session.store.artifactsFor(flightId, bootId)
        assertEquals(1, artifacts.size)
        assertEquals("FLIGHT_LOG", artifacts.single().get("source_kind").asText())
        assertTrue(f.session.store.isArtifactComplete(artifacts.single().get("artifact_id").asText()))
        val expected = CloseoutArtifacts.snapshotBytes(record2, f.bundle)
        assertTrue(expected.contentEquals(f.session.store.artifactBytes(artifacts.single().get("artifact_id").asText())!!))
    }

    @Test
    fun repair_materializes_already_ended_closeouts_on_retry() {
        val f = Fixture()
        val record = f.session.startFlightFromBundle(f.bundle)
        val flightId = record.get("flight_id").asText()
        f.session.controller!!.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", f.bundle.get("bundle_sha256").asText()),
            ),
        )
        // An old-bug record: persisted without its artifact (simulated by
        // recording the close-out while announcement fails), then ended.
        var failuresLeft = 1
        val realAnnounce = f.session.announceCloseout
        f.session.announceCloseout = { r, b, fl, bo ->
            if (failuresLeft-- > 0) throw java.io.IOException("disk full (test fault)")
            realAnnounce(r, b, fl, bo)
        }
        try {
            f.session.persistUnresolvedCloseout(flightId, "x", RcSession.SegmentIngest(0, 0, emptyList()), null)
            throw AssertionError("expected failure")
        } catch (_: java.io.IOException) {
        }
        f.session.announceCloseout = realAnnounce
        f.session.endFlight()

        // Upload-time repair path materializes it (verified boot resolves
        // from the store) — the already-ended flight is not reopened.
        assertTrue(f.session.repairCloseoutArtifact(flightId))
        assertTrue(
            f.session.store.artifactsFor(flightId, f.bootId)
                .any { f.session.store.isArtifactComplete(it.get("artifact_id").asText()) },
        )
        assertNull(f.session.store.activeFlight())
        // Unknown boot with no basis: repair refuses, never invents.
        assertFalse(f.session.repairCloseoutArtifact("00000000-0000-4000-8000-00000000ffff"))
    }
}
