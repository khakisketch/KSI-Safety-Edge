package ksi.safetyedge.rc

import ksi.rc.PendingSiteValidation

/**
 * Offline-first site registration dialog model (adopted UX): minimal
 * registration (name + location confirm only), saved locally with an
 * honest "stored on this device, central registration pending" state,
 * synced later when a Core is reachable.
 *
 * Pure Kotlin, host-tested. The dialog itself (Android views) binds to
 * [PendingSiteForm.problems] for inline errors and to the honesty labels
 * below for status text — no string is invented in the Activity layer.
 *
 * Honesty rules enforced here:
 * - a pending draft is NEVER labeled "등록됨";
 * - position suggestions never force (the existing `SiteSuggestion` stays
 *   selection-only; this form takes an operator-confirmed location or none);
 * - a server name-keyed match is surfaced as a link to the central site
 *   with the same name, never as a new registration.
 */
object PendingSiteRegistration {

    /** Pending badge: stored locally, central registration outstanding. */
    const val PENDING_BADGE = "이 기기에 저장됨 · 중앙 등록 대기"

    /** No Core configured: pending with its reason (not an error). */
    const val NO_CORE_REASON = "Core 미설정 · 중앙 등록 대기"

    /** Offline: the last attempt never happened, nothing failed. */
    const val OFFLINE_REASON = "연결 없음 · 다음 연결 시 중앙 등록 시도"

    /** Org unknown (no synced site carries one): upload not attempted. */
    const val ORG_UNKNOWN_REASON = "중앙 조직 미확인 · 중앙 등록 대기"

    /** Last upload failed: stays pending, retry later. */
    const val ATTEMPT_FAILED_PREFIX = "중앙 등록 실패 · 다시 시도 예정"

    /** Server returned an existing row for the same name (dedup, not new). */
    const val MATCHED_NOTICE = "중앙에 같은 이름 현장 존재 — 연결됨"

    /** Matched row kept its own coordinates: the draft's location was dropped. */
    const val MATCHED_LOCATION_WARN = "위치 다름 · 중앙 위치 유지됨 (현장 확인 필요)"

    /** Server minted a new id for the draft. */
    const val CREATED_NOTICE = "중앙에 새 현장으로 등록됨"

    /** Upload attempted for a flight on an unresolved pending site. */
    const val UPLOAD_WAIT_REASON = "중앙 등록 대기 중 · 먼저 현장 동기화로 중앙 등록하세요"

    /** Dialog entry copy (mission screen site area). */
    const val ADD_ENTRY_LABEL = "현장 추가"

    /** Dialog title copy. */
    const val DIALOG_TITLE = "현장 등록 (이 기기에 저장)"

    /**
     * Minimal registration form: name + an operator-confirmed location
     * (or none — [requireLocation] stays false so a name-only draft is
     * always allowed offline). Location confirm/clear is explicit: clearing
     * drops all three fields together (all-or-nothing, same as the server).
     */
    data class PendingSiteForm(
        val name: String,
        val centerLat: Double? = null,
        val centerLon: Double? = null,
        val radiusM: Double? = null,
    ) {
        /** Korean inline problems (empty = submittable). */
        fun problems(): List<String> {
            val problems = mutableListOf<String>()
            if (PendingSiteValidation.validateName(name) != null) {
                problems.add("현장 이름을 입력하세요")
            }
            for (code in PendingSiteValidation.validateLocation(centerLat, centerLon, radiusM)) {
                problems.add(
                    when (code) {
                        PendingSiteValidation.PARTIAL_LOCATION ->
                            "위치는 위도·경도·반경을 모두 입력하거나 모두 비우세요"
                        PendingSiteValidation.BAD_LAT ->
                            "위도가 범위를 벗어났습니다 (-90~90)"
                        PendingSiteValidation.BAD_LON ->
                            "경도가 범위를 벗어났습니다 (-180~180)"
                        PendingSiteValidation.BAD_RADIUS ->
                            "반경은 0보다 크고 100km 이하여야 합니다"
                        else -> "현장 위치를 확인하세요 ($code)"
                    },
                )
            }
            return problems
        }

        /** True when the form may create a draft. */
        fun submittable(): Boolean = problems().isEmpty()

        /** Location clear (explicit operator action, never partial). */
        fun cleared(): PendingSiteForm =
            PendingSiteForm(name = name)
    }

    /** Pending status line for a draft (badge + optional reason). */
    fun statusLine(reason: String? = null): String =
        if (reason.isNullOrBlank()) PENDING_BADGE else "$PENDING_BADGE · $reason"

    /** Upload-failure status line (keeps the pending badge, adds the fact). */
    fun failedStatusLine(error: String): String =
        "$PENDING_BADGE · $ATTEMPT_FAILED_PREFIX"

    /** Resolution notice for a synced draft (honest about match vs create). */
    fun resolutionNotice(matched: Boolean): String =
        if (matched) MATCHED_NOTICE else CREATED_NOTICE
}
