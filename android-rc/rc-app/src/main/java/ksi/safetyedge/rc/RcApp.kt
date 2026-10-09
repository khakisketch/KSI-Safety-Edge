package ksi.safetyedge.rc

import android.app.Application
import java.io.File

/**
 * Application holder for the single [RcSession]. The session owns the
 * FileRcStore directory (`filesDir/rc-store`), so killing and restarting
 * the app resumes the same flight/evidence/upload receipts — but NOT the
 * mission session or queued control bytes (in-memory): after a restart the
 * operator reloads the bundle and re-prepares/reconciles.
 */
class RcApp : Application() {
    lateinit var session: RcSession
        private set
    lateinit var config: AppConfig
        private set
    lateinit var edgeClock: DeviceWallClock
        private set

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        edgeClock = DeviceWallClock()
        session = RcSession(
            storeDir = File(filesDir, "rc-store"),
            config = config,
            clock = edgeClock,
            // H1: replace with the DJI MSDK v5 implementation (same interface).
            link = FakeDroneLink(),
            initialBundle = null,
        )
    }
}
