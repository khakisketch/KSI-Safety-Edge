package ksi.safetyedge.rc

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure presentation helpers for [FlightHudView]. All input is the already
 * formatted, read-only telemetry text produced by MainActivity; this model
 * never invents a value or treats an unknown/stale field as current.
 */
internal object FlightHudModel {

    /** A displayed value with its latency suffix removed, if present. */
    data class Value(val text: String, val stale: String?)

    /** A compass tape position: normalized degrees and its display label. */
    data class TapeLabel(val degrees: Double, val text: String)

    /**
     * The telemetry formatter appends "·지연N초" to a stale field. Keep the
     * suffix for the warning badge but do not let it widen the compact HUD
     * value cells.
     */
    fun splitStale(raw: String): Value {
        val marker = raw.indexOf("·지연")
        if (marker < 0) return Value(raw, null)
        return Value(raw.substring(0, marker), raw.substring(marker + 1))
    }

    /** Extract "-0.9" from "-0.9°" or "-0.9°·지연6초"; null for 미수신. */
    fun headingDegrees(raw: String): Double? =
        Regex("(-?\\d+(?:\\.\\d+)?)\\s*°")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()

    fun normalizeHeading(degrees: Double): Double {
        val normalized = degrees % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    /**
     * N/E/S/W for headings within 1.5° of a cardinal, otherwise a zero-padded
     * 3-digit label. The tolerance matches the approved tape around -0.9°,
     * where the center is shown as N while the exact value remains in the
     * value row/accessibility description.
     */
    fun tapeLabel(degrees: Double): String {
        val normalized = normalizeHeading(degrees)
        val rounded = normalized.roundToInt() % 360
        return when {
            angularDistance(normalized, 0.0) <= 1.5 -> "N"
            angularDistance(normalized, 90.0) <= 1.5 -> "E"
            angularDistance(normalized, 180.0) <= 1.5 -> "S"
            angularDistance(normalized, 270.0) <= 1.5 -> "W"
            else -> String.format(Locale.ROOT, "%03d", rounded)
        }
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val distance = abs(a - b) % 360.0
        return min(distance, 360.0 - distance)
    }

    /**
     * Quantize the tape center to 5° so the labels stay on the round compass
     * grid (300/330/N/030/060) even when the exact heading is 359.2°. The
     * exact heading remains in the value row and accessibility description.
     */
    fun tapeCenter(centerDegrees: Double?): Double {
        if (centerDegrees == null) return 0.0
        return normalizeHeading((centerDegrees / 5.0).roundToInt() * 5.0)
    }

    /**
     * Labels around a heading. A null heading still returns a neutral
     * reference tape; the view must not draw a current-heading marker in
     * that case.
     */
    fun tapeLabels(centerDegrees: Double?, offsets: List<Int>): List<TapeLabel> {
        val center = tapeCenter(centerDegrees)
        return offsets.map { offset ->
            val degrees = normalizeHeading(center + offset)
            TapeLabel(degrees, tapeLabel(degrees))
        }
    }

    /**
     * One honest latency warning: name the first stale field rather than
     * claiming every value is delayed. Full raw values remain in the HUD's
     * accessibility description.
     */
    fun staleBadge(fields: List<Pair<String, String?>>): String? {
        val (label, stale) = fields.firstOrNull { it.second != null } ?: return null
        return "$label ${stale!!.replace("지연", "지연 ").trim()}"
    }
}
