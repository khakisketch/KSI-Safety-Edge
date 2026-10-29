package ksi.safetyedge.rc

import org.junit.Assert.*
import org.junit.Test

class MissionSelectionTest {
    @Test fun actual_sites_and_multiple_plans_keep_the_explicit_choice() {
        assertNull(MissionSelection.retainBundle(listOf("a1", "a2"), null, null))
        assertEquals("a2", MissionSelection.retainBundle(listOf("a1", "a2"), "a2", null))
        assertNull(MissionSelection.retainBundle(listOf("b1", "b2"), "a2", null))
        assertEquals("b1", MissionSelection.retainBundle(listOf("b1", "b2"), "b2", "b1"))
    }

    @Test fun missing_plan_clears_choice_instead_of_choosing_the_first() {
        assertNull(MissionSelection.retainBundle(listOf("a1"), "a2", null))
        assertNull(MissionSelection.retainBundle(emptyList(), "a1", "a1"))
    }

    @Test fun prepared_and_active_plans_cannot_silently_swap() {
        assertTrue(MissionSelection.canChoose("b", null, null))
        assertTrue(MissionSelection.canChoose("a", "a", "a"))
        assertFalse(MissionSelection.canChoose("b", "a", null))
        assertFalse(MissionSelection.canChoose("b", null, "a"))
    }

    @Test fun matching_id_without_matching_digest_cannot_apply_previous_plan() {
        assertTrue(MissionSelection.matches("a", "sha-a", "a", "sha-a"))
        assertFalse(MissionSelection.matches("b", "sha-b", "a", "sha-a"))
        assertFalse(MissionSelection.matches("a", "sha-b", "a", "sha-a"))
        assertFalse(MissionSelection.matches("a", null, "a", null))
        assertFalse(MissionSelection.matches(null, null, null, null))
    }
}
