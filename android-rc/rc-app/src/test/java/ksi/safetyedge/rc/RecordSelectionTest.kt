package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSelectionTest {
    @Test fun explicitlyRequestedRecordIsNeverReplacedByNewest() {
        assertEquals("older", RecordSelection.resolve("older", listOf("newest", "older")))
        assertNull(RecordSelection.resolve("missing", listOf("newest", "older")))
        assertNull(RecordSelection.resolve("missing", emptyList()))
    }

    @Test fun initialListDefaultsToNewestOnlyWithoutExplicitSelection() {
        assertEquals("newest", RecordSelection.resolve(null, listOf("newest", "older")))
        assertNull(RecordSelection.resolve(null, emptyList()))
    }

    @Test fun asynchronousResultCannotOpenWrongOrHiddenRecord() {
        assertTrue(RecordSelection.shouldOpen("older", "older", true, true))
        assertFalse(RecordSelection.shouldOpen("older", "newer", true, true))
        assertFalse(RecordSelection.shouldOpen("older", "older", false, true))
        assertFalse(RecordSelection.shouldOpen("older", "older", true, false))
        assertFalse(RecordSelection.shouldOpen(null, "older", true, true))
    }
}
