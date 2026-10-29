package ksi.rc

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Failure atomicity for [DurableJsonLinesJournal] and its [SessionResultLog]
 * caller: no cache claim is published before the bytes are durable. Real
 * temp filesystem + store recreation, with deterministic fault injection
 * through the public [JournalIo] seam (production default stays the real
 * FS; public constructors unchanged).
 */
class DurableJournalFailureTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-journal")

    private fun verdict(
        session: String = "sess-1",
        result: SessionResult = SessionResult.SUCCESS,
    ) = SessionResultRecord(
        sessionId = session,
        flightId = "flt-1",
        bootId = "boot-1",
        runtimeEpoch = "e1",
        result = result,
        factsJson = "{}",
        decidedAtUtc = "2026-10-20T00:00:00Z",
    )

    /** Deterministic fault injection: fails appends, optionally leaving a
     *  torn partial prefix on disk like a mid-write crash. */
    private class FaultIo(
        var failAppends: Boolean = false,
        var failTruncate: Boolean = false,
        var partialPrefixBytes: Int? = null,
        val delegate: JournalIo = RealJournalIo,
    ) : JournalIo {
        override fun appendSync(file: File, bytes: ByteArray) {
            if (failAppends) {
                partialPrefixBytes?.let { n ->
                    FileOutputStream(file, true).use {
                        it.write(bytes.copyOf(minOf(n, bytes.size)))
                    }
                }
                throw IOException("injected journal append failure: ${file.name}")
            }
            delegate.appendSync(file, bytes)
        }

        override fun truncateTo(file: File, length: Long) {
            if (failTruncate) throw IOException("injected journal truncate failure")
            delegate.truncateTo(file, length)
        }
    }

    // -- journal unit ----------------------------------------------------------

    @Test
    fun journal_roundtrip_and_old_format_compatible() {
        val root = tempRoot().toFile()
        val journal = DurableJsonLinesJournal(root, "j.ndjson")
        journal.appendLine("""{"a":1}""")
        assertEquals(listOf("""{"a":1}"""), journal.readCompletedLines())

        // Old records (written before the journal existed) read back as-is.
        File(root, "legacy.ndjson").writeText("{\"b\":2}\n")
        val legacy = DurableJsonLinesJournal(root, "legacy.ndjson")
        assertEquals(listOf("""{"b":2}"""), legacy.readCompletedLines())
    }

    // -- SessionResultLog --------------------------------------------------------

    @Test
    fun decide_failure_leaves_prior_cache_unchanged() {
        val root = tempRoot()
        run {
            SessionResultLog(root).decide(verdict("sess-1"))
        }
        val io = FaultIo(failAppends = true)
        val log = SessionResultLog(root, { "2026-10-20T00:00:00Z" }, io)

        assertFailsWith<IOException> { log.decide(verdict("sess-2", SessionResult.FAILED)) }
        // No SUCCESS/FAILED claim for the unwritten verdict; the prior
        // decided verdict is untouched — never a fake SUCCESS.
        assertNull(log.latest("sess-2"))
        assertEquals(SessionResult.SUCCESS, log.latest("sess-1")!!.result)
        assertEquals(
            1, File(root.toFile(), "session_results.ndjson").readLines().size,
            "the failed decide must not reach the file either",
        )
    }

    @Test
    fun decide_retry_after_repair_truly_writes_and_survives_restart() {
        val root = tempRoot()
        run {
            SessionResultLog(root).decide(verdict("sess-1"))
        }
        val io = FaultIo(failAppends = true)
        val log = SessionResultLog(root, { "2026-10-20T00:00:00Z" }, io)
        assertFailsWith<IOException> { log.decide(verdict("sess-2", SessionResult.FAILED)) }

        // Same-instance retry after the disk recovers: truly written.
        io.failAppends = false
        log.decide(verdict("sess-2", SessionResult.FAILED))
        assertEquals(SessionResult.FAILED, log.latest("sess-2")!!.result)

        val restarted = SessionResultLog(root)
        assertEquals(SessionResult.SUCCESS, restarted.latest("sess-1")!!.result)
        assertEquals(SessionResult.FAILED, restarted.latest("sess-2")!!.result)
    }

    @Test
    fun partial_tail_repaired_then_append_survives_second_restart() {
        val root = tempRoot()
        run {
            SessionResultLog(root).decide(verdict("sess-1"))
        }
        FileOutputStream(File(root.toFile(), "session_results.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }
        val recovered = SessionResultLog(root)
        assertTrue(recovered.recoveredPartialWrites >= 1)
        assertEquals(SessionResult.SUCCESS, recovered.latest("sess-1")!!.result)
        // The next append must NOT concatenate onto the repaired-away fragment.
        recovered.decide(verdict("sess-2", SessionResult.FAILED))

        val restarted = SessionResultLog(root)
        assertEquals(SessionResult.SUCCESS, restarted.latest("sess-1")!!.result)
        assertEquals(SessionResult.FAILED, restarted.latest("sess-2")!!.result)
    }

    @Test
    fun fsync_failure_rolls_back_tail_and_retry_succeeds() {
        val root = tempRoot()
        val io = FaultIo(failAppends = true, partialPrefixBytes = 10)
        val log = SessionResultLog(root, { "2026-10-20T00:00:00Z" }, io)
        val file = File(root.toFile(), "session_results.ndjson")
        val before = if (file.exists()) file.length() else 0L

        assertFailsWith<IOException> { log.decide(verdict("sess-1")) }
        assertNull(log.latest("sess-1"))
        assertEquals(before, if (file.exists()) file.length() else 0L)

        io.failAppends = false
        io.partialPrefixBytes = null
        log.decide(verdict("sess-1"))
        assertEquals(SessionResult.SUCCESS, SessionResultLog(root).latest("sess-1")!!.result)
    }

    @Test
    fun rollback_failure_poisons_until_explicit_repair() {
        val root = tempRoot()
        run {
            SessionResultLog(root).decide(verdict("sess-1"))
        }
        val io = FaultIo(failAppends = true, failTruncate = true, partialPrefixBytes = 8)
        val log = SessionResultLog(root, { "2026-10-20T00:00:00Z" }, io)

        val poisoned = assertFailsWith<IOException> { log.decide(verdict("sess-2")) }
        assertTrue(
            poisoned.message!!.contains("repairAfterFailure"),
            "fail-closed error must name the explicit repair: ${poisoned.message}",
        )
        assertNull(log.latest("sess-2"))
        // Fail-closed while poisoned, even for unrelated state.
        val blocked = assertFailsWith<IOException> { log.decide(verdict("sess-3")) }
        assertTrue(blocked.message!!.contains("repairAfterFailure"))

        io.failAppends = false
        io.failTruncate = false
        io.partialPrefixBytes = null
        log.repairAfterFailure()
        log.decide(verdict("sess-2", SessionResult.FAILED))

        val restarted = SessionResultLog(root)
        assertEquals(SessionResult.SUCCESS, restarted.latest("sess-1")!!.result)
        assertEquals(SessionResult.FAILED, restarted.latest("sess-2")!!.result)
        assertNull(restarted.latest("sess-3"))
        assertEquals(
            2, File(root.toFile(), "session_results.ndjson").readLines().size,
            "earlier committed records untouched, no garbage rows",
        )
    }
}
