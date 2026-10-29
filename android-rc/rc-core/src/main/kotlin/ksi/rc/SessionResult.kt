package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Session outcome axis (Unit B of the 2026-10-21 edge-operation contract
 * §6). This is SEPARATE from preservation: `COMMITTED` (Core durably
 * stored the required records) is not an input here, so a fully preserved
 * failed session still classifies FAILED/INTERRUPTED, and L1 success
 * counting must use SUCCESS only.
 */
enum class SessionResult {
    SUCCESS,
    FAILED,
    INTERRUPTED,
    UNKNOWN,
}

/**
 * Local facts the verdict is derived from. Every field is nullable:
 * absent means "not observed", never false/0. The classifier never
 * invents a verdict — missing basis yields [SessionResult.UNKNOWN].
 *
 * Field meanings (all RC-local observations, no Core estimation):
 * - [configApplied]: the applied bundle digest was reported installed in
 *   the engine (`config_applied`).
 * - [runtimeConfirmed]: the Edge reported RUNNING/DEGRADED for this
 *   session. Null while the session is still pending; false ONLY when the
 *   Edge positively reported non-execution where execution was expected.
 * - [frameProcessingObserved]: a positive processed-frame count was
 *   reported. Null when the Edge never reported frame counts.
 * - [observationCount]: locally stored observations for the session boot,
 *   including 0. Zero RECEIVED observation records is not a clean-negative
 *   detection result: even an inference tick with zero detections emits an
 *   Observation. Pending/missing delivery must never qualify as SUCCESS.
 *   Null when the record basis itself is missing.
 * - [videoEvidencePresent]: complete + hash-verified local video bytes
 *   exist for the session boot. False = positively missing; null = unchecked.
 * - [declaredGaps]: the Edge declared unrecoverable gaps for the boot.
 *   True = known-unrecoverable loss (can never become SUCCESS).
 * - [linkInterrupted]: the drone link dropped while the session was
 *   open/pending. Mid-session meaning only: set it false only when the
 *   link provably stayed up for the whole session.
 * - [runtimeEpochChanged]: the reported `runtime_epoch` changed mid-session
 *   (Edge process restart). Set it only for changes BEFORE the session
 *   closed; a reboot after a confirmed STOP is a new generation, not an
 *   interruption of the closed session.
 * - [stopConfirmed]: the Edge reported STOPPED after a STOP request (or
 *   the session otherwise verifiably closed). False/null while open.
 */
data class SessionFacts(
    val configApplied: Boolean? = null,
    val runtimeConfirmed: Boolean? = null,
    val frameProcessingObserved: Boolean? = null,
    val observationCount: Int? = null,
    val videoEvidencePresent: Boolean? = null,
    val declaredGaps: Boolean? = null,
    val linkInterrupted: Boolean? = null,
    val runtimeEpochChanged: Boolean? = null,
    val stopConfirmed: Boolean? = null,
    /** Sticky, positively observed RC recorder failure; null is unmeasured. */
    val recordingFailure: Boolean? = null,
    /** Positive Edge journal/spool loss, independent of RC video capture. */
    val evidenceFailure: Boolean? = null,
)

/**
 * Pure session-result classifier (facts in, verdict out). Decision table,
 * in order:
 * 1. No basis at all (config, runtime and processing all unknown) ->
 *    UNKNOWN. Never fabricate.
 * 2. Config positively not applied -> FAILED (nothing downstream is valid).
 * 3. Link drop or epoch change mid-session while not cleanly closed ->
 *    INTERRUPTED (the session was cut; the Edge may still observe locally,
 *    but the RC cannot confirm it).
 * 4. Closed session (STOP confirmed):
 *    - execution or processing positively unconfirmed (the Edge reported
 *      states/counts, but never RUNNING/DEGRADED or never a positive
 *      frame count) -> FAILED;
 *    - the Edge never reported any execution basis at all (legacy builds
 *      without `runtime_state`/frame counts) -> UNKNOWN, never FAILED —
 *      absence of evidence is not evidence of failure;
 *    - partial basis (e.g. RUNNING seen but frame counts never reported)
 *      -> UNKNOWN;
 *    - declared unrecoverable gaps, positively missing video evidence, or
 *      confirmed execution with no observation records at all -> FAILED
 *      (required records missing);
 *    - any remaining basis missing -> UNKNOWN;
 *    - config applied, execution and processing confirmed, records and
 *      video present, no gaps, no cuts -> SUCCESS.
 * 5. Still-open session with declared unrecoverable gaps -> FAILED (the
 *    loss is already known-unrecoverable); otherwise UNKNOWN — a live
 *    session is never pre-judged SUCCESS/FAILED.
 *
 * COMMITTED (preservation) is deliberately NOT an input: preservation
 * success must never read as session success.
 */
