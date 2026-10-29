package ksi.safetyedge.rc

/**
 * Operator response (대응) to an AI risk candidate during flight.
 *
 * Product definition (user-confirmed 2026-10-20): during flight the operator
 * confirms, holds, or requests field-team escalation on AI risk candidates
 * with minimum input. This model preserves target, time, location, observation
 * evidence, policy, and the operator's action offline so the Core can later
 * re-review the same event.
 *
 * Hard distinctions this model MUST keep honest (never collapse):
 * - 전달 요청(requested) ≠ 실제 전송(sent) ≠ 상대방 수신 확인(received)
 *   ≠ 현장 조치 완료(action completed). Offline a request is only stored
 *   locally and must NEVER be shown as sent/completed.
 * - Operator CONFIRM does not mean the risk cleared. A SHADOW candidate
 *   confirmation is not ACTIVE approval/activation.
 * - AI observation unavailable ≠ no risk. Record gaps stay gaps (a missing
 *   [EvidenceRef] must fail loudly, never read as "safe").
 *
 * Delivery-state tracking of assignments/receipts is explicitly deferred to
 * a future Core extension: [DeliveryState] names the states the RC can
 * *claim* today, marks everything above [DeliveryState.REQUESTED] as
 * requiring proof the RC does not yet produce, and [effectiveDelivery]
 * downgrades unproven claims to [DeliveryState.UNKNOWN].
 */
object ObservationResponse {

    /** KSI-side operator actions on an AI candidate (semantic names). */
    enum class Action {
        CONFIRM,
        HOLD,
        ESCALATE_REQUEST,
    }

    /**
     * Transmission/delivery state of the escalation request, kept as a
     * separate small type so no caller can mistake "stored" for "sent".
     *
     * Only [LOCAL_ONLY] and [REQUESTED] are producible offline. The
     * *_CONFIRMED states each require a corresponding proof reference (see
     * [ObservationResponse.transmissionProof] and friends); without it
     * [effectiveDelivery] reports [UNKNOWN].
     */
    enum class DeliveryState {
        /** Stored on this device only; nothing has left the RC. */
        LOCAL_ONLY,

        /** Operator asked for escalation; still on this device, not sent. */
        REQUESTED,

        /** Edge/Core acknowledged transmission (proof required). */
        SENT_CONFIRMED,

        /** Field team confirmed receipt (proof required). */
        RECEIVED_CONFIRMED,

        /** Field action completion confirmed (proof required). */
        COMPLETED_CONFIRMED,

        /** Proof missing or state not verifiable: no claim allowed. */
        UNKNOWN,
    }

    /**
     * The observation evidence this response refers to: which source frame/
     * event, when the RC received it, and which policy/version was in effect.
     * All three are required — a response without evidence is a gap record,
     * never a safety claim.
     */
    data class EvidenceRef(
        /** Source observation/candidate id (Edge observation or event id). */
        val sourceId: String,
        /** RC receipt wall-clock of the source (ISO-8601 UTC). */
        val receivedAtUtc: String,
        /** Policy id in effect when the operator acted (nullable only when truly unknown). */
        val policyId: String?,
        /** Policy version in effect (nullable only when truly unknown). */
        val policyVersion: String?,
    ) {
        init {
            require(sourceId.isNotBlank()) { "evidence source id must not be blank" }
            require(receivedAtUtc.isNotBlank()) { "evidence received time must not be blank" }
        }
    }

    /**
     * One operator response. Dependency-free: plain strings + enums so
     * rc-core/rc-app can use it without new dependencies.
     *
     * @param note short optional operator note (<= [MAX_NOTE_CHARS]).
     * @param transmissionProof reference proving the request left the RC
     *  (e.g. transport ACK id). Required to claim [DeliveryState.SENT_CONFIRMED]
     *  or above; null offline.
     * @param receiptProof reference proving the other party received it.
     *  Required to claim [DeliveryState.RECEIVED_CONFIRMED] or above.
     * @param completionProof reference proving field action completed.
     *  Required to claim [DeliveryState.COMPLETED_CONFIRMED].
     */
    data class Response(
        val responseId: String,
        val candidateId: String,
        val siteId: String,
        val sessionId: String,
        val action: Action,
        val note: String?,
        val evidence: EvidenceRef,
        /** Operator action wall-clock (ISO-8601 UTC). */
        val actedAtUtc: String,
        val delivery: DeliveryState,
        val transmissionProof: String? = null,
        val receiptProof: String? = null,
        val completionProof: String? = null,
    ) {
        init {
            require(responseId.isNotBlank()) { "response id must not be blank" }
            require(candidateId.isNotBlank()) { "candidate id must not be blank" }
            require(siteId.isNotBlank()) { "site id must not be blank" }
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(actedAtUtc.isNotBlank()) { "action time must not be blank" }
            if (note != null) {
                require(note.length <= MAX_NOTE_CHARS) {
                    "operator note must be <= $MAX_NOTE_CHARS chars"
                }
            }
        }
    }

