package ksi.msdk

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.CanonicalJson
import ksi.rc.ControlHandler
import ksi.rc.EvidenceIngest
import ksi.rc.FileRcStore
import ksi.rc.Inbound
import ksi.rc.Mop
import ksi.rc.MopEndpoint
import ksi.rc.MopError
import ksi.safetyedge.rc.MopReassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Reader-compatibility receive/storage pins for the two edge-to-RC fixture
 * generations:
 *
 * - `contracts/mop/edge-to-rc.mopv1.hex` — the historic contract bytes
 *   (its MISSION_STATUS still says `"running": true` right after APPLY,
 *   before any SESSION_START);
 * - `edge/tests/fixtures/edge-to-rc-current.mopv1.hex` — the corrected
 *   producer pin (same stream with `"running": false` there).
 *
 * Both must flow through the REAL RC receive path ([MopReassembler] +
 * [MopEndpoint] + [EvidenceIngest] + [FileRcStore]) and land the identical
 * evidence set: the `running` correction is session-neutral transport
 * truth, never evidence. The cross-fixture comparison below fails if any
 * envelope *other* than that single STATUS bool (and its digest) ever
 * differs, so a future producer change cannot hide inside this pin.
 *
 * This deliberately mirrors (not reuses) [MopCrossLanguageTest]'s storage
 * assertions so the historic and corrected generations stay pinned even if
 * that test is later repointed at one file.
 */
