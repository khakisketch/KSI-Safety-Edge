package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flight-record contract (A5 fix): the RC device id is install-scoped, but
 * every FLIGHT is its own identity with a pinned mission/bundle snapshot.
 * An unfinished flight is restored after a process restart; finishing it
 * unlocks a new flight id.
 */
class FlightLifecycleTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-flights")

    private fun record(
        flightId: String,
        bundleSha: String = Fixture.hex('d'),
        state: String = "STARTED",
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("flight_id", flightId)
        node.put("site_id", Fixture.uuid(2))
        node.put("mission_id", Fixture.uuid(4))
        node.put("mission_revision_id", Fixture.uuid(5))
        node.put("mission_sha256", Fixture.hex('b'))
        node.put("bundle_id", Fixture.uuid(6))
        node.put("bundle_sha256", bundleSha)
        node.putArray("zone_revision_ids").add(Fixture.uuid(7))
        node.put("started_at_utc", "2026-10-10T01:00:00Z")
        node.putNull("ended_at_utc")
        node.put("state", state)
        return node
    }

    @Test
    fun flight_records_survive_restart_and_keep_their_snapshot() {
        val root = tempRoot()
        val firstFlight = Fixture.uuid(100)
        run {
            val store = FileRcStore(root)
            store.startFlight(record(firstFlight))
            store.updateFlight(firstFlight, "APPLIED", null)
            // Idempotent start: a retry must not fork or duplicate.
            store.startFlight(record(firstFlight))
            assertEquals(1, store.flights().size)
            assertEquals("APPLIED", store.activeFlight()?.get("state")?.asText())
        }
        // Restart: a fresh store instance restores the active flight.
        val restarted = FileRcStore(root)
        val restored = assertNotNull(restarted.activeFlight())
        assertEquals(firstFlight, restored.get("flight_id").asText())
        assertEquals("APPLIED", restored.get("state").asText())
        assertEquals(Fixture.uuid(5), restored.get("mission_revision_id").asText())
        assertEquals(Fixture.hex('d'), restored.get("bundle_sha256").asText())

        // End it: no active flight, history stays immutable.
        restarted.updateFlight(firstFlight, "ENDED", "2026-10-10T02:00:00Z")
        assertNull(restarted.activeFlight())
        assertEquals(
            "ENDED", restarted.flight(firstFlight)?.get("state")?.asText(),
        )
        assertEquals("2026-10-10T01:00:00Z",
            restarted.flight(firstFlight)?.get("started_at_utc")?.asText())

        // A new flight gets a NEW id (the previous flight is untouched).
        val secondFlight = Fixture.uuid(101)
        restarted.startFlight(record(secondFlight))
        assertEquals(secondFlight, restarted.activeFlight()?.get("flight_id")?.asText())
        assertEquals(2, restarted.flights().size)
        assertEquals(firstFlight, restarted.flights().first().get("flight_id").asText())
    }

    @Test
    fun flight_ids_are_per_flight_not_per_install() {
        // Two flights started in sequence never share an id (the A4 bug:
        // a stable install-scoped flight id would merge two flights).
        val store = FileRcStore(tempRoot())
        val ids = mutableListOf<String>()
        for (n in listOf(200, 201, 202)) {
            val flightId = Fixture.uuid(n)
            store.startFlight(record(flightId))
            ids.add(store.activeFlight()!!.get("flight_id").asText())
            store.updateFlight(flightId, "ENDED", "2026-10-10T03:00:00Z")
        }
        assertEquals(3, ids.toSet().size)
        assertNull(store.activeFlight())
    }

    @Test
    fun update_without_record_is_a_noop() {
        val store = FileRcStore(tempRoot())
        store.updateFlight(Fixture.uuid(999), "APPLIED", null)
        assertTrue(store.flights().isEmpty())
        assertNull(store.flight(Fixture.uuid(999)))
    }
}
