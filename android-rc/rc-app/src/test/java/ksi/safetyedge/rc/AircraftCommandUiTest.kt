package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the integrated operation screen's command text contract:
 * every outcome and every gate refusal maps to its own honest Korean
 * line, and a request outcome never reads as an execution confirmation.
 */
class AircraftCommandUiTest {

    private val unverifiedCapabilities = AircraftCommandPort.Capabilities(
        AircraftCommandPort.Command.values().associateWith { AircraftCommandPort.Support.UNVERIFIED },
    )

    @Test
    fun camera_requires_a_confirmed_aircraft_not_a_payload_connection() {
        val unknown = AircraftCommandUi.evaluateCommand(
            AircraftCommandPort.Command.PHOTO, null, unverifiedCapabilities,
        )
        assertEquals(false, unknown.allowed)
        val blocked = AircraftCommandUi.blockedLine(
            AircraftCommandPort.Command.PHOTO, unknown.refusal, unknown.detail,
        )
        assertTrue(blocked.contains("미확인"))
        assertTrue(!blocked.contains("기체 연결 없음"))
        assertEquals(
            true,
            AircraftCommandUi.evaluateCommand(
                AircraftCommandPort.Command.PHOTO, true, unverifiedCapabilities,
            ).allowed,
        )
    }

    @Test
    fun connected_aircraft_never_implies_confirmed_flight_authority() {
        for (command in listOf(
            AircraftCommandPort.Command.TAKEOFF,
            AircraftCommandPort.Command.LAND,
            AircraftCommandPort.Command.RETURN_HOME,
        )) {
            val gate = AircraftCommandUi.evaluateCommand(command, true, unverifiedCapabilities)
            assertEquals(false, gate.allowed)
            assertEquals(CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN, gate.refusal)
            val unknown = AircraftCommandUi.evaluateCommand(command, null, unverifiedCapabilities)
            assertTrue(unknown.detail!!.contains("미확인"))
            assertTrue(!unknown.detail!!.contains("단절"))
        }
    }

    @Test
    fun delivered_names_the_request_and_defers_to_aircraft_state() {
        val text = AircraftCommandUi.outcomeText(
            AircraftCommandPort.Outcome.DELIVERED, null,
        )
        assertTrue(text.contains("요청 전달됨"))
        assertTrue(text.contains("기체 상태로 확인"))
    }

    @Test
    fun each_outcome_has_its_own_text() {
        val texts = AircraftCommandPort.Outcome.values().associateWith { outcome ->
            AircraftCommandUi.outcomeText(outcome, null)
        }
        assertEquals(
            "기체가 요청을 거절함 · 기체 상태 확인",
            texts[AircraftCommandPort.Outcome.REJECTED],
        )
        assertEquals(
            "RC 측 요건 미충족 · 전송 안 됨",
            texts[AircraftCommandPort.Outcome.NOT_PERMITTED],
        )
        assertEquals(
            "기체 연결 없음 · 전송 안 됨",
            texts[AircraftCommandPort.Outcome.UNAVAILABLE],
        )
        assertEquals(
            "결과 판별 불가 · 기체 상태로 확인",
            texts[AircraftCommandPort.Outcome.UNKNOWN],
        )
        assertEquals(
            AircraftCommandPort.Outcome.values().size,
            texts.values.toSet().size,
        )
    }

    @Test
    fun bench_echo_detail_is_not_duplicated() {
        // FakeAircraftCommand echoes the canonical wording as its detail.
        val echoed = AircraftCommandUi.outcomeText(
            AircraftCommandPort.Outcome.DELIVERED,
            "요청 전달됨 · 실행 결과는 기체 상태로 확인",
        )
        assertEquals("요청 전달됨 · 실행 결과는 기체 상태로 확인", echoed)
    }

    @Test
    fun distinct_port_detail_is_kept() {
        val text = AircraftCommandUi.outcomeText(
            AircraftCommandPort.Outcome.UNAVAILABLE,
            "MSDK 명령 어댑터 미연결 · 전송 안 됨",
        )
        assertTrue(text.contains("기체 연결 없음"))
        assertTrue(text.contains("MSDK 명령 어댑터 미연결"))
    }

    @Test
    fun every_refusal_names_its_cause() {
        val texts = CommandGate.Refusal.values()
            .filter { it != CommandGate.Refusal.NONE }
            .associateWith { AircraftCommandUi.refusalText(it) }
        texts.values.forEach { assertTrue(it.isNotBlank()) }
        assertTrue(texts[CommandGate.Refusal.LINK_DOWN]!!.contains("링크 단절"))
        assertTrue(texts[CommandGate.Refusal.AIRCRAFT_UNAVAILABLE]!!.contains("기체 연결 없음"))
        assertTrue(texts[CommandGate.Refusal.UNSUPPORTED]!!.contains("지원"))
        assertTrue(
            texts[CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN]!!.contains("제어 주체 미확인"),
        )
        assertTrue(
            texts[CommandGate.Refusal.FLIGHT_AUTHORITY_NOT_MANUAL]!!.contains("수동 조종"),
        )
        assertTrue(
            texts[CommandGate.Refusal.FLIGHT_CONTROL_REQUEST_PENDING]!!.contains("전환 중"),
        )
        assertEquals(
            CommandGate.Refusal.values().size - 1,
            texts.values.toSet().size,
        )
    }

    @Test
    fun result_line_carries_command_outcome_and_simulation_tag() {
        val bench = AircraftCommandUi.resultLine(
            AircraftCommandPort.Command.PHOTO,
            AircraftCommandPort.Outcome.DELIVERED,
            "요청 전달됨 · 실행 결과는 기체 상태로 확인",
            simulated = true,
        )
        assertTrue(bench.contains("사진"))
        assertTrue(bench.contains("요청 전달됨"))
        assertTrue(bench.contains("[SIMULATED]"))

        val device = AircraftCommandUi.resultLine(
            AircraftCommandPort.Command.TAKEOFF,
            AircraftCommandPort.Outcome.UNAVAILABLE,
            "MSDK 명령 어댑터 미연결 · 전송 안 됨",
            simulated = false,
        )
        assertTrue(device.contains("이륙"))
        assertTrue(!device.contains("[SIMULATED]"))
    }

    @Test
    fun blocked_line_says_why_without_sending() {
        val line = AircraftCommandUi.blockedLine(
            AircraftCommandPort.Command.TAKEOFF,
            CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN,
        )
        assertTrue(line.contains("이륙"))
        assertTrue(line.contains("전송 차단"))
        assertTrue(line.contains("제어 주체 미확인"))
    }

    @Test
    fun every_command_has_a_label() {
        AircraftCommandPort.Command.values().forEach { command ->
            assertTrue(
                command.name,
                AircraftCommandUi.commandLabel(command).isNotBlank(),
            )
        }
    }
}
