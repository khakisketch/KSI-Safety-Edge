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
}
