package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adopted site-first mission model: concise cards from real stored facts
 * only. A suggestion chip never forces, the new polygon patrol stays
 * disclosed-unavailable. Past outcomes are attributed PER PLAN by stored
 * bundle sha — a site-wide flight list is never spread across plans.
 */
class MissionSiteCardsTest {

    private fun bundle(id: String, sha: String, rev: String? = "rev-12345678") =
        MissionSiteCards.BundleInput(
            bundleId = id,
            bundleSha256 = sha,
            missionRevisionId = rev,
            zoneCount = 3,
            ruleCount = 1,
        )

    private fun flight(id: String, sha: String?, state: String = "ENDED", observations: Int = 5) =
        MissionSiteCards.FlightInput(
            flightId = id,
            bundleSha256 = sha,
            state = state,
            observationCount = observations,
        )

    private fun plan(id: String, sha: String, flights: List<MissionSiteCards.FlightInput>) =
        MissionSiteCards.PlanInput(bundle(id, sha), flights)

    @Test
    fun empty_site_shows_honest_empty_state() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.SiteInput(
                siteId = "site-abcdef",
                siteName = "청주 평지",
                plans = emptyList(),
                pastFlights = emptyList(),
                suggested = false,
            ),
        )
        assertEquals("청주 평지", card.title)
        assertTrue("empty reads as missing plans, not as ready", card.statusLine.contains("저장된 계획 없음"))
        assertFalse(card.hasPlans)
        assertNull(card.suggestionChip)
    }

    @Test
    fun suggestion_chip_only_for_genuinely_suggested_site() {
        val suggested = MissionSiteCards.siteCard(
            MissionSiteCards.SiteInput(
                siteId = "site-a",
                siteName = "현장 A",
                plans = listOf(plan("bundle-1", "sha-1", listOf(flight("flight-1", "sha-1")))),
                pastFlights = listOf(flight("flight-1", "sha-1")),
                suggested = true,
            ),
        )
        assertTrue(suggested.hasPlans)
        assertTrue(suggested.statusLine.contains("저장 계획 1건"))
        assertTrue(suggested.statusLine.contains("지난 결과 1건"))
        assertTrue(
            "chip selects but never forces",
            suggested.suggestionChip!!.contains("조종자가 확정"),
        )
    }

    @Test
    fun plan_card_uses_short_ids_and_plain_past_outcome() {
        val plans = MissionSiteCards.planCards(
            listOf(
                plan(
                    "bundle-abcdef1234",
                    "sha-aaa",
                    listOf(flight("flight-1", "sha-aaa", "ENDED", 7)),
                ),
            ),
        )
        assertEquals(1, plans.size)
        val card = plans.single()
        assertTrue("short bundle id: ${card.factLine}", card.factLine.contains("bundle-a"))
        assertTrue("구역 3개: ${card.factLine}", card.factLine.contains("구역 3개"))
        assertTrue("규칙 1건: ${card.factLine}", card.factLine.contains("규칙 1건"))
        assertTrue("past outcome is a plain count: ${card.pastLine}", card.pastLine.contains("지난 결과 1건"))
        assertTrue("route shape honestly unreceived: ${card.routeLine}", card.routeLine.contains("경로 자료 미수신"))
        assertTrue(card.hasPast)
    }

    @Test
    fun two_plans_on_one_site_keep_their_own_outcomes() {
        val plans = MissionSiteCards.planCards(
            listOf(
                plan("bundle-a", "sha-a", listOf(flight("flight-a1", "sha-a", "ENDED", 7))),
                plan("bundle-b", "sha-b", listOf(flight("flight-b1", "sha-b", "STARTED", 3))),
            ),
        )
        assertEquals(2, plans.size)
        val first = plans[0]
        val second = plans[1]
        assertTrue("plan A outcome: ${first.pastLine}", first.pastLine.contains("지난 결과 1건"))
        assertTrue("plan A recent is its own flight: ${first.pastLine}", first.pastLine.contains("기록 종료"))
        assertTrue("plan B outcome: ${second.pastLine}", second.pastLine.contains("지난 결과 1건"))
        assertTrue("plan B state: ${second.pastLine}", second.pastLine.contains("기록 중"))
        assertFalse("no cross-plan leakage into A", first.pastLine.contains("기록 중"))
        assertFalse("no cross-plan leakage into B", second.pastLine.contains("기록 종료"))
    }

    @Test
    fun unmatched_bundle_sha_claims_no_past_result() {
        val plans = MissionSiteCards.planCards(
            listOf(
                plan("bundle-c", "sha-c", emptyList()),
                plan("bundle-d", "sha-d", emptyList()),
            ),
        )
        assertEquals("지난 결과 없음", plans[0].pastLine)
        assertFalse(plans[0].hasPast)
        assertEquals("지난 결과 없음", plans[1].pastLine)
        assertFalse(plans[1].hasPast)
    }

    @Test
    fun plan_card_without_past_results_says_so() {
        val plans = MissionSiteCards.planCards(
            listOf(plan("bundle-1", "sha-1", emptyList())),
        )
        assertEquals("지난 결과 없음", plans.single().pastLine)
        assertFalse(plans.single().hasPast)
    }

    @Test
    fun missing_snapshot_facts_read_unknown_never_zero() {
        val plans = MissionSiteCards.planCards(
            listOf(
                MissionSiteCards.PlanInput(
                    MissionSiteCards.BundleInput(
                        bundleId = "bundle-x",
                        bundleSha256 = "sha-x",
                        missionRevisionId = null,
                        zoneCount = null,
                        ruleCount = null,
                    ),
                    emptyList(),
                ),
            ),
        )
        val line = plans.single().factLine
        assertTrue("missing revision is unknown: $line", line.contains("미확인"))
        assertTrue("missing zones are unknown: $line", line.contains("구역 미확인"))
        assertTrue("missing rules are unknown: $line", line.contains("규칙 미확인"))
        assertFalse("missing is never zero: $line", line.contains("0개") || line.contains("0건"))
    }

    @Test
    fun blank_site_name_falls_back_to_short_id() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.SiteInput(
                siteId = "site-abcdef",
                siteName = "  ",
                plans = emptyList(),
                pastFlights = emptyList(),
                suggested = false,
            ),
        )
        assertTrue("fallback carries the short id: ${card.title}", card.title.contains("site-abc"))
    }
}
