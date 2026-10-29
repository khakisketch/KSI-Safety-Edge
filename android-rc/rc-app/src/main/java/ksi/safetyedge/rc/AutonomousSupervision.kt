package ksi.safetyedge.rc

/**
 * Autonomous-supervision axes for the observe screen (round51).
 *
 * Pure Kotlin, no Android: host-tested. Each axis is derived ONLY from
 * facts the RC actually holds — the bound Edge runtime report
 * ([EdgeRuntimePanel]/`MissionController.boundRuntimeStatus`), the current
 * recorder ([VideoRecorder]), the RC receipt store, and the aircraft
 * connection read. The legacy `running` bool is never an input here.
 *
 * Honesty rules (2026-10-23 autonomous-observation stage 1 + supervision
 * stage 3):
 * - AI execution comes from the actual bound runtime state plus input
 *   health — never from "start requested" and never from a bare counter.
 * - Camera owner / auto permission / action health are three separate
 *   facts. Absent an actual report each reads UNKNOWN ("미확인") — never
 *   guessed. Manual camera work never stops analysis or evidence
 *   preservation (stated on the axis, not gated).
 * - Backend assessment / pre-post context facts are not fields of the
 *   frozen MOP: they always read UNKNOWN here. Received counts or local
 *   preservation are never invented from running/counter values.
 * - Preservation keeps three separate facts: Manifold local preservation
 *   (fixture `local_state`, UNKNOWN without a real path), RC receipt
 *   (store counts — Android actual received MOP facts stay the native
 *   source), Core store (sync receipts; `COMMITTED` only for a verified
 *   receipt). They are never merged into one "complete"; `SEALED` with an
 *   incomplete context is possible.
 * - SHADOW is a candidate, never an Incident and never an Alert; no
 *   ACTIVE approval is enabled here. Zero events/observations is not safe.
 * - No auto-response: this model never produces a confirm/defer/forward
 *   record. Operator confirm/defer/forward stays optional, in record or
 *   exception detail ([CandidateResponse]).
 */
object AutonomousSupervision {

