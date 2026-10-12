package ksi.msdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A9 host tests for the MOP framing layer that will sit on top of the DJI
 * pipeline: message boundaries must survive arbitrary transport
 * segmentation, and a corrupt stream must be counted + discarded (no resync
 * marker exists) rather than silently corrupting state.
 *
 * The golden hex vectors below are byte-identical to the C++ suite
 * (edge/tests/test_mop_frame.cpp) — the cross-language wire contract.
 */
class MopFrameCodecTest {
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun golden_vectors_match_the_cpp_wire_contract() {
        assertEquals("000000050068656c6c6f", hex(single("hello".toByteArray())))
        assertEquals("0000000000", hex(single(ByteArray(0))))
        assertEquals(
            "00000002016162000000010063",
            hex(
                MopFrameCodec.encode("abc".toByteArray(), maxChunk = 2)
                    .fold(ByteArray(0)) { acc, f -> acc + f },
            ),
        )
    }

    private fun single(message: ByteArray): ByteArray =
        MopFrameCodec.encode(message).fold(ByteArray(0)) { acc, f -> acc + f }

    @Test
    fun round_trip_single_and_multi_frame_messages() {
        val small = "hello mop".toByteArray()
        val big = ByteArray(200_000) { (it % 251).toByte() }

        for (message in listOf(small, big)) {
            val frames = MopFrameCodec.encode(message, maxChunk = 64 * 1024)
            assertEquals(
                "big message must split",
                if (message.size <= 64 * 1024) 1 else 4,
                frames.size,
            )
            val reassembler = MopReassembler()
            val out = mutableListOf<ByteArray>()
            for (frame in frames) out += reassembler.feed(frame)
            assertEquals(1, out.size)
            assertArrayEquals(message, out[0])
        }
    }

    @Test
    fun reassembly_survives_byte_by_byte_delivery() {
        val message = ByteArray(1000) { (it * 7 % 256).toByte() }
        val wire = MopFrameCodec.encode(message, maxChunk = 128)
            .fold(ByteArray(0)) { acc, frame -> acc + frame }
        val reassembler = MopReassembler()
        val out = mutableListOf<ByteArray>()
        for (byte in wire) out += reassembler.feed(byteArrayOf(byte))
        assertEquals(1, out.size)
        assertArrayEquals(message, out[0])
        assertEquals(0L, reassembler.errors)
    }

    @Test
    fun two_messages_in_one_read_are_separated() {
        val a = "first".toByteArray()
        val b = "second".toByteArray()
        val wire = single(a) + single(b)
        val out = MopReassembler().feed(wire)
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
    }

    @Test
    fun empty_message_is_a_zero_length_frame() {
        val frames = MopFrameCodec.encode(ByteArray(0))
        assertEquals(1, frames.size)
        assertEquals(5, frames[0].size) // 4B length + 1B flags
        val out = MopReassembler().feed(frames[0])
        assertEquals(1, out.size)
        assertEquals(0, out[0].size)
    }

    @Test
    fun oversize_chunk_is_counted_and_state_discarded() {
        // Chunk length 65537 > 64 KiB wire guard.
        val evil = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00)
        val reassembler = MopReassembler()
        assertEquals(0, reassembler.feed(evil).size)
        assertEquals(1L, reassembler.errors)
        // Clean again: a valid frame still completes.
        val out = reassembler.feed(single("hello".toByteArray()))
        assertEquals(1, out.size)
        assertEquals(1L, reassembler.errors)
    }

    @Test
    fun message_cap_violation_is_counted() {
        // Two 6-byte chunks with MORE exceed a 10-byte message cap.
        val stream = byteArrayOf(0, 0, 0, 6, 1) + "aaaaaa".toByteArray() +
            byteArrayOf(0, 0, 0, 6, 0) + "bbbbbb".toByteArray()
        val reassembler = MopReassembler(maxMessageBytes = 10)
        assertEquals(0, reassembler.feed(stream).size)
        assertEquals(1L, reassembler.errors)
    }

    @Test
    fun reset_discards_partial_state_across_reconnects() {
        val reassembler = MopReassembler()
        val hello = single("hello".toByteArray())
        reassembler.feed(hello.copyOf(3)) // partial frame
        reassembler.reset() // transport reconnect
        val out = reassembler.feed(hello)
        assertEquals(1, out.size)
        assertEquals("hello", out[0].decodeToString())
        assertEquals(0L, reassembler.errors)
    }

    @Test
    fun frame_writer_serialises_concurrent_senders() {
        val sink = ByteArrayOutputStream()
        val writer = MopFrameWriter { frame ->
            sink.write(frame)
            true
        }
        val big = ByteArray(300_000) { 0x11 }
        val small = ByteArray(100) { 0x22 }
        val threads = listOf(
            Thread { repeat(8) { writer.send(big) } },
            Thread { repeat(64) { writer.send(small) } },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val reassembler = MopReassembler()
        val messages = reassembler.feed(sink.toByteArray())
        assertEquals(8 + 64, messages.size)
        assertEquals(8, messages.count { it.size == big.size && it.all { b -> b == 0x11.toByte() } })
        assertEquals(64, messages.count { it.size == small.size && it.all { b -> b == 0x22.toByte() } })
        assertEquals(0L, reassembler.errors)
    }

    @Test
    fun frame_writer_reports_partial_write_as_failure() {
        val writer = MopFrameWriter { false }
        assertFalse(writer.send("x".toByteArray()))
        var seen = 0
        val counting = MopFrameWriter { seen += 1; seen <= 1 }
        // Big message = several frames; only the first write succeeds.
        assertFalse(counting.send(ByteArray(150_000)))
        assertTrue(seen >= 2)
    }
}
