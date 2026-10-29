package ksi.safetyedge.rc

/**
 * Non-visual eligibility for the explicit "Edge 관측 시작 요청" CTA.
 *
 * Pure function over caller-supplied snapshot facts. It NEVER writes a
 * start intent and NEVER calls [RcSession.requestSessionStart] — the
 * parent (MainActivity) calls `requestSessionStart()` only on the
 * explicit CTA tap, and only when [Decision.canRequest] is true.
 *
 * Eligibility mirrors the actual request path:
 * - [RcSession.requestSessionStart] throws unless storage is available,
 *   a mission controller exists (flight started), and an active flight
 *   row exists; it then delegates to `MissionController.startSession`.
 * - `MissionController.startSession` requires state `APPLIED`
 *   (`check(state == MissionState.APPLIED)`) and refuses while the
 *   epoch gate is set (`needsRevalidation` — Edge restart/reinstall
 *   demands an explicit re-prepare + re-apply first). The gate arrives
 *   as [Input.needsRevalidation] and forces RECOVER (never requestable)
 *   — an install that is not what we sent is never trusted.
 *
 * Frozen mapping (parent owns UI copy and binding):
 * 1. `!storageAvailable` -> STORAGE_UNAVAILABLE (unknown stands as open;
 *    storage-down also forces [RcSession.hasOpenStartIntent] true, so it
 *    outranks every other stage).
 * 2. `!hasActiveFlight || controllerStateName == null` ->
 *    PREPARE_REQUIRED (no flight / no controller yet).
 * 3. `SESSION_PENDING` -> REQUEST_PENDING (a SESSION_START was ACKed;
 *    the Edge running state is unconfirmed — NEVER reported as running).
 *    Pending/running keep their actual reported stage even while the
 *    epoch gate is set, but are never requestable.
 * 4. `RUNNING` -> RUNNING (Edge-confirmed execution; analysis truth stays
 *    with [AutonomousSupervision], never this policy).
 * 5. `hasOpenStartIntent || unresolvedCloseout || needsRevalidation` ->
 *    RECOVER (a start intent stands without close-out, an UNRESOLVED
 *    close-out stands, or the Edge generation changed demanding explicit
 *    re-prepare + re-apply; the flight stays blocked until an actual
 *    STOPPED is observed / a fresh-generation APPLY lands).
 * 6. `!linkConnected` -> PREPARE_REQUIRED (link-down is not requestable;
 *    there is no LINK_DOWN stage in the frozen enum, so it shares the
 *    not-ready bucket — canRequest is false either way).
 * 7. `APPLIED` -> READY (the only requestable stage).
 * 8. Anything else (`IDLE`, `PREPARING`, `PREPARED`, `APPLYING`,
 *    `STATUS_UNKNOWN`, `STOPPED`, `REJECTED`, unknown/future names) ->
 *    PREPARE_REQUIRED (startSession accepts APPLIED only; `STOPPED`
 *    needs a fresh prepare/apply cycle).
 *
 * Integration notes (NOT weakened — caller must honor):
 * - READY covers state-level eligibility only while the install binding
 *   holds: the caller passes `controller.needsRevalidation` through
 *   [Input.needsRevalidation] (defaults false), and opening the flight
 *   UI, rendering a stage, or polling snapshots must never write intents
 *   or request.
 */
object ObservationStartPolicy {

    enum class Stage {
        PREPARE_REQUIRED,
        READY,
        REQUEST_PENDING,
        RUNNING,
        RECOVER,
        STORAGE_UNAVAILABLE,
    }

    data class Input(
        val storageAvailable: Boolean,
        val hasActiveFlight: Boolean,
        /** [ksi.rc.MissionState] name, or null when no controller exists. */
        val controllerStateName: String?,
        val linkConnected: Boolean,
        /** [RcSession.hasOpenStartIntent] for the active flight. */
        val hasOpenStartIntent: Boolean,
        /** A durable UNRESOLVED close-out (or otherwise unknown stop) stands. */
        val unresolvedCloseout: Boolean,
        /**
         * `MissionController.needsRevalidation` (Edge restart/reinstall:
         * the recorded runtime generation is gone). Defaults false so
         * existing callers keep prior behavior; true forces RECOVER.
         */
        val needsRevalidation: Boolean = false,
    )

    data class Decision(val stage: Stage, val canRequest: Boolean)

    fun decide(input: Input): Decision {
        if (!input.storageAvailable) {
            return Decision(Stage.STORAGE_UNAVAILABLE, false)
        }
        if (!input.hasActiveFlight || input.controllerStateName == null) {
            return Decision(Stage.PREPARE_REQUIRED, false)
        }
        // SESSION_PENDING is an accepted-but-unconfirmed command: it is
        // never running, and it outranks intent/close-out facts (the
        // request is already in flight — converge, never re-session).
        if (input.controllerStateName == "SESSION_PENDING") {
            return Decision(Stage.REQUEST_PENDING, false)
        }
        if (input.controllerStateName == "RUNNING") {
            return Decision(Stage.RUNNING, false)
        }
        if (input.hasOpenStartIntent || input.unresolvedCloseout || input.needsRevalidation) {
            return Decision(Stage.RECOVER, false)
        }
        if (!input.linkConnected) {
            return Decision(Stage.PREPARE_REQUIRED, false)
        }
        if (input.controllerStateName == "APPLIED") {
            return Decision(Stage.READY, true)
        }
        return Decision(Stage.PREPARE_REQUIRED, false)
    }
}
