package ksi.safetyedge.rc

/** Supervised-auto mission phase machine (pure Kotlin, no Android/MSDK/PSDK).
 *
 *  Display/supervision reference only, not a flight-safety authority. Rules:
 *  manual takeover always wins; a paused mission never auto-resumes — only a
 *  fresh request leaves PAUSED; takeoff guards (home + position fix + battery
 *  + link) gate READY→AUTO_REQUESTED, never bypassed silently.
 */
enum class Phase {
    IDLE, READY, AUTO_REQUESTED, AUTO_CONFIRMED, EXECUTING,
    PAUSE_REQUESTED, PAUSED, MANUAL_CONFIRMED,
    RETURN_REQUESTED, RETURNING, DONE, ERROR, UNKNOWN,
}

data class Context(
    val hasHome: Boolean,
    val hasPositionFix: Boolean,
    val batteryOk: Boolean,
    val linkOk: Boolean,
    val manualTakeover: Boolean,
)

object SupervisedAutoMission {
    fun canStart(ctx: Context): Boolean =
        ctx.hasHome && ctx.hasPositionFix && ctx.batteryOk && ctx.linkOk

    fun step(current: Phase, event: String, ctx: Context): Phase {
        if (ctx.manualTakeover) return Phase.MANUAL_CONFIRMED
        if (event == "fail" || event == "error") return Phase.ERROR
        return when (current) {
            Phase.IDLE -> if (event == "prepare") Phase.READY else current
            Phase.READY -> when (event) {
                "request" -> if (canStart(ctx)) Phase.AUTO_REQUESTED else Phase.ERROR
                else -> current
            }
            Phase.AUTO_REQUESTED -> if (event == "confirm") Phase.AUTO_CONFIRMED else current
            Phase.AUTO_CONFIRMED -> if (event == "begin") Phase.EXECUTING else current
            Phase.EXECUTING -> when (event) {
                "pauseReq" -> Phase.PAUSE_REQUESTED
                "returnReq" -> Phase.RETURN_REQUESTED
                else -> current
            }
            Phase.PAUSE_REQUESTED -> if (event == "pauseConfirm") Phase.PAUSED else current
            // No silent auto-resume: begin/resume events stay PAUSED; only a
            // fresh request (guarded by canStart) or returnReq leaves PAUSED.
            Phase.PAUSED -> when (event) {
                "request" -> if (canStart(ctx)) Phase.AUTO_REQUESTED else Phase.ERROR
                "returnReq" -> Phase.RETURN_REQUESTED
                else -> Phase.PAUSED
            }
            // After manual takeover only a fresh guarded request restarts.
            Phase.MANUAL_CONFIRMED -> when (event) {
                "request" -> if (canStart(ctx)) Phase.AUTO_REQUESTED else Phase.ERROR
                else -> current
            }
            Phase.RETURN_REQUESTED -> if (event == "returnConfirm") Phase.RETURNING else current
            Phase.RETURNING -> if (event == "complete") Phase.DONE else current
            Phase.DONE, Phase.ERROR, Phase.UNKNOWN -> current
        }
    }
}
