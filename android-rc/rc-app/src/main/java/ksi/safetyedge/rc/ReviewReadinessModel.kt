package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.RcFullStore

/**
 * D6 — "현장 검토 준비"와 "중앙 보관"은 **서로 다른 축**이다.
 *
 * RC의 역할은 현장에서 짧게 확인하는 것이고, Core의 역할은 보관·비교·보고다.
 * 그래서 "이 관측을 지금 검토할 수 있는가"와 "이 관측이 중앙에 들어갔는가"는
 * 한 줄로 합치면 안 된다. 합치면 "로컬에 있다"를 "중앙에 있다"로 오독하거나,
 * 반대로 "아직 전송 안 됐다"를 "분석 결과가 없다"로 오독하게 된다.
 *
 * ① 현장 검토 준비 (field-review ready) — **로컬 사실**만으로 판정한다.
 *    판단 근거 3개를 끊지 않는다:
 *      - 정확한 리비전: 적용된 미션 리비전과 부트가 특정된다.
 *      - 디코딩 가능: 검토에 필요한 값(탐지·품질·기하·모델 digest)이 들어 있다.
 *      - 링크 일치: 비행·부트·프레임 순번이 RC가 기록한 사실과 어긋나지 않는다.
 *    하나라도 어긋나면 "준비 아님"이며 **어떤 축이 어긋났는지 이름을 밝힌다**.
 *    관측이 0건이면 "준비"가 아니라 "분석 결과 알 수 없음"이다(0건≠안전).
 *
 * ② 중앙 보관 (centrally stored) — **서버 확인 사실**만으로 판정한다.
 *    로컬에 커밋 영수증(durable_commit_id)이 있으면 STORED, 없으면 NOT_CONFIRMED.
 *    NOT_CONFIRMED는 "서버에 없다"가 아니라 "RC가 확인하지 못했다"다.
 *    서버 상태를 추측하지 않는다(정직 규칙).
 *
 * Android-free on purpose: host JVM에서 unit-test된다.
 */
object ReviewReadinessModel {

    /** 중앙 보관 상태. "확인 못 함"과 "안 됐다"를 구분한다. */
    enum class CentralStorage {
        /** Core 커밋 영수증이 로컬에 있다. */
        STORED,

        /** 커밋 영수증이 없다 — "중앙에 없다"가 아니라 "RC가 확인하지 못했다". */
        NOT_CONFIRMED,
    }

    /**
     * 한 건의 관측에 대한 검토 준비 상태.
     *
     * [centralStorage]는 관측이 속한 부트(boot)의 중앙 보관 상태다 —
     * 커밋은 부트 단위(매니페스트 단위)로 확인되기 때문이다.
     */
    data class ObservationReadiness(
        val observationId: String,
        val flightId: String,
        val edgeBootId: String,
        /** 축 1: 정확한 리비전. */
        val exactRevision: Boolean,
        /** 축 2: 디코딩 가능. */
        val decodable: Boolean,
        /** 축 3: 링크 일치. */
        val linkConsistent: Boolean,
        /** 중앙 보관(부트 단위). */
        val centralStorage: CentralStorage,
    ) {
        /** 현장에서 지금 검토할 수 있는가. */
        val fieldReviewReady: Boolean
            get() = exactRevision && decodable && linkConsistent

        /** 준비되지 않은 사유(축 이름). 준비됨이면 빈 목록. */
        val notReadyReasons: List<String>
            get() = buildList {
                if (!exactRevision) add("리비전 불명확")
                if (!decodable) add("디코딩 불가")
                if (!linkConsistent) add("링크 불일치")
            }
    }

