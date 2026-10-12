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
    override var state: DroneLink.State = DroneLink.State.DISCONNECTED
        private set
    override val isSimulated: Boolean = false
    override val aircraftConnected: Boolean?
        get() = when (bridge.state) {
            DjiBridgeState.REGISTERED -> bridge.connectedProductCount > 0
            else -> null // unknown until SDK registration is verified (device)
        }

    private var pipeline: Pipeline? = null
    private var reader: Thread? = null

    @Volatile
    private var readerRunning = false
    private var lastError: String? = null

    private val reassembler = MopReassembler()

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
        val current = pipeline
        pipeline = null
        if (current != null) {
            try {
                PipelineManager.getInstance().disconnectPipeline(
                    pipelineId, PipelineDeviceType.PAYLOAD,
                    TransmissionControlType.STABLE,
                )
            } catch (_: Exception) {
                // Disconnect best-effort; state is forced below regardless.
            }
        }
        try {
            PipelineManager.getInstance().removePipelineConnectionListener(pConnectionListener)
        } catch (_: Exception) {
            // ignore
        }
        setState(DroneLink.State.DISCONNECTED)
    }

    override fun send(bytes: ByteArray): Boolean {
        val current = pipeline ?: return false
        for (frame in MopFrameCodec.encode(bytes)) {
            val result = try {
                current.writeData(frame)
            } catch (_: Exception) {
                return false
            }
            if (isFailure(result.error) || result.length != frame.size) return false
        }
        return true
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
                        for (message in reassembler.feed(
                            buffer.copyOf(length),
                        )) {
                            listener?.onBytes(message)
                        }
                    } else {
                        Thread.sleep(POLL_IDLE_MS)
                    }
                } catch (_: InterruptedException) {
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
        listener?.onState(next)
    }

    companion object {
        /** Pipeline id agreed with the PSDK side (both ends must match). */
        const val DEFAULT_PIPELINE_ID = 0
        private const val READ_BUFFER_BYTES = 256 * 1024
        private const val POLL_IDLE_MS = 5L
    }
}
