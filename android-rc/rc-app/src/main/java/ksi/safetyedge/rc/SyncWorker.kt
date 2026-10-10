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
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val app = applicationContext as? RcApp ?: return Result.success()
        val session = app.session
        val flightId = inputData.getString(KEY_FLIGHT_ID) ?: return Result.success()
        if (!session.coreConfigured()) return Result.success()
        session.store.flight(flightId) ?: return Result.success()

        // Already fully synced: nothing to do (receipts are durable).
        val artifacts = session.store.artifactsFor(flightId)
        if (artifacts.isNotEmpty() && artifacts.all {
                session.store.isArtifactSynced(it.get("artifact_id").asText())
            }
        ) {
            return Result.success(workDataOf(KEY_STATE to "ALREADY_SYNCED"))
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
            SyncPolicy.decisionFor(
                outcome.state, runAttemptCount, outcome.partialCause,
            )
        ) {
            SyncPolicy.Decision.DONE ->
                Result.success(workDataOf(KEY_STATE to outcome.state))
            SyncPolicy.Decision.RETRY -> Result.retry()
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
     */
    fun enqueuePending(context: Context, session: RcSession): String? {
        val pending = session.store.flights().lastOrNull { record ->
            record.get("state")?.asText() == "ENDED" &&
                session.store.artifactsFor(record.get("flight_id").asText())
                    .any {
                        val artifactId = it.get("artifact_id").asText()
                        session.store.isArtifactComplete(artifactId) &&
                            !session.store.isArtifactSynced(artifactId)
                    }
        } ?: return null
        val flightId = pending.get("flight_id").asText()
        enqueue(context, flightId)
        return flightId
    }
}
