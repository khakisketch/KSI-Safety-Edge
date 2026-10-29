package ksi.safetyedge.rc

/**
 * Operator-facing status contract (B-1): a pure, host-tested summary of
 * "what is happening and what should I do", derived ONLY from real session
 * facts — nothing is invented and nothing is hidden.
 *
 * This is the BEHAVIOR contract, not the look: layout, typography, colors
 * and touch ergonomics are owned by the design workspace (see
 * `docs/plans/2026-10-12-b1-operator-flow-contract.md` §디자인 위임). The UI renders
 * these items; it does not re-decide them.
 *
 * Display-threshold constants below are status INDICATORS, not safety
 * judgments — they never turn "no data" into "safe".
 */
object OperatorStatus {
    enum class Severity { INFO, ATTENTION, PROBLEM }

    data class Item(
        val severity: Severity,
        /** Short fact label, e.g. "MOP 링크 끊김". */
        val label: String,
        /** Fact detail (counts/ages); may be empty. */
        val detail: String,
        /** What the operator should do; null when nothing is required. */
        val action: String?,
    )

    /** Everything the model needs — computed by the caller from the store. */
    data class Input(
        val hasActiveFlight: Boolean,
        val linkState: String,
        val linkSimulated: Boolean,
        val aircraftConnected: Boolean?,
        val internet: String,
        val recorderState: String?,
        val mopHeartbeatAgeMs: Long?,
        val observationAgeMs: Long?,
        val eventCount: Int,
        val artifactsTotal: Int,
        val artifactsComplete: Int,
        val artifactsSynced: Int,
        val pendingAnnotations: Int,
        val coveredFrames: Long?,
        val skippedFrames: Long?,
        val coreConfigured: Boolean,
        val recorderWrittenFrames: Long? = null,
        val recorderHasWriteFailure: Boolean? = null,
        // -- Edge runtime facts (2026-10-21 contract §2/§5) -----------------
        // All optional (default = never reported): absent means "모름" and
        // renders "Edge 실행 상태 미확인", never false/0. The state wording
        // itself comes from [EdgeRuntimePanel.stateLine] (single source).
        /** True once a MISSION_STATUS with runtime fields arrived. */
        val edgeRuntimeSeen: Boolean = false,
        /** EdgeRuntimeState name ("RUNNING", …) or null when unreported. */
        val edgeRuntimeState: String? = null,
        val edgeProcessedFrames: Long? = null,
        val edgeDegradedReasons: List<String> = emptyList(),
        val edgeInputState: Map<String, String> = emptyMap(),
        /** False = link down (show link loss); null = unknown, never guessed. */
        val edgeLinkUp: Boolean? = null,
        // "준비 미완" 알림 (AT-J1). null = 아직 판정하지 않음(경고하지 않는다).
        // 세 사실(동기화·번들 검증·Edge 적용)을 합친 "준비 완료"를 추정하지 않는다.
        val preparationNotice: PreparationReadiness.Notice? = null,
        /** RC receipt time of the last RUNNING/DEGRADED report. */
        val edgeLastConfirmedUtc: String? = null,
        // -- Local-storage availability (fault containment) -----------------
        // False when the durable KSI store failed to open (corrupt counter,
        // unusable root): KSI facts are then "상태 미확인", never healthy
        // and never zero. Physical link/aircraft/video facts below are
        // independent and still render. Defaults keep existing callers on
        // the healthy path.
        /** False when local KSI persistence/observation is unusable. */
        val storageAvailable: Boolean = true,
        /** Captured open failure detail (shown with the storage item). */
        val storageError: String? = null,
    )

    /** Heartbeat older than this shows "지연" (indicator only). */
    const val HEARTBEAT_STALE_MS = 10_000L

    /** Observation older than this shows "지연" (indicator only). */
    const val OBSERVATION_STALE_MS = 5_000L

    /**
     * Degraded evaluation (fault containment): the durable KSI store is
     * down, so every KSI-derived section reads "상태 미확인" — never
     * healthy, never zero. Physical link/aircraft/video facts are
     * independent and still render from the same helpers as the healthy
     * path; the storage PROBLEM item leads so the UI surfaces it first.
     */
    private fun evaluateDegraded(input: Input): List<Item> {
        val items = mutableListOf<Item>()
        val detail = buildString {
            append(StorageHealth.UNAVAILABLE_DETAIL)
            if (!input.storageError.isNullOrBlank()) append(" · ${input.storageError}")
        }
        items += Item(
            Severity.PROBLEM,
            StorageHealth.UNAVAILABLE_TITLE,
            detail,
            StorageHealth.UNAVAILABLE_ACTION,
        )
        flightItem(input.hasActiveFlight)?.let { items += it }
        linkItem(input)?.let { items += it }
        aircraftItem(input.aircraftConnected)?.let { items += it }
        internetItem(input.internet)?.let { items += it }
        recorderItem(input)?.let { items += it }
        items += Item(
            Severity.INFO, "로컬 증거 상태 미확인",
            "저장소 오류로 확인 불가 — 0건이 아님",
            null,
        )
        items += Item(
            Severity.INFO, "Edge 실행 상태 미확인",
            "저장소 오류로 확인 불가 — 부재는 false/0이 아님",
            StorageHealth.UNAVAILABLE_ACTION,
        )
        return items
    }

