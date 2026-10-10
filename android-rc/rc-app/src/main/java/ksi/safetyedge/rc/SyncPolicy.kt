package ksi.safetyedge.rc

import ksi.rc.SyncOutcome

/**
 * A8 retry policy: what a sync outcome means for background work.
 *
 * DONE stops the auto-sync (the operator can always re-run manually):
 * - COMPLETE: everything the server required is committed;
 * - PARTIAL: the missing bytes will not arrive by retrying a finished
 *   flight (they may still be streaming on a live one — manual re-upload
 *   handles it);
 * - NO_DATA: nothing was ever received.
 *
 * RETRY lets WorkManager back off and try again later — the network or
 * Core may return, and uploads resume idempotently.
 */
object SyncPolicy {
    enum class Decision { DONE, RETRY }

    fun decisionFor(state: String): Decision = when (state) {
        SyncOutcome.COMPLETE,
        SyncOutcome.PARTIAL,
        SyncOutcome.NO_DATA,
        -> Decision.DONE

        else -> Decision.RETRY // UNREACHABLE, FAILED, unknown
    }
}
