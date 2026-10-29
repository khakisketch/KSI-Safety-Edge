package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the response/observation area distinction (2026-10-21
 * contract §4.3, screen contract §3-5-1/§4): no observations because
 * analysis did not run reads differently from analysis that ran and found
 * nothing ("관측 0건(분석 수행됨)" — still not "safe"). The existing
 * candidate/response UI contract ([CandidateResponseTest]) is untouched.
 */
class AiObservationAnalysisTest {

    private fun input(
        hasActiveFlight: Boolean = true,
        observationCount: Int = 0,
        eventCount: Int = 0,
        analysisRan: Boolean? = null,
    ) = AiObservationPanel.Input(
        hasActiveFlight = hasActiveFlight,
        observationCount = observationCount,
        eventCount = eventCount,
        observationAgeMs = null,
        frameMappingVerified = false,
        cards = emptyList(),
        analysisRan = analysisRan,
    )

    @Test
    fun processing_confirmation_without_observation_records_is_not_a_clean_negative() {
        val panel = AiObservationPanel.render(input(analysisRan = true))

        assertEquals("분석 실행 확인 · 관측 결과 미수신", panel.headline)
        assertTrue(panel.unavailable)
        assertTrue(panel.detail.contains("안전 확인 아님"))
        assertTrue(panel.detail.contains("탐지 0건 판정 아님"))
    }

    @Test
    fun received_observation_with_zero_events_is_distinct_from_no_result() {
        val panel = AiObservationPanel.render(input(observationCount = 1, analysisRan = true))
        assertFalse(panel.unavailable)
        assertTrue(panel.headline.contains("사건 0건"))
        assertTrue(panel.detail.contains("안전 판정 아님"))
    }

    @Test
    fun analysis_not_run_says_unknown_not_zero() {
        listOf<Boolean?>(false, null).forEach { analysisRan ->
            val panel = AiObservationPanel.render(input(analysisRan = analysisRan))

            assertEquals("관측 미수신 — 분석 결과 알 수 없음", panel.headline)
            assertTrue(panel.unavailable)
            assertTrue(panel.detail.contains("안전 확인 아님"))
        }
    }

    @Test
    fun analysis_ran_does_not_weaken_the_no_session_or_event_branches() {
        val noSession = AiObservationPanel.render(
            input(hasActiveFlight = false, analysisRan = true),
        )
        assertTrue(noSession.headline.contains("기록 없음"))
        assertFalse(noSession.unavailable)

        val eventsWithoutObservations = AiObservationPanel.render(
            AiObservationPanel.Input(
                hasActiveFlight = true,
                observationCount = 0,
                eventCount = 2,
                observationAgeMs = null,
                frameMappingVerified = false,
                cards = emptyList(),
                analysisRan = true,
            ),
        )
        assertTrue(eventsWithoutObservations.unavailable)
        assertTrue(eventsWithoutObservations.headline.contains("분석 범위 알 수 없음"))
    }
}
