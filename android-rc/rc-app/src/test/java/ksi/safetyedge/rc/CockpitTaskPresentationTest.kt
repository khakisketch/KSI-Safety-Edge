package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round47 task-first cockpit presentation ([CockpitTaskPresentation]),
 * revised: explicit compact normalization, bounded support + conditional
 * problem line, visible receipt facts, permanent gate. The compact surfaces
 * shorten NOTHING into a healthier state — counts, hashes, raw codes and
 * dev detail stay in the status/details panel.
 */
class CockpitTaskPresentationTest {

    private fun input(
        hasActiveFlight: Boolean = true,
        runtimeStateName: String? = "RUNNING",
        edgeSeverity: OperatorStatus.Severity = OperatorStatus.Severity.INFO,
        edgeLinkDown: Boolean = false,
        videoInputLost: Boolean = false,
        recorderValue: String = "쓰기 중",
        recordFailure: Boolean = false,
        unsyncedCount: Int? = 2,
        coreUnconfigured: Boolean = false,
        observationGapFrames: Long? = null,
        observationMissing: Boolean = false,
        observationStale: Boolean = false,
        preparationIncomplete: Boolean = false,
        zeroRulesNoJudgment: Boolean = false,
        shadowOnlyNote: Boolean = false,
        videoNeedsRecovery: Boolean = false,
    ) = CockpitTaskPresentation.Input(
        hasActiveFlight = hasActiveFlight,
        runtimeStateName = runtimeStateName,
        edgeSeverity = edgeSeverity,
        edgeLinkDown = edgeLinkDown,
        videoInputLost = videoInputLost,
        recorderValue = recorderValue,
        recordFailure = recordFailure,
        unsyncedCount = unsyncedCount,
        coreUnconfigured = coreUnconfigured,
        observationGapFrames = observationGapFrames,
        observationMissing = observationMissing,
        observationStale = observationStale,
        preparationIncomplete = preparationIncomplete,
        zeroRulesNoJudgment = zeroRulesNoJudgment,
        shadowOnlyNote = shadowOnlyNote,
        videoNeedsRecovery = videoNeedsRecovery,
    )

    @Test
    fun request_reason_is_app_specific_and_not_a_permanent_surface() {
        val reason = CockpitTaskPresentation.requestDetail()
        assertEquals(CockpitTaskPresentation.REQUEST_DETAIL, reason)
        // App request vs physical controller: scoped to this app, with an
        // explicit physical-RC disclaimer.
        assertTrue(reason.contains("앱 비행 요청 불가"))
        assertTrue(reason.contains("제어 주체 미확인"))
        assertTrue(reason.contains("물리 조종기"))
        // Never merged with ACTIVE approval, never a universal lock banner.
        assertFalse(reason.contains("ACTIVE"))
        assertFalse(reason.contains("관제 시작 잠김"))
        assertFalse(reason.contains("승인 미통과"))
        // Never on the persistent compact surfaces.
        val task = CockpitTaskPresentation.present(input())
        assertFalse(task.contentDescription.contains(reason))
        assertFalse(task.edgeLine.contains("잠김"))
        assertFalse(task.supportLine.contains("잠김"))
        assertTrue(task.contentDescription.contains(task.edgeLine))
        // Full proxy/Core/sync source stays in the description, not the line.
        assertTrue(task.contentDescription.contains("RC 프록시"))
    }

    @Test
    fun six_states_normalize_without_counts_hashes_or_raw_codes() {
        val expected = mapOf(
            "REQUEST_ACCEPTED" to "요청 수신 · 실행 시작 전",
            "CONFIG_APPLIED" to "설정 적용됨 · 관측 시작 전",
            "STARTING" to "관측 기동 중",
            "RUNNING" to "관측 실행 중",
            "DEGRADED" to "관측 저하",
            "STOPPED" to "관측 중지",
        )
        for ((state, line) in expected) {
            val task = CockpitTaskPresentation.present(input(runtimeStateName = state))
            assertEquals("state $state", line, task.edgeLine)
        }
        // Even a RUNNING report never smuggles processed counts, hashes,
        // reason codes or timestamps into the compact line.
        val running = CockpitTaskPresentation.present(input(runtimeStateName = "RUNNING"))
        for (banned in listOf("프레임", "해시", "video_", "telemetry_", "ms 전", "미확인 ·")) {
            assertFalse("compact RUNNING must not contain '$banned': ${running.edgeLine}", running.edgeLine.contains(banned))
        }
    }

