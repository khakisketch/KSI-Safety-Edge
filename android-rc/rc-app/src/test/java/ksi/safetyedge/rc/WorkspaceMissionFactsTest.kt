package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.InMemoryRcStore
import ksi.rc.Inbound
import ksi.rc.MissionController
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests for [WorkspaceMissionFacts]: the pure response-evidence
 * snapshot behind the redesigned workspace mission steps.
 *
 * Pure-matrix tests pin every derivation rule; the `real_*` tests drive a
 * real [MissionController] (in-memory store, endpoint loopback — no
 * Android, no link) through the same protocol the Edge speaks.
 */
class WorkspaceMissionFactsTest {

    private companion object {
        const val SHA = "sha-selected-plan"
        const val OTHER_SHA = "sha-other-plan"
        const val APP_ID = "app-1"
        const val BOOT = "00000000-0000-4000-8000-0000000000e1"
    }

    private fun pure(
        selected: String? = SHA,
        controller: String? = SHA,
        state: String? = null,
        transitions: List<String> = emptyList(),
        receipt: String? = null,
        appId: String? = APP_ID,
        reasons: List<String> = emptyList(),
        revalidation: Boolean = false,
        link: Boolean = true,
    ): WorkspaceMissionFacts.Facts = WorkspaceMissionFacts.derive(
        WorkspaceMissionFacts.Input(
            selectedBundleSha = selected,
            controllerBundleSha = controller,
            stateName = state,
            transitionNames = transitions,
            lastApplicationSha = receipt,
            applicationId = appId,
            rejectionReasons = reasons,
            needsRevalidation = revalidation,
            linkConnected = link,
        ),
    )

    // -- selection binding --------------------------------------------------

    @Test
    fun no_selection_never_claims_proof() {
        val f = pure(selected = null, controller = SHA, state = "APPLIED")
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
        assertFalse(f.boundToSelection)
    }

    @Test
    fun selected_plan_without_controller_is_not_requested() {
        val f = pure(selected = SHA, controller = null, state = null)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
        assertFalse(f.boundToSelection)
    }

    @Test
    fun other_plans_proof_is_never_reused() {
        val f = pure(
            selected = SHA,
            controller = OTHER_SHA,
            state = "APPLIED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "APPLIED"),
            receipt = OTHER_SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
        assertFalse(f.boundToSelection)
    }

    // -- prepare/apply lifecycle ---------------------------------------------

    @Test
    fun idle_never_prepared_is_not_requested() {
        val f = pure(state = "IDLE", transitions = emptyList())
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
        assertTrue(f.boundToSelection)
    }

    @Test
    fun preparing_is_pending_not_confirmed() {
        val f = pure(state = "PREPARING", transitions = listOf("PREPARING"))
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
        assertEquals(1, f.currentAttempt)
    }

    @Test
    fun prepared_confirms_prepare_only() {
        val f = pure(
            state = "PREPARED",
            transitions = listOf("PREPARING", "PREPARED"),
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
    }

    @Test
    fun applying_waits_for_the_edge_response() {
        val f = pure(
            state = "APPLYING",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING"),
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.apply)
    }

    @Test
    fun status_unknown_without_proof_is_unknown() {
        val f = pure(
            state = "STATUS_UNKNOWN",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "STATUS_UNKNOWN"),
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        // Reconciling a possibly lost reply asserts nothing outstanding
        // by itself: unknown, never pending.
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.apply)
    }

    @Test
    fun apply_rejection_after_prepared_keeps_prepare_confirmed() {
        val f = pure(
            state = "REJECTED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "REJECTED"),
            reasons = listOf("digest mismatch"),
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REJECTED, f.apply)
    }

    @Test
    fun rejection_without_prepare_proof_leaves_prepare_unknown() {
        val f = pure(
            state = "REJECTED",
            transitions = listOf("REJECTED"),
            reasons = listOf("digest mismatch"),
        )
        // A refused request context without PREPARED proof: unknown, never
        // "not requested" — this instance cannot prove no historic request.
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REJECTED, f.apply)
    }

    @Test
    fun applied_with_matching_receipt_confirms_both() {
        val f = pure(
            state = "APPLIED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "APPLIED"),
            receipt = SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.apply)
    }

    @Test
    fun applied_without_matching_receipt_is_unknown_never_ready() {
        val f = pure(
            state = "APPLIED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "APPLIED"),
            receipt = OTHER_SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.apply)
    }

    @Test
    fun running_alone_never_confirms_apply() {
        val f = pure(
            state = "RUNNING",
            transitions = listOf("PREPARING", "PREPARED"),
            receipt = null,
            appId = null,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.apply)
    }

