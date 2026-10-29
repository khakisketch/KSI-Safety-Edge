package ksi.msdk

import android.content.Context
import dji.sdk.keyvalue.value.mop.PipelineDeviceType
import dji.sdk.keyvalue.value.mop.PipelineState
import dji.sdk.keyvalue.value.mop.TransmissionControlType
import dji.v5.common.error.IDJIError
import dji.v5.manager.mop.Pipeline
import dji.v5.manager.mop.PipelineConnectionListener
import dji.v5.manager.mop.PipelineManager
import ksi.safetyedge.rc.DroneLink
import ksi.safetyedge.rc.MopFrameWriter
import ksi.safetyedge.rc.MopReassembler

/**
 * [DroneLink] over the DJI SDK Interconnection (MOP pipeline) to the
 * Manifold payload (A9).
 *
 * Facts verified against the official 5.18.0 API surface (Maven Central):
 * - `PipelineManager.getInstance().connectPipeline(id, PAYLOAD, STABLE)`
 * - connection callbacks deliver `Map<pipelineId, Pipeline>`
 * - `Pipeline.writeData / readData(byte[])` carry raw bytes (DataResult
 *   exposes length + error); the v5 API has no push data callback, so a
 *   reader thread polls readData.
 *
 * DEVICE GATE: registration, pipeline connect and the real read/write loop
 * are verified on the RC ground session; this class compiles and packages
 * now without hardware. Framing/reassembly is [MopFrameCodec], host-tested.
 * A9-2 transport rules (both ends must agree — the C++ link implements the
 * same policy):
 * - concurrent senders are serialised ([MopFrameWriter]);
 * - a partial send tears the link down (already-delivered bytes cannot be
 *   recalled; reconnect starts a fresh session and the envelope layer
 *   resends the SAME msg_id);
 * - a framing desync (chunk length beyond the wire contract) discards the
 *   reassembler state and drops the link — there is no resync marker;
 * - connect()/disconnect() always reset the reassembler (no stale partial
 *   frames leak across sessions).
 * A9-1 order: the app installs the SDK bootstrap in attachBaseContext
 * (see [MsdkPlatform]) before any of this runs; here the key gate comes
 * first and the pipeline is touched only after REGISTERED.
 */
