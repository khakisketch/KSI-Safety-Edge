package ksi.safetyedge.rc

/**
 * Flavor-agnostic seam for the read-only AIRCRAFT camera telemetry source.
 *
 * Reports real camera state only — recording on/off (+ time if the SDK
 * reports it), lens/camera type, zoom ratio, photo/shooting state and, only
 * with actual media/camera notification evidence, a capture confirmation.
 *
 * Honesty rules (binding):
 * - UNKNOWN until an SDK report arrives: null means "the app has not
 *   confirmed this field", never 0/false/1.0x/idle/success. In particular a
 *   shooting-state `false` transition is NOT a saved-photo/REC success.
 * - Capture CONFIRMED comes ONLY from an actual media/camera notification
 *   (MSDK `KeyNewlyGeneratedMediaFile` with a still-photo type on device;
 *   the bench fake feeds it explicitly). Command acceptances
 *   (`performAction` success), the KSI/RC-proxy recorder state and the
 *   shooting flag alone never produce CONFIRMED.
 * - Change-only keys are never made stale by an invented timer. The SDK
 *   documents no periodic cadence for these reports (DEVICE GATE § cadence),
 *   so the last confirmed value stays current until contradicted — it is
 *   never relabelled STALE by age. [Snapshot] therefore carries receipt
 *   times but no freshness verdict; the display mapping sets
 *   [FlightCameraPresentation.stale] = false and documents the reason.
 * - Read-only: no `KeyManager.setValue`/camera/flight commands, no SDK
 *   parameter changes. The MSDK implementation only `listen`s.
 *
 * Threading mirrors [FlightTelemetrySource]: [onUpdate] is invoked after any
 * field update — off the UI thread (SDK callback thread on device); the UI
 * must post to its own thread and re-read [snapshot].
 */
interface AircraftCameraTelemetrySource {
    /**
     * Last confirmed camera reports. Every nullable field is UNKNOWN until
     * the first SDK report for that field; receipt times are RC monotonic
     * receipt clocks (`elapsedRealtime` domain) supplied by the caller.
     */
    data class Snapshot(
        /** SDK-reported recording state. null = no report yet (unknown). */
        val recording: Boolean? = null,
        val recordingAtMs: Long? = null,
        /**
         * SDK-reported recording elapsed. Units are the SDK's
         * `KeyRecordingTime` integer (seconds per the v5 convention —
         * DEVICE GATE A9-4 confirms the unit on the RC). null = no time
         * report (unknown — not 00:00).
         */
        val recordingSeconds: Long? = null,
        val recordingSecondsAtMs: Long? = null,
        /**
         * Active lens label from the stream source (e.g. "WIDE"/"ZOOM").
         * null = no lens report yet.
         */
        val lensName: String? = null,
        val lensAtMs: Long? = null,
        /**
         * Raw camera hardware type (e.g. "M4D"). Display fallback only when
         * [lensName] is still unknown; never invented.
         */
        val cameraType: String? = null,
        val cameraTypeAtMs: Long? = null,
        /** Current zoom ratio (e.g. 1.0, 3.0). null = no zoom report yet. */
        val zoomRatio: Double? = null,
        val zoomAtMs: Long? = null,
        /**
         * True while ANY shooting report says shooting is in progress; false
         * only when every observed shooting report says idle; null when no
         * shooting report has arrived. A false value is idle — never a
         * capture success (see [lastCaptureAtMs]).
         */
        val shooting: Boolean? = null,
        val shootingAtMs: Long? = null,
        /**
         * Receipt time of the last still-photo media notification
         * (SDK `KeyNewlyGeneratedMediaFile` with a still type). null = no
         * capture has been evidenced. This is the ONLY source of a capture
         * confirmation.
         */
        val lastCaptureAtMs: Long? = null,
        /** Media type name of the last capture (e.g. "JPEG"). */
        val lastCaptureKind: String? = null,
    ) {
        /** True once ANY SDK report has arrived (vs. all-unknown). */
        val hasAnyReport: Boolean
            get() = recording != null ||
                recordingSeconds != null ||
                lensName != null ||
                cameraType != null ||
                zoomRatio != null ||
                shooting != null ||
                lastCaptureAtMs != null

        /**
         * Display lens: the reported lens first, the hardware type as an
         * honest fallback, null while neither has reported.
         */
        fun displayLens(): String? = lensName ?: cameraType

        /**
         * Photo display state derived WITHOUT inventing success or failure:
         * SHOOTING while the SDK says shooting is in progress, CONFIRMED
         * once a still-photo media notification has evidenced a capture
         * (latched until the next SHOOTING — no invented expiry timer),
         * NONE otherwise. FAILED is never produced here: no SDK failure key
         * is wired, and an idle transition is not a failure.
         */
        fun displayPhoto(): PhotoDisplay = when {
            shooting == true -> PhotoDisplay.SHOOTING
            lastCaptureAtMs != null -> PhotoDisplay.CONFIRMED
            else -> PhotoDisplay.NONE
        }
    }

