package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The HUD style is a display preference only. An absent/unknown stored key
 * must fall back to the recommended default rather than crash or silently
 * change another setting.
 */
class FlightHudStyleTest {

    @Test
    fun default_isScale() {
        assertEquals(FlightHudStyle.SCALE, FlightHudStyle.DEFAULT)
        assertEquals(FlightHudStyle.SCALE, FlightHudStyle.fromKey(null))
        assertEquals(FlightHudStyle.SCALE, FlightHudStyle.fromKey(""))
        assertEquals(FlightHudStyle.SCALE, FlightHudStyle.fromKey("UNKNOWN"))
    }

    @Test
    fun everyStyleRoundTripsThroughItsKey() {
        FlightHudStyle.entries.forEach { style ->
            assertEquals(style, FlightHudStyle.fromKey(style.name))
        }
    }

    @Test
    fun labelsAreDistinctAndNamedForTheProposal() {
        val labels = FlightHudStyle.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("H1 텍스트", FlightHudStyle.BARE.label)
        assertEquals("H2 계기선", FlightHudStyle.SCALE.label)
        assertEquals("H3 링", FlightHudStyle.RETICLE.label)
        assertNotEquals(FlightHudStyle.BARE.label, FlightHudStyle.SCALE.label)
    }
}
