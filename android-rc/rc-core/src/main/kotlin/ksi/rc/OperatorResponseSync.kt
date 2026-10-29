package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** One structured operator-response upload attempt for a flight. */
data class OperatorResponseUploadReport(
    /** Responses the server accepted in THIS run. */
    val posted: Int = 0,
    /** Responses the server already held (idempotent replays). */
    val duplicates: Int = 0,
    /** True when a durable receipt already covered the batch (no POST). */
    val skipped: Boolean = false,
    /** Content-derived batch id attempted (null when nothing to send). */
    val clientBatchId: String? = null,
    val serverBatchId: String? = null,
    /** Linked responses still without a durable receipt after this run. */
    val pendingAfter: Int = 0,
    /** Per-failure diagnostics; every entry stays pending and retryable. */
    val failures: List<String> = emptyList(),
)

/**
 * Structured operator-response route (Core `POST /operator-responses` /
 * `GET .../operator-responses`, `operator-response.v1` — no new schema).
 *
 * - payloads come ONLY from the persisted [OperatorActionLog] plus the
 *   caller's actual flight id ([OperatorResponsePayload]); linkage is the
 *   stored session id matched against this flight's boots — a response for
 *   another flight is never stamped with this one, never invented.
 * - one content-derived batch per flight state: a lost reply retries the
 *   SAME `client_batch_id` (server replays idempotently); a new late
 *   response forms a NEW batch even after the artifacts committed.
 * - the durable receipt ([UploadStore.recordResponseBatchReceipt], exact
 *   batch hash + accepted/duplicate ids) is persisted BEFORE the upload
 *   counts as complete; a lost completeIngest-style reply is recovered by
 *   re-requesting the same batch.
 * - strict acceptance: a reply counts ONLY with `durable == true`, a
 *   non-empty `server_batch_id`, and accepted+duplicate id sets covering
 *   EXACTLY the sent ids (no foreign, omitted, or doubly-listed ids). A
 *   200 with `durable:false` (or any malformed coverage) marks nothing,
 *   writes no receipt, and leaves everything pending with a stable retry
 *   batch. A stored receipt is likewise trusted only when its
 *   `content_hash` — and its recorded id coverage — matches the batch.
 * - proofless offline records stay LOCAL_ONLY/REQUESTED (or UNKNOWN when a
 *   stored claim lacks its proof) even when the Core acknowledges durable
 *   preservation: a storage ACK is NOT evidence the field team
 *   received/completed anything and is not a request being delivered.
 * - failures (404 older server, invalid payloads, network) never mark
 *   anything complete and never delete the local log: honest, retryable
 *   diagnostics per the existing finite retry policy. The FLIGHT_LOG
 *   artifact path is untouched and independent.
 */
