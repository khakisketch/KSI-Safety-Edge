package ksi.safetyedge.rc

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 시험용 Edge 링크(TCP) — **제품 통신 경로가 아니다.**
 *
 * WHY
 *   RC의 제품 MOP 경로는 기체 데이터 채널을 거친다(MSDK). 기체 없이 개발할
 *   때 그 경로를 대체할 수 있는 것은 "같은 바이트를 같은 코드로" 처리하는
 *   것뿐이다. 이 링크는 **실제 Edge가 실제로 보낸 MOP 바이트**를 RC의 공통
 *   파서·상태 처리기로 그대로 흘린다. 따라서:
 *
 *   - 수신 경로(파서·검증·상태·저장)은 제품과 **동일한 코드**를 쓴다;
 *   - 송신 경로(명령·ACK)도 동일하다;
 *   - 단, **물리 경로만** 다르다(기체 대신 LAN). 그래서 이 링크는
 *     "개발·통합시험용"으로만 쓰고, 제품 경로로 채택하지 않는다.
 *
 * HONESTY
 *   - [aircraftConnected]는 **null(알 수 없음)**이다: 이 뒤에는 기체가 없다.
 *     기체 명령은 통제권 경로가 없어 실행을 주장할 수 없다(명령 게이트가 막는다).
 *   - [isSimulated]은 **false**다: 바이트는 합성이 아니라 실제 Edge의 것이기
 *     때문이다. 다만 [linkKind]가 "TEST_TCP"이므로 화면에서 구분된다.
 *   - 연결이 끊기면 상태는 "미연결"이고, 보낸 바이트는 rc-core의 outbox에
 *     남아 재시도된다(드론 링크 계약과 동일).
 */
class TcpDroneLink(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3_000,
) : DroneLink {

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    private var listener: DroneLink.Listener? = null
    // Framing belongs to the LINK (the same contract the MSDK link keeps):
    // outgoing envelopes are framed, incoming frames are reassembled into
    // complete envelopes. The RC's ingest path expects unframed envelopes.
    private val writer = MopFrameWriter { frame ->
        val stream = output
        if (stream == null) {
            false
        } else {
            try {
                synchronized(stream) {
                    stream.write(frame)
                    stream.flush()
                }
                true
            } catch (_: IOException) {
                false
            }
        }
    }
    private val reassembler = MopReassembler()

    @Volatile
    private var currentState: DroneLink.State = DroneLink.State.DISCONNECTED

    override val state: DroneLink.State get() = currentState

    /** The bytes are real Edge bytes — not synthesised. */
    override val isSimulated: Boolean = false

    /** No aircraft sits behind a TCP link: UNKNOWN, never false-as-fact. */
    override val aircraftConnected: Boolean? = null

    /** The UI must be able to tell this path apart from the aircraft path. */
    override val linkKind: String get() = "TEST_TCP"

    override fun setListener(listener: DroneLink.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (running.get()) return
        running.set(true)
        setState(DroneLink.State.CONNECTING)
        worker = Thread({ runLoop() }, "tcp-drone-link").apply {
            isDaemon = true
            start()
        }
    }

    override fun disconnect() {
        running.set(false)
        closeSocket()
        setState(DroneLink.State.DISCONNECTED)
    }

    override fun send(bytes: ByteArray): Boolean = writer.send(bytes)

    private fun runLoop() {
        var connected = false
        while (running.get()) {
            // Any exit path must leave the state honest (not CONNECTED).
            try {
                val client = Socket()
                client.tcpNoDelay = true
                client.connect(InetSocketAddress(host, port), connectTimeoutMs)
                reassembler.reset()
                socket = client
                output = client.getOutputStream()
                connected = true
                setState(DroneLink.State.CONNECTED)
                readLoop(client.getInputStream())
            } catch (_: IOException) {
                // fall through to the retry below
            } catch (_: RuntimeException) {
                // a malformed peer must not kill the link thread
            } finally {
                closeSocket()
            }
            if (!running.get()) break
            if (connected) {
                // A dropped peer is a link loss, not a silent success. The
                // state MUST say so: a link that is "connected" while nothing
                // flows makes the RC report a live Edge that is not there.
                setState(DroneLink.State.DISCONNECTED)
                connected = false
            }
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(8 * 1024)
        while (running.get()) {
            val got = input.read(buffer)
            if (got <= 0) break
            val chunk = if (got == buffer.size) buffer.copyOf() else buffer.copyOf(got)
            // Frames -> complete envelopes. A corrupt stream has no resync
            // marker: the transport must be torn down (the link does that).
            val messages = reassembler.feed(chunk)
            if (reassembler.errors > 0) {
                // No resync marker exists: the stream is desynced and the
                // transport must be torn down.
                throw IOException("MOP frame stream desynced (${reassembler.errors} errors)")
            }
            for (message in messages) {
                listener?.onBytes(message)
            }
        }
    }

    private fun closeSocket() {
        try {
            output?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        output = null
        socket = null
    }

    private fun setState(next: DroneLink.State) {
        if (currentState == next) return
        currentState = next
        listener?.onState(next)
    }
}
