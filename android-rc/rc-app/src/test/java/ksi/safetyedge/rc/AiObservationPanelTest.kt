package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the integrated operation screen's AI observation area:
 * honest states from data that already exists (store
 * observations/events + the video source's frame-mapping flag). Nothing
 * is invented; unavailable/pending renders distinctly from "no risk".
 */
class AiObservationPanelTest {

    private fun card(
        eventId: String,
        eventType: String,
        mode: String,
        quality: String = "VALID",
        evidence: List<ObservationModel.EvidenceLink> = emptyList(),
    ) = ObservationModel.EventCard(
        eventId = eventId,
        eventType = eventType,
        mode = mode,
        detectedAtEdgeNs = 1L,
        observationQuality = quality,
        evidence = evidence,
    )

    private fun input(
        hasActiveFlight: Boolean = true,
        observationCount: Int = 2,
        eventCount: Int = 1,
        observationAgeMs: Long? = 2_000L,
        frameMappingVerified: Boolean = false,
        cards: List<ObservationModel.EventCard> = listOf(
            card("e1", "PROXIMITY_CANDIDATE", "SHADOW"),
        ),
    ) = AiObservationPanel.Input(
        hasActiveFlight = hasActiveFlight,
        observationCount = observationCount,
        eventCount = eventCount,
        observationAgeMs = observationAgeMs,
        frameMappingVerified = frameMappingVerified,
        cards = cards,
    )

    @Test
    fun no_session_is_not_unavailable_and_not_no_risk() {
        val panel = AiObservationPanel.render(input(hasActiveFlight = false))
        assertFalse(panel.unavailable)
        assertTrue(panel.headline.contains("기록 없음"))
        assertTrue(panel.candidates.isEmpty())
        assertFalse(panel.headline.contains("사건 0건"))
        assertTrue(panel.emptyListNote.contains("세션 없음"))
        assertFalse(panel.emptyListNote.contains("후보 없음"))
    }

    @Test
    fun missing_observation_is_unavailable_not_no_risk() {
        val panel = AiObservationPanel.render(
            input(observationCount = 0, eventCount = 0, observationAgeMs = null, cards = emptyList()),
        )
        assertTrue(panel.unavailable)
        assertTrue(panel.headline.contains("관측 미수신"))
        assertTrue(panel.headline.contains("알 수 없음"))
        // "사건 0건" must never appear as a verdict here: without an
        // observation the analysis scope itself is unknown.
        assertFalse(panel.headline.contains("사건 0건"))
        assertTrue(panel.detail.contains("안전 확인 아님"))
        assertTrue(panel.emptyListNote.contains("미수신"))
    }

    @Test
    fun events_without_observation_keep_unknown_scope() {
        val panel = AiObservationPanel.render(
            input(observationCount = 0, eventCount = 2, observationAgeMs = null),
        )
        assertTrue(panel.unavailable)
        assertTrue(panel.headline.contains("사건 2건"))
        assertTrue(panel.headline.contains("관측 미수신"))
    }

    @Test
    fun analyzed_zero_events_says_so_without_claiming_safety() {
        val panel = AiObservationPanel.render(
            input(eventCount = 0, cards = emptyList()),
        )
        assertFalse(panel.unavailable)
        assertTrue(panel.headline.contains("사건 0건"))
        assertFalse(panel.headline.contains("분석됨"))
    }

    @Test
    fun received_events_without_cards_are_not_reported_as_zero() {
        val panel = AiObservationPanel.render(input(eventCount = 3, cards = emptyList()))
        assertTrue(panel.headline.contains("사건 3건"))
        assertFalse(panel.headline.contains("사건 0건"))
    }

    @Test
    fun shadow_candidates_are_marked_as_non_warnings() {
        val panel = AiObservationPanel.render(input())
        assertFalse(panel.unavailable)
        assertTrue(panel.headline.contains("SHADOW"))
        assertTrue(panel.headline.contains("경고 아님"))
        assertEquals("SHADOW", panel.candidates.single().kind)
    }

    @Test
    fun active_alerts_look_different_from_shadow() {
        val panel = AiObservationPanel.render(
            input(
                eventCount = 2,
                cards = listOf(
                    card("e-active", "PROXIMITY_CANDIDATE", "ACTIVE"),
                    card("e-shadow", "PROXIMITY_CANDIDATE", "SHADOW"),
                ),
            ),
        )
        assertTrue(panel.headline.contains("ACTIVE 1건"))
        assertTrue(panel.headline.contains("SHADOW 후보 1건"))
        assertEquals(
            listOf("ACTIVE", "SHADOW"),
            panel.candidates.map { it.kind },
        )
    }

    @Test
    fun unknown_and_unrecognized_modes_are_not_shadow_or_non_warnings() {
        for (mode in listOf("UNKNOWN", "OFF", "FUTURE", "")) {
            val cards = listOf(card("unknown", "PROXIMITY_CANDIDATE", mode))
            val panel = AiObservationPanel.render(input(cards = cards))
            assertEquals("모드 미확인", panel.candidates.single().kind)
            assertTrue(panel.headline.contains("모드 미확인 1건"))
            assertFalse(panel.headline.contains("SHADOW"))
            assertFalse(panel.headline.contains("경고 아님"))
            assertFalse(AiObservationPanel.shadowOnly(cards))
            assertFalse(AiObservationPanel.candidateSummary(panel.candidates).contains("경고 아님"))
        }
    }

    @Test
    fun mixed_modes_keep_shadow_qualifier_scoped_and_do_not_hide_unknown() {
        val cards = listOf(
            card("s", "PROXIMITY_CANDIDATE", "SHADOW"),
            card("a", "PROXIMITY_CANDIDATE", "ACTIVE"),
            card("u", "PROXIMITY_CANDIDATE", "UNKNOWN"),
            card("d", "DETECTED_ONLY", "ACTIVE"),
        )
        val panel = AiObservationPanel.render(input(eventCount = 4, cards = cards))
        assertEquals(listOf("SHADOW", "ACTIVE", "모드 미확인", "객체 관측"), panel.candidates.map { it.kind })
        for (text in listOf(panel.headline, AiObservationPanel.candidateSummary(panel.candidates))) {
            assertTrue(text.contains("ACTIVE 1건"))
            assertTrue(text.contains("SHADOW 후보 1건 · SHADOW는 경고 아님"))
            assertTrue(text.contains("모드 미확인 1건"))
            assertTrue(text.contains("객체 관측 1건"))
        }
        assertFalse(AiObservationPanel.shadowOnly(cards))
    }

    @Test
    fun supervision_shadow_only_requires_all_candidates_to_be_explicit_shadow() {
        val shadow = card("s", "PROXIMITY_CANDIDATE", "SHADOW")
        val detected = card("d", "DETECTED_ONLY", "UNKNOWN")
        val unknown = card("u", "PROXIMITY_CANDIDATE", "UNKNOWN")
        val active = card("a", "PROXIMITY_CANDIDATE", "ACTIVE")
        assertTrue(AiObservationPanel.shadowOnly(listOf(shadow, detected)))
        assertFalse(AiObservationPanel.shadowOnly(listOf(detected)))
        assertFalse(AiObservationPanel.shadowOnly(listOf(shadow, unknown)))
        assertFalse(AiObservationPanel.shadowOnly(listOf(shadow, active)))
        assertFalse(AiObservationPanel.shadowOnly(emptyList()))
        assertFalse(AiObservationPanel.shadowOnly(listOf(shadow), complete = false))
    }

    @Test
    fun detections_are_not_risk_candidates() {
        val panel = AiObservationPanel.render(
            input(
                cards = listOf(card("e-d", "DETECTED_ONLY", "SHADOW")),
            ),
        )
        assertEquals("객체 관측", panel.candidates.single().kind)
        assertTrue(panel.headline.contains("객체 관측 1건"))
        assertTrue(panel.headline.contains("평가 결과 미확인"))
    }

    @Test
    fun overlay_is_withheld_until_frame_correspondence_is_verified() {
        val held = AiObservationPanel.render(input(frameMappingVerified = false))
        assertTrue(held.overlayNote.contains("박스를 표시하지 않습니다"))

        val shown = AiObservationPanel.render(input(frameMappingVerified = true))
        assertFalse(shown.overlayNote.contains("박스를 표시하지 않습니다"))
    }

    @Test
    fun map_relation_stays_held_without_deleting_the_detection() {
        val panel = AiObservationPanel.render(input())
        assertTrue(panel.mapNote.contains("지도 위치는 미확인"))
        // The detection fact itself is still listed.
        assertEquals(1, panel.candidates.size)
    }

    @Test
    fun candidate_list_is_capped_newest_first_with_evidence_state() {
        val cards = (1..7).map { index ->
            card(
                eventId = "e$index",
                eventType = "PROXIMITY_CANDIDATE",
                mode = "SHADOW",
                evidence = listOf(
                    ObservationModel.EvidenceLink("a$index", complete = true, synced = false),
                ),
            )
        }
        val panel = AiObservationPanel.render(input(eventCount = 7, cards = cards))
        assertEquals(AiObservationPanel.MAX_CANDIDATES, panel.candidates.size)
        assertEquals("e1", panel.candidates.first().eventId)
        assertTrue(panel.candidates.first().evidenceSummary.contains("로컬 1/1"))
    }
}
