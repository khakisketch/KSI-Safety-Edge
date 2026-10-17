package ksi.safetyedge.rc

import kotlin.math.roundToInt

/**
 * Read-only telemetry strip for the cockpit top line (operations contract v1
 * §개념 구조·§4·§5; round24 topbar). Values come from
 * [FlightTelemetryAccumulator]; freshness is per field and the last
 * confirmed value is never presented as the current state ("지연 N초").
 *
 * Control authority and MOP/aircraft links are NOT accumulator fields: their
 * values pass through from the existing report sources only — nothing is
 * inferred from other flight states or operator intent here. This strip adds
 * no commands, permissions or contracts.
 */
internal object TelemetryStripPresenter {

    internal data class Cell(
        val label: String,
        val value: String,
        val severity: OperatorStatus.Severity,
        val detail: String = "",
        val description: String? = null,
    ) {
        val contentDescription: String
            get() = description ?: buildString {
                append(label).append(": ").append(value)
                if (detail.isNotBlank()) append(" · ").append(detail)
                append(".")
            }
    }

    internal data class Strip(
        val authority: Cell,
        val flightMode: Cell,
        val battery: Cell,
        val links: Cell,
        val position: Cell,
    ) {
        val cells: List<Cell>
            get() = listOf(authority, flightMode, battery, links, position)
    }

    fun present(
        accumulator: FlightTelemetryAccumulator,
        nowMs: Long,
        authorityValue: String? = null,
        mop: CockpitStatusPresenter.Fact? = null,
        aircraft: CockpitStatusPresenter.Fact? = null,
    ): Strip {
        val snapshot = accumulator.snapshot()
        return Strip(
            authority = Cell(
                label = "제어 주체",
                value = authorityValue ?: UNKNOWN,
                severity = OperatorStatus.Severity.INFO,
                detail = if (authorityValue == null) "제어권 보고 출처 미연결" else "",
            ),
            flightMode = telemetryCell(
                label = "비행 모드",
                field = FlightTelemetryAccumulator.Field.FLIGHT_MODE,
                raw = snapshot.flightMode,
                accumulator = accumulator,
                nowMs = nowMs,
            ),
            battery = telemetryCell(
                label = "배터리",
                field = FlightTelemetryAccumulator.Field.BATTERY,
                raw = snapshot.batteryPct?.let { "${it.roundToInt()}%" },
                accumulator = accumulator,
                nowMs = nowMs,
            ),
            links = linksCell(mop, aircraft),
            position = telemetryCell(
                label = "위치 · 홈",
                field = FlightTelemetryAccumulator.Field.HOME_DISTANCE,
                raw = snapshot.homeDistanceM?.let { "홈 거리 ${it.roundToInt()} m" },
                accumulator = accumulator,
                nowMs = nowMs,
            ),
        )
    }

    private fun telemetryCell(
        label: String,
        field: FlightTelemetryAccumulator.Field,
        raw: String?,
        accumulator: FlightTelemetryAccumulator,
        nowMs: Long,
    ): Cell = when (accumulator.fieldState(field, nowMs)) {
        FlightTelemetryAccumulator.FieldState.FRESH ->
            Cell(label, raw ?: NEVER, OperatorStatus.Severity.INFO)

        FlightTelemetryAccumulator.FieldState.STALE -> {
            val seconds = staleSeconds(accumulator.ageMs(field, nowMs))
            Cell(
                label = label,
                value = "${raw ?: NEVER} · 지연 ${seconds}초",
                severity = OperatorStatus.Severity.ATTENTION,
                detail = "마지막 확인값 · 현재 상태 아님",
                description = "$label: 마지막 확인 ${raw ?: NEVER}, 지연 ${seconds}초.",
            )
        }

        FlightTelemetryAccumulator.FieldState.NEVER_RECEIVED ->
            Cell(label, NEVER, OperatorStatus.Severity.INFO)

        FlightTelemetryAccumulator.FieldState.UNSUPPORTED ->
            Cell(label, UNSUPPORTED, OperatorStatus.Severity.INFO)
    }

    private fun linksCell(
        mop: CockpitStatusPresenter.Fact?,
        aircraft: CockpitStatusPresenter.Fact?,
    ): Cell = Cell(
        label = "MOP · 기체 링크",
        value = "MOP ${mop?.value ?: UNKNOWN_LINK} · 기체 ${aircraft?.value ?: UNKNOWN_LINK}",
        severity = maxSeverity(mop?.severity, aircraft?.severity),
    )

    private fun maxSeverity(
        a: OperatorStatus.Severity?,
        b: OperatorStatus.Severity?,
    ): OperatorStatus.Severity {
        fun rank(s: OperatorStatus.Severity?): Int = when (s) {
            OperatorStatus.Severity.PROBLEM -> 2
            OperatorStatus.Severity.ATTENTION -> 1
            else -> 0
        }
        return when {
            rank(a) >= rank(b) -> a ?: OperatorStatus.Severity.INFO
            else -> b ?: OperatorStatus.Severity.INFO
        }
    }

    /** Ceil so a delay never displays smaller than it is. */
    internal fun staleSeconds(ageMs: Long?): Long = ((ageMs ?: 0L) + 999L) / 1_000L

    private const val NEVER = "미수신"
    private const val UNSUPPORTED = "미지원"
    private const val UNKNOWN = "미확인"
    private const val UNKNOWN_LINK = "상태 미확인"
}
