package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import ksi.rc.DurableJsonLinesJournal
import ksi.rc.JournalIo
import ksi.rc.Mop

/**
 * Durable close-out record for a flight (contract §6 closeout).
 *
 * One immutable record per close attempt (append-only NDJSON, latest per
 * flight wins): whether the Edge stop was confirmed or the close stays
 * unresolved, with the recorded reason — never a transient toast. The
 * record doubles as the FLIGHT_LOG closeout artifact content (see
 * [CloseoutArtifacts]): exact fact counts including zero, stop
 * confirmation state, session/boot refs, recorder finalization results
 * and timestamps. Not an operator response — nothing here fabricates one.
 */
data class CloseoutRecord(
    val flightId: String,
    /** Null when no boot basis existed (recorded as missing, never invented). */
    val bootId: String?,
    /** Null when [bootId] is null. */
    val sessionId: String?,
    /** CLEAN (Edge STOPPED confirmed) or UNRESOLVED (reason recorded). */
    val outcome: String,
    /** True only on an Edge-confirmed STOPPED; null/false stays unresolved. */
    val stopConfirmed: Boolean?,
    /** Machine-readable reason for UNRESOLVED (or the clean note). */
    val stopReason: String,
    /** Exact linked operator-response count, including zero. */
    val opinionCount: Int,
    val segmentsIngested: Int,
    val segmentsTotal: Int,
    /** True when the boot linkage could not be proven (kept local). */
    val segmentsUnverified: Boolean,
    /**
     * Known RC recorder capture failure for this flight's recording
     * (sticky recorder flag sampled at finalization). True forces the
     * verdict path to treat video evidence as missing; null means the
     * failure state was never tracked (unknown — never guessed false).
     * A failed capture is never counted as clean video, and nothing
     * invents the missing bytes.
     */
    val videoCaptureFailed: Boolean? = null,
    val bundleId: String?,
    val bundleSha256: String?,
    val verdictResult: String?,
    val verdictFactsJson: String?,
    val decidedAtUtc: String,
) {
    companion object {
        const val CLEAN = "CLEAN"
        const val UNRESOLVED = "UNRESOLVED"

        /**
         * Explicit never-started close: no session was ever opened, no
         * start intent stands, and no Edge boot was recorded. The record
         * states that basis outright — a draft, never a clean execution.
         */
        const val DRAFT = "DRAFT"
    }
}

/**
 * Append-only local store for close-out records (house pattern, same as
 * [ksi.rc.SessionResultLog]: NDJSON append + fsync, latest per flight
 * wins, corrupt lines skipped with a counter). Nothing here deletes or
 * rewrites history.
 */
