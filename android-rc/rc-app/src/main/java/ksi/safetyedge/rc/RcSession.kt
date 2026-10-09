package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
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
    initialBundle: ObjectNode? = null,
) {
    val store = FileRcStore(storeDir.toPath(), wallClock)
    private val ingest = EvidenceIngest(store)

    var controller: MissionController? = null
        private set
    var bundle: ObjectNode? = initialBundle
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
        if (initialBundle != null && store.activeFlight() != null) {
            attachController(
                initialBundle, store.activeFlight()!!.get("flight_id").asText(),
            )
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

    fun uploader(): EvidenceUploader? {
        val (url, token) = coreConfig() ?: return null
        return EvidenceUploader(ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store)
    }

    fun query(): LocalFirstEvidence? {
        val (url, token) = coreConfig() ?: return null
        return LocalFirstEvidence(store, ksi.rc.CoreApi(AndroidCoreTransport(url, token)))
    }

    /** Spec built ONLY from the active flight record — no invented ids and
     *  no server round-trip; digests came in with the flight's mission. */
    fun uploadSpec(): FlightUploadSpec {
        val active = store.activeFlight()
            ?: throw IllegalStateException("no active flight")
        return FlightUploadSpec(
            flightId = active.get("flight_id").asText(),
            siteId = active.get("site_id").asText(),
            rcDeviceId = rcDeviceId,
            missionId = active.get("mission_id").asText(),
            missionRevisionId = active.get("mission_revision_id").asText(),
            missionSha256 = active.get("mission_sha256").asText(),
            bundleSha256 = active.get("bundle_sha256").asText(),
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
