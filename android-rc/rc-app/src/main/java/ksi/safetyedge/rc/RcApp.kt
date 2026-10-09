package ksi.safetyedge.rc

import android.app.Application
import java.io.File

/**
 * Application holder for the single operational [RcSession] plus the
 * physically isolated [DemoSession]. The operational session owns the
 * FileRcStore directory (`filesDir/rc-store`), so killing and restarting
 * the app resumes the active flight, evidence and upload receipts — but NOT
 * the in-memory mission controller (re-attach after restart).
 */
class RcApp : Application() {
    lateinit var session: RcSession
        private set
    lateinit var demoSession: DemoSession
        private set
    lateinit var config: AppConfig
        private set

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        session = RcSession(
            storeDir = File(filesDir, "rc-store"),
            rcDeviceId = config.rcDeviceId,
            wallClock = { ReceiptClock.now() },
            // H1: replace with the DJI MSDK v5 implementation (same interface).
            link = FakeDroneLink(),
            coreConfig = {
                if (config.coreConfigured) config.coreUrl to config.coreToken else null
            },
        )
        demoSession = DemoSession(filesDir, { ReceiptClock.now() })
    }
}
