package ksi.safetyedge.rc

/**
 * Preparation screen (pageMission) binding model (screen contract §2,
 * 2026-10-21 edge-operation contract §5/§6).
 *
 * Pure Kotlin, no Android: host-tested. Binds ONLY mission data the app
 * already holds — zone revisions and their ids from the flight record /
 * bundle snapshot, the bundle digest, and the apply state from
 * `MissionController` — into THREE SEPARATE facts that are never merged
 * into one "준비 완료":
 * - cache (sync result): what the last sync reflected locally;
 * - local save: the digest-verified bundle pinned on this device;
 * - Edge apply: what the Edge confirmed installed (APPLIED 실확인만).
 *
 * Apply failure/rejection is shown honestly. There is deliberately NO
 * map/tap editor here: zone polygons in the bundle are WGS84 GeoJSON and
 * the map-editing surface is a separate unresolved item — the panel says
 * so instead of fabricating zone geometry.
 */
object MissionPreparationModel {

    /** Sync chip state (local reflection only — never a server judgment). */
    enum class SyncResult { UNKNOWN, SUCCESS_LOCAL, FAILURE }

    data class Input(
        val syncResult: SyncResult,
        val siteId: String?,
        val bundleId: String?,
        val bundleDigest: String?,
        val bundleDigestVerified: Boolean,
        val missionRevisionId: String?,
        /** Null = the revision basis itself is missing (never "0개"). */
        val zoneRevisionIds: List<String>?,
        /** Null = no apply context (no controller / never prepared). */
        val controllerState: String?,
        /** True only when the Edge confirmed THIS pinned digest installed. */
        val applyConfirmed: Boolean?,
        val applicationId: String?,
        val rejectionReasons: List<String> = emptyList(),
        val linkUp: Boolean? = null,
        val hasActiveFlight: Boolean = false,
        /**
         * A preparation attempt that survived process death without
         * completing and without a live controller. Shown so the operator
         * explicitly re-runs 준비; never auto-resumed here.
         */
        val restoredPendingBundleId: String? = null,
        val restoredPendingDecidedAtUtc: String? = null,
    )

    data class Panel(
        /** Sync/cache fact. */
        val cacheLine: String,
        /** Local-save fact. */
        val localLine: String,
        /** Edge-apply fact. */
        val applyLine: String,
        /** Restart-restored pending attempt (null = none outstanding). */
        val restoredLine: String?,
        val revisionLine: String,
        val zoneLine: String,
        /** Static gap note: no map editor is fabricated. */
        val mapNote: String = MAP_NOTE,
        val severity: OperatorStatus.Severity,
    )

    const val MAP_NOTE = "구역 지도 표시·편집 미구현 · 리비전 목록과 적용 상태로만 확인합니다"

    fun cacheLine(syncResult: SyncResult): String = when (syncResult) {
        SyncResult.UNKNOWN -> "동기화 · 미확인 · 로컬 반영"
        SyncResult.SUCCESS_LOCAL -> "동기화 · 성공(로컬 반영) · 서버 상태 별도"
        SyncResult.FAILURE -> "동기화 · 실패 · 로컬 반영은 알 수 없음"
    }

    fun localLine(bundleId: String?, bundleDigest: String?, verified: Boolean): String {
        if (bundleId == null) return "로컬 저장 · 선택된 번들 없음"
        if (!verified) return "로컬 저장 · 번들 ${bundleId.take(8)} · digest 미검증 · 적용 불가"
        return "로컬 저장 · 번들 ${bundleId.take(8)} · digest " +
            "${bundleDigest?.take(12) ?: "미확인"}(다운로드 시 검증)"
    }

    fun applyLine(
        controllerState: String?,
        applyConfirmed: Boolean?,
        applicationId: String?,
        rejectionReasons: List<String>,
        linkUp: Boolean?,
    ): String {
        if (rejectionReasons.isNotEmpty()) {
            return "Edge 적용 · 거부됨 · ${rejectionReasons.joinToString(", ")}"
        }
        if (applyConfirmed == true) {
            return "Edge 적용 · APPLIED 확인 · application " +
                "${applicationId?.take(8) ?: "미확인"} · 고정 번들 digest 일치"
        }
        if (controllerState == "STATUS_UNKNOWN") {
            return "Edge 적용 · 상태 미확인 · 재조정 필요"
        }
        if (controllerState == null) {
            return "Edge 적용 · 미확인 · 적용 응답 없음"
        }
        if (controllerState == "PREPARING") {
            return "Edge 적용 · 준비 요청 전송됨(상태 PREPARING) · 적용 확인 아님"
        }
        if (controllerState == "PREPARED") {
            return "Edge 적용 · 준비 확인(PREPARED) · 설치는 별도 확인 필요"
        }
        if (controllerState == "APPLYING") {
            if (linkUp == false) {
                return "Edge 적용 · 미확인(링크 끊김) · 적용 전송은 대기열 · 현재 상태 추정 안 함"
            }
            return "Edge 적용 · 적용 요청 전송됨(상태 APPLYING) · 설치 확인 아님"
        }
        return "Edge 적용 · 미확인(상태 $controllerState) · 적용 확인 아님"
    }

    fun zoneLine(zoneRevisionIds: List<String>?): String {
        if (zoneRevisionIds == null) return "구역 리비전 · 미확인(번들 미선택 또는 스냅샷 없음)"
        if (zoneRevisionIds.isEmpty()) return "구역 리비전 0개 · 빈 구역 집합(사실 기록)"
        val shown = zoneRevisionIds.take(3).joinToString(", ") { it.take(8) }
        val rest = if (zoneRevisionIds.size > 3) " 외 ${zoneRevisionIds.size - 3}건" else ""
        return "구역 리비전 ${zoneRevisionIds.size}개 · $shown$rest"
    }

    fun restoredLine(bundleId: String?, decidedAtUtc: String?): String? {
        if (bundleId == null) return null
        return "재시작 전 준비 요청(미확인) · 번들 ${bundleId.take(8)} · " +
            "응답 확인 안 됨(${decidedAtUtc ?: "시각 미확인"} 요청) · 다시 준비하십시오(자동 재전송 없음)"
    }

    fun render(input: Input): Panel {
        val apply = applyLine(
            input.controllerState,
            input.applyConfirmed,
            input.applicationId,
            input.rejectionReasons,
            input.linkUp,
        )
        val severity = when {
            input.rejectionReasons.isNotEmpty() -> OperatorStatus.Severity.PROBLEM
            input.applyConfirmed == true -> OperatorStatus.Severity.INFO
            else -> OperatorStatus.Severity.ATTENTION
        }
        return Panel(
            cacheLine = cacheLine(input.syncResult),
            localLine = localLine(input.bundleId, input.bundleDigest, input.bundleDigestVerified),
            applyLine = apply,
            restoredLine = restoredLine(input.restoredPendingBundleId, input.restoredPendingDecidedAtUtc),
            revisionLine = "미션 리비전 · ${input.missionRevisionId?.take(8) ?: "미확인"}",
            zoneLine = zoneLine(input.zoneRevisionIds),
            severity = severity,
        )
    }
}
