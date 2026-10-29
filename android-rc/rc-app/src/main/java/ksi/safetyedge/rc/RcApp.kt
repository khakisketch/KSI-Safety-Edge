package ksi.safetyedge.rc

import android.app.Application
import android.content.Context
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

    /** A6: live-video seam. MSDK v5 implementation is H1-gated; the bench
     *  source delivers no frames and never allows overlays. */
    lateinit var videoSource: VideoSource
        private set

    /** A9: device recorder (null on the bench — a fake cannot record). */
    var videoRecorder: VideoRecorder? = null
        private set

    /**
     * Operator-initiated aircraft/camera commands (rc-ports
     * [AircraftCommandPort]). Bench attaches the [FakeAircraftCommand]
     * double; device uses the real MSDK adapter with confirmed connection
     * and authority gates. SDK acceptance never proves execution.
     */
    lateinit var aircraftCommand: AircraftCommandPort
        private set

    /** Physical aircraft connection, not payload/MOP availability. */
    lateinit var aircraftConnection: AircraftConnectionProvider
        private set

    /**
     * A9-1: the device flavor installs the DJI MSDK bootstrap HERE, in the
     * official sample's order (`attachBaseContext` ->
     * `com.cySdkyc.clx.Helper.install`) — before any SDK class is touched.
     * Bench is an explicit no-op.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        PlatformLinks.installPlatform(this)
    }

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        // KSI session first — but its durable open NEVER throws for
        // storage I/O (degraded mode keeps the store object usable for
        // reads and fail-closed for writes), so video/camera init below
        // always runs even when local storage is dead.
        session = createAppSession(
            storeDir = File(filesDir, "rc-store"),
            rcDeviceId = config.rcDeviceId,
            wallClock = { ReceiptClock.now() },
            // A9: the platform flavor decides the implementation here.
            link = PlatformLinks.droneLink(this),
            coreConfig = {
                if (config.coreConfigured) config.coreUrl to config.coreToken else null
            },
        )
        // The test Edge link (TCP) is a DEVELOPMENT path: it connects at
        // startup so a field test needs no hidden tap, and it is only ever
        // attached when a target is configured in the developer settings.
        if (TestEdgeLink.isConfigured(this)) {
            session.link.connect()
        }
        demoSession = DemoSession(filesDir, { ReceiptClock.now() })
        videoSource = PlatformLinks.videoSource(this)
        videoRecorder = PlatformLinks.videoRecorder(this)
        aircraftConnection = PlatformLinks.aircraftConnection(this, session.link)
        aircraftCommand = PlatformLinks.aircraftCommand(this)
        // A9-3: encoded frames flow to the recorder independently of the
        // display surface (bench: both are no-ops).
        PlatformLinks.wireVideo(videoSource, videoRecorder)
        // A8: MOP retransmission timer — re-sends unacked envelopes (same
        // msg_id) and drains the outbox while the link is connected. The
        // same 1s tick also drives the live-status poll
        // (MissionController.pollTick via RcSession.retransmitTick) while a
        // session is in flight — the Edge runtime display goes live without
        // the operator doing anything.
        retransmitExecutor.scheduleWithFixedDelay(
            Runnable { runCatching { session.retransmitTick() } },
            1, 1, java.util.concurrent.TimeUnit.SECONDS,
        )
    }

    private val retransmitExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** A9-3: recording lifecycle (bench = no recorder -> no-op). */
    fun startFlightRecording(flightId: String, segmentSeconds: Int) {
        val recorder = videoRecorder ?: return
        // A bundle-supplied value outside 1..3600 falls back to the default:
        // an invalid policy must not strand an already-started flight (C63).
        val seconds = if (segmentSeconds in 1..3600) segmentSeconds else 300
        PlatformLinks.configureRecorder(recorder, seconds)
        // Provenance anchor for the close path: which Edge boot was known
        // when proxy recording started (null recorded as null), plus the
        // sticky recorder failure flag at that moment — only failures first
        // observed during THIS flight count later (a sticky flag predating
        // the flight belongs to the earlier closeout, never this one).
        session.noteRecordingStart(flightId, recorder.hasWriteFailure)
        recorder.start(flightId)
    }

    /** Stops recording and returns only finalized, real segment files. */
    fun stopFlightRecording(): List<File> = videoRecorder?.stop() ?: emptyList()
}

/**
 * Pure assembly seam for the primary RC app surface (host-testable: no
 * Android APIs — the caller supplies the platform link and clocks). Used
 * by [RcApp.onCreate] and by host tests that reopen the SAME store
 * directory after corrupting it, proving the app degrades instead of
 * crashing. The returned session never throws for storage I/O: check
 * [RcSession.storageAvailable] for the KSI persistence state.
 */
internal fun createAppSession(
    storeDir: File,
    rcDeviceId: String,
    wallClock: () -> String,
    link: DroneLink,
    coreConfig: () -> Pair<String, String>?,
): RcSession = RcSession(
    storeDir = storeDir,
    rcDeviceId = rcDeviceId,
    wallClock = wallClock,
    link = link,
    coreConfig = coreConfig,
)
