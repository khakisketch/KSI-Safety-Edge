package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import ksi.rc.CanonicalJson
import ksi.rc.EvidenceIngest
import ksi.rc.EvidenceUploader
import ksi.rc.FileRcStore
import ksi.rc.FlightUploadSpec
import ksi.rc.Inbound
import ksi.rc.LocalFirstEvidence
import ksi.rc.MissionController

/**
 * App-scoped real-flight RC session (A5 fix): the single owner of all
 * rc-core state for OPERATIONAL data. It no longer carries any demo feed —
 * simulated data lives in its own store ([DemoSession]).
 *
 * - `rcDeviceId` is install-scoped; a FLIGHT is started with a new id and
 *   pins the mission/bundle/zone revisions of that flight (immutable);
 * - the store stamps RC receipt times (the only honest UTC we have — Edge
 *   monotonic is a different device domain and is never converted);
 * - an unfinished flight is restored from the store after a restart;
 * - upload/query are available only when a Core endpoint is configured,
 *   and uploads require an active flight record.
 */
class RcSession(
    storeDir: File,
    val rcDeviceId: String,
    val wallClock: () -> String,
    val link: DroneLink,
    /** (url, token) when the operator configured a Core; null = no upload. */
    private val coreConfig: () -> Pair<String, String>?,
) {
    val store = FileRcStore(storeDir.toPath(), wallClock)
    private val ingest = EvidenceIngest(store)

    var controller: MissionController? = null
        private set
    var bundle: ObjectNode? = null
        private set

    var stored = 0; private set
    var duplicates = 0; private set
    var failed = 0; private set
    var lastInbound: String = "-"; private set
    var lastError: String? = null; private set

    /** App-scoped background work (upload/query): survives rotation. */
    val bg: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()

    init {
        // Restart restore: an unfinished flight whose bundle is in the local
        // mission store re-attaches its controller automatically. NOTE: the
        // controller itself always starts at IDLE with lastApplication=null
        // (in-memory); a flight whose record says APPLIED keeps that record
        // (syncFlightState only advances STARTED->APPLIED), and the operator
        // re-runs Prepare/Apply per the re-apply-after-reboot contract.
        store.activeFlight()?.let { active ->
            store.missionBundle(active.get("bundle_id").asText())?.let { stored ->
                attachController(stored, active.get("flight_id").asText())
            }
        }
        link.setListener(object : DroneLink.Listener {
            override fun onBytes(bytes: ByteArray) = onLinkBytes(bytes)
            override fun onState(state: DroneLink.State) { /* pulled by UI */ }
        })
    }

    // -- flight lifecycle ------------------------------------------------------

    val activeFlight: ObjectNode? get() = store.activeFlight()

    /**
     * Starts a NEW flight (new flight id) pinned to the given mission /
     * bundle / zone revisions. Callers must hold the verified digests
     * (mission store / sync layer); nothing is invented here.
     */
    fun startFlight(
        siteId: String,
        missionId: String,
        missionRevisionId: String,
        missionSha256: String,
        bundleId: String,
        bundleSha256: String,
        zoneRevisionIds: List<String>,
    ): ObjectNode {
        check(store.activeFlight() == null) {
            "a flight is already active; end it before starting a new one"
        }
        val record = ksi.rc.Mop.json.createObjectNode()
        record.put("schema_version", 1)
        record.put("flight_id", UUID.randomUUID().toString())
        record.put("site_id", siteId)
        record.put("mission_id", missionId)
        record.put("mission_revision_id", missionRevisionId)
        record.put("mission_sha256", missionSha256)
        record.put("bundle_id", bundleId)
        record.put("bundle_sha256", bundleSha256)
        val zones = record.putArray("zone_revision_ids")
        zoneRevisionIds.forEach { zones.add(it) }
        record.put("started_at_utc", wallClock())
        record.putNull("ended_at_utc")
        record.put("state", "STARTED")
        store.startFlight(record)
        return store.flight(record.get("flight_id").asText())!!
    }

    fun endFlight(): ObjectNode? {
        val active = store.activeFlight() ?: return null
        val flightId = active.get("flight_id").asText()
        store.updateFlight(flightId, "ENDED", wallClock())
        return store.flight(flightId)
    }

    /** STARTED -> APPLIED once the Edge confirmed the pinned bundle. */
    fun syncFlightState() {
        val active = store.activeFlight() ?: return
        if (active.get("state")?.asText() != "STARTED") return
        val pinnedSha = active.get("bundle_sha256")?.asText() ?: return
        val application = controller?.lastApplication ?: return
        if (application.get("bundle_sha256")?.asText() == pinnedSha) {
            store.updateFlight(active.get("flight_id").asText(), "APPLIED", null)
        }
    }

    // -- mission controller / link ----------------------------------------------

    fun attachController(bundle: ObjectNode, flightId: String) {
        this.bundle = bundle
        controller = MissionController(
            flightId, bundle, store,
            { android.os.SystemClock.elapsedRealtimeNanos() }, ingest,
        )
        lastError = null
    }

    /** Outbound: drain endpoint outbox into the link (unsent stays queued). */
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

    /** Outbound retry: re-send unacked envelopes that were ALREADY sent
     *  (same msg_id, same bytes). Envelopes still waiting in the outbox are
     *  skipped — they go out via [pumpOutbound] exactly once. */
    fun retryUnacked(): Int {
        var sent = 0
        val endpoint = controller?.endpoint ?: return 0
        val pendingIds = endpoint.outbox.map { it.get("msg_id").asText() }.toSet()
        for (env in endpoint.retransmissions()) {
            if (env.get("msg_id").asText() in pendingIds) continue
            if (link.send(ksi.rc.Mop.json.writeValueAsBytes(env))) sent += 1
            else break
        }
        return sent
    }

    /** A8 outbound retry tick (timer-driven in the app): first sends what
     *  was never sent, then re-sends already-sent-but-unacked envelopes —
     *  the same envelope is never double-sent within one tick. Never drops
     *  bytes on a dead link. */
    fun retransmitTick(): Int {
        if (link.state != DroneLink.State.CONNECTED) return 0
        val endpoint = controller?.endpoint ?: return 0
        val freshIds = endpoint.outbox.map { it.get("msg_id").asText() }.toSet()
        var sent = pumpOutbound()
        for (env in endpoint.retransmissions()) {
            if (env.get("msg_id").asText() in freshIds) continue
            if (link.send(ksi.rc.Mop.json.writeValueAsBytes(env))) sent += 1
            else break
        }
        return sent
    }

    /** Inbound: Edge/link bytes through the real endpoint ingest path. */
    fun onLinkBytes(bytes: ByteArray) {
        val endpoint = controller?.endpoint ?: run {
            lastError = "no mission controller (start a flight first)"
            return
        }
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

    fun clearError() {
        lastError = null
    }

    // -- upload / query -----------------------------------------------------------

    fun coreConfigured(): Boolean = coreConfig() != null

    /** A5 mission synchronizer (null when no Core is configured). */
    fun missionSync(): ksi.rc.MissionSync? {
        val (url, token) = coreConfig() ?: return null
        return ksi.rc.MissionSync(
            ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store,
        )
    }

    /**
     * Starts a flight pinned to a DIGEST-VERIFIED local bundle (the sync
     * layer stored it) and attaches the mission controller. Everything
     * after this point (Prepare/Apply) needs no network.
     */
    fun startFlightFromBundle(bundle: ObjectNode): ObjectNode {
        // Defensive: only digest-verified bundles may be pinned (today the
        // caller always passes a store-verified record; this closes the
        // path structurally).
        check(CanonicalJson.verifyDigest(bundle, "bundle_sha256")) {
            "bundle digest mismatch; refusing to pin"
        }
        val snapshot = bundle.get("mission_snapshot") as? ObjectNode
            ?: throw IllegalStateException("bundle is missing its mission snapshot")
        check(CanonicalJson.verifyDigest(snapshot, "content_sha256")) {
            "embedded snapshot digest mismatch; refusing to pin"
        }
        val zoneIds = mutableListOf<String>()
        snapshot.get("zones")?.forEach { zone ->
            zone.get("revision_id")?.asText()?.let { zoneIds.add(it) }
        }
        val record = startFlight(
            siteId = bundle.get("site_id").asText(),
            missionId = snapshot.get("mission_id").asText(),
            missionRevisionId = snapshot.get("mission_revision_id").asText(),
            missionSha256 = snapshot.get("content_sha256").asText(),
            bundleId = bundle.get("bundle_id").asText(),
            bundleSha256 = bundle.get("bundle_sha256").asText(),
            zoneRevisionIds = zoneIds,
        )
        attachController(bundle, record.get("flight_id").asText())
        return record
    }

    fun uploader(): EvidenceUploader? {
        val (url, token) = coreConfig() ?: return null
        return EvidenceUploader(ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store)
    }

    fun query(): LocalFirstEvidence? {
        val (url, token) = coreConfig() ?: return null
        return LocalFirstEvidence(store, ksi.rc.CoreApi(AndroidCoreTransport(url, token)))
    }

    /** A8: reachability-gated, resumable upload for one flight. */
    fun syncCoordinator(): ksi.rc.SyncCoordinator? {
        val (url, token) = coreConfig() ?: return null
        val core = ksi.rc.CoreApi(AndroidCoreTransport(url, token))
        return ksi.rc.SyncCoordinator(core, ksi.rc.EvidenceUploader(core, store))
    }

    /** Spec built ONLY from the flight record — no invented ids and no
     *  server round-trip; digests came in with the flight's mission. */
    fun uploadSpec(): FlightUploadSpec {
        val active = store.activeFlight()
            ?: throw IllegalStateException("no active flight")
        return uploadSpec(active.get("flight_id").asText())
    }

    fun uploadSpec(flightId: String): FlightUploadSpec {
        val record = store.flight(flightId)
            ?: throw IllegalStateException("no such flight: $flightId")
        return FlightUploadSpec(
            flightId = record.get("flight_id").asText(),
            siteId = record.get("site_id").asText(),
            rcDeviceId = rcDeviceId,
            missionId = record.get("mission_id").asText(),
            missionRevisionId = record.get("mission_revision_id").asText(),
            missionSha256 = record.get("mission_sha256").asText(),
            bundleSha256 = record.get("bundle_sha256").asText(),
        )
    }

    fun missionSummary(): String {
        val active = controller ?: return "no bundle attached"
        val state = active.state
        val extra = when (state) {
            ksi.rc.MissionState.APPLIED ->
                "app=${active.lastApplication?.get("application_id")?.asText()}"
            ksi.rc.MissionState.RUNNING, ksi.rc.MissionState.STOPPED,
            ksi.rc.MissionState.SESSION_PENDING ->
                "session=${active.sessionId} active=${active.sessionActive} " +
                    "edgeRunning=${active.edgeReportedRunning}"
            else -> ""
        }
        return "$state $extra transitions=${active.transitions.size}"
    }
}

/** Device wall clock used as the RC receipt clock (store stamping only). */
object ReceiptClock {
    fun now(): String = Instant.now().toString()
}
