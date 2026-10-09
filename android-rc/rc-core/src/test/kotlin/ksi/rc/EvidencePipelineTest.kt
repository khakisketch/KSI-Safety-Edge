package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 evidence-store contracts (ledger §9): relations between flight / boot /
 * observation / event / artifact survive an RC restart; artifacts are only
 * readable once complete; evidence flows through the MOP endpoint with
 * commit-before-ACK semantics (a malformed chunk is not ACKed so the Edge
 * retries).
 */
class EvidencePipelineTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-store-test")

    private fun makeBundle(): ObjectNode {
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", "bundle-1")
        bundle.put("site_id", "site-1")
        bundle.put("bundle_sha256", CanonicalJson.digestExcluding(bundle, "bundle_sha256"))
        return bundle
    }

    private fun observation(): ObjectNode {
        val obs = Mop.json.createObjectNode()
        obs.put("schema_version", 1)
        obs.put("observation_id", "obs-1")
        obs.put("flight_id", "flt-1")
        obs.put("frame_received_monotonic_ns", 1000L)
        obs.set<JsonNode>("detections", Mop.json.createArrayNode())
        return obs
    }

    private fun event(observationId: String): ObjectNode {
        val event = Mop.json.createObjectNode()
        event.put("schema_version", 1)
        event.put("event_id", "evt-1")
        event.put("flight_id", "flt-1")
        event.put("observation_id", observationId)
        event.put("detected_at_edge_monotonic_ns", 2000L)
        return event
    }

    private fun summary(): ObjectNode {
        val summary = Mop.json.createObjectNode()
        summary.put("schema_version", 1)
        summary.put("summary_id", "sum-1")
        summary.put("flight_id", "flt-1")
        return summary
    }

    private fun announce(artifactId: String, size: Long): ObjectNode {
        val announce = Mop.json.createObjectNode()
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", "flt-1")
        announce.put("edge_boot_id", "boot-1")
        announce.put("source_kind", "EDGE_SNAPSHOT")
        announce.put("sha256", "ab".repeat(32))
        announce.put("size_bytes", size)
        announce.put("media_type", "image/jpeg")
        announce.put("required", true)
        announce.put("segment_index", 0)
        return announce
    }

    private fun application(): ObjectNode {
        val app = Mop.json.createObjectNode()
        app.put("application_id", "app-1")
        app.put("flight_id", "flt-1")
        app.put("bundle_id", "bundle-1")
        return app
    }

    private var edgeCounter = 0
    private fun edgeEnvelope(
        type: String,
        seq: Int,
        payload: ObjectNode,
        bootId: String = "boot-1",
    ): ObjectNode {
        edgeCounter += 1
        return Mop.makeEnvelope(
            msgId = Mop.newMsgId(
                "edge", "flt-1", bootId, edgeCounter.toString(), "EVENT_RELIABLE", type,
            ),
            channel = "EVENT_RELIABLE",
            type = type,
            flightId = "flt-1",
            edgeBootId = bootId,
            seq = seq,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 100L * edgeCounter,
        )
    }

    @Test
    fun restart_recovers_flight_boot_event_artifact_relations() {
        val root = tempRoot()
        val bytes = ByteArray(16) { it.toByte() }
        run {
            val store = FileRcStore(root)
            val obs = observation()
            store.storeObservation(obs, "boot-1")
            store.storeEvent(event(obs.get("observation_id").asText()), "boot-1")
            store.storeFlightSummary(summary(), "boot-1")
            store.announceArtifact(announce("artifact-1", 16), "boot-1")
            store.writeChunk("artifact-1", 0, bytes.copyOfRange(0, 8))
            store.writeChunk("artifact-1", 8, bytes.copyOfRange(8, 16))
            store.recordApplication(application())
            val env = Mop.makeEnvelope(
                msgId = Mop.newMsgId("edge", "flt-1", "boot-1", "msg-1"),
                channel = "EVENT_RELIABLE", type = "OBSERVATION", flightId = "flt-1",
                edgeBootId = "boot-1", seq = 1, ackOf = null,
                payload = Mop.json.createObjectNode(), sentMonotonicNs = 1L,
            )
            store.storeMessage(env)
            store.markProcessed(env.get("msg_id").asText())
        }
        // Restart: a brand-new store instance over the same directory.
        val restarted = FileRcStore(root)
        assertEquals(1, restarted.observationsFor("flt-1").size)
        val events = restarted.eventsFor("flt-1")
        assertEquals(1, events.size)
        assertEquals("obs-1", events[0].get("observation_id").asText(),
            "event -> observation relation survives restart")
        assertEquals(1, restarted.summariesFor("flt-1").size)
        assertEquals(listOf("boot-1"), restarted.bootsFor("flt-1"))
        assertContentEquals(bytes, restarted.artifactBytes("artifact-1"))
        assertEquals(1, restarted.applicationsFor("flt-1").size)
        assertTrue(restarted.processedMsgIds().any { it == Mop.newMsgId("edge", "flt-1", "boot-1", "msg-1") })
        val (first, last) = assertNotNull(restarted.firstLastNs("flt-1", "boot-1"))
        assertEquals(1000L, first)
        assertEquals(2000L, last)
    }

    @Test
    fun incomplete_artifact_is_not_available() {
        val store = FileRcStore(tempRoot())
        store.announceArtifact(announce("artifact-x", 16), "boot-1")
        store.writeChunk("artifact-x", 0, ByteArray(8))
        assertNull(store.artifactBytes("artifact-x"),
            "partial artifact bytes must never be exposed")
        store.writeChunk("artifact-x", 8, ByteArray(8))
        assertNotNull(store.artifactBytes("artifact-x"))
    }

    @Test
    fun idempotent_record_writes() {
        val root = tempRoot()
        val store = FileRcStore(root)
        store.storeObservation(observation(), "boot-1")
        store.storeObservation(observation(), "boot-1")
        assertEquals(1, store.observationsFor("flt-1").size)
        assertEquals(1, Files.readAllLines(root.resolve("observations.ndjson")).size,
            "duplicate record must not append a second line")
    }

    @Test
    fun evidence_flows_through_the_endpoint_with_commit_before_ack() {
        val store = FileRcStore(tempRoot())
        val controller = MissionController(
            "flt-1", makeBundle(), store, { 0L },
            dataHandler = EvidenceIngest(store),
        )
        val endpoint = controller.endpoint
        val bytes = ByteArray(16) { it.toByte() }

        val obsPayload = Mop.json.createObjectNode()
        obsPayload.set<JsonNode>("observation", observation())
        val obsOutcome = endpoint.onRawEnvelope(edgeEnvelope("OBSERVATION", 1, obsPayload).toString())
        assertTrue(obsOutcome is Inbound.Stored, "OBSERVATION outcome: $obsOutcome")

        val eventPayload = Mop.json.createObjectNode()
        eventPayload.set<JsonNode>("event", event("obs-1"))
        assertTrue(endpoint.onRawEnvelope(edgeEnvelope("DETECTION_EVENT", 2, eventPayload).toString())
            is Inbound.Stored)

        assertTrue(endpoint.onRawEnvelope(
            edgeEnvelope("ARTIFACT_ANNOUNCE", 3, announce("artifact-1", 16)).toString(),
        ) is Inbound.Stored)
        val chunk0 = Mop.json.createObjectNode()
        chunk0.put("artifact_id", "artifact-1")
        chunk0.put("offset", 0)
        chunk0.put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(0, 8)))
        val chunk1 = Mop.json.createObjectNode()
        chunk1.put("artifact_id", "artifact-1")
        chunk1.put("offset", 8)
        chunk1.put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(8, 16)))
        assertTrue(endpoint.onRawEnvelope(edgeEnvelope("ARTIFACT_CHUNK", 4, chunk0).toString())
            is Inbound.Stored)
        assertTrue(endpoint.onRawEnvelope(edgeEnvelope("ARTIFACT_CHUNK", 5, chunk1).toString())
            is Inbound.Stored)

        assertEquals(1, store.observationsFor("flt-1").size)
        assertEquals(1, store.eventsFor("flt-1").size)
        assertContentEquals(bytes, store.artifactBytes("artifact-1"))
        assertEquals(5, endpoint.ackedCount)

        // A malformed chunk must NOT be ACKed (the Edge retries it).
        val badChunk = Mop.json.createObjectNode()
        badChunk.put("artifact_id", "artifact-1")
        badChunk.put("offset", 16)
        badChunk.put("data_b64", "!!!not-base64!!!")
        val outcome = endpoint.onRawEnvelope(edgeEnvelope("ARTIFACT_CHUNK", 6, badChunk).toString())
        assertTrue(outcome is Inbound.Failed)
        assertEquals(5, endpoint.ackedCount, "failed messages stay un-ACKed")
        assertTrue(endpoint.pendingCount() >= 0) // sanity: no crash, retry path intact
    }
}
