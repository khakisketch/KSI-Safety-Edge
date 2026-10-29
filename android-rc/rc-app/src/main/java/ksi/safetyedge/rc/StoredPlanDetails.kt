package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.JsonNode

/** Read only the facts pinned in this bundle; missing fields stay unknown. */
object StoredPlanDetails {
    fun describe(bundle: JsonNode): List<String> {
        val zones = bundle.path("mission_snapshot").path("zones")
        val rules = bundle.path("risk_policy").path("rules")
        val lines = mutableListOf<String>()
        if (!zones.isArray) lines += "구역 내용 미제공"
        else if (zones.isEmpty) lines += "저장된 구역 없음"
        else zones.forEach { zone ->
            val id = text(zone, "zone_id")?.take(8) ?: "식별자 미제공"
            val kind = text(zone, "rule_kind") ?: "용도 미제공"
            val enabled = when (zone.get("enabled")?.takeIf { it.isBoolean }?.asBoolean()) {
                true -> "사용"; false -> "사용 안 함"; null -> "사용 여부 미제공"
            }
            lines += "구역 $id · ${zoneKind(kind)} · $enabled"
        }
        if (!rules.isArray) lines += "규칙 내용 미제공"
        else if (rules.isEmpty) lines += "저장된 규칙 없음 · 안전 확인 아님"
        else rules.forEach { rule ->
            val kind = text(rule, "kind")?.let(::ruleKind) ?: "종류 미제공"
            val mode = when (val value = text(rule, "mode")) {
                "SHADOW" -> "SHADOW · 경고 아님"
                "OFF" -> "꺼짐"
                "ACTIVE" -> "ACTIVE"
                null -> "모드 미제공"
                else -> value
            }
            val threshold = rule.get("threshold_m")?.takeIf { it.isNumber }?.asText()?.let { " · 기준 ${it}m" }.orEmpty()
            lines += "규칙 $kind · $mode$threshold"
        }
        return lines
    }

    private fun text(node: JsonNode, key: String): String? =
        node.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun zoneKind(value: String) = when (value) {
        "PERSON_ENTRY" -> "사람 진입"; "EQUIPMENT_ENTRY" -> "장비 진입"
        "PPE_REQUIRED" -> "보호구 필요"; "RESTRICTED_AREA" -> "제한 구역"
        else -> value
    }
    private fun ruleKind(value: String) = when (value) {
        "ZONE_INTRUSION" -> "구역 진입"
        "PERSON_EQUIPMENT_PROXIMITY" -> "사람·장비 근접"
        else -> value
    }
}
