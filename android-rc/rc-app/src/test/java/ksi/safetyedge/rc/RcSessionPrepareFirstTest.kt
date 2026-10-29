package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests for the adopted order 준비 → 적용 → 기록 시작 ([RcSession
 * .prepareBundle] + [RcSession.startRecordingFromPrepared]):
 *
 * - attaching a controller from a digest-verified bundle creates NO flight
 *   record and claims NO recording (Prepare/Apply operate on the selected
 *   bundle);
 * - 기록 시작 creates the flight record under the SAME provisional UUID
 *   (one id end-to-end, no mid-flow swap) and binds the already
 *   prepared/applied controller;
 * - abandoning before 기록 시작 leaves NO flight; process death before
 *   기록 시작 resurrects NO phantom flight (resume re-attaches ONLY from
 *   a stored ACTIVE flight record);
 * - [RcSession.syncFlightState] with an APPLIED bundle but no record
 *   advances nothing and fabricates no row.
 */
class RcSessionPrepareFirstTest {

    private var counter = 0

    private inner class Fixture(
        val storeDir: java.io.File = Files.createTempDirectory("rc-prepare-first").toFile(),
    ) {
        var link = FakeDroneLink()
        var session = RcSession(
            storeDir = storeDir,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = link,
            coreConfig = { null },
        )
        val bootId = "00000000-0000-4000-8000-0000000000e1"

        fun edgeBytes(
            flightId: String,
            type: String,
            payload: com.fasterxml.jackson.databind.node.ObjectNode,
        ): ByteArray {
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
    }

    private fun bundle(idSuffix: String) = DemoFeed.demoBundle(
        "00000000-0000-4000-8000-0000000000$idSuffix",
        "00000000-0000-4000-8000-0000000000b2",
    )

    private fun Fixture.prepare(bundle: com.fasterxml.jackson.databind.node.ObjectNode): String =
        session.prepareBundle(bundle)

    private fun Fixture.drivePrepared(
        provisional: String,
        bundle: com.fasterxml.jackson.databind.node.ObjectNode,
    ) {
        val controller = session.controller!!
        controller.prepare()
        assertEquals(MissionState.PREPARING, controller.state)
        session.onLinkBytes(
            edgeBytes(
                provisional, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        assertEquals(MissionState.PREPARED, controller.state)
    }

    private fun Fixture.driveApplied(
        provisional: String,
        appId: String = "app-prepare-1",
    ) {
        val controller = session.controller!!
        controller.apply()
        assertEquals(MissionState.APPLYING, controller.state)
        session.onLinkBytes(
            edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", appId)
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, controller.state)
    }

    @Test
    fun prepare_apply_start_reuses_one_flight_id_end_to_end() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)

        // 준비 attaches the controller but creates NO flight record and
        // claims NO recording.
        assertEquals(provisional, f.session.controller!!.flightId)
        assertEquals(provisional, f.session.pendingFlightId)
        assertNull(f.session.activeFlight)
        assertTrue(f.session.store.flights().isEmpty())

        f.drivePrepared(provisional, bundle)
        // Still no flight after PREPARED.
        assertNull(f.session.activeFlight)
        assertTrue(f.session.store.flights().isEmpty())
        f.driveApplied(provisional)
        // Still no flight after APPLIED; the record follows only at 기록 시작.
        assertNull(f.session.activeFlight)
        assertTrue(f.session.store.flights().isEmpty())

        // 기록 시작 reuses the provisional id — one UUID end-to-end — and
        // binds the already-APPLIED controller (never re-attached).
        val controllerBefore = f.session.controller!!
        val record = f.session.startRecordingFromPrepared(bundle)
        assertEquals(provisional, record.get("flight_id").asText())
        assertTrue(f.session.controller === controllerBefore)
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        assertNull(f.session.pendingFlightId)

        f.session.syncFlightState()
        assertEquals("APPLIED", f.session.store.flight(provisional)!!.get("state").asText())
        assertEquals(
            bundle.get("bundle_sha256").asText(),
            f.session.controller!!.lastApplication!!.get("bundle_sha256").asText(),
        )
    }

    @Test
    fun abandon_before_start_leaves_no_flight() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)
        f.driveApplied(provisional)

        assertTrue(f.session.abandonPrepared())
        assertNull(f.session.controller)
        assertNull(f.session.bundle)
        assertNull(f.session.pendingFlightId)
        assertNull(f.session.activeFlight)
        assertTrue(
            "abandon must never fabricate a flight row",
            f.session.store.flights().isEmpty(),
        )
        // Durable Edge receipts keyed by the provisional id stay as real
        // receipts (never deleted, never relabeled) — but no flight exists.
        assertEquals(1, f.session.store.applicationsFor(provisional).size)