    const val MAX_NOTE_CHARS = 280

    /**
     * Exact operator-facing claim string per delivery state. UI layers must
     * show these verbatim (or a reviewed translation carrying the same
     * denial) — paraphrases that drop the "아님/미확인" clause are forbidden.
     */
    fun operatorClaim(state: DeliveryState): String = when (state) {
        DeliveryState.LOCAL_ONLY -> "전달 요청만 저장됨 · 전송 완료 아님"
        DeliveryState.REQUESTED -> "전달 요청 저장됨 · 전송되지 않음"
        DeliveryState.SENT_CONFIRMED -> "전송 확인됨 · 수신 미확인"
        DeliveryState.RECEIVED_CONFIRMED -> "상대방 수신 확인됨 · 현장 조치 미확인"
        DeliveryState.COMPLETED_CONFIRMED -> "현장 조치 완료 확인됨"
        DeliveryState.UNKNOWN -> "상태 미확인 · 전송·수신·완료 주장 불가"
    }

    /** Claim string for a full response (applies [effectiveDelivery] first). */
    fun operatorClaim(response: Response): String =
        operatorClaim(effectiveDelivery(response))

    /**
     * What may actually be claimed for [response], after proof checks.
     * Non-escalation actions never carry a transmission state: they read as
     * [DeliveryState.LOCAL_ONLY]. An escalation claim above REQUESTED
     * without its proof reference degrades to [DeliveryState.UNKNOWN] —
     * a missing proof must yield Unknown, never the claim.
     */
    fun effectiveDelivery(response: Response): DeliveryState {
        if (response.action != Action.ESCALATE_REQUEST) return DeliveryState.LOCAL_ONLY
        return when (response.delivery) {
            DeliveryState.LOCAL_ONLY,
            DeliveryState.REQUESTED,
            DeliveryState.UNKNOWN,
            -> response.delivery
            DeliveryState.SENT_CONFIRMED ->
                if (!response.transmissionProof.isNullOrBlank()) response.delivery
                else DeliveryState.UNKNOWN
            DeliveryState.RECEIVED_CONFIRMED ->
                if (!response.transmissionProof.isNullOrBlank() &&
                    !response.receiptProof.isNullOrBlank()
                ) {
                    response.delivery
                } else {
                    DeliveryState.UNKNOWN
                }
            DeliveryState.COMPLETED_CONFIRMED ->
                if (!response.transmissionProof.isNullOrBlank() &&
                    !response.receiptProof.isNullOrBlank() &&
                    !response.completionProof.isNullOrBlank()
                ) {
                    response.delivery
                } else {
                    DeliveryState.UNKNOWN
                }
        }
    }

    /**
     * Rejects marking SENT/RECEIVED/COMPLETED without the corresponding
     * evidence reference. Returns the state that may be stored: the
     * requested state when its proof is present, [DeliveryState.UNKNOWN]
     * otherwise. [DeliveryState.LOCAL_ONLY]/[DeliveryState.REQUESTED]/
     * [DeliveryState.UNKNOWN] pass through (they assert no transmission).
     */
    fun requireProofForClaim(
        state: DeliveryState,
        transmissionProof: String?,
        receiptProof: String?,
        completionProof: String?,
    ): DeliveryState = when (state) {
        DeliveryState.LOCAL_ONLY,
        DeliveryState.REQUESTED,
        DeliveryState.UNKNOWN,
        -> state
        DeliveryState.SENT_CONFIRMED ->
            if (!transmissionProof.isNullOrBlank()) state else DeliveryState.UNKNOWN
        DeliveryState.RECEIVED_CONFIRMED ->
            if (!transmissionProof.isNullOrBlank() && !receiptProof.isNullOrBlank()) {
                state
            } else {
                DeliveryState.UNKNOWN
            }
        DeliveryState.COMPLETED_CONFIRMED ->
            if (!transmissionProof.isNullOrBlank() &&
                !receiptProof.isNullOrBlank() &&
                !completionProof.isNullOrBlank()
            ) {
                state
            } else {
                DeliveryState.UNKNOWN
            }
    }

    /**
     * An operator CONFIRM never clears the risk. Always false by contract:
     * callers must not branch on confirmation as if the hazard were gone.
     */
    fun clearsRisk(@Suppress("UNUSED_PARAMETER") action: Action): Boolean = false

    /**
     * A SHADOW candidate confirmation is never ACTIVE approval/activation.
     * Always false by contract.
     */
    fun authorizesActive(@Suppress("UNUSED_PARAMETER") action: Action): Boolean = false
}
