package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test
import ksi.rc.SyncOutcome

/**
 * Host-runnable evidence for the WorkManager decision tree (the WorkManager
 * runtime itself cannot execute on this aarch64 build host — see
 * docs/verification/evidence/2026-10-10-wm/). Every branch [SyncWorker]
 * takes is covered here, including the late-annotation regression.
 */
class SyncWorkerDecisionTest {
    @Test
    fun pre_check_covers_all_branches() {
        // No Core / unknown flight: succeed without retrying.
        assertEquals(
            SyncWorkerDecision.Pre.NOTHING_TO_DO,
            SyncWorkerDecision.preCheck(
                coreConfigured = false, flightExists = false,
                artifactCount = 0, artifactsAllSynced = false,
                pendingAnnotations = 0,
            ),
        )
        assertEquals(
            SyncWorkerDecision.Pre.NOTHING_TO_DO,
            SyncWorkerDecision.preCheck(
                coreConfigured = true, flightExists = false,
                artifactCount = 0, artifactsAllSynced = false,
                pendingAnnotations = 0,
            ),
        )
        // Fully delivered: short-circuit.
        assertEquals(
            SyncWorkerDecision.Pre.ALREADY_SYNCED,
            SyncWorkerDecision.preCheck(
                coreConfigured = true, flightExists = true,
                artifactCount = 3, artifactsAllSynced = true,
                pendingAnnotations = 0,
            ),
        )
        // LATE OPERATOR OPINION (2026-10-11 regression): evidence fully
        // synced but the opinion is still pending -> must PROCEED.
        assertEquals(
            SyncWorkerDecision.Pre.PROCEED,
            SyncWorkerDecision.preCheck(
                coreConfigured = true, flightExists = true,
                artifactCount = 3, artifactsAllSynced = true,
                pendingAnnotations = 1,
            ),
        )
        // Unsynced artifacts -> proceed.
        assertEquals(
            SyncWorkerDecision.Pre.PROCEED,
            SyncWorkerDecision.preCheck(
                coreConfigured = true, flightExists = true,
                artifactCount = 2, artifactsAllSynced = false,
                pendingAnnotations = 0,
            ),
        )
        // Artifact-less flights are never short-circuited here.
        assertEquals(
            SyncWorkerDecision.Pre.PROCEED,
            SyncWorkerDecision.preCheck(
                coreConfigured = true, flightExists = true,
                artifactCount = 0, artifactsAllSynced = false,
                pendingAnnotations = 0,
            ),
        )
    }

    @Test
    fun post_decision_maps_outcomes_to_workmanager_results() {
        assertEquals(
            SyncWorkerDecision.Post.DONE,
            SyncWorkerDecision.postDecision(SyncOutcome.COMPLETE, attempt = 0, partialCause = null),
        )
        assertEquals(
            SyncWorkerDecision.Post.DONE,
            SyncWorkerDecision.postDecision(SyncOutcome.PARTIAL, attempt = 0, partialCause = SyncOutcome.SERVER_MISSING),
        )
        assertEquals(
            SyncWorkerDecision.Post.RETRY,
            SyncWorkerDecision.postDecision(SyncOutcome.PARTIAL, attempt = 1, partialCause = SyncOutcome.RECOVERABLE_PENDING),
        )
        assertEquals(
            SyncWorkerDecision.Post.DONE,
            SyncWorkerDecision.postDecision(SyncOutcome.PARTIAL, attempt = SyncPolicy.MAX_PARTIAL_RETRIES, partialCause = SyncOutcome.RECOVERABLE_PENDING),
        )
        assertEquals(
            SyncWorkerDecision.Post.RETRY,
            SyncWorkerDecision.postDecision(SyncOutcome.UNREACHABLE, attempt = 0, partialCause = null),
        )
        assertEquals(
            SyncWorkerDecision.Post.RETRY,
            SyncWorkerDecision.postDecision(SyncOutcome.FAILED, attempt = 0, partialCause = null),
        )
    }
}
