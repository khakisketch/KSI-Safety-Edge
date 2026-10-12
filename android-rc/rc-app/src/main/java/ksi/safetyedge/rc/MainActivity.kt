package ksi.safetyedge.rc

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var surfaceVideo: android.view.SurfaceView

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
        surfaceVideo = findViewById(R.id.surfaceVideo)
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)

        // -- operational mission (A5: bundle attach comes from the synced
        //    mission store; until then the controller shows "not attached").
        on(R.id.btnSyncSites) { syncSites() }
        on(R.id.btnSyncBundle) { syncBundleLatest() }
        on(R.id.btnStartFlight) { startFlight() }
        on(R.id.btnPrepare) { run("prepare") { session.controller?.prepare() } }
        on(R.id.btnApply) { run("apply") { session.controller?.apply() } }
        on(R.id.btnReconcile) { run("reconcile") { session.controller?.reconcile() } }
        on(R.id.btnStart) { run("start") { session.controller?.startSession() } }
        on(R.id.btnStop) { run("stop") { session.controller?.stopSession() } }
        on(R.id.btnEndFlight) {
            val ended = session.endFlight()
            if (ended != null) {
                val flightId = ended.get("flight_id").asText()
                // A8 policy: a finished flight is queued for background sync
                // (network-constrained; COMMITTED checks stay in the uploader).
                SyncScheduler.enqueue(applicationContext, flightId)
                // A9-3 review: finalize + SHA-256 + chunk ingestion can take
                // seconds on large segments — keep it OFF the UI thread.
                show("flight ended: ${flightId.take(8)} — auto-sync 예약 · 세그먼트 인제스트 중…")
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
                                "flight ended: ${flightId.take(8)} — auto-sync 예약 · $segmentNote" +
                                    if (problemNote.isEmpty()) "" else "\n$problemNote",
                            )
                        }
                    }
                }
            } else {
                show("no active flight")
            }
        }

        // -- operational link
        on(R.id.btnConnect) { session.link.connect(); session.pumpOutbound(); refresh() }
        on(R.id.btnDisconnect) { session.link.disconnect(); refresh() }
        on(R.id.btnRetry) {
            val sent = session.retryUnacked() + session.pumpOutbound()
            show("re-sent $sent envelope(s) — same msg_id, one send per envelope")
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
            show("SIMULATED demo tick: $count envelopes into the demo store")
        }
        on(R.id.btnDemoNewFlight) {
            val record = demo.startNewFlight()
            show("demo flight: ${record.get("flight_id").asText().take(8)}")
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
            if (app.config.coreConfigured) {
                // A8: a Core that just became reachable picks up the latest
                // finished flight whose local data is complete but unsynced.
                val resumed = SyncScheduler.enqueuePending(applicationContext, session)
                if (resumed != null) {
                    show("Core config saved — auto-sync 예약: ${resumed.take(8)}")
                } else {
                    show("Core config saved")
                }
            } else {
                show("upload disabled: Core URL + token required")
            }
            refresh()
        }
        on(R.id.btnUpload) { uploadNow() }
        on(R.id.btnServerState) { serverState() }

        refresh()
        // A8 recovery trigger: a process start re-queues a finished flight
        // whose previously incomplete local evidence is now complete.
        SyncScheduler.enqueuePending(applicationContext, session)?.let { flightId ->
            hasSyncReport = true
            tvSync.text = "auto-sync 재개 예약: ${flightId.take(8)} (완료된 로컬 데이터 재동기화)"
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
                show("error: ${exc.message}")
            }
            session.syncFlightState()
            refresh()
        }
    }

    private fun run(label: String, action: () -> Unit) {
        val active = session.controller
        if (active == null) {
            show("$label unavailable: no bundle attached (A5 mission sync, or demo section)")
            return
        }
        try {
            action()
            val sent = session.pumpOutbound()
            show(
                if (session.link.state == DroneLink.State.CONNECTED) {
                    "$label sent ($sent envelope(s))"
                } else {
                    "$label queued (link down, $sent sent)"
                },
            )
        } catch (exc: Exception) {
            show("$label rejected: ${exc.message}")
        }
    }

    private fun refresh() {
        val flight = session.activeFlight
        val store = session.store
        renderOpsStatus(flight, store)
        val selectedBundle = selectedBundleId?.let { store.missionBundle(it) }
        tvMission.text = "controller: ${session.missionSummary()}\n" +
            "mission cache: sites=${store.sites().size} " +
            "zoneRevs=${selectedSiteId?.let { store.zoneRevisions(it).size } ?: 0} " +
            "bundles=${store.missionBundles().size}\n" +
            "selected site=${selectedSiteId?.take(8) ?: "-"} " +
            "bundle=${selectedBundle?.get("bundle_id")?.asText()?.take(8) ?: "-"} " +
            "sha=${selectedBundle?.get("bundle_sha256")?.asText()?.take(12) ?: "-"} (verified at download)"
        tvFlight.text = if (flight == null) {
            "flight: none active — Sync sites → Download bundle → Start flight"
        } else {
            "flight=${flight.get("flight_id").asText().take(8)} " +
                "state=${flight.get("state").asText()} " +
                "missionRev=${flight.get("mission_revision_id").asText().take(8)} " +
                "bundle=${flight.get("bundle_sha256").asText().take(8)} " +
                "zones=${flight.get("zone_revision_ids").size()}\n" +
                "started(receipt)=${flight.get("started_at_utc").asText()}"
        }

        val endpoint = session.controller?.endpoint
        val simulatedTag = if (session.link.isSimulated) " SIMULATED" else ""
        tvLink.text = "link=${session.link.state}$simulatedTag\n" +
            "in (this session): stored=${session.stored} dup=${session.duplicates} " +
            "failed=${session.failed} last=${session.lastInbound}\n" +
            "out: pending=${endpoint?.pendingCount() ?: 0} " +
            "acked=${endpoint?.ackedCount ?: 0}"

        // A6: three INDEPENDENT connection facts — never merged.
        val aircraft = when (session.link.aircraftConnected) {
            true -> "connected"
            false -> "not connected"
            null -> "unknown (link cannot report)"
        }
        tvStatus.text = "aircraft=$aircraft" +
            (if (session.link.isSimulated) " [SIMULATED link]" else "") + "\n" +
            "manifold/link=${session.link.state}$simulatedTag\n" +
            "internet=${internetStatus()}"

        val videoSim = if (app.videoSource.isSimulated) "SIMULATED" else "MSDK"
        val overlay = if (app.videoSource.frameMappingVerified) {
            "overlay=allowed (mapping verified)"
        } else {
            "overlay=DISABLED (frame<->observation mapping unverified; detections are listed, not drawn)"
        }
        tvVideo.text = "video[$videoSim]=${app.videoSource.state} frames=$videoFrames\n$overlay"

        refreshObservation(flight?.get("flight_id")?.asText())
        refreshHistory()

        val demoStore = demo.session.store
        val demoFlight = demo.activeFlight()
        tvDemo.text = "DEMO (isolated store, no upload)\n" +
            "flight=${demoFlight?.get("flight_id")?.asText()?.take(8) ?: "-"} " +
            "state=${demoFlight?.get("state")?.asText() ?: "-"} " +
            "controller: ${demo.missionSummary()}\n" +
            "demo store: obs=${demoStore.observationsFor(demoFlight?.get("flight_id")?.asText() ?: "-").size}"

        refreshEvidence()
        val coreLine =
            if (app.config.coreConfigured) "core=${app.config.coreUrl}"
            else "core=NOT CONFIGURED (upload disabled)"
        if (!hasSyncReport) {
            tvSync.text = coreLine
        }
        val err = session.lastError
        if (!err.isNullOrEmpty() && err != shownError) {
            shownError = err
            tvError.text = "link/ingest note: $err"
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
        tvOpsStatus.text = OperatorStatus.evaluate(input).joinToString("\n") { item ->
            val marker = when (item.severity) {
                OperatorStatus.Severity.PROBLEM -> "■"
                OperatorStatus.Severity.ATTENTION -> "▲"
                OperatorStatus.Severity.INFO -> "·"
            }
            buildString {
                append("$marker ${item.label}")
                if (item.detail.isNotEmpty()) append(" — ${item.detail}")
                item.action?.let { append("  → $it") }
            }
        }
    }

    private fun refreshEvidence() {
        val flight = session.activeFlight
        val flightId = flight?.get("flight_id")?.asText()
        if (flightId == null) {
            tvEvidence.text = "no active flight (evidence is per-flight)"
            return
        }
        val store = session.store
        val events = store.eventsFor(flightId)
        val observations = store.observationsFor(flightId)
        val artifacts = store.artifactsFor(flightId)
        val synced = artifacts.count { store.isArtifactSynced(it.get("artifact_id").asText()) }
        val lines = StringBuilder()
        lines.append("LOCAL observations=${observations.size} events=${events.size} ")
        lines.append("summaries=${store.summariesFor(flightId).size}\n")
        for (artifact in artifacts) {
            val id = artifact.get("artifact_id").asText()
            val complete = store.artifactBytes(id) != null
            lines.append("artifact ${id.take(8)} size=${artifact.get("size_bytes").asLong()} " +
                "complete=$complete synced=${store.isArtifactSynced(id)}\n")
        }
        lines.append("artifacts=${artifacts.size} synced=$synced (SYNCED only after COMMITTED)")
        tvEvidence.text = lines.toString()
    }

    /** A6/A8: AI/observation status + event cards — derived from the store only. */
    private fun refreshObservation(flightId: String?) {
        if (flightId == null) {
            tvAi.text = "AI: no active flight (start a flight first)"
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
                    append("no events received (local store)")
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
            show("no flights recorded")
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
            show("this flight has no events")
            return
        }
        selectedEventIndex = (selectedEventIndex + direction)
            .coerceIn(0, selectedEventRows.size - 1)
        refreshEventSelection()
    }

    private fun toggleFalsePositive() {
        val row = selectedEventRows.getOrNull(selectedEventIndex) ?: run {
            show("select an event first")
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
                        FlightHistoryModel.revokePrevious(target, session.rcDeviceId, ReceiptClock.now()),
                    )
                    "오탐 의심 해제 — REVOKE_PREVIOUS 기록 추가 (원 기록은 보존)"
                } else {
                    session.store.queueAnnotation(
                        FlightHistoryModel.falsePositiveAnnotation(
                            eventId = row.card.eventId,
                            flightId = flightId,
                            deviceId = session.rcDeviceId,
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
                runOnUiThread { show("annotation failed: ${exc.message}") }
            }
        }
    }

    private fun viewEvidence(localOnly: Boolean) {
        val row = selectedEventRows.getOrNull(selectedEventIndex) ?: run {
            show("select an event first")
            return
        }
        val artifactId = row.card.evidence.firstOrNull()?.artifactId ?: run {
            show("this event has no linked evidence")
            return
        }
        val query = session.query()
        if (!localOnly && query == null) {
            show("Core not configured (server fetch unavailable)")
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
                runOnUiThread { show("evidence load failed: ${exc.message}") }
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
                    append("no flights recorded yet")
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
            append("flight ${row.flightId.take(8)} state=${row.state}\n")
            append("현장 ${row.siteId.take(8)}")
            if (row.siteName != null) append(" (${row.siteName})")
            append(" 미션rev=${row.missionRevisionId.take(8)} ")
            append("bundle=${row.bundleSha256.take(8)}\n")
            append("시간(수신 기준): start=${row.startedAtUtc ?: "-"} ")
            append("end=${row.endedAtUtc ?: "-"} lastData=${row.lastReceivedUtc ?: "-"}\n")
            append("위험구역 핀: ")
            if (detail.zonePins.isEmpty()) {
                append("none")
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
            append("MOP갭=${row.mopGapCount} summary=${if (detail.summaryPresent) "yes" else "none"}")
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
                    } else if (row.effectiveAnnotation == FlightHistoryModel.ANNOTATION_NEEDS_REVIEW) {
                        append(" · 검토 필요")
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
            tvEventDetail.text = "no event selected"
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
            append("event=${row.card.eventId.take(8)} ${row.card.eventType} ")
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
            show("configure Core URL + token first")
            return
        }
        tvSync.text = "syncing sites…"
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
                    tvSync.text = "sites synced: local=${local.size}"
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("site sync failed: ${exc.message}") }
            }
        }
    }

    private fun syncBundleLatest() {
        val sync = session.missionSync() ?: run {
            show("configure Core URL + token first")
            return
        }
        val siteId = selectedSiteId ?: run {
            show("sync sites first")
            return
        }
        tvSync.text = "syncing site data…"
        session.bg.execute {
            try {
                val newZoneRevisions = sync.syncZoneRevisions(siteId)
                val serverBundles = sync.listSiteBundles(siteId)
                val target = serverBundles.lastOrNull()
                    ?: throw IllegalStateException("no bundle on the server for this site")
                val bundle = sync.syncBundle(target.get("bundle_id").asText())
                selectedBundleId = bundle.get("bundle_id").asText()
                val localCount = sync.localSiteBundles(siteId).size
                runOnUiThread {
                    hasSyncReport = true
                    tvSync.text = "site data: +${newZoneRevisions.size} zone revision(s), " +
                        "local bundles=$localCount, selected=" +
                        "${selectedBundleId!!.take(8)} (digest verified)"
                    refresh()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("bundle sync failed: ${exc.message}") }
            }
        }
    }

    private fun startFlight() {
        if (session.activeFlight != null) {
            show("a flight is already active; end it first")
            return
        }
        val bundleId = selectedBundleId ?: run {
            show("download a mission bundle first (Sync sites → Download bundle)")
            return
        }
        val bundle = session.store.missionBundle(bundleId) ?: run {
            show("selected bundle is not in the local store")
            return
        }
        try {
            val record = session.startFlightFromBundle(bundle)
            val segmentSeconds = bundle.get("mission_snapshot")
                ?.get("recording_policy")?.get("segment_seconds")?.asInt() ?: 300
            app.startFlightRecording(record.get("flight_id").asText(), segmentSeconds)
            show(
                "flight started: ${record.get("flight_id").asText().take(8)} " +
                    "(bundle ${bundleId.take(8)} pinned; prepare/apply run offline;" +
                    " 녹화=${segmentSeconds}s 구간, 벤치=레코더 없음)",
            )
        } catch (exc: Exception) {
            show("start failed: ${exc.message}")
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
            show("upload disabled: configure Core URL + token first")
            return
        }
        val spec = try {
            session.uploadSpec()
        } catch (exc: Exception) {
            show("upload refused: ${exc.message}")
            return
        }
        tvSync.text = "uploading…"
        session.bg.execute {
            try {
                val report = uploader.uploadFlight(spec)
                val lines = StringBuilder()
                lines.append("state=${report.uploadState} timeBasis=${report.timeBasis} " +
                    "apps=${report.applicationsUploaded} sessions=${report.sessionsUploaded} " +
                    "annotations=${report.annotationsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("boot ${boot.bootId.take(8)} ${boot.status} " +
                        "chunks=${boot.chunksSent} missing=${boot.missingArtifacts.size} " +
                        "synced=${boot.synced.size} " +
                        "receipt=${boot.receivedStartedUtc ?: "-"}\n")
                }
                if (report.sessionDrift.isNotEmpty()) {
                    lines.append("session drift: ${report.sessionDrift.size} (server holds older snapshot)\n")
                }
                if (report.boots.any { it.incompleteLocal.isNotEmpty() }) {
                    lines.append("미완료 로컬 증거 — 완성되면 다음 동기화에서 자동 재시도됨\n")
                }
                if (report.annotationFailures.isNotEmpty()) {
                    lines.append("annotation 실패(재시도 예정): " +
                        report.annotationFailures.joinToString().take(120) + "\n")
                }
                runOnUiThread {
                    hasSyncReport = true
                    tvSync.text = lines.toString()
                    refreshEvidence()
                }
            } catch (exc: Exception) {
                runOnUiThread { show("upload failed: ${exc.message}") }
            }
        }
    }

    private fun serverState() {
        val query = session.query()
        if (query == null) {
            show("upload disabled: configure Core URL + token first")
            return
        }
        val flight = session.activeFlight
        if (flight == null) {
            show("no active flight to query")
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
                        "server events=${serverEvents.items.size} (${serverEvents.source}, " +
                        "${if (serverEvents.source == EvidenceSource.LOCAL) "offline copy" else "fetched"})"
                }
            } catch (exc: Exception) {
                runOnUiThread { show("server query failed: ${exc.message}") }
            }
        }
    }

    private fun show(message: String) {
        tvError.text = message
    }
}
