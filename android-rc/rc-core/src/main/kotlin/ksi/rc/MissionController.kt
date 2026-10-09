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
 *    not claim more. [sessionActive] follows the confirmed state. The
 *    session id is derived as `uuid5(NAMESPACE, "session|flight|boot")`.
 * 6. Message ids come from a durable monotonic counter ([RcStore
 *    nextMsgCounter]): ids are never reused after an RC process restart
 *    (the Edge dedups by msg_id, so a reused id would silence a new
 *    command).
 *
 * NOTE: this is the KSI message contract on top of a transport. DJI MOP (or
 * a test link) is only how bytes travel; nothing here proves the real
 * aircraft/RC link works (see ledger §9).
 */
class MissionController(
    private val flightId: String,
    private val bundle: ObjectNode,
    private val store: RcStore,
    private val clock: () -> Long,
    /** Evidence-plane handler (A3); control types never reach it. */
    private val dataHandler: ControlHandler? = null,
) : ControlHandler {
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
    var sessionActive: Boolean = false
        private set

    /** Latest Edge-reported running flag (MISSION_STATUS.running), or null. */
    var edgeReportedRunning: Boolean? = null
        private set
    val ignoredStale = mutableListOf<IgnoredStale>()

    val bundleId: String get() = bundle.get("bundle_id").asText()
    val bundleSha: String get() = bundle.get("bundle_sha256").asText()
    val sessionId: String?
        get() = activeBootId?.let {
            Uuid5.of(Mop.NAMESPACE, "session|$flightId|$it").toString()
        }

    private data class ApplyCtx(val bundleId: String, val bundleSha: String)

    private enum class SessionCommand { START, STOP }

    private var applying: ApplyCtx? = null
    private var pendingSession: SessionCommand? = null
    private var sessionStartMsgId: String? = null
    private var sessionStopMsgId: String? = null

    private fun transition(newState: MissionState, reason: String) {
        state = newState
        transitions.add(StateTransition(newState, reason, clock()))
    }

    // -- commands -----------------------------------------------------------

    fun prepare() {
        check(state == MissionState.IDLE || state == MissionState.STOPPED ||
            state == MissionState.REJECTED
        ) { "prepare is not valid from $state" }
        transition(MissionState.PREPARING, "MISSION_PREPARE sent")
        val payload = Mop.json.createObjectNode()
        payload.set<JsonNode>("bundle", bundle)
        endpoint.sendControl("MISSION_PREPARE", payload)
    }

    fun apply() {
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
        endpoint.sendControl("MISSION_STATUS_QUERY", Mop.json.createObjectNode())
    }

    fun startSession() {
        check(state == MissionState.APPLIED) { "startSession is not valid from $state" }
        sessionStopMsgId = null // the newest command governs ACK handling
        sessionStartMsgId =
            endpoint.sendControl("SESSION_START", Mop.json.createObjectNode())
                .get("msg_id").asText()
    }

    fun stopSession() {
        check(sessionActive || pendingSession == SessionCommand.START) {
            "no active or pending session to stop"
        }
        sessionStartMsgId = null // a late START-ACK must not override the stop
        sessionStopMsgId =
            endpoint.sendControl("SESSION_STOP", Mop.json.createObjectNode())
                .get("msg_id").asText()
    }

    private fun onCommandAcked(ackOf: String) {
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
                null
            }

            "MISSION_STATUS" -> {
                lastStatus = payload
                val ctx = applying
                val statusSha =
                    payload.get("applied_bundle_sha256")?.takeIf { !it.isNull }?.asText()
                val applicationId =
                    payload.get("application_id")?.takeIf { !it.isNull }?.asText()
                val statusBoot =
                    payload.get("edge_boot_id")?.takeIf { !it.isNull }?.asText() ?: boot
                if (ctx != null && statusSha == ctx.bundleSha &&
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
                }
                payload.get("running")?.takeIf { it.isBoolean }?.asBoolean()
                    ?.let { reported ->
                        edgeReportedRunning = reported
                        when {
                            pendingSession == SessionCommand.START && reported -> {
                                pendingSession = null
                                sessionActive = true
                                transition(
                                    MissionState.RUNNING,
                                    "edge status confirms running",
                                )
                            }
                            pendingSession == SessionCommand.STOP && !reported -> {
                                pendingSession = null
                                sessionActive = false
                                transition(
                                    MissionState.STOPPED,
                                    "edge status confirms stopped",
                                )
                            }
                            pendingSession == null &&
                                state == MissionState.RUNNING && !reported -> {
                                sessionActive = false
                                transition(
                                    MissionState.STOPPED,
                                    "edge status reports not running",
                                )
                            }
                            pendingSession == null &&
                                state == MissionState.STOPPED && reported -> {
                                sessionActive = true
                                transition(
                                    MissionState.RUNNING,
                                    "edge status reports running",
                                )
                            }
                        }
                    }
                // A non-matching status is diagnostics only: no state change,
                // no record (stale or another flight's apply).
                null
            }

            else -> "unsupported control type $type"
        }
    }

    private fun buildApplication(
        bootId: String,
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