        // Idempotent: abandoning while idle abandons nothing.
        assertFalse(f.session.abandonPrepared())
    }

    @Test
    fun process_death_before_start_resurrects_no_phantom() {        val dir = Files.createTempDirectory("rc-prepare-death").toFile()
        val f = Fixture(dir)
        val bundle = bundle("b1")
        f.prepare(bundle)
        // No flight record exists — only in-memory preparation.

        // Simulate process death: a fresh session on the SAME store dir.
        val resurrected = RcSession(
            storeDir = dir,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
        assertNull(
            "no stored ACTIVE flight means no re-attach",
            resurrected.controller,
        )
        assertNull(resurrected.activeFlight)
        assertTrue(resurrected.store.flights().isEmpty())
        assertNull(resurrected.pendingFlightId)
    }

    @Test
    fun process_death_before_start_leaves_a_visible_pending_attempt() {
        val dir = Files.createTempDirectory("rc-prepare-pending").toFile()
        val f = Fixture(dir)
        val bundle = bundle("b1")
        f.prepare(bundle)
        // A reply may even be in flight — the attempt is pending either way.

        val resurrected = RcSession(
            storeDir = dir,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
        val pending = resurrected.restoredPendingPreparation
        assertNotNull("the operator must see what was pending", pending)
        assertEquals(bundle.get("bundle_id").asText(), pending!!.bundleId)
        assertEquals(bundle.get("bundle_sha256").asText(), pending.bundleDigest)
        // Visibility only: no controller, no flight, no auto-resend.
        assertNull(resurrected.controller)
        assertNull(resurrected.pendingFlightId)
        assertTrue(resurrected.store.flights().isEmpty())
        assertNull(resurrected.activeFlight)
    }

    @Test
    fun consumed_or_abandoned_preparation_leaves_no_pending_attempt() {
        val dir = Files.createTempDirectory("rc-prepare-resolved").toFile()
        val f = Fixture(dir)
        val bundle = bundle("b1")
        f.prepare(bundle)
        val provisional = f.session.pendingFlightId!!
        f.drivePrepared(provisional, bundle)
        f.driveApplied(provisional)
        f.session.startRecordingFromPrepared(bundle)

        val afterRecord = RcSession(
            storeDir = dir,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
        assertNull(afterRecord.restoredPendingPreparation)

        // A fresh preparation can be abandoned: nothing stays pending.
        val dir2 = Files.createTempDirectory("rc-prepare-abandoned").toFile()
        val g = Fixture(dir2)
        g.prepare(bundle("b2"))
        assertTrue(g.session.abandonPrepared())
        val afterAbandon = RcSession(
            storeDir = dir2,
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
        assertNull(afterAbandon.restoredPendingPreparation)
    }

    @Test
    fun repeated_apply_converges_on_one_application() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)

        val controller = f.session.controller!!
        controller.apply()
        controller.apply() // same digest in flight: converges, no second send
        assertEquals(MissionState.APPLYING, controller.state)
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, controller.state)

        controller.apply() // already applied: converges, never re-sessions
        assertEquals(MissionState.APPLIED, controller.state)
        assertEquals(1, f.session.store.applicationsFor(provisional).size)

        // The record follows at 기록 시작 with the same id; still converges.
        val record = f.session.startRecordingFromPrepared(bundle)
        assertEquals(provisional, record.get("flight_id").asText())
        f.session.controller!!.apply()
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        assertEquals(1, f.session.store.applicationsFor(provisional).size)
    }

    @Test
    fun sync_without_record_creates_no_flight_row() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)
        f.driveApplied(provisional)

        f.session.syncFlightState()
        assertTrue(
            "prepared-bundle state is not a flight: no row may appear",
            f.session.store.flights().isEmpty(),
        )
        assertNull(f.session.activeFlight)
    }

    @Test
    fun start_without_prepare_is_refused() {
        val f = Fixture()
        val bundle = bundle("b1")
        try {
            f.session.startRecordingFromPrepared(bundle)
            fail("기록 시작 without 준비 must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("준비"))
        }
        assertTrue(f.session.store.flights().isEmpty())
    }

    @Test
    fun start_with_different_bundle_than_prepared_is_refused() {
        val f = Fixture()
        val bundleA = bundle("b1")
        val bundleB = bundle("b2")
        f.prepare(bundleA)
        try {
            f.session.startRecordingFromPrepared(bundleB)
            fail("binding a different bundle than prepared must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("differs"))
        }
        assertTrue(f.session.store.flights().isEmpty())
    }

    @Test
    fun prepare_rejects_tampered_digest_with_nothing_attached() {
        val f = Fixture()
        val tampered = bundle("b1").deepCopy()
        tampered.put("site_id", "00000000-0000-4000-8000-00000000ffff")
        try {
            f.session.prepareBundle(tampered)
            fail("tampered bundle must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("digest mismatch"))
        }
        assertNull(f.session.controller)
        assertNull(f.session.pendingFlightId)
        assertTrue(f.session.store.flights().isEmpty())
    }

    @Test
    fun prepare_second_bundle_refused_until_abandon() {
        val f = Fixture()
        val bundleA = bundle("b1")
        val bundleB = bundle("b2")
        val provisionalA = f.prepare(bundleA)
        try {
            f.session.prepareBundle(bundleB)
            fail("silently swapping the prepared bundle must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("already prepared"))
        }
        // The original preparation is untouched.
        assertEquals(provisionalA, f.session.pendingFlightId)
        assertEquals(
            bundleA.get("bundle_id").asText(),
            f.session.controller!!.bundleId,
        )

        assertTrue(f.session.abandonPrepared())
        val provisionalB = f.session.prepareBundle(bundleB)
        assertEquals(provisionalB, f.session.pendingFlightId)
        assertEquals(
            bundleB.get("bundle_id").asText(),
            f.session.controller!!.bundleId,
        )
    }

    @Test
    fun abandon_then_reprepare_mints_a_fresh_id_and_converges() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisionalA = f.prepare(bundle)
        f.drivePrepared(provisionalA, bundle)
        f.driveApplied(provisionalA)

        // Cancel: no flight, orphan receipts retained as real Edge receipts.
        assertTrue(f.session.abandonPrepared())
        assertEquals(1, f.session.store.applicationsFor(provisionalA).size)

        // Delayed re-prepare of the same bundle mints a FRESH provisional
        // id (the abandoned one never resurrects) and converges again.
        val provisionalB = f.session.prepareBundle(bundle)
        assertTrue(provisionalB.isNotBlank())
        assertTrue(
            "abandoned id must not resurrect: $provisionalA vs $provisionalB",
            provisionalB != provisionalA,
        )
        f.drivePrepared(provisionalB, bundle)
        // A fresh generation mints its own application id (the store
        // dedupes by application_id: reusing A's id would drop B's row).
        f.driveApplied(provisionalB, "app-prepare-2")
        val record = f.session.startRecordingFromPrepared(bundle)
        assertEquals(provisionalB, record.get("flight_id").asText())
        assertEquals(1, f.session.store.applicationsFor(provisionalB).size)
        // The orphan stays queryable under its own id — retained, never
        // moved, never uploaded as this flight's application.
        assertEquals(1, f.session.store.applicationsFor(provisionalA).size)
    }

    @Test
    fun tap_paths_never_refuse_with_a_blank_reason() {
        // Mirrors the MainActivity tap bodies on the A9-4 device
        // (prepare / re-prepare / apply / apply-from-idle) with a connected
        // link: every refusal must name its reason — a null/blank message
        // must be impossible — and success paths must not throw at all.
        val f = Fixture()
        f.link.connect()
        val bundle = bundle("b1")

        // 준비 tap body: prepare + send, success.
        val provisional = f.session.prepareBundle(bundle)
        f.session.controller!!.prepare()
        assertEquals(1, f.session.pumpOutbound())

        // Re-prepare while PREPARING: refused, with the state named.
        try {
            f.session.prepareBundle(bundle)
            f.session.controller!!.prepare()
            fail("re-prepare while PREPARING must be refused")
        } catch (exc: IllegalStateException) {
            assertFalse(
                "refusal names the state: ${exc.message}",
                exc.message.isNullOrBlank(),
            )
            assertTrue(exc.message!!.contains("PREPARING"))
        }

        // 적용 tap body after PREPARED: apply + send, success.
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        f.session.controller!!.apply()
        // Two envelopes drain: the ACK for the stored MISSION_PREPARED
        // plus the MISSION_APPLY itself.
        assertEquals(2, f.session.pumpOutbound())

        // Re-apply while APPLYING converges silently (same digest in
        // flight): no refusal, nothing new sent.
        f.session.controller!!.apply()
        assertEquals(0, f.session.pumpOutbound())
    }

    @Test
    fun apply_from_idle_is_refused_with_a_reason() {
        val f = Fixture()
        f.link.connect()
        val bundle = bundle("b1")
        f.session.prepareBundle(bundle)
        // No prepare() call: the controller is IDLE, apply is invalid.
        try {
            f.session.controller!!.apply()
            fail("apply without prepare must be refused")
        } catch (exc: IllegalStateException) {
            assertFalse(exc.message.isNullOrBlank())
            assertTrue(exc.message!!.contains("IDLE"))
        }
    }

    @Test
    fun record_does_not_wait_for_deferred_apply_but_start_does() {
        // Device deferred-apply path (A9-4): the Edge ACKs MISSION_APPLY
        // but answers MISSION_APPLIED only after the real install receipt.
        // The local record must not wait for it; the session start must.
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)
        f.session.controller!!.apply()
        assertEquals(MissionState.APPLYING, f.session.controller!!.state)

        // 기록 시작 proceeds while the install is still deferred.
        val record = f.session.startRecordingFromPrepared(bundle)
        assertEquals(provisional, record.get("flight_id").asText())

        // Session start is refused until the deferred APPLIED lands.
        try {
            f.session.requestSessionStart()
            fail("start without APPLIED must be refused (deferred install)")
        } catch (exc: IllegalStateException) {
            assertFalse(exc.message.isNullOrBlank())
            assertTrue(exc.message!!.contains("startSession is not valid"))
        }

        // The deferred APPLIED lands — the install receipt, not a session —
        // and the start proceeds. No deadlock in either direction.
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-deferred")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        f.session.requestSessionStart()
        assertTrue(f.session.hasOpenStartIntent(provisional))
    }

    @Test
    fun session_start_after_epoch_change_is_refused_until_fresh_apply() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)
        f.driveApplied(provisional)
        f.session.startRecordingFromPrepared(bundle)

        // The Edge reports a new generation (reboot): the stale APPLIED
        // no longer authorizes a session start.
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_STATUS",
                Mop.json.createObjectNode()
                    .put("edge_boot_id", "00000000-0000-4000-8000-00000000ee1")
                    .put("runtime_state", "RUNNING")
                    .put("runtime_epoch", "epoch-2"),
            ),
        )
        assertTrue(f.session.controller!!.needsRevalidation)
        try {
            f.session.requestSessionStart()
            fail("start after an Edge reboot must be refused until re-apply")
        } catch (exc: IllegalStateException) {
            assertTrue(
                "refusal names the recovery: ${exc.message}",
                (exc.message ?: "").contains("prepare + apply"),
            )
        }

        // Explicit operator cycle recovers (same path as the mission
        // screen buttons): prepare + fresh apply adopts the new
        // generation, and the start proceeds — no deadlock.
        f.session.controller!!.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        f.session.controller!!.apply()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-epoch-2")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        assertFalse(
            "fresh-generation APPLY adopts the new generation",
            f.session.controller!!.needsRevalidation,
        )
        f.session.requestSessionStart()
        assertFalse(f.session.controller!!.needsRevalidation)
    }

    @Test
    fun abandon_during_apply_is_refused_until_the_reply_lands() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)

        // The apply round is in flight: abandoning now would drop the
        // controller a late MISSION_APPLIED would land on (lost receipt +
        // orphaned Edge install), so abandon waits for the reply.
        f.session.controller!!.apply()
        assertEquals(MissionState.APPLYING, f.session.controller!!.state)
        try {
            f.session.abandonPrepared()
            fail("abandon during an in-flight apply must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue(
                "refusal names the in-flight round: ${exc.message}",
                (exc.message ?: "").contains("진행 중"),
            )
        }
        assertEquals(provisional, f.session.pendingFlightId)

        // The reply lands normally (controller still attached): abandon
        // proceeds, no flight, the receipt stays as a real Edge receipt.
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-apply-refused")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        assertTrue(f.session.abandonPrepared())
        assertNull(f.session.activeFlight)
        assertEquals(1, f.session.store.applicationsFor(provisional).size)
    }

    @Test
    fun abandon_during_prepare_is_refused_until_prepared() {
        val f = Fixture()
        val bundle = bundle("b1")
        f.prepare(bundle)
        f.session.controller!!.prepare()
        assertEquals(MissionState.PREPARING, f.session.controller!!.state)
        try {
            f.session.abandonPrepared()
            fail("abandon during an in-flight prepare must be refused")
        } catch (exc: IllegalStateException) {
            assertTrue((exc.message ?: "").contains("진행 중"))
        }
        f.session.onLinkBytes(
            f.edgeBytes(
                f.session.pendingFlightId!!, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        assertEquals(MissionState.PREPARED, f.session.controller!!.state)
        assertTrue("prepared (reply received) abandons cleanly", f.session.abandonPrepared())
    }

    @Test
    fun epoch_change_with_active_flight_recovers_to_clean_stop() {
        val f = Fixture()
        val bundle = bundle("b1")
        val provisional = f.prepare(bundle)
        f.drivePrepared(provisional, bundle)
        f.driveApplied(provisional)
        val record = f.session.startRecordingFromPrepared(bundle)
        val flightId = record.get("flight_id").asText()

        // The session starts (start intent recorded) before the Edge
        // generation changes.
        f.session.requestSessionStart()
        assertTrue(f.session.hasOpenStartIntent(flightId))

        // Edge reboots: the stale APPLIED authorizes nothing, and the open
        // intent blocks a clean close while the stop is unconfirmed.
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_STATUS",
                Mop.json.createObjectNode()
                    .put("edge_boot_id", "00000000-0000-4000-8000-00000000ee1")
                    .put("runtime_state", "RUNNING")
                    .put("runtime_epoch", "epoch-2"),
            ),
        )
        assertTrue(f.session.controller!!.needsRevalidation)
        try {
            f.session.requestSessionStart()
            fail("start after an Edge reboot must be refused until re-apply")
        } catch (exc: IllegalStateException) {
            assertTrue((exc.message ?: "").contains("prepare + apply"))
        }
        assertTrue(
            "the open intent names the block: ${f.session.closeBlockedReason(flightId)}",
            (f.session.closeBlockedReason(flightId) ?: "").contains("인텐트"),
        )

        // Explicit operator cycle recovers — prepare/apply stay available
        // with an active flight (no deadlock: only the start is gated).
        f.session.controller!!.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        f.session.controller!!.apply()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-epoch-2")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, f.session.controller!!.state)
        assertFalse(
            "fresh-generation APPLY adopts the new generation",
            f.session.controller!!.needsRevalidation,
        )

        // The start proceeds; the session runs to a confirmed stop and a
        // clean close — the forward path exists, the intent resolves.
        f.session.requestSessionStart()
        // The latest SESSION_START: an earlier unacked one from before
        // the epoch change still sits in the retry queue, and acking the
        // stale id must (and does) nothing — only the fresh id converges.
        val startMsgId = f.session.controller!!.endpoint.retransmissions()
            .last { it.get("type").asText() == "SESSION_START" }
            .get("msg_id").asText()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "ACK",
                Mop.json.createObjectNode().put("ack_of", startMsgId),
            ),
        )
        assertEquals(MissionState.SESSION_PENDING, f.session.controller!!.state)
        f.session.controller!!.stopSession()
        val stopMsgId = f.session.controller!!.endpoint.retransmissions()
            .last { it.get("type").asText() == "SESSION_STOP" }
            .get("msg_id").asText()
        f.session.onLinkBytes(
            f.edgeBytes(
                provisional, "ACK",
                Mop.json.createObjectNode().put("ack_of", stopMsgId),
            ),
        )
        val stopped = Mop.json.createObjectNode()
            .put("edge_boot_id", "00000000-0000-4000-8000-0000000000e1")
            .put("applied_bundle_sha256", bundle.get("bundle_sha256").asText())
            .put("runtime_state", "STOPPED")
            .put("config_applied", true)
        ksi.rc.EffectiveConfigHash.expectedFor(bundle)?.let {
            stopped.put("effective_config_hash", it)
        }
        f.session.onLinkBytes(f.edgeBytes(provisional, "MISSION_STATUS", stopped))
        assertEquals(MissionState.STOPPED, f.session.controller!!.state)
        assertEquals(
            ksi.rc.EdgeRuntimeState.STOPPED,
            f.session.controller!!.boundRuntimeStatus?.state,
        )

        val summary = f.session.finalizeAndEndFlight(
            RcSession.SegmentIngest(0, 0, emptyList()), null,
        )
        assertTrue("a confirmed stop finalizes cleanly", summary != null)
        assertEquals(CloseoutRecord.CLEAN, summary!!.closeout.outcome)
        assertNull(f.session.activeFlight)
        assertFalse(f.session.hasOpenStartIntent(flightId))
    }

    @Test
    fun prepare_same_bundle_is_idempotent() {
        val f = Fixture()
        val bundle = bundle("b1")
        val first = f.prepare(bundle)
        assertEquals(first, f.session.prepareBundle(bundle))
        assertEquals(first, f.session.pendingFlightId)
    }
}
