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

    fun droneLink(context: Context): DroneLink = FakeDroneLink()

    fun videoSource(context: Context): VideoSource = FakeVideoSource()

    /** Bench telemetry double (no SDK): scripted values for UI development. */
    fun flightTelemetry(context: Context): FlightTelemetrySource = FakeFlightTelemetrySource()

    /** Bench has no recorder: a fake cannot record real video. */
    fun videoRecorder(context: Context): VideoRecorder? = null

    /** A9-3 bench: no real video pipeline — nothing to wire. */
    fun wireVideo(source: VideoSource, recorder: VideoRecorder?) = Unit

    fun configureRecorder(recorder: VideoRecorder?, segmentSeconds: Int) = Unit

    fun attachVideoSurface(source: VideoSource, surface: android.view.Surface?) =
        Unit
}
