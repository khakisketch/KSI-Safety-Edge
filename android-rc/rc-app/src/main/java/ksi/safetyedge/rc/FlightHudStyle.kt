package ksi.safetyedge.rc

/**
 * Display-only flight HUD presentation. Changing this preference changes only
 * how the read-only telemetry is drawn on the video surface; it never changes
 * command, recording, camera or session state.
 *
 * [BARE], [SCALE] and [RETICLE] are the H1/H2/H3 proposals approved for
 * field comparison. The stored key is the enum name; unknown/absent values
 * fall back to the recommended default so an older install still opens.
 */
enum class FlightHudStyle {
    BARE,
    SCALE,
    RETICLE;

    val label: String
        get() = when (this) {
            BARE -> "H1 텍스트"
            SCALE -> "H2 계기선"
            RETICLE -> "H3 링"
        }

    companion object {
        val DEFAULT: FlightHudStyle = SCALE

        fun fromKey(key: String?): FlightHudStyle =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
