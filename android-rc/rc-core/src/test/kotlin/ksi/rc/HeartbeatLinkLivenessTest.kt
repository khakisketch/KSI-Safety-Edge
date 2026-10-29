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
 * LINK LIVENESS IS NOT A FLIGHT FACT.
 *
 * The Edge beats before the mission handshake adopts the RC's flight id, so
 * a flight-scoped heartbeat lookup reported "no heartbeat" while the Edge
 * was demonstrably alive (the RC showed "하트비트 미수신" next to a live
 * link). The link-level receipt time is the RC's own clock — the two
 * devices' clocks are not comparable, so the peer's stamp is never used.
 */
class HeartbeatLinkLivenessTest {
    private var counter = 0

    private fun tempRoot(): Path = Files.createTempDirectory("rc-heartbeat-test")

    private fun heartbeat(flightId: String): ObjectNode {
        counter += 1
        return Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge", flightId, "boot-1", counter.toString(), "HEARTBEAT", "HEARTBEAT"),
            channel = "HEARTBEAT",
            type = "HEARTBEAT",
            flightId = flightId,
            edgeBootId = "boot-1",
            seq = counter,
            ackOf = null,
            payload = Mop.json.createObjectNode().put("edge_alive", true),
            sentMonotonicNs = 100L * counter,
        )
    }

    @Test
    fun a_heartbeat_of_another_flight_still_proves_the_link_is_alive() {
        val store = FileRcStore(tempRoot())
        store.storeMessage(heartbeat("edge-own-flight"))

        // Flight-scoped: the RC's own flight has no heartbeat yet.
        assertNull(store.lastHeartbeatUtc("rc-flight"))
        // Link-scoped: the Edge's heartbeat is on the wire and received.
        assertNotNull(store.lastHeartbeatUtcAny())
        assertEquals(store.lastHeartbeatUtc("edge-own-flight"), store.lastHeartbeatUtcAny())
    }

    @Test
    fun the_link_level_receipt_survives_a_restart() {
        val root = tempRoot()
        val store = FileRcStore(root)
        store.storeMessage(heartbeat("edge-own-flight"))
        val receipt = store.lastHeartbeatUtcAny()
        assertNotNull(receipt)

        val restarted = FileRcStore(root)
        assertEquals(receipt, restarted.lastHeartbeatUtcAny())
    }

    @Test
    fun an_unavailable_store_never_claims_a_heartbeat() {
        val store = UnavailableRcStore(java.io.IOException("store unavailable"))
        assertNull(store.lastHeartbeatUtcAny())
    }

    @Test
    fun the_receipt_is_the_rc_clock_not_the_peer_stamp() {
        val store = FileRcStore(tempRoot())
        val env = heartbeat("edge-own-flight")
        store.storeMessage(env)
        val receipt = store.lastHeartbeatUtcAny()
        assertNotNull(receipt)
        // The peer's monotonic stamp is a different epoch entirely: it must
        // never be what the RC compares against its own wall clock.
        assertTrue(receipt.endsWith("Z") || receipt.contains("T"))
        assertTrue(receipt != env.get("sent_monotonic_ns").asText())
    }
}