class CloseoutLog private constructor(
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

    private val byFlight = linkedMapOf<String, ObjectNode>()

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
            val id = node.get("flight_id")?.takeIf { it.isTextual }?.asText()
            if (id == null || !valid(node)) {
                corruptSkipped += 1
                continue
            }
            byFlight[id] = node
        }
    }

    @Synchronized
    fun record(record: CloseoutRecord) {
        require(record.flightId.isNotBlank()) { "flight id must not be blank" }
        require(
            record.outcome == CloseoutRecord.CLEAN ||
                record.outcome == CloseoutRecord.UNRESOLVED ||
                record.outcome == CloseoutRecord.DRAFT,
        ) {
            "unknown closeout outcome ${record.outcome}"
        }
        val node = toNode(record)
        // Durable FIRST: publishing the cache entry before the bytes are
        // fsynced would let a retry read a close-out that never reached disk.
        journal.appendLine(Mop.json.writeValueAsString(node))
        byFlight[record.flightId] = node
    }

    /** Explicit recovery after a poisoned failure (see journal). */
    @Synchronized
    fun repairAfterFailure(): Int = journal.repairAfterFailure()

    /** Latest close-out record for a flight, or null when never closed. */
    @Synchronized
    fun latestForFlight(flightId: String): CloseoutRecord? =
        byFlight[flightId]?.let { fromNode(it) }

    fun decidedAtNowUtc(): String = wallClock()

    private fun toNode(record: CloseoutRecord): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("record_kind", "CLOSEOUT")
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
        return node
    }

    private fun fromNode(node: ObjectNode): CloseoutRecord? {
        return try {
            CloseoutRecord(
                flightId = node.get("flight_id")?.takeIf { it.isTextual }?.asText()
                    ?: return null,
                bootId = node.get("edge_boot_id")?.takeIf { it.isTextual }?.asText(),
                sessionId = node.get("session_id")?.takeIf { it.isTextual }?.asText(),
                outcome = node.get("outcome")?.takeIf { it.isTextual }?.asText()
                    ?: return null,
                stopConfirmed = node.get("stop_confirmed")?.takeIf { it.isBoolean }?.asBoolean(),
                stopReason = node.get("stop_reason")?.takeIf { it.isTextual }?.asText()
                    ?: return null,
                opinionCount = node.get("opinion_count")?.takeIf { it.isNumber }?.asInt()
                    ?: return null,
                segmentsIngested = node.get("segments_ingested")?.takeIf { it.isNumber }?.asInt()
                    ?: return null,
                segmentsTotal = node.get("segments_total")?.takeIf { it.isNumber }?.asInt()
                    ?: return null,
                segmentsUnverified = node.get("segments_unverified")?.takeIf { it.isBoolean }?.asBoolean()
                    ?: return null,
                // Absent on pre-tracking records: unknown, never invented.
                videoCaptureFailed = node.get("video_capture_failed")?.takeIf { it.isBoolean }?.asBoolean(),
                bundleId = node.get("bundle_id")?.takeIf { it.isTextual }?.asText(),
                bundleSha256 = node.get("bundle_sha256")?.takeIf { it.isTextual }?.asText(),
                verdictResult = node.get("verdict_result")?.takeIf { it.isTextual }?.asText(),
                verdictFactsJson = node.get("verdict_facts_json")?.takeIf { it.isTextual }?.asText(),
                decidedAtUtc = node.get("decided_at_utc")?.takeIf { it.isTextual }?.asText()
                    ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun valid(node: ObjectNode): Boolean = fromNode(node) != null

    companion object {
        const val FILE_NAME = "closeouts.ndjson"

        /**
         * Unavailable readonly holder for the app degraded path: zero
         * filesystem allocation, zero reads; [record] (and repair) throws
         * [reason]. Reads are empty — only ever shown behind the
         * storage-unavailable UI flag. No mock data, nothing writable.
         */
        fun unavailable(
            reason: IOException,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): CloseoutLog = CloseoutLog(
            Paths.get(""),
            wallClock,
            DurableJsonLinesJournal.unavailable(reason),
        )
    }
}

/**
 * Durable session-start intent for a flight (contract §6 closeout).
 *
 * Persisted (fsync) BEFORE the RC sends SESSION_START: after a crash or
 * restart, an intent without a later close-out means the Edge session may
 * have started — it can never be assumed "never started". The close gate
 * keeps the flight open until an actual bound STOPPED is observed. Unknown
 * is restored as unknown, never as RUNNING.
 */
data class SessionStartIntent(
    val flightId: String,
    /** Edge boot known at request time, or null when none was. */
    val bootId: String?,
    val decidedAtUtc: String,
)

/**
 * Append-only local store for session-start intents (house pattern:
 * NDJSON append + fsync, latest per flight wins). The intent is cleared
 * only by supersession: a close-out record (any outcome) with a later
 * timestamp retires it.
 */
class SessionIntentLog private constructor(
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

    private val byFlight = linkedMapOf<String, ObjectNode>()

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
            val id = node.get("flight_id")?.takeIf { it.isTextual }?.asText()
            if (id == null) {
                corruptSkipped += 1
                continue
            }
            byFlight[id] = node
        }
    }

    @Synchronized
    fun record(intent: SessionStartIntent) {
        require(intent.flightId.isNotBlank()) { "flight id must not be blank" }
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("record_kind", "SESSION_START_INTENT")
        node.put("flight_id", intent.flightId)
        if (intent.bootId == null) node.putNull("edge_boot_id")
        else node.put("edge_boot_id", intent.bootId)
        node.put("decided_at_utc", intent.decidedAtUtc)
        // Durable FIRST: an intent read from cache but never fsynced could
        // strand a flight open after a crash with no close gate.
        journal.appendLine(Mop.json.writeValueAsString(node))
        byFlight[intent.flightId] = node
    }

    /** Explicit recovery after a poisoned failure (see journal). */
    @Synchronized
    fun repairAfterFailure(): Int = journal.repairAfterFailure()

    @Synchronized
    fun latestForFlight(flightId: String): SessionStartIntent? =
        byFlight[flightId]?.let { node ->
            try {
                SessionStartIntent(
                    flightId = node.get("flight_id")?.takeIf { it.isTextual }?.asText()
                        ?: return null,
                    bootId = node.get("edge_boot_id")?.takeIf { it.isTextual }?.asText(),
                    decidedAtUtc = node.get("decided_at_utc")?.takeIf { it.isTextual }?.asText()
                        ?: return null,
                )
            } catch (_: Exception) {
                null
            }
        }

    fun decidedAtNowUtc(): String = wallClock()

    companion object {
        const val FILE_NAME = "session_intents.ndjson"

        /**
         * Unavailable readonly holder for the app degraded path: zero
         * filesystem allocation, zero reads; [record] (and repair) throws
         * [reason]. Reads are empty — only ever shown behind the
         * storage-unavailable UI flag. No mock data, nothing writable.
         */
        fun unavailable(
            reason: IOException,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): SessionIntentLog = SessionIntentLog(
            Paths.get(""),
            wallClock,
            DurableJsonLinesJournal.unavailable(reason),
        )
    }
}