class OperatorResponseSync(
    private val core: CoreApi,
    private val store: UploadStore,
    private val log: OperatorActionLog,
) {
    companion object {
        const val KIND = "operator-responses"
    }

    /** Persisted actions verifiably linked to this flight, in log order. */
    fun pendingForFlight(flightId: String): List<OperatorAction> {
        val sessions = store.bootsFor(flightId)
            .map { OperatorActionArtifacts.sessionIdFor(flightId, it) }
            .toSet()
        if (sessions.isEmpty()) return emptyList()
        val covered = coveredResponseIds(flightId)
        return log.readAll().filter { it.sessionId in sessions && it.responseId !in covered }
    }

    fun uploadForFlight(flightId: String): OperatorResponseUploadReport {
        val pending = pendingForFlight(flightId)
        if (pending.isEmpty()) {
            // Empty action log (or nothing linked to this flight): never
            // POST an empty batch, never block the flight.
            return OperatorResponseUploadReport(skipped = true)
        }
        val items = Mop.json.createArrayNode()
        val failures = mutableListOf<String>()
        for (action in pending) {
            try {
                items.add(OperatorResponsePayload.fromAction(action, flightId))
            } catch (exc: Exception) {
                failures.add(
                    "response ${action.responseId} not serializable, left pending: ${exc.message}",
                )
            }
        }
        if (items.size() == 0) {
            return OperatorResponseUploadReport(
                pendingAfter = pending.size, failures = failures,
            )
        }
        val contentHash = CanonicalJson.digestSha256(items)
        val clientBatchId = Mop.newMsgId(KIND, flightId, contentHash)
        val sentIds = items.map { it.get("response_id").asText() }
        val existing = store.responseBatchReceipt(KIND, clientBatchId)
        if (existing != null) {
            // A durable receipt already covers this exact content: same
            // batch, no POST (lost-reply recovery without duplication).
            // The receipt itself is verified, not trusted by existence:
            // its content hash must match this batch, and its recorded id
            // coverage must match the sent ids.
            val receiptFailure = checkStoredReceipt(existing, contentHash, sentIds, clientBatchId)
            if (receiptFailure != null) {
                return OperatorResponseUploadReport(
                    clientBatchId = clientBatchId,
                    pendingAfter = pending.size,
                    failures = failures + receiptFailure,
                )
            }
            return OperatorResponseUploadReport(
                duplicates = items.size(),
                skipped = true,
                clientBatchId = clientBatchId,
                serverBatchId = existing.get("server_batch_id").asText(),
                pendingAfter = pendingForFlight(flightId).size,
                failures = failures,
            )
        }
        val response = try {
            core.postOperatorResponses(clientBatchId, items)
        } catch (exc: CoreApiError) {
            return OperatorResponseUploadReport(
                clientBatchId = clientBatchId,
                pendingAfter = pending.size,
                failures = failures + responseFailure(exc),
            )
        } catch (exc: Exception) {
            return OperatorResponseUploadReport(
                clientBatchId = clientBatchId,
                pendingAfter = pending.size,
                failures = failures + "response batch $clientBatchId failed: ${exc.message}",
            )
        }
        val replyFailure = checkServerReply(response, sentIds, clientBatchId)
        if (replyFailure != null) {
            return OperatorResponseUploadReport(
                clientBatchId = clientBatchId,
                pendingAfter = pending.size,
                failures = failures + replyFailure,
            )
        }
        val serverBatchId = response.get("server_batch_id").asText()
        val acceptedIds = response.get("accepted_ids").map { it.asText() }
        val duplicateIds = response.get("duplicate_ids").map { it.asText() }
        val receipt = Mop.json.createObjectNode()
        receipt.put("schema_version", 1)
        receipt.put("kind", KIND)
        receipt.put("flight_id", flightId)
        receipt.put("client_batch_id", clientBatchId)
        receipt.put("content_hash", contentHash)
        receipt.put("server_batch_id", serverBatchId)
        val acceptedArray = receipt.putArray("accepted_ids")
        acceptedIds.forEach { acceptedArray.add(it) }
        val duplicateArray = receipt.putArray("duplicate_ids")
        duplicateIds.forEach { duplicateArray.add(it) }
        try {
            store.recordResponseBatchReceipt(receipt)
        } catch (exc: Exception) {
            return OperatorResponseUploadReport(
                clientBatchId = clientBatchId,
                pendingAfter = pending.size,
                failures = failures + "response receipt not durable: ${exc.message}",
            )
        }
        return OperatorResponseUploadReport(
            posted = acceptedIds.size,
            duplicates = duplicateIds.size,
            clientBatchId = clientBatchId,
            serverBatchId = serverBatchId,
            pendingAfter = pendingForFlight(flightId).size,
            failures = failures,
        )
    }

    /** Response ids this flight's durable receipts already cover. */
    private fun coveredResponseIds(flightId: String): Set<String> {
        val covered = mutableSetOf<String>()
        for (receipt in store.responseBatchReceipts(KIND, flightId)) {
            receipt.get("accepted_ids")?.forEach {
                if (it.isTextual) covered.add(it.asText())
            }
            receipt.get("duplicate_ids")?.forEach {
                if (it.isTextual) covered.add(it.asText())
            }
        }
        return covered
    }

    /**
     * Strict server-reply gate: a 200 counts ONLY with `durable == true`,
     * a non-empty `server_batch_id`, and accepted+duplicate id sets
     * covering EXACTLY the sent ids. Returns the failure diagnostic, or
     * null when the reply proves durable acceptance. A bad reply leaves
     * everything pending with the same stable batch id — never a receipt,
     * never a mark.
     */
    private fun checkServerReply(
        response: ObjectNode,
        sentIds: List<String>,
        clientBatchId: String,
    ): String? {
        val durable = response.get("durable")
        if (durable == null || !durable.isBoolean || !durable.asBoolean()) {
            return "response batch $clientBatchId not durable (durable != true); left pending"
        }
        if (response.get("server_batch_id")?.takeIf { it.isTextual }?.asText().isNullOrEmpty()) {
            return "response batch $clientBatchId missing server_batch_id; left pending"
        }
        return checkIdCoverage(
            response.get("accepted_ids"), response.get("duplicate_ids"),
            sentIds, "response batch $clientBatchId",
        )
    }

    /**
     * Stored-receipt gate: the receipt is verified, not trusted by
     * existence. Its `content_hash` must equal this batch's hash and its
     * recorded id coverage must equal the sent ids; otherwise the receipt
     * is corrupt and the batch stays pending (no repost against an
     * inconsistent local state — surfaced for a human instead).
     */
    private fun checkStoredReceipt(
        receipt: ObjectNode,
        contentHash: String,
        sentIds: List<String>,
        clientBatchId: String,
    ): String? {
        if (receipt.get("content_hash")?.takeIf { it.isTextual }?.asText() != contentHash) {
            return "durable receipt content_hash mismatch for batch $clientBatchId; " +
                "left pending, no repost"
        }
        if (receipt.get("server_batch_id")?.takeIf { it.isTextual }?.asText().isNullOrEmpty()) {
            return "durable receipt for batch $clientBatchId missing server_batch_id; " +
                "left pending, no repost"
        }
        val coverage = checkIdCoverage(
            receipt.get("accepted_ids"), receipt.get("duplicate_ids"),
            sentIds, "durable receipt for batch $clientBatchId",
        ) ?: return null
        return "$coverage; left pending, no repost"
    }

    /**
     * Exact id coverage: both lists must be string arrays whose union is
     * exactly [expectedIds] — no foreign ids, no omissions, and no id
     * listed as both accepted and duplicate. Returns the failure
     * diagnostic, or null on exact coverage.
     */
    private fun checkIdCoverage(
        accepted: JsonNode?,
        duplicates: JsonNode?,
        expectedIds: List<String>,
        context: String,
    ): String? {
        if (accepted == null || !accepted.isArray || duplicates == null || !duplicates.isArray ||
            accepted.any { !it.isTextual } || duplicates.any { !it.isTextual }
        ) {
            return "$context has malformed accepted/duplicate ids; left pending"
        }
        val acceptedIds = accepted.map { it.asText() }
        val duplicateIds = duplicates.map { it.asText() }
        val expected = expectedIds.toSet()
        val overlap = acceptedIds.toSet() intersect duplicateIds.toSet()
        if (overlap.isNotEmpty()) {
            return "$context lists ids as both accepted and duplicate ${overlap.sorted()}; left pending"
        }
        val foreign = (acceptedIds + duplicateIds).filter { it !in expected }.toSet()
        if (foreign.isNotEmpty()) {
            return "$context covers foreign ids ${foreign.sorted()}; left pending"
        }
        val missing = expected - (acceptedIds + duplicateIds).toSet()
        if (missing.isNotEmpty()) {
            return "$context omits sent ids ${missing.sorted()}; left pending"
        }
        return null
    }

    private fun responseFailure(exc: CoreApiError): String {
        val hint = if (exc.status == 404) {
            " route not found on Core (older server?): responses stay pending, retryable"
        } else {
            ""
        }
        return "response batch failed: HTTP ${exc.status}$hint"
    }
}
