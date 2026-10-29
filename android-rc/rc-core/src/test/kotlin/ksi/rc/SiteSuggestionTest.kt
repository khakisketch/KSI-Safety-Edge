package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * D2 — GPS 현장 추천(선택 기능) 회귀 시험.
 *
 * 이 기능은 **선택 기능**이며 구현되어 있지 않아도 모든 것이 정상 동작한다.
 * 이 시험이 고정하는 것:
 * ① 자동 확정을 하지 않는다 (후보 제시까지만).
 * ② 위치가 없으면 후보를 만들지 않는다.
 * ③ 좌표가 없는 현장·등록 반경 밖의 현장은 후보가 아니다.
 * ④ 위치 출처(기체/RC GNSS)를 구분한다.
 * ⑤ 위험구역 다각형을 현장 경계로 쓰지 않는다 (중심점+반경만 쓴다).
 */
class SiteSuggestionTest {

    private fun site(
        id: String,
        name: String,
        lat: Double? = 37.0,
        lon: Double? = 127.0,
        radius: Double? = 500.0,
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("site_id", id)
        node.put("name", name)
        if (lat != null) node.put("center_lat", lat) else node.putNull("center_lat")
        if (lon != null) node.put("center_lon", lon) else node.putNull("center_lon")
        if (radius != null) node.put("radius_m", radius) else node.putNull("radius_m")
        return node
    }

    @Test
    fun a_site_inside_its_registered_radius_becomes_a_candidate() {
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터")),
            position = SiteSuggestion.Position(
                37.001, 127.001,
                SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(1, suggestion.candidates.size)
        assertEquals("서부 물류센터", suggestion.best!!.siteName)
        assertEquals(true, suggestion.needsConfirmation)
    }

    @Test
    fun a_site_outside_its_registered_radius_is_not_a_candidate() {
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터", radius = 100.0)),
            position = SiteSuggestion.Position(
                37.05, 127.05, // 수 km 떨어짐
                SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(true, suggestion.candidates.isEmpty(), "반경 밖은 후보가 아니다")
        assertNull(suggestion.best)
    }

    @Test
    fun a_site_without_coordinates_is_never_a_candidate() {
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(site("s1", "좌표 없는 현장", lat = null, lon = null, radius = null)),
            position = SiteSuggestion.Position(
                37.0, 127.0, SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(true, suggestion.candidates.isEmpty())
    }

    @Test
    fun no_position_means_no_candidates_and_no_pretense() {
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터")),
            position = null,
        )
        assertEquals(true, suggestion.candidates.isEmpty())
        assertNull(suggestion.positionUsed)
        assertEquals(
            true,
            SiteSuggestion.headline(suggestion).contains("현장 선택 필요"),
            "후보가 없으면 '선택 필요'라고 말해야 한다",
        )
    }

    @Test
    fun an_unknown_position_source_produces_no_candidates() {
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터")),
            position = SiteSuggestion.Position(
                37.0, 127.0, SiteSuggestion.PositionSource.UNKNOWN,
            ),
        )
        assertEquals(true, suggestion.candidates.isEmpty())
    }

    @Test
    fun candidates_are_ordered_by_distance() {
        val near = site("s1", "가까운 현장", lat = 37.0001, lon = 127.0001, radius = 2000.0)
        val far = site("s2", "먼 현장", lat = 37.0100, lon = 127.0100, radius = 2000.0)
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(far, near),
            position = SiteSuggestion.Position(
                37.0, 127.0, SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(2, suggestion.candidates.size)
        assertEquals("가까운 현장", suggestion.best!!.siteName)
        assertEquals(
            true,
            suggestion.candidates[0].distanceM < suggestion.candidates[1].distanceM,
            "가까운 곳이 먼저",
        )
    }

    @Test
    fun the_position_source_is_named_in_the_headline() {
        val aircraft = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터")),
            position = SiteSuggestion.Position(
                37.001, 127.001, SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(true, SiteSuggestion.headline(aircraft).contains("기체 위치"))

        val rcGnss = SiteSuggestion.suggest(
            sites = listOf(site("s1", "서부 물류센터")),
            position = SiteSuggestion.Position(
                37.001, 127.001, SiteSuggestion.PositionSource.RC_GNSS,
            ),
        )
        assertEquals(true, SiteSuggestion.headline(rcGnss).contains("RC GNSS"))
    }

    @Test
    fun the_candidate_count_is_bounded() {
        val many = (1..10).map { site("s$it", "현장 $it", lat = 37.0, lon = 127.0, radius = 5000.0) }
        val suggestion = SiteSuggestion.suggest(
            sites = many,
            position = SiteSuggestion.Position(
                37.0, 127.0, SiteSuggestion.PositionSource.AIRCRAFT,
            ),
            maxCandidates = 3,
        )
        assertEquals(3, suggestion.candidates.size)
    }

    @Test
    fun haversine_is_zero_for_the_same_point_and_grows_with_distance() {
        assertEquals(0.0, SiteSuggestion.haversineMeters(37.0, 127.0, 37.0, 127.0), 1e-6)
        val near = SiteSuggestion.haversineMeters(37.0, 127.0, 37.001, 127.0)
        val far = SiteSuggestion.haversineMeters(37.0, 127.0, 38.0, 127.0)
        assertEquals(true, near > 0.0)
        assertEquals(true, far > near)
    }

    @Test
    fun a_missing_site_id_is_skipped_not_crashed() {
        val broken = Mop.json.createObjectNode()
        broken.put("name", "id 없는 현장")
        broken.put("center_lat", 37.0)
        broken.put("center_lon", 127.0)
        broken.put("radius_m", 100.0)
        val suggestion = SiteSuggestion.suggest(
            sites = listOf(broken),
            position = SiteSuggestion.Position(
                37.0, 127.0, SiteSuggestion.PositionSource.AIRCRAFT,
            ),
        )
        assertEquals(true, suggestion.candidates.isEmpty())
        assertFalse(suggestion.needsConfirmation)
    }
}
