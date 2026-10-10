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
    fun received_vs_hash_verified_levels_are_distinct() {
        val store = store()
        val bytes = ByteArray(8) { it.toByte() }
        val lying = Mop.json.createObjectNode()
        lying.put("artifact_id", "a-lying")
        lying.put("flight_id", flight)
        lying.put("sha256", "ab".repeat(32)) // declared hash does NOT match
        lying.put("size_bytes", 8L)
        store.announceArtifact(lying, "boot")
        store.writeChunk("a-lying", 0, bytes)
        store.storeEvent(event("e2", "DETECTED_ONLY", listOf("a-lying")), "boot")

        // List level: all bytes received -> "complete" (cheap).
        val link = ObservationModel.eventCards(store, flight).single().evidence.single()
        assertTrue(link.complete)
        // Verified level: the hash level refuses the mismatched content.
        assertNull(store.artifactBytes("a-lying"))
    }

    @Test
    fun last_received_utc_uses_the_newest_timestamp_across_record_kinds() {
        var now = "2026-10-10T02:00:01Z"
        val store = FileRcStore(
            Files.createTempDirectory("obs-model-clock"),
            wallClock = { now },
        )
        store.storeObservation(observation("o-early", withheld = false, degraded = false), "boot")
        // A gap declaration arrives later (stamped by the same receipt clock).
        val gap = Mop.json.createObjectNode()
        gap.put("flight_id", flight)
        gap.put("edge_boot_id", "boot")
        gap.put("channel", "EVENT_RELIABLE")
        gap.put("seq", 9L)
        gap.put("reason", "UNRECOVERABLE:EVENT_RELIABLE")
        now = "2026-10-10T02:00:05Z"
        store.recordGap(gap)
        // An artifact announcement arrives even later.
        now = "2026-10-10T02:00:09Z"
        store.announceArtifact(announce("a-late", ByteArray(4)), "boot")

        assertEquals("2026-10-10T02:00:09Z", store.lastReceivedUtc(flight))
        assertEquals(
            1_000L,
            ObservationModel.lastDataAgeMs(
                store.lastReceivedUtc(flight), "2026-10-10T02:00:10Z",
            ),
        )
    }

    @Test
    fun channel_freshness_is_separated_and_verdict_distinguishes_zero_events() {
        var now = "2026-10-10T06:00:00Z"
        val store = FileRcStore(
            Files.createTempDirectory("obs-channels"),
            wallClock = { now },
        )
        val heartbeat = Mop.json.createObjectNode()
        heartbeat.put("type", "HEARTBEAT")
        heartbeat.put("channel", "HEARTBEAT")
        heartbeat.put("flight_id", flight)
        heartbeat.put("edge_boot_id", "boot")
        store.storeMessage(heartbeat)

        now = "2026-10-10T06:00:10Z"
        store.storeObservation(observation("o1", withheld = false, degraded = false), "boot")

        // Ages are per-channel: the heartbeat age is measured from ITS
        // receipt, not from the newer observation.
        val at = "2026-10-10T06:00:12Z"
        assertEquals(12_000L, ObservationModel.heartbeatAgeMs(store, flight, at))
        assertEquals(2_000L, ObservationModel.observationAgeMs(store, flight, at))
        assertNull(ObservationModel.eventAgeMs(store, flight, at))

        assertEquals(
            "OK",
            ObservationModel.classifyFreshness(2_000L, ObservationModel.STALE_DATA_MS),
        )
        assertEquals(
            "STALE",
            ObservationModel.classifyFreshness(
                40_000L, ObservationModel.HEARTBEAT_STALE_MS,
            ),
        )
        assertEquals("NONE", ObservationModel.classifyFreshness(null, 1L))

        assertEquals("관측 공백 — 수신된 관측 없음", ObservationModel.aiVerdict(0, 0))
        assertEquals("분석됨(관측 2) · 사건 0건", ObservationModel.aiVerdict(2, 0))
        assertEquals("사건 1건 수신 (관측 2)", ObservationModel.aiVerdict(2, 1))
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
