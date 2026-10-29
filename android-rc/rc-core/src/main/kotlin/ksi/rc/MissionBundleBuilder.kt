package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** A stored zone revision left out of a local bundle, with the honest reason. */
data class ExcludedZoneRevision(val revisionId: String, val reason: String)

/** One detector profile for `mission_snapshot.model_profiles[]`. */
data class LocalModelProfile(
    val modelId: String,
    val modelDigest: String,
    val enabled: Boolean = true,
)

/**
 * Inputs for one RC-local bundle composition. Identity fields fall back to
 * deterministic `uuid5` derivations (see [MissionBundleBuilder]) so the
 * same stored facts always yield the same ids — but nothing is invented:
 * `org_id` and the detector profiles must come from the caller or from a
 * stored base snapshot, never from a placeholder.
 */
data class LocalBundleRequest(
    val siteId: String,
    val flightId: String,
    val createdAtUtc: String,
    val missionId: String? = null,
    val orgId: String? = null,
    val missionRevisionId: String? = null,
    /**
     * Stored snapshot to inherit the mission/org/profile/recording basis
     * from (its zones are REPLACED by the pinned selection and its digests
     * recomputed — the base itself is never mutated). Null requires
     * [missionId]/[orgId]/[missionRevisionId]/[modelProfiles] instead.
     */
    val baseMissionSnapshot: ObjectNode? = null,
    /**
     * Explicit revision selection (pinned as-is). Null pins the latest
     * stored revision per zone for the site. Unknown, cross-site,
     * duplicate or invalid revisions are excluded with a reason — never
     * fabricated, never used to overwrite the store.
     */
    val zoneRevisionIds: List<String>? = null,
    val createdBy: String? = null,
    /** SHADOW (default) or OFF. ACTIVE is refused outright (see [build]). */
    val ruleMode: String = SHADOW_MODE,
    /**
     * Geometry level the rules require. Default is the honest one for an
     * uncalibrated site ([DEFAULT_GEOMETRY_LEVEL]); a caller that has
     * verified metric calibration may pass METRIC_VERIFIED explicitly.
     */
    val requiredGeometryLevel: String = DEFAULT_GEOMETRY_LEVEL,
    /**
     * When non-null (and zones are non-empty) a second SHADOW rule,
     * PERSON_EQUIPMENT_PROXIMITY, is added with this threshold. Null means
     * no proximity rule: distances are never invented.
     */
    val proximityThresholdM: Double? = null,
    val hysteresisM: Double = 1.0,
    val enterDurationMs: Long = 1200L,
    val exitDurationMs: Long = 1500L,
    val enabledEquipmentClasses: List<String> = emptyList(),
    val modelProfiles: List<LocalModelProfile>? = null,
) {
    companion object {
        const val SHADOW_MODE = "SHADOW"
        const val DEFAULT_GEOMETRY_LEVEL = "GEO_ESTIMATE"
    }
}

/**
 * One composed LOCAL bundle plus an honest account of its contents: the
 * pinned zone revision ids and the rule count the app shows as
 * "위험 규칙 N건", and every stored revision that was left out with why.
 */
data class LocalBundleResult(
    val bundle: ObjectNode,
    val zoneRevisionIds: List<String>,
    val ruleCount: Int,
    val excluded: List<ExcludedZoneRevision>,
)

/**
 * RC-local mission-bundle composition (edge-operation contract §10.3:
 * stored zone revisions + SHADOW risk policy -> bundle with digest ->
 * PREPARE/APPLY -> installed rule count display).
 *
 * Reads ONLY from [MissionStore] (zone revisions, an optional base
 * snapshot, the site) and never mutates it: revisions are selected and
 * pinned, never overwritten. The produced object carries `origin=RC_LOCAL`
 * and `created_offline=true`, validates against
 * `contracts/schemas/mission-bundle.v1.schema.json` (exact key sets —
 * `additionalProperties: false` — required fields, enum values), and its
 * digests follow `contracts/README.md` (RFC 8785 JCS SHA-256 excluding the
 * digest field, via [CanonicalJson]).
 *
 * Honesty rules, all tested:
 * - the default rule set is ZONE_INTRUSION over the designated work-zone
 *   revisions in SHADOW mode. `mode=ACTIVE` is REFUSED (it throws): ACTIVE
 *   installs are rejected edge-side and must not be producible here.
 * - a PERSON_EQUIPMENT_PROXIMITY rule exists only when the caller supplies
 *   a threshold; zero zones means zero rules (the honest empty result),
 *   never an exception and never an invented rule.
 * - a zone revision without usable geometry is excluded with a reported
 *   reason — a polygon is never fabricated.
 * - ids are deterministic `uuid5` derivations over stable parts
 *   ([Mop.NAMESPACE] family, like the message/session ids), so rebuilding
 *   from the same stored facts yields the same ids.
 */
