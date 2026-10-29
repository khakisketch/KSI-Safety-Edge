package ksi.safetyedge.rc

import ksi.rc.EdgeRuntimeState
import ksi.rc.EdgeRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the Edge runtime reflection in the cockpit 6-fact strip
 * (the AI/observation cell) via [OperatorStatus] + [CockpitStatusPresenter]:
 * verbatim state wording shared with [EdgeRuntimePanel], UNKNOWN when
 * fields are absent, DEGRADED reasons shown, and link-loss wording
 * distinct from input-loss wording.
 */
class EdgeRuntimeStatusStripTest {

    private fun baseInput(
        hasActiveFlight: Boolean = true,
        eventCount: Int = 0,
    ) = OperatorStatus.Input(
        hasActiveFlight = hasActiveFlight,
        linkState = "CONNECTED",
        linkSimulated = false,
        aircraftConnected = true,
        internet = "validated",
        recorderState = "RECORDING",
        mopHeartbeatAgeMs = 1_000L,
        observationAgeMs = 1_000L,
        eventCount = eventCount,
        artifactsTotal = 1,
        artifactsComplete = 1,
        artifactsSynced = 1,
        pendingAnnotations = 0,
        coveredFrames = 10,
        skippedFrames = 0L,
        coreConfigured = true,
    )

    private fun runtimeStatus(
        state: EdgeRuntimeState,
        frames: Long? = null,
        reasons: List<String> = emptyList(),
        inputs: Map<String, String> = emptyMap(),
    ) = EdgeRuntimeStatus(
        state = state,
        runtimeEpoch = "epoch-1",
        configApplied = true,
        effectiveConfigHash = "hash-1",
        processedFrameCount = frames,
        lastProcessedFrameAgeMs = 100L,
        degradedReasons = reasons,
        inputState = inputs,
        observedAtMonotonicNs = 1L,
        parseIssues = emptyList(),
    )

    private fun presentWithEdge(
        input: OperatorStatus.Input,
        linkUp: Boolean = true,
        lastConfirmedUtc: String? = null,
        analysisRan: Boolean? = null,
        observationCount: Int? = null,
        status: EdgeRuntimeStatus? = null,
    ): CockpitStatusPresenter.Overview {
        val edgeInput = EdgeRuntimePanel.Input(
            status = status,
            linkUp = linkUp,
            hasActiveFlight = input.hasActiveFlight,
            lastConfirmedUtc = lastConfirmedUtc,
            analysisRan = analysisRan,
            observationCount = observationCount,
        )
        val edge = EdgeRuntimePanel.render(edgeInput)
        return CockpitStatusPresenter.present(
            OperatorStatus.evaluate(input),
            input.hasActiveFlight,
            (input.artifactsTotal - input.artifactsSynced).coerceAtLeast(0),
            edge = edge,
        )
    }

    @Test
    fun running_state_reaches_the_observation_cell_verbatim() {
        val status = runtimeStatus(EdgeRuntimeState.RUNNING, frames = 11L)
        val input = baseInput().copy(
            edgeRuntimeSeen = true,
            edgeRuntimeState = "RUNNING",
            edgeProcessedFrames = 11L,
        )
        val result = presentWithEdge(input, status = status)

        assertEquals("관측 실행 중 · 처리 11프레임", result.observation.value)
        assertEquals(OperatorStatus.Severity.INFO, result.observation.severity)
        val items = OperatorStatus.evaluate(input)
        assertTrue(items.any { it.label == "관측 실행 중 · 처리 11프레임" })
    }

    @Test
    fun each_state_wording_matches_the_panel_single_source() {
        val cases = mapOf(
            EdgeRuntimeState.REQUEST_ACCEPTED to "요청 수신 · 실행 시작 전",
            EdgeRuntimeState.CONFIG_APPLIED to "설정 적용됨 · 관측 시작 전",
            EdgeRuntimeState.STARTING to "관측 기동 중",
            EdgeRuntimeState.STOPPED to "관측 중지",
        )
        cases.forEach { (state, wording) ->
            val input = baseInput().copy(
                edgeRuntimeSeen = true,
                edgeRuntimeState = state.name,
            )
            val result = presentWithEdge(
                input,
                status = runtimeStatus(state),
            )

            assertEquals("$state must share the panel wording", wording, result.observation.value)
            assertEquals(
                "$state wording must equal EdgeRuntimePanel.stateLine",
                EdgeRuntimePanel.stateLine(state, null, emptyList()),
                result.observation.value,
            )
        }
    }