class MsdkDroneLink(
    private val context: Context,
    private val pipelineId: Int = DEFAULT_PIPELINE_ID,
) : DroneLink {
    private val bridge = DjiSdkBridge(context)
    private var listener: DroneLink.Listener? = null

    @Volatile
    override var state: DroneLink.State = DroneLink.State.DISCONNECTED
        private set
    override val isSimulated: Boolean = false
    override val aircraftConnected: Boolean?
        get() = when (bridge.state) {
            DjiBridgeState.REGISTERED -> bridge.connectedProductCount > 0
            else -> null // unknown until SDK registration is verified (device)
        }

    /**
     * D3 — 연결 3단 진단에서 쓰는 DJI SDK 단계의 사실.
     *
     * 키 유무와 SDK 초기화는 **앱이 실제로 확인한 사실**만 노출한다:
     * - [sdkKeyPresent]: 매니페스트에 키가 주입되어 있는가 (앱이 읽은 값).
     * - [sdkInitialised]: 등록까지 성공했는가. 등록 전은 **확인 불가**다
     *   (키가 있어도 기기에서 등록이 안 끝났을 수 있다).
     *
     * FAILED의 원인은 [sdkError]가 들고 있다.
     */
    override val sdkKeyPresent: Boolean get() = bridge.appKeyPresent()

    /** true = SDK 등록 성공. false/오류 = 초기화 실패. */
    override val sdkInitialised: Boolean get() = bridge.state == DjiBridgeState.REGISTERED

    /** SDK 단계의 마지막 오류(없으면 null). */
    override val sdkError: String? get() = bridge.lastError

    private var pipeline: Pipeline? = null
    private var reader: Thread? = null

    @Volatile
    private var readerRunning = false

    /**
     * Last link activity (bytes received or state change), monotonic ns
     * ([System.nanoTime] domain). Volatile: the UI layer reads it through
     * [ksi.safetyedge.rc.LinkStateMonitor] without polling or blocking the
     * reader; updated only on the state/reader paths below.
     */
    @Volatile
    var lastActivityMonotonicNs: Long = System.nanoTime()
        private set

    @Volatile
    private var lastError: String? = null

    private val reassembler = MopReassembler()

    // A9-2: serialises frame writes — concurrent senders (UI + the app's
    // retransmit timer) must not interleave frames of different messages.
    private val writer = MopFrameWriter { frame -> writeFrame(frame) }

    // Lazy: no SDK listener object is created until connect() runs, so
    // constructing the link (app start / host tests) needs no SDK runtime
    // classes. On device connect() resolves them for real.
    private val pConnectionListener by lazy {
        PipelineConnectionListener { pipelines ->
            val current = pipelines[pipelineId]
            if (current != null && current.pipelineState == PipelineState.CONNECTED) {
                startReader(current)
                setState(DroneLink.State.CONNECTED)
            } else {
                stopReader()
                pipeline = null
                if (state == DroneLink.State.CONNECTED) {
                    // The pipeline dropped while the link was up: honest
                    // CONNECTING (link maintenance), not DISCONNECTED silence.
                    setState(DroneLink.State.CONNECTING)
                }
            }
        }
    }

    override fun setListener(listener: DroneLink.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (state != DroneLink.State.DISCONNECTED) return
        setState(DroneLink.State.CONNECTING)
        reassembler.reset() // fresh session: no stale partial frames
        // A9-1: never touch the SDK (PipelineManager) without the app key —
        // the key gate comes first, and the pipeline is only connected once
        // the SDK reports REGISTERED.
        if (!bridge.appKeyPresent()) {
            lastError = "${DjiSdkBridge.API_KEY_META} missing/blank — SDK not started"
            setState(DroneLink.State.DISCONNECTED)
            return
        }
        bridge.start { bridgeState ->
            when (bridgeState) {
                DjiBridgeState.REGISTERED -> connectPipeline()
                DjiBridgeState.FAILED, DjiBridgeState.KEY_MISSING -> {
                    lastError = bridge.lastError
                    setState(DroneLink.State.DISCONNECTED)
                }
                else -> Unit // INITIALIZING: wait for the callback
            }
        }
    }

    private fun connectPipeline() {
        try {
            val manager = PipelineManager.getInstance()
            manager.init()
            manager.addPipelineConnectionListener(pConnectionListener)
            // TODO(device): confirm the success convention (null vs
            // non-null IDJIError) against the SDK sample on the RC.
            val error = manager.connectPipeline(
                pipelineId,
                PipelineDeviceType.PAYLOAD,
                TransmissionControlType.STABLE,
            )
            if (error != null) {
                lastError = error.description()
                setState(DroneLink.State.DISCONNECTED)
            }
        } catch (e: LinkageError) {
            lastError = "pipeline unavailable: ${e.message ?: e.javaClass.name}"
            setState(DroneLink.State.DISCONNECTED)
        } catch (e: Exception) {
            lastError = "pipeline connect failed: ${e.message ?: e.javaClass.name}"
            setState(DroneLink.State.DISCONNECTED)
        }
    }

    override fun disconnect() {
        stopReader()
        reassembler.reset()
        val current = pipeline
        pipeline = null
        if (current != null) {
            try {
                PipelineManager.getInstance().disconnectPipeline(
                    pipelineId, PipelineDeviceType.PAYLOAD,
                    TransmissionControlType.STABLE,
                )
            } catch (_: LinkageError) {
                // SDK runtime missing (Helper not installed): best-effort.
            } catch (_: Exception) {
                // Disconnect best-effort; state is forced below regardless.
            }
        }
        try {
            PipelineManager.getInstance().removePipelineConnectionListener(pConnectionListener)
        } catch (_: LinkageError) {
            // ignore (same reason as above)
        } catch (_: Exception) {
            // ignore
        }
        setState(DroneLink.State.DISCONNECTED)
    }

    override fun send(bytes: ByteArray): Boolean {
        val ok = writer.send(bytes)
        if (!ok) {
            // A9-2: a partial write means the remote reassembler state is no
            // longer trustworthy (the bytes already delivered cannot be
            // recalled). Tear the link down; reconnect resets both ends and
            // the envelope layer resends with the SAME msg_id.
            lastError = "partial send — transport torn down"
            disconnect()
        }
        return ok
    }

    private fun writeFrame(frame: ByteArray): Boolean {
        val current = pipeline ?: return false
        return try {
            val result = current.writeData(frame)
            !isFailure(result.error) && result.length == frame.size
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun startReader(current: Pipeline) {
        pipeline = current
        if (readerRunning) return
        readerRunning = true
        reader = Thread({
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (readerRunning) {
                val live = pipeline ?: break
                try {
                    val result = live.readData(buffer)
                    val length = result.length
                    if (length > 0 && !isFailure(result.error)) {
                        lastActivityMonotonicNs = System.nanoTime()
                        val errorsBefore = reassembler.errors
                        for (message in reassembler.feed(
                            buffer.copyOf(length),
                        )) {
                            listener?.onBytes(message)
                        }
                        if (reassembler.errors > errorsBefore) {
                            // Framing desync: the format has no resync
                            // marker, so the stream cannot be trusted any
                            // more. Route through disconnect(): it stops the
                            // reader/pipeline and resets the reassembler, so
                            // a later connect() starts a fresh session
                            // (TODO(device): confirm the channel close
                            // semantics on real hardware).
                            lastError = "framing desync — reconnecting"
                            disconnect()
                            break
                        }
                    } else {
                        Thread.sleep(POLL_IDLE_MS)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: LinkageError) {
                    // SDK runtime disappeared mid-read: same recovery as a
                    // desync (stop + reset; a later connect() reconnects).
                    lastError = "reader linkage error"
                    disconnect()
                    break
                } catch (_: Exception) {
                    // Transport hiccup: keep polling; connection state is
                    // driven by the pipeline listener, not by read errors.
                    Thread.sleep(POLL_IDLE_MS)
                }
            }
        }, "ksi-msdk-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopReader() {
        readerRunning = false
        reader?.interrupt()
        reader = null
    }

    private fun isFailure(error: IDJIError?): Boolean = error != null

    private fun setState(next: DroneLink.State) {
        if (state == next) return
        state = next
        lastActivityMonotonicNs = System.nanoTime()
        listener?.onState(next)
    }

    companion object {
        /** Pipeline id agreed with the PSDK side (both ends must match). */
        const val DEFAULT_PIPELINE_ID = 0
        private const val READ_BUFFER_BYTES = 256 * 1024
        private const val POLL_IDLE_MS = 5L
    }
}