    private fun flightItem(hasActiveFlight: Boolean): Item? =
        if (!hasActiveFlight) {
            Item(
                Severity.INFO, "비행 없음",
                "임무 동기화 → 번들 선택 → 비행 시작 순서",
                null,
            )
        } else {
            Item(Severity.INFO, "비행 진행 중", "", null)
        }

    private fun linkItem(input: Input): Item? {
        // 2. MOP link (+ heartbeat freshness only while a flight exists).
        val sim = if (input.linkSimulated) " [SIMULATED]" else ""
        return when (input.linkState) {
            "CONNECTED" -> {
                val age = input.mopHeartbeatAgeMs
                when {
                    !input.hasActiveFlight || age == null ->
                        Item(
                            Severity.INFO, "MOP 연결$sim",
                            if (input.hasActiveFlight) "하트비트 미수신" else "",
                            null,
                        )
                    age > HEARTBEAT_STALE_MS ->
                        Item(
                            Severity.ATTENTION, "기체 하트비트 지연$sim",
                            "${age / 1000}s",
                            "Manifold 상태 확인",
                        )
                    else ->
                        Item(
                            Severity.INFO, "MOP 정상$sim",
                            "하트비트 ${age / 1000}s 전",
                            null,
                        )
                }
            }
            "CONNECTING" ->
                Item(Severity.ATTENTION, "MOP 연결 중$sim", "", null)
            else ->
                Item(
                    Severity.PROBLEM, "MOP 링크 끊김$sim",
                    "",
                    "링크 연결 후 재전송 확인",
                )
        }
    }

    private fun aircraftItem(aircraftConnected: Boolean?): Item? =
        when (aircraftConnected) {
            true -> Item(Severity.INFO, "기체 연결", "", null)
            false -> Item(Severity.ATTENTION, "기체 미연결", "", null)
            null -> Item(
                Severity.INFO, "기체 상태 알 수 없음",
                "링크가 보고하지 않음(추측 안 함)",
                null,
            )
        }

    private fun internetItem(internet: String): Item? =
        when (internet) {
            "no internet", "none" ->
                Item(Severity.INFO, "인터넷 없음", "오프라인 기록 유지", null)
            "connected (unvalidated)" ->
                Item(
                    Severity.INFO, "인터넷 미검증",
                    "동기화가 지연될 수 있음",
                    null,
                )
            else -> null
        }

    private fun recorderItem(input: Input): Item? =
        when (input.recorderState) {
            null -> Item(
                Severity.INFO, "레코더 없음",
                "벤치 빌드(가짜 녹화 없음)",
                null,
            )
            "RECORDING" -> when {
                input.recorderHasWriteFailure == true -> Item(
                    Severity.PROBLEM, "녹화 오류",
                    "일부 구간 저장 실패 · 이후 구간 재시도 중",
                    "로그 확인",
                )
                input.recorderWrittenFrames == 0L -> Item(
                    Severity.INFO, "녹화 입력 대기", "기록 요청됨 · 저장된 영상 프레임 없음", null,
                )
                else -> Item(Severity.INFO, "영상 녹화 중",
                    if (input.recorderWrittenFrames == null) "영상 저장 진행은 미확인" else "", null)
            }
            "ERROR" -> Item(
                Severity.PROBLEM, "녹화 오류",
                "불완전 .tmp는 증거가 되지 않음",
                "로그 확인",
            )
            else -> if (input.hasActiveFlight) {
                Item(
                    Severity.ATTENTION, "녹화 대기",
                    "",
                    "비행 시작 시 자동 시작; 계속 대기면 링크 확인",
                )
            } else {
                null
            }
        }

