package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the honest close-out decisions (contract §6): segment→boot
 * linkage resolves to a single boot only, and only a confirmed STOPPED
 * reads as a clean close.
 */
class FlightCloseoutTest {

    @Test
    fun single_distinct_boot_is_unambiguous() {
        val fromAnchor = FlightCloseout.resolveSegmentBoot("boot-a", listOf("boot-a"))
        assertTrue(fromAnchor is FlightCloseout.SegmentBootResolution.SingleBoot)
        assertEquals("boot-a", (fromAnchor as FlightCloseout.SegmentBootResolution.SingleBoot).bootId)

        val storedOnly = FlightCloseout.resolveSegmentBoot(null, listOf("boot-a"))
        assertEquals(
            "boot-a",
            (storedOnly as FlightCloseout.SegmentBootResolution.SingleBoot).bootId,
        )
    }

    @Test
    fun multi_boot_ambiguity_is_unverifiable_never_relabeled_latest() {
        val ambiguous = FlightCloseout.resolveSegmentBoot("boot-a", listOf("boot-a", "boot-b"))
        assertTrue(ambiguous is FlightCloseout.SegmentBootResolution.Unverifiable)

        val anchorMismatch = FlightCloseout.resolveSegmentBoot("boot-a", listOf("boot-b"))
        assertTrue(anchorMismatch is FlightCloseout.SegmentBootResolution.Unverifiable)

        val noBoots = FlightCloseout.resolveSegmentBoot(null, emptyList())
        assertTrue(noBoots is FlightCloseout.SegmentBootResolution.Unverifiable)

        val reason = (ambiguous as FlightCloseout.SegmentBootResolution.Unverifiable).reason
        assertTrue(reason.contains("미검증"))
    }

    @Test
    fun only_confirmed_stopped_reads_clean() {
        val clean = FlightCloseout.assessStop(
            sessionOpen = true, confirmedStopped = true, linkUp = true,
        )
        assertTrue(clean.clean)
        assertTrue(clean.note.contains("STOPPED 확인됨"))

        // STOP pending/unknown never reads clean-closed.
        val pending = FlightCloseout.assessStop(
            sessionOpen = true, confirmedStopped = false, linkUp = true,
        )
        assertFalse(pending.clean)
        assertTrue(pending.note.contains("STOPPED 미확인"))

        val linkDown = FlightCloseout.assessStop(
            sessionOpen = true, confirmedStopped = false, linkUp = false,
        )
        assertFalse(linkDown.clean)
        assertTrue(linkDown.note.contains("링크 단절"))

        val noSession = FlightCloseout.assessStop(
            sessionOpen = false, confirmedStopped = false, linkUp = true,
        )
        assertFalse(noSession.clean)
        assertTrue(noSession.note.contains("관측 세션 없음"))
    }
}
