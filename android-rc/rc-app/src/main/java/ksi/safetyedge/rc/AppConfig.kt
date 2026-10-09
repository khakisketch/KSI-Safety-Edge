package ksi.safetyedge.rc

import android.content.Context
import java.util.UUID

/**
 * Install-scoped app configuration. The RC DEVICE id is stable per install
 * (upload identity); FLIGHT ids are per-flight and live in the flight
 * records — never here. Upload stays disabled until the operator configures
 * a Core URL + token.
 */
class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("ksi_rc", Context.MODE_PRIVATE)

    private fun stableId(key: String): String {
        var id = prefs.getString(key, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(key, id).apply()
        }
        return id
    }

    val rcDeviceId: String get() = stableId("rc_device_id")

    var coreUrl: String
        get() = prefs.getString("core_url", "") ?: ""
        set(value) { prefs.edit().putString("core_url", value.trim()).apply() }

    var coreToken: String
        get() = prefs.getString("core_token", "") ?: ""
        set(value) { prefs.edit().putString("core_token", value.trim()).apply() }

    // Pre-release: move the token to EncryptedSharedPreferences (A8).
    val coreConfigured: Boolean get() = coreUrl.isNotEmpty() && coreToken.isNotEmpty()
}
