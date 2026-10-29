package ksi.msdk

import android.os.Handler
import android.os.Looper
import ksi.safetyedge.rc.AircraftCommandPort
import ksi.safetyedge.rc.AircraftCommandPort.Capabilities
import ksi.safetyedge.rc.AircraftCommandPort.Command
import ksi.safetyedge.rc.AircraftCommandPort.Listener
import ksi.safetyedge.rc.AircraftCommandPort.Outcome
import ksi.safetyedge.rc.AircraftCommandPort.Request
import ksi.safetyedge.rc.AircraftCommandPort.Result
import ksi.safetyedge.rc.AircraftCommandPort.Support
import ksi.safetyedge.rc.AircraftConnectionProvider
import ksi.safetyedge.rc.CommandGate
import ksi.safetyedge.rc.Owner
import java.util.concurrent.ConcurrentHashMap

/**
 * MSDK-backed [AircraftCommandPort] (A9): the primary-operation RC app sends
 * operator-initiated flight/camera requests through the real v5 KeyManager
 * ([KeyManagerCommandGateway]); the AI stack can never reach this path.
 *
 * Order per request (matches [MsdkDroneLink]/[DjiSdkBridge] house order):
 * 1. [CommandGate.evaluate] — refused requests NEVER reach the SDK and
 *    report [Outcome.NOT_PERMITTED] with the gate's reason. Camera
 *    availability comes from the live [AircraftConnectionProvider] read,
 *    never from a cached value: unknown is refused exactly like an
 *    explicit disconnect.
 * 2. SDK readiness — not initialised/registered (or an exception while
 *    asking) reports [Outcome.UNKNOWN];
 * 3. product absence — explicitly no product reports [Outcome.UNAVAILABLE];
 * 4. the single SDK call — success means the REQUEST was delivered, never
 *    that the aircraft performed the action ([Outcome.DELIVERED] always
 *    carries "실행 결과는 기체 상태로 확인"); an SDK refusal reports
 *    [Outcome.REJECTED] with the SDK text verbatim.
 *
 * Threading: `request()` never blocks; every result is delivered on the
 * main/UI thread via [poster] (default: main-Looper Handler, created lazily
 * so host tests never load it).
 *
 * DEVICE GATE: command→key mapping is verified against the 5.18.0 stubs
 * (`javap`) and host-tested; real delivery, refusal text, zoom behaviour,
 * gimbal frame/duration and mode-switch necessity are verified on the RC
 * ground session (A9-4). `capabilities` therefore defaults every command to
 * [Support.UNVERIFIED]; [setSupport] (or the constructor map) records device
 * evidence later with no code change.
 */