    /** UI-agnostic photo display state (mirrors the app presentation enum). */
    enum class PhotoDisplay { NONE, SHOOTING, CONFIRMED }

    /** Current last-confirmed reports (all null when nothing reported yet). */
    fun snapshot(): Snapshot

    fun start(onUpdate: () -> Unit)

    fun stop()
}

/**
 * Bench double: scripted camera reports for UI development and host tests —
 * no SDK. Values are fed explicitly with caller-provided receipt times;
 * every feed notifies the update callback the same way the device source
 * does. The bench fake never touches real camera state and its reports must
 * never be presented without the SIMULATED mark (the presentation layer
 * owns that flag).
 */
class FakeAircraftCameraTelemetrySource : AircraftCameraTelemetrySource {

    private var snapshot = AircraftCameraTelemetrySource.Snapshot()

    private var onUpdate: (() -> Unit)? = null

    @Volatile
    var running: Boolean = false
        private set

    override fun snapshot(): AircraftCameraTelemetrySource.Snapshot = snapshot

    override fun start(onUpdate: () -> Unit) {
        this.onUpdate = onUpdate
        running = true
    }

    override fun stop() {
        onUpdate = null
        running = false
    }

    private fun emit() {
        onUpdate?.invoke()
    }

    private fun requireRunning() {
        check(running) { "source not started" }
    }

    fun feedRecording(recording: Boolean, atMs: Long) {
        requireRunning()
        snapshot = snapshot.copy(recording = recording, recordingAtMs = atMs)
        emit()
    }

    fun feedRecordingTime(seconds: Long, atMs: Long) {
        requireRunning()
        require(seconds >= 0) { "recording seconds must be >= 0" }
        snapshot = snapshot.copy(recordingSeconds = seconds, recordingSecondsAtMs = atMs)
        emit()
    }

    fun feedLens(lensName: String, atMs: Long) {
        requireRunning()
        require(lensName.isNotBlank()) { "lens name must not be blank" }
        snapshot = snapshot.copy(lensName = lensName, lensAtMs = atMs)
        emit()
    }

    fun feedCameraType(cameraType: String, atMs: Long) {
        requireRunning()
        require(cameraType.isNotBlank()) { "camera type must not be blank" }
        snapshot = snapshot.copy(cameraType = cameraType, cameraTypeAtMs = atMs)
        emit()
    }

    fun feedZoom(ratio: Double, atMs: Long) {
        requireRunning()
        require(ratio.isFinite() && ratio > 0) { "zoom ratio must be finite and > 0" }
        snapshot = snapshot.copy(zoomRatio = ratio, zoomAtMs = atMs)
        emit()
    }

    fun feedShooting(shooting: Boolean, atMs: Long) {
        requireRunning()
        snapshot = snapshot.copy(shooting = shooting, shootingAtMs = atMs)
        emit()
    }

    /**
     * Still-photo capture evidence (bench equivalent of the SDK media
     * notification). [kind] names the media type (e.g. "JPEG").
     */
    fun feedCapture(kind: String, atMs: Long) {
        requireRunning()
        require(kind.isNotBlank()) { "capture kind must not be blank" }
        snapshot = snapshot.copy(lastCaptureAtMs = atMs, lastCaptureKind = kind)
        emit()
    }

    /** Clears the latched capture confirmation (bench harness only). */
    fun clearCapture() {
        requireRunning()
        snapshot = snapshot.copy(lastCaptureAtMs = null, lastCaptureKind = null)
        emit()
    }
}
