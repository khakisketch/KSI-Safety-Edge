package ksi.msdk

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIFlightControllerKey
import dji.sdk.keyvalue.key.DJIGimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.PackageProductCategory
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.sdk.keyvalue.value.camera.CameraContinuousOpticalZoomParam
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraZoomDirection
import dji.sdk.keyvalue.value.camera.CameraZoomSpeed
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode

/**
 * One MSDK round-trip result: a null error means the SDK accepted the
 * REQUEST (never that the aircraft performed the action); a non-null
 * string is the SDK-reported refusal, surfaced verbatim for honesty.
 *
 * Callbacks arrive on MSDK worker threads; the caller ([MsdkAircraftCommand])
 * re-posts them to the UI thread. Implementations may throw
 * ([LinkageError]/[Exception]) when the SDK runtime is absent — the caller
 * classifies that as UNKNOWN, never as a refusal.
 */
typealias GatewayResult = (error: String?) -> Unit

/**
 * Injectable MSDK command surface (A9).
 *
 * The v5 SDK has no FlightController/Camera/Gimbal component objects: every
 * command below is one KeyManager action/set call. [MsdkAircraftCommand]
 * talks only to this interface, so host tests inject a fake and never load
 * the SDK runtime.
 *
 * DEVICE GATE: real delivery, refusal text and timing are verified on the RC
 * ground session (A9-4). Nothing here claims device verification.
 */
interface MsdkCommandGateway {
    /** SDK initialised AND app registered (key gate passed). */
    fun isReady(): Boolean

    /**
     * A product is currently reported, or null when the link cannot report
     * it (unknown — not "absent").
     */
    fun hasProduct(): Boolean?

    fun takeoff(cb: GatewayResult)
    fun land(cb: GatewayResult)
    fun returnHome(cb: GatewayResult)

    /** Relative pitch nudge in degrees (negative = look down). */
    fun rotateGimbalRelative(pitchDeg: Double, cb: GatewayResult)

    /** Starts a continuous optical zoom; the caller must stop it. */
    fun startContinuousZoom(zoomIn: Boolean, cb: GatewayResult)
    fun stopContinuousZoom(cb: GatewayResult)

    fun shootPhoto(cb: GatewayResult)
    fun startRecording(cb: GatewayResult)
    fun stopRecording(cb: GatewayResult)

    /** true = stills mode, false = video mode. */
    fun setCameraModePhoto(photo: Boolean, cb: GatewayResult)
}

/**
 * Real [MsdkCommandGateway]: each command is exactly one verified v5 key,
 * executed directly on the KeyManager.
 *
 * Key facts below were read off the real 5.18.0 `-provided` artifact with
 * `javap` (2026-10-20) — no name was guessed:
 * - flight: `DJIFlightControllerKey.KeyStartTakeoff / KeyStartAutoLanding /
 *   KeyStopAutoLanding-unused / KeyStartGoHome`, each
 *   `DJIActionKeyInfo<EmptyMsg, EmptyMsg>`, via
 *   `KeyManager.performAction(KeyTools.createKey(info), EmptyMsg(),
 *   CompletionCallbackWithParam)`;
 * - gimbal: `DJIGimbalKey.KeyRotateByAngle`,
 *   `DJIActionKeyInfo<GimbalAngleRotation, EmptyMsg>`; the param ctor is
 *   `(mode, pitch, roll, yaw, pitchIgnored, rollIgnored, yawIgnored,
 *   duration, jointReferenceUsed, timeout)`;
 * - zoom: `DJICameraKey.KeyCameraStartContinuousOpticalZoom`
 *   (`CameraContinuousOpticalZoomParam(direction, speed)` ctor) +
 *   `KeyCameraStopContinuousOpticalZoom`;
 * - stills/video: `DJICameraKey.KeyStartShootPhoto / KeyStartRecord /
 *   KeyStopRecord`, each `DJIActionKeyInfo<EmptyMsg, EmptyMsg>`;
 * - mode: `CameraKey.KeyCameraMode`, `DJIKeyInfo<CameraMode>`
 *   (`PHOTO_NORMAL` / `VIDEO_NORMAL`), via `KeyManager.setValue`;
 * - readiness: `SDKManager.getInstance().isRegistered`; product presence is
 *   `getProductCategory() != UNKNOWN` (heuristic — the exact
 *   product-connected semantics are DEVICE GATE, A9-4).
 *
 * Host-test note: the `-provided` jar ships compile stubs whose bytecode
 * cannot load on a host JVM (`VerifyError`), and the implementation AAR
 * carries no `dji.sdk`/`dji.v5` classes at all (only the Helper bootstrap),
 * so this class is inherently device-only and has no host unit test. The
 * mapping above is verified by `javap` + review; the command→gateway-method
 * hop and all classification ARE host-tested ([MsdkAircraftCommandTest]).
 * Numeric extras (gimbal duration/timeout, zoom speed/pulse, mode-switch
 * necessity, joint reference frame) are UX-side defaults — DEVICE GATE,
 * confirmed on the RC (A9-4), not spec facts.
 *
 * No SDK object is touched at construction (lazy gateway use): building this
 * class needs no SDK runtime, matching [MsdkDroneLink]/[DjiSdkBridge] order
 * (Helper install + key gate + REGISTERED first).
 */
