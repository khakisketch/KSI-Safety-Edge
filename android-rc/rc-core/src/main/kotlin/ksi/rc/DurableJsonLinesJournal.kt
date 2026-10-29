package ksi.rc

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Fault-injection seam for [DurableJsonLinesJournal]. Production default is
 * [RealJournalIo] (real filesystem with flush+fsync). Tests substitute a
 * delegate that throws mid-append to prove no cache/idempotency claim is
 * published before the bytes are durable. Public so both rc-core and rc-app
 * tests can inject faults without touching production call shapes.
 */
interface JournalIo {
    /** Append bytes and force them to stable storage before returning. */
    fun appendSync(file: File, bytes: ByteArray)

    /** Truncate to length and force the metadata before returning. */
    fun truncateTo(file: File, length: Long)
}

/** Production [JournalIo]: real filesystem, flush+fsync, platform-free. */
object RealJournalIo : JournalIo {
    override fun appendSync(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
    }

    override fun truncateTo(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(length)
            raf.fd.sync()
        }
    }
}

/**
 * Zero-filesystem [JournalIo] for the app degraded path: every op throws
 * the recorded [reason] without touching any file, directory, or temp
 * allocation. Internal — holders expose it through their own public
 * `unavailable(reason)` factories.
 */
internal class UnavailableJournalIo(val reason: IOException) : JournalIo {
    override fun appendSync(file: File, bytes: ByteArray): Nothing = throw reason
    override fun truncateTo(file: File, length: Long): Nothing = throw reason
}

/**
 * One small platform-free (java.io only) append-only NDJSON journal shared
 * by the single-record logs ([SessionResultLog], app `CloseoutLog`,
 * app `SessionIntentLog`) instead of three duplicate durability fixes.
 *
 * Contract, mirroring `FileRcStore`/`OperatorActionLog`:
 * - [appendLine] publishes NOTHING before the bytes are fsynced: on any
 *   failure it rolls back only the uncommitted tail and throws, so the
 *   caller must update its in-memory cache only after this returns.
 * - a torn trailing fragment (crash mid-append) is repaired — truncated —
 *   at construction and before every append, so a good record can never
 *   concatenate onto crash garbage and be lost on restart. Only bytes
 *   after the last newline are ever truncated; committed records are
 *   untouched and complete-but-corrupt lines are left for the caller to
 *   skip visibly.
 * - when a tail rollback itself cannot be made durable the journal is
 *   poisoned: every later write fails closed with an error naming
 *   [repairAfterFailure] until that explicit repair runs.
 *
 * No new data format: one JSON object per newline-terminated line, exactly
 * the bytes the previous per-log implementations wrote, so old records
 * read back unchanged.
 */
class DurableJsonLinesJournal(
    dir: File,
    fileName: String,
    private val io: JournalIo = RealJournalIo,
) {
    companion object {
        /**
         * Unavailable readonly journal for the app degraded path: zero
         * filesystem allocation and zero reads (no mkdir, no repair stat,
         * no temp file). Reads return empty — only ever shown behind the
         * storage-unavailable UI flag; every write (and repair) throws
         * [reason]. No mock data, nothing writable.
         */
        fun unavailable(reason: IOException): DurableJsonLinesJournal =
            DurableJsonLinesJournal(File(""), "", UnavailableJournalIo(reason))
    }

    val file: File = File(dir, fileName)
    @Volatile
    private var poison: IOException? = null

    /** Torn tails repaired (truncated) while loading/appending. */
    var recoveredPartialWrites = 0
        private set

    init {
        if (io is UnavailableJournalIo) {
            // Unavailable readonly holder: zero filesystem touch — no
            // mkdir, no repair stat, no read. Writes fail via poison below;
            // reads short-circuit to empty.
            poison = io.reason
        } else {
            dir.mkdirs()
            repairTrailingFragment()
        }
    }

    /**
     * Durably appends one record line (the newline is added). Throws on
     * failure WITHOUT publishing anything.
     */
    @Synchronized
    fun appendLine(line: String) {
        ensureWritable()
        repairTrailingFragment()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val committed = if (file.exists()) file.length() else 0L
        try {
            io.appendSync(file, bytes)
        } catch (exc: Exception) {
            rollbackTail(committed, exc)
        }
    }

    /**
     * Newline-terminated lines only; a torn tail is dropped here (it was
     * already counted when repaired at open / before append). An
     * unavailable journal short-circuits to empty with zero filesystem
     * touch — its emptiness is only ever shown behind the
     * storage-unavailable UI flag, never as a healthy fact.
     */
    @Synchronized
    fun readCompletedLines(): List<String> {
        if (io is UnavailableJournalIo) return emptyList()
        if (!file.exists()) return emptyList()
        val text = String(file.readBytes(), Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        val completed = if (text.endsWith("\n")) {
            text
        } else {
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline < 0) return emptyList() else text.substring(0, lastNewline + 1)
        }
        return completed.split("\n").filter { it.isNotBlank() }
    }

    /**
     * Explicit recovery after a poisoned failure: truncates a torn trailing
     * fragment (committed records are never touched) and re-opens the
     * journal for appends. Returns 1 when a fragment was repaired, else 0.
     */
    @Synchronized
    fun repairAfterFailure(): Int {
        (io as? UnavailableJournalIo)?.let { throw it.reason }
        val repaired = if (repairTrailingFragment()) 1 else 0
        poison = null
        return repaired
    }

    private fun ensureWritable() {
        poison?.let {
            throw IOException(
                "journal ${file.name} writes blocked after an unrecoverable " +
                    "IO failure (${it.message}); inspect the directory, " +
                    "then call repairAfterFailure()",
                it,
            )
        }
    }

    private fun repairTrailingFragment(): Boolean {
        if (!file.exists()) return false
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return false
        if (bytes.last() == '\n'.code.toByte()) return false
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        val committed = if (lastNewline < 0) 0L else (lastNewline + 1).toLong()
        try {
            io.truncateTo(file, committed)
        } catch (exc: Exception) {
            val poisoned = IOException(
                "journal ${file.name}: repair truncate failed; writes " +
                    "blocked until repairAfterFailure()",
                exc,
            )
            poison = poisoned
            throw poisoned
        }
        recoveredPartialWrites += 1
        return true
    }

    private fun rollbackTail(committed: Long, cause: Exception): Nothing {
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
            val poisoned = IOException(
                "journal ${file.name}: append failed (${cause.message}) and " +
                    "the uncommitted tail could not be rolled back " +
                    "(${rollbackExc.message}); writes blocked until " +
                    "repairAfterFailure()",
                rollbackExc,
            )
            poison = poisoned
            throw poisoned
        }
        if (tailRemoved) recoveredPartialWrites += 1
        if (cause is IOException) throw cause
        throw IOException("journal ${file.name} append failed: ${cause.message}", cause)
    }
}
