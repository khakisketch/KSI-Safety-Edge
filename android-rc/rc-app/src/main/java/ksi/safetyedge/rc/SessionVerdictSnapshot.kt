package ksi.safetyedge.rc

import ksi.rc.ExecutionFacts
import ksi.rc.Mop
import ksi.rc.SessionFacts
import ksi.rc.StoreSessionFacts
import ksi.rc.toNode

/**
 * The ONE factual snapshot behind verdict display, verdict persistence and
 * the verdict-change check (contract §6).
 *
 * Pure Kotlin, no Android: host-tested. [build] mirrors
 * [ksi.rc.SessionVerdict.decide]'s fact assembly EXACTLY (raw
 * pass-through, store-resolved counts only — no `takeIf` null-coercion, no
 * flight-level fallbacks), so the snapshot the screen classifies and
 * compares is structurally identical to the snapshot the log persists.
 * That canonical equality is what stops the persist-compare loop: a repeat
 * render with unchanged facts decides nothing and fsyncs nothing.
 *
 * Honesty: `false` here is the controller/store's observed flag (no drop
 * seen, no epoch change seen, STOP unconfirmed), never a guessed
 * "unknown". Absent basis stays `null` and leans to UNKNOWN.
 */
object SessionVerdictSnapshot {

    /**
     * The exact facts [ksi.rc.SessionVerdict.decide] derives from the same
     * inputs: controller execution facts, store-resolved facts, the known
     * RC recorder failure (forces video evidence absent and the verdict
     * FAILED) and the Edge durable-evidence failure (forces FAILED — an
     * Edge journal failure, never merged into the RC camera input or any
     * field-safety claim). Unknown stays null, never guessed.
     */
    fun build(
        execution: ExecutionFacts?,
        stored: StoreSessionFacts?,
        localVideoFailure: Boolean? = null,
    ): SessionFacts =
        SessionFacts(
            configApplied = execution?.configApplied,
            runtimeConfirmed = execution?.runtimeConfirmed,
            frameProcessingObserved = execution?.frameProcessingObserved,
            observationCount = stored?.observationCount,
            videoEvidencePresent =
                if (localVideoFailure == true) false else stored?.videoEvidencePresent,
            declaredGaps = stored?.declaredGaps,
            linkInterrupted = execution?.linkInterrupted,
            runtimeEpochChanged = execution?.runtimeEpochChanged,
            stopConfirmed = execution?.stopConfirmed,
            recordingFailure = localVideoFailure,
            evidenceFailure = execution?.durableEvidenceFailure,
        )

    /** Canonical serialized form (deterministic field order via [toNode]). */
    fun factsJson(facts: SessionFacts): String =
        Mop.json.writeValueAsString(facts.toNode())

    /**
     * True when the snapshot must be (re-)persisted: no identical request
     * already in flight and the persisted record carries different facts.
     * Data-class equality on the request side plus the canonical JSON
     * against the stored record — a repeated render with unchanged facts
     * returns false and writes nothing.
     */
    fun shouldRequest(
        facts: SessionFacts,
        pending: SessionFacts?,
        persistedFactsJson: String?,
    ): Boolean {
        if (facts == pending) return false
        return persistedFactsJson != factsJson(facts)
    }
}
