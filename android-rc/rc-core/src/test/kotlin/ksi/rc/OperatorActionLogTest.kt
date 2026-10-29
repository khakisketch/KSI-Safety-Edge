package ksi.rc

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorActionLogTest {
    private fun action(
        responseId: String,
        action: String = "CONFIRM",
        delivery: String = "LOCAL_ONLY",
        transmissionProof: String? = null,
        receiptProof: String? = null,
        completionProof: String? = null,
    ) = OperatorAction(
        responseId = responseId,
        candidateId = "cand-1",
        siteId = "site-1",
        sessionId = "sess-1",
        action = action,
        note = "짧은 메모",
        evidenceSourceId = "obs-1",
        evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
        policyId = "policy-1",
        policyVersion = "v3",
        actedAtUtc = "2026-10-20T00:01:00Z",
        delivery = delivery,
        transmissionProof = transmissionProof,
        receiptProof = receiptProof,
        completionProof = completionProof,
    )

    @Test
    fun append_read_ordering() {
        val root = Files.createTempDirectory("rc-op-actions")
        val log = OperatorActionLog(root)
        log.append(action("r-1", action = "CONFIRM"))
        log.append(action("r-2", action = "HOLD"))
        log.append(action("r-3", action = "ESCALATE_REQUEST", delivery = "REQUESTED"))
        val all = log.readAll()
        assertEquals(listOf("r-1", "r-2", "r-3"), all.map { it.responseId })
        // Restart rebuilds the same order from disk.
        val restarted = OperatorActionLog(root)
        assertEquals(listOf("r-1", "r-2", "r-3"), restarted.readAll().map { it.responseId })
    }

    @Test
    fun re_append_is_idempotent() {
        val root = Files.createTempDirectory("rc-op-actions-idem")
        val log = OperatorActionLog(root)
        log.append(action("r-1"))
        log.append(action("r-1"))
        log.append(action("r-1", action = "HOLD"))
        assertEquals(1, log.readAll().size)
        assertEquals("CONFIRM", log.readAll().first().action)
        // The duplicate must not have grown the file either.
        val restarted = OperatorActionLog(root)
        assertEquals(1, restarted.readAll().size)
    }

    @Test
    fun corrupt_line_is_skipped() {
        val root = Files.createTempDirectory("rc-op-actions-corrupt")
        run {
            val log = OperatorActionLog(root)
            log.append(action("r-1"))
            File(root.toFile(), "operator_actions.ndjson")
                .appendText("this is not json\n")
            log.append(action("r-2", action = "HOLD"))
        }
        val restarted = OperatorActionLog(root)
        assertEquals(listOf("r-1", "r-2"), restarted.readAll().map { it.responseId })
        assertTrue(restarted.recoveredPartialWrites >= 1)
    }

    @Test
    fun partial_trailing_write_is_skipped_on_load() {
        val root = Files.createTempDirectory("rc-op-actions-partial")
        run {
            val log = OperatorActionLog(root)
            log.append(action("r-1"))
        }
        FileOutputStream(
            File(root.toFile(), "operator_actions.ndjson"), true,
        ).use {
            it.write("{\"broken\":".toByteArray(Charsets.UTF_8))
        }
        val restarted = OperatorActionLog(root)
        assertEquals(1, restarted.readAll().size)
        assertTrue(restarted.recoveredPartialWrites >= 1)
    }

    @Test
    fun summary_counts_by_action_and_effective_delivery() {
        val log = OperatorActionLog(Files.createTempDirectory("rc-op-actions-sum"))
        log.append(action("r-1", action = "CONFIRM"))
        log.append(action("r-2", action = "HOLD"))
        log.append(
            action(
                "r-3",
                action = "ESCALATE_REQUEST",
                delivery = "REQUESTED",
            ),
        )
        // Unproven SENT claim: stored, but counted as UNKNOWN (never as sent).
        log.append(
            action(
                "r-4",
                action = "ESCALATE_REQUEST",
                delivery = "SENT_CONFIRMED",
            ),
        )
        val summary = log.summary()
        assertEquals(4, summary.total)
        assertEquals(1, summary.byAction["CONFIRM"])
        assertEquals(1, summary.byAction["HOLD"])
        assertEquals(2, summary.byAction["ESCALATE_REQUEST"])
        assertEquals(2, summary.byDelivery["LOCAL_ONLY"])
        assertEquals(1, summary.byDelivery["REQUESTED"])
        assertEquals(1, summary.byDelivery["UNKNOWN"])
        assertEquals(null, summary.byDelivery["SENT_CONFIRMED"])
    }

    @Test
    fun proven_claim_counts_at_its_state() {
        val log = OperatorActionLog(Files.createTempDirectory("rc-op-actions-proof"))
        log.append(
            action(
                "r-1",
                action = "ESCALATE_REQUEST",
                delivery = "SENT_CONFIRMED",
                transmissionProof = "tx-1",
            ),
        )
        assertEquals("SENT_CONFIRMED", log.readAll().first().delivery)
        assertEquals(
            "SENT_CONFIRMED",
            log.effectiveDelivery(log.readAll().first()),
        )
        assertEquals(1, log.summary().byDelivery["SENT_CONFIRMED"])
    }
}
