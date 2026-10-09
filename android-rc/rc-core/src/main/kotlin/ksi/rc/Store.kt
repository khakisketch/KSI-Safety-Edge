package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * RC-side durable store interface.
 *
 * Semantics required by the contracts (ledger §9, user 2026-10-09):
 * `storeMessage` must be durable BEFORE the envelope ACK is emitted
 * (commit-before-ACK); `markProcessed` makes redelivery a no-op; processed
 * ids survive an RC restart (a restarted endpoint rebuilds its dedup set
 * from [processedMsgIds]). Implementations: [InMemoryRcStore] for tests;
 * the persistent (file/SQLite) implementation lands with A3.
 */
interface RcStore {
    /** Durable monotonic counter for RC message ids; never returns a value
     *  twice, including across process restarts (ids must not be reused:
     *  the Edge dedups by msg_id and a reused id would silence a new
     *  command). The value must be persisted BEFORE the message that uses
     *  it is sent. */
    fun nextMsgCounter(): Long

    fun isProcessed(msgId: String): Boolean

    /** Persist the raw envelope (idempotent by msg_id). */
    fun storeMessage(env: ObjectNode)

    fun markProcessed(msgId: String)

    fun processedMsgIds(): Set<String>

    /** Idempotent by application_id (a repeated record must not duplicate). */
    fun recordApplication(application: ObjectNode)

    fun applicationsFor(flightId: String): List<ObjectNode>
}

/** Test/double store; no durability guarantees beyond this process. */
class InMemoryRcStore : RcStore {
    private val processed = linkedSetOf<String>()
    private val messages = linkedMapOf<String, ObjectNode>()
    private val applications = linkedMapOf<String, ObjectNode>()
    private var counter = 0L

    override fun nextMsgCounter(): Long = ++counter

    override fun isProcessed(msgId: String): Boolean = msgId in processed

    override fun storeMessage(env: ObjectNode) {
        val msgId = env.get("msg_id").asText()
        messages.putIfAbsent(msgId, env.deepCopy())
    }

    override fun markProcessed(msgId: String) {
        processed.add(msgId)
    }

    override fun processedMsgIds(): Set<String> = processed.toSet()

    override fun recordApplication(application: ObjectNode) {
        applications.putIfAbsent(application.get("application_id").asText(),
            application.deepCopy())
    }

    override fun applicationsFor(flightId: String): List<ObjectNode> =
        applications.values.filter { it.get("flight_id").asText() == flightId }
}
