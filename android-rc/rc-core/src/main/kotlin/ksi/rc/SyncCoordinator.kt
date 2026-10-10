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
) {
    companion object {
        const val COMPLETE = "COMPLETE"
        const val PARTIAL = "PARTIAL"
        const val NO_DATA = "NO_DATA"
        const val UNREACHABLE = "UNREACHABLE"
        const val FAILED = "FAILED"
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
            SyncOutcome(report.uploadState, report)
        } catch (exc: Exception) {
            SyncOutcome(SyncOutcome.FAILED, null, "upload failed: ${exc.message}")
        }
    }
}
