package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Outcome of one inbound envelope at the RC endpoint.
 *
 * `Acked` is ONLY transport/durability bookkeeping (it clears the retry
 * queue) — it never means "the Edge applied the mission". Apply-state
 * advancement is the MissionController's domain, driven by MISSION_APPLIED
 * / MISSION_STATUS (see the contract note there).
 */
sealed interface Inbound {
    /** Message was handled, persisted, marked processed and ACKed. */
    data class Stored(val type: String) : Inbound
    /** Already-processed msg_id redelivery; no side effects. */
    data class Duplicate(val type: String) : Inbound
    /** An ACK for one of our outbound messages (retry-queue maintenance). */
    data class Acked(val ackOf: String) : Inbound
    /** Not handled; `reason` says why (domain failure -> no ACK, retried). */
    data class Failed(val type: String, val reason: String) : Inbound
}

/** Domain handler: returns null on success, or a failure reason. */
fun interface ControlHandler {
    fun handle(env: ObjectNode, payload: ObjectNode): String?
}

/**
 * RC side of the MOP exchange (KSI envelope contract on top of whatever
 * transport DJI MOP / a test link provides — the envelope is OUR message
 * contract, not proof that the aircraft/RC link works).
 *
 * Ported semantics from the validated harness (`rc_sim.py` + `mop.py`):
 * - outbound control messages get deterministic uuids
 *   `uuid5(NAMESPACE, "rc|flight|counter|type")`, channel CONTROL_RELIABLE,
 *   seq = null (only the Edge numbers channels);
 * - inbound: dedup by msg_id -> ACK envelope (retry queue) -> persist to
 *   the store -> domain handler -> mark processed -> emit ACK
 *   (commit-before-ACK);
 * - a domain failure leaves the message unmarked and un-ACKed so the Edge
 *   retries the SAME msg_id (reprocessing after a partial failure is safe);
 * - retransmissions reuse the same msg_id and are exposed via
 *   [retransmissions] (timer-driven in the app; pumped in tests).
 */
class MopEndpoint(
    private val flightId: String,
    private val store: RcStore,
    private val clock: () -> Long,
    private val handler: ControlHandler,
    private val onAcked: ((String) -> Unit)? = null,
) {
    val outbox = ArrayDeque<ObjectNode>()
    private val unacked = LinkedHashMap<String, ObjectNode>()
    private val seen: MutableSet<String> = store.processedMsgIds().toMutableSet()
    var ackedCount = 0
        private set

    fun sendControl(type: String, payload: ObjectNode): ObjectNode {
        val counter = store.nextMsgCounter()
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("rc", flightId, counter.toString(), type),
            channel = "CONTROL_RELIABLE",
            type = type,
            flightId = flightId,
            edgeBootId = null,
            seq = null,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = clock(),
        )
        outbox.addLast(env)
        unacked[env.get("msg_id").asText()] = env
        return env
    }

    private fun sendAck(forEnv: ObjectNode) {
        val counter = store.nextMsgCounter()
        val msgId = forEnv.get("msg_id").asText()
        val payload = Mop.json.createObjectNode().put("ack_of", msgId)
        val boot = forEnv.get("edge_boot_id")
        val ack = Mop.makeEnvelope(
            msgId = Mop.newMsgId("rc", flightId, counter.toString(), "ACK", msgId),
            channel = forEnv.get("channel").asText(),
            type = "ACK",
            flightId = flightId,
            edgeBootId = if (boot == null || boot.isNull) null else boot.asText(),
            seq = null,
            ackOf = msgId,
            payload = payload,
            sentMonotonicNs = clock(),
        )
        outbox.addLast(ack)
        ackedCount += 1
    }

    /** Unacked envelopes for retransmission (same msg_id, unchanged bytes). */
    fun retransmissions(): List<ObjectNode> = unacked.values.toList()

    fun pendingCount(): Int = unacked.size

    fun onRawEnvelope(rawJson: String): Inbound {
        val parsed = try {
            Mop.verifyEnvelope(rawJson)
        } catch (exc: MopError) {
            return Inbound.Failed("?", "envelope rejected: ${exc.message}")
        }
        val env = parsed.first
        val payload = parsed.second
        val msgId = env.get("msg_id").asText()
        val type = env.get("type").asText()
        if (msgId in seen || store.isProcessed(msgId)) {
            // Idempotent re-ACK for non-ACK duplicates: an already-processed
            // message whose ACK was lost must not make the Edge retry
            // forever. (Deliberate strengthening over the Python harness,
            // which returns dedup without re-ACKing.)
            if (type != "ACK") ackSafely(env)
            return Inbound.Duplicate(type)
        }
        if (type == "ACK") {
            val ackOf = payload.get("ack_of")?.asText() ?: ""
            unacked.remove(ackOf)
            onAcked?.invoke(ackOf)
            return Inbound.Acked(ackOf)
        }
        // Persist BEFORE the domain handler and the ACK (commit-before-ACK);
        // a persist failure must not ACK (and must not advance anything).
        try {
            store.storeMessage(env)
        } catch (exc: Exception) {
            return Inbound.Failed(type, "persist failed: ${exc.message}")
        }
        if (type !in Mop.EDGE_TO_RC) {
            // Harness parity: unknown types are ACKed (terminal) and counted
            // as errors, never retried by the Edge.
            ackSafely(env)
            return Inbound.Failed(type, "unknown type")
        }
        val failure = try {
            handler.handle(env, payload)
        } catch (exc: Exception) {
            "handler error: ${exc.message}"
        }
        if (failure != null) {
            // No ACK, no mark: the Edge retries the same msg_id and the
            // stored envelope lets reprocessing resume safely.
            return Inbound.Failed(type, failure)
        }
        try {
            store.markProcessed(msgId)
        } catch (exc: Exception) {
            return Inbound.Failed(type, "markProcessed failed: ${exc.message}")
        }
        seen.add(msgId)
        if (!ackSafely(env)) {
            // Domain work is durable; the ACK itself failed to persist. The
            // Edge retry hits the duplicate path above and is re-ACKed.
            return Inbound.Failed(type, "ack could not be persisted")
        }
        return Inbound.Stored(type)
    }

    /** Best-effort ACK emission; false when even the ACK could not persist. */
    private fun ackSafely(env: ObjectNode): Boolean = try {
        sendAck(env)
        true
    } catch (exc: Exception) {
        false
    }
}