    data class Input(
        val hasActiveFlight: Boolean,
        /**
         * Bound Edge runtime state name ("REQUEST_ACCEPTED" … "STOPPED",
         * null when the Edge never reported a bound install). Unbound or
         * foreign reports must arrive here as null — display-only reports
         * are never execution evidence.
         */
        val runtimeStateName: String?,
        /** True once a bound MISSION_STATUS with runtime fields arrived. */
        val runtimeSeen: Boolean,
        /**
         * The Edge reported §3 runtime fields, but none bound to this
         * flight's install (foreign digest, missing install hash, or a
         * revalidation demand): display-only, never execution evidence.
         */
        val unboundReportSeen: Boolean = false,
        /** DroneLink CONNECTED; null = unknown, never guessed healthy. */
        val linkUp: Boolean?,
        /** True = video input loss reported (degraded reason or stale). */
        val videoInputLost: Boolean,
        /** Bound `input_state.engine` ("ok"/…); null = unreported. */
        val engineState: String?,
        /**
         * Latched frame-processing evidence
         * (`executionFacts().frameProcessingObserved`): null = the Edge
         * never reported usable inference evidence.
         */
        val frameProcessingObserved: Boolean?,
        /** Bound processed-frame count; null = unreported (never zero). */
        val processedFrames: Long?,
        /**
         * Actual camera control owner in the fixture vocabulary
         * (`UNKNOWN|OPERATOR|DJI_MISSION|KSI`; null = no actual report on
         * the RC today → UNKNOWN, never guessed). Only an explicit SIM
         * scenario may supply `OPERATOR` — and only with
         * [cameraOwnerConfirmed]; the observe screen never invents it.
         */
        val cameraReportedOwner: String?,
        val cameraOwnerConfirmed: Boolean,
        /**
         * Auto-camera permission in the fixture vocabulary
         * (`UNKNOWN|UNAVAILABLE|RELEASED|ALLOWED`; null = unknown). Host A
         * keeps `UNAVAILABLE`/`NOT_CONFIGURED`: the camera was not run.
         */
        val autoPermission: String?,
        /**
         * Camera adjustment health in the fixture vocabulary
         * (`UNKNOWN|NOT_CONFIGURED|IDLE|OBSERVING|INTERRUPTED|FAILED`;
         * null = no actual report). Analysis state is independent of it.
         */
        val adjustmentState: String?,
        /** Current aircraft availability (null = unknown). */
        val aircraftConnected: Boolean?,
        /** RC receipt: observation records (null = unknown, e.g. store down). */
        val rcObservationCount: Int?,
        /** RC receipt: event records (null = unknown). */
        val rcEventCount: Int?,
        val coreConfigured: Boolean,
        /** Core-committed artifacts (null = unknown). */
        val coreSyncedCount: Int?,
        /** Local artifacts in scope (null = unknown). */
        val coreTotalCount: Int?,
        /**
         * Announced artifacts withheld from the flight-ingest manifest
         * (outside the frozen source_kind enum — e.g. sealed machine
         * assessments). Retained locally, Core-receivable only through the
         * collection path. Null = unknown; explicit 0 = none withheld.
         */
        val coreExcludedCount: Int? = null,
        /**
         * Manifold local preservation in the fixture vocabulary
         * (`UNKNOWN|COLLECTING|SEALED|INCOMPLETE|FAILED`; null = no report
         * field in the frozen MOP → UNKNOWN, never invented from
         * running/counter). `SEALED` with an incomplete context is
         * possible — the two stay separate.
         */
        val manifoldLocalState: String? = null,
        /**
         * Pre-post context completeness (`UNKNOWN|COMPLETE|INCOMPLETE`;
         * null = unknown until a real path carries it).
         */
        val contextState: String? = null,
        /**
         * Backend assessment mode (`SHADOW|…`; null = unknown until a real
         * path carries it). Android actual received MOP facts stay the
         * native source; this never comes from receipt counts.
         */
        val assessmentMode: String? = null,
        /**
         * Backend assessment disposition (`UNKNOWN|ASSESSED|UNDETERMINED|
         * CONDITION_MET|CONDITION_NOT_MET|NOT_EVALUATED`; null = unknown).
         * Even `CONDITION_MET` in SHADOW is never an Incident or Alert.
         */
        val assessmentDisposition: String? = null,
        /**
         * Explicitly supplied assessment reason codes (e.g.
         * `no_applicable_rules`, `camera_manual_override`; empty = absent).
         * Rendered in the assessment detail only, and only alongside a
         * known disposition — never inferred from receipt counters, camera
         * UNKNOWN, or storage state, and never inducing alerts or
         * intervention obligations. Null/absent and empty both mean "no
         * invented reason".
         */
        val assessmentReasonCodes: List<String> = emptyList(),
        /**
         * Sealed machine assessments actually held by the RC store (the
         * artifact-plane transport path — NOT frozen-MOP fields). All
         * three null = unknown (e.g. store down); explicit zeros = "none
         * announced", which is unreceived, never 0건-as-safe. Supplied by
         * the caller from `assessmentsFor`/`pendingAssessmentCount`, never
         * derived from observation/event counters here.
         */
        val assessmentAssessed: Int? = null,
        val assessmentUndetermined: Int? = null,
        val assessmentPending: Int? = null,
        /** True = only SHADOW/object candidates, no ACTIVE. */
        val shadowOnly: Boolean,
        /** True = an approved Alert exists (separate Alert policy). */
        val hasApprovedAlert: Boolean,
        /** True = the current RC proxy record failed. */
        val recordFailure: Boolean,
        /** False = local KSI persistence unusable. */
        val storageAvailable: Boolean,
        val storageDetail: String? = null,
    )

    data class Axis(
        val label: String,
        val value: String,
        val detail: String,
        val severity: OperatorStatus.Severity,
        /** What the operator should do; null when nothing is required. */
        val action: String?,
    ) {
        val contentDescription: String
            get() = buildString {
                append(label).append(": ").append(value)
                if (detail.isNotBlank()) append(" · ").append(detail)
                action?.let { append(" · 다음 조치: ").append(it) }
                append(".")
            }
    }

    data class Panel(
        val ai: Axis,
        val camera: Axis,
        val preservation: Axis,
        val assessment: Axis,
        val action: Axis,
    )

