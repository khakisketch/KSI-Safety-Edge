package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Structured operator-response payloads for the Core
 * `POST /operator-responses` / `GET /flights/{flight}/operator-responses`
 * route — WITHOUT any new schema: the exact
 * `contracts/schemas/operator-response.v1.schema.json` shape
 * (snake_case, all required keys always present, `content_sha256` over
 * RFC 8785 canonical JSON excluding the digest itself).
 *
 * Honesty rules, all tested:
 * - every id/fact comes from the persisted [OperatorAction] plus the
 *   caller's actual flight id — nothing is invented. In particular the
 *   `flight_id` is the flight the caller is uploading (verified against
 *   the session linkage by the caller, see [OperatorResponseSync]); the
 *   payload builder itself takes it as an explicit parameter so a wrong
 *   flight can never be silently stamped.
 * - `delivery_state` is the EFFECTIVE delivery
 *   ([OperatorActionLog.effectiveDeliveryOf]): proofless offline records
 *   stay LOCAL_ONLY/REQUESTED (or UNKNOWN when a stored claim lacks its
 *   proof) even if the Core later acknowledges durable preservation — a
 *   storage ACK is not evidence the field team received or completed
 *   anything, and it is never a request being delivered.
 * - proofs travel verbatim when present, null otherwise; a claim above
 *   REQUESTED without its proof is never emitted (it degrades to UNKNOWN
 *   before serialization).
 * - a CONFIRM never clears the risk (see `ObservationResponse.clearsRisk`);
 *   this payload carries the operator's action, never a safety verdict.
 */
object OperatorResponsePayload {
    /**
     * Exact contract payload for one persisted action. Throws when the
     * action's required ids/facts are blank — an incomplete record is
     * never serialized into a half-claim.
     */
    fun fromAction(action: OperatorAction, flightId: String): ObjectNode {
        require(action.responseId.isNotBlank()) { "response_id must not be blank" }
        require(action.candidateId.isNotBlank()) { "candidate_id must not be blank" }
        require(flightId.isNotBlank()) { "flight_id must not be blank" }
        require(action.siteId.isNotBlank()) { "site_id must not be blank" }
        require(action.sessionId.isNotBlank()) { "session_id must not be blank" }
        require(action.action in OperatorActionLog.ACTIONS) {
            "unknown action ${action.action}"
        }
        require(action.evidenceSourceId.isNotBlank()) {
            "evidence source id must not be blank"
        }
        require(action.evidenceReceivedAtUtc.isNotBlank()) {
            "evidence received time must not be blank"
        }
        require(action.actedAtUtc.isNotBlank()) { "action time must not be blank" }
        if (action.note != null) {
            require(action.note.length <= OperatorActionLog.MAX_NOTE_CHARS) {
                "operator note must be <= ${OperatorActionLog.MAX_NOTE_CHARS} chars"
            }
        }

        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("response_id", action.responseId)
        node.put("candidate_id", action.candidateId)
        node.put("flight_id", flightId)
        node.put("site_id", action.siteId)
        node.put("session_id", action.sessionId)
        node.put("action", action.action)
        if (action.note == null) node.putNull("note") else node.put("note", action.note)
        val evidence = node.putObject("evidence")
        evidence.put("source_id", action.evidenceSourceId)
        evidence.put("received_at_utc", action.evidenceReceivedAtUtc)
        if (action.policyId == null) evidence.putNull("policy_id")
        else evidence.put("policy_id", action.policyId)
        if (action.policyVersion == null) evidence.putNull("policy_version")
        else evidence.put("policy_version", action.policyVersion)
        node.put("acted_at_utc", action.actedAtUtc)
        node.put("delivery_state", OperatorActionLog.effectiveDeliveryOf(action))
        if (action.transmissionProof == null) node.putNull("transmission_proof")
        else node.put("transmission_proof", action.transmissionProof)
        if (action.receiptProof == null) node.putNull("receipt_proof")
        else node.put("receipt_proof", action.receiptProof)
        if (action.completionProof == null) node.putNull("completion_proof")
        else node.put("completion_proof", action.completionProof)
        node.put(
            "content_sha256",
            CanonicalJson.digestExcluding(node, "content_sha256"),
        )
        return node
    }

    /** Exact contract key set (`additionalProperties: false` at both levels). */
    fun topLevelKeys(node: ObjectNode): Set<String> =
        node.fieldNames().asSequence().toSet()

    fun evidenceKeys(node: ObjectNode): Set<String> =
        (node.get("evidence") as? ObjectNode)?.fieldNames()?.asSequence()?.toSet()
            ?: emptySet()

    /** Recompute-and-compare digest check (mirrors the server's strict mode). */
    fun verifyDigest(node: JsonNode): Boolean =
        CanonicalJson.verifyDigest(node, "content_sha256")
}
