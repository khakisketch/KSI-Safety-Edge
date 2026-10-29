package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.FileRcStore
import ksi.rc.Mop
import ksi.rc.Uuid5

/**
 * Assessment view-model tests (Unit A closure).
 *
 * - Verified assessment blobs link to their observation tick and render
 *   disposition + reasons + rule/model digests verbatim;
 * - an unlinked tick reads "미수신", never 0건; an empty flight reads
 *   미확인 (판단 근거 없음 · 안전 확인 아님);
 * - the window ref is re-derived only for trigger ticks (the Edge's own
 *   trigger rule), and frame bytes are reported 미수신 until retained;
 * - Incident/Alert/human counts stay 0 and SHADOW is stated, never a
 *   warning;
 * - window-id derivation matches the Edge (`win|flight|boot|tick`, uuid5).
 */
class AssessmentModelTest {
    private val flight = "00000000-0000-4000-8000-000000000301"
    private val boot = "boot-1"
    private val fixedUtc = "2026-10-20T03:00:00Z"

    private fun store(root: java.nio.file.Path = Files.createTempDirectory("assessmodel")) =
        FileRcStore(root, wallClock = { fixedUtc })

    private fun assessmentNode(
        id: String,
        tick: Long,
        disposition: String = "ASSESSED",
        detectionStatus: String = "OK",
        detectionCount: Int = 1,
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema", "ksi.assessment/v1")
        node.put("assessment_id", id)
        node.put("tick_index", tick)
        node.put("flight_id", flight)
        node.put("edge_boot_id", boot)
        node.put("mode", "SHADOW")
        node.put("simulated_input", true)
        val observations = Mop.json.createObjectNode()
        observations.put("detection_status", detectionStatus)
        observations.put("detection_count", detectionCount)
        observations.put("track_count", 1)
        node.set<com.fasterxml.jackson.databind.JsonNode>("observations", observations)
        val model = Mop.json.createObjectNode()
        model.put("model_id", "stub-detector")
        model.put("model_digest", "stub-digest-not-a-model")
        node.set<com.fasterxml.jackson.databind.JsonNode>("model", model)
        val rules = Mop.json.createArrayNode()
        val rule = Mop.json.createObjectNode()
        rule.put("rule_id", "zone-intrusion-1")
        rule.put("status", "EVALUATED")
        rule.put("detail", "NO_EVENT")
        rule.put("effective_mode", "SHADOW")
        rule.put("is_shadow", true)
        rule.put("event_count", 0)
        rule.put("created_episodes", 0)
        rule.put("closed_episodes", 0)
        rules.add(rule)
        node.set<com.fasterxml.jackson.databind.JsonNode>("rules", rules)
        node.put("disposition", disposition)
        val observed = Mop.json.createArrayNode()
        observed.add("fresh_detections_present")
        observed.add("rule_evaluated")
        node.set<com.fasterxml.jackson.databind.JsonNode>("observed_conditions", observed)
        val missing = Mop.json.createArrayNode()
        missing.add("no_tracks_no_spatial_claim")
        node.set<com.fasterxml.jackson.databind.JsonNode>("missing_reasons", missing)
        val outputs = Mop.json.createObjectNode()
        outputs.put("incidents_created", 0)
        outputs.put("alerts_created", 0)
        outputs.put("human_responses", 0)
        outputs.put("camera_commands", 0)
        outputs.put("flight_commands", 0)
        node.set<com.fasterxml.jackson.databind.JsonNode>("outputs", outputs)
        val custody = Mop.json.createObjectNode()
        custody.put("edge_sealed_locally", true)
        custody.put("rc_received", false)
        custody.put("rc_receipt", "unverified")
        custody.put("core_retained", false)
        node.set<com.fasterxml.jackson.databind.JsonNode>("custody", custody)
        return node
    }

