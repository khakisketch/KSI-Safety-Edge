package ksi.msdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import ksi.safetyedge.rc.AircraftCameraTelemetrySource

/**
 * Read-only MSDK v5 aircraft camera telemetry source (DEVICE GATE)
 * implementing the rc-ports [AircraftCameraTelemetrySource] seam.
 *
 * Scope: report conversion only — no camera/flight commands, no
 * `KeyManager.setValue`/`performAction`, no SDK parameter changes. Every
 * subscription below is a `KeyManager.listen` on a read-only key.
 *
 * Key names/types were read off the official 5.18.0 `-provided` artifact
 * with `javap` (2026-10-26) — no name was guessed:
 * - recording: `DJICameraKey.KeyIsRecording` (`Boolean`) +
 *   `KeyRecordingTime` (`Integer`, SDK seconds convention — unit DEVICE
 *   GATE A9-4) + `KeyCameraRecordingStatus` (`CameraRecordingStatus`:
 *   `IDEL`/`STARTING`/`RECORDING`/`STOPPING`/`UNKNOWN`) as corroboration
 *   only when `KeyIsRecording` has never reported;
 * - shooting: `KeyIsShootingPhoto` + `KeyShootPhotoState` +
 *   `KeyIsShootingSinglePhoto` (all `Boolean`; OR logic — any `true` means
 *   SHOOTING, all-observed-`false` means idle, otherwise unknown). A `false`
 *   (idle) transition is NEVER a capture success;
 * - capture confirmation: `KeyNewlyGeneratedMediaFile`
 *   (`GeneratedMediaFileInfo`; still-photo `MediaFileType` only — MOV/MP4
 *   video segments and unknown types are ignored, never a photo
 *   confirmation). This notification is the ONLY source of CONFIRMED;
 * - lens/camera: `CameraKey.KeyCameraVideoStreamSource`
 *   (`CameraVideoStreamSourceType`: `WIDE_CAMERA`→"WIDE",
 *   `ZOOM_CAMERA`→"ZOOM", `INFRARED_CAMERA`→"INFRARED", `UNKNOWN`→null,
 *   anything else→raw enum name) + `CameraKey.KeyCameraType`
 *   (`CameraType`: `NOT_SUPPORTED`→null, else raw name e.g. "M4D");
 * - zoom: `CameraKey.KeyCameraZoomRatios` (`Double`) +
 *   `DJICameraKey.KeyOpticalZoomFactor` (`Double`) +
 *   `DJICameraKey.KeyDigitalZoomFactor` (`Double`); last report wins, no
 *   fusion or invented 1.0x. The default (unindexed) keys are used;
 *   per-lens indexed keys
 *   (`KeyTools.createCameraKey(…, ComponentIndexType, CameraLensType)`) for
 *   multi-lens aircraft are DEVICE GATE and intentionally not wired here.
 *
 * Stale policy: NONE by timer. These keys are change-only with no
 * SDK-documented periodic cadence (DEVICE GATE § cadence), so inventing a
 * staleness timeout would fabricate "delayed" from silence. The last
 * confirmed value stays current until contradicted; [Snapshot] carries
 * receipt times for future display but no freshness verdict, and the app
 * mapping sets `stale = false` with the reason documented at the call site.
 * A never-reported field stays null (UNKNOWN) — never 1.0x/idle/success.
 *
 * `isKeySupported` is deliberately NOT consulted: its exact semantics for
 * these keys are unverified on device (same caveat as the flight telemetry
 * source §12), so an unsupported key simply never reports and stays
 * UNKNOWN instead of being mislabelled.
 *
 * Wiring follows [MsdkFlightTelemetrySource]: the key gate comes first
 * ([DjiSdkBridge.appKeyPresent]) and SDK objects are only created once the
 * bridge reports REGISTERED — no SDK listener object exists before that.
 *
 * DEVICE GATE — everything below compiles against the 5.18.0 stubs; real
 * callbacks, the recording-time unit, multi-lens indexing, zoom priority
 * and the capture-notification timing are verified on the RC ground
 * session. Nothing here claims device verification.
 */
