package ksi.msdk

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.CanonicalJson
import ksi.rc.ControlHandler
import ksi.rc.EvidenceIngest
import ksi.rc.FileRcStore
import ksi.rc.Inbound
import ksi.rc.Mop
import ksi.rc.MopEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * A9-2 cross-language transport E2E with the C++ edge implementation.
 *
 * Fixtures under `contracts/mop/` are produced by the REAL implementations
 * and then permanently pinned (see the README there):
 *  - `rc-to-edge.mopv1.hex` — generated HERE (this test reproduces it
 *    byte-for-byte; a mismatch means the RC command bytes changed);
 *  - `edge-to-rc.mopv1.hex` — produced by the REAL C++ endpoint+link
 *    (edge/tests/test_mop_e2e.cpp); consumed here through the REAL
 *    [MopEndpoint] + [EvidenceIngest] + [FileRcStore] path, so an envelope
 *    the C++ side built must survive the actual RC evidence pipeline
 *    (JCS digests included) or this test fails.
 */
class MopCrossLanguageTest {
    private val contractsDir = File("../../contracts/mop")

    private class StepClock {
        private var now = 0L
        fun next(): Long {
            now += 1_000_000
            return now
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) {
            ((Character.digit(text[it * 2], 16) shl 4) +
                Character.digit(text[it * 2 + 1], 16)).toByte()
        }

    private fun framesOf(bytes: ByteArray): ByteArray =
        MopFrameCodec.encode(bytes).fold(ByteArray(0)) { acc, f -> acc + f }

    private fun makeBundle(): ObjectNode {
        val bundle = Mop.json.createObjectNode()
        bundle.put("bundle_id", Mop.newMsgId("bundle", "flt-e2e"))
        bundle.put("site_id", "site-e2e")
        bundle.set<ObjectNode>(
            "mission_snapshot",
            Mop.json.createObjectNode().put("site_id", "site-e2e"),
        )
        bundle.set<ObjectNode>(
            "risk_policy",
            Mop.json.createObjectNode().put("site_id", "site-e2e"),
        )
        bundle.put(
            "bundle_sha256",
            CanonicalJson.digestExcluding(bundle, "bundle_sha256"),
        )
        return bundle
    }

    private fun tempStore(): Pair<FileRcStore, File> {
        val dir = Files.createTempDirectory("mop-e2e").toFile()
        return FileRcStore(dir.toPath()) to dir
    }

    // --- RC→Edge commands: pinned exactly by this test -----------------------

    private fun buildRcCommands(store: FileRcStore): ByteArray {
        val clock = StepClock()
        val endpoint = MopEndpoint(
            flightId = "flt-e2e",
            store = store,
            clock = { clock.next() },
            handler = { _, _ -> null },
        )
        val out = ByteArrayOutputStream()
        fun send(type: String, payload: ObjectNode) {
            out.write(framesOf(Mop.json.writeValueAsBytes(endpoint.sendControl(type, payload))))
        }
        val bundle = makeBundle()
        send(
            "MISSION_PREPARE",
            Mop.json.createObjectNode().set<ObjectNode>("bundle", bundle),
        )
        send(
            "MISSION_APPLY",
            Mop.json.createObjectNode()
                .put("bundle_id", bundle.get("bundle_id").asText())
                .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
        )
        send("MISSION_STATUS_QUERY", Mop.json.createObjectNode())
        send("SESSION_START", Mop.json.createObjectNode())
        return out.toByteArray()
    }

    @Test
    fun rc_commands_are_pinned_by_the_fixture() {
        val (store, dir) = tempStore()
        try {
            val produced = buildRcCommands(store)
            val fixture = File(contractsDir, "rc-to-edge.mopv1.hex")
            if (!fixture.exists()) {
                println("GENERATE rc-to-edge.mopv1.hex:")
                println(hex(produced))
                fail("rc-to-edge fixture missing — commit the printed hex")
            }
            assertEquals(fixture.readText().trim(), hex(produced))
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Edge→RC stream: consumed through the real evidence path -------------

    @Test
    fun cpp_edge_stream_reaches_evidence_ingest() {
        val fixture = File(contractsDir, "edge-to-rc.mopv1.hex")
        if (!fixture.exists()) {
            fail(
                "edge-to-rc fixture missing — run edge/tests/test_mop_e2e.cpp " +
                    "(it prints the hex when absent) and commit it",
            )
        }
        val bytes = unhex(fixture.readText().trim())
        val (store, dir) = tempStore()
        try {
            val clock = StepClock()
            val ingest = EvidenceIngest(store)
            // Composite routing like the app: data-plane -> EvidenceIngest;
            // control responses accepted here (MissionController binding is
            // exercised by rc-core's own tests and the A9-4 device session).
            val handler = ControlHandler { env, payload ->
                when (env.get("type").asText()) {
                    "OBSERVATION", "DETECTION_EVENT", "FLIGHT_SUMMARY",
                    "ARTIFACT_ANNOUNCE", "ARTIFACT_CHUNK", "HEARTBEAT",
                    -> ingest.handle(env, payload)
                    else -> null
                }
            }
            val endpoint = MopEndpoint("flt-e2e", store, { clock.next() }, handler)

            val reassembler = MopReassembler()
            val messages = reassembler.feed(bytes)
            var stored = 0
            var failed = 0
            for (message in messages) {
                when (val inbound = endpoint.onRawEnvelope(message.decodeToString())) {
                    is Inbound.Stored -> stored += 1
                    is Inbound.Acked, is Inbound.Duplicate -> Unit
                    is Inbound.Failed -> {
                        failed += 1
                        System.err.println("inbound failed: ${inbound.type} ${inbound.reason}")
                    }
                }
            }
            assertEquals("no inbound failures", 0, failed)
            assertEquals(0L, reassembler.errors)

            // The C++-produced envelopes reached the REAL store.
            assertEquals(1, store.observationsFor("flt-e2e").size)
            assertEquals(1, store.eventsFor("flt-e2e").size)
            assertEquals(1, store.summariesFor("flt-e2e").size)
            val artifacts = store.artifactsFor("flt-e2e")
            assertEquals(1, artifacts.size)

            // Artifact bytes: the exact pattern the C++ side emitted, and the
            // announced sha256 matches the assembled content.
            val artifactId = artifacts[0].get("artifact_id").asText()
            val assembled = store.artifactBytes(artifactId)
            assertNotNull("artifact fully assembled (coverage+size+sha)", assembled)
            val expected = ByteArray(100) { (it % 251).toByte() }
            assertArrayEquals(expected, assembled)
            val sha = MessageDigest.getInstance("SHA-256").digest(assembled!!)
                .joinToString("") { "%02x".format(it) }
            assertEquals(artifacts[0].get("sha256").asText(), sha)

            // Sequence bookkeeping the upload manifest depends on.
            val boot = Mop.newMsgId("boot", "flt-e2e", "0")
            assertEquals(setOf(1L, 2L, 3L), store.receivedSeqs(boot, "EVENT_RELIABLE"))
            assertEquals(setOf(1L, 2L), store.receivedSeqs(boot, "EVIDENCE_TRANSFER"))
            assertNotNull(store.lastHeartbeatUtc("flt-e2e"))

            // Every stored message was ACKed (commit-before-ACK held).
            assertEquals(stored, endpoint.ackedCount)
            assertTrue(stored >= 8)
        } finally {
            dir.deleteRecursively()
        }
    }
}
