package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host tests for the read-only AIRCRAFT camera seam
 * ([AircraftCameraTelemetrySource]): UNKNOWN-until-reported, no invented
 * 1.0x/idle/success, shooting-false is idle (never success), CONFIRMED only
 * from an explicit capture feed, and no staleness timer on change-only
 * reports.
 */
class AircraftCameraTelemetrySourceTest {

    @Test
    fun unknown_until_reported_has_no_display_state() {
        val snap = AircraftCameraTelemetrySource.Snapshot()
        assertFalse(snap.hasAnyReport)
        assertNull(snap.recording)
        assertNull(snap.recordingSeconds)
        assertNull(snap.lensName)
        assertNull(snap.zoomRatio)
        assertNull(snap.shooting)
        assertNull(snap.lastCaptureAtMs)
        assertNull(snap.displayLens())
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.NONE, snap.displayPhoto())
    }

    @Test
    fun display_lens_prefers_stream_source_over_hardware_type() {
        val lensOnly = AircraftCameraTelemetrySource.Snapshot(lensName = "WIDE")
        assertEquals("WIDE", lensOnly.displayLens())

        val both = AircraftCameraTelemetrySource.Snapshot(lensName = "ZOOM", cameraType = "M4D")
        assertEquals("ZOOM", both.displayLens())

        val typeOnly = AircraftCameraTelemetrySource.Snapshot(cameraType = "M4D")
        assertEquals("M4D", typeOnly.displayLens())
        assertTrue(typeOnly.hasAnyReport)
    }

    @Test
    fun display_photo_needs_shooting_or_capture_evidence() {
        val idle = AircraftCameraTelemetrySource.Snapshot(shooting = false)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.NONE, idle.displayPhoto())

        val shooting = AircraftCameraTelemetrySource.Snapshot(
            shooting = true,
            lastCaptureAtMs = 5_000,
            lastCaptureKind = "JPEG",
        )
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.SHOOTING, shooting.displayPhoto())

        val confirmed = AircraftCameraTelemetrySource.Snapshot(
            shooting = false,
            lastCaptureAtMs = 5_000,
            lastCaptureKind = "JPEG",
        )
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.CONFIRMED, confirmed.displayPhoto())

        // Latched: a confirmed capture stays confirmed while idle — no
        // invented expiry timer clears it.
        val latched = AircraftCameraTelemetrySource.Snapshot(lastCaptureAtMs = 9_000)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.CONFIRMED, latched.displayPhoto())
    }

    @Test
    fun fake_feeds_update_snapshot_and_notify_only_while_running() {
        val source = FakeAircraftCameraTelemetrySource()
        var updates = 0

        assertFailsWith<IllegalStateException> {
            source.feedRecording(true, atMs = 1_000)
        }

        source.start { updates += 1 }
        source.feedRecording(true, atMs = 1_000)
        source.feedRecordingTime(53, atMs = 1_500)
        source.feedLens("WIDE", atMs = 1_000)
        source.feedZoom(1.0, atMs = 1_000)
        source.feedShooting(false, atMs = 1_000)
        assertEquals(5, updates)

        val snap = source.snapshot()
        assertEquals(true, snap.recording)
        assertEquals(53L, snap.recordingSeconds)
        assertEquals("WIDE", snap.lensName)
        assertEquals(1.0, snap.zoomRatio)
        assertEquals(false, snap.shooting)
        assertTrue(snap.hasAnyReport)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.NONE, snap.displayPhoto())

        source.feedShooting(true, atMs = 2_000)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.SHOOTING, source.snapshot().displayPhoto())

        // Idle alone is never a capture: still NONE without capture evidence.
        source.feedShooting(false, atMs = 3_000)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.NONE, source.snapshot().displayPhoto())

        source.feedCapture("JPEG", atMs = 4_000)
        assertEquals(AircraftCameraTelemetrySource.PhotoDisplay.CONFIRMED, source.snapshot().displayPhoto())

        source.stop()
        assertFailsWith<IllegalStateException> {
            source.feedZoom(3.0, atMs = 5_000)
        }
        assertEquals(8, updates)
    }

    @Test
    fun fake_rejects_invented_values() {
        val source = FakeAircraftCameraTelemetrySource()
        source.start {}
        assertFailsWith<IllegalArgumentException> { source.feedLens(" ", atMs = 0) }
        assertFailsWith<IllegalArgumentException> { source.feedCameraType("", atMs = 0) }
        assertFailsWith<IllegalArgumentException> { source.feedRecordingTime(-1, atMs = 0) }
        assertFailsWith<IllegalArgumentException> { source.feedZoom(Double.NaN, atMs = 0) }
        assertFailsWith<IllegalArgumentException> { source.feedZoom(0.0, atMs = 0) }
        assertFailsWith<IllegalArgumentException> { source.feedCapture(" ", atMs = 0) }
    }
}
