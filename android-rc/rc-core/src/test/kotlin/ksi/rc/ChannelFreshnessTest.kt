package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A8 channel-freshness contract: Heartbeat / observation / event receipts
 * are separate facts (an idle AI is not a dead Manifold and vice versa)
 * and all of them are receipt-clock based with restart persistence.
 */
class ChannelFreshnessTest {
    private val flight = Fixture.uuid(1)
    private val boot = Fixture.uuid(2)

    private fun tempRoot(): Path = Files.createTempDirectory("rc-freshness")

    private fun minimalNode(idField: String, id: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put(idField, id)
        node.put("flight_id", flight)
        node.put("frame_received_monotonic_ns", 1L)
        node.put("detected_at_edge_monotonic_ns", 1L)
        return node
    }

    private fun heartbeatEnvelope(): ObjectNode {
        val env = Mop.json.createObjectNode()
        env.put("type", "HEARTBEAT")
        env.put("channel", "HEARTBEAT")
        env.put("flight_id", flight)
        env.put("edge_boot_id", boot)
        return env
    }

    @Test
    fun channels_are_tracked_separately_and_survive_restart() {
        var now = "2026-10-10T04:00:00Z"
        val root = tempRoot()
        val store = FileRcStore(root, wallClock = { now })

        // Manifold heartbeat arrives first.
        store.storeMessage(heartbeatEnvelope())
        assertEquals("2026-10-10T04:00:00Z", store.lastHeartbeatUtc(flight))
        assertNull(store.lastObservationUtc(flight))
        assertNull(store.lastEventUtc(flight))

        // An observation later must NOT refresh the heartbeat channel, and
        // the heartbeat must NOT make observations look fresh.
        now = "2026-10-10T04:00:05Z"
        store.storeObservation(minimalNode("observation_id", Fixture.uuid(10)), boot)
        assertEquals("2026-10-10T04:00:05Z", store.lastObservationUtc(flight))
        assertEquals("2026-10-10T04:00:00Z", store.lastHeartbeatUtc(flight))

        now = "2026-10-10T04:00:09Z"
        store.storeEvent(minimalNode("event_id", Fixture.uuid(11)), boot)
        assertEquals("2026-10-10T04:00:09Z", store.lastEventUtc(flight))
        assertEquals(
            "2026-10-10T04:00:09Z", store.lastReceivedUtc(flight),
            "overall freshness stays the max across channels",
        )

        // A second heartbeat moves only the heartbeat channel.
        now = "2026-10-10T04:00:12Z"
        store.storeMessage(heartbeatEnvelope())
        assertEquals("2026-10-10T04:00:12Z", store.lastHeartbeatUtc(flight))
        assertEquals("2026-10-10T04:00:05Z", store.lastObservationUtc(flight))

        // Restart: the heartbeat receipt is durable.
        val restarted = FileRcStore(root, wallClock = { now })
        assertEquals("2026-10-10T04:00:12Z", restarted.lastHeartbeatUtc(flight))
        assertEquals("2026-10-10T04:00:05Z", restarted.lastObservationUtc(flight))
        assertEquals("2026-10-10T04:00:09Z", restarted.lastEventUtc(flight))
    }

    @Test
    fun heartbeat_receipts_do_not_enter_the_evidence_channels() {
        val store = FileRcStore(tempRoot(), wallClock = { "2026-10-10T05:00:00Z" })
        store.storeMessage(heartbeatEnvelope())
        // No observations/events were received; a heartbeat alone is not
        // evidence and must not fabricate freshness for those channels.
        assertNull(store.lastObservationUtc(flight))
        assertNull(store.lastEventUtc(flight))
        assertNull(store.receivedFirstLastUtc(flight, boot))
    }
}
