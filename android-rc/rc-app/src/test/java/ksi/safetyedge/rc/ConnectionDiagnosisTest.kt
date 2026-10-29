package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 — 연결 3단 진단(SDK 키 / 기체 / MOP) 회귀 시험.
 *
 * 사용자 요구: KSI Safety Edge는 RC Plus 2의 주 운용 앱이므로, 연결 문제가
 * 생겼을 때 조종자가 원인을 파악하고 해결할 수 있어야 한다. 다만 **3단계를
 * 홈 화면에 모두 노출하지 않는다** — 정상 시 헤더 1줄, 문제 시에만 상세.
 *
 * 이 시험이 고정하는 것:
 * ① 헤더 1줄 형식: "기체 연결됨 · Edge 미연결" (사용자 제시 형태 그대로).
 * ② "확인 불가(unknown)"와 "미연결(disconnected)"을 **끊지 않는다**.
 * ③ 키가 없으면 SDK는 "확인 불가" (앱이 SDK를 모름) — "실패"로 단정하지 않는다.
 * ④ 어느 단계가 실패해도 **기체 조작·로컬 기록을 막지 않는다**는 사실이 문구에 남는다.
 * ⑤ MOP이 끊겨도 앱 사용을 막지 않는다는 문구가 조치에 포함된다.
 */
class ConnectionDiagnosisTest {

    @Test
    fun headline_has_the_agreed_shape() {
        val ok = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = false,
        )
        assertEquals("기체 연결됨 · Edge 미연결", ok.headline)
    }

    @Test
    fun all_healthy_shows_no_detail_entry() {
        val ok = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = true,
        )
        assertTrue(ok.allOk)
        assertNull(ok.firstProblem)
        assertEquals("", ok.detailHint)
    }

    @Test
    fun a_problem_exposes_the_detail_entry_and_the_first_problem() {
        val broken = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = false,
        )
        assertFalse(broken.allOk)
        assertNotNull(broken.firstProblem)
        assertEquals(ConnectionDiagnosis.Stage.MOP, broken.firstProblem!!.stage)
        assertEquals("연결 상세 보기", broken.detailHint)
    }

    @Test
    fun unknown_is_not_reported_as_disconnected() {
        val unknown = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = null,
            mopConnected = null,
        )
        val aircraft = unknown.steps.first { it.stage == ConnectionDiagnosis.Stage.AIRCRAFT }
        val mop = unknown.steps.first { it.stage == ConnectionDiagnosis.Stage.MOP }
        assertEquals(ConnectionDiagnosis.State.UNKNOWN, aircraft.state)
        assertEquals(ConnectionDiagnosis.State.UNKNOWN, mop.state)
        assertEquals("확인 불가", aircraft.value)
        assertEquals("확인 불가", mop.value)
        assertFalse("확인 불가를 미연결로 말하지 않는다", aircraft.value.contains("미연결"))
    }

    @Test
    fun a_missing_key_is_unknown_not_a_measured_failure() {
        val noKey = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = false,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = null,
        )
        val sdk = noKey.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(ConnectionDiagnosis.State.UNKNOWN, sdk.state)
        assertEquals("앱 키 미설치", sdk.value)
    }

    @Test
    fun a_key_that_failed_to_initialise_is_reported_as_a_failure() {
        val failed = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = null,
        )
        val sdk = failed.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(ConnectionDiagnosis.State.DISCONNECTED, sdk.state)
        assertEquals("초기화 실패", sdk.value)
    }

    @Test
    fun a_mop_failure_never_claims_the_app_cannot_be_used() {
        val broken = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = false,
        )
        val mop = broken.steps.first { it.stage == ConnectionDiagnosis.Stage.MOP }
        assertTrue("계속 쓸 수 있다는 사실을 문구에 남긴다", mop.remedy.contains("계속됩니다"))
    }

    @Test
    fun the_three_stages_are_always_present_and_ordered() {
        val d = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = true,
        )
        assertEquals(3, d.steps.size)
        assertEquals(
            listOf(
                ConnectionDiagnosis.Stage.SDK,
                ConnectionDiagnosis.Stage.AIRCRAFT,
                ConnectionDiagnosis.Stage.MOP,
            ),
            d.steps.map { it.stage },
        )
    }

    @Test
    fun a_detail_line_carries_the_remedy_only_when_there_is_one() {
        val broken = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = false,
            mopConnected = true,
        )
        val aircraft = broken.steps.first { it.stage == ConnectionDiagnosis.Stage.AIRCRAFT }
        val line = ConnectionDiagnosis.detailLine(aircraft)
        assertTrue(line.contains("2. 기체"))
        assertTrue(line.contains("미연결"))
        assertTrue(line.contains("전원"))
    }
}
