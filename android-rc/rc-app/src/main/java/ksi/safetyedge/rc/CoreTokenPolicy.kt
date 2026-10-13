package ksi.safetyedge.rc

/**
 * Pure storage policy for the Core token (host-testable; no Android APIs).
 *
 * The token used to live in plain SharedPreferences (`core_token`). It now
 * lives in a Keystore-backed envelope (`core_token_enc`); a legacy plaintext
 * value is migrated once and removed either way — it is never left behind.
 */
object CoreTokenPolicy {

    enum class LegacyAction { NONE, MIGRATE, DROP_LEGACY }

    fun legacyAction(legacy: String?, encrypted: String?): LegacyAction = when {
        !legacy.isNullOrEmpty() && encrypted.isNullOrEmpty() -> LegacyAction.MIGRATE
        !legacy.isNullOrEmpty() -> LegacyAction.DROP_LEGACY
        else -> LegacyAction.NONE
    }

    /** The envelope is `base64(iv):base64(ciphertext)` — two non-empty parts. */
    fun envelopeWellFormed(stored: String?): Boolean {
        val parts = stored?.split(':', limit = 2) ?: return false
        return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()
    }
}
