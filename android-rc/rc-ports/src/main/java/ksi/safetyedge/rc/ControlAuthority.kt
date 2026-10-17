package ksi.safetyedge.rc

/** Control-authority display model (pure Kotlin, no Android/MSDK/PSDK).
 *
 *  Display reference only: who the RC believes is flying. Request and
 *  confirmation are separate signals and must never be collapsed — a request
 *  without confirmation is TRANSITION, never EDGE_AUTO.
 */
enum class Owner { MANUAL, EDGE_AUTO, FAILSAFE, TRANSITION, UNKNOWN }

data class State(
    val owner: Owner,
    val confirmed: Boolean,
    val detail: String,
)

object ControlAuthorityModel {
    fun fromSignals(
        edgeAutoRequested: Boolean,
        edgeAutoConfirmed: Boolean,
        failsafeActive: Boolean,
        rcManualInput: Boolean,
        linkOk: Boolean,
    ): State {
        if (failsafeActive) {
            return State(Owner.FAILSAFE, true, "페일세이프 동작 중")
        }
        if (!linkOk && !edgeAutoRequested && !edgeAutoConfirmed && !rcManualInput) {
            return State(Owner.UNKNOWN, false, "링크 단절 · 상태 미확인")
        }
        if (edgeAutoRequested != edgeAutoConfirmed) {
            return if (edgeAutoRequested) {
                State(Owner.TRANSITION, false, "자동 전환 요청 중 · 미확인")
            } else {
                State(Owner.TRANSITION, false, "자동 해제 확인 중 · 미확인")
            }
        }
        if (edgeAutoRequested && edgeAutoConfirmed && !rcManualInput) {
            return State(Owner.EDGE_AUTO, true, "엣지 자동 비행 확인됨")
        }
        if (rcManualInput) {
            return State(Owner.MANUAL, true, "조종자 입력 우선")
        }
        if (!linkOk) {
            return State(Owner.UNKNOWN, false, "링크 단절 · 상태 미확인")
        }
        return State(Owner.MANUAL, true, "수동 조종")
    }
}
