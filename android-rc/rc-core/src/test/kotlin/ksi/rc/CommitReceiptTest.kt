package ksi.rc

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit B contract §6 upload-completion semantics: completion is marked only
 * after the Core's commit receipt is persisted locally; a PARTIAL manifest
 * never reads as complete; a lost completeIngest response is recovered by
 * re-requesting the same commit receipt (same durable_commit_id).
 */
class CommitReceiptTest {
    private fun seededStore(): Triple<FileRcStore, FlightUploadSpec, String> {
        val store = FileRcStore(Files.createTempDirectory("rc-commit"))
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        seedFlightEvidence(store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() })
        return Triple(store, spec, boot)
    }

    private fun manifestShaFor(store: FileRcStore, spec: FlightUploadSpec, boot: String): String {
        val manifest = ManifestBuilder(store).build(spec, boot)
        return manifest.get("manifest_sha256").asText()
    }

    @Test
    fun committed_only_after_receipt_stored() {
        val (store, spec, boot) = seededStore()
        val fake = FakeCore()
        val report = EvidenceUploader(CoreApi(fake), store).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots[0].committed)
        val receipt = store.commitReceipt(manifestShaFor(store, spec, boot))
        assertNotNull(receipt, "COMMITTED without a locally stored receipt is not completion")
        assertEquals(report.boots[0].uploadId, receipt.get("upload_id").asText())
        assertTrue(receipt.get("durable_commit_id").asText().isNotBlank())
        assertTrue(store.isBootCommitted(manifestShaFor(store, spec, boot)))
    }

    @Test
    fun partial_never_reads_as_complete_and_stores_no_receipt() {
        val store = FileRcStore(Files.createTempDirectory("rc-commit-partial"))
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        seedFlightEvidence(store, spec, boot, artifactId, bytes, chunkSize = 8, writeChunks = false)
        store.writeChunk(artifactId, 0, bytes.copyOfRange(0, 8))
        val fake = FakeCore()
        val report = EvidenceUploader(CoreApi(fake), store).uploadFlight(spec)
        assertFalse(report.boots[0].committed)
        assertEquals("PARTIAL", report.boots[0].status)
        assertNull(store.commitReceipt(manifestShaFor(store, spec, boot)))
        assertFalse(store.isBootCommitted(manifestShaFor(store, spec, boot)))
        assertFalse(store.isArtifactSynced(artifactId))
    }

    @Test
    fun lost_complete_response_recovers_the_same_receipt() {
        val (store, spec, boot) = seededStore()
        val fake = FakeCore()
        var completeCalls = 0
        val flaky = object : CoreTransport {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): CoreHttpResponse {
                if (method == "POST" && path.endsWith("/complete")) {
                    completeCalls += 1
                    if (completeCalls == 1) throw IOException("response lost on the wire")
                }
                return fake.request(method, path, headers, body)
            }
        }
        // First run: the commit happened server-side but the response was
        // lost — the uploader must surface the failure, not completion.
        assertFailsWith<IOException> {
            EvidenceUploader(CoreApi(flaky), store).uploadFlight(spec)
        }
        assertFalse(store.isArtifactSynced(Fixture.uuid(13)))
        assertNull(store.commitReceipt(manifestShaFor(store, spec, boot)))

        // Second run re-requests the same commit: same durable id, receipt
        // stored, and only then completion.
        val report = EvidenceUploader(CoreApi(flaky), store).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots[0].committed)
        val receipt = store.commitReceipt(manifestShaFor(store, spec, boot))
        assertNotNull(receipt)
        val serverCommit = fake.ingests.values.first().durableId
        assertEquals(serverCommit, receipt.get("durable_commit_id").asText())
    }

    @Test
    fun committed_without_durable_id_is_loud_not_complete() {
        val (store, spec, _) = seededStore()
        val fake = FakeCore()
        val stripping = object : CoreTransport {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): CoreHttpResponse {
                val response = fake.request(method, path, headers, body)
                if (method == "POST" && path.endsWith("/complete")) {
                    val node = Mop.json.readTree(response.body) as com.fasterxml.jackson.databind.node.ObjectNode
                    node.putNull("durable_commit_id")
                    return CoreHttpResponse(response.status, Mop.json.writeValueAsBytes(node))
                }
                return response
            }
        }
        assertFailsWith<IllegalStateException> {
            EvidenceUploader(CoreApi(stripping), store).uploadFlight(spec)
        }
    }
}
