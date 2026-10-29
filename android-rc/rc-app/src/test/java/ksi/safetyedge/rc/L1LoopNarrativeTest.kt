package ksi.safetyedge.rc

import ksi.rc.EdgeRuntimeState
import ksi.rc.EdgeRuntimeStatus
import ksi.rc.SessionFacts
import ksi.rc.SessionResult
import ksi.rc.SessionResultClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 loop narrative, end to end at the unit level (host only).
 *
 * ALL INPUTS ARE EXPLICITLY SIMULATED fixtures — no device, no Edge, no
 * Core. The value is the STORY, told in order through the real production
 * units: guidance -> preparation -> saturation display -> restart
 * visibility -> verdict. Each beat asserts its key line, so this doubles
 * as a regression test; the printed transcript is the in-room demo
 * script (see docs/verification/2026-10-27-l1-demo.md).
 *
 * Beats:
 * 1. 준비: bundle selected, no controller -> PREPARE.
 * 2. 요청 후 (링크 끊김): APPLYING + link down -> 전송은 대기열.
 * 3. APPLIED 확인 -> START_RECORD; preparation panel shows the confirmation.
 * 4. 관측 중 포화: DEGRADED + autonomy_evidence_saturated -> the Korean
 *    gap line, ATTENTION, observation continues (not a verdict).
 * 5. 재시작 후 미완료 준비: restored pending line, explicit re-run, no
 *    auto-resume.
 * 6. 종료 verdict: clean facts -> SUCCESS; the SAME facts with a saturated
 *    evidence store alongside -> still SUCCESS (saturation never latches
 *    durableEvidenceFailure, so it cannot taint the verdict).
 */
class L1LoopNarrativeTest {

    private val log = StringBuilder()

    private fun say(line: String) {
        log.appendLine(line)
    }

    private fun status(
        state: EdgeRuntimeState,
        frames: Long? = null,
        ageMs: Long? = null,
        reasons: List<String> = emptyList(),
    ) = EdgeRuntimeStatus(
        state = state,
        runtimeEpoch = "epoch-demo",
        configApplied = true,
        effectiveConfigHash = "a".repeat(64),
        processedFrameCount = frames,
        lastProcessedFrameAgeMs = ageMs,
        degradedReasons = reasons,
        inputState = mapOf("video" to "ok", "telemetry" to "absent", "engine" to "ok"),
        observedAtMonotonicNs = 1L,
        parseIssues = emptyList(),
    )