    /**
     * SHADOW is never an Incident and never an Alert — fixed wording shared
     * by the assessment and action axes so the two can never drift apart.
     */
    const val SHADOW_NOTE = "SHADOW 후보는 확정 Incident·운용 Alert 아님"

    /** Zero received events/observations is a fact, not safety. Never "Incident". */
    const val ZERO_NOTE = "수신 이벤트 0건·관측 0건 ≠ 안전 확인"

    /** Manual camera work never stops analysis or evidence preservation. */
    const val MANUAL_CAMERA_NOTE = "수동 카메라 조작은 분석·증거 보존을 중지하지 않음"

    fun render(input: Input): Panel = Panel(
        ai = aiAxis(input),
        camera = cameraAxis(input),
        preservation = preservationAxis(input),
        assessment = assessmentAxis(input),
        action = actionAxis(input),
    )

    /**
     * AI execution from the actual bound runtime plus input health. The
     * legacy `running` bool is not an input and can never confirm
     * execution here; a bare processed-frame counter without engine-ok
     * never confirms analysis either.
     *
     * RC local storage failure never erases independent, actually received
     * runtime evidence and never implies the AI stopped: when a bound
     * runtime fact was supplied it renders with its source, plus an RC
     * store note. Only a truly absent source stays unknown.
     */
    private fun aiAxis(input: Input): Axis {
        // RC-side limit, stated alongside — never a substitute for the
        // execution fact itself.
        fun Axis.withStoreNote(): Axis {
            if (input.storageAvailable) return this
            return copy(
                detail = "$detail · ${StorageHealth.UNAVAILABLE_DETAIL}",
                action = action ?: StorageHealth.UNAVAILABLE_ACTION,
            )
        }
        if (!input.hasActiveFlight) {
            return Axis(
                "AI 실행",
                "실행 미확인",
                "활성 세션 없음 · Edge 보고 대기",
                OperatorStatus.Severity.INFO,
                null,
            ).withStoreNote()
        }
        if (input.linkUp == false) {
            return Axis(
                "AI 실행",
                "통신 단절 · 실행 상태 미확인",
                "현재 상태를 정상으로 추정하지 않음 · 마지막 보고 이후 실행을 주장하지 않음",
                OperatorStatus.Severity.PROBLEM,
                "링크 복구 후 MISSION_STATUS 대조",
            ).withStoreNote()
        }
        val state = input.runtimeStateName
        if (state == null || !input.runtimeSeen) {
            if (input.unboundReportSeen) {
                return Axis(
                    "AI 실행",
                    "Edge 보고 있음 · 바인딩 미확인",
                    "표시만 · 실행 상태로 채택 안 함 · 부재는 false/0이 아님",
                    OperatorStatus.Severity.INFO,
                    "Edge 보고 후 MISSION_STATUS 확인",
                ).withStoreNote()
            }
            return Axis(
                "AI 실행",
                EdgeRuntimePanel.UNKNOWN_LINE,
                "Edge 보고 없음 · 부재는 false/0이 아님",
                OperatorStatus.Severity.INFO,
                "Edge 보고 후 MISSION_STATUS 확인",
            ).withStoreNote()
        }
        val engine = when (input.engineState) {
            null -> "엔진 상태 미확인"
            "ok" -> "엔진 ok"
            else -> "엔진 ${input.engineState}"
        }
        val analysis = when {
            // The counter/latch alone never confirms analysis: without
            // engine-ok it tracks frame attempts, not successful inference.
            input.frameProcessingObserved == true && input.engineState == "ok" ->
                "분석 수행 확인(프레임 처리+엔진 ok)"
            input.frameProcessingObserved == true ->
                "처리 보고 있음 · 분석 성공 미확인(엔진 미확인)"
            input.frameProcessingObserved == false -> "분석 수행 확인 안 됨"
            else -> "분석 수행 미확인"
        }
        val videoNote = if (input.videoInputLost) " · 영상 입력 단절(해당 공간 평가 보류)" else ""
        // Execution-report wording: only engine-ok + observed processing may
        // read as validated running analysis. Anything less is a runtime
        // state report — the headline must not imply analysis was validated.
        val analysisConfirmed =
            input.frameProcessingObserved == true && input.engineState == "ok"
        val reported = when (state) {
            "RUNNING" -> Axis(
                "AI 실행",
                if (analysisConfirmed) "관측 실행 중$videoNote" else "관측 실행 보고됨$videoNote",
                "$engine · $analysis · $MANUAL_CAMERA_NOTE",
                if (input.videoInputLost) {
                    OperatorStatus.Severity.ATTENTION
                } else {
                    OperatorStatus.Severity.INFO
                },
                if (input.videoInputLost) "영상 입력·Edge 상태 확인" else null,
            )
            "DEGRADED" -> Axis(
                "AI 실행",
                "관측 저하$videoNote",
                "$engine · $analysis · $MANUAL_CAMERA_NOTE",
                OperatorStatus.Severity.ATTENTION,
                "Edge 저하 사유 확인",
            )
            "STARTING" -> Axis(
                "AI 실행",
                "관측 기동 중",
                "$engine · 실행 확인 전 · 시작 요청은 실행 확인이 아님",
                OperatorStatus.Severity.INFO,
                null,
            )
            "CONFIG_APPLIED" -> Axis(
                "AI 실행",
                "설정 적용됨 · 관측 시작 전",
                "설치 확인은 실행 확인이 아님",
                OperatorStatus.Severity.INFO,
                null,
            )
            "REQUEST_ACCEPTED" -> Axis(
                "AI 실행",
                "요청 수신 · 실행 시작 전",
                "수신 확인은 실행 확인이 아님",
                OperatorStatus.Severity.INFO,
                null,
            )
            "STOPPED" -> Axis(
                "AI 실행",
                "관측 중지",
                // A sealed host replay ends here too: recorded execution,
                // never currently RUNNING — never rendered as live analysis.
                "중지 보고됨 · 기록된 실행(현재 분석 중 아님) · 재시작은 명시 요청으로만",
                OperatorStatus.Severity.INFO,
                null,
            )
            else -> Axis(
                "AI 실행",
                EdgeRuntimePanel.UNKNOWN_LINE,
                "Edge 보고 있음 · 실행 상태 미확인(부재는 false/0이 아님)",
                OperatorStatus.Severity.INFO,
                "Edge 보고 후 MISSION_STATUS 확인",
            )
        }
        // A supplied bound fact renders even when the RC store is down
        // (plus the store note) — storage failure never implies stopped.
        return reported.withStoreNote()
    }

