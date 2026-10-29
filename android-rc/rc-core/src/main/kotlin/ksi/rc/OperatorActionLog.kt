package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Append-only local persistence for operator responses (대응) to AI risk
 * candidates.
 *
 * House pattern (same spirit as [FileRcStore]: NDJSON append, flush+fsync,
 * idempotent by record id, corrupt-line tolerance, insertion ordering):
 * - [append] is idempotent by response id — a repeated write must not
 *   duplicate.
 * - [readAll] returns responses in append order.
 * - [summary] counts by action and by (effective) delivery state for the
 *   record screen. It carries no risk claims: counts never say a risk
 *   cleared, and delivery counts use [effectiveDelivery] so an unproven
 *   SENT/RECEIVED/COMPLETED claim is counted as UNKNOWN, never as sent.
 *
 * This log is intentionally self-contained (String action/delivery fields
 * mirroring rc-ports `ObservationResponse`, no cross-module dependency):
 * rc-core owns no new Gradle dependency for it. Delivery-state tracking of
 * assignments/receipts beyond local storage is explicitly deferred to a
 * future Core extension — see the upload-path note in the task report; this
 * class only stores what the operator did, offline.
 */
data class OperatorAction(
    val responseId: String,
    val candidateId: String,
    val siteId: String,
    val sessionId: String,
    /** One of CONFIRM, HOLD, ESCALATE_REQUEST (rc-ports Action names). */
    val action: String,
    /** Short optional operator note (<= [OperatorActionLog.MAX_NOTE_CHARS]). */
    val note: String?,
    /** Source observation/candidate id the response refers to. */
    val evidenceSourceId: String,
    /** RC receipt wall-clock of the source (ISO-8601 UTC). */
    val evidenceReceivedAtUtc: String,
    /** Policy id in effect (null only when truly unknown). */
    val policyId: String?,
    /** Policy version in effect (null only when truly unknown). */
    val policyVersion: String?,
    /** Operator action wall-clock (ISO-8601 UTC). */
    val actedAtUtc: String,
    /**
     * Stored transmission state: LOCAL_ONLY, REQUESTED, SENT_CONFIRMED,
     * RECEIVED_CONFIRMED, COMPLETED_CONFIRMED, or UNKNOWN. Only LOCAL_ONLY
     * and REQUESTED are producible offline; the *_CONFIRMED states require
     * the matching proof reference below.
     */
    val delivery: String,
    val transmissionProof: String? = null,
    val receiptProof: String? = null,
    val completionProof: String? = null,
)

/** Counts for the record screen: no risk claims, delivery is effective. */
data class OperatorActionSummary(
    val total: Int,
    val byAction: Map<String, Int>,
    val byDelivery: Map<String, Int>,
)

