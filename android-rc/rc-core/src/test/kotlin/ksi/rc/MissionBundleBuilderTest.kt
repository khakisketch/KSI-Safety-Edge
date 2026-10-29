package ksi.rc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G11 local bundle composition (edge-operation contract §10.2/§10.3):
 * the RC's production bundle carries real zones + SHADOW rules from its
 * own store, validates against the mission-bundle schema shape, verifies
 * digests, and reports its contents honestly (rule count + exclusions).
 */
class MissionBundleBuilderTest {
    private val siteId = Fixture.uuid(100)
    private val flightId = Fixture.uuid(101)
    private val orgId = Fixture.uuid(102)
    private val createdAt = "2026-10-21T00:00:00Z"

    private fun seededStore(): FileRcStore {
        val store = FileRcStore(Files.createTempDirectory("rc-local-bundle"))
        val site = Mop.json.createObjectNode()
        site.put("site_id", siteId)
        site.put("org_id", orgId)
        store.saveSite(site)
        return store
    }

    private fun zone(zoneN: Int, revN: Int, lon: Double = 127.0): com.fasterxml.jackson.databind.node.ObjectNode =
        MissionFixture.zoneRevision(siteId, Fixture.uuid(zoneN), Fixture.uuid(revN), null, lon)

    private fun baseRequest(store: FileRcStore) = LocalBundleRequest(
        siteId = siteId,
        flightId = flightId,
        createdAtUtc = createdAt,
        orgId = orgId,
        modelProfiles = listOf(
            LocalModelProfile("ksi-person-detector-dfine-s-coco", Fixture.hex('a')),
        ),
    )

    private fun keySet(node: com.fasterxml.jackson.databind.node.ObjectNode): Set<String> =
        node.fieldNames().asSequence().toSet()

    @Test
    fun local_bundle_validates_shape_and_verifies_digests() {
        val rev1 = zone(200, 201)
        val rev2 = zone(202, 203, lon = 127.01)
        val store = seededStore()
        store.saveZoneRevision(rev1)
        store.saveZoneRevision(rev2)
        val before1 = CanonicalJson.canonicalBytes(rev1).copyOf()
        val before2 = CanonicalJson.canonicalBytes(rev2).copyOf()

        val result = MissionBundleBuilder.build(store, baseRequest(store))
        val bundle = result.bundle

        // Exact key sets (additionalProperties:false at every level).
        assertEquals(
            setOf(
                "schema_version", "bundle_id", "site_id", "created_at_utc",
                "origin", "created_by", "mission_snapshot", "risk_policy",
                "calibration_refs", "ground_model_refs", "bundle_sha256",
            ),
            keySet(bundle),
        )
        val snapshot = bundle.get("mission_snapshot") as com.fasterxml.jackson.databind.node.ObjectNode
        assertEquals(
            setOf(
                "schema_version", "mission_id", "flight_id", "org_id", "site_id",
                "mission_revision_id", "origin", "created_at_utc", "zones",
                "model_profiles", "recording_policy", "content_sha256",
            ),
            keySet(snapshot),
        )
        assertEquals("RC_LOCAL", snapshot.get("origin").asText())
        assertEquals("RC_LOCAL", bundle.get("origin").asText())
        for (zoneNode in snapshot.get("zones")) {
            assertEquals(
                setOf(
                    "zone_id", "revision_id", "rule_kind", "geometry_wgs84",
                    "zone_sha256", "enabled",
                ),
                (zoneNode as com.fasterxml.jackson.databind.node.ObjectNode).fieldNames().asSequence().toSet(),
            )
            assertEquals(
                setOf("type", "coordinates"),
                zoneNode.get("geometry_wgs84").fieldNames().asSequence().toSet(),
            )
        }
        val policy = bundle.get("risk_policy") as com.fasterxml.jackson.databind.node.ObjectNode
        assertEquals(
            setOf(
                "schema_version", "policy_id", "revision_id", "site_id",
                "content_sha256", "rules", "created_offline",
            ),
            keySet(policy),
        )
        assertEquals(true, policy.get("created_offline").asBoolean())
        for (rule in policy.get("rules")) {
            assertEquals(
                setOf(
                    "rule_id", "kind", "mode", "threshold_m", "hysteresis_m",
                    "enter_duration_ms", "exit_duration_ms",
                    "required_geometry_level", "enabled_equipment_classes",
                    "zone_revision_ids",
                ),
                (rule as com.fasterxml.jackson.databind.node.ObjectNode).fieldNames().asSequence().toSet(),
            )
        }

        // Digests verify (recompute and compare).
        assertTrue(CanonicalJson.verifyDigest(snapshot, "content_sha256"))
        assertTrue(CanonicalJson.verifyDigest(policy, "content_sha256"))
        assertTrue(CanonicalJson.verifyDigest(bundle, "bundle_sha256"))

        // Default rule set: ZONE_INTRUSION over the work-zone revisions, SHADOW.
        assertEquals(1, policy.get("rules").size())
        val rule = policy.get("rules").get(0)
        assertEquals("ZONE_INTRUSION", rule.get("kind").asText())
        assertEquals("SHADOW", rule.get("mode").asText())
        assertTrue(rule.get("threshold_m").isNull)
        assertEquals("GEO_ESTIMATE", rule.get("required_geometry_level").asText())
        assertEquals(2, rule.get("zone_revision_ids").size())

        // The app shows "위험 규칙 N건" from this report.
        assertEquals(listOf(Fixture.uuid(201), Fixture.uuid(203)), result.zoneRevisionIds)
        assertEquals(1, result.ruleCount)
        assertTrue(result.excluded.isEmpty())

        // The store was not mutated: revisions are pinned, not overwritten.
        assertTrue(before1.contentEquals(CanonicalJson.canonicalBytes(store.zoneRevision(Fixture.uuid(201))!!)))
        assertTrue(before2.contentEquals(CanonicalJson.canonicalBytes(store.zoneRevision(Fixture.uuid(203))!!)))

        // Deterministic ids: same stored facts rebuild the same bundle.
        val rebuilt = MissionBundleBuilder.build(store, baseRequest(store))
        assertEquals(
            bundle.get("bundle_id").asText(),
            rebuilt.bundle.get("bundle_id").asText(),
        )
        assertEquals(
            bundle.get("bundle_sha256").asText(),
            rebuilt.bundle.get("bundle_sha256").asText(),
        )

        // The composed bundle travels through the existing apply path store.
        store.saveMissionBundle(result.bundle)
        assertNotNull(store.missionBundle(bundle.get("bundle_id").asText()))
        // Identical re-save is a no-op, not a fork.
        store.saveMissionBundle(result.bundle)
    }

