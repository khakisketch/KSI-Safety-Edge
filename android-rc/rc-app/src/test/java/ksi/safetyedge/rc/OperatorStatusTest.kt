package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the operator status contract (B-1). These pin the wording
 * rules the operator relies on: facts only, honest gaps, and an action
 * wherever one exists — plus the rule that "0 events" is never shown as
 * safety.
 */
class OperatorStatusTest {
    private fun input(
        hasActiveFlight: Boolean = true,
        linkState: String = "CONNECTED",
        linkSimulated: Boolean = false,
        aircraftConnected: Boolean? = true,
        internet: String = "validated",
        recorderState: String? = "RECORDING",
        mopHeartbeatAgeMs: Long? = 3_000L,
        observationAgeMs: Long? = 2_000L,
        eventCount: Int = 5,
        artifactsTotal: Int = 2,
        artifactsComplete: Int = 2,
        artifactsSynced: Int = 2,
        pendingAnnotations: Int = 0,
        coveredFrames: Long? = 100,
        skippedFrames: Long? = 0,
        coreConfigured: Boolean = true,
    ) = OperatorStatus.Input(
        hasActiveFlight, linkState, linkSimulated, aircraftConnected, internet,
        recorderState, mopHeartbeatAgeMs, observationAgeMs, eventCount,
        artifactsTotal, artifactsComplete, artifactsSynced, pendingAnnotations,
        coveredFrames, skippedFrames, coreConfigured,
    )

    private fun List<OperatorStatus.Item>.item(label: String) =
        firstOrNull { it.label.startsWith(label) }

    @Test
    fun link_down_is_a_problem_with_an_action() {
        val items = OperatorStatus.evaluate(input(linkState = "DISCONNECTED"))
        val item = items.item("MOP 링크 끊김")
        assertNotNull(item)
        assertEquals(OperatorStatus.Severity.PROBLEM, item!!.severity)
        assertNotNull(item.action)
    }

    @Test
    fun heartbeat_and_observation_ages_are_reported_honestly() {
        val fresh = OperatorStatus.evaluate(input())
        assertNotNull(fresh.item("MOP 정상"))
        assertTrue(fresh.item("MOP 정상")!!.detail.contains("3s"))

        val stale = OperatorStatus.evaluate(
            input(mopHeartbeatAgeMs = 42_000L, observationAgeMs = 9_000L),
        )
        assertEquals(
            OperatorStatus.Severity.ATTENTION,
            stale.item("기체 하트비트 지연")!!.severity,
        )
        assertEquals(
            OperatorStatus.Severity.ATTENTION,
            stale.item("AI 관측 지연")!!.severity,
        )
    }

    @Test
    fun no_flight_yet_reports_the_flow_without_alarm() {
        val items = OperatorStatus.evaluate(
            input(hasActiveFlight = false, linkState = "DISCONNECTED",
                mopHeartbeatAgeMs = null, observationAgeMs = null,
                recorderState = null),
        )
        assertEquals(OperatorStatus.Severity.INFO, items.item("비행 없음")!!.severity)
        // The link problem still shows (actionable even before a flight).
        assertEquals(
            OperatorStatus.Severity.PROBLEM,
            items.item("MOP 링크 끊김")!!.severity,
        )
    }

    @Test
    fun recording_states_are_distinguished() {
        assertNotNull(
            OperatorStatus.evaluate(input(recorderState = null)).item("레코더 없음"),
        )
        assertEquals(
            OperatorStatus.Severity.PROBLEM,
            OperatorStatus.evaluate(input(recorderState = "ERROR")).item("녹화 오류")!!.severity,
        )
        assertEquals(
            OperatorStatus.Severity.ATTENTION,
            OperatorStatus.evaluate(input(recorderState = "IDLE")).item("녹화 대기")!!.severity,
        )
        assertEquals(
            OperatorStatus.Severity.INFO,
            OperatorStatus.evaluate(input(recorderState = "RECORDING"))
                .item("영상 녹화 중")!!.severity,
        )
    }

    @Test
    fun evidence_and_sync_states_follow_local_facts_only() {
        val receiving = OperatorStatus.evaluate(
            input(artifactsTotal = 3, artifactsComplete = 1),
        )
        assertEquals(
            OperatorStatus.Severity.ATTENTION,
            receiving.item("증거 수신 중")!!.severity,
        )

        val pendingSync = OperatorStatus.evaluate(
            input(artifactsTotal = 3, artifactsSynced = 1),
        )
        assertNotNull(pendingSync.item("미동기화 증거 2"))

        val lateOpinion = OperatorStatus.evaluate(
            input(artifactsTotal = 2, artifactsSynced = 2, pendingAnnotations = 1),
        )
        assertEquals(
            OperatorStatus.Severity.ATTENTION,
            lateOpinion.item("늦은 의견 동기화 대기")!!.severity,
        )

        val clean = OperatorStatus.evaluate(input())
        assertNotNull(clean.item("증거 동기화 완료"))

        val noCore = OperatorStatus.evaluate(input(coreConfigured = false))
        assertNotNull(noCore.item("Core 미설정"))
    }

    @Test
    fun zero_events_is_never_safety() {
        val items = OperatorStatus.evaluate(
            input(eventCount = 0, skippedFrames = 7L),
        )
        val zero = items.item("사건 0건")
        assertNotNull(zero)
        assertTrue(zero!!.detail.contains("안전 확인 아님"))
        val gap = items.item("관측 공백 7프레임")
        assertNotNull(gap)
        assertTrue(gap!!.detail.contains("안전은 아님"))
    }

    @Test
    fun unknown_aircraft_and_simulated_link_are_labeled() {
        val items = OperatorStatus.evaluate(
            input(aircraftConnected = null, linkSimulated = true),
        )
        assertNotNull(items.item("기체 상태 알 수 없음"))
        assertNotNull(items.item("MOP 정상 [SIMULATED]"))
    }

    @Test
    fun internet_states_stay_honest() {
        assertNotNull(
            OperatorStatus.evaluate(input(internet = "no internet")).item("인터넷 없음"),
        )
        assertNotNull(
            OperatorStatus.evaluate(input(internet = "connected (unvalidated)"))
                .item("인터넷 미검증"),
        )
        // Validated / unknown internet adds no line (no noise, no claims).
        assertTrue(OperatorStatus.evaluate(input(internet = "validated")).none {
            it.label.startsWith("인터넷")
        })
    }
}
