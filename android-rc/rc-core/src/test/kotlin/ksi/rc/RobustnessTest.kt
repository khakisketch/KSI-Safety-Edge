package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robustness contracts added before A3-b (user review 2026-10-09):
 * 1. message ids never collide across an RC restart (durable counter);
 * 2. artifact completeness = received-range coverage + exact size + sha256,
 *    never file length alone;
 * 3. durable appends (flush + fsync) and tolerant load of a partial trailing
 *    write / corrupt line after a crash;
 * 4. persist failure before ACK, with retry-driven recovery.
 */
class RobustnessTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-robustness")

    private fun makeBundle(): ObjectNode {
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", "bundle-1")
        bundle.put("site_id", "site-1")
        bundle.put("bundle_sha256", CanonicalJson.digestExcluding(bundle, "bundle_sha256"))
        return bundle
    }

    private fun observation(): ObjectNode {
        val obs = Mop.json.createObjectNode()
        obs.put("schema_version", 1)
        obs.put("observation_id", "obs-1")
        obs.put("flight_id", "flt-1")
        obs.put("frame_received_monotonic_ns", 1000L)
        return obs
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun announce(artifactId: String, bytes: ByteArray): ObjectNode {
        val announce = Mop.json.createObjectNode()
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", "flt-1")
        announce.put("edge_boot_id", "boot-1")
        announce.put("source_kind", "EDGE_SNAPSHOT")
        announce.put("sha256", sha256Hex(bytes))
        announce.put("size_bytes", bytes.size.toLong())
        announce.put("media_type", "image/jpeg")
        announce.put("required", true)
        announce.put("segment_index", 0)
        return announce
    }

    // 1) durable message-id counter -----------------------------------------

    @Test
    fun message_ids_survive_restart_without_reuse() {
        val root = tempRoot()
        val firstId: String
        run {
            val store = FileRcStore(root)
            val controller = MissionController("flt-1", makeBundle(), store, { 0L })
            controller.prepare()
            firstId = controller.endpoint.retransmissions()[0].get("msg_id").asText()
            assertEquals(Mop.newMsgId("rc", "flt-1", "1", "MISSION_PREPARE"), firstId)
        }
        // Restart: a new store/endpoint over the same directory.
        val store2 = FileRcStore(root)
        val controller2 = MissionController("flt-1", makeBundle(), store2, { 0L })
        controller2.prepare()
        val secondId = controller2.endpoint.retransmissions()[0].get("msg_id").asText()
        assertNotEquals(firstId, secondId,
            "a restarted RC must not reuse message ids (the Edge would dedup them)")
        assertEquals(Mop.newMsgId("rc", "flt-1", "2", "MISSION_PREPARE"), secondId)
    }

    @Test
    fun corrupt_counter_fails_closed() {
        val root = tempRoot()
        FileRcStore(root).nextMsgCounter()
        File(root.toFile(), "msg_counter").writeText("garbage")
        val store = FileRcStore(root)
        assertFailsWith<IOException> { store.nextMsgCounter() }
    }

    // 2) artifact completeness -------------------------------------------------

    @Test
    fun artifact_requires_full_coverage_not_just_length() {
        val store = FileRcStore(tempRoot())
        val bytes = ByteArray(16) { it.toByte() }
        store.announceArtifact(announce("a-1", bytes), "boot-1")
        // Writing only the second half extends the file to the declared size
        // while leaving a zero hole: length alone must never mean complete.
        store.writeChunk("a-1", 8, bytes.copyOfRange(8, 16))
        assertNull(store.artifactBytes("a-1"),
            "a sparse file with a hole is not complete")
        store.writeChunk("a-1", 0, bytes.copyOfRange(0, 8))
        assertContentEquals(bytes, store.artifactBytes("a-1"))
    }

    @Test
    fun artifact_hash_mismatch_is_rejected() {
        val store = FileRcStore(tempRoot())
        val bytes = ByteArray(16) { it.toByte() }
        val lying = announce("a-2", bytes)
        lying.put("sha256", "00".repeat(32)) // declares a digest the bytes do not have
        store.announceArtifact(lying, "boot-1")
        store.writeChunk("a-2", 0, bytes.copyOfRange(0, 8))
        store.writeChunk("a-2", 8, bytes.copyOfRange(8, 16))
        assertNull(store.artifactBytes("a-2"),
            "content must match the announced digest")
    }

    @Test
    fun coverage_survives_restart() {
        val root = tempRoot()
        val bytes = ByteArray(16) { it.toByte() }
        run {
            val store = FileRcStore(root)
            store.announceArtifact(announce("a-3", bytes), "boot-1")
            store.writeChunk("a-3", 8, bytes.copyOfRange(8, 16))
            store.writeChunk("a-3", 0, bytes.copyOfRange(0, 8))
        }
        val restarted = FileRcStore(root)
        assertContentEquals(bytes, restarted.artifactBytes("a-3"),
            "coverage receipts must be rebuilt after a restart")
    }

    // 3) durability / partial-write recovery ----------------------------------

    @Test
    fun partial_trailing_write_is_skipped_on_load() {
        val root = tempRoot()
        run {
            val store = FileRcStore(root)
            store.storeObservation(observation(), "boot-1")
        }
        // Simulate a crash mid-append: an unterminated JSON fragment.
        FileOutputStream(File(root.toFile(), "observations.ndjson"), true).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }
        val restarted = FileRcStore(root)
        assertEquals(1, restarted.observationsFor("flt-1").size,
            "intact records survive a partial trailing write")
        assertTrue(restarted.recoveredPartialWrites >= 1)
    }

    @Test
    fun persist_failure_prevents_ack_and_retry_recovers() {
        val backing = InMemoryRcStore()
        var failStores = true
        val flaky = object : RcStore by backing {
            override fun storeMessage(env: ObjectNode) {
                if (failStores) throw IOException("disk full")
                backing.storeMessage(env)
            }
        }
        val controller = MissionController("flt-1", makeBundle(), flaky, { 0L })
        val payload = Mop.json.createObjectNode()
        payload.put("bundle_sha256", controller.bundleSha)
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge", "flt-1", "boot-1", "1"),
            channel = "CONTROL_RELIABLE", type = "MISSION_PREPARED",
            flightId = "flt-1", edgeBootId = "boot-1", seq = null, ackOf = null,
            payload = payload, sentMonotonicNs = 1L,
        )
        val failed = controller.endpoint.onRawEnvelope(env.toString())
        assertTrue(failed is Inbound.Failed)
        assertTrue((failed as Inbound.Failed).reason.contains("persist failed"))
        assertEquals(0, controller.endpoint.ackedCount,
            "a failed persist must not ACK")
        assertFalse(controller.endpoint.pendingCount() > 0)
        assertEquals(MissionState.IDLE, controller.state)

        failStores = false
        val recovered = controller.endpoint.onRawEnvelope(env.toString())
        assertTrue(recovered is Inbound.Stored,
            "the Edge retry of the same msg_id must succeed once persisting works")
        assertEquals(MissionState.PREPARED, controller.state)
    }

    // duplicate redelivery re-ACK ----------------------------------------------

    @Test
    fun duplicate_redelivery_is_re_acked() {
        val store = InMemoryRcStore()
        val controller = MissionController("flt-1", makeBundle(), store, { 0L })
        val edge = FakeEdge("flt-1", { 0L })
        controller.prepare()
        pump(controller.endpoint, edge)
        assertEquals(MissionState.PREPARED, controller.state)

        val preparedRaw = edge.sentHistory.first { it.contains("MISSION_PREPARED") }
        val acksBefore = controller.endpoint.ackedCount
        val outcome = controller.endpoint.onRawEnvelope(preparedRaw)
        assertTrue(outcome is Inbound.Duplicate)
        assertEquals(acksBefore + 1, controller.endpoint.ackedCount,
            "an already-processed message whose ACK was lost is re-ACKed")
    }
}
