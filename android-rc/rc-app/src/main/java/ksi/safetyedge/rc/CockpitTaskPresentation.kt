package ksi.safetyedge.rc

/**
 * Task-first cockpit presentation (round49, revised): maps the existing
 * detailed facts ([OperatorStatus] items, [CockpitStatusPresenter.Overview],
 * [EdgeRuntimePanel.Panel]) onto the persistent compact surfaces — the
 * observation row (Edge concise state + record/sync support + conditional
 * problem line) and the bottom task bar (scoped record/panel actions only,
 * no gate line).
 *
 * Pure Kotlin, no Android: host-tested. Disclosure rules:
 * - The compact Edge state is NORMALIZED by [compactState] from the
 *   reported runtime state name — never the panel headline verbatim, so no
 *   processed-frame counts, install hashes, raw degraded-reason codes,
 *   last-confirmed timestamps or other dev detail can leak into the row.
 *   Exact full values stay in the status/details panel.
 * - The support line is concise: KSI session state ("KSI 기록 대기/중") plus a
 *   pending count only when positive and confirmed, plus a visible record-
 *   failure token. Full proxy/Core/sync source stays in the details panel
 *   and the content description — never truncated away silently.
 * - Every safety fact stays visible on the compact surfaces: link loss vs
 *   input loss (different wording), preparation incomplete (axis names stay
 *   in details), zero rules / no risk judgment, observation gap, stale or
 *   missing observation receipt, unsynced count, record failure, and
 *   SHADOW-is-not-a-warning. The full B-1 eight items remain ordered in
 *   the details view; this model only decides the compact lines.
 * - No permanent gate line: physical-controller duplicates were removed from
 *   the default surfaces. The app-specific request reason
 *   ([REQUEST_DETAIL]) is available only in request detail. Gate-first
 *   request semantics live in the command ports, not in this model.
 */
object CockpitTaskPresentation {

    /**
     * App-specific request reason. Shown ONLY in request detail (assist
     * dialogs, details panel) — never as a permanent surface, never merged
     * with ACTIVE approval, and never implying the physical RC is locked.
     * Physical controller operation is the primary path.
     */
    const val REQUEST_DETAIL =
        "앱 비행 요청 불가 · 제어 주체 미확인 — 물리 조종기 조작과는 무관"

    data class Input(
        val hasActiveFlight: Boolean,
        /**
         * Bound Edge runtime state name ("REQUEST_ACCEPTED", …, null when
         * the Edge never reported a bound install). Raw counts/hashes/
         * reason codes are NEVER passed here — see [compactState].
         */
        val runtimeStateName: String?,
        /** Panel severity for the reported state (DEGRADED → ATTENTION). */
        val edgeSeverity: OperatorStatus.Severity,
        /** True = Edge 통신 단절 (link loss — never merged with input loss). */
        val edgeLinkDown: Boolean,
        /** True = 영상 입력 단절 (input loss — never merged with link loss). */
        val videoInputLost: Boolean,
        /** Compact recorder fact, e.g. "쓰기 중" / "녹화 오류". */
        val recorderValue: String,
        /** True = the RC proxy record failed (never hidden, never recovered silently). */
        val recordFailure: Boolean,
        /** Null = sync state unknown (never zero). */
        val unsyncedCount: Int?,
        val coreUnconfigured: Boolean,
        /** Null/0 = no gap token (a missing summary is never a zero gap). */
        val observationGapFrames: Long?,
        /** Active flight but no observation receipt yet (visible, not accessible-only). */
        val observationMissing: Boolean,
        /** Observation receipt older than the display threshold. */
        val observationStale: Boolean,
        /** True = preparation 3-axis incomplete (axis names stay in details). */
        val preparationIncomplete: Boolean,
        /** True = applied bundle carries no risk rule (a state, not a failure). */
        val zeroRulesNoJudgment: Boolean,
        /** True = SHADOW-only observation (warning wording forbidden). */
        val shadowOnlyNote: Boolean,
        /** True = video disconnected → show the connection recovery action. */
        val videoNeedsRecovery: Boolean,
    )

    data class Task(
        /** Normalized concise Edge state only — details live in status/details. */
        val edgeLine: String,
        val edgeSeverity: OperatorStatus.Severity,
        /** Concise KSI session line: session state + positive pending only. */
        val supportLine: String,
        /** Conditional problem tokens, null when nothing needs surfacing. */
        val alertLine: String?,
        /** True = show the "영상 연결" recovery action (not a dev explanation). */
        val showVideoRecovery: Boolean,
        val contentDescription: String,
    )