object SessionResultClassifier {
    fun classify(facts: SessionFacts): SessionResult {
        if (facts.recordingFailure == true) return SessionResult.FAILED
        if (facts.evidenceFailure == true) return SessionResult.FAILED
        if (facts.configApplied == null &&
            facts.runtimeConfirmed == null &&
            facts.frameProcessingObserved == null
        ) {
            return SessionResult.UNKNOWN
        }
        if (facts.configApplied == false) return SessionResult.FAILED
        val cut = facts.runtimeEpochChanged == true || facts.linkInterrupted == true
        if (cut && facts.stopConfirmed != true) return SessionResult.INTERRUPTED
        if (facts.stopConfirmed == true) {
            if (facts.runtimeConfirmed == false || facts.frameProcessingObserved == false) {
                return SessionResult.FAILED
            }
            if (facts.runtimeConfirmed == null && facts.frameProcessingObserved == null) {
                return SessionResult.UNKNOWN
            }
            if (facts.runtimeConfirmed != true || facts.frameProcessingObserved != true) {
                return SessionResult.UNKNOWN
            }
            if (facts.configApplied != true) {
                return if (facts.configApplied == false) SessionResult.FAILED
                else SessionResult.UNKNOWN
            }
            if (facts.declaredGaps == true) return SessionResult.FAILED
            if (facts.videoEvidencePresent == false) return SessionResult.FAILED
            if (facts.observationCount == null) return SessionResult.FAILED
            if (facts.observationCount == 0) return SessionResult.UNKNOWN
            if (facts.videoEvidencePresent != true || facts.declaredGaps != false ||
                facts.linkInterrupted != false || facts.runtimeEpochChanged != false
            ) {
                return SessionResult.UNKNOWN
            }
            return SessionResult.SUCCESS
        }
        if (facts.declaredGaps == true) return SessionResult.FAILED
        return SessionResult.UNKNOWN
    }
}

/**
 * One locally decided verdict for a session, stored next to the session so
 * it can ship as a FLIGHT_LOG artifact. [factsJson] is the serialized
 * [SessionFacts] the verdict was derived from (auditability: re-derive,
 * never trust blindly).
 */
data class SessionResultRecord(
    val sessionId: String,
    val flightId: String,
    val bootId: String,
    val runtimeEpoch: String?,
    val result: SessionResult,
    val factsJson: String,
    val decidedAtUtc: String,
)

/**
 * Append-only local store for session verdicts (house pattern, same as
 * [OperatorActionLog]: NDJSON append + fsync, latest record per session
 * wins, corrupt lines skipped with a counter). Nothing here deletes or
 * rewrites history: every decided verdict is durable, the newest one for
 * a session is the operative one.
 */
