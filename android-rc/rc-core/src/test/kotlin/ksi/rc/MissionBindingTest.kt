package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Install binding + stop exactness (contract §4/§5):
 * - a STOP is confirmed ONLY by an exact `runtime_state == STOPPED`
 *   report (modern fields) or `running == false` (legacy bool);
 *   STARTING/CONFIG_APPLIED/REQUEST_ACCEPTED never confirm it;
 * - a MISSION_STATUS that does not name our installed bundle
 *   (`applied_bundle_sha256` foreign or absent) is display-only: no
 *   latches, no transitions, no frame counts;
 * - a bundle-sha match with an irreconcilable `effective_config_hash`
 *   demands revalidation instead of adoption (the two hashes live in
 *   different domains and are never compared with each other).
 */
class MissionBindingTest {
    @Test
    fun modern_reconciliation_cannot_confirm_apply_without_an_installation_hash() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        f.controller.apply()
        f.deliverStatus {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("application_id", "application-1")
            put("runtime_state", "CONFIG_APPLIED")
            put("config_applied", true)
            remove("effective_config_hash")
        }
        assertEquals(MissionState.APPLYING, f.controller.state)
        assertNull(f.controller.lastApplication)
        f.now += 1
        f.deliverStatus {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("application_id", "application-1")
            put("runtime_state", "CONFIG_APPLIED")
            put("config_applied", true)
        }
        assertEquals(MissionState.APPLIED, f.controller.state)
        assertNotNull(f.controller.lastApplication)
    }
    private inner class Fixture(val bundle: ObjectNode = makeBareBundle()) {
        val store = InMemoryRcStore()
        var now = 0L
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

        fun deliverStatus(configure: ObjectNode.() -> Unit) {
            val payload = Mop.json.createObjectNode()
            payload.putNull("applied_bundle_sha256")
            payload.putNull("application_id")
            payload.put("edge_boot_id", "boot-1")
            payload.put("running", false)
            payload.put("effective_edge_seq", 7L)
            EffectiveConfigHash.expectedFor(bundle)?.let { payload.put("effective_config_hash", it) }
            payload.apply(configure)
            val env = Mop.makeEnvelope(
                msgId = Mop.newMsgId("edge", "flt-1", "boot-1", "status-${now}"),
                channel = "CONTROL_RELIABLE",
                type = "MISSION_STATUS",
                flightId = "flt-1",
                edgeBootId = "boot-1",
                seq = null,
                ackOf = null,
                payload = payload,
                sentMonotonicNs = now,
            )
            val outcome = controller.endpoint.onRawEnvelope(env.toString())
            assertTrue(outcome is Inbound.Stored, "crafted status must store: $outcome")
        }
    }

    private fun makeBareBundle(): ObjectNode {
        return MissionFixture.emptyBundle()
    }

    private fun makeRealBundle(): ObjectNode {
        val zone = MissionFixture.zoneRevision("site-1", "zone-1", "zr-1", null)
        val snapshot = MissionFixture.snapshot(
            "site-1", "mission-1", "rev-1", "flt-1", listOf(zone),
        )
        val policy = MissionFixture.policy("site-1", listOf("zr-1"))
        return MissionFixture.bundle("site-1", snapshot, policy, "bundle-1")
    }

    @Test
    fun stop_pending_through_non_stopped_states_then_stopped_confirms() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        f.controller.stopSession()

        for (nonStopped in listOf("STARTING", "CONFIG_APPLIED", "REQUEST_ACCEPTED")) {
            f.edge.runtimeState = nonStopped
            f.edge.processedFrameCount = 0
            f.controller.reconcile()
            f.pump()
            assertEquals(
                MissionState.SESSION_PENDING, f.controller.state,
                "$nonStopped is never a clean stop",
            )
            assertNull(
                f.controller.executionFacts().stopConfirmed,
                "$nonStopped must not latch a clean close",
            )
        }

        f.edge.runtimeState = "STOPPED"
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.STOPPED, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertEquals(true, f.controller.executionFacts().stopConfirmed)
    }

    @Test
    fun running_report_with_starting_does_not_stop() {
        val f = Fixture()
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 3
        f.edge.inputState = mapOf("engine" to "ok")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)

        // A transient STARTING report is not a stop: no transition, no
        // closed session, display follows the report.
        f.edge.runtimeState = "STARTING"
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertNull(f.controller.executionFacts().stopConfirmed)
        assertEquals(EdgeRuntimeState.STARTING, f.controller.runtimeStatus.state)
    }

    @Test
    fun foreign_bundle_status_is_display_only() {
        val f = Fixture()
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        val transitionsBefore = f.controller.transitions.size

        f.deliverStatus {
            put("applied_bundle_sha256", "foreign-sha")
            put("application_id", "foreign-app")
            put("runtime_state", "RUNNING")
            put("runtime_epoch", "epoch-foreign")
            put("config_applied", true)
            put("effective_config_hash", "f".repeat(64))
            put("processed_frame_count", 99)
            put("running", true)
        }

        // Display follows the foreign report...
        assertEquals(EdgeRuntimeState.RUNNING, f.controller.runtimeStatus.state)
        // ...but nothing is adopted: no state change, no latches, no frames.
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertFalse(f.controller.sessionActive)
        assertEquals(transitionsBefore, f.controller.transitions.size)
        val facts = f.controller.executionFacts()
        assertNull(facts.configApplied)
        assertNull(facts.runtimeConfirmed)
        assertNull(facts.frameProcessingObserved)
        assertNull(f.controller.recordedRuntimeEpoch)
        assertFalse(f.controller.riskEvaluationAvailable)
    }

    @Test
    fun missing_bundle_sha_status_is_display_only() {
        val f = Fixture()
        f.controller.prepare()
        f.pump()
        assertEquals(MissionState.PREPARED, f.controller.state)

        f.deliverStatus {
            put("runtime_state", "RUNNING")
            put("processed_frame_count", 7)
            put("running", true)
        }

        assertEquals(EdgeRuntimeState.RUNNING, f.controller.runtimeStatus.state)
        assertEquals(MissionState.PREPARED, f.controller.state)
        assertNull(f.controller.executionFacts().configApplied)
        assertNull(f.controller.executionFacts().runtimeConfirmed)
        assertNull(f.controller.executionFacts().frameProcessingObserved)
    }

    @Test
    fun install_hash_mismatch_demands_revalidation() {
        val f = Fixture(makeRealBundle())
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)

        // Bundle digest matches, but the install content does not: the
        // Edge installed something we cannot reconcile with our bundle.
        f.deliverStatus {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("application_id", "app-1")
            put("runtime_state", "RUNNING")
            put("runtime_epoch", "epoch-1")
            put("config_applied", true)
            put("effective_config_hash", "0".repeat(64))
            put("processed_frame_count", 5)
            put("running", true)
        }

        assertTrue(
            f.controller.needsRevalidation,
            "unreconcilable install content demands explicit re-apply",
        )
        assertFalse(f.controller.sessionActive)
        assertFalse(f.controller.analysisConfirmed)
        assertEquals(MissionState.SESSION_PENDING, f.controller.state)
        assertNull(f.controller.executionFacts().configApplied)
        assertFalse(f.controller.riskEvaluationAvailable)
    }

    @Test
    fun matching_install_hash_adopts() {
        val f = Fixture(makeRealBundle())
        val expected = EffectiveConfigHash.expectedFor(makeRealBundle())
        assertNotNull(expected)
        f.prepareAndApply()
        f.controller.startSession()
        f.pump()

        f.deliverStatus {
            put("applied_bundle_sha256", f.controller.bundleSha)
            put("application_id", "app-1")
            put("runtime_state", "RUNNING")
            put("runtime_epoch", "epoch-1")
            put("config_applied", true)
            put("effective_config_hash", expected)
            put("processed_frame_count", 5)
            put("running", true)
        }

        assertFalse(f.controller.needsRevalidation)
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertTrue(f.controller.sessionActive)
        assertEquals(true, f.controller.executionFacts().configApplied)
    }
}
