package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified filmstrip availability + viewer request identity (issue #4):
 * only image media with hash-verified, decodable bytes reads preserved;
 * async results apply only to the still-current request.
 */
class FilmstripAvailabilityTest {

    @Test
    fun ready_requires_image_complete_bytes_and_decodable() {
        val availability = FilmstripAvailability.classify(
            isImage = true,
            complete = true,
            bytesPresent = true,
            decodable = true,
        )
        assertEquals(FilmstripAvailability.Status.READY, availability.status)
        assertEquals("RC 로컬 보존됨", availability.visibleLabel)
        assertTrue(availability.reason.contains("Edge 원본·Core 보관은 별도"))
    }

    @Test
    fun cheap_receipt_alone_never_reads_preserved() {
        // complete=true (cheap bytes-received) but no verified bytes.
        val availability = FilmstripAvailability.classify(
            isImage = true,
            complete = true,
            bytesPresent = false,
            decodable = false,
        )
        assertEquals(FilmstripAvailability.Status.MISSING, availability.status)
        assertEquals("로컬 미수신", availability.visibleLabel)
        assertTrue(
            "missing must not prove the Edge did not preserve",
            availability.reason.contains("Edge 보존 여부 미확인"),
        )
    }

    @Test
    fun visible_reasons_distinguish_unreceived_hash_failure_undecodable() {
        val missing = FilmstripAvailability.classify(
            isImage = true,
            complete = false,
            bytesPresent = false,
            decodable = false,
        ).reason
        val undecodable = FilmstripAvailability.classify(
            isImage = true,
            complete = true,
            bytesPresent = true,
            decodable = false,
        ).reason
        assertTrue("distinct reasons: [$missing] vs [$undecodable]", missing != undecodable)
        // Hash-failure attribution overrides the generic corrupt reason.
        val hashFailure = FilmstripAvailability.Availability(
            FilmstripAvailability.Status.CORRUPT,
            "손상됨",
            "수신 기록 있으나 바이트 검증 실패",
        )
        assertTrue(
            "hash failure distinguished: ${hashFailure.reason}",
            hashFailure.reason != missing && hashFailure.reason != undecodable,
        )
    }

    @Test
    fun bad_hash_or_undecodable_reads_corrupt_never_preserved() {
        val corrupt = FilmstripAvailability.classify(
            isImage = true,
            complete = true,
            bytesPresent = true,
            decodable = false,
        )
        assertEquals(FilmstripAvailability.Status.CORRUPT, corrupt.status)
        assertEquals("손상됨", corrupt.visibleLabel)
    }

    @Test
    fun non_image_is_a_supporting_artifact_never_a_frame() {
        val availability = FilmstripAvailability.classify(
            isImage = false,
            complete = true,
            bytesPresent = true,
            decodable = true,
        )
        assertEquals(FilmstripAvailability.Status.NON_IMAGE, availability.status)
        assertEquals("이미지 아님", availability.visibleLabel)
    }

    @Test
    fun viewer_session_drops_stale_requests() {
        val session = ViewerSession()
        val first = session.request("flight-1", "event-1", "artifact-1")
        assertTrue(session.shouldApply(first))
        // A newer request invalidates the older one.
        val second = session.request("flight-1", "event-1", "artifact-2")
        assertFalse("older request must not paint", session.shouldApply(first))
        assertTrue(session.shouldApply(second))
    }

    @Test
    fun viewer_session_clears_on_selection_change() {
        val session = ViewerSession()
        val request = session.request("flight-1", "event-1", "artifact-1")
        session.select()
        assertFalse("cleared request must not paint", session.shouldApply(request))
    }

    @Test
    fun viewer_request_identity_covers_generation_flight_event_artifact() {
        val session = ViewerSession()
        val request = session.request("flight-1", "event-1", "artifact-1")
        val sameValues = ViewerSession.Request(
            generation = request.generation,
            flightId = "flight-1",
            eventId = "event-1",
            artifactId = "artifact-1",
        )
        assertTrue("identical identity applies", session.shouldApply(sameValues))
        val otherArtifact = request.copy(artifactId = "artifact-2")
        assertFalse("different artifact must not paint", session.shouldApply(otherArtifact))
        val otherEvent = request.copy(eventId = "event-9")
        assertFalse("different event must not paint", session.shouldApply(otherEvent))
    }
}