    /**
     * Explicit compact mapping: the reported 6 states, link vs input loss,
     * and unknown. No counts, no hashes, no raw codes, no timestamps, no
     * dev explanations — those stay in the details panel.
     */
    fun compactState(
        runtimeStateName: String?,
        hasActiveFlight: Boolean,
        edgeLinkDown: Boolean,
        videoInputLost: Boolean,
    ): String {
        if (!hasActiveFlight) return EdgeRuntimePanel.UNKNOWN_LINE
        if (edgeLinkDown && videoInputLost) return "Edge 통신 단절 · 영상 입력 단절"
        if (edgeLinkDown) return "Edge 통신 단절"
        val base = when (runtimeStateName) {
            "REQUEST_ACCEPTED" -> "요청 수신 · 실행 시작 전"
            "CONFIG_APPLIED" -> "설정 적용됨 · 관측 시작 전"
            "STARTING" -> "관측 기동 중"
            "RUNNING" -> "관측 실행 중"
            "DEGRADED" -> "관측 저하"
            "STOPPED" -> "관측 중지"
            else -> EdgeRuntimePanel.UNKNOWN_LINE
        }
        return if (videoInputLost) "$base · 영상 입력 단절" else base
    }

    /** App-specific reason for request detail surfaces only. */
    fun requestDetail(): String = REQUEST_DETAIL

    fun present(input: Input): Task {
        val edgeLine = buildString {
            append(
                compactState(
                    input.runtimeStateName,
                    input.hasActiveFlight,
                    input.edgeLinkDown,
                    input.videoInputLost,
                ),
            )
            if (input.zeroRulesNoJudgment) {
                append(" · 위험 규칙 0건 — 위험 판정 없음")
            }
            if (input.shadowOnlyNote) {
                append(" · SHADOW 경고 아님")
            }
        }
        val supportLine = buildString {
            // Compact default: KSI session state only, plus a pending count
            // only when positive and confirmed. Full proxy/Core/sync source
            // stays in the details panel and the content description.
            append(if (!input.hasActiveFlight) "KSI 기록 대기" else "KSI 기록 중")
            if (input.recordFailure) append(" · 프록시 기록 오류")
            val unsynced = input.unsyncedCount
            if (unsynced != null && unsynced > 0) append(" · 미동기화 ${unsynced}건")
        }
        val fullSupportLine = buildString {
            if (!input.hasActiveFlight) {
                append("세션 없음")
            } else if (input.recordFailure) {
                append("RC 프록시 녹화 오류")
            } else {
                append("RC 프록시 ${input.recorderValue}")
            }
            append(" · ")
            val unsynced = input.unsyncedCount
            when {
                unsynced != null && unsynced > 0 -> append("미동기화 ${unsynced}건")
                unsynced != null -> append("미동기화 0건")
                else -> append("동기화 상태 미확인")
            }
            if (input.coreUnconfigured) append(" · Core 미설정")
        }
        // Conditional problem line: short tokens only (most severe first),
        // full explanations stay in the details panel.
        val alertTokens = buildList {
            if (input.recordFailure) add("녹화 오류")
            if (input.edgeLinkDown) add("링크 단절")
            if (input.videoInputLost) add("영상 입력 단절")
            if (input.hasActiveFlight) {
                if (input.preparationIncomplete) add("준비 미완")
                val gap = input.observationGapFrames
                if (gap != null && gap > 0) add("관측 공백 ${gap}프레임")
                if (input.observationMissing) add("관측 미수신")
                else if (input.observationStale) add("관측 수신 지연")
            }
        }
        val alertLine = alertTokens.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        val description = buildString {
            append("AI 관측: ").append(edgeLine).append(". ")
            append(fullSupportLine).append(". ")
            if (alertLine != null) append(alertLine).append(". ")
            if (input.videoNeedsRecovery) append(" 영상 연결 복구 가능.")
        }
        return Task(
            edgeLine = edgeLine,
            edgeSeverity = if (input.edgeLinkDown || input.recordFailure) {
                OperatorStatus.Severity.PROBLEM
            } else {
                input.edgeSeverity
            },
            supportLine = supportLine,
            alertLine = alertLine,
            showVideoRecovery = input.videoNeedsRecovery,
            contentDescription = description.toString(),
        )
    }
}