    @Test
    fun unreported_garbage_and_no_flight_stay_unknown() {
        for (state in listOf(null, "SOME_FUTURE_STATE", "")) {
            val task = CockpitTaskPresentation.present(input(runtimeStateName = state))
            assertEquals("state $state", EdgeRuntimePanel.UNKNOWN_LINE, task.edgeLine)
        }
        val noFlight = CockpitTaskPresentation.present(
            input(hasActiveFlight = false, runtimeStateName = "RUNNING"),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, noFlight.edgeLine)
    }

    @Test
    fun link_loss_and_input_loss_stay_distinct() {
        val link = CockpitTaskPresentation.present(
            input(runtimeStateName = "RUNNING", edgeLinkDown = true),
        )
        assertEquals("Edge 통신 단절", link.edgeLine)
        assertEquals(OperatorStatus.Severity.PROBLEM, link.edgeSeverity)
        assertTrue(link.alertLine!!.contains("링크 단절"))

        val video = CockpitTaskPresentation.present(
            input(runtimeStateName = "RUNNING", videoInputLost = true),
        )
        assertEquals("관측 실행 중 · 영상 입력 단절", video.edgeLine)
        assertFalse(video.edgeLine.contains("통신 단절"))

        val both = CockpitTaskPresentation.present(
            input(runtimeStateName = "RUNNING", edgeLinkDown = true, videoInputLost = true),
        )
        assertEquals("Edge 통신 단절 · 영상 입력 단절", both.edgeLine)
    }

    @Test
    fun support_line_is_concise_session_state_plus_positive_pending() {
        val task = CockpitTaskPresentation.present(
            input(preparationIncomplete = true, observationGapFrames = 4L),
        )
        // Concise: KSI session state + positive pending only. Proxy source,
        // zero counts, Core flags and problem tokens stay out of the line.
        assertTrue(task.supportLine.contains("KSI 기록 중"))
        assertTrue(task.supportLine.contains("미동기화 2건"))
        assertFalse(task.supportLine.contains("RC 프록시"))
        assertFalse(task.supportLine.contains("Core"))
        assertFalse(task.supportLine.contains("미동기화 0건"))
        assertFalse(task.supportLine.contains("준비 미완"))
        assertFalse(task.supportLine.contains("관측 공백"))
        // Full source stays in the description.
        assertTrue(task.contentDescription.contains("RC 프록시"))
    }

    @Test
    fun problem_line_surfaces_safety_tokens_not_explanations() {
        val task = CockpitTaskPresentation.present(
            input(
                preparationIncomplete = true,
                observationGapFrames = 4L,
                observationStale = true,
            ),
        )
        val alert = task.alertLine!!
        assertTrue(alert.contains("준비 미완"))
        assertTrue(alert.contains("관측 공백 4프레임"))
        assertTrue(alert.contains("관측 수신 지연"))
        // No full explanations, no axis names on the compact line.
        assertFalse(alert.contains("동기화 미확인"))
        assertFalse(alert.contains("Edge 적용"))
    }

    @Test
    fun missing_receipt_is_visible_not_accessible_only() {
        val missing = CockpitTaskPresentation.present(
            input(observationMissing = true, observationGapFrames = null),
        )
        assertTrue(missing.alertLine!!.contains("관측 미수신"))

        val clean = CockpitTaskPresentation.present(input())
        assertNull(clean.alertLine)
    }

    @Test
    fun preparation_incomplete_stays_visible_without_axes() {
        val task = CockpitTaskPresentation.present(input(preparationIncomplete = true))
        assertTrue(task.alertLine!!.contains("준비 미완"))
    }

    @Test
    fun zero_rules_is_a_visible_state_not_a_failure() {
        val task = CockpitTaskPresentation.present(input(zeroRulesNoJudgment = true))
        assertTrue(task.edgeLine.contains("위험 규칙 0건"))
        assertTrue(task.edgeLine.contains("위험 판정 없음"))
        assertFalse(task.edgeLine.contains("실패"))
    }

    @Test
    fun shadow_only_never_reads_as_warning() {
        val task = CockpitTaskPresentation.present(input(shadowOnlyNote = true))
        assertTrue(task.edgeLine.contains("SHADOW"))
        assertTrue(task.edgeLine.contains("경고 아님"))
        assertFalse(task.edgeLine.contains("ACTIVE"))
    }

