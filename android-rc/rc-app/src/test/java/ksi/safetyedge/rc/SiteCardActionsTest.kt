package ksi.safetyedge.rc

import ksi.rc.SiteSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 현장 확인 카드 — 동작 결정 회귀.
 *
 * 계약: 자동 확정 금지 · 후보 부재는 경고가 아니라 "버튼 없음" ·
 * 추천 실패가 비행·관측·기록을 막지 않는다.
 */
class SiteCardActionsTest {

    private fun candidate(siteId: String) = SiteSuggestion.Candidate(
        siteId = siteId,
        siteName = "현장 $siteId",
        centerLat = 37.0,
        centerLon = 127.0,
        radiusM = 300.0,
        distanceM = 12.0,
    )

    private fun suggestion(vararg ids: String) = SiteSuggestion.Suggestion(
        candidates = ids.map { candidate(it) },
        positionUsed = SiteSuggestion.Position(
            lat = 37.0,
            lon = 127.0,
            source = SiteSuggestion.PositionSource.RC_GNSS,
        ),
    )

    @Test
    fun a_candidate_offers_the_use_action() {
        val decision = SiteCardActions.decide(suggestion("site-a"), siteCount = 3)
        assertTrue(decision.showUseSuggested)
        assertEquals("site-a", decision.useSuggestedSiteId)
        assertTrue(decision.showChooseOther)
    }

    @Test
    fun no_candidate_means_no_use_action_but_the_list_still_opens() {
        val decision = SiteCardActions.decide(null, siteCount = 2)
        assertFalse("후보가 없으면 만들지 않는다", decision.showUseSuggested)
        assertNull(decision.useSuggestedSiteId)
        assertTrue("현장 목록은 열 수 있다", decision.showChooseOther)
    }

    @Test
    fun no_sites_at_all_means_no_actions() {
        val decision = SiteCardActions.decide(null, siteCount = 0)
        assertFalse(decision.showUseSuggested)
        assertFalse(decision.showChooseOther)
    }

    @Test
    fun the_closest_candidate_is_the_one_that_gets_selected() {
        val decision = SiteCardActions.decide(suggestion("near", "far"), siteCount = 2)
        assertEquals("가장 가까운 후보", "near", decision.useSuggestedSiteId)
    }
}
