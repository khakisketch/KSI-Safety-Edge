package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ksi.safetyedge.rc.TakeoverRequestTracker.Outcome

/**
 * AT-01 ~ AT-06 from the operation screen contract v1
 * (docs/plans/2026-10-17-rc-operations-screen-contract.md §6).
 *
 * The pure models have no device-command channel, so "장치 명령 0" and
 * cross-state non-interference are structural here; the adapter stage
 * re-verifies them against real senders. Each case still checks the changed
 * scope: only the contract's allowed states move.
 */
class OperationContractAcceptanceTest {

    @Test
    fun at01_ack_save_failure_keeps_unacknowledged() {
        val acks = WarningAcknowledgements()
        acks.acknowledge("alert-1")
        acks.onSaved("alert-1", ok = false, reason = "저장 실패")
        // 확인 이력만 되돌아간다: 위험·경고 축은 이 모델의 입력이 아니므로 불변(구조적).
        assertEquals(AckState.UNACKNOWLEDGED, acks.state("alert-1"))
        assertEquals("저장 실패", acks.lastError("alert-1"))
    }

    @Test
    fun at02_shadow_candidate_source_problem_is_shown() {
        val missing = ShadowCandidateSelection.select("c1", sourcePresent = false, sourceFresh = false)
        assertEquals(ShadowCandidateSelection.SourceStatus.MISSING, missing.source)
        val stale = ShadowCandidateSelection.select("c2", sourcePresent = true, sourceFresh = false)
        assertEquals(ShadowCandidateSelection.SourceStatus.STALE, stale.source)
        // 선택은 어떤 정책·장치 상태도 만들지 않는다: 모델에 그런 출력이 없다(구조적).
    }

    @Test
    fun at03_status_report_gap_does_not_infer_takeover_completion() {
        val tracker = TakeoverRequestTracker()
        tracker.begin("req-1")
        // 보고/응답이 끊기면 요청 결과는 '미확인'으로만 전이한다.
        tracker.onLinkLost()
        assertEquals(Outcome.UNKNOWN, tracker.outcome("req-1"))
        // 이후 실제 수동 제어 보고가 새로 확인되어도 — 현재 제어권(별도 모델)만 갱신된다.
        val authority = ControlAuthorityModel.fromSignals(
            edgeAutoRequested = false, edgeAutoConfirmed = false,
            failsafeActive = false, rcManualInput = true, linkOk = true,
        )
        assertEquals(Owner.MANUAL, authority.owner)
        // 이전 요청의 결과는 자동 성공 처리되지 않는다 (수신 상태 신선도 표시는
        // FlightTelemetryModel 소관이며 별도 테스트다).
        assertEquals(Outcome.UNKNOWN, tracker.outcome("req-1"))
        assertTrue(tracker.needsReconcile("req-1"))
    }

    @Test
    fun at04a_lost_response_then_new_manual_report_keeps_request_unknown() {
        val tracker = TakeoverRequestTracker()
        tracker.begin("req-1")
        tracker.onLinkLost() // 응답 유실
        assertEquals(Outcome.UNKNOWN, tracker.outcome("req-1"))

        // 새 수동 제어 보고: 현재 제어권은 보고에 따라 갱신된다.
        val authority = ControlAuthorityModel.fromSignals(
            edgeAutoRequested = false, edgeAutoConfirmed = false,
            failsafeActive = false, rcManualInput = true, linkOk = true,
        )
        assertEquals(Owner.MANUAL, authority.owner)
        assertTrue(authority.confirmed)

        // 해당 요청 결과는 미확인 유지 — 현재 제어권과 명령 처리 결과는 별개다.
        assertEquals(Outcome.UNKNOWN, tracker.outcome("req-1"))
        assertTrue(tracker.needsReconcile("req-1"))
    }

    @Test
    fun at04b_late_response_updates_only_the_older_request() {
        val tracker = TakeoverRequestTracker()
        tracker.begin("req-1")
        tracker.onLinkLost()
        tracker.begin("req-2")

        // 이전 요청의 뒤늦은 응답.
        assertTrue(tracker.onResponse("req-1", accepted = true))
        assertEquals(Outcome.ACCEPTED, tracker.outcome("req-1"))
        // 새 요청은 그대로 대기 중이다.
        assertEquals(Outcome.PENDING, tracker.outcome("req-2"))
        assertTrue(tracker.awaiting("req-2"))
        // 자동 재전송·자동 응답 생성 없음: ignored에도 아무 것도 추가되지 않았다.
        assertEquals(0, tracker.ignored.size)
    }

    @Test
    fun at05_view_load_failure_changes_only_view_state() {
        val loading = ObserveView.switch(ObserveView.initial, ObserveView.Mode.MAP)
        assertEquals(ObserveView.Mode.MAP, loading.mode)
        assertEquals(ObserveView.Load.LOADING, loading.load)

        val failed = ObserveView.onLoaded(loading, ok = false)
        assertEquals(ObserveView.Load.ERROR, failed.load)
        assertEquals(ObserveView.Mode.MAP, failed.mode)
        // 보기 전환은 비행·제어권·AI·기록을 입력으로 받지 않는다(구조적).
        // 프로토타입·어댑터 단계에서 교차 상태 불변을 재검증한다.
    }

    @Test
    fun at06_detection_fact_preserved_while_overlay_and_map_held() {
        val v = ObservationValidity.evaluate(
            hasDetection = true, hasSource = true,
            frameCorrespondence = false, geoConditionsMet = false,
            appliedZoneConfirmed = false,
        )
        assertEquals(ObservationValidity.Stage.SHOWN, v.detection)
        assertEquals(ObservationValidity.Stage.HELD, v.liveOverlay)
        assertEquals(ObservationValidity.Stage.HELD, v.mapRelation)
        assertTrue(v.anyHeld)
        assertFalse(v.mapRelation == ObservationValidity.Stage.SHOWN)
    }
}
