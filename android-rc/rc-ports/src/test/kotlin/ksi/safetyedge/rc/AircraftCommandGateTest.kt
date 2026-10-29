package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AircraftCommandGateTest {

    private val allUnverified = AircraftCommandPort.Capabilities(
        support = AircraftCommandPort.Command.values().associateWith { AircraftCommandPort.Support.UNVERIFIED },
    )

    private fun manual() = ControlAuthorityModel.fromSignals(
        edgeAutoRequested = false,
        edgeAutoConfirmed = false,
        failsafeActive = false,
        rcManualInput = true,
        linkOk = true,
    )

    private fun unknownOwner() = ControlAuthorityModel.fromSignals(
        edgeAutoRequested = false,
        edgeAutoConfirmed = false,
        failsafeActive = false,
        rcManualInput = false,
        linkOk = false,
    )

    @Test
    fun `manual pilot may request flight commands`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.TAKEOFF, manual().owner, manual().confirmed, linkUp = true, aircraftConnected = true, capabilities = allUnverified,
        )
        assertTrue(decision.allowed)
        assertEquals(CommandGate.Refusal.NONE, decision.refusal)
    }

    @Test
    fun `flight commands are refused when the controlling party is unknown`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.RETURN_HOME, unknownOwner().owner, unknownOwner().confirmed, linkUp = true, aircraftConnected = true, capabilities = allUnverified,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.FLIGHT_AUTHORITY_UNKNOWN, decision.refusal)
    }

    @Test
    fun `flight commands are refused while a control transition is pending`() {
        val transition = ControlAuthorityModel.fromSignals(
            edgeAutoRequested = true,
            edgeAutoConfirmed = false,
            failsafeActive = false,
            rcManualInput = false,
            linkOk = true,
        )
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.LAND, transition.owner, transition.confirmed, linkUp = true, aircraftConnected = true, capabilities = allUnverified,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.FLIGHT_CONTROL_REQUEST_PENDING, decision.refusal)
    }

    @Test
    fun `flight commands are refused when the link is down`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.TAKEOFF, manual().owner, manual().confirmed, linkUp = false, aircraftConnected = false, capabilities = allUnverified,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.LINK_DOWN, decision.refusal)
    }

    @Test
    fun `camera commands stay available while the AI stack is degraded`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.ZOOM_IN, manual().owner, manual().confirmed, linkUp = true, aircraftConnected = true, capabilities = allUnverified,
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `camera commands are refused without an aircraft`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.PHOTO, manual().owner, manual().confirmed, linkUp = true, aircraftConnected = false, capabilities = allUnverified,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.AIRCRAFT_UNAVAILABLE, decision.refusal)
    }

    @Test
    fun `unknown aircraft connection refuses camera commands`() {
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.GIMBAL_ROTATE, manual().owner, manual().confirmed, linkUp = true, aircraftConnected = null, capabilities = allUnverified,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.AIRCRAFT_UNAVAILABLE, decision.refusal)
    }

    @Test
    fun `confirmed connection permits camera even with link down`() {
        // Gating is on explicitly confirmed aircraft availability — MOP
        // link health and AI health never substitute, in either direction.
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.ZOOM_IN, manual().owner, manual().confirmed, linkUp = false, aircraftConnected = true, capabilities = allUnverified,
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `unsupported commands are refused before anything else`() {
        val capabilities = AircraftCommandPort.Capabilities(
            support = mapOf(AircraftCommandPort.Command.ZOOM_IN to AircraftCommandPort.Support.UNSUPPORTED),
        )
        val decision = CommandGate.evaluate(
            AircraftCommandPort.Command.ZOOM_IN, manual().owner, manual().confirmed, linkUp = true, aircraftConnected = true, capabilities = capabilities,
        )
        assertFalse(decision.allowed)
        assertEquals(CommandGate.Refusal.UNSUPPORTED, decision.refusal)
    }

    @Test
    fun `bench double reports every command as unverified`() {
        val fake = FakeAircraftCommand()
        assertTrue(
            AircraftCommandPort.Command.values().all {
                fake.capabilities.supportOf(it) == AircraftCommandPort.Support.UNVERIFIED
            },
        )
    }

    @Test
    fun `bench double reports the request, not an execution`() {
        val fake = FakeAircraftCommand()
        var result: AircraftCommandPort.Result? = null
        fake.request(AircraftCommandPort.Request(AircraftCommandPort.Command.TAKEOFF)) { result = it }
        assertEquals(1, fake.requests.size)
        assertEquals(AircraftCommandPort.Outcome.DELIVERED, result?.outcome)
        assertTrue(result?.detail?.contains("실행 결과는 기체 상태로 확인") == true)
    }

    @Test
    fun `bench double can drill a refused outcome`() {
        val fake = FakeAircraftCommand()
        fake.nextOutcome = AircraftCommandPort.Outcome.REJECTED
        var result: AircraftCommandPort.Result? = null
        fake.request(AircraftCommandPort.Request(AircraftCommandPort.Command.PHOTO)) { result = it }
        assertEquals(AircraftCommandPort.Outcome.REJECTED, result?.outcome)
    }
}
