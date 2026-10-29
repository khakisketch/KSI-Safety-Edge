package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AT-J1 회귀 — "준비 미완" 규칙.
 *
 * 화면 계약(§2-2): 준비를 건너뛰고 운용에 들어가면 운용 상단에 "준비 미완"을
 * 표시한다. §2는 캐시·로컬 저장·Edge 적용을 **합치지 않는다**.
 *
 * 이 시험이 고정하는 것:
 * ① 시작하지 않은 상태는 "미완"이 아니다(경고하지 않는다).
 * ② 하나라도 준비되지 않으면 "준비 미완" + **어느 축인지**를 말한다.
 * ③ `null`(보고 없음)은 **실패가 아니라 "미확인"**으로 말한다.
 * ④ 세 축이 모두 확인되면 아무 말도 하지 않는다(거짓 준비 완료 0).
 */
class PreparationReadinessTest {

    private fun facts(
        sync: Boolean? = true,
        bundle: Boolean? = true,
        apply: Boolean? = true,
        active: Boolean = true,
    ) = PreparationReadiness.Facts(
        syncConfirmed = sync,
        bundleVerified = bundle,
        applyConfirmed = apply,
        hasActiveFlight = active,
    )

    @Test
    fun no_active_session_is_not_an_incomplete_preparation() {
        val notice = PreparationReadiness.notice(facts(active = false, sync = null))
        assertFalse("시작하지 않은 상태는 경고하지 않는다", notice.show)
    }

    @Test
    fun all_three_facts_confirmed_says_nothing() {
        val notice = PreparationReadiness.notice(facts())
        assertFalse("준비 완료 추정 금지 — 확인할 것이면 침묵", notice.show)
    }

    @Test
    fun each_axis_names_itself() {
        val syncFailed = PreparationReadiness.notice(facts(sync = false))
        assertTrue(syncFailed.show)
        assertTrue(syncFailed.detail.contains("동기화 실패"))
        assertEquals("준비 미완", syncFailed.label)

        val bundleBad = PreparationReadiness.notice(facts(bundle = false))
        assertTrue(bundleBad.detail.contains("번들 미검증"))

        val applyBad = PreparationReadiness.notice(facts(apply = false))
        assertTrue(applyBad.detail.contains("Edge 적용 미완료"))
    }

    @Test
    fun unknown_is_not_failure() {
        val notice = PreparationReadiness.notice(facts(apply = null, sync = null))
        assertTrue(notice.show)
        assertTrue("보고가 없으면 '미확인'으로 말한다", notice.detail.contains("Edge 적용 미확인"))
        assertTrue(notice.detail.contains("동기화 미확인"))
        assertFalse("미확인을 실패로 바꾸지 않는다", notice.detail.contains("실패"))
    }

    @Test
    fun the_status_panel_carries_the_notice() {
        val notice = PreparationReadiness.notice(facts(apply = false))
        val items = OperatorStatus.evaluate(
            OperatorStatus.Input(
                hasActiveFlight = true,
                linkState = "CONNECTED",
                linkSimulated = false,
                aircraftConnected = true,
                internet = "validated",
                recorderState = null,
                mopHeartbeatAgeMs = 3_000L,
                observationAgeMs = 2_000L,
                eventCount = 0,
                artifactsTotal = 0,
                artifactsComplete = 0,
                artifactsSynced = 0,
                pendingAnnotations = 0,
                coveredFrames = 100,
                skippedFrames = 0,
                coreConfigured = true,
                preparationNotice = notice,
            ),
        )
        val item = items.firstOrNull { it.label == "준비 미완" }
        assertTrue("준비 미완이 상태 패널에 오른다", item != null)
        assertTrue("사유가 함께 보인다", item!!.detail.contains("Edge 적용 미완료"))
        assertEquals("조치 문구가 붙는다", "임무 준비에서 확인", item.action)
    }
}