    /**
     * Camera as three separate facts in the fixture vocabulary. No actual
     * owner/permission/adjustment report exists on the RC today, so each
     * reads UNKNOWN until one does — and a manual camera never stops
     * analysis/evidence either way. Only an explicit SIM scenario may
     * report `OPERATOR`.
     */
    private fun cameraAxis(input: Input): Axis {
        val owner = if (input.cameraOwnerConfirmed && input.cameraReportedOwner != null &&
            input.cameraReportedOwner != "UNKNOWN"
        ) {
            when (input.cameraReportedOwner) {
                "OPERATOR" -> "조종자(모의 입력)"
                "DJI_MISSION" -> "DJI 임무"
                "KSI" -> "KSI"
                else -> "실제 제어 주체 미확인"
            }
        } else {
            "실제 제어 주체 미확인"
        }
        val auto = when (input.autoPermission) {
            "ALLOWED" -> "자동 허용됨(허용은 실제 제어 중이 아님)"
            "RELEASED" -> "자동 허용 해제됨(해제는 분석을 멈추지 않음)"
            "UNAVAILABLE" -> "자동 기능 미구성"
            else -> "자동 허용 미확인"
        }
        val health = when (input.adjustmentState) {
            "IDLE" -> "조절 대기"
            "OBSERVING" -> "추가 관측 중(분석과 독립)"
            "INTERRUPTED" -> "조절 중단됨"
            "FAILED" -> "조절 실패"
            "NOT_CONFIGURED" -> "자동 조절 미구성(수동 카메라와 무관)"
            else -> "조작 결과 미확인"
        }
        val detail = "$auto · $health · $MANUAL_CAMERA_NOTE"
        val severity = when (input.aircraftConnected) {
            false -> OperatorStatus.Severity.ATTENTION
            else -> OperatorStatus.Severity.INFO
        }
        val action = when (input.aircraftConnected) {
            false -> "기체 연결 확인"
            null -> if (!input.hasActiveFlight) null else "기체 연결 상태 확인"
            else -> null
        }
        return Axis("카메라", owner, detail, severity, action)
    }

