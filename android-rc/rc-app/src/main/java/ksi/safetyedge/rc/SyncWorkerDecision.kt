package ksi.safetyedge.rc

/**
 * Pure decision tree for [SyncWorker] — everything between "work started"
 * and "Result.x", extracted so it is host-testable even where the
 * WorkManager runtime itself cannot run on this build host (Robolectric
 * has no Linux/aarch64 native runtime; the qemu x86_64 container path
 * crashes the JVM — see docs/verification/evidence/2026-10-10-wm/).
 * [SyncWorker] is a thin adapter over these functions.
 */
object SyncWorkerDecision {
    enum class Pre {
        /** Run the sync attempt. */
        PROCEED,

        /** Nothing left to deliver: artifacts all synced AND no pending
         *  operator annotations (late opinions keep the sync alive). */
        ALREADY_SYNCED,

        /** No Core configured or unknown flight: succeed without retrying. */
        NOTHING_TO_DO,
    }

    enum class Post { DONE, RETRY }

    fun preCheck(
        coreConfigured: Boolean,
        flightExists: Boolean,
        artifactCount: Int,
        artifactsAllSynced: Boolean,
        pendingAnnotations: Int,
    ): Pre = when {
        !coreConfigured || !flightExists -> Pre.NOTHING_TO_DO
        SyncPolicy.shouldSkipSync(artifactCount, artifactsAllSynced, pendingAnnotations) ->
            Pre.ALREADY_SYNCED
        else -> Pre.PROCEED
    }

    fun postDecision(state: String, attempt: Int, partialCause: String?): Post =
        when (SyncPolicy.decisionFor(state, attempt, partialCause)) {
            SyncPolicy.Decision.DONE -> Post.DONE
            SyncPolicy.Decision.RETRY -> Post.RETRY
        }
}
