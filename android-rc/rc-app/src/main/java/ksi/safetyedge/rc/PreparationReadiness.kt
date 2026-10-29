package ksi.safetyedge.rc

/**
 * AT-J1 — "준비 미완" 규칙.
 *
 * 화면 계약(§2-2)은 이렇게 말한다: *"준비를 건너뛰고 운용에 들어가면 운용 상단에
 * '준비 미완'을 표시한다."* 그리고 §2는 **세 사실을 합치지 않는다**:
 * 캐시(동기화)·로컬 저장(digest 검증 번들)·Edge 적용(APPLIED 실확인).
 *
 * 이 모델은 그 세 사실 중 **무엇이 준비되지 않았는지 이름을 밝힌다**:
 * - `null` = **미확인**(모르다) — "실패"가 아니다. 보고가 없으면 실패로 쓰지 않는다.
 * - `false` = **미충족**(알려진 실패·미완료)
 * - `true` = 확인됨
 *
 * 규칙:
 * 1. **활성 세션이 없으면 경고하지 않는다.** 준비 화면 자체가 진입 지점이고,
 *    아직 시작하지 않은 상태를 "미완"으로 몰아세우지 않는다.
 * 2. 하나라도 준비되지 않았으면 **"준비 미완"**을 한 줄로 밝히고, **어느 축인지**를
 *    함께 말한다. "준비 완료"를 추정하지 않는다(거짓 준비 완료 0).
 * 3. `null`은 "미확인"으로 표기한다 — 링크가 끊겨 보고를 못 받은 것과
 *    적용이 실패한 것은 다른 사실이다.
 *
 * Android-free on purpose: host JVM에서 unit-test된다.
 */
object PreparationReadiness {

    /** 준비 3축의 사실. 기본값은 전부 "미확인"이다(아무것도 알지 못한다). */
    data class Facts(
        /** 동기화 결과: null = 미확인, false = 실패·미반영, true = 로컬 반영 확인. */
        val syncConfirmed: Boolean? = null,
        /** 로컬 번들 digest 검증: null = 미확인·미선택, false = 미검증, true = 검증됨. */
        val bundleVerified: Boolean? = null,
        /** Edge 적용 실확인: null = 보고 없음, false = 미적용, true = 적용 확인. */
        val applyConfirmed: Boolean? = null,
        /** 활성 세션이 있는가(운용 중인가). */
        val hasActiveFlight: Boolean = false,
    )

    /** 화면에 낼 한 줄(없으면 show=false). */
    data class Notice(
        val show: Boolean,
        val label: String,
        val detail: String,
        val action: String?,
    )

    const val LABEL = "준비 미완"
    const val ACTION = "임무 준비에서 확인"

    private val NONE = Notice(
        show = false,
        label = "",
        detail = "",
        action = null,
    )

    /** 세 사실에서 "준비 미완" 한 줄을 만든다. */
    fun notice(facts: Facts): Notice {
        // 시작하지 않은 상태는 "미완"이 아니다(준비 화면이 그 자리다).
        if (!facts.hasActiveFlight) return NONE
        val missing = mutableListOf<String>()
        when (facts.syncConfirmed) {
            true -> Unit
            false -> missing += "동기화 실패"
            null -> missing += "동기화 미확인"
        }
        when (facts.bundleVerified) {
            true -> Unit
            false -> missing += "번들 미검증"
            null -> missing += "번들 선택 미확인"
        }
        when (facts.applyConfirmed) {
            true -> Unit
            false -> missing += "Edge 적용 미완료"
            null -> missing += "Edge 적용 미확인"
        }
        if (missing.isEmpty()) return NONE
        return Notice(
            show = true,
            label = LABEL,
            detail = missing.joinToString(" · "),
            action = ACTION,
        )
    }

    /** 알림 라벨(상태 패널용). */
    fun label(notice: Notice): String =
        if (notice.show) notice.label else ""
}
