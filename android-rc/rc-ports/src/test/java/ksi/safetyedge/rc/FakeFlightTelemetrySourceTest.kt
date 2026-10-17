package ksi.safetyedge.rc

import ksi.safetyedge.rc.FlightTelemetryAccumulator.Field
import ksi.safetyedge.rc.FlightTelemetryAccumulator.FieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeFlightTelemetrySourceTest {

    @Test
    fun feed_updates_accumulator_and_notifies_only_while_running() {
        val source = FakeFlightTelemetrySource()
        var updates = 0

        assertFailsWith<IllegalStateException> {
            source.feedNumber(Field.BATTERY, 86.0, atMs = 1_000)
        }

        source.start { updates += 1 }
        source.feedNumber(Field.BATTERY, 86.0, atMs = 1_000)
        source.feedMode("GPS_ATTI", atMs = 1_000)
        assertEquals(2, updates)
        assertEquals(FieldState.FRESH, source.accumulator.fieldState(Field.BATTERY, nowMs = 2_000))
        assertEquals("GPS_ATTI", source.accumulator.snapshot().flightMode)

        source.stop()
        assertFailsWith<IllegalStateException> {
            source.feedMode("GPS_ATTI", atMs = 3_000)
        }
        assertEquals(2, updates)
    }

    @Test
    fun mark_unsupported_is_visible_through_the_shared_accumulator() {
        val source = FakeFlightTelemetrySource()
        source.start {}
        source.markUnsupported(Field.HOME_DISTANCE)
        assertEquals(
            FieldState.UNSUPPORTED,
            source.accumulator.fieldState(Field.HOME_DISTANCE, nowMs = 1_000),
        )
    }
}
