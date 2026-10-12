package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SegmentNamesTest {
    @Test
    fun file_name_and_parse_round_trip() {
        assertEquals("seg-00000.mp4", SegmentNames.fileName(0))
        assertEquals("seg-00012.mp4", SegmentNames.fileName(12))
        assertEquals(0L, SegmentNames.parseIndex(SegmentNames.fileName(0)))
        assertEquals(12L, SegmentNames.parseIndex("seg-00012.mp4"))
    }

    @Test
    fun foreign_and_tmp_names_are_not_parsed() {
        assertNull(SegmentNames.parseIndex("seg-00012.mp4.tmp"))
        assertNull(SegmentNames.parseIndex("other.mp4"))
        assertNull(SegmentNames.parseIndex("seg-.mp4"))
        assertNull(SegmentNames.parseIndex("seg-xx.mp4"))
    }
}
