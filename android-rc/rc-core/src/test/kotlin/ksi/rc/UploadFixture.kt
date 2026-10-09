package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Deterministic test ids (valid `format: uuid` / 64-hex strings). */
object Fixture {
    fun uuid(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)
    fun hex(ch: Char): String = ch.toString().repeat(64)
}

/** Fixed-sequence receipt clock for deterministic manifest times. */
class ClockQueue(private val values: List<String>) : () -> String {
    private var index = 0
    override fun invoke(): String {
        val value = values[minOf(index, values.size - 1)]
        index += 1
        return value
    }
}

fun uploadSpec(
    flightId: String = Fixture.uuid(1),
    siteId: String = Fixture.uuid(2),
    rcDeviceId: String = Fixture.uuid(3),
    missionId: String = Fixture.uuid(4),
    missionRevisionId: String = Fixture.uuid(5),
): FlightUploadSpec = FlightUploadSpec(
    flightId = flightId,
    siteId = siteId,
    rcDeviceId = rcDeviceId,
    missionId = missionId,
    missionRevisionId = missionRevisionId,
    missionSha256 = Fixture.hex('b'),
    bundleSha256 = Fixture.hex('c'),
)

/** Minimal digest-valid bundle for MissionController/FakeEdge flows. */
fun testBundle(bundleId: String = Fixture.uuid(6)): ObjectNode {
    val bundle = Mop.json.createObjectNode()
    bundle.put("schema_version", 1)
    bundle.put("bundle_id", bundleId)
    bundle.put("site_id", Fixture.uuid(2))
    bundle.put("bundle_sha256", CanonicalJson.digestExcluding(bundle, "bundle_sha256"))
    return bundle
}

/** Schema-valid `observation.v1` (the real-Core E2E validates this). */
fun observationRecord(
    flightId: String,
    bootId: String,
    missionRevisionId: String,
    observationId: String,
    seq: Long,
    ts: Long,
    lens: String = "wide",
): ObjectNode {
    val observation = Mop.json.createObjectNode()
    observation.put("schema_version", 1)
    observation.put("observation_id", observationId)
    observation.put("flight_id", flightId)
    observation.put("mission_revision_id", missionRevisionId)
    observation.put("edge_boot_id", bootId)
    observation.put("source_kind", "PSDK_EDGE")
    observation.put("edge_frame_seq", seq)
    observation.put("edge_frame_id", "frame-$seq")
    observation.put("lens_id", lens)
    observation.put("frame_received_monotonic_ns", ts)
    observation.put("model_digest", Fixture.hex('a'))
    observation.put("sync_quality", "VALID")
    observation.put("geometry_status", "UNVERIFIED")
    observation.set<JsonNode>("telemetry_refs", Mop.json.createArrayNode())
    val detections = Mop.json.createArrayNode()
    val detection = detections.addObject()
    detection.put("detection_id", Fixture.uuid(9000 + seq.toInt()))
    detection.put("class_id", "person")
    detection.put("score", 0.9)
    detection.set<JsonNode>(
        "bbox_xyxy_px", Mop.json.createArrayNode().add(1).add(2).add(3).add(4),
    )
    detection.putNull("track_id")
    detection.putNull("position_wgs84")
    observation.set<JsonNode>("detections", detections)
    observation.set<JsonNode>("quality_reasons", Mop.json.createArrayNode())
    observation.set<JsonNode>("zone_revision_ids", Mop.json.createArrayNode())
    return observation
}

