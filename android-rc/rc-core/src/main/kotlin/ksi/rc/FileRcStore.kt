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
class FileRcStore(
    root: Path,
    /**
     * RC receipt clock: the wall-clock instant recorded alongside every
     * received record. This is the ONLY UTC time source in the store —
     * Edge monotonic ns is preserved as-is inside the records and never
     * converted (no verified cross-device clock mapping exists before H1).
     */
    private val wallClock: () -> String = { java.time.Instant.now().toString() },
) : RcFullStore {
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
    private val gapsFile = File(rootDir, "gaps.ndjson")
    private val appUploadsFile = File(rootDir, "app_uploads.ndjson")
    private val batchReceiptsFile = File(rootDir, "batch_receipts.ndjson")
    private val syncedFile = File(rootDir, "synced.ndjson")
    private val flightsFile = File(rootDir, "flights.ndjson")
    private val sitesFile = File(rootDir, "sites.ndjson")
    private val zoneRevisionsFile = File(rootDir, "zone_revisions.ndjson")
    private val missionSnapshotsFile = File(rootDir, "mission_snapshots.ndjson")
    private val bundlesFile = File(rootDir, "bundles.ndjson")
    private val annotationsFile = File(rootDir, "annotations.ndjson")
    private val annotationUploadsFile = File(rootDir, "annotation_uploads.ndjson")
    private val heartbeatsFile = File(rootDir, "heartbeats.ndjson")
    private val blobsDir = File(rootDir, "blobs")

    private data class BootNode(
        val bootId: String,
        val node: ObjectNode,
        /** RC receipt wall-clock (ISO-8601); null only for legacy files. */
        val receivedUtc: String? = null,
    )

    private val processed = linkedSetOf<String>()
    private val applications = linkedMapOf<String, ObjectNode>()
    private val observations = linkedMapOf<String, BootNode>()
    private val events = linkedMapOf<String, BootNode>()
    private val summaries = linkedMapOf<String, BootNode>()
    private val artifacts = linkedMapOf<String, BootNode>()

    /** Flight record snapshots in log order (latest per id wins). */
    private val flightLog = mutableListOf<ObjectNode>()

    private val sitesById = linkedMapOf<String, ObjectNode>()
    private val zoneRevisionsById = linkedMapOf<String, ObjectNode>()
    private val snapshotsByRevision = linkedMapOf<String, ObjectNode>()
    private val bundlesById = linkedMapOf<String, ObjectNode>()
    private val annotationsById = linkedMapOf<String, ObjectNode>()

    /** Latest HEARTBEAT receipt per flight (A8 channel freshness). */
    private val heartbeatUtc = linkedMapOf<String, String>()

    /** Annotation ids the server durably accepted. */
    private val uploadedAnnotations = linkedSetOf<String>()

    /** Received data-plane seqs per (edge_boot_id, channel); rebuilt from
     *  messages.ndjson at construction (restart contract). */
    private val seqs = linkedMapOf<Pair<String, String>, MutableSet<Long>>()

    /** Boots seen in ANY inbound message per flight (harness parity:
     *  `DISTINCT edge_boot_id FROM messages`). Needed so a boot whose only
     *  evidence is a HEARTBEAT gap declaration still gets a manifest. */
    private val messageBoots = linkedMapOf<String, MutableSet<String>>()

    /** Declared gaps keyed by (edge_boot_id, channel, seq). */
    private val gaps = linkedMapOf<Triple<String, String, Long>, ObjectNode>()

    private val uploadedApps = linkedSetOf<String>()
    private val batchReceipts = linkedMapOf<Pair<String, String>, String>()
    private val syncedArtifacts = linkedSetOf<String>()

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
        loadBootNodes(artifactsFile, "artifact_id", artifacts)
        for (node in readNodes(chunksFile)) {
            val artifactId = node.get("artifact_id")?.asText() ?: continue
            val offset = node.get("offset")?.asLong() ?: continue
            val length = node.get("length")?.asLong() ?: continue
            addRange(artifactId, offset, length)
        }
        for (node in readNodes(gapsFile)) {
            val boot = node.get("edge_boot_id")?.asText() ?: continue
            val channel = node.get("channel")?.asText() ?: continue
            val seq = node.get("seq")?.asLong() ?: continue
            gaps.putIfAbsent(Triple(boot, channel, seq), node)
        }
        for (line in readLines(appUploadsFile)) {
            if (line.isNotBlank()) uploadedApps.add(line.trim())
        }
        for (node in readNodes(batchReceiptsFile)) {
            val kind = node.get("kind")?.asText() ?: continue
            val clientBatchId = node.get("client_batch_id")?.asText() ?: continue
            val serverBatchId = node.get("server_batch_id")?.asText() ?: continue
            batchReceipts.putIfAbsent(kind to clientBatchId, serverBatchId)
        }
        for (line in readLines(syncedFile)) {
            if (line.isNotBlank()) syncedArtifacts.add(line.trim())
        }
        for (node in readNodes(flightsFile)) {
            if (node.get("flight_id")?.asText().isNullOrEmpty()) continue
            flightLog.add(node)
        }
        for (node in readNodes(sitesFile)) {
            node.get("site_id")?.asText()?.let { sitesById.putIfAbsent(it, node) }
        }
        for (node in readNodes(zoneRevisionsFile)) {
            node.get("revision_id")?.asText()?.let { zoneRevisionsById.putIfAbsent(it, node) }
        }
        for (node in readNodes(missionSnapshotsFile)) {
            node.get("mission_revision_id")?.asText()
                ?.let { snapshotsByRevision.putIfAbsent(it, node) }
        }
        for (node in readNodes(bundlesFile)) {
            node.get("bundle_id")?.asText()?.let { bundlesById.putIfAbsent(it, node) }
        }
        for (node in readNodes(annotationsFile)) {
            node.get("annotation_id")?.asText()
                ?.let { annotationsById.putIfAbsent(it, node) }
        }
        for (node in readNodes(heartbeatsFile)) {
            val flight = node.get("flight_id")?.asText() ?: continue
            val utc = node.get("received_wall_utc")?.asText() ?: continue
            val current = heartbeatUtc[flight]
            if (current == null || isAfter(utc, current)) heartbeatUtc[flight] = utc
        }
        for (line in readLines(annotationUploadsFile)) {
            if (line.isNotBlank()) uploadedAnnotations.add(line.trim())
        }
        // Rebuild the seq/boot indexes from the durable envelopes (a restart
        // must not lose the bookkeeping the manifest depends on).
        for (node in readNodes(messagesFile)) {
            indexSeq(node)
            indexBoot(node)
        }
    }

    /** Index an inbound data-plane envelope's seq (no-op for others). */
    private fun indexSeq(env: ObjectNode) {
        val channel = env.get("channel")?.asText() ?: return
        if (channel !in Mop.DATA_CHANNELS) return
        val seq = env.get("seq")?.takeIf { it.isNumber }?.asLong() ?: return
        val boot = env.get("edge_boot_id")?.takeIf { !it.isNull }?.asText() ?: return
        seqs.getOrPut(boot to channel) { linkedSetOf() }.add(seq)
    }

    /** Index the (flight, boot) pair of any inbound envelope. */
    private fun indexBoot(env: ObjectNode) {
        val flight = env.get("flight_id")?.asText() ?: return
        val boot = env.get("edge_boot_id")?.takeIf { !it.isNull }?.asText() ?: return
        messageBoots.getOrPut(flight) { linkedSetOf() }.add(boot)
    }

    // -- RcStore -------------------------------------------------------------

    @Synchronized
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

    @Synchronized
    override fun isProcessed(msgId: String): Boolean = msgId in processed

    @Synchronized
    override fun storeMessage(env: ObjectNode) {
        appendNode(messagesFile, env)
        indexSeq(env)
        indexBoot(env)
        if (env.get("type")?.asText() == "HEARTBEAT") {
            stampHeartbeat(env)
        }
    }

    /** HEARTBEAT receipts are tracked apart from evidence (A8): a live
     *  Manifold process and a live AI are different facts. */
    private fun stampHeartbeat(env: ObjectNode) {
        val flight = env.get("flight_id")?.asText() ?: return
        val utc = wallClock()
        val current = heartbeatUtc[flight]
        if (current == null || isAfter(utc, current)) heartbeatUtc[flight] = utc
        val line = Mop.json.createObjectNode()
        line.put("flight_id", flight)
        line.put("received_wall_utc", utc)
        appendNode(heartbeatsFile, line)
    }

    private fun isAfter(candidate: String, current: String): Boolean {
        val a = runCatching { java.time.Instant.parse(candidate) }.getOrNull()
            ?: return false
        val b = runCatching { java.time.Instant.parse(current) }.getOrNull()
            ?: return true
        return a.isAfter(b)
    }

    @Synchronized
    override fun markProcessed(msgId: String) {
        if (processed.add(msgId)) {
            durableAppend(processedFile, msgId + "\n")
        }
    }

    @Synchronized
    override fun processedMsgIds(): Set<String> = processed.toSet()

    @Synchronized
    override fun recordApplication(application: ObjectNode) {
        val id = application.get("application_id").asText()
        if (applications.putIfAbsent(id, application) == null) {
            appendNode(appsFile, application)
        }
    }

    @Synchronized
    override fun applicationsFor(flightId: String): List<ObjectNode> =
        applications.values.filter { it.get("flight_id")?.asText() == flightId }

    // -- EvidenceStore ---------------------------------------------------------

    @Synchronized
    override fun storeObservation(observation: ObjectNode, bootId: String) {
        storeBootNode(obsFile, "observation_id", observation, bootId, observations)
    }

    @Synchronized
    override fun storeEvent(event: ObjectNode, bootId: String) {
        storeBootNode(eventsFile, "event_id", event, bootId, events)
    }

    @Synchronized
    override fun storeFlightSummary(summary: ObjectNode, bootId: String) {
        storeBootNode(summariesFile, "summary_id", summary, bootId, summaries)
    }

    @Synchronized
    override fun announceArtifact(announce: ObjectNode, bootId: String) {
        storeBootNode(artifactsFile, "artifact_id", announce, bootId, artifacts)
    }

    @Synchronized
    override fun writeChunk(artifactId: String, offset: Long, bytes: ByteArray) {
        if (offset < 0L) {
            throw IllegalArgumentException("chunk offset must be >= 0")
        }
        val announce = artifacts[artifactId]?.node
            ?: throw IllegalArgumentException(
                "chunk for unannounced artifact $artifactId (announce first)",
            )
        val declaredSize = announce.get("size_bytes")?.asLong()
            ?: throw IllegalArgumentException("announce without size_bytes")
        // Overflow-safe range check: without it an offset near Long.MAX_VALUE
        // would wrap the sum and poison the artifact's coverage forever.
        if (offset > declaredSize || bytes.size.toLong() > declaredSize - offset) {
            throw IllegalArgumentException(
                "chunk range exceeds the announced size of $artifactId",
            )
        }
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

    @Synchronized
    override fun isArtifactComplete(artifactId: String): Boolean {
        val announce = artifacts[artifactId]?.node ?: return false
        val declaredSize = announce.get("size_bytes")?.asLong() ?: return false
        val ranges = coverage[artifactId] ?: return false
        if (ranges.size != 1 || ranges[0].first != 0L ||
            ranges[0].last != declaredSize - 1L
        ) {
            return false
        }
        val file = blobFile(artifactId)
        return file.exists() && file.length() == declaredSize
    }

    @Synchronized
    override fun artifactBytes(artifactId: String): ByteArray? {
        val announce = artifacts[artifactId]?.node ?: return null
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

    @Synchronized
    override fun observationsFor(flightId: String): List<ObjectNode> =
        observations.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun eventsFor(flightId: String): List<ObjectNode> =
        events.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun summariesFor(flightId: String): List<ObjectNode> =
        summaries.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun artifactsFor(flightId: String): List<ObjectNode> =
        artifacts.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    // -- UploadStore: boot-scoped reads --------------------------------------

    @Synchronized
    override fun observationsFor(flightId: String, bootId: String): List<ObjectNode> =
        observations.values.filter { it.bootId == bootId }
            .map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun eventsFor(flightId: String, bootId: String): List<ObjectNode> =
        events.values.filter { it.bootId == bootId }
            .map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun artifactsFor(flightId: String, bootId: String): List<ObjectNode> =
        artifacts.values.filter { it.bootId == bootId }
            .map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun receivedSeqs(bootId: String, channel: String): Set<Long> =
        seqs[bootId to channel]?.toSet() ?: emptySet()

    @Synchronized
    override fun recordGap(gap: ObjectNode) {
        val boot = gap.get("edge_boot_id")?.asText() ?: return
        val channel = gap.get("channel")?.asText() ?: return
        val seq = gap.get("seq")?.asLong() ?: return
        if (gap.get("received_wall_utc") == null) {
            gap.put("received_wall_utc", wallClock())
        }
        if (gaps.putIfAbsent(Triple(boot, channel, seq), gap) == null) {
            appendNode(gapsFile, gap)
        }
    }

    @Synchronized
    override fun receivedFirstLastUtc(flightId: String, bootId: String): Pair<String, String>? {
        val times = mutableListOf<String>()
        fun collect(entries: Collection<BootNode>) {
            for (entry in entries) {
                if (entry.bootId != bootId) continue
                if (entry.node.get("flight_id")?.asText() != flightId) continue
                entry.receivedUtc?.let { times.add(it) }
            }
        }
        collect(observations.values)
        collect(events.values)
        collect(summaries.values)
        collect(artifacts.values)
        for ((key, node) in gaps) {
            if (key.first != bootId) continue
            if (node.get("flight_id")?.asText() != flightId) continue
            node.get("received_wall_utc")?.takeIf { it.isTextual }?.asText()
                ?.let { times.add(it) }
        }
        val instants = times.mapNotNull {
            runCatching { java.time.Instant.parse(it) }.getOrNull()
        }
        if (instants.isEmpty()) return null
        // Normalize through Instant so mixed precisions compare correctly.
        return instants.min().toString() to instants.max().toString()
    }

    @Synchronized
    override fun lastReceivedUtc(flightId: String): String? {
        var latest: java.time.Instant? = null
        fun scan(entries: Collection<BootNode>) {
            for (entry in entries) {
                if (entry.node.get("flight_id")?.asText() != flightId) continue
                val instant = entry.receivedUtc
                    ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    ?: continue
                if (latest == null || instant.isAfter(latest)) latest = instant
            }
        }
        scan(observations.values)
        scan(events.values)
        scan(summaries.values)
        scan(artifacts.values)
        for ((_, node) in gaps) {
            if (node.get("flight_id")?.asText() != flightId) continue
            val instant = node.get("received_wall_utc")?.takeIf { it.isTextual }?.asText()
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: continue
            if (latest == null || instant.isAfter(latest)) latest = instant
        }
        return latest?.toString()
    }

    @Synchronized
    override fun lastHeartbeatUtc(flightId: String): String? = heartbeatUtc[flightId]

    @Synchronized
    override fun lastObservationUtc(flightId: String): String? =
        latestSemanticUtc(observations.values, flightId)

    @Synchronized
    override fun lastEventUtc(flightId: String): String? =
        latestSemanticUtc(events.values, flightId)

    private fun latestSemanticUtc(
        entries: Collection<BootNode>,
        flightId: String,
    ): String? {
        var latest: java.time.Instant? = null
        for (entry in entries) {
            if (entry.node.get("flight_id")?.asText() != flightId) continue
            val instant = entry.receivedUtc
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: continue
            if (latest == null || instant.isAfter(latest)) latest = instant
        }
        return latest?.toString()
    }

    @Synchronized
    override fun gapsForBoot(bootId: String): List<ObjectNode> =
        gaps.entries.filter { it.key.first == bootId }
            .sortedWith(compareBy({ it.key.second }, { it.key.third }))
            .map { it.value }

    // -- UploadStore: receipts ----------------------------------------------

    @Synchronized
    override fun markApplicationUploaded(applicationId: String) {
        if (uploadedApps.add(applicationId)) {
            durableAppend(appUploadsFile, applicationId + "\n")
        }
    }

    @Synchronized
    override fun isApplicationUploaded(applicationId: String): Boolean =
        applicationId in uploadedApps

    @Synchronized
    override fun recordBatchReceipt(kind: String, clientBatchId: String, serverBatchId: String) {
        if (batchReceipts.putIfAbsent(kind to clientBatchId, serverBatchId) == null) {
            val node = Mop.json.createObjectNode()
            node.put("kind", kind)
            node.put("client_batch_id", clientBatchId)
            node.put("server_batch_id", serverBatchId)
            appendNode(batchReceiptsFile, node)
        }
    }

    @Synchronized
    override fun batchReceipt(kind: String, clientBatchId: String): String? =
        batchReceipts[kind to clientBatchId]

    @Synchronized
    override fun markArtifactSynced(artifactId: String) {
        if (syncedArtifacts.add(artifactId)) {
            durableAppend(syncedFile, artifactId + "\n")
        }
    }

    @Synchronized
    override fun isArtifactSynced(artifactId: String): Boolean =
        artifactId in syncedArtifacts

    // -- MissionStore: flight records ----------------------------------------

    @Synchronized
    override fun startFlight(record: ObjectNode) {
        val flightId = record.get("flight_id")?.asText() ?: return
        if (flightLog.any { it.get("flight_id")?.asText() == flightId }) return
        flightLog.add(record.deepCopy())
        appendNode(flightsFile, record)
    }

    @Synchronized
    override fun updateFlight(flightId: String, state: String, endedAtUtc: String?) {
        val latest = flight(flightId) ?: return
        val snapshot = latest.deepCopy()
        snapshot.put("state", state)
        if (endedAtUtc != null) snapshot.put("ended_at_utc", endedAtUtc)
        flightLog.add(snapshot)
        appendNode(flightsFile, snapshot)
    }

    @Synchronized
    override fun flight(flightId: String): ObjectNode? =
        flightLog.lastOrNull { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun flights(): List<ObjectNode> {
        val latest = linkedMapOf<String, ObjectNode>()
        for (node in flightLog) {
            latest[node.get("flight_id")?.asText() ?: continue] = node
        }
        return latest.values.toList()
    }

    @Synchronized
    override fun activeFlight(): ObjectNode? =
        flights().lastOrNull { it.get("state")?.asText() != "ENDED" }

    // -- MissionStore: verified cache (immutable per revision) -----------------

    private fun sameContent(a: ObjectNode, b: ObjectNode): Boolean =
        CanonicalJson.canonicalBytes(a).contentEquals(CanonicalJson.canonicalBytes(b))

    private fun saveImmutable(
        node: ObjectNode,
        idField: String,
        index: LinkedHashMap<String, ObjectNode>,
        file: File,
        kind: String,
    ) {
        val id = node.get(idField)?.asText() ?: return
        val existing = index[id]
        if (existing != null) {
            check(sameContent(existing, node)) {
                "integrity: $kind $id re-saved with different content"
            }
            return
        }
        index[id] = node.deepCopy()
        appendNode(file, node)
    }

    @Synchronized
    override fun saveSite(site: ObjectNode) {
        saveImmutable(site, "site_id", sitesById, sitesFile, "site")
    }

    @Synchronized
    override fun site(siteId: String): ObjectNode? = sitesById[siteId]

    @Synchronized
    override fun sites(): List<ObjectNode> = sitesById.values.toList()

    @Synchronized
    override fun saveZoneRevision(revision: ObjectNode) {
        saveImmutable(
            revision, "revision_id", zoneRevisionsById, zoneRevisionsFile,
            "zone revision",
        )
    }

    @Synchronized
    override fun zoneRevision(revisionId: String): ObjectNode? =
        zoneRevisionsById[revisionId]

    @Synchronized
    override fun zoneRevisions(siteId: String): List<ObjectNode> =
        zoneRevisionsById.values.filter { it.get("site_id")?.asText() == siteId }

    @Synchronized
    override fun saveMissionSnapshot(snapshot: ObjectNode) {
        saveImmutable(
            snapshot, "mission_revision_id", snapshotsByRevision,
            missionSnapshotsFile, "mission snapshot",
        )
    }

    @Synchronized
    override fun missionSnapshot(missionRevisionId: String): ObjectNode? =
        snapshotsByRevision[missionRevisionId]

    @Synchronized
    override fun missionSnapshots(): List<ObjectNode> =
        snapshotsByRevision.values.toList()

    @Synchronized
    override fun saveMissionBundle(bundle: ObjectNode) {
        saveImmutable(bundle, "bundle_id", bundlesById, bundlesFile, "bundle")
    }

    @Synchronized
    override fun missionBundle(bundleId: String): ObjectNode? = bundlesById[bundleId]

    @Synchronized
    override fun missionBundles(): List<ObjectNode> = bundlesById.values.toList()

    // -- UploadStore: operator annotations --------------------------------------

    @Synchronized
    override fun queueAnnotation(annotation: ObjectNode) {
        val id = annotation.get("annotation_id")?.asText() ?: return
        if (annotationsById.putIfAbsent(id, annotation.deepCopy()) == null) {
            appendNode(annotationsFile, annotation)
        }
    }

    @Synchronized
    override fun annotationsFor(eventId: String): List<ObjectNode> =
        annotationsById.values.filter { it.get("event_id")?.asText() == eventId }

    @Synchronized
    override fun pendingAnnotations(): List<ObjectNode> =
        annotationsById.values.filter {
            it.get("annotation_id")?.asText() !in uploadedAnnotations
        }

    @Synchronized
    override fun markAnnotationUploaded(annotationId: String) {
        if (uploadedAnnotations.add(annotationId)) {
            durableAppend(annotationUploadsFile, annotationId + "\n")
        }
    }

    @Synchronized
    override fun isAnnotationUploaded(annotationId: String): Boolean =
        annotationId in uploadedAnnotations

    @Synchronized
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
                }.map { it.bootId } +
                artifacts.values.filter {
                    it.node.get("flight_id")?.asText() == flightId
                }.map { it.bootId } +
                // Any boot that ever sent a message (incl. a HEARTBEAT-only
                // boot whose gap declarations must still reach the manifest).
                messageBoots[flightId].orEmpty()
            ).distinct()

    @Synchronized
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
        val receivedUtc = wallClock()
        index[id] = BootNode(bootId, node, receivedUtc)
        val line = Mop.json.createObjectNode()
        line.put("boot_id", bootId)
        line.set<JsonNode>("node", node)
        line.put("received_wall_utc", receivedUtc)
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
            val receivedUtc = lineNode.get("received_wall_utc")
                ?.takeIf { it.isTextual }?.asText()
            index.putIfAbsent(
                node.get(idField)?.asText() ?: continue,
                BootNode(bootId, node, receivedUtc),
            )
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
