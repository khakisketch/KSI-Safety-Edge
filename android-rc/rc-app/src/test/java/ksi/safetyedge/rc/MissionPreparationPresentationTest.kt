package ksi.safetyedge.rc

import org.junit.Assert.*
import org.junit.Test

class MissionPreparationPresentationTest {
    @Test fun recovery_without_an_attempt_stays_absent() {
        assertNull(MissionPreparationPresentation.recoverySummary(null, "2026-10-27T08:23:09Z"))
    }

    @Test fun recovery_uses_minute_precision_and_explicit_utc_without_losing_unknown() {
        val copy = MissionPreparationPresentation.recoverySummary("0123456789abcdef", "2026-10-27T08:23:09.386757Z")!!
        assertTrue(copy.contains("10-27 08:23 UTC"))
        assertTrue(copy.contains("01234567"))
        assertTrue(copy.contains("미확인"))
        assertTrue(copy.contains("자동 재전송하지 않습니다"))
        assertFalse(copy.contains(".386757"))
    }

    @Test fun recovery_normalizes_an_explicit_offset_to_utc() {
        assertTrue(MissionPreparationPresentation.recoverySummary("plan", "2026-10-27T17:23:09+09:00")!!.contains("10-27 08:23 UTC"))
    }

    @Test fun recovery_missing_or_invalid_timestamp_is_not_a_fabricated_time() {
        for (stamp in listOf(null, "bad timestamp")) {
            assertTrue(MissionPreparationPresentation.recoverySummary("plan", stamp)!!.contains("시각 미확인"))
        }
    }

    @Test fun waiting_action_never_confuses_transmission_with_response() {
        assertEquals("전송 대기 중", MissionPreparationPresentation.pendingAction(true))
        assertEquals("장치 응답 대기 중", MissionPreparationPresentation.pendingAction(false))
    }

    @Test fun pending_uses_actual_unsent_fact_not_a_disconnected_link() {
        assertEquals("전송 대기", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.PENDING, true))
        assertEquals("응답 대기", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.PENDING, false))
    }

    @Test fun queued_envelopes_cannot_replace_response_proof() {
        assertEquals("응답 확인", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.CONFIRMED, true))
        assertEquals("거부됨", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.REJECTED, true))
        assertEquals("미확인", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.UNKNOWN, true))
        assertEquals("재확인 필요", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.REVALIDATE, true))
        assertEquals("확인 전", MissionPreparationPresentation.step(WorkspaceMissionFacts.Step.NOT_REQUESTED, true))
    }

    @Test fun offline_preflight_explains_queue_without_disabling_it() {
        val copy = MissionPreparationPresentation.guidance(MissionNextAction.Next.PREPARE, false, false)
        assertTrue(copy.contains("전송 대기열"))
        assertTrue(copy.contains("완료가 아닙니다"))
        val waiting = MissionPreparationPresentation.guidance(MissionNextAction.Next.WAITING, false, false)
        assertTrue(waiting.contains("응답 대기"))
        assertFalse(waiting.contains("전송 대기"))
    }

    @Test fun queued_prepare_discloses_process_restart_limit() {
        val copy = MissionPreparationPresentation.guidance(MissionNextAction.Next.WAITING, true, true)
        assertTrue(copy.contains("전송 대기"))
        assertTrue(copy.contains("준비 대기열 소실"))
    }

    @Test fun record_start_never_claims_camera_or_analysis_or_preservation() {
        val copy = MissionPreparationPresentation.guidance(MissionNextAction.Next.START_RECORD, true, false)
        assertTrue(copy.contains("기체 REC·관측 실행·원본 보존은 별도"))
    }
}
