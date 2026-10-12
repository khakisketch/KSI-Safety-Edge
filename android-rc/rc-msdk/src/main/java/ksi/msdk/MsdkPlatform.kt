package ksi.msdk

import android.app.Application
import com.cySdkyc.clx.Helper

/**
 * A9: the earliest SDK bootstrap for DJI MSDK v5 (device flavor only).
 *
 * Measured facts (C60, 2026-10-12, against Maven Central 5.18.0):
 * - `dji-sdk-v5-aircraft` ships native libraries + a 9-entry `classes.jar`
 *   containing exactly this obfuscated `com.cySdkyc.clx.Helper`;
 * - the Java API classes (e.g. `dji.v5.manager.SDKManager`) live in the
 *   `-provided` jar, which is `compileOnly` in the official sample — and
 *   `dji/v5/manager/SDKManager` is NOT defined in our built device APK's
 *   dex (verified by parsing all classes*.dex of the C59 APK);
 * - `Helper` exposes `native attach(Application, Context)` /
 *   `makeInMemoryDexElements(...)`: it installs the SDK's runtime classes
 *   into the process at runtime, which is why the official sample calls
 *   `Helper.install(this)` FIRST, in `Application.attachBaseContext()`,
 *   before any `dji.v5.*` class is touched.
 *
 * This class is called from [RcApp.attachBaseContext] through the device
 * flavor's PlatformLinks; the bench flavor never includes rc-msdk. The call
 * is honest about failure: a linkage/native error is recorded as FAILED,
 * never hidden, and registration itself stays a device-gate fact.
 */
object MsdkPlatform {
    enum class State { NOT_RUN, INSTALLED, FAILED }

    @Volatile
    var state: State = State.NOT_RUN
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Idempotent; must run before any other SDK class is loaded. A failed
     *  install is retried on the next call (only INSTALLED short-circuits). */
    @Synchronized
    fun install(application: Application) {
        if (state == State.INSTALLED) return
        try {
            Helper.install(application)
            state = State.INSTALLED
            lastError = null
        } catch (e: LinkageError) {
            state = State.FAILED
            lastError = "Helper.install linkage: ${e.message ?: e.javaClass.name}"
        } catch (e: Exception) {
            state = State.FAILED
            lastError = "Helper.install failed: ${e.message ?: e.javaClass.name}"
        }
    }
}
