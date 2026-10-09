package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * A5 mission fixtures: schema-shaped zone revision / snapshot / policy /
 * bundle records (the shapes the Core serves; digests via JCS so the real
 * server's validators accept them).
 */
object MissionFixture {
    fun zoneRevision(
        siteId: String,
        zoneId: String,
        revisionId: String,
        baseRevisionId: String?,
        lon: Double = 127.0,
    ): ObjectNode {
        val revision = Mop.json.createObjectNode()
        revision.put("zone_id", zoneId)
        revision.put("revision_id", revisionId)
        if (baseRevisionId == null) revision.putNull("base_revision_id")
        else revision.put("base_revision_id", baseRevisionId)
        revision.put("site_id", siteId)
        val geometry = revision.putObject("geometry_wgs84")
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
        revision.put("rule_kind", "PERSON_ENTRY")
        return revision
    }

    fun zoneSha(revision: ObjectNode): String =
        sha256Hex(
            revision.get("geometry_wgs84").toString().toByteArray(Charsets.UTF_8),
        )

    fun snapshot(
        siteId: String,
        missionId: String,
        revisionId: String,
        flightId: String,
        zoneRevisions: List<ObjectNode>,
        createdAt: String = "2026-10-10T00:00:00Z",
    ): ObjectNode {
        val snapshot = Mop.json.createObjectNode()
        snapshot.put("schema_version", 1)
        snapshot.put("mission_id", missionId)
        snapshot.put("flight_id", flightId)
        snapshot.put("org_id", Fixture.uuid(900))
        snapshot.put("site_id", siteId)
        snapshot.put("mission_revision_id", revisionId)
        snapshot.put("origin", "SERVER")
        snapshot.put("created_at_utc", createdAt)
        val zones = snapshot.putArray("zones")
        for (revision in zoneRevisions) {
            val zone = zones.addObject()
            zone.put("zone_id", revision.get("zone_id").asText())
            zone.put("revision_id", revision.get("revision_id").asText())
            zone.put("rule_kind", revision.get("rule_kind").asText())
            zone.set<JsonNode>(
                "geometry_wgs84", revision.get("geometry_wgs84").deepCopy(),
            )
            zone.put("zone_sha256", zoneSha(revision))
            zone.put("enabled", true)
        }
        val profiles = snapshot.putArray("model_profiles")
        val profile = profiles.addObject()
        profile.put("model_id", "ksi-person-detector-dfine-s-coco")
        profile.put("model_digest", Fixture.hex('a'))
        profile.put("enabled", true)
        val recording = snapshot.putObject("recording_policy")
        recording.put("rc_proxy", true)
        recording.put("target_video_bitrate_bps", 1_500_000)
        recording.put("target_fps", 15)
        recording.put("segment_seconds", 300)
        snapshot.put(
            "content_sha256",
            CanonicalJson.digestExcluding(snapshot, "content_sha256"),
        )
        return snapshot
    }

    fun policy(siteId: String, zoneRevisionIds: List<String>): ObjectNode {
        val policy = Mop.json.createObjectNode()
        policy.put("schema_version", 1)
        policy.put("policy_id", Fixture.uuid(910))
        policy.put("revision_id", Fixture.uuid(911))
        policy.put("site_id", siteId)
        val rule = policy.putArray("rules").addObject()
        rule.put("rule_id", "excavator-proximity-shadow")
        rule.put("kind", "PERSON_EQUIPMENT_PROXIMITY")
        rule.put("mode", "SHADOW")
        rule.put("threshold_m", 4.0)
        rule.put("hysteresis_m", 1.0)
        rule.put("enter_duration_ms", 1200)
        rule.put("exit_duration_ms", 1500)
        rule.put("required_geometry_level", "METRIC_VERIFIED")
        rule.putArray("enabled_equipment_classes").add("excavator")
        val zones = rule.putArray("zone_revision_ids")
        zoneRevisionIds.forEach { zones.add(it) }
        policy.put("created_offline", false)
        policy.put(
            "content_sha256",
            CanonicalJson.digestExcluding(policy, "content_sha256"),
        )
        return policy
    }

    fun bundle(
        siteId: String,
        snapshot: ObjectNode,
        policy: ObjectNode,
        bundleId: String,
        createdAt: String = "2026-10-10T00:01:00Z",
    ): ObjectNode {
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", bundleId)
        bundle.put("site_id", siteId)
        bundle.put("created_at_utc", createdAt)
        bundle.put("origin", "SERVER")
        bundle.put("created_by", "web-test")
        bundle.set<JsonNode>("mission_snapshot", snapshot.deepCopy())
        bundle.set<JsonNode>("risk_policy", policy.deepCopy())
        bundle.putArray("calibration_refs")
        bundle.putArray("ground_model_refs")
        bundle.put(
            "bundle_sha256",
            CanonicalJson.digestExcluding(bundle, "bundle_sha256"),
        )
        return bundle
    }
}

/** Raw fake-core POST helper (FakeCore mirrors the server's write routes). */
fun postToFake(fake: FakeCore, path: String, node: ObjectNode): ObjectNode {
    val response = fake.request(
        "POST", path, emptyMap(), Mop.json.writeValueAsBytes(node),
    )
    check(response.status == 200) {
        "POST $path -> ${response.status}: ${response.bodyText}"
    }
    return Mop.json.readTree(response.body) as ObjectNode
}
