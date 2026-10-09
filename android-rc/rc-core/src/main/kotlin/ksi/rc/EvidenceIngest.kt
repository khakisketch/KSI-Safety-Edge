package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * Evidence-plane handler (A3): stores OBSERVATION / DETECTION_EVENT /
 * FLIGHT_SUMMARY / ARTIFACT_ANNOUNCE / ARTIFACT_CHUNK payloads into an
 * [EvidenceStore]. Failure returns a reason, which leaves the envelope
 * un-ACKed so the Edge retries the same msg_id (commit-before-ACK lives in
 * [MopEndpoint]). HEARTBEAT is accepted with no side effect here (gap
 * bookkeeping belongs to the uploader, A3-b).
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
                if (payload.get("artifact_id")?.asText().isNullOrEmpty()) {
                    return "announce without artifact_id"
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
            "HEARTBEAT" -> null
            else -> "unsupported data type $type"
        }
    }
}