class MopFixtureReceiveTest {
    private data class Received(
        val name: String,
        val messages: List<String>,
        val stored: Int,
        val failed: List<String>,
        val frameErrors: Long,
        val ackedCount: Int,
        val statusPayloads: List<ObjectNode>,
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            if (File(dir, "contracts/mop/edge-to-rc.mopv1.hex").exists()) return dir
            val parent = dir.parentFile
                ?: throw IllegalStateException(
                    "repo root not found above ${System.getProperty("user.dir")}",
                )
            dir = parent
        }
    }

    private fun unhex(text: String): ByteArray {
        val clean = text.trim()
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) +
                Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun currentFixtureFile(root: File): File {
        val pinned = File(root, "edge/tests/fixtures/edge-to-rc-current.mopv1.hex")
        check(pinned.isFile) { "corrected edge-to-RC fixture missing: $pinned" }
        return pinned
    }

    private fun receive(name: String, bytes: ByteArray): Received {
        val reassembler = MopReassembler()
        val frames = reassembler.feed(bytes)
        val frameErrors = reassembler.errors
        // Every framed message must be a verifiable envelope (digest included).
        val messages = frames.map { it.decodeToString() }
        for ((index, message) in messages.withIndex()) {
            try {
                Mop.verifyEnvelope(message)
            } catch (exc: MopError) {
                fail("$name message $index does not verify: ${exc.message}")
            }
        }
        val dir = Files.createTempDirectory("mop-fixture").toFile()
        val statusPayloads = mutableListOf<ObjectNode>()
        try {
            val store = FileRcStore(dir.toPath())
            var clock = 0L
            val ingest = EvidenceIngest(store)
            val handler = ControlHandler { env, payload ->
                when (env.get("type").asText()) {
                    "OBSERVATION", "DETECTION_EVENT", "FLIGHT_SUMMARY",
                    "ARTIFACT_ANNOUNCE", "ARTIFACT_CHUNK", "HEARTBEAT",
                    -> ingest.handle(env, payload)
                    "MISSION_STATUS" -> {
                        statusPayloads.add(payload.deepCopy())
                        null
                    }
                    else -> null
                }
            }
            val endpoint = MopEndpoint("flt-e2e", store, { ++clock }, handler)
            var stored = 0
            val failed = mutableListOf<String>()
            for (message in messages) {
                when (val inbound = endpoint.onRawEnvelope(message)) {
                    is Inbound.Stored -> stored += 1
                    is Inbound.Acked, is Inbound.Duplicate -> Unit
                    is Inbound.Failed -> failed.add("${inbound.type}: ${inbound.reason}")
                }
            }
            assertEquals("$name: no inbound failures", emptyList<String>(), failed)
            assertEquals("$name: clean reassembly", 0L, frameErrors)

            // The C++-produced envelopes reached the REAL store.
            assertEquals("$name: observations", 1, store.observationsFor("flt-e2e").size)
            assertEquals("$name: events", 1, store.eventsFor("flt-e2e").size)
            assertEquals("$name: summaries", 1, store.summariesFor("flt-e2e").size)
            assertEquals("$name: artifacts", 1, store.artifactsFor("flt-e2e").size)

            val artifactId = store.artifactsFor("flt-e2e")[0].get("artifact_id").asText()
            val assembled = store.artifactBytes(artifactId)
            assertNotNull("$name: artifact fully assembled", assembled)
            val expected = ByteArray(100) { (it % 251).toByte() }
            assertArrayEquals("$name: artifact bytes", expected, assembled)
            val sha = MessageDigest.getInstance("SHA-256").digest(assembled!!)
                .joinToString("") { "%02x".format(it) }
            assertEquals(
                "$name: artifact sha",
                store.artifactsFor("flt-e2e")[0].get("sha256").asText(), sha,
            )

            val boot = Mop.newMsgId("boot", "flt-e2e", "0")
            assertEquals(
                "$name: event seqs",
                setOf(1L, 2L, 3L), store.receivedSeqs(boot, "EVENT_RELIABLE"),
            )
            assertEquals(
                "$name: transfer seqs",
                setOf(1L, 2L), store.receivedSeqs(boot, "EVIDENCE_TRANSFER"),
            )
            assertNotNull("$name: heartbeat freshness", store.lastHeartbeatUtc("flt-e2e"))

            // Tick-evidence mapping fidelity: the C++ MopTickEvidence built
            // these payloads from a real TickRecord, not by hand.
            val observation = store.observationsFor("flt-e2e").single()
            val detections = observation.get("detections")
            assertEquals("$name: detection class", "person", detections.get(0).get("class_id").asText())
            assertEquals("$name: track", "trk-3", detections.get(0).get("track_id").asText())
            assertTrue("$name: position withheld", detections.get(0).get("position_wgs84").isNull)
            assertEquals("$name: sync", "DEGRADED", observation.get("sync_quality").asText())
            assertEquals("$name: frame seq", 1L, observation.get("edge_frame_seq").asLong())
            val event = store.eventsFor("flt-e2e").single()
            assertEquals("$name: event type", "PROXIMITY_CANDIDATE", event.get("event_type").asText())
            assertEquals("$name: mode", "SHADOW", event.get("mode").asText())
            assertEquals("$name: distance", 2.57, event.get("initial_model_output").get("distance_m").asDouble(), 0.0)

            // Commit-before-ACK held for every stored message.
            assertEquals("$name: acked == stored", stored, endpoint.ackedCount)
            assertTrue("$name: stored covers the stream", stored >= 8)

            // Exactly one MISSION_STATUS, with an explicit running bool.
            assertEquals("$name: one STATUS", 1, statusPayloads.size)
            assertTrue(
                "$name: STATUS running is a bool",
                statusPayloads[0].get("running")?.isBoolean == true,
            )
            return Received(name, messages, stored, failed, frameErrors, endpoint.ackedCount, statusPayloads)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun historic_and_corrected_fixtures_store_identical_evidence() {
        val root = repoRoot()
        val historic = receive(
            "historic",
            unhex(File(root, "contracts/mop/edge-to-rc.mopv1.hex").readText()),
        )
        val current = receive("current", unhex(currentFixtureFile(root).readText()))
        assertTrue("historic STATUS before START reports true", historic.statusPayloads.single().get("running").booleanValue())
        assertEquals("current STATUS before START reports false", false, current.statusPayloads.single().get("running").booleanValue())

        println(
            "STATUS running: historic=${historic.statusPayloads[0].get("running")} " +
                "current=${current.statusPayloads[0].get("running")}",
        )
        assertEquals(
            "same envelope count",
            historic.messages.size, current.messages.size,
        )
        fun types(r: Received): List<String> =
            r.messages.map { Mop.verifyEnvelope(it).first.get("type").asText() }
        assertEquals("same type order", types(historic), types(current))

        // Byte-identical everywhere except the single corrected STATUS bool
        // (plus the digest that covers it).
        var statusBoolDiffs = 0
        for (index in historic.messages.indices) {
            val (oldEnv, oldPayload) = Mop.verifyEnvelope(historic.messages[index])
            val (newEnv, newPayload) = Mop.verifyEnvelope(current.messages[index])
            val type = oldEnv.get("type").asText()
            if (type != "MISSION_STATUS") {
                assertEquals(
                    "message $index ($type) identical",
                    historic.messages[index], current.messages[index],
                )
                continue
            }
            val oldRunning = oldPayload.get("running")
            val newRunning = newPayload.get("running")
            statusBoolDiffs += 1
            assertTrue("STATUS running stays a bool", oldRunning.isBoolean && newRunning.isBoolean)
            assertTrue("historical running true", oldRunning.booleanValue())
            assertEquals("current running false", false, newRunning.booleanValue())
            val oldRest = oldPayload.deepCopy().apply { remove("running") }
            val newRest = newPayload.deepCopy().apply { remove("running") }
            assertEquals(
                "STATUS differs only in running",
                CanonicalJson.digestSha256(oldRest),
                CanonicalJson.digestSha256(newRest),
            )
            val oldDigest = oldEnv.get("payload_sha256").asText()
            val newDigest = newEnv.get("payload_sha256").asText()
            assertTrue("STATUS digest follows its payload", oldDigest != newDigest)
            val oldEnvelopeRest = oldEnv.deepCopy().apply { remove("payload"); remove("payload_sha256") }
            val newEnvelopeRest = newEnv.deepCopy().apply { remove("payload"); remove("payload_sha256") }
            assertEquals("other STATUS envelope fields identical", oldEnvelopeRest, newEnvelopeRest)
        }
        assertEquals("exactly one STATUS running correction", 1, statusBoolDiffs)
    }
}
