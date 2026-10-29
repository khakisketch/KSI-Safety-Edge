package ksi.safetyedge.rc

/**
 * Adopted event-kind-first records grouping. Pure Kotlin, no Android —
 * host-tested.
 *
 * Events are grouped by their STORED kind (`event_type` + detection flag),
 * in first-seen order. No sub-area names are invented: the group header is
 * the stored kind only, and zone references stay short revision ids (the
 * row text never carries a zone name the store does not hold).
 */
object EventKindGroups {

    data class KindGroup(
        /** Stored kind key (event_type as held, never rewritten). */
        val kind: String,
        val detectionOnly: Boolean,
        val rows: List<FlightHistoryModel.EventRow>,
    )

    fun group(rows: List<FlightHistoryModel.EventRow>): List<KindGroup> {
        val ordered = LinkedHashMap<String, MutableList<FlightHistoryModel.EventRow>>()
        val flags = mutableMapOf<String, Boolean>()
        for (row in rows) {
            val key = row.card.eventType
            ordered.getOrPut(key) { mutableListOf() }.add(row)
            flags[key] = row.card.detectionOnly
        }
        return ordered.map { (kind, members) ->
            KindGroup(kind = kind, detectionOnly = flags[kind] == true, rows = members.toList())
        }
    }

    /** Honest kind header: detection objects vs risk candidates (warnings never claimed). */
    fun headerLabel(group: KindGroup): String =
        if (group.detectionOnly) "객체 관측" else when (group.kind) {
            "SPATIAL_CANDIDATE" -> "공간 관측 후보 · 경고 아님"
            "PERSONNEL_APPROACH" -> "접근 관측 후보 · 경고 아님"
            else -> "기타 관측 후보 · 경고 아님"
        }

    /** One 48dp row line: quality + assessment + evidence custody + annotation state. */
    fun rowLine(row: FlightHistoryModel.EventRow): String {
        val complete = row.card.evidence.count { it.complete }
        val custody = if (row.card.evidence.isEmpty()) {
            "증거 없음"
        } else {
            "RC 수신 $complete/${row.card.evidence.size}" +
                if (row.card.evidenceSynced) " · 전송 커밋 확인" else ""
        }
        // The sealed machine judgement travels separately from the event
        // row (artifact plane, not the event payload): show its link
        // state, never invent a verdict.
        val assessment = AssessmentModel.rowSuffix(row.card.assessment)
            ?.let { " · $it" } ?: ""
        val annotation = when (row.effectiveAnnotation) {
            FlightHistoryModel.ANNOTATION_FALSE_POSITIVE -> " · 오탐 의심"
            FlightHistoryModel.ANNOTATION_NEEDS_REVIEW -> " · 검토 필요"
            else -> ""
        }
        return "품질 ${UserFacingCopy.observationQualityLabel(row.card.observationQuality)} · $custody$assessment$annotation"
    }
}
