package ksi.safetyedge.rc

/**
 * Operation screen view switching (operation screen contract v1, AT-05).
 *
 * The view changes the layout and the visible surface only (video/map).
 * Flight, control authority, AI evaluation and recording states are not
 * inputs and cannot be changed here; a load failure surfaces as a view
 * error, never as a state or command change.
 */
object ObserveView {

    enum class Mode { VIDEO, MAP }

    enum class Load { IDLE, LOADING, READY, ERROR }

    data class State(val mode: Mode, val load: Load)

    val initial = State(Mode.VIDEO, Load.IDLE)

    fun switch(current: State, to: Mode): State =
        if (current.mode == to) current else State(to, Load.LOADING)

    fun onLoaded(current: State, ok: Boolean): State =
        current.copy(load = if (ok) Load.READY else Load.ERROR)
}