object MissionBundleBuilder {
    const val ORIGIN = "RC_LOCAL"

    private val ZONE_RULE_KINDS = setOf(
        "PERSON_ENTRY", "EQUIPMENT_ENTRY", "PPE_REQUIRED", "RESTRICTED_AREA",
    )
    private val GEOMETRY_LEVELS = setOf(
        "PIXEL_ONLY", "TIME_UNVERIFIED", "GEO_ESTIMATE", "METRIC_VERIFIED",
    )
    private val RULE_MODES = setOf("SHADOW", "OFF")

    fun build(store: MissionStore, request: LocalBundleRequest): LocalBundleResult {
        require(request.siteId.isNotBlank()) { "site_id must not be blank" }
        require(request.flightId.isNotBlank()) { "flight_id must not be blank" }
        requireUuid(request.siteId, "site_id")
        requireUuid(request.flightId, "flight_id")
        require(request.createdAtUtc.isNotBlank()) { "created_at_utc must not be blank" }
        require(request.ruleMode != "ACTIVE") {
            "refused: mode=ACTIVE is not producible RC-side " +
                "(the Edge refuses ACTIVE installs; activation is a separate gate)"
        }
        require(request.ruleMode in RULE_MODES) {
            "unknown rule mode '${request.ruleMode}' (allowed: $RULE_MODES)"
        }
        require(request.requiredGeometryLevel in GEOMETRY_LEVELS) {
            "unknown required_geometry_level '${request.requiredGeometryLevel}' " +
                "(allowed: $GEOMETRY_LEVELS)"
        }
        request.proximityThresholdM?.let {
            require(it > 0.0) { "proximity threshold must be positive, was $it" }
        }
        require(request.hysteresisM >= 0.0) { "hysteresis_m must be >= 0" }
        require(request.enterDurationMs >= 0L) { "enter_duration_ms must be >= 0" }
        require(request.exitDurationMs >= 0L) { "exit_duration_ms must be >= 0" }
        request.createdBy?.let {
            require(it.isNotBlank()) { "created_by must not be blank" }
        }

        // -- 1. select revisions (read-only; the store is never written) ----
        val excluded = mutableListOf<ExcludedZoneRevision>()
        val candidates = selectCandidates(store, request, excluded)

        // -- 2. validate + pin (copies; stored nodes are never mutated) ------
        val included = mutableListOf<ObjectNode>()
        val includedRevisionIds = mutableListOf<String>()
        for (candidate in candidates) {
            val revisionId = candidate.get("revision_id")?.takeIf { it.isTextual }?.asText()
                ?: continue // already reported as excluded during selection
            val problem = revisionProblem(request.siteId, candidate)
            if (problem != null) {
                excluded.add(ExcludedZoneRevision(revisionId, problem))
                continue
            }
            included.add(candidate)
            includedRevisionIds.add(revisionId)
        }

        // -- 3. identity (explicit > base snapshot > deterministic uuid5) ----
        val base = request.baseMissionSnapshot
        val missionRevisionId = request.missionRevisionId
            ?: base?.get("mission_revision_id")?.takeIf { it.isTextual }?.asText()
            ?: Uuid5.of(
                Mop.NAMESPACE,
                "local-mission-rev|${request.siteId}|${includedRevisionIds.sorted().joinToString(",")}",
            ).toString()
        requireUuid(missionRevisionId, "mission_revision_id")
        val missionId = request.missionId
            ?: base?.get("mission_id")?.takeIf { it.isTextual }?.asText()
            ?: Uuid5.of(
                Mop.NAMESPACE, "local-mission|${request.siteId}|$missionRevisionId",
            ).toString()
        requireUuid(missionId, "mission_id")
        val orgId = request.orgId
            ?: base?.get("org_id")?.takeIf { it.isTextual }?.asText()
            ?: store.site(request.siteId)?.get("org_id")
                ?.takeIf { it.isTextual && isUuid(it.asText()) }?.asText()
            ?: throw IllegalArgumentException(
                "org_id unknown for site ${request.siteId}: pass orgId explicitly " +
                    "or supply a base snapshot (never invented)",
            )
        requireUuid(orgId, "org_id")

        // -- 4. snapshot ----------------------------------------------------
        val snapshot = Mop.json.createObjectNode()
        snapshot.put("schema_version", 1)
        snapshot.put("mission_id", missionId)
        snapshot.put("flight_id", request.flightId)
        snapshot.put("org_id", orgId)
        snapshot.put("site_id", request.siteId)
        snapshot.put("mission_revision_id", missionRevisionId)
        snapshot.put("origin", ORIGIN)
        snapshot.put("created_at_utc", request.createdAtUtc)
        val zones = snapshot.putArray("zones")
        for (revision in included) {
            val geometry = revision.get("geometry_wgs84").deepCopy<ObjectNode>()
            val zone = zones.addObject()
            zone.put("zone_id", revision.get("zone_id").asText())
            zone.put("revision_id", revision.get("revision_id").asText())
            zone.put("rule_kind", revision.get("rule_kind").asText())
            zone.set<JsonNode>("geometry_wgs84", geometry)
            // JCS digest of the pinned geometry (stable, no invention).
            zone.put("zone_sha256", CanonicalJson.digestSha256(geometry))
            zone.put("enabled", true)
        }
        val profiles = snapshot.putArray("model_profiles")
        for (profile in resolveProfiles(request, base)) {
            val entry = profiles.addObject()
            entry.put("model_id", profile.modelId)
            entry.put("model_digest", profile.modelDigest)
            entry.put("enabled", profile.enabled)
        }
        base?.get("recording_policy")?.takeIf { it.isObject }?.let {
            snapshot.set<JsonNode>("recording_policy", it.deepCopy())
        } ?: run {
            val recording = snapshot.putObject("recording_policy")
            recording.put("rc_proxy", true)
        }
        snapshot.put(
            "content_sha256",
            CanonicalJson.digestExcluding(snapshot, "content_sha256"),
        )

        // -- 5. local SHADOW risk policy ------------------------------------
        val zoneKey = includedRevisionIds.sorted().joinToString(",")
        val ruleKey = buildRuleKey(request, includedRevisionIds.isNotEmpty())
        val policyRevisionId = Uuid5.of(
            Mop.NAMESPACE,
            "local-policy-rev|${request.siteId}|$missionRevisionId|$ruleKey|$zoneKey",
        ).toString()
        val policyId = Uuid5.of(
            Mop.NAMESPACE, "local-policy|${request.siteId}|$missionRevisionId",
        ).toString()
        val policy = Mop.json.createObjectNode()
        policy.put("schema_version", 1)
        policy.put("policy_id", policyId)
        policy.put("revision_id", policyRevisionId)
        policy.put("site_id", request.siteId)
        val rules = policy.putArray("rules")
        if (included.isNotEmpty()) {
            addZoneRule(rules, request, includedRevisionIds)
            request.proximityThresholdM?.let {
                addProximityRule(rules, request, includedRevisionIds, it)
            }
        }
        // Zero zones -> zero rules: the honest empty result (contract
        // §10.2: the RC shows "위험 규칙 0건", not a fabricated rule).
        policy.put("created_offline", true)
        policy.put(
            "content_sha256",
            CanonicalJson.digestExcluding(policy, "content_sha256"),
        )

        // -- 6. bundle -------------------------------------------------------
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put(
            "bundle_id",
            Uuid5.of(
                Mop.NAMESPACE,
                "local-bundle|${request.siteId}|$missionRevisionId|" +
                    "$policyRevisionId|${request.createdAtUtc}",
            ).toString(),
        )
        bundle.put("site_id", request.siteId)
        bundle.put("created_at_utc", request.createdAtUtc)
        bundle.put("origin", ORIGIN)
        if (request.createdBy == null) bundle.putNull("created_by")
        else bundle.put("created_by", request.createdBy)
        bundle.set<JsonNode>("mission_snapshot", snapshot)
        bundle.set<JsonNode>("risk_policy", policy)
        bundle.putArray("calibration_refs")
        bundle.putArray("ground_model_refs")
        bundle.put(
            "bundle_sha256",
            CanonicalJson.digestExcluding(bundle, "bundle_sha256"),
        )

        return LocalBundleResult(
            bundle = bundle,
            zoneRevisionIds = includedRevisionIds.toList(),
            ruleCount = rules.size(),
            excluded = excluded.toList(),
        )
    }