    /**
     * Preservation as three separate facts. Manifold local preservation has
     * no field in the frozen MOP, so it is UNKNOWN here unless a real path
     * carries the fixture vocabulary — a RUNNING report or a frame counter
     * never invents it, and byte-seal success stays separate from context
     * completeness (`SEALED` + incomplete context is possible). RC receipt
     * comes from the store (Android actual received MOP facts stay the
     * native source); Core store comes from sync receipts only. This host
     * path is never evidence the RC received anything: null counts read
     * "미확인", never zero.
     *
     * RC store failure is stated as the RC axis's own source/action — it
     * never drops explicitly supplied Manifold local/context facts, and
     * Core receipt stays unknown when unverifiable.
     */
    private fun preservationAxis(input: Input): Axis {
        val rc = when {
            !input.storageAvailable -> "RC 저장소 오류"
            input.rcObservationCount == null || input.rcEventCount == null -> "RC 수신 미확인"
            else -> {
                // "수신 이벤트" — received records, never an Incident.
                "RC 수신 관측 ${input.rcObservationCount}건 · 수신 이벤트 ${input.rcEventCount}건"
            }
        }
        // The RC→Core flight-ingest COMMITTED below covers TRANSMISSION
        // scope only (the frozen source_kind enum): it never proves the
        // Edge's sealed original reached the Core. That proof is the
        // collection receipt (Edge sealed revision → PC collector → Core),
        // which the RC cannot observe — stated as RC-unknown, never
        // implied by the RC's own copy being Core-held.
        val core = when {
            !input.coreConfigured -> "Core 미설정 · 로컬 보관 유지"
            input.coreSyncedCount == null || input.coreTotalCount == null ->
                "Core 전송 미확인(서버 응답 없음)"
            else -> "RC→Core 전송 커밋 확인 ${input.coreSyncedCount}/${input.coreTotalCount}(전송 대상만)"
        }
        val collectionNote = "Edge 봉인본 Core 수집 영수증은 수집 경로에서 확인(RC 미확인)"
        val excludedNote = if ((input.coreExcludedCount ?: 0) > 0) {
            " · 전송 제외(수집 경로 보관) ${input.coreExcludedCount}건"
        } else {
            ""
        }
        val local = when (input.manifoldLocalState) {
            "COLLECTING" -> "Manifold 로컬 수집 중"
            "SEALED" -> "Manifold 로컬 봉인됨"
            "INCOMPLETE" -> "Manifold 로컬 불완전"
            "FAILED" -> "Manifold 로컬 실패"
            else -> "Manifold 로컬 보존 미확인(보고 없음 · 실행 중으로 추정 안 함)"
        }
        val context = when (input.contextState) {
            "COMPLETE" -> "전후 맥락 완전"
            "INCOMPLETE" -> "전후 맥락 불완전"
            else -> "전후 맥락 미확인"
        }
        return Axis(
            "원본 보존",
            rc,
            "$local · $context · $core · $collectionNote$excludedNote" +
                if (!input.storageAvailable) " · ${StorageHealth.UNAVAILABLE_DETAIL}" else "",
            // Host fixtures must surface local FAILED/INCOMPLETE with source
            // and recovery — never masked by a healthy RC proxy. A SEALED
            // local with an incomplete context is not complete. RC store
            // failure is itself a PROBLEM source, stated — not blanked.
            when {
                !input.storageAvailable || input.recordFailure ||
                    input.manifoldLocalState == "FAILED" ->
                    OperatorStatus.Severity.PROBLEM
                input.manifoldLocalState == "INCOMPLETE" ->
                    OperatorStatus.Severity.ATTENTION
                else -> OperatorStatus.Severity.INFO
            },
            when {
                !input.storageAvailable -> StorageHealth.UNAVAILABLE_ACTION
                input.recordFailure -> "로그 확인"
                input.manifoldLocalState == "FAILED" -> "Manifold 로컬 상태 확인·복구"
                input.manifoldLocalState == "INCOMPLETE" -> "보존 불완전 구간 확인"
                else -> null
            },
        )
    }

