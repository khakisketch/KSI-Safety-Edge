package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightTelemetryTest {
    private fun fresh() = Snapshot(
        altitudeM = 12.5,
        altitudeRef = "AGL",
        homeDistanceM = 30.0,
        horizontalSpeedMs = 2.0,
        verticalSpeedMs = 0.5,
        headingDeg = 90.0,
        batteryPct = 80.0,
        flightMode = "P-GPS",
        positionFix = true,
        timestampMs = 9_500L,
    )

    @Test
    fun null_snapshot_is_all_unknown_never_normal() {
        val items = FlightTelemetryModel.evaluate(null, 10_000L)
        assertTrue(items.isNotEmpty())
        items.forEach {
            assertEquals(DisplayItem.State.UNKNOWN, it.state)
            assertEquals("미확인", it.value)
        }
    }

    @Test
    fun null_values_are_unknown_not_ok() {
        val items = FlightTelemetryModel.evaluate(fresh().copy(altitudeM = null, flightMode = null), 10_000L)
        val byLabel = items.associateBy { it.label }
        assertEquals(DisplayItem.State.UNKNOWN, byLabel["고도"]!!.state)
        assertEquals("미확인", byLabel["고도"]!!.value)
        assertEquals(DisplayItem.State.UNKNOWN, byLabel["비행 모드"]!!.state)
    }

    @Test
    fun fresh_snapshot_is_ok_with_units() {
        val byLabel = FlightTelemetryModel.evaluate(fresh(), 10_000L).associateBy { it.label }
        assertEquals(DisplayItem.State.OK, byLabel["고도"]!!.state)
        assertEquals("12.5 m", byLabel["고도"]!!.value)
        assertEquals("2.0 m/s", byLabel["수평 속도"]!!.value)
        assertEquals("0.5 m/s", byLabel["수직 속도"]!!.value)
        assertEquals("90.0 °", byLabel["방향"]!!.value)
        assertEquals("80.0 %", byLabel["배터리"]!!.value)
        assertEquals("P-GPS", byLabel["비행 모드"]!!.value)
    }

    @Test
    fun old_snapshot_is_stale_with_delay_seconds() {
        val byLabel = FlightTelemetryModel.evaluate(fresh(), 15_000L).associateBy { it.label }
        assertEquals(DisplayItem.State.STALE, byLabel["고도"]!!.state)
        assertTrue(byLabel["고도"]!!.value.contains("지연 5초"), byLabel["고도"]!!.value)
    }

    @Test
    fun missing_timestamp_is_unknown_never_ok() {
        val items = FlightTelemetryModel.evaluate(fresh().copy(timestampMs = null), 10_000L)
        items.forEach {
            assertEquals(DisplayItem.State.UNKNOWN, it.state)
        }
    }
}
