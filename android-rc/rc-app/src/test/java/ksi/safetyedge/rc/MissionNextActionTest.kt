package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure derivation tests for the fixed-dock next-action line
 * ([MissionNextAction]): one concise outcome per state, null controller is
 * unknown (never ready), and no branch claims readiness it cannot prove.
 */
class MissionNextActionTest {

    private fun decide(
        storageAvailable: Boolean = true,
        hasActiveFlight: Boolean = false,
        hasSelectedBundle: Boolean = true,
        controllerStateName: String? = "IDLE",
    ) = MissionNextAction.decide(
        MissionNextAction.Input(
            storageAvailable = storageAvailable,
            hasActiveFlight = hasActiveFlight,
            hasSelectedBundle = hasSelectedBundle,
            controllerStateName = controllerStateName,
        ),
    )

    @Test
    fun storage_down_wins_over_everything() {
        assertEquals(
            MissionNextAction.Next.STORAGE_DOWN,
            decide(storageAvailable = false, hasActiveFlight = true, controllerStateName = "APPLIED"),
        )
    }

    @Test
    fun active_flight_points_at_ops() {
        assertEquals(
            MissionNextAction.Next.RECORDING,
            decide(hasActiveFlight = true, hasSelectedBundle = false, controllerStateName = null),
        )
    }

    @Test
    fun no_bundle_select_first() {
        assertEquals(
            MissionNextAction.Next.SELECT_BUNDLE,
            decide(hasSelectedBundle = false, controllerStateName = "IDLE"),
        )
    }

    @Test
    fun outstanding_responses_wait() {
        for (state in listOf("PREPARING", "APPLYING", "SESSION_PENDING")) {
            assertEquals("state $state", MissionNextAction.Next.WAITING, decide(controllerStateName = state))
        }
    }

    @Test
    fun prepared_then_applied_sequence() {
        assertEquals(MissionNextAction.Next.APPLY, decide(controllerStateName = "PREPARED"))
        assertEquals(MissionNextAction.Next.START_RECORD, decide(controllerStateName = "APPLIED"))
    }

    @Test
    fun idle_with_bundle_is_prepare() {
        assertEquals(MissionNextAction.Next.PREPARE, decide(controllerStateName = "IDLE"))
    }

    @Test
    fun unknown_and_reconcile_states_stay_unknown() {
        for (state in listOf("STATUS_UNKNOWN", "REJECTED", "STOPPED", "RUNNING", "SOME_FUTURE_STATE")) {
            assertEquals("state $state", MissionNextAction.Next.UNKNOWN, decide(controllerStateName = state))
        }
    }

    @Test
    fun no_controller_with_selected_bundle_is_prepare() {
        assertEquals(
            MissionNextAction.Next.PREPARE,
            decide(hasSelectedBundle = true, controllerStateName = null),
        )
    }

    @Test
    fun no_controller_without_bundle_selects_first() {
        assertEquals(
            MissionNextAction.Next.SELECT_BUNDLE,
            decide(hasSelectedBundle = false, controllerStateName = null),
        )
    }
}
