package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Mission lifecycle states (ledger §9 contract, user 2026-10-09). */
enum class MissionState {
    IDLE,
    PREPARING,
    PREPARED,
    APPLYING,
    /** Apply reply lost / link lost: reconcile via MISSION_STATUS_QUERY. */
    STATUS_UNKNOWN,
    APPLIED,
    /** A session command was ACKed; the Edge running state is unconfirmed. */
    SESSION_PENDING,
    RUNNING,
    STOPPED,
    REJECTED,
}

data class StateTransition(
    val state: MissionState,
    val reason: String,
    val nowNs: Long,
)

data class IgnoredStale(
    val type: String,
    val msgId: String,
    val reason: String,
)

/**
 * RC-local execution facts for [SessionResultClassifier] (contract §6).
 * All nullable except the interruption flags, which default to false and
 * are set only on positive observation:
 * - [configApplied]: latest reported `config_applied` (null = never reported);
 * - [runtimeConfirmed]: the Edge reported RUNNING/DEGRADED for the current
 *   session generation (null = unconfirmed, e.g. still pending);
 * - [frameProcessingObserved]: a positive frame count WITH engine-ok
 *   success evidence (null = the Edge never reported usable inference
 *   evidence — engine absent or fields legacy; false = the Edge positively
 *   reported no successful inference — engine failed or zero counts);
 * - [stopConfirmed]: the Edge reported STOPPED after our STOP;
 * - [runtimeEpochChanged]: same as [MissionController.needsRevalidation];
 * - [linkInterrupted]: [MissionController.linkInterruptedDuringSession].
 *
 * The app layer merges these with store-derived facts (observation counts,
 * video evidence, declared gaps) before classifying — the controller never
 * sees the evidence store.
 */
data class ExecutionFacts(
    val configApplied: Boolean?,
    val runtimeConfirmed: Boolean?,
    val frameProcessingObserved: Boolean?,
    val stopConfirmed: Boolean?,
    val runtimeEpochChanged: Boolean,
    val linkInterrupted: Boolean,
    /**
     * Installed-rule/zone facts derived from the bundle the RC itself
     * applied (see [MissionController.riskEvaluationAvailable]): counts of
     * `risk_policy.rules[]` and `mission_snapshot.zones[]` in that bundle.
     * Zero rules with [configApplied] true means the Edge installed
     * "nothing to install" — observation may run, but NO risk evaluation is.
     */
    val appliedRuleCount: Int = 0,
    val appliedZoneCount: Int = 0,
    /**
     * True ONLY when the Edge reported the config applied AND the applied
     * bundle contains at least one risk rule. Never optimistic: unknown,
     * legacy, rejected or zero-rule states all read false.
     */
    val riskEvaluationAvailable: Boolean = false,
    /** A bound Edge report positively declared irreversible evidence loss. */
    val durableEvidenceFailure: Boolean? = null,
)

/**
 * Mission pre-flight controller (RC side): bundle PREPARE -> APPLY ->
 * session RUN/STOP with explicit contracts:
 *
 * 1. ENVELOPE ACK IS NOT APPLY ACK. An envelope ACK only clears the retry
 *    queue (handled in [MopEndpoint]); the mission state advances to
 *    PREPARED only on MISSION_PREPARED and to APPLIED only on
 *    MISSION_APPLIED or a reconciled MISSION_STATUS whose
 *    `applied_bundle_sha256` matches the pending bundle digest. Sending an
 *    APPLY never marks READY.
 * 2. STATUS_UNKNOWN + reconciliation: when the APPLIED reply or the link is
 *    lost, [reconcile] queries MISSION_STATUS (idempotent; safe to repeat)
 *    and applies it only when the digest matches. This mirrors the
 *    validated harness `prepare_and_apply` flow.
 * 3. STALE / LATE RESPONSES ARE REJECTED, never fabricated into records:
 *    a MISSION_APPLIED/MISSION_STATUS arriving with no pending apply
 *    context (e.g. an Edge retry after we already reconciled) is recorded
 *    in [ignoredStale] and ACKed (the Edge did its part) without creating
 *    an application row. (The Python harness has a latent gap here — a
 *    late retried APPLIED can build an empty-ctx application; this
 *    controller deliberately closes it.)
 * 4. Attempt correlation is the bundle digest + pending context, because
 *    the protocol carries no attempt id; the accepted response's
 *    `edge_boot_id` becomes [activeBootId]. After an Edge reboot the RC
 *    must re-apply (a session must not silently trust the previous boot).
 * 5. SESSION state is marked RUNNING/STOPPED ONLY from Edge-reported status
 *    (MISSION_STATUS.running). A command ACK (SESSION_START/STOP) proves
 *    durable acceptance by the Edge, NOT that its analysis is actually
 *    running: the ACK moves the state to SESSION_PENDING and the UI must
 *    not claim more. [sessionActive] follows the confirmed state. A STOP is
 *    confirmed ONLY by an exact `runtime_state == STOPPED` report (modern
 *    fields) or `running == false` (legacy bool fallback); STARTING,
 *    CONFIG_APPLIED and REQUEST_ACCEPTED are never a clean stop. The
 *    session id is derived as `uuid5(NAMESPACE, "session|flight|boot")`.
 * 6. Message ids come from a durable monotonic counter ([RcStore
 *    nextMsgCounter]): ids are never reused after an RC process restart
 *    (the Edge dedups by msg_id, so a reused id would silence a new
 *    command).
 * 7. Runtime truth comes from the MISSION_STATUS §3 optional fields
 *    ([EdgeRuntimeStatus]), never from the legacy `running` bool alone:
 *    `running=true` is only "start requested, not stopped". The bool path
 *    stays ONLY as a fallback for Edge builds that do not report
 *    `runtime_state` yet. When `runtime_state` is present it is
 *    authoritative: RUNNING or DEGRADED with the observation still going
 *    means executing; anything else means unconfirmed. DEGRADED sets
 *    [sessionActive] (the observation continues) but must NEVER read as
 *    "safe" — the UI shows [runtimeStatus] degraded reasons alongside.
 *    [analysisConfirmed]/[sessionReady] stay false until the Edge reports
 *    successful AI inference: a positive `processed_frame_count` WITH
 *    `input_state.engine == "ok"` while executing (the count alone tracks
 *    frame attempts — cadence skips and inference failures increment it —
 *    so engine absent/failed/unknown never confirms analysis).
 * 8. Install binding, not digest equality: a MISSION_STATUS adopts runtime
 *    facts only when `applied_bundle_sha256` names the intended bundle AND
 *    the reported `effective_config_hash` matches what that bundle installs
 *    ([EffectiveConfigHash] mirrors the Edge parser byte-exactly). The two
 *    hashes live in different domains and are never compared with each
 *    other; anything unverifiable stays unconfirmed (or demands
 *    revalidation), never true. A foreign or digest-less report is
 *    display-only: no latches, no transitions, no frame counts.
 * 9. Retransmission/idempotency and poll coalescing: a repeated
 *    SESSION_START/APPLY while one is already in flight is a no-op that
 *    converges on the reported state
 *    (the endpoint's retry queue already reuses the SAME msg_id). A
 *    repeated command never creates a second session. Status queries
 *    coalesce the same way: at most one MISSION_STATUS_QUERY is ever
 *    pending ([pollTick]/[reconcile] share one tracked envelope), resent
 *    under the same id, never flooding the outbox while disconnected.
 *    When the reported `runtime_epoch` differs from [recordedRuntimeEpoch],
 *    the session needs re-apply/revalidation ([needsRevalidation]): the
 *    controller stops converging onto the foreign generation's report and
 *    the operator restarts the cycle explicitly (prepare/apply/start —
 *    never silent auto-recovery).
 *
 * NOTE: this is the KSI message contract on top of a transport. DJI MOP (or
 * a test link) is only how bytes travel; nothing here proves the real
 * aircraft/RC link works (see ledger §9).
 */
