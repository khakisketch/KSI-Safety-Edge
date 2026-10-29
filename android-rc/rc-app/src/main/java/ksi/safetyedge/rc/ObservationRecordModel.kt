package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.RcFullStore
import ksi.rc.SessionResultLog
import ksi.rc.SessionResultRecord

/**
 * D5 — 관측 기록 모델: "비행 기록 검토"를 **"관측 기록"**으로 재구성한다.
 *
 * 제품 방향: KSI Safety Edge의 가치는 "드론이 어디로 비행했나"가 아니라
 * **"무엇을 관측했고, 어떤 위험 후보가 있었으며, 그 판단을 신뢰할 수 있는가"**다.
 * 비행 이력(경로·고도·속도)은 DJI가 이미 갖고 있으므로 KSI가 다시 만들지 않고,
 * 관측 세션의 **부가 정보**로 내린다.
 *
 * 이 모델은 rc-core 저장소(로컬 사실)에서만 유도한다. 서버 추정·없는 값 채우기·
 * 안전 판정을 하지 않는다. 세 가지 축을 **끊지 않는다**:
 * ① 관측 건수 ② 확인 필요(위험 후보) ③ 분석 공백(판단 근거 없음) — 0건과 "미수신"이 다르다.
 *
 * RC의 역할은 "짧게 확인 + 오탐 의심 표시"까지다. 비교·보고는 Core 웹의 몫이다.
 *
 * Android-free on purpose: host JVM에서 unit-test된다.
 */
object ObservationRecordModel {

    /** 관측 세션 1건의 요약. */
    data class SessionSummary(
        val flightId: String,
        val siteId: String,
        val siteName: String?,
        /** 세션 시작/종료 (RC 수신시각 근사). */
        val startedAtUtc: String?,
        val endedAtUtc: String?,
        /** 관측 건수 (AI 관측). */
        val observationCount: Int,
        /** 확인 필요 = 위험 후보(객체 관측이 아닌 사건). */
        val reviewNeededCount: Int,
        /** 확인 필요 중 검토 대기(NEEDS_REVIEW) 표시가 남은 건. */
        val reviewPendingCount: Int,
        /** 확인 필요 중 오탐 의심 표시가 남은 건. */
        val falsePositiveCount: Int,
        /** 분석 공백 = 판단 근거가 없었던 구간/건수. */
        val analysisGapCount: Int,
        /** 세션 판정 (SUCCESS/FAILED/INTERRUPTED/UNKNOWN). 보존 성공과 별개 축. */
        val verdict: String?,
        /** 로컬 증거 완수/전체. */
        val artifactCompleteCount: Int,
        val artifactCount: Int,
        /** 서버 커밋 확인된 증거 수. */
        val artifactCommittedCount: Int,
        /** 아직 서버로 전송되지 않은 증거 수. */
        val artifactPendingCount: Int,
        /** MOP 링크 공백(미회수 구간) — "유실 없음" 판정의 근거. */
        val linkGapCount: Int,
    ) {
        /** 관측 데이터가 전혀 없으면 "관측 0건"이 아니라 "분석 결과 알 수 없음"이다. */
        val analysisUnknown: Boolean get() = observationCount == 0

        /** 판단 근거가 전혀 없으면 성공으로 읽을 수 없다. */
        val unjudgeable: Boolean get() = analysisUnknown || analysisGapCount > 0
    }

    /**
     * 동기화 신뢰성: 관측 기록이 유실되지 않았는가.
     * 인터넷 없이 운용하는 Edge에서는 이것이 편의 기능이 아니라 **핵심 신뢰성 요구**다.
     */
    data class SyncState(
        /** 로컬에 있는 관측 세션 수. */
        val localSessionCount: Int,
        /** 서버 커밋 확인이 끝난 증거 수. */
        val committedArtifactCount: Int,
        /** 로컬 증거 총수. */
        val localArtifactCount: Int,
        /** 아직 전송 대기 중인 증거 수. */
        val pendingArtifactCount: Int,
        /** Core에 등록된 세션 수(서버가 아는 것). */
        val serverSessionCount: Int?,
    ) {
        /** 로컬에 있고 서버 커밋이 확인되지 않은 증거가 있으면 "유실 위험"을 표시한다. */
        val unconfirmedLocalEvidence: Boolean
            get() = pendingArtifactCount > 0

        /**
         * "관측 기록이 유실되지 않았는지"를 확인할 수 없는 상태인가.
         * 서버를 아직 조회하지 않았다면 **유실 여부를 모른다**는 사실 자체를 드러낸다.
         */
        val completenessUnknown: Boolean
            get() = serverSessionCount == null
    }

