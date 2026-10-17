package ksi.msdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure conversion rules used by [MsdkFlightTelemetrySource]: the derived
 * home distance (no direct SDK key exists — two position keys combined) and
 * the NED velocity display convention.
 */
class GeoDistanceTest {

    @Test
    fun same_point_is_zero() {
        assertEquals(0.0, GeoDistance.haversineMeters(37.0, 127.0, 37.0, 127.0), 1e-9)
    }

    @Test
    fun one_degree_of_latitude_is_about_111_km() {
        val d = GeoDistance.haversineMeters(37.0, 127.0, 38.0, 127.0)
        assertEquals(111_195.0, d, 50.0)
    }

    @Test
    fun small_offset_matches_expected_scale_and_is_symmetric() {
        val a = GeoDistance.haversineMeters(37.0000, 127.0000, 37.0010, 127.0000)
        val b = GeoDistance.haversineMeters(37.0010, 127.0000, 37.0000, 127.0000)
        assertEquals(a, b, 1e-9)
        assertEquals(111.2, a, 0.5)
    }

    @Test
    fun horizontal_speed_is_the_magnitude_of_north_east() {
        assertEquals(5.0, horizontalSpeedMs(3.0, 4.0), 0.0)
        assertEquals(0.0, horizontalSpeedMs(0.0, 0.0), 0.0)
    }

    @Test
    fun vertical_speed_uses_the_up_positive_display_convention() {
        assertEquals(2.5, verticalSpeedMs(-2.5), 0.0) // NED down=-2.5 → 상승 2.5
        assertEquals(-3.0, verticalSpeedMs(3.0), 0.0)
    }
}