    /**
     * 원본 근거 축 — **실측**이다(메타데이터가 아니라 바이트를 확인한 결과).
     *
     * 사건이 참조하는 근거 artifact에 대해: 참조 개수와 그중 **로컬 바이트가
     * 해시까지 일치하는 개수**. 참조가 하나도 없으면 RC에는 아직 원본이 오지
     * 않은 것이며(Edge가 보관 중), 그 상태를 "준비됨"으로 말하지 않는다.
     */
    data class EvidenceReadiness(
        /** 사건이 참조한 근거 artifact id 수(중복 제거). */
        val referencedArtifactCount: Int,
        /** 그중 로컬 바이트가 해시까지 일치하는 개수. */
        val intactArtifactCount: Int,
    ) {
        /** 근거가 하나도 참조되지 않았다 = 원본 미수신(Edge 보관 중). */
        val nothingReceived: Boolean get() = referencedArtifactCount == 0
        /** 참조된 근거가 모두 무결하다 = 원본을 열어볼 수 있다. */
        val fullyIntact: Boolean
            get() = referencedArtifactCount > 0 &&
                intactArtifactCount == referencedArtifactCount
    }

    /**
     * 원본 근거의 **중앙 보관** 상태 — **Core가 발급한 영수증** 기준.
     *
     * 왜 별도 축인가: "RC가 올린 기록이 커밋됨"(로컬 커밋 영수증)과
     * "Edge의 원본 근거가 중앙에 있음"(컬렉션 영수증)은 **다른 사실**이다.
     * 둘을 한 줄로 합치면 로컬 기록이 보관됐다고 원본도 보관됐다고 읽힌다.
     *
     * NOT_QUERIED는 "서버에 물어보지 않았다"는 뜻이다 — "없다"가 아니다.
     */
    enum class SourceStorage { NOT_QUERIED, STORED, NOT_STORED }

    data class SourceEvidence(
        val state: SourceStorage = SourceStorage.NOT_QUERIED,
        val collectionCount: Int = 0,
        val assetCount: Int = 0,
    )

    /** 한 관측 세션(비행)의 검토 준비 요약. */
    data class SessionReadiness(
        val flightId: String,
        /** 로컬에 기록된 관측 건수. */
        val observationCount: Int,
        /** 현장 검토 준비가 된 건수. */
        val readyCount: Int,
        /** 중앙 보관 확인(영수증 보유) 건수. */
        val centralStoredCount: Int,
        /** 원본 근거의 중앙 보관(Core 영수증 기준). */
        val sourceEvidence: SourceEvidence = SourceEvidence(),
        /** 원본 근거 축(실측). */
        val evidence: EvidenceReadiness,
        /** 세션 전체의 중앙 보관 상태 (한 건이라도 미확인이면 미확인). */
        val centralStorage: CentralStorage,
        /** 준비되지 않은 사유별 건수. */
        val notReadyReasonCounts: Map<String, Int>,
    ) {
        /** 검토 준비가 안 된 건수. */
        val notReadyCount: Int get() = observationCount - readyCount

        /**
         * 세션 전체가 검토 준비 상태인가.
         *
         * 관측 0건은 "준비"가 아니다(분석 결과 알 수 없음). 그리고 **원본 근거가
         * 참조되어 무결해야** 한다 — 판단 숫자만 읽을 수 있는 상태를 "검토
         * 준비"로 말하지 않는다(그림이 없으면 검토가 아니라 신빙이다).
         */
        val fieldReviewReady: Boolean
            get() = observationCount > 0 && readyCount == observationCount &&
                evidence.fullyIntact

        /** 중앙 보관이 확인되지 않았는가(영수증 없음). */
        val centralNotConfirmed: Boolean
            get() = centralStorage == CentralStorage.NOT_CONFIRMED
    }

    private val SYNC_QUALITIES = setOf("VALID", "DEGRADED", "UNKNOWN", "INVALID")
    private val DIGEST_PATTERN = Regex("[a-f0-9]{64}")

