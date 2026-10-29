package ksi.safetyedge.rc

import ksi.rc.EdgeRuntimeState
import ksi.rc.EdgeRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the Edge runtime status area (2026-10-21 contract §2/§5,
 * screen contract §3-5-1): verbatim state wording, UNKNOWN on absent
 * fields (never 0/없음), degraded reasons shown, and link loss vs input
 * loss as DIFFERENT messages.
 */
class EdgeRuntimePanelTest {

    private fun status(
        state: EdgeRuntimeState = EdgeRuntimeState.UNKNOWN,
        epoch: String? = null,
        configApplied: Boolean? = null,
        hash: String? = null,
        frames: Long? = null,
        ageMs: Long? = null,
        reasons: List<String> = emptyList(),
        inputs: Map<String, String> = emptyMap(),
    ) = EdgeRuntimeStatus(
        state = state,
        runtimeEpoch = epoch,
        configApplied = configApplied,
        effectiveConfigHash = hash,
        processedFrameCount = frames,
        lastProcessedFrameAgeMs = ageMs,
        degradedReasons = reasons,
        inputState = inputs,
        observedAtMonotonicNs = 1L,
        parseIssues = emptyList(),
    )

    private fun input(
        status: EdgeRuntimeStatus? = null,
        linkUp: Boolean = true,
        hasActiveFlight: Boolean = true,
        lastConfirmedUtc: String? = null,
        analysisRan: Boolean? = null,
        observationCount: Int? = null,
        appliedShadowRuleCount: Int? = null,
        appliedShadowZoneRuleCount: Int? = null,
        appliedShadowProximityRuleCount: Int? = null,
        appliedOffRuleCount: Int? = null,
        appliedEnabledZoneCount: Int? = null,
        riskEvaluationAvailable: Boolean? = null,
        riskEvaluationUnavailableReason: String? = null,
        unboundReportSeen: Boolean = false,
    ) = EdgeRuntimePanel.Input(
        status = status,
        linkUp = linkUp,
        hasActiveFlight = hasActiveFlight,
        lastConfirmedUtc = lastConfirmedUtc,
        analysisRan = analysisRan,
        observationCount = observationCount,
        appliedShadowRuleCount = appliedShadowRuleCount,
        appliedShadowZoneRuleCount = appliedShadowZoneRuleCount,
        appliedShadowProximityRuleCount = appliedShadowProximityRuleCount,
        appliedOffRuleCount = appliedOffRuleCount,
        appliedEnabledZoneCount = appliedEnabledZoneCount,
        riskEvaluationAvailable = riskEvaluationAvailable,
        riskEvaluationUnavailableReason = riskEvaluationUnavailableReason,
        unboundReportSeen = unboundReportSeen,
    )