    @Test
    fun zero_zones_zero_rules_honest_empty_result() {
        val store = seededStore()
        val result = MissionBundleBuilder.build(store, baseRequest(store))
        assertTrue(result.zoneRevisionIds.isEmpty())
        assertEquals(0, result.ruleCount)
        assertTrue(result.excluded.isEmpty())
        val bundle = result.bundle
        assertEquals(0, bundle.get("mission_snapshot").get("zones").size())
        assertEquals(0, bundle.get("risk_policy").get("rules").size())
        assertTrue(CanonicalJson.verifyDigest(bundle, "bundle_sha256"))
    }

    @Test
    fun active_request_refused() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(
                store, baseRequest(store).copy(ruleMode = "ACTIVE"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(
                store, baseRequest(store).copy(ruleMode = "BOGUS"),
            )
        }
        // OFF is inert, not enforcing: allowed, never ACTIVE.
        val off = MissionBundleBuilder.build(
            store, baseRequest(store).copy(ruleMode = "OFF"),
        )
        assertEquals("OFF", off.bundle.get("risk_policy").get("rules").get(0).get("mode").asText())
    }

    @Test
    fun missing_or_bad_geometry_excluded_with_reason_never_fabricated() {
        val good = zone(200, 201)
        val missing = zone(202, 203)
        missing.remove("geometry_wgs84")
        val flat = zone(204, 205)
        (flat.get("geometry_wgs84") as com.fasterxml.jackson.databind.node.ObjectNode).put("type", "Point")
        val open = zone(206, 207)
        val openCoords = open.get("geometry_wgs84").get("coordinates").get(0) as com.fasterxml.jackson.databind.node.ArrayNode
        openCoords.remove(openCoords.size() - 1) // drop the closing position ...
        openCoords.addArray().add(127.5).add(37.5) // ... and end elsewhere: unclosed
        assertEquals(4, openCoords.size())
        val store = seededStore()
        store.saveZoneRevision(good)
        store.saveZoneRevision(missing)
        store.saveZoneRevision(flat)
        store.saveZoneRevision(open)

        val result = MissionBundleBuilder.build(store, baseRequest(store))
        assertEquals(listOf(Fixture.uuid(201)), result.zoneRevisionIds)
        assertEquals(1, result.ruleCount)
        assertEquals(3, result.excluded.size)
        val byId = result.excluded.associate { it.revisionId to it.reason }
        assertTrue(byId[Fixture.uuid(203)]!!.contains("geometry"))
        assertTrue(byId[Fixture.uuid(205)]!!.contains("Polygon"))
        assertTrue(byId[Fixture.uuid(207)]!!.contains("closed"))
        // No fabricated polygon: the bundle carries only the good geometry.
        val zones = result.bundle.get("mission_snapshot").get("zones")
        assertEquals(1, zones.size())
        assertEquals(Fixture.uuid(201), zones.get(0).get("revision_id").asText())
    }

