package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Round51 autonomous-supervision axes (host JVM — NOT a render claim).
 *
 * - AI execution comes from the actual bound runtime plus input health,
 *   never from the legacy `running` bool or a bare counter;
 * - camera owner / auto permission / action health stay UNKNOWN absent
 *   actual reports, and manual camera never stops analysis/evidence;
 * - backend assessment / pre-post context is always UNKNOWN (no frozen-MOP
 *   field) — receipt counts never stand in for it;
 * - preservation keeps Manifold-local / RC-receipt / Core-store apart and
 *   never invents local preservation from running/counter;
 * - SHADOW is never an Incident/Alert and never demands intervention;
 *   zero is never safe; no auto-response is ever produced.
 */
class AutonomousSupervisionTest {

    private fun base(hasFlight: Boolean = true) = AutonomousSupervision.Input(
        hasActiveFlight = hasFlight,
        runtimeStateName = "RUNNING",
        runtimeSeen = true,
        linkUp = true,
        videoInputLost = false,
        engineState = "ok",
        frameProcessingObserved = true,
        processedFrames = 42L,
        cameraReportedOwner = null,
        cameraOwnerConfirmed = false,
        autoPermission = null,
        adjustmentState = null,
        aircraftConnected = true,
        rcObservationCount = 3,
        rcEventCount = 0,
        coreConfigured = false,
        coreSyncedCount = null,
        coreTotalCount = null,
        shadowOnly = true,
        hasApprovedAlert = false,
        recordFailure = false,
        storageAvailable = true,
    )

    @Test
    fun running_with_engine_ok_reports_execution_without_counter_claim() {
        val panel = AutonomousSupervision.render(base())
        assertEquals("관측 실행 중", panel.ai.value)
        assertEquals(OperatorStatus.Severity.INFO, panel.ai.severity)
        assertTrue(panel.ai.detail.contains("엔진 ok"))
        assertTrue(panel.ai.detail.contains("분석 수행 확인"))
        // The counter never leaks into the compact value.
        assertTrue(!panel.ai.value.contains("42"))
    }

    @Test
    fun running_without_engine_ok_never_confirms_analysis() {
        val panel = AutonomousSupervision.render(base().copy(engineState = null))
        // Execution-report wording: the runtime state is reported, but the
        // headline must not imply validated analysis.
        assertEquals("관측 실행 보고됨", panel.ai.value)
        assertTrue(panel.ai.detail.contains("분석 성공 미확인"))
        assertTrue(!panel.ai.detail.contains("분석 수행 확인("))
    }

