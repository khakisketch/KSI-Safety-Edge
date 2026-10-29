package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.FileRcStore
import ksi.rc.Mop

/**
 * D6 회귀 — 현장 검토 준비와 중앙 보관을 **분리**해서 읽는 시험.
 *
 * 제품 방향: RC는 현장에서 "지금 이 관측을 검토할 수 있는가"를 답하고,
 * Core는 "중앙에 보관됐는가"를 답한다. 두 축을 합치면 "로컬에 있다"를
 * "중앙에 있다"로 오독하거나 "아직 전송 안 됐다"를 "분석 결과가 없다"로
 * 오독하게 된다. 이 시험은 그 분리를 고정한다:
 *
 * ① 판단 근거 3축(정확한 리비전·디코딩 가능·링크 일치)을 **끊지 않는다**.
 *    하나라도 어긋나면 준비 아님이며 **어떤 축인지 이름을 밝힌다**.
 * ② 관측 0건은 "준비"가 아니라 "분석 결과 알 수 없음"이다(0건≠안전).
 * ③ 중앙 보관은 **커밋 영수증이 있을 때만** STORED. 영수증이 없으면
 *    "서버에 없다"가 아니라 "RC가 확인하지 못했다"(NOT_CONFIRMED).
 * ④ **원본 근거는 실측**이다: 사건이 참조한 artifact의 바이트가 해시까지
 *    일치해야 "현장 검토 준비"를 말한다. 근거가 없으면 "미수신"으로 말한다
 *    (준비처럼 말하지 않는다).
 */
class ReviewReadinessModelTest {
    private val flightA = "00000000-0000-4000-8000-000000000301"
    private val bootA = "00000000-0000-4000-8000-000000000401"
    private val revision = "00000000-0000-4000-8000-000000000501"
    private val fixedUtc = "2026-10-21T03:00:00Z"
    private val artifactId = "00000000-0000-4000-8000-000000000901"

    private fun store(root: java.nio.file.Path = Files.createTempDirectory("readiness")) =
        FileRcStore(root, wallClock = { fixedUtc })

    private fun flightRecord(flightId: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("flight_id", flightId)
        node.put("site_id", "00000000-0000-4000-8000-0000000000b1")
        node.put("mission_revision_id", revision)
        node.put("bundle_sha256", "cc".repeat(32))
        node.putArray("zone_revision_ids")
        node.put("started_at_utc", fixedUtc)
        node.put("ended_at_utc", fixedUtc)
        node.put("state", "ENDED")
        return node
    }

    private fun observation(
        flightId: String,
        id: String,
        bootId: String = bootA,
        missionRevisionId: String = revision,
        modelDigest: String = "ab".repeat(32),
        syncQuality: String = "VALID",
        geometry: String = "UNVERIFIED",
        frameSeq: Long = 7L,
        withDetections: Boolean = true,
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("observation_id", id)
        node.put("flight_id", flightId)
        node.put("mission_revision_id", missionRevisionId)
        node.put("edge_boot_id", bootId)
        node.put("source_kind", "PSDK_EDGE")
        node.put("edge_frame_seq", frameSeq)
        node.put("edge_frame_id", "42")
        node.put("lens_id", "L1")
        node.put("frame_received_monotonic_ns", 1000L)
        node.put("model_digest", modelDigest)
        node.put("sync_quality", syncQuality)
        node.put("geometry_status", geometry)
        node.putArray("telemetry_refs")
        if (withDetections) {
            val detection = node.putArray("detections").addObject()
            detection.put("class_id", 0)
            detection.put("score", 0.91)
            detection.putArray("bbox").add(0).add(0).add(10).add(10)
        } else {
            node.putArray("detections")
        }
        node.putArray("quality_reasons")
        node.putArray("zone_revision_ids")
        return node
    }

    private fun event(
        flightId: String,
        id: String,
        evidenceArtifactIds: List<String> = emptyList(),
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("event_id", id)
        node.put("flight_id", flightId)
        node.put("edge_boot_id", bootA)
        node.put("event_type", "PROXIMITY_CANDIDATE")
        node.put("mode", "SHADOW")
        node.put("detected_at_edge_monotonic_ns", 42L)
        node.put("observation_quality", "VALID")
        val ids = node.putArray("evidence_artifact_ids")
        for (artifact in evidenceArtifactIds) ids.add(artifact)
        return node
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }

    /** Announce + write an artifact so the store holds verified bytes. */
    private fun storeArtifact(
        store: FileRcStore,
        bytes: ByteArray,
        corrupt: Boolean = false,
    ) {
        val announce = Mop.json.createObjectNode()
        announce.put("artifact_id", artifactId)
        announce.put("flight_id", flightA)
        announce.put("size_bytes", bytes.size)
        announce.put(
            "sha256",
            if (corrupt) sha256Hex(ByteArray(bytes.size) { 'x'.code.toByte() }) else sha256Hex(bytes),
        )
        announce.put("media_type", "application/x-raw-rgb24")
        announce.put("source_kind", "EDGE_SNAPSHOT")
        announce.put("required", true)
        store.announceArtifact(announce, bootA)
        store.writeChunk(artifactId, 0, bytes)
    }

    @Test
    fun a_complete_observation_is_reviewable_as_a_record() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(1, readiness.observationCount)
        assertEquals(1, readiness.readyCount)
        assertEquals(0, readiness.notReadyCount)
        // 근거가 없으면 "현장 검토 준비"가 아니다(그림이 없다).
        assertFalse("원본 근거가 없으면 준비 아님", readiness.fieldReviewReady)
        assertTrue(readiness.evidence.nothingReceived)
    }

    @Test
    fun each_axis_names_its_own_reason() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        // 페이로드의 부트가 RC가 기록한 부트와 다르면 "리비전 불명확" + "링크 불일치".
        // (기록 자체는 bootA 아래에 있다 — RC가 아는 부트는 bootA 뿐이다.)
        store.storeObservation(
            observation(flightA, "o-1", bootId = "00000000-0000-4000-8000-000000000499"),
            bootA,
        )
        // digest가 없으면 "디코딩 불가".
        store.storeObservation(
            observation(flightA, "o-2", modelDigest = "not-a-digest"),
            bootA,
        )

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(2, readiness.observationCount)
        assertFalse("일부만 맞으면 준비 아님", readiness.fieldReviewReady)
        assertEquals("준비 안 된 건은 두 건", 2, readiness.notReadyCount)
        assertEquals(1, readiness.notReadyReasonCounts["리비전 불명확"] ?: 0)
        assertEquals(1, readiness.notReadyReasonCounts["링크 불일치"] ?: 0)
        assertEquals(1, readiness.notReadyReasonCounts["디코딩 불가"] ?: 0)

        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("관측 기록 검토 0/2건"))
        assertTrue(line.contains("디코딩 불가 1건"))
    }

    @Test
    fun zero_observations_is_unknown_not_ready() {
        val store = store()
        store.startFlight(flightRecord(flightA))

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(0, readiness.observationCount)
        assertFalse("관측 0건은 준비가 아니다", readiness.fieldReviewReady)

        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("분석 결과 알 수 없음"))
        assertFalse("0건을 안전으로 읽지 않는다", line.contains("준비 1/"))
    }

    @Test
    fun central_storage_needs_a_commit_receipt() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)

        val before = ReviewReadinessModel.session(store, flightA)
        assertEquals(
            "영수증 없으면 확인 못 함",
            ReviewReadinessModel.CentralStorage.NOT_CONFIRMED,
            before.centralStorage,
        )
        assertTrue(before.centralNotConfirmed)

        val receipt = Mop.json.createObjectNode()
        receipt.put("schema_version", 1)
        receipt.put("flight_id", flightA)
        receipt.put("edge_boot_id", bootA)
        receipt.put("manifest_sha256", "cd".repeat(32))
        receipt.put("upload_id", "upload-1")
        receipt.put("durable_commit_id", "commit-1")
        store.recordCommitReceipt(receipt)

        val after = ReviewReadinessModel.session(store, flightA)
        assertEquals(
            "영수증이 있으면 중앙 보관 확인",
            ReviewReadinessModel.CentralStorage.STORED,
            after.centralStorage,
        )
        assertEquals(1, after.centralStoredCount)
    }

    @Test
    fun the_records_screen_line_keeps_the_axes_apart() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)
        val receipt = Mop.json.createObjectNode()
        receipt.put("schema_version", 1)
        receipt.put("flight_id", flightA)
        receipt.put("edge_boot_id", bootA)
        receipt.put("manifest_sha256", "cd".repeat(32))
        receipt.put("upload_id", "upload-1")
        receipt.put("durable_commit_id", "commit-1")
        store.recordCommitReceipt(receipt)

        val readiness = ReviewReadinessModel.session(store, flightA)
        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("관측 기록 검토"))
        assertTrue(line.contains("중앙 보관"))
        assertFalse("두 축을 한 문장으로 합치지 않는다", line.contains("중앙 보관 완료"))
    }

    @Test
    fun the_source_evidence_axis_is_server_receipt_based() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)

        // 조회 전: "확인 안 함" (없다고 말하지 않는다)
        val notQueried = ReviewReadinessModel.session(store, flightA)
        assertEquals(
            ReviewReadinessModel.SourceStorage.NOT_QUERIED,
            notQueried.sourceEvidence.state,
        )
        assertTrue(
            ReviewReadinessModel.sessionLine(notQueried)
                .contains("원본 근거 중앙 보관 확인 안 함")
        )

        // 영수증이 있으면 확인으로 말한다
        val stored = ReviewReadinessModel.session(
            store,
            flightA,
            ReviewReadinessModel.SourceEvidence(
                state = ReviewReadinessModel.SourceStorage.STORED,
                collectionCount = 2,
                assetCount = 7,
            ),
        )
        val line = ReviewReadinessModel.sessionLine(stored)
        assertTrue(line.contains("원본 근거 중앙 보관 영수증 2건(자산 7개) 확인"))

        // 조회했는데 없음: "영수증 없음"(확인 안 함과 다르다)
        val none = ReviewReadinessModel.session(
            store,
            flightA,
            ReviewReadinessModel.SourceEvidence(
                state = ReviewReadinessModel.SourceStorage.NOT_STORED,
            ),
        )
        assertTrue(
            ReviewReadinessModel.sessionLine(none)
                .contains("원본 근거 중앙 보관 영수증 없음")
        )
    }

    @Test
    fun verified_evidence_is_what_makes_review_ready() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)
        store.storeEvent(event(flightA, "e-1", listOf(artifactId)), bootA)
        storeArtifact(store, "frame-bytes".toByteArray())

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(1, readiness.evidence.referencedArtifactCount)
        assertEquals(1, readiness.evidence.intactArtifactCount)
        assertTrue("무결한 근거가 있으면 현장 검토 준비", readiness.fieldReviewReady)

        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("원본 근거 무결 1건"))
        assertTrue(line.contains("현장 검토 준비됨"))
    }

    @Test
    fun damaged_evidence_never_reads_as_ready() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)
        store.storeEvent(event(flightA, "e-1", listOf(artifactId)), bootA)
        // 바이트는 왔지만 해시가 다르다 → 무결하지 않다.
        storeArtifact(store, "frame-bytes".toByteArray(), corrupt = true)

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(1, readiness.evidence.referencedArtifactCount)
        assertEquals(0, readiness.evidence.intactArtifactCount)
        assertFalse("손상된 근거는 준비가 아니다", readiness.fieldReviewReady)

        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("원본 근거 무결 0/1건"))
        assertFalse(line.contains("현장 검토 준비됨"))
    }

    @Test
    fun missing_evidence_says_missing_not_ready() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), bootA)
        store.storeEvent(event(flightA, "e-1", listOf(artifactId)), bootA)
        // 참조만 있고 바이트는 없다(미수신).

        val readiness = ReviewReadinessModel.session(store, flightA)
        assertEquals(1, readiness.evidence.referencedArtifactCount)
        assertEquals(0, readiness.evidence.intactArtifactCount)
        assertFalse(readiness.fieldReviewReady)

        val line = ReviewReadinessModel.sessionLine(readiness)
        assertTrue(line.contains("원본 근거"))
        assertFalse(line.contains("현장 검토 준비됨"))
    }
}
