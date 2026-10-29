package ksi.safetyedge.rc

/**
 * Round68 copy-density: user-facing wording for raw internal facts.
 *
 * Pure Kotlin, no Android: host-tested. Every mapper keeps the raw fact
 * for logs/details/content-descriptions and returns only a short Korean
 * label for the glanceable line — numbers and status first, explanation
 * in the detail. Unknown inputs are never upgraded to healthy claims.
 *
 * Forced contracts kept: honesty markers (미확인 stays 미확인), S1
 * order/gate wording untouched (nothing here renames a gate), no
 * contracts/docs edits.
 */
object UserFacingCopy {

    /** Display wording only; the stored quality and raw diagnostic value are never rewritten. */
    fun observationQualityLabel(raw: String?): String = when (raw) {
        "HIGH" -> "높음"
        "LOW" -> "낮음"
        "GOOD" -> "양호"
        "DEGRADED" -> "저하"
        "UNVERIFIED" -> "검증 전"
        else -> "미확인"
    }

    /** Short Korean action for a link/ingest failure cause (raw kept for detail). */
    fun causeLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return "원인 미확인"
        if (raw.contains("no mission controller", ignoreCase = true)) {
            return "임무 제어 미연결 · 임무 준비에서 계획·기록 상태를 확인하세요"
        }
        return raw
    }

    /** Short Korean label for an evidence MIME type (unknown types pass through). */
    fun mediaLabel(mime: String?): String = when (mime?.trim()?.lowercase()) {
        "application/json" -> "JSON"
        "application/x-ndjson" -> "NDJSON"
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "video/mp4" -> "MP4"
        null, "" -> "자료"
        else -> mime
    }

    /** Compact byte size: raw bytes stay in the store, this is display only. */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 0 -> "크기 미확인"
        bytes < 1024 -> "${bytes}B"
        else -> {
            val kb = bytes / 1024.0
            if (kb < 1024) "${"%.1f".format(kb)}KB"
            else "${"%.1f".format(kb / 1024.0)}MB"
        }
    }

    /** Short Korean label for a sync time-basis code (unknown codes pass through). */
    fun timeBasisLabel(raw: String?): String = when (raw) {
        "RC_RECEIVE_APPROX" -> "RC 수신시각(근사)"
        null, "" -> "시각 기준 미확인"
        else -> raw
    }

    /** Short Korean label for a collection-session status (unknown passes through). */
    fun bootStatusLabel(raw: String?): String = when (raw) {
        "COMMITTED" -> "커밋 확인"
        "PARTIAL" -> "일부 미완료"
        "OPEN" -> "수집 중"
        null, "" -> "상태 미확인"
        else -> raw
    }
}
