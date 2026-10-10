package ksi.rc

/**
 * A8 sync coordinator: one honest, re-runnable sync attempt for a flight.
 *
 * - reachability is checked first ([CoreApi.health]): an offline field
 *   day must FAIL FAST as UNREACHABLE rather than burning through upload
 *   attempts (the caller/WorkManager decides when to retry);
 * - the actual upload is the existing [EvidenceUploader] — COMMITTED gate,
 *   RESUMING resume and receipts all unchanged;
 * - `syncOnce` is idempotent: calling it again after any outcome resumes
 *   from the durable state (a retry is always safe).
 */
data class SyncOutcome(
    /** COMPLETE | PARTIAL | NO_DATA | UNREACHABLE | FAILED */
    val state: String,
    val report: FlightUploadReport?,
    val detail: String? = null,
    /** PARTIAL refinement for retry policy (null otherwise). */
    val partialCause: String? = null,
) {
    companion object {
        const val COMPLETE = "COMPLETE"
        const val PARTIAL = "PARTIAL"
        const val NO_DATA = "NO_DATA"
        const val UNREACHABLE = "UNREACHABLE"
        const val FAILED = "FAILED"

        /** Local bytes are incomplete: they may still arrive (link/Edge),
         *  so a bounded retry is meaningful. */
        const val RECOVERABLE_PENDING = "RECOVERABLE_PENDING"

        /** Everything local was pushed but the server still reports it
         *  missing: pushing again will not fix it — surface for a human. */
        const val SERVER_MISSING = "SERVER_MISSING"

        const val UNKNOWN_CAUSE = "UNKNOWN"
    }
}

/**
 * PARTIAL classification (user review 2026-10-10): "not enough evidence on
 * the server" can mean (a) local bytes are still incomplete — recoverable
 * while data may arrive, or (b) complete local data was pushed but the
 * server rejects/keeps missing it — retrying cannot heal that. The caller
 * decides policy from this cause, not from the bare state string.
 */
fun classifyPartial(report: FlightUploadReport): String? {
    if (report.uploadState != SyncOutcome.PARTIAL) return null
    val incompleteLocal = report.boots.flatMap { it.incompleteLocal }
    val serverMissing = report.boots.flatMap { it.missingArtifacts }
    return when {
        incompleteLocal.isNotEmpty() -> SyncOutcome.RECOVERABLE_PENDING
        serverMissing.isNotEmpty() -> SyncOutcome.SERVER_MISSING
        else -> SyncOutcome.UNKNOWN_CAUSE
    }
}

class SyncCoordinator(
    private val core: CoreApi,
    private val uploader: EvidenceUploader,
) {
    fun syncOnce(spec: FlightUploadSpec): SyncOutcome {
        if (!core.health()) {
            return SyncOutcome(
                SyncOutcome.UNREACHABLE, null,
                "core unavailable (healthz failed) — no upload attempted",
            )
        }
        return try {
            val report = uploader.uploadFlight(spec)
            SyncOutcome(report.uploadState, report, partialCause = classifyPartial(report))
        } catch (exc: Exception) {
            SyncOutcome(SyncOutcome.FAILED, null, "upload failed: ${exc.message}")
        }
    }
}
