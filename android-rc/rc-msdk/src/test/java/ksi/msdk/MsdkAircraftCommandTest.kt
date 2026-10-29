package ksi.msdk

import ksi.safetyedge.rc.AircraftCommandPort.Command
import ksi.safetyedge.rc.AircraftCommandPort.Outcome
import ksi.safetyedge.rc.AircraftCommandPort.Request
import ksi.safetyedge.rc.AircraftCommandPort.Result
import ksi.safetyedge.rc.AircraftCommandPort.Support
import ksi.safetyedge.rc.AircraftConnectionProvider
import ksi.safetyedge.rc.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for [MsdkAircraftCommand]: gate order, command→gateway mapping
 * and result classification.
 *
 * A [FakeGateway] stands in for the MSDK; no SDK runtime is loaded. Real
 * delivery/zoom/gimbal behaviour stays a DEVICE GATE fact (A9-4) — these
 * tests prove mapping and classification only.
 */
class MsdkAircraftCommandTest {

    private class FakeGateway(
        var ready: Boolean = true,
        var product: Boolean? = true,
        var error: String? = null,
        var throwOnCall: Throwable? = null,
    ) : MsdkCommandGateway {
        val calls = mutableListOf<String>()
        var lastPitch: Double? = null
        val pitches = mutableListOf<Double>()
        var lastZoomIn: Boolean? = null
        var lastPhoto: Boolean? = null

        override fun isReady(): Boolean = ready
        override fun hasProduct(): Boolean? = product

        private fun record(name: String): String? {
            throwOnCall?.let { throw it }
            calls += name
            return error
        }

        override fun takeoff(cb: GatewayResult) = cb(record("takeoff"))
        override fun land(cb: GatewayResult) = cb(record("land"))
        override fun returnHome(cb: GatewayResult) = cb(record("returnHome"))
        override fun rotateGimbalRelative(pitchDeg: Double, cb: GatewayResult) {
            lastPitch = pitchDeg
            pitches += pitchDeg
            cb(record("gimbal"))
        }
        override fun startContinuousZoom(zoomIn: Boolean, cb: GatewayResult) {
            lastZoomIn = zoomIn
            cb(record("zoomStart"))
        }
        override fun stopContinuousZoom(cb: GatewayResult) = cb(record("zoomStop"))
        override fun shootPhoto(cb: GatewayResult) = cb(record("shoot"))
        override fun startRecording(cb: GatewayResult) = cb(record("startRec"))
        override fun stopRecording(cb: GatewayResult) = cb(record("stopRec"))
        override fun setCameraModePhoto(photo: Boolean, cb: GatewayResult) {
            lastPhoto = photo
            cb(record("mode"))
        }
    }

    private fun readyInputs() = MsdkAircraftCommand.GateInputs(
        owner = Owner.MANUAL,
        ownerConfirmed = true,
        linkUp = true,
        aircraftConnected = true,
    )

    private fun adapter(
        gateway: FakeGateway,
        inputs: MsdkAircraftCommand.GateInputs = readyInputs(),
        overrides: Map<Command, Support> = emptyMap(),
        connection: AircraftConnectionProvider = AircraftConnectionProvider { true },
    ) = MsdkAircraftCommand(
        gateInputs = { inputs },
        gateway = gateway,
        connection = connection,
        supportOverrides = overrides,
        poster = { task -> task() }, // inline: no Looper on the host
        zoomStopScheduler = { _, task -> task() }, // inline stop
    )

    private fun request(
        adapter: MsdkAircraftCommand,
        command: Command,
        magnitude: Float? = null,
        parameter: String? = null,
    ): Result {
        var out: Result? = null
        adapter.request(Request(command, magnitude, parameter)) { out = it }
        return out ?: error("listener never called for $command")
    }

    @Test
    fun flight_gate_refusal_never_reaches_the_sdk() {
        val gateway = FakeGateway()
        val ad = adapter(gateway, readyInputs().copy(owner = Owner.UNKNOWN, ownerConfirmed = false))
        val result = request(ad, Command.TAKEOFF)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue("refused request must not touch the SDK", gateway.calls.isEmpty())
    }

