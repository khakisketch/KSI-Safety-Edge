package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RC-side install-hash mirror vs the ACTUAL C++ parser: goldens produced
 * by compiling `edge/src/runtime/bundle_config.cpp` + `transport/json.cpp`
 * (read-only) with a scratch driver replicating the `test_rc_bundle` §4
 * MissionFixture shape plus empty/OFF/ACTIVE variants. If JCS number
 * formatting or the mirror algorithm ever drifts from the Edge, these
 * fail — a binding mismatch must read as unconfirmed, never as a match.
 */
class EffectiveConfigHashTest {
    @Test
    fun missing_required_arrays_cannot_produce_an_installation_receipt() {
        val bundle = MissionFixture.emptyBundle()
        (bundle.get("mission_snapshot") as ObjectNode).remove("zones")
        assertNull(EffectiveConfigHash.expectedFor(bundle))
        val withoutRules = MissionFixture.emptyBundle()
        (withoutRules.get("risk_policy") as ObjectNode).remove("rules")
        assertNull(EffectiveConfigHash.expectedFor(withoutRules))
    }
    // C++ golden outputs (see /tmp/golden/effective_hash_golden.cpp run).
    private val shadowPairBundleSha =
        "b30701337de924209824f517531266a68d65bc3acf8bf3e4a373062fe5d24fe8"
    private val shadowPairHash =
        "6254fecf83c15b454c671d50454cec1a25bd29f79efe650efc36bd571b0b82e7"
    private val emptyBundleSha =
        "f5dc227ee1c8f589194873136bb68e0fa367b385eca96de4c50bbdbfacc7e9e8"
    private val emptyHash =
        "622f3c026f8422fd6a3e91a5ff698465b36ef5d7c01d34cb24be93744ec05ffd"
    private val offOnlyHash =
        "5c8947078057b44ebeb4fa65d219886e09665097be6a76bceb56b33cd9d5be86"
    private val activeOnlyHash =
        "9acc94c228995b8c865e8b1f36ef36b644f8b004e5e92c8f3f62f72415fba105"

    private fun geo(lon: Double = 127.0): ObjectNode {
        val geometry = Mop.json.createObjectNode()
        geometry.put("type", "Polygon")
        val ring = geometry.putArray("coordinates").addArray()
        for ((x, y) in listOf(
            lon to 37.0,
            lon + 0.0008 to 37.0,
            lon + 0.0008 to 37.0007,
            lon to 37.0,
        )) {
            ring.addArray().add(x).add(y)
        }
        return geometry
    }

    private fun zone(rev: String = "zone-rev-1"): ObjectNode {
        val zone = Mop.json.createObjectNode()
        zone.put("zone_id", "zone-1")
        zone.put("revision_id", rev)
        zone.put("rule_kind", "PERSON_ENTRY")
        zone.set<com.fasterxml.jackson.databind.JsonNode>("geometry_wgs84", geo())
        // Placeholder like the C++ fixture (hash-irrelevant: only the
        // revision id feeds the install hash).
        zone.put("zone_sha256", "f".repeat(64))
        zone.put("enabled", true)
        return zone
    }

    private fun proxRule(id: String, mode: String): ObjectNode {
        val rule = Mop.json.createObjectNode()
        rule.put("rule_id", id)
        rule.put("kind", "PERSON_EQUIPMENT_PROXIMITY")
        rule.put("mode", mode)
        rule.put("threshold_m", 4.0)
        rule.put("hysteresis_m", 1.0)
        rule.put("enter_duration_ms", 1200)
        rule.put("exit_duration_ms", 1500)
        rule.put("required_geometry_level", "METRIC_VERIFIED")
        rule.putArray("enabled_equipment_classes").add("excavator")
        rule.putArray("zone_revision_ids").add("zone-rev-1")
        return rule
    }

    private fun zoneRule(id: String, mode: String): ObjectNode {
        val rule = Mop.json.createObjectNode()
        rule.put("rule_id", id)
        rule.put("kind", "ZONE_INTRUSION")
        rule.put("mode", mode)
        rule.putNull("threshold_m")
        rule.put("hysteresis_m", 0.0)
        rule.put("enter_duration_ms", 3000)
        rule.put("exit_duration_ms", 1000)
        rule.put("required_geometry_level", "PIXEL_ONLY")
        rule.putArray("enabled_equipment_classes")
        rule.putArray("zone_revision_ids").add("zone-rev-1")
        return rule
    }

    private fun bundleWith(zones: List<ObjectNode>, rules: List<ObjectNode>): ObjectNode {
        val snapshot = Mop.json.createObjectNode()
        snapshot.put("schema_version", 1)
        snapshot.put("mission_id", "mission-1")
        snapshot.put("mission_revision_id", "mis-rev-1")
        snapshot.put("site_id", "site-1")
        val zoneArray = snapshot.putArray("zones")
        zones.forEach { zoneArray.add(it) }
        val policy = Mop.json.createObjectNode()
        policy.put("schema_version", 1)
        policy.put("revision_id", "pol-rev-1")
        policy.put("site_id", "site-1")
        val ruleArray = policy.putArray("rules")
        rules.forEach { ruleArray.add(it) }
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", "bundle-1")
        bundle.put("site_id", "site-1")
        bundle.set<com.fasterxml.jackson.databind.JsonNode>("mission_snapshot", snapshot)
        bundle.set<com.fasterxml.jackson.databind.JsonNode>("risk_policy", policy)
        bundle.put(
            "bundle_sha256",
            CanonicalJson.digestExcluding(bundle, "bundle_sha256"),
        )
        return bundle
    }

