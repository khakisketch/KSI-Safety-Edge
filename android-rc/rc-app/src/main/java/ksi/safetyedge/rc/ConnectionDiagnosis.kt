package ksi.safetyedge.rc

/**
 * D3 — 연결 3단 진단 (SDK 키 / 기체 / MOP).
 *
 * KSI Safety Edge는 RC Plus 2의 **주 운용 앱**이다. 연결 문제가 생겼을 때
 * 조종자가 "왜 안 되나 · 뭘 해야 하나"를 스스로 해결할 수 있어야 한다.
 *
 * 세 단계를 **끊지 않는다**(기존 3사실 분리 원칙):
 * ① DJI SDK — 앱 키 유무와 SDK 초기화
 * ② 기체     — MSDK가 보는 물리 기체 연결 (미연결 / 연결됨 / 확인 불가)
 * ③ MOP      — RC ↔ Manifold(Edge) 관측 채널 (미연결 / 연결됨 / 확인 불가)
 *
 * "확인 불가(unknown)"와 "미연결(false)"는 다른 사실이다. 확인 불가일 때
 * "연결 안 됐다"고 말하지 않는다.
 *
 * **어느 단계가 실패해도 AI 관측·로컬 기록 자체는 계속 동작한다.** MOP 실패는
 * "관측 데이터가 안 온다"는 사실일 뿐, 앱을 못 쓰게 만드는 원인이 아니다.
 *
 * Android-free on purpose: host JVM에서 unit-test된다.
 */
object ConnectionDiagnosis {

    /** 3단계. */
    enum class Stage { SDK, AIRCRAFT, MOP }

    /** 한 단계의 상태. */
    enum class State {
        /** 확인된 연결(또는 정상). */
        OK,
        /** 연결이 되지 않음이 확인됨. */
        DISCONNECTED,
        /** 이 기기/앱이 확인할 수 없음(키 없음·보고 불가). */
        UNKNOWN,
    }

    data class Step(
        val stage: Stage,
        val state: State,
        /** 이 단계가 정상인지(확인 불가도 "문제"로 별도 취급). */
        val title: String,
        /** 한 줄 상태. */
        val value: String,
        /** 안 될 때의 원인과 조치. 정상이면 빈 문자열. */
        val remedy: String,
    ) {
        /** 조종자가 살펴볼 가치가 있는 단계인가. */
        val actionable: Boolean get() = state != State.OK
    }

    data class Diagnosis(
        val steps: List<Step>,
    ) {
        /** 가장 먼저 살펴볼 단계(문제 있는 것 중 첫 번째). */
        val firstProblem: Step? get() = steps.firstOrNull { it.actionable }

        /** 모든 단계가 확인된 정상인가. */
        val allOk: Boolean get() = steps.none { it.actionable }

        /**
         * 헤더 1줄 요약. 예: "기체 연결됨 · Edge 미연결".
         * 사용자가 요청한 형태 그대로 — 정상이어도 같은 위치·같은 길이.
         * 어느 한쪽이 "확인 불가"면 그 자리를 그대로 드러낸다.
         */
        val headline: String
            get() {
                val aircraft = steps.first { it.stage == Stage.AIRCRAFT }
                val mop = steps.first { it.stage == Stage.MOP }
                return "기체 ${aircraft.value} · Edge ${mop.value}"
            }

        /** 문제가 있을 때만 쓰는 상세 진입 안내. */
        val detailHint: String
            get() = if (allOk) "" else "연결 상세 보기"
    }

    /**
     * 3단 상태를 만든다.
     *
     * @param sdkKeyPresent DJI 앱 키가 이 기기에 설정되어 있는가.
     * @param sdkInitialised SDK가 실제로 초기화되었는가(키가 있어도 실패 가능).
     * @param aircraftConnected 기체 연결 상태. null = 확인 불가.
     * @param mopConnected RC↔Manifold 관측 채널 상태. null = 확인 불가.
     */
    fun diagnose(
        sdkKeyPresent: Boolean,
        sdkInitialised: Boolean,
        aircraftConnected: Boolean?,
        mopConnected: Boolean?,
    ): Diagnosis {
        val sdk = when {
            !sdkKeyPresent -> Step(
                Stage.SDK,
                State.UNKNOWN,
                "1. DJI SDK",
                "앱 키 미설치",
                "SDK가 초기화되지 않아 기체·영상·MOP을 쓸 수 없습니다. 개발자 키가 필요합니다.",
            )
            !sdkInitialised -> Step(
                Stage.SDK,
                State.DISCONNECTED,
                "1. DJI SDK",
                "초기화 실패",
                "키는 설치되어 있으나 SDK 초기화에 실패했습니다. 기기 전원·무료 공간과 설정을 확인하세요.",
            )
            else -> Step(
                Stage.SDK,
                State.OK,
                "1. DJI SDK",
                "초기화됨",
                "",
            )
        }

        val aircraft = when (aircraftConnected) {
            true -> Step(Stage.AIRCRAFT, State.OK, "2. 기체", "연결됨", "")
            false -> Step(
                Stage.AIRCRAFT,
                State.DISCONNECTED,
                "2. 기체",
                "미연결",
                "기체의 전원과 USB 연결을 확인하세요. RC가 기체를 인식해야 관측이 시작됩니다.",
            )
            null -> Step(
                Stage.AIRCRAFT,
                State.UNKNOWN,
                "2. 기체",
                "확인 불가",
                "이 앱이 기체 연결을 확인할 수 없습니다. SDK 초기화 상태를 먼저 확인하세요.",
            )
        }

        val mop = when (mopConnected) {
            true -> Step(Stage.MOP, State.OK, "3. MOP(Edge)", "연결됨", "")
            false -> Step(
                Stage.MOP,
                State.DISCONNECTED,
                "3. MOP(Edge)",
                "미연결",
                "Manifold의 전원과 USB 연결, DPK 기동을 확인하세요. " +
                    "이 단계가 끊겨도 기체 조작과 로컬 기록은 계속됩니다.",
            )
            null -> Step(
                Stage.MOP,
                State.UNKNOWN,
                "3. MOP(Edge)",
                "확인 불가",
                "관측 채널 상태를 확인할 수 없습니다. Manifold 전원·USB 연결을 확인하세요.",
            )
        }

        return Diagnosis(listOf(sdk, aircraft, mop))
    }

    /** 상세 화면용 단계별 전체 문장. */
    fun detailLine(step: Step): String = buildString {
        append(step.title).append(" · ").append(step.value)
        if (step.remedy.isNotBlank()) {
            append(" — ").append(step.remedy)
        }
    }
}