class MsdkCameraTelemetrySource(
    private val context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : AircraftCameraTelemetrySource {

    /**
     * Raw read-only evidence that is NOT part of the frozen camera
     * Snapshot. Values are SDK reports (or null).
     */
    data class Evidence(
        val recordingStatus: String? = null,
        val recordingStatusAtMs: Long? = null,
        val cameraMode: String? = null,
        val cameraModeAtMs: Long? = null,
        val lastMediaType: String? = null,
        val lastMediaAtMs: Long? = null,
    )

    private val bridge = DjiSdkBridge(context)
    private val waiter = Handler(Looper.getMainLooper())

    @Volatile
    private var onUpdate: (() -> Unit)? = null
    @Volatile
    private var waitingForSdk = false

    @Volatile
    private var currentEvidence = Evidence()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var listening: Boolean = false
        private set

    // -- Snapshot state (last confirmed reports; null = no report yet) -----

    @Volatile
    private var recordingDirect: Boolean? = null
    @Volatile
    private var recordingDirectAtMs: Long? = null

    @Volatile
    private var recordingStatusRaw: String? = null

    @Volatile
    private var recordingSeconds: Long? = null
    @Volatile
    private var recordingSecondsAtMs: Long? = null

    @Volatile
    private var lensName: String? = null
    @Volatile
    private var lensAtMs: Long? = null

    @Volatile
    private var cameraType: String? = null
    @Volatile
    private var cameraTypeAtMs: Long? = null

    @Volatile
    private var zoomRatio: Double? = null
    @Volatile
    private var zoomAtMs: Long? = null

    // Shooting OR-logic inputs (each null until its key first reports).
    @Volatile
    private var shootingPhoto: Boolean? = null
    @Volatile
    private var shootPhotoState: Boolean? = null
    @Volatile
    private var shootingSingle: Boolean? = null
    @Volatile
    private var shootingAtMs: Long? = null

    @Volatile
    private var lastCaptureAtMs: Long? = null
    @Volatile
    private var lastCaptureKind: String? = null

    fun evidence(): Evidence = currentEvidence

    override fun snapshot(): AircraftCameraTelemetrySource.Snapshot {
        val derivedRecording = recordingDirect ?: recordingStatusRaw?.let { status ->
            when (status) {
                "RECORDING" -> true
                // NOTE: the SDK spells the idle value "IDEL".
                "IDEL" -> false
                else -> null // STARTING/STOPPING/UNKNOWN are transitional, not a state claim
            }
        }
        val shooting = when {
            shootingPhoto == true || shootPhotoState == true || shootingSingle == true -> true
            shootingPhoto != null || shootPhotoState != null || shootingSingle != null -> false
            else -> null
        }
        return AircraftCameraTelemetrySource.Snapshot(
            recording = derivedRecording,
            recordingAtMs = recordingDirectAtMs,
            recordingSeconds = recordingSeconds,
            recordingSecondsAtMs = recordingSecondsAtMs,
            lensName = lensName,
            lensAtMs = lensAtMs,
            cameraType = cameraType,
            cameraTypeAtMs = cameraTypeAtMs,
            zoomRatio = zoomRatio,
            zoomAtMs = zoomAtMs,
            shooting = shooting,
            shootingAtMs = shootingAtMs,
            lastCaptureAtMs = lastCaptureAtMs,
            lastCaptureKind = lastCaptureKind,
        )
    }

    /**
     * The flight telemetry source owns SDK initialisation in this app. Do
     * not start a second [DjiSdkBridge]/SDKManager.init here — two bridge
     * instances would each try to initialise. Wait for the already-running
     * registration, then register the read-only listeners. If the SDK never
     * registers, stay UNKNOWN with an error; never fake camera state.
     */
    override fun start(onUpdate: () -> Unit) {
        if (listening || waitingForSdk) return
        this.onUpdate = onUpdate
        if (!bridge.appKeyPresent()) {
            lastError = "${DjiSdkBridge.API_KEY_META} missing/blank — SDK not started"
            return
        }
        waitingForSdk = true
        awaitRegistration(0)
    }

    private fun awaitRegistration(attempt: Int) {
        if (!waitingForSdk) return
        if (listening) { waitingForSdk = false; return }
        if (SdkAircraftConnection.isSdkRegistered()) {
            waitingForSdk = false
            listenAll()
            return
        }
        if (attempt >= SDK_WAIT_ATTEMPTS) {
            waitingForSdk = false
            lastError = "SDK not registered — camera state stays unknown"
            return
        }
        waiter.postDelayed({ awaitRegistration(attempt + 1) }, SDK_WAIT_INTERVAL_MS)
    }

    override fun stop() {
        waitingForSdk = false
        waiter.removeCallbacksAndMessages(null)
        if (!listening) return
        try {
            KeyManager.getInstance().cancelListen(this)
        } catch (_: LinkageError) {
            // SDK runtime missing (Helper not installed): best-effort.
        } catch (_: Exception) {
            // ignore — state is forced below regardless
        }
        listening = false
    }

    @Synchronized
    private fun listenAll() {
        if (listening) return
        try {
            val km = KeyManager.getInstance()

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyIsRecording), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let {
                        recordingDirect = it
                        recordingDirectAtMs = clock()
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyRecordingTime), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { seconds ->
                        // SDK seconds convention — unit DEVICE GATE (A9-4).
                        // Negative values are discarded, never displayed.
                        if (seconds >= 0) {
                            recordingSeconds = seconds.toLong()
                            recordingSecondsAtMs = clock()
                            emit()
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyCameraRecordingStatus), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { status ->
                        recordingStatusRaw = status.name
                        updateEvidence {
                            it.copy(recordingStatus = status.name, recordingStatusAtMs = clock())
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyIsShootingPhoto), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let {
                        shootingPhoto = it
                        shootingAtMs = clock()
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyShootPhotoState), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let {
                        shootPhotoState = it
                        shootingAtMs = clock()
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyIsShootingSinglePhoto), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let {
                        shootingSingle = it
                        shootingAtMs = clock()
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyNewlyGeneratedMediaFile), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { info ->
                        val type = try {
                            info.type
                        } catch (_: Exception) {
                            null
                        }
                        val typeName = type?.name
                        if (type != null && typeName != null && isStillPhoto(typeName)) {
                            lastCaptureAtMs = clock()
                            lastCaptureKind = typeName
                            updateEvidence {
                                it.copy(lastMediaType = typeName, lastMediaAtMs = clock())
                            }
                        } else if (typeName != null) {
                            // Video segments (MOV/MP4) and unlisted types are
                            // recorded as evidence but NEVER a photo
                            // confirmation.
                            updateEvidence {
                                it.copy(lastMediaType = typeName, lastMediaAtMs = clock())
                            }
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { source ->
                        mapStreamSource(source.name)?.let { lens ->
                            lensName = lens
                            lensAtMs = clock()
                            emit()
                        } ?: run {
                            // UNKNOWN source: no lens report (stays unknown).
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(CameraKey.KeyCameraType), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { type ->
                        if (type.name != "NOT_SUPPORTED") {
                            cameraType = type.name
                            cameraTypeAtMs = clock()
                            emit()
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(CameraKey.KeyCameraZoomRatios), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { ratio ->
                        if (ratio.isFinite() && ratio > 0) {
                            zoomRatio = ratio
                            zoomAtMs = clock()
                            emit()
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyOpticalZoomFactor), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { ratio ->
                        if (ratio.isFinite() && ratio > 0) {
                            zoomRatio = ratio
                            zoomAtMs = clock()
                            emit()
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(DJICameraKey.KeyDigitalZoomFactor), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { ratio ->
                        if (ratio.isFinite() && ratio > 0) {
                            zoomRatio = ratio
                            zoomAtMs = clock()
                            emit()
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(CameraKey.KeyCameraMode), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { mode ->
                        updateEvidence {
                            it.copy(cameraMode = mode.name, cameraModeAtMs = clock())
                        }
                    }
                },
            )

        } catch (e: LinkageError) {
            lastError = "camera listeners unavailable: ${e.message ?: e.javaClass.name}"
        } catch (e: Exception) {
            lastError = "camera listeners failed: ${e.message ?: e.javaClass.name}"
        } finally {
            // Even a partially registered set must be cancelable on stop;
            // a failed registration is reported via lastError, not retried
            // by inventing state.
            listening = true
        }
    }

    @Synchronized
    private fun updateEvidence(transform: (Evidence) -> Evidence) {
        val next = transform(currentEvidence)
        currentEvidence = next
        onUpdate?.invoke()
    }

    private fun emit() = updateEvidence { it }

    companion object {
        /** 30 × 500ms = 15s for the flight source to finish SDK registration. */
        private const val SDK_WAIT_ATTEMPTS = 30
        private const val SDK_WAIT_INTERVAL_MS = 500L

        /**
         * Still-photo media types that evidence a capture confirmation.
         * MOV/MP4 (video segments) and every unlisted type are excluded on
         * purpose: an unknown type is ignored, never guessed as a photo.
         * Exotic still types not listed here are DEVICE GATE (A9-4) — they
         * stay evidence-only until confirmed on the RC.
         */
        internal val STILL_PHOTO_TYPES: Set<String> = setOf(
            "JPEG",
            "DNG",
            "PANORAMA",
            "TIFF_8_BIT",
            "TIFF_14_BIT",
            "TIFF_14_BIT_LINEAR_LOW_TEMP_RESOLUTION",
            "TIFF_14_BIT_LINEAR_HIGH_TEMP_RESOLUTION",
            "RADIOMETRIC_JPEG",
            "HEIC",
            "RAW",
            "RAW_JPEG",
            "RAW_HEIC",
            "RAW_8_BIT",
            "JLS_HEIC",
        )

        internal fun isStillPhoto(typeName: String): Boolean = typeName in STILL_PHOTO_TYPES

        /**
         * Stream-source → lens label. UNKNOWN stays unknown (null);
         * unlisted sources surface their raw enum name rather than being
         * hidden — no display string is invented beyond the three confirmed
         * lens labels.
         */
        internal fun mapStreamSource(sourceName: String): String? = when (sourceName) {
            "WIDE_CAMERA" -> "WIDE"
            "ZOOM_CAMERA" -> "ZOOM"
            "INFRARED_CAMERA" -> "INFRARED"
            "UNKNOWN" -> null
            else -> sourceName
        }
    }
}
