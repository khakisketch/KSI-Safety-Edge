package ksi.safetyedge.rc

import ksi.rc.SessionResult

/**
 * Session outcome + preservation display for the operation screen
 * (2026-10-21 edge-operation contract §6).
 *
 * Pure Kotlin, no Android: host-tested. The verdict comes from
 * [ksi.rc.SessionResultClassifier] over RC-local facts only; COMMITTED
 * (preservation) is a SEPARATE axis and is never an input to the verdict.
 *
 * Honesty rules:
 * - a fully preserved failed session still reads FAILED/INTERRUPTED;
 * - "업로드 완료" is shown ONLY after the commit receipt is stored locally,
 *   and it never implies "safe" ("안전 확인 아님" is always attached).
 */
object SessionResultPanel {

    data class Input(
        /** Null = undecided / basis missing -> UNKNOWN wording, never invented. */
        val result: SessionResult?,
        /** A live session is never pre-judged SUCCESS/FAILED. */
        val openSession: Boolean,
        /**
         * Commit receipt stored locally (Core confirmed durable storage).
         * Null = unknown. Drives ONLY the preservation/upload wording.
         */
        val commitConfirmed: Boolean?,
        val committedCount: Int? = null,
        val totalCount: Int? = null,
    )

    data class Panel(
        val verdictLine: String,
        val preservationLine: String,
        val uploadLine: String,
    )

    fun verdictLine(result: SessionResult?, openSession: Boolean): String {
        if (openSession) {
            return when (result) {
                SessionResult.FAILED -> "세션 결과 · FAILED · 이미 알려진 복구 불가 결측"
                else -> "세션 결과 · 미확인(진행 중) · 판단 근거 부족"
            }
        }
        return when (result) {
            SessionResult.SUCCESS -> "세션 결과 · SUCCESS · 설정·실행·관측·영상 근거 완비"
            SessionResult.FAILED -> "세션 결과 · FAILED · 실행 실패 또는 필수 기록 결측"
            SessionResult.INTERRUPTED -> "세션 결과 · INTERRUPTED · 링크·재시작 등으로 중단"
            SessionResult.UNKNOWN, null -> "세션 결과 · 미확인(UNKNOWN) · 판단 근거 부족"
        }
    }

    fun preservationLine(commitConfirmed: Boolean?, committedCount: Int?, totalCount: Int?): String {
        val counts = if (committedCount != null && totalCount != null) {
            " · 서버 커밋 $committedCount/$totalCount"
        } else {
            ""
        }
        return when (commitConfirmed) {
            true -> "보존 · 서버 커밋 확인(영속 저장됨)$counts · 세션 결과와 별도 · 안전 확인 아님"
            else -> "보존 · 미확인$counts · 세션 결과와 별도 · 커밋 영수증 없이는 완료 표시 안 함"
        }
    }

    fun uploadLine(commitConfirmed: Boolean?): String = when (commitConfirmed) {
        true -> "업로드 완료 · 서버 커밋 확인(영수증 저장됨) · 안전 확인 아님"
        else -> "업로드 미완료 · 커밋 영수증 없음 · 안전 판단 아님"
    }

    fun render(input: Input): Panel = Panel(
        verdictLine = verdictLine(input.result, input.openSession),
        preservationLine = preservationLine(input.commitConfirmed, input.committedCount, input.totalCount),
        uploadLine = uploadLine(input.commitConfirmed),
    )
}
