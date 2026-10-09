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
    private lateinit var tvLink: TextView
    private lateinit var tvDemo: TextView
    private lateinit var tvEvidence: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvError: TextView
    private lateinit var etCoreUrl: EditText
    private lateinit var etCoreToken: EditText

    private var shownError: String? = null
    private var hasSyncReport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RcApp
        session = app.session
        demo = app.demoSession
        setContentView(R.layout.activity_main)

        tvMission = findViewById(R.id.tvMission)
        tvFlight = findViewById(R.id.tvFlight)
        tvLink = findViewById(R.id.tvLink)
        tvDemo = findViewById(R.id.tvDemo)
        tvEvidence = findViewById(R.id.tvEvidence)
        tvSync = findViewById(R.id.tvSync)
        tvError = findViewById(R.id.tvError)
        etCoreUrl = findViewById(R.id.etCoreUrl)
        etCoreToken = findViewById(R.id.etCoreToken)
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)

        // -- operational mission (A5: bundle attach comes from the synced
        //    mission store; until then the controller shows "not attached").
        on(R.id.btnPrepare) { run("prepare") { session.controller?.prepare() } }
        on(R.id.btnApply) { run("apply") { session.controller?.apply() } }
        on(R.id.btnReconcile) { run("reconcile") { session.controller?.reconcile() } }
        on(R.id.btnStart) { run("start") { session.controller?.startSession() } }
        on(R.id.btnStop) { run("stop") { session.controller?.stopSession() } }
        on(R.id.btnEndFlight) {
            val ended = session.endFlight()
            show(if (ended != null) "flight ended: ${ended.get("flight_id").asText().take(8)}" else "no active flight")
        }

        // -- operational link
        on(R.id.btnConnect) { session.link.connect(); session.pumpOutbound(); refresh() }
        on(R.id.btnDisconnect) { session.link.disconnect(); refresh() }
        on(R.id.btnRetry) {
            val sent = session.retryUnacked()
            show("re-sent $sent unacked envelope(s), same msg_id")
        }

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

        on(R.id.btnSaveCore) {
            app.config.coreUrl = etCoreUrl.text.toString()
            app.config.coreToken = etCoreToken.text.toString()
            hasSyncReport = false
            show(
                if (app.config.coreConfigured) "Core config saved"
                else "upload disabled: Core URL + token required",
            )
            refresh()
        }
        on(R.id.btnUpload) { uploadNow() }
        on(R.id.btnServerState) { serverState() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        tvMission.text = "controller: ${session.missionSummary()}"
        tvFlight.text = if (flight == null) {
            "flight: none active — A5: sync a mission, then start a flight"
        } else {
            "flight=${flight.get("flight_id").asText().take(8)} " +
                "state=${flight.get("state").asText()} " +
                "missionRev=${flight.get("mission_revision_id").asText().take(8)} " +
                "bundle=${flight.get("bundle_sha256").asText().take(8)}\n" +
                "started(receipt)=${flight.get("started_at_utc").asText()}"
        }

        val endpoint = session.controller?.endpoint
        val simulatedTag = if (session.link.isSimulated) " SIMULATED" else ""
        tvLink.text = "link=${session.link.state}$simulatedTag\n" +
            "in (this session): stored=${session.stored} dup=${session.duplicates} " +
            "failed=${session.failed} last=${session.lastInbound}\n" +
            "out: pending=${endpoint?.pendingCount() ?: 0} " +
            "acked=${endpoint?.ackedCount ?: 0}"

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
                    "apps=${report.applicationsUploaded} sessions=${report.sessionsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("boot ${boot.bootId.take(8)} ${boot.status} " +
                        "chunks=${boot.chunksSent} missing=${boot.missingArtifacts.size} " +
                        "synced=${boot.synced.size} " +
                        "receipt=${boot.receivedStartedUtc ?: "-"}\n")
                }
                if (report.sessionDrift.isNotEmpty()) {
                    lines.append("session drift: ${report.sessionDrift.size} (server holds older snapshot)\n")
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