class MissionController(
    val flightId: String,
    private val bundle: ObjectNode,
    private val store: RcStore,
    private val clock: () -> Long,
    /** Evidence-plane handler (A3); control types never reach it. */
    private val dataHandler: ControlHandler? = null,
) : ControlHandler {
    companion object {
        /**
         * Default live-status cadence: one MISSION_STATUS_QUERY per 2 s
         * while a session is in flight. Same order as the 1 s
         * retransmission timer that calls [pollTick], but sparser so the
         * poll stays bounded even when the timer fires faster.
         */
        const val DEFAULT_STATUS_INTERVAL_NS = 2_000_000_000L

        /**
         * Edge degraded-reason code (§3) naming the zone hold: installed
         * zone rules with no convertible local geometry withhold every
         * verdict instead of reporting "no intrusion".
         */
        const val ZONE_GEOMETRY_UNAVAILABLE = "zone_geometry_unavailable"
    }

    val endpoint: MopEndpoint
        get() = endpointRef

    private val endpointRef: MopEndpoint =
        MopEndpoint(flightId, store, clock, this) { ackOf -> onCommandAcked(ackOf) }

    var state: MissionState = MissionState.IDLE
        private set
    val transitions = mutableListOf<StateTransition>()
    var lastApplication: ObjectNode? = null
        private set
    var lastRejectionReasons: List<String> = emptyList()
        private set
    var lastStatus: ObjectNode? = null
        private set
    var activeBootId: String? = null
        private set
    /** Historical identity only, never a restored execution claim. */
    private val restoredAppliedBootId: String? = store.applicationsFor(flightId)
        .lastOrNull()?.get("edge_boot_id")?.takeIf { it.isTextual }?.asText()
    var sessionActive: Boolean = false
        private set

    /** Latest parsed RAW report for diagnostics, including foreign or
     * unbound installations. Current-task UI and receipt clocks MUST use
     * [boundRuntimeStatus]; neither this report nor sessionActive alone
     * establishes current mission execution.
     */
    var runtimeStatus: EdgeRuntimeStatus = EdgeRuntimeStatus.unknown(clock())
        private set

    /** Latest report bound to this mission's actual installation. Raw
     * [runtimeStatus] remains diagnostics, never current-task UI evidence. */
    val boundRuntimeStatus: EdgeRuntimeStatus?
        get() = runtimeStatus.takeIf { installBindingOk && !needsRevalidation }

    /**
     * True only when the Edge confirmed actual frame processing: a positive
     * `processed_frame_count` WITH the engine's own success signal
     * (`input_state.engine == "ok"` on a bound install — a detector ran
     * and its last attempt did not fail) while the runtime is executing
     * (RUNNING or DEGRADED). False on ACKs, on `running=true` alone, on
     * STARTING with zero frames, and — critically — on bare positive
     * counts with engine absent/failed/unknown: counts track attempts
     * (including cadence skips and inference failures), not successful AI
     * inference. A DEGRADED geometry-hold with engine ok and a real count
     * still confirms (the hold withholds zone verdicts, never detection).
     */
    var analysisConfirmed: Boolean = false
        private set

    /**
     * UI-ready notion: a session that is both confirmed executing AND
     * confirmed processing. False unless the Edge reported actual frame
     * processing.
     */
    val sessionReady: Boolean get() = sessionActive && analysisConfirmed

    /**
     * Runtime generation that confirmed the current session. Recorded when
     * a RUNNING/DEGRADED report first confirms execution; compared on
     * every later report (contract §5 reconnect rule).
     */
    var recordedRuntimeEpoch: String? = null
        private set

    /**
     * The reported `runtime_epoch` differs from [recordedRuntimeEpoch]:
     * the generation that confirmed our session is gone (Edge restart).
     * The operator restarts the cycle explicitly (prepare/apply/start) —
     * the controller never auto-recovers. Set on the foreign report;
     * cleared when a fresh-generation APPLY lands ([handle] records the
     * new generation's application receipt) or when a fresh session
     * starts. A start while set is refused ([startSession]) — the flag
     * alone never authorizes a SESSION_START against a wiped install.
     */
    var needsRevalidation: Boolean = false
        private set

    /**
     * The drone link dropped while a session was active or pending.
     * Set via [noteLinkLost] (the app's link monitor calls it); cleared
     * when a new session starts and when a STOP is confirmed. Volatile:
     * the link callback may arrive on the link-reader thread while the
     * mission loop reads it elsewhere.
     */
    @Volatile
    var linkInterruptedDuringSession: Boolean = false
        private set

    // -- session-scoped execution-evidence latches (for ExecutionFacts) ----
    // The legacy `running` bool NEVER sets these: only `runtime_state` and
    // engine-backed frame evidence are execution evidence. `saw*` flags
    // distinguish "reported, never successful" (FAILED basis) from "never
    // reported" (UNKNOWN basis). A bare frame count is attempts, not
    // inference: `confirmedProcessing` latches only on count + engine-ok;
    // `sawFrameCount` latches on counts the engine graded ok-or-failed
    // (engine-absent counts are no basis either way).
    private var confirmedExecuting = false
    private var confirmedProcessing = false
    private var sawRuntimeState = false
    private var sawFrameCount = false
    private var lastConfigApplied: Boolean? = null
    private var durableEvidenceFailure: Boolean? = null
    private var closedCleanly = false

    /** Latest Edge-reported running flag (MISSION_STATUS.running), or null. */
    var edgeReportedRunning: Boolean? = null
        private set
    val ignoredStale = mutableListOf<IgnoredStale>()

    val bundleId: String get() = bundle.get("bundle_id").asText()
    val bundleSha: String get() = bundle.get("bundle_sha256").asText()

    /**
     * Installed-rule/zone facts: the Edge installs exactly the bundle's
     * rules, so the counts are read from the bundle the RC itself applied
     * (`risk_policy.rules[]`, `mission_snapshot.zones[]`). Missing or
     * non-array sections count as zero — never inferred, never optimistic.
     *
     * [appliedRuleCount] is the RAW rule count for display. It says nothing
     * about what actually evaluates: ACTIVE rules are refused edge-side and
     * OFF rules are installed but inert, so never label the raw count as
     * "(SHADOW)". Use [appliedShadowRuleCount] (and the kind splits) plus
     * [riskEvaluationUnavailableReason] for availability wording.
     */
    val appliedRuleCount: Int get() = countRules(bundle)
    val appliedZoneCount: Int get() = countZones(bundle)

    /**
     * Installed SHADOW rules by kind (valid kinds only — anything else
     * fails the Edge install, see [EffectiveConfigHash]). Only these can
     * ever evaluate: [appliedShadowRuleCount] is the honest
     * "위험 규칙 N건" basis, [appliedShadowProximityRuleCount] and
     * [appliedShadowZoneRuleCount] its splits.
     */
    val appliedShadowRuleCount: Int get() = countShadowRules(bundle, null)
    val appliedShadowProximityRuleCount: Int
        get() = countShadowRules(bundle, "PERSON_EQUIPMENT_PROXIMITY")
    val appliedShadowZoneRuleCount: Int
        get() = countShadowRules(bundle, "ZONE_INTRUSION")

    /** OFF-mode rules in the applied bundle: installed but inert, never
     *  evaluating. Counted in the raw [appliedRuleCount], excluded from
     *  every availability claim. */
    val appliedOffRuleCount: Int get() = countModeRules(bundle, "OFF")

    /** Enabled zones in the applied bundle (`enabled == true`). A SHADOW
     *  zone-intrusion rule needs at least one of these to judge anything. */
    val appliedEnabledZoneCount: Int get() = countEnabledZones(bundle)

    /**
     * ACTIVE-mode rules present in the applied bundle. They count in
     * [appliedRuleCount] (a rule is installed), but the RC NEVER presents
     * evaluation as ACTIVE: ACTIVE installs are refused edge-side, and no
     * RC API claims an ACTIVE evaluation. The app layer must not word
     * availability as active enforcement; use this flag to warn, not to
     * claim.
     */
    val appliedActiveRuleCount: Int get() = countActiveRules(bundle)
    val hasActiveModeRule: Boolean get() = appliedActiveRuleCount > 0

    /**
     * Honest risk-evaluation predicate: true ONLY when the Edge reported
     * the config applied ([EdgeRuntimeStatus.configApplied] == true, whose
     * `effective_config_hash` is the receipt), the install binding is
     * proven ([installBindingOk]: the reported `applied_bundle_sha256`
     * names our bundle AND the reported install hash matches what our
     * bundle installs), AND at least one installed SHADOW rule can actually
     * judge (proximity, or zone-intrusion with enabled zones and no
     * `zone_geometry_unavailable` hold). A zero-rule bundle means NO risk
     * evaluation is running even if observation processing is — showing
     * "risk evaluation running" then would be the same class of error as
     * showing 0 detections as safety. ACTIVE/OFF-only bundles, unknown
     * bundle facts, legacy Edge builds (no `config_applied` report),
     * rejections and zero-rule bundles all read false — never optimistic.
     */
    val riskEvaluationAvailable: Boolean
        get() = riskEvaluationUnavailableReason == null

    /**
     * Why [riskEvaluationAvailable] is false, null when it is true.
     * Names the ACTIVE/OFF-only case and the withheld zone judgments
     * explicitly so the UI never shows "risk evaluation running" on an
     * inert or held install.
     */
    val riskEvaluationUnavailableReason: String?
        get() = when {
            appliedShadowRuleCount == 0 ->
                if (appliedRuleCount == 0) {
                    "no risk rules in applied bundle " +
                        "(0 risk_policy.rules[] installed: no risk evaluation running)"
                } else {
                    "applied rules are ACTIVE/OFF-only " +
                        "(no installed SHADOW evaluation running)"
                }
            lastConfigApplied != true ->
                "config application unreported by Edge " +
                    "(config_applied=${lastConfigApplied?.toString() ?: "unknown"})"
            !installBindingOk ->
                "installed config does not match the applied bundle " +
                    "(install binding unproven: applied_bundle_sha256/effective_config_hash)"
            appliedShadowProximityRuleCount == 0 && !zoneEvaluationPossible ->
                "zone-intrusion rules installed but zone judgments withheld " +
                    "(${zoneHoldDetail})"
            else -> null
        }

    /**
     * A SHADOW zone-intrusion rule can judge only with at least one enabled
     * zone installed and no `zone_geometry_unavailable` hold reported by
     * the Edge. A withheld zone never reads as a valid intrusion judgment.
     */
    private val zoneEvaluationPossible: Boolean
        get() = appliedShadowZoneRuleCount > 0 &&
            appliedEnabledZoneCount > 0 &&
            !runtimeStatus.degradedReasons.contains(ZONE_GEOMETRY_UNAVAILABLE)

    private val zoneHoldDetail: String
        get() = if (appliedEnabledZoneCount == 0) {
            "no enabled zones installed"
        } else {
            "zone_geometry_unavailable reported by Edge"
        }

    /**
     * The last adopted MISSION_STATUS proved our bundle installed AND its
     * reported install content matches what our bundle installs (see
     * [onRuntimeStatus]). Cleared on every foreign/unbound/mismatched
     * report and on every session reset — never latched from a report
     * that does not name our install.
     */
    private var installBindingOk = false
    val sessionId: String?
        get() = activeBootId?.let {
            Uuid5.of(Mop.NAMESPACE, "session|$flightId|$it").toString()
        }

    private data class ApplyCtx(val bundleId: String, val bundleSha: String)

    private enum class SessionCommand { START, STOP }

    private var applying: ApplyCtx? = null
    private var pendingSession: SessionCommand? = null
    private var lastStatusQueryNs: Long? = null
    /** Msg id of the coalesced MISSION_STATUS_QUERY still unacked, or null. */
    private var pendingStatusQueryMsgId: String? = null
    private var sessionStartMsgId: String? = null
    private var sessionStopMsgId: String? = null

    private fun transition(newState: MissionState, reason: String) {
        state = newState
        transitions.add(StateTransition(newState, reason, clock()))
    }

    // -- commands -----------------------------------------------------------

    fun prepare() {
        check(
            state == MissionState.IDLE || state == MissionState.STOPPED ||
                state == MissionState.REJECTED || needsRevalidation,
        ) { "prepare is not valid from $state" }
        if (needsRevalidation) {
            // Explicit, user-driven restart of the cycle after an epoch
            // change: the old generation is abandoned, a new one begins.
            // needsRevalidation itself clears when the fresh-generation
            // APPLY lands (see handle) — re-preparing alone recovers
            // nothing, and starting stays refused until then.
            resetSessionEvidence()
        }
        transition(MissionState.PREPARING, "MISSION_PREPARE sent")
        val payload = Mop.json.createObjectNode()
        payload.set<JsonNode>("bundle", bundle)
        endpoint.sendControl("MISSION_PREPARE", payload)
    }

    fun apply() {
        if (applying != null) return // same digest already in flight: converge via status
        if (state == MissionState.APPLIED) {
            return // already applied: a repeated APPLY converges, never re-sessions
        }
        check(state == MissionState.PREPARED) { "apply is not valid from $state" }
        applying = ApplyCtx(bundleId, bundleSha)
        transition(MissionState.APPLYING, "MISSION_APPLY sent")
        val payload = Mop.json.createObjectNode()
        payload.put("bundle_id", bundleId)
        payload.put("bundle_sha256", bundleSha)
        endpoint.sendControl("MISSION_APPLY", payload)
    }

    /** Idempotent reconcile: never marks READY by sending. */
    fun reconcile() {
        if (state == MissionState.APPLYING) {
            transition(MissionState.STATUS_UNKNOWN, "apply reply lost; reconciling")
        }
        // Coalesce with a live poll: at most one query is ever pending.
        // Skipping a redundant send loses nothing — the retry loop
        // (re)transmits the pending query under the same msg_id.
        if (pendingStatusQueryMsgId == null) {
            sendStatusQuery(clock())
        }
    }

    fun startSession() {
        if (state == MissionState.SESSION_PENDING && pendingSession == SessionCommand.START) {
            return // already requested: converge on the reported state, never re-session
        }
        check(state == MissionState.APPLIED) { "startSession is not valid from $state" }
        // Epoch gate (H5): after an Edge restart the stale APPLIED state
        // survives but the install is wiped. Starting here would send
        // SESSION_START against an install that no longer exists. Refuse
        // until a fresh-generation APPLY adopts the new generation (that
        // landing clears the flag — see [handle]); no deadlock, because
        // prepare/apply stay available while flagged and only the start
        // is gated.
        check(!needsRevalidation) {
            "edge generation changed (restart/reinstall); re-run prepare + apply before start"
        }
        if (sessionStartMsgId != null) {
            // Already sent and unacked: the endpoint retries the SAME
            // msg_id (Edge dedups by msg_id). A second envelope with a new
            // id would look like a second session request.
            return
        }
        // A fresh start reaches here only after a fresh APPLIED (a rebooted
        // Edge cannot reconcile the old digest), so starting clears the
        // revalidation demand: the re-apply it demanded has happened (the
        // fresh-generation APPLY landing already cleared it — this is the
        // belt-and-suspenders for the same-generation case).
        resetSessionEvidence()
        needsRevalidation = false
        sessionStartMsgId =
            endpoint.sendControl("SESSION_START", Mop.json.createObjectNode())
                .get("msg_id").asText()
    }

    fun stopSession(reconcileUnknown: Boolean = false) {
        if (state == MissionState.SESSION_PENDING && pendingSession == SessionCommand.STOP) {
            return // already requested: converge on the reported state
        }
        check(
            reconcileUnknown || sessionActive || pendingSession == SessionCommand.START ||
                (needsRevalidation &&
                    (state == MissionState.RUNNING || state == MissionState.SESSION_PENDING)),
        ) {
            "no active or pending session to stop"
        }
        if (sessionStopMsgId != null) return // already sent, unacked: retry reuses the id
        sessionStartMsgId = null // a late START-ACK must not override the stop
        sessionStopMsgId =
            endpoint.sendControl("SESSION_STOP", Mop.json.createObjectNode())
                .get("msg_id").asText()
    }

    /**
     * Drone-link loss observed by the app's link monitor. Counted as a
     * session interruption only while a session is active or pending —
     * link loss with no session in flight is not session evidence.
     */
    fun noteLinkLost() {
        if (sessionActive || pendingSession != null) {
            linkInterruptedDuringSession = true
        }
    }

    /**
     * Link-snapshot entry point for the app's [ksi.safetyedge.rc.LinkStateMonitor]
     * listener (rc-core must not depend on rc-ports, so the app passes
     * `snapshot.lost`): a real drop counts exactly like [noteLinkLost],
     * and a regained link is not session evidence at all. Post-close drops
     * stay uncounted via the same guard as [noteLinkLost].
     */
    fun noteLinkSnapshot(lost: Boolean) {
        if (lost) noteLinkLost()
    }

    /**
     * Periodic live-status poll for the RC-side mission/link loop (contract
     * §5: the displayed runtime state is live, not only the moment of a
     * response). Call this from the retransmission timer — off the UI
     * thread — every time it fires.
     *
     * Bounded and coalesced: at most ONE query is ever pending. While a
     * query is still unacknowledged (e.g. a long offline stretch) further
     * ticks send nothing — the retry loop owns the pending envelope and
     * (re)transmits it under the SAME msg_id on reconnect, so the link
     * never floods and no stale backlog resurrects. The interval gate
     * additionally spaces fresh queries; both use the caller-supplied
     * [intervalNs] (default [DEFAULT_STATUS_INTERVAL_NS]) — no invented
     * thresholds.
     *
     * Sends MISSION_STATUS_QUERY only while a session is in flight
     * ([sessionActive] or a pending SESSION_START/STOP): idle, preparing,
     * applied-but-not-started, stopped and rejected states emit nothing.
     * A query is idempotent and NEVER advances the state machine — sending
     * only enqueues an envelope, and a non-matching MISSION_STATUS reply
     * stays diagnostic-only (see [handle]). Polling never blocks START or
     * STOP: session commands send and converge independently of any
     * pending query.
     *
     * @return true when a query was sent.
     */
    fun pollTick(
        nowNs: Long = clock(),
        intervalNs: Long = DEFAULT_STATUS_INTERVAL_NS,
        reconcileUnresolved: Boolean = false,
    ): Boolean {
        require(intervalNs > 0) { "poll interval must be positive" }
        if (!sessionActive && pendingSession == null && !reconcileUnresolved) return false
        if (pendingStatusQueryMsgId != null) return false
        val last = lastStatusQueryNs
        if (last != null && nowNs - last < intervalNs) return false
        return sendStatusQuery(nowNs)
    }

    /** Enqueue one MISSION_STATUS_QUERY and track it until its ACK. */
    private fun sendStatusQuery(nowNs: Long): Boolean {
        lastStatusQueryNs = nowNs
        pendingStatusQueryMsgId = endpoint.sendControl(
            "MISSION_STATUS_QUERY", Mop.json.createObjectNode(),
        ).get("msg_id").asText()
        return true
    }

    /** Clear per-session evidence at the start of a new session cycle. */
    private fun resetSessionEvidence() {
        pendingSession = null
        applying = null
        lastStatusQueryNs = null
        // A fresh cycle starts with a clean poll slate: a stale unacked
        // query must never clear (or impersonate) the new cycle's poll.
        pendingStatusQueryMsgId = null
        installBindingOk = false
        sessionStartMsgId = null
        sessionStopMsgId = null
        // Abandon the recorded generation: the next confirmation records
        // the generation that actually confirmed the new session. This is
        // also what lets a post-reboot cycle adopt the new epoch instead
        // of flagging it as foreign forever.
        recordedRuntimeEpoch = null
        sessionActive = false
        analysisConfirmed = false
        confirmedExecuting = false
        confirmedProcessing = false
        sawRuntimeState = false
        sawFrameCount = false
        lastConfigApplied = null
        durableEvidenceFailure = null
        closedCleanly = false
        linkInterruptedDuringSession = false
    }

    /** RC-local execution facts for [SessionResultClassifier]. */
    fun executionFacts(): ExecutionFacts = ExecutionFacts(
        configApplied = lastConfigApplied,
        runtimeConfirmed = when {
            confirmedExecuting -> true
            sawRuntimeState -> false
            else -> null
        },
        frameProcessingObserved = when {
            confirmedProcessing -> true
            sawFrameCount -> false
            else -> null
        },
        stopConfirmed = if (closedCleanly) true else null,
        runtimeEpochChanged = needsRevalidation,
        linkInterrupted = linkInterruptedDuringSession,
        appliedRuleCount = appliedRuleCount,
        appliedZoneCount = appliedZoneCount,
        riskEvaluationAvailable = riskEvaluationAvailable,
        durableEvidenceFailure = durableEvidenceFailure,
    )

    private fun onCommandAcked(ackOf: String) {
        // A query ACK clears the coalesced poll — the next interval tick
        // may send a fresh one. A stale ACK (already-honored message, or a
        // previous cycle's query) matches nothing pending and clears nothing.
        if (ackOf == pendingStatusQueryMsgId) {
            pendingStatusQueryMsgId = null
        }
        when (ackOf) {
            sessionStartMsgId -> {
                pendingSession = SessionCommand.START
                transition(
                    MissionState.SESSION_PENDING,
                    "SESSION_START acked; edge running state unconfirmed",
                )
            }
            sessionStopMsgId -> {
                pendingSession = SessionCommand.STOP
                transition(
                    MissionState.SESSION_PENDING,
                    "SESSION_STOP acked; edge stopped state unconfirmed",
                )
            }
        }
    }

    // -- inbound control handling ------------------------------------------

    override fun handle(env: ObjectNode, payload: ObjectNode): String? {
        val boot = env.get("edge_boot_id")?.takeIf { !it.isNull }?.asText()
        val type = env.get("type").asText()
        if (type in Mop.CONTROL_MESSAGE_TYPES && env.get("flight_id")?.asText() != flightId) {
            ignoredStale.add(IgnoredStale(type, env.get("msg_id")?.asText() ?: "?", "foreign control flight"))
            return null
        }
        if (type in Mop.DATA_MESSAGE_TYPES) {
            val data = dataHandler
                ?: return "no evidence handler configured for $type"
            return data.handle(env, payload)
        }
        return when (type) {
            "MISSION_PREPARED" -> {
                if (payload.get("bundle_sha256")?.asText() != bundleSha) {
                    return "MISSION_PREPARED digest mismatch"
                }
                boot?.let { activeBootId = it }
                transition(MissionState.PREPARED, "MISSION_PREPARED")
                null
            }

            "MISSION_APPLY_REJECTED" -> {
                lastRejectionReasons =
                    payload.get("reasons")?.map { it.asText() } ?: emptyList()
                applying = null
                lastConfigApplied = false
                transition(
                    MissionState.REJECTED,
                    "MISSION_APPLY_REJECTED: $lastRejectionReasons",
                )
                null
            }

            "MISSION_APPLIED" -> {
                val ctx = applying
                if (ctx == null) {
                    ignoredStale.add(
                        IgnoredStale(type, env.get("msg_id").asText(),
                            "no pending apply (late/retried response)"),
                    )
                    return null // ACK it: the Edge did its part; record nothing.
                }
                val appBoot = boot ?: return "MISSION_APPLIED without edge_boot_id"
                val applicationId = payload.get("application_id")?.asText()
                    ?: return "MISSION_APPLIED without application_id"
                val app = buildApplication(
                    appBoot, applicationId,
                    payload.get("effective_edge_seq")?.asLong() ?: 0L,
                    env.get("sent_monotonic_ns").asLong(), ctx,
                )
                store.recordApplication(app)
                lastApplication = app
                applying = null
                activeBootId = appBoot
                transition(MissionState.APPLIED, "MISSION_APPLIED")
                // Fresh-generation adoption: the Edge receipt binds our
                // apply to a live boot, so a pending epoch demand is met —
                // a later start is authorized (see [startSession]).
                needsRevalidation = false
                null
            }

            "MISSION_STATUS" -> {
                lastStatus = payload
                onRuntimeStatus(payload)
                val ctx = applying
                val statusSha =
                    payload.get("applied_bundle_sha256")?.takeIf { !it.isNull }?.asText()
                val applicationId =
                    payload.get("application_id")?.takeIf { !it.isNull }?.asText()
                val statusBoot =
                    payload.get("edge_boot_id")?.takeIf { !it.isNull }?.asText() ?: boot
                val modern = payload.has("runtime_state") || payload.has("runtime_epoch") || payload.has("config_applied")
                val installedForReconcile = !modern || boundRuntimeStatus?.configApplied == true
                if (ctx != null && installedForReconcile && statusSha == ctx.bundleSha &&
                    applicationId != null && statusBoot != null
                ) {
                    val app = buildApplication(
                        statusBoot, applicationId,
                        payload.get("effective_edge_seq")?.asLong() ?: 0L,
                        env.get("sent_monotonic_ns").asLong(), ctx,
                    )
                    store.recordApplication(app)
                    lastApplication = app
                    applying = null
                    activeBootId = statusBoot
                    transition(MissionState.APPLIED, "reconciled via MISSION_STATUS")
                    // Same adoption as an explicit MISSION_APPLIED: the
                    // report binds our install (sha + app id + boot), so the
                    // new generation is proven live — the epoch demand is met.
                    needsRevalidation = false
                }
                // A non-matching status is diagnostics only: no state change,
                // no record (stale or another flight's apply).
                null
            }

            else -> "unsupported control type $type"
        }
    }

    /**
     * Converge the session state onto the Edge's reported runtime facts
     * (contract §3/§5). When `runtime_state` is present it is authoritative;
     * otherwise the legacy `running` bool drives the same transitions as
     * before (old Edge builds). Epoch mismatch freezes convergence: the
     * foreign generation's report is shown ([runtimeStatus]) but never
     * adopted as our session's state.
     *
     * Install binding (contract §4/§5): facts are adopted ONLY when the
     * report names OUR installed bundle (`applied_bundle_sha256` equals
     * [bundleSha]) AND its install content matches what our bundle installs
     * (`effective_config_hash` equals [EffectiveConfigHash.expectedFor]).
     * Anything else — a foreign digest, no digest, or a content hash we
     * cannot reconcile — is display-only ([runtimeStatus] still updates):
     * it never latches foreign boot/bundle/config into [executionFacts]
     * or [riskEvaluationAvailable], never counts frames, never moves
     * session state. A content mismatch additionally demands explicit
     * revalidation (same operator action as an epoch change): an install
     * that is not what we sent is never trusted, never claimed.
     *
     * Stop exactness: with modern runtime fields, ONLY an explicit
     * `runtime_state == STOPPED` confirms a stop (STARTING,
     * CONFIG_APPLIED and REQUEST_ACCEPTED are never a clean stop, so a
     * STOP pending through them stays pending). The legacy bool path keeps
     * its protocol fallback (`running == false` updates MissionState), but
     * it cannot establish [closedCleanly] or a runtime close-out receipt.
     * Without typed, bound STOPPED there is no confirmed clean close.
     */
    private fun onRuntimeStatus(payload: ObjectNode) {
        val status = EdgeRuntimeStatus.parse(payload, clock())
        runtimeStatus = status
        installBindingOk = false // proven below, per report — never latched
        val reportedEpoch = status.runtimeEpoch
        val reportedBoot = payload.get("edge_boot_id")?.takeIf { it.isTextual }?.asText()
        val expectedBoot = activeBootId ?: restoredAppliedBootId
        if ((reportedEpoch != null && recordedRuntimeEpoch != null && reportedEpoch != recordedRuntimeEpoch) ||
            (reportedBoot != null && expectedBoot != null && reportedBoot != expectedBoot)
        ) {
            // A real process reboot clears its installation. Detect the
            // identity change BEFORE the missing-install/hash early return.
            needsRevalidation = true
            pendingSession = null
            sessionActive = false
            analysisConfirmed = false
            return
        }
        val installSha = payload.get("applied_bundle_sha256")
            ?.takeIf { it.isTextual }?.asText()
        if (installSha == null || installSha != bundleSha) {
            // Foreign install or nothing applied: display only. (This is the
            // "non-matching status" case of [handle]: diagnostics, no state
            // change, no record, no latches.)
            analysisConfirmed = false
            return
        }
        val reported = status.effectiveConfigHash
        val expected = EffectiveConfigHash.expectedFor(bundle)
        val modern = payload.has("runtime_state") || payload.has("runtime_epoch") || payload.has("config_applied")
        if (modern && reported == null) {
            analysisConfirmed = false
            return // Missing installation evidence is unknown, not a legacy success.
        }
        if (reported != null && reported != expected) {
            // The Edge reports install content we cannot reconcile with our
            // bundle — the install is not what we sent (or not what we can
            // prove we sent: a null expectation is equally unprovable).
            // Drop the live confirmation and demand explicit re-apply.
            if (!needsRevalidation) {
                needsRevalidation = true
                pendingSession = null
                sessionActive = false
                analysisConfirmed = false
            }
            return
        }
        installBindingOk = true

        status.configApplied?.let { lastConfigApplied = it }
        if (payload.has("degraded_reasons")) {
            if ("session_capacity_exceeded" in status.degradedReasons) {
                durableEvidenceFailure = true
            } else if (durableEvidenceFailure != true) {
                durableEvidenceFailure = false
            }
        }
        if (status.state != EdgeRuntimeState.UNKNOWN) sawRuntimeState = true
        // Engine-graded counts only: engine "failed" is positive evidence
        // of no successful inference; engine "absent" (or a missing engine
        // field) leaves no basis either way — the counts stay unlatched
        // (UNKNOWN), never true and never a fabricated false.
        val engine = status.inputState["engine"]
        if (status.processedFrameCount != null && (engine == "ok" || engine == "failed")) {
            sawFrameCount = true
        }

        val known = status.state != EdgeRuntimeState.UNKNOWN
        val executing = known && (status.state == EdgeRuntimeState.RUNNING ||
            status.state == EdgeRuntimeState.DEGRADED)
        // After RC restart the first bound report may already be STOPPED.
        // A positive count plus engine-ok proves historical processing,
        // without claiming that the runtime is CURRENTLY running.
        val stoppedProcessingProof = status.state == EdgeRuntimeState.STOPPED &&
            (status.processedFrameCount ?: 0L) > 0L && engine == "ok"
        if (executing || stoppedProcessingProof) {
            confirmedExecuting = true
            if (reportedEpoch != null && recordedRuntimeEpoch == null) {
                recordedRuntimeEpoch = reportedEpoch
            }
        }
        // Attempts are not inference: a positive count latches only with
        // the engine's own success signal ("ok": a detector ran and its
        // last attempt did not fail) while the runtime is executing, on a
        // bound install. Engine absent/failed/unknown, legacy reports, and
        // non-executing states never confirm analysis — no matter how high
        // the count climbs. A DEGRADED geometry-hold with engine ok still
        // confirms (the hold withholds zone verdicts, never detection).
        if ((status.processedFrameCount ?: 0L) > 0L && engine == "ok" && (executing || stoppedProcessingProof)) {
            confirmedProcessing = true
        }

        val legacyRunning = payload.get("running")
            ?.takeIf { it.isBoolean }?.asBoolean()
        legacyRunning?.let { edgeReportedRunning = it }
        // Authoritative when the Edge reports runtime_state; legacy bool
        // otherwise (old builds without the §3 fields).
        val active: Boolean? = when {
            known -> status.state == EdgeRuntimeState.RUNNING ||
                status.state == EdgeRuntimeState.DEGRADED
            else -> legacyRunning
        }
        if (active == null) return
        // Clean-stop exactness: modern fields require an explicit STOPPED
        // report (a STOP pending through STARTING/CONFIG_APPLIED/
        // REQUEST_ACCEPTED stays pending — none of those is "stopped").
        // The legacy bool updates protocol state only, not close-out proof.
        val cleanStop = (known && status.state == EdgeRuntimeState.STOPPED) ||
            (!known && !active)
        when {
            pendingSession == SessionCommand.START && active -> {
                pendingSession = null
                sessionActive = true
                if (reportedEpoch != null && recordedRuntimeEpoch == null) {
                    recordedRuntimeEpoch = reportedEpoch
                }
                transition(MissionState.RUNNING, "edge status confirms running")
            }
            pendingSession == SessionCommand.STOP && cleanStop -> {
                pendingSession = null
                sessionActive = false
                closedCleanly = known && status.state == EdgeRuntimeState.STOPPED
                linkInterruptedDuringSession = false
                transition(MissionState.STOPPED, "edge status confirms stopped")
            }
            pendingSession == null && state == MissionState.RUNNING && cleanStop -> {
                sessionActive = false
                closedCleanly = known && status.state == EdgeRuntimeState.STOPPED
                transition(MissionState.STOPPED, "edge status reports not running")
            }
            pendingSession == null && state == MissionState.STOPPED && active -> {
                sessionActive = true
                if (reportedEpoch != null && recordedRuntimeEpoch == null) {
                    recordedRuntimeEpoch = reportedEpoch
                }
                transition(MissionState.RUNNING, "edge status reports running")
            }
            pendingSession == null && state == MissionState.STOPPED &&
                known && status.state == EdgeRuntimeState.STOPPED -> {
                closedCleanly = true // Fresh typed proof after an unconfirmed legacy stop.
                sessionActive = false
            }
        }
        // Latched processing evidence AND currently executing. Latched (not
        // latest-report-only) so one count-less report cannot flicker the
        // UI; still false until the Edge EVER reported actual processing.
        analysisConfirmed = confirmedProcessing && sessionActive
    }

    private fun buildApplication(        bootId: String,
        applicationId: String,
        effectiveEdgeSeq: Long,
        sentNs: Long,
        ctx: ApplyCtx,
    ): ObjectNode {
        val app = Mop.json.createObjectNode()
        app.put("schema_version", 1)
        app.put("application_id", applicationId)
        app.put("flight_id", flightId)
        app.put("bundle_id", ctx.bundleId)
        app.put("bundle_sha256", ctx.bundleSha)
        app.put("edge_boot_id", bootId)
        app.put("effective_edge_seq", effectiveEdgeSeq)
        app.put("applied", true)
        app.set<JsonNode>("rejection_reasons", Mop.json.createArrayNode())
        app.set<JsonNode>("excluded_items", Mop.json.createArrayNode())
        app.put("applied_at_edge_monotonic_ns", sentNs)
        return app
    }
}

