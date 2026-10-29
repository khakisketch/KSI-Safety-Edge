package ksi.safetyedge.rc

import org.junit.Assert.*
import org.junit.Test

class FlightCameraPresentationTest {
    @Test fun unknown_is_not_idle_recording_or_one_times() {
        val state = FlightCameraPresentation()
        assertEquals("렌즈·줌 미수신", state.lensLabel())
        assertNull(state.feedback())
        assertNull(state.recording)
    }
    @Test fun recording_requires_a_camera_report_and_never_invents_a_timer() {
        assertEquals("REC", FlightCameraPresentation(recording = true).feedback())
        assertEquals("REC  01:05", FlightCameraPresentation(recording = true, recordingSeconds = 65).feedback())
        assertNull(FlightCameraPresentation(recording = false, recordingSeconds = 65).feedback())
    }
    @Test fun last_recording_report_is_not_current_confirmation() {
        assertEquals("REC · 마지막 보고", FlightCameraPresentation(recording = true, stale = true).feedback())
        assertEquals("카메라 상태 지연", FlightCameraPresentation(lensName = "WIDE", zoomRatio = 3.0, stale = true).lensLabel())
    }
    @Test fun invalid_zoom_never_becomes_a_plausible_reading() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0))
            assertEquals("렌즈·줌 미수신", FlightCameraPresentation(zoomRatio = bad).lensLabel())
        assertEquals("WIDE  3.0×", FlightCameraPresentation(lensName = "WIDE", zoomRatio = 3.0).lensLabel())
    }
    @Test fun photo_feedback_does_not_claim_media_storage() {
        assertEquals("촬영 중", FlightCameraPresentation(photo = FlightCameraPresentation.Photo.SHOOTING).feedback())
        assertEquals("촬영 확인", FlightCameraPresentation(photo = FlightCameraPresentation.Photo.CONFIRMED).feedback())
        assertFalse(FlightCameraPresentation(photo = FlightCameraPresentation.Photo.CONFIRMED).feedback()!!.contains("저장"))
    }
    @Test fun photo_feedback_does_not_hide_a_confirmed_recording() {
        val label = FlightCameraPresentation(recording = true, recordingSeconds = 4, photo = FlightCameraPresentation.Photo.CONFIRMED).feedback()
        assertEquals("REC  00:04 · 촬영 확인", label)
    }
}