    /**
     * 비행 한 건의 관측별 검토 준비 상태.
     *
     * 로컬 저장소(rc-core)만 읽는다. 서버를 조회하지 않으므로 이 결과는
     * "RC가 지금 갖고 있는 사실"이다.
     */
    fun observations(store: RcFullStore, flightId: String): List<ObservationReadiness> {
        val knownBoots = store.bootsFor(flightId).toSet()
        val committedBoots = store.commitReceiptsFor(flightId)
            .mapNotNull { it.get("edge_boot_id")?.takeIf { node -> node.isTextual }?.asText() }
            .toSet()
        return store.observationsFor(flightId).map { observation ->
            val bootId = observation.get("edge_boot_id")?.takeIf { it.isTextual }?.asText().orEmpty()
            ObservationReadiness(
                observationId = observation.get("observation_id")?.takeIf { it.isTextual }?.asText().orEmpty(),
                flightId = observation.get("flight_id")?.takeIf { it.isTextual }?.asText().orEmpty(),
                edgeBootId = bootId,
                exactRevision = hasExactRevision(observation, knownBoots),
                decodable = isDecodable(observation),
                linkConsistent = hasConsistentLinks(observation, flightId, knownBoots),
                centralStorage = if (bootId.isNotBlank() && bootId in committedBoots) {
                    CentralStorage.STORED
                } else {
                    CentralStorage.NOT_CONFIRMED
                },
            )
        }
    }

    /** 비행 한 건의 검토 준비 요약. */
    fun session(
        store: RcFullStore,
        flightId: String,
        sourceEvidence: SourceEvidence = SourceEvidence(),
    ): SessionReadiness {
        val items = observations(store, flightId)
        val evidence = evidenceReadiness(store, flightId)
        val reasonCounts = LinkedHashMap<String, Int>()
        for (item in items) {
            for (reason in item.notReadyReasons) {
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            }
        }
        return SessionReadiness(
            flightId = flightId,
            observationCount = items.size,
            readyCount = items.count { it.fieldReviewReady },
            centralStoredCount = items.count { it.centralStorage == CentralStorage.STORED },
            sourceEvidence = sourceEvidence,
            centralStorage = if (items.isNotEmpty() &&
                items.all { it.centralStorage == CentralStorage.STORED }
            ) {
                CentralStorage.STORED
            } else {
                CentralStorage.NOT_CONFIRMED
            },
            evidence = evidence,
            notReadyReasonCounts = reasonCounts,
        )
    }

    /**
     * 원본 근거 축 — 사건이 참조하는 artifact를 **바이트까지** 확인한다.
     *
     * 참조는 `evidence_artifact_ids`에서만 온다(없는 필드를 지어내지 않는다).
     * 무결 판정은 저장소의 검증 레벨(`isArtifactIntact`: 범위·크기·sha256)을
     * 쓴다 — 목록 렌더링의 싼 레벨이 아니다.
     */
    fun evidenceReadiness(store: RcFullStore, flightId: String): EvidenceReadiness {
        val referenced = store.eventsFor(flightId)
            .flatMap { event ->
                val ids = event.get("evidence_artifact_ids")
                if (ids == null || !ids.isArray) {
                    emptyList()
                } else {
                    ids.mapNotNull { node ->
                        if (node.isTextual) node.asText() else null
                    }
                }
            }
            .distinct()
        val intact = referenced.count { store.isArtifactIntact(it) }
        return EvidenceReadiness(
            referencedArtifactCount = referenced.size,
            intactArtifactCount = intact,
        )
    }

