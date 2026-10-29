package ksi.safetyedge.rc

import ksi.rc.SyncOutcome

/**
 * A8 retry policy (refined after the user review 2026-10-10): what a sync
 * outcome means for background work.
 *
 * DONE stops the auto-sync (the operator can always re-run manually, and
 * the flight keeps its incomplete marks visible):
 * - COMPLETE: everything the server required is committed;
 * - NO_DATA: nothing was ever received;
 * - PARTIAL / SERVER_MISSING: every local byte was pushed but the server
 *   still reports it missing — pushing again cannot heal that, so a human
 *   decides (the record stays visible as unsynced).
 *
 * RETRY lets WorkManager back off and try again:
 * - UNREACHABLE / FAILED: the network or Core may return; resume is
 *   idempotent by construction;
 * - PARTIAL / RECOVERABLE_PENDING: local bytes are incomplete and may
 *   still arrive (live link/Edge) — BOUNDED retries so a permanently lost
 *   file does not loop forever; after [MAX_PARTIAL_RETRIES] the flight
 *   stays visibly unsynced for manual handling.
 */
object SyncPolicy {
    enum class Decision { DONE, RETRY }

    const val MAX_PARTIAL_RETRIES = 5

    fun decisionFor(
        state: String,
        attempt: Int = 0,
        partialCause: String? = null,
    ): Decision = when (state) {
        SyncOutcome.COMPLETE, SyncOutcome.NO_DATA -> Decision.DONE

        SyncOutcome.PARTIAL -> when {
            partialCause == SyncOutcome.SERVER_MISSING -> Decision.DONE
            attempt < MAX_PARTIAL_RETRIES -> Decision.RETRY
            else -> Decision.DONE
        }

        else -> Decision.RETRY // UNREACHABLE, FAILED, unknown
    }

    /**
     * Skip a queued sync only when there is NOTHING left to deliver:
     * artifacts exist, all are synced, AND no pending operator annotations
     * (user review 2026-10-11 — a late "false positive" opinion must still
     * go out; artifact-only completeness is not evidence completeness) AND
     * no linked structured responses still without a durable receipt (a late
     * field response after artifact COMMITTED must still go out — upload
     * success must never read as field-request delivery).
     */
    fun shouldSkipSync(
        artifactCount: Int,
        artifactsAllSynced: Boolean,
        pendingAnnotations: Int,
        pendingResponses: Int = 0,
    ): Boolean = artifactCount > 0 && artifactsAllSynced &&
        pendingAnnotations == 0 && pendingResponses == 0
}
