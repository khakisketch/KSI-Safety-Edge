package ksi.msdk

import android.content.Context
import android.os.SystemClock
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import ksi.safetyedge.rc.FlightTelemetryAccumulator
import ksi.safetyedge.rc.FlightTelemetrySource

/**
 * Read-only MSDK v5 flight telemetry source (DEVICE GATE) implementing the
 * rc-ports [FlightTelemetrySource] seam.
 *
 * Scope (2026-10-17): report conversion only — no flight commands, no
 * authority requests. Key names/types were checked against the official
 * 5.18.0 API docs (`dev-sdk-main/Docs/Android_API/en/Components/IKeyManager`)
 * and the provided stub jar (`javap`; e.g. listener removal is
 * `KeyManager.cancelListen(holder)`); the support matrix lives in
 * `docs/verification/a9-msdk-integration-plan.md` §주 운용 앱 텔레메트리 소스.
 *
 * DEVICE GATE — everything below compiles against the 5.18.0 stubs; real
 * callbacks, the altitude baseline, the multi-control authority value
 * structure and single-RC reporting are verified on the RC ground session.
 * Nothing here claims device verification.
 *
 * Wiring follows [MsdkDroneLink]: the key gate comes first
 * ([DjiSdkBridge.appKeyPresent]) and SDK objects are only created once the
 * bridge reports REGISTERED — no SDK listener object exists before that.
 */
