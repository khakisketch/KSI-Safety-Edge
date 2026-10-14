package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoFreshnessModelTest {
    @Test
    fun connected_bench_never_claims_real_frames() {
        val source = FakeVideoSource()
        source.connect()
        val result = VideoFreshnessModel.evaluate(
            source.state, source.receivedFrameCount, source.lastFrameReceivedElapsedMs, 10_000L,
        )
        assertEquals(VideoFreshnessModel.Status.NO_FRAMES, result.status)
        assertNull(result.ageMs)
    }

    @Test
    fun receipt_ages_without_new_frames_and_recovers_on_new_receipt() {
        fun at(now: Long, receipt: Long = 1_000L) = VideoFreshnessModel.evaluate(
            VideoSource.State.CONNECTED, 20L, receipt, now,
        )
        assertEquals(VideoFreshnessModel.Status.RECENT, at(5_999L).status)
        assertEquals(VideoFreshnessModel.Status.STALE, at(6_000L).status)
        assertEquals(5_000L, at(6_000L).ageMs)
        assertEquals("수신 지연 · 마지막 5초 전", at(6_000L).label)
        assertEquals(VideoFreshnessModel.Status.RECENT, at(8_000L, 7_900L).status)
        assertEquals("최근 수신 · 1초 미만", at(8_000L, 7_900L).label)
    }

    @Test
    fun old_receipts_cannot_override_disconnection_or_signal_loss() {
        listOf(
            VideoSource.State.DISCONNECTED to VideoFreshnessModel.Status.DISCONNECTED,
            VideoSource.State.CONNECTING to VideoFreshnessModel.Status.CONNECTING,
            VideoSource.State.NO_SIGNAL to VideoFreshnessModel.Status.NO_SIGNAL,
        ).forEach { (source, expected) ->
            val result = VideoFreshnessModel.evaluate(source, 90L, 1_000L, 2_000L)
            assertEquals(expected, result.status)
            assertNull(result.ageMs)
        }
    }

    @Test
    fun unknown_or_inconsistent_clocks_do_not_become_fresh() {
        fun state(count: Long?, receipt: Long?) = VideoFreshnessModel.evaluate(
            VideoSource.State.CONNECTED, count, receipt, 10_000L,
        ).status
        assertEquals(VideoFreshnessModel.Status.UNKNOWN, state(null, null))
        assertEquals(VideoFreshnessModel.Status.UNKNOWN, state(9L, null))
        assertEquals(VideoFreshnessModel.Status.UNKNOWN, state(9L, 11_000L))
        assertEquals(VideoFreshnessModel.Status.UNKNOWN, state(-1L, 9_000L))
        // Elapsed clock zero is a valid measured time, not a sentinel.
        assertEquals(VideoFreshnessModel.Status.STALE, state(1L, 0L))
    }
}