    /**
     * Backend assessment / pre-post context. Two supply routes, never mixed:
     * an explicitly supplied single-report disposition (fixture/test path),
     * or store-linked tallies (the artifact-plane transport path —
     * assessmentsFor/pendingAssessmentCount). Store receipt counts never
     * stand in for an assessment here; only the assessment tallies do. A
     * SHADOW `CONDITION_MET` is still never an Incident or Alert. Supplied
     * reason codes render in the detail only (never compact), only with a
     * known single disposition, and never induce alerts or intervention.
     *
     * A known, actually supplied assessment field is never invalidated by
     * the RC storage bool: the backend source is independent of the RC
     * store (the RC store note is appended, not substituted).
     */
    private fun assessmentAxis(input: Input): Axis {
        if (!input.hasActiveFlight) {
            return Axis(
                "평가·맥락",
                "평가 대기",
                "활성 세션 없음",
                OperatorStatus.Severity.INFO,
                null,
            )
        }
        val disposition = when (input.assessmentDisposition) {
            "ASSESSED" -> "평가됨"
            "UNDETERMINED" -> "판정불가"
            "CONDITION_MET" -> "조건 충족"
            "CONDITION_NOT_MET" -> "조건 미충족"
            "NOT_EVALUATED" -> "미평가"
            else -> null
        }
        if (disposition != null) {
            val mode = if (input.assessmentMode != null) "${input.assessmentMode} · " else ""
            // Explicit supply only: orphan reasons (no known disposition)
            // are not displayed, and an empty list invents nothing.
            val reasons = input.assessmentReasonCodes.filter { it.isNotBlank() }
            val reasonNote = if (reasons.isNotEmpty()) " · 사유: ${reasons.joinToString(", ")}" else ""
            return Axis(
                "평가·맥락",
                "${mode}${disposition} · 확정 아님",
                "보고된 평가(모의·개발 입력일 수 있음)$reasonNote · $SHADOW_NOTE · $ZERO_NOTE" +
                    if (!input.storageAvailable) " · ${StorageHealth.UNAVAILABLE_DETAIL}" else "",
                OperatorStatus.Severity.INFO,
                if (!input.storageAvailable) StorageHealth.UNAVAILABLE_ACTION else null,
            )
        }
        // Store-linked counts (real transport path: sealed assessments over
        // the artifact plane — see AssessmentModel). Ticks, not a single
        // verdict: 판정/미확정 are tallied, never merged into one claim,
        // and the per-tick detail lives in the record viewer (action
        // directs there when anything arrived). All three null = unknown
        // (store down or unwired caller) — never zero, never "no findings".
        val assessed = input.assessmentAssessed
        val undetermined = input.assessmentUndetermined
        val pending = input.assessmentPending
        if (assessed != null || undetermined != null || pending != null) {
            val a = assessed ?: 0
            val u = undetermined ?: 0
            val p = pending ?: 0
            if (a == 0 && u == 0 && p == 0) {
                return Axis(
                    "평가·맥락",
                    "평가 미수신 — 미확인",
                    "수신된 평가·미완료 없음(0건 아님 · 안전 확인 아님) · 틱별 판정·사유는 기록 화면에서 확인 · $SHADOW_NOTE · $ZERO_NOTE",
                    OperatorStatus.Severity.INFO,
                    null,
                )
            }
            val pendingNote = if (p > 0) " · 수신 중 ${p}건" else ""
            return Axis(
                "평가·맥락",
                "SHADOW 평가 ${a + u}건(판정 ${a}건 · 미확정 ${u}건)$pendingNote · 확정 아님",
                "틱별 판정·사유·규칙/모델 버전은 기록 화면에서 확인 · $SHADOW_NOTE · $ZERO_NOTE" +
                    if (!input.storageAvailable) " · ${StorageHealth.UNAVAILABLE_DETAIL}" else "",
                OperatorStatus.Severity.INFO,
                if (!input.storageAvailable) StorageHealth.UNAVAILABLE_ACTION else null,
            )
        }
        return Axis(
            "평가·맥락",
            "평가·전후 맥락 미확인",
            "보고 없음(frozen MOP에 필드 없음) · 수신 건수로 추정 안 함 · $SHADOW_NOTE · $ZERO_NOTE",
            OperatorStatus.Severity.INFO,
            null,
        )
    }

