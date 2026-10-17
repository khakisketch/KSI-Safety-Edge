package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-fact preservation regression for the RC cockpit redesign.
 *
 * Pins the OperatorStatus behavior contract against UI-presenter drift:
 * facts only, honest gaps, canonical severities. Pure JVM, no Android
 * framework. Complements [OperatorStatusTest] — cases here are the
 * missing edges/combinations, not duplicates of its exact cases.
 */
class CockpitSourceFactsContractTest {
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
    fun connected_without_heartbeat_reports_misusin_not_normal() {
        val items = OperatorStatus.evaluate(
            input(hasActiveFlight = true, mopHeartbeatAgeMs = null),
        )
        val item = items.item("MOP 연결")
        assertNotNull(item)
        assertEquals(OperatorStatus.Severity.INFO, item!!.severity)
        assertTrue(item.detail.contains("하트비트 미수신"))
        assertTrue(items.none { it.label.startsWith("MOP 정상") })
    }

    @Test
    fun no_active_session_never_suppresses_link_faults() {
        // Non-CONNECTED, non-CONNECTING link states fall to the else branch.
        // Uses TIMEOUT/FAILED rather than the DISCONNECTED case pinned in
        // OperatorStatusTest.
        for (state in listOf("TIMEOUT", "FAILED", "")) {
            val items = OperatorStatus.evaluate(
                input(hasActiveFlight = false, linkState = state,
                    mopHeartbeatAgeMs = null, observationAgeMs = null,
                    recorderState = null),
            )
            val item = items.item("MOP 링크 끊김")
            assertNotNull("linkState=$state", item)
            assertEquals("linkState=$state",
                OperatorStatus.Severity.PROBLEM, item!!.severity)
            assertNotNull("linkState=$state", item.action)
        }
        // CONNECTING stays ATTENTION even before any session exists.
        val connecting = OperatorStatus.evaluate(
            input(hasActiveFlight = false, linkState = "CONNECTING",
                mopHeartbeatAgeMs = null, observationAgeMs = null,
                recorderState = null),
        )
        assertEquals(OperatorStatus.Severity.ATTENTION,
            connecting.item("MOP 연결 중")!!.severity)
    }

    @Test
    fun recording_progress_null_zero_and_positive_stay_distinct() {
        val unknown = OperatorStatus.evaluate(
            input().copy(recorderWrittenFrames = null,
                recorderHasWriteFailure = null),
        )
        val unknownItem = unknown.item("영상 녹화 중")
        assertNotNull(unknownItem)
        assertEquals(OperatorStatus.Severity.INFO, unknownItem!!.severity)
        assertTrue(unknownItem.detail.contains("미확인"))

        val waiting = OperatorStatus.evaluate(
            input().copy(recorderWrittenFrames = 0L,
                recorderHasWriteFailure = null),
        )
        assertNotNull(waiting.item("녹화 입력 대기"))
        assertEquals(OperatorStatus.Severity.INFO,
            waiting.item("녹화 입력 대기")!!.severity)

        val writing = OperatorStatus.evaluate(
            input().copy(recorderWrittenFrames = 48L,
                recorderHasWriteFailure = null),
        )
        assertNotNull(writing.item("영상 녹화 중"))
        assertTrue(writing.none { it.label == "녹화 입력 대기" })
    }

    @Test
    fun sticky_write_failure_dominates_without_positive_count() {
        // OperatorStatusTest pins failure + 10 frames; these are the missing
        // null / zero-count sticky edges.
        for (frames in listOf(null, 0L)) {
            val failed = OperatorStatus.evaluate(
                input().copy(recorderWrittenFrames = frames,
                    recorderHasWriteFailure = true),
            )
            val item = failed.item("녹화 오류")
            assertNotNull("frames=$frames", item)
            assertEquals("frames=$frames",
                OperatorStatus.Severity.PROBLEM, item!!.severity)
            assertTrue("frames=$frames",
                failed.none { it.label == "영상 녹화 중" })
            assertTrue("frames=$frames",
                failed.none { it.label == "녹화 입력 대기" })
        }
    }

    @Test
    fun aircraft_unknown_vs_disconnected_keep_canonical_severity() {
        val unknown = OperatorStatus.evaluate(input(aircraftConnected = null))
        val unknownItem = unknown.item("기체 상태 알 수 없음")
        assertNotNull(unknownItem)
        assertEquals(OperatorStatus.Severity.INFO, unknownItem!!.severity)
        assertTrue(unknownItem.detail.contains("추측"))

        val off = OperatorStatus.evaluate(input(aircraftConnected = false))
        val offItem = off.item("기체 미연결")
        assertNotNull(offItem)
        assertEquals(OperatorStatus.Severity.ATTENTION, offItem!!.severity)

        val on = OperatorStatus.evaluate(input(aircraftConnected = true))
        assertEquals(OperatorStatus.Severity.INFO,
            on.item("기체 연결")!!.severity)
    }

    @Test
    fun observation_gap_carries_count_without_safety_claim() {
        // Non-zero events isolates the gap line from the "사건 0건" line
        // pinned in OperatorStatusTest; null cover shows the "-" fallback.
        val items = OperatorStatus.evaluate(
            input(eventCount = 3, skippedFrames = 12L, coveredFrames = null),
        )
        val gap = items.item("관측 공백 12프레임")
        assertNotNull(gap)
        assertEquals(OperatorStatus.Severity.INFO, gap!!.severity)
        assertTrue(gap.detail.contains("안전은 아님"))
        assertTrue(items.none { it.label.startsWith("사건 0건") })
    }

    @Test
    fun unsynced_and_late_opinions_keep_canonical_severity_by_core_config() {
        val unsynced = OperatorStatus.evaluate(
            input(artifactsTotal = 3, artifactsComplete = 3,
                artifactsSynced = 1, coreConfigured = true),
        )
        assertEquals(OperatorStatus.Severity.INFO,
            unsynced.item("미동기화 증거 2")!!.severity)

        // No media + opinions, Core configured: no "Core 미설정" suffix.
        val lateConfigured = OperatorStatus.evaluate(input(
            artifactsTotal = 0, artifactsComplete = 0, artifactsSynced = 0,
            pendingAnnotations = 2, coreConfigured = true,
        ))
        assertNotNull(lateConfigured.item("로컬 증거 없음"))
        val lateItem = lateConfigured.item("늦은 의견 동기화 대기")
        assertNotNull(lateItem)
        assertEquals(OperatorStatus.Severity.ATTENTION, lateItem!!.severity)
        assertTrue(lateItem.detail.contains("2건"))

        // Same facts, Core unconfigured: canonical suffix preserved.
        val lateUnconfigured = OperatorStatus.evaluate(input(
            artifactsTotal = 0, artifactsComplete = 0, artifactsSynced = 0,
            pendingAnnotations = 2, coreConfigured = false,
        ))
        assertTrue(lateUnconfigured.item("늦은 의견 동기화 대기")!!.detail
            .contains("Core 미설정"))
    }
}
