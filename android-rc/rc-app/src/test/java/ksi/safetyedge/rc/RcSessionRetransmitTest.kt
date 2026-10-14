package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import ksi.rc.FileRcStore
import ksi.rc.Mop

/**
 * A8 retransmission tick: sends only when the link is connected, never
 * drops queued bytes, never double-sends one envelope within a tick, and
 * re-sends the SAME bytes (same msg_id) on later ticks.
 */
class RcSessionRetransmitTest {
    private fun session(link: FakeDroneLink): RcSession = RcSession(
        storeDir = Files.createTempDirectory("rc-session").toFile(),
        rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
        wallClock = { "2026-10-10T07:00:00Z" },
        link = link,
        coreConfig = { null },
    )

    private fun prepareOne(session: RcSession) {
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        session.attachController(bundle, "00000000-0000-4000-8000-0000000000f1")
        session.controller!!.prepare()
    }

    @Test
    fun foreground_listener_receives_link_state_changes_and_can_detach() {
        val link = FakeDroneLink()
        val session = session(link)
        var updates = 0
        session.setUiStateListener { updates += 1 }

        link.connect() // CONNECTING, then CONNECTED
        assertEquals(2, updates)

        session.setUiStateListener(null)
        link.disconnect()
        assertEquals(2, updates)
    }

    @Test
    fun tick_sends_once_when_connected_then_retransmits_identical_bytes() {
        val link = FakeDroneLink()
        val session = session(link)
        prepareOne(session)
        assertEquals(1, session.controller!!.endpoint.outbox.size)

        // Link down: nothing sent, nothing dropped.
        assertEquals(0, session.retransmitTick())
        assertEquals(1, session.controller!!.endpoint.outbox.size)
        assertEquals(0, link.sentBytes.size)

        // Link up: the tick performs the INITIAL send exactly once
        // (no fresh+retransmit double-send of the same envelope).
        link.connect()
        assertEquals(1, session.retransmitTick())
        assertEquals(0, session.controller!!.endpoint.outbox.size)
        assertEquals(1, link.sentBytes.size)

        // Next tick: the still-unacked envelope is re-sent with the SAME
        // msg_id and byte-identical content.
        assertEquals(1, session.retransmitTick())
        assertEquals(2, link.sentBytes.size)
        val first = String(link.sentBytes[0], Charsets.UTF_8)
        val second = String(link.sentBytes[1], Charsets.UTF_8)
        assertEquals("retransmission must be byte-identical", first, second)
        val env = Mop.verifyEnvelope(second).first
        java.util.UUID.fromString(env.get("msg_id").asText())
    }

    @Test
    fun manual_retry_does_not_double_send_fresh_envelopes() {
        val link = FakeDroneLink()
        val session = session(link)
        prepareOne(session)
        link.connect()
        // Manual retry on a fresh (never-sent) envelope: sent once via the
        // pump path, skipped by the retry path.
        val sent = session.retryUnacked() + session.pumpOutbound()
        assertEquals(1, sent)
        assertEquals(1, link.sentBytes.size)
        assertEquals(0, session.controller!!.endpoint.outbox.size)
    }
}