class SessionResultLog private constructor(
    root: Path,
    private val wallClock: () -> String,
    private val journal: DurableJsonLinesJournal,
) {
    constructor(
        root: Path,
        wallClock: () -> String = { java.time.Instant.now().toString() },
    ) : this(root, wallClock, DurableJsonLinesJournal(root.toFile(), FILE_NAME))

    /** Test-only seam: fault injection without changing call shapes. */
    internal constructor(
        root: Path,
        wallClock: () -> String,
        io: JournalIo,
    ) : this(root, wallClock, DurableJsonLinesJournal(root.toFile(), FILE_NAME, io))

    private val bySession = linkedMapOf<String, ObjectNode>()

    /** Complete-but-corrupt lines skipped while loading (diagnostics). */
    private var corruptSkipped = 0

    /** Torn tails repaired plus corrupt lines skipped while loading. */
    val recoveredPartialWrites: Int
        get() = journal.recoveredPartialWrites + corruptSkipped

    init {
        for (line in journal.readCompletedLines()) {
            val node = try {
                Mop.json.readTree(line) as? ObjectNode
            } catch (_: Exception) {
                null
            }
            if (node == null) {
                corruptSkipped += 1
                continue
            }
            val id = node.get("session_id")?.takeIf { it.isTextual }?.asText()
            if (id == null) {
                corruptSkipped += 1
                continue
            }
            bySession[id] = node
        }
    }

    @Synchronized
    fun decide(record: SessionResultRecord) {
        require(record.sessionId.isNotBlank()) { "session id must not be blank" }
        require(record.flightId.isNotBlank()) { "flight id must not be blank" }
        require(record.bootId.isNotBlank()) { "boot id must not be blank" }
        val node = toNode(record)
        // Durable FIRST: publishing the cache entry before the bytes are
        // fsynced would let a retry read a verdict that never reached disk.
        journal.appendLine(Mop.json.writeValueAsString(node))
        bySession[record.sessionId] = node
    }

    /** Explicit recovery after a poisoned failure (see journal). */
    @Synchronized
    fun repairAfterFailure(): Int = journal.repairAfterFailure()

    /** Latest verdict for a session, or null when undecided. */
    @Synchronized
    fun latest(sessionId: String): SessionResultRecord? =
        bySession[sessionId]?.let { fromNode(it) }

    @Synchronized
    fun recordsForFlight(flightId: String): List<SessionResultRecord> =
        bySession.values
            .filter { it.get("flight_id")?.asText() == flightId }
            .mapNotNull { fromNode(it) }

    fun decidedAtNowUtc(): String = wallClock()

    private fun toNode(record: SessionResultRecord): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("session_id", record.sessionId)
        node.put("flight_id", record.flightId)
        node.put("edge_boot_id", record.bootId)
        if (record.runtimeEpoch == null) node.putNull("runtime_epoch")
        else node.put("runtime_epoch", record.runtimeEpoch)
        node.put("result", record.result.name)
        node.put("facts_json", record.factsJson)
        node.put("decided_at_utc", record.decidedAtUtc)
        return node
    }

    private fun fromNode(node: ObjectNode): SessionResultRecord? {
        return try {
            val sessionId = node.get("session_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val flightId = node.get("flight_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val bootId = node.get("edge_boot_id")
                ?.takeIf { it.isTextual }?.asText() ?: return null
            val result = node.get("result")
                ?.takeIf { it.isTextual }?.asText()
                ?.let { runCatching { SessionResult.valueOf(it) }.getOrNull() }
                ?: return null
            SessionResultRecord(
                sessionId = sessionId,
                flightId = flightId,
                bootId = bootId,
                runtimeEpoch = node.get("runtime_epoch")
                    ?.takeIf { it.isTextual }?.asText(),
                result = result,
                factsJson = node.get("facts_json")?.takeIf { it.isTextual }?.asText() ?: "{}",
                decidedAtUtc = node.get("decided_at_utc")
                    ?.takeIf { it.isTextual }?.asText() ?: "",
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val FILE_NAME = "session_results.ndjson"

        /**
         * Unavailable readonly holder for the app degraded path (cold-start
         * disk-full and friends): zero filesystem allocation, zero reads;
         * every write (and repair) throws [reason]. Reads are empty — only
         * ever shown behind the storage-unavailable UI flag, never as
         * healthy facts. No mock data, nothing writable.
         */
        fun unavailable(
            reason: IOException,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): SessionResultLog = SessionResultLog(
            Paths.get(""),
            wallClock,
            DurableJsonLinesJournal.unavailable(reason),
        )
    }
}

/**
 * Session verdicts as a first-class flight artifact — WITHOUT any contract
 * change. `contracts/schemas/flight-upload-manifest.v1.schema.json`
 * already allows `source_kind=FLIGHT_LOG`, so the verdict snapshot travels
 * as a required FLIGHT_LOG artifact through the SAME announce/chunk
 * pipeline as the operator-action snapshot ([OperatorActionArtifacts]):
 * content-derived artifact id, put-if-absent announce, chunks skipped when
 * complete, never SYNCED without a server COMMITTED.
 *
 * The manifest carries the latest snapshot PER FLIGHT_LOG LINEAGE (see
 * [ManifestBuilder]): the verdict lineage never evicts the operator-action
 * lineage — both ship when both exist.
 */
object SessionResultArtifacts {
    const val SOURCE_KIND = "FLIGHT_LOG"
    const val MEDIA_TYPE = "application/x-ndjson"
    const val LINEAGE = "session-result"

    /** Same session-id derivation the session builder uses. */
    fun sessionIdFor(flightId: String, bootId: String): String =
        OperatorActionArtifacts.sessionIdFor(flightId, bootId)

    fun artifactIdFor(flightId: String, bootId: String, sha256Hex: String): String =
        Mop.newMsgId("session-result", flightId, bootId, sha256Hex)

    /** True when this FLIGHT_LOG artifact id carries THIS lineage's bytes. */
    fun isLineage(flightId: String, bootId: String, artifactId: String, sha256Hex: String): Boolean =
        artifactId == artifactIdFor(flightId, bootId, sha256Hex)

    fun snapshotBytes(log: SessionResultLog, sessionId: String): ByteArray {
        val record = log.latest(sessionId) ?: return ByteArray(0)
        val line = Mop.json.createObjectNode()
        line.put("schema_version", 1)
        line.put("record_kind", "SESSION_RESULT")
        line.put("session_id", record.sessionId)
        line.put("flight_id", record.flightId)
        line.put("edge_boot_id", record.bootId)
        if (record.runtimeEpoch == null) line.putNull("runtime_epoch")
        else line.put("runtime_epoch", record.runtimeEpoch)
        line.put("result", record.result.name)
        line.put("facts_json", record.factsJson)
        line.put("decided_at_utc", record.decidedAtUtc)
        return (Mop.json.writeValueAsString(line) + "\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Materialize this boot's verdict snapshot through the standard
     * announce/chunk pipeline. Returns the artifact id, or null when no
     * verdict was decided for this boot (nothing announced, manifest
     * unchanged — undecided sessions ship nothing, never UNKNOWN-by-default
     * bytes that could read as a verdict).
     */
    fun ensureForBoot(
        store: UploadStore,
        log: SessionResultLog,
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
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