    @Test
    fun fresh_preparing_supersedes_the_past_attempt_receipt() {
        val f = pure(
            state = "PREPARING",
            transitions = listOf(
                "PREPARING", "PREPARED", "APPLYING", "APPLIED", "PREPARING",
            ),
            receipt = SHA,
        )
        assertEquals(2, f.currentAttempt)
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
    }

    @Test
    fun progressed_context_without_prepare_proof_is_unknown() {
        val f = pure(
            state = "APPLYING",
            transitions = listOf("APPLYING"),
        )
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.apply)
    }

    @Test
    fun refused_context_without_prepare_proof_is_unknown() {
        val f = pure(
            state = "REJECTED",
            transitions = listOf("APPLYING", "REJECTED"),
            reasons = listOf("digest mismatch"),
        )
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REJECTED, f.apply)
    }

    @Test
    fun bound_snapshot_without_current_state_claims_nothing() {
        val f = pure(
            state = null,
            transitions = listOf("PREPARING", "PREPARED"),
            receipt = SHA,
        )
        // Even with response history, a missing current-state anchor is an
        // invalid snapshot: unknown, never a confirmation.
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.apply)
    }

    @Test
    fun status_unknown_retains_confirmed_proof() {
        val f = pure(
            state = "STATUS_UNKNOWN",
            transitions = listOf(
                "PREPARING", "PREPARED", "APPLYING", "APPLIED", "STATUS_UNKNOWN",
            ),
            receipt = SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.apply)
    }

    @Test
    fun new_applying_then_rejection_outranks_earlier_applied_receipt() {
        // Synthetic ordering (the live protocol cannot re-APPLY inside one
        // attempt): guards that a later round never inherits the earlier
        // receipt, and the current refusal outranks it.
        val f = pure(
            state = "REJECTED",
            transitions = listOf(
                "PREPARING", "PREPARED", "APPLYING", "APPLIED",
                "APPLYING", "REJECTED",
            ),
            receipt = SHA,
            reasons = listOf("digest mismatch"),
        )
        assertEquals(1, f.currentAttempt)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REJECTED, f.apply)
    }

    @Test
    fun new_applying_pending_outranks_earlier_applied_receipt() {
        // Synthetic ordering (same caveat as above): the outstanding new
        // request reads pending, not confirmed off the stale receipt.
        val f = pure(
            state = "APPLYING",
            transitions = listOf(
                "PREPARING", "PREPARED", "APPLYING", "APPLIED", "APPLYING",
            ),
            receipt = SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.apply)
    }

    // -- cross-cutting flags ---------------------------------------------------

    @Test
    fun needs_revalidation_is_never_a_fresh_confirmation() {
        val f = pure(
            state = "APPLIED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "APPLIED"),
            receipt = SHA,
            revalidation = true,
        )
        assertEquals(WorkspaceMissionFacts.Step.REVALIDATE, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REVALIDATE, f.apply)
    }

    @Test
    fun link_down_preserves_old_response_facts_with_staleness() {
        val f = pure(
            state = "APPLIED",
            transitions = listOf("PREPARING", "PREPARED", "APPLYING", "APPLIED"),
            receipt = SHA,
            link = false,
        )
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.apply)
        assertTrue(f.linkStale)
    }

    @Test
    fun unknown_future_state_is_unknown_never_ready() {
        val f = pure(
            state = "FLYING",
            transitions = listOf("PREPARING", "PREPARED"),
            receipt = SHA,
        )
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.UNKNOWN, f.apply)
    }

    // -- real MissionController ------------------------------------------------

    private class Real {
        val flightId = "00000000-0000-4000-8000-0000000000f1"
        val bundleSha = SHA
        val bundle: ObjectNode = Mop.json.createObjectNode()
            .put("bundle_id", "bundle-1")
            .put("bundle_sha256", SHA)
        val store = InMemoryRcStore()
        var now = 0L
        val controller = MissionController(flightId, bundle, store, { now })
        private var n = 0

        fun inbound(type: String, boot: String?, payload: ObjectNode): Inbound {
            n += 1
            val env = Mop.makeEnvelope(
                msgId = "edge-$n",
                channel = "CONTROL_RELIABLE",
                type = type,
                flightId = flightId,
                edgeBootId = boot,
                seq = null,
                ackOf = null,
                payload = payload,
                sentMonotonicNs = 1_000_000L + n,
            )
            return controller.endpoint.onRawEnvelope(Mop.json.writeValueAsString(env))
        }

        fun preparedPayload(): ObjectNode =
            Mop.json.createObjectNode().put("bundle_sha256", bundleSha)

        fun appliedPayload(appId: String = APP_ID): ObjectNode =
            Mop.json.createObjectNode()
                .put("application_id", appId)
                .put("effective_edge_seq", 0L)

        fun driveToApplied() {
            controller.prepare()
            assertEquals(
                Inbound.Stored("MISSION_PREPARED"),
                inbound("MISSION_PREPARED", BOOT, preparedPayload()),
            )
            assertEquals(MissionState.PREPARED, controller.state)
            controller.apply()
            assertEquals(
                Inbound.Stored("MISSION_APPLIED"),
                inbound("MISSION_APPLIED", BOOT, appliedPayload()),
            )
            assertEquals(MissionState.APPLIED, controller.state)
        }

        fun ackOutbound(type: String) {
            val id = controller.endpoint.retransmissions()
                .first { it.get("type").asText() == type }
                .get("msg_id").asText()
            n += 1
            val ackPayload = Mop.json.createObjectNode().put("ack_of", id)
            val env = Mop.makeEnvelope(
                msgId = "edge-$n",
                channel = "CONTROL_RELIABLE",
                type = "ACK",
                flightId = flightId,
                edgeBootId = null,
                seq = null,
                ackOf = null,
                payload = ackPayload,
                sentMonotonicNs = 2_000_000L + n,
            )
            assertEquals(
                Inbound.Acked(id),
                controller.endpoint.onRawEnvelope(Mop.json.writeValueAsString(env)),
            )
        }

        fun facts(link: Boolean = true): WorkspaceMissionFacts.Facts =
            WorkspaceMissionFacts.derive(
                WorkspaceMissionFacts.inputFrom(bundleSha, controller, link),
            )
    }

    @Test
    fun real_controller_applied_confirmation() {
        val r = Real()
        r.driveToApplied()
        val f = r.facts()
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.apply)
        assertTrue(f.boundToSelection)
        assertFalse(f.linkStale)
        assertEquals(1, f.currentAttempt)
    }

    @Test
    fun real_controller_rejection_preserves_prepare() {
        val r = Real()
        r.controller.prepare()
        r.inbound("MISSION_PREPARED", BOOT, r.preparedPayload())
        r.controller.apply()
        val payload = Mop.json.createObjectNode()
        payload.putArray("reasons").add("digest mismatch")
        assertEquals(
            Inbound.Stored("MISSION_APPLY_REJECTED"),
            r.inbound("MISSION_APPLY_REJECTED", BOOT, payload),
        )
        assertEquals(MissionState.REJECTED, r.controller.state)
        val f = r.facts()
        assertEquals(WorkspaceMissionFacts.Step.CONFIRMED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.REJECTED, f.apply)
    }

    @Test
    fun real_controller_fresh_preparing_supersedes_stale_receipt() {
        val r = Real()
        r.driveToApplied()
        r.controller.startSession()
        r.ackOutbound("SESSION_START")
        assertEquals(MissionState.SESSION_PENDING, r.controller.state)
        r.controller.stopSession()
        r.ackOutbound("SESSION_STOP")
        val status = Mop.json.createObjectNode()
            .put("running", false)
            .put("applied_bundle_sha256", SHA)
        assertEquals(
            Inbound.Stored("MISSION_STATUS"),
            r.inbound("MISSION_STATUS", null, status),
        )
        assertEquals(MissionState.STOPPED, r.controller.state)
        // The old receipt row still exists …
        assertNotNull(r.controller.lastApplication)
        // … but the fresh attempt must not masquerade it as current proof.
        r.controller.prepare()
        val f = r.facts()
        assertEquals(2, f.currentAttempt)
        assertEquals(WorkspaceMissionFacts.Step.PENDING, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
    }

    @Test
    fun real_controller_restart_gap_reads_not_requested() {
        val r = Real()
        r.driveToApplied()
        // The store durably holds the application row …
        assertTrue(r.store.applicationsFor(r.flightId).isNotEmpty())
        // … but a restarted RC (fresh in-memory controller on the same
        // store) has no response evidence: the adapter reports the gap
        // honestly instead of inventing history.
        val restarted = MissionController(r.flightId, r.bundle, r.store, { r.now })
        val f = WorkspaceMissionFacts.derive(
            WorkspaceMissionFacts.inputFrom(SHA, restarted, true),
        )
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.prepare)
        assertEquals(WorkspaceMissionFacts.Step.NOT_REQUESTED, f.apply)
    }
}