/** Schema-valid `detection-event.v1` (SHADOW, honest quality fields). */
fun detectionEventRecord(
    flightId: String,
    eventId: String,
    observationId: String,
    episodeId: String,
    detectedAtNs: Long,
    artifactIds: List<String>,
): ObjectNode {
    val event = Mop.json.createObjectNode()
    event.put("schema_version", 1)
    event.put("event_id", eventId)
    event.put("flight_id", flightId)
    event.put("observation_id", observationId)
    event.put("episode_id", episodeId)
    event.put("event_type", "PROXIMITY_CANDIDATE")
    event.put("mode", "SHADOW")
    event.put("detected_at_edge_monotonic_ns", detectedAtNs)
    event.putNull("zone_revision_id")
    event.put("policy_revision_id", Fixture.uuid(7000))
    val evidenceIds = event.putArray("evidence_artifact_ids")
    artifactIds.forEach { evidenceIds.add(it) }
    val output = event.putObject("initial_model_output")
    output.put("rule_id", "person_proximity")
    output.put("episode", 1)
    output.put("distance_m", 2.5)
    output.put("geometry", "GEO_ESTIMATE")
    output.put("synthetic_replay_geometry", false)
    event.put("observation_quality", "VALID")
    event.put("mission_snapshot_hash", Fixture.hex('b'))
    return event
}

/** Schema-valid `flight-summary.v1`. */
fun flightSummaryRecord(
    flightId: String,
    bootId: String,
    sessionId: String,
    summaryId: String,
    startedNs: Long,
    endedNs: Long,
): ObjectNode {
    val summary = Mop.json.createObjectNode()
    summary.put("schema_version", 1)
    summary.put("summary_id", summaryId)
    summary.put("flight_id", flightId)
    summary.put("edge_boot_id", bootId)
    summary.put("session_id", sessionId)
    summary.put("started_at_edge_monotonic_ns", startedNs)
    summary.put("ended_at_edge_monotonic_ns", endedNs)
    summary.put("ai_uptime_ratio", 1.0)
    summary.put("covered_frames", 2)
    summary.put("skipped_frames", 0)
    summary.set<JsonNode>("gaps", Mop.json.createArrayNode())
    summary.putNull("min_verified_person_equipment_distance_m")
    summary.put("person_count_max", 1)
    summary.put("equipment_count_max", 0)
    summary.set<JsonNode>(
        "quality_reasons", Mop.json.createArrayNode().add("SYNTHETIC_TEST_INPUT"),
    )
    summary.put("created_at_edge_monotonic_ns", endedNs + 50)
    return summary
}

/** ARTIFACT_ANNOUNCE payload (also the manifest artifact source record). */
fun artifactAnnounceRecord(
    flightId: String,
    bootId: String,
    artifactId: String,
    bytes: ByteArray,
    sourceKind: String = "EDGE_SNAPSHOT",
    mediaType: String = "image/jpeg",
    required: Boolean = true,
): ObjectNode {
    val announce = Mop.json.createObjectNode()
    announce.put("schema_version", 1)
    announce.put("artifact_id", artifactId)
    announce.put("flight_id", flightId)
    announce.put("edge_boot_id", bootId)
    announce.put("source_kind", sourceKind)
    announce.put("sha256", sha256Hex(bytes))
    announce.put("size_bytes", bytes.size.toLong())
    announce.put("media_type", mediaType)
    announce.put("required", required)
    announce.putNull("segment_index")
    return announce
}

/** Edge -> RC envelope for data/control fixtures. */
fun edgeEnvelope(
    type: String,
    flightId: String,
    bootId: String,
    channel: String,
    seq: Int?,
    payload: ObjectNode,
    tag: String,
    sentMonotonicNs: Long = 1L,
): ObjectNode = Mop.makeEnvelope(
    msgId = Mop.newMsgId("edge", flightId, bootId, tag, type),
    channel = channel,
    type = type,
    flightId = flightId,
    edgeBootId = bootId,
    seq = seq,
    ackOf = null,
    payload = payload,
    sentMonotonicNs = sentMonotonicNs,
)

/** Feed one envelope and require full durability (Stored). */
fun feedEnvelope(endpoint: MopEndpoint, env: ObjectNode) {
    val outcome = endpoint.onRawEnvelope(env.toString())
    check(outcome is Inbound.Stored) { "envelope not stored: $outcome" }
}

