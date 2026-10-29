package ksi.safetyedge.rc

import ksi.rc.PendingSiteDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pending-site rendering in the adopted site-first model: a locally
 * drafted site shows the honest pending badge (never "등록됨"), stays
 * selectable like a synced site for NEW preparations, and suggestions
 * still never force.
 */
class MissionSiteCardsPendingTest {

    private fun draft(
        id: String = "client-uuid-1",
        name: String = "들판 현장",
    ) = PendingSiteDraft(
        clientSiteId = id,
        name = name,
        centerLat = 36.6,
        centerLon = 127.4,
        radiusM = 500.0,
        createdUtc = "2026-10-24T00:00:00Z",
    )

    @Test
    fun pending_site_renders_the_honest_badge() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.pendingInput(draft()),
        )
        assertEquals("들판 현장", card.title)
        assertEquals("이 기기에 저장됨 · 중앙 등록 대기", card.pendingBadge)
        assertTrue(card.statusLine.contains("이 기기에 저장됨 · 중앙 등록 대기"))
        assertFalse("pending must never read as registered: ${card.statusLine}",
            card.statusLine.contains("등록됨"))
        assertFalse(card.hasPlans)
        assertNull(card.suggestionChip)
    }

    @Test
    fun pending_site_carries_its_reason_without_losing_the_badge() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.pendingInput(
                draft(), pendingNote = PendingSiteRegistration.NO_CORE_REASON,
            ),
        )
        assertEquals("이 기기에 저장됨 · 중앙 등록 대기", card.pendingBadge)
        assertTrue(card.statusLine.contains("Core 미설정"))
    }

    @Test
    fun pending_site_uses_the_client_uuid_as_its_selectable_id() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.pendingInput(draft(id = "client-uuid-9")),
        )
        assertEquals("client-uuid-9", card.siteId)
    }

    @Test
    fun pending_site_suggestion_still_never_forces() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.pendingInput(draft(), suggested = true),
        )
        assertTrue(
            "chip selects but never forces",
            card.suggestionChip!!.contains("조종자가 확정"),
        )
        assertEquals("이 기기에 저장됨 · 중앙 등록 대기", card.pendingBadge)
    }

    @Test
    fun synced_sites_render_without_a_pending_badge() {
        val card = MissionSiteCards.siteCard(
            MissionSiteCards.SiteInput(
                siteId = "site-abcdef",
                siteName = "중앙 현장",
                plans = emptyList(),
                pastFlights = emptyList(),
                suggested = false,
            ),
        )
        assertNull(card.pendingBadge)
        assertFalse(card.statusLine.contains("이 기기에 저장"))
    }

    @Test
    fun pending_site_keeps_its_own_flights_out_of_other_plans() {
        val flight = MissionSiteCards.FlightInput(
            flightId = "flight-1", bundleSha256 = null,
            state = "STARTED", observationCount = 0,
        )
        val input = MissionSiteCards.pendingInput(draft(), pastFlights = listOf(flight))
        assertEquals(1, input.pastFlights.size)
        val card = MissionSiteCards.siteCard(input)
        // Pending badge dominates the status line (no invented plan counts).
        assertTrue(card.statusLine.contains("이 기기에 저장됨"))
    }
}
