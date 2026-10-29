package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path

/**
 * One preparation attempt that never completed into a recording (house
 * pattern: events, never rewrites).
 *
 * A prepared-but-never-recorded bundle must never resurrect as a phantom
 * flight after process death — so this journal records the attempt ONLY so
 * the operator can SEE what was pending and explicitly re-run it. It never
 * auto-resumes, never auto-resends, never creates a flight record.
 */
data class PrepareAttempt(
    val provisionalId: String,
    val bundleId: String,
    val bundleDigest: String,
    val decidedAtUtc: String,
)

/**
 * Durable preparation-attempt journal (house pattern). Events, never
 * rewrites:
 * - `attempt`: a prepared bundle (provisional id + bundle identity);
 * - `consumed`: the attempt completed into 기록 시작;
 * - `abandoned`: the operator explicitly abandoned it;
 * - `superseded`: a newer attempt for a different bundle replaced it.
 *
 * `pendingAttempt()` returns the latest attempt with no terminal event, or
 * null when nothing is outstanding. Corrupt lines are skipped and counted;
 * torn tails are repaired by the journal.
 */
class PrepareAttemptJournal private constructor(
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

    private val attempts = linkedMapOf<String, ObjectNode>()
    private val terminal = linkedMapOf<String, ObjectNode>()

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
            val kind = node.get("kind")?.takeIf { it.isTextual }?.asText()
            val id = node.get("provisional_id")?.takeIf { it.isTextual }?.asText()
            if ((kind != "attempt" && kind != "consumed" && kind != "abandoned" &&
                    kind != "superseded") || id.isNullOrEmpty()
            ) {
                corruptSkipped += 1
                continue
            }
            when (kind) {
                "attempt" -> attempts.putIfAbsent(id, node)
                else -> terminal.putIfAbsent(id, node)
            }
        }
    }

    /**
     * Records a preparation attempt (no network, no Edge contact — the
     * caller attaches the controller afterwards). Validates first: an
     * invalid attempt throws WITHOUT writing anything.
     */
    @Synchronized
    fun recordAttempt(
        provisionalId: String,
        bundleId: String,
        bundleDigest: String,
    ): PrepareAttempt {
        require(provisionalId.isNotBlank()) { "provisional id must not be blank" }
        require(bundleId.isNotBlank()) { "bundle id must not be blank" }
        require(bundleDigest.isNotBlank()) { "bundle digest must not be blank" }
        val attempt = PrepareAttempt(
            provisionalId = provisionalId,
            bundleId = bundleId,
            bundleDigest = bundleDigest,
            decidedAtUtc = wallClock(),
        )
        // Durable FIRST: the in-memory controller attach that follows must
        // never outlive the disk record of what was pending.
        journal.appendLine(Mop.json.writeValueAsString(toNode("attempt", attempt)))
        attempts[provisionalId] = toNode("attempt", attempt)
        return attempt
    }

    /** Latest attempt with no terminal event, or null when none outstanding. */
    @Synchronized
    fun pendingAttempt(): PrepareAttempt? =
        attempts.values.mapNotNull { fromNode(it) }
            .lastOrNull { it.provisionalId !in terminal }

    @Synchronized
    fun recordConsumed(provisionalId: String) {
        recordTerminal("consumed", provisionalId)
    }

    @Synchronized
    fun recordAbandoned(provisionalId: String) {
        recordTerminal("abandoned", provisionalId)
    }

    @Synchronized
    fun recordSuperseded(provisionalId: String) {
        recordTerminal("superseded", provisionalId)
    }

    private fun recordTerminal(kind: String, provisionalId: String) {
        require(provisionalId.isNotBlank()) { "provisional id must not be blank" }
        val attempt = attempts[provisionalId]?.let { fromNode(it) } ?: return
        journal.appendLine(Mop.json.writeValueAsString(toNode(kind, attempt)))
        terminal.putIfAbsent(provisionalId, toNode(kind, attempt))
    }

    /** Explicit recovery after a poisoned failure (see journal). */
    @Synchronized
    fun repairAfterFailure(): Int = journal.repairAfterFailure()

    private fun toNode(kind: String, attempt: PrepareAttempt): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("record_kind", "PREPARE_ATTEMPT")
        node.put("kind", kind)
        node.put("provisional_id", attempt.provisionalId)
        node.put("bundle_id", attempt.bundleId)
        node.put("bundle_digest", attempt.bundleDigest)
        node.put("decided_at_utc", attempt.decidedAtUtc)
        return node
    }

    private fun fromNode(node: ObjectNode): PrepareAttempt? {
        val id = node.get("provisional_id")?.takeIf { it.isTextual }?.asText()
            ?: return null
        val bundleId = node.get("bundle_id")?.takeIf { it.isTextual }?.asText()
            ?: return null
        val digest = node.get("bundle_digest")?.takeIf { it.isTextual }?.asText()
            ?: return null
        val decided = node.get("decided_at_utc")?.takeIf { it.isTextual }?.asText()
            ?: return null
        if (id.isBlank() || bundleId.isBlank() || digest.isBlank()) return null
        return PrepareAttempt(id, bundleId, digest, decided)
    }

    companion object {
        const val FILE_NAME = "prepare_attempts.ndjson"

        /**
         * Unavailable readonly holder for the app degraded path: zero
         * filesystem allocation, zero reads; record calls throw [reason].
         * Reads are empty — only ever shown behind the storage-unavailable
         * UI flag. No mock data, nothing writable.
         */
        fun unavailable(
            reason: java.io.IOException,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): PrepareAttemptJournal = PrepareAttemptJournal(
            java.nio.file.Paths.get(""),
            wallClock,
            DurableJsonLinesJournal.unavailable(reason),
        )
    }
}
