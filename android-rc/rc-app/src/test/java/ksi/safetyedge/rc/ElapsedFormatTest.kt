package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host tests for elapsed-time wording: floor units, unknown stays unknown,
 * never a fabricated zero.
 */
class ElapsedFormatTest {

    @Test
    fun null_and_negative_are_unknown_never_zero() {
        assertEquals("경과 미확인", ElapsedFormat.ageAgo(null))
        assertEquals("경과 미확인", ElapsedFormat.ageAgo(-1L))
    }

    @Test
    fun seconds_floor_without_rounding_up() {
        assertEquals("0초 전", ElapsedFormat.ageAgo(0L))
        assertEquals("0초 전", ElapsedFormat.ageAgo(999L))
        assertEquals("1초 전", ElapsedFormat.ageAgo(1_000L))
        assertEquals("59초 전", ElapsedFormat.ageAgo(59_999L))
    }

    @Test
    fun minutes_then_hours() {
        assertEquals("1분 전", ElapsedFormat.ageAgo(60_000L))
        assertEquals("59분 전", ElapsedFormat.ageAgo(3_599_999L))
        assertEquals("1시간 전", ElapsedFormat.ageAgo(3_600_000L))
        assertEquals("25시간 전", ElapsedFormat.ageAgo(90_000_000L))
    }
}
