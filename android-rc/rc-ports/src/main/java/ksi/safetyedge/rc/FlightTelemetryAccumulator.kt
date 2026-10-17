package ksi.safetyedge.rc

/**
 * Per-field flight-telemetry freshness accumulator (pure Kotlin, read-only;
 * no Android/MSDK imports).
 *
 * The operations contract (docs/plans/2026-10-17-rc-operations-screen-contract.md
 * §4·§5) requires four distinguishable states per field — 미수신 / 유효 /
 * 지연 / 미지원 — and forbids presenting the last confirmed value as the
 * current state. MSDK reports each key independently and at different rates,
 * so freshness is tracked per field, never per snapshot.
 *
 * - A report makes only its own field fresh; it never clears another field's
 *   staleness.
 * - [FieldState.STALE] keeps the last confirmed value for display but keeps
 *   its age ([ageMs]) so the UI can say "지연 N초" instead of implying
 *   "현재".
 * - [markUnsupported] marks a capability claim; it outranks *missing* data
 *   but a real report supersedes it (evidence beats the claim) and clears
 *   the mark silently.
 * - [snapshot] carries values only; `timestampMs` is the newest field
 *   receipt ("last update at"), NOT a per-field freshness claim — read
 *   [fieldState] per field. `positionFix` stays null: no documented MSDK key
 *   maps to a fix decision without inventing thresholds (결정 필요 — 실기
 *   확인).
 * - All timestamps are RC monotonic receipt times passed in by the caller.
 *
 * Lives in rc-ports so the device source (`rc-msdk` `MsdkFlightTelemetrySource`)
 * and the bench double ([FakeFlightTelemetrySource]) share it.
 */
class FlightTelemetryAccumulator(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {

    init {
        require(staleAfterMs > 0) { "staleAfterMs must be positive" }
    }

    enum class Field {
        ALTITUDE, HOME_DISTANCE, HORIZONTAL_SPEED, VERTICAL_SPEED,
        HEADING, BATTERY, FLIGHT_MODE,
    }

    enum class FieldState { NEVER_RECEIVED, FRESH, STALE, UNSUPPORTED }

    private val numberFields = setOf(
        Field.ALTITUDE, Field.HOME_DISTANCE, Field.HORIZONTAL_SPEED,
        Field.VERTICAL_SPEED, Field.HEADING, Field.BATTERY,
    )

    private val numbers = HashMap<Field, Pair<Double, Long>>()
    private var flightMode: Pair<String, Long>? = null
    private val unsupported = HashSet<Field>()

    /** Records a numeric report. A real report supersedes an unsupported mark. */
    @Synchronized
    fun onNumber(field: Field, value: Double, atMs: Long) {
        require(field in numberFields) { "$field is not a numeric field" }
        unsupported.remove(field)
        numbers[field] = value to atMs
    }

    /** Records a flight-mode report (raw SDK mode name; display mapping is deferred). */
    @Synchronized
    fun onFlightMode(mode: String, atMs: Long) {
        require(mode.isNotBlank()) { "flight mode must not be blank" }
        unsupported.remove(Field.FLIGHT_MODE)
        flightMode = mode to atMs
    }

    /**
     * Marks the field unsupported for this aircraft/RC/firmware combination.
     * Only meaningful while no reading exists; see the class KDoc for the
     * supersede rule.
     */
    @Synchronized
    fun markUnsupported(field: Field) {
        unsupported.add(field)
    }

    @Synchronized
    fun fieldState(field: Field, nowMs: Long): FieldState {
        if (field in unsupported) return FieldState.UNSUPPORTED
        val at = receiptOf(field) ?: return FieldState.NEVER_RECEIVED
        return if (age(nowMs, at) <= staleAfterMs) FieldState.FRESH else FieldState.STALE
    }

    /** Age of the last confirmed value, or null when never received/unsupported. */
    @Synchronized
    fun ageMs(field: Field, nowMs: Long): Long? {
        if (field in unsupported) return null
        val at = receiptOf(field) ?: return null
        return age(nowMs, at)
    }

    @Synchronized
    fun hasAnyReading(): Boolean = numbers.isNotEmpty() || flightMode != null

    /** Last confirmed values; see the class KDoc for the timestamp semantics. */
    @Synchronized
    fun snapshot(): Snapshot {
        val newest = (numbers.values.map { it.second } + listOfNotNull(flightMode?.second))
            .maxOrNull()
        return Snapshot(
            altitudeM = numbers[Field.ALTITUDE]?.first,
            altitudeRef = ALTITUDE_REF,
            homeDistanceM = numbers[Field.HOME_DISTANCE]?.first,
            horizontalSpeedMs = numbers[Field.HORIZONTAL_SPEED]?.first,
            verticalSpeedMs = numbers[Field.VERTICAL_SPEED]?.first,
            headingDeg = numbers[Field.HEADING]?.first,
            batteryPct = numbers[Field.BATTERY]?.first,
            flightMode = flightMode?.first,
            positionFix = null,
            timestampMs = newest,
        )
    }

    private fun receiptOf(field: Field): Long? = when (field) {
        Field.FLIGHT_MODE -> flightMode?.second
        else -> numbers[field]?.second
    }

    private fun age(nowMs: Long, atMs: Long): Long = (nowMs - atMs).coerceAtLeast(0)

    companion object {
        /**
         * Default freshness window for display purposes only — NOT a safety
         * threshold; tune per data source on device. Matches the existing
         * FlightTelemetryModel default (3 s).
         */
        const val DEFAULT_STALE_AFTER_MS = 3_000L

        /** Source label for the aircraft-reported altitude baseline. */
        const val ALTITUDE_REF = "기체계"
    }
}