    /**
     * 로컬 저장소의 모든 비행(=관측 세션) 요약. newest first.
     * 비행 1회 = 관측 세션 1회이므로 flight 단위가 곧 관측 세션 단위다.
     */
    fun sessions(
        store: RcFullStore,
        verdicts: SessionResultLog? = null,
    ): List<SessionSummary> =
        store.flights().asReversed().mapNotNull { flight ->
            val flightId = flight.get("flight_id")?.asText() ?: return@mapNotNull null
            val siteId = flight.get("site_id")?.asText() ?: "-"
            val events = store.eventsFor(flightId)
            val artifacts = store.artifactsFor(flightId)
            val observations = store.observationsFor(flightId)
            val annotations = events.map {
                store.annotationsFor(it.get("event_id")?.asText() ?: "")
            }
            val candidateEvents = events.filter {
                it.get("event_type")?.asText() != "DETECTED_ONLY"
            }
            val verdict = verdicts?.let { log ->
                log.recordsForFlight(flightId).lastOrNull()?.result?.name
            }
            val boots = store.bootsFor(flightId)
            val completeArtifacts = artifacts.count {
                store.isArtifactComplete(it.get("artifact_id").asText())
            }
            val committedArtifacts = artifacts.count {
                store.isArtifactSynced(it.get("artifact_id").asText())
            }
            SessionSummary(
                flightId = flightId,
                siteId = siteId,
                siteName = store.site(siteId)?.get("name")?.asText(),
                startedAtUtc = flight.get("started_at_utc")?.takeIf { it.isTextual }?.asText(),
                endedAtUtc = flight.get("ended_at_utc")?.takeIf { it.isTextual }?.asText(),
                observationCount = observations.size,
                reviewNeededCount = candidateEvents.size,
                reviewPendingCount = annotations.count {
                    effectiveState(it) == FlightHistoryModel.ANNOTATION_NEEDS_REVIEW
                },
                falsePositiveCount = annotations.count {
                    effectiveState(it) == FlightHistoryModel.ANNOTATION_FALSE_POSITIVE
                },
                analysisGapCount = boots.sumOf { store.gapsForBoot(it).size } +
                    observations.count { it.get("geometry_status")?.asText() == "WITHHELD" },
                verdict = verdict,
                artifactCompleteCount = completeArtifacts,
                artifactCount = artifacts.size,
                artifactCommittedCount = committedArtifacts,
                artifactPendingCount = (completeArtifacts - committedArtifacts).coerceAtLeast(0),
                linkGapCount = boots.sumOf { store.gapsForBoot(it).size },
            )
        }

    /**
     * 동기화 상태. [serverSessionCount]는 서버 조회 결과이며 **null = 아직 조회하지 않음**
     * (유실 여부를 모른다는 사실을 드러내야 하므로 0으로 대체하지 않는다).
     */
    fun syncState(
        store: RcFullStore,
        serverSessionCount: Int?,
    ): SyncState {
        val artifacts = store.flights().flatMap { store.artifactsFor(it.get("flight_id")?.asText() ?: "") }
        val complete = artifacts.count { store.isArtifactComplete(it.get("artifact_id").asText()) }
        val committed = artifacts.count { store.isArtifactSynced(it.get("artifact_id").asText()) }
        return SyncState(
            localSessionCount = store.flights().size,
            committedArtifactCount = committed,
            localArtifactCount = artifacts.size,
            pendingArtifactCount = (complete - committed).coerceAtLeast(0),
            serverSessionCount = serverSessionCount,
        )
    }

    /** append-only 주석 사슬의 유효 상태(마지막 기록이 이김). */
    private fun effectiveState(records: List<ObjectNode>): String? =
        FlightHistoryModel.effectiveAnnotation(records)

    /**
     * 관측 요약 한 줄. 카운트 0건과 "판단 근거 없음"을 **같은 숫자로 표시하지 않는다**.
     */
    fun summaryLine(summary: SessionSummary): String = buildString {
        append("AI 관측 ${summary.observationCount}건")
        append(" · 확인 필요 ${summary.reviewNeededCount}건")
        append(" · 분석 공백 ${summary.analysisGapCount}건")
        append(" · 세션 판정 ${summary.verdict ?: "미확인"}")
    }

    /**
     * 관측 세션 1건의 **검토 준비·중앙 보관** 한 줄.
     *
     * 요약(무엇을 봤는가)과 동기화(유실 여부) 사이에 한 문장을 더 얹는다:
     * "지금 이 관측을 검토할 수 있는가"(로컬 사실)와 "중앙에 들어갔는가"
     * (서버 확인 사실)는 **다른 축**이다. 둘을 한 문장으로 합치면 "로컬에 있다"를
     * "중앙에 있다"로 오독하게 된다(여정 정본 §3).
     */
    fun reviewReadinessLine(
        store: RcFullStore,
        flightId: String,
        sourceEvidence: ReviewReadinessModel.SourceEvidence =
            ReviewReadinessModel.SourceEvidence(),
    ): String =
        ReviewReadinessModel.sessionLine(
            ReviewReadinessModel.session(store, flightId, sourceEvidence)
        )

    /**
     * 동기화/유실 확인 한 줄. "확인 못 했다"와 "유실됐다"는 다른 문장이다.
     */
    fun syncLine(state: SyncState): String = buildString {
        append("로컬 관측 세션 ${state.localSessionCount}건")
        append(" · 증거 ${state.committedArtifactCount}/${state.localArtifactCount} 서버 커밋 확인")
        if (state.pendingArtifactCount > 0) {
            append(" · 미전송 ${state.pendingArtifactCount}건")
        }
        if (state.completenessUnknown) {
            append(" · 서버 미조회 — 유실 여부 아직 확인 못 함")
        } else {
            append(" · 서버 세션 ${state.serverSessionCount}건")
        }
    }
}
