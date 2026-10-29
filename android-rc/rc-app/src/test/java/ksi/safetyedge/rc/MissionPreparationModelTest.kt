package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the preparation-screen binding (screen contract §2):
 * zone revisions + ids, bundle digest, and the apply state stay THREE
 * SEPARATE facts — never merged into one "준비 완료". Apply
 * failure/rejection is shown honestly.
 */
class MissionPreparationModelTest {

    private fun input(
        syncResult: MissionPreparationModel.SyncResult = MissionPreparationModel.SyncResult.SUCCESS_LOCAL,
        siteId: String? = "site-1",
        bundleId: String? = "bundle-abcdef",
        bundleDigest: String? = "0123456789abcdef",
        verified: Boolean = true,
        missionRevisionId: String? = "rev-1234",
        zoneRevisionIds: List<String>? = listOf("zone-r1-aaaa", "zone-r2-bbbb"),
        controllerState: String? = "APPLIED",
        applyConfirmed: Boolean? = true,
        applicationId: String? = "app-12345678",
        rejectionReasons: List<String> = emptyList(),
        linkUp: Boolean? = true,
        hasActiveFlight: Boolean = false,
        restoredPendingBundleId: String? = null,
        restoredPendingDecidedAtUtc: String? = null,
    ) = MissionPreparationModel.Input(
        syncResult = syncResult,
        siteId = siteId,
        bundleId = bundleId,
        bundleDigest = bundleDigest,
        bundleDigestVerified = verified,
        missionRevisionId = missionRevisionId,
        zoneRevisionIds = zoneRevisionIds,
        controllerState = controllerState,
        applyConfirmed = applyConfirmed,
        applicationId = applicationId,
        rejectionReasons = rejectionReasons,
        linkUp = linkUp,
        hasActiveFlight = hasActiveFlight,
        restoredPendingBundleId = restoredPendingBundleId,
        restoredPendingDecidedAtUtc = restoredPendingDecidedAtUtc,
    )

    @Test
    fun restored_pending_attempt_is_shown_for_explicit_rerun_never_autoresumed() {
        val without = MissionPreparationModel.render(input())
        assertNull(without.restoredLine)

        val with = MissionPreparationModel.render(
            input(
                controllerState = null,
                applyConfirmed = null,
                restoredPendingBundleId = "bundle-abcdef",
                restoredPendingDecidedAtUtc = "2026-10-27T00:00:00Z",
            ),
        )
        assertNotNull(with.restoredLine)
        assertTrue(with.restoredLine!!.contains("bundle-a"))
        assertTrue(with.restoredLine!!.contains("다시 준비하십시오"))
        assertTrue(with.restoredLine!!.contains("자동 재전송 없음"))
        assertEquals(
            "재시작 전 준비 요청(미확인) · 번들 bundle-a · " +
                "응답 확인 안 됨(2026-10-27T00:00:00Z 요청) · 다시 준비하십시오(자동 재전송 없음)",
            with.restoredLine,
        )
    }

    @Test
    fun confirmed_apply_keeps_three_separate_facts() {        val panel = MissionPreparationModel.render(input())

        assertEquals("동기화 · 성공(로컬 반영) · 서버 상태 별도", panel.cacheLine)
        assertTrue(panel.localLine.startsWith("로컬 저장 · 번들 bundle-a"))
        assertTrue(panel.localLine.contains("0123456789ab"))
        assertTrue(panel.applyLine.startsWith("Edge 적용 · APPLIED 확인"))
        assertTrue(panel.applyLine.contains("app-1234"))
        assertTrue(
            "cache/local/apply must never merge into one ready line",
            !panel.cacheLine.contains("준비 완료") &&
                !panel.localLine.contains("준비 완료") &&
                !panel.applyLine.contains("준비 완료"),
        )
    }

    @Test
    fun apply_rejection_is_shown_with_reasons() {
        val panel = MissionPreparationModel.render(
            input(
                controllerState = "REJECTED",
                applyConfirmed = false,
                rejectionReasons = listOf("policy_install_rejected"),
            ),
        )

        assertEquals("Edge 적용 · 거부됨 · policy_install_rejected", panel.applyLine)
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.severity)
        assertTrue("rejection must not read as applied", !panel.applyLine.contains("APPLIED"))
    }

    @Test
    fun unknown_apply_state_demands_reconcile_not_assumption() {
        val panel = MissionPreparationModel.render(
            input(controllerState = "STATUS_UNKNOWN", applyConfirmed = null),
        )

        assertEquals("Edge 적용 · 상태 미확인 · 재조정 필요", panel.applyLine)
    }

    @Test
    fun missing_apply_context_is_unknown_not_ready() {
        val panel = MissionPreparationModel.render(
            input(controllerState = null, applyConfirmed = null),
        )

        assertEquals("Edge 적용 · 미확인 · 적용 응답 없음", panel.applyLine)
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.severity)
    }

    @Test
    fun link_down_apply_is_queued_not_estimated() {
        val panel = MissionPreparationModel.render(
            input(controllerState = "APPLYING", applyConfirmed = null, linkUp = false),
        )

        assertTrue(panel.applyLine.contains("링크 끊김"))
        assertTrue(panel.applyLine.contains("추정 안 함"))
    }

    @Test
    fun zone_revisions_list_ids_never_zero_for_absent_basis() {
        val panel = MissionPreparationModel.render(input())

        assertTrue(panel.zoneLine.contains("구역 리비전 2개"))
        assertTrue(panel.zoneLine.contains("zone-r1-"))
        assertTrue(panel.zoneLine.contains("zone-r2-"))
        assertEquals("미션 리비전 · rev-1234", panel.revisionLine)

        val absent = MissionPreparationModel.render(
            input(zoneRevisionIds = null, missionRevisionId = null, bundleId = null),
        )
        assertEquals("구역 리비전 · 미확인(번들 미선택 또는 스냅샷 없음)", absent.zoneLine)
        assertEquals("미션 리비전 · 미확인", absent.revisionLine)
        assertEquals("로컬 저장 · 선택된 번들 없음", absent.localLine)
        assertTrue(
            "absent basis must never render as 0개",
            !absent.zoneLine.contains("0개"),
        )
    }

    @Test
    fun unverified_bundle_cannot_apply() {
        val panel = MissionPreparationModel.render(input(verified = false))

        assertTrue(panel.localLine.contains("digest 미검증 · 적용 불가"))
    }

    @Test
    fun sync_states_keep_the_local_only_chip_wording() {
        assertEquals(
            "동기화 · 미확인 · 로컬 반영",
            MissionPreparationModel.cacheLine(MissionPreparationModel.SyncResult.UNKNOWN),
        )
        assertEquals(
            "동기화 · 성공(로컬 반영) · 서버 상태 별도",
            MissionPreparationModel.cacheLine(MissionPreparationModel.SyncResult.SUCCESS_LOCAL),
        )
        assertEquals(
            "동기화 · 실패 · 로컬 반영은 알 수 없음",
            MissionPreparationModel.cacheLine(MissionPreparationModel.SyncResult.FAILURE),
        )
    }

    @Test
    fun map_gap_is_stated_not_fabricated() {
        val panel = MissionPreparationModel.render(input())

        assertTrue(panel.mapNote.contains("미구현"))
    }
}
