package ksi.safetyedge.rc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Host tests for the MP4 structural sanity gate (A9-3 review residual):
 * only files with a plausible ftyp + moov + mdat structure may become
 * evidence; truncated, empty and non-video files must be rejected.
 */
class Mp4ProbeTest {
    private fun tempFile(bytes: ByteArray): File =
        Files.createTempFile("seg", ".mp4").toFile().apply { writeBytes(bytes) }

    /** A minimal box: u32be size + 4-char type + zero payload. */
    private fun box(type: String, payload: Int): ByteArray {
        val size = 8 + payload
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.encodeToByteArray().copyInto(out, 4)
        return out
    }

    private fun plausibleMp4(): ByteArray =
        box("ftyp", 16) + ByteArray(1024) + box("mdat", 512) + box("moov", 256)

    @Test
    fun accepts_ftyp_mdat_moov_structure() {
        assertTrue(Mp4Probe.looksPlayable(tempFile(plausibleMp4())))
    }

    @Test
    fun rejects_non_video_and_truncated_files() {
        assertFalse(Mp4Probe.looksPlayable(tempFile(ByteArray(4096)))) // zeros
        assertFalse(Mp4Probe.looksPlayable(tempFile(ByteArray(16)))) // too small
        assertFalse(
            Mp4Probe.looksPlayable(
                tempFile("not a movie".toByteArray() + ByteArray(2048)),
            ),
        )
        // ftyp only (the shape a crash leftover / unfinalized writer leaves)
        assertFalse(Mp4Probe.looksPlayable(tempFile(plausibleMp4().copyOf(64))))
        // moov/mdat text in the payload without an ftyp header
        val noHeader = ByteArray(2048)
        "moov".encodeToByteArray().copyInto(noHeader, 100)
        "mdat".encodeToByteArray().copyInto(noHeader, 200)
        assertFalse(Mp4Probe.looksPlayable(tempFile(noHeader)))
    }

    @Test
    fun finds_box_types_across_scan_window_boundaries() {
        // mdat/moov land beyond the first 64 KiB window, exercising the
        // boundary carry in the scanner.
        val big = box("ftyp", 16) +
            ByteArray((1 shl 16) - 100) +
            box("mdat", 16) +
            box("moov", 16)
        assertTrue(Mp4Probe.looksPlayable(tempFile(big)))
    }
}
