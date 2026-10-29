package ksi.safetyedge.rc

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Display-only request/response wording. Never infers sending from link state. */
object MissionPreparationPresentation {
    private val operatorTime = DateTimeFormatter.ofPattern("MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /** Readable summary; the original model retains the full timestamp in technical detail. */
    fun recoverySummary(bundleId: String?, decidedAtUtc: String?): String? {
        if (bundleId == null) return null
        val time = try {
            decidedAtUtc?.let { operatorTime.format(Instant.parse(it)) } ?: "시각 미확인"
        } catch (_: DateTimeException) {
            "시각 미확인"
        }
        return "재시작 전 준비 요청(미확인)\n계획 ${bundleId.take(8)} · $time 요청\n" +
            "다시 준비를 요청하세요. 자동 재전송하지 않습니다."
    }

    fun pendingAction(unsent: Boolean): String = if (unsent) "전송 대기 중" else "장치 응답 대기 중"

    fun step(value: WorkspaceMissionFacts.Step, unsent: Boolean): String = when (value) {
        WorkspaceMissionFacts.Step.NOT_REQUESTED -> "확인 전"
        WorkspaceMissionFacts.Step.PENDING -> if (unsent) "전송 대기" else "응답 대기"
        WorkspaceMissionFacts.Step.CONFIRMED -> "응답 확인"
        WorkspaceMissionFacts.Step.REJECTED -> "거부됨"
        WorkspaceMissionFacts.Step.UNKNOWN -> "미확인"
        WorkspaceMissionFacts.Step.REVALIDATE -> "재확인 필요"
    }

    fun guidance(next: MissionNextAction.Next, connected: Boolean, unsent: Boolean): String = when {
        next == MissionNextAction.Next.STORAGE_DOWN -> StorageHealth.UNAVAILABLE_BANNER
        next == MissionNextAction.Next.WAITING && unsent ->
            "요청 전송 대기 · 응답 미확인\n재시작 시 준비 대기열 소실"
        next == MissionNextAction.Next.WAITING ->
            "장치 응답 대기 · 요청 접수와 실제 준비·적용·관측 실행은 별도입니다."
        next == MissionNextAction.Next.PREPARE || next == MissionNextAction.Next.APPLY ->
            if (connected) "장치 응답으로 확인합니다. 요청만으로 관측·기록이 시작되지 않습니다."
            else "연결이 끊기면 앱 전송 대기열에 남습니다.\n장치 응답 전에는 완료가 아닙니다."
        next == MissionNextAction.Next.START_RECORD ->
            "RC의 KSI 기록을 시작합니다. 기체 REC·관측 실행·원본 보존은 별도 확인합니다."
        next == MissionNextAction.Next.RECORDING -> "KSI 기록 중 · 비행 화면에서 관측 실행과 보존 상태를 확인하세요."
        next == MissionNextAction.Next.SELECT_BUNDLE -> "저장된 계획을 선택하세요. 일반 비행은 계획 선택과 별도입니다."
        else -> "장치 응답을 다시 확인하세요. 미확인 상태를 준비 완료로 간주하지 않습니다."
    }
}
