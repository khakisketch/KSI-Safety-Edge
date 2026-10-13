package ksi.safetyedge.rc

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var tvFlight: TextView
    private lateinit var tvOpsStatus: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvAi: TextView
    private lateinit var tvEvents: TextView
    private lateinit var tvFlights: TextView
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

    // C67 cockpit shell: nav rail + five areas (approved design).
    private lateinit var tvPageTitle: TextView
    private lateinit var tvTopSim: TextView
    private lateinit var tvTopUtc: TextView

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
    private var selectedSiteId: String? = null
    private var selectedBundleId: String? = null
    private var videoFrames = 0L
    private var eventGeneration = 0
    private var selectedFlightId: String? = null
    private var selectedEventRows: List<FlightHistoryModel.EventRow> = emptyList()
    private var selectedEventIndex = 0
    private var historyGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RcApp
        session = app.session
        demo = app.demoSession
        setContentView(R.layout.activity_main)

        tvMission = findViewById(R.id.tvMission)
        tvFlight = findViewById(R.id.tvFlight)
        tvOpsStatus = findViewById(R.id.tvOpsStatus)
        tvLink = findViewById(R.id.tvLink)
        tvStatus = findViewById(R.id.tvStatus)
        tvVideo = findViewById(R.id.tvVideo)
        tvAi = findViewById(R.id.tvAi)
        tvEvents = findViewById(R.id.tvEvents)
        tvFlights = findViewById(R.id.tvFlights)
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
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)
        app.config.coreTokenStorageError?.let {
            show("보안 저장소 오류: $it — 토큰을 다시 입력하세요")
        }
        etOperatorName.setText(app.config.operatorName)
        etOperatorOrg.setText(app.config.operatorOrganization)
        refreshOperatorState()
        requestMissingDangerousPermissions()
        tvDeviceInfo.text = android.os.Build.MODEL
        tvAppVersion.text = appVersionName()

        tvPageTitle = findViewById(R.id.tvPageTitle)
        tvTopSim = findViewById(R.id.tvTopSim)
        tvTopUtc = findViewById(R.id.tvTopUtc)
        navTargets.forEach { (navId, _) -> on(navId) { showPage(navId) } }
        showPage(R.id.btnNavObserve)

        // -- operational mission (A5: bundle attach comes from the synced
        //    mission store; until then the controller shows "not attached").
        on(R.id.btnSyncSites) { syncSites() }
        on(R.id.btnSyncBundle) { syncBundleLatest() }
        on(R.id.btnStartFlight) { startFlight() }
        on(R.id.btnPrepare) { run("준비") { session.controller?.prepare() } }
        on(R.id.btnApply) { run("적용") { session.controller?.apply() } }
        on(R.id.btnReconcile) { run("재조정") { session.controller?.reconcile() } }
        on(R.id.btnStart) { run("시작") { session.controller?.startSession() } }
        on(R.id.btnStop) { run("정지") { session.controller?.stopSession() } }
        on(R.id.btnEndFlight) {
            val ended = session.endFlight()
            if (ended != null) {
                val flightId = ended.get("flight_id").asText()
                // A8 policy: a finished flight is queued for background sync
                // (network-constrained; COMMITTED checks stay in the uploader).
                SyncScheduler.enqueue(applicationContext, flightId)
                // A9-3 review: finalize + SHA-256 + chunk ingestion can take
                // seconds on large segments — keep it OFF the UI thread.
                show("비행 종료: ${flightId.take(8)} — 자동 동기화 예약 · 세그먼트 인제스트 중…")
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
                                "비행 종료: ${flightId.take(8)} — 자동 동기화 예약 · $segmentNote" +
                                    if (problemNote.isEmpty()) "" else "\n$problemNote",
                            )
                        }
                    }
                }
            } else {
                show("진행 중인 비행 없음")
            }
        }

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
                // Bench source never fires; H1 should throttle UI updates
                // instead of refreshing per frame.
                videoFrames = frameCount
            }
        })
        on(R.id.btnVideoConnect) { app.videoSource.connect(); refresh() }
        on(R.id.btnVideoDisconnect) { app.videoSource.disconnect(); refresh() }

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
        on(R.id.btnFlightPrev) { stepFlight(-1) }
        on(R.id.btnFlightNext) { stepFlight(+1) }
        on(R.id.btnEventPrev) { stepEvent(-1) }
        on(R.id.btnEventNext) { stepEvent(+1) }
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
        on(R.id.btnUpload) { uploadNow() }
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
        refresh()
    }

    override fun onDestroy() {
        // The app-scoped video source must not retain an Activity-bound
        // listener across rotation.
        app.videoSource.setListener(null)
        super.onDestroy()
    }

    private fun on(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            session.clearError()
            try {
                action()
            } catch (exc: Exception) {
                show("오류: ${exc.message}")
            }
            session.syncFlightState()
            refresh()
        }
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

    private fun refresh() {
        val flight = session.activeFlight
        val store = session.store
        renderOpsStatus(flight, store)
        refreshTopBar()
        refreshConnectEmphasis()
        val selectedBundle = selectedBundleId?.let { store.missionBundle(it) }
        tvMission.text = "컨트롤러: ${session.missionSummary()}\n" +
            "미션 캐시: 현장=${store.sites().size} " +
            "구역rev=${selectedSiteId?.let { store.zoneRevisions(it).size } ?: 0} " +
            "번들=${store.missionBundles().size}\n" +
            "선택 현장=${selectedSiteId?.take(8) ?: "-"} " +
            "번들=${selectedBundle?.get("bundle_id")?.asText()?.take(8) ?: "-"} " +
            "sha=${selectedBundle?.get("bundle_sha256")?.asText()?.take(12) ?: "-"} (다운로드 시 검증)"
        tvFlight.text = if (flight == null) {
            "비행: 진행 중 없음 — 현장 동기화 → 번들 내려받기 → 비행 시작"
        } else {
            "비행=${flight.get("flight_id").asText().take(8)} " +
                "상태=${flight.get("state").asText()} " +
                "미션rev=${flight.get("mission_revision_id").asText().take(8)} " +
                "번들=${flight.get("bundle_sha256").asText().take(8)} " +
                "구역=${flight.get("zone_revision_ids").size()}\n" +
                "시작(수신)=${flight.get("started_at_utc").asText()}"
        }

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

        val videoSim = if (app.videoSource.isSimulated) "SIMULATED" else "MSDK"
        val overlay = if (app.videoSource.frameMappingVerified) {
            "오버레이=허용(매핑 검증됨)"
        } else {
            "오버레이=비활성(프레임↔관측 매핑 미검증; 탐지는 목록 표시, 그리지 않음)"
        }
        tvVideo.text = "영상[$videoSim]=${app.videoSource.state} frames=$videoFrames\n$overlay"

        refreshObservation(flight?.get("flight_id")?.asText())
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
            tvError.text = "링크/인제스트 참고: $err"
        }
    }

    /**
     * B-1: one honest summary of "what is happening / what to do", computed
     * from real session facts via the host-tested [OperatorStatus] model.
     * Rendering here is intentionally plain text; the visual treatment is
     * design-workspace owned (docs/verification/operator-flow-spec.md).
     */
    private fun renderOpsStatus(
        flight: com.fasterxml.jackson.databind.node.ObjectNode?,
        store: ksi.rc.FileRcStore,
    ) {
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
        )
        // C67: render the contract items with the approved severity colours —
        // ▮ problem (red) / ▲ attention (amber) / · info (blue); label bold.
        val items = OperatorStatus.evaluate(input)
        val styled = SpannableStringBuilder()
        items.forEachIndexed { index, item ->
            if (index > 0) styled.append("\n")
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
        }
        tvOpsStatus.text = styled
    }

    /** C67 top bar: page title + SIMULATED tag (never hidden) + receipt note. */
    private fun refreshTopBar() {
        val simulated = session.link.isSimulated || app.videoSource.isSimulated
        tvTopSim.visibility = if (simulated) View.VISIBLE else View.GONE
        tvTopUtc.text = getString(R.string.top_receipt_note)
    }

    /**
     * C67 (design round 3): the MOP Connect CTA is neutral by default and
     * takes the problem treatment only while the link state is DISCONNECTED.
     */
    private fun refreshConnectEmphasis() {
        val problem = session.link.state == DroneLink.State.DISCONNECTED
        val connect = findViewById<Button>(R.id.btnConnect)
        connect.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (problem) R.color.ksi_problem_bg else R.color.ksi_surface,
        )
        connect.setTextColor(
            ContextCompat.getColor(this, if (problem) R.color.ksi_problem else R.color.ksi_fg),
        )
    }

    /** C67: five-area navigation (approved design — nav rail + page switching). */
    private fun showPage(navId: Int) {
        navTargets.forEach { (nav, page) ->
            findViewById<View>(page).visibility = if (nav == navId) View.VISIBLE else View.GONE
            val button = findViewById<Button>(nav)
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
                else -> R.string.page_title_observe
            },
        )
    }

    private fun refreshEvidence() {
        val flight = session.activeFlight
        val flightId = flight?.get("flight_id")?.asText()
        if (flightId == null) {
            tvEvidence.text = "진행 중인 비행 없음 (증거는 비행 단위)"
            return
        }
        val store = session.store
        val events = store.eventsFor(flightId)
        val observations = store.observationsFor(flightId)
        val artifacts = store.artifactsFor(flightId)
        val synced = artifacts.count { store.isArtifactSynced(it.get("artifact_id").asText()) }
        val lines = StringBuilder()
        lines.append("로컬 관측=${observations.size} 사건=${events.size} ")
        lines.append("요약=${store.summariesFor(flightId).size}\n")
        for (artifact in artifacts) {
            val id = artifact.get("artifact_id").asText()
            val complete = store.artifactBytes(id) != null
            lines.append("아티팩트 ${id.take(8)} 크기=${artifact.get("size_bytes").asLong()} " +
                "complete=$complete synced=${store.isArtifactSynced(id)}\n")
        }
        lines.append("아티팩트=${artifacts.size} synced=$synced (SYNCED는 COMMITTED 후에만)")
        tvEvidence.text = lines.toString()
    }

    /** A6/A8: AI/observation status + event cards — derived from the store only. */
    private fun refreshObservation(flightId: String?) {
        if (flightId == null) {
            tvAi.text = "AI: 진행 중인 비행 없음 (먼저 비행을 시작하세요)"
            tvEvents.text = "-"
            return
        }
        val store = session.store
        val pulse = ObservationModel.observationPulse(store, flightId)
        val eventCount = store.eventsFor(flightId).size
        val now = ReceiptClock.now()
        val hbAge = ObservationModel.heartbeatAgeMs(store, flightId, now)
        val obsAge = ObservationModel.observationAgeMs(store, flightId, now)
        val evAge = ObservationModel.eventAgeMs(store, flightId, now)
        fun ageText(ageMs: Long?): String =
            if (ageMs == null) "없음" else "${ageMs / 1000.0}s 전"
        fun stateText(state: String): String = when (state) {
            "OK" -> "정상"
            "STALE" -> "지연"
            else -> "없음"
        }
        val hbState = ObservationModel.classifyFreshness(
            hbAge, ObservationModel.HEARTBEAT_STALE_MS,
        )
        val obsState = ObservationModel.classifyFreshness(
            obsAge, ObservationModel.STALE_DATA_MS,
        )
        tvAi.text =
            "channels — heartbeat=${stateText(hbState)}(${ageText(hbAge)}) · " +
            "AIobs=${stateText(obsState)}(${ageText(obsAge)}) · " +
            "lastEvent=${ageText(evAge)}\n" +
            "withheld=${pulse.withheld} degraded=${pulse.degraded} · " +
            ObservationModel.aiVerdict(pulse.count, eventCount)

        val generation = ++eventGeneration
        session.bg.execute {
            val cards = ObservationModel.eventCards(session.store, flightId)
            val text = buildString {
                if (cards.isEmpty()) {
                    append("수신된 사건 없음(로컬 저장소)")
                } else {
                    for (card in cards.take(10)) {
                        val kind = if (card.detectionOnly) "관측" else "위험후보(경고 아님)"
                        append("[${card.mode}·$kind]")
                        append(" ${card.eventType} q=${card.observationQuality}")
                        append(" edge_ns=${card.detectedAtEdgeNs}")
                        if (card.evidence.isNotEmpty()) {
                            append(
                                " · " + when {
                                    card.evidenceSynced -> "증거: 동기화됨"
                                    card.evidenceComplete -> "증거: 수신 완료(미동기화)"
                                    else -> "증거: 미완료"
                                },
                            )
                        }
                        append("\n")
                    }
                }
            }
            runOnUiThread {
                if (generation == eventGeneration && !isDestroyed) {
                    tvEvents.text = text
                }
            }
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
                runOnUiThread {
                    if (bitmap != null) {
                        ivEvidence.setImageBitmap(bitmap)
                        tvEvidenceMeta.text =
                            "evidence ${artifactId.take(8)} ${bytes.size}B source=$source (이미지 표시)"
                    } else {
                        ivEvidence.setImageDrawable(null)
                        tvEvidenceMeta.text =
                            "evidence ${artifactId.take(8)} ${bytes.size}B source=$source (이미지 아님 — 메타만)"
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
            val listText = buildString {
                if (rows.isEmpty()) {
                    append("아직 기록된 비행이 없습니다")
                } else {
                    rows.forEachIndexed { index, row ->
                        append(if (row.flightId == selected) "▶ " else "   ")
                        append("[$index] ${row.flightId.take(8)} ${row.state}")
                        append(" obs=${row.observationCount} ev=${row.eventCount}")
                        append("(${row.detectionOnlyCount}탐지/${row.candidateCount}후보)")
                        append(" evid=${row.artifactCompleteCount}/${row.artifactCount}")
                        append(" synced=${row.artifactSyncedCount}")
                        if (row.falsePositiveCount > 0) append(" 오탐의심=${row.falsePositiveCount}")
                        append(" ${row.startedAtUtc?.take(19) ?: "-"}(수신)")
                        if (index < rows.size - 1) append("\n")
                    }
                }
            }
            val detail = selected?.let { FlightHistoryModel.detail(session.store, it) }
            val detailText = detail?.let { renderDetail(it) } ?: "-"
            val eventsText = detail?.let { renderFlightEvents(it) } ?: "-"
            val artifactsText = detail?.let { renderArtifacts(it) } ?: "-"
            runOnUiThread {
                if (generation != historyGeneration || isDestroyed) return@runOnUiThread
                selectedFlightId = selected
                selectedEventRows = detail?.events ?: emptyList()
                if (selectedEventRows.isEmpty()) {
                    selectedEventIndex = 0
                } else {
                    selectedEventIndex = selectedEventIndex.coerceIn(0, selectedEventRows.size - 1)
                }
                tvFlights.text = listText
                tvFlightDetail.text = detailText
                tvFlightEvents.text = eventsText
                tvFlightArtifacts.text = artifactsText
                refreshEventSelection()
            }
        }
    }

    private fun renderDetail(detail: FlightHistoryModel.FlightDetail): String {
        val row = detail.row
        return buildString {
            append("비행 ${row.flightId.take(8)} state=${row.state}\n")
            append("현장 ${row.siteId.take(8)}")
            if (row.siteName != null) append(" (${row.siteName})")
            append(" 미션rev=${row.missionRevisionId.take(8)} ")
            append("bundle=${row.bundleSha256.take(8)}\n")
            append("시간(수신 기준): start=${row.startedAtUtc ?: "-"} ")
            append("end=${row.endedAtUtc ?: "-"} lastData=${row.lastReceivedUtc ?: "-"}\n")
            append("위험구역 핀: ")
            if (detail.zonePins.isEmpty()) {
                append("없음")
            } else {
                detail.zonePins.forEach { pin ->
                    append("[${pin.ruleKind ?: "?"} ${pin.revisionId.take(8)}")
                    append(if (pin.stored) " 저장됨]" else " 캐시없음]")
                    append(" ")
                }
            }
            append("\n관측/공백: obs=${row.observationCount} ")
            append("aiUptime=${row.aiUptimeRatio ?: "-"} ")
            append("covered=${row.coveredFrames ?: "-"} skipped=${row.skippedFrames ?: "-"} ")
            append("MOP갭=${row.mopGapCount} 요약=${if (detail.summaryPresent) "있음" else "없음"}")
        }
    }

    private fun renderFlightEvents(detail: FlightHistoryModel.FlightDetail): String =
        buildString {
            if (detail.events.isEmpty()) {
                append("사건 0건 — 정상적인 비행 기록 (AI가 분석했지만 사건이 없을 수 있음)")
            } else {
                detail.events.forEachIndexed { index, row ->
                    append(if (index == selectedEventIndex) "▶ " else "   ")
                    append("[$index] [${row.card.mode}")
                    append(if (row.card.detectionOnly) "·관측]" else "·위험후보(경고 아님)]")
                    append(" ${row.card.eventType} q=${row.card.observationQuality}")
                    if (row.card.evidence.isNotEmpty()) {
                        append(" 증거:${row.card.evidence.count { it.complete }}/${row.card.evidence.size}수신")
                        if (row.card.evidenceSynced) append("·동기화")
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
                append("증거 없음")
            } else {
                detail.artifacts.forEachIndexed { index, artifact ->
                    append("${artifact.artifactId.take(8)} ${artifact.mediaType} ")
                    append("${artifact.sizeBytes}B ")
                    append("수신=${artifact.complete} synced=${artifact.synced}")
                    if (index < detail.artifacts.size - 1) append("\n")
                }
            }
        }

    private fun refreshEventSelection() {
        val row = selectedEventRows.getOrNull(selectedEventIndex)
        if (row == null) {
            tvEventDetail.text = "선택된 사건 없음"
            btnFlagFalsePositive.text = getString(R.string.btn_flag_false_positive)
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
            append("사건=${row.card.eventId.take(8)} ${row.card.eventType} ")
            append("mode=${row.card.mode} q=${row.card.observationQuality}\n")
            append("edge_monotonic_ns=${row.card.detectedAtEdgeNs} (Edge 도메인 — UTC 변환 안 함)\n")
            append("증거: ")
            if (row.card.evidence.isEmpty()) {
                append("없음")
            } else {
                row.card.evidence.forEach { link ->
                    append("[${link.artifactId.take(8)} ")
                    append("수신=${link.complete} synced=${link.synced}] ")
                }
            }
            append("\n주석 기록=${row.annotationCount} ")
            append("유효표시=${row.effectiveAnnotation ?: "없음"} (append-only)")
        }
    }

    /** Display-only Korean label for the internal internet vocabulary. */
    private fun internetLabel(): String = when (val status = internetStatus()) {
        "validated" -> "검증됨"
        "connected (unvalidated)" -> "연결됨(미검증)"
        "no internet" -> "인터넷 없음"
        "none" -> "없음"
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
                    tvSync.text = "현장 동기화됨: 로컬=${local.size}"
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("현장 동기화 실패: ${exc.message}") }
            }
        }
    }

    private fun syncBundleLatest() {
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
                    tvSync.text = "현장 데이터: 구역 리비전 +${newZoneRevisions.size}, " +
                        "로컬 번들=$localCount, 선택=" +
                        "${selectedBundleId!!.take(8)} (digest 검증됨)"
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("번들 동기화 실패: ${exc.message}") }
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
            val segmentSeconds = bundle.get("mission_snapshot")
                ?.get("recording_policy")?.get("segment_seconds")?.asInt() ?: 300
            app.startFlightRecording(record.get("flight_id").asText(), segmentSeconds)
            show(
                "비행 시작: ${record.get("flight_id").asText().take(8)} " +
                    "(bundle ${bundleId.take(8)} 고정; prepare/apply 오프라인 실행;" +
                    " 녹화=${segmentSeconds}s 구간, 벤치=레코더 없음)",
            )
        } catch (exc: Exception) {
            show("시작 실패: ${exc.message}")
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

    private fun uploadNow() {
        val uploader = session.uploader()
        if (uploader == null) {
            show("업로드 비활성: 먼저 Core URL과 토큰을 설정하세요")
            return
        }
        val spec = try {
            session.uploadSpec()
        } catch (exc: Exception) {
            show("업로드 거부: ${exc.message}")
            return
        }
        tvSync.text = "업로드 중…"
        session.bg.execute {
            try {
                val report = uploader.uploadFlight(spec)
                val lines = StringBuilder()
                lines.append("state=${report.uploadState} timeBasis=${report.timeBasis} " +
                    "앱=${report.applicationsUploaded} 세션=${report.sessionsUploaded} " +
                    "의견=${report.annotationsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("부트 ${boot.bootId.take(8)} ${boot.status} " +
                        "chunks=${boot.chunksSent} missing=${boot.missingArtifacts.size} " +
                        "synced=${boot.synced.size} " +
                        "receipt=${boot.receivedStartedUtc ?: "-"}\n")
                }
                if (report.sessionDrift.isNotEmpty()) {
                    lines.append("세션 드리프트: ${report.sessionDrift.size}건(서버가 이전 스냅샷 보유)\n")
                }
                if (report.boots.any { it.incompleteLocal.isNotEmpty() }) {
                    lines.append("미완료 로컬 증거 — 완성되면 다음 동기화에서 자동 재시도됨\n")
                }
                if (report.annotationFailures.isNotEmpty()) {
                    lines.append("의견 기록 실패(재시도 예정): " +
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
            show("업로드 비활성: 먼저 Core URL과 토큰을 설정하세요")
            return
        }
        val flight = session.activeFlight
        if (flight == null) {
            show("조회할 진행 중 비행 없음")
            return
        }
        val flightId = flight.get("flight_id").asText()
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
                    tvSync.text = "core=server upload_state=$uploadState " +
                        "서버 사건=${serverEvents.items.size} (${serverEvents.source}, " +
                        "${if (serverEvents.source == EvidenceSource.LOCAL) "오프라인 사본" else "가져옴"})"
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
                info.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
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
        tvError.text = message
    }
}