    private fun seal(store: FileRcStore, id: String, node: ObjectNode, tick: Long) {
        val bytes = Mop.json.writeValueAsBytes(node)
        val announce = Mop.json.createObjectNode()
        announce.put("artifact_id", id)
        announce.put("flight_id", flight)
        announce.put("edge_boot_id", boot)
        announce.put("source_kind", ksi.rc.ASSESSMENT_SOURCE_KIND)
        announce.put("sha256", sha256Hex(bytes))
        announce.put("size_bytes", bytes.size.toLong())
        announce.put("media_type", ksi.rc.ASSESSMENT_MEDIA_TYPE)
        announce.put("required", false)
        announce.put("segment_index", 0)
        store.announceArtifact(announce, boot)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 16, bytes.size)
            store.writeChunk(id, offset.toLong(), bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun observation(id: String, tick: Long): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("observation_id", id)
        node.put("flight_id", flight)
        node.put("edge_frame_seq", tick)
        node.put("frame_received_monotonic_ns", 1000L)
        return node
    }

    private fun event(id: String, observationId: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("event_id", id)
        node.put("flight_id", flight)
        node.put("observation_id", observationId)
        node.put("event_type", "ZONE_CANDIDATE")
        node.put("mode", "SHADOW")
        node.put("detected_at_edge_monotonic_ns", 42L)
        node.put("observation_quality", "VALID")
        node.putArray("evidence_artifact_ids")
        return node
    }

    @Test
    fun verified_assessment_links_to_its_observation_tick() {
        val store = store()
        seal(store, "assess-1", assessmentNode("assess-1", 3), 3)
        store.storeObservation(observation("obs-3", 3), boot)
        store.storeEvent(event("evt-1", "obs-3"), boot)

        val byTick = AssessmentModel.assessmentsByTick(store, flight)
        assertEquals(1, byTick.size)
        val card = byTick[3L]
        assertNotNull(card)
        card!!
        assertEquals("assess-1", card.assessmentId)
        assertEquals("ASSESSED", card.disposition)
        assertEquals("SHADOW", card.mode)
        assertEquals(1, card.ruleCount)
        assertTrue(card.ruleLines.single().startsWith("zone-intrusion-1="))
        assertEquals("stub-detector", card.modelId)

        val cards = ObservationModel.eventCards(store, flight)
        assertEquals(1, cards.size)
        assertNotNull("event links its tick assessment", cards[0].assessment)
        assertEquals("assess-1", cards[0].assessment!!.assessmentId)
    }

    @Test
    fun unlinked_tick_reads_unreceived_never_zero() {
        val store = store()
        store.storeObservation(observation("obs-9", 9), boot)
        store.storeEvent(event("evt-9", "obs-9"), boot)

        val cards = ObservationModel.eventCards(store, flight)
        assertEquals(1, cards.size)
        assertNull("no intact assessment covers the tick", cards[0].assessment)
        assertNull(AssessmentModel.rowSuffix(null))
        assertTrue(AssessmentModel.detailText(null).contains("미수신"))

        val line = AssessmentModel.summaryLine(store, flight)
        assertTrue("0건이 아니라 미수신", line.contains("미수신"))
    }

    @Test
    fun empty_flight_reads_unknown_not_safe() {
        val line = AssessmentModel.summaryLine(store(), flight)
        assertTrue(line.contains("미수신"))
        assertTrue(line.contains("안전 확인 아님"))
    }

    @Test
    fun window_ref_derives_only_for_trigger_ticks() {
        val store = store()
        // Detection tick: opens a window — the Edge's window id re-derives.
        seal(store, "assess-t", assessmentNode("assess-t", 5), 5)
        // Clean negative: joins but never opens — no window ref.
        seal(store, "assess-c", assessmentNode("assess-c", 6, "ASSESSED", "OK", 0), 6)

        val byTick = AssessmentModel.assessmentsByTick(store, flight)
        val expected = Uuid5.of(Mop.NAMESPACE, "win|$flight|$boot|5").toString()
        assertEquals(expected, byTick[5L]!!.windowId)
        assertNull("clean negative opens no window", byTick[6L]!!.windowId)

        val detail = AssessmentModel.detailText(byTick[5L])
        assertTrue(detail.contains("윈도우 ${expected.take(8)}"))
        assertTrue("frame bytes honesty", detail.contains("미수신"))
        assertTrue("SHADOW stated, no warning", detail.contains("SHADOW"))
        assertTrue("zeros stated", detail.contains("Incident 0"))
    }

