package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-only checks for the bench display timeline. These prove the review
 * harness changes over time; they do not prove a camera connection, a
 * captured photo, or an operational REC state.
 */
class CameraPreviewTimelineTest {
    @Test
    fun unknown_and_static_samples_do_not_tick() {
        assertFalse(WorkspacePreviewFixture.cameraTimelineEnabled(null))
        assertFalse(WorkspacePreviewFixture.cameraTimelineEnabled("camera-rec"))
        assertNull(WorkspacePreviewFixture.cameraPresentationAt("camera-rec", 5_000).recording)
        assertNull(WorkspacePreviewFixture.cameraPresentationAt(null, 5_000).recording)
    }

    @Test
    fun recording_seconds_advance_from_reported_time() {
        assertTrue(WorkspacePreviewFixture.cameraTimelineEnabled("camera-rec-live"))
        val first = WorkspacePreviewFixture.cameraPresentationAt("camera-rec-live", 0)
        val later = WorkspacePreviewFixture.cameraPresentationAt("camera-rec-live", 4_200)
        assertEquals(0L, first.recordingSeconds)
        assertEquals(4L, later.recordingSeconds)
        assertEquals("WIDE  1.0×", later.lensLabel())
        assertTrue(later.simulated)
    }

    @Test
    fun photo_shows_shooting_then_confirmation_then_idle() {
        val shooting = WorkspacePreviewFixture.cameraPresentationAt("camera-photo-live", 400)
        val confirmed = WorkspacePreviewFixture.cameraPresentationAt("camera-photo-live", 2_000)
        val idle = WorkspacePreviewFixture.cameraPresentationAt("camera-photo-live", 6_000)
        assertEquals(FlightCameraPresentation.Photo.SHOOTING, shooting.photo)
        assertEquals(FlightCameraPresentation.Photo.CONFIRMED, confirmed.photo)
        assertEquals(FlightCameraPresentation.Photo.NONE, idle.photo)
        assertEquals("촬영 중", shooting.feedback())
        assertEquals("촬영 확인", confirmed.feedback())
    }

    @Test
    fun photo_failure_and_stale_are_distinct_from_success() {
        val failed = WorkspacePreviewFixture.cameraPresentationAt("camera-photo-fail-live", 2_000)
        assertEquals(FlightCameraPresentation.Photo.FAILED, failed.photo)
        assertEquals("촬영 실패", failed.feedback())
        val fresh = WorkspacePreviewFixture.cameraPresentationAt("camera-stale-live", 1_000)
        val stale = WorkspacePreviewFixture.cameraPresentationAt("camera-stale-live", 3_000)
        assertFalse(fresh.stale)
        assertTrue(stale.stale)
        assertEquals("REC · 마지막 보고", stale.feedback())
    }

    @Test
    fun rec_and_photo_feedback_remain_independent() {
        val both = WorkspacePreviewFixture.cameraPresentationAt("camera-rec-photo-live", 2_000)
        assertEquals(true, both.recording)
        assertEquals(FlightCameraPresentation.Photo.CONFIRMED, both.photo)
        assertEquals("REC  00:02 · 촬영 확인", both.feedback())
    }

    @Test
    fun lens_and_ratio_change_without_claiming_a_zoom_command() {
        assertEquals("WIDE  1.0×", WorkspacePreviewFixture.cameraPresentationAt("camera-lens-live", 500).lensLabel())
        assertEquals("ZOOM  3.0×", WorkspacePreviewFixture.cameraPresentationAt("camera-lens-live", 2_500).lensLabel())
        assertEquals("ZOOM  6.0×", WorkspacePreviewFixture.cameraPresentationAt("camera-lens-live", 4_500).lensLabel())
        assertEquals(false, WorkspacePreviewFixture.cameraPresentationAt("camera-lens-live", 4_500).recording)
    }
}
