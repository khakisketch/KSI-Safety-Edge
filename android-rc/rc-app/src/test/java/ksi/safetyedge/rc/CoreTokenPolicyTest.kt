package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the pure token-storage policy (no Android runtime). */
class CoreTokenPolicyTest {

    @Test
    fun `legacy plaintext migrates when no encrypted token exists`() {
        assertEquals(
            CoreTokenPolicy.LegacyAction.MIGRATE,
            CoreTokenPolicy.legacyAction(legacy = "tok-1", encrypted = null),
        )
        assertEquals(
            CoreTokenPolicy.LegacyAction.MIGRATE,
            CoreTokenPolicy.legacyAction(legacy = "tok-1", encrypted = ""),
        )
    }

    @Test
    fun `legacy plaintext is dropped when an encrypted token already exists`() {
        assertEquals(
            CoreTokenPolicy.LegacyAction.DROP_LEGACY,
            CoreTokenPolicy.legacyAction("tok-1", "aXY=:Y3Q="),
        )
    }

    @Test
    fun `nothing happens without a legacy value`() {
        assertEquals(
            CoreTokenPolicy.LegacyAction.NONE,
            CoreTokenPolicy.legacyAction(legacy = null, encrypted = "aXY=:Y3Q="),
        )
        assertEquals(
            CoreTokenPolicy.LegacyAction.NONE,
            CoreTokenPolicy.legacyAction(legacy = "", encrypted = null),
        )
    }

    @Test
    fun `envelope framing requires two non-empty parts`() {
        assertTrue(CoreTokenPolicy.envelopeWellFormed("aXY=:Y3Q="))
        assertFalse(CoreTokenPolicy.envelopeWellFormed(null))
        assertFalse(CoreTokenPolicy.envelopeWellFormed(""))
        assertFalse(CoreTokenPolicy.envelopeWellFormed("no-separator"))
        assertFalse(CoreTokenPolicy.envelopeWellFormed(":Y3Q="))
        assertFalse(CoreTokenPolicy.envelopeWellFormed("aXY=:"))
    }
}
