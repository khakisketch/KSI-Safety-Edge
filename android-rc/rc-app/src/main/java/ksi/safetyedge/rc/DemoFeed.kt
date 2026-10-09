package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64
import ksi.rc.CanonicalJson
import ksi.rc.Mop

/**
 * SIMULATED Edge feed for bench development (A4). Builds REAL MOP envelopes
 * carrying schema-shaped observation/event/artifact payloads and delivers
 * them through the real [ksi.rc.MopEndpoint] ingest path
 * (commit-before-ACK, dedup, coverage+hash artifact gates). It is NOT Edge
 * output: every screen labels it SIMULATED, and this object never ships
 * data to the server — only the upload path does, from the store.
 *
 * Retired the moment the MSDK link delivers real Edge bytes (H1/A9).
 *
 * HONESTY NOTE (A5 fix): these rows are written ONLY into the isolated
 * demo store (`rc-demo-store`) by [DemoSession], whose Core configuration
 * is null — the demo bench can never upload and never mixes with
 * operational evidence. Each row still carries SIMULATED_FEED / DEGRADED
 * / UNVERIFIED / synthetic_replay_geometry markers so even a bench export
 * is self-describing.
 */
class DemoFeed(
    private val flightId: String,
    private val bootId: String,
    private val missionRevisionId: String,
) {
    private var seq = 0
    private var clock = 1_000_000L

    private fun nextSeq(): Int {
        seq += 1
        return seq
    }

    private fun envelope(type: String, channel: String, seq: Int?, payload: ObjectNode): ByteArray {
        clock += 1_000_000L
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge", flightId, bootId, "demo-$seq-$type"),
            channel = channel,
            type = type,
            flightId = flightId,
            edgeBootId = bootId,
            seq = seq,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = clock,
        )
        return Mop.json.writeValueAsBytes(env)
    }

    private fun observationPayload(obsId: String, frameSeq: Long, ts: Long): ObjectNode {
        val payload = Mop.json.createObjectNode()
        val obs = payload.putObject("observation")
        obs.put("schema_version", 1)
        obs.put("observation_id", obsId)
        obs.put("flight_id", flightId)
        obs.put("mission_revision_id", missionRevisionId)
        obs.put("edge_boot_id", bootId)
        obs.put("source_kind", "PSDK_EDGE")
        obs.put("edge_frame_seq", frameSeq)
        obs.put("edge_frame_id", "demo-frame-$frameSeq")
        obs.put("lens_id", "wide")
        obs.put("frame_received_monotonic_ns", ts)
        obs.put("model_digest", "d".repeat(64))
        obs.put("sync_quality", "DEGRADED")
        obs.put("geometry_status", "UNVERIFIED")
        obs.putArray("telemetry_refs")
        val detections = obs.putArray("detections")
        val det = detections.addObject()
        det.put("detection_id", Mop.newMsgId("demo", obsId, "det"))
        det.put("class_id", "person")
        det.put("score", 0.81)
        det.putArray("bbox_xyxy_px").add(10).add(20).add(60).add(120)
        det.putNull("track_id")
        det.putNull("position_wgs84")
        obs.putArray("quality_reasons").add("SIMULATED_FEED")
        obs.putArray("zone_revision_ids")
        return payload
    }

    /** One simulated observation tick (2 frames + 1 event + 1 artifact). */
    fun tick(artifactBytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val obsId1 = Mop.newMsgId("demo", flightId, "obs", seq.toString(), "a")
        val obsId2 = Mop.newMsgId("demo", flightId, "obs", seq.toString(), "b")
        out.add(envelope("OBSERVATION", "EVENT_RELIABLE", nextSeq(),
            observationPayload(obsId1, seq * 2L, clock)))
        out.add(envelope("OBSERVATION", "EVENT_RELIABLE", nextSeq(),
            observationPayload(obsId2, seq * 2L + 1, clock + 500_000L)))
        val artifactId = Mop.newMsgId("demo", flightId, "artifact", seq.toString())
        val eventPayload = Mop.json.createObjectNode()
        val event = eventPayload.putObject("event")
        event.put("schema_version", 1)
        event.put("event_id", Mop.newMsgId("demo", flightId, "event", seq.toString()))
        event.put("flight_id", flightId)
        event.put("observation_id", obsId1)
        event.put("episode_id", Mop.newMsgId("demo", flightId, "episode", seq.toString()))
        event.put("event_type", "SPATIAL_CANDIDATE")
        event.put("mode", "SHADOW")
        event.put("detected_at_edge_monotonic_ns", clock)
        event.putNull("zone_revision_id")
        event.putNull("policy_revision_id")
        event.putArray("evidence_artifact_ids").add(artifactId)
        val output = event.putObject("initial_model_output")
        output.put("rule_id", "demo_rule")
        output.put("geometry", "GEO_ESTIMATE")
        output.put("synthetic_replay_geometry", true)
        event.put("observation_quality", "DEGRADED")
        event.put("mission_snapshot_hash", "b".repeat(64))
        out.add(envelope("DETECTION_EVENT", "EVENT_RELIABLE", nextSeq(), eventPayload))
        val announce = Mop.json.createObjectNode()
        announce.put("schema_version", 1)
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", flightId)
        announce.put("edge_boot_id", bootId)
        announce.put("source_kind", "EDGE_SNAPSHOT")
        announce.put("sha256", sha256(artifactBytes))
        announce.put("size_bytes", artifactBytes.size.toLong())
        announce.put("media_type", "image/jpeg")
        announce.put("required", true)
        announce.putNull("segment_index")
        out.add(envelope("ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", nextSeq(), announce))
        var offset = 0
        while (offset < artifactBytes.size) {
            val end = minOf(offset + 4096, artifactBytes.size)
            val chunk = Mop.json.createObjectNode()
            chunk.put("artifact_id", artifactId)
            chunk.put("offset", offset.toLong())
            chunk.put("data_b64", Base64.getEncoder()
                .encodeToString(artifactBytes.copyOfRange(offset, end)))
            out.add(envelope("ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", nextSeq(), chunk))
            offset = end
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    /** Digest-valid local bundle so Prepare/Apply run without an Edge. */
    companion object {
        fun demoBundle(bundleId: String, siteId: String): ObjectNode {
            val bundle = Mop.json.createObjectNode()
            bundle.put("schema_version", 1)
            bundle.put("bundle_id", bundleId)
            bundle.put("site_id", siteId)
            bundle.put("bundle_sha256", CanonicalJson.digestExcluding(bundle, "bundle_sha256"))
            return bundle
        }
    }
}
