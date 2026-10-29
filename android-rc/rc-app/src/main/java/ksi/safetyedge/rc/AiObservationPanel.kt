package ksi.safetyedge.rc

/**
 * AI safety observation area for the integrated operation screen.
 *
 * Pure Kotlin, no Android: host-tested. Reads ONLY data that already
 * exists — [ObservationModel] derivations from the rc-core store plus the
 * video source's frame-mapping flag. No new observation pipeline is
 * invented here; when the source data is absent the panel says so
 * explicitly instead of fabricating a detection.
 *
 * Honesty rules (operation screen contract v1 §4, AT-06):
 * - "observation unavailable" ([Panel.unavailable]) is never "no risk";
 * - live boxes are never shown while frame correspondence is unverified
 *   ([Panel.overlayNote]);
 * - SHADOW candidates and ACTIVE alerts look different ([CandidateRow.kind]);
 * - a held map relation never deletes the detection fact
 *   ([Panel.mapNote]).
 */
object AiObservationPanel {

    data class Input(
        val hasActiveFlight: Boolean,
        val observationCount: Int,
        val eventCount: Int,
        val observationAgeMs: Long?,
        val frameMappingVerified: Boolean,
        /** Newest first (as returned by [ObservationModel.eventCards]). */
        val cards: List<ObservationModel.EventCard> = emptyList(),
        /**
         * Latched frame-processing evidence from the Edge runtime report
         * (null = the Edge never reported counts — unknown, never assumed).
         * Processing confirmation does not prove a clean-negative result.
         * With zero received observation records, delivery is still unknown.
         */
        val analysisRan: Boolean? = null,
    )

    data class CandidateRow(
        val eventId: String,
        /** "SHADOW", "ACTIVE", "모드 미확인" or "객체 관측" — never collapsed. */
        val kind: String,
        val quality: String,
        val evidenceSummary: String,
    )

    data class Panel(
        val headline: String,
        val detail: String,
        val overlayNote: String,
        val mapNote: String,
        val candidates: List<CandidateRow>,
        /** True = the observation source itself is missing/stale. */
        val unavailable: Boolean,
        val emptyListNote: String = "표시할 후보 없음 · 평가 범위 미확인",
    )

    /** Newest candidates shown in the glanceable area. */
    const val MAX_CANDIDATES = 5

    fun render(input: Input): Panel {
        if (!input.hasActiveFlight) {
            return Panel(
                headline = "기록 없음 · 활성 KSI 세션 없음",
                detail = "관측 세션 시작 후 Edge 관측이 표시됩니다",
                overlayNote = overlayNote(input.frameMappingVerified),
                mapNote = MAP_HELD_NOTE,
                candidates = emptyList(),
                unavailable = false,
                emptyListNote = "활성 KSI 세션 없음 · 후보 상태 미확인",
            )
        }
        if (input.observationCount == 0 && input.eventCount == 0) {
            if (input.analysisRan == true) {
                return Panel(
                    headline = "분석 실행 확인 · 관측 결과 미수신",
                    detail = "수신 관측 원문 0건 · 탐지 0건 판정 아님 · 안전 확인 아님",
                    overlayNote = overlayNote(input.frameMappingVerified),
                    mapNote = MAP_HELD_NOTE,
                    candidates = emptyList(),
                    unavailable = true,
                    emptyListNote = "관측 결과 미수신 · 후보 상태 미확인",
                )
            }
            return Panel(
                headline = "관측 미수신 — 분석 결과 알 수 없음",
                detail = "사건 0건과 다릅니다 · 안전 확인 아님",
                overlayNote = overlayNote(input.frameMappingVerified),
                mapNote = MAP_HELD_NOTE,
                candidates = emptyList(),
                unavailable = true,
                emptyListNote = "관측 미수신 · 후보 상태 미확인",
            )
        }
        if (input.observationCount == 0) {
            return Panel(
                headline = "사건 ${input.eventCount}건 수신 · 관측 미수신 — 분석 범위 알 수 없음",
                detail = "관측이 없어 후보의 전후 맥락을 알 수 없음 · 안전 확인 아님",
                overlayNote = overlayNote(input.frameMappingVerified),
                mapNote = MAP_HELD_NOTE,
                candidates = candidateRows(input.cards).take(MAX_CANDIDATES),
                unavailable = true,
            )
        }
        val rows = candidateRows(input.cards)
        val headline = when {
            rows.any { it.kind != "객체 관측" } -> candidateSummary(rows)
            rows.isNotEmpty() -> "${candidateSummary(rows)} · 평가 결과 미확인"
            input.eventCount > 0 -> "관측 ${input.observationCount}건 · 사건 ${input.eventCount}건 수신"
            else -> "관측 ${input.observationCount}건 수신 · 사건 0건"
        }
        return Panel(
            headline = headline,
            detail = freshnessDetail(input.observationAgeMs),
            overlayNote = overlayNote(input.frameMappingVerified),
            mapNote = MAP_HELD_NOTE,
            candidates = rows.take(MAX_CANDIDATES),
            unavailable = false,
        )
    }

    private fun candidateRows(cards: List<ObservationModel.EventCard>): List<CandidateRow> =
        cards.map { card ->
            CandidateRow(
                eventId = card.eventId,
                kind = when {
                    card.detectionOnly -> "객체 관측"
                    card.mode == "ACTIVE" -> "ACTIVE"
                    card.mode == "SHADOW" -> "SHADOW"
                    else -> "모드 미확인"
                },
                quality = card.observationQuality,
                evidenceSummary = when {
                    card.evidence.isEmpty() -> "연결 증거 없음"
                    else -> {
                        val complete = card.evidence.count { it.complete }
                        val synced = card.evidence.count { it.synced }
                        "로컬 $complete/${card.evidence.size} · 서버 $synced/${card.evidence.size}"
                    }
                },
            )
        }

    /** A SHADOW-only note is justified only when every candidate has that exact mode. */
    fun shadowOnly(cards: List<ObservationModel.EventCard>, complete: Boolean = true): Boolean {
        val candidates = cards.filterNot { it.detectionOnly }
        return complete && candidates.isNotEmpty() && candidates.all { it.mode == "SHADOW" }
    }

    /** The non-warning qualifier applies only to explicitly SHADOW rows. */
    fun candidateSummary(rows: List<CandidateRow>): String {
        val active = rows.count { it.kind == "ACTIVE" }
        val shadow = rows.count { it.kind == "SHADOW" }
        val unknown = rows.count { it.kind == "모드 미확인" }
        val detected = rows.count { it.kind == "객체 관측" }
        return buildList {
            if (active > 0) add("ACTIVE ${active}건")
            if (shadow > 0) add("SHADOW 후보 ${shadow}건 · SHADOW는 경고 아님")
            if (unknown > 0) add("모드 미확인 ${unknown}건")
            if (detected > 0) add("객체 관측 ${detected}건")
        }.joinToString(" · ")
    }

    private fun freshnessDetail(ageMs: Long?): String {
        if (ageMs == null) return "수신 시각 미확인 · 지연 여부를 판단하지 않음 · 안전 판정 아님"
        return ObservationModel.observationFreshnessLabel(ageMs) + " · 안전 판정 아님"
    }

    private fun overlayNote(frameMappingVerified: Boolean): String =
        if (frameMappingVerified) {
            "프레임 대응 확인됨 · 목록으로 표시"
        } else {
            "영상 오버레이 보류 · Edge 분석과 DJI 라이브 프레임의 대응 검증 " +
                "전에는 박스를 표시하지 않습니다."
        }

    const val MAP_HELD_NOTE = "탐지는 가능하지만 지도 위치는 미확인"
}
