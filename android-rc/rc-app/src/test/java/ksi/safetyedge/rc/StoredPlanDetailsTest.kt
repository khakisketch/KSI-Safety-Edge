package ksi.safetyedge.rc

import ksi.rc.Mop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredPlanDetailsTest {
    @Test fun missingAndEmptyAreDistinct() {
        val bundle = Mop.json.createObjectNode()
        assertEquals(listOf("구역 내용 미제공", "규칙 내용 미제공"), StoredPlanDetails.describe(bundle))
        bundle.putObject("mission_snapshot").putArray("zones")
        bundle.putObject("risk_policy").putArray("rules")
        assertEquals(listOf("저장된 구역 없음", "저장된 규칙 없음 · 안전 확인 아님"), StoredPlanDetails.describe(bundle))
    }

    @Test fun displaysOnlyPinnedFactsAndShadowIsNotWarning() {
        val bundle = Mop.json.createObjectNode()
        bundle.putObject("mission_snapshot").putArray("zones").addObject()
            .put("zone_id", "abcdef123456").put("rule_kind", "PERSON_ENTRY").put("enabled", false)
        bundle.putObject("risk_policy").putArray("rules").addObject()
            .put("kind", "PERSON_EQUIPMENT_PROXIMITY").put("mode", "SHADOW").put("threshold_m", 3.5)
        val lines = StoredPlanDetails.describe(bundle)
        assertEquals("구역 abcdef12 · 사람 진입 · 사용 안 함", lines[0])
        assertTrue(lines[1].contains("사람·장비 근접 · SHADOW · 경고 아님 · 기준 3.5m"))
    }
}
