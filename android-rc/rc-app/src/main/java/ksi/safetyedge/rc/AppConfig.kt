package ksi.safetyedge.rc

import android.content.Context
import java.util.UUID

/**
 * Stable per-install identities + Core endpoint config (A4). The flight,
 * site, mission and device ids persist across app restarts so a rebooted
 * app resumes the same upload identity instead of forking a new flight.
 * Upload stays disabled until the operator configures a Core URL + token.
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

    val flightId: String get() = stableId("flight_id")
    val siteId: String get() = stableId("site_id")
    val missionId: String get() = stableId("mission_id")
    val missionRevisionId: String get() = stableId("mission_revision_id")
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
