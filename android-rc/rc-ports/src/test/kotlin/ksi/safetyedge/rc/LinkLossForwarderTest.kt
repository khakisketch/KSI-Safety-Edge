package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Link-drop forwarding (contract §5/§6): CONNECTED -> lost transitions
 * reach the session-evidence sink exactly once per drop; anything else
 * (startup CONNECTING, regained links, repeated lost states) fires
 * nothing. What counts as a session interruption stays the sink's call.
 */
class LinkLossForwarderTest {
    @Test
    fun fires_once_per_drop_never_on_connect() {
        val link = FakeDroneLink()
        val monitor = LinkStateMonitor()
        monitor.attach(link)
        var forwarded = 0
        LinkLossForwarder(monitor) { forwarded += 1 }

        // Startup handshake (DISCONNECTED -> CONNECTING -> CONNECTED) is
        // not a drop.
        link.connect()
        assertEquals(0, forwarded)
        assertFalse(monitor.snapshot().lost)

        // A real drop fires once.
        link.disconnect()
        assertEquals(1, forwarded)
        assertTrue(monitor.snapshot().lost)

        // Repeated lost states do not re-fire.
        monitor.onState(DroneLink.State.DISCONNECTED)
        assertEquals(1, forwarded)

        // Reconnect fires nothing; the next drop fires again.
        link.connect()
        assertEquals(1, forwarded)
        link.disconnect()
        assertEquals(2, forwarded)
    }

    @Test
    fun connecting_mid_outage_is_not_a_new_drop() {
        val monitor = LinkStateMonitor()
        var forwarded = 0
        LinkLossForwarder(monitor) { forwarded += 1 }

        monitor.onState(DroneLink.State.CONNECTED)
        assertEquals(0, forwarded)
        monitor.onState(DroneLink.State.CONNECTING)
        assertEquals(1, forwarded)
        // Still lost: no second call for the same outage.
        monitor.onState(DroneLink.State.DISCONNECTED)
        assertEquals(1, forwarded)
        monitor.onState(DroneLink.State.CONNECTED)
        assertEquals(1, forwarded)
    }

    @Test
    fun initial_lost_state_does_not_phantom_fire() {
        // A monitor born lost (no CONNECTED ever seen) reports lost, but
        // construction itself is not a drop event.
        val monitor = LinkStateMonitor()
        var forwarded = 0
        LinkLossForwarder(monitor) { forwarded += 1 }
        assertTrue(monitor.snapshot().lost)
        assertEquals(0, forwarded)

        monitor.onState(DroneLink.State.CONNECTED)
        monitor.onState(DroneLink.State.DISCONNECTED)
        assertEquals(1, forwarded)
    }
}
