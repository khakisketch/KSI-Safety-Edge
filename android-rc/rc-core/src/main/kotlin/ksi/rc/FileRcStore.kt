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
/**
 * Deterministic fault-injection seam for durable writes. Production default
 * is [RealStoreIo] (real filesystem with flush+fsync); tests substitute a
 * delegate that throws mid-append to prove no index/idempotency claim is
 * published before the bytes are durable. Internal so the public
 * `FileRcStore(root)` / `FileRcStore(root, wallClock)` call shapes never
 * change.
 */
internal interface StoreIo {
    fun appendSync(file: File, bytes: ByteArray)
    fun overwriteSync(file: File, bytes: ByteArray)
    fun writeBlobAt(file: File, offset: Long, bytes: ByteArray)
    fun truncateTo(file: File, length: Long)
}

internal object RealStoreIo : StoreIo {
    override fun appendSync(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
    }

    override fun overwriteSync(file: File, bytes: ByteArray) {
        // Fail-closed atomic replacement: tmp (same dir, fsynced) is
        // atomically moved over the target. If the move fails the OLD file
        // is left untouched and the error is thrown — there is deliberately
        // NO truncate-and-copy fallback, which could destroy the only
        // persisted counter mid-copy on a storage error.
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp, false).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
                // Same-directory rename (atomic on POSIX); a false return
                // means the old file is still in place — report, don't copy.
                if (!tmp.renameTo(file)) {
                    throw IOException(
                        "atomic replace failed for ${file.name}: rename returned false",
                    )
                }
            }
            syncParentDir(file.parentFile)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Fsyncs the directory entry so the rename above survives a power loss.
     * Failures are thrown explicitly (never swallowed as fictitious
     * durability): the replacement itself already succeeded, so the caller
     * sees the error and retries instead of assuming persistence.
     */
    private fun syncParentDir(dir: File) {
        try {
            java.nio.channels.FileChannel.open(
                dir.toPath(),
                java.nio.file.StandardOpenOption.READ,
            ).use { channel ->
                channel.force(true)
            }
        } catch (exc: IOException) {
            throw IOException("directory sync failed for ${dir.path}: ${exc.message}", exc)
        }
    }

    override fun writeBlobAt(file: File, offset: Long, bytes: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
            raf.fd.sync()
        }
    }

    override fun truncateTo(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(length)
            raf.fd.sync()
        }
    }
}

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
    /** Test-only seam: fault injection without changing call shapes. */
    internal constructor(root: Path, wallClock: () -> String, io: StoreIo) :
        this(root, wallClock) {
        this.io = io
    }

    private var io: StoreIo = RealStoreIo

    /** Set when a tail rollback itself cannot be made durable: every later
     *  write fails closed until [repairAfterFailure] runs. */
    @Volatile
    private var writePoison: IOException? = null

    /**
     * App-open capture (fault containment): set ONCE by [enterAppOpen] when
     * the persisted store is unusable (corrupt msg_counter, unusable root).
     * While set, every mutating call fails closed with this error — ids are
     * never handed out, the counter file is never rewritten — while reads
     * keep serving the retained durable facts. The strict public
     * constructors never set this: they stay fail-closed by throwing on use.
     */
    @Volatile
    private var openError: IOException? = null

    /**
     * True while the app must treat KSI persistence as down: an unvalidated
     * open ([openError]) OR a poisoned tail rollback ([writePoison]) — both
     * refuse every write, so the same UI banner applies to both.
     */
    val storageUnavailable: Boolean get() = openError != null || writePoison != null

    /** The captured open failure, else the write poison, else null. */
    val storageOpenError: IOException? get() = openError ?: writePoison

    companion object {
        /**
         * APP-ONLY degraded open (RcApp/RcSession startup path): behaves
         * exactly like the strict constructor — same retained facts, same
         * fail-closed writes — except a corrupt/missing-counter or unusable
         * root is captured as [storageUnavailable]/[storageOpenError]
         * instead of throwing out of the whole app. A missing counter file
         * is still a brand-new store (healthy, starts at zero); a PRESENT
         * but unparsable counter is NEVER reset or overwritten.
         */
        @JvmStatic
        fun openForApp(
            root: Path,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): FileRcStore {
            val store = FileRcStore(root, wallClock)
            store.enterAppOpen()
            return store
        }

        /** Test seam for [openForApp] with injected [StoreIo]. */
        @JvmStatic
        internal fun openForApp(
            root: Path,
            wallClock: () -> String,
            io: StoreIo,
        ): FileRcStore {
            val store = FileRcStore(root, wallClock, io)
            store.enterAppOpen()
            return store
        }
    }

    /**
     * Eager read-only validation for the app open path: inspects (never
     * writes) the persisted counter and root, capturing any failure as
     * [openError]. Must run once, right after construction, before any use.
     */
    private fun enterAppOpen() {
        if (!rootDir.isDirectory) {
            openError = IOException(
                "RC store unavailable: storage root is not a usable " +
                    "directory: ${rootDir.path}; KSI persistence disabled, " +
                    "no ids issued, no writes accepted",
            )
            return
        }
        if (!counterFile.exists()) return
        val failure = try {
            parseCounterStrict(counterFile)
            null
        } catch (exc: IOException) {
            exc
        } catch (exc: Exception) {
            IOException(
                "msg_counter unreadable; refusing to risk id reuse: " +
                    "$counterFile (${exc.message})",
                exc,
            )
        }
        if (failure != null) {
            openError = IOException(
                "RC store unavailable: msg_counter corrupt " +
                    "(must be a nonnegative integer); refusing to risk id " +
                    "reuse — no ids issued, no writes accepted, counter " +
                    "preserved for inspection: ${counterFile.path}",
                failure,
            )
        }
    }
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
    private val responseBatchesFile = File(rootDir, "response_batches.ndjson")
    private val syncedFile = File(rootDir, "synced.ndjson")
    private val flightsFile = File(rootDir, "flights.ndjson")
    private val sitesFile = File(rootDir, "sites.ndjson")
    private val zoneRevisionsFile = File(rootDir, "zone_revisions.ndjson")
    private val missionSnapshotsFile = File(rootDir, "mission_snapshots.ndjson")
    private val bundlesFile = File(rootDir, "bundles.ndjson")
    private val annotationsFile = File(rootDir, "annotations.ndjson")
    private val annotationUploadsFile = File(rootDir, "annotation_uploads.ndjson")
    private val heartbeatsFile = File(rootDir, "heartbeats.ndjson")
    private val commitReceiptsFile = File(rootDir, "commit_receipts.ndjson")
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

    /** Structured response-batch receipts, keyed by (kind, client_batch_id). */
    private val responseBatchReceipts = linkedMapOf<Pair<String, String>, ObjectNode>()
    private val syncedArtifacts = linkedSetOf<String>()

    /** Durable Core commit receipts, keyed by manifest_sha256. */
    private val commitReceipts = linkedMapOf<String, ObjectNode>()

    /** Merged received byte ranges per artifact (0-based, inclusive). */
    private val coverage = linkedMapOf<String, MutableList<LongRange>>()

    /** Count of trailing/corrupt records skipped while loading (diagnostics). */
    var recoveredPartialWrites = 0
        private set

    init {
        rootDir.mkdirs()
        blobsDir.mkdirs()
        // Repair any torn trailing fragment BEFORE loading: otherwise the
        // next append would concatenate a good record onto crash garbage and
        // the combined line would be lost on restart. Only bytes after the
        // last newline are ever truncated; committed records are untouched.
        for (f in managedFiles()) repairTrailingFragment(f)
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
        for (node in readNodes(responseBatchesFile)) {
            val kind = node.get("kind")?.asText() ?: continue
            val clientBatchId = node.get("client_batch_id")?.asText() ?: continue
            if (node.get("server_batch_id")?.takeIf { it.isTextual }?.asText().isNullOrEmpty()) {
                continue
            }
            responseBatchReceipts.putIfAbsent(kind to clientBatchId, node)
        }
        for (line in readLines(syncedFile)) {
            if (line.isNotBlank()) syncedArtifacts.add(line.trim())
        }
        for (node in readNodes(commitReceiptsFile)) {
            node.get("manifest_sha256")?.takeIf { it.isTextual }?.asText()
                ?.let { commitReceipts.putIfAbsent(it, node) }
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
        // Degraded app open: never hand out an id from an unvalidated
        // counter — and never touch the counter file (no reset to 0).
        openError?.let { throw it }
        // Strict, fail-closed parse: the file must contain exactly one
        // nonnegative base-10 integer (optional trailing newline). Anything
        // else — empty, garbage, negative — is an ERROR, never a silent
        // reset to 0 (which would reuse outgoing ids). A missing file is the
        // only "start at zero" case (a brand-new store).
        val current = if (counterFile.exists()) {
            parseCounterStrict(counterFile)
        } else {
            0L
        }
        if (current == Long.MAX_VALUE) {
            throw IOException("msg_counter exhausted; refusing to wrap ids: $counterFile")
        }
        val next = current + 1
        // Persist BEFORE the value is used: a crash between this write and
        // the send skips an id (safe); the value is never handed out twice.
        // Single-writer assumption: one RC process owns the directory.
        durableWrite(counterFile, "$next\n")
        return next
    }

    private fun parseCounterStrict(file: File): Long {
        val raw = try {
            String(file.readBytes(), Charsets.UTF_8)
        } catch (exc: Exception) {
            throw IOException("msg_counter unreadable; refusing to risk id reuse: $file", exc)
        }
        val text = raw.trim()
        val value = if (text.matches(Regex("[0-9]+"))) text.toLongOrNull() else null
        return value
            ?: throw IOException(
                "msg_counter corrupt (must be a nonnegative integer); " +
                    "refusing to risk id reuse: $file",
            )
    }

    @Synchronized
    override fun isProcessed(msgId: String): Boolean = msgId in processed

    @Synchronized
    override fun storeMessage(env: ObjectNode) {
        // Stamp the receipt BEFORE the durable append: the receipt time is
        // when the RC received the envelope, not when the fsync finished.
        // With a slow disk the difference grew to a minute, and the screen
        // then reported "하트비트 지연" for a link that was healthy.
        if (env.get("type")?.asText() == "HEARTBEAT") {
            stampHeartbeat(env)
        }
        appendNode(messagesFile, env)
        indexSeq(env)
        indexBoot(env)
    }

    /** HEARTBEAT receipts are tracked apart from evidence (A8): a live
     *  Manifold process and a live AI are different facts. */
    private fun stampHeartbeat(env: ObjectNode) {
        val flight = env.get("flight_id")?.asText() ?: return
        val utc = wallClock()
        val line = Mop.json.createObjectNode()
        line.put("flight_id", flight)
        line.put("received_wall_utc", utc)
        // Receipt durable before the in-memory freshness claim: a crash in
        // between must read as "no heartbeat", never as a fresh one.
        appendNode(heartbeatsFile, line)
        val current = heartbeatUtc[flight]
        if (current == null || isAfter(utc, current)) heartbeatUtc[flight] = utc
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
        if (msgId in processed) return
        // Durable FIRST: publishing the in-memory mark before the bytes are
        // fsynced would let a retry take the duplicate path and re-ACK a
        // row that never reached disk.
        durableAppend(processedFile, msgId + "\n")
        processed.add(msgId)
    }

    @Synchronized
    override fun processedMsgIds(): Set<String> = processed.toSet()

    @Synchronized
    override fun recordApplication(application: ObjectNode) {
        val id = application.get("application_id").asText()
        if (applications.containsKey(id)) return
        appendNode(appsFile, application)
        applications[id] = application
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
        ensureWritable()
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
        // Blob bytes durable before the receipt below; a torn blob write is
        // rolled back to its pre-write length so a retry starts clean.
        val blob = blobFile(artifactId)
        val blobCommitted = if (blob.exists()) blob.length() else 0L
        try {
            io.writeBlobAt(blob, offset, bytes)
        } catch (exc: Exception) {
            rollbackTail(blob, blobCommitted, exc)
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

    @Synchronized
    override fun isArtifactIntact(artifactId: String): Boolean {
        val announce = artifacts[artifactId]?.node ?: return false
        val declaredSize = announce.get("size_bytes")?.asLong() ?: return false
        val declaredSha = announce.get("sha256")?.asText() ?: return false
        val ranges = coverage[artifactId] ?: return false
        if (ranges.size != 1 || ranges[0].first != 0L ||
            ranges[0].last != declaredSize - 1L
        ) {
            return false
        }
        val file = blobFile(artifactId)
        if (!file.exists() || file.length() != declaredSize) return false
        // Streamed digest: the verified truth without loading the artifact.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return actual == declaredSha
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

    /**
     * Exact indexed receipt for one event: the stored node's own flight
     * id, the delivering boot, and the stamped receipt clock. Null when
     * the event was never stored — never the latest boot, never a guess.
     */
    @Synchronized
    override fun eventReceipt(eventId: String): EventReceipt? {
        val entry = events[eventId] ?: return null
        return EventReceipt(
            eventId = eventId,
            flightId = entry.node.get("flight_id")?.takeIf { it.isTextual }?.asText(),
            bootId = entry.bootId,
            receivedUtc = entry.receivedUtc,
            policyRevisionId = entry.node.get("policy_revision_id")
                ?.takeIf { it.isTextual }?.asText(),
        )
    }

    @Synchronized
    override fun summariesFor(flightId: String): List<ObjectNode> =
        summaries.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun artifactsFor(flightId: String): List<ObjectNode> =
        artifacts.values.map { it.node }
            .filter { it.get("flight_id")?.asText() == flightId }

    @Synchronized
    override fun announceFor(artifactId: String): ObjectNode? =
        artifacts[artifactId]?.node

    // -- Assessments (sealed machine facts, Unit A closure) ------------------
    // See [EvidenceStore.assessmentsFor]: only INTACT blobs parse; the
    // announce index already survives restarts (rebuilt at construction),
    // so these relations do too.

    @Synchronized
    override fun assessmentsFor(flightId: String): List<ObjectNode> =
        artifacts.values.map { it.node }
            .filter {
                it.get("flight_id")?.asText() == flightId &&
                    it.get("media_type")?.asText() == ASSESSMENT_MEDIA_TYPE
            }
            .mapNotNull { announce ->
                val id = announce.get("artifact_id")?.asText()
                    ?: return@mapNotNull null
                // Intact first (coverage + size + sha256 without loading
                // the bytes), then parse: an incomplete or damaged
                // assessment is unreceived, never half-judged.
                if (!isArtifactIntact(id)) return@mapNotNull null
                val bytes = try {
                    artifactBytes(id)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                try {
                    Mop.json.readTree(bytes) as? ObjectNode
                } catch (_: Exception) {
                    null
                }
            }

    @Synchronized
    override fun pendingAssessmentCount(flightId: String): Int =
        artifacts.values.map { it.node }
            .filter {
                it.get("flight_id")?.asText() == flightId &&
                    it.get("media_type")?.asText() == ASSESSMENT_MEDIA_TYPE
            }
            .count { announce ->
                val id = announce.get("artifact_id")?.asText()
                    ?: return@count true
                !isArtifactIntact(id)
            }

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
        val key = Triple(boot, channel, seq)
        if (gaps.containsKey(key)) return
        appendNode(gapsFile, gap)
        gaps[key] = gap
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

    override fun lastHeartbeatUtcAny(): String? {
        var latest: String? = null
        for (value in heartbeatUtc.values) {
            if (latest == null || isAfter(value, latest)) latest = value
        }
        return latest
    }

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
        if (applicationId in uploadedApps) return
        durableAppend(appUploadsFile, applicationId + "\n")
        uploadedApps.add(applicationId)
    }

    @Synchronized
    override fun isApplicationUploaded(applicationId: String): Boolean =
        applicationId in uploadedApps

    @Synchronized
    override fun recordBatchReceipt(kind: String, clientBatchId: String, serverBatchId: String) {
        val key = kind to clientBatchId
        if (batchReceipts.containsKey(key)) return
        val node = Mop.json.createObjectNode()
        node.put("kind", kind)
        node.put("client_batch_id", clientBatchId)
        node.put("server_batch_id", serverBatchId)
        appendNode(batchReceiptsFile, node)
        batchReceipts[key] = serverBatchId
    }

    @Synchronized
    override fun batchReceipt(kind: String, clientBatchId: String): String? =
        batchReceipts[kind to clientBatchId]

    // -- UploadStore: structured operator-response batch receipts ------------

    @Synchronized
    override fun recordResponseBatchReceipt(receipt: ObjectNode) {
        val kind = receipt.get("kind")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("response batch receipt without kind")
        val clientBatchId = receipt.get("client_batch_id")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("response batch receipt without client_batch_id")
        receipt.get("server_batch_id")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("response batch receipt without server_batch_id")
        receipt.get("content_hash")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("response batch receipt without content_hash")
        if (receipt.get("received_wall_utc") == null) {
            receipt.put("received_wall_utc", wallClock())
        }
        val key = kind to clientBatchId
        if (responseBatchReceipts.containsKey(key)) return
        appendNode(responseBatchesFile, receipt)
        responseBatchReceipts[key] = receipt.deepCopy()
    }

    @Synchronized
    override fun responseBatchReceipt(kind: String, clientBatchId: String): ObjectNode? =
        responseBatchReceipts[kind to clientBatchId]?.deepCopy()

    @Synchronized
    override fun responseBatchReceipts(kind: String, flightId: String): List<ObjectNode> =
        responseBatchReceipts.values
            .filter {
                it.get("kind")?.asText() == kind &&
                    it.get("flight_id")?.asText() == flightId
            }
            .map { it.deepCopy() }

    @Synchronized
    override fun markArtifactSynced(artifactId: String) {
        if (artifactId in syncedArtifacts) return
        durableAppend(syncedFile, artifactId + "\n")
        syncedArtifacts.add(artifactId)
    }

    @Synchronized
    override fun isArtifactSynced(artifactId: String): Boolean =
        artifactId in syncedArtifacts

    // -- UploadStore: commit receipts (COMMITTED is a locally stored fact) --

    @Synchronized
    override fun recordCommitReceipt(receipt: ObjectNode) {
        val sha = receipt.get("manifest_sha256")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("commit receipt without manifest_sha256")
        receipt.get("durable_commit_id")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("commit receipt without durable_commit_id")
        if (commitReceipts.containsKey(sha)) return
        appendNode(commitReceiptsFile, receipt)
        commitReceipts[sha] = receipt.deepCopy()
    }

    @Synchronized
    override fun commitReceipt(manifestSha256: String): ObjectNode? =
        commitReceipts[manifestSha256]?.deepCopy()

    @Synchronized
    override fun commitReceiptsFor(flightId: String): List<ObjectNode> =
        commitReceipts.values
            .filter { it.get("flight_id")?.asText() == flightId }
            .map { it.deepCopy() }

    // -- MissionStore: flight records ----------------------------------------

    @Synchronized
    override fun startFlight(record: ObjectNode) {
        val flightId = record.get("flight_id")?.asText() ?: return
        if (flightLog.any { it.get("flight_id")?.asText() == flightId }) return
        appendNode(flightsFile, record)
        flightLog.add(record.deepCopy())
    }

    @Synchronized
    override fun updateFlight(flightId: String, state: String, endedAtUtc: String?) {
        val latest = flight(flightId) ?: return
        val snapshot = latest.deepCopy()
        snapshot.put("state", state)
        if (endedAtUtc != null) snapshot.put("ended_at_utc", endedAtUtc)
        appendNode(flightsFile, snapshot)
        flightLog.add(snapshot)
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
        // Durable before the cache claims it: a failed save must stay
        // invisible so the retry re-attempts the write.
        appendNode(file, node)
        index[id] = node.deepCopy()
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
        if (annotationsById.containsKey(id)) return
        appendNode(annotationsFile, annotation)
        annotationsById[id] = annotation.deepCopy()
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
        if (annotationId in uploadedAnnotations) return
        durableAppend(annotationUploadsFile, annotationId + "\n")
        uploadedAnnotations.add(annotationId)
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
        // A failed append below must leave NO in-memory claim: the same
        // record retried must re-attempt the write, never take an early
        // "already stored" exit that would ACK a row never fsynced.
        if (index.containsKey(id)) return
        val receivedUtc = wallClock()
        val line = Mop.json.createObjectNode()
        line.put("boot_id", bootId)
        line.set<JsonNode>("node", node)
        line.put("received_wall_utc", receivedUtc)
        appendNode(file, line)
        index[id] = BootNode(bootId, node, receivedUtc)
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

    /** Fail closed while a previous rollback could not be made durable. */
    private fun ensureWritable() {
        // Degraded app open first: an unvalidated counter poisons every
        // write, not just the counter itself.
        openError?.let { throw it }
        writePoison?.let {
            throw IOException(
                "RC store writes blocked after an unrecoverable IO failure " +
                    "(${it.message}); inspect the directory, then call " +
                    "repairAfterFailure()",
                it,
            )
        }
    }

    /**
     * Explicit recovery after a poisoned failure: truncates every torn
     * trailing fragment (bytes after the last newline — committed records
     * are never touched), drops stale counter temp files, and re-opens the
     * store for writes. Returns how many files were repaired.
     */
    @Synchronized
    fun repairAfterFailure(): Int {
        // A corrupt counter is deliberately NOT repaired here (the counter
        // file is never fragment-repaired and never reset): recovery
        // requires explicitly fixing or replacing the counter outside the
        // app, never a silent new identity.
        openError?.let { throw it }
        var repaired = 0
        for (file in managedFiles()) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            if (tmp.exists()) tmp.delete()
            if (repairTrailingFragment(file)) repaired += 1
        }
        // The counter is a single-value file, not NDJSON: it is never
        // fragment-repaired (a missing trailing newline may be a valid
        // legacy value), but its stale temp must still be cleaned.
        File(counterFile.parentFile, counterFile.name + ".tmp").delete()
        writePoison = null
        return repaired
    }

    // NOTE: counterFile is deliberately absent. It holds one bare integer,
    // not newline-terminated records — truncating "bytes after the last
    // newline" could destroy a valid legacy value. Corruption there is
    // handled by fail-closed strict parsing in nextMsgCounter instead.
    private fun managedFiles(): List<File> = listOf(
        messagesFile, processedFile, appsFile, obsFile,
        eventsFile, summariesFile, artifactsFile, chunksFile, gapsFile,
        appUploadsFile, batchReceiptsFile, responseBatchesFile, syncedFile,
        flightsFile, sitesFile, zoneRevisionsFile, missionSnapshotsFile,
        bundlesFile, annotationsFile, annotationUploadsFile, heartbeatsFile,
        commitReceiptsFile,
    )

    /**
     * Truncates a torn trailing fragment (a crash mid-append leaves bytes
     * with no terminating newline). Only the uncommitted tail is removed;
     * complete records — even corrupt ones — are preserved for the loader
     * to skip visibly via [recoveredPartialWrites], never silently erased.
     */
    private fun repairTrailingFragment(file: File): Boolean {
        if (!file.exists()) return false
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return false
        if (bytes.last() == '\n'.code.toByte()) return false
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        val committed = if (lastNewline < 0) 0L else (lastNewline + 1).toLong()
        try {
            io.truncateTo(file, committed)
        } catch (exc: Exception) {
            val poison = IOException(
                "RC store ${file.name}: repair truncate failed; writes " +
                    "blocked until repairAfterFailure()",
                exc,
            )
            writePoison = poison
            throw poison
        }
        recoveredPartialWrites += 1
        return true
    }

    /**
     * Rolls back only the uncommitted tail written by the failed append.
     * When the rollback itself cannot be made durable the store is poisoned
     * (fail-closed for future writes) instead of silently succeeding.
     */
    private fun rollbackTail(file: File, committed: Long, cause: Exception): Nothing {
        var tailRemoved = false
        try {
            val current = if (file.exists()) file.length() else 0L
            if (current != committed) {
                io.truncateTo(file, committed)
                tailRemoved = true
            }
            if (file.exists() && file.length() != committed) {
                throw IOException("tail length ${file.length()} != committed $committed")
            }
        } catch (rollbackExc: Exception) {
            val poison = IOException(
                "RC store ${file.name}: append failed (${cause.message}) and " +
                    "the uncommitted tail could not be rolled back " +
                    "(${rollbackExc.message}); writes blocked until " +
                    "repairAfterFailure()",
                rollbackExc,
            )
            writePoison = poison
            throw poison
        }
        if (tailRemoved) recoveredPartialWrites += 1
        if (cause is IOException) throw cause
        throw IOException("RC store append to ${file.name} failed: ${cause.message}", cause)
    }

    /** Append with flush + fsync so a crash cannot lose a completed record
     *  (the commit-before-ACK path depends on this). A torn tail left by a
     *  crash after this store was opened is repaired first, so a good
     *  record can never concatenate onto crash garbage. */
    private fun durableAppend(file: File, text: String) {
        ensureWritable()
        repairTrailingFragment(file)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val committed = if (file.exists()) file.length() else 0L
        try {
            io.appendSync(file, bytes)
        } catch (exc: Exception) {
            rollbackTail(file, committed, exc)
        }
    }

    /** Whole-file durable write via a temp file + rename (counters). */
    private fun durableWrite(file: File, text: String) {
        ensureWritable()
        try {
            io.overwriteSync(file, text.toByteArray(Charsets.UTF_8))
        } catch (exc: Exception) {
            File(file.parentFile, file.name + ".tmp").delete()
            if (exc is IOException) throw exc
            throw IOException(
                "RC store overwrite of ${file.name} failed: ${exc.message}",
                exc,
            )
        }
    }
}
