package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.security.MessageDigest

/**
 * RC proxy-segment ingestion (user review 2026-10-10, item 3): a recorded
 * video segment becomes a standard artifact (`source_kind=RC_PROXY_SEGMENT`)
 * through the SAME announce/chunk pipeline every other artifact uses, so
 * the manifest, coverage+hash completeness, COMMITTED-gated sync and
 * retention rules apply unchanged. Segments are recorded for EVERY flight
 * — including zero-event flights — because the recording is what proves
 * what was (and was not) observed.
 *
 * Deterministic id: `uuid5("segment|flight|boot|index|sha256")` —
 * re-ingesting the identical file is a no-op; a different file yields a
 * NEW artifact id and the previous artifact is kept (append-only
 * evidence: nothing is ever deleted, before or after sync).
 *
 * The actual capture (MSDK video -> encoded segments) is the H1/A9 item;
 * this class is the tested storage/upload path that capture feeds.
 */
class SegmentIngestor(
    private val store: UploadStore,
    private val chunkBytes: Int = 1 shl 20,
) {
    init {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
    }

    fun ingestSegment(
        flightId: String,
        bootId: String,
        segmentIndex: Long,
        file: File,
        mediaType: String = "video/mp4",
        required: Boolean = true,
    ): String {
        require(file.exists() && file.isFile) { "segment file missing: $file" }
        val sha = sha256File(file)
        val artifactId = Mop.newMsgId(
            "segment", flightId, bootId, segmentIndex.toString(), sha,
        )
        val announce: ObjectNode = Mop.json.createObjectNode()
        announce.put("schema_version", 1)
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", flightId)
        announce.put("edge_boot_id", bootId)
        announce.put("source_kind", "RC_PROXY_SEGMENT")
        announce.put("sha256", sha)
        announce.put("size_bytes", file.length())
        announce.put("media_type", mediaType)
        announce.put("required", required)
        announce.put("segment_index", segmentIndex)
        store.announceArtifact(announce, bootId)
        if (store.isArtifactComplete(artifactId)) {
            return artifactId // identical re-ingest: bytes already stored
        }
        var offset = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(chunkBytes)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                store.writeChunk(artifactId, offset, buffer.copyOfRange(0, read))
                offset += read
            }
        }
        return artifactId
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