    // -- selection ----------------------------------------------------------

    private fun selectCandidates(
        store: MissionStore,
        request: LocalBundleRequest,
        excluded: MutableList<ExcludedZoneRevision>,
    ): List<ObjectNode> {
        val ids = request.zoneRevisionIds
        if (ids != null) {
            val out = mutableListOf<ObjectNode>()
            val seen = linkedSetOf<String>()
            for (id in ids) {
                if (!seen.add(id)) {
                    excluded.add(ExcludedZoneRevision(id, "duplicate selection"))
                    continue
                }
                val revision = store.zoneRevision(id)
                if (revision == null) {
                    excluded.add(ExcludedZoneRevision(id, "unknown zone revision id"))
                    continue
                }
                out.add(revision)
            }
            return out
        }
        // Latest stored revision per zone (insertion order; superseded
        // revisions are reported, not silently dropped).
        val latest = linkedMapOf<String, ObjectNode>()
        val superseded = mutableListOf<ObjectNode>()
        for (revision in store.zoneRevisions(request.siteId)) {
            val zoneId = revision.get("zone_id")?.takeIf { it.isTextual }?.asText() ?: continue
            latest.put(zoneId, revision)?.let { superseded.add(it) }
        }
        for (old in superseded) {
            old.get("revision_id")?.takeIf { it.isTextual }?.asText()?.let {
                excluded.add(ExcludedZoneRevision(it, "superseded by a newer stored revision"))
            }
        }
        return latest.values.toList()
    }

