package ksi.msdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A9-3 host tests for the segment rotation core: rotation boundaries,
 * `.tmp` -> `.mp4` finalization, failed writers staying OUT of evidence,
 * and the crash-safety contract that [SegmentRecorderCore.stop] returns
 * only real, finalized files.
 */
class SegmentRecorderCoreTest {
    private class FakeWriter(
        private val tmp: File,
        private val failOnClose: Boolean = false,
    ) : VideoSegmentWriter {
        var writes = 0
            private set

        override fun write(frame: EncodedVideoFrame): Boolean {
            writes += 1
            return true
        }

        override fun close(): Boolean {
            // A real muxer leaves the partial file behind on failure; the
            // core must keep it OUT of evidence (never renamed).
            tmp.writeBytes(ByteArray(16) { 1 })
            return !failOnClose
        }
    }

    private fun frame(ts: Long = 0L) = EncodedVideoFrame(
        data = ByteArray(4),
        pts = ts,
        seq = ts,
        isKeyFrame = true,
        width = 1280,
        height = 720,
        fps = 15,
        sps = ByteArray(2),
        pps = ByteArray(2),
    )

    private fun tempDir(): File = Files.createTempDirectory("seg-rec").toFile()

    @Test
    fun rolls_over_at_boundaries_and_returns_real_files() {
        val dir = tempDir()
        val core = SegmentRecorderCore(SegmentRotationPolicy(segmentSeconds = 1)) {
            FakeWriter(it)
        }
        assertTrue(core.start(dir, elapsedMs = 0))
        assertTrue(core.onFrame(frame(), 0))
        assertTrue(core.onFrame(frame(), 999))
        assertTrue(core.onFrame(frame(), 1_000)) // boundary: rotate to seg 1
        assertTrue(core.onFrame(frame(), 2_500)) // boundary: rotate to seg 2
        val files = core.stop()
        assertEquals(3, files.size)
        assertEquals(
            listOf("seg-00000.mp4", "seg-00001.mp4", "seg-00002.mp4"),
            files.map { it.name },
        )
        assertTrue(files.all { it.isFile && it.length() > 0 })
        assertFalse(File(dir, "seg-00002.mp4.tmp").exists())
        assertEquals(0, core.failedSegments)
    }

    @Test
    fun failed_close_keeps_the_tmp_out_of_evidence() {
        val dir = tempDir()
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) {
            FakeWriter(it, failOnClose = true)
        }
        assertTrue(core.start(dir, 0))
        core.onFrame(frame(), 0)
        val files = core.stop()
        assertTrue(files.isEmpty())
        assertEquals(1, core.failedSegments)
        assertTrue(File(dir, "seg-00000.mp4.tmp").exists()) // kept for diagnosis
        assertFalse(File(dir, "seg-00000.mp4").exists()) // never evidence
    }

    @Test
    fun stop_filters_files_deleted_outside() {
        val dir = tempDir()
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { FakeWriter(it) }
        core.start(dir, 0)
        core.onFrame(frame(), 0)
        core.onFrame(frame(), 1_000) // finalizes seg 0, opens seg 1
        File(dir, "seg-00000.mp4").delete() // external deletion
        val files = core.stop()
        assertEquals(1, files.size)
        assertEquals("seg-00001.mp4", files[0].name)
    }

    @Test
    fun recording_continues_after_a_failed_segment() {
        var factoryCalls = 0
        val dir = tempDir()
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { tmp ->
            factoryCalls += 1
            FakeWriter(tmp, failOnClose = factoryCalls == 1)
        }
        core.start(dir, 0)
        core.onFrame(frame(), 0)
        core.onFrame(frame(), 1_000) // seg 0 closes (fail) -> seg 1 opens
        core.onFrame(frame(), 1_500)
        val files = core.stop()
        assertEquals(1, files.size)
        assertEquals("seg-00001.mp4", files[0].name)
        assertEquals(1, core.failedSegments)
    }

    @Test
    fun start_fails_when_the_target_is_not_a_directory() {
        val notADir = File(tempDir(), "not-a-dir")
        notADir.writeBytes(ByteArray(1))
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { FakeWriter(it) }
        assertFalse(core.start(notADir, 0))
        assertFalse(core.isRecording)
    }

    @Test
    fun stray_crash_leftovers_are_never_returned() {
        val dir = tempDir()
        File(dir, "seg-00009.mp4.tmp").writeBytes(ByteArray(4)) // crash leftover
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { FakeWriter(it) }
        core.start(dir, 0)
        core.onFrame(frame(), 0)
        val files = core.stop()
        assertEquals(listOf("seg-00000.mp4"), files.map { it.name })
    }

    @Test
    fun a_failed_rotation_open_recovers_at_the_next_boundary() {
        var factoryCalls = 0
        val dir = tempDir()
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { tmp ->
            factoryCalls += 1
            if (factoryCalls == 2) throw IllegalStateException("transient open failure")
            FakeWriter(tmp)
        }
        assertTrue(core.start(dir, 0))
        assertTrue(core.onFrame(frame(), 0)) // seg 0
        assertFalse(core.onFrame(frame(), 1_000)) // boundary 1: open fails
        assertFalse(core.isRecording)
        assertFalse(core.onFrame(frame(), 1_500)) // no per-frame retry storm
        assertEquals(2, factoryCalls)
        assertTrue(core.onFrame(frame(), 2_000)) // boundary 2: reopen succeeds
        assertTrue(core.isRecording)
        val files = core.stop()
        assertEquals(
            listOf("seg-00000.mp4", "seg-00002.mp4"),
            files.map { it.name },
        )
        assertEquals(1, core.failedSegments)
    }

    @Test
    fun a_segment_with_an_untrusted_write_is_never_published() {
        val dir = tempDir()
        var writes = 0
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { tmp ->
            object : VideoSegmentWriter {
                override fun write(frame: EncodedVideoFrame): Boolean {
                    writes += 1
                    return writes != 2 // the second frame is untrusted
                }

                override fun close(): Boolean {
                    tmp.writeBytes(ByteArray(16))
                    return true // closes cleanly anyway
                }
            }
        }
        core.start(dir, 0)
        assertTrue(core.onFrame(frame(), 0))
        assertFalse(core.onFrame(frame(), 10))
        assertTrue(core.onFrame(frame(), 20))
        val files = core.stop()
        assertTrue(files.isEmpty())
        assertEquals(1, core.failedSegments)
        assertTrue(File(dir, "seg-00000.mp4.tmp").exists())
        assertFalse(File(dir, "seg-00000.mp4").exists())
    }

    @Test
    fun frames_after_stop_never_reopen_a_segment() {
        val dir = tempDir()
        var factoryCalls = 0
        val core = SegmentRecorderCore(SegmentRotationPolicy(1)) { tmp ->
            factoryCalls += 1
            FakeWriter(tmp)
        }
        core.start(dir, 0)
        core.onFrame(frame(), 0)
        assertEquals(1, core.stop().size)
        assertFalse(core.onFrame(frame(), 5_000))
        assertEquals(1, factoryCalls)
        assertFalse(core.isRecording)
    }
}
