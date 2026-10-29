package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * D2 — GPS 현장 추천(선택 기능).
 *
 * **선택 기능이며, 없어도 모든 것이 정상 동작한다.** 현장 인식이 안 되거나
 * 꺼져 있어도 비행 준비·AI 관측·기록 시작은 제한되지 않는다. 무엇이 달라지는
 * 것은 "어느 현장의 정책이 적용되는지"를 조종자가 덜 확인해야 한다는 사실뿐이다.
 *
 * 원칙:
 * - **자동 확정 금지.** 후보는 제안일 뿐이고, 조종자가 확인해야 확정된다.
 * - 위치 출처를 **끊지 않는다**: 기체 위치(MSDK) / RC 자체 GNSS / 미확인.
 * - **경계가 없는 현장**은 후보가 아니다. 중심점+반경이 등록된 현장만 후보가
 *   되며, 그 반경 밖이면 후보가 아니다. 위험구역 다각형을 현장 경계로 쓰지 않는다.
 * - 좌표가 없는 현장, 정확도를 모르는 위치는 조용히 넘어간다(추측 금지).
 */
object SiteSuggestion {

    /** 위치 출처. 서로 섞지 않는다. */
    enum class PositionSource {
        /** 기체 위치(MSDK가 읽은 값). */
        AIRCRAFT,
        /** RC 자체 GNSS. */
        RC_GNSS,
        /** 이 앱이 위치를 알 수 없음. */
        UNKNOWN,
    }

    /** 위치 1건. */
    data class Position(
        val lat: Double,
        val lon: Double,
        val source: PositionSource,
        /** 위치 정확도(m). null = 정확도를 알 수 없음. */
        val accuracyM: Double? = null,
    )

    /** 후보 현장 1건. */
    data class Candidate(
        val siteId: String,
        val siteName: String,
        val centerLat: Double,
        val centerLon: Double,
        val radiusM: Double,
        /** 중심점까지의 거리(m). */
        val distanceM: Double,
    )

    /**
     * 추천 결과.
     *
     * @param candidates 거리순 후보 (가까운 순).
     * @param positionUsed 추천에 쓴 위치(null = 위치를 못 써서 후보 없음).
     */
    data class Suggestion(
        val candidates: List<Candidate>,
        val positionUsed: Position?,
    ) {
        /** 가장 가까운 후보. */
        val best: Candidate? get() = candidates.firstOrNull()

        /** 후보가 하나뿐이어도 자동 확정은 아니다(조종자 확인이 남는다). */
        val needsConfirmation: Boolean get() = best != null
    }

    /**
     * 현장 목록에서 GPS 후보를 고른다.
     *
     * @param sites 서버에서 내려받은 현장 목록. 좌표 필드가 없는 현장은 건너뛴다.
     * @param position 현재 위치. null이면 후보를 만들지 않는다.
     * @param maxCandidates 제시할 최대 후보 수.
     */
    fun suggest(
        sites: List<ObjectNode>,
        position: Position?,
        maxCandidates: Int = 3,
    ): Suggestion {
        if (position == null || position.source == PositionSource.UNKNOWN) {
            return Suggestion(emptyList(), null)
        }
        val candidates = sites.mapNotNull { site ->
            val siteId = site.get("site_id")?.asText() ?: return@mapNotNull null
            val lat = site.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
                ?: return@mapNotNull null
            val lon = site.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
                ?: return@mapNotNull null
            val radius = site.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
                ?: return@mapNotNull null
            val distance = haversineMeters(position.lat, position.lon, lat, lon)
            // 등록 반경 밖이면 후보가 아니다. 현장 경계 대신 쓰지 않는다.
            if (distance > radius) return@mapNotNull null
            Candidate(
                siteId = siteId,
                siteName = site.get("name")?.asText() ?: "현장 ${siteId.take(8)}",
                centerLat = lat,
                centerLon = lon,
                radiusM = radius,
                distanceM = distance,
            )
        }.sortedBy { it.distanceM }.take(maxCandidates)
        return Suggestion(candidates, position)
    }

    /** 대권 거리(m). */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6_371_008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLon = kotlin.math.sin(dLon / 2)
        val a = sinLat * sinLat +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            sinLon * sinLon
        return 2 * earthRadiusM * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(a)))
    }

    /** 카드에 표시할 한 줄. 후보가 없으면 "선택 필요"를 명시한다. */
    fun headline(suggestion: Suggestion): String {
        val best = suggestion.best ?: return "현장 선택 필요 — GPS 기준 후보 없음"
        val source = when (suggestion.positionUsed?.source) {
            PositionSource.AIRCRAFT -> "기체 위치"
            PositionSource.RC_GNSS -> "RC GNSS"
            else -> "위치 미확인"
        }
        return "$source 기준 추천 · ${best.siteName} · ${formatDistance(best.distanceM)}"
    }

    /** 후보가 여러 개일 때 쓰는 보조 줄. */
    fun alternativesLine(suggestion: Suggestion): String? {
        if (suggestion.candidates.size < 2) return null
        return "다른 후보 " + suggestion.candidates.drop(1).joinToString(" · ") {
            "${it.siteName}(${formatDistance(it.distanceM)})"
        }
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000.0) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)
}