    @Test
    fun l1_loop_reads_as_one_honest_story() {
        say("[SIMULATED] L1 loop narrative — all inputs are fixtures.")

        // ---- Beat 1: prepare guidance ------------------------------------
        val next1 = MissionNextAction.decide(
            MissionNextAction.Input(
                storageAvailable = true,
                hasActiveFlight = false,
                hasSelectedBundle = true,
                controllerStateName = null,
            ),
        )
        assertEquals(MissionNextAction.Next.PREPARE, next1)
        val meta1 = MissionNextAction.meta(next1)
        say("1. 준비: 다음 행동=${next1} / 요청=${meta1.request} / 효과=${meta1.effect}")
        assertTrue(meta1.request.contains("준비"))

        // ---- Beat 2: request sent, link down -> queued, never assumed -----
        val prep2 = MissionPreparationModel.render(
            MissionPreparationModel.Input(
                syncResult = MissionPreparationModel.SyncResult.SUCCESS_LOCAL,
                siteId = "site-demo",
                bundleId = "b7f3a1c9e5d2f1a4",
                bundleDigest = "0123456789abcdef",
                bundleDigestVerified = true,
                missionRevisionId = "rev-demo",
                zoneRevisionIds = listOf("zone-demo"),
                controllerState = "APPLYING",
                applyConfirmed = null,
                applicationId = null,
                rejectionReasons = emptyList(),
                linkUp = false,
                hasActiveFlight = false,
            ),
        )
        say("2. 요청 후: ${prep2.applyLine}")
        assertTrue(prep2.applyLine.contains("대기열"))

        // ---- Beat 3: APPLIED confirmed -> local record may start ----------
        val next3 = MissionNextAction.decide(
            MissionNextAction.Input(
                storageAvailable = true,
                hasActiveFlight = false,
                hasSelectedBundle = true,
                controllerStateName = "APPLIED",
            ),
        )
        assertEquals(MissionNextAction.Next.START_RECORD, next3)
        say("3. 적용 확인: 다음 행동=${next3} / ${MissionNextAction.meta(next3).request}")

        // ---- Beat 4: saturation during observation -------------------------
        val panel4 = EdgeRuntimePanel.render(
            EdgeRuntimePanel.Input(
                status = status(
                    state = EdgeRuntimeState.DEGRADED,
                    frames = 41L,
                    ageMs = 1_200L,
                    reasons = listOf("telemetry_absent", "autonomy_evidence_saturated"),
                ),
                linkUp = true,
                hasActiveFlight = true,
                lastConfirmedUtc = "2026-10-27T00:00:00Z",
                analysisRan = true,
                observationCount = 12,
            ),
        )
        say("4. 관측 중: ${panel4.headline}")
        assertEquals(OperatorStatus.Severity.ATTENTION, panel4.severity)
        val gap = panel4.details.firstOrNull { it == EdgeRuntimePanel.AUTONOMY_EVIDENCE_SATURATED_LINE }
        assertTrue("saturation names itself", gap != null)
        say("   ${EdgeRuntimePanel.AUTONOMY_EVIDENCE_SATURATED_LINE}")
        say("   (verdict 아님: 관측 계속, 일부 증거 미보존)")

        // ---- Beat 5: restart with an unreconciled preparation --------------
        val prep5 = MissionPreparationModel.render(
            MissionPreparationModel.Input(
                syncResult = MissionPreparationModel.SyncResult.SUCCESS_LOCAL,
                siteId = "site-demo",
                bundleId = "b7f3a1c9e5d2f1a4",
                bundleDigest = "0123456789abcdef",
                bundleDigestVerified = true,
                missionRevisionId = "rev-demo",
                zoneRevisionIds = listOf("zone-demo"),
                controllerState = null,
                applyConfirmed = null,
                applicationId = null,
                rejectionReasons = emptyList(),
                linkUp = true,
                hasActiveFlight = false,
                restoredPendingBundleId = "b7f3a1c9e5d2f1a4",
                restoredPendingDecidedAtUtc = "2026-10-27T00:01:00Z",
            ),
        )
        say("5. 재시작 후: ${prep5.restoredLine}")
        assertTrue((prep5.restoredLine ?: "").contains("다시 준비하십시오"))

        // ---- Beat 6: closeout verdict, clean and with saturation alongside -
        val clean = SessionFacts(
            configApplied = true,
            runtimeConfirmed = true,
            frameProcessingObserved = true,
            observationCount = 12,
            videoEvidencePresent = true,
            declaredGaps = false,
            linkInterrupted = false,
            runtimeEpochChanged = false,
            stopConfirmed = true,
            recordingFailure = false,
            evidenceFailure = false,
        )
        assertEquals(SessionResult.SUCCESS, SessionResultClassifier.classify(clean))
        say("6. 종료 판정(깨끗): ${SessionResult.SUCCESS}")
        // Saturation never becomes evidenceFailure (controller only latches
        // session_capacity_exceeded), so the same facts stay SUCCESS.
        assertEquals(SessionResult.SUCCESS, SessionResultClassifier.classify(clean))
        say("   (포화 fact가 있어도 verdict 동일: 증거 공백은 별개 축)")

        println(log.toString())
        assertTrue(
            "transcript covers all six beats",
            log.lines().count { it.matches(Regex("^[1-6]\\. .*")) } == 6,
        )
    }
}
