package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operator-opinion transport (user review 2026-10-10): operator annotations
 * reach the server as SEPARATE append-only records — the mark and its
 * revocation are both uploaded, idempotent by annotation_id, and a failed
 * post stays pending for the next sync without failing the evidence sync.
 */
class AnnotationUploadTest {
    private val device = Fixture.uuid(900)

    private fun annotation(
        flightId: String,
        eventId: String,
        type: String,
        supersedes: String? = null,
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("annotation_id", UUID.randomUUID().toString())
        node.put("event_id", eventId)
        node.put("flight_id", flightId)
        node.put("actor_id", device)
        node.put("client_id", device)
        node.put("annotation_type", type)
        node.putNull("reason")
        node.putArray("evidence_ids")
        node.put("created_at_local", "2026-10-10T08:00:00Z")
        if (supersedes == null) {
            node.putNull("supersedes_annotation_id")
        } else {
            node.put("supersedes_annotation_id", supersedes)
        }
        return node
    }

    private fun seeded(): Triple<FileRcStore, FlightUploadSpec, String> {
        val store = FileRcStore(Files.createTempDirectory("rc-ann"))
        val spec = uploadSpec()
        seedFlightEvidence(
            store, spec, Fixture.uuid(12), Fixture.uuid(13),
            ByteArray(16) { it.toByte() },
        )
        val eventId = store.eventsFor(spec.flightId).first().get("event_id").asText()
        return Triple(store, spec, eventId)
    }

    @Test
    fun mark_and_revoke_upload_as_separate_append_only_records() {
        val (store, spec, eventId) = seeded()
        val mark = annotation(
            spec.flightId, eventId, "FALSE_POSITIVE_SUSPECTED",
        )
        store.queueAnnotation(mark)
        val revoke = annotation(
            spec.flightId, eventId, "REVOKE_PREVIOUS",
            supersedes = mark.get("annotation_id").asText(),
        )
        store.queueAnnotation(revoke)

        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store)
        val report = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertEquals(2, report.annotationsUploaded)
        assertTrue(report.annotationFailures.isEmpty())
        assertTrue(store.pendingAnnotations().isEmpty())
        val server = fake.annotationsByEvent[eventId]!!
        assertEquals(2, server.size)
        assertEquals(
            "FALSE_POSITIVE_SUSPECTED",
            server[0].get("annotation_type").asText(),
        )
        assertEquals(
            "REVOKE_PREVIOUS", server[1].get("annotation_type").asText(),
        )
        assertEquals(
            mark.get("annotation_id").asText(),
            server[1].get("supersedes_annotation_id").asText(),
        )
        // The original mark record is untouched on the server.
        assertEquals(
            mark.get("annotation_id").asText(),
            server[0].get("annotation_id").asText(),
        )

        // Second run: nothing pending, the server list does not grow.
        val again = uploader.uploadFlight(spec)
        assertEquals(0, again.annotationsUploaded)
        assertEquals(2, fake.annotationsByEvent[eventId]!!.size)
    }

    @Test
    fun failed_annotation_post_stays_pending_and_evidence_still_syncs() {
        val (store, spec, eventId) = seeded()
        store.queueAnnotation(annotation(spec.flightId, eventId, "NEEDS_REVIEW"))

        val fake = FakeCore()
        fake.failNextAnnotationPost = true
        val uploader = EvidenceUploader(CoreApi(fake), store)
        val first = uploader.uploadFlight(spec)
        // Evidence is unaffected: it commits; only the opinion failed.
        assertEquals("COMPLETE", first.uploadState)
        assertEquals(0, first.annotationsUploaded)
        assertEquals(1, first.annotationFailures.size)
        assertEquals(1, store.pendingAnnotations().size)

        val second = uploader.uploadFlight(spec)
        assertEquals(1, second.annotationsUploaded)
        assertTrue(store.pendingAnnotations().isEmpty())
        assertEquals(1, fake.annotationsByEvent[eventId]!!.size)
    }
}
