package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingCopyTest {

    @Test
    fun mission_controller_cause_becomes_a_korean_retry_action() {
        assertEquals(
            "임무 제어 미연결 · 임무 준비에서 계획·기록 상태를 확인하세요",
            UserFacingCopy.causeLabel("no mission controller (start a flight first)"),
        )
    }

    @Test
    fun unknown_cause_stays_unknown_never_healthy() {
        assertEquals("원인 미확인", UserFacingCopy.causeLabel(null))
        assertEquals("원인 미확인", UserFacingCopy.causeLabel("  "))
    }

    @Test
    fun unmapped_cause_passes_through_for_diagnosis() {
        assertEquals("socket timeout", UserFacingCopy.causeLabel("socket timeout"))
    }

    @Test
    fun media_types_map_to_short_labels() {
        assertEquals("JSON", UserFacingCopy.mediaLabel("application/json"))
        assertEquals("NDJSON", UserFacingCopy.mediaLabel("application/x-ndjson"))
        assertEquals("자료", UserFacingCopy.mediaLabel(null))
        assertEquals("application/octet-stream", UserFacingCopy.mediaLabel("application/octet-stream"))
    }

    @Test
    fun sizes_stay_compact() {
        assertEquals("612B", UserFacingCopy.sizeLabel(612))
        assertEquals("5.6KB", UserFacingCopy.sizeLabel(5763))
        assertEquals("크기 미확인", UserFacingCopy.sizeLabel(-1))
    }

    @Test
    fun time_basis_and_boot_status_map_to_korean() {
        assertEquals("RC 수신시각(근사)", UserFacingCopy.timeBasisLabel("RC_RECEIVE_APPROX"))
        assertEquals("커밋 확인", UserFacingCopy.bootStatusLabel("COMMITTED"))
        assertEquals("일부 미완료", UserFacingCopy.bootStatusLabel("PARTIAL"))
        assertEquals("수집 중", UserFacingCopy.bootStatusLabel("OPEN"))
        assertEquals("MYSTERY", UserFacingCopy.bootStatusLabel("MYSTERY"))
    }
}
