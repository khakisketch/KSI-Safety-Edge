package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import ksi.rc.CanonicalJson
import ksi.rc.EvidenceIngest
import ksi.rc.EvidenceUploader
import ksi.rc.ExecutionFacts
import ksi.rc.FileRcStore
import ksi.rc.FlightUploadSpec
import ksi.rc.Inbound
import ksi.rc.LocalBundleRequest
import ksi.rc.LocalBundleResult
import ksi.rc.LocalFirstEvidence
import ksi.rc.MissionController
import ksi.rc.OperatorActionArtifacts
import ksi.rc.OperatorActionLog
import ksi.rc.OperatorResponseSync
import ksi.rc.PendingSiteJournal
import ksi.rc.PendingSiteSync
import ksi.rc.PendingSyncReport
import ksi.rc.RcFullStore
import ksi.rc.SessionFacts
import ksi.rc.SessionFactsResolver
import ksi.rc.SessionResultLog
import ksi.rc.SessionResultRecord
import ksi.rc.SessionVerdict
import ksi.rc.UnavailableRcStore

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
    /**
     * Monotonic-ns clock for the live-status poll ([MissionController.pollTick]).
     * Defaults to the device elapsed-realtime clock; injectable so host tests
     * can advance time deterministically.
     */
    private val clockNs: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
) {
    /**
     * Durable KSI store. Normally the real [FileRcStore] (degraded app open:
     * readable facts retained, writes fail closed); an [UnavailableRcStore]
     * when even construction throws (no facts readable). Construction NEVER
     * throws for storage I/O — [storageAvailable] gates every KSI path
     * while the physical link/video seams stay usable.
     */
    val store: RcFullStore

    /** Captured durable-open failure, or null when KSI persistence is up. */
    val storageError: java.io.IOException?

    /** False when local KSI persistence/observation is unusable — including
     *  a post-open poisoned write path (banner stays until explicit
     *  repair). */
    val storageAvailable: Boolean
        get() = storageError == null &&
            (store as? FileRcStore)?.storageUnavailable == false

    /** Recorded failure detail: the open error, else a post-open poison. */
    val storageErrorMessage: String?
        get() = storageError?.message
            ?: (store as? FileRcStore)?.storageOpenError?.message

    /** App-surface storage status (banner source for the UI). */
    val storageStatus: StorageStatus
        get() = if (storageAvailable) {
            StorageHealth.available()
        } else {
            StorageHealth.unavailable(storageErrorMessage)
        }

    /**
     * Fail-closed gate for every KSI persistence/write path: throws the
     * captured storage error (never a silent no-op, never a fabricated
     * write) so callers observe the refusal explicitly. No ACK, no commit,
     * no id may come out of an unavailable store.
     */
    private fun requireStoreAvailable() {
        val err = storageError ?: (store as? FileRcStore)?.storageOpenError
        err?.let {
            throw java.io.IOException(
                "${StorageHealth.UNAVAILABLE_BANNER} · ${it.message}",
                it,
            )
        }
    }

    private data class DurableOpen(
        val store: RcFullStore,
        val verdicts: SessionResultLog,
        val operatorActions: OperatorActionLog,
        val closeouts: CloseoutLog,
        val intents: SessionIntentLog,
        val pendingSites: PendingSiteJournal,
        val prepareAttempts: ksi.rc.PrepareAttemptJournal,
        val error: java.io.IOException?,
    )

    /**
     * Opens every durable log without ever throwing for storage I/O (the
     * fault-containment contract): a corrupt counter or unusable root
     * degrades to [storageError] with retained facts, never a whole-app
     * crash. Unavailable holders allocate NOTHING — no temp dir, no mkdir,
     * no reads; their empty reads are only ever shown behind
     * [storageAvailable], and every one of their writes throws. Partially
     * readable facts stay on the real read-only store.
     */
    private fun openDurable(storeDir: File, wallClock: () -> String): DurableOpen {
        var error: java.io.IOException? = null
        fun noteFailure(exc: Exception, what: String): java.io.IOException {
            val io = if (exc is java.io.IOException) {
                exc
            } else {
                java.io.IOException(
                    "$what unavailable (${exc.message}); KSI persistence disabled",
                    exc,
                )
            }
            if (error == null) error = io
            return io
        }
        val store: RcFullStore = try {
            val opened = FileRcStore.openForApp(storeDir.toPath(), wallClock)
            opened.storageOpenError?.let { noteFailure(it, "local store") }
            opened
        } catch (exc: Exception) {
            UnavailableRcStore(noteFailure(exc, "local store"))
        }
        val verdicts = try {
            SessionResultLog(storeDir.toPath(), wallClock)
        } catch (exc: Exception) {
            SessionResultLog.unavailable(noteFailure(exc, "session verdict log"), wallClock)
        }
        val operatorActions = try {
            OperatorActionLog(storeDir.toPath())
        } catch (exc: Exception) {
            OperatorActionLog.unavailable(noteFailure(exc, "operator action log"))
        }
        val closeouts = try {
            CloseoutLog(storeDir.toPath(), wallClock)
        } catch (exc: Exception) {
            CloseoutLog.unavailable(noteFailure(exc, "close-out log"), wallClock)
        }
        val intents = try {
            SessionIntentLog(storeDir.toPath(), wallClock)
        } catch (exc: Exception) {
            SessionIntentLog.unavailable(noteFailure(exc, "session start-intent log"), wallClock)
        }
        val pendingSites = try {
            PendingSiteJournal(storeDir.toPath(), wallClock)
        } catch (exc: Exception) {
            PendingSiteJournal.unavailable(
                noteFailure(exc, "pending site journal"), wallClock,
            )
        }
        val prepareAttempts = try {
            ksi.rc.PrepareAttemptJournal(storeDir.toPath(), wallClock)
        } catch (exc: Exception) {
            ksi.rc.PrepareAttemptJournal.unavailable(
                noteFailure(exc, "prepare attempt journal"), wallClock,
            )
        }
        return DurableOpen(store, verdicts, operatorActions, closeouts, intents, pendingSites, prepareAttempts, error)
    }

    /**
     * Append-only log of locally decided session verdicts ([SessionVerdict]):
     * facts in, persisted record out. COMMITTED/upload state is never an
     * input — a preserved FAILED session still reads FAILED.
     */
    val verdicts: SessionResultLog

    /** Durable close-out records (contract §6 closeout). */
    val closeouts: CloseoutLog

    /** Durable session-start intents (restart authority, never assumed). */
    val intents: SessionIntentLog

    /** Durable preparation attempts (restart visibility, never auto-resume). */
    val prepareAttempts: ksi.rc.PrepareAttemptJournal

    /**
     * Durable pending-site drafts (offline-first registration): created
     * with no network, uploaded later via [pendingSiteSync]. Reads are
     * empty only behind [storageAvailable]; every write throws when the
     * journal is unavailable.
     */
    val pendingSites: PendingSiteJournal

    /**
     * Drone-link state exposure for the UI (edge-operation contract §5):
     * sits between the link and the session's transport pump. Records
     * every transition/byte; the UI reads [linkMonitor], never guesses.
     */
    val linkMonitor = LinkStateMonitor()

    /**
     * Session-interruption evidence (contract §5/§6): a real drone-link drop
     * (CONNECTED -> lost) reaches the controller as [MissionController
     * .noteLinkSnapshot]; a regained link is not evidence of anything and
     * fires nothing. Keeps the callback fast (a flag set, never I/O).
     */
    private val linkLossForwarder = LinkLossForwarder(linkMonitor) {
        controller?.noteLinkSnapshot(true)
    }

    /**
     * Single source of truth for operator responses: the same
     * `operator_actions.ndjson` file the uploader ships as a FLIGHT_LOG
     * artifact. No response UI exists yet — when it lands it must write here
     * (with the active flight's session id) so the uploaded log is exactly
     * what the operator entered.
     */
    val operatorActions: OperatorActionLog
    private val ingest: EvidenceIngest

    var controller: MissionController? = null
        private set
    var bundle: ObjectNode? = null
        private set
    /**
     * Provisional flight id minted at 준비 (see [prepareBundle]): the same
     * id the later 기록 시작 reuses as the flight record's `flight_id`
     * (one UUID end-to-end, never an id swap mid-flow). In-memory only —
     * it survives rotation (this session is app-scoped) but NEVER process
     * death: the restart path re-attaches ONLY from a stored ACTIVE flight
     * record, so a prepared-but-never-recorded bundle can never resurrect
     * as a phantom flight. Null once consumed by [startRecordingFromPrepared]
     * or cleared by [abandonPrepared].
     */
    var pendingFlightId: String? = null
        private set

    var stored = 0; private set
    var duplicates = 0; private set
    var failed = 0; private set
    var lastInbound: String = "-"; private set
    var lastError: String? = null; private set

    /** Optional foreground UI observer; callback must be thread-safe and fast. */
    @Volatile
    private var uiStateListener: (() -> Unit)? = null

    fun setUiStateListener(listener: (() -> Unit)?) {
        uiStateListener = listener
    }

    /** App-scoped background work (upload/query): survives rotation. */
    val bg: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()

    /** Guards MopEndpoint.outbox/unacked: the retransmission TIMER runs on
     *  a background executor while button actions and inbound bytes run on
     *  the UI thread — unsynchronized mutation could crash or lose sends. */
    private val outboundLock = Any()

    init {
        val opened = openDurable(storeDir, wallClock)
        store = opened.store
        verdicts = opened.verdicts
        operatorActions = opened.operatorActions
        closeouts = opened.closeouts
        intents = opened.intents
        pendingSites = opened.pendingSites
        prepareAttempts = opened.prepareAttempts
        storageError = opened.error
        ingest = EvidenceIngest(store)
        if (!storageAvailable) {
            // Degraded: no auto-attach, no auto-resume, no new session from
            // unverified state — the operator sees the banner and the
            // physical link/video seams stay usable.
            lastError = StorageHealth.UNAVAILABLE_BANNER
        } else {
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
        }
        // Link-loss forwarding sits between the link and the transport pump:
        // the monitor records transitions/bytes, the forwarder turns a real
        // drop into session-interruption evidence, and the pump below still
        // receives every byte/state as before.
        linkMonitor.attach(link, downstream = object : DroneLink.Listener {
            override fun onBytes(bytes: ByteArray) {
                // Hand off to the ingest worker: the reader thread must stay
                // fast (the link's contract), and durable writes on it stall
                // the transport.
                enqueueInbound(bytes)
                uiStateListener?.invoke()
            }

            override fun onState(state: DroneLink.State) {
                uiStateListener?.invoke()
            }
        })
    }

    // -- flight lifecycle ------------------------------------------------------

    /** Poison pill for the ingest worker. */
    private companion object {
        val INBOUND_SHUTDOWN = ByteArray(0)
    }

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
        requireStoreAvailable()
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

    /** STARTED -> APPLIED once the Edge confirmed the pinned bundle.
     *  Explicit no-record case: a prepared/applied bundle WITHOUT a flight
     *  record (준비 → 적용 before 기록 시작) is prepared-bundle state, NOT
     *  a flight — there is no row to advance and none is fabricated. */
    fun syncFlightState() {
        if (!storageAvailable) return
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
        requireStoreAvailable()
        this.bundle = bundle
        controller = MissionController(
            flightId, bundle, store,
            { android.os.SystemClock.elapsedRealtimeNanos() }, ingest,
        )
        lastError = null
    }

    /** In-memory unsent requests for THIS plan, not envelope ACKs or response proof. */
    fun unsentMissionRequestTypes(selectedBundleSha: String?): Set<String> = synchronized(outboundLock) {
        val attached = controller ?: return@synchronized emptySet()
        if (selectedBundleSha == null || selectedBundleSha != attached.bundleSha) {
            return@synchronized emptySet()
        }
        attached.endpoint.outbox.mapNotNull { it.get("type")?.asText() }
            .filter { it in setOf("MISSION_PREPARE", "MISSION_APPLY", "SESSION_START") }.toSet()
    }

    /** Outbound: drain endpoint outbox into the link (unsent stays queued). */
    fun pumpOutbound(): Int = synchronized(outboundLock) {
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
    fun retryUnacked(): Int = synchronized(outboundLock) {
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

    /**
     * UI-thread-safe pump (A9-4): the caller runs state checks and command
     * queueing on its own thread, then hands ONLY the blocking socket
     * write to [bg]. Android throws NetworkOnMainThreadException for
     * socket writes on the UI thread — the envelope already reached the
     * Edge via the timer retry while the tap reported refusal, which is
     * exactly the "거부: null" class of bug this removes. The outcome
     * posts to [onDone] on the bg thread (the caller forwards to UI);
     * a throwing link surfaces as a failure value, never as a crash of
     * the shared executor. Same-msg_id retry semantics are unchanged —
     * no new commands are invented here.
     */
    fun pumpOutboundAsync(onDone: (Result<Int>) -> Unit) {
        bg.execute {
            val result = runCatching { pumpOutbound() }
            runCatching { onDone(result) }
        }
    }

    /**
     * UI-thread-safe retry + pump (the 재전송 tap shape): re-sends
     * already-sent-but-unacked envelopes under the same msg_id, then
     * drains the outbox — the same combination [retransmitTick] runs on
     * the timer. Same threading contract as [pumpOutboundAsync].
     */
    fun flushOutboundAsync(onDone: (Result<Int>) -> Unit) {
        bg.execute {
            val result = runCatching { retryUnacked() + pumpOutbound() }
            runCatching { onDone(result) }
        }
    }

    /** A8 outbound retry tick (timer-driven in the app): first sends what
     *  was never sent, then re-sends already-sent-but-unacked envelopes —
     *  the same envelope is never double-sent within one tick. Never drops
     *  bytes on a dead link.
     *
     *  The same 1s timer tick also drives the live-status poll
     *  ([MissionController.pollTick], off the UI thread): while a session is
     *  in flight the RC asks the Edge for MISSION_STATUS every 2s so the
     *  displayed runtime state goes live without the operator doing
     *  anything. The poll is bounded by its own interval gate and never
     *  advances the mission state machine. */
    fun retransmitTick(): Int = synchronized(outboundLock) {
        controller?.let { c ->
            val activeId = activeFlight?.get("flight_id")?.asText()
            val unresolved = activeId != null && (
                hasOpenStartIntent(activeId) ||
                    latestCloseout(activeId)?.outcome == CloseoutRecord.UNRESOLVED
                ) && c.boundRuntimeStatus?.state != ksi.rc.EdgeRuntimeState.STOPPED
            runCatching { c.pollTick(clockNs(), reconcileUnresolved = unresolved) }
        }
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

    /**
     * Inbound processing runs OFF the link's reader thread.
     *
     * The ingest path is DURABLE (an fsync per envelope). Doing that on the
     * reader thread stalls the transport: the socket buffer fills, the
     * Edge's writes block, and the heartbeat freshness — measured on the
     * RC's own receipt clock — fell a minute behind, so the screen said
     * "하트비트 지연" while the link was healthy. The reader now only
     * ENQUEUES; one worker keeps the order (the endpoint is not
     * thread-safe) and the backlog is bounded. An overflow is counted and
     * dropped: the Edge retransmits, so at-least-once still holds.
     */
    private val inboundQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(256)
    private val inboundDropped = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        Thread({
            while (true) {
                val chunk = try {
                    inboundQueue.take()
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (chunk === INBOUND_SHUTDOWN) return@Thread
                onLinkBytes(chunk)
            }
        }, "ksi-inbound").apply { isDaemon = true; start() }
    }

    /** Enqueue inbound bytes for the ingest worker; never blocks the reader. */
    private fun enqueueInbound(bytes: ByteArray) {
        if (!inboundQueue.offer(bytes)) {
            val dropped = inboundDropped.incrementAndGet()
            lastError = "수신 백로그 초과로 $dropped 건 버림 (Edge 재전송으로 복구)"
        }
    }

    /** Inbound: Edge/link bytes through the real endpoint ingest path.
     *  Shares [outboundLock]: ACK handling mutates the unacked queue. */
    fun onLinkBytes(bytes: ByteArray): Int = synchronized(outboundLock) {
        if (!storageAvailable) {
            // No endpoint, no ingest, no ACK: the bytes cannot be
            // durably received while the store is down. Counted, never
            // stored, never acknowledged.
            failed += 1
            lastError = StorageHealth.UNAVAILABLE_BANNER
            return 0
        }
        val endpoint = controller?.endpoint ?: run {
            lastError = "no mission controller (start a flight first)"
            return 0
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
        // WHY: an inbound envelope that never becomes "stored" leaves the Edge
        // waiting for an ACK forever, and the RC shows "하트비트 미수신" with
        // no reason. One line per envelope makes the failure visible.
        android.util.Log.i(
            "KsiMop",
            "inbound type=${lastInbound} stored=$stored dup=$duplicates failed=$failed err=${lastError}",
        )
        stored
    }

    fun clearError() {
        lastError = null
    }

    // -- upload / query -----------------------------------------------------------

    fun coreConfigured(): Boolean = coreConfig() != null

    /** A5 mission synchronizer (null when no Core is configured). */
    fun missionSync(): ksi.rc.MissionSync? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        return ksi.rc.MissionSync(
            ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store,
        )
    }

    /**
     * Creates a pending site draft (offline-first: no network, no Core
     * needed). The draft is stored durably and registered centrally later
     * via [pendingSiteSync]. Refuses when storage is down (fail-closed).
     */
    fun createPendingSite(
        name: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusM: Double? = null,
    ): ksi.rc.PendingSiteDraft {
        requireStoreAvailable()
        return pendingSites.createDraft(name, centerLat, centerLon, radiusM)
    }

    /**
     * Deferred uploader for pending-site drafts (null when no Core is
     * configured or storage is down — drafts stay pending with their
     * reason, never an error).
     */
    fun pendingSiteSync(): PendingSiteSync? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        return PendingSiteSync(
            ksi.rc.CoreApi(AndroidCoreTransport(url, token)), pendingSites, store,
        )
    }

    /**
     * Site-first ordering seam for reconnect/auto uploads: runs one
     * deferred pending-site sync round NOW and reports it (null when no
     * Core is configured or storage is down — same as [pendingSiteSync]).
     *
     * WHY a separate call: a flight recorded under an unresolved pending
     * id must not reach the Core before its site does — the server
     * registers flights without validating the site id, and a later
     * alias change would turn every re-register into a 409 (same
     * flight_id, different content). [uploadSpec] refuses such flights,
     * so the upload path (manual or WorkManager) must call this first on
     * reconnect and only then build the spec. Both upload entries do:
     * [SyncWorker.doWork] (whose CONNECTED constraint plus the enqueue
     * points — Core config save, process start, manual upload — are the
     * reconnect hook; no separate NetworkCallback is invented) and the
     * manual `uploadNow` path. Durable-write failures throw fail-closed
     * (poisoned journal must surface); transport failures stay inside
     * the returned report. Never invents an org.
     */
    fun syncPendingSitesNow(): PendingSyncReport? =
        pendingSiteSync()?.syncOnce()

    /**
     * Digest gate shared by both entry orders: only digest-verified bundles
     * may be pinned (the sync layer stored them; this closes the path
     * structurally). Throws on mismatch — nothing attaches, no id mints.
     */
    private fun verifiedBundleForPin(bundle: ObjectNode): ObjectNode {
        check(CanonicalJson.verifyDigest(bundle, "bundle_sha256")) {
            "bundle digest mismatch; refusing to pin"
        }
        val snapshot = bundle.get("mission_snapshot") as? ObjectNode
            ?: throw IllegalStateException("bundle is missing its mission snapshot")
        check(CanonicalJson.verifyDigest(snapshot, "content_sha256")) {
            "embedded snapshot digest mismatch; refusing to pin"
        }
        return snapshot
    }

    /**
     * 준비 (adopted order 준비 → 적용 → 기록 시작): attaches a
     * [MissionController] for a digest-verified selected bundle WITHOUT
     * creating a flight record and WITHOUT claiming recording. Mints one
     * provisional UUID ([pendingFlightId]) that the later 기록 시작 reuses
     * as the flight record's id. Prepare/Apply then operate on the selected
     * bundle. Idempotent for the same bundle (returns the existing
     * provisional id); refuses a DIFFERENT bundle while one is attached
     * (abandon first — silently swapping would orphan in-flight envelopes).
     */
    fun prepareBundle(bundle: ObjectNode): String {
        requireStoreAvailable()
        check(store.activeFlight() == null) {
            "a flight is already active; end it before preparing a new bundle"
        }
        verifiedBundleForPin(bundle)
        val attached = controller
        if (attached != null) {
            check(attached.bundleId == bundle.get("bundle_id").asText()) {
                "bundle ${attached.bundleId.take(8)} already prepared; " +
                    "abandon it before preparing another bundle"
            }
            return pendingFlightId
                ?: throw IllegalStateException("controller attached with no pending id")
        }
        val provisionalId = UUID.randomUUID().toString()
        val newBundleId = bundle.get("bundle_id").asText()
        // Durable FIRST: a restart between here and the attach must still
        // show what was pending. A pending attempt for a DIFFERENT bundle is
        // superseded (its Edge request, if any, belongs to the abandoned
        // flow the operator explicitly left).
        prepareAttempts.pendingAttempt()?.let { outstanding ->
            if (outstanding.bundleId != newBundleId) {
                prepareAttempts.recordSuperseded(outstanding.provisionalId)
            }
        }
        prepareAttempts.recordAttempt(
            provisionalId = provisionalId,
            bundleId = newBundleId,
            bundleDigest = bundle.get("bundle_sha256").asText(),
        )
        attachController(bundle, provisionalId)
        pendingFlightId = provisionalId
        return provisionalId
    }

    /**
     * Abandons a prepared-but-never-recorded bundle: detaches the
     * controller and drops the provisional id WITHOUT creating a flight
     * record. Refuses while a flight is active (that path ends via the
     * close-out, never via abandon) and while an Edge request round is
     * observably in flight (PREPARING/APPLYING states): a late reply
     * landing on a detached controller would be lost (and the Edge
     * install orphaned), so abandon waits for the reply — which then lands
     * normally — instead of dropping it. Durable
     * application receipts keyed by the provisional id, if any (APPLIED
     * was reached), stay in the store as real Edge receipts — they are
     * never deleted, never relabeled. A silent Edge (no reply at all) is
     * recovered by restart, never by abandon: preparation state is
     * in-memory only and never resurrects as a phantom flight.
     *
     * @return true when something was abandoned, false when idle.
     */
    fun abandonPrepared(): Boolean {
        requireStoreAvailable()
        check(store.activeFlight() == null) {
            "a flight is active; abandon applies only before 기록 시작"
        }
        val attached = controller
        if (attached != null && (
            attached.state == ksi.rc.MissionState.PREPARING ||
                attached.state == ksi.rc.MissionState.APPLYING
            )
        ) {
            throw IllegalStateException(
                "Edge 적용 요청 진행 중 · 응답을 받은 뒤 취소하세요 " +
                    "(응답 없음 시 앱 재시작 — 준비 상태는 복원되지 않음)",
            )
        }
        if (controller == null && pendingFlightId == null) return false
        val abandonedId = pendingFlightId
        controller = null
        bundle = null
        pendingFlightId = null
        lastError = null
        if (abandonedId != null) prepareAttempts.recordAbandoned(abandonedId)
        return true
    }

    /** Pins the given bundle's revisions under [flightId] (no id invented). */
    private fun pinnedRecord(bundle: ObjectNode, snapshot: ObjectNode, flightId: String): ObjectNode {
        val zoneIds = mutableListOf<String>()
        snapshot.get("zones")?.forEach { zone ->
            zone.get("revision_id")?.asText()?.let { zoneIds.add(it) }
        }
        val record = ksi.rc.Mop.json.createObjectNode()
        record.put("schema_version", 1)
        record.put("flight_id", flightId)
        record.put("site_id", bundle.get("site_id").asText())
        record.put("mission_id", snapshot.get("mission_id").asText())
        record.put("mission_revision_id", snapshot.get("mission_revision_id").asText())
        record.put("mission_sha256", snapshot.get("content_sha256").asText())
        record.put("bundle_id", bundle.get("bundle_id").asText())
        record.put("bundle_sha256", bundle.get("bundle_sha256").asText())
        val zones = record.putArray("zone_revision_ids")
        zoneIds.forEach { zones.add(it) }
        record.put("started_at_utc", wallClock())
        record.putNull("ended_at_utc")
        record.put("state", "STARTED")
        return record
    }

    /**
     * Starts a flight pinned to a DIGEST-VERIFIED local bundle (the sync
     * layer stored it) and attaches the mission controller. Legacy order
     * (기록 시작 first): everything after this point (Prepare/Apply) needs
     * no network.
     */
    fun startFlightFromBundle(bundle: ObjectNode): ObjectNode {
        requireStoreAvailable()
        check(store.activeFlight() == null) {
            "a flight is already active; end it before starting a new one"
        }
        val snapshot = verifiedBundleForPin(bundle)
        val record = pinnedRecord(bundle, snapshot, UUID.randomUUID().toString())
        store.startFlight(record)
        val stored = store.flight(record.get("flight_id").asText())!!
        attachController(bundle, stored.get("flight_id").asText())
        pendingFlightId = null
        return stored
    }

    /**
     * 기록 시작 (adopted order 준비 → 적용 → 기록 시작): creates the flight
     * record for an ALREADY prepared/applied bundle, reusing the provisional
     * id minted at 준비 ([pendingFlightId]) as the flight record's
     * `flight_id` — one UUID end-to-end. Binds the already-attached
     * controller (never re-attached: that would reset its APPLIED progress
     * to IDLE). Throws when nothing was prepared, when the prepared bundle
     * is not the given one, or when a flight is already active.
     */
    fun startRecordingFromPrepared(bundle: ObjectNode): ObjectNode {
        requireStoreAvailable()
        check(store.activeFlight() == null) {
            "a flight is already active; end it before starting a new one"
        }
        val snapshot = verifiedBundleForPin(bundle)
        val provisionalId = pendingFlightId
            ?: throw IllegalStateException("no prepared bundle (run 준비 first)")
        val attached = controller
            ?: throw IllegalStateException("no prepared bundle (run 준비 first)")
        check(attached.bundleId == bundle.get("bundle_id").asText()) {
            "prepared bundle ${attached.bundleId.take(8)} differs from the selected bundle"
        }
        check(attached.flightId == provisionalId) {
            "prepared controller id mismatch; refusing to bind"
        }
        val record = pinnedRecord(bundle, snapshot, provisionalId)
        store.startFlight(record)
        pendingFlightId = null
        prepareAttempts.recordConsumed(provisionalId)
        return store.flight(provisionalId)!!
    }

    /**
     * A preparation attempt that survived process death WITHOUT completing
     * into a recording and WITHOUT a live controller to speak for it.
     * Shown so the operator can explicitly re-run 준비; never auto-resumed,
     * never auto-resent, never a flight. Null whenever live state
     * (controller, provisional id, or an active flight) is authoritative.
     */
    val restoredPendingPreparation: ksi.rc.PrepareAttempt?
        get() {
            if (controller != null || pendingFlightId != null) return null
            if (!storageAvailable) return null
            if (store.activeFlight() != null) return null
            return prepareAttempts.pendingAttempt()
        }

    /**
     * LOCAL bundle composition (product gap G11): builds a SHADOW-rule
     * bundle from the RC's OWN stored zone revisions
     * ([MissionStore.zoneRevisions]) plus the stored mission snapshot basis,
     * saves it with [MissionStore.saveMissionBundle], and returns the honest
     * account (pinned revision ids, rule count, excluded revisions with
     * reasons). The caller then runs the existing
     * startFlightFromBundle -> prepare -> apply path; this composition step
     * invents no geometry (see [MissionBundleBuilder]).
     */
    fun composeLocalBundle(
        siteId: String,
        createdAtUtc: String,
        zoneRevisionIds: List<String>? = null,
        proximityThresholdM: Double? = null,
    ): LocalBundleResult {
        requireStoreAvailable()
        val base = LocalBundleComposer.baseSnapshotFor(store, siteId)
        val request = LocalBundleRequest(
            siteId = siteId,
            flightId = java.util.UUID.randomUUID().toString(),
            createdAtUtc = createdAtUtc,
            baseMissionSnapshot = base,
            zoneRevisionIds = zoneRevisionIds,
            createdBy = null,
            proximityThresholdM = proximityThresholdM,
        )
        return LocalBundleComposer.composeAndSave(store, request)
    }

    /**
     * Facts-only session verdict (contract §6). The persisted snapshot is
     * built by [SessionVerdictSnapshot] from the same inputs the screen
     * classifies and compares — that shared snapshot is what stops the
     * persist-compare loop. Takes NO preservation/COMMITTED input — a
     * fully preserved failed session still reads FAILED.
     */
    fun decideVerdict(
        flightId: String,
        bootId: String,
        localVideoFailure: Boolean? = null,
    ): SessionResultRecord {
        requireStoreAvailable()
        val execution = controller?.executionFacts() ?: ExecutionFacts(
            configApplied = null,
            runtimeConfirmed = null,
            frameProcessingObserved = null,
            stopConfirmed = null,
            runtimeEpochChanged = false,
            linkInterrupted = false,
        )
        val stored = SessionFactsResolver.resolve(store, flightId, bootId)
        val record = SessionVerdict.decide(
            store = store,
            results = verdicts,
            execution = execution,
            flightId = flightId,
            bootId = bootId,
            runtimeEpoch = controller?.recordedRuntimeEpoch,
            localVideoFailure = localVideoFailure,
        )
        lastDecidedFacts = SessionVerdictSnapshot.build(execution, stored, localVideoFailure)
        return record
    }

    /** Latest persisted verdict for a session, or null when undecided. */
    fun latestVerdict(sessionId: String): SessionResultRecord? =
        verdicts.latest(sessionId)

    /**
     * The exact snapshot backing the last [decideVerdict] record (in-memory
     * only — the durable truth is the verdict log). Lets the UI skip a
     * re-decide when the current snapshot is already persisted, without
     * re-reading the log or comparing JSON strings.
     */
    @Volatile
    var lastDecidedFacts: SessionFacts? = null
        private set

    /** True when [facts] is exactly what the last decide persisted. */
    fun isVerdictCurrent(facts: SessionFacts): Boolean = lastDecidedFacts == facts

    /**
     * Segment→boot provenance anchor: the Edge boot observed when RC proxy
     * recording started for a flight (null when no boot was known — also
     * recorded, never invented). Read at close together with the boots the
     * store actually holds; a single distinct boot means unambiguous
     * linkage, anything else keeps the segments local and unverified.
     */
    private val recordingStartBoot = mutableMapOf<String, String?>()

    /** Sticky recorder failure flag as observed at each flight's record start. */
    private val recordingFailedAtStart = mutableMapOf<String, Boolean?>()

    /** Call when proxy recording starts (best-effort anchor, never a claim). */
    fun noteRecordingStart(flightId: String, recorderFailedAtStart: Boolean? = null) {
        recordingStartBoot[flightId] =
            controller?.activeBootId ?: store.bootsFor(flightId).lastOrNull()
        recordingFailedAtStart[flightId] = recorderFailedAtStart
    }

    /** Recorded start anchor for [flightId], or null when never captured. */
    fun recordingStartBootFor(flightId: String): String? = recordingStartBoot[flightId]

    /** Sticky recorder failure flag observed when recording started (null = unknown). */
    fun recordingFailedAtStart(flightId: String): Boolean? = recordingFailedAtStart[flightId]

    /**
     * Operator session-start entry point (contract §6 closeout): persists
     * the start intent (fsync) while holding the transport lock, BEFORE
     * the Edge can send anything for the new session. After a crash or
     * restart the intent stands without a close-out, and the flight stays
     * blocked until an actual STOPPED is observed — never assumed clean.
     */
    fun requestSessionStart() {
        requireStoreAvailable()
        val controller = controller
            ?: throw IllegalStateException("no mission controller (start a flight first)")
        val flightId = store.activeFlight()?.get("flight_id")?.asText()
            ?: throw IllegalStateException("no active flight")
        synchronized(outboundLock) {
            intents.record(SessionStartIntent(flightId, controller.activeBootId, wallClock()))
            controller.startSession()
        }
    }

    /**
     * True when a start intent stands without a later close-out: the Edge
     * session may have started (including a crash before any close-out),
     * so the flight stays blocked until an actual STOPPED is observed.
     * Unknown is restored as unknown — never as "never started".
     */
    fun hasOpenStartIntent(flightId: String): Boolean {
        // Unknown stands as open when the store is down: never unlock a
        // new session from unavailable data.
        if (!storageAvailable) return true
        val intent = intents.latestForFlight(flightId) ?: return false
        val closeout = closeouts.latestForFlight(flightId) ?: return true
        return !isSameOrLater(closeout.decidedAtUtc, intent.decidedAtUtc)
    }

    /**
     * Structured operator-response route for a flight (Core
     * `POST /operator-responses`). Null when no Core is configured — the
     * pending count then reads 0 and the old behavior is preserved.
     */
    fun responseSync(): OperatorResponseSync? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        return OperatorResponseSync(
            ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store, operatorActions,
        )
    }

    /**
     * Linked responses still without a durable structured-route receipt.
     * A late response after artifact COMMITTED keeps the sync alive — the
     * artifact-only check must not short-circuit it.
     */
    fun pendingResponseCount(flightId: String): Int =
        responseSync()?.pendingForFlight(flightId)?.size ?: 0

    /**
     * Linked operator responses for a flight's sessions (read-only count
     * for the close summary). Zero is an explicitly stated fact — no
     * response item is ever fabricated to fill it.
     */
    fun opinionCountFor(flightId: String): Int {
        val sessions = store.bootsFor(flightId)
            .map { OperatorActionArtifacts.sessionIdFor(flightId, it) }.toSet()
        if (sessions.isEmpty()) return 0
        return operatorActions.readAll().count { it.sessionId in sessions }
    }

    /** Latest close-out record for a flight, or null when never closed. */
    fun latestCloseout(flightId: String): CloseoutRecord? =
        closeouts.latestForFlight(flightId)


    private fun isSameOrLater(a: String, b: String): Boolean {
        val ai = runCatching { java.time.Instant.parse(a) }.getOrNull()
        val bi = runCatching { java.time.Instant.parse(b) }.getOrNull()
        if (ai != null && bi != null) return !ai.isBefore(bi)
        return a >= b
    }

    /**
     * Boot ref for close-out: Edge-confirmed, else latest stored, else
     * null (recorded missing, never invented).
     */
    fun closingBootId(flightId: String): String? =
        controller?.activeBootId ?: store.bootsFor(flightId).lastOrNull()

    /**
     * Sends STOP when a session is open and assesses the outcome WITHOUT
     * inventing confirmation. A send failure is reported as an
     * unresolved assessment, never thrown into the close path.
     */
    /**
     * Sends STOP when a session is open and assesses without inventing
     * confirmation. A send failure is reported as an unresolved
     * assessment, never thrown into the close path.
     *
     * Recovery only: with the controller at IDLE (e.g. after a restart)
     * but a durable UNRESOLVED close-out or open start intent for the
     * active flight, an explicit operator recovery STOP is permitted via
     * `reconcileUnknown` — actual STOPPED receipt is still required, and
     * nothing auto-executes. Never used for automatic SDK flight.
     */
    fun requestEdgeStop(): FlightCloseout.StopRequest {
        if (!storageAvailable) {
            // Unknown stands as open: no clean assessment may come out of
            // unavailable data — the flight stays blocked until a real
            // store validates a STOPPED.
            return FlightCloseout.StopRequest(
                sessionOpen = true,
                assessment = FlightCloseout.StopAssessment(
                    false, StorageHealth.UNAVAILABLE_BANNER,
                ),
            )
        }
        val controller = controller
        if (controller == null) {
            val activeId = store.activeFlight()?.get("flight_id")?.asText()
            val unresolved = activeId != null && (
                hasOpenStartIntent(activeId) ||
                    closeouts.latestForFlight(activeId)?.outcome == CloseoutRecord.UNRESOLVED ||
                    store.bootsFor(activeId).isNotEmpty()
                )
            return FlightCloseout.StopRequest(
                sessionOpen = unresolved,
                assessment = if (unresolved) FlightCloseout.StopAssessment(
                    false, "Edge 제어 상태 복구 미확인 · 중지 확인 전 마감 불가",
                ) else FlightCloseout.assessStop(
                    sessionOpen = false, confirmedStopped = false, linkUp = true,
                ),
            )
        }
        val open = controller.sessionActive ||
            controller.state == ksi.rc.MissionState.SESSION_PENDING
        if (open) {
            try {
                controller.stopSession()
                pumpOutbound()
            } catch (exc: Exception) {
                return FlightCloseout.StopRequest(
                    sessionOpen = open,
                    assessment = FlightCloseout.StopAssessment(
                        clean = false,
                        note = "STOP 요청 불가(${exc.message}) — 미확인 종료",
                    ),
                )
            }
        } else {
            val flightId = store.activeFlight()?.get("flight_id")?.asText()
            val needsRecovery = flightId != null &&
                (closeouts.latestForFlight(flightId)?.outcome == CloseoutRecord.UNRESOLVED ||
                    hasOpenStartIntent(flightId))
            if (needsRecovery) {
                try {
                    controller.stopSession(reconcileUnknown = true)
                    pumpOutbound()
                    return FlightCloseout.StopRequest(
                        sessionOpen = true,
                        assessment = FlightCloseout.StopAssessment(
                            clean = false,
                            note = "복구 STOP 요청 전송됨 · Edge 확인 대기 — 미확인 종료(사유: STOPPED 미확인)",
                        ),
                    )
                } catch (exc: Exception) {
                    return FlightCloseout.StopRequest(
                        sessionOpen = true,
                        assessment = FlightCloseout.StopAssessment(
                            clean = false,
                            note = "복구 STOP 요청 불가(${exc.message}) — 미확인 종료",
                        ),
                    )
                }
            }
        }
        return FlightCloseout.StopRequest(
            sessionOpen = open,
            assessment = FlightCloseout.assessStop(
                sessionOpen = open,
                confirmedStopped = confirmedEdgeStop(),
                linkUp = link.state == DroneLink.State.CONNECTED,
            ),
        )
    }

    /**
     * One reconcile round: asks the Edge for MISSION_STATUS and pumps it
     * out. True when the Edge now reports STOPPED for our stop.
     */
    fun reconcileOnce(): Boolean {
        val controller = controller ?: return false
        controller.reconcile()
        pumpOutbound()
        return confirmedEdgeStop()
    }

    /** Protocol STOPPED/legacy running=false alone is not runtime proof. */
    private fun confirmedEdgeStop(): Boolean =
        controller?.state == ksi.rc.MissionState.STOPPED &&
            controller?.boundRuntimeStatus?.state == ksi.rc.EdgeRuntimeState.STOPPED

    /** Segment ingest outcome for the close path (local bytes never deleted). */
    data class SegmentIngest(
        val ingested: Int,
        val total: Int,
        val problems: List<String>,
    )

    /**
     * Ingests finalized recorder files with per-boot provenance: a single
     * distinct boot only. Anything else keeps the files local, marks the
     * linkage unverified, and counts nothing — prior-boot segments are
     * never relabeled to the latest boot.
     */
    fun ingestFinalizedSegments(flightId: String, files: List<File>): SegmentIngest {
        requireStoreAvailable()
        if (files.isEmpty()) return SegmentIngest(0, 0, emptyList())
        val resolution = FlightCloseout.resolveSegmentBoot(
            recordingStartBootFor(flightId),
            store.bootsFor(flightId),
        )
        if (resolution is FlightCloseout.SegmentBootResolution.Unverifiable) {
            return SegmentIngest(
                0,
                files.size,
                listOf(
                    "부트 연계 미검증 — 세그먼트 ${files.size}개 로컬 보관(증거 미생성·집계 안 함) · " +
                        resolution.reason,
                ),
            )
        }
        val bootId =
            (resolution as FlightCloseout.SegmentBootResolution.SingleBoot).bootId
        val ingestor = ksi.rc.SegmentIngestor(store)
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

    /**
     * Persists the UNRESOLVED close-out: recording finalized, Edge session
     * still open. The flight record stays active (new sessions blocked)
     * until a later STOPPED completes it. Returns the record.
     */
    fun persistUnresolvedCloseout(
        flightId: String,
        reason: String,
        ingest: SegmentIngest,
        videoFailed: Boolean?,
    ): CloseoutRecord {
        requireStoreAvailable()
        val active = store.activeFlight()
        val boot = closingBootId(flightId)
        val decided = boot?.let {
            runCatching { decideVerdict(flightId, it, videoFailed) }.getOrNull()
        }
        val record = CloseoutRecord(
            flightId = flightId,
            bootId = boot,
            sessionId = boot?.let {
                ksi.rc.SessionResultArtifacts.sessionIdFor(flightId, it)
            },
            outcome = CloseoutRecord.UNRESOLVED,
            stopConfirmed = controller?.executionFacts()?.stopConfirmed,
            stopReason = reason,
            opinionCount = opinionCountFor(flightId),
            segmentsIngested = ingest.ingested,
            segmentsTotal = ingest.total,
            segmentsUnverified = ingest.total > 0 && ingest.ingested == 0,
            videoCaptureFailed = videoFailed,
            bundleId = active?.get("bundle_id")?.takeIf { it.isTextual }?.asText(),
            bundleSha256 = active?.get("bundle_sha256")?.takeIf { it.isTextual }?.asText(),
            verdictResult = decided?.result?.name,
            verdictFactsJson = decided?.factsJson,
            decidedAtUtc = wallClock(),
        )
        closeouts.record(record)
        ensureCloseoutArtifact(record)
        return record
    }

    /** Clean-close completion data (record ended, safe to enqueue). */
    data class FlightEndSummary(
        val flightId: String,
        val verdict: ksi.rc.SessionResult?,
        val closeout: CloseoutRecord,
        val bundleArtifacts: Int,
        val opinions: Int,
    )

    /**
     * Lifecycle gate + clean completion: refuses (null) while an Edge
     * session is still open and unconfirmed — no clean end, no unlock,
     * without a matching STOPPED. The LIVE controller state is checked,
     * never a stale request-time assessment. Otherwise persists the
     * closing verdict, the CLEAN close-out (with recorder results), the
     * combined bundle snapshot artifact, ends the flight record, and
     * returns the summary. Null also when another path already ended the
     * flight (idempotent completion).
     */
    fun finalizeAndEndFlight(ingest: SegmentIngest, videoFailed: Boolean?): FlightEndSummary? {
        // Degraded: no clean end, no unlock — same as the UNRESOLVED lock.
        // Writes must fail, so this refuses instead of persisting anything.
        if (!storageAvailable) return null
        val active = store.activeFlight() ?: return null
        val flightId = active.get("flight_id").asText()
        val c = controller
        val stoppedNow = confirmedEdgeStop()
        val sessionOpen = c != null &&
            (c.sessionActive || c.state == ksi.rc.MissionState.SESSION_PENDING || stoppedNow)
        // Lifecycle gate (memory AND durable intent): an open Edge session
        // without an observed STOPPED never ends cleanly; neither does a
        // flight whose persisted UNRESOLVED close-out or start intent still
        // stands. Unknown is restored as unknown — never assumed clean.
        if (sessionOpen && !stoppedNow) return null
        if (!stoppedNow) {
            val unresolved = closeouts.latestForFlight(flightId)
            if (unresolved != null && unresolved.outcome == CloseoutRecord.UNRESOLVED) return null
            if (hasOpenStartIntent(flightId)) return null
        }
        val linkUp = link.state == DroneLink.State.CONNECTED
        val assessment = FlightCloseout.assessStop(
            sessionOpen = sessionOpen,
            confirmedStopped = stoppedNow,
            linkUp = linkUp,
        )
        val boot = closingBootId(flightId)
        val decided = boot?.let {
            runCatching { decideVerdict(flightId, it, videoFailed) }.getOrNull()
        }
        // Explicit never-started draft: no session ever opened, no start
        // intent stands, and no Edge boot was ever recorded for the flight.
        // The record states the basis outright; anything less certain stays
        // UNRESOLVED or blocked above.
        val everStarted = sessionOpen || hasOpenStartIntent(flightId) ||
            store.bootsFor(flightId).isNotEmpty()
        val record = CloseoutRecord(
            flightId = flightId,
            bootId = boot,
            sessionId = boot?.let {
                ksi.rc.SessionResultArtifacts.sessionIdFor(flightId, it)
            },
            outcome = if (everStarted) CloseoutRecord.CLEAN else CloseoutRecord.DRAFT,
            stopConfirmed = if (!sessionOpen) {
                null
            } else {
                c?.executionFacts()?.stopConfirmed ?: stoppedNow
            },
            stopReason = assessment.note,
            opinionCount = opinionCountFor(flightId),
            segmentsIngested = ingest.ingested,
            segmentsTotal = ingest.total,
            segmentsUnverified = ingest.total > 0 && ingest.ingested == 0,
            videoCaptureFailed = videoFailed,
            bundleId = active.get("bundle_id")?.takeIf { it.isTextual }?.asText(),
            bundleSha256 = active.get("bundle_sha256")?.takeIf { it.isTextual }?.asText(),
            verdictResult = decided?.result?.name,
            verdictFactsJson = decided?.factsJson,
            decidedAtUtc = wallClock(),
        )
        closeouts.record(record)
        val bundleArtifacts = ensureCloseoutArtifact(record)
        endFlight() ?: return null
        return FlightEndSummary(
            flightId = flightId,
            verdict = decided?.result,
            closeout = record,
            bundleArtifacts = bundleArtifacts,
            opinions = record.opinionCount,
        )
    }

    /** Injectable closeout announcer (production store pipeline by default). */
    var announceCloseout: (
        record: CloseoutRecord,
        bundle: com.fasterxml.jackson.databind.node.ObjectNode?,
        flightId: String,
        bootId: String,
    ) -> String? = { record, bundle, flightId, bootId ->
        CloseoutArtifacts.ensureForBoot(store, record, bundle, flightId, bootId)
    }

    /**
     * Announces the combined mission + closeout snapshot for the record's
     * verified boot ref. Returns 1 when covered, 0 when there is no
     * verified boot to attach to (nothing announced, never a default).
     * Store failures THROW: the close path must not end or enqueue on an
     * unmaterialized required artifact — the flight stays active and
     * blocked for retry. The record's `edge_boot_id` must equal the
     * announced boot.
     */
    fun ensureCloseoutArtifact(record: CloseoutRecord): Int {
        val boot = record.bootId ?: return 0
        val bundle = record.bundleId?.let { store.missionBundle(it) }
        return if (announceCloseout(record, bundle, record.flightId, boot) != null) {
            1
        } else {
            0
        }
    }

    /**
     * Why a clean finalize would currently refuse (null when it would
     * proceed): an UNRESOLVED close-out or open start intent still stands
     * without an observed STOPPED. Lets the coordinator report the standby
     * honestly instead of mislabeling it "already ended".
     */
    fun closeBlockedReason(flightId: String): String? {
        if (!storageAvailable) return StorageHealth.UNAVAILABLE_BANNER
        if (store.activeFlight()?.get("flight_id")?.asText() != flightId) return null
        if (controller?.state == ksi.rc.MissionState.STOPPED) return null
        val unresolved = closeouts.latestForFlight(flightId)
        if (unresolved != null && unresolved.outcome == CloseoutRecord.UNRESOLVED) {
            return "미확인 종료 기록이 STOPPED 확인을 기다림 · ${unresolved.stopReason}"
        }
        if (hasOpenStartIntent(flightId)) {
            return "시작 인텐트 미확인 · Edge STOPPED 확인 전까지 새 비행 잠김"
        }
        return null
    }

    /**
     * Reuse-or-repair for manual/automatic upload paths: if the flight's
     * latest close-out lacks its combined artifact (older records, or a
     * boot that only appeared later), materialize it now when a verified
     * boot is resolvable — including for already-ended flights. Best
     * effort: false means the close-out stays local and the upload
     * proceeds without claiming it; never a throw out of the upload path.
     */
    fun repairCloseoutArtifact(flightId: String): Boolean {
        if (!storageAvailable) return false
        val record = closeouts.latestForFlight(flightId) ?: return false
        val boot = record.bootId ?: closingBootId(flightId) ?: return false
        val effective = if (record.bootId == boot) {
            record
        } else {
            val fixed = record.copy(
                bootId = boot,
                sessionId = ksi.rc.SessionResultArtifacts.sessionIdFor(flightId, boot),
            )
            closeouts.record(fixed)
            fixed
        }
        return try {
            ensureCloseoutArtifact(effective) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun uploader(): EvidenceUploader? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        return EvidenceUploader(
            ksi.rc.CoreApi(AndroidCoreTransport(url, token)), store,
            operatorActions = operatorActions,
            // Verdicts decided via [decideVerdict] ship as required
            // FLIGHT_LOG artifacts of the session-result lineage — no
            // verdict for a boot means nothing announced, never a default.
            sessionResults = verdicts,
        )
    }

    fun query(): LocalFirstEvidence? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        return LocalFirstEvidence(store, ksi.rc.CoreApi(AndroidCoreTransport(url, token)))
    }

    /** A8: reachability-gated, resumable upload for one flight. */
    fun syncCoordinator(): ksi.rc.SyncCoordinator? {
        if (!storageAvailable) return null
        val (url, token) = coreConfig() ?: return null
        val core = ksi.rc.CoreApi(AndroidCoreTransport(url, token))
        return ksi.rc.SyncCoordinator(
            core,
            ksi.rc.EvidenceUploader(
                core, store,
                operatorActions = operatorActions,
                sessionResults = verdicts,
            ),
        )
    }

    /** Spec built ONLY from the flight record — no invented ids and no
     *  server round-trip; digests came in with the flight's mission. The
     *  site id maps through the pending-site alias ([PendingSiteJournal
     *  .resolveForCore]) for Core-facing reporting only: the stored record
     *  keeps its pinned id (forward-only, never rewritten). */
    fun uploadSpec(): FlightUploadSpec {
        val active = store.activeFlight()
            ?: throw IllegalStateException("no active flight")
        return uploadSpec(active.get("flight_id").asText())
    }

    /**
     * Pure pre-upload gate: null when the flight may upload, else the
     * honest wait reason (a flight recorded under an UNRESOLVED pending
     * id). The server registers flights without validating the site id,
     * so an early upload would key the server row by a client UUID
     * (unqueryable by the canonical site), and the later alias change
     * would turn every re-register into a 409 (same flight_id, different
     * content). Unknown flights return null here — [uploadSpec] still
     * throws for them ("no such flight"); the gate only names the wait.
     */
    fun uploadGateReason(flightId: String): String? {
        val record = store.flight(flightId) ?: return null
        val siteId = record.get("site_id").asText()
        return if (pendingSites.statusFor(siteId)?.isSynced == false) {
            "${PendingSiteRegistration.UPLOAD_WAIT_REASON} " +
                "(현장 ${siteId.take(8)})"
        } else {
            null
        }
    }

    fun uploadSpec(flightId: String): FlightUploadSpec {
        val record = store.flight(flightId)
            ?: throw IllegalStateException("no such flight: $flightId")
        // Site-first ordering: refuse with the honest wait reason — the
        // local record is untouched, the upload is deferred until
        // [syncPendingSitesNow] resolves the site.
        uploadGateReason(flightId)?.let { throw IllegalStateException(it) }
        val siteId = record.get("site_id").asText()
        return FlightUploadSpec(
            flightId = record.get("flight_id").asText(),
            siteId = pendingSites.resolveForCore(siteId),
            rcDeviceId = rcDeviceId,
            missionId = record.get("mission_id").asText(),
            missionRevisionId = record.get("mission_revision_id").asText(),
            missionSha256 = record.get("mission_sha256").asText(),
            bundleSha256 = record.get("bundle_sha256").asText(),
        )
    }

    fun missionSummary(): String {
        if (!storageAvailable) {
            return "저장소 사용 불가 · ${StorageHealth.UNAVAILABLE_DETAIL}"
        }
        val active = controller ?: return "no bundle attached"
        val state = active.state
        // A prepared bundle without a flight record is preparation state,
        // never a started session — label it so it cannot read as one.
        val prefix = if (store.activeFlight() == null) {
            "prepared bundle (no flight record) · "
        } else {
            ""
        }
        val extra = when (state) {
            ksi.rc.MissionState.APPLIED ->
                "app=${active.lastApplication?.get("application_id")?.asText()}"
            ksi.rc.MissionState.RUNNING, ksi.rc.MissionState.STOPPED,
            ksi.rc.MissionState.SESSION_PENDING ->
                "session=${active.sessionId} active=${active.sessionActive} " +
                    "edgeRunning=${active.edgeReportedRunning}"
            else -> ""
        }
        return "$prefix$state $extra transitions=${active.transitions.size}"
    }
}

/** Device wall clock used as the RC receipt clock (store stamping only). */
object ReceiptClock {
    fun now(): String = Instant.now().toString()
}
