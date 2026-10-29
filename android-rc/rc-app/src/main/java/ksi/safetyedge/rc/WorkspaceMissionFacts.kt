package ksi.safetyedge.rc

import ksi.rc.MissionController

/**
 * Workspace mission-step facts: a pure response-evidence snapshot for the
 * redesigned workspace UI (round74 visual/IA overhaul).
 *
 * The UI must never erase a confirmed PREPARED on a same-attempt
 * MISSION_APPLY_REJECTED, and must never present protocol APPLIED (or a
 * RUNNING report) as actual analysis. Both failures came from deriving the
 * steps straight from the current controller state. This adapter derives
 * them from response evidence instead:
 *
 * - an attempt is the transitions after the last PREPARING (a fresh
 *   PREPARING restarts the attempt; older attempts never confirm the
 *   current one);
 * - prepare is CONFIRMED only by a PREPARED response inside the current
 *   attempt, so a same-attempt REJECTED leaves it intact. A progressed (or
 *   refused) request context without PREPARED proof — APPLYING and later,
 *   or REJECTED — reads UNKNOWN, never NOT_REQUESTED: this instance's
 *   evidence cannot prove no historic request;
 * - apply is CONFIRMED only by the LATEST apply round's proof: an APPLIED
 *   response inside the current attempt that postdates the latest APPLYING
 *   PLUS a matching application receipt
 *   (`lastApplication.bundle_sha256 == selectedBundleSha` with an
 *   `application_id`). A new APPLYING cannot inherit an earlier APPLIED
 *   receipt — a current pending/refusal outranks it. A RUNNING /
 *   SESSION_PENDING / STOPPED report alone, or a receipt from a past
 *   attempt, never confirms. STATUS_UNKNOWN (reconciling a possibly lost
 *   reply) retains an already-confirmed proof but otherwise asserts
 *   nothing outstanding by itself, so it reads UNKNOWN, not PENDING;
 * - the steps bind to the current selection: evidence for another bundle
 *   is never reused for this one (unbound selections read NOT_REQUESTED);
 * - [MissionController.needsRevalidation] never reads as a fresh
 *   confirmation (REVALIDATE); a link drop preserves the old response
 *   facts and only flags [Facts.linkStale].
 *
 * Non-goals (settled elsewhere, unchanged): this adapter carries NO
 * user-facing strings, sends NO commands, writes NO store rows, and never
 * gates commands or changes policy — command eligibility stays with
 * [ObservationStartPolicy] and [MissionNextAction].
 *
 * Known provenance gap (reported, not papered over): [MissionController]
 * keeps `transitions`/`lastApplication` in memory only. After an RC
 * process restart a genuinely applied install therefore reads
 * NOT_REQUESTED until the Edge responds again — the adapter does NOT
 * consult durable store rows, because the store is keyed by flight while
 * this snapshot is bound to the current selection + controller instance.
 */
object WorkspaceMissionFacts {

    /** Per-step response-evidence outcome. The parent maps these to UI copy. */
    enum class Step {
        /**
         * No bound proof in this instance's evidence (also: unbound
         * selection). This is NOT a global "never requested" claim — the
         * current instance cannot prove no historic request — so the
         * parent renders it as not-yet-confirmed, never as a denial.
         */
        NOT_REQUESTED,
        /** Requested; the Edge response has not arrived yet. */
        PENDING,
        /** The Edge response confirmed this step for the current attempt. */
        CONFIRMED,
        /** The Edge refused (apply); prepare evidence is unaffected. */
        REJECTED,
        /** Contradictory or unrecognized evidence — never shown as ready. */
        UNKNOWN,
        /** Generation changed; re-run prepare/apply before trusting anything. */
        REVALIDATE,
    }

    /**
     * Pure input so tests (and previews) can drive [derive] without a
     * controller. [transitionNames] are [ksi.rc.MissionState] names in
     * order; [stateName] is the current one (an unrecognized name reads
     * UNKNOWN). Use [inputFrom] to build this from live objects.
     */
    data class Input(
        val selectedBundleSha: String?,
        val controllerBundleSha: String?,
        val stateName: String?,
        val transitionNames: List<String> = emptyList(),
        val lastApplicationSha: String? = null,
        val applicationId: String? = null,
        val rejectionReasons: List<String> = emptyList(),
        val needsRevalidation: Boolean = false,
        val linkConnected: Boolean = true,
    )

    /** Snapshot for one selection: two step outcomes plus binding flags. */
    data class Facts(
        val prepare: Step,
        val apply: Step,
        /** The controller evidence belongs to the current selection. */
        val boundToSelection: Boolean,
        /** Link is down: steps still show the last response facts. */
        val linkStale: Boolean,
        /** PREPARING count in [Input.transitionNames]; 0 = no attempt yet. */
        val currentAttempt: Int,
    )

    private const val PREPARING = "PREPARING"
    private const val PREPARED = "PREPARED"
    private const val APPLYING = "APPLYING"
    private const val STATUS_UNKNOWN = "STATUS_UNKNOWN"
    private const val APPLIED = "APPLIED"
    private const val SESSION_PENDING = "SESSION_PENDING"
    private const val RUNNING = "RUNNING"
    private const val STOPPED = "STOPPED"
    private const val REJECTED = "REJECTED"
    private const val IDLE = "IDLE"

    private val knownStates = setOf(
        IDLE, PREPARING, PREPARED, APPLYING, STATUS_UNKNOWN,
        APPLIED, SESSION_PENDING, RUNNING, STOPPED, REJECTED,
    )

