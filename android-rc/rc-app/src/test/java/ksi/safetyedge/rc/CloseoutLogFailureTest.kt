package ksi.safetyedge.rc

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import ksi.rc.JournalIo
import ksi.rc.RealJournalIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Failure atomicity for [CloseoutLog] and [SessionIntentLog]: no cache claim
 * (no fake CLEAN close, no phantom intent) is published before the bytes are
 * durable. Real temp filesystem + log recreation, with deterministic fault
 * injection through the public [JournalIo] seam (production default stays
 * the real FS; public constructors unchanged).
 */
class CloseoutLogFailureTest {

    private fun closeout(
        flightId: String = "flt-1",
        outcome: String = CloseoutRecord.UNRESOLVED,
    ) = CloseoutRecord(
        flightId = flightId,
        bootId = "boot-1",
        sessionId = "sess-1",
        outcome = outcome,
        stopConfirmed = null,
        stopReason = "STOP 요청 전송됨 · Edge 확인 미수신 — 미확인 종료",
        opinionCount = 0,
        segmentsIngested = 0,
        segmentsTotal = 2,
        segmentsUnverified = true,
        videoCaptureFailed = null,
        bundleId = "bundle-1",
        bundleSha256 = "b".repeat(64),
        verdictResult = "UNKNOWN",
        verdictFactsJson = "{}",
        decidedAtUtc = "2026-10-10T07:00:00Z",
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

    private fun assertThrowsIo(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        fail("expected IOException")
        throw AssertionError("unreachable")
    }

    // -- CloseoutLog ---------------------------------------------------------

    @Test
    fun closeout_record_failure_leaves_prior_cache_unchanged() {
        val root = Files.createTempDirectory("rc-closeout-fail")
        CloseoutLog(root).record(closeout())

        val io = FaultIo(failAppends = true)
        val log = CloseoutLog(root, { "2026-10-20T00:00:00Z" }, io)
        assertThrowsIo { log.record(closeout(outcome = CloseoutRecord.CLEAN)) }

        // No fake CLEAN close: the prior UNRESOLVED record still stands.
        assertEquals(CloseoutRecord.UNRESOLVED, log.latestForFlight("flt-1")!!.outcome)
        assertEquals(
            1,
            File(root.toFile(), "closeouts.ndjson").readLines().size,
        )
    }

    @Test
    fun closeout_retry_after_repair_truly_writes_and_survives_restart() {
        val root = Files.createTempDirectory("rc-closeout-retry")
        CloseoutLog(root).record(closeout())

        val io = FaultIo(failAppends = true)
        val log = CloseoutLog(root, { "2026-10-20T00:00:00Z" }, io)
        assertThrowsIo { log.record(closeout(outcome = CloseoutRecord.CLEAN)) }

        // Same-instance retry after the disk recovers: truly written.
        io.failAppends = false
        log.record(closeout(outcome = CloseoutRecord.CLEAN))
        assertEquals(CloseoutRecord.CLEAN, log.latestForFlight("flt-1")!!.outcome)

        val restarted = CloseoutLog(root)
        assertEquals(CloseoutRecord.CLEAN, restarted.latestForFlight("flt-1")!!.outcome)
    }

    @Test
    fun closeout_partial_tail_repaired_then_append_survives_second_restart() {
        val root = Files.createTempDirectory("rc-closeout-tail")
        CloseoutLog(root).record(closeout())
        FileOutputStream(File(root.toFile(), "closeouts.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }

        val recovered = CloseoutLog(root)
        assertTrue(recovered.recoveredPartialWrites >= 1)
        assertEquals(
            CloseoutRecord.UNRESOLVED,
            recovered.latestForFlight("flt-1")!!.outcome,
        )
        // The next record must NOT concatenate onto the repaired-away fragment.
        recovered.record(closeout(flightId = "flt-2", outcome = CloseoutRecord.CLEAN))

        val restarted = CloseoutLog(root)
        assertEquals(CloseoutRecord.UNRESOLVED, restarted.latestForFlight("flt-1")!!.outcome)
        assertEquals(CloseoutRecord.CLEAN, restarted.latestForFlight("flt-2")!!.outcome)
    }

    @Test
    fun closeout_rollback_failure_poisons_until_explicit_repair() {
        val root = Files.createTempDirectory("rc-closeout-poison")
        CloseoutLog(root).record(closeout())

        val io = FaultIo(failAppends = true, failTruncate = true, partialPrefixBytes = 8)
        val log = CloseoutLog(root, { "2026-10-20T00:00:00Z" }, io)
        val poisoned = assertThrowsIo { log.record(closeout(outcome = CloseoutRecord.CLEAN)) }
        assertTrue(
            "fail-closed error must name the explicit repair: ${poisoned.message}",
            poisoned.message!!.contains("repairAfterFailure"),
        )
        assertEquals(CloseoutRecord.UNRESOLVED, log.latestForFlight("flt-1")!!.outcome)
        // Fail-closed while poisoned.
        val blocked = assertThrowsIo { log.record(closeout(flightId = "flt-2")) }
        assertTrue(blocked.message!!.contains("repairAfterFailure"))

        io.failAppends = false
        io.failTruncate = false
        io.partialPrefixBytes = null
        log.repairAfterFailure()
        log.record(closeout(outcome = CloseoutRecord.CLEAN))

        val restarted = CloseoutLog(root)
        assertEquals(CloseoutRecord.CLEAN, restarted.latestForFlight("flt-1")!!.outcome)
        assertNull(restarted.latestForFlight("flt-2"))
        assertEquals(
            2,
            File(root.toFile(), "closeouts.ndjson").readLines().size,
        )
    }

    // -- SessionIntentLog ------------------------------------------------------

    @Test
    fun intent_record_failure_leaves_no_intent_retry_succeeds() {
        val root = Files.createTempDirectory("rc-intent-fail")
        val io = FaultIo(failAppends = true)
        val log = SessionIntentLog(root, { "2026-10-20T00:00:00Z" }, io)

        assertThrowsIo {
            log.record(SessionStartIntent("flt-1", "boot-1", "2026-10-20T00:00:00Z"))
        }
        // No phantom intent: a crash here must read as "never started".
        assertNull(log.latestForFlight("flt-1"))

        io.failAppends = false
        log.record(SessionStartIntent("flt-1", "boot-1", "2026-10-20T00:00:00Z"))
        assertEquals("boot-1", log.latestForFlight("flt-1")!!.bootId)

        val restarted = SessionIntentLog(root)
        assertEquals("boot-1", restarted.latestForFlight("flt-1")!!.bootId)
    }

    @Test
    fun intent_partial_tail_repaired_then_append_survives_second_restart() {
        val root = Files.createTempDirectory("rc-intent-tail")
        SessionIntentLog(root)
            .record(SessionStartIntent("flt-1", "boot-1", "2026-10-20T00:00:00Z"))
        FileOutputStream(File(root.toFile(), "session_intents.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }

        val recovered = SessionIntentLog(root)
        assertTrue(recovered.recoveredPartialWrites >= 1)
        assertEquals("boot-1", recovered.latestForFlight("flt-1")!!.bootId)
        recovered.record(SessionStartIntent("flt-2", null, "2026-10-20T00:01:00Z"))

        val restarted = SessionIntentLog(root)
        assertEquals("boot-1", restarted.latestForFlight("flt-1")!!.bootId)
        assertNull(restarted.latestForFlight("flt-2")!!.bootId)
        assertEquals("2026-10-20T00:01:00Z", restarted.latestForFlight("flt-2")!!.decidedAtUtc)
    }
}
