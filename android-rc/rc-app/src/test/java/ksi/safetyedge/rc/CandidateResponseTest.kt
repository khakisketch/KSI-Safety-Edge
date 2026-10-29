package ksi.safetyedge.rc

import java.nio.file.Files
import ksi.rc.OperatorActionArtifacts
import ksi.rc.OperatorActionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the integrated operation screen's candidate selection +
 * operator response input (screen contract v1 §3-8, §4; AT-01/AT-02):
 *
 * - selecting a candidate only opens its detail (AT-02 — nothing else
 *   changes) and reports which candidate is selected with its source
 *   state (AVAILABLE / MISSING / STALE);
 * - 확인/보류/전달 요청 writes exactly one record to the same
 *   [OperatorActionLog] the uploader ships, with the ACTIVE flight's
 *   session id, the observation evidence reference, and the honest
 *   offline delivery state — never SENT or above, never a cleared-risk
 *   or ACTIVE-approval claim;
 * - refusal (no session, no boot session, no evidence, over-long note)
 *   writes nothing.
 */
class CandidateResponseTest {

    private val flight = "00000000-0000-4000-8000-000000000001"
    private val site = "site-west-01"
    private val bootOld = "boot-old"
    private val bootNew = "boot-new"
    private val actedAt = "2026-10-20T14:03:00Z"

    private fun found(
        bootId: String,
        receivedUtc: String = "2026-10-20T14:02:11Z",
        policyRevisionId: String? = null,
    ) = CandidateResponse.SourceResolution.Found(
        CandidateResponse.EventSource(bootId, receivedUtc, policyRevisionId),
    )

    private fun card(
        eventId: String,
        mode: String = "SHADOW",
        eventType: String = "PROXIMITY_CANDIDATE",
    ) = ObservationModel.EventCard(
        eventId = eventId,
        eventType = eventType,
        mode = mode,
        detectedAtEdgeNs = 1L,
        observationQuality = "VALID",
        evidence = emptyList(),
    )

    private fun log(): OperatorActionLog =
        OperatorActionLog(Files.createTempDirectory("candidate-response"))

    // -- selection ----------------------------------------------------------

    @Test
    fun selection_reports_which_candidate_with_an_available_source() {
        val selection = CandidateResponse.select(
            cards = listOf(card("e1"), card("c-231")),
            candidateId = "c-231",
            observationAgeMs = 2_000L,
        )
        assertEquals("c-231", selection.candidateId)
        assertEquals(ShadowCandidateSelection.SourceStatus.AVAILABLE, selection.source)
    }

    @Test
    fun selection_of_an_unknown_candidate_reports_missing() {
        val selection = CandidateResponse.select(
            cards = listOf(card("e1")),
            candidateId = "no-such-candidate",
            observationAgeMs = 2_000L,
        )
        assertEquals(
            ShadowCandidateSelection.SourceStatus.MISSING,
            selection.source,
        )
    }

    @Test
    fun selection_with_stale_observation_reports_stale_not_current() {
        val selection = CandidateResponse.select(
            cards = listOf(card("c-231")),
            candidateId = "c-231",
            observationAgeMs = ObservationModel.STALE_DATA_MS + 1_000L,
        )
        assertEquals(ShadowCandidateSelection.SourceStatus.STALE, selection.source)
    }

    @Test
    fun selection_without_any_observation_is_never_available() {
        val selection = CandidateResponse.select(
            cards = listOf(card("c-231")),
            candidateId = "c-231",
            observationAgeMs = null,
        )
        assertEquals(ShadowCandidateSelection.SourceStatus.STALE, selection.source)
    }

    @Test
    fun selection_changes_nothing_else() {
        // AT-02: pure read of the card list — repeated selection returns
        // the same view and touches no session, policy, record or store.
        val cards = listOf(card("e1"), card("c-231"))
        val first = CandidateResponse.select(cards, "c-231", 1_000L)
        val second = CandidateResponse.select(cards, "c-231", 1_000L)
        assertEquals(first, second)
        assertEquals(2, cards.size)
    }

    // -- response building --------------------------------------------------

