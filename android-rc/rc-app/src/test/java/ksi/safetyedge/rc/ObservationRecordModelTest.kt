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
 * D5 — 관측 기록 모델 회귀 시험.
 *
 * 제품 방향: RC의 "기록"은 **비행 이력**이 아니라 **관측 기록**이다.
 * 무엇을 관측했고, 어떤 위험 후보가 있었고, 그 판단을 신뢰할 수 있는가를
 * 보여주는 것이 목적이다. 이 시험은 다음을 고정한다:
 *
 * ① 관측 건수 / 확인 필요(위험 후보) / 분석 공백을 **끊지 않는다**.
 * ② 관측 0건은 "분석 결과 알 수 없음"이며 "탐지 0건(안전)"이 아니다.
 * ③ 서버를 조회하지 않은 상태는 "유실 여부를 모른다"로 표시한다.
 *    조회해도 커밋이 확인되지 않은 로컬 증거는 "아직 전송 안 됨"으로 남긴다.
 * ④ 비행 이력은 관측 세션의 부가 정보로만 쓰고 판단 근거로 삼지 않는다.
 */
class ObservationRecordModelTest {
    private val flightA = "00000000-0000-4000-8000-000000000201"
    private val flightB = "00000000-0000-4000-8000-000000000202"
    private val siteId = "00000000-0000-4000-8000-0000000000b1"
    private val fixedUtc = "2026-10-20T03:00:00Z"

    private fun store(root: java.nio.file.Path = Files.createTempDirectory("obsrec")) =
        FileRcStore(root, wallClock = { fixedUtc })

    private fun flightRecord(flightId: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("flight_id", flightId)
        node.put("site_id", siteId)
        node.put("mission_revision_id", "00000000-0000-4000-8000-0000000000c2")
        node.put("bundle_sha256", "cc".repeat(32))
        node.putArray("zone_revision_ids")
        node.put("started_at_utc", fixedUtc)
        node.put("ended_at_utc", fixedUtc)
        node.put("state", "ENDED")
        return node
    }