class OperatorActionLog(
    root: Path,
    /** Set ONLY by [unavailable]: zero-filesystem readonly mode. */
    private val unavailableReason: IOException? = null,
) {
    /** Test-only seam: fault injection without changing call shapes. */
    internal constructor(root: Path, io: StoreIo) : this(root) {
        this.io = io
    }

    private var io: StoreIo = RealStoreIo

    /** Set when a tail rollback itself cannot be made durable: every later
     *  append fails closed until [repairAfterFailure] runs. */
    @Volatile
    private var writePoison: IOException? = null

    private val file = File(root.toFile(), "operator_actions.ndjson")
    private val byId = linkedMapOf<String, ObjectNode>()

    /** Corrupt/partial lines skipped while loading (diagnostics). */
    var recoveredPartialWrites = 0
        private set

    init {
        if (unavailableReason == null) {
            root.toFile().mkdirs()
            // Repair a torn trailing fragment BEFORE loading: otherwise the next
            // append would concatenate a good record onto crash garbage and the
            // combined line would be lost on restart. Committed records are
            // never truncated.
            repairTrailingFragment()
            for (node in readNodes()) {
                val id = node.get("response_id")?.takeIf { it.isTextual }?.asText()
                if (id == null || !validNode(node)) {
                    recoveredPartialWrites += 1
                    continue
                }
                byId.putIfAbsent(id, node)
            }
        }
        // Unavailable: no mkdir, no repair, no read — byId stays empty and
        // every write throws the recorded reason below.
    }

    @Synchronized
    fun append(action: OperatorAction) {
        require(action.responseId.isNotBlank()) { "response id must not be blank" }
        require(action.candidateId.isNotBlank()) { "candidate id must not be blank" }
        require(action.siteId.isNotBlank()) { "site id must not be blank" }
        require(action.sessionId.isNotBlank()) { "session id must not be blank" }
        require(action.action in ACTIONS) { "unknown action ${action.action}" }
        require(action.delivery in DELIVERIES) { "unknown delivery ${action.delivery}" }
        require(action.evidenceSourceId.isNotBlank()) { "evidence source id must not be blank" }
        require(action.evidenceReceivedAtUtc.isNotBlank()) {
            "evidence received time must not be blank"
        }
        require(action.actedAtUtc.isNotBlank()) { "action time must not be blank" }
        if (action.note != null) {
            require(action.note.length <= MAX_NOTE_CHARS) {
                "operator note must be <= $MAX_NOTE_CHARS chars"
            }
        }
        if (byId.containsKey(action.responseId)) return
        val node = toNode(action)
        // Durable FIRST: publishing the in-memory entry before the bytes
        // are fsynced would let a retry take the idempotent early-return
        // and silently drop a record that never reached disk.
        durableAppend(Mop.json.writeValueAsString(node) + "\n")
        byId[action.responseId] = node
    }

    @Synchronized
    fun readAll(): List<OperatorAction> =
        byId.values.mapNotNull { fromNode(it) }

    /**
     * Counts by action and by EFFECTIVE delivery state (unproven
     * SENT/RECEIVED/COMPLETED counts as UNKNOWN). No risk claims: a CONFIRM
     * count never means the risk cleared.
     */
    @Synchronized
    fun summary(): OperatorActionSummary {
        val actions = readAll()
        val byAction = linkedMapOf<String, Int>()
        val byDelivery = linkedMapOf<String, Int>()
        for (action in actions) {
            byAction[action.action] = (byAction[action.action] ?: 0) + 1
            val effective = effectiveDelivery(action)
            byDelivery[effective] = (byDelivery[effective] ?: 0) + 1
        }
        return OperatorActionSummary(
            total = actions.size,
            byAction = byAction.toMap(),
            byDelivery = byDelivery.toMap(),
        )
    }

    /**
     * What may actually be claimed for [action], after proof checks.
     * Non-escalation actions never carry a transmission state (LOCAL_ONLY).
     * An escalation claim above REQUESTED without its proof degrades to
     * UNKNOWN. Mirrors rc-ports `ObservationResponse.effectiveDelivery`
     * without taking a cross-module dependency.
     */
    fun effectiveDelivery(action: OperatorAction): String =
        effectiveDeliveryOf(action)

    // -- serialization ---------------------------------------------------------

    /**
     * Canonical NDJSON line for [action]: byte-identical to the line [append]
     * stores. The upload path reuses this so the shipped bytes always match
     * the stored record (no re-serialization drift).
     */
    internal fun lineFor(action: OperatorAction): String =
        Mop.json.writeValueAsString(toNode(action))

    private fun toNode(action: OperatorAction): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", SCHEMA_VERSION)
        node.put("response_id", action.responseId)
        node.put("candidate_id", action.candidateId)
        node.put("site_id", action.siteId)
        node.put("session_id", action.sessionId)
        node.put("action", action.action)
        if (action.note == null) node.putNull("note") else node.put("note", action.note)
        node.put("evidence_source_id", action.evidenceSourceId)
        node.put("evidence_received_at_utc", action.evidenceReceivedAtUtc)
        if (action.policyId == null) node.putNull("policy_id") else node.put("policy_id", action.policyId)
        if (action.policyVersion == null) {
            node.putNull("policy_version")
        } else {
            node.put("policy_version", action.policyVersion)
        }
        node.put("acted_at_utc", action.actedAtUtc)
        node.put("delivery", action.delivery)
        if (action.transmissionProof == null) {
            node.putNull("transmission_proof")
        } else {
            node.put("transmission_proof", action.transmissionProof)
        }
        if (action.receiptProof == null) {
            node.putNull("receipt_proof")
        } else {
            node.put("receipt_proof", action.receiptProof)
        }
        if (action.completionProof == null) {
            node.putNull("completion_proof")
        } else {
            node.put("completion_proof", action.completionProof)
        }
        return node
    }

    private fun fromNode(node: ObjectNode): OperatorAction? {
        return try {
            val responseId = node.get("response_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val candidateId = node.get("candidate_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val siteId = node.get("site_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val sessionId = node.get("session_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val action = node.get("action")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val evidenceSourceId = node.get("evidence_source_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val evidenceReceivedAtUtc = node.get("evidence_received_at_utc")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val actedAtUtc = node.get("acted_at_utc")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val delivery = node.get("delivery")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            OperatorAction(
                responseId = responseId,
                candidateId = candidateId,
                siteId = siteId,
                sessionId = sessionId,
                action = action,
                note = node.get("note")?.takeIf { it.isTextual }?.asText(),
                evidenceSourceId = evidenceSourceId,
                evidenceReceivedAtUtc = evidenceReceivedAtUtc,
                policyId = node.get("policy_id")?.takeIf { it.isTextual }?.asText(),
                policyVersion = node.get("policy_version")
                    ?.takeIf { it.isTextual }?.asText(),
                actedAtUtc = actedAtUtc,
                delivery = delivery,
                transmissionProof = node.get("transmission_proof")
                    ?.takeIf { it.isTextual }?.asText(),
                receiptProof = node.get("receipt_proof")
                    ?.takeIf { it.isTextual }?.asText(),
                completionProof = node.get("completion_proof")
                    ?.takeIf { it.isTextual }?.asText(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Structural check applied at load: required ids, known enums. */
    private fun validNode(node: ObjectNode): Boolean {
        for (field in REQUIRED_TEXT) {
            if (node.get(field)?.takeIf { it.isTextual }?.asText().isNullOrEmpty()) {
                return false
            }
        }
        if (node.get("action")?.asText() !in ACTIONS) return false
        if (node.get("delivery")?.asText() !in DELIVERIES) return false
        return true
    }

    // -- durable NDJSON ----------------------------------------------------------

    private fun readNodes(): List<ObjectNode> {
        if (!file.exists()) return emptyList()
        val text = String(file.readBytes(), Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        val completed = if (text.endsWith("\n")) {
            text
        } else {
            // Trailing fragment without its newline is a partial write from
            // a crash: skip it (records are append-only, everything before
            // it is intact).
            recoveredPartialWrites += 1
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline < 0) "" else text.substring(0, lastNewline + 1)
        }
        val out = mutableListOf<ObjectNode>()
        for (line in completed.split("\n")) {
            if (line.isBlank()) continue
            try {
                (Mop.json.readTree(line) as? ObjectNode)?.let { out.add(it) }
            } catch (_: Exception) {
                // Corrupt complete line: skip, keep recovering the rest.
                recoveredPartialWrites += 1
            }
        }
        return out
    }

    /** Fail closed while a previous rollback could not be made durable. */
    private fun ensureWritable() {
        unavailableReason?.let { throw it }
        writePoison?.let {
            throw IOException(
                "OperatorActionLog writes blocked after an unrecoverable IO " +
                    "failure (${it.message}); inspect the directory, then " +
                    "call repairAfterFailure()",
                it,
            )
        }
    }

    /**
     * Explicit recovery after a poisoned failure: truncates a torn trailing
     * fragment (committed records are never touched) and re-opens the log
     * for appends. Returns 1 when a fragment was repaired, else 0.
     */
    @Synchronized
    fun repairAfterFailure(): Int {
        unavailableReason?.let { throw it }
        val repaired = if (repairTrailingFragment()) 1 else 0
        writePoison = null
        return repaired
    }

    private fun repairTrailingFragment(): Boolean {
        if (!file.exists()) return false
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return false
        if (bytes.last() == '\n'.code.toByte()) return false
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        val committed = if (lastNewline < 0) 0L else (lastNewline + 1).toLong()
        try {
            io.truncateTo(file, committed)
        } catch (exc: Exception) {
            val poison = IOException(
                "OperatorActionLog: repair truncate failed; writes blocked " +
                    "until repairAfterFailure()",
                exc,
            )
            writePoison = poison
            throw poison
        }
        recoveredPartialWrites += 1
        return true
    }

    /** Append with flush + fsync so a crash cannot lose a completed record.
     *  A torn tail left by a crash after this log was opened is repaired
     *  first, so a good record can never concatenate onto crash garbage. */
    private fun durableAppend(text: String) {
        ensureWritable()
        repairTrailingFragment()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val committed = if (file.exists()) file.length() else 0L
        try {
            io.appendSync(file, bytes)
        } catch (exc: Exception) {
            var tailRemoved = false
            try {
                val current = if (file.exists()) file.length() else 0L
                if (current != committed) {
                    io.truncateTo(file, committed)
                    tailRemoved = true
                }
                if (file.exists() && file.length() != committed) {
                    throw IOException(
                        "tail length ${file.length()} != committed $committed",
                    )
                }
            } catch (rollbackExc: Exception) {
                val poison = IOException(
                    "OperatorActionLog: append failed (${exc.message}) and " +
                        "the uncommitted tail could not be rolled back " +
                        "(${rollbackExc.message}); writes blocked until " +
                        "repairAfterFailure()",
                    rollbackExc,
                )
                writePoison = poison
                throw poison
            }
            if (tailRemoved) recoveredPartialWrites += 1
            if (exc is IOException) throw exc
            throw IOException("OperatorActionLog append failed: ${exc.message}", exc)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_NOTE_CHARS = 280

        /**
         * Unavailable readonly holder for the app degraded path: zero
         * filesystem allocation, zero reads; [append] (and repair) throws
         * [reason]. Reads are empty — only ever shown behind the
         * storage-unavailable UI flag. No mock data, nothing writable.
         */
        fun unavailable(reason: IOException): OperatorActionLog =
            OperatorActionLog(Paths.get(""), unavailableReason = reason)
        const val CONFIRM = "CONFIRM"
        const val HOLD = "HOLD"
        const val ESCALATE_REQUEST = "ESCALATE_REQUEST"
        val ACTIONS = setOf(CONFIRM, HOLD, ESCALATE_REQUEST)

        const val LOCAL_ONLY = "LOCAL_ONLY"
        const val REQUESTED = "REQUESTED"
        const val SENT_CONFIRMED = "SENT_CONFIRMED"
        const val RECEIVED_CONFIRMED = "RECEIVED_CONFIRMED"
        const val COMPLETED_CONFIRMED = "COMPLETED_CONFIRMED"
        const val UNKNOWN = "UNKNOWN"
        val DELIVERIES = setOf(
            LOCAL_ONLY, REQUESTED, SENT_CONFIRMED,
            RECEIVED_CONFIRMED, COMPLETED_CONFIRMED, UNKNOWN,
        )

        private val REQUIRED_TEXT = listOf(
            "response_id", "candidate_id", "site_id", "session_id",
            "evidence_source_id", "evidence_received_at_utc", "acted_at_utc",
        )

        /**
         * Proof-gated delivery claim, instance-free so the structured
         * upload route ([OperatorResponsePayload]) shares the exact same
         * rule the log screen uses — one rule, never two copies.
         */
        fun effectiveDeliveryOf(action: OperatorAction): String {
            if (action.action != ESCALATE_REQUEST) return LOCAL_ONLY
            return when (action.delivery) {
                LOCAL_ONLY, REQUESTED, UNKNOWN -> action.delivery
                SENT_CONFIRMED ->
                    if (!action.transmissionProof.isNullOrBlank()) action.delivery
                    else UNKNOWN
                RECEIVED_CONFIRMED ->
                    if (!action.transmissionProof.isNullOrBlank() &&
                        !action.receiptProof.isNullOrBlank()
                    ) {
                        action.delivery
                    } else {
                        UNKNOWN
                    }
                COMPLETED_CONFIRMED ->
                    if (!action.transmissionProof.isNullOrBlank() &&
                        !action.receiptProof.isNullOrBlank() &&
                        !action.completionProof.isNullOrBlank()
                    ) {
                        action.delivery
                    } else {
                        UNKNOWN
                    }
                else -> UNKNOWN
            }
        }
    }
}
