package ksi.safetyedge.rc

import ksi.rc.EdgeRuntimeState
import ksi.rc.EdgeRuntimeStatus

/**
 * Edge (Manifold) runtime status area for the integrated operation screen
 * (2026-10-21 edge-operation contract §2/§5, screen contract §3-5-1).
 *
 * Pure Kotlin, no Android: host-tested. Reads ONLY what the Edge reported
 * in MISSION_STATUS ([EdgeRuntimeStatus], via `MissionController`) plus the
 * RC-local link state. The legacy `running` bool is NEVER execution
 * evidence: without reported runtime fields the panel says
 * "Edge 실행 상태 미확인" — never 0/없음.
 *
 * State wording is verbatim per contract §3-5-1:
 * - REQUEST_ACCEPTED -> "요청 수신 · 실행 시작 전"
 * - CONFIG_APPLIED  -> "설정 적용됨 · 관측 시작 전"
 * - STARTING        -> "관측 기동 중"
 * - RUNNING         -> "관측 실행 중 · 처리 N프레임"
 * - DEGRADED        -> "관측 저하 · <사유>" (+ 입력 상태)
 * - STOPPED         -> "관측 중지"
 * - absent          -> "Edge 실행 상태 미확인"
 *
 * Link loss and input loss are DIFFERENT messages:
 * "Edge 통신 단절 · 마지막 확인 실행 시각 …" vs "영상 입력 단절 · 관측 저하".
 */
object EdgeRuntimePanel {

    /** Absent runtime fields render as this — never false/0/없음. */
    const val UNKNOWN_LINE = "Edge 실행 상태 미확인"

    /**
     * Always shown next to the zero count: a performed analysis that found
     * nothing is a recorded fact, not a safety verdict.
     */
    const val ZERO_NOTE = "0건 관측(분석 수행됨) ≠ 분석 결과 없음"

    /**
     * Shown when the applied bundle carries no risk rule: observation
     * processing may run, but NO risk evaluation is running. The Edge
     * installs exactly the bundle's rules, so the RC knows the installed
     * count from the bundle it applied (contract §10.2).
     */
    const val NO_RISK_RULES_NOTE = "위험 규칙 0건 — 위험 판정 없음(관측만 동작)"

    /** Degraded codes that mean the video input specifically is lost/stale. */
    private val VIDEO_LOSS_CODES = setOf("video_input_lost", "video_input_stale")

    /**
     * Bounded autonomy store saturated (Edge `autonomy_evidence_saturated`):
     * observation CONTINUES, later artifacts are absent. Shown as its own
     * line because the raw code alone does not say the run kept observing.
     * Unknown future codes keep rendering raw elsewhere — this maps one
     * known code only, never invents meanings for others.
     */
    const val AUTONOMY_EVIDENCE_SATURATED_CODE = "autonomy_evidence_saturated"
    const val AUTONOMY_EVIDENCE_SATURATED_LINE =
        "자동 기록 포화 · 관측 계속, 일부 증거 미보존"

    data class Input(
        val status: EdgeRuntimeStatus?,
        /** DroneLink CONNECTED. False = show link loss, never estimate normality. */
        val linkUp: Boolean,
        val hasActiveFlight: Boolean,
        /** RC receipt time of the last RUNNING/DEGRADED report (null = none yet). */
        val lastConfirmedUtc: String?,
        /** Latched frame-processing evidence (null = the Edge never reported counts). */
        val analysisRan: Boolean?,
        val observationCount: Int?,
        /**
         * The Edge reported §3 runtime fields, but none of them bound to
         * this flight's install (foreign digest, missing install hash, or
         * a revalidation demand): display-only, never execution evidence.
         */
        val unboundReportSeen: Boolean = false,
        /**
         * Installed SHADOW rules in the bundle the RC applied (null = not
         * known here). The raw rule count includes ACTIVE/OFF installs that
         * never evaluate, so it must NEVER be labeled "(SHADOW)": only this
         * SHADOW basis is the honest "위험 규칙 N건".
         */
        val appliedShadowRuleCount: Int? = null,
        /** Installed SHADOW zone-intrusion rules (null = not known here). */
        val appliedShadowZoneRuleCount: Int? = null,
        /** Installed SHADOW proximity rules (null = not known here). */
        val appliedShadowProximityRuleCount: Int? = null,
        /** Installed but inert OFF-mode rules (never evaluating). */
        val appliedOffRuleCount: Int? = null,
        /** Enabled zones installed (zone-intrusion needs at least one). */
        val appliedEnabledZoneCount: Int? = null,
        /** rc-core: config applied AND bound install AND judging SHADOW rules. */
        val riskEvaluationAvailable: Boolean? = null,
        /** rc-core: why risk evaluation is unavailable (null = available/unknown). */
        val riskEvaluationUnavailableReason: String? = null,
    )

