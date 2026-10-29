package ksi.safetyedge.rc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round47 scope metadata ([MissionNextAction.meta]): every next action
 * names its recipient and requested change (what/who/effect). DJI flight
 * authority, Edge mission work (PREPARE/APPLY/SESSION_START) and the KSI
 * local record are separate scopes — metadata never merges them and never
 * alters controller state ([MissionNextAction.decide] is unchanged).
 */
class MissionNextActionMetaTest {

    @Test
    fun every_next_action_has_a_named_recipient_request_and_effect() {
        for (next in MissionNextAction.Next.values()) {
            val meta = MissionNextAction.meta(next)
            assertTrue("$next target must be named", meta.target.isNotBlank())
            assertTrue("$next request must be named", meta.request.isNotBlank())
            assertTrue("$next effect must be named", meta.effect.isNotBlank())
        }
    }

    @Test
    fun edge_work_requests_name_edge_not_dji() {
        assertEquals("Edge", MissionNextAction.meta(MissionNextAction.Next.PREPARE).target)
        assertEquals("Edge", MissionNextAction.meta(MissionNextAction.Next.APPLY).target)
        assertTrue(
            MissionNextAction.meta(MissionNextAction.Next.WAITING).target.contains("Edge"),
        )
        for (next in listOf(
            MissionNextAction.Next.PREPARE,
            MissionNextAction.Next.APPLY,
            MissionNextAction.Next.WAITING,
        )) {
            val meta = MissionNextAction.meta(next)
            assertTrue("$next must not claim DJI scope", !meta.target.contains("DJI"))
            assertTrue("$next must not claim flight control", !meta.effect.contains("비행 조종"))
        }
    }

    @Test
    fun local_record_start_is_ksi_scope_not_flight_control() {
        val meta = MissionNextAction.meta(MissionNextAction.Next.START_RECORD)
        assertTrue(meta.target.contains("KSI"))
        assertTrue(meta.effect.contains("RC 로컬 기록 세션 생성"))
        assertTrue(meta.effect.contains("DJI 조종권 불변"))
    }

    @Test
    fun applied_ack_never_stands_for_engine_installation_or_runtime() {
        // Edge-operation contract §10.2: protocol APPLIED is a response
        // receipt, not engine-installation or runtime evidence.
        val meta = MissionNextAction.meta(MissionNextAction.Next.APPLY)
        assertTrue(meta.effect.contains("적용 응답 확인"))
        assertTrue(meta.effect.contains("별도 확인"))
        for (falseClaim in listOf("설치가 확인", "설치됨", "실행 중", "관측 중")) {
            assertTrue(
                "APPLY effect must not claim '$falseClaim': ${meta.effect}",
                !meta.effect.contains(falseClaim),
            )
        }
    }

    @Test
    fun prepare_response_is_not_observation_start() {
        val meta = MissionNextAction.meta(MissionNextAction.Next.PREPARE)
        assertTrue(meta.effect.contains("준비 응답 확인"))
        assertTrue(meta.effect.contains("관측 시작 아님"))
    }

    @Test
    fun unknown_never_advances() {
        val meta = MissionNextAction.meta(MissionNextAction.Next.UNKNOWN)
        assertTrue(meta.effect.contains("진행 없음"))
    }

    @Test
    fun metadata_does_not_change_the_decision() {
        // Spot-check: decide() still maps states exactly as before.
        assertEquals(
            MissionNextAction.Next.APPLY,
            MissionNextAction.decide(
                MissionNextAction.Input(
                    storageAvailable = true,
                    hasActiveFlight = false,
                    hasSelectedBundle = true,
                    controllerStateName = "PREPARED",
                ),
            ),
        )
        assertEquals(
            MissionNextAction.Next.START_RECORD,
            MissionNextAction.decide(
                MissionNextAction.Input(
                    storageAvailable = true,
                    hasActiveFlight = false,
                    hasSelectedBundle = true,
                    controllerStateName = "APPLIED",
                ),
            ),
        )
    }

    /**
     * The scope metadata is actually bound: the mission dock's next-action
     * line keeps its short visible text while the content description
     * carries target/request/effect — so ACK/APPLIED can never be read as
     * engine installation. Host source check (same style as the dock
     * reserve check in [RcDensity768LayoutTest]).
     */
    @Test
    fun scope_metadata_is_bound_to_the_next_action_description() {
        var dir = File(System.getProperty("user.dir"))
        var source: String? = null
        repeat(8) {
            for (relative in listOf(
                "src/main/java/ksi/safetyedge/rc/MainActivity.kt",
                "android-rc/rc-app/src/main/java/ksi/safetyedge/rc/MainActivity.kt",
            )) {
                val candidate = File(dir, relative)
                if (candidate.isFile) source = candidate.readText()
            }
            dir = dir.parentFile ?: return@repeat
        }
        val body = checkNotNull(source) { "cannot locate MainActivity.kt" }
            .substringAfter("if (::tvMissionNextAction.isInitialized)")
            .substringBefore("updateMissionSyncResultUi()")
        assertTrue("next action still decides from live state", body.contains("MissionNextAction.decide("))
        assertTrue("visible line keeps the short string", body.contains("tvMissionNextAction.setText(nextRes)"))
        assertTrue("scope metadata is read", body.contains("MissionNextAction.meta(next)"))
        assertTrue(
            "target/request/effect ride the description",
            body.contains("scope.target") && body.contains("scope.request") && body.contains("scope.effect"),
        )
    }
}