    /**
     * Bound states that imply a progressed (or refused) request context:
     * without PREPARED proof the prepare step reads UNKNOWN, never
     * NOT_REQUESTED. Only IDLE (or an empty attempt) means no bound proof.
     */
    private val progressedStates = setOf(
        APPLYING, APPLIED, STATUS_UNKNOWN,
        SESSION_PENDING, RUNNING, STOPPED, REJECTED,
    )

    /** Builds a pure [Input] from the current selection + controller. */
    fun inputFrom(
        selectedBundleSha: String?,
        controller: MissionController?,
        linkConnected: Boolean,
    ): Input {
        if (controller == null) {
            return Input(
                selectedBundleSha = selectedBundleSha,
                controllerBundleSha = null,
                stateName = null,
                linkConnected = linkConnected,
            )
        }
        val receipt = controller.lastApplication
        return Input(
            selectedBundleSha = selectedBundleSha,
            controllerBundleSha = controller.bundleSha,
            stateName = controller.state.name,
            transitionNames = controller.transitions.map { it.state.name },
            lastApplicationSha = receipt?.get("bundle_sha256")
                ?.takeIf { it.isTextual }?.asText(),
            applicationId = receipt?.get("application_id")
                ?.takeIf { it.isTextual }?.asText(),
            rejectionReasons = controller.lastRejectionReasons,
            needsRevalidation = controller.needsRevalidation,
            linkConnected = linkConnected,
        )
    }

    /** Derives the per-step snapshot. Pure: no I/O, no commands. */
    fun derive(input: Input): Facts {
        val selected = input.selectedBundleSha
        val bound = selected != null &&
            input.controllerBundleSha != null &&
            selected == input.controllerBundleSha
        val linkStale = !input.linkConnected
        val attempt = input.transitionNames.count { it == PREPARING }
        if (!bound) {
            // No selection, no controller, or another plan's controller:
            // no bound proof in this instance — never reuse foreign proof,
            // never claim ready, and never deny a historic request either.
            return Facts(
                prepare = Step.NOT_REQUESTED,
                apply = Step.NOT_REQUESTED,
                boundToSelection = false,
                linkStale = linkStale,
                currentAttempt = attempt,
            )
        }
        val state = input.stateName
        if (state == null) {
            // Bound snapshot without a current state cannot anchor the
            // attempt, so neither step claims anything. (Unreachable via
            // [inputFrom]; a real controller always reports its state.)
            return Facts(
                prepare = Step.UNKNOWN,
                apply = Step.UNKNOWN,
                boundToSelection = true,
                linkStale = linkStale,
                currentAttempt = attempt,
            )
        }
        if (state !in knownStates) {
            return Facts(
                prepare = Step.UNKNOWN,
                apply = Step.UNKNOWN,
                boundToSelection = true,
                linkStale = linkStale,
                currentAttempt = attempt,
            )
        }
        if (input.needsRevalidation) {
            return Facts(
                prepare = Step.REVALIDATE,
                apply = Step.REVALIDATE,
                boundToSelection = true,
                linkStale = linkStale,
                currentAttempt = attempt,
            )
        }
        // Current attempt only: everything at/after the last PREPARING,
        // plus the current state (normally its last transition already).
        val lastPreparing = input.transitionNames.indexOfLast { it == PREPARING }
        val attemptTransitions = if (lastPreparing < 0) {
            input.transitionNames
        } else {
            input.transitionNames.drop(lastPreparing)
        }
        val evidence = attemptTransitions + listOfNotNull(state)
        val prepare = when {
            // Survives a same-attempt REJECTED by construction: the
            // rejection step below never clears this.
            PREPARED in evidence -> Step.CONFIRMED
            state == PREPARING -> Step.PENDING
            // A progressed (or refused) context without PREPARED proof is
            // unknown — never "not requested", which this instance cannot
            // prove. Only IDLE falls through below.
            state in progressedStates -> Step.UNKNOWN
            else -> Step.NOT_REQUESTED
        }
        val receiptMatches = input.lastApplicationSha != null &&
            input.lastApplicationSha == selected &&
            input.applicationId != null
        // Order matters: only the latest apply round's proof counts, so a
        // new APPLYING after an APPLIED cannot inherit the earlier receipt
        // and a current pending/refusal outranks it.
        val lastApplied = evidence.indexOfLast { it == APPLIED }
        val lastApplying = evidence.indexOfLast { it == APPLYING }
        val freshProof = receiptMatches && lastApplied >= 0 &&
            (lastApplying < 0 || lastApplied > lastApplying)
        val apply = when {
            // Durable proof wins: a same-attempt APPLIED postdating the
            // latest APPLYING, with a matching stored receipt, is not
            // erased by a later REJECTED that had no new APPLYING behind
            // it (no in-flight apply to refuse).
            freshProof -> Step.CONFIRMED
            state == REJECTED -> Step.REJECTED
            state == APPLYING -> Step.PENDING
            // STATUS_UNKNOWN reconciles a possibly lost reply: it asserts
            // nothing outstanding by itself (a confirmed proof above is
            // retained; anything else is unknown, never pending).
            state == STATUS_UNKNOWN -> Step.UNKNOWN
            // Claims APPLIED but the receipt is missing, names another
            // bundle, or belongs to a superseded round: contradictory —
            // unknown, never confirmed, never ready.
            APPLIED in evidence -> Step.UNKNOWN
            // A running/stopped report alone is not an apply confirmation.
            state == SESSION_PENDING || state == RUNNING || state == STOPPED -> Step.UNKNOWN
            else -> Step.NOT_REQUESTED
        }
        return Facts(
            prepare = prepare,
            apply = apply,
            boundToSelection = true,
            linkStale = linkStale,
            currentAttempt = attempt,
        )
    }
}
