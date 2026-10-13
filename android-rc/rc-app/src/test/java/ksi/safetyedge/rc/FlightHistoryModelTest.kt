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
 * A7 host-side tests: flight-history rows/details derive from the real
 * FileRcStore, annotation chains are append-only (mark/revoke), and the
 * cheap (received) vs verified (hash) evidence levels stay distinct.
 */
class FlightHistoryModelTest {
    private val flightA = "00000000-0000-4000-8000-000000000101"
    private val flightB = "00000000-0000-4000-8000-000000000102"
    private val deviceId = "00000000-0000-4000-8000-0000000000aa"
    private val operatorId = "00000000-0000-4000-8000-0000000000ab"
    private val fixedUtc = "2026-10-10T03:00:00Z"

    private fun store(root: java.nio.file.Path = Files.createTempDirectory("hist")) =
        FileRcStore(root, wallClock = { fixedUtc })

    private fun flightRecord(
        flightId: String,
        siteId: String = "00000000-0000-4000-8000-0000000000b1",
        zoneIds: List<String> = emptyList(),
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("flight_id", flightId)
        node.put("site_id", siteId)
        node.put("mission_id", "00000000-0000-4000-8000-0000000000c1")
        node.put("mission_revision_id", "00000000-0000-4000-8000-0000000000c2")
        node.put("mission_sha256", "bb".repeat(32))
        node.put("bundle_id", "00000000-0000-4000-8000-0000000000c3")
        node.put("bundle_sha256", "cc".repeat(32))
        val zones = node.putArray("zone_revision_ids")
        zoneIds.forEach { zones.add(it) }
        node.put("started_at_utc", fixedUtc)
        node.putNull("ended_at_utc")
        node.put("state", "ENDED")
        return node
    }

    private fun observation(flightId: String, id: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("observation_id", id)
        node.put("flight_id", flightId)
        node.put("frame_received_monotonic_ns", 1000L)
        node.put("geometry_status", "UNVERIFIED")
        node.put("sync_quality", "VALID")
        return node
    }

    private fun event(flightId: String, id: String, type: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("event_id", id)
        node.put("flight_id", flightId)
        node.put("event_type", type)
        node.put("mode", "SHADOW")
        node.put("detected_at_edge_monotonic_ns", 42L)
        node.put("observation_quality", "VALID")
        node.putArray("evidence_artifact_ids")
        return node
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun rows_are_newest_first_and_zero_event_flights_stay_normal() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-a1"), "boot")
        store.storeEvent(event(flightA, "e-a1", "PROXIMITY_CANDIDATE"), "boot")
        store.startFlight(flightRecord(flightB))
        store.storeObservation(observation(flightB, "o-b1"), "boot")

        val rows = FlightHistoryModel.flights(store)
        assertEquals(2, rows.size)
        assertEquals("newest flight first", flightB, rows[0].flightId)
        assertEquals(0, rows[0].eventCount)
        assertEquals(1, rows[0].observationCount)
        assertEquals("사건 0건도 정상 기록", "ENDED", rows[0].state)
        assertEquals(flightA, rows[1].flightId)
        assertEquals(1, rows[1].eventCount)
        assertEquals(0, rows[1].detectionOnlyCount)
        assertEquals(1, rows[1].candidateCount)
    }

    @Test
    fun annotation_chain_is_append_only_with_effective_state() {
        val root = Files.createTempDirectory("hist-ann")
        val store = store(root)
        store.startFlight(flightRecord(flightA))
        store.storeEvent(event(flightA, "e-a1", "SPATIAL_CANDIDATE"), "boot")

        val mark = FlightHistoryModel.falsePositiveAnnotation(
            eventId = "e-a1",
            flightId = flightA,
            actorId = operatorId,
            clientId = deviceId,
            artifactIds = listOf("a-1"),
            createdAtLocal = fixedUtc,
        )
        store.queueAnnotation(mark)
        // Idempotent by annotation_id.
        store.queueAnnotation(mark)
        var detail = FlightHistoryModel.detail(store, flightA)!!
        assertEquals(1, detail.events.single().annotationCount)
        assertEquals(
            FlightHistoryModel.ANNOTATION_FALSE_POSITIVE,
            detail.events.single().effectiveAnnotation,
        )
        assertEquals(1, detail.row.falsePositiveCount)
        assertEquals(operatorId, detail.events.single().effectiveActorId)

        // Undo = a NEW REVOKE_PREVIOUS record (original preserved).
        val revoke = FlightHistoryModel.revokePrevious(mark, operatorId, deviceId, fixedUtc)
        assertEquals(
            mark.get("annotation_id").asText(),
            revoke.get("supersedes_annotation_id").asText(),
        )
        store.queueAnnotation(revoke)
        detail = FlightHistoryModel.detail(store, flightA)!!
        assertEquals(2, detail.events.single().annotationCount)
        assertNull(detail.events.single().effectiveAnnotation)
        assertNull(detail.events.single().effectiveActorId)

        // Restart: both records survive (append-only log).
        val restarted = FileRcStore(root, wallClock = { fixedUtc })
        assertEquals(2, restarted.annotationsFor("e-a1").size)
    }