    @Test
    fun camera_gate_refusal_never_reaches_the_sdk() {
        val gateway = FakeGateway()
        val ad = adapter(
            gateway,
            readyInputs().copy(aircraftConnected = false),
            connection = AircraftConnectionProvider { false },
        )
        val result = request(ad, Command.PHOTO)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun unsupported_capability_is_refused_before_the_sdk() {
        val gateway = FakeGateway()
        val ad = adapter(gateway, overrides = mapOf(Command.LAND to Support.UNSUPPORTED))
        val result = request(ad, Command.LAND)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun sdk_not_ready_is_unknown() {
        val gateway = FakeGateway(ready = false)
        val result = request(adapter(gateway), Command.TAKEOFF)
        assertEquals(Outcome.UNKNOWN, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun missing_product_is_unavailable() {
        val gateway = FakeGateway(product = false)
        val result = request(adapter(gateway), Command.TAKEOFF)
        assertEquals(Outcome.UNAVAILABLE, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun flight_commands_map_to_the_matching_gateway_call() {
        val gateway = FakeGateway()
        val ad = adapter(gateway)
        assertEquals(Outcome.DELIVERED, request(ad, Command.TAKEOFF).outcome)
        assertEquals(Outcome.DELIVERED, request(ad, Command.LAND).outcome)
        assertEquals(Outcome.DELIVERED, request(ad, Command.RETURN_HOME).outcome)
        assertEquals(listOf("takeoff", "land", "returnHome"), gateway.calls)
    }

    @Test
    fun gimbal_passes_magnitude_or_the_default_nudge() {
        val gateway = FakeGateway()
        val ad = adapter(gateway)
        request(ad, Command.GIMBAL_ROTATE, magnitude = 15f)
        request(ad, Command.GIMBAL_ROTATE)
        assertEquals(listOf("gimbal", "gimbal"), gateway.calls)
        assertEquals(listOf(15.0, MsdkAircraftCommand.GIMBAL_DEFAULT_PITCH_DEG), gateway.pitches)
    }

    @Test
    fun gimbal_default_is_the_documented_nudge() {
        val gateway = FakeGateway()
        val ad = adapter(gateway)
        request(ad, Command.GIMBAL_ROTATE)
        assertEquals(MsdkAircraftCommand.GIMBAL_DEFAULT_PITCH_DEG, gateway.lastPitch!!, 0.0)
    }

    @Test
    fun zoom_pulse_starts_then_stops_and_reports_delivered() {
        val gateway = FakeGateway()
        val ad = adapter(gateway)
        val result = request(ad, Command.ZOOM_IN)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("zoomStart", "zoomStop"), gateway.calls)
        assertEquals(true, gateway.lastZoomIn)

        val gateway2 = FakeGateway()
        val ad2 = adapter(gateway2)
        request(ad2, Command.ZOOM_OUT)
        assertEquals(false, gateway2.lastZoomIn)
    }

    @Test
    fun photo_selects_stills_mode_before_shooting() {
        val gateway = FakeGateway()
        val result = request(adapter(gateway), Command.PHOTO)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("mode", "shoot"), gateway.calls)
        assertEquals(true, gateway.lastPhoto)
    }

    @Test
    fun recording_selects_video_mode_before_starting() {
        val gateway = FakeGateway()
        val result = request(adapter(gateway), Command.START_RECORDING)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("mode", "startRec"), gateway.calls)
        assertEquals(false, gateway.lastPhoto)
    }

    @Test
    fun stop_recording_needs_no_mode_switch() {
        val gateway = FakeGateway()
        val result = request(adapter(gateway), Command.STOP_RECORDING)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("stopRec"), gateway.calls)
    }

    @Test
    fun parameter_overrides_the_camera_mode_default() {
        val gateway = FakeGateway()
        request(adapter(gateway), Command.PHOTO, parameter = "video")
        assertEquals(false, gateway.lastPhoto)
    }

    @Test
    fun mode_switch_refusal_never_fires_the_action() {
        val gateway = FakeGateway(error = "mode-denied")
        val result = request(adapter(gateway), Command.PHOTO)
        assertEquals(Outcome.REJECTED, result.outcome)
        assertEquals(listOf("mode"), gateway.calls) // shoot never fired
    }

    @Test
    fun delivered_detail_says_execution_is_confirmed_from_aircraft_state() {
        val result = request(adapter(FakeGateway()), Command.TAKEOFF)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertTrue(result.detail!!.contains("기체 상태로 확인"))
    }

    @Test
    fun sdk_refusal_is_rejected_with_sdk_text() {
        val result = request(adapter(FakeGateway(error = "err-7")), Command.LAND)
        assertEquals(Outcome.REJECTED, result.outcome)
        assertTrue(result.detail!!.contains("err-7"))
    }

    @Test
    fun gateway_throw_is_unknown_never_delivered() {
        val result = request(adapter(FakeGateway(throwOnCall = LinkageError("no runtime"))), Command.TAKEOFF)
        assertEquals(Outcome.UNKNOWN, result.outcome)
    }

    @Test
    fun capabilities_default_unverified_and_settable_without_code_change() {
        val ad = adapter(FakeGateway())
        assertTrue(ad.capabilities.support.values.all { it == Support.UNVERIFIED })
        assertTrue(ad.capabilities.note!!.contains("A9-4"))
        ad.setSupport(Command.TAKEOFF, Support.DEVICE_VERIFIED)
        assertEquals(Support.DEVICE_VERIFIED, ad.capabilities.supportOf(Command.TAKEOFF))
        assertEquals(Support.UNVERIFIED, ad.capabilities.supportOf(Command.LAND))
    }

    @Test
    fun zoom_stop_failure_stays_diagnostic_and_still_delivered() {
        val gateway = FakeGateway()
        val ad = MsdkAircraftCommand(
            gateInputs = { readyInputs() },
            gateway = gateway,
            connection = AircraftConnectionProvider { true },
            poster = { task -> task() },
            zoomStopScheduler = { _, _ -> throw LinkageError("no looper") },
        )
        val result = request(ad, Command.ZOOM_IN)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("zoomStart"), gateway.calls)
        // The failed stop is diagnostics only — never thrown, never a refusal.
        assertTrue(ad.lastError!!.contains("예약"))
    }

    @Test
    fun unknown_connection_refuses_camera_despite_connected_link_inputs() {
        // Provider unknown wins over a connected-looking app snapshot:
        // unknown is refused exactly like an explicit disconnect.
        val gateway = FakeGateway()
        val ad = adapter(gateway, connection = AircraftConnectionProvider { null })
        val result = request(ad, Command.PHOTO)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun false_connection_refuses_camera_despite_connected_link_inputs() {
        val gateway = FakeGateway()
        val ad = adapter(gateway, connection = AircraftConnectionProvider { false })
        val result = request(ad, Command.GIMBAL_ROTATE, magnitude = 5f)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun true_connection_permits_camera_without_mop_or_ai() {
        // Gating is on the explicitly confirmed aircraft connection only:
        // a down MOP link and an unknown authority never block camera work.
        val gateway = FakeGateway()
        val ad = adapter(
            gateway,
            inputs = MsdkAircraftCommand.GateInputs(
                owner = Owner.UNKNOWN,
                ownerConfirmed = false,
                linkUp = false,
                aircraftConnected = null,
            ),
            connection = AircraftConnectionProvider { true },
        )
        val result = request(ad, Command.PHOTO)
        assertEquals(Outcome.DELIVERED, result.outcome)
        assertEquals(listOf("mode", "shoot"), gateway.calls)
    }

    @Test
    fun flight_unknown_authority_blocked_even_connected() {
        val gateway = FakeGateway()
        val ad = adapter(
            gateway,
            inputs = readyInputs().copy(owner = Owner.UNKNOWN, ownerConfirmed = false),
            connection = AircraftConnectionProvider { true },
        )
        val result = request(ad, Command.TAKEOFF)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun explicit_app_disconnect_stays_unavailable_despite_live_sdk_read() {
        // Either side saying explicitly disconnected blocks delivery:
        // the fresher SDK read never overrides an explicit app-side no.
        val gateway = FakeGateway()
        val ad = adapter(
            gateway,
            inputs = readyInputs().copy(aircraftConnected = false),
            connection = AircraftConnectionProvider { true },
        )
        val result = request(ad, Command.PHOTO)
        assertEquals(Outcome.UNAVAILABLE, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun connection_throw_is_unknown_safe_without_product_fallback() {
        // A failing KeyConnection read degrades to unknown and refuses at
        // the gate — the productCategory heuristic is never consulted as
        // connection proof (the gateway stays untouched).
        val gateway = FakeGateway(product = true)
        val ad = adapter(
            gateway,
            connection = AircraftConnectionProvider {
                throw LinkageError("no runtime")
            },
        )
        val result = request(ad, Command.PHOTO)
        assertEquals(Outcome.NOT_PERMITTED, result.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun operational_factory_blocks_flight_and_reports_unverified() {
        // The exact shape PlatformLinks will instantiate (fake gateway on
        // host so no SDK runtime loads; inline poster since the host has
        // no main Looper): authority UNKNOWN blocks flight, unknown
        // connection blocks camera, everything stays UNVERIFIED.
        val gateway = FakeGateway()
        val ad = MsdkAircraftCommand.operational(
            gateway = gateway,
            poster = { task -> task() },
        )
        assertTrue(ad.capabilities.support.values.all { it == Support.UNVERIFIED })
        val flight = request(ad, Command.TAKEOFF)
        assertEquals(Outcome.NOT_PERMITTED, flight.outcome)
        val camera = request(ad, Command.PHOTO)
        assertEquals(Outcome.NOT_PERMITTED, camera.outcome)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun sdk_connection_provider_is_unknown_when_sdk_absent() {
        // Host JVM: no SDK runtime on the classpath — the read degrades to
        // null instead of throwing, and re-reads stay null (never cached).
        assertEquals(null, SdkAircraftConnection.readAircraftConnected())
        assertEquals(null, SdkAircraftConnection.readAircraftConnected())
    }
}
