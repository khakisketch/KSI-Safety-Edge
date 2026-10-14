package ksi.safetyedge.rc

/** Glanceable video INPUT state, independent of AI, recording and display.
 *  All ages use the RC monotonic receipt clock. A recent encoded frame does
 *  not prove that the decoder rendered it, nor prove flight safety. */
object VideoFreshnessModel {
    enum class Status { DISCONNECTED, CONNECTING, NO_SIGNAL, NO_FRAMES, UNKNOWN, RECENT, STALE }

    data class Result(val status: Status, val ageMs: Long?, val label: String)

    /** Display reference only, not a flight/safety or capture-latency gate. */
    const val STALE_FRAME_MS = 5_000L

    fun evaluate(
        sourceState: VideoSource.State,
        receivedFrameCount: Long?,
        lastFrameReceivedElapsedMs: Long?,
        nowElapsedMs: Long,
        staleMs: Long = STALE_FRAME_MS,
    ): Result {
        require(staleMs > 0L)
        when (sourceState) {
            VideoSource.State.DISCONNECTED -> return Result(Status.DISCONNECTED, null, "미연결")
            VideoSource.State.CONNECTING -> return Result(Status.CONNECTING, null, "연결 중")
            VideoSource.State.NO_SIGNAL -> return Result(Status.NO_SIGNAL, null, "신호 없음")
            VideoSource.State.CONNECTED -> Unit
        }
        if (receivedFrameCount == 0L) return Result(Status.NO_FRAMES, null, "프레임 미수신")
        val receipt = lastFrameReceivedElapsedMs
        if (receivedFrameCount == null || receivedFrameCount < 0L || receipt == null ||
            receipt < 0L || nowElapsedMs < receipt
        ) return Result(Status.UNKNOWN, null, "수신 시각 미확인")

        val age = nowElapsedMs - receipt
        val seconds = age / 1_000L
        return if (age >= staleMs) {
            Result(Status.STALE, age, "수신 지연 · 마지막 ${seconds}초 전")
        } else {
            Result(Status.RECENT, age, if (age < 1_000L) "최근 수신 · 1초 미만" else "최근 수신 · ${seconds}초 전")
        }
    }
}
