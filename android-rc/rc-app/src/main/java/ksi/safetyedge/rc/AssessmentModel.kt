package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.ASSESSMENT_MEDIA_TYPE
import ksi.rc.Mop
import ksi.rc.UploadStore
import ksi.rc.Uuid5

/**
 * Assessment view model (Unit A closure): sealed machine assessments that
 * arrived over the existing artifact plane, parsed from INTACT blobs only.
 *
 * Reads [ksi.rc.EvidenceStore.assessmentsFor] (the store link) and shapes
 * it into view records. Nothing here invents data:
 *
 * - a tick with an intact assessment shows its disposition + reasons +
 *   rule/model digests; a tick without one shows "미수신" (never 0건);
 * - every assessment is SHADOW (the Edge seals `mode:"SHADOW"` only) and
 *   carries ZERO incidents/alerts/human or machine commands — the viewer
 *   states that fact instead of hiding the rows;
 * - the window reference is DERIVED (`win|flight|boot|trigger-tick`, the
 *   same uuid5 the Edge seals under) and only for trigger ticks
 *   (detections present or inference failure — the Edge's own trigger
 *   rule); pre/post frame BYTES stay on the sealed-revision/Core path and
 *   are reported as "미수신" until retained, never guessed;
 * - the JSON's own `custody` block still says `rc_received:false` — it was
 *   sealed before receipt. The RC receipt is a LOCAL store fact; this
 *   model never rewrites the sealed bytes.
 *
 * Android-free on purpose: host-tested on the JVM.
 */
object AssessmentModel {

    /** One sealed tick judgement, parsed from verified bytes. */
    data class AssessmentCard(
        val assessmentId: String,
        val tickIndex: Long,
        val flightId: String?,
        val edgeBootId: String?,
        /** ASSESSED | UNDETERMINED | anything the Edge sealed (verbatim). */
        val disposition: String,
        val mode: String,
        val detectionStatus: String?,
        val detectionCount: Int,
        val observedConditions: List<String>,
        val missingReasons: List<String>,
        val ruleCount: Int,
        val ruleLines: List<String>,
        val modelId: String?,
        val modelDigest: String?,
        val simulated: Boolean,
        /** Derived window ref for trigger ticks; null when this tick could
         *  not have opened a window (clean negatives join, never open). */
        val windowId: String?,
        val incidentsCreated: Int,
        val alertsCreated: Int,
        val humanResponses: Int,
    )

    private fun text(node: ObjectNode, field: String): String? =
        node.get(field)?.takeIf { it.isTextual }?.asText()

    private fun strings(node: ObjectNode, field: String): List<String> =
        node.get(field)?.takeIf { it.isArray }
            ?.mapNotNull { if (it.isTextual) it.asText() else null }
            ?: emptyList()

    /** Null when the node is not a readable assessment (never throws). */
    fun parse(node: ObjectNode): AssessmentCard? {
        val id = text(node, "assessment_id") ?: return null
        val tick = node.get("tick_index")?.takeIf { it.isNumber }?.asLong()
            ?: return null
        val flightId = text(node, "flight_id")
        val bootId = text(node, "edge_boot_id")
        val observations = node.get("observations") as? ObjectNode
        val detectionStatus = observations?.let { text(it, "detection_status") }
        val detectionCount = observations?.get("detection_count")
            ?.takeIf { it.isNumber }?.asInt() ?: 0
        val model = node.get("model") as? ObjectNode
        val rules = node.get("rules")?.takeIf { it.isArray }
        val ruleLines = rules
            ?.mapNotNull { it as? ObjectNode }
            ?.mapNotNull { rule ->
                val ruleId = text(rule, "rule_id") ?: return@mapNotNull null
                val status = text(rule, "status") ?: "?"
                val mode = text(rule, "effective_mode") ?: "?"
                "$ruleId=$status($mode)"
            } ?: emptyList()
        val outputs = node.get("outputs") as? ObjectNode
        return AssessmentCard(
            assessmentId = id,
            tickIndex = tick,
            flightId = flightId,
            edgeBootId = bootId,
            disposition = text(node, "disposition") ?: "UNKNOWN",
            mode = text(node, "mode") ?: "?",
            detectionStatus = detectionStatus,
            detectionCount = detectionCount,
            observedConditions = strings(node, "observed_conditions"),
            missingReasons = strings(node, "missing_reasons"),
            ruleCount = rules?.size() ?: 0,
            ruleLines = ruleLines,
            modelId = model?.let { text(it, "model_id") },
            modelDigest = model?.let { text(it, "model_digest") },
            simulated = node.get("simulated_input")?.asBoolean(false) == true,
            windowId = triggerWindowId(flightId, bootId, tick, detectionStatus, detectionCount),
            incidentsCreated = outputs?.get("incidents_created")
                ?.takeIf { it.isNumber }?.asInt() ?: 0,
            alertsCreated = outputs?.get("alerts_created")?.takeIf { it.isNumber }?.asInt() ?: 0,
            humanResponses = outputs?.get("human_responses")
                ?.takeIf { it.isNumber }?.asInt() ?: 0,
        )
    }

