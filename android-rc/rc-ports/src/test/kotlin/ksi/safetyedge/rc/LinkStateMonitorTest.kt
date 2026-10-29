package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit B contract §5 link exposure: connected vs lost plus the last
 * confirmed activity time, delivered by listener callback — no polling
 * from a UI thread, no blocking of the link reader.
 */
class LinkStateMonitorTest {
    private class Clock {
        var now = 1000L
        fun tick(): Long = ++now
    }

    @Test
    fun attach_tracks_state_and_last_activity() {
        val clock = Clock()
        val link = FakeDroneLink()
        val monitor = LinkStateMonitor(clock = { clock.now })
        val seen = mutableListOf<DroneLink.State>()
        monitor.addStateCallback { seen.add(it) }
        monitor.attach(link)

        assertNull(monitor.lastActivityMonotonicNs)
        assertTrue(monitor.snapshot().lost)

        link.connect() // CONNECTING -> CONNECTED through the monitor
        assertEquals(DroneLink.State.CONNECTED, monitor.state)
        assertEquals(listOf(DroneLink.State.CONNECTING, DroneLink.State.CONNECTED), seen)
        assertFalse(monitor.snapshot().lost)
        val connectedActivity = monitor.lastActivityMonotonicNs
        assertNotNull(connectedActivity)

        clock.tick()
        monitor.onBytes(byteArrayOf(1, 2, 3))
        assertEquals(clock.now, monitor.lastActivityMonotonicNs)

        link.disconnect()
        val snapshot = monitor.snapshot()
        assertTrue(snapshot.lost)
        assertEquals(DroneLink.State.DISCONNECTED, snapshot.state)
        assertEquals(clock.now, snapshot.lastStateChangeMonotonicNs)
    }

    @Test
    fun downstream_still_receives_bytes_and_states() {
        val link = FakeDroneLink()
        val monitor = LinkStateMonitor()
        val bytes = mutableListOf<ByteArray>()
        val states = mutableListOf<DroneLink.State>()
        monitor.attach(link, downstream = object : DroneLink.Listener {
            override fun onBytes(b: ByteArray) { bytes.add(b) }
            override fun onState(state: DroneLink.State) { states.add(state) }
        })
        link.connect()
        monitor.onBytes(byteArrayOf(9))
        assertEquals(1, bytes.size)
        assertEquals(listOf(DroneLink.State.CONNECTING, DroneLink.State.CONNECTED), states)
    }

    @Test
    fun lost_link_shows_last_confirmed_time_not_normality() {
        val clock = Clock()
        val monitor = LinkStateMonitor(clock = { clock.now })
        monitor.onState(DroneLink.State.CONNECTED)
        clock.tick()
        monitor.onBytes(byteArrayOf(7))
        val lastActivity = monitor.lastActivityMonotonicNs
        clock.tick()
        monitor.onState(DroneLink.State.DISCONNECTED)
        val snapshot = monitor.snapshot()
        assertTrue(snapshot.lost)
        // The state change itself is activity, but it must read as LOST —
        // never as a guessed normal state.
        assertEquals(DroneLink.State.DISCONNECTED, snapshot.state)
        assertNotNull(lastActivity)
        assertNotNull(snapshot.lastActivityMonotonicNs)
    }
}
