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
    private val prefs = context.getSharedPreferences(CoreTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val tokenStore = CoreTokenStore(context)

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

    /**
     * The Core token is a secret: it is stored in a Keystore-backed envelope
     * ([CoreTokenStore]), never plaintext. A legacy plaintext value is
     * migrated on first read. If the Keystore fails, reads fall back to the
     * legacy value (or, after a failed write, the value of this session) and
     * [coreTokenStorageError] carries the reason — never hidden.
     */
    var coreToken: String
        get() = tokenStore.read() ?: ""
        set(value) { tokenStore.write(value.trim()) }

    /** Non-null while the Keystore-backed token storage is unhealthy. */
    val coreTokenStorageError: String? get() = tokenStore.lastError

    val coreConfigured: Boolean get() = coreUrl.isNotEmpty() && coreToken.isNotEmpty()

    // -- operator (pilot) identity -------------------------------------------

    /**
     * Person-scoped identity for annotations. The frozen annotation contract
     * requires a uuid `actor_id`, so registration mints a stable uuid once;
     * the name stays on this device (server-side name resolution is a later
     * login/identity item — a name is never fabricated into the record).
     */
    var operatorName: String
        get() = prefs.getString("operator_name", "") ?: ""
        set(value) { prefs.edit().putString("operator_name", value.trim()).apply() }

    var operatorOrganization: String
        get() = prefs.getString("operator_org", "") ?: ""
        set(value) { prefs.edit().putString("operator_org", value.trim()).apply() }

    /** Stable operator uuid (minted on first access). */
    val operatorId: String get() = stableId("operator_id")

    val operatorRegistered: Boolean get() = operatorName.isNotEmpty()
}
