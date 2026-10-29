package ksi.safetyedge.rc

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import ksi.rc.EvidenceSource
import ksi.rc.LocalBundleResult
import ksi.rc.ManifestBuilder
import ksi.rc.MissionState
import ksi.rc.OperatorActionArtifacts
import ksi.rc.SessionFacts
import ksi.rc.SessionFactsResolver
import ksi.rc.SessionResultArtifacts
import ksi.rc.SessionResultClassifier

/**
 * A5 shell: operational sections read the single [RcSession] live state;
 * the demo bench reads/is confined to the physically isolated
 * [DemoSession]. No screen keeps its own copy of core state.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var app: RcApp
    private lateinit var session: RcSession
    private lateinit var demo: DemoSession
    private lateinit var workspace: RcWorkspaceLayout
    private var currentDestination = R.id.btnNavObserve
    private var viewerSelectionKey: String? = null
    private var previewFixtureActive = false

    private lateinit var tvMission: TextView
    private lateinit var tvMissionReadiness: TextView
    private lateinit var tvMissionNextAction: TextView
    private lateinit var tvMissionSyncResult: TextView
    private lateinit var tvMissionSite: TextView
    private lateinit var tvMissionBundle: TextView
    private lateinit var tvMissionRevision: TextView
    private lateinit var tvMissionZone: TextView
    private lateinit var tvMissionFlightId: TextView
    private lateinit var tvMissionFlightState: TextView
    private lateinit var tvMissionStarted: TextView
    private lateinit var tvMissionZoneRevisions: TextView
    private lateinit var tvMissionApplyState: TextView
    private lateinit var tvLocalBundleResult: TextView
    /** Last LOCAL composition shown on the prep screen (G11). */
    private var lastLocalBundle: LocalBundleResult? = null
    // AT-J1: "준비 미완" 알림(준비 화면의 세 사실에서 파생). 상태 패널이 읽는다.
    private var preparationNotice: PreparationReadiness.Notice? = null
    /** Guards the bg verdict-decide post: at most one in flight. */
    private var verdictSyncPending = false
    /** Snapshot of the in-flight decide request (data-class equality). */
    private var pendingVerdictFacts: SessionFacts? = null
    private lateinit var tvOpsStatus: TextView
    private lateinit var tvTestEdgeLink: TextView
    private lateinit var tvPrimaryAlert: TextView
    private lateinit var tvPrimaryAction: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvVideoPlaceholder: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvInternetStatus: TextView
    private lateinit var tvFlights: TextView
    private lateinit var tvObservationSummary: TextView
    private lateinit var tvObservationSync: TextView
    private lateinit var tvConnectionHeadline: TextView
    private lateinit var tvSiteSuggestion: TextView
    private lateinit var tvSiteSuggestionAlt: TextView
    private lateinit var tvSiteConfirmNote: TextView
    private lateinit var btnSiteGnss: Button
    private lateinit var btnSiteUseSuggested: Button
    private lateinit var btnSiteChooseOther: Button
    private lateinit var spHistoryFlight: Spinner
    private lateinit var tvFlightDetail: TextView
    private lateinit var tvFlightArtifacts: TextView
    private lateinit var tvEventDetail: TextView
    private lateinit var tvEvidenceMeta: TextView
    private lateinit var ivEvidence: android.widget.ImageView
    private lateinit var btnFlagFalsePositive: Button
    private lateinit var tvDemo: TextView
    private lateinit var tvEvidence: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvError: TextView
    private lateinit var etCoreUrl: EditText
    private lateinit var etCoreToken: EditText
    private lateinit var etOperatorName: EditText
    private lateinit var etOperatorOrg: EditText
    private lateinit var btnSaveOperator: Button
    private lateinit var tvOperator: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var tvUploadGate: TextView
    private lateinit var surfaceVideo: android.view.SurfaceView
    // Adopted site-first mission: cards replace the site/bundle spinners.
    // Selection state (ids only) is preserved; the detail disclosure for the
    // expanded site stays closed by default (never auto-opened).
    private lateinit var siteCardList: LinearLayout
    private lateinit var siteCardDetail: LinearLayout
    private var expandedSiteId: String? = null
    private var missionPlanPanel: MissionPlanPanel? = null
    private var renderedSiteCards: List<MissionSiteCards.SiteCard>? = null
    private var renderedSiteSelection: String? = null
    /** GNSS-suggested site id, for the card chip only — never auto-selected. */
    private var suggestedSiteId: String? = null
    // Adopted event-kind-first records: 48dp rows grouped by stored kind,
    // built in code; plus the filmstrip of genuinely preserved frames.
    private lateinit var eventRowList: LinearLayout
    private lateinit var filmstripList: LinearLayout
    private lateinit var tvCustodyLine: TextView
    /** Guards async evidence loads against stale selections (issue #4). */
    private val viewerSession = ViewerSession()
    /** First genuinely verified frame auto-loaded for the selection. */
    private var viewerChosenArtifact: String? = null
    private lateinit var navRail: View
    private lateinit var rightStatusPanel: View
    private lateinit var developerTools: View
    private lateinit var btnNavMenu: Button
    private lateinit var appHeader: View
    private lateinit var headerDivider: View
    private lateinit var tvCockpitSim: TextView

    // G18: nav/connect buttons are bound once in onCreate and reused; every
    // other dynamic lookup goes through buttonOrNull() (log + skip, never
    // crash) so a missing id cannot take down the shell.
    private val navButtons = mutableMapOf<Int, Button>()
    private var btnConnect: Button? = null

    // The menu is feature-based; flight phases are expressed within each page.
    private lateinit var tvPageTitle: TextView
    private lateinit var tvTopSim: TextView
    private lateinit var tvTopUtc: TextView
    private lateinit var tvTopContext: TextView
    /** Round47 bottom task bar: permanent control gate (never execution). */
    /** Gate reason in FULL inside the details scroll (never truncated). */
    private lateinit var tvControlGateReason: TextView
    private lateinit var tvRecordEndNote: TextView

    // Integrated ops deck (observe screen): video/map view toggle,
    // operator commands via AircraftCommandPort, AI panel from the data
    // the RC session already receives. No screen keeps its own copy of
    // core state — everything here re-reads session/store/video source.
    private lateinit var tvCommandResult: TextView
    private lateinit var tvAiState: TextView
    private lateinit var tvAiCandidates: TextView
    private lateinit var tvAiOverlayNote: TextView
    private lateinit var tvAiMapNote: TextView
    // Round51 autonomous supervision: four separate axes (AI execution /
    // camera / preservation / assessment) plus the intervention need. Each
    // axis states only facts the RC actually holds; backend assessment and
    // Manifold local preservation read UNKNOWN (no frozen-MOP field).
    private lateinit var tvSuperviseAi: TextView
    private lateinit var tvSuperviseCamera: TextView
    private lateinit var tvSupervisePreserve: TextView
    private lateinit var tvSuperviseAssess: TextView
    private lateinit var tvSuperviseAction: TextView
    // Round51: routine SHADOW never auto-expands a candidate bar. The
    // tappable rows stay folded until the operator explicitly opens them;
    // confirm/hold/forward stays optional in the detail (no auto-response).
    private var candidatesExpanded: Boolean = false
    // Round51 fix: compact supervision glance row (AI / camera / Manifold
    // local) — full-width 48dp under the header/HUD, always visible by
    // default; a tap opens the detail panel. Short tokens only.
    private lateinit var tvCompactAi: TextView
    private lateinit var tvCompactCamera: TextView
    private lateinit var tvCompactPreserve: TextView
    private lateinit var tvCompactSite: TextView
    private lateinit var tvMapStatus: TextView
    private lateinit var tvMapPlaceholder: TextView
    // Edge runtime status area (Unit C): fixed above the deck scroll.
    private lateinit var tvEdgeRuntimeState: TextView
    private lateinit var tvEdgeRuntimeZeroNote: TextView
    private lateinit var tvEdgeRuntimeDetail: TextView
    private lateinit var tvSessionResult: TextView
    private lateinit var tvCommitState: TextView
    /** RC receipt time of the last RUNNING/DEGRADED Edge report. */
    private val runtimeLatch = RuntimeConfirmLatch()
    private var observeViewState: ObserveView.State = ObserveView.initial

    // AI candidate selection + operator response (screen contract v1 §3-8,
    // §4). Selection only opens the inline detail; the response panel writes
    // to RcSession.operatorActions (the same log the uploader ships).
    private lateinit var aiCandidateList: LinearLayout
    private lateinit var responsePanel: View
    private lateinit var tvResponseCandidate: TextView
    private lateinit var etResponseNote: EditText
    private lateinit var tvResponseResult: TextView
    private var selectedCandidateId: String? = null
    private var lastCandidateIds: List<String>? = null

    // Read-only telemetry strip (round24 topbar). Per-field freshness comes
    // from the shared accumulator; a stale sample is never shown as current.
    private lateinit var tvTeleAuthorityValue: TextView
    private lateinit var tvTeleModeValue: TextView
    private lateinit var tvTeleBatteryValue: TextView
    private lateinit var tvTeleLinksValue: TextView
    private lateinit var tvTelePositionValue: TextView
    private lateinit var telemetrySource: FlightTelemetrySource
    private val telemetryUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var telemetryRenderPending = false
    // Read-only AIRCRAFT camera telemetry (bench fake / device MSDK, DEVICE
    // GATE). Change-only SDK reports: UNKNOWN until the first report, last
    // confirmed stays current (no invented staleness timer); capture
    // CONFIRMED comes only from a still-photo media notification.
    private lateinit var cameraSource: AircraftCameraTelemetrySource
    private var cameraRenderPending = false
    private var lastOverview: CockpitStatusPresenter.Overview? = null
    private var lastEdgePanel: EdgeRuntimePanel.Panel? = null
    /** Latest video-freshness verdict: true = disconnected, show recovery. */
    private var videoRecoveryNeeded = false

    // MSDK declares dangerous permissions (location/phone/storage); targetSdk
    // 34 requires a runtime grant — request the declared set once per launch
    // and report the outcome honestly (no hidden failure).
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        show(
            if (denied.isEmpty()) {
                "권한 승인 완료"
            } else {
                "권한 미승인: ${denied.joinToString { it.substringAfterLast('.') }} — 일부 SDK 기능이 제한될 수 있음"
            },
        )
    }
    private val navTargets = listOf(
        R.id.btnNavObserve to R.id.pageObserve,
        R.id.btnNavMission to R.id.pageMission,
        R.id.btnNavReview to R.id.pageReview,
        R.id.btnNavSettings to R.id.pageSettings,
    )

    private var shownError: String? = null
    private var hasSyncReport = false
    private var missionSyncResultResource = R.string.mission_sync_result_unknown
    private var selectedSiteId: String? = null
    private var selectedBundleId: String? = null
    private var flightChromeCollapsed = false
    @Volatile private var cockpitStateDirty = true
    private var activityResumed = false
    private var selectedFlightId: String? = null
    private var recordDetailRequestedId: String? = null
    private var historyFlightIds: List<String?> = emptyList()
    private var historyAdapterLabels: List<String> = emptyList()
    private var updatingHistorySelector = false
    private var selectedEventRows: List<FlightHistoryModel.EventRow> = emptyList()
    private var selectedEventIndex = 0
    private var historyGeneration = 0
    private val cockpitHandler = Handler(Looper.getMainLooper())
    private val cockpitTicker = object : Runnable {
        override fun run() {
            if (!activityResumed || isDestroyed) return
            // Per-field freshness and connection facts remain live in every
            // destination, not just while looking at the video.
            renderTelemetryStrip()
            renderCameraState()
            refreshConnectionHeadline()
            if (::navRail.isInitialized &&
                findViewById<View>(R.id.pageObserve).visibility == View.VISIBLE
            ) {
                if (cockpitStateDirty) {
                    cockpitStateDirty = false
                    refreshLiveCockpit()
                } else {
                    updateAiPeekFreshness()
                    refreshVideo()
                }
            } else if (cockpitStateDirty) {
                // A delayed PREPARED/APPLIED/error response must advance the
                // currently open mission workspace too, not wait for a page
                // change or a second tap. Evidence selection/zoom is retained
                // by its record+event identity below.
                cockpitStateDirty = false
                refresh()
            }
            cockpitHandler.postDelayed(this, COCKPIT_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RcApp
        session = app.session
        demo = app.demoSession
        // Lifecycle view state only. Restoring a choice never restores an
        // installation receipt, starts a record, or sends a command.
        savedInstanceState?.let { state ->
            selectedSiteId = state.getString("workspace.site")
            expandedSiteId = state.getString("workspace.expandedSite")
            selectedBundleId = state.getString("workspace.bundle")
            selectedFlightId = state.getString("workspace.record")
            selectedEventIndex = state.getInt("workspace.event", 0)
        }
        previewFixtureActive = WorkspacePreviewFixture.seed(app, intent.getStringExtra("workspaceScenario"))
        setContentView(R.layout.activity_main)
        workspace = RcWorkspaceLayout(this)
        workspace.install()
        // Display-only HUD preference: persisting it cannot start a flight,
        // send a camera command or change recording state.
        workspace.onHudStyleSelected = { style ->
            app.config.hudStyle = style
            workspace.setHudStyle(style)
            show("비행 HUD: ${style.label}")
        }
        workspace.setHudStyle(app.config.hudStyle)

        tvMission = findViewById(R.id.tvMission)
        tvMissionSite = findViewById(R.id.tvMissionSite)
        tvMissionBundle = findViewById(R.id.tvMissionBundle)
        tvMissionRevision = findViewById(R.id.tvMissionRevision)
        tvMissionZone = findViewById(R.id.tvMissionZone)
        tvMissionFlightId = findViewById(R.id.tvMissionFlightId)
        tvMissionFlightState = findViewById(R.id.tvMissionFlightState)
        tvMissionStarted = findViewById(R.id.tvMissionStarted)
        tvMissionZoneRevisions = findViewById(R.id.tvMissionZoneRevisions)
        tvMissionApplyState = findViewById(R.id.tvMissionApplyState)
        tvLocalBundleResult = findViewById(R.id.tvLocalBundleResult)
        tvOpsStatus = findViewById(R.id.tvOpsStatus)
        tvTestEdgeLink = findViewById(R.id.tvTestEdgeLink)
        tvPrimaryAlert = findViewById(R.id.tvPrimaryAlert)
        tvPrimaryAction = findViewById(R.id.tvPrimaryAction)
        tvLink = findViewById(R.id.tvLink)
        tvStatus = findViewById(R.id.tvStatus)
        tvVideo = findViewById(R.id.tvVideo)
        tvVideoPlaceholder = findViewById(R.id.tvVideoPlaceholder)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        tvInternetStatus = findViewById(R.id.tvInternetStatus)
        tvFlights = findViewById(R.id.tvFlights)
        tvObservationSummary = findViewById(R.id.tvObservationSummary)
        tvObservationSync = findViewById(R.id.tvObservationSync)
        tvConnectionHeadline = findViewById(R.id.tvConnectionHeadline)
        tvSiteSuggestion = findViewById(R.id.tvSiteSuggestion)
        tvSiteSuggestionAlt = findViewById(R.id.tvSiteSuggestionAlt)
        tvSiteConfirmNote = findViewById(R.id.tvSiteConfirmNote)
        btnSiteGnss = findViewById(R.id.btnSiteGnss)
        btnSiteUseSuggested = findViewById(R.id.btnSiteUseSuggested)
        btnSiteChooseOther = findViewById(R.id.btnSiteChooseOther)
        spHistoryFlight = findViewById(R.id.spHistoryFlight)
        tvFlightDetail = findViewById(R.id.tvFlightDetail)
        tvFlightArtifacts = findViewById(R.id.tvFlightArtifacts)
        tvEventDetail = findViewById(R.id.tvEventDetail)
        tvEvidenceMeta = findViewById(R.id.tvEvidenceMeta)
        ivEvidence = findViewById(R.id.ivEvidence)
        (ivEvidence as? ZoomEvidenceImageView)?.let { image ->
            image.onViewportChanged = { enabled, percent -> workspace.bindZoom(enabled, percent) }
            findViewById<View>(R.id.workspaceZoomIn).setOnClickListener { image.zoomIn() }
            findViewById<View>(R.id.workspaceZoomOut).setOnClickListener { image.zoomOut() }
            findViewById<View>(R.id.workspaceZoomReset).setOnClickListener { image.resetToFit() }
            workspace.bindZoom(false, 100)
        }
        btnFlagFalsePositive = findViewById(R.id.btnFlagFalsePositive)
        tvDemo = findViewById(R.id.tvDemo)
        tvEvidence = findViewById(R.id.tvEvidence)
        tvSync = findViewById(R.id.tvSync)
        tvError = findViewById(R.id.tvError)
        etCoreUrl = findViewById(R.id.etCoreUrl)
        etCoreToken = findViewById(R.id.etCoreToken)
        etOperatorName = findViewById(R.id.etOperatorName)
        etOperatorOrg = findViewById(R.id.etOperatorOrg)
        btnSaveOperator = findViewById(R.id.btnSaveOperator)
        tvOperator = findViewById(R.id.tvOperator)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvAppVersion = findViewById(R.id.tvAppVersion)
        tvUploadGate = findViewById(R.id.tvUploadGate)
        surfaceVideo = findViewById(R.id.surfaceVideo)
        siteCardList = findViewById(R.id.siteCardList)
        siteCardDetail = findViewById(R.id.siteCardDetail)
        eventRowList = findViewById(R.id.eventRowList)
        filmstripList = findViewById(R.id.filmstripList)
        tvCustodyLine = findViewById(R.id.tvCustodyLine)
        navRail = findViewById(R.id.navRail)
        btnNavMenu = findViewById(R.id.btnNavMenu)
        rightStatusPanel = findViewById(R.id.rightStatusPanel)
        developerTools = findViewById(R.id.developerTools)
        appHeader = findViewById(R.id.appHeader)
        headerDivider = findViewById(R.id.headerDivider)
        tvCockpitSim = findViewById(R.id.tvCockpitSim)
        tvRecordEndNote = findViewById(R.id.tvRecordEndNote)
        bindStaticButtons()
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)
        app.config.coreTokenStorageError?.let {
            show("보안 저장소 오류: $it — 토큰을 다시 입력하세요")
        }
        etOperatorName.setText(app.config.operatorName)
        etOperatorOrg.setText(app.config.operatorOrganization)
        refreshOperatorState()
        // D2: GPS는 **선택 기능**이라 첫 진입을 권한 대화로 막지 않는다.
        // 현장 확인 카드가 "이 위치로 현장 찾기"를 눌렀을 때만 요청한다.
        // 거부해도 비행·AI 관측·기록은 계속 동작한다(추천 없이 수동 선택).
        tvDeviceInfo.text = getString(R.string.app_info_device_format, android.os.Build.MODEL)
        tvAppVersion.text = appVersionName()

        tvPageTitle = findViewById(R.id.tvPageTitle)
        tvTopSim = findViewById(R.id.tvTopSim)
        tvTopUtc = findViewById(R.id.tvTopUtc)
        tvTopContext = findViewById(R.id.tvTopContext)
        tvControlGateReason = findViewById(R.id.tvControlGateReason)
        bindTaskGate()
        tvTeleAuthorityValue = findViewById(R.id.tvTeleAuthorityValue)
        tvTeleModeValue = findViewById(R.id.tvTeleModeValue)
        tvTeleBatteryValue = findViewById(R.id.tvTeleBatteryValue)
        tvTeleLinksValue = findViewById(R.id.tvTeleLinksValue)
        tvTelePositionValue = findViewById(R.id.tvTelePositionValue)
        // Read-only telemetry source (bench fake / device MSDK, DEVICE GATE):
        // callbacks arrive off the UI thread — coalesce, then re-read states.
        telemetrySource = PlatformLinks.flightTelemetry(this)
        telemetrySource.start { scheduleTelemetryRender() }
        renderTelemetryStrip()
        // Read-only AIRCRAFT camera source (bench fake / device MSDK, DEVICE
        // GATE): same threading contract — coalesce, then map the Snapshot
        // to FlightCameraPresentation. Never a command receipt.
        cameraSource = PlatformLinks.aircraftCamera(this)
        cameraSource.start { scheduleCameraRender() }
        renderCameraState()
        installMissionReadinessUi()
        bindOpsDeck()
        tvError.setOnClickListener { tvError.visibility = View.GONE }
        bindHistorySelector()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                recordDetailRequestedId = null
                when {
                    findViewById<View>(R.id.reviewOverlay).visibility == View.VISIBLE -> {
                        findViewById<View>(R.id.reviewOverlay).visibility = View.GONE
                        buttonOrNull(R.id.btnReviewDetail)?.requestFocus()
                    }
                    rightStatusPanel.visibility == View.VISIBLE -> {
                        setFlightPanelOpen(false)
                        findViewById<View>(R.id.supervisionCompactRow).requestFocus()
                    }
                    findViewById<View>(R.id.opsRightColumn).visibility == View.VISIBLE -> {
                        setFlightControlsOpen(false)
                        buttonOrNull(R.id.btnTaskDetails)?.requestFocus()
                    }
                    findViewById<View>(R.id.pageEvidence).visibility == View.VISIBLE -> showPage(R.id.btnNavReview)
                    workspace.handleBack() -> Unit
                    currentDestination != R.id.btnNavObserve -> showPage(R.id.btnNavObserve)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        navTargets.forEach { (navId, _) -> on(navId) { showPage(navId) } }
        on(R.id.btnNavMenu) { showNavPopup(btnNavMenu) }
        // Two mutually exclusive, on-demand trays. Neither reserves video
        // width when closed; routine SHADOW never opens one automatically.
        on(R.id.btnTaskDetails) {
            setFlightControlsOpen(findViewById<View>(R.id.opsRightColumn).visibility != View.VISIBLE)
        }
        on(R.id.btnTaskStatus) { setFlightPanelOpen(rightStatusPanel.visibility != View.VISIBLE) }
        on(R.id.btnPriorityDetails) {
            workspace.selectFlightDetailTab(RcWorkspaceLayout.FlightDetailTab.STATE)
            setFlightPanelOpen(true)
        }
        on(R.id.btnReviewEmptyRecovery) { showEvidencePage() }
        // Round47 task bar: scoped entries — preparation opens the mission
        // page, details opens the status/session panel, recovery reconnects
        // the video input. None of these is a flight-control request.
        on(R.id.btnTaskPrepare) { showPage(R.id.btnNavMission) }
        on(R.id.btnTaskVideoRecover) { app.videoSource.connect(); refresh() }
        on(R.id.btnToggleRecordHelp) {
            val help = findViewById<View>(R.id.tvRecordingSourceNote)
            val expanded = help.visibility != View.VISIBLE
            help.visibility = if (expanded) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.btnToggleRecordHelp).setText(
                if (expanded) R.string.btn_hide_recording_help else R.string.btn_show_recording_help,
            )
        }
        on(R.id.btnToggleCockpitDiagnostics) {
            val diagnostics = findViewById<View>(R.id.cockpitDiagnostics)
            val expanded = diagnostics.visibility != View.VISIBLE
            diagnostics.visibility = if (expanded) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.btnToggleCockpitDiagnostics).setText(
                if (expanded) R.string.btn_hide_cockpit_diagnostics else R.string.btn_show_cockpit_diagnostics,
            )
        }
        on(R.id.btnOpenEvidence) { showEvidencePage() }
        on(R.id.btnEvidenceBack) { showPage(R.id.btnNavReview) }
        // Adopted: detail/annotations/fetch/diagnostics live in the
        // default-closed records overlay — never in the fixed stack.
        on(R.id.btnReviewDetail) { findViewById<View>(R.id.reviewOverlay)?.visibility = View.VISIBLE }
        on(R.id.btnReviewOverlayClose) { findViewById<View>(R.id.reviewOverlay)?.visibility = View.GONE }
        // Adopted: general flight without a site stays on the flight page
        // with the spatial-limit notice — no fake spatial judgment, and no
        // KSI session is started by this entry.
        on(R.id.btnGeneralFlight) {
            showPage(R.id.btnNavObserve)
            show(getString(R.string.mission_general_flight_moved))
        }
        // Adopted: 영상 넓게 보기 lives in settings and reuses the same
        // focus flag the flight header menu toggles.
        on(R.id.btnWideVideo) { toggleFocusMode() }
        refreshWideVideoButton()
        on(R.id.btnToggleDeveloper) {
            val expanded = developerTools.visibility != View.VISIBLE
            developerTools.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        // D1: 첫 화면은 항상 관측(운용) 화면. 활성 세션이 있으면 그 세션으로
        // 복귀하고, 없으면 "준비 대기" 상태로 관측 화면을 연다(현장 확인 카드).
        // 별도 홈 화면을 두지 않는다.
        showPage(R.id.btnNavObserve)

        // -- operational mission (A5: bundle attach comes from the synced
        //    mission store; until then the controller shows "not attached").
        on(R.id.btnSyncSites) { syncSites() }
        on(R.id.btnSyncBundle) { syncBundleLatest() }
        on(R.id.btnAddPendingSite) { showPendingSiteDialog() }
        on(R.id.btnComposeLocal) { composeLocalBundle() }
        on(R.id.btnStartFlight) { startFlight() }
        on(R.id.btnPrepare) { prepareMission() }
        on(R.id.btnApply) { run("Edge 번들 적용") {
            check(selectedPlanMatchesController()) { "선택 계획과 준비 중인 계획이 다릅니다. 준비 중인 계획을 선택하세요." }
            session.controller?.apply()
        } }
        on(R.id.workspaceMissionBoundPlan) {
            session.bundle?.let { prepared ->
                selectedSiteId = prepared.get("site_id")?.asText()
                expandedSiteId = selectedSiteId
                selectedBundleId = prepared.get("bundle_id")?.asText()
            }
        }
        on(R.id.btnReconcile) { run("Edge 적용 상태 확인") { session.controller?.reconcile() } }
        on(R.id.btnStart) {
            val readiness = observationStartDecision()
            if (readiness.canRequest) run("Edge 관측 시작 요청") { session.requestSessionStart() }
            else show("관측 시작 불가 · 준비·적용과 현재 기록 상태를 먼저 확인하세요")
        }
        on(R.id.btnStop) { run("Edge 관측 정지 요청") { session.controller?.stopSession() } }
        on(R.id.btnEndFlight) { confirmEndFlight() }

        // -- operational link
        on(R.id.btnConnect) {
            session.link.connect()
            // The connect itself is async; only the drain needs the
            // background (socket write, never on the UI thread).
            session.pumpOutboundAsync { result ->
                runOnUiThread {
                    result.onFailure { exc ->
                        Log.e(TAG, "connect pump failed", exc)
                        show("연결 전송 실패: ${refusalReason(exc)}")
                    }
                    refresh()
                }
            }
        }
        on(R.id.btnDisconnect) { session.link.disconnect(); refresh() }
        on(R.id.btnRetry) {
            // Same-msg_id retry + drain, off the UI thread; the count
            // posts back like the other tap paths.
            session.flushOutboundAsync { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = { sent ->
                            show("재전송 ${sent}건 — 동일 msg_id, 봉투당 1회 전송")
                        },
                        onFailure = { exc ->
                            Log.e(TAG, "retry pump failed", exc)
                            show("재전송 거부: ${refusalReason(exc)}")
                        },
                    )
                }
            }
        }

        // -- A6 video seam
        app.videoSource.setListener(object : VideoSource.Listener {
            override fun onState(state: VideoSource.State) {
                // A future MSDK source may call back off the main thread.
                runOnUiThread { if (!isDestroyed) refresh() }
            }

            override fun onFrame(frameCount: Long) {
                // Receipt counts are refreshed on the 1s UI ticker; encoded
                // input receipt does not establish successful display.
                cockpitStateDirty = true
            }
        })
        on(R.id.btnVideoConnect) { app.videoSource.connect(); refresh() }
        on(R.id.btnVideoDisconnect) { confirmVideoDisconnectInput() }

        // A9-3: live surface lifecycle -> display decoder (device flavor
        // only; bench is a no-op). Recording is NOT tied to this surface.
        surfaceVideo.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                PlatformLinks.attachVideoSurface(app.videoSource, holder.surface)
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                PlatformLinks.attachVideoSurface(app.videoSource, null)
            }
        })

        // -- demo bench (isolated store; NEVER uploads)
        on(R.id.btnDemoTick) {
            val count = demo.tick()
            show("SIMULATED 데모 틱: 데모 저장소로 봉투 ${count}건")
        }
        on(R.id.btnDemoNewFlight) {
            val record = demo.startNewFlight()
            show("데모 비행: ${record.get("flight_id").asText().take(8)}")
        }

        on(R.id.btnRefreshEvidence) { refreshEvidence() }
        refreshTestEdgeLinkState()

        // 시험용 Edge 링크(TCP) — 개발·통합시험용. 기체 경유 MOP를 대체하지
        // 않고, 같은 바이트를 같은 파서로 처리하는 경로다.
        on(R.id.btnTestEdgeLink) { showTestEdgeLinkDialog() }

        // -- A7 flight history
        on(R.id.btnFlightPrev) { stepFlight(+1) }
        on(R.id.btnFlightNext) { stepFlight(-1) }
        on(R.id.btnEventPrev) { stepEvent(+1) }
        on(R.id.btnEventNext) { stepEvent(-1) }
        on(R.id.btnFlagFalsePositive) { toggleFalsePositive() }
        on(R.id.btnViewEvidence) { viewEvidence(localOnly = true) }
        on(R.id.btnFetchEvidence) { viewEvidence(localOnly = false) }

        on(R.id.btnSaveCore) {
            app.config.coreUrl = etCoreUrl.text.toString()
            app.config.coreToken = etCoreToken.text.toString()
            hasSyncReport = false
            val tokenError = app.config.coreTokenStorageError
            if (tokenError != null) {
                // Honest: do not pretend the token was stored.
                show("보안 저장소 오류 — 토큰이 저장되지 않았습니다: $tokenError")
            } else if (app.config.coreConfigured) {
                // A8: a Core that just became reachable picks up the latest
                // finished flight whose local data is complete but unsynced.
                // Skipped while the store is down (nothing provably complete).
                val resumed = if (session.storageAvailable) {
                    SyncScheduler.enqueuePending(applicationContext, session)
                } else {
                    null
                }
                if (resumed != null) {
                    show("Core 설정 저장 — 자동 동기화 예약: ${resumed.take(8)}")
                } else {
                    show("Core 설정 저장")
                }
            } else {
                show("업로드 비활성: Core URL과 토큰이 필요합니다")
            }
            refresh()
        }
        on(R.id.btnSaveOperator) {
            app.config.operatorName = etOperatorName.text.toString()
            app.config.operatorOrganization = etOperatorOrg.text.toString()
            refreshOperatorState()
            val org = app.config.operatorOrganization
            show(
                if (app.config.operatorRegistered) {
                    "조종사 등록: ${app.config.operatorName}" + if (org.isNotEmpty()) " ($org)" else ""
                } else {
                    "조종사 이름이 비었습니다 — 오탐 표시/해제가 잠깁니다"
                },
            )
        }
        on(R.id.btnUpload) { uploadNow(selectedFlightId) }
        on(R.id.btnServerState) { serverState() }

        refresh()
        savedInstanceState?.let { state ->
            workspace.restoreUiState(state)
            val destination = state.getInt("workspace.destination", R.id.btnNavObserve)
            if (destination in navTargets.map { it.first }) showPage(destination)
        }
        // A8 recovery trigger: a process start re-queues a finished flight
        // whose previously incomplete local evidence is now complete.
        // Skipped while the store is down (nothing may be proven complete
        // from unavailable data).
        if (session.storageAvailable) {
            SyncScheduler.enqueuePending(applicationContext, session)?.let { flightId ->
                hasSyncReport = true
                tvSync.text = "자동 동기화 재개 예약: ${flightId.take(8)} (완료된 로컬 데이터 재동기화)"
            }
        }

        // A9-3 review residual: a restart during an active flight used to
        // silently stop recording. Resume it — SegmentRecorderCore continues
        // the flight directory's numbering — and state the lost tail.
        // Never resume after a durable recording-only close: the recorder
        // was already finalized and the flight awaits STOPPED; restarting
        // capture would orphan segments outside any close-out. Never resume
        // from unavailable storage (no new session from unverified data).
        val activeFlight = session.activeFlight
        val recorder = app.videoRecorder
        val closeoutPending = activeFlight?.let {
            session.latestCloseout(it.get("flight_id").asText())?.outcome ==
                CloseoutRecord.UNRESOLVED
        } == true
        if (session.storageAvailable && activeFlight != null && recorder != null &&
            recorder.state != VideoRecorder.State.RECORDING && !closeoutPending
        ) {
            val flightId = activeFlight.get("flight_id").asText()
            app.startFlightRecording(flightId, flightSegmentSeconds(flightId))
            show("녹화 재개: 재시작 이전 구간 일부는 저장되지 않았습니다 (세그먼트 번호는 이어짐)")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("workspace.site", selectedSiteId)
        outState.putString("workspace.expandedSite", expandedSiteId)
        outState.putString("workspace.bundle", selectedBundleId)
        outState.putString("workspace.record", selectedFlightId)
        outState.putInt("workspace.event", selectedEventIndex)
        outState.putInt("workspace.destination", currentDestination)
        if (::workspace.isInitialized) workspace.saveUiState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        session.setUiStateListener { cockpitStateDirty = true }
        refresh()
        cockpitHandler.removeCallbacks(cockpitTicker)
        cockpitHandler.postDelayed(cockpitTicker, COCKPIT_REFRESH_MS)
    }

    override fun onPause() {
        activityResumed = false
        session.setUiStateListener(null)
        cockpitHandler.removeCallbacks(cockpitTicker)
        super.onPause()
    }

    override fun onDestroy() {
        activityResumed = false
        session.setUiStateListener(null)
        cockpitHandler.removeCallbacks(cockpitTicker)
        telemetryUiHandler.removeCallbacksAndMessages(null)
        if (::telemetrySource.isInitialized) telemetrySource.stop()
        if (::cameraSource.isInitialized) cameraSource.stop()
        // The app-scoped video source must not retain an Activity-bound
        // listener across rotation.
        app.videoSource.setListener(null)
        super.onDestroy()
    }

    private fun on(id: Int, action: () -> Unit) {
        val button = buttonOrNull(id) ?: return
        button.setOnClickListener {
            session.clearError()
            shownError = null
            try {
                action()
            } catch (exc: Exception) {
                show("오류: ${exc.message}")
            }
            session.syncFlightState()
            refresh()
        }
    }

    /** G18: bind-once cache for nav + connect buttons; missing ids log and skip. */
    private fun bindStaticButtons() {
        navTargets.forEach { (navId, _) ->
            val button: Button? = findViewById(navId)
            if (button == null) {
                Log.w(TAG, "bindStaticButtons: missing nav button id=$navId")
            } else {
                navButtons[navId] = button
            }
        }
        val connect: Button? = findViewById(R.id.btnConnect)
        if (connect == null) {
            Log.w(TAG, "bindStaticButtons: missing btnConnect")
        } else {
            btnConnect = connect
        }
    }

    /** G18: guarded lookup — log + null instead of crashing on a missing id. */
    private fun buttonOrNull(id: Int): Button? {
        navButtons[id]?.let { return it }
        if (id == R.id.btnConnect) btnConnect?.let { return it }
        val button: Button? = findViewById(id)
        if (button == null) Log.w(TAG, "buttonOrNull: missing button id=$id")
        return button
    }

    /**
     * Adopted site-first mission: concise cards (name + one-line status +
     * suggestion chip) built from the real store. Cards start CLOSED and
     * stay closed until the operator taps one — no auto-select, no
     * auto-expand on refresh; the only exception is a genuine active
     * session, whose site is fixed by the session itself. Selecting only
     * sets ids for the existing prepare/apply/record path — it never
     * starts, applies, or records anything. A suggestion chip never forces:
     * tapping selects, it never auto-confirms. While a session is active
     * the site stays fixed (no mid-session switch).
     *
     * Offline-first drafts join the list: each unresolved pending site
     * renders as a selectable card under its client UUID (honest pending
     * badge, never "등록됨") so a new preparation can be composed against
     * it once a bundle exists — selecting never claims registration.
     */
    private fun refreshSiteCards() {
        if (!::siteCardList.isInitialized) return
        if (missionPlanPanel == null) missionPlanPanel = MissionPlanPanel(this, siteCardDetail)
        if (!session.storageAvailable) {
            siteCardList.removeAllViews()
            renderedSiteCards = null
            selectedSiteId = null
            selectedBundleId = null
            expandedSiteId = null
            missionPlanPanel?.clear()
            workspace.bindMissionSite(false)
            workspace.bindOfflineSites(emptyList(), false) { }
            findViewById<View>(R.id.missionCommandDock)?.visibility = View.GONE
            return
        }
        val sites = session.store.sites()
        val siteIds = sites.mapNotNull { it.get("site_id")?.asText() }
        val pendingStatuses = session.pendingSites.statuses().filter { !it.isSynced }
        val pendingIds = pendingStatuses.map { it.draft.clientSiteId }
        val knownSiteIds = siteIds + pendingIds
        val active = session.activeFlight
        active?.get("site_id")?.asText()?.takeIf { it in knownSiteIds }?.let {
            selectedSiteId = it
            expandedSiteId = it
        }
        // No auto-select: an empty choice stays empty until the operator
        // picks. Only a genuine active session fixes the site.
        if (selectedSiteId == null || selectedSiteId !in knownSiteIds) {
            selectedSiteId = active?.get("site_id")?.asText()?.takeIf { it in knownSiteIds }
        }
        if (selectedBundleId != null) {
            val stillThere = session.store.missionBundles().any {
                it.get("bundle_id")?.asText() == selectedBundleId
            }
            if (!stillThere) selectedBundleId = null
        }
        if (knownSiteIds.isEmpty()) {
            siteCardList.removeAllViews()
            renderedSiteCards = null
            workspace.bindOfflineSites(emptyList(), false) { }
            siteCardList.addView(siteCardNote(getString(R.string.mission_site_empty)))
            selectedSiteId = null
            selectedBundleId = null
            expandedSiteId = null
            missionPlanPanel?.clear()
            workspace.bindMissionSite(false)
            return
        }
        if (expandedSiteId != null && expandedSiteId !in knownSiteIds) expandedSiteId = null
        // Details stay CLOSED until the operator taps a card. Collapsing
        // works because nothing reopens the disclosure behind the tap.
        val orgKnown = sites.any { it.get("org_id")?.asText()?.isNotBlank() == true }
        val coreConfigured = session.coreConfigured()
        val inputs = sites.mapNotNull { site ->
            val siteId = site.get("site_id")?.asText() ?: return@mapNotNull null
            val (plans, flights) = plansAndFlightsFor(siteId)
            MissionSiteCards.SiteInput(
                siteId = siteId,
                siteName = site.get("name")?.asText(),
                plans = plans,
                pastFlights = flights,
                suggested = siteId == suggestedSiteId,
            )
        } + pendingStatuses.map { status ->
            val pendingId = status.draft.clientSiteId
            val (plans, flights) = plansAndFlightsFor(pendingId)
            MissionSiteCards.pendingInput(
                draft = status.draft,
                plans = plans,
                pastFlights = flights,
                suggested = pendingId == suggestedSiteId,
                pendingNote = PendingSiteMissionFlow.pendingNoteFor(
                    status, coreConfigured, orgKnown,
                ),
            )
        }
        val cards = MissionSiteCards.siteCards(inputs)
        workspace.bindOfflineSites(cards, active == null) { siteId ->
            if (session.activeFlight != null) {
                show("기록 세션 중 · 현장은 고정됩니다")
            } else {
                if (selectedSiteId != siteId) selectedBundleId = null
                selectedSiteId = siteId
                expandedSiteId = siteId
                showPage(R.id.btnNavMission)
                refresh()
            }
        }
        if (renderedSiteCards != cards || renderedSiteSelection != selectedSiteId) {
            siteCardList.removeAllViews()
            cards.forEach { siteCardList.addView(buildSiteCard(it)) }
            renderedSiteCards = cards
            renderedSiteSelection = selectedSiteId
        }
        // A site tap owns the right-hand plan workspace. No automatic plan choice.
        val browsingSite = inputs.firstOrNull { it.siteId == (expandedSiteId ?: selectedSiteId) }
        // Bundle default: only the genuine active session fixes the plan.
        // Otherwise the operator picks via "이 계획으로 시작".
        val candidates = selectedSiteId?.let { siteId ->
            session.store.missionBundles().filter { it.get("site_id")?.asText() == siteId }
        }.orEmpty()
        val nextBundleIds = candidates.mapNotNull { it.get("bundle_id")?.asText() }
        selectedBundleId = MissionSelection.retainBundle(nextBundleIds, selectedBundleId, active?.get("bundle_id")?.asText())
        if (browsingSite != null) buildPlanDetail(browsingSite) else missionPlanPanel?.clear()
        workspace.bindMissionSite(browsingSite != null)
        // The workspace owns the preparation disclosure. Selecting a plan
        // updates its map/summary, never auto-opens a third column.
    }

    /**
     * Stored plans + local flights for one site id (synced server id or
     * pending client UUID — the store keys both the same way, and flights
     * recorded under a pending id keep that id).
     */
    private fun plansAndFlightsFor(
        siteId: String,
    ): Pair<List<MissionSiteCards.PlanInput>, List<MissionSiteCards.FlightInput>> {
        val bundles = session.store.missionBundles()
            .filter { it.get("site_id")?.asText() == siteId }
        // Per-plan past: only flights that recorded THIS bundle's own
        // sha count toward its outcome. Site-wide lists never spread
        // across plans; an unmatched sha claims no past result.
        val flights = session.store.flights()
            .filter { it.get("site_id")?.asText() == siteId }
            .mapNotNull { record ->
                val flightId = record.get("flight_id")?.asText() ?: return@mapNotNull null
                MissionSiteCards.FlightInput(
                    flightId = flightId,
                    bundleSha256 = record.get("bundle_sha256")?.asText(),
                    state = record.get("state")?.asText() ?: "UNKNOWN",
                    observationCount = session.store.observationsFor(flightId).size,
                )
            }
        val plans = bundles.map { bundle ->
            val snapshot = bundle.get("mission_snapshot")
            val sha = bundle.get("bundle_sha256")?.asText()
            val ownFlights = if (sha != null) {
                flights.filter { it.bundleSha256 == sha }
            } else {
                emptyList()
            }
            MissionSiteCards.PlanInput(
                bundle = MissionSiteCards.BundleInput(
                    bundleId = bundle.get("bundle_id")?.asText() ?: "-",
                    bundleSha256 = sha,
                    missionRevisionId = snapshot?.get("mission_revision_id")?.asText(),
                    zoneCount = snapshot?.get("zones")?.size(),
                    ruleCount = bundle.get("risk_policy")?.get("rules")?.size(),
                    details = StoredPlanDetails.describe(bundle),
                ),
                pastFlights = ownFlights,
            )
        }
        return plans to flights
    }

    private fun bindHistorySelector() {
        spHistoryFlight.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (updatingHistorySelector) return
                val flightId = historyFlightIds.getOrNull(position) ?: return
                if (flightId == selectedFlightId) return
                selectedFlightId = flightId
                selectedEventIndex = 0
                refreshHistory()
                refreshEvidence()
            }
        }
    }

    private fun siteCardNote(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_muted))
        textSize = 12f
    }

    private fun buildSiteCard(card: MissionSiteCards.SiteCard): View {
        val selected = card.siteId == selectedSiteId
        return MissionSiteRow.create(this, card, selected) {
            if (session.activeFlight != null) {
                show("기록 세션 중 · 현장은 고정됩니다")
            } else {
                if (selectedSiteId != card.siteId) selectedBundleId = null
                selectedSiteId = card.siteId
                expandedSiteId = card.siteId
                refresh()
            }
        }
    }

    /** Stored-plan disclosure for the expanded site: one reuse card per
     *  stored plan. Each card shows the plan's own stored facts, an honest
     *  "경로 자료 미수신" route line (never invented geometry), and ONLY
     *  the past outcome of flights that recorded that plan's own bundle —
     *  or an honest empty state. */
    private fun buildPlanDetail(input: MissionSiteCards.SiteInput) {
        missionPlanPanel?.bind(input, selectedBundleId,
            session.activeFlight?.get("bundle_id")?.asText(),
            session.pendingFlightId?.let { session.controller?.bundleId },
            onSelect = { id ->
                if (MissionSelection.canChoose(id, session.activeFlight?.get("bundle_id")?.asText(),
                        session.pendingFlightId?.let { session.controller?.bundleId })) {
                    selectedBundleId = id
                    selectedSiteId = input.siteId
                    expandedSiteId = input.siteId
                    refresh()
                    findViewById<View>(R.id.workspaceMissionOpenPreparation)?.requestFocus()
                }
            },
            onHistory = { id ->
                selectedFlightId = id
                selectedEventIndex = 0
                viewerSession.select()
                showPage(R.id.btnNavReview)
                workspace.showRecordIndex()
                recordDetailRequestedId = id
                refreshHistory()
                refreshEvidence()
            },
        )
    }

    private fun selectedPlanMatchesController(): Boolean {
        val selected = selectedBundleId?.let { session.store.missionBundle(it) }
        return MissionSelection.matches(selectedBundleId, selected?.get("bundle_sha256")?.asText(),
            session.controller?.bundleId, session.controller?.bundleSha)
    }

    private fun toggleFocusMode() {
        flightChromeCollapsed = !flightChromeCollapsed
        updateFlightChrome()
        refreshWideVideoButton()
    }

    private fun refreshWideVideoButton() {
        buttonOrNull(R.id.btnWideVideo)?.apply {
            setText(
                if (flightChromeCollapsed) R.string.btn_wide_video_off else R.string.btn_wide_video_on,
            )
        }
    }

    /**
     * Round51: routine SHADOW never auto-opens this panel. Only the
     * operator's explicit entries (task/details buttons, priority details)
     * open it; page navigation always closes it. Candidate review stays
     * optional in record/exception detail — never a modal over video.
     */
    private fun setFlightPanelOpen(open: Boolean) {
        findViewById<View>(R.id.opsRightColumn).visibility = View.GONE
        buttonOrNull(R.id.btnTaskDetails)?.setText(R.string.btn_task_details)
        rightStatusPanel.visibility = if (open) View.VISIBLE else View.GONE
        buttonOrNull(R.id.btnTaskStatus)?.setText(
            if (open) R.string.btn_task_status_close else R.string.btn_task_status,
        )
        // The detail is a full-screen mode, not a side dock. No video width
        // is reserved while flying; opening detail intentionally leaves the
        // video view. The app header/nav remain for orientation/back.
        if (open) findViewById<View>(R.id.tvOpsPanelTitle).requestFocus()
    }

    private fun setFlightControlsOpen(open: Boolean) {
        setFlightPanelOpen(false)
        findViewById<View>(R.id.opsRightColumn).visibility = if (open) View.VISIBLE else View.GONE
        buttonOrNull(R.id.btnTaskDetails)?.setText(
            if (open) R.string.btn_task_controls_close else R.string.btn_task_details,
        )
    }

    /** Evidence is reached from records only (filter entry), with an
     *  explicit back entry to records — never a nav-rail destination. */
    private fun showEvidencePage() {
        showPage(R.id.btnNavReview)
        findViewById<View>(R.id.pageReview)?.visibility = View.GONE
        findViewById<View>(R.id.pageEvidence)?.visibility = View.VISIBLE
        tvPageTitle.setText(R.string.page_title_evidence)
        updateFlightChrome()
        refreshEvidence()
    }

    private fun updateFlightChrome() {
        if (::workspace.isInitialized) {
            val evidenceOpen = findViewById<View>(R.id.pageEvidence).visibility == View.VISIBLE
            workspace.onDestination(currentDestination)
            if (evidenceOpen) {
                findViewById<View>(R.id.pageReview).visibility = View.GONE
                findViewById<View>(R.id.pageEvidence).visibility = View.VISIBLE
            }
            navRail.visibility = View.GONE
            appHeader.visibility = View.VISIBLE
            btnNavMenu.visibility = View.VISIBLE
            headerDivider.visibility = View.GONE
            for (id in listOf(R.id.btnReqPhoto, R.id.btnReqRec, R.id.btnReqZoom)) {
                findViewById<View>(id).visibility = if (flightChromeCollapsed) View.GONE else View.VISIBLE
            }
            refreshConnectionHeadline()
            return
        }
        val observeVisible =
            findViewById<View>(R.id.pageObserve).visibility == View.VISIBLE
        // Round48 v2: observe always reclaims the rail. The focus-mode flag
        // only hides the header for full video; the header menu entry reaches
        // every destination through the same showPage path.
        val chrome = ObserveChrome.resolve(observeVisible, flightChromeCollapsed)
        navRail.visibility = if (chrome.railVisible) View.VISIBLE else View.GONE
        appHeader.visibility = if (chrome.headerVisible) View.VISIBLE else View.GONE
        headerDivider.visibility = appHeader.visibility
        btnNavMenu.visibility = if (chrome.menuVisible) View.VISIBLE else View.GONE
        tvConnectionHeadline.visibility = if (observeVisible) View.VISIBLE else View.GONE
        val reviewVisible = findViewById<View>(R.id.pageReview).visibility == View.VISIBLE
        for (id in listOf(R.id.btnReviewDetail, R.id.btnOpenEvidence)) {
            findViewById<View>(id).visibility = if (reviewVisible) View.VISIBLE else View.GONE
        }
        refreshConnectionHeadline()
    }

    /** Observe reclaims the rail: a header menu entry offers the same
     *  destinations through the same showPage path. Dismiss/back restores
     *  focus to the entry. */
    /** Adopted: the nav menu holds the 4 destinations only (비행·임무·
     *  기록·설정). Per-request camera/flight entries live as contextual
     *  buttons in the observation/camera detail (deck request row) — same
     *  gates, same dialog restoration, never commands from the menu. */
    private fun showNavPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, R.id.btnNavObserve, 0, getString(R.string.nav_observe))
        popup.menu.add(0, R.id.btnNavMission, 1, getString(R.string.nav_mission))
        popup.menu.add(0, R.id.btnNavReview, 2, getString(R.string.nav_review))
        popup.menu.add(0, R.id.btnNavSettings, 3, getString(R.string.nav_settings))
        popup.setOnMenuItemClickListener { item ->
            showPage(item.itemId)
            anchor.requestFocus()
            true
        }
        popup.setOnDismissListener { anchor.requestFocus() }
        popup.show()
    }

    /** Contextual per-request entry: same gate-first dialog as before,
     *  opened from the deck — never a flight command. */
    private fun openAssist(holderId: Int) {
        val dialog = OpsAssistMenu.dialogFor(holderId) ?: return
        showSecondaryPanel(dialog.holderId, dialog.titleRes)
    }

    /** On-demand assist: a bounded dialog hosts the existing command views
     *  plus the shared request-result view, then returns both to their
     *  hidden dock holders on dismiss. Same buttons, same gate-first
     *  sendCommand, no permanent space — and a request is never written to
     *  an invisible line. */
    private fun showSecondaryPanel(holderId: Int, titleRes: Int) {
        val holder = findViewById<LinearLayout>(holderId) ?: return
        val result = if (::tvCommandResult.isInitialized) tvCommandResult else return
        val resultHome = result.parent as? android.view.ViewGroup ?: return
        if (resultHome.id != R.id.commandDock) return
        val home = holder.parent as? android.view.ViewGroup ?: return
        // Zoom is nested under the original gimbal holder. Accept only
        // descendants of this dock, not only its direct children, and restore
        // each holder to its exact original parent/index on dismiss.
        val dock = findViewById<android.view.ViewGroup>(R.id.commandDock) ?: return
        if (dock.findViewById<View>(holderId) !== holder) return
        val holderIndex = home.indexOfChild(holder)
        val resultIndex = resultHome.indexOfChild(result)
        val duplicateTitle = (holder.getChildAt(0) as? TextView)?.takeIf { it.text.toString() == getString(titleRes) }
        val titleVisibility = duplicateTitle?.visibility
        val requestLabels = when (holderId) {
            R.id.photoGroup -> listOf(R.id.btnPhoto to "촬영 요청")
            R.id.recGroup -> listOf(R.id.btnRecStart to "녹화 시작 요청", R.id.btnRecStop to "녹화 정지 요청")
            R.id.zoomGroup -> listOf(R.id.btnZoomIn to "확대 요청", R.id.btnZoomOut to "축소 요청")
            else -> emptyList()
        }.mapNotNull { (id, label) -> holder.findViewById<Button>(id)?.let { Triple(it, it.text, label) } }
        requestLabels.forEach { (button, _, label) -> button.text = label }
        home.removeView(holder)
        resultHome.removeView(result)
        holder.visibility = View.VISIBLE
        result.visibility = View.VISIBLE
        duplicateTitle?.visibility = View.GONE
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (16 * resources.displayMetrics.density + .5f).toInt()
            setPadding(inset, inset / 2, inset, 0)
            addView(holder)
            addView(result)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(when (holderId) {
                R.id.photoGroup -> "기체 사진 촬영"
                R.id.recGroup -> "기체 녹화"
                R.id.zoomGroup -> "카메라 줌"
                else -> getString(titleRes)
            })
            .setView(body)
            .setPositiveButton(R.string.btn_close_ops_panel, null)
            .setOnDismissListener {
                (holder.parent as? android.view.ViewGroup)?.removeView(holder)
                (result.parent as? android.view.ViewGroup)?.removeView(result)
                holder.visibility = View.GONE
                if (titleVisibility != null) duplicateTitle?.visibility = titleVisibility
                requestLabels.forEach { (button, original, _) -> button.text = original }
                home.addView(holder, holderIndex.coerceIn(0, home.childCount))
                resultHome.addView(result, resultIndex.coerceIn(0, resultHome.childCount))
                if (::btnNavMenu.isInitialized) btnNavMenu.requestFocus()
            }
            .create()
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            val width = minOf((360 * density + .5f).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun run(label: String, action: () -> Unit) {
        val active = session.controller
        if (active == null) {
            show("$label 불가: 연결된 번들 없음 (임무 동기화 또는 데모 섹션)")
            return
        }
        // State checks + command queueing stay on the UI thread (cheap,
        // in-memory); ONLY the blocking socket write goes background
        // (A9-4: link.send on the main thread throws
        // NetworkOnMainThreadException while the envelope still reaches
        // Edge via retry — a refusal banner for a sent command). The
        // toast posts back on UI with a real reason (never literal null).
        try {
            action()
        } catch (exc: Exception) {
            Log.e(TAG, "$label refused", exc)
            show("$label 거부: ${refusalReason(exc)}")
            return
        }
        session.pumpOutboundAsync { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { sent ->
                        if (label != "Edge 관측 시작 요청") show(
                            if (session.link.state == DroneLink.State.CONNECTED && sent > 0) {
                                "$label 전송됨 · 장치 응답은 별도 확인"
                            } else if (session.link.state == DroneLink.State.CONNECTED) {
                                "$label · 추가 전송 없음 · 장치 상태는 별도 확인"
                            } else {
                                "$label 대기열 (링크 끊김, ${sent}건 전송)"
                            },
                        )
                    },
                    onFailure = { exc ->
                        Log.e(TAG, "$label pump failed", exc)
                        show("$label 거부: ${refusalReason(exc)}")
                    },
                )
            }
        }
    }

    /**
     * User-visible refusal reason that is never blank: the exception
     * message when present, else the exception class (a null message must
     * never surface as the literal "null").
     */
    private fun refusalReason(exc: Throwable): String =
        // Round68 copy-density: internal English causes become a short Korean
        // action phrase on the banner; the raw cause stays in logs/details.
        UserFacingCopy.causeLabel(
            exc.message?.takeIf { it.isNotBlank() } ?: exc.javaClass.simpleName,
        )

    /**
     * G17: the mission order is declared statically in activity_main.xml
     * (left bounded scroll: full readiness facts → sync row → sync chip →
     * additional-check/reconcile row → observe session row; fixed command
     * dock: concise next-action (max 2 lines) → prepare → apply →
     * local-record start → explanation scroll). This only binds the static
     * views and sets the initial chip text; no runtime remove/add
     * reordering (no parent-cast / sibling-order assumptions,
     * no silent early return).
     */
    private fun installMissionReadinessUi() {
        val readiness: TextView? = findViewById(R.id.tvMissionReadiness)
        if (readiness == null) {
            Log.w(TAG, "installMissionReadinessUi: missing tvMissionReadiness")
            return
        }
        tvMissionReadiness = readiness
        val nextAction: TextView? = findViewById(R.id.tvMissionNextAction)
        if (nextAction == null) {
            Log.w(TAG, "installMissionReadinessUi: missing tvMissionNextAction")
            return
        }
        tvMissionNextAction = nextAction
        val syncResult: TextView? = findViewById(R.id.tvMissionSyncResult)
        if (syncResult == null) {
            Log.w(TAG, "installMissionReadinessUi: missing tvMissionSyncResult")
            return
        }
        tvMissionSyncResult = syncResult
        updateMissionSyncResultUi()
    }

    private fun setMissionSyncResult(resource: Int) {
        missionSyncResultResource = resource
        updateMissionSyncResultUi()
    }

    private fun updateMissionSyncResultUi() {
        if (!::tvMissionSyncResult.isInitialized) return
        val result = getString(missionSyncResultResource)
        tvMissionSyncResult.text = result
        tvMissionSyncResult.contentDescription = getString(
            R.string.mission_sync_result_accessibility_format,
            result,
            getString(R.string.mission_sync_result_description),
        )
    }

    private fun refresh() {
        cockpitStateDirty = false
        val flight = session.activeFlight
        val store = session.store
        val opsItems = renderOpsStatus(flight, store)
        refreshTopBar()
        refreshConnectionHeadline()
        refreshSiteSuggestion()
        refreshConnectEmphasis()
        refreshSiteCards()
        val selectedBundle = selectedBundleId?.let { store.missionBundle(it) }
        val coreReadiness = getString(
            if (app.config.coreConfigured) {
                R.string.mission_readiness_sync_configured
            } else {
                R.string.mission_readiness_sync_unconfigured
            },
        )
        val bundleReadiness = selectedBundle?.let {
            getString(
                R.string.mission_readiness_bundle_format,
                it.get("bundle_id")?.asText()?.take(8) ?: "알 수 없음",
            )
        } ?: getString(R.string.mission_readiness_bundle_none)
        val recordReadiness = when {
            flight != null -> getString(R.string.mission_readiness_record_active)
            selectedBundle == null -> getString(R.string.mission_readiness_record_no_bundle)
            else -> getString(R.string.mission_readiness_record_ready)
        }
        if (::tvMissionReadiness.isInitialized) {
            val lines = listOf(
                getString(R.string.mission_readiness_sync_format, coreReadiness),
                bundleReadiness,
                recordReadiness,
            )
            // Degraded: readiness from unavailable data would pose as fact.
            tvMissionReadiness.text = if (session.storageAvailable) {
                lines.joinToString("\n")
            } else {
                (listOf(StorageHealth.UNAVAILABLE_BANNER) + lines).joinToString("\n")
            }
        }
        // Fixed-dock next action: one concise derived line (max 2), never the
        // full facts. Pure derivation — no gate or button logic changes here.
        // The scope metadata (recipient / requested change / actual effect)
        // rides the content description, so ACK/APPLIED is never read as
        // engine installation and record start never reads as flight control.
        if (::tvMissionNextAction.isInitialized) {
            val next = MissionNextAction.decide(
                MissionNextAction.Input(
                    storageAvailable = session.storageAvailable,
                    hasActiveFlight = flight != null,
                    hasSelectedBundle = selectedBundle != null,
                    controllerStateName = session.controller?.state?.name,
                ),
            )
            val nextRes = when (next) {
                MissionNextAction.Next.STORAGE_DOWN -> R.string.mission_next_storage_down
                MissionNextAction.Next.RECORDING -> R.string.mission_next_recording
                MissionNextAction.Next.SELECT_BUNDLE -> R.string.mission_next_select_bundle
                MissionNextAction.Next.WAITING -> R.string.mission_next_waiting
                MissionNextAction.Next.APPLY -> R.string.mission_next_apply
                MissionNextAction.Next.START_RECORD -> R.string.mission_next_start_record
                MissionNextAction.Next.PREPARE -> R.string.mission_next_prepare
                MissionNextAction.Next.UNKNOWN -> R.string.mission_next_unknown
            }
            tvMissionNextAction.setText(nextRes)
            // The derived next step, not always record creation, owns the
            // primary visual role. Enabled/safety gates are untouched.
            val primaryId = when (next) {
                MissionNextAction.Next.PREPARE -> R.id.btnPrepare
                MissionNextAction.Next.APPLY -> R.id.btnApply
                MissionNextAction.Next.START_RECORD -> R.id.btnStartFlight
                else -> null
            }
            for (id in listOf(R.id.btnPrepare, R.id.btnApply, R.id.btnStartFlight)) {
                buttonOrNull(id)?.apply {
                    setBackgroundResource(if (id == primaryId) R.drawable.button_primary else R.drawable.button_secondary)
                    setTextColor(ContextCompat.getColorStateList(
                        this@MainActivity,
                        if (id == primaryId) R.color.button_primary_text else R.color.button_secondary_text,
                    ))
                }
            }
            val scope = MissionNextAction.meta(next)
            tvMissionNextAction.contentDescription = buildString {
                append(getString(nextRes).replace("\n", " "))
                append(" · ").append(scope.target)
                append(" · ").append(scope.request)
                append(" · ").append(scope.effect)
                append(".")
            }
        }
        updateMissionSyncResultUi()
        tvMission.text = if (selectedBundle == null) {
            "이 기기에 선택 가능한 번들이 없습니다. 현장 동기화 후 번들을 내려받으세요."
        } else {
            "기체 임무 상태 · ${missionStateLabel()}\n" +
                "선택 번들 digest: ${selectedBundle.get("bundle_sha256")?.asText()?.take(12) ?: "-"} " +
                "(다운로드 시 검증)"
        }
        tvMissionSite.text = "현장 ID · ${selectedSiteId?.take(8) ?: "선택 필요"}"
        tvMissionBundle.text = "번들 ID · ${selectedBundle?.get("bundle_id")?.asText()?.take(8) ?: "선택 필요"}"
        val snapshot = selectedBundle?.get("mission_snapshot")
        val revisionId = flight?.get("mission_revision_id")?.asText()
            ?: snapshot?.get("mission_revision_id")?.asText()
        tvMissionRevision.text = getString(
            R.string.mission_revision_display_format,
            revisionId?.take(8) ?: "-",
        )
        tvMissionRevision.contentDescription = revisionId?.let {
            getString(R.string.mission_revision_accessibility_format, it)
        } ?: getString(R.string.mission_revision_unknown_accessibility)
        val zoneCount = flight?.get("zone_revision_ids")?.size()
            ?: snapshot?.get("zones")?.size()
        tvMissionZone.text = "구역 · ${zoneCount?.let { "${it}개" } ?: "-"}"
        refreshPreparationPanel(flight, selectedBundle)
        tvMissionFlightId.text = "기록 ID · ${flight?.get("flight_id")?.asText()?.take(8) ?: "없음"}"
        tvMissionFlightState.text = "기록 상태 · ${flight?.get("state")?.asText() ?: "미시작"}"
        tvMissionStarted.text = "시작 수신 · ${flight?.get("started_at_utc")?.asText()?.take(19) ?: "-"}"
        setButtonEnabled(R.id.btnSyncSites, app.config.coreConfigured && session.storageAvailable)
        setButtonEnabled(R.id.btnAddPendingSite, session.storageAvailable)
        setButtonEnabled(
            R.id.btnSyncBundle,
            app.config.coreConfigured && selectedSiteId != null && session.storageAvailable,
        )
        setButtonEnabled(R.id.btnComposeLocal, selectedSiteId != null && session.storageAvailable)
        setButtonEnabled(
            R.id.btnStartFlight,
            selectedBundle != null && flight == null && session.storageAvailable &&
                (session.controller == null || selectedPlanMatchesController()),
        )
        val hasController = session.controller != null && session.storageAvailable
        setButtonEnabled(
            R.id.btnPrepare,
            selectedBundle != null && flight == null && session.storageAvailable &&
                (session.controller == null || selectedPlanMatchesController()),
        )
        setButtonEnabled(R.id.btnApply, hasController && selectedPlanMatchesController())
        setButtonEnabled(R.id.btnReconcile, hasController)
        setButtonEnabled(R.id.btnStart, hasController)
        setButtonEnabled(R.id.btnStop, hasController)
        setButtonEnabled(R.id.btnEndFlight, flight != null && session.storageAvailable)

        val endpoint = session.controller?.endpoint
        val simulatedTag = if (session.link.isSimulated) " SIMULATED" else ""
        tvLink.text = "링크=${session.link.state}$simulatedTag\n" +
            "수신(세션): 저장=${session.stored} 중복=${session.duplicates} " +
            "실패=${session.failed} 마지막=${session.lastInbound}\n" +
            "송신: 대기=${endpoint?.pendingCount() ?: 0} " +
            "확인=${endpoint?.ackedCount ?: 0}"

        // A6: three INDEPENDENT connection facts — never merged.
        val aircraft = when (session.link.aircraftConnected) {
            true -> "연결됨"
            false -> "미연결"
            null -> "알 수 없음(링크 보고 불가)"
        }
        tvStatus.text = "기체=$aircraft" +
            (if (session.link.isSimulated) " [SIMULATED link]" else "") + "\n" +
            "manifold/link=${session.link.state}$simulatedTag\n" +
            "인터넷=${internetLabel()}"

        refreshVideo()
        refreshCockpitFacts(opsItems)
        refreshEdgeRuntimePanel(flight?.get("flight_id")?.asText())

        refreshOpsDeck()
        updateAiPeekFreshness()
        refreshHistory()

        val demoStore = demo.session.store
        val demoFlight = demo.activeFlight()
        tvDemo.text = "DEMO (격리 저장소, 업로드 없음)\n" +
            "비행=${demoFlight?.get("flight_id")?.asText()?.take(8) ?: "-"} " +
            "상태=${demoFlight?.get("state")?.asText() ?: "-"} " +
            "컨트롤러: ${demo.missionSummary()}\n" +
            "데모 저장소: 관측=${demoStore.observationsFor(demoFlight?.get("flight_id")?.asText() ?: "-").size}"

        refreshEvidence()
        val coreLine = if (app.config.coreConfigured) {
            "Core 연결 설정됨 · 서버 보관 상태는 ‘서버 확인’으로 조회하세요"
        } else {
            "Core 미설정 · 설정에서 서버 주소와 접근 토큰을 저장하세요"
        }
        val tokenStorageError = app.config.coreTokenStorageError
        tvUploadGate.text = when {
            tokenStorageError != null -> "보안 저장소 오류 · 설정을 확인하세요"
            app.config.coreConfigured -> "설정됨"
            else -> "주소·접근 토큰 필요"
        }
        if (!hasSyncReport) {
            tvSync.text = coreLine
        }
        val err = session.lastError
        val errorKey = if (session.activeFlight == null && err?.contains("no mission controller", ignoreCase = true) == true) "before-record:$err" else err
        if (!err.isNullOrEmpty() && errorKey != shownError) {
            shownError = errorKey
            if (err.contains("no mission controller", ignoreCase = true) && session.activeFlight == null) {
                // Expected pre-preparation state: no banner, but shownError
                // still advances so a later active fault is never suppressed.
            } else if (err.contains("no mission controller", ignoreCase = true)) {
                show(
                    "관측 장치 응답 오류 · 임무에서 상태를 다시 확인하세요.",
                    contentDescription = "관측 장치 응답을 확인할 수 없습니다. 임무에서 상태를 다시 확인하세요.",
                )
            } else {
                show(
                    "관측 자료 수신 오류 · ${UserFacingCopy.causeLabel(err)}",
                    contentDescription = "관측 자료 수신 오류 · ${UserFacingCopy.causeLabel(err)}",
                )
            }
        }
        refreshWorkspaceState()
    }

    /** Refresh live cockpit facts at the UI cadence after new MOP bytes or
     *  link transitions; history/evidence pages are not rescanned here. */
    private fun refreshLiveCockpit() {
        val flight = session.activeFlight
        val opsItems = renderOpsStatus(flight, session.store)
        refreshTopBar()
        refreshConnectEmphasis()
        refreshVideo()
        refreshCockpitFacts(opsItems)
        refreshEdgeRuntimePanel(flight?.get("flight_id")?.asText())
        refreshOpsDeck()
        updateAiPeekFreshness()
        // Complete a previously unresolved close once the Edge confirms
        // STOPPED (the flight stayed open and blocked until now).
        maybeCompletePendingClose()
        refreshWorkspaceState()
    }

    /**
     * B-1: one honest summary of "what is happening / what to do", computed
     * from real session facts via the host-tested [OperatorStatus] model.
     * Rendering here is intentionally plain text; the visual treatment is
     * design-workspace owned (docs/plans/2026-10-12-b1-operator-flow-contract.md).
     */
    /**
     * B-1 facts without rendering: shared by the dirty path (which also
     * paints the ordered details text) and the 1s ticker (compact task row
     * only). The evaluation — and only the evaluation — decides the facts.
     */
    private fun renderOpsStatusItems(
        flight: com.fasterxml.jackson.databind.node.ObjectNode?,
        store: ksi.rc.RcFullStore,
    ): List<OperatorStatus.Item> {
        // Degraded: KSI fact counts below may be partial (poisoned real
        // store) or empty (unreadable root) — OperatorStatus renders them
        // as "상태 미확인", never as healthy/zero.
        val flightId = flight?.get("flight_id")?.asText()
        val now = ReceiptClock.now()
        val summary = flightId?.let { store.summariesFor(it).lastOrNull() }
        val artifacts = flightId?.let { store.artifactsFor(it) } ?: emptyList()
        val input = OperatorStatus.Input(
            hasActiveFlight = flightId != null,
            linkState = session.link.state.name,
            linkSimulated = session.link.isSimulated,
            aircraftConnected = runCatching { app.aircraftConnection.readAircraftConnected() }.getOrNull(),
            internet = internetStatus(),
            recorderState = app.videoRecorder?.state?.name,
            // Link liveness first (the Edge beats before the flight id is
            // adopted), then the flight-scoped receipt as a fallback.
            mopHeartbeatAgeMs = ObservationModel.heartbeatAgeMsAny(store, now)
                ?: flightId?.let {
                    ObservationModel.heartbeatAgeMs(store, it, now)
                },
            observationAgeMs = flightId?.let {
                ObservationModel.observationAgeMs(store, it, now)
            },
            eventCount = flightId?.let { store.eventsFor(it).size } ?: 0,
            artifactsTotal = artifacts.size,
            artifactsComplete = artifacts.count {
                store.isArtifactComplete(it.get("artifact_id").asText())
            },
            artifactsSynced = artifacts.count {
                store.isArtifactSynced(it.get("artifact_id").asText())
            },
            pendingAnnotations = store.pendingAnnotations().count {
                it.get("flight_id")?.asText() == flightId
            },
            coveredFrames = summary?.get("covered_frames")?.asLong(),
            skippedFrames = summary?.get("skipped_frames")?.asLong(),
            coreConfigured = session.coreConfigured(),
            recorderWrittenFrames = app.videoRecorder?.writtenFrameCount,
            recorderHasWriteFailure = app.videoRecorder?.hasWriteFailure,
            // Unit C: Edge runtime facts (honest execution evidence only).
            edgeRuntimeSeen = edgeRuntimeFieldsSeen(),
            edgeRuntimeState = session.controller?.runtimeStatus?.state?.name,
            edgeProcessedFrames = session.controller?.runtimeStatus?.processedFrameCount,
            edgeDegradedReasons = session.controller?.runtimeStatus?.degradedReasons
                ?: emptyList(),
            edgeInputState = session.controller?.runtimeStatus?.inputState ?: emptyMap(),
            edgeLinkUp = session.link.state == DroneLink.State.CONNECTED,
            edgeLastConfirmedUtc = runtimeLatch.lastConfirmedUtc,
            // AT-J1: 준비 미완 알림(준비 화면 세 사실 파생). 아직 판정 전이면 null.
            preparationNotice = preparationNotice,
            // Fault containment: when the store is down the KSI sections
            // render "상태 미확인" (never healthy/zero); physical link,
            // aircraft and recorder facts above still render.
            storageAvailable = session.storageAvailable,
            storageError = session.storageErrorMessage,
        )
        // C67: render the contract items with the approved severity colours —
        // ▮ problem (red) / ▲ attention (amber) / · info (blue); label bold.
        return OperatorStatus.evaluate(input)
    }

    private fun renderOpsStatus(
        flight: com.fasterxml.jackson.databind.node.ObjectNode?,
        store: ksi.rc.RcFullStore,
    ): List<OperatorStatus.Item> {
        val items = renderOpsStatusItems(flight, store)
        // C67: the ordered B-1 details text lives here and only here; the
        // compact task row never reorders or shortens these items.
        val styled = SpannableStringBuilder()
        var previousGroup: Int? = null
        items.forEachIndexed { index, item ->
            val group = when {
                item.label.startsWith("비행") || item.label.startsWith("MOP") ||
                    item.label.startsWith("기체") || item.label.startsWith("인터넷") -> 0
                item.label.contains("레코더") || item.label.contains("녹화") -> 1
                item.label.contains("증거") || item.label.contains("Core") ||
                    item.label.contains("의견") -> 2
                else -> 3
            }
            if (group != previousGroup) {
                if (index > 0) styled.append("\n")
                val headingStart = styled.length
                val heading = when (group) {
                    0 -> R.string.cockpit_status_group_connection
                    1 -> R.string.cockpit_status_group_recording
                    2 -> R.string.cockpit_status_group_evidence
                    else -> R.string.cockpit_status_group_observation
                }
                styled.append(getString(heading)).append('\n')
                styled.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this, R.color.ksi_muted)),
                    headingStart, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                styled.setSpan(
                    StyleSpan(Typeface.BOLD),
                    headingStart, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                styled.append("\n")
            }
            val start = styled.length
            val marker = when (item.severity) {
                OperatorStatus.Severity.PROBLEM -> "■"
                OperatorStatus.Severity.ATTENTION -> "▲"
                OperatorStatus.Severity.INFO -> "·"
            }
            styled.append("$marker ${item.label}")
            val color = when (item.severity) {
                OperatorStatus.Severity.PROBLEM -> R.color.ksi_problem
                OperatorStatus.Severity.ATTENTION -> R.color.ksi_attention
                OperatorStatus.Severity.INFO -> R.color.ksi_info
            }
            styled.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, color)),
                start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (item.detail.isNotEmpty()) styled.append(" — ${item.detail}")
            item.action?.let { styled.append("  → $it") }
            previousGroup = group
        }
        val detailsScroll = findViewById<android.widget.ScrollView>(R.id.cockpitDetailsScroll)
        val savedScrollY = detailsScroll.scrollY
        val savedFocus = currentFocus?.takeIf { isWithin(it, detailsScroll) }
        tvOpsStatus.text = styled
        if (rightStatusPanel.visibility == View.VISIBLE) {
            detailsScroll.post {
                val maxScroll = (detailsScroll.getChildAt(0)?.height ?: 0) - detailsScroll.height
                detailsScroll.scrollTo(0, savedScrollY.coerceIn(0, maxScroll.coerceAtLeast(0)))
                if (savedFocus?.isShown == true) savedFocus.requestFocus()
            }
        }
        return items
    }

    /** C67 top bar: page title + SIMULATED tag (never hidden) + receipt note. */
    private fun refreshTopBar() {
        val simulated = session.link.isSimulated || app.videoSource.isSimulated
        tvTopSim.visibility = if (simulated) View.VISIBLE else View.GONE
        tvCockpitSim.visibility = if (simulated) View.VISIBLE else View.GONE
        tvTopUtc.text = getString(R.string.top_receipt_note)
        tvTopContext.setText(R.string.cockpit_live_view)
    }

    private fun refreshVideo() {
        val source = app.videoSource
        val freshness = VideoFreshnessModel.evaluate(
            sourceState = source.state,
            receivedFrameCount = source.receivedFrameCount,
            lastFrameReceivedElapsedMs = source.lastFrameReceivedElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        val count = source.receivedFrameCount?.toString() ?: "알 수 없음"
        val encodedState = freshness.label
        val quickState = when (freshness.status) {
            VideoFreshnessModel.Status.DISCONNECTED -> "미연결"
            VideoFreshnessModel.Status.CONNECTING -> "연결 중"
            VideoFreshnessModel.Status.NO_SIGNAL -> "신호 없음"
            VideoFreshnessModel.Status.NO_FRAMES -> "프레임 없음"
            VideoFreshnessModel.Status.UNKNOWN -> "시각 미확인"
            VideoFreshnessModel.Status.RECENT -> "입력 최근"
            VideoFreshnessModel.Status.STALE -> "입력 지연"
        }
        val compact = when (freshness.status) {
            VideoFreshnessModel.Status.DISCONNECTED -> "미연결"
            VideoFreshnessModel.Status.CONNECTING -> "연결 중"
            VideoFreshnessModel.Status.NO_SIGNAL -> "신호 없음"
            VideoFreshnessModel.Status.NO_FRAMES -> "프레임 미수신"
            VideoFreshnessModel.Status.UNKNOWN -> "수신 시각 미확인"
            VideoFreshnessModel.Status.RECENT -> {
                val age = freshness.ageMs
                if (age == null) "입력 최근 시각 미확인"
                else if (age < 1_000L) "입력 최근 <1초"
                else "입력 최근 ${age / 1_000L}초"
            }
            VideoFreshnessModel.Status.STALE -> "입력 지연 ≥${freshness.ageMs?.div(1_000L) ?: "?"}초"
        }
        val markerAndColor = when (freshness.status) {
            VideoFreshnessModel.Status.NO_SIGNAL,
            VideoFreshnessModel.Status.CONNECTING,
            VideoFreshnessModel.Status.STALE -> "▲" to R.color.ksi_attention_video
            VideoFreshnessModel.Status.UNKNOWN -> "?" to R.color.ksi_video_fg
            else -> "·" to R.color.ksi_video_fg
        }
        renderStatusChip(
            tvVideoStatus,
            quickState,
            markerAndColor.second,
            markerAndColor.first,
        )
        tvVideoStatus.contentDescription = when (freshness.status) {
            VideoFreshnessModel.Status.STALE -> "영상 인코딩 입력 수신 지연. 마지막 입력 ${freshness.ageMs?.div(1_000L) ?: "알 수 없음"}초 전. 비행 안전 경고가 아닙니다. 화면 표시 상태는 확인되지 않았습니다."
            VideoFreshnessModel.Status.UNKNOWN -> "영상 입력 수신 시각을 알 수 없습니다. 지연 또는 표시 성공으로 추측하지 않습니다."
            else -> "영상 인코딩 입력 상태: $compact. 입력 수신은 디코딩 또는 화면 표시를 확인하지 않습니다."
        }
        val mapping = if (source.frameMappingVerified) {
            "프레임 매핑 확인 · overlay 기능 검증은 별도"
        } else {
            "프레임 매핑 미검증 · AI 박스 표시 안 함"
        }
        val simulated = if (source.isSimulated) " · [SIMULATED]" else ""
        tvVideo.text = "인코딩 입력 $count · 화면 표시/디코딩 확인 안 됨 · $mapping$simulated"
        tvVideo.contentDescription = "영상 입력 수신 상태: $encodedState, 수신 프레임 수 $count. 입력 수신은 디코딩·화면 표시를 확인하지 않습니다. $mapping$simulated"
        // Round68 copy-density: the center stage is narrow on the 768x480dp
        // device — short status tokens here, the full input/display caveat
        // stays in the top status/detail text. DISCONNECTED names the next
        // action (the 48dp recovery entry); the tap is a shortcut to it.
        tvVideoPlaceholder.text = when (freshness.status) {
            VideoFreshnessModel.Status.DISCONNECTED -> "영상 미연결"
            VideoFreshnessModel.Status.CONNECTING -> "영상 연결 중"
            VideoFreshnessModel.Status.NO_SIGNAL -> "영상 신호 없음"
            VideoFreshnessModel.Status.NO_FRAMES -> "영상 미수신"
            VideoFreshnessModel.Status.UNKNOWN -> "영상 상태 미확인"
            VideoFreshnessModel.Status.RECENT -> "영상 입력 수신 중"
            VideoFreshnessModel.Status.STALE -> "영상 입력 지연"
        }
        // Hide the center note while fresh input may be rendering, so it does
        // not obstruct the view. The input-only state and unverified display
        // caveat remain visible in the top status/detail text.
        tvVideoPlaceholder.visibility = if (freshness.status == VideoFreshnessModel.Status.RECENT) {
            View.GONE
        } else {
            View.VISIBLE
        }
        tvVideoPlaceholder.contentDescription = tvVideoPlaceholder.text
        // Round47: a disconnected video shows the connection recovery action
        // on the compact task row — not a developer explanation. The
        // placeholder text above stays a factual input/display statement.
        videoRecoveryNeeded = when (freshness.status) {
            VideoFreshnessModel.Status.DISCONNECTED,
            VideoFreshnessModel.Status.NO_SIGNAL,
            VideoFreshnessModel.Status.NO_FRAMES -> true
            else -> false
        }
        workspace.bindVideoConnection(tvVideoPlaceholder.text.toString(), videoRecoveryNeeded)
        buttonOrNull(R.id.btnTaskVideoRecover)?.visibility =
            if (videoRecoveryNeeded) View.VISIBLE else View.GONE
        // The empty frame is tappable only while it offers the recovery
        // action — otherwise touches fall through to the video stage.
        if (videoRecoveryNeeded) {
            tvVideoPlaceholder.isFocusable = true
            tvVideoPlaceholder.setOnClickListener { app.videoSource.connect(); refresh() }
        } else {
            tvVideoPlaceholder.setOnClickListener(null)
            tvVideoPlaceholder.isClickable = false
            tvVideoPlaceholder.isFocusable = false
        }
        // Fresh input may occupy the video surface; it is NOT a claim of
        // verified decoding. All other states expose real stored context.
        workspace.bindVideoAvailability(
            showing = freshness.status == VideoFreshnessModel.Status.RECENT,
            recoverable = videoRecoveryNeeded,
        )
    }

    /**
     * Coalesces telemetry callbacks (SDK thread on device) into one UI
     * render. The strip is static text: rapid samples do not each announce,
     * and TalkBack reads it on demand only.
     */
    private fun scheduleTelemetryRender() {
        if (telemetryRenderPending) return
        telemetryRenderPending = true
        telemetryUiHandler.post {
            telemetryRenderPending = false
            renderTelemetryStrip()
        }
    }

    private fun renderTelemetryStrip() {
        if (!::tvTeleAuthorityValue.isInitialized || !::telemetrySource.isInitialized) return
        val strip = TelemetryStripPresenter.present(
            accumulator = telemetrySource.accumulator,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            authorityValue = null, // 제어권 보고 출처 미연결 — 추정하지 않음
            mop = lastOverview?.mop,
            aircraft = lastOverview?.aircraft,
        )
        bindTelemetryCell(tvTeleAuthorityValue, strip.authority)
        bindTelemetryCell(tvTeleModeValue, strip.flightMode)
        bindTelemetryCell(tvTeleBatteryValue, strip.battery)
        bindTelemetryCell(tvTeleLinksValue, strip.links)
        bindTelemetryCell(tvTelePositionValue, strip.position)
        refreshWorkspaceHeader()
    }

    private fun bindTelemetryCell(view: TextView, cell: TelemetryStripPresenter.Cell) {
        view.text = cell.value
        view.setTextColor(
            ContextCompat.getColor(
                this,
                when (cell.severity) {
                    OperatorStatus.Severity.PROBLEM -> R.color.ksi_problem_video
                    OperatorStatus.Severity.ATTENTION -> R.color.ksi_attention_video
                    OperatorStatus.Severity.INFO -> R.color.ksi_video_fg
                },
            ),
        )
        view.contentDescription = cell.contentDescription
    }

    /**
     * Coalesces AIRCRAFT camera callbacks (SDK thread on device) into one UI
     * render. Read-only: the Snapshot is mapped to
     * [FlightCameraPresentation] and bound — command acceptances, the KSI/RC
     * proxy recorder and the shooting flag alone never produce a capture
     * confirmation.
     */
    private fun scheduleCameraRender() {
        if (cameraRenderPending) return
        cameraRenderPending = true
        telemetryUiHandler.post {
            cameraRenderPending = false
            renderCameraState()
        }
    }

    private fun renderCameraState() {
        if (!::workspace.isInitialized || !::cameraSource.isInitialized) return
        // Bench review harness owns the camera view while a camera scenario
        // is active (static sample or timed timeline): the real/fake source
        // must not race the fixture ticker. Device has no fixture (no-op).
        if (cameraFixtureOwnsView()) return
        workspace.bindCameraPresentation(mapCameraSnapshot(cameraSource.snapshot()))
    }

    private fun cameraFixtureOwnsView(): Boolean {
        // Bench review harness owns the camera view while a camera scenario
        // is active (static sample or timed timeline): the real/fake source
        // must not race the fixture ticker. Device has no fixture (no-op).
        // The extra name matches RcWorkspaceLayout ("cameraScenario").
        val scenario = intent.getStringExtra("cameraScenario") ?: return false
        if (WorkspacePreviewFixture.cameraTimelineEnabled(scenario)) return true
        return WorkspacePreviewFixture.cameraPresentation(scenario) != FlightCameraPresentation()
    }

    private fun observationStartDecision(): ObservationStartPolicy.Decision {
        val active = session.activeFlight
        val flightId = active?.get("flight_id")?.asText()
        val controller = session.controller
        return ObservationStartPolicy.decide(
            ObservationStartPolicy.Input(
                storageAvailable = session.storageAvailable,
                hasActiveFlight = active != null,
                controllerStateName = controller?.state?.name,
                linkConnected = session.link.state == DroneLink.State.CONNECTED,
                hasOpenStartIntent = flightId?.let {
                    runCatching { session.hasOpenStartIntent(it) }.getOrDefault(true)
                } ?: false,
                unresolvedCloseout = flightId?.let {
                    runCatching { session.latestCloseout(it)?.outcome == CloseoutRecord.UNRESOLVED }
                        .getOrDefault(true)
                } ?: false,
                needsRevalidation = controller?.needsRevalidation ?: false,
            ),
        )
    }

    private fun refreshWorkspaceHeader() {
        if (!::workspace.isInitialized || !::tvTeleModeValue.isInitialized || !::telemetrySource.isInitialized) return
        val aircraft = when (runCatching { app.aircraftConnection.readAircraftConnected() }.getOrNull()) {
            true -> "연결"
            false -> "미연결"
            null -> "미확인"
        }
        workspace.bindHeader(
            authority = "제어 ${tvTeleAuthorityValue.text}",
            mode = "모드 ${tvTeleModeValue.text}",
            battery = "배터리 ${tvTeleBatteryValue.text}",
            position = "홈 ${tvTelePositionValue.text}",
            aircraft = "기체 $aircraft",
            simulated = session.link.isSimulated,
        )
        workspace.bindConnections(when (session.link.state) {
            DroneLink.State.CONNECTED -> "링크 연결됨 · 장치 실행·관측은 별도 확인"
            DroneLink.State.CONNECTING -> "연결 중 · 응답을 기다립니다"
            DroneLink.State.DISCONNECTED -> "연결 안 됨 · 연결 상태를 확인하세요"
        })
        val accumulator = telemetrySource.accumulator
        val snap = accumulator.snapshot()
        val now = SystemClock.elapsedRealtime()
        fun number(field: FlightTelemetryAccumulator.Field, value: Double?, unit: String): String =
            when (accumulator.fieldState(field, now)) {
                FlightTelemetryAccumulator.FieldState.NEVER_RECEIVED -> "미수신"
                FlightTelemetryAccumulator.FieldState.UNSUPPORTED -> "미지원"
                FlightTelemetryAccumulator.FieldState.FRESH -> value?.let { "%.1f%s".format(java.util.Locale.ROOT, it, unit) } ?: "미수신"
                FlightTelemetryAccumulator.FieldState.STALE -> {
                    val last = value?.let { "%.1f%s".format(java.util.Locale.ROOT, it, unit) } ?: "미수신"
                    "$last·지연${((accumulator.ageMs(field, now) ?: 0) + 999) / 1000}초"
                }
            }
        workspace.bindFlightTelemetry(
            altitude = number(FlightTelemetryAccumulator.Field.ALTITUDE, snap.altitudeM, "m"),
            distance = number(FlightTelemetryAccumulator.Field.HOME_DISTANCE, snap.homeDistanceM, "m"),
            speed = number(FlightTelemetryAccumulator.Field.HORIZONTAL_SPEED, snap.horizontalSpeedMs, "m/s"),
            verticalSpeed = number(FlightTelemetryAccumulator.Field.VERTICAL_SPEED, snap.verticalSpeedMs, "m/s"),
            heading = number(FlightTelemetryAccumulator.Field.HEADING, snap.headingDeg, "°"),
        )
    }

    private fun refreshWorkspaceState() {
        if (!::workspace.isInitialized || !::tvCompactAi.isInitialized) return
        val active = session.activeFlight
        val bundle = selectedBundleId?.let { session.store.missionBundle(it) }
        val readiness = observationStartDecision()
        val hasUnresolvedStart = active?.get("flight_id")?.asText()?.let {
            runCatching { session.hasOpenStartIntent(it) }.getOrDefault(true)
        } == true
        val displayStage = if (readiness.stage == ObservationStartPolicy.Stage.PREPARE_REQUIRED && hasUnresolvedStart)
            ObservationStartPolicy.Stage.RECOVER else readiness.stage
        val compactFacts = AutonomousSupervision.compact(compactSupervisionInput())
        workspace.bindSupervision(
            ai = compactFacts.ai, camera = compactFacts.camera,
            preservation = compactFacts.preservation, stage = displayStage,
            canStart = readiness.canRequest, hasRecord = active != null,
        )
        buttonOrNull(R.id.btnStart)?.isEnabled = readiness.canRequest
        val next = MissionNextAction.decide(MissionNextAction.Input(
            storageAvailable = session.storageAvailable, hasActiveFlight = active != null,
            hasSelectedBundle = bundle != null, controllerStateName = session.controller?.state?.name,
        ))
        val siteName = selectedSiteId?.let { id ->
            session.store.sites().firstOrNull { it.get("site_id")?.asText() == id }?.get("name")?.asText()
                ?: session.pendingSites.statuses().firstOrNull { it.draft.clientSiteId == id }?.draft?.name
                ?: id.take(8)
        }
        val missionFacts = WorkspaceMissionFacts.derive(
            WorkspaceMissionFacts.inputFrom(
                bundle?.get("bundle_sha256")?.asText(),
                session.controller,
                session.link.state == DroneLink.State.CONNECTED,
            ),
        )
        val selectionMismatch = session.controller != null && !selectedPlanMatchesController()
        workspace.bindMission(
            bundle = bundle, siteName = siteName, stateName = session.controller?.state?.name,
            hasRecord = active != null, needsRevalidation = session.controller?.needsRevalidation ?: false,
            rejection = session.controller?.lastRejectionReasons?.takeIf { it.isNotEmpty() }
                ?.let { "관측 장치가 적용을 받지 못했습니다. 준비·적용 상세에서 확인하세요." },
            next = if (selectionMismatch || session.controller?.needsRevalidation == true) MissionNextAction.Next.UNKNOWN else next,
            facts = missionFacts,
            unsentRequestTypes = session.unsentMissionRequestTypes(bundle?.get("bundle_sha256")?.asText()),
            selectionMismatch = selectionMismatch,
        )
        if (currentDestination == R.id.btnNavObserve) {
            tvPageTitle.text = siteName ?: "비행"
        }
        refreshWorkspaceHeader()
    }

    private fun refreshCockpitFacts(items: List<OperatorStatus.Item>) {
        val activeFlight = session.activeFlight
        val activeFlightId = activeFlight?.get("flight_id")?.asText()
        val unsyncedArtifactCount = unsyncedCountFor(activeFlightId)
        val edgePanel = buildEdgePanel(activeFlightId)
        lastEdgePanel = edgePanel
        val overview = CockpitStatusPresenter.present(
            items = items,
            hasActiveFlight = activeFlight != null,
            unsyncedArtifactCount = unsyncedArtifactCount,
            edge = edgePanel,
        )
        lastOverview = overview
        workspace.bindPriorityDetail(overview.priority)
        renderTelemetryStrip()
        val endRecordButton = buttonOrNull(R.id.btnEndFlight)
        if (activeFlight == null) {
            endRecordButton?.visibility = View.GONE
            tvRecordEndNote.setText(R.string.cockpit_record_end_note_none)
        } else {
            endRecordButton?.visibility = View.VISIBLE
            tvRecordEndNote.setText(R.string.cockpit_record_end_note_active)
        }
        val internet = internetStatus()
        val (internetValue, internetMarker) = when (internet) {
            "validated" -> "연결됨" to "·"
            "connected (unvalidated)" -> "미검증" to "?"
            "no internet", "none" -> "없음" to "·"
            else -> "알 수 없음" to "?"
        }
        renderStatusChip(tvInternetStatus, "인터넷 · $internetValue", R.color.ksi_info, internetMarker)

        overview.priority?.let { priority ->
            val marker = statusMarker(priority.severity)
            val severityColor = if (priority.severity == OperatorStatus.Severity.PROBLEM) {
                R.color.ksi_problem_video
            } else {
                R.color.ksi_attention_video
            }
            val prioritySource = when {
                priority.label.startsWith("MOP") || priority.label.contains("하트비트") -> "MOP 링크"
                priority.label.startsWith("녹화") || priority.label.startsWith("레코더") ||
                    priority.label.startsWith("영상 녹화") -> "RC 프록시 기록"
                priority.label.startsWith("기체") -> "기체 연결"
                priority.label.startsWith("AI 관측") -> "AI 관측"
                else -> "상태"
            }
            tvPrimaryAlert.text = "$marker $prioritySource · ${priority.label}"
            tvPrimaryAction.text = priority.action
                ?.substringBefore(" · ")
                ?.let { "다음: $it" }
                .orEmpty()
            tvPrimaryAction.visibility = if (priority.action == null) View.GONE else View.VISIBLE
            tvPrimaryAlert.setTextColor(ContextCompat.getColor(this, severityColor))
            if (priority.action != null) {
                tvPrimaryAction.setTextColor(ContextCompat.getColor(this, severityColor))
            }
            tvPrimaryAlert.contentDescription = buildString {
                append(prioritySource).append(" · ").append(priority.label)
                if (priority.detail.isNotBlank()) append(" · ${priority.detail}")
                priority.action?.let { append(" · 다음 조치: $it") }
                if (priority.label == "AI 관측 지연") append(" · 안전 판정이나 비행 경고가 아닙니다")
                if (priority.label == "녹화 오류") append(" · 불완전 .tmp는 확정 증거가 아닙니다")
            }
            tvPrimaryAction.contentDescription = priority.action?.let { "다음 조치: $it" }
            findViewById<View>(R.id.cockpitPriorityRow).visibility = View.VISIBLE
        } ?: run {
            findViewById<View>(R.id.cockpitPriorityRow).visibility = View.GONE
            tvPrimaryAlert.setText(R.string.no_urgent_action)
            tvPrimaryAlert.setTextColor(ContextCompat.getColor(this, R.color.ksi_video_fg))
            tvPrimaryAlert.contentDescription = null
            tvPrimaryAction.text = ""
            tvPrimaryAction.visibility = View.GONE
            tvPrimaryAction.contentDescription = null
        }

    }

    /**
     * Round49: no permanent gate line anywhere. The app-specific request
     * reason lives only in request detail (assist dialogs + the details
     * panel) — never merged with ACTIVE approval or physical RC state.
     */
    private fun bindTaskGate() {
        tvControlGateReason.setText(R.string.ops_request_detail)
        tvControlGateReason.contentDescription = getString(R.string.ops_request_detail)
    }

    /**
     * Degraded: no count from unavailable data may pose as a fact — the
     * presenters render "상태 미확인" from the storage item instead.
     */
    private fun unsyncedCountFor(activeFlightId: String?): Int? {
        if (!session.storageAvailable) return null
        return activeFlightId?.let { flightId ->
            session.store.artifactsFor(flightId).count { artifact ->
                !session.store.isArtifactSynced(artifact.get("artifact_id").asText())
            }
        } ?: 0
    }

    /**
     * True once the Edge actually sent MISSION_STATUS optional runtime
     * fields (§3). The legacy `running` bool alone never counts: absent
     * fields render "Edge 실행 상태 미확인", never 0/없음.
     */
    private fun edgeRuntimeFieldsSeen(): Boolean {
        val last = session.controller?.lastStatus ?: return false
        return last.has("runtime_state") || last.has("processed_frame_count") ||
            last.has("config_applied") || last.has("effective_config_hash") ||
            last.has("last_processed_frame_age_ms") || last.has("degraded_reasons") ||
            last.has("input_state") || last.has("runtime_epoch")
    }

    /**
     * Unit C: builds the always-visible Edge runtime panel input from what
     * the Edge reported (via `MissionController`) plus the RC-local link
     * state. The receipt time latches ONLY on a new RUNNING/DEGRADED
     * report (report identity, never render time) and freezes while the
     * link is lost — normality is never estimated.
     */
    private fun buildEdgePanel(flightId: String?): EdgeRuntimePanel.Panel {
        // Bound reports only: foreign/unbound/missing-hash Edge reports are
        // display-diagnostic and never promote execution truth (a modern
        // missing hash keeps the confirmation frozen — never current time).
        val runtime = session.controller?.boundRuntimeStatus
        val linkUp = session.link.state == DroneLink.State.CONNECTED
        val lastConfirmed = runtimeLatch.observe(
            runtime?.state, runtime?.observedAtMonotonicNs, linkUp,
        ) { ReceiptClock.now() }
        return EdgeRuntimePanel.render(
            EdgeRuntimePanel.Input(
                status = runtime,
                linkUp = linkUp,
                hasActiveFlight = flightId != null,
                lastConfirmedUtc = lastConfirmed,
                analysisRan = session.controller?.executionFacts()?.frameProcessingObserved,
                observationCount = flightId?.let {
                    if (session.storageAvailable) session.store.observationsFor(it).size else null
                },
                unboundReportSeen = edgeRuntimeFieldsSeen() &&
                    session.controller?.boundRuntimeStatus == null,
                appliedShadowRuleCount = session.controller?.appliedShadowRuleCount,
                appliedShadowZoneRuleCount = session.controller?.appliedShadowZoneRuleCount,
                appliedShadowProximityRuleCount = session.controller?.appliedShadowProximityRuleCount,
                appliedOffRuleCount = session.controller?.appliedOffRuleCount,
                appliedEnabledZoneCount = session.controller?.appliedEnabledZoneCount,
                riskEvaluationAvailable = session.controller?.riskEvaluationAvailable,
                riskEvaluationUnavailableReason = session.controller?.riskEvaluationUnavailableReason,
            ),
        )
    }

    /** RC-local session facts for [SessionResultClassifier] (contract §6). */
    private fun currentBootId(flightId: String?): String? {
        if (flightId == null) return null
        // The Edge-confirmed boot once MISSION_PREPARED/MISSION_APPLIED ran;
        // otherwise the latest boot the store actually holds. Null when
        // neither exists — the verdict basis is then missing, never invented.
        return session.controller?.activeBootId
            ?: session.store.bootsFor(flightId).lastOrNull()
    }

    /**
     * RC-local session facts for [SessionResultClassifier] (contract §6).
     *
     * The SAME snapshot the verdict log persists ([SessionVerdictSnapshot]):
     * raw pass-through of the controller/store observations, store-resolved
     * counts only. No `takeIf` null-coercion (that diverged from the stored
     * `false` and re-persisted every refresh) and no flight-level fallback
     * the decide path cannot reproduce.
     */
    private fun currentSessionFacts(flightId: String?): SessionFacts {
        val bootId = currentBootId(flightId)
        val stored = if (flightId != null && bootId != null) {
            SessionFactsResolver.resolve(session.store, flightId, bootId)
        } else {
            null
        }
        return SessionVerdictSnapshot.build(
            session.controller?.executionFacts(),
            stored,
            currentVideoFailure(flightId),
        )
    }

    /**
     * Unit C: binds the fixed runtime area (state + session verdict +
     * preservation). The verdict is computed from controller execution
     * facts merged with store-derived facts ([SessionFactsResolver]) and
     * persisted via [RcSession.decideVerdict]; the display prefers the
     * persisted record when one exists. The upload wording is driven by
     * the stored commit receipt only — "업로드 완료" never implies "safe".
     */
    private fun refreshEdgeRuntimePanel(flightId: String?) {
        if (!::tvEdgeRuntimeState.isInitialized) return
        // Degraded: no verdict may be classified or displayed from
        // unavailable data — state the storage failure plainly instead.
        if (!session.storageAvailable) {
            tvEdgeRuntimeState.text = StorageHealth.UNAVAILABLE_TITLE
            tvEdgeRuntimeState.contentDescription = "Edge 실행 상태: ${StorageHealth.UNAVAILABLE_BANNER}."
            tvEdgeRuntimeDetail.text = StorageHealth.UNAVAILABLE_DETAIL
            tvEdgeRuntimeDetail.contentDescription = StorageHealth.UNAVAILABLE_DETAIL
            tvSessionResult.text = "세션 결과 · ${StorageHealth.UNAVAILABLE_DETAIL}"
            tvSessionResult.contentDescription = tvSessionResult.text.toString() + "."
            tvCommitState.text = StorageHealth.UNAVAILABLE_BANNER + closeoutStateLine(flightId)
            tvCommitState.contentDescription = tvCommitState.text.toString() + "."
            refreshZeroNote()
            return
        }
        val panel = lastEdgePanel ?: buildEdgePanel(flightId)
        // Fixed panel keeps the CONCISE state only (same normalization as
        // the task row): no processed counts, hashes, raw codes or last-
        // confirmed timestamps. Full values are bound below into the
        // scrolling detail (tvEdgeRuntimeDetail/tvSessionResult/tvCommitState
        // now live in cockpitDetailsScroll — the fixed panel cannot grow).
        val compactState = CockpitTaskPresentation.compactState(
            session.controller?.boundRuntimeStatus?.state?.name,
            flightId != null,
            panel.linkLine != null,
            panel.inputLossLine != null,
        ) + if (panel.details.any { it.contains("위험 규칙 0건") }) " · 위험 규칙 0건" else ""
        tvEdgeRuntimeState.text = compactState
        tvEdgeRuntimeState.contentDescription = "Edge 실행 상태: $compactState."
        refreshZeroNote()
        tvEdgeRuntimeDetail.text = panel.details.joinToString("\n")
        tvEdgeRuntimeDetail.contentDescription = panel.details.joinToString(". ")
        val openSession = session.activeFlight?.get("state")?.asText() != "ENDED" &&
            flightId != null
        val facts = if (flightId == null) null else currentSessionFacts(flightId)
        val bootId = currentBootId(flightId)
        val sessionId = if (flightId != null && bootId != null) {
            SessionResultArtifacts.sessionIdFor(flightId, bootId)
        } else {
            null
        }
        val persisted = sessionId?.let { session.latestVerdict(it) }
        val verdict = persisted?.result
            ?: if (flightId == null || facts == null) {
                null
            } else {
                SessionResultClassifier.classify(facts)
            }
        persistVerdictWhenChanged(flightId, bootId, facts, persisted?.factsJson)
        val artifacts = flightId?.let { session.store.artifactsFor(it) } ?: emptyList()
        val committed = artifacts.count {
            session.store.isArtifactSynced(it.get("artifact_id").asText())
        }
        val commitConfirmed: Boolean? = if (artifacts.isEmpty()) {
            null
        } else {
            committed == artifacts.size
        }
        val resultPanel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = verdict,
                openSession = openSession,
                commitConfirmed = commitConfirmed,
                committedCount = committed.takeIf { artifacts.isNotEmpty() },
                totalCount = artifacts.size.takeIf { artifacts.isNotEmpty() },
            ),
        )
        tvSessionResult.text = if (flightId == null) {
            "세션 결과 · 미확인 · 활성 세션 없음"
        } else {
            resultPanel.verdictLine
        }
        tvSessionResult.contentDescription = tvSessionResult.text.toString() + "."
        tvCommitState.text = resultPanel.preservationLine + "\n" + resultPanel.uploadLine +
            closeoutStateLine(flightId)
        tvCommitState.contentDescription = tvCommitState.text.toString() + "."
    }

    /**
     * Round68 residual 2 (device 09-polish-flight): the glance zero-note must
     * not claim "(분석 수행됨)" on a fresh boot where nothing ran. The
     * performed-analysis wording appears only after latched frame-processing
     * evidence (same latch as the AI panel); otherwise the note states the
     * miss. 0건≠안전 holds in both wordings.
     */
    private fun refreshZeroNote() {
        if (!::tvEdgeRuntimeZeroNote.isInitialized) return
        val text = if (session.controller?.executionFacts()?.frameProcessingObserved == true) {
            getString(R.string.edge_runtime_zero_note)
        } else {
            getString(R.string.edge_runtime_no_analysis_note)
        }
        tvEdgeRuntimeZeroNote.text = text
        tvEdgeRuntimeZeroNote.contentDescription = "$text."
    }

    /**
     * Durable close-out state (contract §6 closeout): an UNRESOLVED close
     * stays visibly unresolved with its recorded reason and the new-flight
     * lock — never a transient toast. Nothing when no close-out exists
     * or the latest close is clean.
     */
    private fun closeoutStateLine(flightId: String?): String {
        if (!session.storageAvailable) return "\n" + StorageHealth.UNAVAILABLE_BANNER
        val closeout = flightId?.let { session.latestCloseout(it) } ?: return ""
        if (closeout.outcome != CloseoutRecord.UNRESOLVED) return ""
        return "\n마감 미확인 · ${closeout.stopReason} · 새 비행 잠김"
    }

    /**
     * Persists the current verdict basis when it changed since the last
     * persisted record (bounded: at most one decide per distinct snapshot,
     * off the UI thread). The next refresh then displays the persisted
     * record — the verdict and the upload/commit state stay separate axes.
     *
     * Three guards, cheapest first: the in-memory snapshot of the last
     * decide, the in-flight request, then the canonical JSON against the
     * stored record (covers restarts). A repeated render with unchanged
     * facts requests nothing and writes nothing.
     */
    private fun persistVerdictWhenChanged(
        flightId: String?,
        bootId: String?,
        facts: SessionFacts?,
        persistedFactsJson: String?,
    ) {
        // Degraded: verdicts cannot be decided or persisted without the
        // store — skip (the banner explains) instead of futile retries.
        if (!session.storageAvailable) return
        if (flightId == null || bootId == null || facts == null) return
        if (session.isVerdictCurrent(facts)) return
        if (facts == pendingVerdictFacts) return
        if (persistedFactsJson == SessionVerdictSnapshot.factsJson(facts)) return
        pendingVerdictFacts = facts
        verdictSyncPending = true
        // The SAME snapshot the guards compared: the decide must carry the
        // identical localVideoFailure, or the persisted facts would diverge
        // from the displayed ones and every refresh would re-decide.
        val videoFailed = facts.recordingFailure
        session.bg.execute {
            try {
                session.decideVerdict(flightId, bootId, videoFailed)
            } catch (_: Exception) {
                // A failed decide never crashes the UI; the next refresh
                // retries with the same basis.
            }
            runOnUiThread {
                verdictSyncPending = false
                pendingVerdictFacts = null
                if (!isDestroyed) refreshEdgeRuntimePanel(flightId)
            }
        }
    }

    /**
     * Unit C: preparation facts (zone revisions + bundle digest + Edge apply
     * state) as THREE SEPARATE facts. Cache / local save / Edge apply are
     * never merged into one "준비 완료".
     */
    private fun refreshPreparationPanel(
        flight: com.fasterxml.jackson.databind.node.ObjectNode?,
        selectedBundle: com.fasterxml.jackson.databind.node.ObjectNode?,
    ) {
        if (!::tvMissionZoneRevisions.isInitialized) return
        val snapshot = selectedBundle?.get("mission_snapshot")
        val flightZoneIds = flight?.get("zone_revision_ids")
            ?.mapNotNull { it.asText() }
        val snapshotZoneIds = snapshot?.get("zones")
            ?.mapNotNull { it.get("revision_id")?.asText() }
        val controller = session.controller
        val pinnedSha = flight?.get("bundle_sha256")?.asText()
        val appliedSha = controller?.lastApplication?.get("bundle_sha256")?.asText()
        val panel = MissionPreparationModel.render(
            MissionPreparationModel.Input(
                syncResult = when (missionSyncResultResource) {
                    R.string.mission_sync_result_success_local ->
                        MissionPreparationModel.SyncResult.SUCCESS_LOCAL
                    R.string.mission_sync_result_failure ->
                        MissionPreparationModel.SyncResult.FAILURE
                    else -> MissionPreparationModel.SyncResult.UNKNOWN
                },
                siteId = selectedSiteId,
                bundleId = selectedBundle?.get("bundle_id")?.asText(),
                bundleDigest = selectedBundle?.get("bundle_sha256")?.asText(),
                // The sync layer only stores digest-verified bundles
                // (MissionSync rejects mismatches before storing).
                bundleDigestVerified = selectedBundle != null,
                missionRevisionId = flight?.get("mission_revision_id")?.asText()
                    ?: snapshot?.get("mission_revision_id")?.asText(),
                zoneRevisionIds = flightZoneIds ?: snapshotZoneIds,
                controllerState = controller?.state?.name,
                applyConfirmed = if (pinnedSha != null && appliedSha != null) {
                    appliedSha == pinnedSha
                } else {
                    null
                },
                applicationId = controller?.lastApplication
                    ?.get("application_id")?.asText(),
                rejectionReasons = controller?.lastRejectionReasons ?: emptyList(),
                linkUp = session.link.state == DroneLink.State.CONNECTED,
                hasActiveFlight = flight != null,
                restoredPendingBundleId = session.restoredPendingPreparation?.bundleId,
                restoredPendingDecidedAtUtc = session.restoredPendingPreparation?.decidedAtUtc,
            ),
        )
        // AT-J1: 같은 세 사실에서 "준비 미완" 한 줄을 만든다(거짓 준비 완료 0).
        // 미확인(null)은 실패로 쓰지 않고 "미확인"으로 말한다.
        preparationNotice = PreparationReadiness.notice(
            PreparationReadiness.Facts(
                syncConfirmed = when (missionSyncResultResource) {
                    R.string.mission_sync_result_success_local -> true
                    R.string.mission_sync_result_failure -> false
                    else -> null
                },
                bundleVerified = selectedBundle != null,
                applyConfirmed = if (pinnedSha != null && appliedSha != null) {
                    appliedSha == pinnedSha
                } else {
                    null
                },
                hasActiveFlight = flight != null,
            ),
        )
        tvMissionZoneRevisions.text = panel.zoneLine
        workspace.bindPreparationRecovery(MissionPreparationPresentation.recoverySummary(
            session.restoredPendingPreparation?.bundleId,
            session.restoredPendingPreparation?.decidedAtUtc,
        ))
        tvMissionZoneRevisions.contentDescription = panel.zoneLine
        tvMissionApplyState.text = listOf(
            panel.cacheLine,
            panel.localLine,
            panel.applyLine,
        ).plus(
            if (panel.restoredLine != null) listOf(panel.restoredLine) else emptyList(),
        ).joinToString("\n")
        tvMissionApplyState.contentDescription = tvMissionApplyState.text
        refreshLocalBundleResult(selectedBundle)
    }

    /**
     * G11 LOCAL composition result: which stored zone revision ids the
     * LOCAL bundle pins, the SHADOW rule count (0 is a state, not an
     * error), and every excluded revision with its reason. The map note
     * states the editing gap instead of faking a map.
     */
    private fun refreshLocalBundleResult(selectedBundle: com.fasterxml.jackson.databind.node.ObjectNode?) {
        if (!::tvLocalBundleResult.isInitialized) return
        val selectedId = selectedBundle?.get("bundle_id")?.asText()
        val composed = lastLocalBundle?.takeIf {
            it.bundle.get("bundle_id").asText() == selectedId
        }
        val localPanel = LocalBundlePanel.render(
            LocalBundlePanel.Input(
                bundleId = composed?.bundle?.get("bundle_id")?.asText(),
                zoneRevisionIds = composed?.zoneRevisionIds,
                ruleCount = composed?.ruleCount,
                excluded = composed?.excluded ?: emptyList(),
            ),
        )
        tvLocalBundleResult.text = (
            listOf(localPanel.compositionLine, localPanel.ruleLine) +
                localPanel.excludedLines + localPanel.mapNote
            ).joinToString("\n")
        tvLocalBundleResult.contentDescription = tvLocalBundleResult.text
    }

    private fun isWithin(child: View, ancestor: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    /**
     * D3 — 연결 3단(SDK 키 / 기체 / MOP) 상태를 만든다.
     *
     * 세 사실의 원천을 **끊지 않는다**:
     * - SDK: 키는 앱 매니페스트(어느 링크 경로에서도 읽힘), 등록 상태는
     *   링크 또는 MSDK 자체(어느 쪽이든 실제 등록만 인정). 같은 key-class
     *   결함 — 시험 링크의 기본값(false)이 등록된 SDK를 실패로 오표시 —
     *   은 초기화에도 있어 양쪽 다 OR한다. 막 시작해 결과가 없는 등록은
     *   확인 중(UNKNOWN)으로, 확정 실패가 아니다.
     * - 기체: MSDK가 보는 물리 연결 (null = 확인 불가).
     * - MOP: RC↔Manifold 관측 채널 (null = 확인 불가).
     */
    private fun connectionDiagnosis(): ConnectionDiagnosis.Diagnosis {
        val link = session.link
        val aircraft = runCatching { app.aircraftConnection.readAircraftConnected() }.getOrNull()
        val mopUp = link.state == DroneLink.State.CONNECTED
        val keyPresent = link.sdkKeyPresent || PlatformLinks.sdkKeyPresent(this)
        val initialised = link.sdkInitialised || PlatformLinks.sdkInitialised()
        val base = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = keyPresent,
            sdkInitialised = initialised,
            aircraftConnected = aircraft,
            mopConnected = if (mopUp) true else if (link.isSimulated) null else false,
        )
        return withSdkPendingState(
            diagnosis = base,
            keyPresent = keyPresent,
            initialised = initialised,
            pending = if (::telemetrySource.isInitialized) {
                PlatformLinks.sdkInitPending(telemetrySource)
            } else {
                false
            },
            pendingValue = getString(R.string.connection_sdk_checking),
            pendingRemedy = getString(R.string.connection_sdk_checking_remedy),
        )
    }

    /** D3 — 헤더 1줄 + [상세] 진입 버튼. */
    private fun refreshConnectionHeadline() {
        if (!::tvConnectionHeadline.isInitialized) return
        val diagnosis = connectionDiagnosis()
        tvConnectionHeadline.text = diagnosis.headline
        tvConnectionHeadline.contentDescription = buildString {
            append("연결 상태: ").append(diagnosis.headline)
            diagnosis.firstProblem?.let {
                append(" · ").append(it.title).append(" ").append(it.value)
            }
            append(".")
        }
        val detail = buttonOrNull(R.id.btnConnectionDetail)
        val onFlight = findViewById<View>(R.id.pageObserve).visibility == View.VISIBLE
        detail?.visibility = if (onFlight && !diagnosis.allOk) View.VISIBLE else View.GONE
        detail?.setOnClickListener { showConnectionDetail(diagnosis) }
        refreshWorkspaceHeader()
    }

    /**
     * D2 — 현장 확인 카드(선택 기능).
     *
     * **자동 확정하지 않는다.** GPS 후보가 있으면 제시만 하고 조종자가 확인한다.
     * 후보가 없거나 위치를 모르면 "현장 선택 필요"만 보여준다. 어느 경우든
     * 비행 준비·AI 관측·기록 시작은 막지 않는다.
     */
    private fun refreshSiteSuggestion() {
        if (!::tvSiteSuggestion.isInitialized) return
        // 세션이 이미 있으면 현장은 그 세션에 고정되어 있다(비행 중 자동 변경 금지).
        // 이때는 카드를 감춘다 — 카드 자리가 명령 데크에 필요하지 않다.
        val hasActiveSession = session.activeFlight != null
        findViewById<View>(R.id.siteConfirmPanel)?.visibility =
            if (hasActiveSession) View.GONE else View.VISIBLE
        if (hasActiveSession) {
            tvSiteSuggestion.text = getString(R.string.site_confirm_placeholder)
            tvSiteSuggestionAlt.text = "-"
            return
        }
        // 위치 출처를 구분한다: 기체 위치(MSDK) → RC 자체 GNSS → 미확인.
        val aircraft = runCatching { telemetrySource.aircraftPosition }.getOrNull()
        val rcGnss = runCatching { RcGnssPosition.lastKnown(this) }.getOrNull()
        val position = when {
            aircraft != null -> ksi.rc.SiteSuggestion.Position(
                lat = aircraft.first,
                lon = aircraft.second,
                source = ksi.rc.SiteSuggestion.PositionSource.AIRCRAFT,
            )
            rcGnss != null -> ksi.rc.SiteSuggestion.Position(
                lat = rcGnss.first,
                lon = rcGnss.second,
                source = ksi.rc.SiteSuggestion.PositionSource.RC_GNSS,
                accuracyM = runCatching { RcGnssPosition.lastAccuracyM(this) }.getOrNull(),
            )
            else -> null
        }
        val sites = runCatching { session.store.sites() }.getOrElse { emptyList() }
        val suggestion = ksi.rc.SiteSuggestion.suggest(sites, position)
        suggestedSiteId = suggestion?.candidates?.firstOrNull()?.siteId
        tvSiteSuggestion.text = ksi.rc.SiteSuggestion.headline(suggestion)
        tvSiteSuggestionAlt.text = ksi.rc.SiteSuggestion.alternativesLine(suggestion) ?: "-"
        tvSiteSuggestion.contentDescription = buildString {
            append(ksi.rc.SiteSuggestion.headline(suggestion))
            ksi.rc.SiteSuggestion.alternativesLine(suggestion)?.let { append(" · ").append(it) }
            append(".")
        }
        // D2: "이 위치로 현장 찾기"는 **명시적 요청**이다. 누를 때만 위치 권한을
        // 묻고, 그 전에는 권한 대화로 첫 진입을 막지 않는다.
        // 버튼은 "위치를 아직 못 읽을 때"만 보인다 — 권한 체크가 아니라
        // 실제 가용성으로 판단한다(실기기에서 권한 체크와 읽기 가능이 어긋남).
        btnSiteGnss.visibility = if (position != null) View.GONE else View.VISIBLE
        btnSiteGnss.setOnClickListener {
            requestLocationPermission()
        }

        // D2 동작 2종 — **자동 확정은 없다. 조종자가 누른다.**
        // ① "이 현장으로 준비": 추천 후보(가장 가까운 1건)를 선택하고 준비 화면으로.
        //    후보가 없으면 이 버튼은 없다(없는 걸 만들지 않는다).
        val actions = SiteCardActions.decide(suggestion, sites.size)
        btnSiteUseSuggested.visibility =
            if (actions.showUseSuggested) View.VISIBLE else View.GONE
        btnSiteUseSuggested.setOnClickListener {
            val siteId = actions.useSuggestedSiteId ?: return@setOnClickListener
            selectedSiteId = siteId
            // 그 현장의 번들 중 마지막 것을 기본 후보로 둔다(준비 화면이 검증한다).
            selectedBundleId = session.store.missionBundles()
                .lastOrNull { it.get("site_id")?.asText() == siteId }
                ?.get("bundle_id")?.asText()
            refresh()
            showPage(R.id.btnNavMission)
        }
        // ② "다른 현장 선택": 현장 선택은 **준비 화면의 선택 목록**에서 한다.
        //    카드로 되돌아오지 않고 그 자리로 간다(왕복을 만들지 않는다).
        btnSiteChooseOther.visibility =
            if (actions.showChooseOther) View.VISIBLE else View.GONE
        btnSiteChooseOther.setOnClickListener {
            showPage(R.id.btnNavMission)
        }
    }

    /**
     * D2 — 위치 권한 획득 여부(선택 기능).
     *
     * **fine만**을 기준으로 판단한다. Android 12+에서는 coarse를 부여하면
     * fine이 "부여됨"으로 보고되므로 coarse만 허용받았는데도 버튼이
     * 사라지는 일이 생긴다(실기기에서 확인). 추천精度에 필요한 것은 fine이며,
     * coarse만으로는 후보를 만들지 않는다.
     */
    private fun hasLocationPermission(): Boolean =
        try {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /** D3 — 상세 진단: 3단계 각각의 상태·원인·조치. */
    /**
     * 시험용 Edge 링크(TCP) 설정.
     *
     * WHY A DIALOG AND NOT A SETTINGS SCREEN: 이 경로는 **개발·통합시험용**이고
     * 제품 경로가 아니다. 그래서(1) 기본값은 "사용 안 함", (2) 켜도 화면이
     * 경로를 구분해 표시하고, (3) 기체 명령은 그대로 차단된다.
     */
    private fun showTestEdgeLinkDialog() {
        val hostInput = android.widget.EditText(this).apply {
            hint = "Edge 호스트 IP"
            setSingleLine()
        }
        val portInput = android.widget.EditText(this).apply {
            hint = "포트 (예: 5060)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(hostInput)
            addView(portInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.test_edge_link_title)
            .setMessage(R.string.test_edge_link_note)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = portInput.text.toString().trim().toIntOrNull() ?: 0
                TestEdgeLink.save(this, hostInput.text.toString(), port)
                refreshTestEdgeLinkState()
                show(
                    if (port in 1..65535 && hostInput.text.toString().isNotBlank()) {
                        "시험용 Edge 링크 저장됨 — 앱을 다시 시작하면 적용됩니다"
                    } else {
                        "시험용 Edge 링크를 사용하지 않습니다(기체 경유 MOP 사용)"
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 설정 화면의 시험용 링크 상태 줄. */
    private fun refreshTestEdgeLinkState() {
        if (!::tvTestEdgeLink.isInitialized) return
        tvTestEdgeLink.text = TestEdgeLink.describe(this)
    }

    /**
     * 조종사 대응 이력 한 줄 — 기록 검토 화면.
     *
     * 화면에서 말하는 것: 대응의 **개수**와 **전달 상태**만. `확인`은 위험
     * 해소를 뜻하지 않고, 증명이 없는 전송·수신은 "미확인"으로 낮춰 말한다.
     */
    private fun operatorResponseSessionLine(): String {
        val log = session.operatorActions
        val actions = try {
            log.readAll()
        } catch (exc: Exception) {
            return "조종사 대응 기록 확인 불가 — 0건이 아니라 확인 불가"
        }
        return OperatorResponseSummary.sessionLine(actions) { log.effectiveDelivery(it) }
    }

    /**
     * 한 사건에 대한 조종사 대응 표시(없으면 null).
     *
     * '오탐 의심'(사후 검토 의견)과 '대응'(운용 중 결정)은 **다른 기록**이라
     * 둘 다 보인다. 전달 상태는 증명이 있는 것만 말한다.
     */
    private fun operatorResponseForEvent(eventId: String?): String? {
        if (eventId.isNullOrBlank()) return null
        val log = session.operatorActions
        val actions = try {
            log.readAll()
        } catch (exc: Exception) {
            return "대응 확인 불가"
        }.filter { it.evidenceSourceId == eventId }
        if (actions.isEmpty()) return null
        return OperatorResponseSummary.lineFor(actions) { log.effectiveDelivery(it) }
    }

    private fun showConnectionDetail(diagnosis: ConnectionDiagnosis.Diagnosis) {
        showConnectionDetail(
            diagnosis,
            msdkReportsConnection = PlatformLinks.msdkReportsConnection(),
        )
    }

    /**
     * Connection detail with the A9-4 unverified-report line: when the
     * aircraft fact is unknown but the MSDK itself reports connected, the
     * report stays visible as its own line (masked as neither 연결됨 nor
     * 미연결). Host-testable condition lives in [unverifiedReportLine].
     */
    private fun showConnectionDetail(
        diagnosis: ConnectionDiagnosis.Diagnosis,
        msdkReportsConnection: Boolean,
    ) {
        val body = buildString {
            // The compact header keeps aircraft/battery/authority visible.
            // Its disclosure retains the unabridged telemetry facts, including
            // mode/home and every unknown/stale qualifier from the presenter.
            if (::tvTeleModeValue.isInitialized) {
                append("모드 ${tvTeleModeValue.text}\n")
                append("배터리 ${tvTeleBatteryValue.text}\n")
                append("제어 ${tvTeleAuthorityValue.text}\n")
                append("홈 ${tvTelePositionValue.text}\n\n")
            }
            append(getString(R.string.connection_detail_intro))
            append("\n\n")
            diagnosis.steps.forEach { step ->
                append(ConnectionDiagnosis.detailLine(step))
                append("\n\n")
            }
            val aircraftUnknown = diagnosis.steps.any {
                it.stage == ConnectionDiagnosis.Stage.AIRCRAFT &&
                    it.state == ConnectionDiagnosis.State.UNKNOWN
            }
            unverifiedReportLine(
                aircraftUnknown = aircraftUnknown,
                msdkReportsConnection = msdkReportsConnection,
                line = getString(R.string.connection_aircraft_reported_unverified),
            )?.let {
                append(it)
                append("\n\n")
            }
        }
        val detailText = TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
            val inset = (16 * resources.displayMetrics.density + .5f).toInt()
            setPadding(inset, inset / 2, inset, inset)
        }
        val detailScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            setScrollIndicators(View.SCROLL_INDICATOR_TOP or View.SCROLL_INDICATOR_BOTTOM)
            addView(detailText)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.connection_detail_title)
            .setView(detailScroll)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            val width = minOf((520 * density + .5f).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
            detailScroll.layoutParams = detailScroll.layoutParams.apply {
                height = minOf((248 * density + .5f).toInt(), (resources.displayMetrics.heightPixels * .52f).toInt())
            }
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun statusMarker(severity: OperatorStatus.Severity): String = when (severity) {
        OperatorStatus.Severity.PROBLEM -> "■"
        OperatorStatus.Severity.ATTENTION -> "▲"
        OperatorStatus.Severity.INFO -> "·"
    }

    private fun renderStatusChip(view: TextView, text: String, colorId: Int, marker: String = "·") {
        view.text = "$marker $text"
        view.setTextColor(ContextCompat.getColor(this, colorId))
    }

    private fun missionStateLabel(): String {
        val controller = session.controller
            ?: return "번들 선택 후 준비 요청 가능 (기록 세션 없음)"
        return when (controller.state.name) {
            "IDLE" -> "기체 확인 대기"
            "PREPARING" -> "준비 요청 중"
            "PREPARED" -> "준비 응답 확인"
            "APPLYING" -> "번들 적용 요청 중"
            "STATUS_UNKNOWN" -> "적용 상태 미확인 · 재조정 필요"
            "APPLIED" -> "번들 적용 응답 확인"
            "SESSION_PENDING" -> "관측 세션 응답 대기"
            "RUNNING" -> "관측 세션 실행 보고"
            "STOPPED" -> "관측 세션 정지 보고"
            "REJECTED" -> "기체에서 요청 거부"
            else -> "상태 ${controller.state.name}"
        }
    }

    /**
     * C67 (design round 3): the MOP Connect CTA is neutral by default and
     * takes the problem treatment only while the link state is DISCONNECTED.
     */
    /** G18: guarded enable — log + skip instead of crashing on a missing id. */
    private fun setButtonEnabled(id: Int, enabled: Boolean) {
        val button = buttonOrNull(id) ?: return
        button.isEnabled = enabled
    }

    private fun refreshConnectEmphasis() {
        val problem = session.link.state == DroneLink.State.DISCONNECTED
        val connect = btnConnect ?: buttonOrNull(R.id.btnConnect) ?: return
        connect.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (problem) R.color.ksi_problem_bg else R.color.ksi_surface,
        )
        connect.setTextColor(
            ContextCompat.getColor(this, if (problem) R.color.ksi_problem else R.color.ksi_fg),
        )
    }

    /** Feature navigation; immersive cockpit is one destination, not a menu phase. */
    private fun showPage(navId: Int) {
        recordDetailRequestedId = null
        currentDestination = navId
        val cockpit = navId == R.id.btnNavObserve
        // Round48 v2: entering observe never depends on the focus-mode flag.
        // The rail stays reclaimed; only the header (with its menu) shows.
        flightChromeCollapsed = false
        navTargets.forEach { (nav, page) ->
            val pageView: View? = findViewById(page)
            if (pageView == null) {
                Log.w(TAG, "showPage: missing page id=$page")
            } else {
                pageView.visibility = if (nav == navId) View.VISIBLE else View.GONE
            }
            val button = navButtons[nav] ?: buttonOrNull(nav) ?: return@forEach
            val selected = nav == navId
            button.isSelected = selected
        }
        tvPageTitle.setText(
            when (navId) {
                R.id.btnNavMission -> R.string.page_title_mission
                R.id.btnNavReview -> R.string.page_title_review
                R.id.btnNavSettings -> R.string.page_title_settings
                else -> R.string.page_title_inflight
            },
        )
        // Navigation always closes the records overlay: detail on demand,
        // never stuck open across destinations.
        findViewById<View>(R.id.reviewOverlay)?.visibility = View.GONE
        // Stale tap feedback never follows across destinations: the banner is
        // one-shot (show()/showBanner), refresh() never re-asserts it, and live
        // conditions own dedicated surfaces (panel banners, disabled buttons).
        if (::tvError.isInitialized) tvError.visibility = View.GONE
        // Evidence is a records-internal sub-view (not a destination):
        // leaving via any destination always returns to the records page.
        findViewById<View>(R.id.pageEvidence)?.visibility = View.GONE
        setFlightPanelOpen(false)
        updateFlightChrome()
        // The same RC workspace header owns all destinations; retain the
        // controller's full height while respecting the system navigation inset.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun refreshEvidence() {
        // Degraded: zero counts below would pose as healthy/empty facts.
        if (!session.storageAvailable) {
            tvEvidence.text = StorageHealth.UNAVAILABLE_BANNER
            setButtonEnabled(R.id.btnUpload, false)
            setButtonEnabled(R.id.btnServerState, false)
            return
        }
        val store = session.store
        val rows = FlightHistoryModel.flights(store)
        val activeId = session.activeFlight?.get("flight_id")?.asText()
        val selectedId = selectedFlightId?.takeIf { id -> rows.any { it.flightId == id } }
            ?: activeId?.takeIf { id -> rows.any { it.flightId == id } }
            ?: rows.firstOrNull()?.flightId
        if (selectedId != null) selectedFlightId = selectedId

        val allArtifacts = rows.flatMap { row -> store.artifactsFor(row.flightId) }
        val total = allArtifacts.size
        val complete = allArtifacts.count { store.isArtifactComplete(it.get("artifact_id").asText()) }
        val committed = allArtifacts.count { store.isArtifactSynced(it.get("artifact_id").asText()) }
        val pending = total - committed
        val lines = StringBuilder()
        lines.append("전체 로컬 자료 · 비행 ${rows.size}건 · 증거 ${total}건\n")
        lines.append("로컬 수신 완료 $complete/$total · 서버 커밋 확인 $committed/$total · 대기/미확인 ${pending}건\n")
        lines.append("\n선택 비행 · ")
        val selected = selectedId?.let { id -> rows.firstOrNull { it.flightId == id } }
        if (selected == null) {
            lines.append("기록 없음")
        } else {
            lines.append("${selected.siteName ?: selected.siteId.take(8)} · ${selected.flightId.take(8)}\n")
            lines.append("관측 ${selected.observationCount} · 사건 ${selected.eventCount} · ")
            lines.append("수신시각 ${selected.startedAtUtc?.take(19) ?: "알 수 없음"} (근사)\n\n")
            val selectedArtifacts = store.artifactsFor(selected.flightId)
            if (selectedArtifacts.isEmpty()) {
                lines.append("이 비행의 로컬 증거 자료가 없습니다. 사건 0건이나 안전 확인을 뜻하지 않습니다.")
            } else {
                selectedArtifacts.take(12).forEach { artifact ->
                    val id = artifact.get("artifact_id")?.asText() ?: "-"
                    val bytes = artifact.get("size_bytes")?.asLong() ?: 0L
                    val localComplete = store.isArtifactComplete(id)
                    val serverCommitted = store.isArtifactSynced(id)
                    // Round68: 숫자·상태 우선 — MIME/바이트 원문은 짧은 토큰으로.
                    lines.append("${id.take(8)} · ${UserFacingCopy.mediaLabel(artifact.get("media_type")?.asText())} · ${UserFacingCopy.sizeLabel(bytes)}\n")
                    lines.append("  로컬 ${if (localComplete) "수신 완료" else "수신 미완료"} · ")
                    lines.append("서버 ${if (serverCommitted) "커밋 확인" else "미확인"}\n")
                }
                if (selectedArtifacts.size > 12) {
                    lines.append("외 ${selectedArtifacts.size - 12}건 · 상세 상태는 비행 이력에서 확인")
                }
            }
        }
        tvEvidence.text = lines.toString()
        val hasSelection = selectedId != null
        val canReachCore = session.coreConfigured()
        val selectedHasPending = selected?.let { row ->
            store.artifactsFor(row.flightId).any { artifact ->
                !store.isArtifactSynced(artifact.get("artifact_id").asText())
            } || store.pendingAnnotations().any {
                it.get("flight_id")?.asText() == row.flightId
            }
        } == true
        setButtonEnabled(
            R.id.btnUpload,
            hasSelection && canReachCore && selectedHasPending,
        )
        setButtonEnabled(R.id.btnServerState, hasSelection && canReachCore)
    }

    /**
     * 1s-ticker freshness for the task-first observation row: rebuilds the
     * compact task mapping from current facts (same inputs as the dirty
     * path, without rewriting the B-1 details text or the priority row).
     * The Edge runtime state leads; the RC-receipt freshness follows —
     * never the legacy bool, never a safety verdict.
     */
    private fun updateAiPeekFreshness() {
        val flight = session.activeFlight
        val items = renderOpsStatusItems(flight, session.store)
        val flightId = flight?.get("flight_id")?.asText()
        val unsynced = unsyncedCountFor(flightId)
        val edgePanel = buildEdgePanel(flightId)
        lastEdgePanel = edgePanel
        val overview = CockpitStatusPresenter.present(
            items = items,
            hasActiveFlight = flight != null,
            unsyncedArtifactCount = unsynced,
            edge = edgePanel,
        )
        lastOverview = overview
        // Ticker path: the glance supervision row rebinds here (no store
        // read) so link/staleness changes show newest without waiting for
        // a dirty state refresh. Full deck axes stay on the dirty path.
        bindCompactRow()
    }

    // -- A7 flight history ------------------------------------------------------

    private fun stepFlight(direction: Int) {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val rows = FlightHistoryModel.flights(session.store)
        if (rows.isEmpty()) {
            show("기록된 비행 없음")
            return
        }
        val current = rows.indexOfFirst { it.flightId == selectedFlightId }
        val next = ((current.takeIf { it >= 0 } ?: 0) + direction)
            .coerceIn(0, rows.size - 1)
        selectedFlightId = rows[next].flightId
        selectedEventIndex = 0
        refreshHistory()
    }

    private fun stepEvent(direction: Int) {
        if (selectedEventRows.isEmpty()) {
            show("이 비행에 사건 없음")
            return
        }
        selectedEventIndex = (selectedEventIndex + direction)
            .coerceIn(0, selectedEventRows.size - 1)
        refreshEventSelection()
    }

    private fun toggleFalsePositive() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        if (!app.config.operatorRegistered) {
            show("조종사 미등록 — 설정·개발에서 조종사 이름을 등록해야 의견을 기록할 수 있습니다 (사람 단위 기록)")
            return
        }
        val row = selectedEventRows.getOrNull(selectedEventIndex) ?: run {
            show("먼저 사건을 선택하세요")
            return
        }
        val flightId = selectedFlightId ?: return
        // Disk append + chain fold run off the UI thread; views update on it.
        session.bg.execute {
            try {
                val records = session.store.annotationsFor(row.card.eventId)
                val effective = FlightHistoryModel.effectiveAnnotation(records)
                val message = if (effective == FlightHistoryModel.ANNOTATION_FALSE_POSITIVE) {
                    val target = records.lastOrNull {
                        it.get("annotation_type")?.asText() ==
                            FlightHistoryModel.ANNOTATION_FALSE_POSITIVE
                    } ?: throw IllegalStateException("annotation record missing; nothing to revoke")
                    session.store.queueAnnotation(
                        FlightHistoryModel.revokePrevious(
                            target, app.config.operatorId, session.rcDeviceId, ReceiptClock.now(),
                        ),
                    )
                    "오탐 의심 해제 — REVOKE_PREVIOUS 기록 추가 (원 기록은 보존)"
                } else {
                    session.store.queueAnnotation(
                        FlightHistoryModel.falsePositiveAnnotation(
                            eventId = row.card.eventId,
                            flightId = flightId,
                            actorId = app.config.operatorId,
                            clientId = session.rcDeviceId,
                            artifactIds = row.card.evidence.map { it.artifactId },
                            createdAtLocal = ReceiptClock.now(),
                        ),
                    )
                    "오탐 의심 표시 — 새 주석 기록 추가 (탐지/증거는 수정되지 않음)"
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // A late opinion must still reach the server even when
                    // the evidence was fully synced earlier.
                    val scheduled = if (session.coreConfigured()) {
                        SyncScheduler.enqueue(applicationContext, flightId)
                        " · 서버 동기화 예약"
                    } else {
                        " · Core 설정 시 동기화"
                    }
                    show(message + scheduled)
                    refreshHistory()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("의견 기록 실패: ${exc.message}") }
            }
        }
    }

    private fun viewEvidence(localOnly: Boolean) {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val row = selectedEventRows.getOrNull(selectedEventIndex) ?: run {
            show("먼저 사건을 선택하세요")
            return
        }
        // Never pick an arbitrary first JSON when a later genuine image
        // exists: prefer a complete image link, then any image link, and
        // only then the legacy first link.
        val mediaById: Map<String, String> = selectedFlightId?.let { fid ->
            runCatching {
                session.store.artifactsFor(fid).associate { artifact ->
                    artifact.get("artifact_id").asText() to
                        (artifact.get("media_type")?.asText() ?: "")
                }
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val links = row.card.evidence
        val artifactId = links.firstOrNull { link ->
            link.complete && (mediaById[link.artifactId] ?: "").startsWith("image/")
        }?.artifactId
            ?: links.firstOrNull { link ->
                (mediaById[link.artifactId] ?: "").startsWith("image/")
            }?.artifactId
            ?: links.firstOrNull()?.artifactId ?: run {
                show("이 사건에 연결된 증거 없음")
                return
            }
        viewEvidenceFor(artifactId, row.card.eventId, localOnly)
    }

    /**
     * Async evidence load with a staleness guard: the request paints only
     * when it is still the current one. A completed load for a previous
     * selection/event/artifact is dropped — never a stale frame attribution.
     */
    private fun viewEvidenceFor(artifactId: String, eventId: String, localOnly: Boolean) {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val request = viewerSession.request(selectedFlightId, eventId, artifactId)
        viewerChosenArtifact = artifactId
        for (i in 0 until filmstripList.childCount) {
            val frame = filmstripList.getChildAt(i)
            frame.isSelected = frame.tag == artifactId
        }
        tvEvidenceMeta.text = getString(R.string.review_viewer_loading)
        setEvidenceViewerMessage(getString(R.string.review_viewer_loading), recovering = false)
        val query = session.query()
        if (!localOnly && query == null) {
            tvEvidenceMeta.text = "Core 미설정 · 설정에서 연결 정보를 저장하세요"
            setEvidenceViewerMessage(tvEvidenceMeta.text.toString())
            show("Core 미설정(서버 가져오기 불가)")
            return
        }
        session.bg.execute {
            try {
                val slice = if (localOnly) {
                    val local = session.store.artifactBytes(artifactId)
                    if (local == null) {
                        runOnUiThread {
                            // Current request only: a stuck "loading" line is
                            // replaced by an explicit missing state, not a
                            // toast alone. Stale requests stay silent.
                            if (!isDestroyed && viewerSession.shouldApply(request)) {
                                tvEvidenceMeta.text = "증거 ${artifactId.take(8)} · 로컬 수신 불완전/미검증 — 서버 가져오기 사용"
                                tvEvidenceMeta.contentDescription = tvEvidenceMeta.text.toString() + "."
                                setEvidenceViewerMessage(tvEvidenceMeta.text.toString())
                            }
                            show("$artifactId: 로컬 수신 불완전/미검증 — 서버 가져오기 사용")
                        }
                        return@execute
                    }
                    EvidenceSource.LOCAL to local
                } else {
                    val fetched = query!!.artifactBytes(artifactId)
                    if (fetched == null) {
                        runOnUiThread {
                            if (!isDestroyed && viewerSession.shouldApply(request)) {
                                tvEvidenceMeta.text = getString(R.string.review_viewer_missing)
                                tvEvidenceMeta.contentDescription = tvEvidenceMeta.text.toString() + "."
                                setEvidenceViewerMessage(tvEvidenceMeta.text.toString())
                            }
                            show("$artifactId: 로컬·서버 모두 없음")
                        }
                        return@execute
                    }
                    fetched.source to fetched.items
                }
                val (source, bytes) = slice
                val bitmap = decodeSampled(bytes)
                val sourceLabel = when (source) {
                    EvidenceSource.LOCAL -> "기기 로컬"
                    EvidenceSource.SERVER -> "Core 응답"
                    else -> "출처 미확인"
                }
                runOnUiThread {
                    // Stale result: the selection moved on — drop, never paint.
                    if (isDestroyed || !viewerSession.shouldApply(request)) return@runOnUiThread
                    if (bitmap != null) {
                        ivEvidence.setImageBitmap(bitmap)
                        setEvidenceViewerMessage(null)
                        tvEvidenceMeta.text =
                            if (previewFixtureActive) "[SIMULATED] 참고사진 ESO/E. Graf · CC BY 4.0 · 실제 KSI 자료 아님"
                            else "출처 $sourceLabel · 이미지 검증 · ${UserFacingCopy.sizeLabel(bytes.size.toLong())} · ${artifactId.take(8)}"
                    } else {
                        ivEvidence.setImageDrawable(null)
                        setEvidenceViewerMessage("이 자료는 이미지 형식이 아닙니다\n수신·보관 상태에서 자료를 확인하세요")
                        tvEvidenceMeta.text =
                            "증거 ${artifactId.take(8)} · ${bytes.size}B · 출처 $sourceLabel · 이미지 형식 아님"
                    }
                }
            } catch (exc: Exception) {
                runOnUiThread {
                    if (!isDestroyed && viewerSession.shouldApply(request)) {
                        tvEvidenceMeta.text = getString(R.string.review_viewer_load_failed)
                        tvEvidenceMeta.contentDescription = tvEvidenceMeta.text.toString() + "."
                        setEvidenceViewerMessage(tvEvidenceMeta.text.toString())
                    }
                    show("증거를 불러오지 못했습니다 · 연결을 확인하고 다시 시도하세요")
                }
            }
        }
    }

    /**
     * Bounded decode: Edge snapshots can be large; sampling keeps the
     * preview well under any single-allocation limit (no full-res crash).
     */
    private fun decodeSampled(
        bytes: ByteArray,
        maxDimension: Int = 2048,
    ): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension ||
            bounds.outHeight / sample > maxDimension
        ) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun refreshHistory() {
        // Degraded: an empty read below would pose as "no records" — state
        // the unknown instead. Retained facts stay visible once repaired.
        if (!session.storageAvailable) {
            historyGeneration++
            recordDetailRequestedId = null
            tvFlights.text = StorageHealth.UNAVAILABLE_BANNER
            tvFlightDetail.text = StorageHealth.UNAVAILABLE_DETAIL
            eventRowList.removeAllViews()
            tvFlightArtifacts.text = StorageHealth.UNAVAILABLE_DETAIL
            tvCustodyLine.text = StorageHealth.UNAVAILABLE_BANNER
            tvCustodyLine.contentDescription = StorageHealth.UNAVAILABLE_BANNER + "."
            workspace.bindRecordContext(null, StorageHealth.UNAVAILABLE_BANNER)
            setEvidenceViewerMessage(StorageHealth.UNAVAILABLE_BANNER)
            workspace.bindRecordIndex(emptyList(), null, false) { }
            return
        }
        val generation = ++historyGeneration
        val requestedFlightId = selectedFlightId
        session.bg.execute {
            val rows = FlightHistoryModel.flights(session.store)
            val selected = RecordSelection.resolve(requestedFlightId, rows.map { it.flightId })
            val labels = rows.mapIndexed { index, row ->
                val site = row.siteName ?: row.siteId.take(8)
                // D5/round51: 목록은 "어디서 무엇을 관측했나"를 먼저 보여준다.
                // SHADOW·관측 후보는 선택 확인 대상이지 검토 의무가 아니다.
                // Round68: 숫자·상태 우선 — 시각은 아래 상세(tvFlightDetail)에
                // 있으므로 라벨에서 뺀다. 긴 현장명은 가운데를 줄인다.
                "${index + 1}. $site · 관측 ${row.observationCount}건 · 후보 ${row.candidateCount}건"
            }
            val detail = selected?.let { FlightHistoryModel.detail(session.store, it) }
            // Review belongs to THIS record's immutable bundle, never the
            // currently selected mission or a site's latest cached plan.
            val pinnedBundleId = selected?.let { session.store.flight(it)?.get("bundle_id")?.asText() }
            val pinnedBundle = pinnedBundleId?.let { id ->
                session.store.missionBundles().firstOrNull { it.get("bundle_id")?.asText() == id }
            }
            val detailText = detail?.let { renderDetail(it) } ?: "-"
            val artifactsText = detail?.let { renderArtifacts(it) } ?: "-"
            runOnUiThread {
                if (generation != historyGeneration || isDestroyed) return@runOnUiThread
                // Keep the requested id if unavailable: do not silently substitute another record.
                selectedFlightId = selected ?: requestedFlightId
                selectedEventRows = detail?.events ?: emptyList()
                workspace.bindRecordIndex(rows, selected, session.storageAvailable) { id ->
                    selectedFlightId = id
                    selectedEventIndex = 0
                    viewerSession.select()
                    recordDetailRequestedId = id
                    refreshHistory()
                }
                val missingRequested = requestedFlightId != null && selected == null
                historyFlightIds = if (missingRequested) listOf(null) + rows.map { it.flightId } else rows.map { it.flightId }
                val displayLabels = if (missingRequested) listOf("선택한 기록을 찾을 수 없음") + labels
                    else labels.ifEmpty { listOf("관측 기록 없음") }
                updatingHistorySelector = true
                if (historyAdapterLabels != displayLabels) {
                    historyAdapterLabels = displayLabels
                    spHistoryFlight.adapter = object : ArrayAdapter<String>(
                        this,
                        android.R.layout.simple_spinner_item,
                        displayLabels,
                    ) {
                        // Round68: middle-ellipsis keeps the leading index/site
                        // AND the trailing counts glanceable on 768x480dp.
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View {
                            val view = super.getView(position, convertView, parent)
                            (view as? TextView)?.apply {
                                textSize = 13f
                                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
                                setSingleLine(true)
                                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding((8 * resources.displayMetrics.density).toInt(), 0,
                                    (8 * resources.displayMetrics.density).toInt(), 0)
                            }
                            return view
                        }

                        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return super.getDropDownView(position, convertView, parent).also { item ->
                                (item as? TextView)?.apply {
                                    textSize = 13f
                                    minHeight = (48 * resources.displayMetrics.density).toInt()
                                    maxLines = 2
                                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
                                }
                            }
                        }
                    }.also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                }
                val selectedIndex = selected?.let { id ->
                    historyFlightIds.indexOf(id).takeIf { it >= 0 }
                } ?: 0
                spHistoryFlight.setSelection(selectedIndex, false)
                updatingHistorySelector = false
                val selectedPosition = selected?.let { id ->
                    historyFlightIds.indexOf(id).takeIf { it >= 0 }
                }
                tvFlights.text = if (requestedFlightId != null && selected == null) {
                    "선택한 기록을 이 기기에서 찾을 수 없습니다. 목록에서 기록을 선택하세요."
                } else if (rows.isEmpty()) {
                    "이 기기에 저장된 관측 기록이 없습니다."
                } else {
                    "${rows.size}개 로컬 관측 세션 · 선택 ${selectedPosition?.plus(1) ?: "-"}/${rows.size} · 최신 기록 우선"
                }
                if (selectedEventRows.isEmpty()) {
                    selectedEventIndex = 0
                } else {
                    selectedEventIndex = selectedEventIndex.coerceIn(0, selectedEventRows.size - 1)
                }
                tvFlightDetail.text = detailText
                workspace.bindRecordContext(pinnedBundle, detail?.row?.let { row ->
                    "관측 ${row.observationCount}건 · 후보 ${row.candidateCount}건 · " +
                        "RC 자료 ${row.artifactCompleteCount}/${row.artifactCount}"
                } ?: "저장 기록 없음")
                buildEventRows(detail)
                tvFlightArtifacts.text = artifactsText
                refreshEventSelection()
                // D5: 관측 요약 + 동기화(유실 확인) — 같은 화면에 모은다.
                val summary = detail?.let {
                    ObservationRecordModel.sessions(session.store, session.verdicts)
                        .firstOrNull { row -> row.flightId == it.row.flightId }
                }
                // 요약(무엇을 봤는가) + 검토 준비/중앙 보관(어디까지 준비됐는가)을
                // **다른 문장**으로 붙인다(여정 정본 §3: 두 축을 합치면 오독한다).
                tvObservationSummary.text = summary?.let {
                    buildString {
                        append(ObservationRecordModel.summaryLine(it))
                        append("\n")
                        append(
                            ObservationRecordModel.reviewReadinessLine(
                                session.store,
                                it.flightId,
                                sourceEvidence,
                            )
                        )
                        // Sealed machine judgements (SHADOW, artifact
                        // plane): disposition + reasons per tick, linked by
                        // tick — 0건 and 미수신 stay different sentences.
                        append("\n")
                        append(AssessmentModel.summaryLine(session.store, it.flightId))
                        // 조종사가 현장에서 한 대응(확인·보류·전달 요청)과 그
                        // 전달 상태. "대응 기록이 없다"는 위험 없음이 아니다.
                        append("\n")
                        append(operatorResponseSessionLine())
                    }
                } ?: "-"
                val sync = ObservationRecordModel.syncState(
                    store = session.store,
                    serverSessionCount = serverSessionCount,
                )
                tvObservationSync.text = ObservationRecordModel.syncLine(sync)
                if (recordDetailRequestedId == requestedFlightId) {
                    recordDetailRequestedId = null
                    if (RecordSelection.shouldOpen(requestedFlightId, selected, detail != null,
                            currentDestination == R.id.btnNavReview &&
                                findViewById<View>(R.id.pageReview).visibility == View.VISIBLE)) {
                        workspace.openRecordDetail()
                    } else if (requestedFlightId != null && selected == null && currentDestination == R.id.btnNavReview) {
                        show("선택한 기록을 찾을 수 없습니다. 기록 목록을 확인하세요.")
                    }
                }
            }
        }
    }

    /**
     * 서버가 아는 세션 수. **null = 아직 조회하지 않음**이며, 유실 여부를 모른다는
     * 사실 자체를 드러내야 하므로 0으로 대체하지 않는다.
     */
    private var serverSessionCount: Int? = null
    // 원본 근거의 중앙 보관(Core 컬렉션 영수증). 조회 전에는 "확인 안 함".
    private var sourceEvidence: ReviewReadinessModel.SourceEvidence =
        ReviewReadinessModel.SourceEvidence()

    private fun renderDetail(detail: FlightHistoryModel.FlightDetail): String {
        val row = detail.row
        val state = when (row.state) {
            "STARTED" -> "기록 중"
            "ENDED" -> "기록 종료"
            else -> row.state
        }
        return buildString {
            // D5: 관측 기록이 먼저다. 비행 이력(미션·번들·구역·시간)은
            // 그 세션이 "어떤 조건에서 관측됐는지"를 말해 주는 부가 정보로 내린다.
            append("관측 ${row.observationCount}건 · SHADOW·관측 후보 ${row.candidateCount}건(선택 확인)\n")
            append("객체 관측 ${row.detectionOnlyCount}건 · 커버 ${row.coveredFrames ?: "-"} / 공백 ${row.skippedFrames ?: "-"}프레임\n")
            append("증거 로컬 ${row.artifactCompleteCount}/${row.artifactCount} · 서버 커밋 ${row.artifactSyncedCount}/${row.artifactCount} · 링크 공백 ${row.mopGapCount}\n")
            append("── 관측 조건 (부가 정보) ──\n")
            append("${row.siteName ?: "현장 ${row.siteId.take(8)}"} · ${row.flightId.take(8)} · $state\n")
            append("미션 rev ${row.missionRevisionId.take(8)} · 번들 ${row.bundleSha256.take(8)} · ")
            append("구역 ${row.zoneRevisionIds.size}개\n")
            append("시작 ${row.startedAtUtc?.take(19) ?: "알 수 없음"} · 종료 ${row.endedAtUtc?.take(19) ?: "-"} (RC 수신시각 근사)\n")
            append(getString(
                R.string.review_duration_line_format,
                formatFlightDuration(row.startedAtUtc, row.endedAtUtc),
            ))
        }
    }

    private fun formatFlightDuration(startedAtUtc: String?, endedAtUtc: String?): String {
        if (startedAtUtc.isNullOrBlank() || endedAtUtc.isNullOrBlank()) return "알 수 없음"
        val duration = runCatching {
            Duration.between(Instant.parse(startedAtUtc), Instant.parse(endedAtUtc))
        }.getOrNull()?.takeIf { !it.isNegative } ?: return "알 수 없음"
        val totalSeconds = duration.seconds
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}시간 ${minutes}분"
            minutes > 0 -> "${minutes}분 ${seconds}초"
            else -> "${seconds}초"
        }
    }

    /**
     * Adopted event-kind-first records: events grouped by their stored kind
     * (first-seen order, no invented sub-area names). Each row keeps the
     * 48dp glove minimum; tapping selects the single detail record below.
     * Custody and response/annotation facts ride the row line (content
     * description carries the same facts verbatim).
     */
    private fun buildEventRows(detail: FlightHistoryModel.FlightDetail?) {
        eventRowList.removeAllViews()
        if (detail == null) return
        if (detail.events.isEmpty()) {
            eventRowList.addView(TextView(this).apply {
                val explanation = if (detail.row.observationCount == 0) {
                    "관측 데이터 없음 · 사건 0건과 구분 필요 · 안전 확인 아님"
                } else {
                    "관측 ${detail.row.observationCount}건 · 사건 0건 · 정상 기록일 수 있으나 안전 확인은 아님"
                }
                text = if (detail.row.observationCount == 0) "관측 미수신 · 상세" else "사건 0건 · 상세"
                contentDescription = "$explanation. 자료 상태 상세 열기."
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                isFocusable = true
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("관측 상태")
                        .setMessage(explanation).setPositiveButton("닫기", null).show()
                }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
                textSize = 13f
            })
            return
        }
        for (group in EventKindGroups.group(detail.events)) {
            eventRowList.addView(TextView(this).apply {
                text = getString(
                    R.string.review_kind_section_format,
                    EventKindGroups.headerLabel(group),
                    group.rows.size,
                )
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
                textSize = 13f
            })
            for (row in group.rows) {
                val flatIndex = detail.events.indexOf(row)
                val selected = flatIndex == selectedEventIndex
                val tail = buildString {
                    operatorResponseForEvent(row.card.eventId)?.let { response ->
                        append(" · ").append(response)
                    }
                    if (row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_FALSE_POSITIVE) {
                        append(" · 오탐 의심").append(localOperatorSuffix(row.effectiveActorId))
                    } else if (row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_NEEDS_REVIEW) {
                        append(" · 검토 필요").append(localOperatorSuffix(row.effectiveActorId))
                    }
                }
                val line = EventKindGroups.rowLine(row) + tail
                eventRowList.addView(Button(this).apply {
                    text = line
                    isAllCaps = false
                    textSize = 12f
                    minWidth = 0
                    minimumHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    contentDescription = "$line."
                    setBackgroundResource(R.drawable.candidate_row_selector)
                    isSelected = selected
                    setOnClickListener {
                        selectedEventIndex = flatIndex
                        buildEventRows(detail)
                        refreshEventSelection()
                    }
                })
            }
        }
    }

    private fun renderArtifacts(detail: FlightHistoryModel.FlightDetail): String =
        buildString {
            if (detail.artifacts.isEmpty()) {
                append("연결된 증거가 없습니다")
            } else {
                detail.artifacts.forEachIndexed { index, artifact ->
                    append("${artifact.artifactId.take(8)} · ${artifact.mediaType} · ${artifact.sizeBytes}B · ")
                    append("로컬 ${if (artifact.complete) "완료" else "수신 중"} · ")
                    append("서버 ${if (artifact.synced) "커밋 확인" else "미확인"}")
                    if (index < detail.artifacts.size - 1) append("\n")
                }
            }
        }

    private fun refreshEventSelection() {
        val row = selectedEventRows.getOrNull(selectedEventIndex)
        for (id in listOf(R.id.btnEventPrev, R.id.btnEventNext)) {
            setButtonEnabled(id, selectedEventRows.size > 1)
        }
        setButtonEnabled(R.id.btnViewEvidence, row != null)
        setButtonEnabled(R.id.btnFetchEvidence, row != null && session.coreConfigured())
        // A different record/event resets the image and its viewport. A
        // metadata refresh of the SAME selection must not discard an
        // operator's zoom or invalidate an explicit image request.
        val selectionKey = "${selectedFlightId}:${row?.card?.eventId}"
        val selectionChanged = selectionKey != viewerSelectionKey
        if (selectionChanged) {
            viewerSelectionKey = selectionKey
            viewerSession.select()
            viewerChosenArtifact = null
            ivEvidence.setImageDrawable(null)
            tvEvidenceMeta.text = getString(R.string.review_viewer_empty)
            tvEvidenceMeta.contentDescription = getString(R.string.review_viewer_empty) + "."
            setEvidenceViewerMessage(getString(
                if (row == null) R.string.review_observation_unavailable else R.string.review_frame_unavailable,
            ))
        }
        filmstripList.removeAllViews()
        findViewById<View>(R.id.filmstripScroll).visibility = if (row == null) View.GONE else View.VISIBLE
        if (row == null) {
            workspace.bindReviewContext("관측 시점 미선택 · 판정 자료 미수신")
            tvEventDetail.text = getString(R.string.postflight_no_event_detail)
            tvCustodyLine.text = getString(R.string.review_custody_unknown)
            tvCustodyLine.contentDescription = getString(R.string.review_custody_unknown) + "."
            btnFlagFalsePositive.text = getString(R.string.postflight_no_event_action)
            btnFlagFalsePositive.isEnabled = false
            workspace.bindCustody(null, null, null, false)
            return
        }
        btnFlagFalsePositive.isEnabled = true
        workspace.bindReviewContext(
            "관측 ${selectedEventIndex + 1}/${selectedEventRows.size} · " +
                EventKindGroups.headerLabel(EventKindGroups.group(listOf(row)).first()) +
                "\n품질 ${UserFacingCopy.observationQualityLabel(row.card.observationQuality)} · " +
                (AssessmentModel.rowSuffix(row.card.assessment) ?: "기계 평가 미수신 · 안전 확인 아님"),
        )
        btnFlagFalsePositive.text = if (
            row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_FALSE_POSITIVE
        ) {
            getString(R.string.btn_unflag_false_positive)
        } else {
            getString(R.string.btn_flag_false_positive)
        }
        tvEventDetail.text = buildString {
            append(if (row.card.detectionOnly) "객체 관측" else "위험 후보 · 경고 아님")
            append(" · ${row.card.eventType} · ${row.card.mode}\n")
            append("사건 ID ${row.card.eventId.take(8)} · 관측 품질 ${row.card.observationQuality}\n")
            append("분석 시각 원값 ${row.card.detectedAtEdgeNs} (Edge 단조시계 · RC UTC 변환 안 함)\n")
            append("연결 증거: ")
            if (row.card.evidence.isEmpty()) {
                append("없음")
            } else {
                row.card.evidence.forEach { link ->
                    append("[${link.artifactId.take(8)} · 로컬 ${if (link.complete) "완료" else "미완료"} · ")
                    append("서버 ${if (link.synced) "커밋 확인" else "미확인"}] ")
                }
            }
            val annotationLabel = when (row.effectiveAnnotation) {
                FlightHistoryModel.ANNOTATION_FALSE_POSITIVE -> "오탐 의심"
                FlightHistoryModel.ANNOTATION_NEEDS_REVIEW -> "검토 필요"
                else -> "없음"
            }
            append("\n의견 이력 ${row.annotationCount}건 · 현재 표시 $annotationLabel (원본은 변경되지 않음)")
            // Sealed machine judgement for this event's observation tick
            // (SHADOW-only, zero incidents/alerts/responses by contract):
            // shown verbatim, or explicitly unreceived — never 0건.
            append("\n")
            append(AssessmentModel.detailText(row.card.assessment))
        }
        // Custody stays persistent outside the overlay: short independent
        // local/RC/Core facts for the selected event (unknown, never 0).
        val completeCount = row.card.evidence.count { it.complete }
        tvCustodyLine.text = if (row.card.evidence.isEmpty()) {
            getString(R.string.review_custody_unknown)
        } else {
            getString(
                R.string.review_custody_format,
                "로컬 $completeCount/${row.card.evidence.size}",
                if (row.card.evidenceSynced) {
                    getString(R.string.review_evidence_server_done)
                } else {
                    getString(R.string.review_evidence_server_unknown)
                },
            )
        }
        tvCustodyLine.contentDescription = tvCustodyLine.text.toString() + "."
        workspace.bindCustody(
            total = row.card.evidence.size.takeIf { it > 0 },
            complete = completeCount.takeIf { row.card.evidence.isNotEmpty() },
            synced = row.card.evidence.count { it.synced }.takeIf { row.card.evidence.isNotEmpty() },
            closeUnresolved = selectedFlightId?.let {
                session.latestCloseout(it)?.outcome == CloseoutRecord.UNRESOLVED
            } == true,
        )
        buildFilmstrip(row)
    }

    /** A blank video-colored box is not an empty, loading or error state. */
    private fun setEvidenceViewerMessage(message: String?, recovering: Boolean = true) {
        workspace.bindEvidenceAvailability(message == null && ivEvidence.drawable != null)
        findViewById<View>(R.id.evidenceViewerEmpty).visibility =
            if (message == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvEvidenceEmpty).apply {
            val status = when (message) {
                getString(R.string.review_observation_unavailable) -> "관측 시점 없음"
                getString(R.string.review_frame_unavailable) -> "사진 없음"
                else -> message?.substringBefore('\n')
            }
            text = status?.let { "$it · 상세" }
            contentDescription = message?.let { "$it. 자료 상태 상세 열기." }
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isFocusable = message != null
            setOnClickListener(if (message == null) null else View.OnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("자료 상태")
                    .setMessage(message).setPositiveButton("닫기", null).show()
            })
        }
        findViewById<View>(R.id.btnReviewEmptyRecovery).visibility =
            if (recovering) View.VISIBLE else View.GONE
    }

    /**
     * Verified filmstrip: one 48dp entry per linked frame. ONLY genuinely
     * preserved frames are enabled — image media, cheap receipt complete,
     * hash-verified bytes present, and bytes that decode as an image.
     * Anything else stays disabled with its VISIBLE reason on the button
     * itself (never faked, never hidden). Verification runs off the main
     * thread; each result applies only while its selection is still
     * current. Non-images are supporting artifacts, noted, not previewed.
     */
    private fun buildFilmstrip(row: FlightHistoryModel.EventRow) {
        val flightId = selectedFlightId
        val eventId = row.card.eventId
        val mediaById: Map<String, String> = flightId?.let { fid ->
            runCatching {
                session.store.artifactsFor(fid).associate { artifact ->
                    artifact.get("artifact_id").asText() to
                        (artifact.get("media_type")?.asText() ?: "")
                }
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        if (row.card.evidence.isEmpty()) {
            filmstripList.addView(TextView(this).apply {
                text = "연결된 증거 없음"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_muted))
                textSize = 12f
            })
            return
        }
        val density = resources.displayMetrics.density
        var nonImageCount = 0
        var imageOrdinal = 0
        for (link in row.card.evidence) {
            val isImage = (mediaById[link.artifactId] ?: "").startsWith("image/")
            if (!isImage) {
                // Non-images are supporting artifacts, never preview
                // frames: no button, only the short note below.
                nonImageCount += 1
                continue
            }
            val ordinal = ++imageOrdinal
            if (!link.complete) {
                filmstripList.addView(
                    filmstripButton(
                        link,
                        FilmstripAvailability.classify(
                            isImage = true,
                            complete = false,
                            bytesPresent = false,
                            decodable = false,
                        ),
                        enabled = false,
                        ordinal = ordinal,
                    ),
                )
                continue
            }
            // Checking state first; the verified result replaces it in
            // place — only while this selection is still current.
            val pending = filmstripButton(
                link,
                FilmstripAvailability.Availability(
                    FilmstripAvailability.Status.MISSING,
                    getString(R.string.review_filmstrip_checking),
                    "",
                ),
                enabled = false,
                ordinal = ordinal,
            )
            filmstripList.addView(pending)
            val index = filmstripList.indexOfChild(pending)
            session.bg.execute {
                val availability = verifyFrame(link.artifactId)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // Stale verification: the selection moved on — drop, so
                    // a late result never enables a new selection's button.
                    if (selectedFlightId != flightId ||
                        selectedEventRows.getOrNull(selectedEventIndex)?.card?.eventId != eventId
                    ) {
                        return@runOnUiThread
                    }
                    val replacement = filmstripButton(
                        link,
                        availability,
                        enabled = availability.status == FilmstripAvailability.Status.READY,
                        ordinal = ordinal,
                    ) { viewEvidenceFor(link.artifactId, eventId, localOnly = true) }
                    if (index in 0 until filmstripList.childCount) {
                        filmstripList.removeViewAt(index)
                        filmstripList.addView(replacement, index)
                    }
                    // Auto-load the first genuinely available frame for the
                    // current selection (read-only): verification already
                    // proved it — no stale frame, no arbitrary pick.
                    if (availability.status == FilmstripAvailability.Status.READY &&
                        viewerChosenArtifact == null &&
                        selectedFlightId == flightId &&
                        selectedEventRows.getOrNull(selectedEventIndex)?.card?.eventId == eventId
                    ) {
                        viewerChosenArtifact = link.artifactId
                        viewEvidenceFor(link.artifactId, eventId, localOnly = true)
                    }
                }
            }
        }
        if (nonImageCount > 0) {
            filmstripList.addView(TextView(this).apply {
                text = getString(R.string.review_non_image_note, nonImageCount)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_muted))
                textSize = 12f
            })
        }
    }

    private fun filmstripButton(
        link: ObservationModel.EvidenceLink,
        availability: FilmstripAvailability.Availability,
        enabled: Boolean,
        ordinal: Int,
        onTap: (() -> Unit)? = null,
    ): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = "$ordinal · ${availability.visibleLabel}"
            tag = link.artifactId
            isSelected = link.artifactId == viewerChosenArtifact
            setBackgroundResource(R.drawable.candidate_row_selector)
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = (48 * density + 0.5f).toInt()
            minimumHeight = (48 * density + 0.5f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = (4 * density + 0.5f).toInt()
            }
            isEnabled = enabled
            contentDescription = buildString {
                append("증거 ${link.artifactId.take(8)} · ${availability.visibleLabel}.")
                if (availability.reason.isNotEmpty()) append(" ${availability.reason}.")
            }.toString()
            if (enabled && onTap != null) setOnClickListener { onTap() }
        }
    }

    /**
     * Real preservation check (background only, never main-thread I/O):
     * hash-verified bytes via the store plus an actual bounded decode —
     * a header-only/truncated file that merely reports dimensions never
     * reads READY. The verification bitmap is recycled, never kept.
     * A receipt-complete link whose bytes fail verification reads CORRUPT,
     * never "보존됨".
     */
    private fun verifyFrame(artifactId: String): FilmstripAvailability.Availability {
        val bytes = try {
            session.store.artifactBytes(artifactId)
        } catch (_: Throwable) {
            null
        } ?: return FilmstripAvailability.Availability(
            FilmstripAvailability.Status.CORRUPT,
            getString(R.string.review_filmstrip_corrupt),
            "수신 기록 있으나 바이트 검증 실패",
        )
        // Genuine decodability: run the same bounded decode the viewer
        // uses (not a bounds-only probe), then recycle the probe bitmap.
        val decodable = try {
            val probe = decodeSampled(bytes)
            val ok = probe != null
            probe?.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
        if (!decodable) {
            return FilmstripAvailability.Availability(
                FilmstripAvailability.Status.CORRUPT,
                getString(R.string.review_filmstrip_corrupt),
                "바이트 검증됨 · 이미지로 열리지 않음",
            )
        }
        return FilmstripAvailability.classify(
            isImage = true,
            complete = true,
            bytesPresent = true,
            decodable = true,
        )
    }

    /** Display-only Korean label for the internal internet vocabulary. */
    private fun internetLabel(): String = when (val status = internetStatus()) {
        "validated" -> "연결됨"
        "connected (unvalidated)" -> "미검증"
        "no internet" -> "인터넷 없음"
        "none" -> "인터넷 없음"
        else -> "알 수 없음($status)"
    }

    private fun internetStatus(): String {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return "unknown"
        val network = manager.activeNetwork ?: return "none"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
        val internet = capabilities.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
        )
        val validated = capabilities.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        )
        return when {
            validated -> "validated"
            internet -> "connected (unvalidated)"
            else -> "no internet"
        }
    }

    private fun syncSites() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        setMissionSyncResult(R.string.mission_sync_result_unknown)
        val sync = session.missionSync() ?: run {
            show("먼저 Core URL과 토큰을 설정하세요")
            return
        }
        tvSync.text = "현장 동기화 중…"
        session.bg.execute {
            try {
                sync.syncSites()
                // Deferred registration rides the same trigger: the
                // site-first seam resolves pending drafts (additive-only,
                // journal-owned) and surfaces the honest resolution per
                // draft (created vs name-matched link vs wait reason) —
                // the same call the worker makes before every flight
                // upload, so manual and automatic paths share one order.
                // Selection follows a resolved draft to its canonical id;
                // still-pending ids stay selected.
                val pendingReport = session.syncPendingSitesNow()
                val local = session.store.sites()
                val previous = selectedSiteId
                val followed = previous?.let { session.pendingSites.canonicalIdFor(it) }
                val stillPending = previous?.let { session.pendingSites.statusFor(it) }
                    ?.takeIf { !it.isSynced }?.draft?.clientSiteId
                selectedSiteId = followed?.takeIf { id ->
                    local.any { it.get("site_id").asText() == id }
                } ?: stillPending ?: previous?.takeIf { id ->
                    local.any { it.get("site_id").asText() == id }
                } ?: local.firstOrNull()?.get("site_id")?.asText()
                runOnUiThread {
                    hasSyncReport = true
                    setMissionSyncResult(R.string.mission_sync_result_success_local)
                    tvSync.text = buildString {
                        append("성공(로컬 반영): 현장 ${local.size}건 · 서버 상태 별도")
                        pendingReport?.let { report ->
                            val notes = PendingSiteMissionFlow.resolutionLines(report) { id ->
                                session.pendingSites.statusFor(id)?.draft?.name
                            }
                            if (notes.isNotEmpty()) {
                                append("\n")
                                append(notes.joinToString("\n"))
                            }
                            PendingSiteMissionFlow.pendingCountSuffix(
                                session.pendingSites.pendingDrafts().size,
                            )?.let { suffix ->
                                append("\n중앙 등록 $suffix")
                            }
                        }
                    }
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread {
                    setMissionSyncResult(R.string.mission_sync_result_failure)
                    show("현장 동기화 실패: ${exc.message}")
                }
            }
        }
    }

    private fun syncBundleLatest() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        setMissionSyncResult(R.string.mission_sync_result_unknown)
        val sync = session.missionSync() ?: run {
            show("먼저 Core URL과 토큰을 설정하세요")
            return
        }
        val siteId = selectedSiteId ?: run {
            show("먼저 현장을 동기화하세요")
            return
        }
        // A pending draft has no server bundles yet: say the wait plainly
        // instead of failing a server lookup against a client UUID.
        session.pendingSites.statusFor(siteId)?.takeIf { !it.isSynced }?.let {
            show("중앙 등록 대기 중 · 번들 내려받기는 중앙 등록(현장 동기화) 후 가능합니다")
            return
        }
        tvSync.text = "현장 데이터 동기화 중…"
        session.bg.execute {
            try {
                val newZoneRevisions = sync.syncZoneRevisions(siteId)
                val serverBundles = sync.listSiteBundles(siteId)
                val target = serverBundles.lastOrNull()
                    ?: throw IllegalStateException("이 현장의 번들이 서버에 없습니다")
                val bundle = sync.syncBundle(target.get("bundle_id").asText())
                selectedBundleId = bundle.get("bundle_id").asText()
                val localCount = sync.localSiteBundles(siteId).size
                runOnUiThread {
                    hasSyncReport = true
                    setMissionSyncResult(R.string.mission_sync_result_success_local)
                    tvSync.text = "성공(로컬 반영): 구역 리비전 +${newZoneRevisions.size}, " +
                        "로컬 번들=$localCount, 선택=" +
                        "${selectedBundleId!!.take(8)} (digest 검증됨) · 서버 상태 별도"
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread {
                    setMissionSyncResult(R.string.mission_sync_result_failure)
                    show("번들 동기화 실패: ${exc.message}")
                }
            }
        }
    }

    /**
     * LOCAL bundle composition (product gap G11): builds a SHADOW-rule
     * bundle from the RC's OWN stored zone revisions plus the stored
     * mission snapshot basis, saves it locally, and selects it — the
     * operator then runs the existing 비행 시작 -> 준비 -> 적용 path.
     * The EXISTING empty demo bundle stays as the SIMULATED bench path;
     * this LOCAL path is explicit and separate. No map editor is
     * fabricated: zone polygons stay WGS84 GeoJSON from the stored
     * revisions (the map surface is a separate unresolved item).
     */
    private fun composeLocalBundle() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val siteId = selectedSiteId ?: run {
            show("먼저 현장을 동기화하세요")
            return
        }
        if (LocalBundleComposer.baseSnapshotFor(session.store, siteId) == null) {
            show("로컬 구성 불가: 저장된 미션 스냅샷 없음(먼저 동기화하세요)")
            return
        }
        try {
            val result = session.composeLocalBundle(siteId, ReceiptClock.now())
            selectedBundleId = result.bundle.get("bundle_id").asText()
            lastLocalBundle = result
            val shadowOnly = result.bundle.get("risk_policy")?.get("rules")
                ?.all { it.get("mode")?.asText() == "SHADOW" } != false
            show(
                "로컬 번들 구성: 구역 리비전 ${result.zoneRevisionIds.size}개·" +
                    "위험 규칙 ${result.ruleCount}건(SHADOW)" +
                    if (result.excluded.isNotEmpty()) "·제외 ${result.excluded.size}건" else "" +
                    if (!shadowOnly) " · 경고: 비-SHADOW 규칙 포함" else "",
            )
        } catch (exc: Exception) {
            show("로컬 구성 실패: ${exc.message}")
        }
    }

    /**
     * Offline-first site registration dialog (현장 추가): name + an
     * optional operator-confirmed location, stored durably on this device
     * with the honest pending badge — no network, no Core needed. Location
     * confirm/clear is explicit and all-or-nothing (per
     * [PendingSiteRegistration.PendingSiteForm]). Refused with the storage
     * banner when the journal is unavailable (fail-closed: no silent
     * memory-only drafts). The new draft is selected immediately; central
     * registration happens on the next 현장 동기화.
     */
    private fun showPendingSiteDialog(
        initial: PendingSiteRegistration.PendingSiteForm =
            PendingSiteRegistration.PendingSiteForm(name = ""),
    ) {
        PendingSiteMissionFlow.creationRefusal(session.storageAvailable)?.let { refusal ->
            show(refusal)
            return
        }
        val density = resources.displayMetrics.density
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
        }
        val nameInput = field("현장 이름 (필수)", initial.name)
        val latInput = field("위도 -90~90 (비우면 위치 없음)", initial.centerLat?.toString() ?: "")
        val lonInput = field("경도 -180~180 (비우면 위치 없음)", initial.centerLon?.toString() ?: "")
        val radiusInput = field("반경 m (비우면 위치 없음)", initial.radiusM?.toString() ?: "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density + 0.5f).toInt()
            setPadding(pad, (8 * density + 0.5f).toInt(), pad, 0)
            addView(nameInput)
            addView(latInput)
            addView(lonInput)
            addView(radiusInput)
        }
        AlertDialog.Builder(this)
            .setTitle(PendingSiteRegistration.DIALOG_TITLE)
            .setMessage("이름은 필수 · 위치는 위도·경도·반경을 모두 입력하거나 모두 비우세요")
            .setView(box)
            .setNeutralButton("위치 지우기") { _, _ ->
                showPendingSiteDialog(
                    readPendingForm(nameInput, latInput, lonInput, radiusInput).cleared(),
                )
            }
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val form = readPendingForm(nameInput, latInput, lonInput, radiusInput)
                val problems = form.problems()
                if (problems.isNotEmpty()) {
                    show(problems.joinToString(" · "))
                    showPendingSiteDialog(form)
                    return@setPositiveButton
                }
                try {
                    val draft = session.createPendingSite(
                        form.name, form.centerLat, form.centerLon, form.radiusM,
                    )
                    selectedSiteId = draft.clientSiteId
                    expandedSiteId = draft.clientSiteId
                    selectedBundleId = null
                    val countLine = PendingSiteMissionFlow.pendingCountSuffix(
                        session.pendingSites.pendingDrafts().size,
                    )?.let { " · $it" } ?: ""
                    show("${PendingSiteRegistration.PENDING_BADGE}$countLine")
                } catch (exc: Exception) {
                    show("현장 저장 실패: ${exc.message}")
                }
                session.syncFlightState()
                refresh()
            }
            .show()
    }

    /** Reads the dialog inputs: blank location fields stay null (no location),
     *  non-numeric input maps to NaN so the form reports a range problem. */
    private fun readPendingForm(
        name: EditText,
        lat: EditText,
        lon: EditText,
        radius: EditText,
    ): PendingSiteRegistration.PendingSiteForm {
        fun number(raw: String): Double? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return trimmed.toDoubleOrNull() ?: Double.NaN
        }
        return PendingSiteRegistration.PendingSiteForm(
            name = name.text.toString(),
            centerLat = number(lat.text.toString()),
            centerLon = number(lon.text.toString()),
            radiusM = number(radius.text.toString()),
        )
    }

    /**
     * 준비 (adopted order 준비 → 적용 → 기록 시작): attaches the mission
     * controller for the selected digest-verified bundle WITHOUT creating
     * a flight record or claiming recording. A second tap on the same
     * bundle reuses the preparation; preparing a different bundle is
     * refused until the current one is abandoned (no silent swap).
     */
    private fun prepareMission() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        if (session.activeFlight != null) {
            show("기록 세션 중 · 준비는 새 기록 시작 전에만 가능합니다")
            return
        }
        val bundleId = selectedBundleId ?: run {
            show("먼저 미션 번들을 선택하세요 (현장 동기화 → 번들 내려받기)")
            return
        }
        val bundle = session.store.missionBundle(bundleId) ?: run {
            show("선택한 번들이 로컬 저장소에 없습니다")
            return
        }
        try {
            session.prepareBundle(bundle)
            session.controller?.prepare()
            // Queueing done — the blocking drain goes background; the
            // confirmation posts back on UI (see run()).
            session.pumpOutboundAsync { result ->
                runOnUiThread {
                    result.fold(
                        // The phase panel reads actual outbox/response facts.
                        // Link state and a total envelope count cannot prove
                        // that THIS request was sent or prepared.
                        onSuccess = { refresh() },
                        onFailure = { exc ->
                            Log.e(TAG, "prepare pump failed", exc)
                            show("준비 요청 전송 오류: ${refusalReason(exc)} · 준비·적용 상태에서 대기열과 응답을 확인하세요")
                        },
                    )
                }
            }
        } catch (exc: Exception) {
            Log.e(TAG, "prepare refused", exc)
            show("준비 거부: ${refusalReason(exc)}")
        }
        session.syncFlightState()
        refresh()
    }

    private fun startFlight() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        if (session.activeFlight != null) {
            show("이미 진행 중인 비행이 있습니다 — 먼저 종료하세요")
            return
        }
        val bundleId = selectedBundleId ?: run {
            show("먼저 미션 번들을 내려받으세요 (현장 동기화 → 번들 내려받기)")
            return
        }
        val bundle = session.store.missionBundle(bundleId) ?: run {
            show("선택한 번들이 로컬 저장소에 없습니다")
            return
        }
        if (session.controller != null && !selectedPlanMatchesController()) {
            show("선택 계획과 준비 중인 계획이 다릅니다. 준비 중인 계획을 먼저 확인하세요.")
            return
        }
        try {
            // Adopted order 준비 → 적용 → 기록 시작: a bundle prepared
            // earlier reuses its provisional id as the flight record's id
            // (one UUID end-to-end, controller stays bound to its APPLIED
            // progress). Otherwise the legacy record-first path runs.
            val preparedId = session.pendingFlightId
            val preparedBundleId = session.bundle?.get("bundle_id")?.asText()
            val record = if (preparedId != null && preparedBundleId == bundleId) {
                session.startRecordingFromPrepared(bundle)
            } else {
                session.startFlightFromBundle(bundle)
            }
            selectedFlightId = record.get("flight_id").asText()
            val segmentSeconds = bundle.get("mission_snapshot")
                ?.get("recording_policy")?.get("segment_seconds")?.asInt() ?: 300
            app.startFlightRecording(record.get("flight_id").asText(), segmentSeconds)
            show(
                "KSI 로컬 기록 시작: ${record.get("flight_id").asText().take(8)} · " +
                "번들 ${bundleId.take(8)} 고정 · ${segmentSeconds}초 녹화 구간 · " +
                if (app.videoRecorder == null) "벤치 빌드 · 실제 녹화 없음" else "기록 세션 활성",
            )
        } catch (exc: Exception) {
            Log.e(TAG, "start refused", exc)
            show("시작 실패: ${refusalReason(exc)}")
        }
    }

    private fun confirmVideoDisconnectInput() {
        val disconnect = {
            app.videoSource.disconnect()
            refresh()
        }
        if (session.activeFlight == null) {
            disconnect()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.video_disconnect_confirm_title)
            .setMessage(R.string.video_disconnect_confirm_message)
            .setNegativeButton("취소", null)
            .setPositiveButton(R.string.btn_video_disconnect) { _, _ -> disconnect() }
            .show()
    }

    private fun confirmEndFlight() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        if (session.activeFlight == null) {
            show("종료할 KSI 로컬 기록 세션이 없습니다")
            return
        }
        val endButton = findViewById<Button>(R.id.btnEndFlight)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("KSI 기록 세션 종료")
            .setMessage(
                getString(R.string.cockpit_end_record_confirm_message) + "\n" +
                    getString(R.string.close_scope_note),
            )
            .setNegativeButton("계속 기록", null)
            .setNeutralButton("RC 기록만 마감 · Manifold 중지 미확인") { _, _ ->
                finishFlight(FlightCloseCoordinator.Mode.RECORDING_ONLY)
            }
            .setPositiveButton("관측 중지 후 마감") { _, _ ->
                finishFlight(FlightCloseCoordinator.Mode.FULL)
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (session.activeFlight != null && endButton.isShown && endButton.isEnabled) {
                endButton.requestFocus()
            }
        }
        dialog.show()
    }

    /**
     * STOPPED-aware close sequence (contract §6 closeout): the production
     * [FlightCloseCoordinator] drives the order — STOP request, bounded
     * reconciliation, recorder finalization, per-boot ingest, close-out
     * persist, record end, and only then the upload enqueue. A clean close
     * (record end, enqueue, new-session unlock) happens ONLY after an
     * Edge-confirmed STOPPED; otherwise the RC recording is finalized but
     * the flight stays open (blocked) with the unresolved reason durably
     * recorded, until a later STOPPED completes it. Nothing here blocks the
     * controls, deletes local bytes, or invents a clean close.
     */
    private fun finishFlight(mode: FlightCloseCoordinator.Mode) {
        val active = session.activeFlight
        if (active == null) {
            show("종료할 관측 기록이 없습니다")
            return
        }
        val flightId = active.get("flight_id").asText()
        selectedFlightId = flightId
        selectedEventIndex = 0
        viewerSession.select()
        showPage(R.id.btnNavReview)
        workspace.showRecordIndex()
        recordDetailRequestedId = flightId
        val syncBefore = tvSync.text
        if (mode == FlightCloseCoordinator.Mode.FULL) {
            tvSync.text = "관측 중지 확인 중…(Edge STOPPED 대기)"
        }
        show("KSI 기록 ${flightId.take(8)} 종료 처리 중…")
        refreshHistory()
        refreshEvidence()
        session.bg.execute {
            val outcome = try {
                FlightCloseCoordinator().close(mode, closeSteps(flightId))
            } catch (exc: Exception) {
                FlightCloseCoordinator.Outcome.Failed("마감 실패(로컬 보존, 재시도 가능): ${exc.message}")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tvSync.text = syncBefore
                when (outcome) {
                    is FlightCloseCoordinator.Outcome.CleanClosed ->
                        if (outcome.note.startsWith("마감 CLEAN")) {
                            showBanner(outcome.note, info = true)
                        } else {
                            show("KSI 기록 ${flightId.take(8)} 종료 · ${outcome.note}")
                        }
                    is FlightCloseCoordinator.Outcome.RecordingFinalizedUnresolved ->
                        show(
                            "KSI 기록 ${flightId.take(8)} · RC 기록 마감 · " +
                                "Edge 세션 미확인 유지(새 비행 잠김) · ${outcome.reason}",
                        )
                    is FlightCloseCoordinator.Outcome.Failed ->
                        show("${outcome.reason} · 관측 기록 유지됨(재시도 가능)")
                }
                refreshHistory()
                refreshEvidence()
            }
        }
    }

    /**
     * Known recorder failure for [flightId]'s capture: the sticky recorder
     * flag sampled now versus the anchor at recording start (only failures
     * first observed during THIS flight count — a sticky flag predating
     * the flight belongs to the earlier closeout), or the durable closeout
     * failure already persisted. Null when neither measured — never
     * guessed false.
     */
    private fun currentVideoFailure(flightId: String?): Boolean? {
        val closeoutFailed = flightId?.let { session.latestCloseout(it)?.videoCaptureFailed }
        if (closeoutFailed == true) return true
        val recorder = app.videoRecorder ?: return closeoutFailed
        val atStart = flightId?.let { session.recordingFailedAtStart(it) } ?: return closeoutFailed
        return recorder.hasWriteFailure == true && atStart == false
    }

    /** Production close steps: real session/app calls (tests use fakes). */
    private fun closeSteps(flightId: String): FlightCloseCoordinator.Steps =
        object : FlightCloseCoordinator.Steps {
            override fun requestStop() = session.requestEdgeStop()

            override fun reconcileOnce() = session.reconcileOnce()

            override fun finalizeRecorder(): List<java.io.File> = app.stopFlightRecording()

            override fun recorderWriteFailed(): Boolean? = currentVideoFailure(flightId)

            override fun ingest(files: List<java.io.File>) =
                session.ingestFinalizedSegments(flightId, files).let {
                    FlightCloseCoordinator.IngestSummary(it.ingested, it.total, it.problems)
                }

            override fun persistUnresolved(
                reason: String,
                ingest: FlightCloseCoordinator.IngestSummary,
                videoFailed: Boolean?,
            ): String {
                val record = session.persistUnresolvedCloseout(
                    flightId, reason,
                    RcSession.SegmentIngest(ingest.ingested, ingest.total, ingest.problems),
                    videoFailed,
                )
                return "미확인 종료 기록됨 · 사유: ${record.stopReason} · " +
                    "세그먼트 ${record.segmentsIngested}/${record.segmentsTotal}" +
                    if (record.segmentsUnverified) "(연계 미검증·로컬 보관)" else "" +
                    videoNote(record.videoCaptureFailed) +
                    " · 조종사 의견 ${record.opinionCount}건 · 판정 ${record.verdictResult ?: "미기록"}"
            }

            override fun finalizeAndEnd(
                ingest: FlightCloseCoordinator.IngestSummary,
                videoFailed: Boolean?,
            ): FlightCloseCoordinator.FinalizeResult? {
                val summary = session.finalizeAndEndFlight(
                    RcSession.SegmentIngest(ingest.ingested, ingest.total, ingest.problems),
                    videoFailed,
                ) ?: return null
                val headline = "마감 ${summary.closeout.outcome} · KSI 기록 ${flightId.take(8)} 종료"
                val base = if (summary.closeout.outcome == CloseoutRecord.DRAFT) {
                    "시작 기록 없음 · 드래프트 마감(기록 보존) · 조종사 의견 ${summary.opinions}건"
                } else {
                    "세션 판정 ${summary.verdict ?: "미기록"}(근거 저장됨) · " +
                        "관측 중지 확인됨 · " +
                        "번들 스냅샷 ${summary.bundleArtifacts}부트 반영 · " +
                        "조종사 의견 ${summary.opinions}건"
                }
                return FlightCloseCoordinator.FinalizeResult(
                    note = headline + "\n" + base +
                        videoNote(summary.closeout.videoCaptureFailed),
                    outcome = summary.closeout.outcome,
                )
            }

            override fun closeBlockedReason(): String? =
                session.closeBlockedReason(flightId)

            override fun enqueueUpload() {
                session.repairCloseoutArtifact(flightId)
                if (session.coreConfigured()) {
                    SyncScheduler.enqueue(applicationContext, flightId)
                }
            }
        }

    /** Clean-video claim guard: a known capture failure is always stated. */
    private fun videoNote(videoFailed: Boolean?): String = when (videoFailed) {
        true -> " · 영상 기록 불완전(기록 실패 확인 — 안전 판단 아님)"
        else -> ""
    }

    /**
     * Completes a previously unresolved close — or a standing start intent
     * without any close-out (e.g. a crash before close) — once the Edge
     * confirms STOPPED (the ticker calls this; the flight stayed open and
     * blocked until now). Idempotent: a concurrent completion ends it once.
     */
    private fun maybeCompletePendingClose() {
        val active = session.activeFlight ?: return
        val flightId = active.get("flight_id").asText()
        val closeout = session.latestCloseout(flightId)
        val unresolved = closeout?.outcome == CloseoutRecord.UNRESOLVED
        if (!unresolved && !session.hasOpenStartIntent(flightId)) return
        if (session.controller?.state != MissionState.STOPPED) return
        if (session.controller?.boundRuntimeStatus?.state != ksi.rc.EdgeRuntimeState.STOPPED) return
        session.bg.execute {
            // The recorder was already finalized at unresolved-close time:
            // carry its segment facts and capture-failure flag forward
            // (nothing re-ingested here) — completing only ends the record
            // (idempotent) and enqueues.
            val prior = session.latestCloseout(flightId)
            val summary = session.finalizeAndEndFlight(
                RcSession.SegmentIngest(
                    prior?.segmentsIngested ?: 0,
                    prior?.segmentsTotal ?: 0,
                    emptyList(),
                ),
                prior?.videoCaptureFailed,
            ) ?: return@execute
            session.repairCloseoutArtifact(flightId)
            if (session.coreConfigured()) SyncScheduler.enqueue(applicationContext, flightId)
            runOnUiThread {
                if (!isDestroyed) {
                    show(
                        "마감 완료 · KSI 기록 ${flightId.take(8)} 종료\n" +
                            "세션 판정 ${summary.verdict ?: "미기록"}",
                    )
                    refreshHistory()
                    refreshEvidence()
                }
            }
        }
    }


    /** The flight's pinned bundle carries recording_policy.segment_seconds. */
    private fun flightSegmentSeconds(flightId: String): Int {
        val record = session.store.flight(flightId) ?: return 300
        val bundleId = record.get("bundle_id")?.asText() ?: return 300
        val bundle = session.store.missionBundle(bundleId) ?: return 300
        return bundle.get("mission_snapshot")
            ?.get("recording_policy")?.get("segment_seconds")?.asInt() ?: 300
    }

    private fun uploadNow(requestedFlightId: String? = null) {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val uploader = session.uploader()
        if (uploader == null) {
            show("동기화 불가: 설정에서 Core URL과 토큰을 확인하세요")
            return
        }
        val flightId = requestedFlightId
            ?: session.activeFlight?.get("flight_id")?.asText()
            ?: FlightHistoryModel.flights(session.store).firstOrNull()?.flightId
        if (flightId == null) {
            show("동기화할 로컬 관측 기록이 없습니다")
            return
        }
        val spec = try {
            session.uploadSpec(flightId)
        } catch (exc: Exception) {
            Log.e(TAG, "upload refused", exc)
            show("업로드 거부: ${refusalReason(exc)}")
            return
        }
        tvSync.text = "비행 ${flightId.take(8)} · Core 동기화 요청 중…\n" +
            "완료 여부는 서버 커밋 응답으로 확인합니다."
        session.bg.execute {
            try {
                // Site-first ordering (same seam as the worker): resolve
                // pending drafts before building the spec. A durable-write
                // failure is logged, not fatal — the spec gate below still
                // refuses unresolved pending sites with the honest reason.
                try {
                    session.syncPendingSitesNow()
                } catch (exc: Exception) {
                    Log.w("KsiUpload", "pending site sync deferred: ${exc.message}")
                }
                // Repair a missing combined close-out artifact first (older
                // records, or a boot that only appeared later): best effort,
                // never blocks the upload and never claims the close-out.
                session.repairCloseoutArtifact(flightId)
                val report = uploader.uploadFlight(spec)
                val lines = StringBuilder()
                val uploadState = when (report.uploadState) {
                    "COMMITTED" -> "서버 커밋 확인"
                    "PARTIAL" -> "일부 자료 미완료"
                    else -> "서버 응답 ${report.uploadState}"
                }
                lines.append("비행 ${flightId.take(8)} · $uploadState\n")
                // Round68: 원시 코드(RC_RECEIVE_APPROX·COMMITTED)는 짧은 한국어
                // 상태로 — 원문은 로그·서버 응답에 그대로 남는다.
                lines.append("시각 기준 ${UserFacingCopy.timeBasisLabel(report.timeBasis)} · 임무 적용 ${report.applicationsUploaded} · " +
                    "세션 ${report.sessionsUploaded} · 의견 ${report.annotationsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("수집 세션 ${boot.bootId.take(8)} · ${UserFacingCopy.bootStatusLabel(boot.status)} · " +
                        "전송 ${boot.chunksSent} · 누락 ${boot.missingArtifacts.size} · " +
                        "커밋 확인 ${boot.synced.size}\n")
                }
                if (report.sessionDrift.isNotEmpty()) {
                    lines.append("서버와 기존 세션 자료 차이 ${report.sessionDrift.size}건 · 상세 확인 필요\n")
                }
                if (report.boots.any { it.incompleteLocal.isNotEmpty() }) {
                    lines.append("로컬 미완료 증거가 있습니다 · 완성 후 재시도됩니다\n")
                }
                if (report.annotationFailures.isNotEmpty()) {
                    lines.append("의견 기록 ${report.annotationFailures.size}건 실패 · 재시도 예정: " +
                        report.annotationFailures.joinToString().take(120) + "\n")
                }
                // Structured operator responses (Core POST /operator-responses):
                // a preservation ACK is storage only — it never marks a field
                // request SENT, so pending responses stay visible + retrying.
                if (report.responsesUploaded > 0 || report.responsesPending > 0) {
                    lines.append("구조화 응답 · 전송 ${report.responsesUploaded} · 대기 ${report.responsesPending}\n")
                }
                if (report.responseFailures.isNotEmpty()) {
                    lines.append("구조화 응답 ${report.responseFailures.size}건 실패 · 재시도 예정: " +
                        report.responseFailures.joinToString().take(120) + "\n")
                }
                if (report.responsesPending > 0) {
                    SyncScheduler.enqueue(applicationContext, flightId)
                    lines.append("구조화 응답 대기 — 자동 재시도 예약(보존 ACK는 현장 전달 확인 아님)\n")
                }
                runOnUiThread {
                    hasSyncReport = true
                    tvSync.text = lines.toString()
                    refreshEvidence()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("업로드 실패: ${exc.message}") }
            }
        }
    }

    private fun serverState() {
        if (!session.storageAvailable) {
            show(StorageHealth.UNAVAILABLE_BANNER)
            return
        }
        val query = session.query()
        if (query == null) {
            show("Core 확인 불가: 설정에서 URL과 토큰을 확인하세요")
            return
        }
        val flightId = session.activeFlight?.get("flight_id")?.asText()
            ?: selectedFlightId
            ?: FlightHistoryModel.flights(session.store).firstOrNull()?.flightId
        if (flightId == null) {
            show("서버에서 확인할 로컬 관측 기록이 없습니다")
            return
        }
        tvSync.text = "비행 ${flightId.take(8)} · Core 응답 확인 중…"
        session.bg.execute {
            try {
                val api = ksi.rc.CoreApi(
                    AndroidCoreTransport(app.config.coreUrl, app.config.coreToken),
                )
                val view = api.getFlight(flightId)
                val uploadState = view.get("upload_state")?.asText() ?: "UNKNOWN"
                val serverEvents = query.events(flightId)
                // D5: 서버가 아는 전체 관측 세션 수. 유실 여부를 대조하려면
                // "서버가 몇 개를 아는지"가 함께 필요하다.
                val serverFlights = api.listFlights()
                val serverCount = serverFlights.get("items")?.size()
                // 원본 근거의 중앙 보관 — Core가 발급한 컬렉션 영수증만으로
                // 말한다. 조회 실패는 "없다"가 아니라 "확인 안 함"이다.
                val collections = api.listCollections(flightId)
                val collectionNode = collections.get("items")
                val collectionItems: List<com.fasterxml.jackson.databind.JsonNode> =
                    if (collectionNode == null || collectionNode.isNull) {
                        emptyList()
                    } else {
                        collectionNode.elements().asSequence().toList()
                    }
                val sourceState = ReviewReadinessModel.SourceEvidence(
                    state = ReviewReadinessModel.SourceStorage.STORED,
                    collectionCount = collectionItems.size,
                    assetCount = collectionItems.sumOf { item ->
                        item.get("required_artifact_count")?.asInt() ?: 0
                    },
                )
                runOnUiThread {
                    hasSyncReport = true
                    val stateLabel = when (uploadState) {
                        "COMMITTED" -> "서버 커밋 확인"
                        "PARTIAL" -> "서버 일부 자료 미완료"
                        else -> "서버 상태 $uploadState"
                    }
                    tvSync.text = "Core 응답 · 비행 ${flightId.take(8)} · $stateLabel\n" +
                        "사건 응답 ${serverEvents.items.size}건 · " +
                        if (serverEvents.source == EvidenceSource.LOCAL) {
                            "기기 로컬 사본(서버 사건 응답 아님)"
                        } else {
                            "Core에서 조회"
                        }
                    // D5: 서버가 이 비행을 알고 있다는 사실이 확인되었으므로
                    // 이제 "유실 여부를 아직 모른다"를 내려고 관측 기록 동기화
                    // 상태를 갱신한다(확인된 세션 수 = 조회한 1건).
                    serverSessionCount = serverCount
                    sourceEvidence = sourceState
                    refreshHistory()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("서버 조회 실패: ${exc.message}") }
            }
        }
    }

    /**
     * Requests only the dangerous permissions THIS package declares (the DJI
     * MSDK manifests add location/phone/storage in the device flavor; bench
     * declares none of them, so nothing is asked there). A refusal is shown,
     * never hidden — the rest of the app keeps working.
     */
    private fun requestMissingDangerousPermissions() {
        val declared = try {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS,
            ).requestedPermissions ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        val missing = declared.filter { permission ->
            try {
                val info = packageManager.getPermissionInfo(permission, 0)
                val protection = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.protection
                } else {
                    @Suppress("DEPRECATION")
                    (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)
                }
                protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
                    packageManager.checkPermission(permission, packageName) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * D2 — 위치 권한만 요청한다.
     *
     * [requestMissingDangerousPermissions]는 패키지가 선언한 위험 권한을 **전부**
     * 묻는데, DJI MSDK AAR이 전화·사진/미디어 권한까지 선언해 있어서
     * "이 위치로 현장 찾기"를 누르면 위치와 무관한 전화·저장소 대화까지
     * 뜨게 된다(실기기에서 확인). 현장 추천 버튼은 **위치만** 요청해야 한다.
     */
    private fun requestLocationPermission() {
        val wanted = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter { permission ->
            try {
                packageManager.checkPermission(permission, packageName) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    /** Installed app version (BuildConfig generation is off in this module). */
    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "알 수 없음"
    } catch (_: Exception) {
        "알 수 없음"
    }

    /** Registered operator state line (settings). */
    private fun refreshOperatorState() {
        val org = app.config.operatorOrganization
        tvOperator.text = if (app.config.operatorRegistered) {
            "등록: ${app.config.operatorName}" + if (org.isNotEmpty()) " ($org)" else ""
        } else {
            "미등록 — 비행 후 오탐 표시/해제에 필요합니다"
        }
    }

    /** " (이름)" when the annotation's actor is the locally registered operator. */
    private fun localOperatorSuffix(actorId: String?): String =
        if (actorId != null && app.config.operatorRegistered && actorId == app.config.operatorId) {
            " (${app.config.operatorName})"
        } else {
            ""
        }

    private fun show(message: String, contentDescription: String? = null) {
        val problem = listOf("실패", "오류", "거부", "불가", "미승인", "미등록")
            .any { it in message }
        if (!problem) {
            tvError.visibility = View.GONE
            if (message.isNotBlank()) {
                // Feedback occupies its own flow row above the work surface;
                // it can never cover original/RC/Core custody or image tools.
                showBanner(message, info = true, contentDescription = contentDescription)
            }
            return
        }
        showBanner(message, info = false, contentDescription = contentDescription)
    }

    /**
     * Round68 copy-density: the close-out RESULT leads in its own banner.
     * A CLEAN close stays neutral (info) even when the subordinate session
     * verdict is FAILED or the video note mentions a recording failure —
     * the verdict/preservation axes are subordinate text, never the verdict
     * of the close itself. 0건≠안전: neutral is never green "safe".
     */
    private fun showBanner(message: String, info: Boolean, contentDescription: String? = null) {
        tvError.text = "$message  ·  눌러 닫기"
        tvError.visibility = View.VISIBLE
        tvError.contentDescription = contentDescription ?: "$message · 눌러 닫기"
        if (info) {
            tvError.setBackgroundColor(
                ContextCompat.getColor(this, R.color.ksi_surface),
            )
            tvError.setTextColor(
                ContextCompat.getColor(this, R.color.ksi_info),
            )
        } else {
            tvError.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    R.color.ksi_problem_bg,
                ),
            )
            tvError.setTextColor(
                ContextCompat.getColor(this, R.color.ksi_problem),
            )
        }
    }

    /**
     * Integrated ops deck wiring (observe screen): video/map view toggle,
     * operator commands through [AircraftCommandPort], and the AI panel.
     * Buttons stay enabled so a blocked command can always explain WHY
     * (gate refusal) instead of dying silently. All lookups go through
     * the guarded [on]/[buttonOrNull] path — a missing id logs and skips,
     * never crashes.
     */
    private fun bindOpsDeck() {
        tvCommandResult = findViewById(R.id.tvCommandResult)
        tvAiState = findViewById(R.id.tvAiState)
        tvAiCandidates = findViewById(R.id.tvAiCandidates)
        tvAiOverlayNote = findViewById(R.id.tvAiOverlayNote)
        tvAiMapNote = findViewById(R.id.tvAiMapNote)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        tvMapPlaceholder = findViewById(R.id.tvMapPlaceholder)
        // D7/round51: a tap on the corner map expands/collapses the map
        // (video keeps flowing underneath either way); the unavailable-
        // map explanation stays one long-press away. Routine SHADOW never
        // forces the expanded map.
        tvMapPlaceholder.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("지도 타일 미연결")
                .setMessage("실제 지도를 표시할 소스가 연결되지 않았습니다. 선택한 계획의 저장 좌표 구역은 임무에서 확인할 수 있습니다. 비행 경로·현재 위치와는 별도입니다.")
                .setPositiveButton("저장 구역 보기") { _, _ -> showPage(R.id.btnNavMission) }
                .setNegativeButton("닫기", null)
                .setOnDismissListener { tvMapPlaceholder.requestFocus() }
                .show()
        }
        tvMapPlaceholder.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_view_map)
                .setMessage(R.string.map_unavailable_detail)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { tvMapPlaceholder.requestFocus() }
                .show()
            true
        }
        tvSuperviseAi = findViewById(R.id.tvSuperviseAi)
        tvSuperviseCamera = findViewById(R.id.tvSuperviseCamera)
        tvSupervisePreserve = findViewById(R.id.tvSupervisePreserve)
        tvSuperviseAssess = findViewById(R.id.tvSuperviseAssess)
        tvSuperviseAction = findViewById(R.id.tvSuperviseAction)
        tvCompactAi = findViewById(R.id.tvCompactAi)
        tvCompactCamera = findViewById(R.id.tvCompactCamera)
        tvCompactPreserve = findViewById(R.id.tvCompactPreserve)
        tvCompactSite = findViewById(R.id.tvCompactSite)
        // Glance row is READ-ONLY (24dp visual summary, below the 48dp
        // touch floor): no click, no focus. The operable detail entry is
        // the 48dp bar button beside it. Same resolution as the web sibling.
        on(R.id.btnCandidatesToggle) {
            candidatesExpanded = !candidatesExpanded
            refreshOpsDeck()
        }
        tvEdgeRuntimeState = findViewById(R.id.tvEdgeRuntimeState)
        tvEdgeRuntimeZeroNote = findViewById(R.id.tvEdgeRuntimeZeroNote)
        tvEdgeRuntimeDetail = findViewById(R.id.tvEdgeRuntimeDetail)
        tvSessionResult = findViewById(R.id.tvSessionResult)
        tvCommitState = findViewById(R.id.tvCommitState)
        aiCandidateList = findViewById(R.id.aiCandidateList)
        responsePanel = findViewById(R.id.responsePanel)
        tvResponseCandidate = findViewById(R.id.tvResponseCandidate)
        etResponseNote = findViewById(R.id.etResponseNote)
        tvResponseResult = findViewById(R.id.tvResponseResult)
        on(R.id.btnViewVideo) { setObserveView(ObserveView.Mode.VIDEO) }
        // Contextual per-request entries (deck, not nav menu): same
        // gate-first dialogs, restored on dismiss.
        on(R.id.btnReqPhoto) { openAssist(R.id.photoGroup) }
        on(R.id.btnReqRec) { openAssist(R.id.recGroup) }
        // Zoom is the physical dial on the flight screen. No in-flight touch
        // popup; the read-only camera state lives in the bounded detail sheet.
        on(R.id.btnReqZoom) { }
        on(R.id.btnReqGimbal) { openAssist(R.id.gimbalGroup) }
        on(R.id.btnReqFlight) { openAssist(R.id.flightGroup) }
        on(R.id.btnViewMap) { setObserveView(ObserveView.Mode.MAP) }
        on(R.id.btnTakeoff) { sendCommand(AircraftCommandPort.Command.TAKEOFF) }
        on(R.id.btnLand) { sendCommand(AircraftCommandPort.Command.LAND) }
        on(R.id.btnRth) { sendCommand(AircraftCommandPort.Command.RETURN_HOME) }
        on(R.id.btnGimbalUp) {
            sendCommand(AircraftCommandPort.Command.GIMBAL_ROTATE, magnitude = 15f)
        }
        on(R.id.btnGimbalDown) {
            sendCommand(AircraftCommandPort.Command.GIMBAL_ROTATE, magnitude = -15f)
        }
        on(R.id.btnZoomIn) {
            sendCommand(AircraftCommandPort.Command.ZOOM_IN, magnitude = 1f)
        }
        on(R.id.btnZoomOut) {
            sendCommand(AircraftCommandPort.Command.ZOOM_OUT, magnitude = -1f)
        }
        on(R.id.btnPhoto) { sendCommand(AircraftCommandPort.Command.PHOTO) }
        on(R.id.btnRecStart) { sendCommand(AircraftCommandPort.Command.START_RECORDING) }
        on(R.id.btnRecStop) { sendCommand(AircraftCommandPort.Command.STOP_RECORDING) }
        tvCommandResult.setOnClickListener {
            // Keep the primary dock bounded even for long refusal/unknown
            // messages. Expanding this TextView to eight lines would again
            // consume the candidate scroll viewport at RC height.
            AlertDialog.Builder(this)
                .setTitle(R.string.cmd_result_accessibility)
                .setMessage(tvCommandResult.text)
                .setPositiveButton(R.string.btn_close_ops_panel, null)
                .setOnDismissListener { tvCommandResult.requestFocus() }
                .show()
        }
        on(R.id.btnRespondConfirm) { submitCandidateResponse(ObservationResponse.Action.CONFIRM) }
        on(R.id.btnRespondHold) { submitCandidateResponse(ObservationResponse.Action.HOLD) }
        on(R.id.btnRespondEscalate) { submitCandidateResponse(ObservationResponse.Action.ESCALATE_REQUEST) }
    }

    /**
     * Gate inputs from facts only: no authority report source is connected
     * on the RC today (the telemetry strip shows "미확인" for the same
     * reason), so the owner is honestly UNKNOWN until one exists. Nothing
     * is guessed — flight commands stay blocked, camera work stays
     * available while the aircraft is reachable.
     */
    private fun evaluateCommand(command: AircraftCommandPort.Command): CommandGate.Decision =
        AircraftCommandUi.evaluateCommand(
            command = command,
            aircraftConnected = runCatching { app.aircraftConnection.readAircraftConnected() }.getOrNull(),
            capabilities = app.aircraftCommand.capabilities,
        )

    /** Operator command: gate first (blocked = never sent + reason shown),
     *  then the port. The outcome text is about the REQUEST, never an
     *  execution claim. */
    private fun sendCommand(command: AircraftCommandPort.Command, magnitude: Float? = null) {
        val decision = evaluateCommand(command)
        if (!decision.allowed) {
            val line = AircraftCommandUi.blockedLine(command, decision.refusal, decision.detail)
            tvCommandResult.text = line
            tvCommandResult.contentDescription = line
            show(line)
            return
        }
        val simulated = session.link.isSimulated
        app.aircraftCommand.request(
            AircraftCommandPort.Request(command = command, magnitude = magnitude),
        ) { result ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val line = AircraftCommandUi.resultLine(
                    command, result.outcome, result.detail, simulated,
                )
                tvCommandResult.text = line
                tvCommandResult.contentDescription =
                    getString(R.string.cmd_result_accessibility) + ": " + line
                show(line)
            }
        }
    }

    private fun setObserveView(mode: ObserveView.Mode) {
        observeViewState = ObserveView.onLoaded(
            ObserveView.switch(observeViewState, mode),
            ok = mode == ObserveView.Mode.VIDEO,
        )
        refreshObserveView()
    }

    /**
     * Video/map is display-only: flight, authority, AI and recording
     * states are not inputs here. MAP has no source today, so selecting it
     * reports the miss as an overlay status — the video surface is never
     * cleared and the live input keeps flowing underneath.
     */
    private fun refreshObserveView() {
        if (!::tvMapPlaceholder.isInitialized) return
        val map = observeViewState.mode == ObserveView.Mode.MAP
        // D7/round51: big video stays persistent with a corner map PIP; MAP
        // expands the map full-stage while the video surface keeps flowing
        // underneath (a missing map never blanks the video). Routine SHADOW
        // never forces the expanded map — the operator toggles it.
        tvMapPlaceholder.visibility = View.VISIBLE
        tvMapPlaceholder.contentDescription = getString(R.string.map_unavailable_detail)
        surfaceVideo.visibility = View.VISIBLE
        val mapParams = tvMapPlaceholder.layoutParams
        if (mapParams is android.widget.FrameLayout.LayoutParams) {
            // An unavailable basemap never expands over the live video.
            // It stays a recovery entry; stored zone outlines live in Mission.
            mapParams.width = (144 * resources.displayMetrics.density).toInt()
            mapParams.height = (72 * resources.displayMetrics.density).toInt()
            val inset = (12 * resources.displayMetrics.density).toInt()
            mapParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            mapParams.marginStart = inset
            mapParams.marginEnd = 0
            mapParams.bottomMargin = inset
            tvMapPlaceholder.layoutParams = mapParams
        }
        tvMapPlaceholder.text = "지도 미연결\n저장 구역 보기"
        refreshVideo()
        tvMapStatus.text = getString(
            if (map) R.string.map_unavailable_note else R.string.map_video_active,
        )
        setButtonEnabled(R.id.btnViewVideo, map)
        setButtonEnabled(R.id.btnViewMap, !map)
    }

    /** Ops deck refresh: view state plus the AI panel from existing data.
     *  The command result line is event-driven and never overwritten here. */
    private fun refreshOpsDeck() {
        refreshObserveView()
        refreshAiPanel()
        refreshSupervision()
    }

    /**
     * Round51 autonomous-supervision axes: AI execution from the actual
     * bound runtime plus input health (never the legacy `running` bool),
     * camera owner/permission/health as three separate UNKNOWN-until-
     * reported facts, Manifold-local/RC-receipt/Core-store preservation
     * kept apart (ingest-COMMITTED vs collection receipt), backend
     * assessment from the store-linked artifact-plane tallies (singular
     * frozen-MOP fields stay absent), and an intervention need that
     * routine SHADOW never triggers. No auto-response is produced here;
     * confirm/hold/forward stays optional in record/exception detail.
     */
    private fun refreshSupervision() {
        if (!::tvSuperviseAi.isInitialized) return
        val flightId = session.activeFlight?.get("flight_id")?.asText()
        val bound = session.controller?.boundRuntimeStatus
        val linkState = session.link.state
        val edgePanel = lastEdgePanel
        val cards = flightId?.let {
            if (session.storageAvailable) {
                ObservationModel.eventCards(session.store, it)
            } else {
                emptyList()
            }
        } ?: emptyList()
        // A full preview may be truncated; older candidates' modes are unknown.
        val shadowOnly = AiObservationPanel.shadowOnly(cards, complete = cards.size < ObservationModel.DEFAULT_CARD_LIMIT)
        val artifacts = flightId?.let {
            if (session.storageAvailable) session.store.artifactsFor(it) else emptyList()
        } ?: emptyList()
        // Flight-ingest transmission scope (frozen source_kind enum): the
        // COMMITTED fraction below is transmission-only. Withheld kinds
        // (e.g. sealed assessments) count separately and never dilute it.
        val ingestibleArtifacts = artifacts.filter { artifact ->
            (artifact.get("source_kind")?.asText() ?: "") in ManifestBuilder.INGESTIBLE_SOURCE_KINDS
        }
        // Store-linked assessment tallies (real transport path: sealed
        // assessments over the artifact plane). Null when unwired (no
        // flight / store down) → the axis stays UNKNOWN, never zero.
        val assessmentByTick = flightId?.let {
            if (session.storageAvailable) AssessmentModel.assessmentsByTick(session.store, it) else null
        }
        val assessmentAssessed = assessmentByTick?.values?.count { AssessmentModel.isAssessed(it) }
        // Camera truth on the RC today: connection availability is readable,
        // but the actual control owner, the auto-camera permission and the
        // adjustment health have no device report — each stays UNKNOWN
        // (only an explicit SIM scenario may report OPERATOR).
        val panel = AutonomousSupervision.render(
            AutonomousSupervision.Input(
                hasActiveFlight = flightId != null,
                runtimeStateName = bound?.state?.name,
                runtimeSeen = bound != null && edgeRuntimeFieldsSeen(),
                unboundReportSeen = edgeRuntimeFieldsSeen() &&
                    session.controller?.boundRuntimeStatus == null,
                linkUp = when (linkState) {
                    DroneLink.State.CONNECTED -> true
                    DroneLink.State.DISCONNECTED -> false
                    else -> null
                },
                videoInputLost = edgePanel?.inputLossLine != null,
                engineState = bound?.inputState?.get("engine"),
                frameProcessingObserved = session.controller?.executionFacts()?.frameProcessingObserved,
                processedFrames = bound?.processedFrameCount,
                cameraReportedOwner = null,
                cameraOwnerConfirmed = false,
                autoPermission = null,
                adjustmentState = null,
                aircraftConnected = runCatching {
                    app.aircraftConnection.readAircraftConnected()
                }.getOrNull(),
                rcObservationCount = flightId?.let {
                    if (session.storageAvailable) session.store.observationsFor(it).size else null
                },
                rcEventCount = flightId?.let {
                    if (session.storageAvailable) session.store.eventsFor(it).size else null
                },
                coreConfigured = session.coreConfigured(),
                coreSyncedCount = flightId?.let {
                    if (session.storageAvailable) {
                        ingestibleArtifacts.count { artifact ->
                            session.store.isArtifactSynced(artifact.get("artifact_id").asText())
                        }
                    } else {
                        null
                    }
                },
                coreTotalCount = flightId?.let {
                    if (session.storageAvailable) ingestibleArtifacts.size else null
                },
                coreExcludedCount = flightId?.let {
                    if (session.storageAvailable) artifacts.size - ingestibleArtifacts.size else null
                },
                assessmentAssessed = assessmentAssessed,
                assessmentUndetermined = assessmentByTick?.let { byTick ->
                    byTick.size - (assessmentAssessed ?: 0)
                },
                assessmentPending = flightId?.let {
                    if (session.storageAvailable) session.store.pendingAssessmentCount(it) else null
                },
                shadowOnly = shadowOnly,
                // No approved-Alert policy source exists on the RC: store
                // ACTIVE events are not approved Alerts and never demand
                // intervention here. Faults below still surface with source
                // and action.
                hasApprovedAlert = false,
                recordFailure = app.videoRecorder?.hasWriteFailure == true,
                storageAvailable = session.storageAvailable,
                storageDetail = session.storageErrorMessage,
            ),
        )
        bindSupervisionAxis(tvSuperviseAi, panel.ai)
        bindSupervisionAxis(tvSuperviseCamera, panel.camera)
        bindSupervisionAxis(tvSupervisePreserve, panel.preservation)
        bindSupervisionAxis(tvSuperviseAssess, panel.assessment)
        bindSupervisionAxis(tvSuperviseAction, panel.action)
        bindCompactRow()
        buttonOrNull(R.id.btnCandidatesToggle)?.setText(
            if (candidatesExpanded) {
                R.string.btn_candidates_hide
            } else {
                R.string.btn_candidates_show
            },
        )
    }

    private fun bindSupervisionAxis(view: TextView, axis: AutonomousSupervision.Axis) {
        view.text = "${axis.label} · ${axis.value}\n${axis.detail}"
        view.contentDescription = axis.contentDescription
        view.setTextColor(
            ContextCompat.getColor(
                this,
                when (axis.severity) {
                    OperatorStatus.Severity.PROBLEM -> R.color.ksi_problem
                    OperatorStatus.Severity.ATTENTION -> R.color.ksi_attention
                    OperatorStatus.Severity.INFO -> R.color.ksi_fg
                },
            ),
        )
    }

    /**
     * Round51 fix: glance-row input without any store read — bound runtime,
     * link, latched panel loss line, recorder-independent camera facts.
     * Cheap enough for the 1s ticker, so the row stays newest when link or
     * staleness changes without waiting for a dirty state refresh.
     */
    private fun compactSupervisionInput(): AutonomousSupervision.CompactInput {
        val bound = session.controller?.boundRuntimeStatus
        return AutonomousSupervision.CompactInput(
            hasActiveFlight = session.activeFlight != null,
            runtimeStateName = bound?.state?.name,
            runtimeSeen = bound != null && edgeRuntimeFieldsSeen(),
            unboundReportSeen = edgeRuntimeFieldsSeen() &&
                session.controller?.boundRuntimeStatus == null,
            linkUp = when (session.link.state) {
                DroneLink.State.CONNECTED -> true
                DroneLink.State.DISCONNECTED -> false
                else -> null
            },
            videoInputLost = lastEdgePanel?.inputLossLine != null,
            engineState = bound?.inputState?.get("engine"),
            frameProcessingObserved = session.controller?.executionFacts()?.frameProcessingObserved,
            // No actual camera report source on the RC: UNKNOWN until an
            // explicit SIM scenario reports otherwise (never invented here).
            cameraReportedOwner = null,
            cameraOwnerConfirmed = false,
            autoPermission = null,
            // No Manifold-local report field in the frozen MOP: UNKNOWN.
            manifoldLocalState = null,
            storageAvailable = session.storageAvailable,
        )
    }

    private fun bindCompactRow() {
        if (!::tvCompactAi.isInitialized) return
        val row = AutonomousSupervision.compact(compactSupervisionInput())
        tvCompactAi.text = row.ai
        tvCompactCamera.text = row.camera
        tvCompactPreserve.text = row.preservation
        val color = ContextCompat.getColor(
            this,
            when (row.severity) {
                OperatorStatus.Severity.PROBLEM -> R.color.ksi_problem
                OperatorStatus.Severity.ATTENTION -> R.color.ksi_attention
                OperatorStatus.Severity.INFO -> R.color.ksi_fg
            },
        )
        tvCompactAi.setTextColor(color)
        tvCompactCamera.setTextColor(color)
        tvCompactPreserve.setTextColor(color)
        // Persistent concise spatial-limit notice (E): the site context the
        // current flight actually has — never a dismissible toast. No site
        // or no applied plan reads as spatial-judgment-limited, unknown
        // never reads as covered.
        val siteLine = when {
            selectedSiteId == null -> getString(R.string.site_compact_unknown)
            selectedBundleId == null -> getString(
                R.string.site_compact_unapplied_format,
                selectedSiteId!!.take(8),
            )
            else -> getString(R.string.site_compact_applied_format, selectedSiteId!!.take(8))
        }
        tvCompactSite.text = siteLine
        tvCompactSite.contentDescription = "$siteLine."
        findViewById<View>(R.id.supervisionCompactRow)?.contentDescription = row.contentDescription
        refreshWorkspaceState()
    }

    /** AI panel from data that already exists: store observations/events
     *  plus the video source's frame-mapping flag. Absent data renders as
     *  unavailable/pending — never fabricated. The "0건 vs 미수신"
     *  distinction comes from the Edge runtime report: analysis that ran
     *  and found nothing says "관측 0건(분석 수행됨)", still not "safe". */
    private fun refreshAiPanel() {
        if (!::tvAiState.isInitialized) return
        val flightId = session.activeFlight?.get("flight_id")?.asText()
        val analysisRan = session.controller?.executionFacts()?.frameProcessingObserved
        val panel = if (flightId == null) {
            AiObservationPanel.render(
                AiObservationPanel.Input(
                    hasActiveFlight = false,
                    observationCount = 0,
                    eventCount = 0,
                    observationAgeMs = null,
                    frameMappingVerified = app.videoSource.frameMappingVerified,
                ),
            )
        } else {
            val store = session.store
            AiObservationPanel.render(
                AiObservationPanel.Input(
                    hasActiveFlight = true,
                    observationCount = store.observationsFor(flightId).size,
                    eventCount = store.eventsFor(flightId).size,
                    observationAgeMs = ObservationModel.observationAgeMs(
                        store, flightId, ReceiptClock.now(),
                    ),
                    frameMappingVerified = app.videoSource.frameMappingVerified,
                    cards = ObservationModel.eventCards(store, flightId),
                    analysisRan = analysisRan,
                ),
            )
        }
        tvAiState.text = panel.headline + "\n" + panel.detail
        tvAiState.contentDescription = "AI 관측: ${panel.headline}. ${panel.detail}."
        tvAiCandidates.text = if (panel.candidates.isEmpty()) {
            if (panel.unavailable) "후보 목록 없음 · 관측 미수신" else "후보 없음"
        } else {
            panel.candidates.joinToString("\n") { row ->
                "[${row.kind}] ${row.eventId.take(8)} · ${row.quality} · ${row.evidenceSummary}"
            }
        }
        tvAiOverlayNote.text = panel.overlayNote
        tvAiMapNote.text = panel.mapNote
        tvAiState.setTextColor(
            ContextCompat.getColor(
                this,
                if (panel.unavailable) R.color.ksi_attention else R.color.ksi_fg,
            ),
        )
        if (::workspace.isInitialized) workspace.bindDetectionFeed(panel)
        refreshCandidateUi(panel)
    }

    /**
     * Candidate tap -> inline detail + response input (contract §3-8, §4).
     * AT-02: selection only opens the detail — it never changes control,
     * policy, session or records. A missing/stale source is reported as
     * such instead of pretending the detail is current.
     */
    private fun onCandidateTapped(candidateId: String) {
        if (candidateId.isBlank()) return
        val flightId = session.activeFlight?.get("flight_id")?.asText()
        val cards = if (flightId == null) {
            emptyList()
        } else {
            ObservationModel.eventCards(session.store, flightId)
        }
        val ageMs = if (flightId == null) {
            null
        } else {
            ObservationModel.observationAgeMs(session.store, flightId, ReceiptClock.now())
        }
        val selection = CandidateResponse.select(cards, candidateId, ageMs)
        selectedCandidateId = selection.candidateId
        tvResponseResult.text = when (selection.source) {
            ShadowCandidateSelection.SourceStatus.MISSING ->
                getString(R.string.resp_source_missing)
            ShadowCandidateSelection.SourceStatus.STALE ->
                getString(R.string.resp_source_stale)
            ShadowCandidateSelection.SourceStatus.AVAILABLE ->
                getString(R.string.resp_result_none)
        }
        tvResponseResult.contentDescription = tvResponseResult.text
        refreshAiPanel()
    }

    /**
     * Tappable candidate rows + inline response panel. The buttons rebuild
     * only when the candidate id set changes (the 1s ticker must not steal
     * touches); selection state repaints every refresh. Green background
     * ONLY on the selected row.
     */
    private fun refreshCandidateUi(panel: AiObservationPanel.Panel) {
        if (!::aiCandidateList.isInitialized) return
        val flightId = session.activeFlight?.get("flight_id")?.asText()
        val ids = panel.candidates.map { it.eventId }
        // Round51: routine SHADOW never auto-expands a candidate bar. The
        // tappable rows (and the inline confirm/hold/forward detail) stay
        // folded until the operator explicitly opens them; the summary line
        // above keeps the counts reachable, and the full
        // candidate/history/evidence record stays in review. Collapsing
        // clears the inline selection — opening the detail is the selection.
        if (!candidatesExpanded) {
            if (selectedCandidateId != null) {
                selectedCandidateId = null
                tvResponseResult.text = getString(R.string.resp_result_none)
                tvResponseResult.contentDescription = tvResponseResult.text
            }
            lastCandidateIds = emptyList()
            aiCandidateList.removeAllViews()
            aiCandidateList.visibility = View.GONE
            tvAiCandidates.visibility = View.VISIBLE
            if (ids.isNotEmpty()) {
                val kinds = AiObservationPanel.candidateSummary(panel.candidates)
                tvAiCandidates.text = "$kinds · 펼쳐서 확인(기록에서도 확인 가능)"
                tvAiCandidates.contentDescription = "AI 관측 후보: $kinds. " +
                    "펼치거나 관측 기록에서 확인하세요."
            }
            responsePanel.visibility = View.GONE
            return
        }
        if (flightId == null) {
            selectedCandidateId = null
        } else if (selectedCandidateId != null && selectedCandidateId !in ids) {
            selectedCandidateId = null
            tvResponseResult.text = getString(R.string.resp_result_none)
            tvResponseResult.contentDescription = tvResponseResult.text
        }
        val selected = ids.firstOrNull { it == selectedCandidateId }
        if (lastCandidateIds != ids) {
            lastCandidateIds = ids
            aiCandidateList.removeAllViews()
            for (row in panel.candidates) {
                val rowId = row.eventId
                val button = Button(this).apply {
                    text = "[${row.kind}] ${rowId.take(8)} · ${row.quality} · ${row.evidenceSummary}"
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    isAllCaps = false
                    textSize = 13f
                    minHeight = dp(48)
                    setBackgroundResource(R.drawable.candidate_row_selector)
                }
                button.setOnClickListener { onCandidateTapped(rowId) }
                button.contentDescription = "후보 $rowId"
                aiCandidateList.addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) },
                )
            }
        }
        for (index in 0 until aiCandidateList.childCount) {
            val child = aiCandidateList.getChildAt(index)
            val rowId = ids.getOrNull(index) ?: continue
            val isSelected = rowId == selected
            child.isSelected = isSelected
            child.contentDescription =
                "후보 $rowId" + if (isSelected) " · 선택됨" else ""
            (child as? Button)?.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        aiCandidateList.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        // The tappable rows carry the same content when candidates exist;
        // the plain text list stays for the empty states only.
        tvAiCandidates.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE
        val showResponse = selected != null && flightId != null
        responsePanel.visibility = if (showResponse) View.VISIBLE else View.GONE
        if (showResponse) {
            val row = panel.candidates.first { it.eventId == selected }
            val detail = candidateDetailText(row, flightId!!)
            tvResponseCandidate.text = detail
            tvResponseCandidate.contentDescription = "선택된 후보 $selected. $detail"
        }
    }

    /**
     * Selected-candidate detail from data the app already holds: kind (as
     * rendered), observation quality, evidence state, RC receipt time,
     * and the held relations (frame boxes, map position). Anything absent
     * is said to be absent — never invented.
     */
    private fun candidateDetailText(
        row: AiObservationPanel.CandidateRow,
        flightId: String,
    ): String {
        // The SELECTED row's own receipt basis — never the flight-latest
        // time, which would re-stamp an older candidate with a newer boot.
        val resolved = CandidateResponse.resolveEventSource(session.store, flightId, row.eventId)
        val received = (resolved as? CandidateResponse.SourceResolution.Found)?.source?.receivedUtc
        val kindLine = when (row.kind) {
            "SHADOW" -> "종류 · SHADOW · 경고 아님"
            "ACTIVE" -> "종류 · ACTIVE"
            else -> "종류 · ${row.kind}"
        }
        val receivedLine = if (received.isNullOrBlank()) {
            "수신시각 · 수신 기록 미확인 — 추정 안 함"
        } else {
            "수신시각 · RC 수신시각(근사) · $received"
        }
        return listOf(
            kindLine,
            "관측 품질 · ${row.quality}",
            "증거 · ${row.evidenceSummary}",
            receivedLine,
            "유효성 · 탐지 표시 · 영상 박스 보류 · 지도 위치 미확인",
            "정책 · ${getString(R.string.resp_policy_unknown)}",
        ).joinToString("\n")
    }

    /**
     * Minimum-input operator response: writes exactly one record to
     * [RcSession.operatorActions] (the same log the uploader ships as a
     * FLIGHT_LOG artifact) with the ACTIVE flight's session id, or refuses
     * with a reason and writes nothing. Confirming never clears the risk
     * and a SHADOW confirmation is never ACTIVE approval (surfaced by the
     * invariant note next to the result line).
     */
    private fun submitCandidateResponse(action: ObservationResponse.Action) {
        val flight = session.activeFlight
        val flightId = flight?.get("flight_id")?.asText()
        val siteId = flight?.get("site_id")?.asText()
        val cards = if (flightId == null) {
            emptyList()
        } else {
            ObservationModel.eventCards(session.store, flightId)
        }
        val card = cards.firstOrNull { it.eventId == selectedCandidateId }
        // The SELECTED event's actual source — boot and receipt basis follow
        // the event, never the latest boot/time (a newer boot must not
        // re-stamp an older candidate). Unresolvable source refuses below.
        val source = if (flightId == null) {
            null
        } else {
            CandidateResponse.resolveEventSource(
                session.store, flightId, card?.eventId,
            )
        }
        val note = etResponseNote.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
        when (
            val outcome = CandidateResponse.buildResponse(
                flightId = flightId,
                siteId = siteId,
                source = source,
                eventId = card?.eventId,
                action = action,
                note = note,
                actedAtUtc = ReceiptClock.now(),
            )
        ) {
            is CandidateResponse.Outcome.Ready -> {
                try {
                    session.operatorActions.append(outcome.action)
                } catch (exc: Exception) {
                    tvResponseResult.text = getString(R.string.resp_save_failed)
                    tvResponseResult.contentDescription = tvResponseResult.text
                    return
                }
                val label = getString(
                    when (action) {
                        ObservationResponse.Action.CONFIRM -> R.string.btn_respond_confirm
                        ObservationResponse.Action.HOLD -> R.string.btn_respond_hold
                        ObservationResponse.Action.ESCALATE_REQUEST -> R.string.btn_respond_escalate
                    },
                )
                val line = getString(R.string.resp_saved_format, label, outcome.claim)
                tvResponseResult.text = line
                tvResponseResult.contentDescription = line
                etResponseNote.setText("")
                show(line)
            }
            is CandidateResponse.Outcome.Refused -> {
                tvResponseResult.text = outcome.reason
                tvResponseResult.contentDescription = outcome.reason
                show(outcome.reason)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val COCKPIT_REFRESH_MS = 1_000L
        const val TAG = "MainActivity"
    }
}

/**
 * Host-testable condition for the A9-4 unverified-report line: the
 * diagnostic surfaces exactly when the gated aircraft fact is unknown
 * while the MSDK itself reports connected. Returns the line to append,
 * or null when there is nothing to add. Pure so the "reported but
 * unverified ⇒ shown, never masked and never merged into 연결됨"
 * contract is pinned where the Android runtime cannot run.
 */
internal fun unverifiedReportLine(
    aircraftUnknown: Boolean,
    msdkReportsConnection: Boolean,
    line: String,
): String? = if (aircraftUnknown && msdkReportsConnection) line else null

/**
 * Host-testable pending override for the SDK diagnosis step: a started
 * but unresolved SDK init (key present, not registered, no terminal
 * outcome) reads as 확인 중(UNKNOWN), never as a confident 초기화 실패.
 * Key-missing, already-initialised, and non-pending runs pass the
 * diagnosis through untouched. Pure so the matrix is pinned on host.
 */
internal fun withSdkPendingState(
    diagnosis: ConnectionDiagnosis.Diagnosis,
    keyPresent: Boolean,
    initialised: Boolean,
    pending: Boolean,
    pendingValue: String,
    pendingRemedy: String,
): ConnectionDiagnosis.Diagnosis {
    if (!pending || !keyPresent || initialised) return diagnosis
    return diagnosis.copy(steps = diagnosis.steps.map { step ->
        if (step.stage == ConnectionDiagnosis.Stage.SDK) {
            step.copy(
                state = ConnectionDiagnosis.State.UNKNOWN,
                value = pendingValue,
                remedy = pendingRemedy,
            )
        } else {
            step
        }
    })
}

/**
 * Maps a read-only AIRCRAFT camera [Snapshot] to the display input.
 * Pure so the honesty contract is pinned on host where the SDK cannot run:
 * - UNKNOWN until reported: every null stays null (never 1.0x/idle/success);
 * - recording/time/lens/zoom pass through verbatim (lens prefers the stream
 *   source, hardware type is the honest fallback);
 * - photo comes from [AircraftCameraTelemetrySource.Snapshot.displayPhoto]
 *   only: SHOOTING while the SDK reports shooting, CONFIRMED once a
 *   still-photo media notification has evidenced a capture (latched until
 *   the next SHOOTING — no invented expiry), NONE only with an explicit
 *   shooting=false report, otherwise UNKNOWN. NEVER from a command receipt
 *   or an idle transition. FAILED is never produced (no failure key wired);
 * - stale is always false: change-only keys have no SDK-documented cadence,
 *   so silence is not delay (see the source KDoc);
 * - simulated is always false: the real/fake source reports are never
 *   bench-fixture display inputs.
 */
internal fun mapCameraSnapshot(
    snapshot: AircraftCameraTelemetrySource.Snapshot,
): FlightCameraPresentation = FlightCameraPresentation(
    recording = snapshot.recording,
    recordingSeconds = snapshot.recordingSeconds,
    lensName = snapshot.displayLens(),
    zoomRatio = snapshot.zoomRatio,
    photo = when (snapshot.displayPhoto()) {
        AircraftCameraTelemetrySource.PhotoDisplay.SHOOTING -> FlightCameraPresentation.Photo.SHOOTING
        AircraftCameraTelemetrySource.PhotoDisplay.CONFIRMED -> FlightCameraPresentation.Photo.CONFIRMED
        AircraftCameraTelemetrySource.PhotoDisplay.NONE -> when (snapshot.shooting) {
            // Only an explicit shooting=false report confirms an idle photo
            // state. No report at all stays UNKNOWN (detail shows 미수신).
            false -> FlightCameraPresentation.Photo.NONE
            else -> FlightCameraPresentation.Photo.UNKNOWN
        }
    },
    stale = false,
    simulated = false,
)
