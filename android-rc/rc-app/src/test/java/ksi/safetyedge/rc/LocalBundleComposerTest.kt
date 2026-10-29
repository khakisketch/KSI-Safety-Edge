package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.CanonicalJson
import ksi.rc.LocalBundleRequest
import ksi.rc.Mop

/**
 * Host tests for the LOCAL bundle composition path (product gap G11):
 * SHADOW-rule bundles built from the RC's OWN stored zone revisions plus
 * the stored mission snapshot basis — saved, digest-verified, origin
 * RC_LOCAL, never ACTIVE, never invented geometry.
 */
class LocalBundleComposerTest {

    private val siteId = "00000000-0000-4000-8000-000000000001"
    private val otherSiteId = "00000000-0000-4000-8000-000000000011"
    private val zoneA = "00000000-0000-4000-8000-000000000002"
    private val revA1 = "00000000-0000-4000-8000-000000000003"
    private val revA2 = "00000000-0000-4000-8000-000000000004"
    private val zoneB = "00000000-0000-4000-8000-000000000005"
    private val revB1 = "00000000-0000-4000-8000-000000000006"
    private val missionId = "00000000-0000-4000-8000-000000000007"
    private val missionRev = "00000000-0000-4000-8000-000000000008"
    private val orgId = "00000000-0000-4000-8000-000000000009"
    private val flightId = "00000000-0000-4000-8000-000000000010"
    private val unknownRev = "11111111-1111-4111-8111-111111111111"

    private fun session(): RcSession = RcSession(
        storeDir = Files.createTempDirectory("rc-local-bundle").toFile(),
        rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
        wallClock = { "2026-10-10T07:00:00Z" },
        link = FakeDroneLink(),
        coreConfig = { null },
    )

