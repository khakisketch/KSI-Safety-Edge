package ksi.msdk

import dji.sdk.keyvalue.key.DJIProductKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import ksi.safetyedge.rc.AircraftConnectionProvider

/**
 * Live aircraft-connection read off the real MSDK v5 key
 * (`DJIProductKey.KeyConnection`, `DJIKeyInfo<Boolean>`).
 *
 * Facts, verified against the installed 5.18.0 `-provided` stubs with
 * `javap` plus the official v5 Key/ProductKey docs (Context7, 2026-10-21):
 * - `KeyConnection` is get+listen, supported since MSDK 5.0.0; `true`
 *   means the hardware product is connected.
 * - `KeyManager.getInstance().getValue(key)` reads the MSDK cache
 *   synchronously; the no-default overload yields null when the value
 *   cannot be read (the documented default-value overload exists
 *   precisely for that case).
 *
 * Decided honesty rule (A9-4 false-healthy): `KeyConnection=true` ALONE
 * never reads as connected — the MSDK reported TRUE with zero product
 * present (device evidence: registration status 0, no product/aircraft
 * events in 7 logcats, headline flipped 확인 불가 → 연결됨). Displaying
 * 연결됨 on that input is a false healthy. Connected requires the
 * product handshake too ([hasProductHandshake]: a recognizable linked
 * product type is readable through the MSDK keys). Anything less is
 * unknown; the bare MSDK report stays visible via
 * [msdkReportsConnection] so diagnostics can surface it instead of
 * masking it.
 *
 * Honesty rules:
 * - SDK registration is checked FIRST (`isRegistered`): an unregistered
 *   SDK reports unknown, never healthy and never absent.
 * - No `registerApp()` is ever attempted here (read-only probe — the
 *   singleton register flow in [DjiSdkBridge] stays the single writer),
 *   and no value is ever cached: every call re-reads, so a stale `true`
 *   can never leak into a gate decision.
 * - `null` covers missing/unreadable/stale/error uniformly; callers must
 *   treat it as "cannot confirm", i.e. refuse — never as disconnected
 *   (which would be a claim) and never as connected (which would be a
 *   fabrication).
 *
 * Host safety: DJI classes appear only inside method bodies, so merely
 * referencing this object never loads the SDK runtime; on a host JVM the
 * read degrades to `null`/`false` via the LinkageError catch. DEVICE
 * GATE for the actual connected/handshake values (A9-4).
 */
object SdkAircraftConnection : AircraftConnectionProvider {

    /**
     * Pure honesty gate (host-testable): the SDK's `KeyConnection` claim
     * becomes a connected fact only together with a product handshake.
     * A bare `true` degrades to unknown (surfaced in diagnostics, never
     * displayed as 연결됨); `false` and null pass through unchanged.
     */
    fun gateConnection(reported: Boolean?, handshakePresent: Boolean): Boolean? =
        when {
            reported != true -> reported
            handshakePresent -> true
            else -> null
        }

    /** The MSDK's own connection claim (KeyConnection), independent of
     *  the handshake gate: true only when a registered SDK reports
     *  connected. Read-only, never cached; false when unregistered,
     *  unreadable, or host-side. Diagnostics surface this when the gated
     *  fact stays unknown — the report is shown, not masked. */
    fun msdkReportsConnection(): Boolean = try {
        if (!SDKManager.getInstance().isRegistered) {
            false
        } else {
            KeyManager.getInstance()
                .getValue(KeyTools.createKey(DJIProductKey.KeyConnection)) == true
        }
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }

    /**
     * Product handshake: a recognizable linked product type is readable
     * through the MSDK keys ([DJIProductKey.KeyProductType]). Unknown,
     * unrecognized, missing, or unreadable reads as no handshake. Never
     * throws (fail-closed false).
     */
    fun hasProductHandshake(): Boolean = try {
        if (!SDKManager.getInstance().isRegistered) {
            false
        } else {
            val type: ProductType? = KeyManager.getInstance()
                .getValue(KeyTools.createKey(DJIProductKey.KeyProductType))
            type != null && type != ProductType.UNKNOWN && type != ProductType.UNRECOGNIZED
        }
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }

    /**
     * Static SDK registration fact (no bridge instance needed, no side
     * effects): true only when the MSDK itself reports registered. Used
     * on routes where no link ever starts a bridge (TEST_TCP) so a
     * registered SDK is never misreported as failed. LinkageError/
     * Exception (Helper not installed, host runtime) degrade to false.
     */
    fun isSdkRegistered(): Boolean = try {
        SDKManager.getInstance().isRegistered
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }

    override fun readAircraftConnected(): Boolean? = try {
        if (!SDKManager.getInstance().isRegistered) {
            null
        } else {
            val reported: Boolean? = KeyManager.getInstance()
                .getValue(KeyTools.createKey(DJIProductKey.KeyConnection))
            gateConnection(reported, hasProductHandshake())
        }
    } catch (_: LinkageError) {
        null
    } catch (_: Exception) {
        null
    }
}