    @Test
    fun zero_shadow_rules_state_that_no_risk_evaluation_runs() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.RUNNING,
                    configApplied = true,
                    hash = "a".repeat(64),
                    frames = 12L,
                ),
                appliedShadowRuleCount = 0,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason = "no risk rules in applied bundle",
            ),
        )
        assertTrue(
            panel.details.any { it.contains("위험 규칙 0건") && it.contains("위험 판정 없음") },
        )
    }

    @Test
    fun installed_shadow_rules_are_reported_with_kind_splits() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.RUNNING,
                    configApplied = true,
                    hash = "b".repeat(64),
                    frames = 3L,
                ),
                appliedShadowRuleCount = 2,
                appliedShadowZoneRuleCount = 1,
                appliedShadowProximityRuleCount = 1,
                appliedEnabledZoneCount = 2,
                riskEvaluationAvailable = true,
            ),
        )
        assertTrue(panel.details.any { it.contains("위험 규칙 2건(SHADOW)") })
        assertTrue(panel.details.any { it.contains("구역 침입 1건") && it.contains("접근 1건") })
        assertTrue(panel.details.none { it.contains("위험 판정 없음") })
        assertTrue(panel.details.any { it.contains("SHADOW 규칙 설치·바인딩 확인") })
        assertTrue(panel.details.any { it.contains("공간 판정 유효성은 별도") })
        assertTrue(panel.details.none { it.contains("위험 판정 가능") })
    }

    @Test
    fun unknown_rule_count_is_not_zero() {        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.RUNNING,
                    configApplied = true,
                    frames = 1L,
                ),
                appliedShadowRuleCount = null,
            ),
        )
        assertTrue(panel.details.any { it.contains("위험 규칙 수 미확인") })
        assertTrue(panel.details.none { it.contains("위험 규칙 0건") })
    }

    @Test
    fun raw_counts_are_never_labeled_shadow() {
        // An ACTIVE/OFF-heavy bundle: zero SHADOW rules with inert installs
        // reads as no SHADOW evaluation — never "N건(SHADOW)" from a raw total.
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 3L),
                appliedShadowRuleCount = 0,
                appliedOffRuleCount = 3,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason = "applied rules are ACTIVE/OFF-only",
            ),
        )
        assertTrue(panel.details.any { it.contains("위험 규칙 0건") })
        assertTrue(panel.details.none { it.contains("3건(SHADOW)") })
        assertTrue(panel.details.any { it.contains("ACTIVE/OFF 전용") })
    }

    @Test
    fun zone_geometry_hold_names_the_exact_cause() {
        val held = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    reasons = listOf("zone_geometry_unavailable"),
                ),
                appliedShadowRuleCount = 1,
                appliedShadowZoneRuleCount = 1,
                appliedEnabledZoneCount = 1,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason =
                    "zone-intrusion rules installed but zone judgments withheld " +
                        "(zone_geometry_unavailable reported by Edge)",
            ),
        )
        assertTrue(held.details.any { it.contains("구역 판정 보류") && it.contains("zone_geometry_unavailable") })
        assertTrue(held.details.none { it.contains("위험 판정 가능") })

        val noZones = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 1L),
                appliedShadowRuleCount = 1,
                appliedShadowZoneRuleCount = 1,
                appliedEnabledZoneCount = 0,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason =
                    "zone-intrusion rules installed but zone judgments withheld " +
                        "(no enabled zones installed)",
            ),
        )
        assertTrue(noZones.details.any { it.contains("구역 판정 보류") && it.contains("유효 구역 0개") })
    }

    @Test
    fun unbound_install_and_unreported_config_name_their_causes() {
        val unbound = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 1L),
                appliedShadowRuleCount = 1,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason =
                    "installed config does not match the applied bundle " +
                        "(install binding unproven: applied_bundle_sha256/effective_config_hash)",
            ),
        )
        assertTrue(unbound.details.any { it.contains("바인딩 미확인") && it.contains("재적용 필요") })

        val unreported = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 1L),
                appliedShadowRuleCount = 1,
                riskEvaluationAvailable = false,
                riskEvaluationUnavailableReason =
                    "config application unreported by Edge (config_applied=unknown)",
            ),
        )
        assertTrue(unreported.details.any { it.contains("config_applied 미보고") })
    }

    @Test
    fun request_accepted_wording_is_verbatim() {
        val panel = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.REQUEST_ACCEPTED)),
        )

        assertEquals("요청 수신 · 실행 시작 전", panel.headline)
        assertNull(panel.linkLine)
        assertNull(panel.inputLossLine)
        assertTrue("must not read as executing", !panel.headline.contains("실행 중"))
    }

    @Test
    fun config_applied_wording_is_verbatim() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.CONFIG_APPLIED,
                    configApplied = true,
                    hash = "abcdef0123456789",
                ),
            ),
        )

        assertEquals("설정 적용됨 · 관측 시작 전", panel.headline)
        assertTrue(panel.details.any { it.contains("abcdef012345") })
        assertTrue("must not read as observing", !panel.headline.contains("관측 중"))
    }

    @Test
    fun starting_wording_is_verbatim_and_zero_frames_not_execution() {
        val panel = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.STARTING, frames = 0L)),
        )

        assertEquals("관측 기동 중", panel.headline)
        assertTrue(panel.details.any { it.contains("처리 0프레임") })
    }

    @Test
    fun running_wording_carries_the_reported_frame_count() {
        val panel = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.RUNNING, frames = 42L)),
        )

        assertEquals("관측 실행 중 · 처리 42프레임", panel.headline)
    }

    @Test
    fun running_without_a_reported_count_does_not_invent_zero() {
        val panel = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.RUNNING, frames = null)),
        )

        assertEquals("관측 실행 중 · 처리 수 미확인", panel.headline)
        assertTrue(panel.details.any { it.contains("처리 수 미확인") })
        assertTrue(
            "absent count must never render as 0",
            panel.details.none { it.contains("처리 0프레임") },
        )
    }

    @Test
    fun degraded_wording_shows_reasons_and_input_state() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    frames = 7L,
                    reasons = listOf("telemetry_stale", "engine_failed"),
                    inputs = mapOf("video" to "ok", "telemetry" to "stale", "engine" to "failed"),
                ),
            ),
        )

        assertEquals("관측 저하 · telemetry_stale, engine_failed", panel.headline)
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.severity)
        assertTrue(panel.details.any { it.contains("텔레메트리 stale") })
        assertTrue(panel.details.any { it.contains("엔진 failed") })
        assertNull("telemetry/engine loss is not the video-loss message", panel.inputLossLine)
    }

    @Test
    fun autonomy_evidence_saturation_names_the_gap_while_observation_continues() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    frames = 41L,
                    reasons = listOf("telemetry_absent", "autonomy_evidence_saturated"),
                    inputs = mapOf("video" to "ok", "telemetry" to "absent", "engine" to "ok"),
                ),
                analysisRan = true,
            ),
        )

        // Headline keeps the verbatim contract wording with the raw code.
        assertEquals(
            "관측 저하 · telemetry_absent, autonomy_evidence_saturated",
            panel.headline,
        )
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.severity)
        assertTrue(
            "saturation explains itself instead of leaving a bare code",
            panel.details.any { it == EdgeRuntimePanel.AUTONOMY_EVIDENCE_SATURATED_LINE },
        )
        assertTrue(panel.details.any { it.contains("분석 수행됨") })
        assertNull("saturation is not video loss", panel.inputLossLine)
    }

    @Test
    fun unknown_future_codes_render_raw_without_a_saturation_line() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    frames = 3L,
                    reasons = listOf("some_future_code"),
                ),
            ),
        )

        assertEquals("관측 저하 · some_future_code", panel.headline)
        assertTrue(
            panel.details.none { it == EdgeRuntimePanel.AUTONOMY_EVIDENCE_SATURATED_LINE },
        )
    }

    @Test
    fun stopped_wording_is_verbatim() {        val panel = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.STOPPED)),
        )

        assertEquals("관측 중지", panel.headline)
    }

    @Test
    fun absent_fields_render_unknown_never_zero() {
        val panel = EdgeRuntimePanel.render(input(status = null))

        assertEquals("Edge 실행 상태 미확인", panel.headline)
        assertNull(panel.linkLine)
        assertTrue(panel.details.any { it.contains("부재는 false/0이 아님") })
        val blob = panel.headline + panel.details.joinToString()
        assertTrue("absent must never render as 0", !blob.contains("0프레임"))
        assertTrue("absent must never render as a zero finding", !blob.contains("0건"))
        assertTrue("absent must not borrow the analyzed-zero headline", !blob.contains("분석 수행됨"))
    }

    @Test
    fun unknown_state_with_no_session_says_unknown() {
        val panel = EdgeRuntimePanel.render(
            input(status = null, hasActiveFlight = false, linkUp = false),
        )

        assertEquals("Edge 실행 상태 미확인", panel.headline)
        assertNull("no link-loss claim without a session", panel.linkLine)
    }

    @Test
    fun link_loss_wording_is_distinct_from_input_loss_wording() {
        val linkPanel = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 9L),
                linkUp = false,
                lastConfirmedUtc = "2026-10-20T01:02:03Z",
            ),
        )
        val inputPanel = EdgeRuntimePanel.render(
            input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    reasons = listOf("video_input_lost"),
                    inputs = mapOf("video" to "lost"),
                ),
            ),
        )

        assertNotNull(linkPanel.linkLine)
        assertTrue(linkPanel.headline.contains("Edge 통신 단절 · 마지막 확인 실행 시각 2026-10-20T01:02:03Z"))
        assertTrue("link loss never estimates normality",
            (linkPanel.headline + linkPanel.details.joinToString()).contains("정상"))
        assertEquals(OperatorStatus.Severity.PROBLEM, linkPanel.severity)

        assertNotNull(inputPanel.inputLossLine)
        assertEquals("영상 입력 단절 · 관측 저하", inputPanel.inputLossLine)
        assertNull("input loss is not link loss", inputPanel.linkLine)

        assertTrue("messages must differ", linkPanel.headline != inputPanel.inputLossLine)
        assertTrue(
            "link-loss message must not mention video input loss",
            !linkPanel.headline.contains("영상 입력 단절"),
        )
        assertTrue(
            "input-loss message must not mention link loss",
            !inputPanel.inputLossLine!!.contains("통신 단절"),
        )
    }

    @Test
    fun link_loss_without_a_confirmed_time_says_unknown_not_guessed() {
        val panel = EdgeRuntimePanel.render(
            input(
                status = status(state = EdgeRuntimeState.RUNNING, frames = 3L),
                linkUp = false,
                lastConfirmedUtc = null,
            ),
        )

        assertTrue(panel.headline.contains("마지막 확인 실행 시각 미확인"))
    }

    @Test
    fun zero_note_line_is_fixed() {
        assertEquals("0건 관측(분석 수행됨) ≠ 분석 결과 없음", EdgeRuntimePanel.ZERO_NOTE)
    }

    @Test
    fun unbound_reports_are_display_only_never_execution() {
        // Foreign digest / missing install hash: fields were reported, but
        // none bound — the panel says so instead of "no report" or a state.
        val unbound = EdgeRuntimePanel.render(
            input(status = null, unboundReportSeen = true),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, unbound.headline)
        assertTrue(unbound.details.any { it.contains("바인딩 미확인") })
        assertTrue(unbound.details.none { it.contains("실행 중") || it.contains("관측 중") })

        // A bound report without runtime fields: reported, but the execution
        // state itself is still unknown — never 0/없음.
        val boundUnknown = EdgeRuntimePanel.render(
            input(status = status(state = EdgeRuntimeState.UNKNOWN), unboundReportSeen = false),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, boundUnknown.headline)
        assertTrue(boundUnknown.details.any { it.contains("실행 상태 미확인") })
    }
}
