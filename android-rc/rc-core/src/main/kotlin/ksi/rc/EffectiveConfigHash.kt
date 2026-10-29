package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * RC-side mirror of the Edge install-hash algorithm
 * (`edge/src/runtime/bundle_config.cpp`, "effective config hash"): the
 * SHA-256 of the canonical (JCS) bytes of a normalized installed-config
 * object with sorted arrays —
 * `{bundle_sha256, mission_revision_id, policy_revision_id,
 *   proximity_rules[] (sorted by rule_id), zone_rules[] (sorted),
 *   zones[] ({revision_id, local_polygons: 0}, sorted by revision_id),
 *   rejected_rule_ids[] (sorted)}`.
 *
 * Returns null exactly when the Edge would fail the install (ok=false, no
 * hash produced): missing snapshot/policy objects, non-array zones/rules,
 * unparseable rule fields, ACTIVE handling aside (ACTIVE rules are rejected
 * into `rejected_rule_ids`, never installed), invalid ENABLED-zone
 * geometry, duplicate ids. A null never compares equal to a reported
 * hash — "can't prove binding" reads as unconfirmed/revalidation, never
 * as a match.
 *
 * Byte parity with the C++ `WriteCanonicalJson` (RFC 8785 incl. ES6 number
 * formatting) is pinned by [EffectiveConfigHashTest] against hashes
 * produced by the actual C++ parser — including doubles (`threshold_m`
 * 4.0, WGS84 coordinates) and the empty/OFF/ACTIVE shapes.
 */
object EffectiveConfigHash {
    private val RULE_KINDS = setOf("PERSON_EQUIPMENT_PROXIMITY", "ZONE_INTRUSION")
    private val RULE_MODES = setOf("OFF", "SHADOW", "ACTIVE")
    private val GEOMETRY_LEVELS = setOf(
        "PIXEL_ONLY", "TIME_UNVERIFIED", "GEO_ESTIMATE", "METRIC_VERIFIED",
    )

    private const val KIND_PROXIMITY = "PERSON_EQUIPMENT_PROXIMITY"
    private const val MODE_ACTIVE = "ACTIVE"

    /** Expected `effective_config_hash` for [bundle], or null when the Edge
     *  would refuse the install (fail-closed, no hash). */
    fun expectedFor(bundle: ObjectNode): String? {
        val snapshot = bundle.get("mission_snapshot")?.takeIf { it.isObject }
            ?: return null
        val policy = bundle.get("risk_policy")?.takeIf { it.isObject }
            ?: return null

        val zones = parseZones(snapshot) ?: return null
        val (proximity, zoneRules, rejected) = parseRules(policy) ?: return null

        val canonical = Mop.json.createObjectNode()
        canonical.put("bundle_sha256", bundle.get("bundle_sha256")?.takeIf { it.isTextual }?.asText() ?: "")
        canonical.put("mission_revision_id", snapshot.get("mission_revision_id")?.takeIf { it.isTextual }?.asText() ?: "")
        canonical.put("policy_revision_id", policy.get("revision_id")?.takeIf { it.isTextual }?.asText() ?: "")
        val proxArray = canonical.putArray("proximity_rules")
        for (rule in proximity.sortedBy { it.get("rule_id").asText() }) proxArray.add(rule)
        val zoneArray = canonical.putArray("zone_rules")
        for (rule in zoneRules.sortedBy { it.get("rule_id").asText() }) zoneArray.add(rule)
        val zoneEntries = canonical.putArray("zones")
        for (revisionId in zones.sorted()) {
            val entry = zoneEntries.addObject()
            entry.put("revision_id", revisionId)
            entry.put("local_polygons", 0)
        }
        val rejectedArray = canonical.putArray("rejected_rule_ids")
        for (ruleId in rejected.sorted()) rejectedArray.add(ruleId)
        return CanonicalJson.digestSha256(canonical)
    }

    /**
     * Installed (enabled) zone revision ids. Null when the Edge would fail
     * the install (mirrors the zone half of [expectedFor]).
     */
    private fun parseZones(snapshot: JsonNode): List<String>? {
        val zonesNode = snapshot.get("zones") ?: return null
        if (!zonesNode.isArray) return null
        val seen = mutableSetOf<String>()
        val installed = mutableListOf<String>()
        for (zone in zonesNode) {
            if (!zone.isObject) return null
            val revisionId = zone.get("revision_id")?.takeIf { it.isTextual }?.asText()
            if (revisionId.isNullOrEmpty()) return null
            if (!seen.add(revisionId)) return null // duplicate (C++ fails closed)
            val enabled = zone.get("enabled")
            if (enabled == null || !enabled.isBoolean) return null
            if (!enabled.asBoolean()) continue // disabled revisions are not installed
            if (geometryProblem(zone.get("geometry_wgs84")) != null) return null
            installed.add(revisionId)
        }
        return installed
    }

