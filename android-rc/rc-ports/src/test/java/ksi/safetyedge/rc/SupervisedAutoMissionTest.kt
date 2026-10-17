package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupervisedAutoMissionTest {
    private fun good() = Context(
        hasHome = true, hasPositionFix = true, batteryOk = true, linkOk = true, manualTakeover = false,
    )

    @Test
    fun takeoff_guards_block_start_without_home_or_position() {
        assertFalse(SupervisedAutoMission.canStart(good().copy(hasHome = false)))
        assertFalse(SupervisedAutoMission.canStart(good().copy(hasPositionFix = false)))
        assertFalse(SupervisedAutoMission.canStart(good().copy(batteryOk = false)))
        assertFalse(SupervisedAutoMission.canStart(good().copy(linkOk = false)))
        assertTrue(SupervisedAutoMission.canStart(good()))
        assertEquals(Phase.ERROR, SupervisedAutoMission.step(Phase.READY, "request", good().copy(hasHome = false)))
        assertEquals(
            Phase.ERROR,
            SupervisedAutoMission.step(Phase.READY, "request", good().copy(hasPositionFix = false)),
        )
    }

    @Test
    fun happy_path_prepare_to_executing() {
        val ctx = good()
        assertEquals(Phase.READY, SupervisedAutoMission.step(Phase.IDLE, "prepare", ctx))
        assertEquals(Phase.AUTO_REQUESTED, SupervisedAutoMission.step(Phase.READY, "request", ctx))
        assertEquals(Phase.AUTO_CONFIRMED, SupervisedAutoMission.step(Phase.AUTO_REQUESTED, "confirm", ctx))
        assertEquals(Phase.EXECUTING, SupervisedAutoMission.step(Phase.AUTO_CONFIRMED, "begin", ctx))
    }

    @Test
    fun pause_flow_and_no_silent_auto_resume() {
        val ctx = good()
        assertEquals(Phase.PAUSE_REQUESTED, SupervisedAutoMission.step(Phase.EXECUTING, "pauseReq", ctx))
        assertEquals(Phase.PAUSED, SupervisedAutoMission.step(Phase.PAUSE_REQUESTED, "pauseConfirm", ctx))
        // begin/resume attempts must not leave PAUSED without a fresh request.
        assertEquals(Phase.PAUSED, SupervisedAutoMission.step(Phase.PAUSED, "begin", ctx))
        assertEquals(Phase.PAUSED, SupervisedAutoMission.step(Phase.PAUSED, "confirm", ctx))
        // A fresh guarded request is the only resume path.
        assertEquals(Phase.AUTO_REQUESTED, SupervisedAutoMission.step(Phase.PAUSED, "request", ctx))
    }

    @Test
    fun manual_takeover_always_wins() {
        val manual = good().copy(manualTakeover = true)
        listOf(Phase.IDLE, Phase.READY, Phase.AUTO_REQUESTED, Phase.EXECUTING, Phase.PAUSED, Phase.RETURNING)
            .forEach {
                assertEquals(Phase.MANUAL_CONFIRMED, SupervisedAutoMission.step(it, "anything", manual), "$it")
            }
        // After manual, only a fresh guarded request restarts — no auto-resume.
        assertEquals(
            Phase.MANUAL_CONFIRMED,
            SupervisedAutoMission.step(Phase.MANUAL_CONFIRMED, "begin", good()),
        )
        assertEquals(
            Phase.AUTO_REQUESTED,
            SupervisedAutoMission.step(Phase.MANUAL_CONFIRMED, "request", good()),
        )
    }

    @Test
    fun return_flow_and_failure() {
        val ctx = good()
        assertEquals(Phase.RETURN_REQUESTED, SupervisedAutoMission.step(Phase.EXECUTING, "returnReq", ctx))
        assertEquals(Phase.RETURN_REQUESTED, SupervisedAutoMission.step(Phase.PAUSED, "returnReq", ctx))
        assertEquals(Phase.RETURNING, SupervisedAutoMission.step(Phase.RETURN_REQUESTED, "returnConfirm", ctx))
        assertEquals(Phase.DONE, SupervisedAutoMission.step(Phase.RETURNING, "complete", ctx))
        assertEquals(Phase.ERROR, SupervisedAutoMission.step(Phase.EXECUTING, "fail", ctx))
    }
}
