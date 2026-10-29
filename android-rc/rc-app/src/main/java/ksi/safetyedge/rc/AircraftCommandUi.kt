package ksi.safetyedge.rc

/**
 * User-visible text for aircraft/camera command results (integrated
 * operation screen).
 *
 * Pure Kotlin, no Android: host-tested. The core rule from the port
 * contract is preserved here — a request outcome is never an execution
 * confirmation. [Outcome.DELIVERED] means the request reached the
 * command channel; whether the aircraft acted is read back from the
 * aircraft's own state, never inferred from this text.
 */
object AircraftCommandUi {

    /** MOP/AI health cannot substitute for the aircraft's own connection. */
    fun evaluateCommand(
        command: AircraftCommandPort.Command,
        aircraftConnected: Boolean?,
        capabilities: AircraftCommandPort.Capabilities,
    ): CommandGate.Decision = if (CommandGate.isFlightCommand(command) && aircraftConnected == null) {
        CommandGate.Decision(
            false,
            CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN,
            "기체 연결·제어 주체 미확인 · 명령 전송 불가",
        )
    } else CommandGate.evaluate(
        command = command,
        owner = Owner.UNKNOWN,
        ownerConfirmed = false,
        linkUp = aircraftConnected == true,
        aircraftConnected = aircraftConnected,
        capabilities = capabilities,
    )

    fun commandLabel(command: AircraftCommandPort.Command): String = when (command) {
        AircraftCommandPort.Command.TAKEOFF -> "이륙"
        AircraftCommandPort.Command.LAND -> "착륙"
        AircraftCommandPort.Command.RETURN_HOME -> "RTH"
        AircraftCommandPort.Command.GIMBAL_ROTATE -> "짐벌"
        AircraftCommandPort.Command.ZOOM_IN -> "줌 +"
        AircraftCommandPort.Command.ZOOM_OUT -> "줌 −"
        AircraftCommandPort.Command.PHOTO -> "사진"
        AircraftCommandPort.Command.START_RECORDING -> "기체 녹화 시작"
        AircraftCommandPort.Command.STOP_RECORDING -> "기체 녹화 정지"
    }

    /**
     * Canonical operator text for a request outcome. The port-supplied
     * [detail] is appended only when it adds information beyond the
     * canonical text (bench doubles echo the canonical wording).
     */
    fun outcomeText(
        outcome: AircraftCommandPort.Outcome,
        detail: String? = null,
    ): String {
        val canonical = when (outcome) {
            AircraftCommandPort.Outcome.DELIVERED ->
                "요청 전달됨 · 실행 결과는 기체 상태로 확인"
            AircraftCommandPort.Outcome.REJECTED ->
                "기체가 요청을 거절함 · 기체 상태 확인"
            AircraftCommandPort.Outcome.NOT_PERMITTED ->
                "RC 측 요건 미충족 · 전송 안 됨"
            AircraftCommandPort.Outcome.UNAVAILABLE ->
                "기체 연결 없음 · 전송 안 됨"
            AircraftCommandPort.Outcome.UNKNOWN ->
                "결과 판별 불가 · 기체 상태로 확인"
        }
        val extra = detail?.trim().orEmpty()
        return if (extra.isEmpty() || extra == canonical || canonical.startsWith(extra)) {
            canonical
        } else {
            "$canonical · $extra"
        }
    }

    /**
     * Why a command was blocked on the RC side (never sent). Every refusal
     * names its cause — a blocked button press always explains itself.
     */
    fun refusalText(refusal: CommandGate.Refusal): String = when (refusal) {
        CommandGate.Refusal.NONE -> ""
        CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN ->
            "제어 주체 미확인 · 명령 전송 불가"
        CommandGate.Refusal.FLIGHT_AUTHORITY_NOT_MANUAL ->
            "수동 조종 확인 안 됨 · 명령 전송 불가"
        CommandGate.Refusal.FLIGHT_CONTROL_REQUEST_PENDING ->
            "제어 주체 전환 중 · 명령 전송 불가"
        CommandGate.Refusal.LINK_DOWN ->
            "링크 단절 · 명령 전송 불가"
        CommandGate.Refusal.AIRCRAFT_UNAVAILABLE ->
            "기체 연결 없음 · 카메라 명령 불가"
        CommandGate.Refusal.UNSUPPORTED ->
            "이 기체·조종기 조합에서 지원 확인되지 않음"
    }

    /**
     * One honest result line: what was asked, what happened to the
     * request, and — on bench/demo values — the [SIMULATED] tag. Device
     * values that are unknown stay "미확인", never guessed.
     */
    fun resultLine(
        command: AircraftCommandPort.Command,
        outcome: AircraftCommandPort.Outcome,
        detail: String?,
        simulated: Boolean,
    ): String {
        val tag = if (simulated) " [SIMULATED]" else ""
        return "${commandLabel(command)} · ${outcomeText(outcome, detail)}$tag"
    }

    /** Blocked (never sent) result line — the gate reason is the message. */
    fun blockedLine(
        command: AircraftCommandPort.Command,
        refusal: CommandGate.Refusal,
        detail: String? = null,
    ): String = "${commandLabel(command)} · 전송 차단 · ${detail?.takeIf { it.isNotBlank() } ?: refusalText(refusal)}"
}
