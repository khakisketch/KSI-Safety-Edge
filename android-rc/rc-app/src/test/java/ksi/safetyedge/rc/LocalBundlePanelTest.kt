package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.ExcludedZoneRevision

/**
 * Host tests for the LOCAL composition display (product gap G11): pinned
 * revision ids, the SHADOW rule count, excluded revisions with reasons —
 * and "위험 규칙 0건" as a state, never an error.
 */
class LocalBundlePanelTest {

    @Test
    fun composition_names_the_local_bundle_and_pinned_revisions() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = "bundle-abcdef-1234",
                zoneRevisionIds = listOf("zone-r1-aaaa", "zone-r2-bbbb"),
                ruleCount = 1,
                excluded = emptyList(),
            ),
        )

        assertTrue(panel.compositionLine.contains("로컬 구성"))
        assertTrue(panel.compositionLine.contains("bundle-a"))
        assertTrue(panel.compositionLine.contains("RC_LOCAL"))
        assertTrue(panel.compositionLine.contains("SHADOW"))
        assertTrue(panel.compositionLine.contains("구역 리비전 2개 포함"))
        assertTrue(panel.compositionLine.contains("zone-r1-"))
        assertTrue(panel.ruleLine.contains("위험 규칙 1건(SHADOW)"))
        assertTrue(panel.excludedLines.isEmpty())
    }

    @Test
    fun zero_rules_are_a_state_not_an_error() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = "bundle-empty-0000",
                zoneRevisionIds = emptyList(),
                ruleCount = 0,
                excluded = emptyList(),
            ),
        )

        assertEquals(EdgeRuntimePanel.NO_RISK_RULES_NOTE, panel.ruleLine)
        assertTrue(panel.ruleLine.contains("위험 규칙 0건"))
    }

    @Test
    fun missing_basis_is_unknown_never_zero() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = null,
                zoneRevisionIds = null,
                ruleCount = null,
                excluded = emptyList(),
            ),
        )

        assertTrue(panel.compositionLine.contains("미수행"))
        assertTrue(panel.ruleLine.contains("미확인"))
        assertTrue(
            "absent basis must never render as 0건",
            !panel.ruleLine.contains("0건") && !panel.compositionLine.contains("0개"),
        )
    }

    @Test
    fun excluded_revisions_are_shown_with_reasons() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = "bundle-abcdef-1234",
                zoneRevisionIds = listOf("zone-r1-aaaa"),
                ruleCount = 1,
                excluded = listOf(
                    ExcludedZoneRevision("rev-unknown-1", "unknown zone revision id"),
                    ExcludedZoneRevision("rev-old-2", "superseded by a newer stored revision"),
                ),
            ),
        )

        assertEquals(2, panel.excludedLines.size)
        assertTrue(panel.excludedLines[0].contains("rev-unkn"))
        assertTrue(panel.excludedLines[0].contains("unknown zone revision id"))
        assertTrue(panel.excludedLines[1].contains("superseded"))
    }

    @Test
    fun off_mode_rules_are_labeled_inert_never_shadow() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = "bundle-off-0000",
                zoneRevisionIds = listOf("zone-r1-aaaa"),
                ruleCount = 2,
                excluded = emptyList(),
                ruleMode = "OFF",
            ),
        )

        assertTrue(panel.ruleLine.contains("위험 규칙 2건(OFF"))
        assertTrue(panel.ruleLine.contains("동작 안 함"))
        assertTrue(!panel.ruleLine.contains("SHADOW"))
    }

    @Test
    fun map_gap_is_stated_not_fabricated() {
        val panel = LocalBundlePanel.render(
            LocalBundlePanel.Input(bundleId = null, zoneRevisionIds = null, ruleCount = null),
        )

        assertTrue(panel.mapNote.contains("미제공"))
    }
}