    data class Panel(
        val headline: String,
        val details: List<String>,
        /** Non-null only while the link is down. Never an input-loss message. */
        val linkLine: String?,
        /** Non-null only for video input loss. Never a link-loss message. */
        val inputLossLine: String?,
        val severity: OperatorStatus.Severity,
    )

    /**
     * Single source of the §3-5-1 state wording. [OperatorStatus] reuses
     * this so the strip and the panel can never drift apart.
     */
    fun stateLine(
        state: EdgeRuntimeState,
        processedFrames: Long?,
        degradedReasons: List<String>,
    ): String = when (state) {
        EdgeRuntimeState.REQUEST_ACCEPTED -> "요청 수신 · 실행 시작 전"
        EdgeRuntimeState.CONFIG_APPLIED -> "설정 적용됨 · 관측 시작 전"
        EdgeRuntimeState.STARTING -> "관측 기동 중"
        EdgeRuntimeState.RUNNING -> if (processedFrames != null) {
            "관측 실행 중 · 처리 ${processedFrames}프레임"
        } else {
            "관측 실행 중 · 처리 수 미확인"
        }
        EdgeRuntimeState.DEGRADED -> if (degradedReasons.isEmpty()) {
            "관측 저하 · 사유 미보고"
        } else {
            "관측 저하 · ${degradedReasons.joinToString(", ")}"
        }
        EdgeRuntimeState.STOPPED -> "관측 중지"
        EdgeRuntimeState.UNKNOWN -> UNKNOWN_LINE
    }

    fun render(input: Input): Panel {
        if (!input.hasActiveFlight) {
            return Panel(
                headline = UNKNOWN_LINE,
                details = listOf("활성 KSI 세션 없음 · Edge 보고 대기"),
                linkLine = null,
                inputLossLine = null,
                severity = OperatorStatus.Severity.INFO,
            )
        }
        val status = input.status
        if (status == null || status.state == EdgeRuntimeState.UNKNOWN) {
            val linkLine = if (!input.linkUp) linkLossLine(input.lastConfirmedUtc) else null
            val noReportLine = when {
                status != null -> "Edge 보고 있음 · 실행 상태 미확인(부재는 false/0이 아님)"
                input.unboundReportSeen -> "Edge 보고 있음 · 바인딩 미확인(표시만) · 실행 상태로 채택 안 함"
                else -> "Edge 보고 없음 · 부재는 false/0이 아님"
            }
            return Panel(
                headline = linkLine ?: UNKNOWN_LINE,
                details = if (linkLine != null) {
                    listOf("현재 상태를 정상으로 추정하지 않음")
                } else {
                    listOf(noReportLine)
                },
                linkLine = linkLine,
                inputLossLine = null,
                severity = if (linkLine != null) {
                    OperatorStatus.Severity.PROBLEM
                } else {
                    OperatorStatus.Severity.INFO
                },
            )
        }
        val details = mutableListOf<String>()
        details += if (status.processedFrameCount != null) {
            "처리 ${status.processedFrameCount}프레임(실행 세대 내)"
        } else {
            "처리 수 미확인(부재는 0이 아님)"
        }
        details += if (status.lastProcessedFrameAgeMs != null) {
            "마지막 처리 ${ElapsedFormat.ageAgo(status.lastProcessedFrameAgeMs)}"
        } else {
            "마지막 처리 경과 미확인"
        }
        details += configLine(status)
        details += ruleLine(input)
        ruleKindLine(input)?.let { details += it }
        availabilityLine(input)?.let { details += it }
        details += "실행 세대 ${status.runtimeEpoch ?: "미확인"}"
        details += inputStateLine(status.inputState)
        if (status.degradedReasons.isNotEmpty()) {
            details += "저하 사유 ${status.degradedReasons.joinToString(", ")}"
        }
        if (AUTONOMY_EVIDENCE_SATURATED_CODE in status.degradedReasons) {
            details += AUTONOMY_EVIDENCE_SATURATED_LINE
        }
        when (input.analysisRan) {
            true -> details += "분석 수행됨(프레임 처리 확인)"
            false -> details += "분석 수행 확인 안 됨"
            null -> Unit
        }
        if (input.observationCount != null) {
            details += "로컬 관측 기록 ${input.observationCount}건"
        }
        val inputLossLine = if (status.degradedReasons.any { it in VIDEO_LOSS_CODES }) {
            "영상 입력 단절 · 관측 저하"
        } else {
            null
        }
        if (!input.linkUp) {
            val linkLine = linkLossLine(input.lastConfirmedUtc)
            return Panel(
                headline = linkLine,
                details = listOf(
                    "마지막 보고 · ${stateLine(status.state, status.processedFrameCount, status.degradedReasons)}",
                    "현재 상태를 정상으로 추정하지 않음",
                ) + details,
                linkLine = linkLine,
                inputLossLine = inputLossLine,
                severity = OperatorStatus.Severity.PROBLEM,
            )
        }
        return Panel(
            headline = stateLine(status.state, status.processedFrameCount, status.degradedReasons),
            details = details,
            linkLine = null,
            inputLossLine = inputLossLine,
            severity = if (status.state == EdgeRuntimeState.DEGRADED) {
                OperatorStatus.Severity.ATTENTION
            } else {
                OperatorStatus.Severity.INFO
            },
        )
    }

