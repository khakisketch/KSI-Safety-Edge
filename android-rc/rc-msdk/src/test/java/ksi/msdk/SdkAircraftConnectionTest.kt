package ksi.msdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host tests for the A9-4 honesty gate ([SdkAircraftConnection]).
 *
 * On a host JVM there is no DJI runtime: the read degrades to `null`
 * and the raw report to `false` via the LinkageError catch — never a
 * throw, never a claim. (DEVICE GATE for actual connected/handshake
 * values.) The gate DECISION itself is pure and pinned exhaustively
 * here: `KeyConnection=true` alone never becomes connected; only
 * true-plus-handshake does; `false`/null pass through unchanged.
 */
class SdkAircraftConnectionTest {

    @Test
    fun without_sdk_runtime_the_read_is_unknown_never_a_claim() {
        val read = SdkAircraftConnection.readAircraftConnected()
        assertNull(
            "no SDK runtime on host: must not claim either way, got=$read",
            read,
        )
    }

    @Test
    fun without_sdk_runtime_nothing_is_reported() {
        assertFalse(
            "no SDK runtime on host: no MSDK report may exist",
            SdkAircraftConnection.msdkReportsConnection(),
        )
        assertFalse(
            "no SDK runtime on host: no handshake may exist",
            SdkAircraftConnection.hasProductHandshake(),
        )
        assertFalse(
            "no SDK runtime on host: never registered",
            SdkAircraftConnection.isSdkRegistered(),
        )
    }

    @Test
    fun bare_true_without_handshake_degrades_to_unknown() {
        assertNull(
            "KeyConnection=true alone must never display 연결됨",
            SdkAircraftConnection.gateConnection(reported = true, handshakePresent = false),
        )
    }

    @Test
    fun true_with_handshake_stays_connected() {
        assertEquals(
            true,
            SdkAircraftConnection.gateConnection(reported = true, handshakePresent = true),
        )
    }

    @Test
    fun false_and_null_pass_through_unchanged() {
        assertEquals(
            false,
            SdkAircraftConnection.gateConnection(reported = false, handshakePresent = true),
        )
        assertEquals(
            false,
            SdkAircraftConnection.gateConnection(reported = false, handshakePresent = false),
        )
        assertNull(
            SdkAircraftConnection.gateConnection(reported = null, handshakePresent = true),
        )
        assertNull(
            SdkAircraftConnection.gateConnection(reported = null, handshakePresent = false),
        )
    }
}
