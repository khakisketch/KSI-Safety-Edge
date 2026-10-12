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
 * frozen pipeline stay deterministic.
 *
 * A9-1 order (verified against the official sample, 2026-10-12):
 * 1. `com.cySdkyc.clx.Helper.install(application)` in
 *    `Application.attachBaseContext` (see [MsdkPlatform]) — it loads the
 *    SDK runtime classes, which are NOT part of our app dex;
 * 2. `SDKManager.getInstance().init(context, callback)`;
 * 3. `registerApp()` only after `onInitProcess(INITIALIZE_COMPLETE)` —
 *    never immediately after init().
 * Registration success, product connection and every SDK call below are
 * DEVICE-GATE facts: they compile and package now, to be verified on the
 * RC (device gate) — nothing here claims device verification yet.
 */
class DjiSdkBridge(private val context: Context) {
    @Volatile
    var state: DjiBridgeState = DjiBridgeState.IDLE
        private set
    @Volatile
    var connectedProductCount: Int = 0
        private set
    @Volatile
    var lastError: String? = null
        private set

    private var onChange: ((DjiBridgeState) -> Unit)? = null

    @Volatile
    private var initializing = false

    @Volatile
    private var registrationRequested = false

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
        val manager = try {
            SDKManager.getInstance()
        } catch (e: LinkageError) {
            // A9-1: honest failure when the SDK runtime classes are absent
            // (the Helper bootstrap in [MsdkPlatform] loads them).
            setState(DjiBridgeState.FAILED, "SDKManager unavailable: ${reason(e)}")
            return
        } catch (e: Exception) {
            setState(DjiBridgeState.FAILED, "SDKManager unavailable: ${reason(e)}")
            return
        }
        if (manager.isRegistered) {
            setState(DjiBridgeState.REGISTERED, null)
            return
        }
        if (initializing) {
            // init() runs once per process; later start() calls just
            // re-observe the current state — including a recorded FAILED,
            // which must never be masked as INITIALIZING (registration
            // retry is explicit: retryRegistration()).
            setState(state, lastError)
            return
        }
        initializing = true
        setState(DjiBridgeState.INITIALIZING, null)
        try {
            manager.init(
                context,
                object : SDKManagerCallback {
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
                        // A9-1 (official sample order): registerApp() waits
                        // for INITIALIZE_COMPLETE — never called directly
                        // after init(). The flag keeps it to one call.
                        if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                            registerOnce()
                        }
                    }

                    override fun onDatabaseDownloadProgress(currentBytes: Long, totalBytes: Long) {
                        /* fly-safe DB download: progress logged by the SDK */
                    }
                },
            )
        } catch (e: LinkageError) {
            setState(DjiBridgeState.FAILED, "SDK init linkage: ${reason(e)}")
        } catch (e: Exception) {
            setState(DjiBridgeState.FAILED, "SDK init failed: ${reason(e)}")
        }
    }

    @Synchronized
    private fun registerOnce() {
        if (registrationRequested) return
        registrationRequested = true
        try {
            SDKManager.getInstance().registerApp()
        } catch (e: LinkageError) {
            setState(DjiBridgeState.FAILED, "registerApp linkage: ${reason(e)}")
        } catch (e: Exception) {
            setState(DjiBridgeState.FAILED, "registerApp failed: ${reason(e)}")
        }
    }

    /** Retry a failed registration once (e.g. after the network returned);
     *  a no-op when already registered or the key is missing. The official
     *  sample re-registers on network-availability callbacks — the app can
     *  call this on its own connectivity signal. No re-init is performed. */
    @Synchronized
    fun retryRegistration() {
        if (state == DjiBridgeState.REGISTERED) return
        if (!appKeyPresent()) return
        registrationRequested = false
        registerOnce()
    }

    private fun reason(e: Throwable): String = e.message ?: e.javaClass.name

    private fun setState(next: DjiBridgeState, error: String?) {
        state = next
        lastError = error
        onChange?.invoke(next)
    }

    companion object {
        const val API_KEY_META = "com.dji.sdk.API_KEY"
    }
}
