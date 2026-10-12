package ksi.safetyedge.rc

import android.content.Context

/**
 * A9 assembly point: the ONLY place that chooses port implementations.
 * The main source set provides the bench defaults (no DJI SDK); the
 * `device` product flavor's source set replaces this object with the real
 * DJI MSDK adapters from `rc-msdk`. Nothing else in the frozen pipeline
 * knows which implementation is attached.
 */
object PlatformLinks {
    fun droneLink(context: Context): DroneLink = FakeDroneLink()

    fun videoSource(context: Context): VideoSource = FakeVideoSource()

    /** Bench has no recorder: a fake cannot record real video. */
    fun videoRecorder(context: Context): VideoRecorder? = null
}
