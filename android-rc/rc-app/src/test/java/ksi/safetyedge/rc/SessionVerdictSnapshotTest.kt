package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.ExecutionFacts
import ksi.rc.SessionResult
import ksi.rc.SessionResultClassifier
import ksi.rc.StoreSessionFacts

/**
 * Host tests for the shared verdict snapshot (contract §6): the display,
 * the compare and the persisted record use structurally identical facts,
 * so a repeated render with unchanged facts requests nothing and writes
 * nothing. No `takeIf` null-coercion, no flight-level fallbacks.
 */
class SessionVerdictSnapshotTest {

    private fun exec(
        linkInterrupted: Boolean = false,
        epochChanged: Boolean = false,
    ) = ExecutionFacts(
        configApplied = true,
        runtimeConfirmed = true,
        frameProcessingObserved = true,
        stopConfirmed = true,
        runtimeEpochChanged = epochChanged,
        linkInterrupted = linkInterrupted,
    )

    private fun stored(count: Int? = 3) = StoreSessionFacts(
        observationCount = count,
        videoEvidencePresent = true,
        declaredGaps = false,
    )

    @Test
    fun build_passes_flags_through_raw_false_stays_false() {
        val facts = SessionVerdictSnapshot.build(exec(), stored())

        // The old takeIf coercion turned these false and diverged from the
        // stored record; the shared snapshot keeps the observed value.
        assertEquals(false, facts.linkInterrupted)
        assertEquals(false, facts.runtimeEpochChanged)
        assertEquals(true, facts.configApplied)
        assertEquals(3, facts.observationCount)
        assertEquals(true, facts.videoEvidencePresent)
        assertEquals(false, facts.declaredGaps)
    }

    @Test
    fun build_keeps_absent_basis_null() {
        val facts = SessionVerdictSnapshot.build(null, null)

        assertEquals(null, facts.configApplied)
        assertEquals(null, facts.observationCount)
        assertEquals(null, facts.linkInterrupted)
    }

    @Test
    fun build_does_not_fall_back_to_flight_level_counts() {
        // Resolver says the boot basis is missing (null) — the display
        // snapshot must say the same, never a flight-level number the
        // decide path cannot reproduce.
        val facts = SessionVerdictSnapshot.build(exec(), StoreSessionFacts(null, null, null))

        assertEquals(null, facts.observationCount)
    }

    @Test
    fun shouldRequest_repeated_render_with_unchanged_facts_writes_nothing() {
        val facts = SessionVerdictSnapshot.build(exec(), stored())
        val json = SessionVerdictSnapshot.factsJson(facts)

        // First render: nothing in flight, nothing persisted -> request.
        assertTrue(SessionVerdictSnapshot.shouldRequest(facts, null, null))
        // Repeat render with the identical snapshot while the request is
        // in flight -> no second request.
        assertFalse(SessionVerdictSnapshot.shouldRequest(facts, facts, null))
        // After persistence the stored JSON matches -> no further request.
        assertFalse(SessionVerdictSnapshot.shouldRequest(facts, null, json))
        // ... and the in-memory current check agrees.
        assertTrue(facts == facts)
    }

    @Test
    fun shouldRequest_changed_facts_request_again() {
        val before = SessionVerdictSnapshot.build(exec(), stored(count = 3))
        val after = SessionVerdictSnapshot.build(exec(), stored(count = 4))

        assertFalse(before == after)
        assertTrue(
            SessionVerdictSnapshot.shouldRequest(
                after, pending = before,
                persistedFactsJson = SessionVerdictSnapshot.factsJson(before),
            ),
        )
    }

    @Test
    fun local_video_failure_forces_video_absent_and_failed() {
        // The flag mirrors SessionVerdict.decide exactly: known failure
        // forces video evidence absent (even with stored segments) and the
        // classifier fails the verdict on that basis.
        val flagged = SessionVerdictSnapshot.build(exec(), stored(), localVideoFailure = true)
        assertEquals(true, flagged.recordingFailure)
        assertEquals(false, flagged.videoEvidencePresent)
        assertEquals(SessionResult.FAILED, SessionResultClassifier.classify(flagged))

        // Unknown stays unknown (null is never guessed false), and an
        // explicit false keeps the stored basis.
        val unknown = SessionVerdictSnapshot.build(exec(), stored())
        assertEquals(null, unknown.recordingFailure)
        assertEquals(3, unknown.observationCount)
        val clear = SessionVerdictSnapshot.build(exec(), stored(), localVideoFailure = false)
        assertEquals(false, clear.recordingFailure)
        assertEquals(3, clear.observationCount)
        // The flag participates in the canonical compare: a changed flag
        // is a changed snapshot.
        assertFalse(
            SessionVerdictSnapshot.factsJson(flagged) ==
                SessionVerdictSnapshot.factsJson(clear),
        )
    }

    @Test
    fun durable_edge_evidence_failure_forwards_verbatim_never_merged() {
        // Edge journal failure (session_capacity_exceeded latch): forwarded
        // as-is into evidenceFailure and forces FAILED — never merged into
        // the RC camera input (videoEvidencePresent untouched by it) and
        // never a field-safety claim.
        val flagged = SessionVerdictSnapshot.build(
            exec().copy(durableEvidenceFailure = true),
            stored(),
        )
        assertEquals(true, flagged.evidenceFailure)
        assertEquals(true, flagged.videoEvidencePresent)
        assertEquals(SessionResult.FAILED, SessionResultClassifier.classify(flagged))

        val clear = SessionVerdictSnapshot.build(exec(), stored())
        assertEquals(null, clear.evidenceFailure)
        assertEquals(
            SessionVerdictSnapshot.factsJson(clear),
            SessionVerdictSnapshot.factsJson(SessionVerdictSnapshot.build(exec(), stored())),
        )
    }
}