    private fun observation(flightId: String, id: String, geometry: String = "UNVERIFIED"): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("observation_id", id)
        node.put("flight_id", flightId)
        node.put("frame_received_monotonic_ns", 1000L)
        node.put("geometry_status", geometry)
        node.put("sync_quality", "VALID")
        return node
    }

    private fun event(flightId: String, id: String, type: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("event_id", id)
        node.put("flight_id", flightId)
        node.put("event_type", type)
        node.put("mode", "SHADOW")
        node.put("detected_at_edge_monotonic_ns", 42L)
        node.put("observation_quality", "VALID")
        node.putArray("evidence_artifact_ids")
        return node
    }

    @Test
    fun sessions_are_newest_first_and_separate_the_three_count_axes() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-a1"), "boot")
        store.storeObservation(observation(flightA, "o-a2"), "boot")
        store.storeEvent(event(flightA, "e-a1", "PROXIMITY_CANDIDATE"), "boot")
        store.storeEvent(event(flightA, "e-a2", "DETECTED_ONLY"), "boot")
        store.startFlight(flightRecord(flightB))
        store.storeObservation(observation(flightB, "o-b1"), "boot")

        val sessions = ObservationRecordModel.sessions(store)
        assertEquals(2, sessions.size)
        assertEquals("최신 세션 우선", flightB, sessions[0].flightId)
        assertEquals(1, sessions[0].observationCount)
        assertEquals("객체 관측만이면 확인 필요 0건", 0, sessions[0].reviewNeededCount)
        assertEquals(2, sessions[1].observationCount)
        assertEquals("위험 후보만 확인 필요로 센다", 1, sessions[1].reviewNeededCount)
    }

    @Test
    fun zero_observations_is_unknown_analysis_not_a_clean_negative() {
        val store = store()
        store.startFlight(flightRecord(flightA))

        val session = ObservationRecordModel.sessions(store).single()
        assertEquals(0, session.observationCount)
        assertTrue("관측 0건은 분석 결과 알 수 없음", session.analysisUnknown)
        assertTrue("판단 근거 없음", session.unjudgeable)

        val line = ObservationRecordModel.summaryLine(session)
        assertTrue(line.contains("AI 관측 0건"))
        assertTrue("0건과 공백을 구분", line.contains("분석 공백 0건"))
    }

    @Test
    fun withheld_geometry_counts_as_analysis_gap() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1", "WITHHELD"), "boot")

        val session = ObservationRecordModel.sessions(store).single()
        assertEquals(1, session.analysisGapCount)
        assertTrue("공백이 있으면 판단 불가", session.unjudgeable)
    }

    @Test
    fun sync_state_without_a_server_query_says_completeness_is_unknown() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), "boot")

        val state = ObservationRecordModel.syncState(store, serverSessionCount = null)
        assertEquals(1, state.localSessionCount)
        assertTrue("서버 미조회 = 유실 여부 미확인", state.completenessUnknown)
        val line = ObservationRecordModel.syncLine(state)
        assertTrue(line.contains("유실 여부 아직 확인 못 함"))
    }

    @Test
    fun a_server_query_removes_the_unknown_but_keeps_unconfirmed_evidence_visible() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), "boot")
        // 로컬에는 있으나 서버 커밋은 아직 확인되지 않은 증거(typical: 오프라인 종료).
        val bytes = ByteArray(8) { it.toByte() }
        val artifact = Mop.json.createObjectNode()
        artifact.put("artifact_id", "a-local")
        artifact.put("flight_id", flightA)
        artifact.put("sha256", sha256Hex(bytes))
        artifact.put("size_bytes", 8L)
        artifact.put("media_type", "image/jpeg")
        store.announceArtifact(artifact, "boot")
        store.writeChunk("a-local", 0, bytes)

        val state = ObservationRecordModel.syncState(store, serverSessionCount = 1)
        assertFalse(state.completenessUnknown)
        assertEquals(1, state.localArtifactCount)
        assertEquals("아직 커밋 확인 안 된 증거", 1, state.pendingArtifactCount)
        assertTrue("미전송 증거는 숨기지 않는다", state.unconfirmedLocalEvidence)
        val line = ObservationRecordModel.syncLine(state)
        assertTrue(line.contains("미전송 1건"))
        assertTrue(line.contains("서버 세션 1건"))
    }

    @Test
    fun a_locally_complete_artifact_alone_does_not_claim_server_confirmation() {
        val store = store()
        store.startFlight(flightRecord(flightA))
        val bytes = ByteArray(8) { it.toByte() }
        val artifact = Mop.json.createObjectNode()
        artifact.put("artifact_id", "a-local")
        artifact.put("flight_id", flightA)
        artifact.put("sha256", sha256Hex(bytes))
        artifact.put("size_bytes", 8L)
        artifact.put("media_type", "image/jpeg")
        store.announceArtifact(artifact, "boot")
        store.writeChunk("a-local", 0, bytes)

        val state = ObservationRecordModel.syncState(store, serverSessionCount = 1)
        assertEquals("로컬 완수만으로는 서버 커밋이 아니다", 0, state.committedArtifactCount)
        assertEquals(1, state.localArtifactCount)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun the_flight_history_row_stays_the_source_of_the_observation_detail() {
        // D5: 비행 이력(미션·번들·구역·시각)은 관측 세션의 부가 정보로만 쓰고,
        // 관측 요약(건수·후보·공백)이 앞에 온다.
        val store = store()
        store.startFlight(flightRecord(flightA))
        store.storeObservation(observation(flightA, "o-1"), "boot")
        store.storeEvent(event(flightA, "e-1", "PROXIMITY_CANDIDATE"), "boot")

        val session = ObservationRecordModel.sessions(store).single()
        val line = ObservationRecordModel.summaryLine(session)
        assertTrue(line.indexOf("AI 관측") < line.indexOf("세션 판정"))
        assertTrue(line.contains("확인 필요 1건"))
    }
}
