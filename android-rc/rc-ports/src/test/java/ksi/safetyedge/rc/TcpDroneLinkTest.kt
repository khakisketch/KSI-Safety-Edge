package ksi.safetyedge.rc

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시험용 TCP Edge 링크 — 바이트 흐름·상태 전이·차단 후 재시도.
 *
 * 고정하는 것:
 * ① 수신 바이트는 **그대로** 리스너로 전달된다(제품과 같은 파서로 간다).
 * ② 송신은 연결 전에는 실패하고, outbox가 재시도할 수 있게 한다.
 * ③ 연결이 끊기면 상태는 "미연결"이다(조용한 성공이 아니다).
 * ④ 기체 연결 사실은 **알 수 없음**(null)이다 — TCP 뒤에 기체가 없다.
 * ⑤ 경로 표시는 "TEST_TCP"이다(제품 경로와 구분된다).
 */
class TcpDroneLinkTest {

    private class Peer {
        val server = ServerSocket(0)
        val received = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        @Volatile var connected = false
        @Volatile var closeAfterFirstRead = false
    }

    @Test
    fun bytes_flow_both_ways_and_the_state_machine_reports_the_link() {
        val peer = Peer()
        val thread = Thread {
            try {
                peer.server.accept().use { socket ->
                    peer.connected = true
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val got = input.read(buffer)
                        if (got <= 0) break
                        peer.received.add(buffer.copyOf(got))
                        // The Edge answers what the RC sent (MOP is a
                        // request/answer protocol, not a broadcast).
                        // The link owns framing: the peer frames its reply.
                        for (frame in MopFrameCodec.encode("edge-reply".toByteArray())) {
                            output.write(frame)
                        }
                        output.flush()
                        if (peer.closeAfterFirstRead) break
                    }
                }
            } catch (_: Exception) {
            }
        }
        thread.isDaemon = true
        thread.start()

        val link = TcpDroneLink("127.0.0.1", peer.server.localPort)
        val states = mutableListOf<DroneLink.State>()
        val received = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)
        link.setListener(object : DroneLink.Listener {
            override fun onBytes(bytes: ByteArray) {
                received.add(bytes)
                latch.countDown()
            }

            override fun onState(state: DroneLink.State) {
                states.add(state)
            }
        })

        assertFalse(link.send(byteArrayOf(1)), "연결 전에는 송신이 실패한다(재시도 대상)")
        link.connect()
        // 연결은 상태로 보고된다(바이트는 RC가 먼저 보내야 답이 온다).
        val connectedDeadline = System.currentTimeMillis() + 10_000
        while (!states.contains(DroneLink.State.CONNECTED) &&
            System.currentTimeMillis() < connectedDeadline
        ) {
            Thread.sleep(20)
        }
        assertTrue(states.contains(DroneLink.State.CONNECTED), "연결 상태가 보고된다")

        // 송신: 링크가 프레이밍해 피어에 도착하고, 피어의 답이 그대로 돌아온다.
        link.send("ping".toByteArray())
        assertTrue(latch.await(10, TimeUnit.SECONDS), "응답 대기")
        assertTrue(received.isNotEmpty(), "바이트가 그대로 전달된다")
        assertEquals(
            String(received.first()),
            "edge-reply",
            "수신 바이트가 변하지 않는다",
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (peer.received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(peer.received.isNotEmpty(), "송신 바이트가 도착한다")
        // The peer receives FRAMES (the link's framing is the contract).
        val peerMessages = MopReassembler().feed(peer.received.first())
        assertEquals(1, peerMessages.size)
        assertEquals("ping", String(peerMessages.first()))

        // 기체 사실은 "알 수 없음"이다(TCP 뒤에 기체가 없다).
        assertNull(link.aircraftConnected)
        assertFalse(link.isSimulated, "합성 바이트가 아니다")
        assertEquals("TEST_TCP", link.linkKind, "시험용 경로로 표시된다")

        link.disconnect()
        assertEquals(DroneLink.State.DISCONNECTED, link.state)
        peer.server.close()
    }
}
