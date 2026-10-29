package ksi.safetyedge.rc

import ksi.rc.SiteSuggestion

/**
 * D2 현장 확인 카드의 **동작 결정** — 무엇을 보여줄지만 정한다(동작은 하지 않는다).
 *
 * 계약(D2): "자동 확정 금지. 후보가 있으면 제시만 하고 조종자가 확인한다."
 * 그래서:
 * - "이 현장으로 준비"는 **추천 후보가 있을 때만** 존재한다(없으면 만들지 않는다).
 * - "다른 현장 선택"은 **현장 목록이 있을 때만** 존재한다.
 * - 어느 경우에도 자동으로 현장이 확정되지 않는다. 선택은 조종자의 누름이다.
 * - 추천 실패(위치 미확인·좌표 없는 현장)는 관측·기록·비행을 막지 않는다 →
 *   여기서도 후보 부재는 "버튼 없음"으로만 표현하고, 경고·차단으로 바꾸지 않는다.
 */
object SiteCardActions {

    data class Decision(
        /** "이 현장으로 준비"를 보여줄가. */
        val showUseSuggested: Boolean,
        /** 그 버튼이 선택할 현장 id(없으면 null). */
        val useSuggestedSiteId: String?,
        /** "다른 현장 선택"을 보여줄가. */
        val showChooseOther: Boolean,
    )

    private val NONE = Decision(
        showUseSuggested = false,
        useSuggestedSiteId = null,
        showChooseOther = false,
    )

    /**
     * @param suggestion GPS 추천 결과(null = 추천을 만들지 못함)
     * @param siteCount RC가 알고 있는 현장 수(0 = 현장 목록이 없다)
     */
    fun decide(suggestion: SiteSuggestion.Suggestion?, siteCount: Int): Decision {
        val best = suggestion?.best
        if (best == null && siteCount == 0) return NONE
        return Decision(
            showUseSuggested = best != null,
            useSuggestedSiteId = best?.siteId,
            showChooseOther = siteCount > 0,
        )
    }
}
