package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unknown, idle and reported camera facts must not share one label. */
class CameraDetailCopyTest {

    @Test
    fun recFalseIsIdleAndNullIsUnknown() {
        assertEquals("미수신", CameraDetailCopy.rec(null, null, false))
        assertEquals("REC 아님", CameraDetailCopy.rec(false, null, false))
        assertEquals("REC 아님", CameraDetailCopy.rec(false, 53, false))
        assertEquals("REC", CameraDetailCopy.rec(true, null, false))
        assertEquals("REC  00:53", CameraDetailCopy.rec(true, 53, false))
    }

    @Test
    fun staleKeepsTheLastReportedFact() {
        assertEquals("지연 · REC", CameraDetailCopy.rec(true, 53, true))
        assertEquals("지연 · REC 아님", CameraDetailCopy.rec(false, null, true))
        assertEquals("지연 · 미수신", CameraDetailCopy.rec(null, null, true))
    }

    @Test
    fun unknownPhotoIsNotNoPhoto() {
        assertEquals("미수신", CameraDetailCopy.photo(FlightCameraPresentation.Photo.UNKNOWN))
        assertEquals("없음", CameraDetailCopy.photo(FlightCameraPresentation.Photo.NONE))
        assertEquals("촬영 중", CameraDetailCopy.photo(FlightCameraPresentation.Photo.SHOOTING))
        assertEquals("촬영 확인", CameraDetailCopy.photo(FlightCameraPresentation.Photo.CONFIRMED))
        assertEquals("촬영 실패", CameraDetailCopy.photo(FlightCameraPresentation.Photo.FAILED))
    }
}