    @Test
    fun shadow_pair_matches_cpp_parser_byte_exact() {
        val bundle = bundleWith(
            listOf(zone()),
            listOf(
                proxRule("excavator-proximity-shadow", "SHADOW"),
                zoneRule("zone-intrusion-shadow", "SHADOW"),
            ),
        )
        // The bundle digest itself must match first: it proves JCS
        // formatting parity (doubles included) with the C++ writer.
        assertEquals(shadowPairBundleSha, bundle.get("bundle_sha256").asText())
        assertEquals(shadowPairHash, EffectiveConfigHash.expectedFor(bundle))
    }

    @Test
    fun empty_install_matches_cpp() {
        val bundle = bundleWith(emptyList(), emptyList())
        assertEquals(emptyBundleSha, bundle.get("bundle_sha256").asText())
        assertEquals(emptyHash, EffectiveConfigHash.expectedFor(bundle))
    }

    @Test
    fun off_only_is_installed_but_inert_per_cpp() {
        val bundle = bundleWith(
            listOf(zone()),
            listOf(proxRule("prox-off", "OFF")),
        )
        assertEquals(offOnlyHash, EffectiveConfigHash.expectedFor(bundle))
    }

    @Test
    fun active_only_is_rejected_per_cpp() {
        val bundle = bundleWith(
            listOf(zone()),
            listOf(proxRule("prox-active", "ACTIVE")),
        )
        assertEquals(activeOnlyHash, EffectiveConfigHash.expectedFor(bundle))
    }

    @Test
    fun unparseable_bundles_yield_no_hash_like_failed_cpp_install() {
        val good = bundleWith(
            listOf(zone()),
            listOf(proxRule("excavator-proximity-shadow", "SHADOW")),
        )
        // Missing snapshot.
        val noSnapshot = good.deepCopy()
        noSnapshot.remove("mission_snapshot")
        assertNull(EffectiveConfigHash.expectedFor(noSnapshot))
        // Zones present-but-not-an-array.
        val badZones = good.deepCopy()
        (badZones.get("mission_snapshot") as ObjectNode).put("zones", "nope")
        assertNull(EffectiveConfigHash.expectedFor(badZones))
        // Enabled flag missing.
        val noEnabled = good.deepCopy()
        ((((noEnabled.get("mission_snapshot") as ObjectNode).get("zones") as com.fasterxml.jackson.databind.node.ArrayNode).get(0) as ObjectNode)).remove("enabled")
        assertNull(EffectiveConfigHash.expectedFor(noEnabled))
        // Enabled-zone geometry not a polygon.
        val badGeo = good.deepCopy()
        (((badGeo.get("mission_snapshot") as ObjectNode).get("zones") as com.fasterxml.jackson.databind.node.ArrayNode).get(0) as ObjectNode).putObject("geometry_wgs84").put("type", "Point")
        assertNull(EffectiveConfigHash.expectedFor(badGeo))
        // Enabled-zone geometry out of range.
        val outOfRange = good.deepCopy()
        val ring = (((outOfRange.get("mission_snapshot") as ObjectNode).get("zones") as com.fasterxml.jackson.databind.node.ArrayNode).get(0) as ObjectNode).get("geometry_wgs84").get("coordinates").get(0) as com.fasterxml.jackson.databind.node.ArrayNode
        (ring.get(0) as com.fasterxml.jackson.databind.node.ArrayNode).removeAll()
        (ring.get(0) as com.fasterxml.jackson.databind.node.ArrayNode).add(999.0).add(37.0)
        assertNull(EffectiveConfigHash.expectedFor(outOfRange))
        // Duplicate zone revision.
        val dupZone = bundleWith(listOf(zone(), zone()), listOf(proxRule("p", "SHADOW")))
        assertNull(EffectiveConfigHash.expectedFor(dupZone))
        // Unknown rule kind.
        val badKind = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW").apply { put("kind", "BOGUS") }),
        )
        assertNull(EffectiveConfigHash.expectedFor(badKind))
        // Zone rule with a threshold (C++ fails closed).
        val zoneThreshold = bundleWith(
            listOf(zone()),
            listOf(zoneRule("z", "SHADOW").apply { put("threshold_m", 5.0) }),
        )
        assertNull(EffectiveConfigHash.expectedFor(zoneThreshold))
        // Proximity rule without a positive threshold.
        val noThreshold = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW").apply { putNull("threshold_m") }),
        )
        assertNull(EffectiveConfigHash.expectedFor(noThreshold))
        // Millisecond field as double (C++ requires int).
        val doubleMs = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW").apply { put("enter_duration_ms", 1200.0) }),
        )
        assertNull(EffectiveConfigHash.expectedFor(doubleMs))
        // Unknown geometry level.
        val badLevel = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW").apply { put("required_geometry_level", "BOGUS") }),
        )
        assertNull(EffectiveConfigHash.expectedFor(badLevel))
        // Non-string equipment class.
        val badClass = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW").apply {
                (get("enabled_equipment_classes") as com.fasterxml.jackson.databind.node.ArrayNode).add(7)
            }),
        )
        assertNull(EffectiveConfigHash.expectedFor(badClass))
        // Duplicate rule id.
        val dupRule = bundleWith(
            listOf(zone()),
            listOf(proxRule("p", "SHADOW"), proxRule("p", "SHADOW")),
        )
        assertNull(EffectiveConfigHash.expectedFor(dupRule))
    }
}