class MsdkFlightTelemetrySource(
    private val context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : FlightTelemetrySource {

    /**
     * Raw read-only evidence that is NOT part of the frozen telemetry
     * Snapshot. Values are SDK reports (or null); authority-owner is kept as
     * a raw string until the value structure is verified on device. Stick
     * positions are deliberately not captured yet: no threshold mapping is
     * decided (물리 입력 → 제어권 매핑은 별도 결정).
     */
    data class Evidence(
        val isFlying: Boolean? = null,
        val isFlyingAtMs: Long? = null,
        val failsafeActive: Boolean? = null,
        val failsafeAtMs: Long? = null,
        val gpsSignalLevel: String? = null,
        val gpsSignalLevelAtMs: Long? = null,
        val gpsSatelliteCount: Int? = null,
        val gpsSatelliteCountAtMs: Long? = null,
        val multiControlSupported: Boolean? = null,
        val multiControlSupportedAtMs: Long? = null,
        val authorityOwnerRaw: String? = null,
        val authorityOwnerAtMs: Long? = null,
    )

    override val accumulator = FlightTelemetryAccumulator()

    private val bridge = DjiSdkBridge(context)

    @Volatile
    private var onUpdate: (() -> Unit)? = null

    @Volatile
    private var currentEvidence = Evidence()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var listening: Boolean = false
        private set

    private var homeLocation: Pair<Double, Double>? = null
    private var aircraftLocation: Pair<Double, Double>? = null

    fun evidence(): Evidence = currentEvidence

    /** Key gate first; listeners are registered only once REGISTERED. */
    override fun start(onUpdate: () -> Unit) {
        if (listening) return
        this.onUpdate = onUpdate
        if (!bridge.appKeyPresent()) {
            lastError = "${DjiSdkBridge.API_KEY_META} missing/blank — SDK not started"
            return
        }
        bridge.start { state ->
            when (state) {
                DjiBridgeState.REGISTERED -> listenAll()
                DjiBridgeState.FAILED, DjiBridgeState.KEY_MISSING -> lastError = bridge.lastError
                else -> Unit // INITIALIZING: wait for the callback
            }
        }
    }

    override fun stop() {
        if (!listening) return
        try {
            KeyManager.getInstance().cancelListen(this)
        } catch (_: LinkageError) {
            // SDK runtime missing (Helper not installed): best-effort.
        } catch (_: Exception) {
            // ignore — state is forced below regardless
        }
        listening = false
    }

    @Synchronized
    private fun listenAll() {
        if (listening) return
        try {
            val km = KeyManager.getInstance()

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { loc ->
                        val at = clock()
                        accumulator.onNumber(
                            FlightTelemetryAccumulator.Field.ALTITUDE,
                            loc.altitude.toDouble(), at,
                        )
                        aircraftLocation = loc.latitude.toDouble() to loc.longitude.toDouble()
                        recomputeHomeDistance(at)
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { loc ->
                        homeLocation = loc.latitude.toDouble() to loc.longitude.toDouble()
                        recomputeHomeDistance(clock())
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { v ->
                        val at = clock()
                        accumulator.onNumber(
                            FlightTelemetryAccumulator.Field.HORIZONTAL_SPEED,
                            horizontalSpeedMs(v.x.toDouble(), v.y.toDouble()), at,
                        )
                        accumulator.onNumber(
                            FlightTelemetryAccumulator.Field.VERTICAL_SPEED,
                            verticalSpeedMs(v.z.toDouble()), at,
                        )
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyCompassHeading), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { heading ->
                        accumulator.onNumber(
                            FlightTelemetryAccumulator.Field.HEADING,
                            heading.toDouble(), clock(),
                        )
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { percent ->
                        accumulator.onNumber(
                            FlightTelemetryAccumulator.Field.BATTERY,
                            percent.toDouble(), clock(),
                        )
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyFlightMode), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { mode ->
                        accumulator.onFlightMode(mode.name, clock())
                        emit()
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyIsFlying), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { flying ->
                        updateEvidence { it.copy(isFlying = flying, isFlyingAtMs = clock()) }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyIsFailSafe), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { failsafe ->
                        updateEvidence { it.copy(failsafeActive = failsafe, failsafeAtMs = clock()) }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { level ->
                        updateEvidence { it.copy(gpsSignalLevel = level.name, gpsSignalLevelAtMs = clock()) }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { count ->
                        updateEvidence { it.copy(gpsSatelliteCount = count, gpsSatelliteCountAtMs = clock()) }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(RemoteControllerKey.KeyMultiControlIsSupported), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { supported ->
                        updateEvidence {
                            it.copy(multiControlSupported = supported, multiControlSupportedAtMs = clock())
                        }
                    }
                },
            )

            km.listen(
                KeyTools.createKey(RemoteControllerKey.KeyMultiControlFlightControlAuthorityOwner), this,
                CommonCallbacks.KeyListener { _, value ->
                    value?.let { owner ->
                        // Raw string only: the value structure and single-RC
                        // reporting are DEVICE GATE facts (matrix §).
                        updateEvidence {
                            it.copy(authorityOwnerRaw = owner.toString(), authorityOwnerAtMs = clock())
                        }
                    }
                },
            )

            listening = true
        } catch (e: LinkageError) {
            lastError = "telemetry listeners unavailable: ${e.message ?: e.javaClass.name}"
        } catch (e: Exception) {
            lastError = "telemetry listeners failed: ${e.message ?: e.javaClass.name}"
        }
    }

    /** Derived from two SDK-reported positions; no direct home-distance key exists. */
    private fun recomputeHomeDistance(atMs: Long) {
        val home = homeLocation ?: return
        val aircraft = aircraftLocation ?: return
        accumulator.onNumber(
            FlightTelemetryAccumulator.Field.HOME_DISTANCE,
            GeoDistance.haversineMeters(aircraft.first, aircraft.second, home.first, home.second),
            atMs,
        )
    }

    @Synchronized
    private fun updateEvidence(transform: (Evidence) -> Evidence) {
        val next = transform(currentEvidence)
        currentEvidence = next
        onUpdate?.invoke()
    }

    private fun emit() = updateEvidence { it }
}

/** Great-circle distance; used only for the derived home-distance display value. */
internal object GeoDistance {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLon = kotlin.math.sin(dLon / 2)
        val a = sinLat * sinLat +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            sinLon * sinLon
        return 2 * EARTH_RADIUS_M * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(a)))
    }
}

/** Velocity3D is NED (x=north, y=east, z=down); display convention: up is positive. */
internal fun horizontalSpeedMs(northMs: Double, eastMs: Double): Double =
    kotlin.math.hypot(northMs, eastMs)

internal fun verticalSpeedMs(downMs: Double): Double = -downMs
