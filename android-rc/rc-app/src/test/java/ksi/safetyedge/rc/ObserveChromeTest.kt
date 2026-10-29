package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host tests for the Round48 v2 observe chrome contract: the observe page
 * always reclaims the rail (never relies on focus-mode opt-in), the header
 * menu entry is visible exactly when observe shows its header, and the
 * focus-mode flag only hides the header for full video.
 */
class ObserveChromeTest {

    @Test
    fun normal_observe_reclaims_rail_with_menu() {
        assertEquals(
            ObserveChrome.Chrome(
                railVisible = false,
                headerVisible = true,
                menuVisible = true,
            ),
            ObserveChrome.resolve(observeVisible = true, collapsed = false),
        )
    }

    @Test
    fun collapsed_observe_hides_header_but_keeps_rail_reclaimed() {
        assertEquals(
            ObserveChrome.Chrome(
                railVisible = false,
                headerVisible = false,
                menuVisible = false,
            ),
            ObserveChrome.resolve(observeVisible = true, collapsed = true),
        )
    }

    @Test
    fun other_pages_keep_rail_and_hide_menu() {
        assertEquals(
            ObserveChrome.Chrome(
                railVisible = true,
                headerVisible = true,
                menuVisible = false,
            ),
            ObserveChrome.resolve(observeVisible = false, collapsed = false),
        )
        assertEquals(
            ObserveChrome.Chrome(
                railVisible = true,
                headerVisible = true,
                menuVisible = false,
            ),
            ObserveChrome.resolve(observeVisible = false, collapsed = true),
        )
    }
}
