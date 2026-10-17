package ksi.safetyedge.rc

import android.app.Application
import android.content.Context

/**
 * Device flavor: real DJI MSDK v5 adapters (rc-msdk). Compiles and packages
 * without a developer app key; SDK initialisation/registration succeeds
 * only on a real RC (device gate, A9).
 */
object PlatformLinks {
    /**
     * A9-1: the MSDK bootstrap must run in Application.attachBaseContext —
     * it loads the SDK runtime classes that every other call depends on.
     */
    fun installPlatform(application: Application) =
        ksi.msdk.MsdkPlatform.install(application)

    fun droneLink(context: Context): DroneLink = ksi.msdk.MsdkDroneLink(context)

    fun videoSource(context: Context): VideoSource = ksi.msdk.MsdkVideoSource(context)

    fun videoRecorder(context: Context): VideoRecorder? =
        ksi.msdk.MsdkVideoRecorder(context)

    /** Read-only MSDK telemetry source (DEVICE GATE — key gate + registration). */
    fun flightTelemetry(context: Context): FlightTelemetrySource =
        ksi.msdk.MsdkFlightTelemetrySource(context)

    /** A9-3: encoded frames flow source -> recorder (display is separate). */
    fun wireVideo(source: VideoSource, recorder: VideoRecorder?) {
        val msdkSource = source as? ksi.msdk.MsdkVideoSource ?: return
        msdkSource.setFrameSink(recorder as? ksi.msdk.MsdkVideoRecorder)
    }

    fun configureRecorder(recorder: VideoRecorder?, segmentSeconds: Int) {
        (recorder as? ksi.msdk.MsdkVideoRecorder)?.segmentSeconds = segmentSeconds
    }

    fun attachVideoSurface(source: VideoSource, surface: android.view.Surface?) {
        (source as? ksi.msdk.MsdkVideoSource)?.attachSurface(surface)
    }
}