    /**
     * 화면 한 줄. "검토 준비"와 "중앙 보관"을 **다른 문장**으로 나눈다.
     * 0건일 때는 "분석 결과 알 수 없음"을 먼저 말한다(0건≠안전).
     */
    fun sessionLine(readiness: SessionReadiness): String = buildString {
        if (readiness.observationCount == 0) {
            append("분석 결과 알 수 없음(관측 0건)")
            append(" · 현장 검토 준비 판단 불가")
            return@buildString
        }
        append("관측 기록 검토 ${readiness.readyCount}/${readiness.observationCount}건")
        if (readiness.notReadyCount > 0) {
            append(" · 미완성 사유 ")
            append(
                readiness.notReadyReasonCounts.entries
                    .joinToString(", ") { "${it.key} ${it.value}건" }
            )
        }
        // 원본 근거 — 실측. 미수신이면 "없다"고 말한다(준비처럼 말하지 않는다).
        append(" · 원본 근거 ")
        append(
            when {
                readiness.evidence.nothingReceived ->
                    "미수신(Edge 보관) · 현장 검토 준비 아님"
                readiness.evidence.fullyIntact ->
                    "무결 ${readiness.evidence.intactArtifactCount}건 · 현장 검토 준비됨"
                else ->
                    "무결 ${readiness.evidence.intactArtifactCount}/" +
                        "${readiness.evidence.referencedArtifactCount}건 · 일부만 열림"
            }
        )
        // 원본 근거의 중앙 보관 — Core 영수증. 조회하지 않았으면 "확인 안 함".
        append(" · 원본 근거 중앙 보관 ")
        append(
            when (readiness.sourceEvidence.state) {
                SourceStorage.NOT_QUERIED -> "확인 안 함"
                SourceStorage.STORED ->
                    "영수증 ${readiness.sourceEvidence.collectionCount}건" +
                        "(자산 ${readiness.sourceEvidence.assetCount}개) 확인"
                SourceStorage.NOT_STORED -> "영수증 없음"
            }
        )
        append(" · 중앙 보관 ")
        append(
            if (readiness.centralStorage == CentralStorage.STORED) {
                "확인 ${readiness.centralStoredCount}건"
            } else {
                "미확인 ${readiness.observationCount - readiness.centralStoredCount}건"
            }
        )
    }

    // -- 판단 축 -------------------------------------------------------------

    /**
     * 축 1 — 정확한 리비전.
     * 미션 리비전과 부트가 특정되어야 "어느 임무의 어느 부트에서 온 판단인가"가
     * 말된다. RC가 실제로 기록한 부트인지까지 확인한다(다른 비행의 부트를
     * 붙여 붙이면 검토가 아니라 추측이 된다).
     */
    private fun hasExactRevision(observation: ObjectNode, knownBoots: Set<String>): Boolean {
        val revision = observation.get("mission_revision_id")
            ?.takeIf { it.isTextual }?.asText().orEmpty()
        val boot = observation.get("edge_boot_id")
            ?.takeIf { it.isTextual }?.asText().orEmpty()
        if (revision.isBlank() || boot.isBlank()) return false
        return boot in knownBoots
    }

    /**
     * 축 2 — 디코딩 가능.
     * 검토에 필요한 값이 실제로 들어 있는가. 없는 값을 0으로 채우거나
     * 기본값으로 메우면 검토가 아니라 추정이 된다.
     */
    private fun isDecodable(observation: ObjectNode): Boolean {
        val digest = observation.get("model_digest")?.takeIf { it.isTextual }?.asText().orEmpty()
        if (!DIGEST_PATTERN.matches(digest)) return false
        val syncQuality = observation.get("sync_quality")?.takeIf { it.isTextual }?.asText()
        if (syncQuality == null || syncQuality !in SYNC_QUALITIES) return false
        val geometry = observation.get("geometry_status")?.takeIf { it.isTextual }?.asText()
        if (geometry.isNullOrBlank()) return false
        val detections = observation.get("detections")
        if (detections == null || !detections.isArray) return false
        val reasons = observation.get("quality_reasons")
        if (reasons == null || !reasons.isArray) return false
        val seq = observation.get("edge_frame_seq")
        if (seq == null || !seq.isIntegralNumber || seq.asLong() < 0L) return false
        return true
    }

    /**
     * 축 3 — 링크 일치.
     * 관측이 주장하는 비행·부트가 RC가 기록한 비행·부트와 같고, 프레임 순번이
     * 음수가 아니어야 한다. 어긋나면 다른 비행의 판단을 빌려 쓰는 셈이다.
     */
    private fun hasConsistentLinks(
        observation: ObjectNode,
        flightId: String,
        knownBoots: Set<String>,
    ): Boolean {
        val flight = observation.get("flight_id")?.takeIf { it.isTextual }?.asText().orEmpty()
        val boot = observation.get("edge_boot_id")?.takeIf { it.isTextual }?.asText().orEmpty()
        if (flight.isBlank() || flight != flightId) return false
        if (boot.isBlank() || boot !in knownBoots) return false
        val source = observation.get("source_kind")?.takeIf { it.isTextual }?.asText()
        if (source != null && source != "PSDK_EDGE") return false
        return true
    }
}
