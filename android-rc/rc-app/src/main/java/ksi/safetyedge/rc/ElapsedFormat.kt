package ksi.safetyedge.rc

/**
 * Human elapsed-time wording for operator displays (pure Kotlin, host-tested).
 *
 * Floor units, never rounded up: "3초 전" means at least 3 full seconds
 * elapsed. Null or negative (unknown/missing basis) renders as unknown —
 * never 0, never a fabricated zero-second claim. Hours cap the scale;
 * beyond a day still reads "N시간 전" (the exact figure stays in logs).
 */
object ElapsedFormat {
    /** Unknown basis (null/negative) line. */
    const val UNKNOWN = "경과 미확인"

    fun ageAgo(ageMs: Long?): String {
        if (ageMs == null || ageMs < 0) return UNKNOWN
        val seconds = ageMs / 1_000L
        if (seconds < 60L) return "${seconds}초 전"
        val minutes = seconds / 60L
        if (minutes < 60L) return "${minutes}분 전"
        return "${minutes / 60L}시간 전"
    }
}
