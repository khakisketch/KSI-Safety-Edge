package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adopted event-kind-first records grouping: stored kinds lead, first-seen
 * order is kept, and no sub-area names are invented.
 */
class EventKindGroupsTest {

    private fun row(
        eventId: String,
        eventType: String,
        detectionOnly: Boolean,
        complete: Int = 1,
        total: Int = 1,
    ): FlightHistoryModel.EventRow {
        val card = ObservationModel.EventCard(
            eventId = eventId,
            eventType = eventType,
            mode = "SHADOW",
            detectedAtEdgeNs = 1L,
            observationQuality = "HIGH",
            evidence = (0 until total).map { index ->
                ObservationModel.EvidenceLink(
                    artifactId = "artifact-$index-$eventId",
                    complete = index < complete,
                    synced = false,
                )
            },
        )
        return FlightHistoryModel.EventRow(card, null, 0)
    }

    @Test
    fun groups_by_stored_kind_in_first_seen_order() {
        val rows = listOf(
            row("e1", "PERSONNEL_APPROACH", false),
            row("e2", "DETECTED_ONLY", true),
            row("e3", "PERSONNEL_APPROACH", false),
        )
        val groups = EventKindGroups.group(rows)
        assertEquals(listOf("PERSONNEL_APPROACH", "DETECTED_ONLY"), groups.map { it.kind })
        assertEquals(2, groups[0].rows.size)
        assertEquals(1, groups[1].rows.size)
    }

    @Test
    fun header_labels_detection_vs_candidate_honestly() {
        val groups = EventKindGroups.group(
            listOf(
                row("e1", "DETECTED_ONLY", true),
                row("e2", "PERSONNEL_APPROACH", false),
            ),
        )
        assertEquals("객체 관측", EventKindGroups.headerLabel(groups[0]))
        val candidate = EventKindGroups.headerLabel(groups[1])
        assertTrue("candidate uses the kind's field wording: $candidate", candidate.contains("접근 관측 후보"))
        assertEquals("grouping still retains the exact stored kind", "PERSONNEL_APPROACH", groups[1].kind)
        assertTrue("candidate is never a warning: $candidate", candidate.contains("경고 아님"))
    }

    @Test
    fun row_line_carries_custody_without_zone_names() {
        val line = EventKindGroups.rowLine(row("e1", "PERSONNEL_APPROACH", false, complete = 1, total = 2))
        assertTrue("quality: $line", line.contains("품질 높음"))
        assertTrue("custody count names the receiving device: $line", line.contains("RC 수신 1/2"))
        assertTrue("no invented zone name: $line", !line.contains("구역"))
    }

    @Test
    fun row_line_with_response_and_annotation() {
        val annotated = FlightHistoryModel.EventRow(
            row("e1", "PERSONNEL_APPROACH", false).card,
            FlightHistoryModel.ANNOTATION_FALSE_POSITIVE,
            2,
            "operator-1",
        )
        val line = EventKindGroups.rowLine(annotated)
        assertTrue("annotation state: $line", line.contains("오탐 의심"))
    }
}
