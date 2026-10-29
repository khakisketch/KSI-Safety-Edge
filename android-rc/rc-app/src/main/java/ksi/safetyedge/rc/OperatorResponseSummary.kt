package ksi.safetyedge.rc

import ksi.rc.OperatorAction

/**
 * D6 — **조종사 대응 이력의 표시** (기록 검토 화면).
 *
 * WHY THIS EXISTS
 *   기록 검토 화면에는 '오탐 의심'(사후 검토 의견, operator-annotation)이 보인다.
 *   그런데 **운용 중의 대응**(확인·보류·전달 요청, operator-response)은
 *   Jo종자가 현장에서 한 결정이며, 그 결정이 **어떤 상태로 남아 있는지**(로컬
 *   기록뿐인지, 전달 요청만인지, 전송이 확인됐는지)는 그 결정의 일부다.
 *   화면에 없으면 조종자는 "내가 표시한 게 남아 있는지" 알 수 없다.
 *
 * HONESTY (이 표시가 지켜야 할 것)
 *   - `확인`은 **위험이 해소됐다는 뜻이 아니다.** 위험 판단은 조종자의
 *     판단이지 시스템의 안전 확정이 아니다(매니폴드 역할 계약 N2).
 *   - 전달 상태는 **증명이 있는 것만** 말한다. 증명이 없는 전송·수신·조치
 *     완료는 `effectiveDelivery`이 미확인으로 낮춘다(rc-core 규칙과 동일).
 *   - `전달 요청만 저장됨`은 **전송이 안 됐다는 뜻**이다. 상대 수신이 아님.
 *
 * Android-free on purpose: host JVM에서 unit-test된다.
 */
object OperatorResponseSummary {

    /** 표시할 대응 종류(내부 상수 이름은 rc-core와 같다). */
    private const val CONFIRM = "CONFIRM"
    private const val HOLD = "HOLD"
    private const val ESCALATE_REQUEST = "ESCALATE_REQUEST"

    private const val LOCAL_ONLY = "LOCAL_ONLY"
    private const val REQUESTED = "REQUESTED"
    private const val SENT_CONFIRMED = "SENT_CONFIRMED"
    private const val RECEIVED_CONFIRMED = "RECEIVED_CONFIRMED"
    private const val COMPLETED_CONFIRMED = "COMPLETED_CONFIRMED"

    /** 대응 1건의 표시 문구. */
    fun actionLabel(action: String): String = when (action) {
        CONFIRM -> "확인"
        HOLD -> "보류"
        ESCALATE_REQUEST -> "전달 요청"
        else -> "대응"
    }

    /** 전달 상태의 표시 문구. 증명이 없으면 낮춰 말한다. */
    fun deliveryLabel(effectiveDelivery: String): String = when (effectiveDelivery) {
        LOCAL_ONLY -> "로컬 기록만 · 전송 안 됨"
        REQUESTED -> "전달 요청만 저장됨 · 전송 완료 아님"
        SENT_CONFIRMED -> "전송 확인"
        RECEIVED_CONFIRMED -> "수신 확인"
        COMPLETED_CONFIRMED -> "조치 완료 확인"
        else -> "전달 상태 미확인"
    }

    /**
     * 한 사건의 대응 표시(여러 건이면 최신 건의 상태를 대표로 쓰되,
     * 미전달 건이 남아 있으면 그것을 함께 밝힌다).
     */
    fun lineFor(actions: List<OperatorAction>, effectiveDelivery: (OperatorAction) -> String): String? {
        if (actions.isEmpty()) return null
        val latest = actions.maxByOrNull { it.actedAtUtc } ?: return null
        val parts = mutableListOf<String>()
        parts += actionLabel(latest.action)
        parts += deliveryLabel(effectiveDelivery(latest))
        // 미전달 대응이 더 있으면 "아직 전달 안 된 대응 N건"을 함께 말한다.
        val pending = actions.count { effectiveDelivery(it) == LOCAL_ONLY || effectiveDelivery(it) == REQUESTED }
        if (pending > 1) {
            parts += "미전달 대응 ${pending}건"
        }
        return parts.joinToString(" · ")
    }

    /**
     * 세션 한 줄: 조종사가 한 대응의 개수와 전달 상태 분포.
     * **위험 판단 결과가 아니다** — 대응의 존재와 전달 상태만 말한다.
     */
    fun sessionLine(
        actions: List<OperatorAction>,
        effectiveDelivery: (OperatorAction) -> String,
    ): String {
        if (actions.isEmpty()) {
            return "조종사 대응 0건 · 대응 기록이 없다는 뜻이며 위험 없음이 아니다"
        }
        val byAction = LinkedHashMap<String, Int>()
        val byDelivery = LinkedHashMap<String, Int>()
        for (action in actions) {
            byAction[actionLabel(action.action)] = (byAction[actionLabel(action.action)] ?: 0) + 1
            val label = deliveryLabel(effectiveDelivery(action))
            byDelivery[label] = (byDelivery[label] ?: 0) + 1
        }
        return buildString {
            append("조종사 대응 ${actions.size}건")
            byAction.forEach { (label, count) -> append(" · $label ${count}건") }
            byDelivery.forEach { (label, count) -> append(" · $label ${count}건") }
        }
    }
}
