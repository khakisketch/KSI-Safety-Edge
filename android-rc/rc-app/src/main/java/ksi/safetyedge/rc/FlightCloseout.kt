package ksi.safetyedge.rc

/**
 * Pure close-out decisions for the flight close path (contract §6).
 *
 * Pure Kotlin, no Android: host-tested. Both functions encode "honest
 * close" facts the close sequence must not invent:
 * - segment→boot linkage resolves to a single Edge boot only when the
 *   start anchor and the stored boots agree on exactly one boot — anything
 *   else is [SegmentBootResolution.Unverifiable], and prior-boot segments
 *   are never relabeled to the latest boot;
 * - only an Edge-confirmed STOPPED reads as a clean close; a pending or
 *   unknown STOP names its reason and stays unresolved.
 */
object FlightCloseout {

    /** Segment→boot resolution outcome for the close path. */
    sealed interface SegmentBootResolution {
        /** Exactly one distinct boot: unambiguous linkage. */
        data class SingleBoot(val bootId: String) : SegmentBootResolution

        /** Zero or several distinct boots: keep local, count nothing. */
        data class Unverifiable(val reason: String) : SegmentBootResolution
    }

    /** Assessed STOP outcome for the close summary. */
    data class StopAssessment(
        val clean: Boolean,
        val note: String,
        /** False when no Edge session was open (no request applicable). */
        val sessionOpen: Boolean = true,
    )

    /** STOP request outcome handed to the close coordinator. */
    data class StopRequest(
        val sessionOpen: Boolean,
        val assessment: StopAssessment,
    )

    /**
     * Resolves which boot finalized proxy segments belong to. [startBoot]
     * is the anchor recorded when recording began (null when never
     * captured); [storedBoots] are the boots the store actually holds.
     * A single distinct boot across both means unambiguous linkage;
     * anything else — including "latest wins" guessing — is unverifiable.
     */
    fun resolveSegmentBoot(
        startBoot: String?,
        storedBoots: List<String>,
    ): SegmentBootResolution {
        val candidates = (listOfNotNull(startBoot) + storedBoots).distinct()
        if (candidates.size == 1) return SegmentBootResolution.SingleBoot(candidates[0])
        return SegmentBootResolution.Unverifiable(
            "부트 연계 미검증 · 기록 시작 부트 ${startBoot?.take(8) ?: "미상"} · " +
                "저장 부트 ${storedBoots.size}개",
        )
    }

    /**
     * Assesses the STOP request outcome. [sessionOpen] means an Edge
     * session was started (a request was applicable); [confirmedStopped]
     * means the Edge confirmed STOPPED afterwards. Only a confirmed stop
     * reads clean — pending/unknown names its reason.
     */
    fun assessStop(
        sessionOpen: Boolean,
        confirmedStopped: Boolean,
        linkUp: Boolean,
    ): StopAssessment = when {
        !sessionOpen -> StopAssessment(
            false, "관측 세션 없음(시작된 Edge 실행 없음)", sessionOpen = false,
        )
        confirmedStopped -> StopAssessment(true, "관측 세션 STOPPED 확인됨")
        !linkUp -> StopAssessment(
            false, "STOP 요청 전송됨 · 링크 끊김으로 확인 불가 — 미확인 종료(사유: 링크 단절)",
        )
        else -> StopAssessment(
            false, "STOP 요청 전송됨 · Edge 확인 미수신 — 미확인 종료(사유: STOPPED 미확인)",
        )
    }
}
