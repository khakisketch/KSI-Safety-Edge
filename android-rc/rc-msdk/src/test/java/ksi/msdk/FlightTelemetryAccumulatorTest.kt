package ksi.msdk

import ksi.msdk.FlightTelemetryAccumulator.Field
import ksi.msdk.FlightTelemetryAccumulator.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the per-field freshness rules the operations contract
 * requires (docs/plans/2026-10-17-rc-operations-screen-contract.md §4·§5,
 * AT-03 신선도 절반) — 미수신 / 유효 / 지연 / 미지원 구분과
 * "마지막 확인값을 현재로 오인하지 않음".
 */
class FlightTelemetryAccumulatorTest {

    private fun accumulator() = FlightTelemetryAccumulator(staleAfterMs = 3_000)

    @Test
    fun fresh_reports_are_fresh_and_fill_the_snapshot() {
        val acc = accumulator()
        acc.onNumber(Field.ALTITUDE, 42.0, atMs = 1_000)
        acc.onNumber(Field.BATTERY, 86.0, atMs = 1_000)
        acc.onFlightMode("GPS_ATTI", atMs = 1_000)

        assertEquals(FieldState.FRESH, acc.fieldState(Field.ALTITUDE, nowMs = 2_000))
        assertEquals(FieldState.FRESH, acc.fieldState(Field.FLIGHT_MODE, nowMs = 2_000))

        val snap = acc.snapshot()
        assertEquals(42.0, snap.altitudeM!!, 0.0)
        assertEquals(86.0, snap.batteryPct!!, 0.0)
        assertEquals("GPS_ATTI", snap.flightMode)
        assertEquals(1_000L, snap.timestampMs)
        assertEquals(FlightTelemetryAccumulator.ALTITUDE_REF, snap.altitudeRef)
        assertNull(snap.positionFix) // 추정 금지: 고정 판정 기준 미확정
        assertTrue(acc.hasAnyReading())
    }

    @Test
    fun stale_field_keeps_last_value_but_is_not_current() {
        val acc = accumulator()
        acc.onNumber(Field.ALTITUDE, 42.0, atMs = 1_000)
        acc.onNumber(Field.BATTERY, 86.0, atMs = 9_000)

        assertEquals(FieldState.STALE, acc.fieldState(Field.ALTITUDE, nowMs = 10_000))
        assertEquals(FieldState.FRESH, acc.fieldState(Field.BATTERY, nowMs = 10_000))
        assertEquals(9_000L, acc.ageMs(Field.ALTITUDE, nowMs = 10_000))

        val snap = acc.snapshot()
        assertEquals(42.0, snap.altitudeM!!, 0.0) // 값은 지워지지 않는다
        assertEquals(9_000L, snap.timestampMs) // 최신 수신 시각(현재 주장 아님)
    }

    @Test
    fun never_received_is_distinct_from_stale() {
        val acc = accumulator()
        acc.onNumber(Field.BATTERY, 86.0, atMs = 1_000)
        assertEquals(FieldState.NEVER_RECEIVED, acc.fieldState(Field.ALTITUDE, nowMs = 1_500))
        assertNull(acc.ageMs(Field.ALTITUDE, nowMs = 1_500))
        assertNull(acc.snapshot().altitudeM)
    }

    @Test
    fun a_reading_never_refreshes_other_fields() {
        val acc = accumulator()
        acc.onNumber(Field.ALTITUDE, 10.0, atMs = 0)
        acc.onNumber(Field.HOME_DISTANCE, 5.0, atMs = 9_000)
        acc.onNumber(Field.BATTERY, 80.0, atMs = 9_100)
        assertEquals(FieldState.STALE, acc.fieldState(Field.ALTITUDE, nowMs = 9_200))
    }

    @Test
    fun unsupported_outranks_missing_and_a_real_report_supersedes_it() {
        val acc = accumulator()
        acc.markUnsupported(Field.HEADING)
        assertEquals(FieldState.UNSUPPORTED, acc.fieldState(Field.HEADING, nowMs = 1_000))
        assertNull(acc.ageMs(Field.HEADING, nowMs = 1_000))

        acc.onNumber(Field.HEADING, 268.0, atMs = 2_000) // 실보고가 지원 주장을 이긴다
        assertEquals(FieldState.FRESH, acc.fieldState(Field.HEADING, nowMs = 2_500))
    }

    @Test
    fun snapshot_without_readings_is_empty_and_timestampless() {
        val snap = accumulator().snapshot()
        assertNull(snap.timestampMs)
        assertNull(snap.altitudeM)
        assertNull(snap.batteryPct)
        assertNull(snap.flightMode)
        assertFalse(accumulator().hasAnyReading())
    }

    @Test
    fun negative_clock_skew_is_clamped_to_fresh() {
        val acc = accumulator()
        acc.onNumber(Field.ALTITUDE, 42.0, atMs = 5_000)
        assertEquals(FieldState.FRESH, acc.fieldState(Field.ALTITUDE, nowMs = 4_000))
        assertEquals(0L, acc.ageMs(Field.ALTITUDE, nowMs = 4_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun numeric_reports_for_the_mode_field_are_rejected() {
        accumulator().onNumber(Field.FLIGHT_MODE, 1.0, atMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blank_flight_mode_is_rejected() {
        accumulator().onFlightMode(" ", atMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun non_positive_staleness_window_is_rejected() {
        FlightTelemetryAccumulator(staleAfterMs = 0)
    }
}
