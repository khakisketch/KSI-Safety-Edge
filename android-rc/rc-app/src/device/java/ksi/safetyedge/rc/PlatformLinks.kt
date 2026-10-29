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

    /**
     * The product path — EXCEPT when a developer test link (TCP) is
     * configured: then the real Edge bytes travel over LAN instead of the
     * aircraft path. Same parser, same state handling, different physical
     * route. The link reports its kind ("TEST_TCP" vs "AIRCRAFT") so the
     * UI can tell them apart.
     */
    fun droneLink(context: Context): DroneLink =
        TestEdgeLink.from(context) ?: ksi.msdk.MsdkDroneLink(context)

    /**
     * DJI app-key presence, read from THIS app's own manifest
     * ([ksi.msdk.DjiSdkBridge.appKeyPresent]) — not from whichever link
     * currently carries bytes. On the TEST_TCP development route the link
     * cannot report SDK facts (its defaults mean "unknown via this link"),
     * but the key fact is still readable and must not misreport as
     * missing when the key is injected.
     */
    fun sdkKeyPresent(context: Context): Boolean =
        ksi.msdk.DjiSdkBridge(context).appKeyPresent()

    /**
     * The MSDK's own connection claim (KeyConnection), independent of the
     * product-handshake gate ([ksi.msdk.SdkAircraftConnection]): true only
     * when a registered SDK reports connected. The gated aircraft fact
     * ([aircraftConnection]) never displays 연결됨 on this claim alone;
     * the claim itself stays readable here so diagnostics can surface it
     * instead of masking it.
     */
    fun msdkReportsConnection(): Boolean =
        ksi.msdk.SdkAircraftConnection.msdkReportsConnection()

    /**
     * Real SDK registration state, read statically (no bridge instance
     * needed, no side effects): true only when the MSDK itself reports
     * registered. On the TEST_TCP route no link ever starts a bridge, so
     * the link's default (false) would misreport a registered SDK as
     * failed — this reads the fact instead. LinkageError/Exception
     * (Helper not installed, host runtime) degrade to false, never throw.
     */
    fun sdkInitialised(): Boolean =
        ksi.msdk.SdkAircraftConnection.isSdkRegistered()

    /**
     * Genuinely-pending SDK init (started, no terminal outcome yet): true
     * only when the started source neither listens (registered) nor
     * carries an error. Lets the diagnosis show 확인 중 instead of a
     * confident 실패. Anything else — including unknown sources — is not
     * pending (a FAILED bridge reports its error path, never pending).
     */
    fun sdkInitPending(source: FlightTelemetrySource): Boolean {
        val msdk = source as? ksi.msdk.MsdkFlightTelemetrySource ?: return false
        return !msdk.listening && msdk.lastError == null
    }

    fun videoSource(context: Context): VideoSource = ksi.msdk.MsdkVideoSource(context)

    fun videoRecorder(context: Context): VideoRecorder? =
        ksi.msdk.MsdkVideoRecorder(context)

    /** Read-only MSDK telemetry source (DEVICE GATE — key gate + registration). */
    fun flightTelemetry(context: Context): FlightTelemetrySource =
        ksi.msdk.MsdkFlightTelemetrySource(context)

    /** Read-only MSDK aircraft camera source (DEVICE GATE — key gate + registration). */
    fun aircraftCamera(context: Context): AircraftCameraTelemetrySource =
        ksi.msdk.MsdkCameraTelemetrySource(context)

    /** Live aircraft connection, independent of the payload/MOP channel. */
    fun aircraftConnection(context: Context, link: DroneLink): AircraftConnectionProvider =
        ksi.msdk.SdkAircraftConnection

    /**
     * Operator-only SDK adapter. Unknown authority still blocks flight
     * commands; camera commands require a current confirmed aircraft
     * connection. SDK delivery and support remain device-unverified.
     */
    fun aircraftCommand(context: Context): AircraftCommandPort =
        ksi.msdk.MsdkAircraftCommand.operational()

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
