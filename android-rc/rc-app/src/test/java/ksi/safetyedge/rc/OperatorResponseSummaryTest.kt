package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.OperatorAction
import ksi.rc.OperatorActionLog

/**
 * D6 회귀 — 대응 이력 표시.
 *
 * 고정하는 것:
 * ① 대응이 없으면 "대응 기록이 없다는 뜻이며 위험 없음이 아니다"라고 말한다.
 * ② `확인`은 위험 해소가 아니다 — 화면 문구에 "위험 없음"이 들어가지 않는다.
 * ③ 전달 상태는 증명이 있는 것만 말한다(미확인은 낮춰 말한다).
 * ④ "전달 요청만 저장됨"은 전송 완료가 아니다.
 */
class OperatorResponseSummaryTest {

    private fun log(): OperatorActionLog {
        val dir = Files.createTempDirectory("opresp")
        return OperatorActionLog(dir)
    }

    private fun action(
        log: OperatorActionLog,
        id: String,
        sourceId: String,
        action: String,
        delivery: String,
        at: String = "2026-10-21T03:00:00Z",
    ): OperatorAction {
        val record = OperatorAction(
            responseId = id,
            candidateId = "c-$id",
            siteId = "site-1",
            sessionId = "session-1",
            action = action,
            note = null,
            evidenceSourceId = sourceId,
            evidenceReceivedAtUtc = at,
            policyId = null,
            policyVersion = null,
            actedAtUtc = at,
            delivery = delivery,
        )
        log.append(record)
        return record
    }

    @Test
    fun no_response_is_not_safety() {
        val line = OperatorResponseSummary.sessionLine(emptyList()) { "LOCAL_ONLY" }
        assertTrue(line.contains("대응 0건"))
        assertTrue(line.contains("위험 없음이 아니다"))
        assertFalse(line.contains("안전"))
    }

    @Test
    fun the_confirm_action_never_claims_the_risk_is_gone() {
        val log = log()
        action(log, "r1", "event-1", "CONFIRM", "LOCAL_ONLY")
        val actions = log.readAll()
        val label = OperatorResponseSummary.actionLabel(actions.first().action)
        assertEquals("확인", label)
        assertFalse("위험 해소를 말하지 않는다", label.contains("위험 없음"))
        assertFalse(label.contains("해소"))
    }

    @Test
    fun the_delivery_state_is_spoken_only_with_proof() {
        assertEquals(
            "로컬 기록만 · 전송 안 됨",
            OperatorResponseSummary.deliveryLabel("LOCAL_ONLY"),
        )
        assertEquals(
            "전달 요청만 저장됨 · 전송 완료 아님",
            OperatorResponseSummary.deliveryLabel("REQUESTED"),
        )
        assertEquals(
            "전달 상태 미확인",
            OperatorResponseSummary.deliveryLabel("UNKNOWN"),
        )
        assertEquals(
            "전송 확인",
            OperatorResponseSummary.deliveryLabel("SENT_CONFIRMED"),
        )
    }

    @Test
    fun the_session_line_counts_actions_and_delivery_states() {
        val log = log()
        action(log, "r1", "event-1", "CONFIRM", "LOCAL_ONLY", "2026-10-21T03:00:00Z")
        action(log, "r2", "event-2", "ESCALATE_REQUEST", "REQUESTED", "2026-10-21T04:00:00Z")
        val actions = log.readAll()
        val line = OperatorResponseSummary.sessionLine(actions) { log.effectiveDelivery(it) }
        assertTrue(line.contains("조종사 대응 2건"))
        assertTrue(line.contains("확인 1건"))
        assertTrue(line.contains("전달 요청 1건"))
        assertTrue(line.contains("로컬 기록만 · 전송 안 됨 1건"))
        assertTrue(line.contains("전달 요청만 저장됨 · 전송 완료 아님 1건"))
    }

    @Test
    fun a_pending_response_beside_a_latest_one_is_still_visible() {
        val log = log()
        action(log, "r1", "event-1", "CONFIRM", "LOCAL_ONLY", "2026-10-21T03:00:00Z")
        action(log, "r2", "event-1", "ESCALATE_REQUEST", "REQUESTED", "2026-10-21T05:00:00Z")
        action(log, "r3", "event-1", "HOLD", "LOCAL_ONLY", "2026-10-21T04:00:00Z")
        val actions = log.readAll().filter { it.evidenceSourceId == "event-1" }
        val line = OperatorResponseSummary.lineFor(actions) { log.effectiveDelivery(it) }
        assertTrue(line != null)
        // 최신 건(전달 요청)의 상태가 대표로 보인다.
        assertTrue(line!!.contains("전달 요청"))
        // 미전달 대응이 있으면 그 사실도 함께 말한다(최신 건 포함 3건).
        assertTrue(line.contains("미전달 대응 3건"))
    }

    @Test
    fun an_event_without_responses_has_no_line() {
        assertNull(OperatorResponseSummary.lineFor(emptyList()) { "LOCAL_ONLY" })
    }
}