    private data class ParsedRules(
        val proximity: List<ObjectNode>,
        val zoneRules: List<ObjectNode>,
        val rejected: List<String>,
    )

    private fun parseRules(policy: JsonNode): ParsedRules? {
        val rulesNode = policy.get("rules") ?: return null
        if (!rulesNode.isArray) return null
        val seen = mutableSetOf<String>()
        val proximity = mutableListOf<ObjectNode>()
        val zoneRules = mutableListOf<ObjectNode>()
        val rejected = mutableListOf<String>()
        for (node in rulesNode) {
            if (!node.isObject) return null
            val ruleId = node.get("rule_id")?.takeIf { it.isTextual }?.asText()
            if (ruleId.isNullOrEmpty()) return null
            if (!seen.add(ruleId)) return null
            val kind = node.get("kind")?.takeIf { it.isTextual }?.asText()
            if (kind !in RULE_KINDS) return null
            val mode = node.get("mode")?.takeIf { it.isTextual }?.asText()
            if (mode !in RULE_MODES) return null
            val threshold = node.get("threshold_m")
            val hysteresis = node.get("hysteresis_m")
            if (hysteresis == null || !hysteresis.isNumber || hysteresis.asDouble() < 0.0) {
                return null
            }
            val enter = node.get("enter_duration_ms")
            val exit = node.get("exit_duration_ms")
            if (!isNonNegativeInt(enter) || !isNonNegativeInt(exit)) return null
            val geometryLevel = node.get("required_geometry_level")?.takeIf { it.isTextual }?.asText()
            if (geometryLevel !in GEOMETRY_LEVELS) return null
            val classes = node.get("enabled_equipment_classes")
            if (classes == null || !classes.isArray ||
                classes.any { !it.isTextual }
            ) {
                return null
            }
            val revisions = node.get("zone_revision_ids")
            if (revisions == null || !revisions.isArray ||
                revisions.any { !it.isTextual }
            ) {
                return null
            }
            if (mode == MODE_ACTIVE) {
                // Never installed (§4.5): refused, reported, hashed as rejected.
                rejected.add(ruleId)
                continue
            }
            if (kind == KIND_PROXIMITY) {
                // Proximity without a positive threshold fails the install.
                if (threshold == null || !threshold.isNumber || threshold.asDouble() <= 0.0) {
                    return null
                }
            } else {
                // A zone threshold must be null-or-absent (never invented).
                if (threshold != null && !threshold.isNull) return null
            }
            val canonical = Mop.json.createObjectNode()
            canonical.put("rule_id", ruleId)
            canonical.put("kind", kind)
            canonical.put("mode", mode)
            // C++ RuleToCanonical always writes doubles (zone threshold 0.0).
            canonical.put("threshold_m", threshold?.asDouble() ?: 0.0)
            canonical.put("hysteresis_m", hysteresis.asDouble())
            canonical.put("enter_duration_ms", enter.asLong())
            canonical.put("exit_duration_ms", exit.asLong())
            canonical.put("required_geometry_level", geometryLevel)
            val classArray = canonical.putArray("enabled_equipment_classes")
            for (name in classes.map { it.asText() }.sorted()) classArray.add(name)
            val revisionArray = canonical.putArray("zone_revision_ids")
            for (id in revisions.map { it.asText() }.sorted()) revisionArray.add(id)
            if (kind == KIND_PROXIMITY) proximity.add(canonical)
            else zoneRules.add(canonical)
        }
        return ParsedRules(proximity, zoneRules, rejected)
    }

    /** C++ int64 millisecond fields: integral, in range, non-negative. */
    private fun isNonNegativeInt(node: JsonNode?): Boolean {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong()) {
            return false
        }
        return node.asLong() >= 0L
    }

    /** C++ CheckGeoJsonPolygon (no closure requirement — parity, not taste). */
    private fun geometryProblem(geometry: JsonNode?): String? {
        if (geometry == null || !geometry.isObject) return "geometry must be an object"
        if (geometry.get("type")?.asText() != "Polygon") return "geometry must be a Polygon"
        val coords = geometry.get("coordinates")
        if (coords == null || !coords.isArray || coords.size() == 0) {
            return "polygon needs a non-empty ring array"
        }
        for (ring in coords) {
            if (!ring.isArray || ring.size() < 4) return "ring needs >= 4 positions"
            for (position in ring) {
                if (!position.isArray || position.size() != 2) {
                    return "position must be [lon, lat]"
                }
                val lon = position.get(0)
                val lat = position.get(1)
                if (lon == null || !lon.isNumber || lat == null || !lat.isNumber) {
                    return "position must hold numbers"
                }
                if (lon.asDouble() !in -180.0..180.0 ||
                    lat.asDouble() !in -90.0..90.0
                ) {
                    return "position out of WGS84 range"
                }
            }
        }
        return null
    }
}