    @Test
    fun absent_runtime_fields_render_unknown_in_items_and_cell() {
        val input = baseInput()
        val items = OperatorStatus.evaluate(input)
        val unknown = items.firstOrNull { it.label == "Edge 실행 상태 미확인" }

        assertNotNull(unknown)
        assertEquals(OperatorStatus.Severity.INFO, unknown!!.severity)
        assertTrue(unknown.detail.contains("부재는 false/0이 아님"))

        val result = presentWithEdge(input, status = null)
        assertEquals("Edge 실행 상태 미확인", result.observation.value)
        assertNull("no link-loss claim on a healthy link", result.observation.action)
    }

    @Test
    fun degraded_reasons_are_shown_and_video_loss_is_a_separate_item() {
        val input = baseInput().copy(
            edgeRuntimeSeen = true,
            edgeRuntimeState = "DEGRADED",
            edgeProcessedFrames = 5L,
            edgeDegradedReasons = listOf("video_input_lost"),
            edgeInputState = mapOf("video" to "lost"),
        )
        val items = OperatorStatus.evaluate(input)
        val result = presentWithEdge(
            input,
            status = runtimeStatus(
                EdgeRuntimeState.DEGRADED,
                frames = 5L,
                reasons = listOf("video_input_lost"),
            ),
        )

        assertTrue(items.any { it.label == "관측 저하 · video_input_lost" })
        val videoLoss = items.firstOrNull { it.label == "영상 입력 단절 · 관측 저하" }
        assertNotNull(videoLoss)
        assertEquals(OperatorStatus.Severity.ATTENTION, videoLoss!!.severity)
        assertTrue(result.observation.value.contains("관측 저하 · video_input_lost"))
        assertEquals(OperatorStatus.Severity.ATTENTION, result.observation.severity)
    }

    @Test
    fun link_loss_item_differs_from_input_loss_item() {
        val input = baseInput().copy(
            edgeRuntimeSeen = true,
            edgeRuntimeState = "RUNNING",
            edgeProcessedFrames = 4L,
            edgeLinkUp = false,
            edgeLastConfirmedUtc = "2026-10-20T01:02:03Z",
        )
        val items = OperatorStatus.evaluate(input)

        val link = items.firstOrNull { it.label == "Edge 통신 단절" }
        assertNotNull(link)
        assertEquals(OperatorStatus.Severity.PROBLEM, link!!.severity)
        assertTrue(link.detail.contains("마지막 확인 실행 시각 2026-10-20T01:02:03Z"))
        assertTrue(link.detail.contains("정상으로 추정하지 않음"))
        assertTrue(
            "link loss must not borrow the input-loss message",
            !link.label.contains("영상 입력 단절") && !link.detail.contains("영상 입력 단절"),
        )
    }

    @Test
    fun unreported_state_name_is_unknown_never_crash() {
        val input = baseInput().copy(
            edgeRuntimeSeen = true,
            edgeRuntimeState = "FUTURE_STATE",
        )
        val items = OperatorStatus.evaluate(input)

        assertTrue(items.any { it.label == "Edge 실행 상태 미확인" })
    }

    @Test
    fun default_inputs_keep_legacy_strip_behavior() {
        // No Edge fields set (all existing callers): exactly one extra
        // INFO item, never a priority alert, never a changed cell fact.
        val input = baseInput()
        val items = OperatorStatus.evaluate(input)
        val edgeItems = items.filter { it.label.startsWith("Edge ") }

        assertEquals(1, edgeItems.size)
        assertEquals(OperatorStatus.Severity.INFO, edgeItems.first().severity)
        val result = CockpitStatusPresenter.present(
            items,
            input.hasActiveFlight,
            (input.artifactsTotal - input.artifactsSynced).coerceAtLeast(0),
        )
        assertEquals(
            "flight active but Edge silent: cell says unknown, never a guess",
            EdgeRuntimePanel.UNKNOWN_LINE,
            result.observation.value,
        )
    }
}
