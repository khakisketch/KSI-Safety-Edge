package ksi.msdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A9 host tests: segment rotation indexing and file naming. */
class SegmentRotationTest {
    @Test
    fun indices_roll_over_exactly_at_the_boundary() {
        val policy = SegmentRotationPolicy(segmentSeconds = 300)
        assertEquals(0, policy.segmentIndexFor(0))
        assertEquals(0, policy.segmentIndexFor(299_999))
        assertEquals(1, policy.segmentIndexFor(300_000))
        assertEquals(1, policy.segmentIndexFor(599_999))
        assertEquals(2, policy.segmentIndexFor(600_000))
    }

    @Test
    fun file_names_sort_chronologically() {
        val policy = SegmentRotationPolicy(segmentSeconds = 60)
        assertEquals("seg-00000.mp4", policy.fileName(0))
        assertEquals("seg-00001.mp4", policy.fileName(1))
        val sorted = listOf(policy.fileName(12), policy.fileName(2), policy.fileName(103))
            .sorted()
        assertEquals(
            listOf("seg-00002.mp4", "seg-00012.mp4", "seg-00103.mp4"),
            sorted,
        )
    }

    @Test
    fun invalid_parameters_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentRotationPolicy(segmentSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SegmentRotationPolicy(60).segmentIndexFor(-1)
        }
    }
}