    private fun zoneRevision(site: String, zone: String, rev: String): com.fasterxml.jackson.databind.node.ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("zone_id", zone)
        node.put("revision_id", rev)
        node.putNull("base_revision_id")
        node.put("site_id", site)
        val geometry = node.putObject("geometry_wgs84")
        geometry.put("type", "Polygon")
        val ring = geometry.putArray("coordinates").addArray()
        ring.addArray().add(127.0).add(37.0)
        ring.addArray().add(127.0008).add(37.0)
        ring.addArray().add(127.0008).add(37.0007)
        ring.addArray().add(127.0).add(37.0)
        node.put("rule_kind", "PERSON_ENTRY")
        return node
    }

    private fun snapshot(site: String, zones: List<com.fasterxml.jackson.databind.node.ObjectNode>): com.fasterxml.jackson.databind.node.ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("mission_id", missionId)
        node.put("flight_id", flightId)
        node.put("org_id", orgId)
        node.put("site_id", site)
        node.put("mission_revision_id", missionRev)
        node.put("origin", "SERVER")
        node.put("created_at_utc", "2026-10-10T00:00:00Z")
        val zoneArr = node.putArray("zones")
        for (zone in zones) {
            val entry = zoneArr.addObject()
            entry.put("zone_id", zone.get("zone_id").asText())
            entry.put("revision_id", zone.get("revision_id").asText())
            entry.put("rule_kind", zone.get("rule_kind").asText())
            entry.set<com.fasterxml.jackson.databind.JsonNode>(
                "geometry_wgs84", zone.get("geometry_wgs84").deepCopy(),
            )
            entry.put("zone_sha256", "0".repeat(64))
            entry.put("enabled", true)
        }
        val profiles = node.putArray("model_profiles")
        profiles.addObject()
            .put("model_id", "ksi-person-detector")
            .put("model_digest", "a".repeat(64))
            .put("enabled", true)
        node.putObject("recording_policy").put("rc_proxy", true)
        node.put(
            "content_sha256",
            CanonicalJson.digestExcluding(node, "content_sha256"),
        )
        return node
    }

    private fun seeded(): RcSession {
        val s = session()
        s.store.saveZoneRevision(zoneRevision(siteId, zoneA, revA1))
        s.store.saveZoneRevision(zoneRevision(siteId, zoneB, revB1))
        s.store.saveMissionSnapshot(snapshot(siteId, listOf(zoneRevision(siteId, zoneA, revA1))))
        return s
    }

    private fun request(
        store: ksi.rc.MissionStore,
        site: String = siteId,
        revisions: List<String>? = null,
        proximity: Double? = null,
    ) = LocalBundleRequest(
        siteId = site,
        flightId = flightId,
        createdAtUtc = "2026-10-10T07:00:00Z",
        baseMissionSnapshot = LocalBundleComposer.baseSnapshotFor(store, site),
        zoneRevisionIds = revisions,
        proximityThresholdM = proximity,
    )

    @Test
    fun explicit_selection_pins_ids_with_shadow_rule_count() {
        val s = seeded()
        val result = LocalBundleComposer.composeAndSave(
            s.store, request(s.store, revisions = listOf(revA1, revB1)),
        )

        assertEquals(listOf(revA1, revB1), result.zoneRevisionIds)
        assertEquals(1, result.ruleCount)
        assertTrue(result.excluded.isEmpty())
        assertEquals("RC_LOCAL", result.bundle.get("origin").asText())
        val rules = result.bundle.get("risk_policy").get("rules")
        assertEquals(1, rules.size())
        assertEquals("SHADOW", rules.get(0).get("mode").asText())
        assertEquals("ZONE_INTRUSION", rules.get(0).get("kind").asText())
        // Saved and digest-verified: the existing apply path can pin it.
        val stored = s.store.missionBundle(result.bundle.get("bundle_id").asText())
        assertTrue(stored != null)
        assertTrue(CanonicalJson.verifyDigest(result.bundle, "bundle_sha256"))
    }

    @Test
    fun default_selection_pins_latest_per_zone_and_reports_superseded() {
        val s = seeded()
        s.store.saveZoneRevision(zoneRevision(siteId, zoneA, revA2))
        val result = LocalBundleComposer.composeAndSave(s.store, request(s.store))

        assertEquals(listOf(revA2, revB1), result.zoneRevisionIds)
        assertEquals(1, result.ruleCount)
        assertEquals(1, result.excluded.size)
        assertEquals(revA1, result.excluded[0].revisionId)
        assertTrue(result.excluded[0].reason.contains("superseded"))
    }

    @Test
    fun unknown_duplicate_and_cross_site_revisions_are_excluded_with_reasons() {
        val s = seeded()
        val foreign = zoneRevision(otherSiteId, zoneA, "00000000-0000-4000-8000-000000000012")
        s.store.saveZoneRevision(foreign)
        val result = LocalBundleComposer.composeAndSave(
            s.store,
            request(
                s.store,
                revisions = listOf(revA1, revA1, unknownRev, foreign.get("revision_id").asText()),
            ),
        )

        assertEquals(listOf(revA1), result.zoneRevisionIds)
        assertEquals(3, result.excluded.size)
        val byId = result.excluded.associateBy { it.revisionId }
        assertTrue(byId[revA1]!!.reason.contains("duplicate"))
        assertTrue(byId[unknownRev]!!.reason.contains("unknown"))
        assertTrue(byId[foreign.get("revision_id").asText()]!!.reason.contains(otherSiteId))
    }

    @Test
    fun empty_zone_set_is_zero_rules_not_an_error() {
        val s = seeded()
        val result = LocalBundleComposer.composeAndSave(
            s.store, request(s.store, revisions = emptyList()),
        )

        assertTrue(result.zoneRevisionIds.isEmpty())
        assertEquals(0, result.ruleCount)
        assertEquals(0, result.bundle.get("risk_policy").get("rules").size())
        assertTrue(CanonicalJson.verifyDigest(result.bundle, "bundle_sha256"))
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = result.bundle.get("bundle_id").asText(),
                zoneRevisionIds = result.zoneRevisionIds,
                ruleCount = result.ruleCount,
                excluded = result.excluded,
            ),
        )
        assertTrue(panel.ruleLine.contains("위험 규칙 0건"))
    }

    @Test
    fun proximity_threshold_adds_a_second_shadow_rule() {
        val s = seeded()
        val result = LocalBundleComposer.composeAndSave(
            s.store, request(s.store, revisions = listOf(revA1), proximity = 4.0),
        )

        assertEquals(2, result.ruleCount)
        val rules = result.bundle.get("risk_policy").get("rules")
        assertTrue((0 until rules.size()).all { rules.get(it).get("mode").asText() == "SHADOW" })
    }

    @Test
    fun session_compose_entry_saves_and_selects_from_its_own_store() {
        val s = seeded()
        val result = s.composeLocalBundle(siteId, "2026-10-10T07:00:00Z")

        assertEquals(listOf(revA1, revB1), result.zoneRevisionIds)
        assertTrue(s.store.missionBundle(result.bundle.get("bundle_id").asText()) != null)
        // The composed bundle pins a flight through the existing path.
        val record = s.startFlightFromBundle(result.bundle)
        val zones = record.get("zone_revision_ids")
        assertEquals(
            result.zoneRevisionIds.sorted(),
            (0 until zones.size()).map { zones.get(it).asText() }.sorted(),
        )
    }

    @Test
    fun compose_without_a_snapshot_basis_refuses() {
        val s = session()
        s.store.saveZoneRevision(zoneRevision(siteId, zoneA, revA1))
        try {
            s.composeLocalBundle(siteId, "2026-10-10T07:00:00Z")
            throw AssertionError("expected refusal without a snapshot basis")
        } catch (exc: IllegalArgumentException) {
            assertTrue(exc.message!!.contains("org_id") || exc.message!!.contains("basis"))
        }
    }
}
