package ksi.safetyedge.rc

/**
 * Device-flavor command stub: reports [AircraftCommandPort.Outcome.UNAVAILABLE]
 * for every request until the real MSDK adapter (other session's work) is
 * attached through [PlatformLinks]. Success is never pretended; the
 * [capabilities] stay UNKNOWN so the UI cannot imply device verification.
 */
class StubAircraftCommand : AircraftCommandPort {

    override val capabilities: AircraftCommandPort.Capabilities =
        AircraftCommandPort.Capabilities(
            support = AircraftCommandPort.Command.values()
                .associateWith { AircraftCommandPort.Support.UNKNOWN },
            note = "MSDK 명령 어댑터 미연결",
        )

    override fun request(
        request: AircraftCommandPort.Request,
        listener: AircraftCommandPort.Listener,
    ) {
        listener.onResult(
            AircraftCommandPort.Result(
                command = request.command,
                outcome = AircraftCommandPort.Outcome.UNAVAILABLE,
                detail = "MSDK 명령 어댑터 미연결 · 전송 안 됨",
            ),
        )
    }
}
