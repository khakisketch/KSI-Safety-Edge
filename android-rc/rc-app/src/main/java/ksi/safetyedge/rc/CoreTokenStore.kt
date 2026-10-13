package ksi.safetyedge.rc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Core token storage backed by the Android Keystore (AES-256-GCM; the key
 * never leaves the Keystore). Replaces plain SharedPreferences storage.
 *
 * Why not EncryptedSharedPreferences: `androidx.security:security-crypto`
 * 1.1.0 (July 2025) deprecated its APIs in favour of direct Android Keystore
 * use, so this stays dependency-free.
 *
 * Honest failure: if the Keystore is unavailable or the envelope cannot be
 * decrypted, [lastError] carries the reason and nothing is silently
 * downgraded. A failed legacy migration keeps the legacy value usable for
 * this session (still surfaced via [lastError]).
 */
class CoreTokenStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Human-readable reason of the last storage failure (null = healthy). */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun read(): String? {
        migrateLegacyIfNeeded()
        val stored = prefs.getString(PREF_ENCRYPTED, null)
        if (!stored.isNullOrEmpty()) {
            return try {
                decrypt(stored)
            } catch (e: Exception) {
                lastError = "Keystore 복호화 실패: ${e.javaClass.simpleName}"
                null
            }
        }
        // The envelope is absent: either nothing was stored, or the legacy
        // migration could not finish (lastError set). The legacy value stays
        // usable for this session — visible in the error text, never hidden.
        return prefs.getString(PREF_LEGACY, null)
    }

    @Synchronized
    fun write(plain: String) {
        if (plain.isEmpty()) {
            prefs.edit().remove(PREF_ENCRYPTED).remove(PREF_LEGACY).apply()
            lastError = null
            return
        }
        try {
            prefs.edit().putString(PREF_ENCRYPTED, encrypt(plain)).apply()
            lastError = null
        } catch (e: Exception) {
            lastError = "Keystore 암호화 실패: ${e.javaClass.simpleName}"
        }
    }

    /** One-time move of the legacy plaintext token; never leaves it behind. */
    private fun migrateLegacyIfNeeded() {
        val legacy = prefs.getString(PREF_LEGACY, null)
        val encrypted = prefs.getString(PREF_ENCRYPTED, null)
        when (CoreTokenPolicy.legacyAction(legacy, encrypted)) {
            CoreTokenPolicy.LegacyAction.MIGRATE -> try {
                prefs.edit()
                    .putString(PREF_ENCRYPTED, encrypt(legacy!!))
                    .remove(PREF_LEGACY)
                    .apply()
            } catch (e: Exception) {
                // Keep the legacy value for a later attempt — do not lose the token.
                lastError = "기존 토큰 마이그레이션 실패: ${e.javaClass.simpleName}"
            }
            CoreTokenPolicy.LegacyAction.DROP_LEGACY ->
                prefs.edit().remove(PREF_LEGACY).apply()
            CoreTokenPolicy.LegacyAction.NONE -> Unit
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${b64(cipher.iv)}:${b64(ct)}"
    }

    private fun decrypt(stored: String): String? {
        if (!CoreTokenPolicy.envelopeWellFormed(stored)) {
            lastError = "저장 형식 손상 — 토큰을 다시 입력하세요"
            return null
        }
        val parts = stored.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, b64d(parts[0])),
        )
        return String(cipher.doFinal(b64d(parts[1])), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val PREFS_NAME = "ksi_rc"
        const val PREF_ENCRYPTED = "core_token_enc"
        const val PREF_LEGACY = "core_token"
        const val KEY_ALIAS = "ksi_rc_core_token"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
