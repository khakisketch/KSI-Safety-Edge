package ksi.safetyedge.rc

/**
 * Honest copy for the bounded camera detail. A `false` recording report is
 * an idle fact ("REC 아님"), not a missing report ("미수신"). Photo NONE is
 * only shown when the read-only source explicitly reported shooting=false;
 * UNKNOWN stays "미수신". These strings never imply saved media.
 */
internal object CameraDetailCopy {

    fun rec(recording: Boolean?, recordingSeconds: Long?, stale: Boolean): String {
        if (stale) return when (recording) {
            true -> "지연 · REC"
            false -> "지연 · REC 아님"
            null -> "지연 · 미수신"
        }
        return when {
            recording == true && recordingSeconds != null && recordingSeconds >= 0 ->
                "REC  %02d:%02d".format(
                    java.util.Locale.ROOT,
                    recordingSeconds / 60,
                    recordingSeconds % 60,
                )
            recording == true -> "REC"
            recording == false -> "REC 아님"
            else -> "미수신"
        }
    }

    fun photo(photo: FlightCameraPresentation.Photo): String = when (photo) {
        FlightCameraPresentation.Photo.UNKNOWN -> "미수신"
        FlightCameraPresentation.Photo.NONE -> "없음"
        FlightCameraPresentation.Photo.SHOOTING -> "촬영 중"
        FlightCameraPresentation.Photo.CONFIRMED -> "촬영 확인"
        FlightCameraPresentation.Photo.FAILED -> "촬영 실패"
    }
}
