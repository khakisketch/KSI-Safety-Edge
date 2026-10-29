package ksi.safetyedge.rc

import android.app.Application
import android.content.Context

/**
 * A9 assembly point: the ONLY place that chooses port implementations.
 * The main source set provides the bench defaults (no DJI SDK); the
 * `device` product flavor's source set replaces this object with the real
 * DJI MSDK adapters from `rc-msdk`. Nothing else in the frozen pipeline
 * knows which implementation is attached.
 */
object PlatformLinks {
    /** Bench: no SDK, nothing to install. */
    fun installPlatform(application: Application) = Unit

    /**
     * The product path — EXCEPT when a developer test link (TCP) is
     * configured: then the real Edge bytes travel over LAN instead of the
     * aircraft path. Same parser, same state handling, different physical
     * route. The link reports its kind ("TEST_TCP" vs "AIRCRAFT") so the
     * UI can tell them apart.
     */
    fun droneLink(context: Context): DroneLink =
        TestEdgeLink.from(context) ?: FakeDroneLink()

    /**
     * Bench has no SDK: the key is never present. (Device reads the real
     * manifest fact via the MSDK bridge; the bench double cannot.)
     */
    fun sdkKeyPresent(context: Context): Boolean = false

    /** Bench has no SDK: the MSDK reports nothing. */
    fun msdkReportsConnection(): Boolean = false

    /** Bench has no SDK: nothing is ever initialised. */
    fun sdkInitialised(): Boolean = false

    /** Bench has no SDK: nothing can be pending. */
    fun sdkInitPending(source: FlightTelemetrySource): Boolean = false

    fun videoSource(context: Context): VideoSource = FakeVideoSource()

    /** Bench telemetry double (no SDK): scripted values for UI development. */
    fun flightTelemetry(context: Context): FlightTelemetrySource = FakeFlightTelemetrySource()

    /** Bench camera double (no SDK): scripted reports for UI development. */
    fun aircraftCamera(context: Context): AircraftCameraTelemetrySource =
        FakeAircraftCameraTelemetrySource()

    /** Bench: no aircraft behind the commands — never claims execution. */
    fun aircraftCommand(context: Context): AircraftCommandPort = FakeAircraftCommand()

    /** Scripted bench fact only; no real SDK/aircraft is consulted. */
    fun aircraftConnection(context: Context, link: DroneLink): AircraftConnectionProvider =
        AircraftConnectionProvider { link.aircraftConnected }

    /** Bench has no recorder: a fake cannot record real video. */
    fun videoRecorder(context: Context): VideoRecorder? = null

    /** A9-3 bench: no real video pipeline — nothing to wire. */
    fun wireVideo(source: VideoSource, recorder: VideoRecorder?) = Unit

    fun configureRecorder(recorder: VideoRecorder?, segmentSeconds: Int) = Unit

    fun attachVideoSurface(source: VideoSource, surface: android.view.Surface?) =
        Unit
}
