package ksi.safetyedge.rc

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session layer for offline-first registration: drafts are created with no
 * network, survive a session restart (process death), resolve through the
 * pending alias for Core-facing reporting only, and refuse fail-closed
 * when storage is down. Full upload semantics (CREATED vs MATCHED_EXISTING
 * vs retry) live in rc-core `PendingSiteSyncTest` against the fake Core.
 */
class RcSessionPendingSiteTest {
    private val now = "2026-10-24T00:00:00Z"
    private val deviceId = "00000000-0000-4000-8000-0000000000aa"

    private fun session(storeDir: File, core: (() -> Pair<String, String>?)? = null): RcSession =
        RcSession(
            storeDir = storeDir,
            rcDeviceId = deviceId,
            wallClock = { now },
            link = FakeDroneLink(),
            coreConfig = core ?: { null },
        )

    @Test
    fun draft_created_offline_survives_session_restart() {
        val storeDir = Files.createTempDirectory("rc-pending-site-session").toFile()
        val created = session(storeDir).createPendingSite("들판 현장", 36.6, 127.4, 500.0)
        assertTrue(created.clientSiteId.isNotBlank())

        // Process death: a new session on the same directory.
        val restarted = session(storeDir)
        val pending = restarted.pendingSites.pendingDrafts()
        assertEquals(1, pending.size)
        assertEquals(created.clientSiteId, pending.single().clientSiteId)
        assertEquals("들판 현장", pending.single().name)
    }

    @Test
    fun invalid_draft_is_rejected_without_touching_storage() {
        val storeDir = Files.createTempDirectory("rc-pending-site-invalid").toFile()
        val sess = session(storeDir)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            sess.createPendingSite("  ", 36.6, null, null)
        }
        assertTrue(sess.pendingSites.pendingDrafts().isEmpty())
    }

    @Test
    fun pending_sync_is_null_without_core_and_without_storage() {
        val storeDir = Files.createTempDirectory("rc-pending-site-nocore").toFile()
        val sess = session(storeDir)
        assertNull("no Core configured: no uploader, drafts stay pending", sess.pendingSiteSync())

        // Corrupt counter reopens degraded (same assembly seam as RcApp).
        File(storeDir, "pending_sites.ndjson").writeText("{}\n")
        File(storeDir, "msg_counter").writeText("garbage")
        val degraded = session(storeDir)
        assertTrue(degraded.storageError != null)
        assertNull(degraded.pendingSiteSync())
        org.junit.Assert.assertThrows(IOException::class.java) {
            degraded.createPendingSite("현장", null, null, null)
        }
        // Degraded reads stay empty behind the banner (never healthy facts).
        assertTrue(degraded.pendingSites.pendingDrafts().isEmpty())
        assertNull(degraded.pendingSites.canonicalIdFor("any"))
    }

    @Test
    fun upload_gate_reason_names_the_wait_and_clears_after_sync() {
        val storeDir = Files.createTempDirectory("rc-pending-site-gate").toFile()
        val sess = session(storeDir)
        val draft = sess.createPendingSite("비행 현장", null, null, null)
        sess.store.startFlight(
            ksi.rc.Mop.json.createObjectNode().apply {
                put("schema_version", 1)
                put("flight_id", "flight-1")
                put("site_id", draft.clientSiteId)
                put("mission_id", "mission-1")
                put("mission_revision_id", "rev-1")
                put("mission_sha256", "msha")
                put("bundle_id", "bundle-1")
                put("bundle_sha256", "bsha")
                putArray("zone_revision_ids")
                put("started_at_utc", now)
                putNull("ended_at_utc")
                put("state", "STARTED")
            },
        )
        val reason = sess.uploadGateReason("flight-1")
        assertTrue("gate names the site-first wait", reason != null)
        assertEquals(
            "${PendingSiteRegistration.UPLOAD_WAIT_REASON} (현장 ${draft.clientSiteId.take(8)})",
            reason,
        )
        // Unknown flights are not a wait (the spec still throws for them).
        assertNull(sess.uploadGateReason("no-such-flight"))
        // Post-sync the gate opens to the canonical id.
        sess.pendingSites.recordSynced(
            draft.clientSiteId, "site-canonical-1", ksi.rc.PendingSyncOutcome.CREATED,
        )
        assertNull(sess.uploadGateReason("flight-1"))
        assertEquals("site-canonical-1", sess.uploadSpec("flight-1").siteId)
    }

    @Test
    fun upload_spec_refuses_unresolved_pending_site_and_resolves_after_sync() {
        val storeDir = Files.createTempDirectory("rc-pending-site-spec").toFile()
        val sess = session(storeDir)
        val draft = sess.createPendingSite("비행 현장", null, null, null)
        sess.store.startFlight(
            ksi.rc.Mop.json.createObjectNode().apply {
                put("schema_version", 1)
                put("flight_id", "flight-1")
                put("site_id", draft.clientSiteId)
                put("mission_id", "mission-1")
                put("mission_revision_id", "rev-1")
                put("mission_sha256", "msha")
                put("bundle_id", "bundle-1")
                put("bundle_sha256", "bsha")
                putArray("zone_revision_ids")
                put("started_at_utc", now)
                putNull("ended_at_utc")
                put("state", "STARTED")
            },
        )
        // Pre-sync the spec is REFUSED, not keyed by the client UUID: the
        // server registers flights without validating the site id, and an
        // early upload would strand the row under a UUID plus 409 every
        // re-register after the alias resolves.
        val refused = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            sess.uploadSpec("flight-1")
        }
        assertTrue(
            "honest wait reason names the site-first order: ${refused.message}",
            (refused.message ?: "").contains("현장 동기화"),
        )
        // The stored record is untouched by the refusal.
        assertEquals(
            draft.clientSiteId,
            sess.store.flight("flight-1")!!.get("site_id").asText(),
        )
        // Post-sync the alias resolves forward-only to the canonical id.
        sess.pendingSites.recordSynced(
            draft.clientSiteId, "site-canonical-1", ksi.rc.PendingSyncOutcome.CREATED,
        )
        assertEquals("site-canonical-1", sess.uploadSpec("flight-1").siteId)
        assertEquals(
            draft.clientSiteId,
            sess.store.flight("flight-1")!!.get("site_id").asText(),
        )
    }

    @Test
    fun sync_pending_sites_now_is_null_without_core_or_storage() {
        val storeDir = Files.createTempDirectory("rc-pending-site-seam").toFile()
        val sess = session(storeDir)
        sess.createPendingSite("들판 현장", null, null, null)
        assertNull("no Core configured: no sync round, drafts stay pending", sess.syncPendingSitesNow())

        File(storeDir, "pending_sites.ndjson").writeText("{}\n")
        File(storeDir, "msg_counter").writeText("garbage")
        val degraded = session(storeDir)
        assertTrue(degraded.storageError != null)
        assertNull("storage down: fail-closed, no sync round", degraded.syncPendingSitesNow())
    }

    @Test
    fun sync_pending_sites_now_fails_fast_offline() {
        val storeDir = Files.createTempDirectory("rc-pending-site-offline").toFile()
        val sess = session(
            storeDir,
            core = { "http://127.0.0.1:9" to "field-token" },
        )
        sess.createPendingSite("들판 현장", null, null, null)

        val report = sess.syncPendingSitesNow()

        assertTrue("a sync object exists once a Core is configured", report != null)
        assertTrue("unreachable Core: no attempt, drafts untouched", report!!.attempted == false)
        assertEquals(1, sess.pendingSites.pendingDrafts().size)
    }
}
