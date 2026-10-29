package ksi.safetyedge.rc

/**
 * Aircraft & camera command boundary for the integrated RC operation app.
 *
 * The confirmed product definition makes the KSI app the primary operation
 * surface, so the RC must be able to *ask* the aircraft to do the standard
 * DJI things a pilot does (take off, land, return home, point the gimbal,
 * zoom, take a photo, record) while the AI stack only ever observes.
 *
 * Two rules shape this port:
 *
 * 1. **A request is not a confirmation.** [Result.outcome] describes what
 *    happened to the REQUEST (delivered, rejected, not permitted, link
 *    missing, unknown). Whether the aircraft actually performed the action
 *    is a separate fact, read back from the aircraft's own state — never
 *    inferred from a successful call. The same separation the
 *    [ControlAuthority] model already keeps between a request and a
 *    confirmation.
 * 2. **The AI stack never commands the aircraft.** Flight commands are
 *    operator-initiated only. [CommandGate] encodes that rule (and the
 *    link/authority preconditions) as a pure decision so it can be tested
 *    without hardware, and so no future code path can quietly route an
 *    automatic action into the flight link.
 *
 * Implementations: the MSDK-backed one in `rc-msdk` (real aircraft, device
 * gate) and [FakeAircraftCommand] for bench/demo. Nothing in `rc-core`
 * knows which one is attached.
 */
interface AircraftCommandPort {

    /** Operator-initiated actions the RC can request. */
    enum class Command {
        TAKEOFF,
        LAND,
        RETURN_HOME,

        GIMBAL_ROTATE,
        ZOOM_IN,
        ZOOM_OUT,
        PHOTO,
        START_RECORDING,
        STOP_RECORDING,
    }

    /** What happened to the request itself. Never "the aircraft did it". */
    enum class Outcome {
        /** Delivered to the aircraft's command channel; execution not confirmed here. */
        DELIVERED,

        /** The aircraft/firmware refused the request. */
        REJECTED,

        /** Preconditions failed on the RC side (authority, link, aircraft). */
        NOT_PERMITTED,

        /** No aircraft / no product connected. */
        UNAVAILABLE,

        /** The outcome could not be determined (SDK not initialised, error, timeout). */
        UNKNOWN,
    }

    data class Request(
        val command: Command,
        /** Optional magnitude (gimbal pitch/degrees, zoom step). */
        val magnitude: Float? = null,
        /** Optional named parameter (camera mode). */
        val parameter: String? = null,
    )

    data class Result(
        val command: Command,
        val outcome: Outcome,
        /** Operator-facing reason. Never a safety verdict. */
        val detail: String? = null,
    )

    /**
     * How far the support of this command is actually known.
     *
     * `DEVICE_VERIFIED` may only be set after the command was exercised on
     * the real aircraft/RC/firmware combination. Everything else stays
     * `UNVERIFIED` or `UNKNOWN` — a command that the SDK exposes is not
     * evidence that it works on this airframe.
     */
    enum class Support {
        DEVICE_VERIFIED,
        UNVERIFIED,
        UNSUPPORTED,
        UNKNOWN,
    }

    data class Capabilities(
        val support: Map<Command, Support>,
        /** Human-facing note about what is not known yet. */
        val note: String? = null,
    ) {
        fun supportOf(command: Command): Support = support[command] ?: Support.UNKNOWN
    }

    fun interface Listener {
        fun onResult(result: Result)
    }

    /** What this aircraft/RC combination currently supports. */
    val capabilities: Capabilities

    /**
     * Requests one operator action.
     *
     * The call is asynchronous: the listener receives the outcome later.
     * Implementations must not block the UI thread and must never report
     * [Outcome.DELIVERED] for a request they did not actually hand to the
     * aircraft.
     */
    fun request(request: Request, listener: Listener)
}

/**
 * Pure preconditions for operator commands (no Android, no SDK).
 *
 * The rules come from the confirmed product definition:
 *
 * - DJI keeps flight stabilisation and protection; KSI must not re-implement
 *   them, and must not let the AI stack take the aircraft.
 * - A flight command is only allowed while the RC knows a human is flying
 *   (authority MANUAL, no pending transition) and the link is up.
 * - AI/observation failure must never block the pilot's camera work, so
 *   camera commands are gated only on aircraft availability.
 */
object CommandGate {

    /** Why a command was refused, for operator-facing text. */
    enum class Refusal {
        NONE,
        FLIGHT_AUTHORITY_UNKNOWN,
        FLIGHT_AUTHORITY_NOT_MANUAL,
        FLIGHT_CONTROL_REQUEST_PENDING,
        LINK_DOWN,
        AIRCRAFT_UNAVAILABLE,
        UNSUPPORTED,
    }

    data class Decision(
        val allowed: Boolean,
        val refusal: Refusal = Refusal.NONE,
        val detail: String? = null,
    )

    private val FLIGHT_COMMANDS = setOf(
        AircraftCommandPort.Command.TAKEOFF,
        AircraftCommandPort.Command.LAND,
        AircraftCommandPort.Command.RETURN_HOME,
    )

    private val CAMERA_COMMANDS = setOf(
        AircraftCommandPort.Command.GIMBAL_ROTATE,
        AircraftCommandPort.Command.ZOOM_IN,
        AircraftCommandPort.Command.ZOOM_OUT,
        AircraftCommandPort.Command.PHOTO,
        AircraftCommandPort.Command.START_RECORDING,
        AircraftCommandPort.Command.STOP_RECORDING,
    )

    fun isFlightCommand(command: AircraftCommandPort.Command): Boolean = command in FLIGHT_COMMANDS