    @Test
    fun summary_counts_dispositions_and_pending() {        val store = store()
        seal(store, "assess-a", assessmentNode("assess-a", 1), 1)
        seal(store, "assess-u", assessmentNode("assess-u", 2, "UNDETERMINED"), 2)
        store.storeObservation(observation("obs-1", 1), boot)
        store.storeObservation(observation("obs-2", 2), boot)
        store.storeObservation(observation("obs-3", 3), boot)
        // Announced but incomplete: pending, never judged.
        val pending = Mop.json.createObjectNode()
        pending.put("artifact_id", "assess-p")
        pending.put("flight_id", flight)
        pending.put("edge_boot_id", boot)
        pending.put("source_kind", ksi.rc.ASSESSMENT_SOURCE_KIND)
        pending.put("sha256", "00".repeat(32))
        pending.put("size_bytes", 64L)
        pending.put("media_type", ksi.rc.ASSESSMENT_MEDIA_TYPE)
        pending.put("required", false)
        pending.put("segment_index", 0)
        store.announceArtifact(pending, boot)

        val line = AssessmentModel.summaryLine(store, flight)
        assertTrue(line.contains("SHADOW 평가 2건"))
        assertTrue(line.contains("판정 1"))
        assertTrue(line.contains("미확정 1"))
        assertTrue(line.contains("평가 미연결 1건"))
        assertTrue(line.contains("수신 중(미완료) 1건"))
        assertTrue(line.contains("확정·경보 아님"))
        assertTrue(line.contains("SIMULATED"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun unknown_fields_and_dispositions_are_tolerated_never_assessed() {
        // ksi.assessment/v1 has no frozen schema file: the RC must accept
        // forward-compatible additions (unknown fields ignored) and must
        // never read an unknown disposition as ASSESSED. Unknown stays on
        // the 미확정 side — a false "판정" is the one error direction that
        // could be mistaken for a safety claim.
        val node = assessmentNode("assess-future", 11, "CONDITION_FULFILLED_V2")
        node.put("future_field", "must-be-ignored")
        val rules = node.get("rules")
        if (rules != null && rules.isArray && rules.size() > 0) {
            (rules[0] as ObjectNode).put("future_rule_field", 42)
        }
        (node.get("model") as ObjectNode).put("future_model_field", true)

        val card = AssessmentModel.parse(node)
        assertNotNull(card)
        card!!
        assertEquals("CONDITION_FULFILLED_V2", card.disposition)
        assertTrue(!AssessmentModel.isAssessed(card))

        val store = store()
        seal(store, "assess-future", node, 11)
        store.storeObservation(observation("obs-11", 11), boot)
        val line = AssessmentModel.summaryLine(store, flight)
        assertTrue(line.contains("미확정 1"))
        assertTrue(!line.contains("판정 1"))
        assertTrue(AssessmentModel.detailText(card).contains("CONDITION_FULFILLED_V2"))
    }

    @Test
    fun missing_disposition_and_tick_are_unreadable_not_defaulted() {
        val node = assessmentNode("assess-bad", 12)
        node.remove("disposition")
        val noDisposition = AssessmentModel.parse(node)
        assertNotNull(noDisposition)
        assertEquals("UNKNOWN", noDisposition!!.disposition)
        assertTrue(!AssessmentModel.isAssessed(noDisposition))

        val noTick = assessmentNode("assess-notick", 13)
        noTick.remove("tick_index")
        assertNull(AssessmentModel.parse(noTick))
        val noId = assessmentNode("assess-noid", 14)
        noId.remove("assessment_id")
        assertNull(AssessmentModel.parse(noId))
    }
}
