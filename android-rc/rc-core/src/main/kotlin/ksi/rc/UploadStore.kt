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

    // -- Commit receipts: COMMITTED is a locally stored fact -----------------
    // Contract §6: the RC shows upload completion ONLY after the Core's
    // commit receipt (durable_commit_id) is persisted locally. A lost
    // completeIngest response is recovered by re-requesting the same commit
    // (idempotent by manifest_sha256); the UI-facing completion flag flips
    // only when the receipt is in the store.

    /** Durable commit receipt for one manifest (idempotent by
     *  manifest_sha256; first write wins). */
    fun recordCommitReceipt(receipt: ObjectNode)

    /** Stored COMMITTED receipt for a manifest, or null. */
    fun commitReceipt(manifestSha256: String): ObjectNode?

    fun isBootCommitted(manifestSha256: String): Boolean =
        commitReceipt(manifestSha256) != null

    /**
     * Stored COMMITTED receipts of one flight (insertion order).
     *
     * WHY: "중앙에 보관됐는가"를 서버 추측으로 답하면 안 된다. 로컬에
     * **커밋 영수증(durable_commit_id)이 있는 것**만 사실이다. 이 조회는
     * 그 사실을 비행 단위로 묶어 보여주기 위한 읽기 전용 통로다.
     */
    fun commitReceiptsFor(flightId: String): List<ObjectNode>

    // -- A7: operator annotations (append-only local queue) --------------------
    // The RC never deletes or rewrites a detection/event: a "false positive
    // suspected" mark is a NEW operator-annotation record; undo is a
    // REVOKE_PREVIOUS record that supersedes it (server contract).

    /** Idempotent by annotation_id. */
    fun queueAnnotation(annotation: ObjectNode)

    /** Local annotations for an event, insertion order (oldest first). */
    fun annotationsFor(eventId: String): List<ObjectNode>

    // -- Operator annotation upload (server append, per id) --------------------

    /** Annotations not yet accepted by the server, insertion order. */
    fun pendingAnnotations(): List<ObjectNode>

    fun markAnnotationUploaded(annotationId: String)
    fun isAnnotationUploaded(annotationId: String): Boolean

    // -- Structured operator-response batches (queryable route) ----------------
    // Durable receipts for `POST /operator-responses` batches, keyed by
    // (kind, client_batch_id) like the evidence batches. The client batch
    // id is content-derived, so the receipt proves THIS exact content was
    // durably accepted (accepted + duplicate ids included) — a lost reply
    // retries the same batch, and new late responses form a new batch.

    /** Idempotent by (kind, client_batch_id); first write wins. */
    fun recordResponseBatchReceipt(receipt: ObjectNode)

    /** Stored receipt for one batch, or null when it never completed. */
    fun responseBatchReceipt(kind: String, clientBatchId: String): ObjectNode?

    /** Stored receipts for a flight (insertion order). */
    fun responseBatchReceipts(kind: String, flightId: String): List<ObjectNode>
}

/**
 * The full RC store surface: evidence/upload capabilities PLUS mission
 * cache and flight records. [FileRcStore] is the single implementation;
 * components that need both sides (e.g. the app's flight-history model)
 * depend on this instead of two parameters.
 */
interface RcFullStore : UploadStore, MissionStore

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
