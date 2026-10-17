package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ksi.safetyedge.rc.TakeoverRequestTracker.Outcome

class TakeoverRequestsTest {

    @Test
    fun begin_starts_pending_and_rejects_reused_id() {
        val t = TakeoverRequestTracker()
        val r = t.begin("req-1")
        assertEquals(Outcome.PENDING, r.outcome)
        assertTrue(t.awaiting("req-1"))
        assertFailsWith<IllegalStateException> { t.begin("req-1") }
    }

    @Test
    fun response_resolves_only_the_matching_request() {
        val t = TakeoverRequestTracker()
        t.begin("a")
        t.begin("b")
        assertTrue(t.onResponse("a", accepted = true))
        assertEquals(Outcome.ACCEPTED, t.outcome("a"))
        assertEquals(Outcome.PENDING, t.outcome("b"))
        assertEquals("b", t.latest()!!.id)
        assertEquals(Outcome.PENDING, t.latest()!!.outcome)
    }

    @Test
    fun link_loss_marks_pending_unknown_and_never_accepts() {
        val t = TakeoverRequestTracker()
        t.begin("a")
        t.onLinkLost()
        assertEquals(Outcome.UNKNOWN, t.outcome("a"))
        assertTrue(t.needsReconcile("a"))
        assertFalse(t.awaiting("a"))
    }

    @Test
    fun link_loss_leaves_terminal_outcomes_untouched() {
        val t = TakeoverRequestTracker()
        t.begin("a")
        t.onResponse("a", accepted = true)
        t.onLinkLost()
        assertEquals(Outcome.ACCEPTED, t.outcome("a"))
        assertFalse(t.needsReconcile("a"))
    }

    @Test
    fun late_response_resolves_old_request_after_newer_begins() {
        val t = TakeoverRequestTracker()
        t.begin("old")
        t.onLinkLost()
        t.begin("new")
        assertTrue(t.onResponse("old", accepted = false))
        assertEquals(Outcome.REJECTED, t.outcome("old"))
        assertEquals(Outcome.PENDING, t.outcome("new"))
        assertTrue(t.awaiting("new"))
        assertFalse(t.needsReconcile("new"))
    }

    @Test
    fun duplicate_response_does_not_rewrite_terminal_outcome() {
        val t = TakeoverRequestTracker()
        t.begin("a")
        t.onResponse("a", accepted = true)
        assertFalse(t.onResponse("a", accepted = false))
        assertEquals(Outcome.ACCEPTED, t.outcome("a"))
        assertEquals(1, t.ignored.size)
    }

    @Test
    fun unknown_id_response_is_ignored_and_changes_nothing() {
        val t = TakeoverRequestTracker()
        assertFalse(t.onResponse("nope", accepted = true))
        assertEquals(1, t.ignored.size)
        assertEquals(0, t.all().size)
    }

    @Test
    fun no_resend_api_surface_note() {
        // 자동 재전송 금지는 구조적이다: 이 모델에는 전송·타이머 API가 없고,
        // 응답 도착만이 결과를 바꾸는 유일한 경로다. 어댑터 단계에서 재검증한다.
        val t = TakeoverRequestTracker()
        t.begin("a")
        t.onLinkLost()
        repeat(3) { t.all(); t.latest(); t.needsReconcile("a"); t.awaiting("a") }
        assertEquals(Outcome.UNKNOWN, t.outcome("a"))
    }
}
