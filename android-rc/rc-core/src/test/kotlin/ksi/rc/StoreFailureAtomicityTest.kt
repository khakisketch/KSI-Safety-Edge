package ksi.rc

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-persistence failure atomicity: no observable index, idempotency, or
 * ACK claim may be published before the row is durable. Every test writes to
 * a real temp filesystem and recreates the store object (restart recovery),
 * with deterministic fault injection through the internal [StoreIo] seam
 * (production default stays the real FS; constructor call shapes unchanged).
 */
class StoreFailureAtomicityTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-store-atomicity")

    private fun observation(id: String = "obs-1"): com.fasterxml.jackson.databind.node.ObjectNode {
        val obs = Mop.json.createObjectNode()
        obs.put("schema_version", 1)
        obs.put("observation_id", id)
        obs.put("flight_id", "flt-1")
        obs.put("frame_received_monotonic_ns", 1000L)
        return obs
    }

    private fun event(id: String = "evt-1"): com.fasterxml.jackson.databind.node.ObjectNode {
        val evt = Mop.json.createObjectNode()
        evt.put("schema_version", 1)
        evt.put("event_id", id)
        evt.put("flight_id", "flt-1")
        evt.put("observation_id", "obs-1")
        evt.put("detected_at_edge_monotonic_ns", 2000L)
        return evt
    }

    private fun obsEnvelope(seq: Int, obsId: String): com.fasterxml.jackson.databind.node.ObjectNode {
        val payload = Mop.json.createObjectNode()
        payload.set<com.fasterxml.jackson.databind.JsonNode>("observation", observation(obsId))
        return Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge", "flt-1", "boot-1", "obs-$seq"),
            channel = "EVIDENCE_TRANSFER",
            type = "OBSERVATION",
            flightId = "flt-1",
            edgeBootId = "boot-1",
            seq = seq,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 100L * seq,
        )
    }

    private fun opAction(id: String): OperatorAction = OperatorAction(
        responseId = id,
        candidateId = "cand-1",
        siteId = "site-1",
        sessionId = "sess-1",
        action = "CONFIRM",
        note = null,
        evidenceSourceId = "obs-1",
        evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
        policyId = "policy-1",
        policyVersion = "v3",
        actedAtUtc = "2026-10-20T00:01:00Z",
        delivery = "LOCAL_ONLY",
    )

    /** Deterministic fault injection: fails selected files, optionally
     *  leaving a torn partial prefix on disk like a mid-write crash. */
    private class FaultIo(
        var failFiles: Set<String> = emptySet(),
        var failAllAppends: Boolean = false,
        var failOverwrite: Boolean = false,
        var failTruncate: Boolean = false,
        var partialPrefixBytes: Int? = null,
        val delegate: StoreIo = RealStoreIo,
    ) : StoreIo {
        override fun appendSync(file: File, bytes: ByteArray) {
            if (failAllAppends || file.name in failFiles) {
                partialPrefixBytes?.let { n ->
                    FileOutputStream(file, true).use {
                        it.write(bytes.copyOf(minOf(n, bytes.size)))
                    }
                }
                throw IOException("injected append failure: ${file.name}")
            }
            delegate.appendSync(file, bytes)
        }

        override fun overwriteSync(file: File, bytes: ByteArray) {
            if (failOverwrite || failAllAppends) {
                throw IOException("injected overwrite failure: ${file.name}")
            }
            delegate.overwriteSync(file, bytes)
        }

        override fun writeBlobAt(file: File, offset: Long, bytes: ByteArray) {
            if (failAllAppends || file.name in failFiles) {
                throw IOException("injected blob failure: ${file.name}")
            }
            delegate.writeBlobAt(file, offset, bytes)
        }

        override fun truncateTo(file: File, length: Long) {
            if (failTruncate) throw IOException("injected truncate failure: ${file.name}")
            delegate.truncateTo(file, length)
        }
    }

    // -- msg_counter: fail-closed atomic replacement ------------------------

    @Test
    fun new_store_counter_starts_at_one() {
        val store = FileRcStore(tempRoot())
        assertEquals(1L, store.nextMsgCounter())
    }

    @Test
    fun counter_replacement_failure_preserves_old_counter() {
        val root = tempRoot()
        val lastReturned = run {
            val seed = FileRcStore(root)
            var last = 0L
            repeat(5) { last = seed.nextMsgCounter() }
            last
        }
        assertEquals(5L, lastReturned)
        val before = File(root.toFile(), "msg_counter").readBytes()

        // Replacement fails AFTER 5 was returned and persisted.
        val io = FaultIo(failOverwrite = true)
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)
        assertFailsWith<IOException> { store.nextMsgCounter() }
        assertContentEquals(
            before,
            File(root.toFile(), "msg_counter").readBytes(),
            "a failed replacement must leave the old counter byte-identical",
        )

        // Recovery hands out exactly the next id — no reuse, no silent skip.
        io.failOverwrite = false
        assertEquals(lastReturned + 1, store.nextMsgCounter())

        // Process recreation continues forward, never reuses.
        val restarted = FileRcStore(root)
        assertEquals(lastReturned + 2, restarted.nextMsgCounter())
    }

    @Test
    fun corrupt_counter_fails_closed_preserving_file() {
        for (bad in listOf("", "   ", "garbage", "-3", "-0", "12.5", "0x10", "4_2")) {
            val root = tempRoot()
            val file = File(root.toFile(), "msg_counter")
            file.writeBytes(bad.toByteArray(Charsets.UTF_8))
            val before = file.readBytes()
            val store = FileRcStore(root)
            assertFailsWith<IOException>("counter '$bad' must fail closed") {
                store.nextMsgCounter()
            }
            assertContentEquals(
                before, file.readBytes(),
                "a corrupt counter must be preserved for inspection, not reset",
            )
        }
    }

    @Test
    fun legacy_counter_without_newline_still_parses() {
        val root = tempRoot()
        File(root.toFile(), "msg_counter").writeBytes("7".toByteArray(Charsets.UTF_8))
        val store = FileRcStore(root)
        assertEquals(8L, store.nextMsgCounter())
        val restarted = FileRcStore(root)
        assertEquals(9L, restarted.nextMsgCounter())
    }

    @Test
    fun real_overwrite_replaces_atomically_and_cleans_tmp() {
        val root = tempRoot().toFile()
        val target = File(root, "msg_counter")
        target.writeText("41\n")
        RealStoreIo.overwriteSync(target, "42\n".toByteArray(Charsets.UTF_8))
        assertEquals("42\n", target.readText())
        assertTrue(!File(root, "msg_counter.tmp").exists(), "no temp left behind")
        // Restart reads the replaced value — never the pre-replace one.
        val store = FileRcStore(root.toPath())
        assertEquals(43L, store.nextMsgCounter())
    }

    // -- commit-before-ACK with the REAL endpoint + ingest -------------------

    @Test
    fun observation_persist_failure_no_ack_retry_succeeds_and_survives_restart() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("observations.ndjson"))
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)
        // REAL endpoint + REAL ingest: the first persist failure must not ACK.
        val endpoint = MopEndpoint("flt-1", store, { 0L }, EvidenceIngest(store))

        val env = obsEnvelope(seq = 1, obsId = "obs-1")
        val failed = endpoint.onRawEnvelope(env.toString())
        assertTrue(failed is Inbound.Failed, "persist failure must not ACK: $failed")
        assertEquals(0, endpoint.ackedCount)
        // No index claim: the row is invisible, so the retry re-attempts it.
        assertTrue(store.observationsFor("flt-1").isEmpty())
        assertNull(store.eventReceipt("nope"))

        // Same-message retry after the disk recovers: truly written this time.
        io.failFiles = emptySet()
        val recovered = endpoint.onRawEnvelope(env.toString())
        assertTrue(recovered is Inbound.Stored, "retry must succeed: $recovered")
        assertEquals(1, endpoint.ackedCount)
        assertEquals(1, store.observationsFor("flt-1").size)

        // Restart: the retried record is really on disk.
        val restarted = FileRcStore(root)
        assertEquals(1, restarted.observationsFor("flt-1").size)
        assertEquals("obs-1", restarted.observationsFor("flt-1")[0].get("observation_id").asText())
    }

    // -- direct store setters: failure publishes no claim ---------------------

    @Test
    fun event_persist_failure_leaves_no_receipt_retry_recovers() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("events.ndjson"))
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)

        assertFailsWith<IOException> { store.storeEvent(event(), "boot-1") }
        assertTrue(store.eventsFor("flt-1").isEmpty())
        assertNull(store.eventReceipt("evt-1"), "no receipt may exist for a row never fsynced")

        io.failFiles = emptySet()
        store.storeEvent(event(), "boot-1")
        val receipt = store.eventReceipt("evt-1")
        assertTrue(receipt != null)
        assertEquals("boot-1", receipt.bootId)

        val restarted = FileRcStore(root)
        assertEquals(1, restarted.eventsFor("flt-1").size)
        assertTrue(restarted.eventReceipt("evt-1") != null)
    }

    @Test
    fun application_persist_failure_not_committed_retry_succeeds() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("applications.ndjson"))
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)
        val app = Mop.json.createObjectNode()
        app.put("application_id", "app-1")
        app.put("flight_id", "flt-1")

        assertFailsWith<IOException> { store.recordApplication(app) }
        assertTrue(store.applicationsFor("flt-1").isEmpty())

        io.failFiles = emptySet()
        store.recordApplication(app)
        assertEquals(1, store.applicationsFor("flt-1").size)

        val restarted = FileRcStore(root)
        assertEquals(1, restarted.applicationsFor("flt-1").size)
    }

    @Test
    fun batch_receipt_failure_not_committed_retry_succeeds() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("batch_receipts.ndjson"))
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)

        assertFailsWith<IOException> {
            store.recordBatchReceipt("EVIDENCE", "client-1", "server-1")
        }
        assertNull(store.batchReceipt("EVIDENCE", "client-1"))

        io.failFiles = emptySet()
        store.recordBatchReceipt("EVIDENCE", "client-1", "server-1")
        assertEquals("server-1", store.batchReceipt("EVIDENCE", "client-1"))

        val restarted = FileRcStore(root)
        assertEquals("server-1", restarted.batchReceipt("EVIDENCE", "client-1"))
    }

    @Test
    fun counter_failure_hands_out_no_id_and_never_reuses() {
        val root = tempRoot()
        val io = FaultIo(failOverwrite = true)
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)

        assertFailsWith<IOException> { store.nextMsgCounter() }

        io.failOverwrite = false
        assertEquals(1L, store.nextMsgCounter())
        assertEquals(2L, store.nextMsgCounter())

        val restarted = FileRcStore(root)
        assertEquals(3L, restarted.nextMsgCounter())
    }

    @Test
    fun mark_processed_failure_not_claimed_retry_marks() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("processed.ndjson"))
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)

        assertFailsWith<IOException> { store.markProcessed("msg-1") }
        assertTrue(!store.isProcessed("msg-1"))

        io.failFiles = emptySet()
        store.markProcessed("msg-1")
        assertTrue(store.isProcessed("msg-1"))

        val restarted = FileRcStore(root)
        assertTrue(restarted.isProcessed("msg-1"))
    }

    // -- torn-tail safety -----------------------------------------------------

    @Test
    fun mid_write_partial_tail_rolled_back_and_retry_succeeds() {
        val root = tempRoot()
        // Torn write: 12 bytes hit disk, then the failure surfaces.
        val io = FaultIo(
            failFiles = setOf("observations.ndjson"),
            partialPrefixBytes = 12,
        )
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)
        val before = File(root.toFile(), "observations.ndjson").let {
            if (it.exists()) it.length() else 0L
        }

        assertFailsWith<IOException> { store.storeObservation(observation(), "boot-1") }
        assertTrue(store.observationsFor("flt-1").isEmpty())
        val file = File(root.toFile(), "observations.ndjson")
        assertEquals(before, if (file.exists()) file.length() else 0L,
            "the torn tail must be rolled back, not left for the next append")

        io.failFiles = emptySet()
        store.storeObservation(observation(), "boot-1")
        assertEquals(1, store.observationsFor("flt-1").size)

        val restarted = FileRcStore(root)
        assertEquals(1, restarted.observationsFor("flt-1").size)
        assertEquals("obs-1", restarted.observationsFor("flt-1")[0].get("observation_id").asText())
    }

    @Test
    fun startup_trailing_fragment_repaired_then_append_survives_second_restart() {
        val root = tempRoot()
        run {
            val store = FileRcStore(root)
            store.storeObservation(observation("obs-1"), "boot-1")
        }
        // Simulate a crash mid-append: an unterminated JSON fragment.
        FileOutputStream(File(root.toFile(), "observations.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }
        val recovered = FileRcStore(root)
        assertTrue(recovered.recoveredPartialWrites >= 1)
        assertEquals(1, recovered.observationsFor("flt-1").size)
        // The next append must NOT concatenate onto the repaired-away fragment.
        recovered.storeObservation(observation("obs-2"), "boot-1")
        assertEquals(2, recovered.observationsFor("flt-1").size)

        val restarted = FileRcStore(root)
        assertEquals(
            listOf("obs-1", "obs-2"),
            restarted.observationsFor("flt-1").map { it.get("observation_id").asText() },
            "both the pre-crash record and the post-recovery record survive",
        )
    }

    @Test
    fun unrecoverable_rollback_poisons_writes_until_explicit_repair() {
        val root = tempRoot()
        val io = FaultIo(
            failFiles = setOf("observations.ndjson"),
            partialPrefixBytes = 8,
            failTruncate = true,
        )
        val store = FileRcStore(root, { "2026-10-20T00:00:00Z" }, io)

        val poisoned = assertFailsWith<IOException> {
            store.storeObservation(observation(), "boot-1")
        }
        assertTrue(
            poisoned.message!!.contains("repairAfterFailure"),
            "fail-closed error must name the explicit repair: ${poisoned.message}",
        )
        // Fail-closed: even unrelated files refuse writes while poisoned.
        val blocked = assertFailsWith<IOException> {
            store.recordBatchReceipt("EVIDENCE", "client-9", "server-9")
        }
        assertTrue(blocked.message!!.contains("repairAfterFailure"))
        assertNull(store.batchReceipt("EVIDENCE", "client-9"))

        // Explicit repair re-opens the store; the retry is truly written.
        io.failFiles = emptySet()
        io.failTruncate = false
        io.partialPrefixBytes = null
        store.repairAfterFailure()
        store.storeObservation(observation(), "boot-1")
        assertEquals(1, store.observationsFor("flt-1").size)

        val restarted = FileRcStore(root)
        assertEquals(1, restarted.observationsFor("flt-1").size)
    }

    // -- OperatorActionLog ----------------------------------------------------

    @Test
    fun operator_log_append_failure_not_visible_retry_succeeds() {
        val root = tempRoot()
        val io = FaultIo(failFiles = setOf("operator_actions.ndjson"))
        val log = OperatorActionLog(root, io)

        assertFailsWith<IOException> { log.append(opAction("r-1")) }
        assertTrue(log.readAll().isEmpty(),
            "a failed append must not pretend the record is already in the log")

        io.failFiles = emptySet()
        log.append(opAction("r-1"))
        assertEquals(listOf("r-1"), log.readAll().map { it.responseId })

        val restarted = OperatorActionLog(root)
        assertEquals(listOf("r-1"), restarted.readAll().map { it.responseId })
    }

    @Test
    fun operator_log_startup_fragment_then_append_survives_restart() {
        val root = tempRoot()
        run {
            val log = OperatorActionLog(root)
            log.append(opAction("r-1"))
        }
        FileOutputStream(File(root.toFile(), "operator_actions.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }
        val recovered = OperatorActionLog(root)
        assertTrue(recovered.recoveredPartialWrites >= 1)
        recovered.append(opAction("r-2"))
        assertEquals(
            listOf("r-1", "r-2"),
            recovered.readAll().map { it.responseId },
        )

        val restarted = OperatorActionLog(root)
        assertEquals(
            listOf("r-1", "r-2"),
            restarted.readAll().map { it.responseId },
        )
    }
}
