package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3-b manifest contracts: the digest golden below was produced by the REAL
 * Python module (`ksi_contracts.set_digest` over the identical fixture), so
 * a mismatch means the Kotlin RC would compute a different manifest digest
 * than the server verifies. Gap synthesis mirrors the validated harness
 * (`_synthesize_missing_gaps`).
 */
class ManifestBuilderTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-manifest")

    /** Minimal stored envelope: enough for the seq index (no handler). */
    private fun seqEnvelope(
        flightId: String,
        bootId: String,
        channel: String,
        seq: Long,
    ): ObjectNode {
        val env = Mop.json.createObjectNode()
        env.put("channel", channel)
        env.put("seq", seq)
        env.put("edge_boot_id", bootId)
        env.put("flight_id", flightId)
        return env
    }

    @Test
    fun golden_manifest_matches_python_digest() {
        val flight = "11111111-1111-4111-8111-111111111111"
        val missionRev = "22222222-2222-4222-8222-222222222222"
        val rcDevice = "33333333-3333-4333-8333-333333333333"
        val boot = "44444444-4444-4444-8444-444444444444"
        val artifact = "55555555-5555-4555-8555-555555555555"
        val spec = FlightUploadSpec(
            flightId = flight,
            siteId = Fixture.uuid(2),
            rcDeviceId = rcDevice,
            missionId = Fixture.uuid(4),
            missionRevisionId = missionRev,
            missionSha256 = Fixture.hex('b'),
            bundleSha256 = Fixture.hex('c'),
        )
        val store = FileRcStore(tempRoot())
        for (seq in longArrayOf(1, 2, 4)) {
            store.storeMessage(seqEnvelope(flight, boot, "EVENT_RELIABLE", seq))
        }
        for (seq in longArrayOf(1, 2)) {
            store.storeMessage(seqEnvelope(flight, boot, "EVIDENCE_TRANSFER", seq))
        }
        store.storeObservation(
            observationRecord(flight, boot, missionRev, Fixture.uuid(1001), 1, 1000),
            boot,
        )
        store.storeObservation(
            observationRecord(flight, boot, missionRev, Fixture.uuid(1002), 2, 5000),
            boot,
        )
        store.storeEvent(
            detectionEventRecord(
                flight, Fixture.uuid(3001), Fixture.uuid(1001), Fixture.uuid(4001),
                2500, listOf(artifact),
            ),
            boot,
        )
        val bytes = ByteArray(16) { it.toByte() }
        store.announceArtifact(
            artifactAnnounceRecord(flight, boot, artifact, bytes), boot,
        )
        store.writeChunk(artifact, 0, bytes)
        val declaredGap = Mop.json.createObjectNode()
        declaredGap.put("schema_version", 1)
        declaredGap.put("edge_boot_id", boot)
        declaredGap.put("channel", "EVENT_RELIABLE")
        declaredGap.put("seq", 3)
        declaredGap.put("reason", "UNRECOVERABLE:EVENT_RELIABLE")
        declaredGap.put("destination", "RC_MANIFEST")
        declaredGap.put("flight_id", flight)
        store.recordGap(declaredGap)

        val manifest = ManifestBuilder(store, ::testToUtc).build(spec, boot)

        // Pinned by libs/ksi_contracts (set_digest over the identical data):
        assertEquals(
            "7e729b6289ed222cb1892673b2c840d055fd145987152fc80a35c89295259977",
            manifest.get("manifest_sha256").asText(),
        )
        assertTrue(CanonicalJson.verifyDigest(manifest, "manifest_sha256"))
        assertEquals(1, manifest.get("event_count").asInt())
        assertEquals(4, manifest.get("edge_last_seq").asInt())
        assertEquals(4, manifest.get("rc_received_last_seq").asInt())
        assertEquals(
            "2026-10-08T02:05:00.000001Z",
            manifest.get("flight_started_at_utc").asText(),
        )
        assertEquals(
            "2026-10-08T02:05:00.000005Z",
            manifest.get("flight_ended_at_utc").asText(),
        )
        val artifacts = manifest.get("artifacts")
        assertEquals(1, artifacts.size())
        assertEquals(artifact, artifacts[0].get("artifact_id").asText())
        assertEquals("EDGE_SNAPSHOT", artifacts[0].get("source_kind").asText())
        assertEquals(
            "be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991",
            artifacts[0].get("sha256").asText(),
        )
        assertEquals(16, artifacts[0].get("size_bytes").asInt())
        assertTrue(artifacts[0].get("required").asBoolean())
        assertTrue(artifacts[0].get("segment_index").isNull)
        val gaps = manifest.get("gaps")
        assertEquals(1, gaps.size())
        assertEquals("MOP_EVENT_GAP", gaps[0].get("kind").asText())
        assertEquals(99, gaps[0].get("start_ms").asInt()) // 3 * 33ms
        assertEquals(33, gaps[0].get("duration_ms").asInt())
        assertEquals("UNRECOVERABLE:EVENT_RELIABLE", gaps[0].get("reason").asText())
    }

    @Test
    fun synthesizes_missing_seqs_only_where_the_edge_declared_a_gap() {
        val spec = uploadSpec()
        val boot = Fixture.uuid(50)
        // No declared gap: leftovers are NOT turned into gaps (harness parity).
        val quiet = FileRcStore(tempRoot())
        for (seq in longArrayOf(1, 2, 4)) {
            quiet.storeMessage(seqEnvelope(spec.flightId, boot, "EVENT_RELIABLE", seq))
        }
        val quietManifest = ManifestBuilder(quiet, ::testToUtc).build(spec, boot)
        assertEquals(0, quietManifest.get("gaps").size())
        assertTrue(quiet.gapsForBoot(boot).isEmpty())

        // A declared gap on the channel: missing seq 3 is synthesized.
        val store = FileRcStore(tempRoot())
        for (seq in longArrayOf(1, 2, 4)) {
            store.storeMessage(seqEnvelope(spec.flightId, boot, "EVENT_RELIABLE", seq))
        }
        val declared = Mop.json.createObjectNode()
        declared.put("schema_version", 1)
        declared.put("edge_boot_id", boot)
        declared.put("channel", "EVENT_RELIABLE")
        declared.put("seq", 5)
        declared.put("reason", "UNRECOVERABLE:EVENT_RELIABLE")
        declared.put("destination", "RC_MANIFEST")
        declared.put("flight_id", spec.flightId)
        store.recordGap(declared)

        val manifest = ManifestBuilder(store, ::testToUtc).build(spec, boot)
        val gaps = manifest.get("gaps")
        assertEquals(2, gaps.size())
        assertEquals(99, gaps[0].get("start_ms").asInt()) // seq 3 run
        assertEquals("SYNTHESIZED:EVENT_RELIABLE", gaps[0].get("reason").asText())
        assertEquals(165, gaps[1].get("start_ms").asInt()) // seq 5 run
        assertEquals("UNRECOVERABLE:EVENT_RELIABLE", gaps[1].get("reason").asText())
        // Rebuilding is idempotent (recorded gaps dedupe by boot/channel/seq).
        val again = ManifestBuilder(store, ::testToUtc).build(spec, boot)
        assertEquals(2, again.get("gaps").size())
        assertEquals(2, store.gapsForBoot(boot).size)
    }

    @Test
    fun heartbeat_gaps_ignore_received_seqs_and_dedupe() {
        val spec = uploadSpec()
        val boot = Fixture.uuid(51)
        val store = FileRcStore(tempRoot())
        store.storeMessage(seqEnvelope(spec.flightId, boot, "EVENT_RELIABLE", 1))
        store.storeMessage(seqEnvelope(spec.flightId, boot, "EVENT_RELIABLE", 2))
        val endpoint = MopEndpoint(spec.flightId, store, { 0L }, EvidenceIngest(store))
        val payload = Mop.json.createObjectNode()
        val gaps = payload.putArray("unrecoverable_gaps")
        val group = gaps.addObject()
        group.put("channel", "EVENT_RELIABLE")
        val seqs = group.putArray("seqs")
        seqs.add(3) // truly missing
        seqs.add(1) // actually received: must not become a gap
        seqs.add(2)
        feedEnvelope(
            endpoint,
            edgeEnvelope(
                "HEARTBEAT", spec.flightId, boot, "HEARTBEAT", null, payload, "hb1",
            ),
        )
        val recorded = store.gapsForBoot(boot)
        assertEquals(1, recorded.size)
        assertEquals(3, recorded[0].get("seq").asInt())
        assertEquals("UNRECOVERABLE:EVENT_RELIABLE", recorded[0].get("reason").asText())
        // Redelivery of the same declaration is a no-op.
        feedEnvelope(
            endpoint,
            edgeEnvelope(
                "HEARTBEAT", spec.flightId, boot, "HEARTBEAT", null, payload, "hb2",
            ),
        )
        assertEquals(1, store.gapsForBoot(boot).size)
    }

    @Test
    fun session_matches_the_validated_harness_shape() {
        val spec = uploadSpec()
        val boot = Fixture.uuid(60)
        val store = FileRcStore(tempRoot())
        val application = Mop.json.createObjectNode()
        application.put("schema_version", 1)
        application.put("application_id", Fixture.uuid(61))
        application.put("flight_id", spec.flightId)
        application.put("bundle_id", Fixture.uuid(62))
        application.put("bundle_sha256", Fixture.hex('d'))
        application.put("edge_boot_id", boot)
        application.put("effective_edge_seq", 7)
        application.put("applied", true)
        application.set<com.fasterxml.jackson.databind.JsonNode>(
            "rejection_reasons", Mop.json.createArrayNode(),
        )
        application.set<com.fasterxml.jackson.databind.JsonNode>(
            "excluded_items", Mop.json.createArrayNode(),
        )
        application.put("applied_at_edge_monotonic_ns", 500)
        store.recordApplication(application)
        store.storeObservation(
            observationRecord(
                spec.flightId, boot, spec.missionRevisionId,
                Fixture.uuid(1001), 1, 1000, lens = "wide",
            ),
            boot,
        )
        store.storeObservation(
            observationRecord(
                spec.flightId, boot, spec.missionRevisionId,
                Fixture.uuid(1002), 2, 2000, lens = "wide",
            ),
            boot,
        )
        store.storeObservation(
            observationRecord(
                spec.flightId, boot, spec.missionRevisionId,
                Fixture.uuid(1003), 3, 1500, lens = "zoom",
            ),
            boot,
        )
        val session = SessionBuilder(store).build(spec, boot)
        assertEquals(
            Uuid5.of(Mop.NAMESPACE, "session|${spec.flightId}|$boot").toString(),
            session.get("session_id").asText(),
        )
        assertEquals(1, session.get("mission_application_ids").size())
        assertEquals(
            Fixture.uuid(61),
            session.get("mission_application_ids")[0].asText(),
        )
        assertEquals("COMPLETED", session.get("end_reason").asText())
        assertEquals(1000, session.get("started_at_edge_monotonic_ns").asInt())
        assertEquals(2000, session.get("ended_at_edge_monotonic_ns").asInt())
        val epochs = session.get("tracker_epochs")
        assertEquals(2, epochs.size())
        assertEquals("wide-0", epochs[0].get("camera_stream_id").asText())
        assertEquals("zoom-0", epochs[1].get("camera_stream_id").asText())
        assertEquals(1000, epochs[0].get("started_at").asInt())
        assertEquals(1500, epochs[1].get("started_at").asInt())
    }
}