    fun evaluate(input: Input): List<Item> {
        if (!input.storageAvailable) return evaluateDegraded(input)
        val items = mutableListOf<Item>()

        // 1. Flight.
        flightItem(input.hasActiveFlight)?.let { items += it }

        // 1b. Preparation: an active session whose preparation is not
        // confirmed says so — by name, never as a merged "완료".
        input.preparationNotice?.takeIf { it.show }?.let { notice ->
            items += Item(
                Severity.ATTENTION,
                notice.label,
                notice.detail,
                notice.action,
            )
        }

        // 2. MOP link (+ heartbeat freshness only while a flight exists).
        linkItem(input)?.let { items += it }

        // 3. Aircraft connection (independent fact; null = cannot report).
        aircraftItem(input.aircraftConnected)?.let { items += it }

        // 4. Internet (offline is NORMAL for this product).
        internetItem(input.internet)?.let { items += it }

        // 5. Recording (bench = no recorder; a fake can never record).
        recorderItem(input)?.let { items += it }

        // 6. Evidence / sync (local facts only; server state is never guessed).
        when {
            !input.hasActiveFlight -> Unit
            input.artifactsTotal == 0 -> {
                items += Item(
                    Severity.INFO, "로컬 증거 없음",
                    "완료 세그먼트 없음(롤오버·종료 시 확정)",
                    null,
                )
                // Opinions are their own append-only data. A flight with
                // no media artifacts can still have unsynced opinions.
                if (input.pendingAnnotations > 0) {
                    items += Item(
                        Severity.ATTENTION, "늦은 의견 동기화 대기",
                        "${input.pendingAnnotations}건" +
                            if (!input.coreConfigured) " · Core 미설정" else "",
                        null,
                    )
                }
            }
            else -> {
                if (input.artifactsComplete < input.artifactsTotal) {
                    items += Item(
                        Severity.ATTENTION, "증거 수신 중",
                        "${input.artifactsComplete}/${input.artifactsTotal}",
                        null,
                    )
                }
                val unsynced = input.artifactsTotal - input.artifactsSynced
                if (!input.coreConfigured) {
                    items += Item(
                        Severity.INFO, "Core 미설정",
                        "업로드 비활성 — 증거는 로컬 보관 유지",
                        null,
                    )
                } else if (unsynced > 0) {
                    items += Item(
                        Severity.INFO, "미동기화 증거 $unsynced",
                        "인터넷 시 자동 / 수동 업로드 가능",
                        null,
                    )
                } else if (input.pendingAnnotations > 0) {
                    items += Item(
                        Severity.ATTENTION, "늦은 의견 동기화 대기",
                        "${input.pendingAnnotations}건",
                        null,
                    )
                } else {
                    items += Item(Severity.INFO, "증거 동기화 완료", "", null)
                }
            }
        }

        // 7. Observation quality (gap ≠ safety; zero events ≠ safe).
        if (input.hasActiveFlight) {
            val age = input.observationAgeMs
            if (age != null && age >= OBSERVATION_STALE_MS) {
                items += Item(
                    Severity.ATTENTION, "AI 관측 지연",
                    "${age / 1000}s",
                    "MOP 링크·기체 상태 확인",
                )
            }
            val skipped = input.skippedFrames
            if (skipped != null && skipped > 0) {
                items += Item(
                    Severity.INFO, "관측 공백 ${skipped}프레임",
                    "요약 기준(커버 ${input.coveredFrames ?: "-"}) — 사건 0건이 안전은 아님",
                    null,
                )
            }
            if (input.eventCount == 0) {
                items += Item(
                    Severity.INFO, "사건 0건",
                    "정상 기록일 수 있음 — 안전 확인 아님",
                    null,
                )
            }
        }

        // 8. Edge runtime facts (2026-10-21 contract §2/§5): reported by
        // the Edge, never inferred from ACKs or the legacy running bool.
        // Absent fields render "Edge 실행 상태 미확인" — never 0/없음.
        if (!input.hasActiveFlight) {
            items += Item(
                Severity.INFO, "Edge 실행 상태 미확인",
                "활성 세션 없음 · Edge 보고 대기",
                null,
            )
        } else if (!input.edgeRuntimeSeen && input.edgeRuntimeState == null) {
            items += Item(
                Severity.INFO, "Edge 실행 상태 미확인",
                "Edge 보고 없음 · 부재는 false/0이 아님",
                "Edge 보고 후 MISSION_STATUS 확인",
            )
        } else {
            val parsed = runCatching {
                ksi.rc.EdgeRuntimeState.valueOf(input.edgeRuntimeState ?: "UNKNOWN")
            }.getOrDefault(ksi.rc.EdgeRuntimeState.UNKNOWN)
            val label = EdgeRuntimePanel.stateLine(
                parsed,
                input.edgeProcessedFrames,
                input.edgeDegradedReasons,
            )
            val severity = when (parsed) {
                ksi.rc.EdgeRuntimeState.DEGRADED -> Severity.ATTENTION
                else -> Severity.INFO
            }
            val detail = buildString {
                append(
                    if (input.edgeProcessedFrames != null) {
                        "처리 ${input.edgeProcessedFrames}프레임"
                    } else {
                        "처리 수 미확인"
                    },
                )
                if (input.edgeDegradedReasons.isNotEmpty()) {
                    append(" · ${input.edgeDegradedReasons.joinToString(", ")}")
                }
                if (input.edgeInputState.isNotEmpty()) {
                    append(" · 입력 ${input.edgeInputState.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                }
            }
            items += Item(severity, label, detail, null)
        }
        if (input.hasActiveFlight && input.edgeLinkUp == false) {
            items += Item(
                Severity.PROBLEM, "Edge 통신 단절",
                "마지막 확인 실행 시각 ${input.edgeLastConfirmedUtc ?: "미확인"} · " +
                    "현재 상태를 정상으로 추정하지 않음",
                "링크 복구 후 MISSION_STATUS 대조",
            )
        }
        if (input.hasActiveFlight && input.edgeDegradedReasons.any {
                it == "video_input_lost" || it == "video_input_stale"
            }
        ) {
            items += Item(
                Severity.ATTENTION, "영상 입력 단절 · 관측 저하",
                input.edgeDegradedReasons.joinToString(", "),
                "영상 입력·Edge 상태 확인",
            )
        }

        return items
    }
}