    @Test
    fun missing_indexed_receipt_refuses_without_boot_window_guess() {
        val store = ksi.rc.FileRcStore(Files.createTempDirectory("candidate-unindexed")) { "2026-10-20T14:00:00Z" }
        fun event(id: String) = ksi.rc.Mop.json.createObjectNode().apply {
            put("event_id", id)
            put("flight_id", flight)
        }
        store.storeEvent(event("older"), bootOld)
        store.storeEvent(event("newer"), bootOld)
        // A store wrapper without the indexed receipt API: the response
        // must refuse with EventUnknown instead of guessing from a boot
        // window — no fallback, never a default.
        val withoutIndexedReceipt = object : ksi.rc.UploadStore by store {
            override fun eventReceipt(eventId: String): ksi.rc.EventReceipt? = null
        }
        val source = CandidateResponse.resolveEventSource(withoutIndexedReceipt, flight, "older")
        assertEquals(CandidateResponse.SourceResolution.EventUnknown, source)
        val outcome = CandidateResponse.buildResponse(
            flightId = flight,
            siteId = site,
            source = source,
            eventId = "older",
            action = ObservationResponse.Action.CONFIRM,
            note = null,
            actedAtUtc = actedAt,
        )
        assertTrue(outcome is CandidateResponse.Outcome.Refused)
    }

    @Test
    fun confirm_writes_exactly_one_local_only_record_with_active_session() {
        val store = log()
        val outcome = CandidateResponse.buildResponse(
            flightId = flight,
            siteId = site,
            source = found(bootNew),
            eventId = "c-231",
            action = ObservationResponse.Action.CONFIRM,
            note = null,
            actedAtUtc = actedAt,
            responseId = "resp-1",
        )
        assertTrue(outcome is CandidateResponse.Outcome.Ready)
        outcome as CandidateResponse.Outcome.Ready
        store.append(outcome.action)

        val records = store.readAll()
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("resp-1", record.responseId)
        assertEquals("c-231", record.candidateId)
        assertEquals(site, record.siteId)
        assertEquals(
            OperatorActionArtifacts.sessionIdFor(flight, bootNew),
            record.sessionId,
        )
        assertEquals(OperatorActionLog.CONFIRM, record.action)
        assertEquals("c-231", record.evidenceSourceId)
        assertEquals("2026-10-20T14:02:11Z", record.evidenceReceivedAtUtc)
        assertEquals(actedAt, record.actedAtUtc)
        // Offline: stored LOCAL_ONLY, verbatim honesty claim.
        assertEquals(OperatorActionLog.LOCAL_ONLY, record.delivery)
        assertEquals(
            "전달 요청만 저장됨 · 전송 완료 아님",
            outcome.claim,
        )
    }