    /** Non-null when the revision must be excluded, with the reason. */
    private fun revisionProblem(siteId: String, revision: ObjectNode): String? {
        val revisionId = revision.get("revision_id")?.takeIf { it.isTextual }?.asText()
            ?: return "revision without a revision_id"
        val zoneId = revision.get("zone_id")?.takeIf { it.isTextual }?.asText()
        if (zoneId == null || !isUuid(zoneId)) return "zone_id missing or not a uuid"
        if (!isUuid(revisionId)) return "revision_id not a uuid"
        val revisionSite = revision.get("site_id")?.takeIf { it.isTextual }?.asText()
        if (revisionSite != siteId) {
            return "revision belongs to site $revisionSite, not $siteId"
        }
        val ruleKind = revision.get("rule_kind")?.takeIf { it.isTextual }?.asText()
        if (ruleKind == null || ruleKind !in ZONE_RULE_KINDS) {
            return "unknown rule_kind '${ruleKind ?: "missing"}'"
        }
        return geometryProblem(revision.get("geometry_wgs84"))
    }

    private fun geometryProblem(geometry: JsonNode?): String? {
        if (geometry == null || !geometry.isObject) return "missing geometry_wgs84"
        if (geometry.get("type")?.asText() != "Polygon") {
            return "geometry is not a Polygon (never fabricated)"
        }
        val rings = geometry.get("coordinates")
        if (rings == null || !rings.isArray || rings.size() == 0) {
            return "polygon has no rings"
        }
        for (ring in rings) {
            if (!ring.isArray || ring.size() < 4) {
                return "ring has fewer than 4 positions"
            }
            for (position in ring) {
                if (!position.isArray || position.size() != 2) {
                    return "position is not a lon/lat pair"
                }
                val lon = position.get(0)
                val lat = position.get(1)
                if (lon == null || !lon.isNumber || lat == null || !lat.isNumber) {
                    return "position is not numeric"
                }
                if (lon.asDouble() !in -180.0..180.0 || lat.asDouble() !in -90.0..90.0) {
                    return "position out of WGS84 range"
                }
            }
            val first = ring.get(0)
            val last = ring.get(ring.size() - 1)
            if (first.get(0).asDouble() != last.get(0).asDouble() ||
                first.get(1).asDouble() != last.get(1).asDouble()
            ) {
                return "ring is not closed"
            }
        }
        return null
    }

