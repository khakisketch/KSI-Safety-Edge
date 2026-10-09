package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.FileRcStore
import ksi.rc.Mop

/**
 * A6 host-side tests for the pure observation model: derivations from the
 * (real) FileRcStore only — detection vs candidate labels, evidence
 * completeness/sync states, withheld/degraded counts, and receipt-clock
 * freshness. No Android dependency.
 */
class ObservationModelTest {
    private val flight = "00000000-0000-4000-8000-000000000001"
    private val fixedUtc = "2026-10-10T02:00:00Z"

    private fun store(): FileRcStore = FileRcStore(
        Files.createTempDirectory("obs-model"),
        wallClock = { fixedUtc },
    )

    private fun observation(id: String, withheld: Boolean, degraded: Boolean): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("observation_id", id)
        node.put("flight_id", flight)
        node.put("frame_received_monotonic_ns", 1000L)
        node.put("geometry_status", if (withheld) "WITHHELD" else "UNVERIFIED")
        node.put("sync_quality", if (degraded) "DEGRADED" else "VALID")
        return node
    }

    private fun event(id: String, type: String, artifacts: List<String>): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("event_id", id)
        node.put("flight_id", flight)
        node.put("event_type", type)
        node.put("mode", "SHADOW")
        node.put("detected_at_edge_monotonic_ns", 12345L)
        node.put("observation_quality", "DEGRADED")
        val ids = node.putArray("evidence_artifact_ids")
        artifacts.forEach { ids.add(it) }
        return node
    }

    private fun announce(artifactId: String, bytes: ByteArray): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("artifact_id", artifactId)
        node.put("flight_id", flight)
        node.put("sha256", sha256Hex(bytes))
        node.put("size_bytes", bytes.size.toLong())
        return node
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun cards_distinguish_object_detection_from_shadow_candidates() {
        val store = store()
        store.storeEvent(event("e-detect", "DETECTED_ONLY", emptyList()), "boot")
        store.storeEvent(event("e-candidate", "PROXIMITY_CANDIDATE", emptyList()), "boot")

        val cards = ObservationModel.eventCards(store, flight)
        assertEquals(2, cards.size)
        // Newest first (insertion order reversed).
        assertEquals("e-candidate", cards[0].eventId)
        assertFalse(cards[0].detectionOnly)
        assertEquals("SHADOW", cards[0].mode)
        assertEquals("e-detect", cards[1].eventId)
        assertTrue(cards[1].detectionOnly)
        // No evidence ids: honestly "no evidence linked" (not "complete").
        assertFalse(cards[0].evidenceComplete)
        assertFalse(cards[0].evidenceSynced)
    }

    @Test
    fun evidence_states_track_local_completeness_and_sync() {
        val store = store()
        val fullBytes = ByteArray(8) { it.toByte() }
        val partialBytes = ByteArray(8) { (it + 8).toByte() }
        store.announceArtifact(announce("a-full", fullBytes), "boot")
        store.writeChunk("a-full", 0, fullBytes)
        store.announceArtifact(announce("a-partial", partialBytes), "boot")
        store.writeChunk("a-partial", 0, partialBytes.copyOfRange(0, 4))
        store.storeEvent(event("e1", "SPATIAL_CANDIDATE", listOf("a-full", "a-partial")), "boot")

        val card = ObservationModel.eventCards(store, flight).single()
        val full = card.evidence.first { it.artifactId == "a-full" }
        val partial = card.evidence.first { it.artifactId == "a-partial" }
        assertTrue(full.complete)
        assertFalse(full.synced)
        assertFalse(partial.complete)
        assertFalse(card.evidenceComplete)

        // Sync mark arrives only via the uploader (COMMITTED); the model
        // just reports it.
        store.markArtifactSynced("a-full")
        val after = ObservationModel.eventCards(store, flight).single()
            .evidence.first { it.artifactId == "a-full" }
        assertTrue(after.synced)
    }

    @Test
    fun pulse_counts_withheld_degraded_and_receipt_freshness() {
        val store = store()
        store.storeObservation(observation("o1", withheld = true, degraded = false), "boot")
        store.storeObservation(observation("o2", withheld = false, degraded = true), "boot")

        val pulse = ObservationModel.observationPulse(store, flight)
        assertEquals(2, pulse.count)
        assertEquals(1, pulse.withheld)
        assertEquals(1, pulse.degraded)
        assertEquals(fixedUtc, pulse.lastReceivedUtc)

        assertEquals(
            3_000L,
            ObservationModel.lastDataAgeMs(fixedUtc, "2026-10-10T02:00:03Z"),
        )
        // A clock that went backwards clamps to zero — never negative.
        assertEquals(
            0L,
            ObservationModel.lastDataAgeMs(fixedUtc, "2026-10-10T01:59:59Z"),
        )
        assertNull(ObservationModel.lastDataAgeMs(null, fixedUtc))
        assertNull(ObservationModel.lastDataAgeMs("not-a-time", fixedUtc))
    }
}