    @Test
    fun false_positive_annotation_matches_the_contract_fields() {
        val eventUuid = "00000000-0000-4000-8000-0000000000e1"
        val artifactA = "00000000-0000-4000-8000-0000000000f1"
        val artifactB = "00000000-0000-4000-8000-0000000000f2"
        val mark = FlightHistoryModel.falsePositiveAnnotation(
            eventId = eventUuid,
            flightId = flightA,
            actorId = operatorId,
            clientId = deviceId,
            artifactIds = listOf(artifactA, artifactB),
            createdAtLocal = fixedUtc,
        )
        assertEquals(1, mark.get("schema_version").asInt())
        assertEquals(eventUuid, mark.get("event_id").asText())
        assertEquals(flightA, mark.get("flight_id").asText())
        assertEquals(operatorId, mark.get("actor_id").asText())
        assertEquals(deviceId, mark.get("client_id").asText())
        assertEquals(
            FlightHistoryModel.ANNOTATION_FALSE_POSITIVE,
            mark.get("annotation_type").asText(),
        )
        assertTrue(mark.get("reason").isNull)
        assertEquals(2, mark.get("evidence_ids").size())
        assertEquals(fixedUtc, mark.get("created_at_local").asText())
        assertTrue(mark.get("supersedes_annotation_id").isNull)
        // Schema FACETS, not just presence: uuid ids + RFC3339 time must
        // parse — a builder emitting malformed ids would fail here.
        java.util.UUID.fromString(mark.get("annotation_id").asText())
        java.util.UUID.fromString(mark.get("event_id").asText())
        java.util.UUID.fromString(mark.get("flight_id").asText())
        java.util.UUID.fromString(mark.get("actor_id").asText())
        java.util.UUID.fromString(mark.get("client_id").asText())
        mark.get("evidence_ids").forEach {
            java.util.UUID.fromString(it.asText())
        }
        java.time.Instant.parse(mark.get("created_at_local").asText())

        val revoke = FlightHistoryModel.revokePrevious(mark, operatorId, deviceId, fixedUtc)
        assertEquals(
            mark.get("annotation_id").asText(),
            revoke.get("supersedes_annotation_id").asText(),
        )
        java.util.UUID.fromString(revoke.get("supersedes_annotation_id").asText())
    }

    @Test
    fun detail_reports_scene_pins_and_evidence_levels() {
        val store = store()
        val zoneStored = "00000000-0000-4000-8000-0000000000d1"
        val zoneMissing = "00000000-0000-4000-8000-0000000000d2"
        store.saveSite(
            Mop.json.createObjectNode()
                .put("site_id", "00000000-0000-4000-8000-0000000000b1")
                .put("name", "테스트 현장"),
        )
        store.saveZoneRevision(
            Mop.json.createObjectNode()
                .put("revision_id", zoneStored)
                .put("zone_id", "00000000-0000-4000-8000-0000000000d0")
                .put("site_id", "00000000-0000-4000-8000-0000000000b1")
                .put("rule_kind", "PERSON_ENTRY"),
        )
        store.startFlight(flightRecord(flightA, zoneIds = listOf(zoneStored, zoneMissing)))

        val bytes = ByteArray(8) { it.toByte() }
        val good = Mop.json.createObjectNode()
        good.put("artifact_id", "a-good")
        good.put("flight_id", flightA)
        good.put("sha256", sha256Hex(bytes))
        good.put("size_bytes", 8L)
        good.put("media_type", "image/jpeg")
        store.announceArtifact(good, "boot")
        store.writeChunk("a-good", 0, bytes)

        val detail = FlightHistoryModel.detail(store, flightA)!!
        assertEquals("테스트 현장", detail.row.siteName)
        assertEquals(2, detail.zonePins.size)
        assertTrue(detail.zonePins[0].stored)
        assertEquals("PERSON_ENTRY", detail.zonePins[0].ruleKind)
        assertFalse(detail.zonePins[1].stored)
        assertEquals(1, detail.artifacts.size)
        assertTrue(detail.artifacts[0].complete)
        assertFalse(detail.artifacts[0].synced)
        // Verified level succeeds for a correct hash.
        assertEquals(8, store.artifactBytes("a-good")!!.size)
        assertNull(FlightHistoryModel.detail(store, "missing-flight"))
    }
}
