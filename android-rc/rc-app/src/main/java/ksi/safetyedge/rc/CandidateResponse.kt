package ksi.safetyedge.rc

import java.util.UUID
import ksi.rc.OperatorAction
import ksi.rc.OperatorActionArtifacts
import ksi.rc.OperatorActionLog

/**
 * Operator selection + response input for AI candidates on the integrated
 * operation screen (screen contract v1 §3-8, §4).
 *
 * Pure Kotlin, no Android: host-tested. It wires two existing pieces that
 * were never connected to a UI:
 *
 * - candidate tap -> [ShadowCandidateSelection] (rc-ports): selection opens
 *   the candidate detail and reports the source state (AVAILABLE / MISSING /
 *   STALE). It never changes control, policy, session or records (AT-02) —
 *   this function only reads the card list and returns a view.
 * - 확인/보류/전달 요청 -> [ObservationResponse] (rc-ports) +
 *   [OperatorActionLog] (rc-core, via [RcSession.operatorActions]): minimum
 *   input (action + optional note <= 280 chars) appended with the SELECTED
 *   event's actual boot session (resolved via [resolveEventSource], never
 *   the latest boot) and that boot's receipt basis. Nothing is fabricated:
 *   a missing flight, missing site, unselected candidate, or an event
 *   whose boot/receipt cannot be resolved is a [Outcome.Refused] with a
 *   reason, and nothing is written.
 *
 * Evidence honesty (contract §4-4/§4-5):
 * - policy id/version: the RC holds no observation-policy source, so both
 *   are null (truly unknown — never invented).
 * - delivery: CONFIRM/HOLD are stored LOCAL_ONLY; ESCALATE_REQUEST is
 *   stored REQUESTED (stored on this device, never claimed sent). The
 *   operator-facing claim comes from [ObservationResponse.operatorClaim]
 *   verbatim — SENT or above is never produced offline.
 */
object CandidateResponse {

    data class Selection(
        val candidateId: String,
        val source: ShadowCandidateSelection.SourceStatus,
    )

    /**
     * The SELECTED event's actual source: the boot holding it plus that
     * boot's RC receipt basis and the event's own policy revision (null
     * when the event carries none — never invented).
     */
    data class EventSource(
        val bootId: String,
        val receivedUtc: String,
        val policyRevisionId: String?,
    )

    /** Resolution of a selected candidate against the store. */
    sealed interface SourceResolution {
        /** The event's actual boot and receipt basis. */
        data class Found(val source: EventSource) : SourceResolution

        /** The event id is absent from every stored boot. */
        data object EventUnknown : SourceResolution

        /** The event is stored but its receipt basis is missing. */
        data object ReceiptUnknown : SourceResolution
    }

