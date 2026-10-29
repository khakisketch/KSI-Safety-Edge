package ksi.safetyedge.rc

import ksi.rc.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the session outcome + preservation display (2026-10-21
 * contract §6): verdict wording per result, COMMITTED separation, and
 * "업로드 완료" driven ONLY by the stored commit receipt — never implying
 * "safe".
 */
class SessionResultPanelTest {

    @Test
    fun success_wording_requires_all_basis() {
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = SessionResult.SUCCESS,
                openSession = false,
                commitConfirmed = true,
                committedCount = 5,
                totalCount = 5,
            ),
        )

        assertEquals("세션 결과 · SUCCESS · 설정·실행·관측·영상 근거 완비", panel.verdictLine)
    }

    @Test
    fun failed_wording_names_missing_basis() {
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = SessionResult.FAILED,
                openSession = false,
                commitConfirmed = true,
            ),
        )

        assertEquals("세션 결과 · FAILED · 실행 실패 또는 필수 기록 결측", panel.verdictLine)
        assertTrue(
            "preservation must not read as success",
            panel.preservationLine.contains("세션 결과와 별도"),
        )
    }

    @Test
    fun interrupted_wording_names_the_cut() {
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = SessionResult.INTERRUPTED,
                openSession = false,
                commitConfirmed = false,
            ),
        )

        assertEquals("세션 결과 · INTERRUPTED · 링크·재시작 등으로 중단", panel.verdictLine)
    }

    @Test
    fun unknown_and_undecided_render_unknown_never_invented() {
        listOf(
            SessionResultPanel.Input(result = null, openSession = false, commitConfirmed = null),
            SessionResultPanel.Input(result = SessionResult.UNKNOWN, openSession = false, commitConfirmed = false),
        ).forEach { input ->
            val panel = SessionResultPanel.render(input)
            assertEquals("세션 결과 · 미확인(UNKNOWN) · 판단 근거 부족", panel.verdictLine)
        }
    }

    @Test
    fun open_session_is_never_prejudged_success_or_failed() {
        listOf(SessionResult.SUCCESS, SessionResult.FAILED, SessionResult.UNKNOWN, null).forEach { result ->
            val panel = SessionResultPanel.render(
                SessionResultPanel.Input(
                    result = result,
                    openSession = true,
                    commitConfirmed = null,
                ),
            )
            if (result == SessionResult.FAILED) {
                assertEquals("세션 결과 · FAILED · 이미 알려진 복구 불가 결측", panel.verdictLine)
            } else {
                assertEquals("세션 결과 · 미확인(진행 중) · 판단 근거 부족", panel.verdictLine)
            }
            assertTrue(!panel.verdictLine.contains("SUCCESS"))
        }
    }

    @Test
    fun upload_complete_requires_the_stored_commit_receipt_and_never_implies_safe() {
        val committed = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = SessionResult.FAILED,
                openSession = false,
                commitConfirmed = true,
            ),
        )
        assertEquals("업로드 완료 · 서버 커밋 확인(영수증 저장됨) · 안전 확인 아님", committed.uploadLine)
        assertTrue(
            "a preserved FAILED session stays FAILED",
            committed.verdictLine.contains("FAILED"),
        )

        listOf<Boolean?>(false, null).forEach { commit ->
            val panel = SessionResultPanel.render(
                SessionResultPanel.Input(
                    result = SessionResult.SUCCESS,
                    openSession = false,
                    commitConfirmed = commit,
                ),
            )
            assertEquals("업로드 미완료 · 커밋 영수증 없음 · 안전 판단 아님", panel.uploadLine)
        }
    }

    @Test
    fun preservation_counts_are_shown_without_merging_into_the_verdict() {
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = SessionResult.SUCCESS,
                openSession = false,
                commitConfirmed = true,
                committedCount = 3,
                totalCount = 5,
            ),
        )

        assertTrue(panel.preservationLine.contains("서버 커밋 3/5"))
        assertTrue(panel.preservationLine.contains("세션 결과와 별도"))
    }
}