    @Test
    fun unsynced_zero_and_unknown_stay_out_of_the_concise_line() {
        val task = CockpitTaskPresentation.present(
            input(unsyncedCount = 2, coreUnconfigured = true),
        )
        assertTrue(task.supportLine.contains("미동기화 2건"))
        assertFalse(task.supportLine.contains("Core 미설정"))

        val zero = CockpitTaskPresentation.present(input(unsyncedCount = 0))
        assertFalse(zero.supportLine.contains("미동기화 0건"))

        val unknown = CockpitTaskPresentation.present(input(unsyncedCount = null))
        assertFalse(unknown.supportLine.contains("동기화 상태 미확인"))
        assertFalse(unknown.supportLine.contains("미동기화 0건"))
        // Full sync source (incl. zero/unknown/Core) stays in the description.
        assertTrue(unknown.contentDescription.contains("동기화 상태 미확인"))
        assertTrue(task.contentDescription.contains("Core 미설정"))
        assertTrue(task.contentDescription.contains("미동기화 2건"))
    }

    @Test
    fun record_failure_stays_problem_and_first_token() {
        val task = CockpitTaskPresentation.present(
            input(recordFailure = true, recorderValue = "녹화 오류"),
        )
        assertTrue(task.supportLine.contains("프록시 기록 오류"))
        assertEquals(OperatorStatus.Severity.PROBLEM, task.edgeSeverity)
        assertTrue(task.alertLine!!.startsWith("녹화 오류"))
    }

    @Test
    fun all_faults_at_once_drops_no_token() {
        val task = CockpitTaskPresentation.present(
            input(
                runtimeStateName = "DEGRADED",
                edgeLinkDown = true,
                videoInputLost = true,
                recordFailure = true,
                recorderValue = "녹화 오류",
                unsyncedCount = 7,
                coreUnconfigured = true,
                observationGapFrames = 12L,
                observationMissing = false,
                observationStale = true,
                preparationIncomplete = true,
                zeroRulesNoJudgment = true,
                shadowOnlyNote = true,
            ),
        )
        assertEquals("Edge 통신 단절 · 영상 입력 단절 · 위험 규칙 0건 — 위험 판정 없음 · SHADOW 경고 아님", task.edgeLine)
        val alert = task.alertLine!!
        for (token in listOf("녹화 오류", "링크 단절", "영상 입력 단절", "준비 미완", "관측 공백 12프레임", "관측 수신 지연")) {
            assertTrue("alert must keep '$token': $alert", alert.contains(token))
        }
        assertTrue(task.supportLine.contains("KSI 기록 중"))
        assertTrue(task.supportLine.contains("프록시 기록 오류"))
        assertTrue(task.supportLine.contains("미동기화 7건"))
        assertFalse(task.supportLine.contains("Core 미설정"))
        assertFalse(task.supportLine.contains("RC 프록시"))
        assertTrue(task.contentDescription.contains("RC 프록시 녹화 오류"))
        assertTrue(task.contentDescription.contains("Core 미설정"))
        assertEquals(OperatorStatus.Severity.PROBLEM, task.edgeSeverity)
    }

    @Test
    fun huge_counters_render_verbatim_without_dropping_tokens() {
        val task = CockpitTaskPresentation.present(
            input(unsyncedCount = Int.MAX_VALUE, observationGapFrames = Long.MAX_VALUE),
        )
        assertTrue(task.supportLine.contains("미동기화 ${Int.MAX_VALUE}건"))
        assertTrue(task.alertLine!!.contains("관측 공백 ${Long.MAX_VALUE}프레임"))
    }

    @Test
    fun video_recovery_action_only_when_disconnected() {
        assertTrue(CockpitTaskPresentation.present(input(videoNeedsRecovery = true)).showVideoRecovery)
        assertFalse(CockpitTaskPresentation.present(input(videoNeedsRecovery = false)).showVideoRecovery)
    }

    @Test
    fun no_session_says_so_without_claiming_health() {
        val task = CockpitTaskPresentation.present(
            input(hasActiveFlight = false, runtimeStateName = null, unsyncedCount = 0),
        )
        assertEquals("KSI 기록 대기", task.supportLine)
        assertTrue(task.edgeLine.contains("미확인"))
        assertNull(task.alertLine)
    }
}
