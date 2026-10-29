package ksi.safetyedge.rc

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural regression fences supplement (never replace) actual RC rendered verification. */
class WorkspaceIntegrationContractTest {
    private fun source(name: String) = File("src/main/java/ksi/safetyedge/rc/$name.kt").readText()

    @Test fun explicit_record_navigation_waits_for_matching_detail_and_cancels_on_exit() {
        val activity = source("MainActivity")
        assertTrue(activity.contains("recordDetailRequestedId = id"))
        assertTrue(activity.contains("recordDetailRequestedId = flightId"))
        assertTrue(activity.contains("RecordSelection.shouldOpen(requestedFlightId, selected, detail != null"))
        assertTrue(activity.contains("private fun showPage(navId: Int) {\n        recordDetailRequestedId = null"))
        assertTrue(activity.contains("historyGeneration++"))
    }

    @Test fun priority_and_preparation_keep_recovery_and_long_content_accessible() {
        val layout = source("RcWorkspaceLayout")
        assertFalse(layout.contains("text.contains(\"MOP 링크\")"))
        assertTrue(layout.contains("fun bindPriorityDetail(item: OperatorStatus.Item?)"))
        assertTrue(layout.contains("연결 상태와 복구 확인"))
        assertTrue(layout.contains("val stepsScroll = ScrollView(activity)"))
        assertTrue(layout.contains("minimumHeight = dp(48)"))
        assertTrue(layout.contains("!selectionMismatch && next == MissionNextAction.Next.APPLY"))
        assertTrue(layout.contains("!selectionMismatch && next == MissionNextAction.Next.START_RECORD"))
    }

    @Test fun native_composition_installs_before_business_view_bindings() {
        val activity = source("MainActivity")
        assertTrue(activity.indexOf("setContentView(R.layout.activity_main)") < activity.indexOf("workspace.install()"))
        assertTrue(activity.indexOf("workspace.install()") < activity.indexOf("tvMission = findViewById"))
        assertFalse("real native composition, not embedded prototype", source("RcWorkspaceLayout").contains("WebView"))
    }

    @Test fun explicit_ai_start_uses_real_request_and_revalidation_gate() {
        val activity = source("MainActivity")
        assertTrue(activity.contains("if (readiness.canRequest)"))
        assertTrue(activity.contains("session.requestSessionStart()"))
        assertTrue(activity.contains("needsRevalidation = controller?.needsRevalidation ?: false"))
        assertTrue(source("RcWorkspaceLayout").contains("stage == ObservationStartPolicy.Stage.READY && canStart"))
    }

    @Test fun inline_feedback_does_not_use_a_system_toast_over_custody() {
        assertFalse(source("MainActivity").contains("Toast.makeText"))
        val layout = source("RcWorkspaceLayout")
        assertTrue(layout.indexOf("newRoot.addView(tvError)") < layout.indexOf("newRoot.addView(pageFrame)"))
    }

    @Test fun native_zoom_is_display_only_and_has_button_equivalents() {
        val view = source("ZoomEvidenceImageView")
        assertTrue(view.contains("ScaleGestureDetector"))
        assertTrue(view.contains("viewport?.panBy"))
        assertTrue(view.contains("viewport?.zoomBy"))
        assertTrue(view.contains("fun resetToFit()"))
        assertFalse(view.contains("RcSession"))
        assertFalse(view.contains("artifactBytes"))
        val activity = source("MainActivity")
        for (id in listOf("workspaceZoomIn", "workspaceZoomOut", "workspaceZoomReset")) assertTrue(activity.contains("R.id.$id"))
        assertTrue("same-selection metadata refresh does not clear inspection", activity.contains("if (selectionChanged)"))
    }

    @Test fun device_cannot_enable_bench_fixtures_and_bench_has_a_separate_sandbox() {
        val device = File("src/device/java/ksi/safetyedge/rc/WorkspacePreviewFixture.kt").readText()
        assertTrue(device.contains("false"))
        assertFalse(device.contains("seedSession"))
        assertTrue(File("build.gradle.kts").readText().contains("applicationIdSuffix = \".bench\""))
        val bench = File("src/bench/java/ksi/safetyedge/rc/WorkspacePreviewFixture.kt").readText()
        assertTrue(bench.contains("FakeDroneLink"))
        assertTrue(bench.contains("coreConfigured"))
    }

