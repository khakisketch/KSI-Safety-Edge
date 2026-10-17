package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlAuthorityTest {
    @Test
    fun requested_without_confirmed_is_transition_never_edge_auto() {
        val s = ControlAuthorityModel.fromSignals(
            edgeAutoRequested = true, edgeAutoConfirmed = false,
            failsafeActive = false, rcManualInput = false, linkOk = true,
        )
        assertEquals(Owner.TRANSITION, s.owner)
        assertFalse(s.confirmed)
    }

    @Test
    fun edge_auto_only_when_requested_and_confirmed_without_manual() {
        val auto = ControlAuthorityModel.fromSignals(true, true, false, false, true)
        assertEquals(Owner.EDGE_AUTO, auto.owner)
        assertTrue(auto.confirmed)
        val manualWins = ControlAuthorityModel.fromSignals(true, true, false, true, true)
        assertEquals(Owner.MANUAL, manualWins.owner)
    }

    @Test
    fun failsafe_wins_over_all_signals() {
        val s = ControlAuthorityModel.fromSignals(true, true, true, true, true)
        assertEquals(Owner.FAILSAFE, s.owner)
        assertTrue(s.confirmed)
    }

    @Test
    fun link_down_without_signals_is_unknown() {
        val s = ControlAuthorityModel.fromSignals(false, false, false, false, false)
        assertEquals(Owner.UNKNOWN, s.owner)
        assertFalse(s.confirmed)
    }

    @Test
    fun default_is_manual() {
        val s = ControlAuthorityModel.fromSignals(false, false, false, false, true)
        assertEquals(Owner.MANUAL, s.owner)
        assertTrue(s.confirmed)
    }
}
