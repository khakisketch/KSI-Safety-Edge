package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.Mop
import ksi.rc.UploadStore

/**
 * Combined mission + closeout snapshot as ONE required FLIGHT_LOG artifact
 * (contract §6 closeout).
 *
 * A single content-addressed snapshot per (flight, boot) carries BOTH the
 * exact pinned bundle bytes the Edge installed AND the close-out metadata
 * (verdict, stop confirmation, opinion count, segment results). One
 * artifact keeps one manifest lineage slot: the core manifest builder
 * preserves each unknown FLIGHT_LOG id independently
 * (`legacy:<artifactId>`), so the combined snapshot never evicts — and is
 * never evicted by — another lineage. Keeping mission + closeout in ONE
 * content-derived snapshot (instead of a separate bundle-only artifact
 * next to it) is what guarantees a single slot per boot.
 *
 * Id derivation is stable and shared: `uuid5("mission-bundle"|flight|boot|
 * sha)` where sha covers the COMBINED content — re-running with identical
 * facts reuses the artifact; changed facts yield a NEW id while the old
 * record is kept (append-only evidence). Exact id shape for core-side
 * lineage coordination: mission-bundle UUIDv5 over
 * flightId|bootId|contentSha256.
 */
object CloseoutArtifacts {
    const val SOURCE_KIND = "FLIGHT_LOG"
    const val MEDIA_TYPE = "application/json"
    const val LINEAGE = "mission-bundle"
    const val RECORD_KIND = "CLOSEOUT"

    fun artifactIdFor(flightId: String, bootId: String, sha256Hex: String): String =
        Mop.newMsgId("mission-bundle", flightId, bootId, sha256Hex)

    /**
     * Canonical combined bytes: the close-out record plus the exact stored
     * bundle snapshot it closed under (null bundle ships a null snapshot —
     * the missing basis is explicit, never defaulted).
     */
    fun snapshotBytes(record: CloseoutRecord, bundle: ObjectNode?): ByteArray {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("record_kind", RECORD_KIND)
        node.put("flight_id", record.flightId)
        if (record.bootId == null) node.putNull("edge_boot_id")
        else node.put("edge_boot_id", record.bootId)
        if (record.sessionId == null) node.putNull("session_id")
        else node.put("session_id", record.sessionId)
        node.put("outcome", record.outcome)
        if (record.stopConfirmed == null) node.putNull("stop_confirmed")
        else node.put("stop_confirmed", record.stopConfirmed)
        node.put("stop_reason", record.stopReason)
        node.put("opinion_count", record.opinionCount)
        node.put("segments_ingested", record.segmentsIngested)
        node.put("segments_total", record.segmentsTotal)
        node.put("segments_unverified", record.segmentsUnverified)
        if (record.videoCaptureFailed == null) node.putNull("video_capture_failed")
        else node.put("video_capture_failed", record.videoCaptureFailed)
        if (record.bundleId == null) node.putNull("bundle_id")
        else node.put("bundle_id", record.bundleId)
        if (record.bundleSha256 == null) node.putNull("bundle_sha256")
        else node.put("bundle_sha256", record.bundleSha256)
        if (record.verdictResult == null) node.putNull("verdict_result")
        else node.put("verdict_result", record.verdictResult)
        if (record.verdictFactsJson == null) node.putNull("verdict_facts_json")
        else node.put("verdict_facts_json", record.verdictFactsJson)
        node.put("decided_at_utc", record.decidedAtUtc)
        if (bundle == null) node.putNull("bundle_snapshot")
        else node.set<JsonNode>("bundle_snapshot", bundle.deepCopy())
        return Mop.json.writeValueAsBytes(node)
    }

    /**
     * Materialize this boot's combined snapshot through the standard
     * announce/chunk pipeline. Only verified actual refs are announced:
     * [record] must carry the boot being materialized (its `edge_boot_id`
     * equals [bootId]) — a mismatch announces nothing. Returns the artifact
     * id, or null when the record does not belong to this boot.
     */
    fun ensureForBoot(
        store: UploadStore,
        record: CloseoutRecord,
        bundle: ObjectNode?,
        flightId: String,
        bootId: String,
        chunkBytes: Int = 64 * 1024,
    ): String? {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        if (record.flightId != flightId || record.bootId != bootId) return null
        val bytes = snapshotBytes(record, bundle)
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
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
