package ksi.safetyedge.rc

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * A8 background sync: upload a finished flight when the network is
 * available. WorkManager survives process death (the resumed run is
 * idempotent by [ksi.rc.EvidenceUploader] construction), the CONNECTED
 * constraint covers "internet available", and [ksi.rc.SyncCoordinator]
 * additionally verifies server reachability before attempting uploads.
 * COMMITTED-before-SYNCED is unchanged.
 *
 * Connectivity trigger: there is no separate NetworkCallback — the
 * CONNECTED constraint plus the enqueue points (Core config save,
 * process start, manual upload) ARE the reconnect hook. Each run calls
 * [RcSession.syncPendingSitesNow] before building the flight spec
 * (site-first ordering: no UUID-keyed server rows, no post-alias 409).
 *
 * All branching lives in [SyncWorkerDecision] (host-tested); this class
 * only reads store facts and maps decisions to WorkManager results.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val app = applicationContext as? RcApp ?: return Result.success()
        val session = app.session
        val flightId = inputData.getString(KEY_FLIGHT_ID) ?: return Result.success()

        val coreConfigured = session.coreConfigured()
        val flightExists = coreConfigured && session.store.flight(flightId) != null
        val artifacts = if (flightExists) {
            session.store.artifactsFor(flightId)
        } else {
            emptyList()
        }
        val artifactsAllSynced = artifacts.isNotEmpty() && artifacts.all {
            session.store.isArtifactSynced(it.get("artifact_id").asText())
        }
        val pendingAnnotations = if (flightExists) {
            session.store.pendingAnnotations()
                .count { it.get("flight_id")?.asText() == flightId }
        } else {
            0
        }
        // Structured-route receipts (Core POST /operator-responses): a late
        // field response after artifact COMMITTED keeps this run alive —
        // the artifact-only short-circuit below must not strand it.
        val pendingResponses = if (flightExists) {
            session.pendingResponseCount(flightId)
        } else {
            0
        }

        when (
            SyncWorkerDecision.preCheck(
                coreConfigured, flightExists, artifacts.size,
                artifactsAllSynced, pendingAnnotations, pendingResponses,
            )
        ) {
            SyncWorkerDecision.Pre.NOTHING_TO_DO -> return Result.success()
            SyncWorkerDecision.Pre.ALREADY_SYNCED ->
                return Result.success(workDataOf(KEY_STATE to "ALREADY_SYNCED"))
            SyncWorkerDecision.Pre.PROCEED -> Unit
        }

        val coordinator = session.syncCoordinator() ?: return Result.success()
        // Site-first ordering: a reconnect reaching the worker resolves
        // pending site drafts BEFORE any flight upload builds its spec —
        // otherwise a flight recorded under a client UUID registers a
        // server row that the later alias change turns into a 409 on
        // every re-register. Fail-fast report, never an invented upload;
        // a durable-write failure is logged and the run continues — the
        // per-flight gate below still refuses unresolved pending sites,
        // so unrelated flights are not blocked by a poisoned site journal.
        try {
            session.syncPendingSitesNow()
        } catch (exc: Exception) {
            Log.w("SyncWorker", "pending site sync deferred: ${exc.message}")
        }
        // The site-first wait is a DISTINCT success state, never the
        // SKIPPED bucket: a missing record is settled (do not loop), but
        // an unresolved site must requeue — [SyncScheduler.enqueuePending]
        // picks wait-state flights back up on the next trigger, after the
        // site resolves. The spec throw stays as the backstop for callers
        // that bypass this gate.
        session.uploadGateReason(flightId)?.let { reason ->
            Log.i(
                "SyncWorker",
                "flight=${flightId.take(8)} site sync wait ($reason)",
            )
            return Result.success(
                workDataOf(KEY_STATE to preUploadGateState(reason)),
            )
        }
        val spec = try {
            session.uploadSpec(flightId)
        } catch (exc: Exception) {
            // Not representable (e.g. missing record): the gate above
            // already diverted the site-first wait, so this is settled —
            // do not loop.
            return Result.success(workDataOf(KEY_STATE to "SKIPPED"))
        }
        // Best-effort close-out repair before uploading: an already-ended
        // flight whose close-out artifact was never materialized (older
        // records, late boots) gets it now when a verified boot resolves.
        // Never blocks the upload and never claims the close-out.
        session.repairCloseoutArtifact(flightId)
        val outcome = coordinator.syncOnce(spec)
        Log.i(
            "SyncWorker",
            "flight=${flightId.take(8)} attempt=$runAttemptCount sync -> " +
                "${outcome.state}" +
                (outcome.partialCause?.let { " ($it)" } ?: "") +
                (outcome.detail?.let { " [$it]" } ?: ""),
        )
        return when (
            SyncWorkerDecision.postDecision(
                outcome.state, runAttemptCount, outcome.partialCause,
            )
        ) {
            SyncWorkerDecision.Post.DONE ->
                Result.success(workDataOf(KEY_STATE to outcome.state))
            SyncWorkerDecision.Post.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_FLIGHT_ID = "flight_id"
        const val KEY_STATE = "state"

        /**
         * Distinct worker-visible state for the site-first wait: an
         * upload refused on an unresolved pending site succeeds as WAIT,
         * never as SKIPPED (SKIPPED settles missing records). A WAIT run
         * is requeueable — [SyncScheduler.enqueuePending] picks
         * wait-state flights back up — while SKIPPED never requeues.
         */
        const val WAIT_STATE = "SITE_SYNC_WAIT"
        const val UNIQUE_PREFIX = "ksi-upload-"
    }
}

