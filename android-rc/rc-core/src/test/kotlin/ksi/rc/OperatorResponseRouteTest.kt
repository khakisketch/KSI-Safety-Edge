package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structured operator-response route (`POST /operator-responses` /
 * `GET /flights/{flight}/operator-responses`, `operator-response.v1`):
 * exact payloads from persisted actions only, content-derived batches,
 * durable receipts before completion, honest retryable failures, and an
 * empty log that never blocks the flight. The FLIGHT_LOG artifact path
 * stays independent throughout.
 */
class OperatorResponseRouteTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-op-responses")

    private fun response(
        responseId: String,
        sessionId: String,
        action: String = "CONFIRM",
        delivery: String = "LOCAL_ONLY",
    ) = OperatorAction(
        responseId = responseId,
        candidateId = Fixture.uuid(700),
        siteId = Fixture.uuid(2),
        sessionId = sessionId,
        action = action,
        note = "짧은 메모",
        evidenceSourceId = Fixture.uuid(701),
        evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
        policyId = Fixture.uuid(702),
        policyVersion = Fixture.uuid(703),
        actedAtUtc = "2026-10-20T00:01:00Z",
        delivery = delivery,
    )

    private data class Seeded(
        val root: Path,
        val store: FileRcStore,
        val log: OperatorActionLog,
        val spec: FlightUploadSpec,
        val boot: String,
        val sessionId: String,
    )

    private fun seeded(root: Path = tempRoot()): Seeded {
        val store = FileRcStore(root)
        val log = OperatorActionLog(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        seedFlightEvidence(
            store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() },
        )
        val sessionId = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
        return Seeded(root, store, log, spec, boot, sessionId)
    }

    private fun postRequests(fake: FakeCore): List<String> =
        fake.requests.filter { it == "POST /operator-responses" }

    @Test
    fun payload_matches_contract_example_byte_exact() {
        val expected = Mop.json.readTree(
            """
            {"schema_version":1,
             "response_id":"33333333-3333-4333-8333-000000000031",
             "candidate_id":"33333333-3333-4333-8333-000000000032",
             "flight_id":"33333333-3333-4333-8333-000000000004",
             "site_id":"33333333-3333-4333-8333-000000000001",
             "session_id":"33333333-3333-4333-8333-000000000033",
             "action":"ESCALATE_REQUEST","note":"지상팀 전달 요청 — 전송 아님",
             "evidence":{"source_id":"33333333-3333-4333-8333-000000000032",
              "received_at_utc":"2026-10-20T02:10:00Z",
              "policy_id":"33333333-3333-4333-8333-000000000007",
              "policy_version":"33333333-3333-4333-8333-000000000009"},
             "acted_at_utc":"2026-10-20T02:11:00Z","delivery_state":"REQUESTED",
             "transmission_proof":null,"receipt_proof":null,"completion_proof":null,
             "content_sha256":"0d350660fc427e61fe96b16af9344c8c2813f01f39587e11338eb658c0f9ff05"}
            """.trimIndent(),
        ) as ObjectNode
        val action = OperatorAction(
            responseId = "33333333-3333-4333-8333-000000000031",
            candidateId = "33333333-3333-4333-8333-000000000032",
            siteId = "33333333-3333-4333-8333-000000000001",
            sessionId = "33333333-3333-4333-8333-000000000033",
            action = "ESCALATE_REQUEST",
            note = "지상팀 전달 요청 — 전송 아님",
            evidenceSourceId = "33333333-3333-4333-8333-000000000032",
            evidenceReceivedAtUtc = "2026-10-20T02:10:00Z",
            policyId = "33333333-3333-4333-8333-000000000007",
            policyVersion = "33333333-3333-4333-8333-000000000009",
            actedAtUtc = "2026-10-20T02:11:00Z",
            delivery = "REQUESTED",
        )
        val payload = OperatorResponsePayload.fromAction(
            action, "33333333-3333-4333-8333-000000000004",
        )

        // Exact contract shape (additionalProperties:false at both levels).
        assertEquals(
            setOf(
                "schema_version", "response_id", "candidate_id", "flight_id",
                "site_id", "session_id", "action", "note", "evidence",
                "acted_at_utc", "delivery_state", "transmission_proof",
                "receipt_proof", "completion_proof", "content_sha256",
            ),
            OperatorResponsePayload.topLevelKeys(payload),
        )
        assertEquals(
            setOf("source_id", "received_at_utc", "policy_id", "policy_version"),
            OperatorResponsePayload.evidenceKeys(payload),
        )

        // Byte-exact against the frozen contract example, digest included.
        assertTrue(
            CanonicalJson.canonicalBytes(payload)
                .contentEquals(CanonicalJson.canonicalBytes(expected)),
        )
        assertEquals(
            "0d350660fc427e61fe96b16af9344c8c2813f01f39587e11338eb658c0f9ff05",
            payload.get("content_sha256").asText(),
        )
        assertTrue(OperatorResponsePayload.verifyDigest(payload))
    }

    @Test
    fun proofless_offline_records_stay_local_only_or_requested() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId, action = "CONFIRM"))
        s.log.append(response(Fixture.uuid(802), s.sessionId, action = "HOLD"))
        s.log.append(
            response(
                Fixture.uuid(803), s.sessionId,
                action = "ESCALATE_REQUEST", delivery = "REQUESTED",
            ),
        )
        // A stored SENT claim without its proof degrades, never ships as sent.
        s.log.append(
            response(
                Fixture.uuid(804), s.sessionId,
                action = "ESCALATE_REQUEST", delivery = "SENT_CONFIRMED",
            ),
        )

        val fake = FakeCore()
        val report = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals("COMPLETE", report.uploadState)
        assertEquals(4, report.responsesUploaded)
        assertEquals(0, report.responsesPending)
        assertTrue(report.responseFailures.isEmpty())

        // The server's durable accept did NOT upgrade anything: storage is
        // storage, not field-team receipt.
        val serverItems = fake.responsesFor(s.spec.flightId)
        assertEquals(4, serverItems.size)
        val byId = serverItems.associate { it.get("response_id").asText() to it }
        assertEquals("LOCAL_ONLY", byId[Fixture.uuid(801)]!!.get("delivery_state").asText())
        assertEquals("LOCAL_ONLY", byId[Fixture.uuid(802)]!!.get("delivery_state").asText())
        assertEquals("REQUESTED", byId[Fixture.uuid(803)]!!.get("delivery_state").asText())
        assertEquals("UNKNOWN", byId[Fixture.uuid(804)]!!.get("delivery_state").asText())
        serverItems.forEach {
            assertTrue(OperatorResponsePayload.verifyDigest(it))
        }

        // Proofs travel verbatim when they genuinely exist.
        val proven = response(
            Fixture.uuid(805), s.sessionId,
            action = "ESCALATE_REQUEST", delivery = "SENT_CONFIRMED",
        ).copy(transmissionProof = "tx-proof-1")
        val provenPayload = OperatorResponsePayload.fromAction(proven, s.spec.flightId)
        assertEquals("SENT_CONFIRMED", provenPayload.get("delivery_state").asText())
        assertEquals("tx-proof-1", provenPayload.get("transmission_proof").asText())
        assertTrue(OperatorResponsePayload.verifyDigest(provenPayload))
    }

    @Test
    fun same_id_replay_is_idempotent_no_duplicate_post() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), s.store, operatorActions = s.log)
        val first = uploader.uploadFlight(s.spec)
        assertEquals(1, first.responsesUploaded)
        assertEquals(1, postRequests(fake).size)
        val batchId = s.store.responseBatchReceipts("operator-responses", s.spec.flightId)
            .single().get("client_batch_id").asText()

        // Re-run: the durable receipt covers the batch — no second POST,
        // no server-side duplication, nothing re-sent.
        val second = uploader.uploadFlight(s.spec)
        assertEquals(1, postRequests(fake).size)
        assertEquals(0, second.responsesUploaded)
        assertEquals(0, second.responsesPending)
        assertTrue(second.responseFailures.isEmpty())
        assertEquals(1, fake.responsesFor(s.spec.flightId).size)
        assertEquals(
            batchId,
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId)
                .single().get("client_batch_id").asText(),
        )
        // Receipt carries the exact batch hash + accepted ids.
        val receipt = s.store.responseBatchReceipt("operator-responses", batchId)!!
        assertEquals(
            CanonicalJson.digestSha256(
                Mop.json.createArrayNode().add(
                    OperatorResponsePayload.fromAction(
                        s.log.readAll().single(), s.spec.flightId,
                    ),
                ),
            ),
            receipt.get("content_hash").asText(),
        )
        assertEquals(
            Fixture.uuid(801),
            receipt.get("accepted_ids").single().asText(),
        )
        assertTrue(receipt.get("server_batch_id").asText().isNotBlank())
    }

    @Test
    fun lost_reply_retries_same_batch() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        var calls = 0
        val lossy = object : CoreTransport {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): CoreHttpResponse {
                if (method == "POST" && path == "/operator-responses") {
                    calls += 1
                    if (calls == 1) throw IOException("injected reply loss")
                }
                return fake.request(method, path, headers, body)
            }
        }
        val sync = OperatorResponseSync(CoreApi(lossy), s.store, s.log)
        // The uploader always registers the flight before the route runs.
        val register = Mop.json.createObjectNode()
        register.put("flight_id", s.spec.flightId)
        register.put("site_id", s.spec.siteId)
        register.put("rc_device_id", s.spec.rcDeviceId)
        register.put("mission_id", s.spec.missionId)
        register.put("mission_revision_id", s.spec.missionRevisionId)
        register.put("bundle_sha256", s.spec.bundleSha256)
        register.put("started_at_utc", "2026-10-20T00:00:00Z")
        register.putNull("ended_at_utc")
        CoreApi(fake).registerFlight(register)

        // The reply is lost: failure recorded, nothing marked, still pending.
        val failed = sync.uploadForFlight(s.spec.flightId)
        assertEquals(1, failed.failures.size)
        assertEquals(0, failed.posted)
        assertEquals(1, failed.pendingAfter)
        assertEquals(1, failed.clientBatchId?.let { 1 } ?: 0)
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )

        // Retry: SAME content-derived batch id, server accepts once.
        val retried = sync.uploadForFlight(s.spec.flightId)
        assertEquals(failed.clientBatchId, retried.clientBatchId)
        assertEquals(1, retried.posted)
        assertEquals(0, retried.pendingAfter)
        assertTrue(retried.failures.isEmpty())
        assertEquals(1, fake.responsesFor(s.spec.flightId).size)
    }

    @Test
    fun late_response_after_commit_goes_through_route_flight_log_independent() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), s.store, operatorActions = s.log)
        val first = uploader.uploadFlight(s.spec)
        assertEquals("COMPLETE", first.uploadState)
        assertEquals(1, first.responsesUploaded)
        assertTrue(first.boots.all { it.committed })
        val firstBatch = s.store.responseBatchReceipts("operator-responses", s.spec.flightId)
            .single().get("client_batch_id").asText()

        // The operator acts LATER, after everything committed.
        s.log.append(response(Fixture.uuid(802), s.sessionId, action = "HOLD"))
        val second = uploader.uploadFlight(s.spec)
        assertEquals("COMPLETE", second.uploadState)
        assertEquals(1, second.responsesUploaded)
        assertEquals(0, second.responsesPending)

        // A NEW content-based batch (only the late response), not a replay.
        val receipts = s.store.responseBatchReceipts("operator-responses", s.spec.flightId)
        assertEquals(2, receipts.size)
        val secondBatch = receipts.last().get("client_batch_id").asText()
        assertTrue(secondBatch != firstBatch)
        assertEquals(2, fake.responsesFor(s.spec.flightId).size)
        assertEquals(
            setOf(Fixture.uuid(801), Fixture.uuid(802)),
            fake.responsesFor(s.spec.flightId)
                .map { it.get("response_id").asText() }.toSet(),
        )

        // The FLIGHT_LOG artifact channel is preserved independently: the
        // latest manifest still carries a verifiable operator snapshot.
        val latest = fake.ingests.values.last().manifest
        val flightLogs = latest.get("artifacts").mapNotNull { it as? ObjectNode }
            .filter { it.get("source_kind").asText() == OperatorActionArtifacts.SOURCE_KIND }
        assertEquals(1, flightLogs.size)
        val expected = OperatorActionArtifacts.snapshotBytes(
            s.log, OperatorActionArtifacts.sessionIdFor(s.spec.flightId, s.boot),
        )
        assertEquals(sha256Hex(expected), flightLogs.single().get("sha256").asText())
    }

    @Test
    fun older_core_404_is_explicit_pending_coordinator_does_not_short_circuit() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore().apply { responsesRouteSupported = false }
        val core = CoreApi(fake)
        val uploader = EvidenceUploader(core, s.store, operatorActions = s.log)
        val report = uploader.uploadFlight(s.spec)

        // Artifacts are fine: COMMITTED stays true — but the structured
        // route explicitly failed, and the log is untouched.
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots.all { it.committed })
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertEquals(1, report.responseFailures.size)
        assertTrue(report.responseFailures.single().contains("404"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
        assertEquals(1, s.log.readAll().size)

        // The coordinator must not short-circuit on artifact COMPLETE.
        val coordinator = SyncCoordinator(core, uploader)
        val outcome = coordinator.syncOnce(s.spec)
        assertEquals(SyncOutcome.PARTIAL, outcome.state)
        assertEquals(SyncOutcome.RESPONSES_PENDING, outcome.partialCause)

        // A newer Core heals it on retry: same content, same batch, posted.
        fake.responsesRouteSupported = true
        val healed = coordinator.syncOnce(s.spec)
        assertEquals(SyncOutcome.COMPLETE, healed.state)
        assertEquals(null, healed.partialCause)
        assertEquals(1, healed.report!!.responsesUploaded)
        assertEquals(0, healed.report!!.responsesPending)
        assertEquals(1, fake.responsesFor(s.spec.flightId).size)
    }

    @Test
    fun empty_log_posts_nothing_and_blocks_nothing() {
        val s = seeded()
        val fake = FakeCore()
        val report = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals("COMPLETE", report.uploadState)
        assertEquals(0, report.responsesUploaded)
        assertEquals(0, report.responsesPending)
        assertTrue(report.responseFailures.isEmpty())
        assertTrue(postRequests(fake).isEmpty())

        // A log with only foreign-flight actions behaves the same for this
        // flight: nothing of another flight is ever stamped with this one.
        s.log.append(response(Fixture.uuid(801), "unrelated-session"))
        val second = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals("COMPLETE", second.uploadState)
        assertEquals(0, second.responsesUploaded)
        assertEquals(0, second.responsesPending)
        assertTrue(postRequests(fake).isEmpty())
        assertTrue(fake.responsesFor(s.spec.flightId).isEmpty())
        assertEquals(1, s.log.readAll().size)
    }

    @Test
    fun ids_stable_across_restart_no_stale_duplicates() {        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        val first = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(1, first.responsesUploaded)
        assertEquals(1, postRequests(fake).size)

        // "Restart": fresh store + fresh log over the same directory.
        val restartedStore = FileRcStore(s.root)
        val restartedLog = OperatorActionLog(s.root)
        assertEquals(1, restartedLog.readAll().size)
        val again = EvidenceUploader(
            CoreApi(fake), restartedStore, operatorActions = restartedLog,
        ).uploadFlight(s.spec)
        assertEquals(0, again.responsesUploaded)
        assertEquals(0, again.responsesPending)
        assertEquals(1, postRequests(fake).size)
        assertEquals(1, fake.responsesFor(s.spec.flightId).size)
        // The receipt (exact batch hash + ids) survived the restart too.
        assertEquals(
            1,
            restartedStore.responseBatchReceipts("operator-responses", s.spec.flightId).size,
        )
    }

    // -- malformed replies: nothing marks, nothing deletes, stable retry ---

    private fun replyBody(
        serverId: String?,
        accepted: List<String>,
        duplicates: List<String>,
        durable: Boolean = true,
    ): ObjectNode {
        val body = Mop.json.createObjectNode()
        body.put("durable", durable)
        if (serverId != null) body.put("server_batch_id", serverId)
        val acceptedArray = body.putArray("accepted_ids")
        accepted.forEach { acceptedArray.add(it) }
        val duplicateArray = body.putArray("duplicate_ids")
        duplicates.forEach { duplicateArray.add(it) }
        return body
    }

    private fun cannedResponses(
        fake: FakeCore,
        reply: (com.fasterxml.jackson.databind.node.ArrayNode) -> ObjectNode,
    ): CoreTransport = object : CoreTransport {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): CoreHttpResponse {
            if (method == "POST" && path == "/operator-responses") {
                val parsed = Mop.json.readTree(body) as ObjectNode
                val items = parsed.get("items")
                    as com.fasterxml.jackson.databind.node.ArrayNode
                return CoreHttpResponse(200, Mop.json.writeValueAsBytes(reply(items)))
            }
            return fake.request(method, path, headers, body)
        }
    }

    private fun batchIdAndHash(
        flightId: String,
        actions: List<OperatorAction>,
    ): Pair<String, String> {
        val items = Mop.json.createArrayNode()
        actions.forEach { items.add(OperatorResponsePayload.fromAction(it, flightId)) }
        val hash = CanonicalJson.digestSha256(items)
        return Mop.newMsgId(OperatorResponseSync.KIND, flightId, hash) to hash
    }

    @Test
    fun durable_false_reply_marks_nothing_pending_stays() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        val r2 = Fixture.uuid(802)
        val a1 = response(r1, s.sessionId)
        val a2 = response(r2, s.sessionId)
        s.log.append(a1)
        s.log.append(a2)
        // The batch id is a pure function of the content: a stable retry
        // must reuse it.
        val (batchId, _) = batchIdAndHash(s.spec.flightId, listOf(a1, a2))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            replyBody("srv-1", listOf(r1, r2), emptyList(), durable = false)
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)

        // A 200 with durable:false is NOT acceptance: nothing marked, no
        // receipt, everything pending — the local log is untouched.
        assertEquals(0, report.responsesUploaded)
        assertEquals(2, report.responsesPending)
        assertEquals(1, report.responseFailures.size)
        assertTrue(report.responseFailures.single().contains("durable"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
        assertEquals(2, s.log.readAll().size)

        // Retry against an honest Core: SAME batch id, both posted.
        val healed = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(2, healed.responsesUploaded)
        assertEquals(0, healed.responsesPending)
        assertEquals(
            batchId,
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId)
                .single().get("client_batch_id").asText(),
        )
        assertEquals(
            setOf(r1, r2),
            fake.responsesFor(s.spec.flightId).map { it.get("response_id").asText() }.toSet(),
        )
    }

    @Test
    fun missing_server_batch_id_is_malformed() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            replyBody(null, listOf(Fixture.uuid(801)), emptyList())
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertTrue(report.responseFailures.single().contains("server_batch_id"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
    }

    @Test
    fun foreign_ids_in_reply_are_rejected() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        val r2 = Fixture.uuid(802)
        val foreign = Fixture.uuid(999)
        s.log.append(response(r1, s.sessionId))
        s.log.append(response(r2, s.sessionId))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            replyBody("srv-1", listOf(r1, foreign), listOf(r2))
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(2, report.responsesPending)
        assertTrue(report.responseFailures.single().contains("foreign"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
    }

    @Test
    fun omitted_ids_in_reply_are_rejected() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        val r2 = Fixture.uuid(802)
        s.log.append(response(r1, s.sessionId))
        s.log.append(response(r2, s.sessionId))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            // Covers only r1: r2 is silently dropped by the server.
            replyBody("srv-1", listOf(r1), emptyList())
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(2, report.responsesPending)
        assertTrue(report.responseFailures.single().contains(r2))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
    }

    @Test
    fun id_in_both_lists_is_rejected() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        s.log.append(response(r1, s.sessionId))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            replyBody("srv-1", listOf(r1), listOf(r1))
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertTrue(report.responseFailures.single().contains("both accepted and duplicate"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
    }

    @Test
    fun non_array_ids_are_rejected() {
        val s = seeded()
        s.log.append(response(Fixture.uuid(801), s.sessionId))

        val fake = FakeCore()
        val lying = cannedResponses(fake) { _ ->
            val body = replyBody("srv-1", listOf(Fixture.uuid(801)), emptyList())
            body.put("accepted_ids", "not-an-array")
            body
        }
        val report = EvidenceUploader(
            CoreApi(lying), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertTrue(report.responseFailures.single().contains("malformed"))
        assertTrue(
            s.store.responseBatchReceipts("operator-responses", s.spec.flightId).isEmpty(),
        )
    }

    @Test
    fun corrupt_stored_receipt_hash_is_not_trusted() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        val action = response(r1, s.sessionId)
        s.log.append(action)

        // A receipt under the right batch key but with a wrong content
        // hash (store corruption) that covers an unrelated id: the pending
        // action is still uncovered, and the hit must not skip or repost.
        val (batchId, _) = batchIdAndHash(s.spec.flightId, listOf(action))
        val corrupt = Mop.json.createObjectNode()
        corrupt.put("schema_version", 1)
        corrupt.put("kind", OperatorResponseSync.KIND)
        corrupt.put("flight_id", s.spec.flightId)
        corrupt.put("client_batch_id", batchId)
        corrupt.put("content_hash", "00".repeat(32))
        corrupt.put("server_batch_id", "srv-1")
        corrupt.putArray("accepted_ids").add(Fixture.uuid(999))
        corrupt.putArray("duplicate_ids")
        s.store.recordResponseBatchReceipt(corrupt)

        val fake = FakeCore()
        val report = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertEquals(1, report.responseFailures.size)
        assertTrue(report.responseFailures.single().contains("content_hash mismatch"))
        assertTrue(postRequests(fake).isEmpty())
        assertTrue(fake.responsesFor(s.spec.flightId).isEmpty())
    }

    @Test
    fun corrupt_stored_receipt_ids_are_not_trusted() {
        val s = seeded()
        val r1 = Fixture.uuid(801)
        val action = response(r1, s.sessionId)
        s.log.append(action)

        // Right hash, wrong ids: the recorded coverage does not match the
        // sent batch — must not skip, must not repost.
        val (batchId, hash) = batchIdAndHash(s.spec.flightId, listOf(action))
        val corrupt = Mop.json.createObjectNode()
        corrupt.put("schema_version", 1)
        corrupt.put("kind", OperatorResponseSync.KIND)
        corrupt.put("flight_id", s.spec.flightId)
        corrupt.put("client_batch_id", batchId)
        corrupt.put("content_hash", hash)
        corrupt.put("server_batch_id", "srv-1")
        corrupt.putArray("accepted_ids").add(Fixture.uuid(999))
        corrupt.putArray("duplicate_ids")
        s.store.recordResponseBatchReceipt(corrupt)

        val fake = FakeCore()
        val report = EvidenceUploader(
            CoreApi(fake), s.store, operatorActions = s.log,
        ).uploadFlight(s.spec)
        assertEquals(0, report.responsesUploaded)
        assertEquals(1, report.responsesPending)
        assertEquals(1, report.responseFailures.size)
        assertTrue(
            report.responseFailures.single().contains("foreign") ||
                report.responseFailures.single().contains("omits"),
        )
        assertTrue(postRequests(fake).isEmpty())
        assertTrue(fake.responsesFor(s.spec.flightId).isEmpty())
    }
}
