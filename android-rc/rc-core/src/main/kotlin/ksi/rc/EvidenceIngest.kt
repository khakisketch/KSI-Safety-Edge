package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * Evidence-plane handler (A3): stores OBSERVATION / DETECTION_EVENT /
 * FLIGHT_SUMMARY / ARTIFACT_ANNOUNCE / ARTIFACT_CHUNK payloads into an
 * [EvidenceStore]. Failure returns a reason, which leaves the envelope
 * un-ACKed so the Edge retries the same msg_id (commit-before-ACK lives in
 * [MopEndpoint]). HEARTBEAT records the Edge's `unrecoverable_gaps`
 * declarations (A3-b manifest input); the RC never declares gaps itself.
 */
class EvidenceIngest(private val store: EvidenceStore) : ControlHandler {
    override fun handle(env: ObjectNode, payload: ObjectNode): String? {
        val boot = env.get("edge_boot_id")?.takeIf { !it.isNull }?.asText()
            ?: return "missing edge_boot_id"
        return when (val type = env.get("type").asText()) {
            "OBSERVATION" -> {
                val observation = payload.get("observation") as? ObjectNode
                    ?: return "missing observation"
                if (observation.get("flight_id")?.asText().isNullOrEmpty()) {
                    return "observation without flight_id"
                }
                store.storeObservation(observation, boot)
                null
            }
            "DETECTION_EVENT" -> {
                val event = payload.get("event") as? ObjectNode
                    ?: return "missing event"
                if (event.get("flight_id")?.asText().isNullOrEmpty()) {
                    return "event without flight_id"
                }
                store.storeEvent(event, boot)
                null
            }
            "FLIGHT_SUMMARY" -> {
                val summary = payload.get("summary") as? ObjectNode
                    ?: return "missing summary"
                store.storeFlightSummary(summary, boot)
                null
            }
            "ARTIFACT_ANNOUNCE" -> {
                val artifactId = payload.get("artifact_id")?.asText()
                if (artifactId.isNullOrEmpty()) {
                    return "announce without artifact_id"
                }
                // Same id + different bytes is a conflict (the Edge/Core
                // contract refuses those): never silently keep the first
                // and ACK the second. Failure leaves the envelope un-ACKed
                // so the Edge retries the same msg_id; a genuine conflict
                // stays visible as unacked instead of corrupting the link.
                val existing = store.announceFor(artifactId)
                if (existing != null && !sameAnnounce(existing, payload)) {
                    return "conflicting re-announce for $artifactId"
                }
                store.announceArtifact(payload, boot)
                null
            }
            "ARTIFACT_CHUNK" -> {
                val artifactId = payload.get("artifact_id")?.asText()
                    ?: return "chunk without artifact_id"
                val offset = payload.get("offset")?.asLong()
                    ?: return "chunk without offset"
                val data = payload.get("data_b64")?.asText()
                    ?: return "chunk without data_b64"
                val bytes = try {
                    Base64.getDecoder().decode(data)
                } catch (exc: IllegalArgumentException) {
                    return "bad base64 chunk"
                }
                store.writeChunk(artifactId, offset, bytes)
                null
            }
            "HEARTBEAT" -> {
                // Record only the Edge's unrecoverable-gap declarations;
                // seqs that were actually received are not gaps (mirror of
                // the validated harness `_on_heartbeat`).
                val declared = payload.get("unrecoverable_gaps")
                if (declared != null && declared.isArray) {
                    for (item in declared) {
                        val channel = item.get("channel")?.asText() ?: continue
                        val seqs = item.get("seqs") ?: continue
                        if (!seqs.isArray) continue
                        for (seqNode in seqs) {
                            val seq = seqNode.asLong()
                            if (seq in store.receivedSeqs(boot, channel)) continue
                            val gap = Mop.json.createObjectNode()
                            gap.put("schema_version", 1)
                            gap.put("edge_boot_id", boot)
                            gap.put("channel", channel)
                            gap.put("seq", seq)
                            gap.put("reason", "UNRECOVERABLE:$channel")
                            gap.put("destination", "RC_MANIFEST")
                            gap.put("flight_id", env.get("flight_id")?.asText() ?: "")
                            store.recordGap(gap)
                        }
                    }
                }
                null
            }
            else -> "unsupported data type $type"
        }
    }

    companion object {
        /**
         * Byte-identity of two artifact announcements: digest, size, media
         * and source kind. Anything else (e.g. a re-announce with the same
         * id but different bytes) is a conflict, never an idempotent retry.
         */
        fun sameAnnounce(first: ObjectNode, second: ObjectNode): Boolean {
            if (first.get("sha256")?.asText() != second.get("sha256")?.asText()) return false
            if (first.get("size_bytes")?.asLong() != second.get("size_bytes")?.asLong()) return false
            if (first.get("media_type")?.asText() != second.get("media_type")?.asText()) return false
            if (first.get("source_kind")?.asText() != second.get("source_kind")?.asText()) return false
            return true
        }
    }
}
