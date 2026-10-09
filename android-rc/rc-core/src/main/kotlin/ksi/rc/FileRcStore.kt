package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
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
    private val appsFile = File(rootDir, "applications.ndjson")
    private val obsFile = File(rootDir, "observations.ndjson")
    private val eventsFile = File(rootDir, "events.ndjson")
    private val summariesFile = File(rootDir, "summaries.ndjson")
    private val artifactsFile = File(rootDir, "artifacts.ndjson")
    private val blobsDir = File(rootDir, "blobs")

    private data class BootNode(val bootId: String, val node: ObjectNode)

    private val processed = linkedSetOf<String>()
    private val applications = linkedMapOf<String, ObjectNode>()
    private val observations = linkedMapOf<String, BootNode>()
    private val events = linkedMapOf<String, BootNode>()
    private val summaries = linkedMapOf<String, BootNode>()
    private val artifacts = linkedMapOf<String, ObjectNode>()

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
    }

    // -- RcStore -------------------------------------------------------------

    override fun isProcessed(msgId: String): Boolean = msgId in processed

    override fun storeMessage(env: ObjectNode) {
        appendNode(messagesFile, env)
    }

    override fun markProcessed(msgId: String) {
        if (processed.add(msgId)) {
            processedFile.appendText(msgId + "\n")
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
        }
    }

    override fun artifactBytes(artifactId: String): ByteArray? {
        val file = blobFile(artifactId)
        if (!file.exists()) return null
        val declared = artifacts[artifactId]?.get("size_bytes")?.asLong() ?: return null
        if (file.length() < declared) return null // incomplete
        return file.readBytes()
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

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines() else emptyList()

    private fun readNodes(file: File): List<ObjectNode> =
        readLines(file).filter { it.isNotBlank() }
            .map { Mop.json.readTree(it) as ObjectNode }

    private fun appendNode(file: File, node: ObjectNode) {
        file.appendText(Mop.json.writeValueAsString(node) + "\n")
    }
}
