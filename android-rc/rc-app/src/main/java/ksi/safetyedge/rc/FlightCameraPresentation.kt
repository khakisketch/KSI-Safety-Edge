package ksi.safetyedge.rc

import java.util.Locale

/** Display input from camera reports, never a command receipt or KSI/proxy recording.
 * The read-only camera source is connected on device; until a field is reported it
 * stays UNKNOWN/null. Bench inputs are explicitly simulated and cannot be selected
 * in the device flavor. */
data class FlightCameraPresentation(
    val recording: Boolean? = null,
    val recordingSeconds: Long? = null,
    val lensName: String? = null,
    val zoomRatio: Double? = null,
    val photo: Photo = Photo.UNKNOWN,
    val stale: Boolean = false,
    val simulated: Boolean = false,
) {
    enum class Photo { UNKNOWN, NONE, SHOOTING, CONFIRMED, FAILED }

    fun lensLabel(): String {
        if (stale) return "카메라 상태 지연"
        val lens = lensName?.takeIf { it.isNotBlank() }
        val zoom = zoomRatio?.takeIf { it.isFinite() && it >= 1.0 }
            ?.let { String.format(Locale.ROOT, "%.1f×", it) }
        if (lens == null && zoom == null) return "렌즈·줌 미수신"
        return listOfNotNull(lens, zoom).joinToString("  ")
    }

    fun feedback(): String? {
        if (stale) return if (recording == true) "REC · 마지막 보고" else null
        val photoLabel = when (photo) {
            Photo.SHOOTING -> "촬영 중"
            Photo.CONFIRMED -> "촬영 확인"
            Photo.FAILED -> "촬영 실패"
            Photo.NONE, Photo.UNKNOWN -> null
        }
        val recLabel = if (recording == true) recordingSeconds?.takeIf { it >= 0 }?.let {
            String.format(Locale.ROOT, "REC  %02d:%02d", it / 60, it % 60)
        } ?: "REC" else null
        return listOfNotNull(recLabel, photoLabel).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
