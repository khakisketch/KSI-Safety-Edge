package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObservationValidityTest {

    @Test
    fun missing_source_holds_detection_fact() {
        val v = ObservationValidity.evaluate(
            hasDetection = true, hasSource = false,
            frameCorrespondence = false, geoConditionsMet = false,
            appliedZoneConfirmed = false,
        )
        assertEquals(ObservationValidity.Stage.HELD, v.detection)
        assertEquals(ObservationValidity.Stage.HELD, v.liveOverlay)
        assertEquals(ObservationValidity.Stage.HELD, v.mapRelation)
    }

    @Test
    fun detection_preserved_while_overlay_and_map_held() {
        val v = ObservationValidity.evaluate(
            hasDetection = true, hasSource = true,
            frameCorrespondence = false, geoConditionsMet = false,
            appliedZoneConfirmed = false,
        )
        assertEquals(ObservationValidity.Stage.SHOWN, v.detection)
        assertEquals(ObservationValidity.Stage.HELD, v.liveOverlay)
        assertEquals(ObservationValidity.Stage.HELD, v.mapRelation)
        assertTrue(v.anyHeld)
    }

    @Test
    fun map_relation_requires_applied_zone_even_when_geo_conditions_met() {
        val withoutZone = ObservationValidity.evaluate(
            hasDetection = true, hasSource = true,
            frameCorrespondence = true, geoConditionsMet = true,
            appliedZoneConfirmed = false,
        )
        assertEquals(ObservationValidity.Stage.SHOWN, withoutZone.liveOverlay)
        assertEquals(ObservationValidity.Stage.HELD, withoutZone.mapRelation)

        val withZone = ObservationValidity.evaluate(
            hasDetection = true, hasSource = true,
            frameCorrespondence = true, geoConditionsMet = true,
            appliedZoneConfirmed = true,
        )
        assertEquals(ObservationValidity.Stage.SHOWN, withZone.mapRelation)
    }

    @Test
    fun full_evidence_shows_all_three_stages() {
        val v = ObservationValidity.evaluate(
            hasDetection = true, hasSource = true,
            frameCorrespondence = true, geoConditionsMet = true,
            appliedZoneConfirmed = true,
        )
        assertEquals(ObservationValidity.Stage.SHOWN, v.detection)
        assertEquals(ObservationValidity.Stage.SHOWN, v.liveOverlay)
        assertEquals(ObservationValidity.Stage.SHOWN, v.mapRelation)
        assertEquals(false, v.anyHeld)
    }
}

class ShadowCandidateSelectionTest {

    @Test
    fun missing_and_stale_sources_are_reported_not_hidden() {
        val missing = ShadowCandidateSelection.select("c1", sourcePresent = false, sourceFresh = false)
        assertEquals(ShadowCandidateSelection.SourceStatus.MISSING, missing.source)

        val stale = ShadowCandidateSelection.select("c2", sourcePresent = true, sourceFresh = false)
        assertEquals(ShadowCandidateSelection.SourceStatus.STALE, stale.source)
    }

    @Test
    fun available_source_is_shown_with_candidate_id() {
        val v = ShadowCandidateSelection.select("c3", sourcePresent = true, sourceFresh = true)
        assertEquals(ShadowCandidateSelection.SourceStatus.AVAILABLE, v.source)
        assertEquals("c3", v.candidateId)
    }

    @Test
    fun blank_candidate_id_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ShadowCandidateSelection.select(" ", sourcePresent = true, sourceFresh = true)
        }
    }
}
