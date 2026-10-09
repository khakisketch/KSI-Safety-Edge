package ksi.rc

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3-b uploader semantics against the [FakeCore] contract double:
 * commit-before-SYNCED, restart resumability, honest PARTIAL handling,
 * content-derived batch ids, and idempotent re-runs.
 */
class EvidenceUploaderTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-upload")

    private fun seedApplication(store: FileRcStore, spec: FlightUploadSpec, bootId: String) {
        val application = Mop.json.createObjectNode()
        application.put("schema_version", 1)
        application.put("application_id", Fixture.uuid(61))
        application.put("flight_id", spec.flightId)
        application.put("bundle_id", Fixture.uuid(62))
        application.put("bundle_sha256", Fixture.hex('d'))
        application.put("edge_boot_id", bootId)
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
    }

    @Test
    fun uploads_evidence_and_marks_synced_only_after_commit() {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        seedFlightEvidence(store, spec, boot, artifactId, bytes, chunkSize = 8)
        seedApplication(store, spec, boot)
        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store, ::testToUtc, chunkBytes = 8)

        val report = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertEquals(1, report.applicationsUploaded)
        assertEquals(1, report.sessionsUploaded)
        assertEquals(1, report.boots.size)
        assertTrue(report.boots[0].committed)
        assertEquals("COMMITTED", report.boots[0].status)
        assertEquals(2, report.boots[0].chunksSent)
        assertEquals(listOf(artifactId), report.boots[0].synced)
        assertTrue(report.boots[0].missingArtifacts.isEmpty())
        assertTrue(report.boots[0].incompleteLocal.isEmpty())
        assertTrue(store.isArtifactSynced(artifactId))
        assertTrue(store.isApplicationUploaded(Fixture.uuid(61)))
        assertTrue(fake.flights.containsKey(spec.flightId))
        assertEquals(1, fake.sessions.size)
        assertEquals(2, fake.batchedItems("observations", spec.flightId).size)
        assertEquals(1, fake.batchedItems("events", spec.flightId).size)
        assertEquals(1, fake.batchedItems("flight", spec.flightId).size)
        assertContentEquals(bytes, fake.latestObjectBytes(artifactId))
        // The Edge-declared gap reached the server inside the manifest.
        val session = fake.ingests.values.first()
        val gaps = session.manifest.get("gaps")
        assertEquals(1, gaps.size())
        assertEquals(9 * 33, gaps[0].get("start_ms").asInt())
        assertEquals("UNRECOVERABLE:EVENT_RELIABLE", gaps[0].get("reason").asText())

        // Re-run: receipts + resident list + session list suppress all work.
        val putsBefore = fake.chunkPuts
        val second = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", second.uploadState)
        assertEquals(0, second.applicationsUploaded)
        assertEquals(0, second.sessionsUploaded)
        assertEquals(0, second.boots[0].chunksSent)
        assertEquals(putsBefore, fake.chunkPuts)
        assertTrue(second.batches.all { it.skipped })
    }

    @Test
    fun resumes_after_mid_upload_transport_failure() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(12) { it.toByte() }
        seedFlightEvidence(store, spec, boot, artifactId, bytes, chunkSize = 4)
        val fake = FakeCore()
        fake.failAtChunkPut = 2
        val uploader = EvidenceUploader(CoreApi(fake), store, ::testToUtc, chunkBytes = 4)

        assertFailsWith<IOException> { uploader.uploadFlight(spec) }
        assertFalse(store.isArtifactSynced(artifactId))
        assertEquals(2, store.observationsFor(spec.flightId).size)

        // "Restart": fresh store instance over the same directory.
        val restarted = FileRcStore(root)
        fake.failAtChunkPut = null
        val resumed = EvidenceUploader(CoreApi(fake), restarted, ::testToUtc, chunkBytes = 4)
        val report = resumed.uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots[0].committed)
        // Not resident server-side yet: all chunks are re-sent (the server
        // merges ranges, so re-sending is idempotent).
        assertEquals(3, report.boots[0].chunksSent)
        assertTrue(restarted.isArtifactSynced(artifactId))
        assertContentEquals(bytes, fake.latestObjectBytes(artifactId))
        assertEquals(5, fake.chunkPuts) // 1 stored + 1 failed + 3 stored
    }

    @Test
    fun partial_artifacts_never_get_synced_and_resume_after_repair() {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        // Announced with the full digest, but only half the bytes arrived.
        seedFlightEvidence(
            store, spec, boot, artifactId, bytes,
            chunkSize = 8, writeChunks = false,
        )
        store.writeChunk(artifactId, 0, bytes.copyOfRange(0, 8))
        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store, ::testToUtc, chunkBytes = 8)

        val partial = uploader.uploadFlight(spec)
        assertFalse(partial.boots[0].committed)
        assertEquals("PARTIAL", partial.boots[0].status)
        assertEquals(listOf(artifactId), partial.boots[0].missingArtifacts)
        assertEquals(listOf(artifactId), partial.boots[0].incompleteLocal)
        assertEquals(0, partial.boots[0].chunksSent)
        assertFalse(store.isArtifactSynced(artifactId))

        // Bytes complete: the same manifest resumes and commits.
        store.writeChunk(artifactId, 8, bytes.copyOfRange(8, 16))
        val report = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots[0].committed)
        assertEquals(listOf(artifactId), report.boots[0].synced)
        assertTrue(store.isArtifactSynced(artifactId))
        assertContentEquals(bytes, fake.latestObjectBytes(artifactId))
    }

    @Test
    fun new_evidence_appends_a_new_batch_and_a_new_manifest() {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        val endpoint = seedFlightEvidence(store, spec, boot, artifactId, bytes)
        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store, ::testToUtc)

        assertEquals("COMPLETE", uploader.uploadFlight(spec).uploadState)

        // A late observation arrives after the first full upload.
        feedEnvelope(
            endpoint,
            edgeEnvelope(
                "OBSERVATION", spec.flightId, boot, "EVENT_RELIABLE", 5,
                Mop.json.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>(
                        "observation",
                        observationRecord(
                            spec.flightId, boot, spec.missionRevisionId,
                            Fixture.uuid(1005), 5, 3000,
                        ),
                    )
                },
                "obs3",
            ),
        )
        val report = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        val observations = report.batches.first { it.kind == "observations" }
        assertFalse(observations.skipped)
        // The server dedupes by item id: only the late observation is new.
        assertEquals(1, observations.accepted)
        assertEquals(2, observations.duplicates)
        // The session snapshot became stale (ended_at moved) and is
        // surfaced, not silent: server sessions are immutable records.
        val sessionId = Uuid5.of(Mop.NAMESPACE, "session|${spec.flightId}|$boot").toString()
        assertEquals(listOf(sessionId), report.sessionDrift)
        assertEquals(0, report.sessionsUploaded)
        // The longer time range is a NEW manifest -> a new committed ingest
        // session re-verifying the artifact against the server (per-session
        // object storage is the server contract).
        assertEquals(2, fake.ingests.values.size)
        assertTrue(fake.ingests.values.all { it.status == "COMMITTED" })
    }

    @Test
    fun boot_with_only_gap_declarations_still_gets_a_manifest() {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        seedFlightEvidence(store, spec, boot, artifactId, bytes)

        // A second boot whose ONLY evidence is a HEARTBEAT gap declaration.
        val gapBoot = Fixture.uuid(70)
        val endpoint = MopEndpoint(spec.flightId, store, { 0L }, EvidenceIngest(store))
        val heartbeat = Mop.json.createObjectNode()
        val group = heartbeat.putArray("unrecoverable_gaps").addObject()
        group.put("channel", "EVENT_RELIABLE")
        group.putArray("seqs").add(2)
        feedEnvelope(
            endpoint,
            edgeEnvelope(
                "HEARTBEAT", spec.flightId, gapBoot, "HEARTBEAT", null,
                heartbeat, "hb-gap-boot",
            ),
        )

        val fake = FakeCore()
        val report = EvidenceUploader(CoreApi(fake), store, ::testToUtc).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertEquals(2, report.boots.size)
        val gapBootReport = report.boots.first { it.bootId == gapBoot }
        assertTrue(gapBootReport.committed)
        // The declared gap reached the server: it must not be dropped just
        // because that boot produced no observations/events.
        val gapSession = fake.ingests.values.first {
            it.manifest.get("edge_boot_id").asText() == gapBoot
        }
        assertEquals(1, gapSession.manifest.get("gaps").size())
        assertEquals(2 * 33, gapSession.manifest.get("gaps")[0].get("start_ms").asInt())
        assertEquals(
            "UNRECOVERABLE:EVENT_RELIABLE",
            gapSession.manifest.get("gaps")[0].get("reason").asText(),
        )
    }
}
