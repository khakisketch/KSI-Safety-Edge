package ksi.rc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * L1 closure item 3 (user review 2026-10-10): recorded proxy segments —
 * including those of ZERO-EVENT flights — become standard artifacts through
 * the same announce/chunk pipeline, sync under the same COMMITTED gate,
 * and are never deleted (before or after sync).
 */
class SegmentIngestTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-segment")

    private fun tempFile(dir: Path, name: String, bytes: ByteArray): java.io.File {
        val file = dir.resolve(name).toFile()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun segment_becomes_a_standard_artifact_and_syncs_like_any_evidence() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val segmentBytes = ByteArray(3000) { (it % 251).toByte() }
        val segmentFile = tempFile(root, "seg-0.mp4", segmentBytes)

        val ingestor = SegmentIngestor(store, chunkBytes = 1024)
        val artifactId = ingestor.ingestSegment(
            spec.flightId, boot, segmentIndex = 0, file = segmentFile,
        )

        // Stored through the standard artifact contract.
        val record = store.artifactsFor(spec.flightId).single()
        assertEquals(artifactId, record.get("artifact_id").asText())
        assertEquals("RC_PROXY_SEGMENT", record.get("source_kind").asText())
        assertEquals(0, record.get("segment_index").asInt())
        assertEquals("video/mp4", record.get("media_type").asText())
        assertTrue(store.isArtifactComplete(artifactId))
        assertContentEquals(segmentBytes, store.artifactBytes(artifactId))

        // Upload works with no special-casing; the manifest carries the
        // segment artifact, and a zero-record flight uploads fine.
        val fake = FakeCore()
        val report = EvidenceUploader(CoreApi(fake), store).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        val session = fake.ingests.values.first()
        val manifestArtifacts = session.manifest.get("artifacts")
        assertEquals(1, manifestArtifacts.size())
        assertEquals(artifactId, manifestArtifacts[0].get("artifact_id").asText())
        assertEquals(
            "RC_PROXY_SEGMENT",
            manifestArtifacts[0].get("source_kind").asText(),
        )

        // Retention: the local artifact survives the COMMITTED sync (no
        // deletion path exists — assertions pin that).
        assertTrue(store.isArtifactSynced(artifactId))
        assertContentEquals(segmentBytes, store.artifactBytes(artifactId))
    }

    @Test
    fun identical_reingest_is_idempotent_and_a_changed_file_is_a_new_artifact() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val bytes = ByteArray(2048) { it.toByte() }
        val file = tempFile(root, "seg-0.mp4", bytes)
        val ingestor = SegmentIngestor(store, chunkBytes = 512)

        val first = ingestor.ingestSegment(spec.flightId, boot, 0, file)
        val again = ingestor.ingestSegment(spec.flightId, boot, 0, file)
        assertEquals(first, again, "identical content re-ingest is a no-op")
        assertEquals(1, store.artifactsFor(spec.flightId).size)

        // A different recording (same slot) is a NEW artifact; the original
        // segment is preserved untouched.
        file.writeBytes(ByteArray(2048) { (it + 1).toByte() })
        val second = ingestor.ingestSegment(spec.flightId, boot, 0, file)
        assertNotEquals(first, second)
        assertEquals(2, store.artifactsFor(spec.flightId).size)
        assertContentEquals(bytes, store.artifactBytes(first))
    }
}