    private fun resolveProfiles(
        request: LocalBundleRequest,
        base: ObjectNode?,
    ): List<LocalModelProfile> {
        request.modelProfiles?.let { return it }
        val baseProfiles = base?.get("model_profiles")?.takeIf { it.isArray }
        if (baseProfiles != null) {
            return baseProfiles.map { node ->
                LocalModelProfile(
                    modelId = node.get("model_id")?.asText() ?: "",
                    modelDigest = node.get("model_digest")?.asText() ?: "",
                    enabled = node.get("enabled")?.asBoolean() ?: true,
                )
            }
        }
        throw IllegalArgumentException(
            "no detector-profile basis: pass modelProfiles explicitly or supply " +
                "a base snapshot (profiles are never invented)",
        )
    }

    private fun buildRuleKey(request: LocalBundleRequest, hasZones: Boolean): String {
        if (!hasZones) return "no-rules"
        val base = "zone-intrusion|${request.ruleMode}|${request.requiredGeometryLevel}"
        val proximity = request.proximityThresholdM?.let { "|proximity:$it" } ?: ""
        return base + proximity
    }

    private fun addZoneRule(
        rules: com.fasterxml.jackson.databind.node.ArrayNode,
        request: LocalBundleRequest,
        revisionIds: List<String>,
    ) {
        val rule = rules.addObject()
        rule.put("rule_id", "zone-intrusion-${request.ruleMode.lowercase()}")
        rule.put("kind", "ZONE_INTRUSION")
        rule.put("mode", request.ruleMode)
        rule.putNull("threshold_m")
        rule.put("hysteresis_m", request.hysteresisM)
        rule.put("enter_duration_ms", request.enterDurationMs)
        rule.put("exit_duration_ms", request.exitDurationMs)
        rule.put("required_geometry_level", request.requiredGeometryLevel)
        val equipment = rule.putArray("enabled_equipment_classes")
        request.enabledEquipmentClasses.forEach { equipment.add(it) }
        val zones = rule.putArray("zone_revision_ids")
        revisionIds.forEach { zones.add(it) }
    }

    private fun addProximityRule(
        rules: com.fasterxml.jackson.databind.node.ArrayNode,
        request: LocalBundleRequest,
        revisionIds: List<String>,
        thresholdM: Double,
    ) {
        val rule = rules.addObject()
        rule.put("rule_id", "person-equipment-proximity-${request.ruleMode.lowercase()}")
        rule.put("kind", "PERSON_EQUIPMENT_PROXIMITY")
        rule.put("mode", request.ruleMode)
        rule.put("threshold_m", thresholdM)
        rule.put("hysteresis_m", request.hysteresisM)
        rule.put("enter_duration_ms", request.enterDurationMs)
        rule.put("exit_duration_ms", request.exitDurationMs)
        rule.put("required_geometry_level", request.requiredGeometryLevel)
        val equipment = rule.putArray("enabled_equipment_classes")
        request.enabledEquipmentClasses.forEach { equipment.add(it) }
        val zones = rule.putArray("zone_revision_ids")
        revisionIds.forEach { zones.add(it) }
    }

    private fun isUuid(value: String): Boolean = try {
        java.util.UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun requireUuid(value: String, field: String) {
        require(isUuid(value)) { "$field must be a uuid, was '$value'" }
    }
}