private fun countRules(bundle: ObjectNode): Int {
    val rules = bundle.get("risk_policy")?.get("rules")
    return if (rules != null && rules.isArray) rules.size() else 0
}

private fun countZones(bundle: ObjectNode): Int {
    val zones = bundle.get("mission_snapshot")?.get("zones")
    return if (zones != null && zones.isArray) zones.size() else 0
}

private fun countActiveRules(bundle: ObjectNode): Int {
    val rules = bundle.get("risk_policy")?.get("rules")
    if (rules == null || !rules.isArray) return 0
    var count = 0
    for (rule in rules) {
        if (rule.get("mode")?.asText() == "ACTIVE") count += 1
    }
    return count
}

private val INSTALLED_RULE_KINDS = setOf(
    "PERSON_EQUIPMENT_PROXIMITY", "ZONE_INTRUSION",
)

/** Rules of one mode (valid installable kinds only — see [EffectiveConfigHash]). */
private fun countModeRules(bundle: ObjectNode, mode: String): Int {
    val rules = bundle.get("risk_policy")?.get("rules")
    if (rules == null || !rules.isArray) return 0
    var count = 0
    for (rule in rules) {
        if (rule.get("mode")?.asText() == mode &&
            rule.get("kind")?.asText() in INSTALLED_RULE_KINDS
        ) {
            count += 1
        }
    }
    return count
}

private fun countShadowRules(bundle: ObjectNode, kind: String?): Int {
    val rules = bundle.get("risk_policy")?.get("rules")
    if (rules == null || !rules.isArray) return 0
    var count = 0
    for (rule in rules) {
        if (rule.get("mode")?.asText() != "SHADOW") continue
        val ruleKind = rule.get("kind")?.asText()
        if (ruleKind !in INSTALLED_RULE_KINDS) continue
        if (kind != null && ruleKind != kind) continue
        count += 1
    }
    return count
}

private fun countEnabledZones(bundle: ObjectNode): Int {
    val zones = bundle.get("mission_snapshot")?.get("zones")
    if (zones == null || !zones.isArray) return 0
    var count = 0
    for (zone in zones) {
        if (zone.get("enabled")?.asBoolean() == true) count += 1
    }
    return count
}
