package ksi.safetyedge.rc

import java.nio.file.Files
import ksi.rc.MissionState
import ksi.rc.Mop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the bench-only [WorkspacePreviewFixture] core
 * ([WorkspacePreviewFixture.seedSession]): allowlist gating, guard rails
 * (non-bench link / configured Core / contradictory state refuse without
 * touching the store), per-scenario states, and idempotent re-entry.
 *
 * Pure JVM: real [RcSession] on temp dirs with [FakeDroneLink], no Robolectric,
 * no device, no network. Image bytes are small synthetics — the store path
 * is opaque bytes; real JPEG decode happens on-device from the bundled
 * ESO scene asset.
 */
class WorkspacePreviewFixtureTest {

    private fun session(core: (() -> Pair<String, String>?)? = null) = RcSession(
        storeDir = Files.createTempDirectory("rc-workspace-fixture").toFile(),
        rcDeviceId = Mop.newMsgId("fixture-test", "rc-device"),
        wallClock = { "2026-10-24T00:00:00Z" },
        link = FakeDroneLink(),
        coreConfig = core ?: { null },
    )

    private fun siteId(scenario: String) = Mop.newMsgId("fixture-workspace", scenario, "site")

    private val image = ByteArray(9_000) { (it % 251).toByte() }

    @Test fun preparation_response_fixtures_never_create_record_or_claim_runtime() {
        for (scenario in listOf("mission-prepared", "mission-applied")) {
            val s = session()
            assertTrue(WorkspacePreviewFixture.seedSession(s, scenario, null))
            assertEquals(if (scenario == "mission-applied") MissionState.APPLIED else MissionState.PREPARED, s.controller!!.state)
            assertTrue(s.store.flights().isEmpty())
            assertNotNull(s.pendingFlightId)
            assertFalse(s.controller!!.analysisConfirmed)
            assertFalse(s.controller!!.sessionActive)
            assertFalse(WorkspacePreviewFixture.seedSession(s, scenario, null))
        }
    }

    @Test fun mission_browser_is_idempotent_multi_site_multi_plan_without_commands() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "mission-browser", null))
        assertEquals(2, s.store.sites().size)
        assertEquals(4, s.store.missionBundles().size)
        s.store.missionBundles().forEach {
            assertTrue(ksi.rc.CanonicalJson.verifyDigest(it, "bundle_sha256"))
            assertTrue(it.path("mission_snapshot").isObject)
        }
        assertNull(s.controller)
        assertTrue(s.store.flights().isEmpty())
        assertTrue(WorkspacePreviewFixture.seedSession(s, "mission-browser", null))
        assertEquals(4, s.store.missionBundles().size)
        assertTrue((s.link as FakeDroneLink).sentBytes.isEmpty())
    }

    @Test
    fun unknown_scenario_touches_nothing() {
        val s = session()
        assertFalse(WorkspacePreviewFixture.seedSession(s, "nope", image))
        assertFalse(WorkspacePreviewFixture.seedSession(s, null, image))
        assertFalse(WorkspacePreviewFixture.seedSession(s, "", image))
        assertTrue(s.store.flights().isEmpty())
        assertTrue(s.store.missionBundles().isEmpty())
        assertNull(s.activeFlight)
    }

    @Test
    fun guards_refuse_without_touching_the_store() {
        // Configured Core: bench must never seed an uploadable store.
        val withCore = session(core = { "http://127.0.0.1:1/api" to "tok" })
        assertFalse(WorkspacePreviewFixture.seedSession(withCore, "ready", image))
        assertTrue(withCore.store.flights().isEmpty())
        assertTrue(withCore.store.missionBundles().isEmpty())
    }

    @Test
    fun ready_seeds_site_and_bundle_with_no_flight() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "ready", image))
        assertNull(s.activeFlight)
        assertNull(s.controller)
        assertTrue(s.store.flights().isEmpty())
        val bundles = s.store.missionBundles()
            .filter { it.get("site_id")?.asText() == siteId("ready") }
        assertEquals(1, bundles.size)
        assertNotNull(s.store.site(siteId("ready")))
        // Idempotent re-entry: no second bundle.
        assertTrue(WorkspacePreviewFixture.seedSession(s, "ready", image))
        assertEquals(
            1,
            s.store.missionBundles().count { it.get("site_id")?.asText() == siteId("ready") },
        )
    }

    @Test
    fun recording_seeds_applied_record_without_session() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "recording", image))
        val active = s.activeFlight
        assertNotNull(active)
        assertEquals("APPLIED", active!!.get("state").asText())
        assertEquals(siteId("recording"), active.get("site_id").asText())
        val controller = s.controller
        assertNotNull(controller)
        assertEquals(MissionState.APPLIED, controller!!.state)
        assertFalse(controller.sessionActive)
        assertFalse(controller.analysisConfirmed)
        // Idempotent re-entry keeps the same flight.
        assertTrue(WorkspacePreviewFixture.seedSession(s, "recording", image))
        assertEquals(
            active.get("flight_id").asText(),
            s.activeFlight!!.get("flight_id").asText(),
        )
    }

    @Test
    fun observing_seeds_running_with_frame_proof_and_full_image() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "observing", image))
        val controller = s.controller
        assertNotNull(controller)
        assertEquals(MissionState.RUNNING, controller!!.state)
        assertTrue(controller.sessionActive)
        assertTrue(
            "bound runtime engine frame proof required",
            controller.analysisConfirmed,
        )
        assertNotNull(controller.boundRuntimeStatus)
        val active = s.activeFlight
        assertNotNull(active)
        val flightId = active!!.get("flight_id").asText()
        val stored = s.store.artifactBytes(WorkspacePreviewFixture.artifactIdFor("observing"))
        assertNotNull("announced artifact must have all chunks stored", stored)
        assertTrue(stored!!.contentEquals(image))
        assertTrue(s.store.artifactsFor(flightId).isNotEmpty())
    }

    @Test
    fun partial_announces_artifact_but_withholds_chunks() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "partial", image))
        assertNotNull(s.activeFlight)
        assertNotNull(
            "announce must be stored",
            s.store.announceFor(WorkspacePreviewFixture.artifactIdFor("partial")),
        )
        assertNull(
            "chunks withheld: no complete bytes",
            s.store.artifactBytes(WorkspacePreviewFixture.artifactIdFor("partial")),
        )
    }

    @Test
    fun apply_error_reports_rejection_without_flight() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "apply-error", image))
        assertEquals(MissionState.REJECTED, s.controller!!.state)
        assertTrue(s.controller!!.lastRejectionReasons.isNotEmpty())
        assertNull("rejection must not fabricate a flight", s.activeFlight)
        assertTrue(s.store.flights().isEmpty())
        // Idempotent re-entry via the still-attached REJECTED controller.
        assertTrue(WorkspacePreviewFixture.seedSession(s, "apply-error", image))
        assertTrue(s.store.flights().isEmpty())
    }

    @Test
    fun contradictory_active_flight_is_refused_not_reset() {
        val s = session()
        assertTrue(WorkspacePreviewFixture.seedSession(s, "recording", image))
        val flightId = s.activeFlight!!.get("flight_id").asText()
        assertFalse(WorkspacePreviewFixture.seedSession(s, "observing", image))
        assertFalse(WorkspacePreviewFixture.seedSession(s, "ready", image))
        // The existing record is untouched.
        assertEquals(flightId, s.activeFlight!!.get("flight_id").asText())
        assertEquals(1, s.store.flights().size)
    }
}
