package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM eligibility for the explicit observation-start CTA.
 *
 * Mirrors the real request path (`RcSession.requestSessionStart` ->
 * `MissionController.startSession`): READY only on the explicit known
 * allowed state (APPLIED) with an active flight, storage, link up, and
 * no open intent / unresolved close-out. SESSION_PENDING never reports
 * running; RUNNING reflects the actual confirmed controller state.
 */
class ObservationStartPolicyTest {

    private fun base() = ObservationStartPolicy.Input(
        storageAvailable = true,
        hasActiveFlight = true,
        controllerStateName = "APPLIED",
        linkConnected = true,
        hasOpenStartIntent = false,
        unresolvedCloseout = false,
    )

    @Test
    fun readyOnlyWhenAllFactsAllow() {
        val d = ObservationStartPolicy.decide(base())
        assertEquals(ObservationStartPolicy.Stage.READY, d.stage)
        assertTrue(d.canRequest)
    }

    @Test
    fun storageDownOutranksEverything() {
        val d = ObservationStartPolicy.decide(
            base().copy(
                storageAvailable = false,
                controllerStateName = "RUNNING",
                hasOpenStartIntent = true,
                unresolvedCloseout = true,
            ),
        )
        assertEquals(ObservationStartPolicy.Stage.STORAGE_UNAVAILABLE, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun noActiveFlightNeedsPrepare() {
        val d = ObservationStartPolicy.decide(base().copy(hasActiveFlight = false))
        assertEquals(ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun nullControllerNeedsPrepare() {
        val d = ObservationStartPolicy.decide(base().copy(controllerStateName = null))
        assertEquals(ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun unknownStateNameNeedsPrepare() {
        val d = ObservationStartPolicy.decide(base().copy(controllerStateName = "SOME_FUTURE_STATE"))
        assertEquals(ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun preApplyStatesNeedPrepare() {
        for (state in listOf("IDLE", "PREPARING", "PREPARED", "APPLYING", "STATUS_UNKNOWN", "REJECTED")) {
            val d = ObservationStartPolicy.decide(base().copy(controllerStateName = state))
            assertEquals("state=$state", ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
            assertFalse("state=$state", d.canRequest)
        }
    }

    @Test
    fun stoppedNeedsFreshPrepareApplyCycle() {
        // startSession accepts APPLIED only — a stopped session restarts
        // the cycle explicitly, never straight back to start.
        val d = ObservationStartPolicy.decide(base().copy(controllerStateName = "STOPPED"))
        assertEquals(ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun linkDownIsNotRequestable() {
        val d = ObservationStartPolicy.decide(base().copy(linkConnected = false))
        assertEquals(ObservationStartPolicy.Stage.PREPARE_REQUIRED, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun openStartIntentAfterRestartNeedsRecover() {
        // Crash/restart with a start intent standing and no close-out:
        // blocked until an actual STOPPED is observed.
        val d = ObservationStartPolicy.decide(
            base().copy(controllerStateName = "IDLE", hasOpenStartIntent = true),
        )
        assertEquals(ObservationStartPolicy.Stage.RECOVER, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun openIntentBlocksEvenApplied() {
        val d = ObservationStartPolicy.decide(base().copy(hasOpenStartIntent = true))
        assertEquals(ObservationStartPolicy.Stage.RECOVER, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun unresolvedCloseoutNeedsRecover() {
        val d = ObservationStartPolicy.decide(base().copy(unresolvedCloseout = true))
        assertEquals(ObservationStartPolicy.Stage.RECOVER, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun sessionPendingNeverReportsRunning() {
        val d = ObservationStartPolicy.decide(
            base().copy(controllerStateName = "SESSION_PENDING", hasOpenStartIntent = true),
        )
        assertEquals(ObservationStartPolicy.Stage.REQUEST_PENDING, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun runningReflectsConfirmedControllerState() {
        val d = ObservationStartPolicy.decide(base().copy(controllerStateName = "RUNNING"))
        assertEquals(ObservationStartPolicy.Stage.RUNNING, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun appliedWithEpochGateNeedsRecover() {
        // APPLIED but the Edge generation changed (restart/reinstall):
        // startSession refuses until a fresh prepare + apply lands.
        val d = ObservationStartPolicy.decide(base().copy(needsRevalidation = true))
        assertEquals(ObservationStartPolicy.Stage.RECOVER, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun epochGateDoesNotMaskPendingOrRunning() {
        // Reconnect-unsafe: the reported command/runtime state keeps its
        // presentation, but nothing is requestable while revalidation
        // is outstanding.
        var d = ObservationStartPolicy.decide(
            base().copy(controllerStateName = "SESSION_PENDING", needsRevalidation = true),
        )
        assertEquals(ObservationStartPolicy.Stage.REQUEST_PENDING, d.stage)
        assertFalse(d.canRequest)
        d = ObservationStartPolicy.decide(
            base().copy(controllerStateName = "RUNNING", needsRevalidation = true),
        )
        assertEquals(ObservationStartPolicy.Stage.RUNNING, d.stage)
        assertFalse(d.canRequest)
    }

    @Test
    fun defaultGateKeepsPriorBehavior() {
        // needsRevalidation defaults to false: omitting it preserves the
        // pre-gate READY behavior for the all-facts-allow snapshot.
        val d = ObservationStartPolicy.decide(base())
        assertEquals(ObservationStartPolicy.Stage.READY, d.stage)
        assertTrue(d.canRequest)
    }

    @Test
    fun canRequestTrueOnlyForReady() {
        val allInputs = listOf(
            base(),
            base().copy(storageAvailable = false),
            base().copy(hasActiveFlight = false),
            base().copy(controllerStateName = null),
            base().copy(controllerStateName = "IDLE"),
            base().copy(controllerStateName = "SESSION_PENDING"),
            base().copy(controllerStateName = "RUNNING"),
            base().copy(controllerStateName = "STOPPED"),
            base().copy(linkConnected = false),
            base().copy(hasOpenStartIntent = true),
            base().copy(unresolvedCloseout = true),
        )
        for (input in allInputs) {
            val d = ObservationStartPolicy.decide(input)
            assertEquals(
                "input=$input",
                d.stage == ObservationStartPolicy.Stage.READY,
                d.canRequest,
            )
        }
    }
}
