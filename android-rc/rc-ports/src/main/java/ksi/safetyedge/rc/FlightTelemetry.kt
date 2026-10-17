package ksi.safetyedge.rc

/** Flight telemetry display model (pure Kotlin, no Android/MSDK/PSDK).
 *
 *  All outputs are DISPLAY indicators only, never safety judgments: OK means
 *  "freshly received", STALE means "received but old", UNKNOWN means "no usable
 *  value". A fresh display value must never be read as proof of flight safety.
 */
data class Reading(
    val value: Double?,
    val unit: String,
    /** Value origin label, e.g. "GNSS", "기체계", "미확인". */
    val source: String,
    val ageMs: Long?,
    val unknown: Boolean,
    val stale: Boolean,
)

data class Snapshot(
    val altitudeM: Double?,
    val altitudeRef: String?,
    val homeDistanceM: Double?,
    val horizontalSpeedMs: Double?,
    val verticalSpeedMs: Double?,
    val headingDeg: Double?,
    val batteryPct: Double?,
    val flightMode: String?,
    val positionFix: Boolean?,
    /** RC receipt-clock timestamp of this snapshot, null when unconfirmed. */
    val timestampMs: Long?,
)

data class DisplayItem(
    val label: String,
    val value: String,
    val state: State,
) {
    enum class State { OK, STALE, UNKNOWN }
}

object FlightTelemetryModel {
    const val UNKNOWN_LABEL = "미확인"

    fun evaluate(snapshot: Snapshot?, nowMs: Long, maxAgeMs: Long = 3000): List<DisplayItem> {
        require(maxAgeMs > 0L)
        if (snapshot == null) return unknownItems()
        val age = snapshot.timestampMs?.let { if (nowMs >= it) nowMs - it else null }
        val stale = age != null && age > maxAgeMs
        val staleSuffix = if (stale) " · 지연 ${age!! / 1_000L}초" else ""
        fun number(label: String, v: Double?, unit: String): DisplayItem {
            if (v == null || age == null) return DisplayItem(label, UNKNOWN_LABEL, DisplayItem.State.UNKNOWN)
            if (stale) return DisplayItem(label, "$v $unit$staleSuffix", DisplayItem.State.STALE)
            return DisplayItem(label, "$v $unit", DisplayItem.State.OK)
        }
        val items = ArrayList<DisplayItem>(8)
        items += number("고도", snapshot.altitudeM, "m")
        items += number("홈 거리", snapshot.homeDistanceM, "m")
        items += number("수평 속도", snapshot.horizontalSpeedMs, "m/s")
        items += number("수직 속도", snapshot.verticalSpeedMs, "m/s")
        items += number("방향", snapshot.headingDeg, "°")
        items += number("배터리", snapshot.batteryPct, "%")
        val mode = snapshot.flightMode?.takeIf { it.isNotBlank() }
        items += if (mode == null || age == null) {
            DisplayItem("비행 모드", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN)
        } else if (stale) {
            DisplayItem("비행 모드", "$mode$staleSuffix", DisplayItem.State.STALE)
        } else {
            DisplayItem("비행 모드", mode, DisplayItem.State.OK)
        }
        val fix = snapshot.positionFix
        items += if (fix == null || age == null) {
            DisplayItem("위치 고정", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN)
        } else if (stale) {
            DisplayItem("위치 고정", "${if (fix) "고정됨" else "미고정"}$staleSuffix", DisplayItem.State.STALE)
        } else {
            DisplayItem("위치 고정", if (fix) "고정됨" else "미고정", DisplayItem.State.OK)
        }
        return items
    }

    private fun unknownItems(): List<DisplayItem> = listOf(
        DisplayItem("고도", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("홈 거리", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("수평 속도", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("수직 속도", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("방향", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("배터리", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("비행 모드", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
        DisplayItem("위치 고정", UNKNOWN_LABEL, DisplayItem.State.UNKNOWN),
    )
}
