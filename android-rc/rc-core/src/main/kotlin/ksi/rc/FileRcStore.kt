package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Durable RC store: append-only NDJSON files + artifact blob files under
 * one directory. Pure java.io/nio so it runs on the JVM (tests, host) and
 * on Android alike; all indexes are rebuilt at construction, which is the
 * restart-recovery contract (ledger §9 A3): flight/boot/observation/event/
 * artifact relations survive an RC process restart.
 *
 * Write path: append line -> (idempotent by record id) -> crash between
 * message store and markProcessed leaves the message reprocessable, which
 * every record handler tolerates. Nothing here deletes evidence.
 */
class FileRcStore(root: Path) : RcStore, EvidenceStore {
    private val rootDir = root.toFile()
    private val messagesFile = File(rootDir, "messages.ndjson")
    private val processedFile = File(rootDir, "processed.ndjson")
    private val counterFile = File(rootDir, "msg_counter")
    private val appsFile = File(rootDir, "applications.ndjson")
    private val obsFile = File(rootDir, "observations.ndjson")
    private val eventsFile = File(rootDir, "events.ndjson")
    private val summariesFile = File(rootDir, "summaries.ndjson")
    private val artifactsFile = File(rootDir, "artifacts.ndjson")
    private val chunksFile = File(rootDir, "chunks.ndjson")
    private val blobsDir = File(rootDir, "blobs")

    private data class BootNode(val bootId: String, val node: ObjectNode)

    private val processed = linkedSetOf<String>()
    private val applications = linkedMapOf<String, ObjectNode>()
    private val observations = linkedMapOf<String, BootNode>()
    private val events = linkedMapOf<String, BootNode>()
    private val summaries = linkedMapOf<String, BootNode>()
    private val artifacts = linkedMapOf<String, ObjectNode>()

    /** Merged received byte ranges per artifact (0-based, inclusive). */
    private val coverage = linkedMapOf<String, MutableList<LongRange>>()

    /** Count of trailing/corrupt records skipped while loading (diagnostics). */
    var recoveredPartialWrites = 0
        private set

    init {
        rootDir.mkdirs()
        blobsDir.mkdirs()
        for (line in readLines(processedFile)) {
            if (line.isNotBlank()) processed.add(line.trim())
        }
        for (node in readNodes(appsFile)) {
            applications.putIfAbsent(node.get("application_id").asText(), node)
        }
        loadBootNodes(obsFile, "observation_id", observations)
        loadBootNodes(eventsFile, "event_id", events)
        loadBootNodes(summariesFile, "summary_id", summaries)
        for (node in readNodes(artifactsFile)) {
            artifacts.putIfAbsent(node.get("artifact_id").asText(), node)
        }
        for (node in readNodes(chunksFile)) {
            val artifactId = node.get("artifact_id")?.asText() ?: continue
            val offset = node.get("offset")?.asLong() ?: continue
            val length = node.get("length")?.asLong() ?: continue
            addRange(artifactId, offset, length)
        }
    }

    // -- RcStore -------------------------------------------------------------

    override fun nextMsgCounter(): Long {
        val current = if (counterFile.exists()) {
            val text = counterFile.readText().trim()
            text.toLongOrNull()
                ?: throw IOException(
                    "msg_counter corrupt; refusing to risk id reuse: $counterFile",
                )
        } else {
            0L
        }
        val next = current + 1
        // Persist BEFORE the value is used: a crash between this write and
        // the send skips an id (safe); the value is never handed out twice.
        // Single-writer assumption: one RC process owns the directory.
        durableWrite(counterFile, "$next\n")
        return next
    }

    override fun isProcessed(msgId: String): Boolean = msgId in processed

    override fun storeMessage(env: ObjectNode) {
        appendNode(messagesFile, env)
    }

    override fun markProcessed(msgId: String) {
        if (processed.add(msgId)) {
            durableAppend(processedFile, msgId + "\n")
        }
    }

    override fun processedMsgIds(): Set<String> = processed.toSet()

    override fun recordApplication(application: ObjectNode) {
        val id = application.get("application_id").asText()
        if (applications.putIfAbsent(id, application) == null) {
            appendNode(appsFile, application)
        }
    }

    override fun applicationsFor(flightId: String): List<ObjectNode> =
        applications.values.filter { it.get("flight_id")?.asText() == flightId }

    // -- EvidenceStore ---------------------------------------------------------

    override fun storeObservation(observation: ObjectNode, bootId: String) {
        storeBootNode(obsFile, "observation_id", observation, bootId, observations)
    }

    override fun storeEvent(event: ObjectNode, bootId: String) {
        storeBootNode(eventsFile, "event_id", event, bootId, events)
    }

    override fun storeFlightSummary(summary: ObjectNode, bootId: String) {
        storeBootNode(summariesFile, "summary_id", summary, bootId, summaries)
    }

    override fun announceArtifact(announce: ObjectNode, bootId: String) {
        val id = announce.get("artifact_id").asText()
        if (artifacts.putIfAbsent(id, announce) == null) {
            appendNode(artifactsFile, announce)
        }
    }

