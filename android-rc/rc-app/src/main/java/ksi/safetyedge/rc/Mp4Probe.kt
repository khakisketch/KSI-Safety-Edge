package ksi.safetyedge.rc

import java.io.File

/**
 * Structural sanity check for finalized MP4 segments (A9-3 review residual):
 * guards [ksi.rc.SegmentIngestor] against promoting a truncated or broken
 * file as video evidence. A plausible file starts with an `ftyp` box and
 * contains both `moov` and `mdat` boxes.
 *
 * This is deliberately structural — it does NOT decode or prove playability
 * (that stays an RC ground-session check, e.g. PTS-unit verification). Its
 * job is only to catch the obviously-unusable file (crash leftovers,
 * muxer output without a moov atom) BEFORE it becomes an artifact.
 */
object Mp4Probe {
    /** Smallest structure that could possibly carry two samples. */
    private const val MIN_BYTES = 1024L
    private val MOOV = byteArrayOf('m'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())
    private val MDAT = byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte())
    private const val WINDOW = 1 shl 16

    fun looksPlayable(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_BYTES) return false
        val header = readFirst(file, 8)
        if (header.size < 8) return false
        if (!isBox(header, 0, "ftyp")) return false

        var seenMoov = false
        var seenMdat = false
        val window = ByteArray(WINDOW)
        var keep = 0
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(window, keep, window.size - keep)
                if (read <= 0) break
                val filled = keep + read
                if (!seenMoov && indexOf(window, filled, MOOV) >= 0) seenMoov = true
                if (!seenMdat && indexOf(window, filled, MDAT) >= 0) seenMdat = true
                if (seenMoov && seenMdat) return true
                // Carry the tail so a box type split across reads still matches.
                keep = minOf(3, filled)
                System.arraycopy(window, filled - keep, window, 0, keep)
            }
        }
        return seenMoov && seenMdat
    }

    private fun readFirst(file: File, bytes: Int): ByteArray {
        val header = ByteArray(bytes)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
        }
        return header
    }

    /** True when a box at [offset] has the given 4-char type and a sane size. */
    private fun isBox(data: ByteArray, offset: Int, type: String): Boolean {
        if (offset + 8 > data.size) return false
        val size = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        if (size < 8 || size > 1024) return false
        for (i in 0 until 4) {
            if (data[offset + 4 + i] != type[i].code.toByte()) return false
        }
        return true
    }

    private fun indexOf(data: ByteArray, length: Int, needle: ByteArray): Int {
        if (length < needle.size) return -1
        outer@ for (start in 0..length - needle.size) {
            for (i in needle.indices) {
                if (data[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