    /**
     * Intervention need: approved Alerts or important observation/storage
     * faults only (the fixture's `intervention.required` is computed from
     * important input/storage faults alone). Routine SHADOW candidates and
     * undecided assessments never auto-true it, and unknown values are
     * never coerced to false — the record stays reachable, nothing
     * auto-responds.
     */
    private fun actionAxis(input: Input): Axis {
        if (!input.storageAvailable) {
            return Axis(
                "개입 필요",
                "저장소 오류 확인 필요",
                StorageHealth.UNAVAILABLE_DETAIL,
                OperatorStatus.Severity.PROBLEM,
                StorageHealth.UNAVAILABLE_ACTION,
            )
        }
        if (input.recordFailure) {
            return Axis(
                "개입 필요",
                "프록시 기록 오류",
                "RC 프록시 저장 실패 · 이후 구간 재시도 중",
                OperatorStatus.Severity.PROBLEM,
                "로그 확인",
            )
        }
        if (input.linkUp == false && input.hasActiveFlight) {
            return Axis(
                "개입 필요",
                "링크 단절",
                "Edge 통신 단절 · 현재 상태를 정상으로 추정하지 않음",
                OperatorStatus.Severity.PROBLEM,
                "링크 복구 후 MISSION_STATUS 대조",
            )
        }
        if (input.videoInputLost && input.hasActiveFlight) {
            return Axis(
                "개입 필요",
                "영상 입력 단절",
                "영상 입력 단절 · 해당 공간 평가 보류 · $MANUAL_CAMERA_NOTE",
                OperatorStatus.Severity.ATTENTION,
                "영상 입력·Edge 상태 확인",
            )
        }
        if (input.hasApprovedAlert) {
            return Axis(
                "개입 필요",
                "승인된 알림 확인 필요",
                "근거 장면·시각·위치 품질·필요 행동은 기록·예외 상세에서 확인",
                OperatorStatus.Severity.ATTENTION,
                "기록·예외 상세 확인",
            )
        }
        // Active but insufficient facts: absence is not "false". The need
        // stays unknown — stated, never a forced modal.
        if (input.hasActiveFlight && (input.linkUp == null || input.runtimeStateName == null || !input.runtimeSeen)) {
            return Axis(
                "개입 필요",
                "개입 필요 여부 미확인",
                "실행·링크 미확인 — 부재는 정상이 아님 · 자동 판단 없음",
                OperatorStatus.Severity.INFO,
                "Edge 보고 후 MISSION_STATUS 확인",
            )
        }
        val shadowNote = if (input.shadowOnly && input.hasActiveFlight) " · $SHADOW_NOTE" else ""
        return Axis(
            "개입 필요",
            // Quiet headline: no report of a fault is not proof of no need
            // (runtime/input freshness is not fully observed). Routine
            // SHADOW stays explicitly non-mandatory; unknown stays unknown.
            "보고된 개입 요청 없음",
            "정기 SHADOW는 검토 의무가 아님 · 후보·이력·증거는 기록에서 확인$shadowNote · 자동 응답 없음",
            OperatorStatus.Severity.INFO,
            null,
        )
    }

    /**
     * Glance-visible compact facts (round51 fix): AI observation, camera
     * owner + auto permission, and Manifold local preservation — the three
     * axes the default screen must show without opening any panel. Short
     * tokens only: no counts, hashes, reason codes, timestamps, or prose.
     * Full values stay in the detail panel and the deck axes. Store reads
     * are never needed here, so the 1s ticker can rebind it cheaply and the
     * row stays newest when link/staleness changes.
     */
    data class CompactInput(
        val hasActiveFlight: Boolean,
        val runtimeStateName: String?,
        val runtimeSeen: Boolean,
        val unboundReportSeen: Boolean = false,
        /** DroneLink CONNECTED; null = unknown, never guessed healthy. */
        val linkUp: Boolean?,
        val videoInputLost: Boolean,
        val engineState: String?,
        val frameProcessingObserved: Boolean?,
        val cameraReportedOwner: String?,
        val cameraOwnerConfirmed: Boolean,
        val autoPermission: String?,
        val manifoldLocalState: String? = null,
        val storageAvailable: Boolean = true,
    )