class MsdkAircraftCommand(
    private val gateInputs: () -> GateInputs = { GateInputs(Owner.UNKNOWN, false, false, null) },
    private val gateway: MsdkCommandGateway = KeyManagerCommandGateway(),
    /**
     * Live aircraft-availability source for [CommandGate] (default: the
     * real SDK `KeyConnection` read). Read fresh on every request, never
     * cached; a read failure degrades to unknown, which the gate refuses.
     * Host tests and the bench inject lambdas — no SDK runtime is loaded.
     */
    private val connection: AircraftConnectionProvider = SdkAircraftConnection,
    supportOverrides: Map<Command, Support> = emptyMap(),
    private val poster: ((() -> Unit) -> Unit) = MainThreadPoster::post,
    private val zoomPulseMs: Long = ZOOM_PULSE_MS,
    private val zoomStopScheduler: (Long, () -> Unit) -> Unit = MainThreadPoster::postDelayed,
) : AircraftCommandPort {

    /** Observed RC-side preconditions feeding [CommandGate]. */
    data class GateInputs(
        val owner: Owner,
        val ownerConfirmed: Boolean,
        val linkUp: Boolean,
        val aircraftConnected: Boolean?,
    )

    private val overrides = ConcurrentHashMap<Command, Support>(supportOverrides)

    override val capabilities: Capabilities
        get() = Capabilities(
            support = Command.values().associateWith { overrides[it] ?: Support.UNVERIFIED },
            note = "A9-4 실기 검증 전 · 전 명령 UNVERIFIED",
        )

    /** Records device evidence (A9-4) with no code change. */
    fun setSupport(command: Command, support: Support) {
        overrides[command] = support
    }

    /** Last best-effort failure (zoom-stop follow-up); diagnostics only. */
    @Volatile
    var lastError: String? = null
        private set

    override fun request(request: Request, listener: Listener) {
        val inputs = try {
            gateInputs()
        } catch (e: Exception) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "RC 상태 확인 불가: ${reason(e)}"))
            return
        }
        // Explicitly confirmed current availability only: the provider is
        // re-read per request (never a cached value, never the MOP/AI
        // state), and any read failure degrades to unknown — refused.
        val liveConnected = try {
            connection.readAircraftConnected()
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
        val decision = CommandGate.evaluate(
            request.command,
            inputs.owner,
            inputs.ownerConfirmed,
            inputs.linkUp,
            liveConnected,
            capabilities,
        )
        if (!decision.allowed) {
            // Gate refusal: the SDK is never touched.
            post(listener, Result(request.command, Outcome.NOT_PERMITTED, decision.detail))
            return
        }
        val ready = try {
            gateway.isReady()
        } catch (e: LinkageError) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 상태 확인 불가: ${reason(e)}"))
            return
        } catch (e: Exception) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 상태 확인 불가: ${reason(e)}"))
            return
        }
        if (!ready) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 미초기화/미등록 · 명령 미전송"))
            return
        }
        val product = try {
            gateway.hasProduct()
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
        if (inputs.aircraftConnected == false || liveConnected == false || product == false) {
            post(listener, Result(request.command, Outcome.UNAVAILABLE, "기체 연결 없음 · 명령 미전송"))
            return
        }
        try {
            when (request.command) {
                Command.TAKEOFF -> gateway.takeoff { post(listener, classify(request, it)) }
                Command.LAND -> gateway.land { post(listener, classify(request, it)) }
                Command.RETURN_HOME -> gateway.returnHome { post(listener, classify(request, it)) }
                Command.GIMBAL_ROTATE -> gateway.rotateGimbalRelative(
                    request.magnitude?.toDouble() ?: GIMBAL_DEFAULT_PITCH_DEG,
                ) { post(listener, classify(request, it)) }
                Command.ZOOM_IN -> zoomPulse(zoomIn = true, request, listener)
                Command.ZOOM_OUT -> zoomPulse(zoomIn = false, request, listener)
                Command.PHOTO -> ensureModeThen(photo = photoModeWanted(request, default = true), request, listener) {
                    gateway.shootPhoto { post(listener, classify(request, it)) }
                }
                Command.START_RECORDING -> ensureModeThen(
                    photo = photoModeWanted(request, default = false), request, listener,
                ) {
                    gateway.startRecording { post(listener, classify(request, it)) }
                }
                Command.STOP_RECORDING -> gateway.stopRecording { post(listener, classify(request, it)) }
            }
        } catch (e: LinkageError) {
            // SDK runtime absent (Helper not installed): UNKNOWN, never a claim.
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 불가: ${reason(e)}"))
        } catch (e: Exception) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 실패: ${reason(e)}"))
        }
    }

    /**
     * Zoom is a press-and-hold on Pilot 2; the v5 key API only offers
     * start/stop continuous zoom, so ZOOM_IN/OUT is a short pulse: start,
     * then a best-effort stop after [zoomPulseMs]. DELIVERED reports the
     * START acceptance; a failed stop is recorded in [lastError] only.
     * Pulse length is DEVICE GATE (A9-4).
     */
    private fun zoomPulse(zoomIn: Boolean, request: Request, listener: Listener) {
        gateway.startContinuousZoom(zoomIn) { error ->
            if (error != null) {
                post(listener, Result(request.command, Outcome.REJECTED, "줌 요청 거절: $error"))
                return@startContinuousZoom
            }
            try {
                zoomStopScheduler(zoomPulseMs) {
                    try {
                        gateway.stopContinuousZoom { stopError ->
                            if (stopError != null) lastError = "줌 정지 실패: $stopError"
                        }
                    } catch (e: LinkageError) {
                        lastError = "줌 정지 불가: ${reason(e)}"
                    } catch (e: Exception) {
                        lastError = "줌 정지 실패: ${reason(e)}"
                    }
                }
            } catch (e: LinkageError) {
                lastError = "줌 정지 예약 불가: ${reason(e)}"
            } catch (e: Exception) {
                lastError = "줌 정지 예약 실패: ${reason(e)}"
            }
            post(listener, Result(request.command, Outcome.DELIVERED, DELIVERED_DETAIL))
        }
    }

    /**
     * Stills/video actions first select the camera mode (explicit
     * [Request.parameter] "photo"/"video" wins, else the command default),
     * then fire the action. A refused mode switch reports REJECTED and the
     * action is never fired. Mode-switch necessity is DEVICE GATE (A9-4).
     */
    private fun ensureModeThen(photo: Boolean, request: Request, listener: Listener, action: () -> Unit) {
        try {
            gateway.setCameraModePhoto(photo) { modeError ->
                if (modeError != null) {
                    post(listener, Result(request.command, Outcome.REJECTED, "카메라 모드 전환 거절: $modeError"))
                    return@setCameraModePhoto
                }
                try {
                    action()
                } catch (e: LinkageError) {
                    post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 불가: ${reason(e)}"))
                } catch (e: Exception) {
                    post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 실패: ${reason(e)}"))
                }
            }
        } catch (e: LinkageError) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 불가: ${reason(e)}"))
        } catch (e: Exception) {
            post(listener, Result(request.command, Outcome.UNKNOWN, "SDK 호출 실패: ${reason(e)}"))
        }
    }

    private fun photoModeWanted(request: Request, default: Boolean): Boolean =
        when (request.parameter?.lowercase()) {
            "photo", "picture", "single", "stills" -> true
            "video", "record", "movie" -> false
            else -> default
        }

    private fun classify(request: Request, sdkError: String?): Result =
        if (sdkError == null) {
            Result(request.command, Outcome.DELIVERED, DELIVERED_DETAIL)
        } else {
            Result(request.command, Outcome.REJECTED, "기체·펌웨어가 요청을 거절: $sdkError")
        }

    private fun post(listener: Listener, result: Result) {
        try {
            poster { listener.onResult(result) }
        } catch (_: Exception) {
            // Poster failure (no Looper on a bare thread): deliver inline so
            // the request never hangs silently.
            listener.onResult(result)
        }
    }

    private fun reason(e: Throwable): String = e.message ?: e.javaClass.name

    companion object {
        /** Delivered-request wording (matches the bench double): execution is
         *  confirmed only by reading aircraft state, never by this call. */
        const val DELIVERED_DETAIL = "요청 전달됨 · 실행 결과는 기체 상태로 확인"

        /** Default gimbal pitch nudge (deg, negative = look down) when the
         *  request carries no magnitude. DEVICE GATE — A9-4 confirms. */
        const val GIMBAL_DEFAULT_PITCH_DEG = -10.0

        /** Continuous-zoom pulse length (ms). DEVICE GATE — A9-4 confirms. */
        const val ZOOM_PULSE_MS = 600L

        /**
         * Device-ready construction for the app's `PlatformLinks` (one line,
         * no rc-app edits needed by this unit):
         *
         * ```kotlin
         * fun aircraftCommand(context: Context): AircraftCommandPort =
         *     MsdkAircraftCommand.operational()
         * ```
         *
         * Wiring, all explicit:
         * - real command gateway ([KeyManagerCommandGateway]) and the live
         *   SDK connection read ([SdkAircraftConnection]);
         * - authority stays [Owner.UNKNOWN] + unconfirmed, so flight
         *   commands remain blocked until the app supplies confirmed
         *   authority through its own [gateInputs] — connection state and
         *   stick input are never inferred as MANUAL here;
         * - link health comes from [linkUp] (default: down — flight stays
         *   blocked; camera availability never depends on it);
         * - every command stays [Support.UNVERIFIED] until A9-4 device
         *   evidence lands via [setSupport]: real delivery is NOT claimed.
         *
         * Host-safe: constructing the adapter touches no SDK runtime (both
         * defaults resolve SDK classes only when a method runs on-device).
         */
        fun operational(
            gateway: MsdkCommandGateway = KeyManagerCommandGateway(),
            connection: AircraftConnectionProvider = SdkAircraftConnection,
            linkUp: () -> Boolean = { false },
            poster: ((() -> Unit) -> Unit) = MainThreadPoster::post,
        ): MsdkAircraftCommand = MsdkAircraftCommand(
            gateInputs = {
                GateInputs(
                    owner = Owner.UNKNOWN,
                    ownerConfirmed = false,
                    linkUp = try {
                        linkUp()
                    } catch (_: Exception) {
                        false
                    },
                    aircraftConnected = null,
                )
            },
            gateway = gateway,
            connection = connection,
            poster = poster,
        )
    }
}

/** Main/UI-thread poster; the Handler is lazy so host tests never load it. */
internal object MainThreadPoster {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun post(task: () -> Unit) {
        handler.post(task)
    }

    fun postDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }
}
