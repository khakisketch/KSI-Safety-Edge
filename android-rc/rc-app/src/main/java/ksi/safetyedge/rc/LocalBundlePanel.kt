package ksi.safetyedge.rc

import ksi.rc.ExcludedZoneRevision
import ksi.rc.LocalBundleRequest

/**
 * LOCAL bundle composition display for the preparation screen (product gap
 * G11).
 *
 * Pure Kotlin, no Android: host-tested. Shows the composed LOCAL bundle
 * honestly: which stored zone revision ids were pinned, the RULE COUNT
 * (SHADOW), and every stored revision that was left out with WHY. A zero
 * rule count is a state ("위험 규칙 0건"), never an error — observation may
 * run, but no risk evaluation is. The map-editing surface is a separate
 * unresolved item: the panel states the gap instead of faking a map.
 */
object LocalBundlePanel {

    data class Input(
        val bundleId: String?,
        val zoneRevisionIds: List<String>?,
        /** Null = the composition basis itself is missing (never "0건"). */
        val ruleCount: Int?,
        val excluded: List<ExcludedZoneRevision> = emptyList(),
        /** Install mode of the counted rules (the app composes SHADOW only). */
        val ruleMode: String = LocalBundleRequest.SHADOW_MODE,
    )

    data class Panel(
        /** Composition fact: which LOCAL bundle pins which revisions. */
        val compositionLine: String,
        /** Rule-count fact (SHADOW); zero is a state, not an error. */
        val ruleLine: String,
        /** One line per excluded revision with its reason. */
        val excludedLines: List<String>,
        /** Static gap note: no map editor is fabricated. */
        val mapNote: String = MAP_NOTE,
    )

    const val MAP_NOTE =
        "로컬 구성 · 지도 편집 미제공 · WGS84 구역 리비전 목록으로만 확인합니다"

    fun compositionLine(bundleId: String?, zoneRevisionIds: List<String>?): String {
        if (bundleId == null) {
            return "로컬 구성 · 미수행 — 저장된 구역 리비전에서 SHADOW 번들을 구성합니다"
        }
        val ids = zoneRevisionIds ?: emptyList()
        val shown = ids.take(3).joinToString(", ") { it.take(8) }
        val rest = if (ids.size > 3) " 외 ${ids.size - 3}건" else ""
        return "로컬 구성 · 번들 ${bundleId.take(8)}(RC_LOCAL·SHADOW) · " +
            "구역 리비전 ${ids.size}개 포함 · $shown$rest"
    }

    fun ruleLine(ruleCount: Int?, ruleMode: String = LocalBundleRequest.SHADOW_MODE): String = when {
        ruleCount == null -> "위험 규칙 수 미확인(로컬 구성 없음)"
        ruleCount == 0 -> EdgeRuntimePanel.NO_RISK_RULES_NOTE
        ruleMode == LocalBundleRequest.SHADOW_MODE -> "위험 규칙 ${ruleCount}건(SHADOW)"
        else -> "위험 규칙 ${ruleCount}건($ruleMode · 설치되나 동작 안 함)"
    }

    fun excludedLines(excluded: List<ExcludedZoneRevision>): List<String> =
        excluded.map { "제외 · ${it.revisionId.take(8)} · ${it.reason}" }

    fun render(input: Input): Panel = Panel(
        compositionLine = compositionLine(input.bundleId, input.zoneRevisionIds),
        ruleLine = ruleLine(input.ruleCount, input.ruleMode),
        excludedLines = excludedLines(input.excluded),
    )
}
