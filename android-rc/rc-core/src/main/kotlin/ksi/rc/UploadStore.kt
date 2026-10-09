package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Upload-side view of the RC store (A3-b). Adds, on top of [RcStore] and
 * [EvidenceStore]:
 *
 * - boot-scoped reads the manifest/session builders need;
 * - durable upload receipts so a restarted RC process does not re-upload
 *   what the server already durably accepted (and never marks anything
 *   SYNCED without a server COMMITTED confirmation — the uploader enforces
 *   that; these methods only record the outcome).
 */
interface UploadStore : RcStore, EvidenceStore {
    fun observationsFor(flightId: String, bootId: String): List<ObjectNode>
    fun eventsFor(flightId: String, bootId: String): List<ObjectNode>
    fun artifactsFor(flightId: String, bootId: String): List<ObjectNode>

    fun markApplicationUploaded(applicationId: String)
    fun isApplicationUploaded(applicationId: String): Boolean

    /** Record a successful batch POST (keyed by kind + client batch id). */
    fun recordBatchReceipt(kind: String, clientBatchId: String, serverBatchId: String)
    fun batchReceipt(kind: String, clientBatchId: String): String?

    /** Server-confirmed durable storage (COMMITTED ingest only). */
    fun markArtifactSynced(artifactId: String)
    fun isArtifactSynced(artifactId: String): Boolean
}

/**
 * Flight-level inputs the uploader cannot derive from the RC store: the
 * mission context comes from the applied bundle (the app supplies it). The
 * uploader derives everything else (times, sessions, manifests, batches)
 * from local evidence.
 */
data class FlightUploadSpec(
    val flightId: String,
    val siteId: String,
    val rcDeviceId: String,
    val missionId: String,
    val missionRevisionId: String,
    /** Mission content digest (`mission_snapshot_hash` of events). */
    val missionSha256: String,
    /** Digest of the applied bundle (MissionController.bundleSha). */
    val bundleSha256: String,
)
