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

        when (
            SyncWorkerDecision.preCheck(
                coreConfigured, flightExists, artifacts.size,
                artifactsAllSynced, pendingAnnotations,
            )
        ) {
            SyncWorkerDecision.Pre.NOTHING_TO_DO -> return Result.success()
            SyncWorkerDecision.Pre.ALREADY_SYNCED ->
                return Result.success(workDataOf(KEY_STATE to "ALREADY_SYNCED"))
            SyncWorkerDecision.Pre.PROCEED -> Unit
        }

        val coordinator = session.syncCoordinator() ?: return Result.success()
        val spec = try {
            session.uploadSpec(flightId)
        } catch (exc: Exception) {
            // Not representable (e.g. missing record) — do not loop.
            return Result.success(workDataOf(KEY_STATE to "SKIPPED"))
        }
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
        const val UNIQUE_PREFIX = "ksi-upload-"
    }
}

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
     * must still reach the server (user review 2026-10-11).
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
            hasCompleteUnsynced || hasPendingOpinion
        } ?: return null
        val flightId = pending.get("flight_id").asText()
        enqueue(context, flightId)
        return flightId
    }
}
