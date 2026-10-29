package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ObservationResponseTest {
    private fun response(
        action: ObservationResponse.Action =
            ObservationResponse.Action.ESCALATE_REQUEST,
        delivery: ObservationResponse.DeliveryState =
            ObservationResponse.DeliveryState.REQUESTED,
        transmissionProof: String? = null,
        receiptProof: String? = null,
        completionProof: String? = null,
    ) = ObservationResponse.Response(
        responseId = "resp-1",
        candidateId = "cand-1",
        siteId = "site-1",
        sessionId = "sess-1",
        action = action,
        note = "짧은 메모",
        evidence = ObservationResponse.EvidenceRef(
            sourceId = "obs-1",
            receivedAtUtc = "2026-10-20T00:00:00Z",
            policyId = "policy-1",
            policyVersion = "v3",
        ),
        actedAtUtc = "2026-10-20T00:01:00Z",
        delivery = delivery,
        transmissionProof = transmissionProof,
        receiptProof = receiptProof,
        completionProof = completionProof,
    )

    @Test
    fun escalation_request_never_claims_sent_without_proof() {
        val requested = response(delivery = ObservationResponse.DeliveryState.REQUESTED)
        assertEquals(
            ObservationResponse.DeliveryState.REQUESTED,
            ObservationResponse.effectiveDelivery(requested),
        )
        // A stored SENT claim with no transmission proof degrades to Unknown.
        for (claimed in listOf(
            ObservationResponse.DeliveryState.SENT_CONFIRMED,
            ObservationResponse.DeliveryState.RECEIVED_CONFIRMED,
            ObservationResponse.DeliveryState.COMPLETED_CONFIRMED,
        )
        ) {
            val unproven = response(delivery = claimed)
            assertEquals(
                ObservationResponse.DeliveryState.UNKNOWN,
                ObservationResponse.effectiveDelivery(unproven),
                "unproven $claimed must degrade to UNKNOWN",
            )
            assertEquals(
                ObservationResponse.DeliveryState.UNKNOWN,
                ObservationResponse.requireProofForClaim(claimed, null, null, null),
            )
        }
        // Full proof chain sustains each claim.
        assertEquals(
            ObservationResponse.DeliveryState.SENT_CONFIRMED,
            ObservationResponse.effectiveDelivery(
                response(
                    delivery = ObservationResponse.DeliveryState.SENT_CONFIRMED,
                    transmissionProof = "tx-1",
                ),
            ),
        )
        assertEquals(
            ObservationResponse.DeliveryState.RECEIVED_CONFIRMED,
            ObservationResponse.effectiveDelivery(
                response(
                    delivery = ObservationResponse.DeliveryState.RECEIVED_CONFIRMED,
                    transmissionProof = "tx-1",
                    receiptProof = "rx-1",
                ),
            ),
        )
        assertEquals(
            ObservationResponse.DeliveryState.COMPLETED_CONFIRMED,
            ObservationResponse.effectiveDelivery(
                response(
                    delivery = ObservationResponse.DeliveryState.COMPLETED_CONFIRMED,
                    transmissionProof = "tx-1",
                    receiptProof = "rx-1",
                    completionProof = "done-1",
                ),
            ),
        )
        // Partial proof still degrades (receipt without transmission, etc.).
        assertEquals(
            ObservationResponse.DeliveryState.UNKNOWN,
            ObservationResponse.effectiveDelivery(
                response(
                    delivery = ObservationResponse.DeliveryState.RECEIVED_CONFIRMED,
                    receiptProof = "rx-1",
                ),
            ),
        )
    }

    @Test
    fun confirmation_does_not_clear_risk_or_authorize_active() {
        for (action in ObservationResponse.Action.entries) {
            assertFalse(
                ObservationResponse.clearsRisk(action),
                "$action must never clear risk",
            )
            assertFalse(
                ObservationResponse.authorizesActive(action),
                "$action must never authorize ACTIVE",
            )
        }
        // Non-escalation actions carry no transmission state, even if stored so.
        val confirm = response(
            action = ObservationResponse.Action.CONFIRM,
            delivery = ObservationResponse.DeliveryState.SENT_CONFIRMED,
            transmissionProof = "tx-1",
        )
        assertEquals(
            ObservationResponse.DeliveryState.LOCAL_ONLY,
            ObservationResponse.effectiveDelivery(confirm),
        )
    }

    @Test
    fun delivery_claim_strings_keep_the_denial_clause() {
        assertEquals(
            "전달 요청만 저장됨 · 전송 완료 아님",
            ObservationResponse.operatorClaim(ObservationResponse.DeliveryState.LOCAL_ONLY),
        )
        assertEquals(
            "전달 요청 저장됨 · 전송되지 않음",
            ObservationResponse.operatorClaim(ObservationResponse.DeliveryState.REQUESTED),
        )
        assertEquals(
            "전송 확인됨 · 수신 미확인",
            ObservationResponse.operatorClaim(ObservationResponse.DeliveryState.SENT_CONFIRMED),
        )
        assertEquals(
            "상대방 수신 확인됨 · 현장 조치 미확인",
            ObservationResponse.operatorClaim(
                ObservationResponse.DeliveryState.RECEIVED_CONFIRMED,
            ),
        )
        assertEquals(
            "현장 조치 완료 확인됨",
            ObservationResponse.operatorClaim(
                ObservationResponse.DeliveryState.COMPLETED_CONFIRMED,
            ),
        )
        assertEquals(
            "상태 미확인 · 전송·수신·완료 주장 불가",
            ObservationResponse.operatorClaim(ObservationResponse.DeliveryState.UNKNOWN),
        )
    }

    @Test
    fun unknown_when_proof_missing_even_for_response_claim_string() {
        val unproven = response(
            delivery = ObservationResponse.DeliveryState.SENT_CONFIRMED,
        )
        assertEquals(
            "상태 미확인 · 전송·수신·완료 주장 불가",
            ObservationResponse.operatorClaim(unproven),
        )
    }
}
