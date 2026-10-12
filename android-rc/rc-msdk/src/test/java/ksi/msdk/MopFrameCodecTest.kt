package ksi.msdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A9 host tests for the MOP framing layer that will sit on top of the DJI
 * pipeline: message boundaries must survive arbitrary transport
 * segmentation, and an over-long frame must be rejected rather than
 * silently corrupting stream state.
 */
class MopFrameCodecTest {
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
    }

    @Test
    fun two_messages_in_one_read_are_separated() {
        val a = "first".toByteArray()
        val b = "second".toByteArray()
        val wire = MopFrameCodec.encode(a).fold(ByteArray(0)) { acc, f -> acc + f } +
            MopFrameCodec.encode(b).fold(ByteArray(0)) { acc, f -> acc + f }
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
    fun oversized_frame_is_rejected_not_buffered() {
        // u32 length ~2GB + flags byte; cap is 1 MiB.
        val evil = byteArrayOf(0x7F, -1, -1, -1, 0x00)
        assertThrows(IllegalStateException::class.java) {
            MopReassembler(maxMessageBytes = 1 shl 20).feed(evil)
        }
    }
}
