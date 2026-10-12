package ksi.safetyedge.rc

/**
 * Operator-facing status contract (B-1): a pure, host-tested summary of
 * "what is happening and what should I do", derived ONLY from real session
 * facts — nothing is invented and nothing is hidden.
 *
 * This is the BEHAVIOR contract, not the look: layout, typography, colors
 * and touch ergonomics are owned by the design workspace (see
 * `docs/verification/operator-flow-spec.md` §디자인 위임). The UI renders
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
    )

    /** Heartbeat older than this shows "지연" (indicator only). */
    const val HEARTBEAT_STALE_MS = 10_000L

    /** Observation older than this shows "지연" (indicator only). */
    const val OBSERVATION_STALE_MS = 5_000L

    fun evaluate(input: Input): List<Item> {
        val items = mutableListOf<Item>()

        // 1. Flight.
        items += if (!input.hasActiveFlight) {
            Item(
                Severity.INFO, "비행 없음",
                "임무 동기화 → 번들 선택 → 비행 시작 순서",
                null,
            )
        } else {
            Item(Severity.INFO, "비행 진행 중", "", null)
        }

        // 2. MOP link (+ heartbeat freshness only while a flight exists).
        val sim = if (input.linkSimulated) " [SIMULATED]" else ""
        when (input.linkState) {
            "CONNECTED" -> {
                val age = input.mopHeartbeatAgeMs
                when {
                    !input.hasActiveFlight || age == null ->
                        items += Item(
                            Severity.INFO, "MOP 연결$sim",
                            if (input.hasActiveFlight) "하트비트 미수신" else "",
                            null,
                        )
                    age > HEARTBEAT_STALE_MS ->
                        items += Item(
                            Severity.ATTENTION, "기체 하트비트 지연$sim",
                            "${age / 1000}s",
                            "Manifold 상태 확인",
                        )
                    else ->
                        items += Item(
                            Severity.INFO, "MOP 정상$sim",
                            "하트비트 ${age / 1000}s 전",
                            null,
                        )
                }
            }
            "CONNECTING" ->
                items += Item(Severity.ATTENTION, "MOP 연결 중$sim", "", null)
            else ->
                items += Item(
                    Severity.PROBLEM, "MOP 링크 끊김$sim",
                    "",
                    "링크 연결 후 재전송 확인",
                )
        }

        // 3. Aircraft connection (independent fact; null = cannot report).
        items += when (input.aircraftConnected) {
            true -> Item(Severity.INFO, "기체 연결", "", null)
            false -> Item(Severity.ATTENTION, "기체 미연결", "", null)
            null -> Item(
                Severity.INFO, "기체 상태 알 수 없음",
                "링크가 보고하지 않음(추측 안 함)",
                null,
            )
        }

        // 4. Internet (offline is NORMAL for this product).
        when (input.internet) {
            "no internet", "none" ->
                items += Item(Severity.INFO, "인터넷 없음", "오프라인 기록 유지", null)
            "connected (unvalidated)" ->
                items += Item(
                    Severity.INFO, "인터넷 미검증",
                    "동기화가 지연될 수 있음",
                    null,
                )
        }

        // 5. Recording (bench = no recorder; a fake can never record).
        when (input.recorderState) {
            null -> items += Item(
                Severity.INFO, "레코더 없음",
                "벤치 빌드(가짜 녹화 없음)",
                null,
            )
            "RECORDING" ->
                items += Item(Severity.INFO, "영상 녹화 중", "", null)
            "ERROR" -> items += Item(
                Severity.PROBLEM, "녹화 오류",
                "불완전 .tmp는 증거가 되지 않음",
                "로그 확인",
            )
            else -> if (input.hasActiveFlight) {
                items += Item(
                    Severity.ATTENTION, "녹화 대기",
                    "",
                    "비행 시작 시 자동 시작; 계속 대기면 링크 확인",
                )
            }
        }

        // 6. Evidence / sync (local facts only; server state is never guessed).
        when {
            !input.hasActiveFlight -> Unit
            input.artifactsTotal == 0 ->
                items += Item(
                    Severity.INFO, "로컬 증거 없음",
                    "완료 세그먼트 없음(롤오버·종료 시 확정)",
                    null,
                )
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
            if (age != null && age > OBSERVATION_STALE_MS) {
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

        return items
    }
}
