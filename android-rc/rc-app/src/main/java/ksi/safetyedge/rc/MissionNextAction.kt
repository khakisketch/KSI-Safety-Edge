package ksi.safetyedge.rc

/**
 * Fixed command-dock next-action line (pageMission, 768x480dp density
 * contract): ONE concise line (max 2), no metadata, derived purely from
 * state the app already holds — never a fabricated "준비 완료".
 *
 * Pure Kotlin, no Android: host-tested. Priority order:
 * 1. storage unavailable -> cannot start recording (fact, not readiness);
 * 2. active flight -> recording is underway, check the ops screen;
 * 3. no bundle selected -> select site/bundle first;
 * 4. PREPARING/APPLYING/SESSION_PENDING -> a response is outstanding;
 * 5. PREPARED -> next is Apply; APPLIED -> next is local record start;
 * 6. IDLE with a bundle -> next is Prepare; no controller yet with a
 *    bundle selected -> next is also Prepare (준비 only attaches a
 *    controller for the bundle — no flight record, no recording claim);
 * 7. anything else (STATUS_UNKNOWN, REJECTED, RUNNING or STOPPED without
 *    a flight, future states) -> unknown, check further.
 * Null controller without a selected bundle stays unknown (via rule 3),
 * never ready.
 */
object MissionNextAction {

    enum class Next {
        STORAGE_DOWN,
        RECORDING,
        SELECT_BUNDLE,
        WAITING,
        APPLY,
        START_RECORD,
        PREPARE,
        UNKNOWN,
    }

    data class Input(
        val storageAvailable: Boolean,
        val hasActiveFlight: Boolean,
        val hasSelectedBundle: Boolean,
        /** Null = no controller attached yet (준비 not run) — with a
         *  selected bundle and no flight this means Prepare. */
        val controllerStateName: String?,
    )

    /**
     * Round47 task-first scope metadata (pure, display-only): WHAT is
     * requested, WHO receives it, and WHICH real state actually changes.
     * DJI flight authority, Edge mission work (PREPARE/APPLY/SESSION_START)
     * and the KSI local record are separate scopes — this never merges
     * them and never alters controller state.
     */
    data class ActionMeta(
        /** Recipient/scope, e.g. "Edge" or "KSI 로컬 기록". */
        val target: String,
        /** The requested change, e.g. "번들 적용 요청". */
        val request: String,
        /** The real state that changes on success (never execution). */
        val effect: String,
    )

    /** Scope wording for each [Next]: recipient, requested change, effect. */
    fun meta(next: Next): ActionMeta = when (next) {
        Next.STORAGE_DOWN -> ActionMeta(
            target = "기기 저장소",
            request = "저장소 상태 복구 확인",
            effect = "복구 전까지 기록 시작 불가",
        )
        Next.RECORDING -> ActionMeta(
            target = "운용 화면",
            request = "진행 중 기록 확인",
            effect = "새 기록 시작 없음",
        )
        Next.SELECT_BUNDLE -> ActionMeta(
            target = "현장·번들 선택",
            request = "동기화 후 번들 선택",
            effect = "선택 전까지 Edge 요청 없음",
        )
        Next.WAITING -> ActionMeta(
            target = "Edge",
            request = "요청 응답 대기",
            effect = "응답 전까지 다음 단계 없음",
        )
        Next.APPLY -> ActionMeta(
            target = "Edge",
            request = "번들 적용 요청",
            // Contract §10.2: protocol APPLIED (ACK) must NOT stand for
            // real engine installation or runtime — those need separate
            // confirmation (effective_config_hash / runtime report).
            effect = "적용 응답 확인 · 엔진 설치·실행은 별도 확인",
        )
        Next.START_RECORD -> ActionMeta(
            target = "KSI 로컬 기록",
            request = "로컬 기록 시작",
            effect = "RC 로컬 기록 세션 생성 · DJI 조종권 불변",
        )
        Next.PREPARE -> ActionMeta(
            target = "Edge",
            request = "준비 요청",
            effect = "Edge 번들 준비 응답 확인 · 관측 시작 아님",
        )
        Next.UNKNOWN -> ActionMeta(
            target = "상태 확인",
            request = "추가 확인",
            effect = "미확인 상태로 진행 없음",
        )
    }

    fun decide(input: Input): Next = when {
        !input.storageAvailable -> Next.STORAGE_DOWN
        input.hasActiveFlight -> Next.RECORDING
        !input.hasSelectedBundle -> Next.SELECT_BUNDLE
        input.controllerStateName in setOf("PREPARING", "APPLYING", "SESSION_PENDING") -> Next.WAITING
        input.controllerStateName == "PREPARED" -> Next.APPLY
        input.controllerStateName == "APPLIED" -> Next.START_RECORD
        input.controllerStateName == "IDLE" -> Next.PREPARE
        input.controllerStateName == null -> Next.PREPARE
        else -> Next.UNKNOWN
    }
}
