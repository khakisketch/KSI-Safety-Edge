package ksi.safetyedge.rc

/**
 * Presentation-only summary for the RC cockpit. Every status and severity is
 * sourced from [OperatorStatus]; this class shortens display text but never
 * infers a healthier state or creates an operator action.
 */
internal object CockpitStatusPresenter {
    internal data class Fact(
        val label: String,
        val value: String,
        val severity: OperatorStatus.Severity,
        val detail: String = "",
        val action: String? = null,
    ) {
        val contentDescription: String
            get() = buildString {
                append(label).append(": ").append(value)
                if (detail.isNotBlank()) append(" · ").append(detail)
                action?.let { append(" · 다음 조치: ").append(it) }
                append(".")
            }
    }

    internal data class Overview(
        val ksiSession: Fact,
        val mop: Fact,
        val aircraft: Fact,
        val recorder: Fact,
        val evidence: Fact,
        val priority: OperatorStatus.Item?,
    )

    fun present(
        items: List<OperatorStatus.Item>,
        hasActiveFlight: Boolean,
        unsyncedArtifactCount: Int? = null,
    ): Overview {
        val mopItem = items.firstOrNull {
            it.label.startsWith("MOP") || it.label.contains("하트비트")
        }
        val aircraftItem = items.firstOrNull {
            it.label == "기체 연결" ||
                it.label == "기체 미연결" ||
                it.label == "기체 상태 알 수 없음"
        }
        val recorderItem = items.firstOrNull {
            it.label == "레코더 없음" || it.label.startsWith("녹화") || it.label.startsWith("영상 녹화")
        }
        val evidenceItems = items.filter(::isEvidenceFact)
        return Overview(
            ksiSession = Fact(
                label = "KSI 세션",
                value = if (hasActiveFlight) "진행 중" else "세션 없음",
                severity = OperatorStatus.Severity.INFO,
                detail = if (hasActiveFlight) {
                    "활성 로컬 세션 · 실제 기체 비행 상태와는 별도"
                } else {
                    "종료할 활성 세션 없음"
                },
            ),
            mop = mopFact(mopItem),
            aircraft = aircraftFact(aircraftItem),
            recorder = recorderFact(recorderItem),
            evidence = evidenceFact(evidenceItems, hasActiveFlight, unsyncedArtifactCount),
            priority = items.firstOrNull { it.label == "녹화 오류" }
                ?: items.firstOrNull { it.severity != OperatorStatus.Severity.INFO },
        )
    }

    private fun mopFact(item: OperatorStatus.Item?): Fact {
        if (item == null) return unknown("MOP 링크")
        val value = when {
            item.label.startsWith("기체 하트비트") -> item.label.removePrefix("기체 ")
            item.label.startsWith("MOP ") -> item.label.removePrefix("MOP ")
            else -> item.label
        }
        return Fact("MOP 링크", appendDetail(value, item.detail), item.severity, action = item.action)
    }

    private fun aircraftFact(item: OperatorStatus.Item?): Fact {
        if (item == null) return unknown("기체")
        val value = if (item.label == "기체 상태 알 수 없음") {
            "알 수 없음"
        } else {
            item.label.removePrefix("기체 ")
        }
        return Fact("기체", value, item.severity, item.detail, item.action)
    }

    private fun recorderFact(item: OperatorStatus.Item?): Fact {
        if (item == null) return unknown("RC 프록시")
        val value = when {
            item.label == "레코더 없음" -> "레코더 없음"
            item.label == "녹화 입력 대기" -> "입력 대기"
            item.label == "영상 녹화 중" && item.detail.contains("미확인") -> "쓰기 수치 미확인"
            item.label == "영상 녹화 중" -> "쓰기 중"
            item.label == "녹화 오류" -> "녹화 오류"
            item.label == "녹화 대기" -> "녹화 대기"
            else -> item.label
        }
        return Fact("RC 프록시", value, item.severity, item.detail, item.action)
    }

    private fun evidenceFact(
        items: List<OperatorStatus.Item>,
        hasActiveFlight: Boolean,
        unsyncedArtifactCount: Int?,
    ): Fact {
        if (!hasActiveFlight) {
            return Fact(
                "로컬 증거",
                "활성 비행 없음",
                OperatorStatus.Severity.INFO,
                "전체 로컬 동기화 상태는 증거 화면에서 확인",
            )
        }
        if (items.isEmpty()) return unknown("로컬 증거")

        val unsynced = items.firstOrNull { it.label.startsWith("미동기화 증거 ") }
        val receiving = items.firstOrNull { it.label == "증거 수신 중" }
        val lateOpinion = items.firstOrNull { it.label == "늦은 의견 동기화 대기" }
        val primary = items.maxByOrNull { severityRank(it.severity) } ?: items.first()
        val sourceCount = unsynced?.label?.removePrefix("미동기화 증거 ")?.toIntOrNull()
        val pendingCount = unsyncedArtifactCount ?: sourceCount
        val coreUnconfigured = items.any { it.label == "Core 미설정" }
        val showPendingCount = pendingCount != null && (pendingCount > 0 || coreUnconfigured)
        val value = when {
            showPendingCount -> {
                "미동기화 ${pendingCount}건" +
                    (receiving?.let { " · 수신 ${it.detail}" } ?: "") +
                    (if (coreUnconfigured) " · Core 미설정" else "")
            }
            lateOpinion != null -> "늦은 의견 ${lateOpinion.detail.ifBlank { "대기" }}"
            receiving != null -> "수신 중 ${receiving.detail}"
            items.any { it.label == "로컬 증거 없음" } -> "증거 없음"
            items.any { it.label == "증거 동기화 완료" } -> "동기화 완료"
            items.any { it.label == "Core 미설정" } -> "Core 미설정"
            else -> primary.label
        }
        return Fact(
            "로컬 증거",
            value,
            items.maxByOrNull { severityRank(it.severity) }?.severity ?: OperatorStatus.Severity.INFO,
            primary.detail,
            primary.action,
        )
    }

    private fun isEvidenceFact(item: OperatorStatus.Item): Boolean =
        item.label.startsWith("로컬 증거") ||
            item.label.startsWith("증거") ||
            item.label.startsWith("미동기화 증거") ||
            item.label == "Core 미설정" ||
            item.label.startsWith("늦은 의견")

    private fun appendDetail(value: String, detail: String): String =
        if (detail.isBlank()) value else "$value · $detail"

    private fun unknown(label: String) = Fact(
        label = label,
        value = "상태 미확인",
        severity = OperatorStatus.Severity.INFO,
        detail = "상태를 추측하지 않음",
    )

    private fun severityRank(severity: OperatorStatus.Severity): Int = when (severity) {
        OperatorStatus.Severity.INFO -> 0
        OperatorStatus.Severity.ATTENTION -> 1
        OperatorStatus.Severity.PROBLEM -> 2
    }
}
