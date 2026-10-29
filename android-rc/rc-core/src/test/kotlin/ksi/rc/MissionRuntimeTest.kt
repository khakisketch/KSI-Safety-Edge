package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit B controller runtime behavior (contract §2/§3/§5): ACK is not
 * execution proof, runtime_state is authoritative when present, DEGRADED
 * executes but is never "safe", epoch changes demand revalidation, and
 * repeated commands converge instead of re-sessioning.
 */
class MissionRuntimeTest {
    private inner class Fixture(bundle: ObjectNode? = null) {
        val store = InMemoryRcStore()
        var now = 0L
        val realBundle: ObjectNode = bundle ?: makeBundle()
        val controller = MissionController("flt-1", realBundle, store, { now })
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
    }

    private fun makeBundle(): ObjectNode {
        return MissionFixture.emptyBundle()
    }

    /** Real installable bundle (zones + SHADOW rule): the FakeEdge reports
     *  the install hash of what it actually received, like a real Edge. */
    private fun makeRealBundle(): ObjectNode {
        val zone = MissionFixture.zoneRevision("site-1", "zone-1", "zr-1", null)
        val snapshot = MissionFixture.snapshot(
            "site-1", "mission-1", "rev-1", "flt-1", listOf(zone),
        )
        val policy = MissionFixture.policy("site-1", listOf("zr-1"))
        return MissionFixture.bundle("site-1", snapshot, policy, "bundle-1")
    }

    private fun report(
        f: Fixture,
        hash: String? = EffectiveConfigHash.expectedFor(f.realBundle),
        bundleSha: String = f.controller.bundleSha,
        controlFlight: String = "flt-1",
        degraded: List<String> = emptyList(),
    ) {
        val payload = Mop.json.createObjectNode().apply {
            put("applied_bundle_sha256", bundleSha)
            put("edge_boot_id", "boot-1")
            put("running", true)
            put("runtime_state", if (degraded.isEmpty()) "RUNNING" else "DEGRADED")
            put("runtime_epoch", "epoch-1")
            put("config_applied", true)
            if (hash != null) put("effective_config_hash", hash)
            put("processed_frame_count", 12)
            putArray("degraded_reasons").also { a -> degraded.forEach { a.add(it) } }
            putObject("input_state").put("engine", "ok").put("video", "ok")
        }
        val env = Mop.makeEnvelope(
            Mop.newMsgId("runtime-review", controlFlight, f.now.toString()),
            "CONTROL_RELIABLE", "MISSION_STATUS", controlFlight,
            "boot-1", 1, null, payload, f.now,
        )
        f.controller.handle(env, payload)
    }

    private fun reportUninstalledRestart(controller: MissionController, now: Long) {
        val payload = Mop.json.createObjectNode().apply {
            putNull("applied_bundle_sha256")
            put("edge_boot_id", "boot-after-process-restart")
            put("runtime_epoch", "epoch-after-process-restart")
            put("runtime_state", "STOPPED")
            put("config_applied", false)
            putNull("effective_config_hash")
            put("processed_frame_count", 0)
        }
        val env = Mop.makeEnvelope(
            Mop.newMsgId("uninstalled-restart", now.toString()),
            "CONTROL_RELIABLE", "MISSION_STATUS", "flt-1",
            "boot-after-process-restart", 1, null, payload, now,
        )
        controller.handle(env, payload)
    }

