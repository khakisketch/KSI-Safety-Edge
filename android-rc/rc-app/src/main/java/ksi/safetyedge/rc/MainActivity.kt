package ksi.safetyedge.rc

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ksi.rc.EvidenceSource

/**
 * A4 shell: four sections, every value read from the single [RcSession]
 * (real rc-core state). No screen keeps its own copy; an action that is
 * not valid from the current state shows the core's reason instead of
 * pretending. The demo feed is the only simulated input and is labeled.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var session: RcSession
    private lateinit var app: RcApp
    private var shownError: String? = null
    private var hasSyncReport = false

    private lateinit var tvMission: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvEvidence: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvError: TextView
    private lateinit var etCoreUrl: EditText
    private lateinit var etCoreToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RcApp
        session = app.session
        setContentView(R.layout.activity_main)

        val clockNote = findViewById<TextView>(R.id.tvClockNote)
        clockNote.text = if (app.edgeClock.verified) {
            "Clock anchor VERIFIED (H1 Edge sync)."
        } else {
            getString(R.string.clock_unverified)
        }

        tvMission = findViewById(R.id.tvMission)
        tvLink = findViewById(R.id.tvLink)
        tvEvidence = findViewById(R.id.tvEvidence)
        tvSync = findViewById(R.id.tvSync)
        tvError = findViewById(R.id.tvError)
        etCoreUrl = findViewById(R.id.etCoreUrl)
        etCoreToken = findViewById(R.id.etCoreToken)
        etCoreUrl.setText(app.config.coreUrl)
        etCoreToken.setText(app.config.coreToken)

        on(R.id.btnDemoBundle) {
            val bundle = session.newDemoBundle()
            show("demo bundle loaded: ${bundle.get("bundle_id").asText()}")
        }
        on(R.id.btnPrepare) { run("prepare") { session.controller?.prepare() } }
        on(R.id.btnApply) { run("apply") { session.controller?.apply() } }
        on(R.id.btnReconcile) { run("reconcile") { session.controller?.reconcile() } }
        on(R.id.btnStart) { run("start") { session.controller?.startSession() } }
        on(R.id.btnStop) { run("stop") { session.controller?.stopSession() } }

        on(R.id.btnConnect) { session.link.connect(); session.pumpOutbound(); refresh() }
        on(R.id.btnDisconnect) { session.link.disconnect(); refresh() }
        on(R.id.btnDemoTick) {
            val count = session.demoTick()
            show("SIMULATED tick: $count envelopes through real ingest")
        }
        on(R.id.btnRetry) {
            val sent = session.retryUnacked()
            show("re-sent $sent unacked envelope(s), same msg_id")
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

    override fun onDestroy() {
        // The background executor is app-scoped (RcSession.bg) so rotation
        // does not abort an in-flight upload; it dies with the process.
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
            refresh()
        }
    }

    private fun run(label: String, action: () -> Unit) {
        val active = session.controller
        if (active == null) {
            show("no bundle loaded (Demo bundle first)")
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
        val active = session.controller
        val bundleLine = if (active == null) {
            "bundle: none"
        } else {
            "bundle=${active.bundleId.take(8)} state=${session.missionSummary()}"
        }
        tvMission.text = "$bundleLine\nflight=${session.config.flightId.take(8)}"
        val endpoint = active?.endpoint
        val simulatedTag = if (session.link.isSimulated) " SIMULATED" else ""
        tvLink.text = "link=${session.link.state}$simulatedTag\n" +
            "in (this session): stored=${session.stored} dup=${session.duplicates} " +
            "failed=${session.failed} last=${session.lastInbound}\n" +
            "out: pending=${endpoint?.pendingCount() ?: 0} " +
            "acked=${endpoint?.ackedCount ?: 0}"
        refreshEvidence()
        val cfg = app.config
        val coreLine = if (cfg.coreConfigured) "core=${cfg.coreUrl}" else "core=NOT CONFIGURED (upload disabled)"
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
        val fid = session.config.flightId
        val store = session.store
        val events = store.eventsFor(fid)
        val observations = store.observationsFor(fid)
        val artifacts = store.artifactsFor(fid)
        val synced = artifacts.count { store.isArtifactSynced(it.get("artifact_id").asText()) }
        val lines = StringBuilder()
        lines.append("LOCAL observations=${observations.size} events=${events.size} ")
        lines.append("summaries=${store.summariesFor(fid).size}\n")
        for (artifact in artifacts) {
            val id = artifact.get("artifact_id").asText()
            val complete = store.artifactBytes(id) != null
            lines.append("artifact ${id.take(8)} size=${artifact.get("size_bytes").asLong()} " +
                "complete=$complete synced=${store.isArtifactSynced(id)}\n")
        }
        lines.append("artifacts=${artifacts.size} synced=$synced (SYNCED only after COMMITTED)")
        if (session.hasSimulatedRows()) {
            lines.append("\nWARNING: store holds SIMULATED-feed rows — they WILL upload")
        }
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
                lines.append("state=${report.uploadState} " +
                    "apps=${report.applicationsUploaded} sessions=${report.sessionsUploaded}\n")
                for (boot in report.boots) {
                    lines.append("boot ${boot.bootId.take(8)} ${boot.status} " +
                        "chunks=${boot.chunksSent} missing=${boot.missingArtifacts.size} " +
                        "synced=${boot.synced.size}\n")
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
        session.bg.execute {
            try {
                val api = ksi.rc.CoreApi(
                    AndroidCoreTransport(
                        app.config.coreUrl,
                        app.config.coreToken,
                    ),
                )
                val flight = api.getFlight(session.config.flightId)
                val uploadState = flight.get("upload_state")?.asText() ?: "UNKNOWN"
                val serverEvents = query.events(session.config.flightId)
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
