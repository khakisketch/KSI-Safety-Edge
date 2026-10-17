package ksi.safetyedge.rc

/**
 * Flavor-agnostic seam for the read-only flight-telemetry source
 * (operations contract v1 §5): the device flavor attaches the MSDK
 * KeyManager source (`rc-msdk` `MsdkFlightTelemetrySource`), the bench
 * flavor attaches [FakeFlightTelemetrySource]. The main source set never
 * knows which implementation is attached — same pattern as [DroneLink].
 *
 * [onUpdate] is invoked after any field update — off the UI thread (SDK
 * callback thread on device); the UI must post to its own thread and
 * re-read [accumulator] states.
 *
 * Boundary: read-only. No flight commands, no authority requests. Freshness
 * is per field (미수신/유효/지연/미지원); the last confirmed value is never
 * reported as the current state.
 */
interface FlightTelemetrySource {
    /** Per-field values + 4-state freshness. */
    val accumulator: FlightTelemetryAccumulator

    fun start(onUpdate: () -> Unit)

    fun stop()
}

/**
 * Bench double: scripted telemetry for UI development and host tests — no
 * SDK. Values are fed explicitly with caller-provided receipt times;
 * [feedNumber]/[feedMode]/[markUnsupported] notify the update callback the
 * same way the device source does.
 */
class FakeFlightTelemetrySource : FlightTelemetrySource {

    override val accumulator = FlightTelemetryAccumulator()

    private var onUpdate: (() -> Unit)? = null

    @Volatile
    var running: Boolean = false
        private set

    override fun start(onUpdate: () -> Unit) {
        this.onUpdate = onUpdate
        running = true
    }

    override fun stop() {
        onUpdate = null
        running = false
    }

    fun feedNumber(field: FlightTelemetryAccumulator.Field, value: Double, atMs: Long) {
        check(running) { "source not started" }
        accumulator.onNumber(field, value, atMs)
        onUpdate?.invoke()
    }

    fun feedMode(mode: String, atMs: Long) {
        check(running) { "source not started" }
        accumulator.onFlightMode(mode, atMs)
        onUpdate?.invoke()
    }

    fun markUnsupported(field: FlightTelemetryAccumulator.Field) {
        check(running) { "source not started" }
        accumulator.markUnsupported(field)
        onUpdate?.invoke()
    }
}
