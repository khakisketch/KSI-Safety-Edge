package ksi.safetyedge.rc

import java.nio.file.Files
import ksi.rc.Mop
import org.junit.Assert.*
import org.junit.Test

class MissionRequestProgressTest {
    private val plan = DemoFeed.demoBundle("00000000-0000-4000-8000-0000000000b1", "00000000-0000-4000-8000-0000000000b2")
    private val sha = plan.path("bundle_sha256").asText()
    private val link = FakeDroneLink()
    private val session = RcSession(Files.createTempDirectory("mission-request-progress").toFile(),
        "00000000-0000-4000-8000-0000000000aa", { "2026-10-26T12:00:00Z" }, link, { null })

    private fun facts() = WorkspaceMissionFacts.derive(WorkspaceMissionFacts.inputFrom(sha, session.controller,
        link.state == DroneLink.State.CONNECTED))

    @Test fun offline_queue_send_link_loss_and_response_are_distinct() {
        val id = session.prepareBundle(plan)
        val controller = session.controller!!
        controller.prepare()
        assertEquals(0, session.pumpOutbound())
        assertEquals(setOf("MISSION_PREPARE"), session.unsentMissionRequestTypes(sha))
        assertEquals("전송 대기", MissionPreparationPresentation.step(facts().prepare, true))
        assertTrue(session.unsentMissionRequestTypes("foreign-sha").isEmpty())
        assertTrue(session.unsentMissionRequestTypes(null).isEmpty())
        assertNull(session.activeFlight)

        link.connect()
        assertTrue(session.pumpOutbound() > 0)
        assertTrue(session.unsentMissionRequestTypes(sha).isEmpty())
        assertEquals("응답 대기", MissionPreparationPresentation.step(facts().prepare, false))
        link.disconnect()
        assertTrue(session.unsentMissionRequestTypes(sha).isEmpty())
        assertEquals(WorkspaceMissionFacts.Step.PENDING, facts().prepare)

        // A real protocol response, not an envelope count or a link transition.
        session.onLinkBytes(Mop.json.writeValueAsBytes(Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge-test", id, "MISSION_PREPARED", "1"),
            channel = "CONTROL_RELIABLE", type = "MISSION_PREPARED", flightId = id,
            edgeBootId = "00000000-0000-4000-8000-0000000000e1", seq = null, ackOf = null,
            payload = Mop.json.createObjectNode().put("bundle_sha256", sha), sentMonotonicNs = 1_000_000L,
        )))
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, facts().prepare)
        assertTrue(facts().linkStale)
        assertTrue(session.store.flights().isEmpty())
        controller.apply()
        assertEquals(setOf("MISSION_APPLY"), session.unsentMissionRequestTypes(sha))
        assertEquals(WorkspaceMissionFacts.Step.PENDING, facts().apply)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, facts().prepare)
    }
}