    data class CompactRow(
        val ai: String,
        val camera: String,
        val preservation: String,
        val severity: OperatorStatus.Severity,
        val contentDescription: String,
    )

    fun compact(input: CompactInput): CompactRow {
        // No early storage wipe-out: supplied runtime/camera/local facts
        // render independently (see [aiAxis]); the store fault is stated
        // in severity + description, never substituted for the facts.
        val storeNote = if (!input.storageAvailable) " · RC 저장소 오류" else ""
        if (!input.hasActiveFlight) {
            return CompactRow(
                "실행 미확인", "카메라 미확인 · 자동 미확인", "로컬 보존 미확인",
                if (!input.storageAvailable) {
                    OperatorStatus.Severity.PROBLEM
                } else {
                    OperatorStatus.Severity.INFO
                },
                "자율 관측 감독 · 활성 세션 없음$storeNote · 자세히는 조작·상세.",
            )
        }
        if (input.linkUp == false) {
            return CompactRow(
                "통신 단절", compactCamera(input), compactLocal(input.manifoldLocalState),
                OperatorStatus.Severity.PROBLEM,
                "자율 관측 감독 · Edge 통신 단절 · 현재 상태를 정상으로 추정하지 않음$storeNote · 자세히는 조작·상세.",
            )
        }
        val confirmed =
            input.frameProcessingObserved == true && input.engineState == "ok"
        val videoNote = if (input.videoInputLost) " · 영상 입력 단절" else ""
        val ai = when {
            input.runtimeStateName == null || !input.runtimeSeen ->
                if (input.unboundReportSeen) "바인딩 미확인" else "실행 상태 미확인"
            input.runtimeStateName == "RUNNING" ->
                if (confirmed) "관측 실행 중$videoNote" else "관측 실행 보고됨$videoNote"
            input.runtimeStateName == "DEGRADED" -> "관측 저하$videoNote"
            input.runtimeStateName == "STARTING" -> "관측 기동 중"
            input.runtimeStateName == "CONFIG_APPLIED" -> "설정 적용됨"
            input.runtimeStateName == "REQUEST_ACCEPTED" -> "요청 수신"
            input.runtimeStateName == "STOPPED" -> "관측 중지 · 기록됨"
            else -> "실행 상태 미확인"
        }
        val severity = when {
            !input.storageAvailable || input.manifoldLocalState == "FAILED" ->
                OperatorStatus.Severity.PROBLEM
            input.videoInputLost || input.manifoldLocalState == "INCOMPLETE" ||
                input.runtimeStateName == "DEGRADED" ->
                OperatorStatus.Severity.ATTENTION
            else -> OperatorStatus.Severity.INFO
        }
        val camera = compactCamera(input)
        val preservation = compactLocal(input.manifoldLocalState)
        return CompactRow(
            ai, camera, preservation, severity,
            "자율 관측 감독 · AI $ai · $camera · 원본 $preservation$storeNote · 자세히는 조작·상세.",
        )
    }

    private fun compactCamera(input: CompactInput): String {
        val owner = if (input.cameraOwnerConfirmed && input.cameraReportedOwner != null &&
            input.cameraReportedOwner != "UNKNOWN"
        ) {
            when (input.cameraReportedOwner) {
                "OPERATOR" -> "카메라: 조종자"
                "DJI_MISSION" -> "카메라: DJI 임무"
                "KSI" -> "카메라: KSI"
                else -> "카메라 미확인"
            }
        } else {
            "카메라 미확인"
        }
        val auto = when (input.autoPermission) {
            "ALLOWED" -> "자동 허용"
            "RELEASED" -> "자동 해제"
            "UNAVAILABLE" -> "자동 미구성"
            else -> "자동 미확인"
        }
        return "$owner · $auto"
    }

    private fun compactLocal(manifoldLocalState: String?): String = when (manifoldLocalState) {
        "COLLECTING" -> "로컬 수집 중"
        "SEALED" -> "로컬 봉인됨"
        "INCOMPLETE" -> "로컬 불완전"
        "FAILED" -> "로컬 실패"
        else -> "로컬 보존 미확인"
    }
}