    @Test fun reference_scene_credit_is_only_built_with_bench_backdrop() {
        val layout = source("RcWorkspaceLayout")
        val backdrop = layout.indexOf("val backdrop = WorkspacePreviewFixture.videoBackdrop(activity)")
        val guarded = layout.indexOf("if (backdrop != null)", backdrop)
        val credit = layout.indexOf("참고 장면 · 실제 영상 아님 · ESO/E. Graf · CC BY 4.0", guarded)
        val badgeAssignment = layout.indexOf("flightSceneBadge = badge", credit)
        assertTrue(backdrop >= 0 && guarded > backdrop && credit > guarded && badgeAssignment > credit)
        val device = File("src/device/java/ksi/safetyedge/rc/WorkspacePreviewFixture.kt").readText()
        assertTrue(device.contains("fun videoBackdrop(activity: Activity): Drawable? = null"))
    }

    @Test fun unknown_quality_never_becomes_validated() {
        assertEquals("저하", UserFacingCopy.observationQualityLabel("DEGRADED"))
        assertEquals("검증 전", UserFacingCopy.observationQualityLabel("UNVERIFIED"))
        assertEquals("미확인", UserFacingCopy.observationQualityLabel("FUTURE_VALUE"))
        assertEquals("미확인", UserFacingCopy.observationQualityLabel(null))
    }

    @Test fun pending_and_expanded_review_keep_their_task_context() {
        val layout = source("RcWorkspaceLayout")
        assertTrue(layout.contains("ObservationStartPolicy.Stage.REQUEST_PENDING -> \"관측 시작 응답 대기\""))
        assertTrue(layout.contains("ObservationStartPolicy.Stage.RECOVER -> \"관측 요청 미확인\""))
        assertTrue(layout.contains("R.id.workspaceObservationRecovery"))
        assertTrue(layout.contains("req(R.id.btnReconcile).performClick()"))
        assertTrue(layout.contains("fun bindReviewContext"))
        assertFalse("frame switching stays alongside expanded image", layout.contains("filmstripScroll.visibility = if (evidenceExpanded) View.GONE"))
        assertTrue(layout.contains("recordHeader.addView(button"))
    }

    @Test fun settings_scroll_factory_owns_the_only_direct_child() {
        val layout = source("RcWorkspaceLayout")
        for (name in listOf("conn", "operator", "display", "diagnostics")) {
            assertFalse("panelScroll already owns its child: $name",
                layout.contains("${name}Scroll.addView(${name}Inner)"))
        }
    }

    @Test fun absent_media_exposes_real_context_and_never_the_current_mission_as_evidence() {
        val main = source("MainActivity")
        assertTrue(main.contains("workspace.bindOfflineSites(cards, active == null)"))
        assertTrue(main.contains("workspace.bindVideoAvailability("))
        assertTrue(main.contains("workspace.bindEvidenceAvailability(message == null && ivEvidence.drawable != null)"))
        assertTrue(main.contains("val pinnedBundleId = selected?.let { session.store.flight(it)?.get(\"bundle_id\")?.asText() }"))
        assertTrue(main.contains("workspace.bindRecordContext(pinnedBundle"))
        val layout = source("RcWorkspaceLayout")
        assertTrue("an unselected mission gets task guidance, not a map placeholder",
            layout.contains("missionEmpty.visibility = if (!missionHasSite && !missionHasSelection && !preparing) View.VISIBLE else View.GONE"))
        assertTrue("no current mission reused as review evidence", layout.contains("recordContext.bindPlan(bundle)"))
    }

    @Test fun four_tabs_and_record_index_keep_navigation_separate_from_commands() {
        val layout = source("RcWorkspaceLayout")
        assertTrue(layout.contains("destinationTabs[id] = tab"))
        assertTrue(layout.contains("missionOpenFlight = action(\"비행 화면으로\")"))
        assertFalse("the Flight navigation tab must never move into a command dock", layout.contains("detach(openFlight)"))
        assertTrue(layout.contains("fun bindRecordIndex(rows: List<FlightHistoryModel.FlightRow>"))
        assertTrue(layout.contains("if (pageReview.visibility == View.VISIBLE && recordDetailOpen)"))
        assertTrue(layout.contains("recordTools.visibility = if (evidenceVisible) View.VISIBLE else View.GONE"))
        assertTrue(source("MainActivity").contains("workspace.bindRecordIndex(rows, selected, session.storageAvailable)"))
    }

