package ksi.safetyedge.rc

import java.io.File
import java.io.IOException
import java.nio.file.Files
import ksi.rc.CoreApi
import ksi.rc.CoreHttpResponse
import ksi.rc.CoreTransport
import ksi.rc.DroppedLocation
import ksi.rc.Mop
import ksi.rc.PendingSiteSync
import ksi.rc.PendingSyncOutcome
import ksi.rc.PendingSyncReport
import ksi.rc.PendingUploadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission-screen wiring for offline-first registration (현장 추가):
 * dialog model -> session -> cards round trip, pending client UUID
 * selectable for 준비, sync refresh surfacing the honest resolution,
 * and fail-closed refusal when durable storage is down.
 *
 * Server double mirrors ONLY the `POST /sites` name-dedup rule the
 * uploader depends on (same name = existing row, never a forked
 * duplicate); auth/durability stay with the real-Core E2E suite.
 */
class PendingSiteMissionFlowTest {

    private val now = "2026-10-24T00:00:00Z"
    private val deviceId = "00000000-0000-4000-8000-0000000000aa"

    private fun session(storeDir: File): RcSession = RcSession(
        storeDir = storeDir,
        rcDeviceId = deviceId,
        wallClock = { now },
        link = FakeDroneLink(),
        coreConfig = { null },
    )

