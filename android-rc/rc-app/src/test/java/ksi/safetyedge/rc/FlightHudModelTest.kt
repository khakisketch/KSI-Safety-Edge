package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the read-only HUD presentation model: latency suffixes are
 * split for the warning badge, unknown headings never become 0°, and the
 * compass tape is centered only on an actually reported heading.
 */
class FlightHudModelTest {

    @Test
    fun staleSuffixIsSplitWithoutChangingTheValue() {
        val fresh = FlightHudModel.splitStale("0.0m/s")
        assertEquals("0.0m/s", fresh.text)
        assertNull(fresh.stale)

        val stale = FlightHudModel.splitStale("0.0m/s·지연6초")
        assertEquals("0.0m/s", stale.text)
        assertEquals("지연6초", stale.stale)
    }

    @Test
    fun unknownValuesStayUnknown() {
        val value = FlightHudModel.splitStale("미수신")
        assertEquals("미수신", value.text)
        assertNull(value.stale)
        assertNull(FlightHudModel.headingDegrees("미수신"))
        assertNull(FlightHudModel.headingDegrees("미지원"))
    }

    @Test
    fun headingIsParsedFromFreshAndStaleText() {
        assertEquals(-0.9, FlightHudModel.headingDegrees("-0.9°")!!, 0.0001)
        assertEquals(359.1, FlightHudModel.headingDegrees("-0.9°·지연6초")!! + 360.0, 0.0001)
        assertEquals(120.0, FlightHudModel.headingDegrees("120.0°")!!, 0.0001)
        assertEquals(0.0, FlightHudModel.normalizeHeading(360.0), 0.0001)
        assertEquals(359.1, FlightHudModel.normalizeHeading(-0.9), 0.0001)
    }

    @Test
    fun tapeLabelsUseCardinalsOrThreeDigits() {
        assertEquals("N", FlightHudModel.tapeLabel(0.0))
        assertEquals("E", FlightHudModel.tapeLabel(90.0))
        assertEquals("S", FlightHudModel.tapeLabel(180.0))
        assertEquals("W", FlightHudModel.tapeLabel(270.0))
        assertEquals("330", FlightHudModel.tapeLabel(330.0))
        assertEquals("030", FlightHudModel.tapeLabel(30.0))
        assertEquals("120", FlightHudModel.tapeLabel(120.0))
        // The approved H2/H1 tape centers -0.9° as N while the value row
        // keeps the exact -0.9°. Beyond the display tolerance stays numeric.
        assertEquals("N", FlightHudModel.tapeLabel(359.1))
        assertEquals("N", FlightHudModel.tapeLabel(1.0))
        assertEquals("358", FlightHudModel.tapeLabel(358.0))
        assertEquals(0.0, FlightHudModel.tapeCenter(359.2), 0.0001)
        assertEquals(355.0, FlightHudModel.tapeCenter(357.0), 0.0001)
        assertEquals(0.0, FlightHudModel.tapeCenter(-0.8), 0.0001)
    }

    @Test
    fun tapeAroundNorthMatchesTheApprovedLabels() {
        val labels = FlightHudModel.tapeLabels(0.0, listOf(-60, -30, 0, 30, 60))
            .map { it.text }
        assertEquals(listOf("300", "330", "N", "030", "060"), labels)
        assertEquals(
            listOf("300", "330", "N", "030", "060"),
            FlightHudModel.tapeLabels(359.2, listOf(-60, -30, 0, 30, 60)).map { it.text },
        )
    }

    @Test
    fun tapeAroundEastKeepsTheCenterHeading() {
        val labels = FlightHudModel.tapeLabels(120.0, listOf(-60, -30, 0, 30, 60))
            .map { it.text }
        assertEquals(listOf("060", "E", "120", "150", "S"), labels)
    }

    @Test
    fun unknownHeadingUsesNeutralReferenceWithoutACurrentMarker() {
        val labels = FlightHudModel.tapeLabels(null, listOf(-30, 0, 30))
            .map { it.text }
        assertEquals(listOf("330", "N", "030"), labels)
    }

    @Test
    fun staleBadgeNamesTheFirstStaleField() {
        assertEquals(
            "속도 지연 6초",
            FlightHudModel.staleBadge(
                listOf(
                    "고도" to null,
                    "거리" to null,
                    "속도" to "지연6초",
                    "방위" to null,
                ),
            ),
        )
        assertNull(FlightHudModel.staleBadge(listOf("고도" to null, "속도" to null)))
        assertTrue(
            FlightHudModel.staleBadge(listOf("방위" to "지연12초"))!!.contains("12초"),
        )
    }
}
