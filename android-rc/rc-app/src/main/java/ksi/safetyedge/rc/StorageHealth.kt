package ksi.safetyedge.rc

/**
 * Local-storage availability for the KSI app surface (fault containment):
 * an AI/observation/storage failure must never impair the basic
 * aircraft/camera surface. When [StorageStatus.available] is false the app
 * keeps the physical link/video/command seams usable, retains whatever
 * durable facts could be read, refuses every KSI persistence/write path,
 * and tells the operator plainly — never healthy, never "0 records".
 */
data class StorageStatus(
    val available: Boolean,
    /** Captured open failure detail, or null when available. */
    val errorDetail: String?,
)

object StorageHealth {
    /** Prominent banner: shown verbatim whenever KSI persistence is down. */
    const val UNAVAILABLE_BANNER =
        "로컬 저장소 오류 · KSI 관측/기록 사용 불가 · 현재 상태 미확인"

    const val UNAVAILABLE_TITLE = "로컬 저장소 오류"
    const val UNAVAILABLE_DETAIL = "KSI 관측/기록 사용 불가 · 현재 상태 미확인"
    const val UNAVAILABLE_ACTION = "저장소 점검 후 재시작"

    fun unavailable(errorDetail: String?): StorageStatus =
        StorageStatus(available = false, errorDetail = errorDetail)

    fun available(): StorageStatus = StorageStatus(available = true, errorDetail = null)
}
