package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Minimal in-test Edge endpoint mirroring the validated harness
 * (`edge_replay.py`): handles MISSION_PREPARE (digest check -> PREPARED or
 * REJECTED), MISSION_APPLY (-> APPLIED or REJECTED), MISSION_STATUS_QUERY
 * (-> MISSION_STATUS), SESSION_START/STOP (Edge-local state + ACK). Keeps
 * an unacked queue for retransmission tests. Test-scope only.
 */
class FakeEdge(
    private val flightId: String,
    private val clock: () -> Long,
    private val bootId: String = "boot-1",
) {
    val outbox = ArrayDeque<ObjectNode>()
    val sentHistory = mutableListOf<String>()
    private val unacked = LinkedHashMap<String, ObjectNode>()
    private val seen = mutableSetOf<String>()
    private var counter = 0
    private var preparedSha: String? = null
    private var appliedSha: String? = null
    private var appliedApplicationId: String? = null
    var running = false
    var effectiveEdgeSeq = 7L
    /** Forces a prepare rejection (digest mismatch path). */
    var corruptPrepare = false
    /** When set, the next message of this type is enqueued twice. */
    var duplicateNextType: String? = null

    private fun send(type: String, payload: ObjectNode): ObjectNode {
        counter += 1
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId(
                "edge", flightId, bootId, counter.toString(),
                "CONTROL_RELIABLE", type,
            ),
            channel = "CONTROL_RELIABLE",
            type = type,
            flightId = flightId,
            edgeBootId = bootId,
            seq = null,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = clock(),
        )
        outbox.addLast(env)
        sentHistory.add(env.toString())
        unacked[env.get("msg_id").asText()] = env
        if (type == duplicateNextType) {
            outbox.addLast(env.deepCopy())
            duplicateNextType = null
        }
        return env
    }

    fun retransmissions(): List<ObjectNode> = unacked.values.toList()

    fun onRaw(raw: String): String {
        val parsed = try {
            Mop.verifyEnvelope(raw)
        } catch (exc: MopError) {
            return "error"
        }
        val env = parsed.first
        val payload = parsed.second
        val msgId = env.get("msg_id").asText()
        if (msgId in seen) return "dedup"
        if (env.get("type").asText() == "ACK") {
            unacked.remove(payload.get("ack_of")?.asText() ?: "")
            return "ack"
        }
        seen.add(msgId)
        when (env.get("type").asText()) {
            "MISSION_PREPARE" -> {
                val bundle = payload.get("bundle") as? ObjectNode
                val digestOk = bundle != null &&
                    CanonicalJson.verifyDigest(bundle, "bundle_sha256")
                if (digestOk && !corruptPrepare) {
                    preparedSha = bundle!!.get("bundle_sha256").asText()
                    val reply = Mop.json.createObjectNode()
                    reply.put("bundle_sha256", preparedSha)
                    reply.put("supported", true)
                    reply.set<com.fasterxml.jackson.databind.JsonNode>(
                        "exclusions", Mop.json.createArrayNode())
                    send("MISSION_PREPARED", reply)
                } else {
                    val reply = Mop.json.createObjectNode()
                    reply.set<com.fasterxml.jackson.databind.JsonNode>(
                        "reasons",
                        Mop.json.createArrayNode().add("DIGEST_MISMATCH"))
                    send("MISSION_APPLY_REJECTED", reply)
                }
            }
            "MISSION_APPLY" -> {
                val sha = payload.get("bundle_sha256")?.asText()
                if (preparedSha != null && sha == preparedSha) {
                    appliedSha = sha
                    appliedApplicationId = Uuid5.of(
                        Mop.NAMESPACE,
                        "apply|$flightId|${payload.get("bundle_id").asText()}|$bootId",
                    ).toString()
                    val reply = Mop.json.createObjectNode()
                    reply.put("application_id", appliedApplicationId)
                    reply.put("effective_edge_seq", effectiveEdgeSeq)
                    send("MISSION_APPLIED", reply)
                } else {
                    val reply = Mop.json.createObjectNode()
                    reply.set<com.fasterxml.jackson.databind.JsonNode>(
                        "reasons",
                        Mop.json.createArrayNode().add("UNKNOWN_BUNDLE"))
                    send("MISSION_APPLY_REJECTED", reply)
                }
            }
            "MISSION_STATUS_QUERY" -> {
                val reply = Mop.json.createObjectNode()
                if (appliedSha != null && appliedApplicationId != null) {
                    reply.put("applied_bundle_sha256", appliedSha)
                    reply.put("application_id", appliedApplicationId)
                } else {
                    reply.putNull("applied_bundle_sha256")
                    reply.putNull("application_id")
                }
                reply.put("edge_boot_id", bootId)
                reply.put("running", running)
                reply.put("effective_edge_seq", effectiveEdgeSeq)
                send("MISSION_STATUS", reply)
            }
            "SESSION_START" -> running = true
            "SESSION_STOP" -> running = false
        }
        ack(env)
        return "stored"
    }

    private fun ack(forEnv: ObjectNode) {
        counter += 1
        val msgId = forEnv.get("msg_id").asText()
        val payload = Mop.json.createObjectNode().put("ack_of", msgId)
        val ackEnv = Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge", flightId, bootId, counter.toString(),
                "ACK", msgId),
            channel = forEnv.get("channel").asText(),
            type = "ACK",
            flightId = flightId,
            edgeBootId = bootId,
            seq = null,
            ackOf = msgId,
            payload = payload,
            sentMonotonicNs = clock(),
        )
        outbox.addLast(ackEnv)
        sentHistory.add(ackEnv.toString())
    }
}

/** Deterministic delivery loop: initial sends + retransmissions per round. */
fun pump(
    rc: MopEndpoint,
    edge: FakeEdge,
    rounds: Int = 4,
    dropToRc: (ObjectNode) -> Boolean = { false },
    dropToEdge: (ObjectNode) -> Boolean = { false },
) {
    for (round in 0 until rounds) {
        if (round > 0) {
            rc.retransmissions().forEach { env ->
                if (!dropToEdge(env)) edge.onRaw(env.toString())
            }
            edge.retransmissions().forEach { env ->
                if (!dropToRc(env)) rc.onRawEnvelope(env.toString())
            }
        }
        while (rc.outbox.isNotEmpty()) {
            val env = rc.outbox.removeFirst()
            if (!dropToEdge(env)) edge.onRaw(env.toString())
        }
        while (edge.outbox.isNotEmpty()) {
            val env = edge.outbox.removeFirst()
            if (!dropToRc(env)) rc.onRawEnvelope(env.toString())
        }
    }
}
