package ksi.safetyedge.rc

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
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import ksi.rc.EvidenceSource

/**
 * A5 shell: operational sections read the single [RcSession] live state;
 * the demo bench reads/is confined to the physically isolated
 * [DemoSession]. No screen keeps its own copy of core state.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var app: RcApp
    private lateinit var session: RcSession
    private lateinit var demo: DemoSession

    private lateinit var tvMission: TextView
    private lateinit var tvMissionReadiness: TextView
    private lateinit var tvMissionSyncResult: TextView
    private lateinit var tvMissionSite: TextView
    private lateinit var tvMissionBundle: TextView
    private lateinit var tvMissionRevision: TextView
    private lateinit var tvMissionZone: TextView
    private lateinit var tvMissionFlightId: TextView
    private lateinit var tvMissionFlightState: TextView
    private lateinit var tvMissionStarted: TextView
    private lateinit var tvOpsStatus: TextView
    private lateinit var tvPrimaryAlert: TextView
    private lateinit var tvPrimaryAction: TextView
    private lateinit var tvAiPeek: TextView
    private lateinit var tvObservationGapPeek: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvVideoPlaceholder: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvInternetStatus: TextView
    private lateinit var tvEvidenceStatus: TextView
    private lateinit var tvFlights: TextView
    private lateinit var spHistoryFlight: Spinner
    private lateinit var tvFlightDetail: TextView
    private lateinit var tvFlightEvents: TextView
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
    private lateinit var spSite: Spinner
    private lateinit var spBundle: Spinner
    private lateinit var navRail: View
    private lateinit var rightStatusPanel: View
    private lateinit var developerTools: View
    private lateinit var btnFocusMode: Button
    private lateinit var btnTogglePanel: Button
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
    private lateinit var tvAircraftStatus: TextView
    private lateinit var tvMopStatus: TextView
    private lateinit var tvKsiRecordStatus: TextView
    private lateinit var tvRecorderStatus: TextView
    private lateinit var tvRecordEndNote: TextView

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
    private var lastOverview: CockpitStatusPresenter.Overview? = null

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
        R.id.btnNavEvidence to R.id.pageEvidence,
        R.id.btnNavSettings to R.id.pageSettings,
    )

    private var shownError: String? = null
    private var hasSyncReport = false
    private var missionSyncResultResource = R.string.mission_sync_result_unknown
    private var selectedSiteId: String? = null
    private var selectedBundleId: String? = null
    private var siteIds: List<String> = emptyList()
    private var siteAdapterLabels: List<String> = emptyList()
    private var bundleIds: List<String> = emptyList()
    private var bundleAdapterLabels: List<String> = emptyList()
    private var updatingMissionSelectors = false
    private var flightChromeCollapsed = false
    @Volatile private var cockpitStateDirty = true
    private var activityResumed = false
    private var selectedFlightId: String? = null
    private var historyFlightIds: List<String> = emptyList()
    private var historyAdapterLabels: List<String> = emptyList()
    private var updatingHistorySelector = false
    private var selectedEventRows: List<FlightHistoryModel.EventRow> = emptyList()
    private var selectedEventIndex = 0
    private var historyGeneration = 0
    private val cockpitHandler = Handler(Looper.getMainLooper())
    private val cockpitTicker = object : Runnable {
        override fun run() {
            if (!activityResumed || isDestroyed) return
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
            }
            cockpitHandler.postDelayed(this, COCKPIT_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RcApp
        session = app.session
        demo = app.demoSession
        setContentView(R.layout.activity_main)

        tvMission = findViewById(R.id.tvMission)
        tvMissionSite = findViewById(R.id.tvMissionSite)
        tvMissionBundle = findViewById(R.id.tvMissionBundle)
        tvMissionRevision = findViewById(R.id.tvMissionRevision)
        tvMissionZone = findViewById(R.id.tvMissionZone)
        tvMissionFlightId = findViewById(R.id.tvMissionFlightId)
        tvMissionFlightState = findViewById(R.id.tvMissionFlightState)
        tvMissionStarted = findViewById(R.id.tvMissionStarted)
        tvOpsStatus = findViewById(R.id.tvOpsStatus)
        tvPrimaryAlert = findViewById(R.id.tvPrimaryAlert)
        tvPrimaryAction = findViewById(R.id.tvPrimaryAction)
        tvAiPeek = findViewById(R.id.tvAiPeek)
        tvObservationGapPeek = findViewById(R.id.tvObservationGapPeek)
        tvLink = findViewById(R.id.tvLink)
        tvStatus = findViewById(R.id.tvStatus)
        tvVideo = findViewById(R.id.tvVideo)
        tvVideoPlaceholder = findViewById(R.id.tvVideoPlaceholder)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        tvInternetStatus = findViewById(R.id.tvInternetStatus)
        tvEvidenceStatus = findViewById(R.id.tvEvidenceStatus)
        tvFlights = findViewById(R.id.tvFlights)
        spHistoryFlight = findViewById(R.id.spHistoryFlight)
        tvFlightDetail = findViewById(R.id.tvFlightDetail)
        tvFlightEvents = findViewById(R.id.tvFlightEvents)
        tvFlightArtifacts = findViewById(R.id.tvFlightArtifacts)
        tvEventDetail = findViewById(R.id.tvEventDetail)
        tvEvidenceMeta = findViewById(R.id.tvEvidenceMeta)
        ivEvidence = findViewById(R.id.ivEvidence)
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
        spSite = findViewById(R.id.spSite)
        spBundle = findViewById(R.id.spBundle)
        navRail = findViewById(R.id.navRail)
        rightStatusPanel = findViewById(R.id.rightStatusPanel)
        developerTools = findViewById(R.id.developerTools)
        btnFocusMode = findViewById(R.id.btnFocusMode)
        btnTogglePanel = findViewById(R.id.btnTogglePanel)
        appHeader = findViewById(R.id.appHeader)
        headerDivider = findViewById(R.id.headerDivider)
        tvCockpitSim = findViewById(R.id.tvCockpitSim)
        tvRecordEndNote = findViewById(R.id.tvRecordEndNote)
        bindStaticButtons()
        findViewById<Button>(R.id.btnStartFlight)?.apply {
            backgroundTintList = ContextCompat.getColorStateList(
                this@MainActivity,
                R.color.ksi_primary,
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_on_primary))
        } ?: Log.w(TAG, "bindStaticButtons: missing btnStartFlight")
        findViewById<Button>(R.id.btnEndFlight)?.apply {
            backgroundTintList = ContextCompat.getColorStateList(
                this@MainActivity,
                R.color.ksi_surface,
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ksi_fg))
        } ?: Log.w(TAG, "bindStaticButtons: missing btnEndFlight")
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)
        app.config.coreTokenStorageError?.let {
            show("보안 저장소 오류: $it — 토큰을 다시 입력하세요")
        }
        etOperatorName.setText(app.config.operatorName)
        etOperatorOrg.setText(app.config.operatorOrganization)
        refreshOperatorState()
        requestMissingDangerousPermissions()
        tvDeviceInfo.text = getString(R.string.app_info_device_format, android.os.Build.MODEL)
        tvAppVersion.text = appVersionName()

        tvPageTitle = findViewById(R.id.tvPageTitle)
        tvTopSim = findViewById(R.id.tvTopSim)
        tvTopUtc = findViewById(R.id.tvTopUtc)
        tvTopContext = findViewById(R.id.tvTopContext)
        tvAircraftStatus = findViewById(R.id.tvAircraftStatus)
        tvMopStatus = findViewById(R.id.tvMopStatus)
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
        tvKsiRecordStatus = findViewById(R.id.tvKsiRecordStatus)
        tvRecorderStatus = findViewById(R.id.tvRecorderStatus)
        installMissionReadinessUi()
        tvError.setOnClickListener { tvError.visibility = View.GONE }
        bindMissionSelectors()
        bindHistorySelector()
        navTargets.forEach { (navId, _) -> on(navId) { showPage(navId) } }
        on(R.id.btnFocusMode) { toggleFocusMode() }
        on(R.id.btnTogglePanel) { toggleFlightPanel() }
        on(R.id.btnPriorityDetails) { setFlightPanelOpen(true) }
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
        on(R.id.btnToggleDeveloper) {
            val expanded = developerTools.visibility != View.VISIBLE
            developerTools.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        showPage(
            if (session.activeFlight != null) R.id.btnNavObserve else R.id.btnNavMission,
        )

        // -- operational mission (A5: bundle attach comes from the synced
        //    mission store; until then the controller shows "not attached").
        on(R.id.btnSyncSites) { syncSites() }
        on(R.id.btnSyncBundle) { syncBundleLatest() }
        on(R.id.btnStartFlight) { startFlight() }
        on(R.id.btnPrepare) { run("임무 준비 요청") { session.controller?.prepare() } }
        on(R.id.btnApply) { run("임무 적용 요청") { session.controller?.apply() } }
        on(R.id.btnReconcile) { run("적용 상태 확인") { session.controller?.reconcile() } }
        on(R.id.btnStart) { run("관측 세션 시작") { session.controller?.startSession() } }
        on(R.id.btnStop) { run("관측 세션 정지") { session.controller?.stopSession() } }
        on(R.id.btnEndFlight) { confirmEndFlight() }

        // -- operational link
        on(R.id.btnConnect) { session.link.connect(); session.pumpOutbound(); refresh() }
        on(R.id.btnDisconnect) { session.link.disconnect(); refresh() }
        on(R.id.btnRetry) {
            val sent = session.retryUnacked() + session.pumpOutbound()
            show("재전송 ${sent}건 — 동일 msg_id, 봉투당 1회 전송")
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
                val resumed = SyncScheduler.enqueuePending(applicationContext, session)
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
        // A8 recovery trigger: a process start re-queues a finished flight
        // whose previously incomplete local evidence is now complete.
        SyncScheduler.enqueuePending(applicationContext, session)?.let { flightId ->
            hasSyncReport = true
            tvSync.text = "자동 동기화 재개 예약: ${flightId.take(8)} (완료된 로컬 데이터 재동기화)"
        }

        // A9-3 review residual: a restart during an active flight used to
        // silently stop recording. Resume it — SegmentRecorderCore continues
        // the flight directory's numbering — and state the lost tail.
        val activeFlight = session.activeFlight
        val recorder = app.videoRecorder
        if (activeFlight != null && recorder != null &&
            recorder.state != VideoRecorder.State.RECORDING
        ) {
            val flightId = activeFlight.get("flight_id").asText()
            app.startFlightRecording(flightId, flightSegmentSeconds(flightId))
            show("녹화 재개: 재시작 이전 구간 일부는 저장되지 않았습니다 (세그먼트 번호는 이어짐)")
        }
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

    private fun bindMissionSelectors() {
        spSite.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (updatingMissionSelectors) return
                val siteId = siteIds.getOrNull(position) ?: return
                if (siteId == selectedSiteId) return
                selectedSiteId = siteId
                selectedBundleId = session.store.missionBundles()
                    .lastOrNull { it.get("site_id")?.asText() == siteId }
                    ?.get("bundle_id")?.asText()
                refresh()
            }
        }
        spBundle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (updatingMissionSelectors) return
                val bundleId = bundleIds.getOrNull(position) ?: return
                if (bundleId == selectedBundleId) return
                selectedBundleId = bundleId
                refresh()
            }
        }
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

    private fun refreshMissionSelectors() {
        val sites = session.store.sites()
        val nextSiteIds = sites.mapNotNull { it.get("site_id")?.asText() }
        val nextSiteLabels = sites.mapIndexed { index, site ->
            val name = site.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: "현장 ${index + 1}"
            "$name · ${nextSiteIds.getOrNull(index)?.take(8) ?: "-"}"
        }
        val active = session.activeFlight
        if (selectedSiteId == null || selectedSiteId !in nextSiteIds) {
            selectedSiteId = active?.get("site_id")?.asText()?.takeIf { it in nextSiteIds }
                ?: nextSiteIds.firstOrNull()
        }
        siteIds = nextSiteIds
        val siteDisplay = nextSiteLabels.ifEmpty { listOf("현장 없음 · 먼저 동기화하세요") }

        val candidates = selectedSiteId?.let { siteId ->
            session.store.missionBundles().filter { it.get("site_id")?.asText() == siteId }
        }.orEmpty()
        val nextBundleIds = candidates.mapNotNull { it.get("bundle_id")?.asText() }
        val nextBundleLabels = candidates.mapIndexed { index, bundle ->
            val bundleId = bundle.get("bundle_id")?.asText() ?: "-"
            val revision = bundle.get("mission_snapshot")?.get("mission_revision_id")
                ?.asText()?.take(8) ?: "리비전 미상"
            "${index + 1}. ${bundleId.take(8)} · rev $revision"
        }
        if (selectedBundleId == null || selectedBundleId !in nextBundleIds) {
            val activeBundle = active?.get("bundle_id")?.asText()
            selectedBundleId = activeBundle?.takeIf { it in nextBundleIds }
                ?: nextBundleIds.lastOrNull()
        }
        bundleIds = nextBundleIds
        val bundleDisplay = nextBundleLabels.ifEmpty { listOf("번들 없음 · 내려받기가 필요합니다") }

        updatingMissionSelectors = true
        if (siteAdapterLabels != siteDisplay) {
            siteAdapterLabels = siteDisplay
            spSite.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                siteDisplay,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val siteIndex = selectedSiteId?.let { siteIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        if (siteDisplay.isNotEmpty()) spSite.setSelection(siteIndex, false)

        if (bundleAdapterLabels != bundleDisplay) {
            bundleAdapterLabels = bundleDisplay
            spBundle.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                bundleDisplay,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val bundleIndex = selectedBundleId?.let { bundleIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        if (bundleDisplay.isNotEmpty()) spBundle.setSelection(bundleIndex, false)
        updatingMissionSelectors = false
    }

    private fun toggleFocusMode() {
        flightChromeCollapsed = !flightChromeCollapsed
        updateFlightChrome()
    }

    private fun toggleFlightPanel() {
        setFlightPanelOpen(rightStatusPanel.visibility != View.VISIBLE)
    }

    private fun setFlightPanelOpen(open: Boolean) {
        rightStatusPanel.visibility = if (open) View.VISIBLE else View.GONE
        val videoStage = findViewById<View>(R.id.videoStage)
        val stageParams = videoStage.layoutParams
        if (stageParams is android.widget.FrameLayout.LayoutParams) {
            // Reserve the same 360dp panel + 16dp gutter before showing the
            // diagnostics panel. The live surface resizes instead of sitting
            // underneath it, so status inspection never covers the image.
            val reserve = if (open) {
                (376f * resources.displayMetrics.density + 0.5f).toInt()
            } else {
                0
            }
            if (stageParams.rightMargin != reserve) {
                stageParams.rightMargin = reserve
                videoStage.layoutParams = stageParams
            }
        }
        btnTogglePanel.setText(
            if (open) R.string.btn_close_ops_panel else R.string.btn_open_ops_panel,
        )
        if (open) findViewById<View>(R.id.tvOpsPanelTitle).requestFocus()
    }

    private fun showEvidencePage() {
        showPage(R.id.btnNavEvidence)
        refreshEvidence()
    }

    private fun updateFlightChrome() {
        val collapsed = flightChromeCollapsed &&
            findViewById<View>(R.id.pageObserve).visibility == View.VISIBLE
        navRail.visibility = if (collapsed) View.GONE else View.VISIBLE
        appHeader.visibility = if (collapsed) View.GONE else View.VISIBLE
        headerDivider.visibility = if (collapsed) View.GONE else View.VISIBLE
        btnFocusMode.setText(
            if (collapsed) R.string.btn_show_menu else R.string.btn_hide_menu,
        )
    }

    private fun run(label: String, action: () -> Unit) {
        val active = session.controller
        if (active == null) {
            show("$label 불가: 연결된 번들 없음 (임무 동기화 또는 데모 섹션)")
            return
        }
        try {
            action()
            val sent = session.pumpOutbound()
            show(
                if (session.link.state == DroneLink.State.CONNECTED) {
                    "$label 전송됨 (봉투 ${sent}건)"
                } else {
                    "$label 대기열 (링크 끊김, ${sent}건 전송)"
                },
            )
        } catch (exc: Exception) {
            show("$label 거부: ${exc.message}")
        }
    }

    /**
     * G17: the mission order is declared statically in activity_main.xml
     * (sync row → sync chip → prepare/apply row → local-record start →
     * start note → additional-check/reconcile row → observe session row).
     * This only binds the two static views and sets the initial chip text;
     * no runtime remove/add reordering (no parent-cast / sibling-order
     * assumptions, no silent early return).
     */
    private fun installMissionReadinessUi() {
        val readiness: TextView? = findViewById(R.id.tvMissionReadiness)
        if (readiness == null) {
            Log.w(TAG, "installMissionReadinessUi: missing tvMissionReadiness")
            return
        }
        tvMissionReadiness = readiness
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
        refreshConnectEmphasis()
        refreshMissionSelectors()
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
            tvMissionReadiness.text = listOf(
                getString(R.string.mission_readiness_sync_format, coreReadiness),
                bundleReadiness,
                recordReadiness,
            ).joinToString("\n")
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
        tvMissionFlightId.text = "기록 ID · ${flight?.get("flight_id")?.asText()?.take(8) ?: "없음"}"
        tvMissionFlightState.text = "기록 상태 · ${flight?.get("state")?.asText() ?: "미시작"}"
        tvMissionStarted.text = "시작 수신 · ${flight?.get("started_at_utc")?.asText()?.take(19) ?: "-"}"
        setButtonEnabled(R.id.btnSyncSites, app.config.coreConfigured)
        setButtonEnabled(
            R.id.btnSyncBundle,
            app.config.coreConfigured && selectedSiteId != null,
        )
        setButtonEnabled(
            R.id.btnStartFlight,
            selectedBundle != null && flight == null,
        )
        val hasController = session.controller != null
        setButtonEnabled(R.id.btnPrepare, hasController)
        setButtonEnabled(R.id.btnApply, hasController)
        setButtonEnabled(R.id.btnReconcile, hasController)
        setButtonEnabled(R.id.btnStart, hasController)
        setButtonEnabled(R.id.btnStop, hasController)
        setButtonEnabled(R.id.btnEndFlight, flight != null)

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
        val coreLine =
            if (app.config.coreConfigured) "core=${app.config.coreUrl}"
            else "core=미설정(업로드 비활성)"
        val tokenStorageError = app.config.coreTokenStorageError
        tvUploadGate.text = when {
            tokenStorageError != null -> "업로드: 비활성 — 보안 저장소 오류($tokenStorageError)"
            app.config.coreConfigured -> "업로드: 활성"
            else -> "업로드: 비활성 — Core URL과 토큰 필요"
        }
        if (!hasSyncReport) {
            tvSync.text = coreLine
        }
        val err = session.lastError
        if (!err.isNullOrEmpty() && err != shownError) {
            shownError = err
            show("오류 · 링크/인제스트: $err")
        }
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
        updateAiPeekFreshness()
    }

    /**
     * B-1: one honest summary of "what is happening / what to do", computed
     * from real session facts via the host-tested [OperatorStatus] model.
     * Rendering here is intentionally plain text; the visual treatment is
     * design-workspace owned (docs/plans/2026-10-12-b1-operator-flow-contract.md).
     */
    private fun renderOpsStatus(
        flight: com.fasterxml.jackson.databind.node.ObjectNode?,
        store: ksi.rc.FileRcStore,
    ): List<OperatorStatus.Item> {
        val flightId = flight?.get("flight_id")?.asText()
        val now = ReceiptClock.now()
        val summary = flightId?.let { store.summariesFor(it).lastOrNull() }
        val artifacts = flightId?.let { store.artifactsFor(it) } ?: emptyList()
        val input = OperatorStatus.Input(
            hasActiveFlight = flightId != null,
            linkState = session.link.state.name,
            linkSimulated = session.link.isSimulated,
            aircraftConnected = session.link.aircraftConnected,
            internet = internetStatus(),
            recorderState = app.videoRecorder?.state?.name,
            mopHeartbeatAgeMs = flightId?.let {
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
        )
        // C67: render the contract items with the approved severity colours —
        // ▮ problem (red) / ▲ attention (amber) / · info (blue); label bold.
        val items = OperatorStatus.evaluate(input)
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
            VideoFreshnessModel.Status.STALE -> "▲" to R.color.ksi_attention
            VideoFreshnessModel.Status.UNKNOWN -> "?" to R.color.ksi_info
            else -> "·" to R.color.ksi_info
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
        tvVideoPlaceholder.text = when (freshness.status) {
            VideoFreshnessModel.Status.DISCONNECTED -> "인코딩 입력과 화면 표시는 별도로 확인합니다"
            VideoFreshnessModel.Status.CONNECTING -> "연결 요청 상태 · 실제 스트림/프레임은 미확인"
            VideoFreshnessModel.Status.NO_SIGNAL -> "수신 신호는 보고되지 않았습니다 · 화면 표시 여부 미확인"
            VideoFreshnessModel.Status.NO_FRAMES -> "연결 보고와 프레임 수신은 별도입니다"
            VideoFreshnessModel.Status.UNKNOWN -> "지연·실패·화면 표시 성공으로 추측하지 않습니다"
            VideoFreshnessModel.Status.RECENT -> "수신은 디코딩·화면 표시 성공을 뜻하지 않습니다"
            VideoFreshnessModel.Status.STALE -> "마지막 입력 이후 경과 표시 · 화면 표시는 별도 확인"
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

    private fun refreshCockpitFacts(items: List<OperatorStatus.Item>) {
        val activeFlight = session.activeFlight
        val activeFlightId = activeFlight?.get("flight_id")?.asText()
        val unsyncedArtifactCount = activeFlightId?.let { flightId ->
            session.store.artifactsFor(flightId).count { artifact ->
                !session.store.isArtifactSynced(artifact.get("artifact_id").asText())
            }
        } ?: 0
        val overview = CockpitStatusPresenter.present(
            items = items,
            hasActiveFlight = activeFlight != null,
            unsyncedArtifactCount = unsyncedArtifactCount,
        )
        lastOverview = overview
        renderTelemetryStrip()
        val endRecordButton = buttonOrNull(R.id.btnEndFlight)
        if (activeFlight == null) {
            endRecordButton?.visibility = View.GONE
            tvRecordEndNote.setText(R.string.cockpit_record_end_note_none)
        } else {
            endRecordButton?.visibility = View.VISIBLE
            tvRecordEndNote.setText(R.string.cockpit_record_end_note_active)
        }
        renderStatusChip(
            tvKsiRecordStatus,
            overview.ksiSession.value,
            statusColor(overview.ksiSession.severity),
            statusMarker(overview.ksiSession.severity),
        )
        tvKsiRecordStatus.contentDescription = overview.ksiSession.contentDescription

        renderStatusChip(
            tvMopStatus,
            overview.mop.value,
            statusColor(overview.mop.severity),
            statusMarker(overview.mop.severity),
        )
        tvMopStatus.contentDescription = overview.mop.contentDescription

        renderStatusChip(
            tvAircraftStatus,
            overview.aircraft.value,
            statusColor(overview.aircraft.severity),
            if (overview.aircraft.value.contains("알 수 없음")) "?" else statusMarker(overview.aircraft.severity),
        )
        tvAircraftStatus.contentDescription = overview.aircraft.contentDescription

        val internet = internetStatus()
        val (internetValue, internetMarker) = when (internet) {
            "validated" -> "연결됨" to "·"
            "connected (unvalidated)" -> "미검증" to "?"
            "no internet", "none" -> "없음" to "·"
            else -> "알 수 없음" to "?"
        }
        renderStatusChip(tvInternetStatus, "인터넷 · $internetValue", R.color.ksi_info, internetMarker)

        renderStatusChip(
            tvRecorderStatus,
            overview.recorder.value,
            statusColor(overview.recorder.severity),
            statusMarker(overview.recorder.severity),
        )
        tvRecorderStatus.contentDescription = overview.recorder.contentDescription

        renderStatusChip(
            tvEvidenceStatus,
            overview.evidence.value,
            statusColor(overview.evidence.severity),
            statusMarker(overview.evidence.severity),
        )
        tvEvidenceStatus.contentDescription = overview.evidence.contentDescription

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

    private fun isWithin(child: View, ancestor: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun statusColor(severity: OperatorStatus.Severity): Int = when (severity) {
        OperatorStatus.Severity.PROBLEM -> R.color.ksi_problem
        OperatorStatus.Severity.ATTENTION -> R.color.ksi_attention
        OperatorStatus.Severity.INFO -> R.color.ksi_info
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
        val controller = session.controller ?: return "기록 세션 시작 후 기체 임무 제어 연결"
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
        val cockpit = navId == R.id.btnNavObserve
        flightChromeCollapsed = cockpit
        navTargets.forEach { (nav, page) ->
            val pageView: View? = findViewById(page)
            if (pageView == null) {
                Log.w(TAG, "showPage: missing page id=$page")
            } else {
                pageView.visibility = if (nav == navId) View.VISIBLE else View.GONE
            }
            val button = navButtons[nav] ?: buttonOrNull(nav) ?: return@forEach
            val selected = nav == navId
            button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (selected) R.color.ksi_surface_subtle else R.color.ksi_surface,
            )
            button.setTextColor(ContextCompat.getColor(this, R.color.ksi_fg))
        }
        tvPageTitle.setText(
            when (navId) {
                R.id.btnNavMission -> R.string.page_title_mission
                R.id.btnNavReview -> R.string.page_title_review
                R.id.btnNavEvidence -> R.string.page_title_evidence
                R.id.btnNavSettings -> R.string.page_title_settings
                else -> R.string.page_title_inflight
            },
        )
        setFlightPanelOpen(false)
        updateFlightChrome()
        if (cockpit) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun refreshEvidence() {
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
                    lines.append("${id.take(8)} · ${artifact.get("media_type")?.asText() ?: "자료"} · ${bytes}B\n")
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

    private fun updateAiPeekFreshness() {
        if (!::tvAiPeek.isInitialized) return
        val flightId = session.activeFlight?.get("flight_id")?.asText()
        if (flightId == null) {
            tvAiPeek.text = "기록 없음"
            tvAiPeek.setTextColor(ContextCompat.getColor(this, R.color.ksi_fg))
            tvObservationGapPeek.text = ""
            tvObservationGapPeek.visibility = View.GONE
            tvObservationGapPeek.contentDescription = null
            tvAiPeek.contentDescription =
                "AI 관측 수신 상태. 활성 기록이 없어 관측 수신을 기다리는 중입니다."
            return
        }
        val ageMs = ObservationModel.observationAgeMs(
            session.store,
            flightId,
            ReceiptClock.now(),
        )
        val freshness = ObservationModel.observationFreshnessLabel(ageMs)
        val stale = ageMs != null && ageMs >= OperatorStatus.OBSERVATION_STALE_MS
        val state = when {
            ageMs == null -> "미수신"
            stale -> "수신 지연 · $freshness"
            else -> freshness
        }
        val latestSummary = session.store.summariesFor(flightId).lastOrNull()
        val skippedFrames = latestSummary?.get("skipped_frames")
            ?.takeIf { it.isNumber }
            ?.asLong()
        val coveredFrames = latestSummary?.get("covered_frames")
            ?.takeIf { it.isNumber }
            ?.asLong()
        val gapDescription = if (skippedFrames != null && skippedFrames > 0) {
            val covered = coveredFrames?.let { "$it" } ?: "수 미확인"
            "직전 요약 기준, 심각도 정보, 커버 ${covered}프레임 · 관측 공백 ${skippedFrames}프레임"
        } else {
            null
        }
        tvObservationGapPeek.text = if (gapDescription != null) {
            "정보 · 직전 요약 · 공백 ${skippedFrames}프레임"
        } else {
            ""
        }
        tvObservationGapPeek.visibility = if (gapDescription != null) View.VISIBLE else View.GONE
        tvObservationGapPeek.contentDescription = gapDescription
        tvAiPeek.text = state
        tvAiPeek.setTextColor(
            ContextCompat.getColor(
                this,
                if (stale) R.color.ksi_attention else R.color.ksi_fg,
            ),
        )
        tvAiPeek.contentDescription = buildString {
            append("AI 관측 수신 상태: ").append(state).append(".")
            gapDescription?.let { append(" ").append(it).append(".") }
            append(" 수신 지연은 안전 판정이 아닙니다.")
        }
    }

    // -- A7 flight history ------------------------------------------------------

    private fun stepFlight(direction: Int) {
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
        val row = selectedEventRows.getOrNull(selectedEventIndex) ?: run {
            show("먼저 사건을 선택하세요")
            return
        }
        val artifactId = row.card.evidence.firstOrNull()?.artifactId ?: run {
            show("이 사건에 연결된 증거 없음")
            return
        }
        val query = session.query()
        if (!localOnly && query == null) {
            show("Core 미설정(서버 가져오기 불가)")
            return
        }
        session.bg.execute {
            try {
                val slice = if (localOnly) {
                    val local = session.store.artifactBytes(artifactId)
                    if (local == null) {
                        runOnUiThread {
                            show("$artifactId: 로컬 수신 불완전/미검증 — 서버 가져오기 사용")
                        }
                        return@execute
                    }
                    EvidenceSource.LOCAL to local
                } else {
                    val fetched = query!!.artifactBytes(artifactId)
                    if (fetched == null) {
                        runOnUiThread { show("$artifactId: 로컬·서버 모두 없음") }
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
                    if (bitmap != null) {
                        ivEvidence.setImageBitmap(bitmap)
                        tvEvidenceMeta.text =
                            "증거 ${artifactId.take(8)} · ${bytes.size}B · 출처 $sourceLabel · 이미지 표시"
                    } else {
                        ivEvidence.setImageDrawable(null)
                        tvEvidenceMeta.text =
                            "증거 ${artifactId.take(8)} · ${bytes.size}B · 출처 $sourceLabel · 이미지 형식 아님"
                    }
                }
            } catch (exc: Exception) {
                runOnUiThread { show("증거 불러오기 실패: ${exc.message}") }
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
        val generation = ++historyGeneration
        session.bg.execute {
            val rows = FlightHistoryModel.flights(session.store)
            val selected = selectedFlightId?.takeIf { id ->
                rows.any { it.flightId == id }
            } ?: rows.firstOrNull()?.flightId
            val labels = rows.mapIndexed { index, row ->
                val site = row.siteName ?: row.siteId.take(8)
                val received = row.startedAtUtc?.take(16) ?: "시작 시각 미상"
                "${index + 1}. $site · ${row.flightId.take(8)} · $received (수신 근사)"
            }
            val detail = selected?.let { FlightHistoryModel.detail(session.store, it) }
            val detailText = detail?.let { renderDetail(it) } ?: "-"
            val eventsText = detail?.let { renderFlightEvents(it) } ?: "-"
            val artifactsText = detail?.let { renderArtifacts(it) } ?: "-"
            runOnUiThread {
                if (generation != historyGeneration || isDestroyed) return@runOnUiThread
                selectedFlightId = selected
                selectedEventRows = detail?.events ?: emptyList()
                historyFlightIds = rows.map { it.flightId }
                val displayLabels = labels.ifEmpty { listOf("비행 기록 없음") }
                updatingHistorySelector = true
                if (historyAdapterLabels != displayLabels) {
                    historyAdapterLabels = displayLabels
                    spHistoryFlight.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_item,
                        displayLabels,
                    ).also {
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
                tvFlights.text = if (rows.isEmpty()) {
                    "이 기기에 저장된 비행 기록이 없습니다."
                } else {
                    "${rows.size}개 로컬 기록 · 선택 ${selectedPosition?.plus(1) ?: "-"}/${rows.size} · 최근 기록 우선"
                }
                if (selectedEventRows.isEmpty()) {
                    selectedEventIndex = 0
                } else {
                    selectedEventIndex = selectedEventIndex.coerceIn(0, selectedEventRows.size - 1)
                }
                tvFlightDetail.text = detailText
                tvFlightEvents.text = eventsText
                tvFlightArtifacts.text = artifactsText
                refreshEventSelection()
            }
        }
    }

    private fun renderDetail(detail: FlightHistoryModel.FlightDetail): String {
        val row = detail.row
        val state = when (row.state) {
            "STARTED" -> "기록 중"
            "ENDED" -> "기록 종료"
            else -> row.state
        }
        return buildString {
            append("${row.siteName ?: "현장 ${row.siteId.take(8)}"} · ${row.flightId.take(8)} · $state\n")
            append("미션 rev ${row.missionRevisionId.take(8)} · 번들 ${row.bundleSha256.take(8)} · ")
            append("구역 ${row.zoneRevisionIds.size}개\n")
            append("시작 ${row.startedAtUtc?.take(19) ?: "알 수 없음"} · 종료 ${row.endedAtUtc?.take(19) ?: "-"} (RC 수신시각 근사)\n")
            append(getString(
                R.string.review_duration_line_format,
                formatFlightDuration(row.startedAtUtc, row.endedAtUtc),
            ) + "\n")
            append("관측 ${row.observationCount} · 객체 관측 ${row.detectionOnlyCount} · 후보 ${row.candidateCount} · ")
            append("커버 ${row.coveredFrames ?: "-"} / 공백 ${row.skippedFrames ?: "-"}프레임\n")
            append("로컬 증거 ${row.artifactCompleteCount}/${row.artifactCount} 완료 · ")
            append("서버 커밋 확인 ${row.artifactSyncedCount}/${row.artifactCount} · 링크 공백 ${row.mopGapCount}")
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

    private fun renderFlightEvents(detail: FlightHistoryModel.FlightDetail): String =
        buildString {
            if (detail.events.isEmpty()) {
                if (detail.row.observationCount == 0) {
                    append("관측 데이터 없음 · 사건 0건과 구분 필요 · 안전 확인 아님")
                } else {
                    append("관측 ${detail.row.observationCount}건 · 사건 0건 · 정상 기록일 수 있으나 안전 확인은 아님")
                }
            } else {
                detail.events.forEachIndexed { index, row ->
                    append(if (index == selectedEventIndex) "▶ " else "· ")
                    append(if (row.card.detectionOnly) "객체 관측" else "위험 후보 · 경고 아님")
                    append(" [${row.card.mode}] · 품질 ${row.card.observationQuality}")
                    if (row.card.evidence.isNotEmpty()) {
                        append(" · 증거 로컬 ${row.card.evidence.count { it.complete }}/${row.card.evidence.size}")
                        if (row.card.evidenceSynced) append(" · 서버 커밋 확인")
                    }
                    if (row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_FALSE_POSITIVE) {
                        append(" · 오탐 의심")
                        append(localOperatorSuffix(row.effectiveActorId))
                    } else if (row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_NEEDS_REVIEW) {
                        append(" · 검토 필요")
                        append(localOperatorSuffix(row.effectiveActorId))
                    }
                    if (index < detail.events.size - 1) append("\n")
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
        if (row == null) {
            tvEventDetail.text = getString(R.string.postflight_no_event_detail)
            btnFlagFalsePositive.text = getString(R.string.postflight_no_event_action)
            btnFlagFalsePositive.isEnabled = false
            return
        }
        btnFlagFalsePositive.isEnabled = true
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
        }
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
        setMissionSyncResult(R.string.mission_sync_result_unknown)
        val sync = session.missionSync() ?: run {
            show("먼저 Core URL과 토큰을 설정하세요")
            return
        }
        tvSync.text = "현장 동기화 중…"
        session.bg.execute {
            try {
                sync.syncSites()
                val local = session.store.sites()
                val previous = selectedSiteId
                selectedSiteId = previous?.takeIf { id ->
                    local.any { it.get("site_id").asText() == id }
                } ?: local.firstOrNull()?.get("site_id")?.asText()
                runOnUiThread {
                    hasSyncReport = true
                    setMissionSyncResult(R.string.mission_sync_result_success_local)
                    tvSync.text = "성공(로컬 반영): 현장 ${local.size}건 · 서버 상태 별도"
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
        setMissionSyncResult(R.string.mission_sync_result_unknown)
        val sync = session.missionSync() ?: run {
            show("먼저 Core URL과 토큰을 설정하세요")
            return
        }
        val siteId = selectedSiteId ?: run {
            show("먼저 현장을 동기화하세요")
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

    private fun startFlight() {
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
        try {
            val record = session.startFlightFromBundle(bundle)
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
            show("시작 실패: ${exc.message}")
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
        if (session.activeFlight == null) {
            show("종료할 KSI 로컬 기록 세션이 없습니다")
            return
        }
        val endButton = findViewById<Button>(R.id.btnEndFlight)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("KSI 기록 세션 종료")
            .setMessage(R.string.cockpit_end_record_confirm_message)
            .setNegativeButton("계속 기록", null)
            .setPositiveButton(R.string.btn_end_flight) { _, _ -> finishFlight() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (session.activeFlight != null && endButton.isShown && endButton.isEnabled) {
                endButton.requestFocus()
            }
        }
        dialog.show()
    }

    private fun finishFlight() {
        try {
            val ended = session.endFlight()
            if (ended == null) {
                show("종료할 비행 기록이 없습니다")
                return
            }
            val flightId = ended.get("flight_id").asText()
            selectedFlightId = flightId
            SyncScheduler.enqueue(applicationContext, flightId)
            showPage(R.id.btnNavReview)
            show("KSI 기록 ${flightId.take(8)} 종료 · 로컬 자료 정리 및 자동 동기화 예약 중…")
            refreshHistory()
            refreshEvidence()
            session.bg.execute {
                val ingest = ingestRecordedSegments(flightId)
                val segmentNote = when {
                    ingest.total == 0 -> "세그먼트 0(레코더 없음/영상 미수신)"
                    ingest.problems.isNotEmpty() && ingest.ingested == 0 ->
                        "세그먼트 ${ingest.total}개 로컬 보관(사유 ${ingest.problems.size}건)"
                    ingest.ingested == ingest.total ->
                        "세그먼트 ${ingest.ingested}개 → 증거 인제스트"
                    else -> "세그먼트 ${ingest.ingested}/${ingest.total} 인제스트"
                }
                val problemNote = ingest.problems.take(3).joinToString("\n")
                runOnUiThread {
                    if (!isDestroyed) {
                        show(
                            "KSI 기록 ${flightId.take(8)} 종료 · $segmentNote" +
                                if (problemNote.isEmpty()) "" else "\n$problemNote",
                        )
                        refreshHistory()
                        refreshEvidence()
                    }
                }
            }
        } catch (exc: Exception) {
            show("비행 기록 종료 실패: ${exc.message}")
        }
    }

    private data class SegmentIngest(
        val ingested: Int,
        val total: Int,
        val problems: List<String>,
    )

    /**
     * A9-3: finalized recording segments become RC_PROXY_SEGMENT evidence on
     * the flight's LATEST Edge boot (the normal Manifold-connected case). A
     * flight with no Edge boot keeps its segments local and says so — no
     * synthetic boot is fabricated. Structurally broken files are never
     * ingested ([Mp4Probe]). Runs off the UI thread; problems are RETURNED
     * for the caller to surface on the UI thread.
     */
    private fun ingestRecordedSegments(flightId: String): SegmentIngest {
        val files = app.stopFlightRecording()
        if (files.isEmpty()) return SegmentIngest(0, 0, emptyList())
        val bootId = ksi.rc.SessionBuilder(session.store)
            .sortedBoots(flightId).lastOrNull()
            ?: return SegmentIngest(
                0,
                files.size,
                listOf("Edge 부트 없음 — 세그먼트 로컬 보관(증거 미생성)"),
            )
        val ingestor = ksi.rc.SegmentIngestor(session.store)
        var ingested = 0
        val problems = mutableListOf<String>()
        for (file in files) {
            val index = SegmentNames.parseIndex(file.name) ?: continue
            if (!Mp4Probe.looksPlayable(file)) {
                problems.add("${file.name}: MP4 구조 아님 — 증거 제출 안 함(로컬 보관)")
                continue
            }
            try {
                ingestor.ingestSegment(flightId, bootId, index, file)
                ingested += 1
            } catch (exc: Exception) {
                problems.add("${file.name}: ${exc.message}")
            }
        }
        return SegmentIngest(ingested, files.size, problems)
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
        val uploader = session.uploader()
        if (uploader == null) {
            show("동기화 불가: 설정에서 Core URL과 토큰을 확인하세요")
            return
        }
        val flightId = requestedFlightId
            ?: session.activeFlight?.get("flight_id")?.asText()
            ?: FlightHistoryModel.flights(session.store).firstOrNull()?.flightId
        if (flightId == null) {
            show("동기화할 로컬 비행 기록이 없습니다")
            return
        }
        val spec = try {
            session.uploadSpec(flightId)
        } catch (exc: Exception) {
            show("업로드 거부: ${exc.message}")
            return
        }
        tvSync.text = "비행 ${flightId.take(8)} · Core 동기화 요청 중…\n" +
            "완료 여부는 서버 커밋 응답으로 확인합니다."
        session.bg.execute {
            try {
                val report = uploader.uploadFlight(spec)
                val lines = StringBuilder()
                val uploadState = when (report.uploadState) {
                    "COMMITTED" -> "서버 커밋 확인"
                    "PARTIAL" -> "일부 자료 미완료"
                    else -> "서버 응답 ${report.uploadState}"
                }
                lines.append("비행 ${flightId.take(8)} · $uploadState\n")
                lines.append("시각 기준 ${report.timeBasis} · 임무 적용 ${report.applicationsUploaded} · " +
                    "세션 ${report.sessionsUploaded} · 의견 ${report.annotationsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("수집 세션 ${boot.bootId.take(8)} · ${boot.status} · " +
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
        val query = session.query()
        if (query == null) {
            show("Core 확인 불가: 설정에서 URL과 토큰을 확인하세요")
            return
        }
        val flightId = session.activeFlight?.get("flight_id")?.asText()
            ?: selectedFlightId
            ?: FlightHistoryModel.flights(session.store).firstOrNull()?.flightId
        if (flightId == null) {
            show("서버에서 확인할 로컬 비행 기록이 없습니다")
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

    private fun show(message: String) {
        val problem = listOf("실패", "오류", "거부", "불가", "미승인", "미등록")
            .any { it in message }
        if (!problem) {
            tvError.visibility = View.GONE
            if (message.isNotBlank()) {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        tvError.text = "$message  ·  눌러 닫기"
        tvError.visibility = View.VISIBLE
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

    private companion object {
        const val COCKPIT_REFRESH_MS = 1_000L
        const val TAG = "MainActivity"
    }
}
