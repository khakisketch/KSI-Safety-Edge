package ksi.safetyedge.rc

import android.os.SystemClock
import java.time.Instant

/**
 * Edge-monotonic-ns to UTC mapper (A4). The manifest needs UTC instants but
 * the Edge speaks monotonic nanoseconds; the true anchor (Edge clock sync)
 * is an H1/MSDK follow-up. Until then the app anchors to the device wall
 * clock at session start and LABELS the mapping unverified wherever it is
 * shown — never presented as surveyed time.
 */
fun interface EdgeClock {
    fun toUtc(edgeMonoNs: Long): String
}

class DeviceWallClock : EdgeClock {
    private val mono0 = SystemClock.elapsedRealtimeNanos()
    private val utc0 = System.currentTimeMillis()

    /** False until an H1 Edge clock anchor replaces this class. */
    val verified: Boolean = false

    override fun toUtc(edgeMonoNs: Long): String {
        val millis = utc0 + (edgeMonoNs - mono0) / 1_000_000L
        return Instant.ofEpochMilli(millis).toString()
    }
}