    override fun writeChunk(artifactId: String, offset: Long, bytes: ByteArray) {
        RandomAccessFile(blobFile(artifactId), "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
            raf.fd.sync() // bytes durable before the receipt below
        }
        // Receipt AFTER the bytes: a crash in between leaves no receipt and
        // the chunk is rewritten on retry (bytes -> receipt -> ACK order).
        val receipt = Mop.json.createObjectNode()
        receipt.put("artifact_id", artifactId)
        receipt.put("offset", offset)
        receipt.put("length", bytes.size.toLong())
        durableAppend(chunksFile, Mop.json.writeValueAsString(receipt) + "\n")
        addRange(artifactId, offset, bytes.size.toLong())
    }

    override fun artifactBytes(artifactId: String): ByteArray? {
        val announce = artifacts[artifactId] ?: return null
        val declaredSize = announce.get("size_bytes")?.asLong() ?: return null
        val declaredSha = announce.get("sha256")?.asText() ?: return null
        // 1) received-range coverage must exactly equal [0, size)
        val ranges = coverage[artifactId] ?: return null
        if (ranges.size != 1 || ranges[0].first != 0L ||
            ranges[0].last != declaredSize - 1L
        ) {
            return null
        }
        // 2) length on disk must equal the declared size
        val file = blobFile(artifactId)
        if (!file.exists() || file.length() != declaredSize) return null
        // 3) content must hash to the announced digest
        val bytes = file.readBytes()
        if (sha256Hex(bytes) != declaredSha) return null
        return bytes
    }

    private fun addRange(artifactId: String, offset: Long, length: Long) {
        if (length <= 0L) return
        val ranges = coverage.getOrPut(artifactId) { mutableListOf() }
        ranges.add(offset until (offset + length))
        ranges.sortBy { it.first }
        val merged = mutableListOf<LongRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1L) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        coverage[artifactId] = merged
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    override fun observationsFor(flightId: String): List<ObjectNode> =
        observations.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    override fun eventsFor(flightId: String): List<ObjectNode> =
        events.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    override fun summariesFor(flightId: String): List<ObjectNode> =
        summaries.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    override fun artifactsFor(flightId: String): List<ObjectNode> =
        artifacts.values.filter { it.get("flight_id")?.asText() == flightId }

    override fun bootsFor(flightId: String): List<String> =
        (
            observations.values.filter {
                it.node.get("flight_id")?.asText() == flightId
            }.map { it.bootId } +
                events.values.filter {
                    it.node.get("flight_id")?.asText() == flightId
                }.map { it.bootId } +
                summaries.values.filter {
                    it.node.get("flight_id")?.asText() == flightId
                }.map { it.bootId }
            ).distinct()

    override fun firstLastNs(flightId: String, bootId: String): Pair<Long, Long>? {
        val times = mutableListOf<Long>()
        for (entry in observations.values) {
            if (entry.bootId != bootId) continue
            if (entry.node.get("flight_id")?.asText() != flightId) continue
            entry.node.get("frame_received_monotonic_ns")?.asLong()?.let { times.add(it) }
        }
        for (entry in events.values) {
            if (entry.bootId != bootId) continue
            if (entry.node.get("flight_id")?.asText() != flightId) continue
            entry.node.get("detected_at_edge_monotonic_ns")?.asLong()?.let { times.add(it) }
        }
        if (times.isEmpty()) return null
        return times.min() to times.max()
    }

    // -- helpers ----------------------------------------------------------------

    private fun blobFile(artifactId: String) = File(blobsDir, "$artifactId.bin")

    private fun storeBootNode(
        file: File,
        idField: String,
        node: ObjectNode,
        bootId: String,
        index: LinkedHashMap<String, BootNode>,
    ) {
        val id = node.get(idField).asText()
        if (index.containsKey(id)) return
        index[id] = BootNode(bootId, node)
        val line = Mop.json.createObjectNode()
        line.put("boot_id", bootId)
        line.set<JsonNode>("node", node)
        appendNode(file, line)
    }

    private fun loadBootNodes(
        file: File,
        idField: String,
        index: LinkedHashMap<String, BootNode>,
    ) {
        for (lineNode in readNodes(file)) {
            val node = lineNode.get("node") as? ObjectNode ?: continue
            val bootId = lineNode.get("boot_id")?.asText() ?: ""
            index.putIfAbsent(node.get(idField)?.asText() ?: continue, BootNode(bootId, node))
        }
    }

    private fun readLines(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val text = String(file.readBytes(), Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        val completed = if (text.endsWith("\n")) {
            text
        } else {
            // A trailing fragment without its newline is a partial write
            // from a crash: skip it (records are append-only, so everything
            // before it is intact).
            recoveredPartialWrites += 1
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline < 0) "" else text.substring(0, lastNewline + 1)
        }
        return completed.split("\n").filter { it.isNotBlank() }
    }

    private fun readNodes(file: File): List<ObjectNode> {
        val out = mutableListOf<ObjectNode>()
        for (line in readLines(file)) {
            if (line.isBlank()) continue
            try {
                (Mop.json.readTree(line) as? ObjectNode)?.let { out.add(it) }
            } catch (exc: Exception) {
                // Corrupt complete line: skip, keep recovering the rest.
                recoveredPartialWrites += 1
            }
        }
        return out
    }

    private fun appendNode(file: File, node: ObjectNode) {
        durableAppend(file, Mop.json.writeValueAsString(node) + "\n")
    }

    /** Append with flush + fsync so a crash cannot lose a completed record
     *  (the commit-before-ACK path depends on this). */
    private fun durableAppend(file: File, text: String) {
        FileOutputStream(file, true).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }

    /** Whole-file durable write via a temp file + rename (counters). */
    private fun durableWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp, false).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
