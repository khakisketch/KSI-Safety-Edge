package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitStatusPresenterTest {
    private fun input(
        hasActiveFlight: Boolean = true,
        linkState: String = "CONNECTED",
        aircraftConnected: Boolean? = true,
        internet: String = "validated",
        recorderWrittenFrames: Long? = 10L,
        recorderHasWriteFailure: Boolean? = false,
        mopHeartbeatAgeMs: Long? = 3_000L,
        observationAgeMs: Long? = 2_000L,
        skippedFrames: Long? = 0L,
        artifactsTotal: Int = 3,
        artifactsComplete: Int = 3,
        artifactsSynced: Int = 1,
        pendingAnnotations: Int = 0,
        coreConfigured: Boolean = true,
    ) = OperatorStatus.Input(
        hasActiveFlight = hasActiveFlight,
        linkState = linkState,
        linkSimulated = false,
        aircraftConnected = aircraftConnected,
        internet = internet,
        recorderState = "RECORDING",
        mopHeartbeatAgeMs = mopHeartbeatAgeMs,
        observationAgeMs = observationAgeMs,
        eventCount = 2,
        artifactsTotal = artifactsTotal,
        artifactsComplete = artifactsComplete,
        artifactsSynced = artifactsSynced,
        pendingAnnotations = pendingAnnotations,
        coveredFrames = 100,
        skippedFrames = skippedFrames,
        coreConfigured = coreConfigured,
        recorderWrittenFrames = recorderWrittenFrames,
        recorderHasWriteFailure = recorderHasWriteFailure,
    )

    private fun overview(input: OperatorStatus.Input) =
        CockpitStatusPresenter.present(
            OperatorStatus.evaluate(input),
            input.hasActiveFlight,
            (input.artifactsTotal - input.artifactsSynced).coerceAtLeast(0),
        )

    @Test
    fun connected_mop_without_heartbeat_stays_distinct_from_healthy() {
        val result = overview(input(mopHeartbeatAgeMs = null))

        assertEquals(OperatorStatus.Severity.INFO, result.mop.severity)
        assertTrue(result.mop.value.contains("연결"))
        assertTrue(result.mop.value.contains("하트비트 미수신"))
        assertTrue(result.mop.value != "정상 · 하트비트 3s 전")
    }

    @Test
    fun active_local_session_detail_does_not_claim_aircraft_flight_state() {
        val result = overview(input())

        assertEquals("진행 중", result.ksiSession.value)
        assertTrue(result.ksiSession.detail.contains("실제 기체 비행 상태와는 별도"))
    }

    @Test
    fun stale_heartbeat_never_replaces_the_independent_aircraft_fact() {
        listOf(
            Triple(true, "연결", OperatorStatus.Severity.INFO),
            Triple(false, "미연결", OperatorStatus.Severity.ATTENTION),
            Triple(null, "알 수 없음", OperatorStatus.Severity.INFO),
        ).forEach { (aircraftConnected, expected, severity) ->
            val result = overview(
                input(mopHeartbeatAgeMs = 11_000L, aircraftConnected = aircraftConnected),
            )

            assertEquals(OperatorStatus.Severity.ATTENTION, result.mop.severity)
            assertTrue(result.mop.value.contains("하트비트 지연"))
            assertEquals(expected, result.aircraft.value)
            assertEquals(severity, result.aircraft.severity)
        }
    }

    @Test
    fun evidence_keeps_unsynced_count_and_receiving_attention_severity() {
        val result = overview(
            input(artifactsComplete = 1, artifactsSynced = 1),
        )

        assertEquals(OperatorStatus.Severity.ATTENTION, result.evidence.severity)
        assertTrue(result.evidence.value.contains("미동기화 2"))
        assertTrue(result.evidence.value.contains("수신 1/3"))
    }

    @Test
    fun core_unconfigured_keeps_actual_unsynced_count_and_partial_receipt_severity() {
        val result = overview(
            input(
                artifactsTotal = 3,
                artifactsComplete = 2,
                artifactsSynced = 1,
                coreConfigured = false,
            ),
        )

        assertEquals(OperatorStatus.Severity.ATTENTION, result.evidence.severity)
        assertTrue(result.evidence.value.contains("미동기화 2건"))
        assertTrue(result.evidence.value.contains("수신 2/3"))
        assertTrue(result.evidence.value.contains("Core 미설정"))
    }

    @Test
    fun core_unconfigured_still_shows_zero_unsynced_from_the_local_store() {
        val result = overview(
            input(
                artifactsTotal = 3,
                artifactsComplete = 3,
                artifactsSynced = 3,
                coreConfigured = false,
            ),
        )

        assertEquals(OperatorStatus.Severity.INFO, result.evidence.severity)
        assertTrue(result.evidence.value.contains("미동기화 0건"))
        assertTrue(result.evidence.value.contains("Core 미설정"))
    }

    @Test
    fun late_opinion_attention_is_visible_from_the_source_item() {
        val result = overview(
            input(
                artifactsTotal = 2,
                artifactsComplete = 2,
                artifactsSynced = 2,
                pendingAnnotations = 2,
            ),
        )

        assertEquals(OperatorStatus.Severity.ATTENTION, result.evidence.severity)
        assertTrue(result.evidence.value.contains("늦은 의견 2건"))
    }

    @Test
    fun recording_progress_unknown_keeps_source_info_severity() {
        val result = overview(input(recorderWrittenFrames = null))

        assertEquals(OperatorStatus.Severity.INFO, result.recorder.severity)
        assertTrue(result.recorder.value.contains("수치 미확인"))
    }

    @Test
    fun priority_contains_the_source_action_and_recording_failure_stays_first() {
        val result = overview(
            input(
                linkState = "DISCONNECTED",
                observationAgeMs = 6_000L,
                recorderHasWriteFailure = true,
            ),
        )

        assertNotNull(result.priority)
        assertEquals("녹화 오류", result.priority!!.label)
        assertEquals("로그 확인", result.priority!!.action)
    }

    @Test
    fun unknown_only_statuses_do_not_create_a_priority_alert() {
        val result = overview(
            input(
                aircraftConnected = null,
                mopHeartbeatAgeMs = null,
                observationAgeMs = null,
                artifactsTotal = 0,
                artifactsComplete = 0,
                artifactsSynced = 0,
                recorderWrittenFrames = null,
            ),
        )

        assertNull(result.priority)
        assertTrue(result.mop.value.contains("하트비트 미수신"))
        assertEquals("알 수 없음", result.aircraft.value)
    }

    @Test
    fun priority_with_null_source_action_does_not_invent_an_action() {
        val result = overview(input(aircraftConnected = false))

        assertNotNull(result.priority)
        assertEquals("기체 미연결", result.priority!!.label)
        assertNull(result.priority!!.action)
    }

    @Test
    fun positive_observation_gap_stays_info_and_does_not_become_a_priority_warning() {
        val input = input(observationAgeMs = 2_000L, skippedFrames = 4L)
        val items = OperatorStatus.evaluate(input)
        val gap = items.firstOrNull { it.label.startsWith("관측 공백 ") }
        val result = CockpitStatusPresenter.present(
            items,
            input.hasActiveFlight,
            (input.artifactsTotal - input.artifactsSynced).coerceAtLeast(0),
        )

        assertNotNull(gap)
        assertEquals(OperatorStatus.Severity.INFO, gap!!.severity)
        assertNull(result.priority)
    }

    @Test
    fun missing_observation_summary_is_not_presented_as_zero_gap() {
        val input = input(observationAgeMs = 2_000L, skippedFrames = null)
        val items = OperatorStatus.evaluate(input)

        assertTrue(items.none { it.label.startsWith("관측 공백 ") })
    }
}
