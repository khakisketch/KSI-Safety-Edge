package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the read-only telemetry strip (operations contract v1
 * §4·§5): per-field freshness text, ceiled delay, no 0/normal substitution,
 * and no inference for authority/links.
 */
class TelemetryStripPresenterTest {

    private fun accumulator() = FlightTelemetryAccumulator(staleAfterMs = 3_000)

    private val mopOk = CockpitStatusPresenter.Fact(
        "MOP 링크", "정상", OperatorStatus.Severity.INFO,
    )

    @Test
    fun fresh_values_show_the_raw_value_without_delay_text() {
        val acc = accumulator()
        acc.onNumber(FlightTelemetryAccumulator.Field.BATTERY, 86.0, atMs = 1_000)
        acc.onFlightMode("GPS_ATTI", atMs = 1_000)
        acc.onNumber(FlightTelemetryAccumulator.Field.HOME_DISTANCE, 118.4, atMs = 1_000)

        val strip = TelemetryStripPresenter.present(acc, nowMs = 2_000)

        assertEquals("86%", strip.battery.value)
        assertEquals(OperatorStatus.Severity.INFO, strip.battery.severity)
        assertEquals("비행 모드: GPS_ATTI.", strip.flightMode.contentDescription)
        assertEquals("홈 거리 118 m", strip.position.value)
    }

    @Test
    fun stale_values_keep_the_last_confirmed_text_with_a_ceiled_delay() {
        val acc = accumulator()
        acc.onNumber(FlightTelemetryAccumulator.Field.BATTERY, 86.0, atMs = 1_000)

        // exactly 4.000 s → 4초 (ceil boundary stays small)
        val exact = TelemetryStripPresenter.present(acc, nowMs = 5_000)
        assertEquals("86% · 지연 4초", exact.battery.value)
        assertEquals(OperatorStatus.Severity.ATTENTION, exact.battery.severity)
        assertEquals("배터리: 마지막 확인 86%, 지연 4초.", exact.battery.contentDescription)

        // 4.001 s → 5초 (never displayed smaller than it is)
        val over = TelemetryStripPresenter.present(acc, nowMs = 5_001)
        assertEquals("86% · 지연 5초", over.battery.value)
        assertTrue(over.battery.detail.contains("현재 상태 아님"))
    }

    @Test
    fun never_received_and_unsupported_are_not_zero_or_normal() {
        val acc = accumulator()
        val never = TelemetryStripPresenter.present(acc, nowMs = 1_000)
        assertEquals("미수신", never.battery.value)
        assertEquals("미수신", never.flightMode.value)

        acc.markUnsupported(FlightTelemetryAccumulator.Field.BATTERY)
        val unsupported = TelemetryStripPresenter.present(acc, nowMs = 1_000)
        assertEquals("미지원", unsupported.battery.value)
    }

    @Test
    fun links_combine_existing_sources_and_keep_the_max_severity() {
        val aircraftAttention = CockpitStatusPresenter.Fact(
            "기체", "미연결", OperatorStatus.Severity.ATTENTION,
        )
        val strip = TelemetryStripPresenter.present(
            accumulator(),
            nowMs = 0,
            mop = mopOk,
            aircraft = aircraftAttention,
        )
        assertEquals("정상 · 미연결", strip.links.value)
        assertEquals(OperatorStatus.Severity.ATTENTION, strip.links.severity)
        assertTrue(
            "glance value carries no redundant link prefixes: ${strip.links.value}",
            !strip.links.value.contains("MOP ") && !strip.links.value.contains("기체 "),
        )

        val unknown = TelemetryStripPresenter.present(accumulator(), nowMs = 0)
        assertEquals("미확인 · 미확인", unknown.links.value)
        assertEquals(OperatorStatus.Severity.INFO, unknown.links.severity)
        assertTrue(
            "full sentence survives in the detail: ${unknown.links.detail}",
            unknown.links.detail.contains("MOP 상태 미확인 · 기체 상태 미확인"),
        )
        assertTrue(
            unknown.links.contentDescription.contains("MOP 상태 미확인 · 기체 상태 미확인"),
        )
    }

    @Test
    fun authority_stays_unconfirmed_until_a_report_source_exists() {
        val strip = TelemetryStripPresenter.present(accumulator(), nowMs = 0)
        assertEquals("미확인", strip.authority.value)
        assertEquals(OperatorStatus.Severity.INFO, strip.authority.severity)
        assertTrue(strip.authority.contentDescription.contains("제어권 보고 출처 미연결"))
    }

    @Test
    fun cells_are_exposed_in_strip_order() {
        val strip = TelemetryStripPresenter.present(accumulator(), nowMs = 0)
        assertEquals(
            listOf("제어 주체", "비행 모드", "배터리", "MOP · 기체 링크", "위치 · 홈"),
            strip.cells.map { it.label },
        )
    }
}