    /**
     * Resolves the SELECTED event's actual source: the event's own boot
     * (session linkage) and its stamped RC receipt basis (time), plus the
     * stored policy revision when the record carries one. The ONLY source
     * is the indexed per-event receipt ([ksi.rc.EvidenceStore.eventReceipt]):
     * the ACTUAL delivering boot and stamped receipt clock, never the
     * latest boot/time, never a boot-window fallback. A boot's receipt
     * window also includes other records, so its end is NOT a substitute
     * for this event's receipt. Unknown stays unknown and refuses the
     * write — a store without an indexed receipt refuses, never guesses.
     */
    fun resolveEventSource(
        store: ksi.rc.UploadStore,
        flightId: String,
        eventId: String?,
    ): SourceResolution {
        if (eventId.isNullOrBlank()) return SourceResolution.EventUnknown
        val receipt = store.eventReceipt(eventId) ?: return SourceResolution.EventUnknown
        if (receipt.flightId != flightId || receipt.bootId.isBlank()) {
            // Stored under another flight (or none): never cross-link
            // the response to this flight.
            return SourceResolution.EventUnknown
        }
        val received = receipt.receivedUtc
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { parseableUtc(it) }
            ?: return SourceResolution.ReceiptUnknown
        return SourceResolution.Found(
            EventSource(
                bootId = receipt.bootId,
                receivedUtc = received,
                policyRevisionId = receipt.policyRevisionId
                    ?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun parseableUtc(value: String): Boolean =
        runCatching { java.time.Instant.parse(value) }.isSuccess

    sealed interface Outcome {
        /** Exactly one record to append; [claim] is the verbatim operator line. */
        data class Ready(val action: OperatorAction, val claim: String) : Outcome
        /** Nothing may be written; [reason] is the operator-facing line. */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Tap-to-select: which candidate is selected and whether its source is
     * actually present and fresh. Pure read — no state change anywhere.
     */
    fun select(
        cards: List<ObservationModel.EventCard>,
        candidateId: String,
        observationAgeMs: Long?,
    ): Selection {
        val view = ShadowCandidateSelection.select(
            candidateId = candidateId,
            sourcePresent = cards.any { it.eventId == candidateId },
            sourceFresh = observationAgeMs != null &&
                observationAgeMs < ObservationModel.STALE_DATA_MS,
        )
        return Selection(candidateId = view.candidateId, source = view.source)
    }

    /**
     * Build the single operator-action record for [action], or refuse with
     * a reason. [source] is the SELECTED event's resolved source
     * ([resolveEventSource]): the session id follows the event's actual
     * boot and the evidence time follows that event's indexed receipt — the
     * latest boot/time is never used as a fallback, so responding to an
     * older candidate after newer boots cannot mis-stamp it. Unknown
     * source (event or receipt) refuses the write.
     */
    @Suppress("LongParameterList")
    fun buildResponse(
        flightId: String?,
        siteId: String?,
        source: SourceResolution?,
        eventId: String?,
        action: ObservationResponse.Action,
        note: String?,
        actedAtUtc: String,
        responseId: String = UUID.randomUUID().toString(),
    ): Outcome {
        if (flightId.isNullOrBlank()) {
            return Outcome.Refused("활성 KSI 세션 없음 · 응답을 연결할 기록이 없습니다. 기록하지 않았습니다.")
        }
        if (siteId.isNullOrBlank()) {
            return Outcome.Refused("현장 정보 없음 · 응답을 연결할 현장을 알 수 없습니다. 기록하지 않았습니다.")
        }
        if (eventId.isNullOrBlank()) {
            return Outcome.Refused("후보 정보 없음 · 선택된 후보를 확인할 수 없습니다. 기록하지 않았습니다.")
        }
        val resolved = when (source) {
            is SourceResolution.Found -> source.source
            SourceResolution.EventUnknown, null ->
                return Outcome.Refused("연결 증거 없음 · 선택한 후보가 저장소에 없습니다. 기록하지 않았습니다.")
            SourceResolution.ReceiptUnknown ->
                return Outcome.Refused("연결 증거 없음 · 선택한 후보의 수신 기록이 없어 응답을 작성할 수 없습니다. 기록하지 않았습니다.")
        }
        if (note != null && note.length > ObservationResponse.MAX_NOTE_CHARS) {
            return Outcome.Refused("의견 메모가 280자를 초과합니다. 기록하지 않았습니다.")
        }
        if (actedAtUtc.isBlank()) {
            return Outcome.Refused("시각 정보 없음 · 응답 시각을 알 수 없습니다. 기록하지 않았습니다.")
        }
        val delivery = when (action) {
            ObservationResponse.Action.CONFIRM -> ObservationResponse.DeliveryState.LOCAL_ONLY
            ObservationResponse.Action.HOLD -> ObservationResponse.DeliveryState.LOCAL_ONLY
            ObservationResponse.Action.ESCALATE_REQUEST -> ObservationResponse.DeliveryState.REQUESTED
        }
        val record = OperatorAction(
            responseId = responseId,
            candidateId = eventId,
            siteId = siteId,
            sessionId = OperatorActionArtifacts.sessionIdFor(flightId, resolved.bootId),
            action = action.name,
            note = note,
            evidenceSourceId = eventId,
            evidenceReceivedAtUtc = resolved.receivedUtc,
            // The event's own policy revision when it carries one; the RC
            // holds no other observation-policy source — never invented.
            policyId = resolved.policyRevisionId,
            policyVersion = null,
            actedAtUtc = actedAtUtc,
            delivery = delivery.name,
            transmissionProof = null,
            receiptProof = null,
            completionProof = null,
        )
        return Outcome.Ready(record, ObservationResponse.operatorClaim(delivery))
    }

    /** Delivery states the RC may store offline — never SENT or above. */
    fun offlineDeliveryStates(): Set<ObservationResponse.DeliveryState> =
        setOf(
            ObservationResponse.DeliveryState.LOCAL_ONLY,
            ObservationResponse.DeliveryState.REQUESTED,
        )

    /** Storage-name mapping shared with [OperatorActionLog] (no drift). */
    fun storedActionName(action: ObservationResponse.Action): String = when (action) {
        ObservationResponse.Action.CONFIRM -> OperatorActionLog.CONFIRM
        ObservationResponse.Action.HOLD -> OperatorActionLog.HOLD
        ObservationResponse.Action.ESCALATE_REQUEST -> OperatorActionLog.ESCALATE_REQUEST
    }

    fun storedDeliveryName(delivery: ObservationResponse.DeliveryState): String = when (delivery) {
        ObservationResponse.DeliveryState.LOCAL_ONLY -> OperatorActionLog.LOCAL_ONLY
        ObservationResponse.DeliveryState.REQUESTED -> OperatorActionLog.REQUESTED
        ObservationResponse.DeliveryState.SENT_CONFIRMED -> OperatorActionLog.SENT_CONFIRMED
        ObservationResponse.DeliveryState.RECEIVED_CONFIRMED -> OperatorActionLog.RECEIVED_CONFIRMED
        ObservationResponse.DeliveryState.COMPLETED_CONFIRMED -> OperatorActionLog.COMPLETED_CONFIRMED
        ObservationResponse.DeliveryState.UNKNOWN -> OperatorActionLog.UNKNOWN
    }
}