    @Test
    fun proximity_rule_only_with_explicit_threshold() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        // Default: no proximity rule, distances are never invented.
        val shadow = MissionBundleBuilder.build(store, baseRequest(store))
        assertEquals(1, shadow.ruleCount)

        val withProximity = MissionBundleBuilder.build(
            store, baseRequest(store).copy(proximityThresholdM = 4.0),
        )
        assertEquals(2, withProximity.ruleCount)
        val proximity = withProximity.bundle.get("risk_policy").get("rules").get(1)
        assertEquals("PERSON_EQUIPMENT_PROXIMITY", proximity.get("kind").asText())
        assertEquals("SHADOW", proximity.get("mode").asText())
        assertEquals(4.0, proximity.get("threshold_m").asDouble())
        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(
                store, baseRequest(store).copy(proximityThresholdM = 0.0),
            )
        }

        // Zero zones + threshold still means zero rules.
        val empty = MissionBundleBuilder.build(
            seededStore(), baseRequest(seededStore()).copy(proximityThresholdM = 4.0),
        )
        assertEquals(0, empty.ruleCount)
    }

    @Test
    fun geometry_level_is_caller_chosen_default_honest_for_uncalibrated() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        val level = MissionBundleBuilder.build(store, baseRequest(store))
            .bundle.get("risk_policy").get("rules").get(0)
            .get("required_geometry_level").asText()
        assertEquals("GEO_ESTIMATE", level)

        val explicit = MissionBundleBuilder.build(
            store, baseRequest(store).copy(requiredGeometryLevel = "METRIC_VERIFIED"),
        ).bundle.get("risk_policy").get("rules").get(0)
            .get("required_geometry_level").asText()
        assertEquals("METRIC_VERIFIED", explicit)

        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(
                store, baseRequest(store).copy(requiredGeometryLevel = "BOGUS"),
            )
        }
    }

    @Test
    fun explicit_selection_pins_revisions_unknown_and_foreign_excluded() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        store.saveZoneRevision(zone(202, 203))
        val foreign = MissionFixture.zoneRevision(
            Fixture.uuid(999), Fixture.uuid(300), Fixture.uuid(301), null,
        )
        store.saveZoneRevision(foreign)

        val result = MissionBundleBuilder.build(
            store,
            baseRequest(store).copy(
                zoneRevisionIds = listOf(
                    Fixture.uuid(203), Fixture.uuid(203), // duplicate
                    Fixture.uuid(404), // unknown
                    Fixture.uuid(301), // foreign site
                ),
            ),
        )
        assertEquals(listOf(Fixture.uuid(203)), result.zoneRevisionIds)
        assertEquals(1, result.ruleCount)
        val byId = result.excluded.associate { it.revisionId to it.reason }
        assertTrue(byId[Fixture.uuid(203)]!!.contains("duplicate"))
        assertTrue(byId[Fixture.uuid(404)]!!.contains("unknown"))
        assertTrue(byId[Fixture.uuid(301)]!!.contains("site"))
    }

    @Test
    fun no_profile_basis_fails_loudly_never_invented() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(store, baseRequest(store).copy(modelProfiles = null))
        }
    }

    @Test
    fun base_snapshot_supplies_identity_and_profiles() {
        val store = seededStore()
        store.saveZoneRevision(zone(200, 201))
        val base = MissionFixture.snapshot(
            siteId, Fixture.uuid(710), Fixture.uuid(711), Fixture.uuid(712),
            listOf(zone(200, 201)),
        )
        val request = LocalBundleRequest(
            siteId = siteId,
            flightId = flightId,
            createdAtUtc = createdAt,
            baseMissionSnapshot = base,
        )
        val result = MissionBundleBuilder.build(store, request)
        val snapshot = result.bundle.get("mission_snapshot")
        assertEquals(Fixture.uuid(710), snapshot.get("mission_id").asText())
        assertEquals(Fixture.uuid(711), snapshot.get("mission_revision_id").asText())
        assertEquals(Fixture.uuid(900), snapshot.get("org_id").asText())
        assertEquals("RC_LOCAL", snapshot.get("origin").asText())
        assertEquals(1, snapshot.get("zones").size())
        assertEquals(1, snapshot.get("model_profiles").size())
        assertEquals(1, result.ruleCount)
    }

    @Test
    fun org_without_basis_fails_loudly() {
        val root = Files.createTempDirectory("rc-local-bundle-noorg")
        val store = FileRcStore(root) // no site saved: no org basis anywhere
        store.saveZoneRevision(zone(200, 201))
        val request = LocalBundleRequest(
            siteId = siteId,
            flightId = flightId,
            createdAtUtc = createdAt,
            modelProfiles = listOf(LocalModelProfile("m", Fixture.hex('a'))),
        )
        assertFailsWith<IllegalArgumentException> {
            MissionBundleBuilder.build(store, request)
        }
        assertNull(store.missionBundle("anything"))
    }
}