    private fun linkLossLine(lastConfirmedUtc: String?): String =
        "Edge 통신 단절 · 마지막 확인 실행 시각 ${lastConfirmedUtc ?: "미확인"}"

    /**
     * Risk-rule honesty (contract §10.2): observation processing and risk
     * evaluation are different things. The count basis is installed SHADOW
     * rules ONLY — ACTIVE installs are refused edge-side and OFF installs
     * never evaluate, so the raw rule count is never labeled "(SHADOW)".
     * A zero SHADOW count means NO risk evaluation is running — that is a
     * state, not a failure, and it must be visible instead of a bare
     * "설정 적용됨".
     */
    private fun ruleLine(input: Input): String {
        val rules = input.appliedShadowRuleCount
        return when {
            rules == null -> "위험 규칙 수 미확인"
            rules == 0 -> NO_RISK_RULES_NOTE
            else -> "위험 규칙 ${rules}건(SHADOW)"
        }
    }

    /**
     * Precise availability wording. SHADOW presence alone never reads as
     * "running": the Edge must have reported the install applied AND bound
     * ([riskEvaluationAvailable]), and a zone-geometry hold withholds zone
     * judgments even with rules installed. The hold states the exact cause —
     * geometry unconfirmed vs no enabled zones — never a bare count.
     */
    /** Installed-kind split (shown only when the RC knows it). */
    private fun ruleKindLine(input: Input): String? {
        val zone = input.appliedShadowZoneRuleCount
        val proximity = input.appliedShadowProximityRuleCount
        val off = input.appliedOffRuleCount
        if (zone == null && proximity == null && off == null) return null
        fun count(value: Int?): String = value?.let { "${it}건" } ?: "미확인"
        val parts = mutableListOf(
            "구역 침입 ${count(zone)}", "접근 ${count(proximity)}",
        )
        if ((off ?: 0) > 0) parts.add("OFF ${off}건(미동작)")
        return "판정 종류 · " + parts.joinToString(" · ")
    }

    private fun availabilityLine(input: Input): String? {
        if (input.riskEvaluationAvailable == true) {
            return "SHADOW 규칙 설치·바인딩 확인 · 공간 판정 유효성은 별도"
        }
        val reason = input.riskEvaluationUnavailableReason ?: return null
        return when {
            reason.contains("ACTIVE/OFF-only") ->
                "설치된 규칙은 ACTIVE/OFF 전용 — SHADOW 위험 판정 없음(관측만 동작)"
            reason.contains("config application unreported") ->
                "설치 미확인(Edge config_applied 미보고) · 위험 판정 미확인"
            reason.contains("binding unproven") ->
                "설치 번들 불일치(바인딩 미확인) · 재적용 필요 · 위험 판정 미확인"
            reason.contains("withheld") -> zoneHoldLine(input, reason)
            reason.contains("no risk rules") -> null // covered by NO_RISK_RULES_NOTE
            else -> "위험 판정 불가 · $reason"
        }
    }

    private fun zoneHoldLine(input: Input, reason: String): String {
        val geometryUnconfirmed = input.status?.degradedReasons
            ?.contains("zone_geometry_unavailable") == true ||
            reason.contains("zone_geometry_unavailable")
        return when {
            geometryUnconfirmed ->
                "구역 판정 보류 · Edge 기하 미확정 보고(zone_geometry_unavailable) · " +
                    "구역 침입 판단 없음(관측만 동작)"
            input.appliedEnabledZoneCount == 0 ->
                "구역 판정 보류 · 유효 구역 0개 · 구역 침입 판단 없음"
            else -> "구역 판정 보류 · 구역 침입 판단 없음(관측만 동작)"
        }
    }

    private fun configLine(status: EdgeRuntimeStatus): String = when (status.configApplied) {
        true -> {
            val hash = status.effectiveConfigHash
            if (hash != null) {
                "설치 해시 ${hash.take(12)}"
            } else {
                "설치 확인 · 해시 미보고"
            }
        }
        false -> "설치 안 됨"
        null -> "설치 미확인"
    }

    private fun inputStateLine(inputState: Map<String, String>): String {
        if (inputState.isEmpty()) return "입력 상태 미확인(영상·텔레메트리·엔진)"
        fun part(key: String, label: String) = "$label ${inputState[key] ?: "미확인"}"
        return "입력 상태 · ${part("video", "영상")} · ${part("telemetry", "텔레메트리")} · " +
            part("engine", "엔진")
    }
}