class KeyManagerCommandGateway : MsdkCommandGateway {

    override fun isReady(): Boolean = try {
        SDKManager.getInstance().isRegistered
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }

    override fun hasProduct(): Boolean? = try {
        if (!SDKManager.getInstance().isRegistered) {
            null
        } else {
            SDKManager.getInstance().productCategory != PackageProductCategory.UNKNOWN
        }
    } catch (_: LinkageError) {
        null
    } catch (_: Exception) {
        null
    }

    override fun takeoff(cb: GatewayResult) {
        perform(KeyTools.createKey(DJIFlightControllerKey.KeyStartTakeoff), EmptyMsg(), cb)
    }

    override fun land(cb: GatewayResult) {
        perform(KeyTools.createKey(DJIFlightControllerKey.KeyStartAutoLanding), EmptyMsg(), cb)
    }

    override fun returnHome(cb: GatewayResult) {
        perform(KeyTools.createKey(DJIFlightControllerKey.KeyStartGoHome), EmptyMsg(), cb)
    }

    override fun rotateGimbalRelative(pitchDeg: Double, cb: GatewayResult) {
        val rotation = GimbalAngleRotation(
            GimbalAngleRotationMode.RELATIVE_ANGLE,
            pitchDeg,
            0.0,
            0.0,
            false, // pitch is commanded …
            true, // … roll/yaw stay where they are
            true,
            GIMBAL_DURATION_SEC,
            false, // joint reference: DEVICE GATE (A9-4 confirms the frame)
            GIMBAL_TIMEOUT_SEC,
        )
        perform(KeyTools.createKey(DJIGimbalKey.KeyRotateByAngle), rotation, cb)
    }

    override fun startContinuousZoom(zoomIn: Boolean, cb: GatewayResult) {
        val param = CameraContinuousOpticalZoomParam(
            if (zoomIn) CameraZoomDirection.ZOOM_IN else CameraZoomDirection.ZOOM_OUT,
            CameraZoomSpeed.NORMAL, // DEVICE GATE: zoom speed confirmed on the RC
        )
        perform(KeyTools.createKey(DJICameraKey.KeyCameraStartContinuousOpticalZoom), param, cb)
    }

    override fun stopContinuousZoom(cb: GatewayResult) {
        perform(KeyTools.createKey(DJICameraKey.KeyCameraStopContinuousOpticalZoom), EmptyMsg(), cb)
    }

    override fun shootPhoto(cb: GatewayResult) {
        perform(KeyTools.createKey(DJICameraKey.KeyStartShootPhoto), EmptyMsg(), cb)
    }

    override fun startRecording(cb: GatewayResult) {
        perform(KeyTools.createKey(DJICameraKey.KeyStartRecord), EmptyMsg(), cb)
    }

    override fun stopRecording(cb: GatewayResult) {
        perform(KeyTools.createKey(DJICameraKey.KeyStopRecord), EmptyMsg(), cb)
    }

    override fun setCameraModePhoto(photo: Boolean, cb: GatewayResult) {
        try {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(CameraKey.KeyCameraMode),
                if (photo) CameraMode.PHOTO_NORMAL else CameraMode.VIDEO_NORMAL,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = cb(null)
                    override fun onFailure(error: IDJIError) = cb(error.description())
                },
            )
        } catch (e: LinkageError) {
            throw e // caller classifies transport failure as UNKNOWN
        }
    }

    private fun <P, R> perform(
        key: dji.sdk.keyvalue.key.DJIKey.ActionKey<P, R>,
        param: P,
        cb: GatewayResult,
    ) {
        KeyManager.getInstance().performAction(
            key,
            param,
            object : CommonCallbacks.CompletionCallbackWithParam<R> {
                override fun onSuccess(result: R) = cb(null)
                override fun onFailure(error: IDJIError) = cb(error.description())
            },
        )
    }

    companion object {
        /** Gimbal nudge duration (s). DEVICE GATE — A9-4 confirms. */
        const val GIMBAL_DURATION_SEC = 1.0

        /** Gimbal action timeout (unit per SDK; value DEVICE GATE — A9-4). */
        const val GIMBAL_TIMEOUT_SEC = 6
    }
}
