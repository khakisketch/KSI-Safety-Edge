package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host tests for the read-only AIRCRAFT camera display mapping
 * ([mapCameraSnapshot]): UNKNOWN stays unknown, reports pass through
 * verbatim, CONFIRMED comes only from capture evidence, and the mapping
 * never invents staleness or simulation.
 */
class CameraSnapshotMappingTest {

    @Test
    fun unknown_snapshot_maps_to_unknown_presentation() {
        val presentation = mapCameraSnapshot(AircraftCameraTelemetrySource.Snapshot())

        assertNull(presentation.recording)
        assertNull(presentation.recordingSeconds)
        assertNull(presentation.lensName)
        assertNull(presentation.zoomRatio)
        assertEquals(FlightCameraPresentation.Photo.UNKNOWN, presentation.photo)
        assertFalse(presentation.stale)
        assertFalse(presentation.simulated)
        // Unknown is a detail fact: the flight edge shows the "?" entry.
        assertEquals("카메라 ?", presentation.let {
            // Mirrors RcWorkspaceLayout.bindCameraPresentation hasReport gate.
            val hasReport = it.lensName != null || it.zoomRatio != null ||
                it.recording != null || it.photo != FlightCameraPresentation.Photo.UNKNOWN
            if (hasReport) it.lensLabel() else "카메라 ?"
        })
    }

    @Test
    fun recording_lens_and_zoom_pass_through_verbatim() {
        val presentation = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(
                recording = true,
                recordingAtMs = 1_000,
                recordingSeconds = 53,
                recordingSecondsAtMs = 1_500,
                lensName = "WIDE",
                lensAtMs = 1_000,
                zoomRatio = 1.0,
                zoomAtMs = 1_000,
                shooting = false,
                shootingAtMs = 1_000,
            ),
        )

        assertEquals(true, presentation.recording)
        assertEquals(53L, presentation.recordingSeconds)
        assertEquals("WIDE", presentation.lensName)
        assertEquals(1.0, presentation.zoomRatio!!, 0.0)
        assertEquals(FlightCameraPresentation.Photo.NONE, presentation.photo)
        assertEquals("WIDE  1.0×", presentation.lensLabel())
        assertEquals("REC  00:53", presentation.feedback())
    }

    @Test
    fun lens_falls_back_to_hardware_type_only_when_lens_unknown() {
        val fallback = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(cameraType = "M4D"),
        )
        assertEquals("M4D", fallback.lensName)

        val preferred = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(lensName = "ZOOM", cameraType = "M4D"),
        )
        assertEquals("ZOOM", preferred.lensName)
    }

    @Test
    fun shooting_overrides_capture_and_idle_is_never_success() {
        val shooting = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(
                shooting = true,
                lastCaptureAtMs = 4_000,
                lastCaptureKind = "JPEG",
            ),
        )
        assertEquals(FlightCameraPresentation.Photo.SHOOTING, shooting.photo)

        val idleWithoutCapture = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(shooting = false),
        )
        assertEquals(FlightCameraPresentation.Photo.NONE, idleWithoutCapture.photo)

        val unknownShooting = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(lensName = "WIDE", zoomRatio = 1.0),
        )
        assertEquals(FlightCameraPresentation.Photo.UNKNOWN, unknownShooting.photo)

        val confirmed = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(
                shooting = false,
                lastCaptureAtMs = 4_000,
                lastCaptureKind = "JPEG",
            ),
        )
        assertEquals(FlightCameraPresentation.Photo.CONFIRMED, confirmed.photo)
    }

    @Test
    fun mapping_never_invents_stale_or_simulation() {
        val presentation = mapCameraSnapshot(
            AircraftCameraTelemetrySource.Snapshot(
                recording = true,
                recordingSeconds = 53,
                lensName = "ZOOM",
                zoomRatio = 6.0,
                lastCaptureAtMs = 2_000,
                lastCaptureKind = "JPEG",
            ),
        )
        assertFalse(presentation.stale)
        assertFalse(presentation.simulated)
    }
}
