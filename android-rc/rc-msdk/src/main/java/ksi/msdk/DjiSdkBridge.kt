package ksi.msdk

import android.content.Context
import android.content.pm.PackageManager
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

enum class DjiBridgeState { IDLE, KEY_MISSING, INITIALIZING, REGISTERED, FAILED }

/**
 * DJI MSDK v5 initialisation/registration wrapper (A9).
 *
 * Honest boundary: without a developer app key in the manifest meta-data
 * (`com.dji.sdk.API_KEY`, injected at build time from local.properties —
 * never committed) the SDK is NOT initialised, so bench builds and the
 * frozen pipeline stay deterministic. Registration success, product
 * connection and every SDK call below are DEVICE-GATE facts: they compile
 * and package now, and are verified on the RC (A9 ground session).
 */
class DjiSdkBridge(private val context: Context) {
    var state: DjiBridgeState = DjiBridgeState.IDLE
        private set
    var connectedProductCount: Int = 0
        private set
    var lastError: String? = null
        private set

    private var onChange: ((DjiBridgeState) -> Unit)? = null

    fun appKeyPresent(): Boolean = try {
        val info = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA,
        )
        !info.metaData?.getString(API_KEY_META).isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    fun start(onChange: (DjiBridgeState) -> Unit) {
        this.onChange = onChange
        if (!appKeyPresent()) {
            setState(
                DjiBridgeState.KEY_MISSING,
                "$API_KEY_META meta-data missing/blank — SDK not initialised",
            )
            return
        }
        val manager = SDKManager.getInstance()
        if (manager.isRegistered) {
            setState(DjiBridgeState.REGISTERED, null)
            return
        }
        setState(DjiBridgeState.INITIALIZING, null)
        manager.init(context, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                setState(DjiBridgeState.REGISTERED, null)
            }

            override fun onRegisterFailure(error: IDJIError?) {
                setState(DjiBridgeState.FAILED, error?.description())
            }

            override fun onProductDisconnect(productId: Int) {
                connectedProductCount = (connectedProductCount - 1).coerceAtLeast(0)
            }

            override fun onProductConnect(productId: Int) {
                connectedProductCount += 1
            }

            override fun onProductChanged(productId: Int) { /* diagnostics only */ }

            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                /* progress is surfaced by state INITIALIZING until registered */
            }

            override fun onDatabaseDownloadProgress(currentBytes: Long, totalBytes: Long) {
                /* fly-safe DB download: progress logged by the SDK */
            }
        })
        manager.registerApp()
    }

    private fun setState(next: DjiBridgeState, error: String?) {
        state = next
        lastError = error
        onChange?.invoke(next)
    }

    companion object {
        const val API_KEY_META = "com.dji.sdk.API_KEY"
    }
}