    /**
     * The Edge opens a pre/post window exactly when a tick carries fresh
     * detections or fails inference (clean negatives only join open
     * windows). For those trigger ticks the window id is re-derivable
     * (`win|flight|boot|trigger-tick`); otherwise null — never a guess.
     */
    fun triggerWindowId(
        flightId: String?,
        bootId: String?,
        tickIndex: Long,
        detectionStatus: String?,
        detectionCount: Int,
    ): String? {
        if (flightId.isNullOrEmpty() || bootId.isNullOrEmpty()) return null
        val triggered = detectionCount > 0 || detectionStatus == "INFERENCE_FAILURE"
        if (!triggered) return null
        return Uuid5.of(Mop.NAMESPACE, "win|$flightId|$bootId|$tickIndex").toString()
    }

    /** Verified assessments of a flight keyed by tick. First seal wins
     *  (the store keeps the first announce per id and rejects conflicting
     *  re-announces — same rule as the Edge/Core conflict refusal): a
     *  re-sealed tick never silently replaces the stored judgement. */
    fun assessmentsByTick(
        store: UploadStore,
        flightId: String,
    ): Map<Long, AssessmentCard> {
        val out = linkedMapOf<Long, AssessmentCard>()
        for (node in store.assessmentsFor(flightId)) {
            val card = parse(node) ?: continue
            out[card.tickIndex] = card
        }
        return out
    }

    /**
     * The assessment behind one stored observation (tick =
     * `edge_frame_seq`), or null when no intact assessment covers it —
     * "미수신", never 0건.
     */
    fun assessmentForObservation(
        byTick: Map<Long, AssessmentCard>,
        observation: ObjectNode,
    ): AssessmentCard? {
        val tick = observation.get("edge_frame_seq")?.takeIf { it.isNumber }?.asLong()
            ?: return null
        return byTick[tick]
    }

    /**
     * Flight-level assessment line. Counts what arrived; 0건 and 미수신 are
     * different sentences (a clean negative IS evidence and reads ASSESSED,
     * while a missing assessment reads 미수신 — never "안전").
     */
    fun summaryLine(store: UploadStore, flightId: String): String {
        val cards = assessmentsByTick(store, flightId).values.toList()
        val pending = store.pendingAssessmentCount(flightId)
        if (cards.isEmpty() && pending == 0) {
            return "SHADOW 평가 미수신 — 미확인(판단 근거 없음 · 안전 확인 아님)"
        }
        val assessed = cards.count { it.disposition == "ASSESSED" }
        val undetermined = cards.size - assessed
        val simulated = if (cards.any { it.simulated }) " · [SIMULATED 입력]" else ""
        val observations = store.observationsFor(flightId)
        val linked = observations.count { assessmentForObservation(cards.associateBy { it.tickIndex }, it) != null }
        val unlinked = (observations.size - linked).coerceAtLeast(0)
        return buildString {
            append("SHADOW 평가 ${cards.size}건(판정 $assessed · 미확정 $undetermined)")
            if (unlinked > 0) append(" · 관측 중 평가 미연결 ${unlinked}건")
            if (pending > 0) append(" · 수신 중(미완료) ${pending}건")
            append(simulated)
            append(" · 확정·경보 아님")
        }
    }

    /** Short row suffix for one event card (null = flight line covers it). */
    fun rowSuffix(card: AssessmentCard?): String? = when {
        card == null -> null
        card.disposition == "ASSESSED" -> "평가 판정"
        else -> "평가 미확정(${card.disposition})"
    }

    /** Multi-line detail for the selected event (sealed facts, verbatim). */
    fun detailText(card: AssessmentCard?): String {
        if (card == null) {
            return "기계 평가 미수신 — 해당 관측의 판정 아직 없음(안전 확인 아님)"
        }
        return buildString {
            append("기계 평가 ${card.disposition} · ${card.mode}")
            if (card.simulated) append(" · [SIMULATED 입력]")
            append("\n판정 사유: ")
            append(if (card.missingReasons.isEmpty()) "없음" else card.missingReasons.joinToString(", "))
            append("\n관측 조건: ")
            append(if (card.observedConditions.isEmpty()) "없음" else card.observedConditions.joinToString(", "))
            append("\n규칙 ${card.ruleCount}건")
            if (card.ruleLines.isNotEmpty()) {
                append(" · " + card.ruleLines.take(4).joinToString(", "))
                if (card.ruleLines.size > 4) append(" 외 ${card.ruleLines.size - 4}건")
            }
            append("\n모델 ${card.modelId ?: "?"} · digest ${(card.modelDigest ?: "?").take(12)}")
            append("\n전후 맥락: ")
            if (card.windowId != null) {
                append("윈도우 ${card.windowId.take(8)} 참조 · 프레임 바이트는 봉인본/Core 보관 확인 전까지 미수신")
            } else {
                append("이 틱은 윈도우를 열지 않음(깨끗한 무검출은 맥락으로만 참여)")
            }
            append("\n생성 0건 고정 — Incident ${card.incidentsCreated} · Alert ${card.alertsCreated} · 사람 응답 ${card.humanResponses}(SHADOW는 확정·경보·대응이 아님)")
            append("\n로컬 봉인됨 · RC 수신은 로컬 영수증 시각으로 별도 확인 · Core 보관은 커밋 영수증으로 별도 확인")
        }
    }

    /** True only for the closed disposition vocabulary (never a guess). */
    fun isAssessed(card: AssessmentCard): Boolean = card.disposition == "ASSESSED"
}