    @Test
    fun response_follows_the_selected_event_boot_never_the_latest() {
        // Two boots in the store; the older candidate keeps its own boot
        // session and receipt time even though a newer boot exists.
        val times = mutableListOf("2026-10-20T14:00:00Z", "2026-10-20T14:05:00Z")
        var tick = 0
        val store = ksi.rc.FileRcStore(
            Files.createTempDirectory("candidate-source").toFile().toPath(),
            wallClock = { times[tick++] },
        )
        store.storeEvent(eventNode("e-old", flight, null), bootOld)
        store.storeEvent(eventNode("e-new", flight, null), bootNew)

        val oldResolution = CandidateResponse.resolveEventSource(store, flight, "e-old")
        assertTrue(oldResolution is CandidateResponse.SourceResolution.Found)
        val oldSource = (oldResolution as CandidateResponse.SourceResolution.Found).source
        assertEquals(bootOld, oldSource.bootId)
        assertEquals("2026-10-20T14:00:00Z", oldSource.receivedUtc)
        assertEquals(null, oldSource.policyRevisionId)

        val old = CandidateResponse.buildResponse(
            flight, site, oldResolution, "e-old",
            ObservationResponse.Action.CONFIRM, null, actedAt, "resp-old",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(
            OperatorActionArtifacts.sessionIdFor(flight, bootOld),
            old.action.sessionId,
        )
        assertEquals("2026-10-20T14:00:00Z", old.action.evidenceReceivedAtUtc)

        val newResolution = CandidateResponse.resolveEventSource(store, flight, "e-new")
        val new = CandidateResponse.buildResponse(
            flight, site, newResolution, "e-new",
            ObservationResponse.Action.CONFIRM, null, actedAt, "resp-new",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(
            OperatorActionArtifacts.sessionIdFor(flight, bootNew),
            new.action.sessionId,
        )
        assertEquals("2026-10-20T14:05:00Z", new.action.evidenceReceivedAtUtc)
    }

    @Test
    fun event_policy_revision_is_carried_when_present_absent_otherwise() {
        val policyRev = "00000000-0000-4000-8000-0000000000aa"
        val times = mutableListOf("2026-10-20T14:00:00Z", "2026-10-20T14:01:00Z")
        var tick = 0
        val store = ksi.rc.FileRcStore(
            Files.createTempDirectory("candidate-policy").toFile().toPath(),
            wallClock = { times[tick++] },
        )
        store.storeEvent(eventNode("e-pol", flight, policyRev), bootOld)
        store.storeEvent(eventNode("e-nopol", flight, null), bootOld)

        val withPolicy = CandidateResponse.resolveEventSource(store, flight, "e-pol")
            as CandidateResponse.SourceResolution.Found
        assertEquals(policyRev, withPolicy.source.policyRevisionId)
        val ready = CandidateResponse.buildResponse(
            flight, site, withPolicy, "e-pol",
            ObservationResponse.Action.CONFIRM, null, actedAt, "resp-pol",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(policyRev, ready.action.policyId)

        val withoutPolicy = CandidateResponse.resolveEventSource(store, flight, "e-nopol")
            as CandidateResponse.SourceResolution.Found
        val readyPlain = CandidateResponse.buildResponse(
            flight, site, withoutPolicy, "e-nopol",
            ObservationResponse.Action.CONFIRM, null, actedAt, "resp-plain",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(null, readyPlain.action.policyId)
    }

    @Test
    fun unknown_event_or_receipt_refuses_and_writes_nothing() {
        val store = Files.createTempDirectory("candidate-unknown").toFile()
        val rcStore = ksi.rc.FileRcStore(store.toPath(), wallClock = { "bogus-time" })
        rcStore.storeEvent(eventNode("e-noreceipt", flight, null), bootOld)

        // Event absent from every boot: no write.
        val missing = CandidateResponse.resolveEventSource(rcStore, flight, "no-such")
        assertTrue(missing is CandidateResponse.SourceResolution.EventUnknown)
        assertTrue(
            CandidateResponse.buildResponse(
                flight, site, missing, "no-such",
                ObservationResponse.Action.CONFIRM, null, actedAt,
            ) is CandidateResponse.Outcome.Refused,
        )
        // Event present but its receipt basis unparseable: no write.
        val noReceipt = CandidateResponse.resolveEventSource(rcStore, flight, "e-noreceipt")
        assertTrue(noReceipt is CandidateResponse.SourceResolution.ReceiptUnknown)
        assertTrue(
            CandidateResponse.buildResponse(
                flight, site, noReceipt, "e-noreceipt",
                ObservationResponse.Action.CONFIRM, null, actedAt,
            ) is CandidateResponse.Outcome.Refused,
        )
    }

    private fun eventNode(
        eventId: String,
        flightId: String,
        policyRevisionId: String?,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        val node = ksi.rc.Mop.json.createObjectNode()
        node.put("event_id", eventId)
        node.put("flight_id", flightId)
        if (policyRevisionId == null) node.putNull("policy_revision_id")
        else node.put("policy_revision_id", policyRevisionId)
        return node
    }

    @Test
    fun hold_is_local_only_and_escalation_is_requested_never_sent() {
        val hold = CandidateResponse.buildResponse(
            flight, site, found(bootNew), "c-231",
            ObservationResponse.Action.HOLD, null, actedAt, "resp-hold",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(OperatorActionLog.LOCAL_ONLY, hold.action.delivery)
        assertEquals("전달 요청만 저장됨 · 전송 완료 아님", hold.claim)

        val escalate = CandidateResponse.buildResponse(
            flight, site, found(bootNew), "c-231",
            ObservationResponse.Action.ESCALATE_REQUEST, "하역장 쪽 재확인",
            actedAt, "resp-esc",
        ) as CandidateResponse.Outcome.Ready
        assertEquals(OperatorActionLog.REQUESTED, escalate.action.delivery)
        assertEquals("하역장 쪽 재확인", escalate.action.note)
        assertEquals("전달 요청 저장됨 · 전송되지 않음", escalate.claim)

        // The RC never produces SENT or above offline.
        assertTrue(
            CandidateResponse.offlineDeliveryStates()
                .none { it == ObservationResponse.DeliveryState.SENT_CONFIRMED },
        )
    }

    @Test
    fun note_is_optional_but_capped_at_280_chars() {
        val ok = CandidateResponse.buildResponse(
            flight, site, found(bootNew), "c-231",
            ObservationResponse.Action.CONFIRM, "a".repeat(280), actedAt, "resp-note",
        )
        assertTrue(ok is CandidateResponse.Outcome.Ready)

        val tooLong = CandidateResponse.buildResponse(
            flight, site, found(bootNew), "c-231",
            ObservationResponse.Action.CONFIRM, "a".repeat(281), actedAt, "resp-long",
        )
        assertTrue(tooLong is CandidateResponse.Outcome.Refused)
    }

    @Test
    fun refusal_cases_write_nothing() {
        val store = log()
        val refusals = listOf(
            // No active session: nothing to attach the record to.
            CandidateResponse.buildResponse(null, site, found(bootNew), "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
            // No site: cannot attribute the record.
            CandidateResponse.buildResponse(flight, null, found(bootNew), "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
            CandidateResponse.buildResponse(flight, "", found(bootNew), "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
            // No candidate: nothing selected.
            CandidateResponse.buildResponse(flight, site, found(bootNew), null, ObservationResponse.Action.CONFIRM, null, actedAt),
            // Unknown event: the candidate is absent from every boot.
            CandidateResponse.buildResponse(flight, site, CandidateResponse.SourceResolution.EventUnknown, "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
            // Unknown receipt: the event's receipt basis is missing.
            CandidateResponse.buildResponse(flight, site, CandidateResponse.SourceResolution.ReceiptUnknown, "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
            // Unresolved source at all: no write.
            CandidateResponse.buildResponse(flight, site, null, "c-231", ObservationResponse.Action.CONFIRM, null, actedAt),
        )
        refusals.forEachIndexed { index, outcome ->
            assertTrue("case $index must refuse", outcome is CandidateResponse.Outcome.Refused)
        }
        assertTrue(store.readAll().isEmpty())
        assertEquals(0, store.summary().total)
    }

    @Test
    fun unproven_sent_claim_degrades_to_unknown() {
        // The model never lets an offline escalation read as sent: proof
        // is required for SENT and above, otherwise UNKNOWN.
        val response = ObservationResponse.Response(
            responseId = "resp-x",
            candidateId = "c-231",
            siteId = site,
            sessionId = OperatorActionArtifacts.sessionIdFor(flight, "boot-2"),
            action = ObservationResponse.Action.ESCALATE_REQUEST,
            note = null,
            evidence = ObservationResponse.EvidenceRef("c-231", "2026-10-20T14:02:11Z", null, null),
            actedAtUtc = actedAt,
            delivery = ObservationResponse.DeliveryState.SENT_CONFIRMED,
            transmissionProof = null,
            receiptProof = null,
            completionProof = null,
        )
        assertEquals(
            ObservationResponse.DeliveryState.UNKNOWN,
            ObservationResponse.effectiveDelivery(response),
        )
        assertEquals(
            "상태 미확인 · 전송·수신·완료 주장 불가",
            ObservationResponse.operatorClaim(response),
        )
    }

    @Test
    fun confirm_never_clears_risk_and_never_authorizes_active() {
        for (action in ObservationResponse.Action.values()) {
            assertFalse(ObservationResponse.clearsRisk(action))
            assertFalse(ObservationResponse.authorizesActive(action))
        }
    }

    @Test
    fun stored_names_match_the_operator_action_log() {
        assertEquals(OperatorActionLog.CONFIRM, CandidateResponse.storedActionName(ObservationResponse.Action.CONFIRM))
        assertEquals(OperatorActionLog.HOLD, CandidateResponse.storedActionName(ObservationResponse.Action.HOLD))
        assertEquals(
            OperatorActionLog.ESCALATE_REQUEST,
            CandidateResponse.storedActionName(ObservationResponse.Action.ESCALATE_REQUEST),
        )
        assertEquals(OperatorActionLog.LOCAL_ONLY, CandidateResponse.storedDeliveryName(ObservationResponse.DeliveryState.LOCAL_ONLY))
        assertEquals(OperatorActionLog.REQUESTED, CandidateResponse.storedDeliveryName(ObservationResponse.DeliveryState.REQUESTED))
    }
}