private fun payloadOf(field: String, node: ObjectNode): ObjectNode =
    Mop.json.createObjectNode().apply { set<com.fasterxml.jackson.databind.JsonNode>(field, node) }

/**
 * Standard uploader fixture: 2 observations, 1 event, 1 flight summary,
 * 1 artifact (announce + chunks) and one Edge-declared unrecoverable gap
 * (EVENT_RELIABLE seq 9). Returns the endpoint so tests can feed more
 * evidence later. `writeChunks = false` announces the artifact without
 * bytes (incomplete-artifact scenarios).
 */
fun seedFlightEvidence(
    store: UploadStore,
    spec: FlightUploadSpec,
    bootId: String,
    artifactId: String,
    artifactBytes: ByteArray,
    chunkSize: Int = artifactBytes.size,
    writeChunks: Boolean = true,
): MopEndpoint {
    val endpoint = MopEndpoint(spec.flightId, store, { 0L }, EvidenceIngest(store))
    val observationId = Fixture.uuid(1001)
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "OBSERVATION", spec.flightId, bootId, "EVENT_RELIABLE", 1,
            payloadOf(
                "observation",
                observationRecord(
                    spec.flightId, bootId, spec.missionRevisionId,
                    observationId, 1, 1000,
                ),
            ),
            "obs1",
        ),
    )
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "OBSERVATION", spec.flightId, bootId, "EVENT_RELIABLE", 2,
            payloadOf(
                "observation",
                observationRecord(
                    spec.flightId, bootId, spec.missionRevisionId,
                    Fixture.uuid(1002), 2, 2000,
                ),
            ),
            "obs2",
        ),
    )
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "DETECTION_EVENT", spec.flightId, bootId, "EVENT_RELIABLE", 3,
            payloadOf(
                "event",
                detectionEventRecord(
                    spec.flightId, Fixture.uuid(3001), observationId,
                    Fixture.uuid(4001), 2500, listOf(artifactId),
                ),
            ),
            "event1",
        ),
    )
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "FLIGHT_SUMMARY", spec.flightId, bootId, "EVENT_RELIABLE", 4,
            payloadOf(
                "summary",
                flightSummaryRecord(
                    spec.flightId, bootId,
                    Uuid5.of(
                        Mop.NAMESPACE, "session|${spec.flightId}|$bootId",
                    ).toString(),
                    Fixture.uuid(5001), 1000, 2000,
                ),
            ),
            "summary1",
        ),
    )
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "ARTIFACT_ANNOUNCE", spec.flightId, bootId, "EVIDENCE_TRANSFER", 1,
            artifactAnnounceRecord(spec.flightId, bootId, artifactId, artifactBytes),
            "announce1",
        ),
    )
    if (writeChunks) {
        var seq = 2
        var offset = 0
        while (offset < artifactBytes.size) {
            val end = minOf(offset + chunkSize, artifactBytes.size)
            val chunk = Mop.json.createObjectNode()
            chunk.put("artifact_id", artifactId)
            chunk.put("offset", offset.toLong())
            chunk.put(
                "data_b64",
                java.util.Base64.getEncoder()
                    .encodeToString(artifactBytes.copyOfRange(offset, end)),
            )
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "ARTIFACT_CHUNK", spec.flightId, bootId,
                    "EVIDENCE_TRANSFER", seq, chunk, "chunk-$seq",
                ),
            )
            seq += 1
            offset = end
        }
    }
    val heartbeat = Mop.json.createObjectNode()
    val group = heartbeat.putArray("unrecoverable_gaps").addObject()
    group.put("channel", "EVENT_RELIABLE")
    group.putArray("seqs").add(9)
    feedEnvelope(
        endpoint,
        edgeEnvelope(
            "HEARTBEAT", spec.flightId, bootId, "HEARTBEAT", null, heartbeat, "hb1",
        ),
    )
    return endpoint
}