    @Test fun mission_steps_consume_response_evidence_and_no_feed_is_equipment_check() {
        val layout = source("RcWorkspaceLayout")
        assertTrue(layout.contains("prepareValue.text = MissionPreparationPresentation.step(facts.prepare, \"MISSION_PREPARE\" in unsentRequestTypes)"))
        assertTrue(layout.contains("applyValue.text = MissionPreparationPresentation.step(facts.apply, \"MISSION_APPLY\" in unsentRequestTypes)"))
        assertFalse(layout.contains("val prepared = stateName in setOf"))
        assertFalse(layout.contains("flightOfflineContext.bindSites(cards"))
        assertTrue(layout.contains("buildFlightDeck()"))
        assertFalse("Flight must not become equipment onboarding", layout.contains("buildConnectionWorkspace"))
        assertFalse(layout.contains("flightPill.visibility = if (liveVideoVisible)"))
        assertFalse(layout.contains("flightCamera.visibility = if (liveVideoVisible)"))
        assertTrue(layout.contains("StoredZonePreviewView(activity, compact = true"))
        assertTrue(source("MainActivity").contains("workspace.bindFlightTelemetry("))
        assertTrue("supervision consumes source facts, never its own decorated labels", source("MainActivity").contains("ai = compactFacts.ai, camera = compactFacts.camera"))
        assertTrue("the record and source-preservation lines must both fit", layout.contains("maxLines = 2\n            ellipsize = null"))
        assertTrue(source("MainActivity").contains("WorkspaceMissionFacts.inputFrom("))
        assertTrue("disconnected response history is marked stale", layout.contains("missionConnectionNote.visibility = if (facts.linkStale) View.VISIBLE else View.GONE"))
        assertTrue("record totals are only the fallback when no event exists", layout.contains("selectedRecordRow?.takeIf { it.eventCount == 0 }"))
    }

    @Test fun font_and_theme_recreation_restore_only_view_choices_not_command_proof() {
        val main = source("MainActivity")
        assertTrue(main.contains("selectedBundleId = state.getString(\"workspace.bundle\")"))
        assertTrue(main.contains("workspace.restoreUiState(state)"))
        val save = main.substringAfter("override fun onSaveInstanceState").substringBefore("override fun onResume")
        assertTrue(save.contains("workspace.saveUiState(outState)"))
        assertFalse(save.contains("session."))
        val restore = source("RcWorkspaceLayout").substringAfter("fun restoreUiState").substringBefore("fun handleBack")
        assertFalse(restore.contains("performClick"))
        assertFalse(restore.contains("session."))
        assertFalse(restore.contains("WorkspaceMissionFacts"))
    }

    @Test fun compact_flight_keeps_full_facts_and_uniform_command_entries() {
        val layout = source("RcWorkspaceLayout")
        val flight = layout.substringAfter("private fun buildFlightDeck()").substringBefore("// ---------------------------------------------------------------- mission")
        assertTrue(flight.contains("footer.minimumHeight = dp(28)"))
        assertTrue(flight.contains("listOf(req(R.id.btnTaskDetails), req(R.id.btnEndFlight)).forEach { park(it) }"))
        assertTrue(flight.contains("layoutFlightOverlays(right - left)"))
        assertTrue(layout.contains("listOf(tvCompactCamera, btnReqPhoto, btnReqRec, btnReqZoom).forEach { park(it) }"))
        assertFalse(layout.contains("else \"KSI 기록 없음\""))
        assertTrue(layout.contains("showFlightTools(this)"))
        val telemetry = layout.substringAfter("fun bindFlightTelemetry(").substringBefore("fun bindReviewContext")
        assertTrue(telemetry.contains("\$value"))
        assertFalse("unknown/stale values are not shortened into fabricated readings", telemetry.contains("substring"))
        val main = source("MainActivity")
        val detail = main.substringAfter("private fun showConnectionDetail(\n").substringBefore("private fun statusMarker")
        for (value in listOf("tvTeleModeValue", "tvTeleBatteryValue", "tvTeleAuthorityValue", "tvTelePositionValue"))
            assertTrue("full fact must remain reachable: $value", detail.contains("${value}.text"))
    }
}
