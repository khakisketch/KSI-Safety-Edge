package ksi.safetyedge.rc

import java.util.concurrent.atomic.AtomicReference
import ksi.rc.EvidenceIngest
import ksi.rc.EvidenceUploader
import ksi.rc.FileRcStore
import ksi.rc.FlightUploadSpec
import ksi.rc.Inbound
import ksi.rc.LocalFirstEvidence
import ksi.rc.MissionController
import ksi.rc.MissionState

/**
 * App-scoped RC session (A4): the single owner of all rc-core state.
 * Every screen reads and drives THIS object — no screen keeps its own
 * copy of mission/evidence/sync state, so the UI can never disagree with
 * the core.
 *
 * - store: FileRcStore under the app files dir (survives process death;
 *   evidence is never deleted here);
 * - controller: mission lifecycle (Prepare/Apply/Start/Stop + reconcile);
 * - bridge: endpoint outbox <-> DroneLink bytes, inbound counters;
 * - uploader/query: built on demand from the current Core config.
 *
 * Storage-full / IO failures surface as [lastError] text; the session
 * never marks or deletes anything it did not durably complete.
 */
class RcSession(
    storeDir: java.io.File,
    val config: AppConfig,
    val clock: EdgeClock,
    val link: DroneLink,
    initialBundle: com.fasterxml.jackson.databind.node.ObjectNode?,
) {
    val store = FileRcStore(storeDir.toPath())
    private val ingest = EvidenceIngest(store)

    var controller: MissionController? = null
        private set
    var bundle: com.fasterxml.jackson.databind.node.ObjectNode? = initialBundle
        private set

    var stored = 0; private set
    var duplicates = 0; private set
    var failed = 0; private set
    var lastInbound: String = "-"; private set
    var lastError: String? = null; private set

    private val demoFeed = DemoFeed(config.flightId, demoBootId(), config.missionRevisionId)
    private val demoArtifact = ByteArray(8192) { (it % 251).toByte() }

    init {
        bundle?.let { attachController(it) }
        link.setListener(object : DroneLink.Listener {
            override fun onBytes(bytes: ByteArray) = onLinkBytes(bytes)
            override fun onState(state: DroneLink.State) { /* pulled by UI */ }
        })
    }

    fun demoBootId(): String {
        val id = config.flightId
        require(id.length == 36) { "flight id is not a uuid" }
        return id.replaceRange(24, 36, "b007b007b007")
    }

    fun attachController(bundle: com.fasterxml.jackson.databind.node.ObjectNode) {
        this.bundle = bundle
        controller = MissionController(
            config.flightId, bundle, store,
            { android.os.SystemClock.elapsedRealtimeNanos() }, ingest,
        )
        lastError = null
    }

    fun newDemoBundle(): com.fasterxml.jackson.databind.node.ObjectNode {
        // Best effort: flush the previous endpoint's queue before swapping
        // (queued control bytes belong to the old session).
        pumpOutbound()
        val bundle = demoFeed.demoBundle(
            java.util.UUID.randomUUID().toString(), config.siteId,
        )
        attachController(bundle)
        return bundle
    }

    /** Outbound: drain endpoint outbox into the link (unsent stays queued).
     *  Returns how many envelopes actually left the device. */
    fun pumpOutbound(): Int {
        val endpoint = controller?.endpoint ?: return 0
        var sent = 0
        while (true) {
            val env = endpoint.outbox.firstOrNull() ?: break
            val bytes = ksi.rc.Mop.json.writeValueAsBytes(env)
            if (!link.send(bytes)) break
            endpoint.outbox.removeFirst()
            sent += 1
        }
        return sent
    }

    /** Outbound retry: re-send unacked envelopes (same msg_id, same bytes). */
    fun retryUnacked(): Int {
        var sent = 0
        val endpoint = controller?.endpoint ?: return 0
        for (env in endpoint.retransmissions()) {
            if (link.send(ksi.rc.Mop.json.writeValueAsBytes(env))) sent += 1
            else break
        }
        return sent
    }

    /** Inbound: Edge/link bytes through the real endpoint ingest path. */
    fun onLinkBytes(bytes: ByteArray) {
        val endpoint = controller?.endpoint ?: run {
            lastError = "no mission controller (load a bundle first)"
            return
        }
        // String(bytes, UTF_8) never throws (malformed input becomes U+FFFD);
        // a non-envelope then fails honestly in verifyEnvelope below.
        when (val outcome = endpoint.onRawEnvelope(String(bytes, Charsets.UTF_8))) {
            is Inbound.Stored -> { stored += 1; lastInbound = outcome.type }
            is Inbound.Duplicate -> { duplicates += 1; lastInbound = "dup:${outcome.type}" }
            is Inbound.Acked -> { lastInbound = "ack:${outcome.ackOf.take(8)}" }
            is Inbound.Failed -> {
                failed += 1
                lastInbound = "failed:${outcome.type}"
                lastError = outcome.reason
            }
        }
    }

    /** One SIMULATED Edge tick through the real ingest path (dev only). */
    fun demoTick(): Int {
        var count = 0
        for (bytes in demoFeed.tick(demoArtifact)) {
            onLinkBytes(bytes)
            count += 1
        }
        pumpOutbound()
        return count
    }

    fun uploadSpec(): FlightUploadSpec {
        val active = controller
            ?: throw IllegalStateException("load a bundle first")
        active.lastApplication
            ?: throw IllegalStateException("apply the bundle first (no APPLIED application)")
        // The mission digest is resolved from the server's mission
        // snapshot — never invented locally. The configured revision must
        // match, otherwise the manifest would certify the wrong mission.
        val api = ksi.rc.CoreApi(
            AndroidCoreTransport(config.coreUrl, config.coreToken),
        )
        val snapshot = api.getMissionSnapshot(config.missionId)
        val snapshotRev = snapshot.get("mission_revision_id")?.asText()
        if (snapshotRev != config.missionRevisionId) {
            throw IllegalStateException(
                "mission revision mismatch: app=${config.missionRevisionId.take(8)} " +
                    "server=$snapshotRev (A5 mission download pending)",
            )
        }
        val missionSha = snapshot.get("content_sha256")?.asText()
            ?: throw IllegalStateException("server snapshot has no content_sha256")
        return FlightUploadSpec(
            flightId = config.flightId,
            siteId = config.siteId,
            rcDeviceId = config.rcDeviceId,
            missionId = config.missionId,
            missionRevisionId = config.missionRevisionId,
            missionSha256 = missionSha,
            bundleSha256 = active.bundleSha,
        )
    }

    fun uploader(): EvidenceUploader? {
        if (!config.coreConfigured) return null
        val transport = AndroidCoreTransport(config.coreUrl, config.coreToken)
        return EvidenceUploader(ksi.rc.CoreApi(transport), store, clock::toUtc)
    }

    fun query(): LocalFirstEvidence? {
        if (!config.coreConfigured) return null
        val transport = AndroidCoreTransport(config.coreUrl, config.coreToken)
        return LocalFirstEvidence(store, ksi.rc.CoreApi(transport))
    }

    fun clearError() {
        lastError = null
    }

    /** True when the store holds SIMULATED-feed rows (they are real store
     *  rows and WILL upload — the operator must know before syncing). */
    fun hasSimulatedRows(): Boolean {
        val fid = config.flightId
        return (store.observationsFor(fid) + store.eventsFor(fid)).any { node ->
            node.get("quality_reasons")?.any {
                it.asText() == "SIMULATED_FEED"
            } == true
        }
    }

    /** App-scoped background work (upload/query): survives rotation, unlike
     *  an activity-scoped executor. Shut down only with the process. */
    val bg: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    fun missionSummary(): String {
        val active = controller ?: return "no bundle loaded"
        val state = active.state
        val extra = when (state) {
            MissionState.APPLIED -> "app=${active.lastApplication?.get("application_id")?.asText()}"
            MissionState.RUNNING, MissionState.STOPPED, MissionState.SESSION_PENDING ->
                "session=${active.sessionId} active=${active.sessionActive} " +
                    "edgeRunning=${active.edgeReportedRunning}"
            else -> ""
        }
        return "$state $extra transitions=${active.transitions.size}"
    }
}
