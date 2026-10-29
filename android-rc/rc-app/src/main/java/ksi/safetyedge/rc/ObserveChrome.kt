package ksi.safetyedge.rc

/**
 * Pure nav-chrome decision for the observe cockpit (Round48 v2).
 *
 * The observe page always reclaims the persistent rail; the focus-mode flag
 * only hides the header for full video. The header menu entry is visible
 * exactly when the observe page shows its header, so every destination stays
 * reachable without the rail. No Android classes — host-testable.
 */
object ObserveChrome {

    data class Chrome(
        val railVisible: Boolean,
        val headerVisible: Boolean,
        val menuVisible: Boolean,
    )

    fun resolve(observeVisible: Boolean, collapsed: Boolean): Chrome {
        val headerVisible = !(collapsed && observeVisible)
        return Chrome(
            railVisible = !observeVisible,
            headerVisible = headerVisible,
            menuVisible = observeVisible && headerVisible,
        )
    }
}