    fun isCameraCommand(command: AircraftCommandPort.Command): Boolean = command in CAMERA_COMMANDS

    /**
     * @param owner who the RC believes is flying (see [Owner]).
     * @param ownerConfirmed the authority signal is confirmed, not only requested.
     * @param linkUp aircraft link state as observed, not assumed.
     * @param aircraftConnected explicitly confirmed current aircraft
     *   availability (see [AircraftConnectionProvider]): only `true`
     *   permits camera work. `null` (unknown) and `false` (disconnected)
     *   both refuse — MOP/link health and AI health never substitute.
     * @param capabilities what this combination supports today.
     */
    fun evaluate(
        command: AircraftCommandPort.Command,
        owner: Owner,
        ownerConfirmed: Boolean,
        linkUp: Boolean,
        aircraftConnected: Boolean?,
        capabilities: AircraftCommandPort.Capabilities,
    ): Decision {
        val support = capabilities.supportOf(command)
        if (support == AircraftCommandPort.Support.UNSUPPORTED) {
            return Decision(false, Refusal.UNSUPPORTED, "이 기체·조종기 조합에서 지원 확인되지 않음")
        }

        if (isFlightCommand(command)) {
            if (!linkUp) {
                return Decision(false, Refusal.LINK_DOWN, "링크 단절 · 명령 전송 불가")
            }
            when (owner) {
                Owner.FAILSAFE ->
                    return Decision(false, Refusal.FLIGHT_AUTHORITY_NOT_MANUAL, "페일세이프 동작 중 · 명령 전송 불가")
                Owner.UNKNOWN ->
                    return Decision(false, Refusal.FLIGHT_AUTHORITY_UNKNOWN, "제어 주체 미확인 · 명령 전송 불가")
                Owner.TRANSITION ->
                    return Decision(false, Refusal.FLIGHT_CONTROL_REQUEST_PENDING, "제어 주체 전환 중 · 명령 전송 불가")
                Owner.EDGE_AUTO ->
                    return Decision(false, Refusal.FLIGHT_AUTHORITY_NOT_MANUAL, "자동 운용 확인됨 · 조종자 명령 전송 불가")
                Owner.MANUAL -> Unit
            }
            if (!ownerConfirmed) {
                return Decision(false, Refusal.FLIGHT_AUTHORITY_UNKNOWN, "제어 주체 미확인 · 명령 전송 불가")
            }
        } else {
            // Camera work stays available while the AI stack is degraded,
            // but it needs explicitly confirmed current aircraft
            // availability: `null` (unknown) and `false` (disconnected)
            // both refuse. MOP/link health and AI health never substitute
            // for the aircraft's own connection state.
            if (aircraftConnected != true) {
                val detail = if (aircraftConnected == null) {
                    "기체 연결 미확인 · 카메라 명령 불가"
                } else {
                    "기체 연결 없음 · 카메라 명령 불가"
                }
                return Decision(false, Refusal.AIRCRAFT_UNAVAILABLE, detail)
            }
        }
        return Decision(true)
    }
}

/**
 * Current-aircraft-availability source for command gating (pure Kotlin, no
 * Android, no SDK).
 *
 * - `true` — the aircraft connection is explicitly confirmed right now.
 * - `false` — explicitly not connected right now.
 * - `null` — unknown (SDK missing/unregistered, key unreadable, stale, or
 *   error). Unknown is NEVER healthy: [CommandGate] refuses camera work
 *   on it exactly like an explicit disconnect.
 *
 * Implementations must read live on every call and never cache: a stored
 * `true` from minutes ago is not current availability. The DJI MSDK
 * implementation ([ksi.msdk.SdkAircraftConnection], rc-msdk) reads
 * `ProductKey.KeyConnection` behind the SDK registration gate; tests and
 * the bench inject lambdas.
 */
fun interface AircraftConnectionProvider {
    fun readAircraftConnected(): Boolean?
}

/**
 * Bench/demo command port: records what the operator asked for, and never
 * claims the aircraft performed it.
 *
 * [capabilities] deliberately reports [AircraftCommandPort.Support.UNVERIFIED]
 * for every command: a bench double has no aircraft behind it, and the UI
 * must not imply device-verified support.
 */
class FakeAircraftCommand(
    override val capabilities: AircraftCommandPort.Capabilities = AircraftCommandPort.Capabilities(
        support = AircraftCommandPort.Command.values().associateWith { AircraftCommandPort.Support.UNVERIFIED },
        note = "시연용 · 실제 기체 명령 미연결",
    ),
) : AircraftCommandPort {

    val requests = mutableListOf<AircraftCommandPort.Request>()

    /** When set, the next request is refused with this outcome (failure drill). */
    var nextOutcome: AircraftCommandPort.Outcome? = null

    override fun request(request: AircraftCommandPort.Request, listener: AircraftCommandPort.Listener) {
        requests.add(request)
        val requested = nextOutcome
        nextOutcome = null
        val outcome = requested ?: AircraftCommandPort.Outcome.DELIVERED
        listener.onResult(
            AircraftCommandPort.Result(
                command = request.command,
                outcome = outcome,
                detail = when (outcome) {
                    AircraftCommandPort.Outcome.DELIVERED -> "요청 전달됨 · 실행 결과는 기체 상태로 확인"
                    AircraftCommandPort.Outcome.REJECTED -> "기체가 요청을 거절함"
                    AircraftCommandPort.Outcome.NOT_PERMITTED -> "RC 측 요건 미충족"
                    AircraftCommandPort.Outcome.UNAVAILABLE -> "기체 연결 없음"
                    AircraftCommandPort.Outcome.UNKNOWN -> "결과 판별 불가"
                },
            ),
        )
    }
}