    @Test
    fun actual_reboot_with_cleared_installation_still_demands_revalidation() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        report(f)
        reportUninstalledRestart(f.controller, f.now + 1)
        assertTrue(f.controller.needsRevalidation)
        assertFalse(f.controller.sessionActive)
        assertNull(f.controller.boundRuntimeStatus)
    }

    @Test
    fun restored_controller_compares_historical_boot_identity_without_restoring_running() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        val restored = MissionController("flt-1", f.realBundle, f.store, { f.now })
        assertEquals(MissionState.IDLE, restored.state)
        reportUninstalledRestart(restored, f.now + 1)
        assertTrue(restored.needsRevalidation)
        assertFalse(restored.sessionActive)
        assertNull(restored.boundRuntimeStatus)
    }

    @Test
    fun modern_report_without_installation_hash_is_not_current_execution_evidence() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        report(f, hash = null)
        assertEquals(EdgeRuntimeState.RUNNING, f.controller.runtimeStatus.state)
        assertNull(f.controller.boundRuntimeStatus)
        assertFalse(f.controller.analysisConfirmed)
        assertNull(f.controller.executionFacts().frameProcessingObserved)
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
    }

    @Test
    fun foreign_install_report_remains_raw_diagnostics_not_a_fresh_confirmed_report() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        report(f)
        assertTrue(f.controller.analysisConfirmed)
        assertEquals(EdgeRuntimeState.RUNNING, f.controller.boundRuntimeStatus?.state)
        f.now += 1000
        report(f, bundleSha = "b".repeat(64))
        assertEquals(EdgeRuntimeState.RUNNING, f.controller.runtimeStatus.state)
        assertNull(f.controller.boundRuntimeStatus)
        assertFalse(f.controller.analysisConfirmed)
    }

    @Test
    fun foreign_control_flight_cannot_change_the_current_task() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        report(f, controlFlight = "another-flight")
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertEquals(EdgeRuntimeState.UNKNOWN, f.controller.runtimeStatus.state)
        assertNull(f.controller.boundRuntimeStatus)
        assertTrue(f.controller.ignoredStale.any { it.reason == "foreign control flight" })
    }

    @Test
    fun a_reported_durable_evidence_failure_cannot_be_cleared_by_a_later_healthy_snapshot() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        report(f, degraded = listOf("session_capacity_exceeded"))
        assertEquals(true, f.controller.executionFacts().durableEvidenceFailure)
        f.now += 1000
        report(f)
        assertEquals(true, f.controller.executionFacts().durableEvidenceFailure)
    }

    @Test
    fun autonomy_evidence_saturation_is_not_a_durable_evidence_failure() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        // Saturation is a bounded, non-fatal evidence gap: observation keeps
        // running, so it must never latch the failure that forces FAILED.
        report(f, degraded = listOf("autonomy_evidence_saturated"))
        assertEquals(false, f.controller.executionFacts().durableEvidenceFailure)
        f.now += 1000
        report(f)
        assertEquals(false, f.controller.executionFacts().durableEvidenceFailure)
    }

    @Test
    fun explicit_unknown_session_stop_requires_a_fresh_bound_stopped_report() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.controller.stopSession(reconcileUnknown = true)
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        f.edge.runtimeState = "STOPPED"
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertEquals(EdgeRuntimeState.STOPPED, f.controller.boundRuntimeStatus?.state)
        assertNull(f.controller.executionFacts().frameProcessingObserved)
    }

    @Test
    fun a_fresh_bound_stopped_receipt_can_prove_history_without_restoring_live_execution() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        val restored = MissionController("flt-1", f.realBundle, f.store, { f.now })
        assertFalse(restored.sessionActive)
        restored.stopSession(reconcileUnknown = true)
        ksi.rc.pump(restored.endpoint, f.edge)
        f.edge.runtimeState = "STOPPED"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 20
        f.edge.inputState = mapOf("engine" to "ok", "video" to "ok")
        restored.reconcile()
        ksi.rc.pump(restored.endpoint, f.edge)
        assertEquals(MissionState.STOPPED, restored.state)
        assertEquals(true, restored.executionFacts().frameProcessingObserved)
        assertEquals(true, restored.executionFacts().runtimeConfirmed)
        assertFalse(restored.sessionActive)
        assertFalse(restored.analysisConfirmed)
    }

    @Test
    fun ack_alone_never_confirms_execution() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertFalse(f.controller.sessionReady)
        assertFalse(f.controller.analysisConfirmed)
        assertEquals(EdgeRuntimeState.UNKNOWN, f.controller.runtimeStatus.state)
    }

    @Test
    fun running_reported_without_frame_counts_is_active_but_not_confirmed() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        // The Edge claims RUNNING but reported no frame processing:
        // active, yet analysis unconfirmed.
        assertFalse(f.controller.analysisConfirmed)
        assertFalse(f.controller.sessionReady)
        assertEquals("epoch-1", f.controller.recordedRuntimeEpoch)
    }

    @Test
    fun running_with_frames_confirms_analysis() {
        // A real installable bundle: the Edge reports the install hash of
        // what it received, and the RC verifies it (never an invented hash
        // compared against a different domain).
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 12
        f.edge.lastProcessedFrameAgeMs = 40
        // Engine success signal: a detector ran and its last attempt did
        // not fail. Counts alone are attempts, not inference.
        f.edge.inputState = mapOf("engine" to "ok", "video" to "ok", "telemetry" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertTrue(f.controller.analysisConfirmed)
        assertTrue(f.controller.sessionReady)
        assertEquals(true, f.controller.executionFacts().frameProcessingObserved)
        assertEquals(true, f.controller.executionFacts().configApplied)
        assertTrue(f.controller.riskEvaluationAvailable)
    }

    @Test
    fun degraded_executes_but_is_never_safe() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "DEGRADED"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 4
        f.edge.degradedReasons =
            listOf("video_input_stale", "telemetry_absent", "zone_geometry_unavailable")
        f.edge.inputState =
            mapOf("video" to "stale", "telemetry" to "absent", "engine" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        // DEGRADED with the observation still going: the session executes
        // (active + confirmed processing — the geometry hold withholds zone
        // verdicts, never detection itself) — but the UI must read the
        // degraded reasons, never sessionActive, as the safety signal.
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertTrue(f.controller.analysisConfirmed)
        assertEquals(EdgeRuntimeState.DEGRADED, f.controller.runtimeStatus.state)
        assertEquals(
            listOf("video_input_stale", "telemetry_absent", "zone_geometry_unavailable"),
            f.controller.runtimeStatus.degradedReasons,
        )
        assertEquals("stale", f.controller.runtimeStatus.inputState["video"])
    }

    @Test
    fun starting_with_zero_frames_stays_pending() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "STARTING"
        f.edge.processedFrameCount = 0
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertFalse(f.controller.analysisConfirmed)
    }

    @Test
    fun positive_count_with_engine_absent_is_attempts_not_analysis() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 12
        // No detector ever bound: the count tracks frame attempts, not
        // successful inference.
        f.edge.inputState = mapOf("engine" to "absent", "video" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        // Execution is confirmed (RUNNING), but no analysis ran.
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertFalse(f.controller.analysisConfirmed)
        assertFalse(f.controller.sessionReady)
        assertNull(
            f.controller.executionFacts().frameProcessingObserved,
            "engine absent: unknown, never true and never a fabricated false",
        )

        // A clean close still must not read SUCCESS: no analysis evidence.
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertEquals(true, f.controller.executionFacts().stopConfirmed)
        assertEquals(true, f.controller.executionFacts().runtimeConfirmed)
        assertNull(f.controller.executionFacts().frameProcessingObserved)
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(
                SessionFacts(
                    configApplied = true,
                    runtimeConfirmed = true,
                    frameProcessingObserved = null,
                    observationCount = 2,
                    videoEvidencePresent = true,
                    declaredGaps = false,
                    linkInterrupted = false,
                    runtimeEpochChanged = false,
                    stopConfirmed = true,
                ),
            ),
            "positive attempts without engine success are not a SUCCESS basis",
        )
    }

    @Test
    fun positive_count_with_engine_failed_is_not_analysis() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 12
        // The last attempt failed: positive evidence of NO successful
        // inference (false, not unknown).
        f.edge.inputState = mapOf("engine" to "failed", "video" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertFalse(f.controller.analysisConfirmed)
        assertFalse(f.controller.sessionReady)
        assertEquals(false, f.controller.executionFacts().frameProcessingObserved)
    }

    @Test
    fun positive_count_with_missing_engine_field_is_unknown() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 12
        // Legacy/partial report: counts but no engine signal at all.
        f.edge.inputState = mapOf("video" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertFalse(f.controller.analysisConfirmed)
        assertNull(f.controller.executionFacts().frameProcessingObserved)
    }

    @Test
    fun runtime_epoch_change_demands_revalidation_without_silent_recovery() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 10
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)

        // The Edge restarts: new generation, still (claims) running.
        f.edge.runtimeEpoch = "epoch-2"
        f.controller.reconcile()
        f.pump()
        assertTrue(f.controller.needsRevalidation)
        assertFalse(f.controller.sessionActive,
            "the old generation's confirmation died with it")
        assertFalse(f.controller.analysisConfirmed)
        assertEquals("epoch-1", f.controller.recordedRuntimeEpoch,
            "the recorded generation is history, not overwritten")
        assertEquals("epoch-2", f.controller.runtimeStatus.runtimeEpoch,
            "the new report is shown, not adopted")

        // Repeat reports from the foreign generation converge nothing.
        f.controller.reconcile()
        f.pump()
        assertTrue(f.controller.needsRevalidation)
        assertFalse(f.controller.sessionActive)

        // Explicit revalidation cycle: prepare is allowed while flagged,
        // and a fresh start clears the flag only after a fresh apply.
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
        f.controller.apply()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        f.controller.startSession()
        assertFalse(f.controller.needsRevalidation)
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals("epoch-2", f.controller.recordedRuntimeEpoch)
        assertTrue(f.controller.sessionActive)
    }

    @Test
    fun session_start_after_epoch_change_is_refused_until_a_fresh_apply() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 10
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)

        // The Edge restarts: the old generation's APPLIED state survives
        // locally, but the install is wiped.
        f.edge.runtimeEpoch = "epoch-2"
        f.controller.reconcile()
        f.pump()
        assertTrue(f.controller.needsRevalidation)

        // Starting now would send SESSION_START against a wiped install:
        // refused, and nothing is emitted.
        val outboxBefore = f.controller.endpoint.outbox.size
        assertFailsWith<IllegalStateException> {
            f.controller.startSession()
        }
        assertEquals(
            outboxBefore, f.controller.endpoint.outbox.size,
            "a refused start must not emit a session command",
        )
        assertTrue(f.controller.needsRevalidation)

        // Explicit operator cycle: prepare + fresh apply adopts the new
        // generation (no deadlock — prepare/apply stay available flagged).
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)
        f.controller.apply()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertFalse(
            f.controller.needsRevalidation,
            "fresh-generation APPLY adopts the new generation",
        )

        // Starting is authorized again.
        f.controller.startSession()
        assertFalse(f.controller.needsRevalidation)
    }

    @Test
    fun repeated_start_and_stop_converge_without_resessioning() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 3
        f.controller.startSession()
        val outboxBefore = f.controller.endpoint.outbox.size
        // A repeated SESSION_START while one is pending is a no-op.
        f.controller.startSession()
        assertEquals(outboxBefore, f.controller.endpoint.outbox.size,
            "repeated start must not emit a second session command")
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)

        // Repeated STOP while the stop is pending is a no-op too.
        f.edge.runtimeState = "STOPPED"
        f.controller.stopSession()
        val stopOutbox = f.controller.endpoint.outbox.size
        f.controller.stopSession()
        assertEquals(stopOutbox, f.controller.endpoint.outbox.size,
            "repeated stop must not emit a second session command")
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state,
            "a stop ACK confirms nothing by itself")
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertEquals(true, f.controller.executionFacts().stopConfirmed)
    }

    @Test
    fun apply_is_idempotent_and_rejection_marks_config_failed() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.pump()
        assertEquals(MissionState.APPLIED, f.controller.state)
        // A repeated APPLY converges: no second application row.
        f.controller.apply()
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertEquals(1, f.store.applicationsFor("flt-1").size)

        // Rejection is execution-failure evidence for the classifier.
        val g = Fixture()
        g.edge.corruptPrepare = true
        g.controller.prepare()
        g.pump()
        assertEquals(MissionState.REJECTED, g.controller.state)
        assertEquals(false, g.controller.executionFacts().configApplied)
    }

    @Test
    fun link_loss_is_session_interruption_evidence_only_while_pending_or_active() {
        val f = Fixture()
        // No session in flight: link loss is not session evidence.
        f.controller.noteLinkLost()
        assertFalse(f.controller.linkInterruptedDuringSession)

        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.processedFrameCount = 2
        f.controller.startSession()
        f.pump()
        f.controller.noteLinkLost()
        assertTrue(f.controller.linkInterruptedDuringSession)
        assertTrue(f.controller.executionFacts().linkInterrupted)
    }

    @Test
    fun legacy_bool_path_still_drives_state_without_claiming_analysis() {
        val f = Fixture()
        f.prepareAndApply()
        // FakeEdge without any §3 fields: pure legacy `running` bool.
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertEquals(EdgeRuntimeState.UNKNOWN, f.controller.runtimeStatus.state)
        assertFalse(f.controller.analysisConfirmed,
            "a bool alone never proves frame processing")
        assertNull(f.controller.executionFacts().runtimeConfirmed,
            "no runtime_state ever reported: unknown, not false")
        f.controller.stopSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state) // legacy protocol state only
        assertNull(f.controller.executionFacts().stopConfirmed)
        assertFalse(f.controller.boundRuntimeStatus?.state == EdgeRuntimeState.STOPPED)
    }
}
