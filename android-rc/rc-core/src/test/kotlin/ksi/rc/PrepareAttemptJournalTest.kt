package ksi.rc

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Durable preparation attempts: an attempt is recorded before the
 * in-memory attach, survives process death for explicit re-run display,
 * and resolves only via consumed/abandoned/superseded — never by
 * auto-resume. Restart visibility only; no flight record is ever created
 * here.
 */
class PrepareAttemptJournalTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-prepare-attempt")

    private fun journalOn(
        root: Path,
        now: String = "2026-10-27T00:00:00Z",
    ): PrepareAttemptJournal = PrepareAttemptJournal(root, { now })

    @Test
    fun blank_ids_are_rejected_without_writing() {
        val root = tempRoot()
        val journal = journalOn(root)
        assertFailsWith<IllegalArgumentException> {
            journal.recordAttempt("", "bundle-1", "digest-1")
        }
        assertFailsWith<IllegalArgumentException> {
            journal.recordAttempt("prov-1", "", "digest-1")
        }
        assertFailsWith<IllegalArgumentException> {
            journal.recordAttempt("prov-1", "bundle-1", "")
        }
        assertNull(journal.pendingAttempt())
        assertTrue(!root.resolve(PrepareAttemptJournal.FILE_NAME).toFile().exists())
    }

    @Test
    fun attempt_survives_reopen_until_consumed() {
        val root = tempRoot()
        val first = journalOn(root)
        first.recordAttempt("prov-1", "bundle-1", "digest-1")
        assertEquals("bundle-1", journalOn(root).pendingAttempt()?.bundleId)

        // Consuming under a reopened journal clears it.
        journalOn(root).recordConsumed("prov-1")
        assertNull(journalOn(root).pendingAttempt())
    }

    @Test
    fun abandon_and_supersede_resolve_pending() {
        val root = tempRoot()
        val journal = journalOn(root)
        journal.recordAttempt("prov-1", "bundle-1", "digest-1")
        journal.recordAbandoned("prov-1")
        assertNull(journal.pendingAttempt())

        journal.recordAttempt("prov-2", "bundle-1", "digest-1")
        journal.recordSuperseded("prov-2")
        assertNull(journalOn(root).pendingAttempt())

        // Terminal events for unknown ids are ignored, never crash.
        journal.recordConsumed("prov-unknown")
        assertNull(journal.pendingAttempt())
    }

    @Test
    fun latest_attempt_wins_while_earlier_stays_unresolved() {
        val root = tempRoot()
        val journal = journalOn(root)
        journal.recordAttempt("prov-1", "bundle-1", "digest-1")
        journal.recordAttempt("prov-2", "bundle-2", "digest-2")
        assertEquals("prov-2", journal.pendingAttempt()?.provisionalId)
        // Resolving the latest leaves the earlier attempt pending: it was
        // never resolved, and only an explicit consume/abandon/supersede
        // may close it.
        journal.recordAbandoned("prov-2")
        assertEquals("prov-1", journalOn(root).pendingAttempt()?.provisionalId)
    }

    @Test
    fun corrupt_lines_are_skipped_and_counted() {
        val root = tempRoot()
        root.resolve(PrepareAttemptJournal.FILE_NAME).toFile().writeText(
            "{\"kind\":\"attempt\",\"provisional_id\":\"prov-1\"," +
                "\"bundle_id\":\"bundle-1\",\"bundle_digest\":\"d1\"," +
                "\"decided_at_utc\":\"2026-10-27T00:00:00Z\"}\n" +
                "not json\n" +
                "{\"kind\":\"attempt\"}\n",
            Charsets.UTF_8,
        )
        val journal = journalOn(root)
        assertEquals("prov-1", journal.pendingAttempt()?.provisionalId)
        assertEquals(2, journal.recoveredPartialWrites)
    }

    @Test
    fun unavailable_journal_throws_on_write_reads_empty() {
        val journal = PrepareAttemptJournal.unavailable(IOException("down"))
        assertNull(journal.pendingAttempt())
        assertFailsWith<IOException> {
            journal.recordAttempt("prov-1", "bundle-1", "digest-1")
        }
    }
}
