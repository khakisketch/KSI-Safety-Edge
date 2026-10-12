package ksi.msdk

import java.io.ByteArrayOutputStream

/**
 * MOP transport framing over the DJI pipeline (A9). The pipeline carries
 * byte payloads with its own packet/flow characteristics; we add an
 * explicit frame header so MESSAGE boundaries survive splitting and any
 * transport segmentation:
 *
 *   frame := u32be(chunkLength) | u8(flags) | chunk[chunkLength]
 *             flags bit0 MORE = another chunk of the same message follows
 *
 * This byte contract is cross-pinned with the C++ edge side
 * (`edge/src/transport/mop_frame.cpp` + golden vectors in both suites).
 *
 * - [MopFrameCodec.encode] splits one message into 1..n frames (≤ maxChunk);
 * - [MopReassembler] rebuilds MESSAGES from arbitrary byte feeds. There is
 *   no resync marker in the format: a corrupt length cannot be recovered
 *   mid-stream, so violations DISCARD the buffered state and are counted
 *   ([MopReassembler.errors]) — a broken transport must reconnect and the
 *   fresh session starts from a clean reassembler (see MsdkDroneLink).
 * - [MopFrameWriter] serialises frame writes: one message's frames go out
 *   as an uninterrupted sequence even when several threads send at once.
 *
 * TODO(device, A9): confirm the pipeline's practical packet size, whether
 * it preserves boundaries, and its flow-control behaviour (writeData
 * back-pressure); the framing above is transport-agnostic either way.
 * Pure JVM — host-tested.
 */
object MopFrameCodec {
    /** Sender-side chunk size AND the reassembler's chunk-length guard:
     *  the wire contract both ends enforce (a chunk larger than this is a
     *  corrupt/desynced stream, not a legal frame). */
    const val DEFAULT_MAX_CHUNK = 64 * 1024

    private const val MAX_CHUNK_GUARD = 0xFFFFFF // u24 practical guard

    fun encode(
        message: ByteArray,
        maxChunk: Int = DEFAULT_MAX_CHUNK,
    ): List<ByteArray> {
        require(maxChunk in 1..MAX_CHUNK_GUARD) { "bad maxChunk $maxChunk" }
        if (message.isEmpty()) return listOf(frame(ByteArray(0), more = false))
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val end = minOf(offset + maxChunk, message.size)
            frames.add(
                frame(message.copyOfRange(offset, end), more = end < message.size),
            )
            offset = end
        }
        return frames
    }

    private fun frame(chunk: ByteArray, more: Boolean): ByteArray {
        val out = ByteArray(5 + chunk.size)
        out[0] = ((chunk.size ushr 24) and 0xFF).toByte()
        out[1] = ((chunk.size ushr 16) and 0xFF).toByte()
        out[2] = ((chunk.size ushr 8) and 0xFF).toByte()
        out[3] = (chunk.size and 0xFF).toByte()
        out[4] = if (more) FLAG_MORE.toByte() else 0
        chunk.copyInto(out, 5)
        return out
    }

    const val FLAG_MORE = 0x01
}

/**
 * Serialised frame writes: concurrent [send] callers are queued so the
 * frames of one message are never interleaved with another message's.
 * A partial/failed write returns false — the CALLER must treat the
 * transport as broken (see MsdkDroneLink.send).
 */
class MopFrameWriter(
    private val maxChunk: Int = MopFrameCodec.DEFAULT_MAX_CHUNK,
    private val write: (ByteArray) -> Boolean,
) {
    @Synchronized
    fun send(message: ByteArray): Boolean {
        for (frame in MopFrameCodec.encode(message, maxChunk)) {
            if (!write(frame)) return false
        }
        return true
    }
}

/** Incremental frame->message reassembly; total message size is capped. */
class MopReassembler(
    private val maxMessageBytes: Int = 4 * 1024 * 1024,
    private val maxChunkBytes: Int = MopFrameCodec.DEFAULT_MAX_CHUNK,
) {
    private val header = ByteArray(5)
    private var headerRead = 0
    private var chunkLength = -1
    private var chunkReceived = 0
    private var more = false
    private val message = ByteArrayOutputStream()

    /** Count of discarded (corrupt/oversize) streams; never reset by feed. */
    var errors = 0L
        private set

    /** Discard any partial frame/message state (transport reconnect/reset). */
    @Synchronized
    fun reset() {
        headerRead = 0
        chunkLength = -1
        chunkReceived = 0
        more = false
        message.reset()
    }

    /** Feed raw transport bytes; returns every COMPLETE message.
     *
     *  A chunk length above [maxChunkBytes] or a message above
     *  [maxMessageBytes] is a corrupt/desynced stream: the state is
     *  discarded, [errors] increments, and this feed stops (the caller
     *  should tear the transport down — there is no resync marker). */
    @Synchronized
    fun feed(bytes: ByteArray): List<ByteArray> {
        var offset = 0
        val messages = mutableListOf<ByteArray>()
        while (offset < bytes.size) {
            if (chunkLength < 0) {
                val take = minOf(5 - headerRead, bytes.size - offset)
                System.arraycopy(bytes, offset, header, headerRead, take)
                headerRead += take
                offset += take
                if (headerRead < 5) break
                chunkLength = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                more = (header[4].toInt() and MopFrameCodec.FLAG_MORE) != 0
                if (chunkLength > maxChunkBytes ||
                    message.size() + chunkLength > maxMessageBytes
                ) {
                    errors += 1
                    reset()
                    break
                }
            }
            val need = chunkLength - chunkReceived
            val take = minOf(need, bytes.size - offset)
            message.write(bytes, offset, take)
            offset += take
            chunkReceived += take
            if (chunkReceived == chunkLength) {
                if (!more) {
                    messages.add(message.toByteArray())
                    message.reset()
                }
                headerRead = 0
                chunkLength = -1
                chunkReceived = 0
            }
        }
        return messages
    }
}