/**
 * Pre-upload gate outcome for a [RcSession.uploadGateReason]: a non-null
 * reason (unresolved pending site) maps to the distinct WAIT state; null
 * proceeds to the spec. Pure so the wait ≠ skipped contract is
 * host-testable where the WorkManager runtime cannot run.
 */
fun preUploadGateState(gateReason: String?): String? =
    gateReason?.let { SyncWorker.WAIT_STATE }

/**
 * Host-testable requeue predicate: the shared evidence/annotations/
 * responses rule OR the site-first wait. A finished flight gated on an
 * unresolved pending site requeues on the next trigger so it retries
 * AFTER the site resolves — it must never silently settle because its
 * artifacts happen to be synced.
 */
fun shouldRequeueFlight(
    hasCompleteUnsynced: Boolean,
    hasPendingOpinion: Boolean,
    hasPendingResponses: Boolean,
    siteSyncWait: Boolean,
): Boolean = SyncWorkerDecision.shouldEnqueue(
    hasCompleteUnsynced, hasPendingOpinion, hasPendingResponses,
) || siteSyncWait

/** Enqueue helper (unique per flight; KEEP so a scheduled retry survives). */
object SyncScheduler {
    fun enqueue(context: Context, flightId: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_FLIGHT_ID to flightId))
            .addTag("ksi-upload")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.UNIQUE_PREFIX + flightId, ExistingWorkPolicy.KEEP, request,
        )
    }

    /**
     * Recovery trigger (user review): once the missing LOCAL bytes of a
     * finished flight are complete again (e.g. the Edge stream finished
     * after the flight record ended, or the app restarted), the flight is
     * re-queued automatically. Flights that can never heal (server-side
     * missing) are NOT re-queued — they stay visible for a human.
     *
     * Also re-queues a flight with PENDING OPERATOR ANNOTATIONS even when
     * all artifacts are synced: opinions added after the evidence sync
     * must still reach the server (user review 2026-10-11). And a flight
     * with LINKED STRUCTURED RESPONSES still without a durable receipt:
     * late field responses survive artifact COMMITTED and must not be
     * stranded by the artifact-only check.
     */
    fun enqueuePending(context: Context, session: RcSession): String? {
        val pending = session.store.flights().lastOrNull { record ->
            if (record.get("state")?.asText() != "ENDED") return@lastOrNull false
            val flightId = record.get("flight_id").asText()
            val hasCompleteUnsynced = session.store.artifactsFor(flightId).any {
                val artifactId = it.get("artifact_id").asText()
                session.store.isArtifactComplete(artifactId) &&
                    !session.store.isArtifactSynced(artifactId)
            }
            val hasPendingOpinion = session.store.pendingAnnotations()
                .any { it.get("flight_id")?.asText() == flightId }
            val hasPendingResponses = session.pendingResponseCount(flightId) > 0
            // Site-first wait requeues too: a finished flight gated on an
            // unresolved pending site retries AFTER the site resolves, even
            // when its artifacts are already synced (the evidence-only
            // check would otherwise strand it forever).
            val siteSyncWait = session.uploadGateReason(flightId) != null
            shouldRequeueFlight(
                hasCompleteUnsynced, hasPendingOpinion, hasPendingResponses, siteSyncWait,
            )
        } ?: return null
        val flightId = pending.get("flight_id").asText()
        enqueue(context, flightId)
        return flightId
    }
}