    /** Minimal in-memory `POST /sites` double (name-keyed dedup). */
    private class FakeSiteCore(var healthy: Boolean = true) : CoreTransport {
        val sites = linkedMapOf<String, com.fasterxml.jackson.databind.node.ObjectNode>()
        private var counter = 0

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): CoreHttpResponse {
            fun ok(node: com.fasterxml.jackson.databind.JsonNode) =
                CoreHttpResponse(200, Mop.json.writeValueAsBytes(node))
            if (method == "GET" && path == "/healthz") {
                if (!healthy) return CoreHttpResponse(503, ByteArray(0))
                return ok(Mop.json.createObjectNode().put("status", "ok"))
            }
            if (method == "GET" && path == "/sites") {
                val items = Mop.json.createArrayNode()
                sites.values.forEach { items.add(it) }
                return ok(
                    Mop.json.createObjectNode().apply {
                        set<com.fasterxml.jackson.databind.JsonNode>("items", items)
                        putNull("next_cursor")
                    },
                )
            }
            if (method == "POST" && path == "/sites") {
                val req = Mop.json.readTree(body) as com.fasterxml.jackson.databind.node.ObjectNode
                val name = req.get("name").asText()
                val existing = sites.values.firstOrNull { it.get("name")?.asText() == name }
                if (existing != null) return ok(existing.deepCopy())
                counter += 1
                val site = Mop.json.createObjectNode()
                site.put("site_id", "00000000-0000-4000-8000-00000000c%03d".format(counter))
                site.put("org_id", req.get("org_id").asText())
                site.put("name", name)
                val lat = req.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
                val lon = req.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
                val radius = req.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
                if (lat != null && lon != null && radius != null) {
                    site.put("center_lat", lat)
                    site.put("center_lon", lon)
                    site.put("radius_m", radius)
                } else {
                    site.putNull("center_lat")
                    site.putNull("center_lon")
                    site.putNull("radius_m")
                }
                sites[site.get("site_id").asText()] = site
                return ok(site.deepCopy())
            }
            return CoreHttpResponse(404, ByteArray(0))
        }
    }

    private fun syncedSiteNode(siteId: String, name: String, orgId: String = "org-a") =
        Mop.json.createObjectNode().apply {
            put("site_id", siteId)
            put("org_id", orgId)
            put("name", name)
            putNull("center_lat")
            putNull("center_lon")
            putNull("radius_m")
        }

    @Test
    fun dialog_model_to_session_to_cards_round_trip() {
        val sess = session(Files.createTempDirectory("rc-pending-flow").toFile())
        // Dialog model: name + confirmed location (all-or-nothing).
        val form = PendingSiteRegistration.PendingSiteForm(
            name = "들판 현장", centerLat = 36.6, centerLon = 127.4, radiusM = 500.0,
        )
        assertTrue("valid form must be submittable", form.submittable())

        val draft = sess.createPendingSite(form.name, form.centerLat, form.centerLon, form.radiusM)
        assertEquals("들판 현장", draft.name)

        // Cards merge: the pending draft is selectable under its client UUID
        // with the honest badge — never "등록됨".
        val statuses = sess.pendingSites.statuses()
        assertEquals(1, statuses.size)
        val note = PendingSiteMissionFlow.pendingNoteFor(
            statuses.single(), coreConfigured = false, orgKnown = false,
        )
        assertEquals(PendingSiteRegistration.NO_CORE_REASON, note)
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.pendingInput(statuses.single().draft, pendingNote = note),
        )
        assertEquals(draft.clientSiteId, card.siteId)
        assertEquals(PendingSiteRegistration.PENDING_BADGE, card.pendingBadge)
        assertTrue(card.statusLine.contains(PendingSiteRegistration.PENDING_BADGE))
        assertFalse("pending must never read as registered", card.statusLine.contains("등록됨"))
        assertTrue("selection stays on the pending id", PendingSiteMissionFlow.isSelectableSiteId(card.siteId, emptyList(), listOf(draft.clientSiteId)))
        assertFalse(
            "unknown ids are not selectable",
            PendingSiteMissionFlow.isSelectableSiteId("no-such-site", emptyList(), listOf(draft.clientSiteId)),
        )
    }

    @Test
    fun pending_client_uuid_is_selectable_for_prepare_and_keeps_its_flight_id() {
        val sess = session(Files.createTempDirectory("rc-pending-prepare").toFile())
        val draft = sess.createPendingSite("비행 현장", null, null, null)

        // 준비 accepts a digest-verified bundle pinned to the pending id —
        // the selection path must not block the client UUID.
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b9",
            draft.clientSiteId,
        )
        val provisional = sess.prepareBundle(bundle)
        assertTrue(provisional.isNotBlank())
        assertTrue(sess.abandonPrepared())

        // 기록 시작 keeps the pending id on the stored flight row.
        val record = sess.startFlightFromBundle(bundle)
        assertEquals(draft.clientSiteId, record.get("site_id").asText())
        // The upload spec REFUSES the unresolved pending site (site-first
        // ordering) instead of keying a server row by the client UUID.
        val refused = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            sess.uploadSpec(record.get("flight_id").asText())
        }
        assertTrue(
            "upload waits for central registration: ${refused.message}",
            (refused.message ?: "").contains("현장 동기화"),
        )
    }

    @Test
    fun sync_refresh_reports_created_vs_matched_honestly() {
        val dir = Files.createTempDirectory("rc-pending-sync").toFile()
        val sess = session(dir)
        sess.store.saveSite(syncedSiteNode("site-central", "중앙 현장"))
        val created = sess.createPendingSite("새 들판", 36.6, 127.4, 500.0)
        val matched = sess.createPendingSite("중앙 현장", null, null, null)

        val fake = FakeSiteCore()
        // The central row pre-exists on the server (classification is
        // server-side knowledge: the POST answer carries a pre-existing id).
        fake.sites["site-central"] = syncedSiteNode("site-central", "중앙 현장")
        val report = PendingSiteSync(
            CoreApi(fake), sess.pendingSites, sess.store,
        ).syncOnce()
        assertTrue(report.attempted)
        assertEquals(2, report.results.size)

        val lines = PendingSiteMissionFlow.resolutionLines(report) { clientId ->
            sess.pendingSites.statusFor(clientId)?.draft?.name
        }
        assertEquals(2, lines.size)
        assertTrue(
            "created draft names the new registration: $lines",
            lines.any { it.contains("새 들판") && it.contains(PendingSiteRegistration.CREATED_NOTICE) },
        )
        assertTrue(
            "matched draft names the link, never a new registration: $lines",
            lines.any {
                it.contains("중앙 현장") && it.contains(PendingSiteRegistration.MATCHED_NOTICE) &&
                    !it.contains("새 현장")
            },
        )
        // Post-sync the journal resolves the client id forward-only; the
        // stored flight row keeps its pinned id (checked by the prepare test).
        assertEquals(
            report.results.first { it.clientSiteId == created.clientSiteId }.canonicalSiteId,
            sess.pendingSites.resolveForCore(created.clientSiteId),
        )
        assertTrue(sess.pendingSites.isSynced(matched.clientSiteId))
        assertTrue("resolved drafts leave the pending set", sess.pendingSites.pendingDrafts().isEmpty())
        assertNull(
            "nothing pending, no count suffix",
            PendingSiteMissionFlow.pendingCountSuffix(sess.pendingSites.pendingDrafts().size),
        )
    }

    @Test
    fun sync_without_attempt_surfaces_the_wait_reason() {
        val sess = session(Files.createTempDirectory("rc-pending-noattempt").toFile())
        sess.createPendingSite("들판 현장", null, null, null)
        // No org on any synced site: no attempt, draft untouched.
        val fake = FakeSiteCore()
        val report = PendingSiteSync(
            CoreApi(fake), sess.pendingSites, sess.store,
        ).syncOnce()
        assertFalse(report.attempted)
        val lines = PendingSiteMissionFlow.resolutionLines(report) { null }
        assertEquals(1, lines.size)
        assertTrue("org-unknown wait reason surfaces: $lines", lines.single().contains("중앙 조직 미확인"))
        assertEquals(1, sess.pendingSites.pendingDrafts().size)
        assertEquals("대기 1건", PendingSiteMissionFlow.pendingCountSuffix(1))
    }

    @Test
    fun storage_down_refuses_creation_fail_closed() {

        // Same degraded assembly as the app open path: corrupt counter.
        val dir = Files.createTempDirectory("rc-pending-degraded").toFile()
        File(dir, "pending_sites.ndjson").writeText("{}\n")
        File(dir, "msg_counter").writeText("garbage")
        val degraded = session(dir)
        assertTrue(degraded.storageError != null)

        assertEquals(
            StorageHealth.UNAVAILABLE_BANNER,
            PendingSiteMissionFlow.creationRefusal(degraded.storageAvailable),
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            degraded.createPendingSite("현장", null, null, null)
        }
        // Degraded reads stay empty behind the banner (never healthy facts).
        assertTrue(degraded.pendingSites.pendingDrafts().isEmpty())
        assertNull(PendingSiteMissionFlow.creationRefusal(true))
    }

    @Test
    fun matched_location_drop_warns_while_created_stays_quiet() {
        val dropped = PendingUploadResult(
            clientSiteId = "client-1",
            canonicalSiteId = "site-1",
            outcome = PendingSyncOutcome.MATCHED_EXISTING,
            droppedLocation = DroppedLocation(
                draftLat = 36.6, draftLon = 127.4, draftRadiusM = 500.0,
                serverLat = 37.0, serverLon = 127.0, serverRadiusM = 300.0,
            ),
        )
        val created = PendingUploadResult(
            clientSiteId = "client-2",
            canonicalSiteId = "site-2",
            outcome = ksi.rc.PendingSyncOutcome.CREATED,
        )
        val report = PendingSyncReport(
            attempted = true, detail = null, results = listOf(dropped, created),
        )
        val lines = PendingSiteMissionFlow.resolutionLines(report) {
            mapOf("client-1" to "같은 이름", "client-2" to "새 들판")[it]
        }
        assertEquals(2, lines.size)
        assertTrue(
            "dropped draft location warns the operator: $lines",
            lines[0].contains(PendingSiteRegistration.MATCHED_LOCATION_WARN) &&
                lines[0].contains(PendingSiteRegistration.MATCHED_NOTICE),
        )
        assertTrue(
            "created rows carry no location warning: $lines",
            lines[1].contains(PendingSiteRegistration.CREATED_NOTICE) &&
                !lines[1].contains(PendingSiteRegistration.MATCHED_LOCATION_WARN),
        )
    }
}
