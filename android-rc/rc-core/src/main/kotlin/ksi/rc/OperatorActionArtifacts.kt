package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/**
 * Operator responses as a first-class flight artifact — WITHOUT any contract
 * change. `contracts/schemas/flight-upload-manifest.v1.schema.json` already
 * allows `source_kind=FLIGHT_LOG` (unused until now), so the operator-action
 * NDJSON travels as a required FLIGHT_LOG artifact through the SAME
 * announce/chunk pipeline every other artifact uses:
 *
 * - manifest entry (`source_kind=FLIGHT_LOG`, `media_type`
 *   `application/x-ndjson`, sha256 + size over the exact bytes,
 *   `segment_index=null`);
 * - chunked upload with the existing dedup/ACK rules: never acknowledged
 *   before durable write, never sent unless coverage+size+sha256 verify,
 *   never marked SYNCED without a server COMMITTED;
 * - idempotent re-runs (content-derived artifact id; announce put-if-absent;
 *   chunks skipped when complete; manifest digest resume);
 * - append-only history: a new response after an upload yields a NEW
 *   snapshot artifact (content-derived id); the previous snapshot is kept
 *   locally but the manifest carries only the latest (see [ManifestBuilder]),
 *   so a stale partial snapshot can never hold a flight PARTIAL forever.
 *
 * Join key: [OperatorAction.sessionId] is matched against the session id the
 * [SessionBuilder] derives for the (flight, boot) pair
 * (`uuid5("session|flight|boot")`). The writer (RC UI) must store that
 * session id — the uploader never invents flight linkage.
 *
 * Honest rules:
 * - no responses for this boot: [ensureForBoot] announces nothing and the
 *   manifest (and the flight's completeness judgement) is unchanged. An empty
 *   required artifact is never created, and responses are never silently
 *   claimed.
 * - responses exist: the snapshot is REQUIRED — missing bytes surface as
 *   honest PARTIAL, never COMPLETE.
 * - delivery/proof semantics are untouched: this ships the stored records
 *   verbatim; effective-delivery downgrades still apply at read time.
 */
object OperatorActionArtifacts {
    const val SOURCE_KIND = "FLIGHT_LOG"
    const val MEDIA_TYPE = "application/x-ndjson"

    /** Session id the [SessionBuilder] derives for a (flight, boot). */
    fun sessionIdFor(flightId: String, bootId: String): String =
        Uuid5.of(Mop.NAMESPACE, "session|$flightId|$bootId").toString()

    /**
     * Content-derived artifact id (`uuid5("operator-actions|flight|boot|
     * sha")`): re-running with identical responses reuses the artifact;
     * changed responses yield a NEW artifact while the old one is kept.
     */
    fun artifactIdFor(flightId: String, bootId: String, sha256Hex: String): String =
        Mop.newMsgId("operator-actions", flightId, bootId, sha256Hex)

    /**
     * NDJSON snapshot of this boot's responses in log (insertion) order.
     * Empty when the boot has no responses (the caller must then omit the
     * artifact entirely).
     */
    fun snapshotBytes(log: OperatorActionLog, sessionId: String): ByteArray {
        val lines = log.readAll()
            .filter { it.sessionId == sessionId }
            .map { log.lineFor(it) }
        if (lines.isEmpty()) return ByteArray(0)
        return lines.joinToString(separator = "\n", postfix = "\n")
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Materialize this boot's operator-action snapshot through the standard
     * announce/chunk pipeline. Returns the artifact id, or null when this
     * boot has no responses (nothing announced, manifest unchanged).
     */
    fun ensureForBoot(
        store: UploadStore,
        log: OperatorActionLog,
        flightId: String,
        bootId: String,
        chunkBytes: Int = 64 * 1024,
    ): String? {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val bytes = snapshotBytes(log, sessionIdFor(flightId, bootId))
        if (bytes.isEmpty()) return null
        val sha = sha256Hex(bytes)
        val artifactId = artifactIdFor(flightId, bootId, sha)
        val existing = store.artifactsFor(flightId, bootId)
            .firstOrNull { it.get("artifact_id")?.asText() == artifactId }
        if (existing == null) {
            store.announceArtifact(announce(flightId, bootId, artifactId, bytes, sha), bootId)
        } else {
            check(
                existing.get("sha256")?.asText() == sha &&
                    existing.get("size_bytes")?.asLong() == bytes.size.toLong(),
            ) {
                "artifact id collision with different content for $artifactId"
            }
        }
        if (store.isArtifactComplete(artifactId)) return artifactId
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkBytes, bytes.size)
            store.writeChunk(artifactId, offset.toLong(), bytes.copyOfRange(offset, end))
            offset = end
        }
        return artifactId
    }

    private fun announce(
        flightId: String,
        bootId: String,
        artifactId: String,
        bytes: ByteArray,
        sha: String,
    ): ObjectNode {
        val announce = Mop.json.createObjectNode()
        announce.put("schema_version", 1)
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", flightId)
        announce.put("edge_boot_id", bootId)
        announce.put("source_kind", SOURCE_KIND)
        announce.put("sha256", sha)
        announce.put("size_bytes", bytes.size.toLong())
        announce.put("media_type", MEDIA_TYPE)
        announce.put("required", true)
        announce.putNull("segment_index")
        return announce
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
