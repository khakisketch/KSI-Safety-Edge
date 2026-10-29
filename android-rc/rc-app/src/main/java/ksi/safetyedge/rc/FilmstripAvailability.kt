package ksi.safetyedge.rc

/**
 * Verified filmstrip availability. Pure Kotlin, no Android — host-tested.
 *
 * A frame is shown as preserved ONLY when it is genuinely available:
 * image media, cheap receipt complete, bytes actually present, and bytes
 * that decode as an image. Anything else stays disabled with a VISIBLE
 * reason (the button text itself carries it, not just the content
 * description). In particular `isArtifactComplete` alone (cheap
 * bytes-received, no hash) never reads as "보존됨".
 */
object FilmstripAvailability {

    enum class Status { READY, MISSING, CORRUPT, NON_IMAGE }

    data class Availability(
        val status: Status,
        /** Shown on the button itself. */
        val visibleLabel: String,
        /** Shown beside/under the button. */
        val reason: String,
    )

    fun classify(
        isImage: Boolean,
        complete: Boolean,
        bytesPresent: Boolean,
        decodable: Boolean,
    ): Availability = when {
        !isImage -> Availability(
            Status.NON_IMAGE,
            "이미지 아님",
            "지원 자료 · 상세에서 확인",
        )
        !complete || !bytesPresent -> Availability(
            Status.MISSING,
            "로컬 미수신",
            "로컬 수신 없음 · Edge 보존 여부 미확인 · 서버 가져오기로 확인",
        )
        !decodable -> Availability(
            Status.CORRUPT,
            "손상됨",
            "바이트는 있으나 이미지로 열리지 않음",
        )
        else -> Availability(
            Status.READY,
            "RC 로컬 보존됨",
            "RC 로컬 바이트 검증됨 · Edge 원본·Core 보관은 별도",
        )
    }
}

/**
 * Viewer request identity: async evidence loads must never paint a stale
 * frame over a newer selection. The session hands out generations; a
 * completed load applies only when its request still equals the current
 * one. Pure Kotlin — host-tested; MainActivity delegates to an instance.
 */
class ViewerSession {

    data class Request(
        val generation: Long,
        val flightId: String?,
        val eventId: String,
        val artifactId: String,
    )

    private var generation = 0L
    var current: Request? = null
        private set

    /** A selection change clears the viewer and invalidates everything. */
    fun select(): Long {
        generation += 1
        current = null
        return generation
    }

    /** A new load becomes the only request allowed to paint. */
    fun request(flightId: String?, eventId: String, artifactId: String): Request {
        generation += 1
        val request = Request(generation, flightId, eventId, artifactId)
        current = request
        return request
    }

    fun shouldApply(request: Request): Boolean = request == current
}