    @Test
    fun video_loss_holds_space_judgment_but_keeps_analysis() {
        val panel = AutonomousSupervision.render(base().copy(videoInputLost = true))
        assertEquals("관측 실행 중 · 영상 입력 단절(해당 공간 평가 보류)", panel.ai.value)
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.ai.severity)
        assertTrue(panel.ai.detail.contains(AutonomousSupervision.MANUAL_CAMERA_NOTE))
        assertEquals("영상 입력 단절", panel.action.value)
        assertTrue(panel.action.action != null)
    }

    @Test
    fun absent_runtime_is_unknown_never_zero_or_false() {
        val panel = AutonomousSupervision.render(
            base().copy(runtimeStateName = null, runtimeSeen = false),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, panel.ai.value)
        assertTrue(panel.ai.detail.contains("false/0"))
        assertTrue(!panel.ai.value.contains("0"))
    }

    @Test
    fun unbound_report_is_display_only_never_execution() {
        val panel = AutonomousSupervision.render(
            base().copy(runtimeStateName = null, runtimeSeen = false, unboundReportSeen = true),
        )
        assertEquals("Edge 보고 있음 · 바인딩 미확인", panel.ai.value)
        assertTrue(panel.ai.detail.contains("실행 상태로 채택 안 함"))
    }

    @Test
    fun link_loss_never_estimates_normality() {
        val panel = AutonomousSupervision.render(base().copy(linkUp = false))
        assertEquals("통신 단절 · 실행 상태 미확인", panel.ai.value)
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.ai.severity)
        assertTrue(panel.ai.detail.contains("정상"))
        assertEquals("링크 단절", panel.action.value)
    }

    @Test
    fun camera_axes_stay_unknown_absent_actual_reports() {
        val panel = AutonomousSupervision.render(base())
        assertEquals("실제 제어 주체 미확인", panel.camera.value)
        assertTrue(panel.camera.detail.contains("자동 허용 미확인"))
        assertTrue(panel.camera.detail.contains("조작 결과 미확인"))
        assertTrue(panel.camera.detail.contains(AutonomousSupervision.MANUAL_CAMERA_NOTE))
        assertEquals(OperatorStatus.Severity.INFO, panel.camera.severity)
    }

    @Test
    fun confirmed_camera_owner_passes_through_with_manual_note_kept() {
        val panel = AutonomousSupervision.render(
            base().copy(
                // Only an explicit SIM scenario may report OPERATOR.
                cameraReportedOwner = "OPERATOR",
                cameraOwnerConfirmed = true,
                autoPermission = "RELEASED",
                adjustmentState = "IDLE",
            ),
        )
        assertEquals("조종자(모의 입력)", panel.camera.value)
        assertTrue(panel.camera.detail.contains("자동 허용 해제됨"))
        assertTrue(panel.camera.detail.contains("조절 대기"))
        // Manual camera work still never stops analysis/evidence.
        assertTrue(panel.camera.detail.contains(AutonomousSupervision.MANUAL_CAMERA_NOTE))
        assertEquals("관측 실행 중", panel.ai.value)
    }

    @Test
    fun operator_without_an_explicit_scenario_stays_unknown() {
        val panel = AutonomousSupervision.render(
            base().copy(cameraReportedOwner = "OPERATOR", cameraOwnerConfirmed = false),
        )
        assertEquals("실제 제어 주체 미확인", panel.camera.value)
    }

    @Test
    fun host_a_camera_vocabulary_stays_unconfigured_not_healthy() {
        val panel = AutonomousSupervision.render(
            base().copy(autoPermission = "UNAVAILABLE", adjustmentState = "NOT_CONFIGURED"),
        )
        assertEquals("실제 제어 주체 미확인", panel.camera.value)
        assertTrue(panel.camera.detail.contains("자동 기능 미구성"))
        assertTrue(panel.camera.detail.contains("자동 조절 미구성"))
        assertEquals(OperatorStatus.Severity.INFO, panel.camera.severity)
    }

    @Test
    fun sealed_replay_is_recorded_execution_never_currently_running() {
        val panel = AutonomousSupervision.render(
            base().copy(
                runtimeStateName = "STOPPED",
                manifoldLocalState = "SEALED",
                contextState = "COMPLETE",
            ),
        )
        assertEquals("관측 중지", panel.ai.value)
        assertTrue(panel.ai.detail.contains("기록된 실행(현재 분석 중 아님)"))
        assertTrue(!panel.ai.value.contains("실행 중"))
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 봉인됨"))
        assertTrue(panel.preservation.detail.contains("전후 맥락 완전"))
    }

    @Test
    fun sealed_with_incomplete_context_stays_separate() {
        // SEALED+INCOMPLETE is possible: seal success and context
        // completeness are different facts.
        val panel = AutonomousSupervision.render(
            base().copy(manifoldLocalState = "SEALED", contextState = "INCOMPLETE"),
        )
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 봉인됨"))
        assertTrue(panel.preservation.detail.contains("전후 맥락 불완전"))
    }

    @Test
    fun shadow_condition_met_is_never_an_incident_or_alert() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentMode = "SHADOW", assessmentDisposition = "CONDITION_MET"),
        )
        assertTrue(panel.assessment.value.contains("조건 충족"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertEquals(OperatorStatus.Severity.INFO, panel.assessment.severity)
        // And it never demands intervention by itself.
        assertEquals("보고된 개입 요청 없음", panel.action.value)
    }

    @Test
    fun undetermined_assessment_renders_without_safety_claim() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentMode = "SHADOW", assessmentDisposition = "UNDETERMINED"),
        )
        assertTrue(panel.assessment.value.contains("판정불가"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
    }

    @Test
    fun assessed_is_evaluation_complete_not_hazard_confirmation() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentMode = "SHADOW", assessmentDisposition = "ASSESSED"),
        )
        assertTrue(panel.assessment.value.contains("평가됨"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertEquals("보고된 개입 요청 없음", panel.action.value)
    }

    @Test
    fun supplied_undetermined_reasons_render_without_inducing_response() {
        // Review gap (round52): a known UNDETERMINED must be able to show
        // its supplied reason codes. Reasons are explicit supply only —
        // they change neither analysis independence nor intervention need.
        val panel = AutonomousSupervision.render(
            base().copy(
                assessmentMode = "SHADOW",
                assessmentDisposition = "UNDETERMINED",
                assessmentReasonCodes = listOf("no_applicable_rules", "camera_manual_override"),
            ),
        )
        assertTrue(panel.assessment.value.contains("판정불가"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
        assertTrue(panel.assessment.detail.contains("사유: no_applicable_rules, camera_manual_override"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertEquals(OperatorStatus.Severity.INFO, panel.assessment.severity)
        // Analysis stays independent and manual-camera note is kept …
        assertEquals("관측 실행 중", panel.ai.value)
        assertTrue(panel.camera.detail.contains(AutonomousSupervision.MANUAL_CAMERA_NOTE))
        // … and no alert or intervention obligation is induced.
        assertEquals("보고된 개입 요청 없음", panel.action.value)
        assertNull(panel.action.action)
    }

    @Test
    fun empty_or_orphan_reasons_invent_nothing() {
        // Known disposition with no supplied reasons: no "사유" is shown.
        val bare = AutonomousSupervision.render(
            base().copy(assessmentMode = "SHADOW", assessmentDisposition = "UNDETERMINED"),
        )
        assertTrue(!bare.assessment.detail.contains("사유"))
        // Reasons without a known disposition are orphaned, not displayed —
        // unknown stays unknown, never attributed.
        val orphan = AutonomousSupervision.render(
            base().copy(assessmentReasonCodes = listOf("no_applicable_rules")),
        )
        assertEquals("평가·전후 맥락 미확인", orphan.assessment.value)
        assertTrue(!orphan.assessment.detail.contains("no_applicable_rules"))
    }

    @Test
    fun preservation_keeps_three_facts_apart_never_invented() {
        val panel = AutonomousSupervision.render(base())
        // RC receipt is stated as received records — never an Incident …
        assertTrue(panel.preservation.value.contains("RC 수신 관측 3건 · 수신 이벤트 0건"))
        // … Manifold local stays UNKNOWN even while RUNNING with counts …
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 보존 미확인"))
        assertTrue(panel.preservation.detail.contains("실행 중으로 추정 안 함"))
        // … and Core unconfigured is its own fact.
        assertTrue(panel.preservation.detail.contains("Core 미설정"))
        assertEquals(OperatorStatus.Severity.INFO, panel.preservation.severity)
    }

    @Test
    fun preservation_never_reports_zero_from_unavailable_data() {
        val panel = AutonomousSupervision.render(
            base().copy(rcObservationCount = null, rcEventCount = null),
        )
        assertEquals("RC 수신 미확인", panel.preservation.value)
    }

    @Test
    fun rc_artifact_commits_do_not_prove_manifold_original_custody() {
        val panel = AutonomousSupervision.render(
            base().copy(coreConfigured = true, coreSyncedCount = 2, coreTotalCount = 3),
        )
        assertTrue(panel.preservation.detail.contains("RC→Core 전송 커밋 확인 2/3"))
        assertTrue(panel.preservation.detail.contains("전송 대상만"))
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 보존 미확인"))
    }

    @Test
    fun ingest_committed_never_implies_collection_receipt() {
        // Flight-ingest COMMITTED is transmission scope; the Edge sealed
        // original's Core custody is a collection receipt the RC cannot
        // observe — the axis must say so, never imply the RC copy held by
        // the Core is the sealed original.
        val panel = AutonomousSupervision.render(
            base().copy(coreConfigured = true, coreSyncedCount = 3, coreTotalCount = 3),
        )
        assertTrue(panel.preservation.detail.contains("수집 경로에서 확인(RC 미확인)"))
        assertTrue(!panel.preservation.detail.contains("Core 보관됨"))
        assertTrue(!panel.preservation.value.contains("수집 영수증 확인"))
    }

    @Test
    fun transmission_excluded_artifacts_are_named_never_hidden() {
        val panel = AutonomousSupervision.render(
            base().copy(coreConfigured = true, coreSyncedCount = 2, coreTotalCount = 2, coreExcludedCount = 1),
        )
        assertTrue(panel.preservation.detail.contains("전송 제외(수집 경로 보관) 1건"))
        assertTrue(panel.preservation.detail.contains("수집 경로에서 확인(RC 미확인)"))
    }

    @Test
    fun assessment_is_always_unknown_with_active_flight() {
        val panel = AutonomousSupervision.render(base())
        assertEquals("평가·전후 맥락 미확인", panel.assessment.value)
        assertTrue(panel.assessment.detail.contains("frozen MOP"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.ZERO_NOTE))
        assertEquals(OperatorStatus.Severity.INFO, panel.assessment.severity)
    }

    @Test
    fun store_linked_assessment_counts_render_without_safety_claim() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentAssessed = 2, assessmentUndetermined = 1, assessmentPending = 0),
        )
        assertTrue(panel.assessment.value.contains("SHADOW 평가 3건"))
        assertTrue(panel.assessment.value.contains("판정 2건"))
        assertTrue(panel.assessment.value.contains("미확정 1건"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
        assertTrue(panel.assessment.detail.contains("기록 화면에서 확인"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.ZERO_NOTE))
        assertEquals(OperatorStatus.Severity.INFO, panel.assessment.severity)
        assertNull(panel.assessment.action)
    }

    @Test
    fun store_linked_pending_assessments_read_receiving_never_judged() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentAssessed = 0, assessmentUndetermined = 0, assessmentPending = 2),
        )
        assertTrue(panel.assessment.value.contains("수신 중 2건"))
        assertTrue(panel.assessment.value.contains("판정 0건"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
    }

    @Test
    fun store_linked_zero_assessments_read_unreceived_never_safe() {
        val panel = AutonomousSupervision.render(
            base().copy(assessmentAssessed = 0, assessmentUndetermined = 0, assessmentPending = 0),
        )
        assertTrue(panel.assessment.value.contains("미수신"))
        assertTrue(panel.assessment.detail.contains("안전 확인 아님"))
        assertTrue(panel.assessment.detail.contains(AutonomousSupervision.ZERO_NOTE))
    }

    @Test
    fun active_but_insufficient_facts_is_unknown_never_not_needed() {
        // Absence is not "false": an active flight with unknown link and
        // no runtime report must read "미확인", never "보고된 개입 요청 없음".
        // No forced modal — the need is stated with a detail entry.
        val panel = AutonomousSupervision.render(
            base().copy(linkUp = null, runtimeStateName = null, runtimeSeen = false),
        )
        assertEquals("개입 필요 여부 미확인", panel.action.value)
        assertTrue(panel.action.detail.contains("부재는 정상이 아님"))
        assertEquals(OperatorStatus.Severity.INFO, panel.action.severity)
        assertTrue(panel.action.action != null)
    }

    @Test
    fun confirmed_quiet_shadow_stays_not_needed() {
        // Link up + bound runtime seen + no faults: the routine case may
        // read quiet (reported-request wording, never categorical).
        val panel = AutonomousSupervision.render(base())
        assertEquals("보고된 개입 요청 없음", panel.action.value)
        assertNull(panel.action.action)
    }

    @Test
    fun manifold_local_failed_surfaces_source_and_recovery() {
        val panel = AutonomousSupervision.render(
            base().copy(manifoldLocalState = "FAILED", contextState = "INCOMPLETE"),
        )
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.preservation.severity)
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 실패"))
        assertTrue(panel.preservation.action!!.contains("복구"))
    }

    @Test
    fun manifold_local_incomplete_is_attention_not_complete() {
        val panel = AutonomousSupervision.render(
            base().copy(manifoldLocalState = "INCOMPLETE"),
        )
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.preservation.severity)
        assertTrue(panel.preservation.action!!.contains("불완전"))
    }

    // -- Compact glance row -------------------------------------------------

    private fun compactBase() = AutonomousSupervision.CompactInput(
        hasActiveFlight = true,
        runtimeStateName = "RUNNING",
        runtimeSeen = true,
        linkUp = true,
        videoInputLost = false,
        engineState = "ok",
        frameProcessingObserved = true,
        cameraReportedOwner = null,
        cameraOwnerConfirmed = false,
        autoPermission = null,
        storageAvailable = true,
    )

    @Test
    fun compact_row_shows_three_required_axes_by_default() {
        val row = AutonomousSupervision.compact(compactBase())
        assertEquals("관측 실행 중", row.ai)
        // Required camera owner + auto permission, glance-visible.
        assertEquals("카메라 미확인 · 자동 미확인", row.camera)
        // Required Manifold local preservation, glance-visible.
        assertEquals("로컬 보존 미확인", row.preservation)
        assertEquals(OperatorStatus.Severity.INFO, row.severity)
        assertTrue(row.contentDescription.contains("조작·상세"))
    }

    @Test
    fun compact_row_never_confirms_analysis_from_state_alone() {
        val row = AutonomousSupervision.compact(compactBase().copy(engineState = null))
        assertEquals("관측 실행 보고됨", row.ai)
    }

    @Test
    fun compact_row_reports_link_loss_and_sealed_replay() {
        val down = AutonomousSupervision.compact(compactBase().copy(linkUp = false))
        assertEquals("통신 단절", down.ai)
        assertEquals(OperatorStatus.Severity.PROBLEM, down.severity)
        val sealed = AutonomousSupervision.compact(
            compactBase().copy(
                runtimeStateName = "STOPPED",
                manifoldLocalState = "SEALED",
            ),
        )
        assertEquals("관측 중지 · 기록됨", sealed.ai)
        assertEquals("로컬 봉인됨", sealed.preservation)
        assertTrue(!sealed.ai.contains("실행 중"))
    }

    @Test
    fun compact_camera_reports_explicit_sim_owner_only() {
        val sim = AutonomousSupervision.compact(
            compactBase().copy(
                cameraReportedOwner = "OPERATOR",
                cameraOwnerConfirmed = true,
                autoPermission = "RELEASED",
            ),
        )
        assertEquals("카메라: 조종자 · 자동 해제", sim.camera)
        val unconfirmed = AutonomousSupervision.compact(
            compactBase().copy(cameraReportedOwner = "OPERATOR", cameraOwnerConfirmed = false),
        )
        assertEquals("카메라 미확인 · 자동 미확인", unconfirmed.camera)
    }

    @Test
    fun routine_shadow_never_demands_intervention_or_response() {
        val panel = AutonomousSupervision.render(base())
        assertEquals("보고된 개입 요청 없음", panel.action.value)
        assertTrue(panel.action.detail.contains(AutonomousSupervision.SHADOW_NOTE))
        assertTrue(panel.action.detail.contains("자동 응답 없음"))
        assertNull(panel.action.action)
    }

    @Test
    fun record_failure_surfaces_with_source_and_action() {
        val panel = AutonomousSupervision.render(base().copy(recordFailure = true))
        assertEquals("프록시 기록 오류", panel.action.value)
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.action.severity)
        assertTrue(panel.action.action != null)
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.preservation.severity)
    }

    @Test
    fun approved_alert_is_separate_from_shadow() {
        val panel = AutonomousSupervision.render(
            base().copy(hasApprovedAlert = true, shadowOnly = false),
        )
        assertEquals("승인된 알림 확인 필요", panel.action.value)
        assertEquals(OperatorStatus.Severity.ATTENTION, panel.action.severity)
    }

    @Test
    fun storage_down_never_erases_independent_runtime_evidence() {
        // RC store failure must not imply the AI stopped: a supplied bound
        // runtime fact renders with its source, plus the store note. The
        // fault itself stays on the action axis.
        val panel = AutonomousSupervision.render(
            base().copy(storageAvailable = false, rcObservationCount = null, rcEventCount = null),
        )
        assertEquals("관측 실행 중", panel.ai.value)
        assertTrue(panel.ai.detail.contains("엔진 ok"))
        assertTrue(panel.ai.detail.contains(StorageHealth.UNAVAILABLE_DETAIL))
        assertEquals("저장소 오류 확인 필요", panel.action.value)
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.action.severity)
        assertTrue(!panel.ai.value.contains("중지"))
    }

    @Test
    fun storage_down_without_runtime_stays_unknown() {
        // Only a truly absent source stays unknown.
        val panel = AutonomousSupervision.render(
            base().copy(
                storageAvailable = false,
                runtimeStateName = null,
                runtimeSeen = false,
                rcObservationCount = null,
                rcEventCount = null,
            ),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, panel.ai.value)
    }

    @Test
    fun storage_down_keeps_supplied_manifold_facts_with_rc_source_stated() {
        val panel = AutonomousSupervision.render(
            base().copy(
                storageAvailable = false,
                rcObservationCount = null,
                rcEventCount = null,
                manifoldLocalState = "SEALED",
                contextState = "INCOMPLETE",
            ),
        )
        assertEquals("RC 저장소 오류", panel.preservation.value)
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 봉인됨"))
        assertTrue(panel.preservation.detail.contains("전후 맥락 불완전"))
        assertEquals(OperatorStatus.Severity.PROBLEM, panel.preservation.severity)
        assertEquals(StorageHealth.UNAVAILABLE_ACTION, panel.preservation.action)
        assertTrue(!panel.preservation.value.contains("0"))
    }

    @Test
    fun supplied_assessment_survives_storage_failure() {
        val panel = AutonomousSupervision.render(
            base().copy(
                storageAvailable = false,
                assessmentMode = "SHADOW",
                assessmentDisposition = "CONDITION_MET",
            ),
        )
        assertTrue(panel.assessment.value.contains("조건 충족"))
        assertTrue(panel.assessment.value.contains("확정 아님"))
    }

    @Test
    fun compact_storage_down_shows_runtime_with_fault_severity() {
        val row = AutonomousSupervision.compact(compactBase().copy(storageAvailable = false))
        assertEquals("관측 실행 중", row.ai)
        assertEquals("로컬 보존 미확인", row.preservation)
        assertEquals(OperatorStatus.Severity.PROBLEM, row.severity)
        assertTrue(row.contentDescription.contains("RC 저장소 오류"))
    }

    @Test
    fun no_flight_waits_without_claims() {
        val panel = AutonomousSupervision.render(base(hasFlight = false))
        assertEquals("실행 미확인", panel.ai.value)
        assertEquals("평가 대기", panel.assessment.value)
        assertEquals("보고된 개입 요청 없음", panel.action.value)
        assertNull(panel.action.action)
    }

    // -- Static wiring guards (host-only XML/source parse, not renders) ------

    private fun findModuleFile(relative: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val prefixed = File(dir, "android-rc/rc-app/$relative")
            if (prefixed.isFile) return prefixed
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw IllegalStateException("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun Element.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out.add(node)
        }
        return out
    }

    private fun Element.idName(): String? =
        getAttribute("android:id").takeIf { it.isNotEmpty() }?.substringAfter("/")

    private fun findById(root: Element, id: String): Element? {
        if (root.idName() == id) return root
        return root.childElements().firstNotNullOfOrNull { findById(it, id) }
    }

    private fun parentElement(root: Element, target: Element): Element? {
        for (child in root.childElements()) {
            if (child.isSameNode(target)) return root
            val found = parentElement(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun isDescendantOf(root: Element, ancestorId: String, target: Element): Boolean {
        var current = parentElement(root, target)
        while (current != null) {
            if (current.idName() == ancestorId) return true
            current = parentElement(root, current)
        }
        return false
    }

    @Test
    fun supervision_axes_live_in_the_deck_scroll_never_over_video() {
        val file = findModuleFile("src/main/res/layout/activity_main.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        for (id in listOf(
            "tvSuperviseAi", "tvSuperviseCamera", "tvSupervisePreserve",
            "tvSuperviseAssess", "tvSuperviseAction", "btnCandidatesToggle",
        )) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live under aiPanel", isDescendantOf(root, "aiPanel", view!!))
            assertTrue("$id must not overlay the video stage", !isDescendantOf(root, "videoStage", view))
            assertTrue("$id must not cover the telemetry band", !isDescendantOf(root, "telemetryStrip", view))
            assertTrue("$id must not sit in the status side panel", !isDescendantOf(root, "rightStatusPanel", view))
        }
        val toggle = findById(root, "btnCandidatesToggle")!!
        assertTrue(
            "candidate toggle keeps the 48dp glove minimum",
            toggle.getAttribute("android:layout_height") == "48dp" ||
                toggle.getAttribute("android:layout_height") == "@dimen/rc_touch_min",
        )
    }

    @Test
    fun routine_shadow_never_auto_opens_the_status_panel() {
        // Only explicit operator entries may open the panel; page
        // navigation always closes it. A SHADOW-driven auto-open would
        // re-create the mandatory 39% interruption this unit removes.
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        val openers = "setFlightPanelOpen\\(true\\)".toRegex().findAll(source).toList()
        assertTrue("panel must have explicit open entries (got ${openers.size})", openers.isNotEmpty())
        for (match in openers) {
            val context = source.substring(maxOf(0, match.range.first - 200), match.range.first)
            val line = context.lines().takeLast(3).joinToString("\n")
            assertTrue(
                "panel opens only from an explicit operator entry, never from candidate/SHADOW state: …$line",
                line.contains("on(R.id.btn") || line.contains("btnPriorityDetails") ||
                    line.contains("btnTaskDetails") || context.contains("on(R.id.") ||
                    context.contains("supervisionCompactRow"),
            )
        }
        assertTrue(
            "navigation must close the panel",
            source.contains("setFlightPanelOpen(false)"),
        )
    }

    @Test
    fun compact_row_is_glance_visible_with_three_axes_and_detail_entry() {
        // Round51 fix: the default screen shows AI / camera / Manifold
        // local compactly — no panel opening required to see them.
        val file = findModuleFile("src/main/res/layout/activity_main.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val row = findById(root, "supervisionCompactRow")
        assertTrue("supervisionCompactRow must exist", row != null)
        assertEquals("pageObserve", parentElement(root, row!!)?.idName())
        val order = findById(root, "pageObserve")!!.childElements().mapNotNull { it.idName() }
        val stripIndex = order.indexOf("telemetryStrip")
        val rowIndex = order.indexOf("supervisionCompactRow")
        val priorityIndex = order.indexOf("cockpitPriorityRow")
        val workIndex = order.indexOf("observeWorkRow")
        assertTrue("compact row must be declared: $order", rowIndex >= 0)
        assertTrue("compact row below the HUD strip: $order", stripIndex < rowIndex)
        assertTrue("compact row above the priority row: $order", rowIndex < priorityIndex)
        assertTrue("work row still fills below: $order", priorityIndex < workIndex)
        assertEquals(
            "glance summary is one slim line via dimens",
            "@dimen/rc_supervision_summary_height",
            row.getAttribute("android:minHeight"),
        )
        // Read-only: below the 48dp touch floor, never a tap target — the
        // operable detail entry is the 48dp bar button (same as web).
        assertFalse("summary is not clickable", row.getAttribute("android:clickable") == "true")
        assertFalse("summary is not focusable", row.getAttribute("android:focusable") == "true")
        for (id in listOf("tvCompactAi", "tvCompactCamera", "tvCompactPreserve")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live in the compact row", isDescendantOf(root, "supervisionCompactRow", view!!))
            assertTrue("$id must not overlay the video stage", !isDescendantOf(root, "videoStage", view))
            assertTrue("$id must not cover the telemetry band", !isDescendantOf(root, "telemetryStrip", view))
        }
    }

    @Test
    fun compact_row_refreshes_on_the_ticker_and_stays_readonly() {
        // Point 6: link/staleness changes must show newest without waiting
        // for a dirty state refresh — the ticker rebinds the row. The row
        // itself never opens anything (read-only 24dp); the 48dp bar
        // button is the detail entry.
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        val ticker = source.substringAfter("private fun updateAiPeekFreshness() {")
            .substringBefore("// -- A7 flight history")
        assertTrue("ticker must rebind the compact row", ticker.contains("bindCompactRow()"))
        assertFalse(
            "no click handler wires the summary to the panel",
            source.contains("R.id.supervisionCompactRow)?.setOnClickListener"),
        )
        assertTrue(
            "detail entry is the 48dp bar button",
            source.contains("on(R.id.btnTaskDetails)"),
        )
    }

    // -- Round52 polish guards: unknown stays unknown -------------------------

    @Test
    fun fully_unreported_flight_stays_unknown_on_every_axis() {
        // Active flight, but no bound runtime, no link reading, no store
        // counts and no backend report: every axis must read UNKNOWN —
        // never zero, never false, never a positive claim — and routine
        // SHADOW must not demand intervention or a response record.
        val panel = AutonomousSupervision.render(
            base().copy(
                runtimeStateName = null,
                runtimeSeen = false,
                linkUp = null,
                engineState = null,
                frameProcessingObserved = null,
                processedFrames = null,
                rcObservationCount = null,
                rcEventCount = null,
                coreSyncedCount = null,
                coreTotalCount = null,
            ),
        )
        assertEquals(EdgeRuntimePanel.UNKNOWN_LINE, panel.ai.value)
        assertTrue(panel.ai.detail.contains("false/0"))
        assertTrue(!panel.ai.value.contains("0"))
        assertEquals("실제 제어 주체 미확인", panel.camera.value)
        assertEquals("RC 수신 미확인", panel.preservation.value)
        assertTrue(panel.preservation.detail.contains("Manifold 로컬 보존 미확인"))
        assertTrue(panel.preservation.detail.contains("전후 맥락 미확인"))
        assertTrue(panel.preservation.detail.contains("Core 미설정"))
        assertEquals("평가·전후 맥락 미확인", panel.assessment.value)
        assertEquals("개입 필요 여부 미확인", panel.action.value)
        assertTrue(panel.action.detail.contains("자동 판단 없음"))
        val row = AutonomousSupervision.compact(
            AutonomousSupervision.CompactInput(
                hasActiveFlight = true,
                runtimeStateName = null,
                runtimeSeen = false,
                linkUp = null,
                videoInputLost = false,
                engineState = null,
                frameProcessingObserved = null,
                cameraReportedOwner = null,
                cameraOwnerConfirmed = false,
                autoPermission = null,
            ),
        )
        assertEquals("실행 상태 미확인", row.ai)
        assertEquals("카메라 미확인 · 자동 미확인", row.camera)
        assertEquals("로컬 보존 미확인", row.preservation)
    }

    @Test
    fun wiring_never_invents_unreported_camera_assessment_or_local_facts() {
        // The frozen MOP carries no camera-owner/auto/adjustment report,
        // no single-report backend assessment/mode/reason field and no
        // Manifold-local preservation field — MainActivity must keep
        // passing null/defaults (UNKNOWN) instead of inventing them from
        // counters or constants. The artifact-plane tallies ARE a real
        // transport path and must be wired from the store reads.
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        assertTrue("camera owner stays unreported", source.contains("cameraReportedOwner = null"))
        assertTrue("auto permission stays unreported", source.contains("autoPermission = null"))
        assertTrue("adjustment health stays unreported", source.contains("adjustmentState = null"))
        assertTrue("manifold-local stays unreported", source.contains("manifoldLocalState = null"))
        for (field in listOf("assessmentMode =", "assessmentDisposition =", "contextState =", "assessmentReasonCodes =")) {
            assertTrue(
                "$field must stay default (no invented transport fact)",
                !source.contains(field),
            )
        }
        assertTrue(
            "store-linked assessed tally is wired",
            source.contains("assessmentAssessed ="),
        )
        assertTrue(
            "store-linked undetermined tally is wired",
            source.contains("assessmentUndetermined ="),
        )
        assertTrue(
            "store-linked pending tally is wired",
            source.contains("assessmentPending ="),
        )
        assertTrue(
            "tallies derive from the verified assessment reads",
            source.contains("assessmentsByTick") && source.contains("pendingAssessmentCount"),
        )
        assertTrue(
            "transmission scope counts only ingestible artifacts",
            source.contains("INGESTIBLE_SOURCE_KINDS"),
        )
    }
}
