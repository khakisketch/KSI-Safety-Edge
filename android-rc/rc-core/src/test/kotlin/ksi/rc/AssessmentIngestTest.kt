package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Assessment delivery (Unit A closure): sealed assessment JSONs ride the
 * EXISTING generic artifact plane (ARTIFACT_ANNOUNCE + ARTIFACT_CHUNK) —
 * no new MOP type, no contract change.
 *
 * Pins:
 * - ANNOUNCE (media `application/vnd.ksi.assessment+json`) + CHUNKs flow
 *   through the REAL endpoint + [EvidenceIngest] with commit-before-ACK
 *   (a malformed chunk is NOT ACKed, the Edge retries the same msg_id);
 * - [FileRcStore.assessmentsFor] exposes only INTACT blobs (coverage +
 *   size + sha256); an announced-but-incomplete assessment counts as
 *   pending, never as judged;
 * - relations survive an RC restart;
 * - a hypothetical `ASSESSMENT_REPORT` wire type is REJECTED (terminal):
 *   new message semantics need the approved contract first (proposal
 *   options A/B/C), they are not smuggled in here.
 */
class AssessmentIngestTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-assess-test")

    private fun assessmentJson(assessmentId: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema", "ksi.assessment/v1")
        node.put("assessment_id", assessmentId)
        node.put("tick_index", 3L)
        node.put("frame_id", 3)
        node.put("receive_mono_ns", 300000000L)
        node.put("time_grade", "receive_only")
        node.put("flight_id", "flt-1")
        node.put("edge_boot_id", "boot-1")
        node.put("stream_id", "S")
        node.put("lens_id", "wide")
        val model = Mop.json.createObjectNode()
        model.put("model_id", "stub-detector")
        model.put("model_digest", "stub-digest-not-a-model")
        node.set<JsonNode>("model", model)
        node.put("mode", "SHADOW")
        node.put("simulated_input", true)
        val observations = Mop.json.createObjectNode()
        observations.put("detection_status", "OK")
        observations.put("detection_count", 1)
        observations.put("track_count", 1)
        node.set<JsonNode>("observations", observations)
        node.set<JsonNode>("rules", Mop.json.createArrayNode())
        node.put("disposition", "UNDETERMINED")
        val observed = Mop.json.createArrayNode()
        observed.add("fresh_detections_present")
        node.set<JsonNode>("observed_conditions", observed)
        val missing = Mop.json.createArrayNode()
        missing.add("no_applicable_rules")
        node.set<JsonNode>("missing_reasons", missing)
        val outputs = Mop.json.createObjectNode()
        outputs.put("incidents_created", 0)
        outputs.put("alerts_created", 0)
        outputs.put("human_responses", 0)
        outputs.put("camera_commands", 0)
        outputs.put("flight_commands", 0)
        node.set<JsonNode>("outputs", outputs)
        val custody = Mop.json.createObjectNode()
        custody.put("edge_sealed_locally", true)
        custody.put("rc_received", false)
        custody.put("rc_receipt", "unverified")
        custody.put("core_retained", false)
        node.set<JsonNode>("custody", custody)
        return node
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun announce(artifactId: String, bytes: ByteArray): ObjectNode {
        val announce = Mop.json.createObjectNode()
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", "flt-1")
        announce.put("edge_boot_id", "boot-1")
        announce.put("source_kind", ASSESSMENT_SOURCE_KIND)
        announce.put("sha256", sha256Hex(bytes))
        announce.put("size_bytes", bytes.size.toLong())
        announce.put("media_type", ASSESSMENT_MEDIA_TYPE)
        announce.put("required", false)
        announce.put("segment_index", 0)
        return announce
    }

    private var edgeCounter = 0

    private fun edgeEnvelope(
        type: String,
        channel: String,
        seq: Int?,
        payload: ObjectNode,
    ): ObjectNode {
        edgeCounter += 1
        return Mop.makeEnvelope(
            msgId = Mop.newMsgId(
                "edge", "flt-1", "boot-1", edgeCounter.toString(), channel, type,
            ),
            channel = channel,
            type = type,
            flightId = "flt-1",
            edgeBootId = "boot-1",
            seq = seq,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 100L * edgeCounter,
        )
    }

    private fun chunkPayload(artifactId: String, offset: Int, bytes: ByteArray): ObjectNode {
        val chunk = Mop.json.createObjectNode()
        chunk.put("artifact_id", artifactId)
        chunk.put("offset", offset.toLong())
        chunk.put(
            "data_b64",
            java.util.Base64.getEncoder().encodeToString(bytes),
        )
        return chunk
    }

    private fun endpoint(store: FileRcStore): MopEndpoint = MopEndpoint(
        "flt-1", store, { 0L }, EvidenceIngest(store),
    )

    private fun feedAssessment(
        endpoint: MopEndpoint,
        artifactId: String,
        bytes: ByteArray,
        chunkSize: Int = 8,
    ) {
        val announceOutcome = endpoint.onRawEnvelope(
            edgeEnvelope(
                "ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 1,
                announce(artifactId, bytes),
            ).toString(),
        )
        assertTrue(announceOutcome is Inbound.Stored, "announce outcome: $announceOutcome")
        var offset = 0
        var seq = 2
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val outcome = endpoint.onRawEnvelope(
                edgeEnvelope(
                    "ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", seq,
                    chunkPayload(artifactId, offset, bytes.copyOfRange(offset, end)),
                ).toString(),
            )
            assertTrue(outcome is Inbound.Stored, "chunk@$offset outcome: $outcome")
            offset = end
            seq += 1
        }
    }

    @Test
    fun assessment_flows_through_the_endpoint_with_commit_before_ack() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-1"))
        feedAssessment(endpoint, "assess-1", bytes)

        val assessments = store.assessmentsFor("flt-1")
        assertEquals(1, assessments.size)
        assertEquals("assess-1", assessments[0].get("assessment_id").asText())
        assertEquals("UNDETERMINED", assessments[0].get("disposition").asText())
        assertEquals("SHADOW", assessments[0].get("mode").asText())
        assertEquals(0, store.pendingAssessmentCount("flt-1"))
        // The artifact is an ordinary manifest/CORE-upload entry already:
        // assessment delivery reuses the whole receipt chain, no new path.
        assertTrue(store.artifactsFor("flt-1").any {
            it.get("artifact_id").asText() == "assess-1" &&
                it.get("media_type").asText() == ASSESSMENT_MEDIA_TYPE
        })
    }

    @Test
    fun incomplete_assessment_is_pending_never_judged() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-2"))
        val announced = endpoint.onRawEnvelope(
            edgeEnvelope(
                "ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 1,
                announce("assess-2", bytes),
            ).toString(),
        )
        assertTrue(announced is Inbound.Stored)
        // Half the bytes only.
        val half = bytes.copyOfRange(0, bytes.size / 2)
        val chunked = endpoint.onRawEnvelope(
            edgeEnvelope(
                "ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", 2,
                chunkPayload("assess-2", 0, half),
            ).toString(),
        )
        assertTrue(chunked is Inbound.Stored)
        assertEquals(0, store.assessmentsFor("flt-1").size,
            "an incomplete assessment must never read as judged")
        assertEquals(1, store.pendingAssessmentCount("flt-1"))
    }

    @Test
    fun malformed_chunk_is_not_acked() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-3"))
        assertTrue(endpoint.onRawEnvelope(
            edgeEnvelope(
                "ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 1,
                announce("assess-3", bytes),
            ).toString(),
        ) is Inbound.Stored)
        val ackedBefore = endpoint.ackedCount
        val badChunk = Mop.json.createObjectNode()
        badChunk.put("artifact_id", "assess-3")
        badChunk.put("offset", 0)
        badChunk.put("data_b64", "!!!not-base64!!!")
        val outcome = endpoint.onRawEnvelope(
            edgeEnvelope(
                "ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", 2, badChunk,
            ).toString(),
        )
        assertTrue(outcome is Inbound.Failed)
        assertEquals(ackedBefore, endpoint.ackedCount,
            "failed messages stay un-ACKed so the Edge retries the same msg_id")
    }

    @Test
    fun assessments_survive_an_rc_restart() {
        val root = tempRoot()
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-4"))
        run {
            val endpoint = endpoint(FileRcStore(root))
            feedAssessment(endpoint, "assess-4", bytes)
        }
        val restarted = FileRcStore(root)
        val assessments = restarted.assessmentsFor("flt-1")
        assertEquals(1, assessments.size)
        assertEquals("UNDETERMINED", assessments[0].get("disposition").asText())
        assertEquals(0, restarted.pendingAssessmentCount("flt-1"))
        assertEquals(listOf("boot-1"), restarted.bootsFor("flt-1"))
    }

    @Test
    fun conflicting_reannounce_is_rejected_never_silently_kept() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-6"))
        feedAssessment(endpoint, "assess-6", bytes)
        assertEquals(1, store.assessmentsFor("flt-1").size)

        // Same id, different bytes (different digest + size): conflict.
        val conflict = Mop.json.writeValueAsBytes(assessmentJson("assess-6-other"))
        val badAnnounce = announce("assess-6", conflict)
        val ackedBefore = endpoint.ackedCount
        val outcome = endpoint.onRawEnvelope(
            edgeEnvelope("ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 50, badAnnounce).toString(),
        )
        assertTrue(outcome is Inbound.Failed, "conflict outcome: $outcome")
        assertEquals(
            ackedBefore, endpoint.ackedCount,
            "conflicts stay un-ACKed so the Edge retries the same msg_id",
        )
        // The first seal still verifies; the conflict never replaced it.
        val kept = store.assessmentsFor("flt-1")
        assertEquals(1, kept.size)
        assertEquals("assess-6", kept[0].get("assessment_id").asText())
    }

    @Test
    fun identical_reannounce_is_idempotent_and_acked() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val bytes = Mop.json.writeValueAsBytes(assessmentJson("assess-7"))
        feedAssessment(endpoint, "assess-7", bytes)
        val ackedBefore = endpoint.ackedCount
        // Exact same announce retried (ACK loss recovery): accepted, ACKed.
        val outcome = endpoint.onRawEnvelope(
            edgeEnvelope("ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 1, announce("assess-7", bytes)).toString(),
        )
        assertTrue(outcome is Inbound.Stored, "idempotent outcome: $outcome")
        assertEquals(ackedBefore + 1, endpoint.ackedCount)
        assertEquals(1, store.assessmentsFor("flt-1").size)
    }

    @Test
    fun assessment_report_wire_type_is_rejected_without_a_contract() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val payload = Mop.json.createObjectNode()
        payload.set<JsonNode>("assessment", assessmentJson("assess-x"))
        val outcome = endpoint.onRawEnvelope(
            edgeEnvelope("ASSESSMENT_REPORT", "EVENT_RELIABLE", 1, payload).toString(),
        )
        assertTrue(outcome is Inbound.Failed,
            "a new wire type without an approved contract must not be accepted")
        assertEquals(0, store.assessmentsFor("flt-1").size)
    }

    @Test
    fun manifest_withholds_non_ingestible_kinds_but_keeps_the_rest() {
        val store = FileRcStore(tempRoot())
        val endpoint = endpoint(store)
        val assessBytes = Mop.json.writeValueAsBytes(assessmentJson("assess-5"))
        feedAssessment(endpoint, "assess-5", assessBytes)

        // An ingestible snapshot alongside the assessment.
        val snapBytes = ByteArray(16) { it.toByte() }
        val snap = Mop.json.createObjectNode()
        snap.put("artifact_id", "snap-5")
        snap.put("flight_id", "flt-1")
        snap.put("edge_boot_id", "boot-1")
        snap.put("source_kind", "EDGE_SNAPSHOT")
        snap.put("sha256", sha256Hex(snapBytes))
        snap.put("size_bytes", snapBytes.size.toLong())
        snap.put("media_type", "image/jpeg")
        snap.put("required", true)
        snap.put("segment_index", 0)
        assertTrue(endpoint.onRawEnvelope(
            edgeEnvelope("ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", 50, snap).toString(),
        ) is Inbound.Stored)
        val snapChunk = Mop.json.createObjectNode()
        snapChunk.put("artifact_id", "snap-5")
        snapChunk.put("offset", 0)
        snapChunk.put("data_b64", java.util.Base64.getEncoder().encodeToString(snapBytes))
        assertTrue(endpoint.onRawEnvelope(
            edgeEnvelope("ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", 51, snapChunk).toString(),
        ) is Inbound.Stored)

        val spec = FlightUploadSpec(
            flightId = "flt-1",
            siteId = "site-1",
            rcDeviceId = "rc-1",
            missionId = "m-1",
            missionRevisionId = "mis-1",
            missionSha256 = "ab".repeat(32),
            bundleSha256 = "cd".repeat(32),
        )
        val builder = ManifestBuilder(store)
        val manifest = builder.build(spec, "boot-1")
        val ids = manifest.get("artifacts").map { it.get("artifact_id").asText() }
        assertTrue("snap-5" in ids, "ingestible snapshot ships")
        assertTrue(
            "assess-5" !in ids,
            "EDGE_ASSESSMENT is outside the frozen ingest enum: withheld, never relabelled",
        )
        assertEquals(
            listOf("assess-5"),
            builder.excludedArtifacts("flt-1", "boot-1")
                .map { it.get("artifact_id").asText() },
        )
    }
}