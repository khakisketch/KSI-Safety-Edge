package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline-first registration dialog model: minimal input (name + confirmed
 * location), honest pending labels, and validation that mirrors the server
 * location rule (all-or-nothing + ranges). No string is invented in the
 * Activity layer — it binds to these problems and labels.
 */
class PendingSiteRegistrationTest {

    @Test
    fun blank_name_is_not_submittable() {
        val form = PendingSiteRegistration.PendingSiteForm(name = "  ")
        assertFalse(form.submittable())
        assertTrue(form.problems().any { it.contains("이름") })
    }

    @Test
    fun name_only_is_submittable_offline() {
        val form = PendingSiteRegistration.PendingSiteForm(name = "들판 현장")
        assertTrue("name-only drafts must always be allowed", form.submittable())
        assertTrue(form.problems().isEmpty())
    }

    @Test
    fun full_location_is_submittable() {
        val form = PendingSiteRegistration.PendingSiteForm(
            name = "들판 현장", centerLat = 36.6, centerLon = 127.4, radiusM = 500.0,
        )
        assertTrue(form.submittable())
    }

    @Test
    fun partial_location_is_rejected_with_guidance() {
        val form = PendingSiteRegistration.PendingSiteForm(
            name = "들판 현장", centerLat = 36.6, centerLon = null, radiusM = null,
        )
        assertFalse(form.submittable())
        assertTrue(form.problems().any { it.contains("모두") })
    }

    @Test
    fun out_of_range_location_is_rejected_with_guidance() {
        val badLat = PendingSiteRegistration.PendingSiteForm(
            name = "들판", centerLat = 91.0, centerLon = 127.0, radiusM = 500.0,
        )
        assertFalse(badLat.submittable())
        assertTrue(badLat.problems().any { it.contains("위도") })

        val badRadius = PendingSiteRegistration.PendingSiteForm(
            name = "들판", centerLat = 36.6, centerLon = 127.0, radiusM = 0.0,
        )
        assertFalse(badRadius.submittable())
        assertTrue(badRadius.problems().any { it.contains("반경") })
    }

    @Test
    fun clear_drops_the_whole_location_together() {
        val form = PendingSiteRegistration.PendingSiteForm(
            name = "들판", centerLat = 36.6, centerLon = 127.0, radiusM = 500.0,
        )
        val cleared = form.cleared()
        assertNull(cleared.centerLat)
        assertNull(cleared.centerLon)
        assertNull(cleared.radiusM)
        assertEquals("들판", cleared.name)
        assertTrue(cleared.submittable())
    }

    @Test
    fun pending_badge_never_claims_registration() {
        assertEquals("이 기기에 저장됨 · 중앙 등록 대기", PendingSiteRegistration.PENDING_BADGE)
        val line = PendingSiteRegistration.statusLine()
        assertTrue(line.contains(PendingSiteRegistration.PENDING_BADGE))
        assertFalse("pending must never read as registered", line.contains("등록됨"))
        val withReason = PendingSiteRegistration.statusLine(
            PendingSiteRegistration.NO_CORE_REASON,
        )
        assertTrue(withReason.contains(PendingSiteRegistration.PENDING_BADGE))
        assertTrue(withReason.contains("Core 미설정"))
    }

    @Test
    fun failed_status_keeps_the_pending_badge() {
        val line = PendingSiteRegistration.failedStatusLine("boom")
        assertTrue(line.contains(PendingSiteRegistration.PENDING_BADGE))
        assertTrue(line.contains("다시 시도"))
    }

    @Test
    fun match_notice_names_the_link_honestly() {
        val notice = PendingSiteRegistration.resolutionNotice(matched = true)
        assertEquals("중앙에 같은 이름 현장 존재 — 연결됨", notice)
        assertFalse("a match is a link, never a new registration", notice.contains("새 현장"))
        assertEquals("중앙에 새 현장으로 등록됨", PendingSiteRegistration.resolutionNotice(false))
    }

    @Test
    fun dialog_copy_is_minimal_and_honest() {
        assertEquals("현장 추가", PendingSiteRegistration.ADD_ENTRY_LABEL)
        assertTrue(PendingSiteRegistration.DIALOG_TITLE.contains("이 기기에 저장"))
    }

    @Test
    fun upload_wait_reason_names_site_first_ordering() {
        val reason = PendingSiteRegistration.UPLOAD_WAIT_REASON
        assertTrue("upload waits for central registration: $reason", reason.contains("현장 동기화"))
        assertFalse("a wait is not a failure claim: $reason", reason.contains("실패"))
    }
}
