package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round50: the observe view state machine carries display mode only.
 * Switching views never mutates flight, command, recording, AI or link
 * state — there is no payload to mutate. A missing map source resolves to
 * an overlay status (see MainActivity.refreshObserveView contract, pinned
 * in layout tests); the view state itself only tracks mode + load.
 */
class ObserveViewContractTest {

    @Test
    fun initial_view_is_video_idle() {
        assertEquals(ObserveView.Mode.VIDEO, ObserveView.initial.mode)
        assertEquals(ObserveView.Load.IDLE, ObserveView.initial.load)
    }

    @Test
    fun switch_changes_only_mode_and_resets_load() {
        val loading = ObserveView.switch(ObserveView.initial, ObserveView.Mode.MAP)
        assertEquals(ObserveView.Mode.MAP, loading.mode)
        assertEquals(ObserveView.Load.LOADING, loading.load)
        val back = ObserveView.switch(loading, ObserveView.Mode.VIDEO)
        assertEquals(ObserveView.Mode.VIDEO, back.mode)
        assertEquals(ObserveView.Load.LOADING, back.load)
    }

    @Test
    fun switch_to_same_mode_changes_nothing() {
        val state = ObserveView.State(ObserveView.Mode.MAP, ObserveView.Load.READY)
        assertEquals(state, ObserveView.switch(state, ObserveView.Mode.MAP))
    }

    @Test
    fun load_outcome_keeps_mode_and_never_claims_new_state() {
        val loading = ObserveView.switch(ObserveView.initial, ObserveView.Mode.MAP)
        assertEquals(
            ObserveView.State(ObserveView.Mode.MAP, ObserveView.Load.READY),
            ObserveView.onLoaded(loading, ok = true),
        )
        assertEquals(
            ObserveView.State(ObserveView.Mode.MAP, ObserveView.Load.ERROR),
            ObserveView.onLoaded(loading, ok = false),
        )
    }

    @Test
    fun state_carries_no_flight_record_or_command_payload() {
        // The contract: State must remain exactly (mode, load). A view
        // switch therefore cannot mutate flight, record, AI or link state.
        val fields = ObserveView.State::class.java.declaredFields
            .filter { !it.isSynthetic && it.name != "\$stable" && it.name != "Companion" && !it.name.contains("$") }
            .map { it.name }.toSet()
        assertEquals(setOf("mode", "load"), fields)
    }
}
