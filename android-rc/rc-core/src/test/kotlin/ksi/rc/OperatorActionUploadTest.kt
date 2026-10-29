package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Operator responses ride the standard artifact pipeline as FLIGHT_LOG
 * (an already-allowed `source_kind`, so no contract change):
 *
 * - responses present: the manifest carries a REQUIRED FLIGHT_LOG artifact
 *   whose sha256/size match the exact uploaded bytes (verifiable end to
 *   end, e.g. by `scripts/rc_core_correlate.py`-style comparison);
 * - log empty for a boot: the artifact is omitted entirely and the flight's
 *   completeness judgement is unchanged (never an empty required artifact,
 *   never a silent claim);
 * - bytes survive an RC restart with the declared digest intact;
 * - a partial/absent artifact reports as missing (honest PARTIAL, never
 *   COMPLETE) — both when local bytes are incomplete and when the server
 *   drops what was pushed;
 * - re-running the upload is idempotent (same manifest digest, zero
 *   re-sent chunks, no duplicated artifact);
 * - a new response after an upload commits a NEW manifest snapshot while
 *   the old snapshot is kept locally (append-only history).
 */
class OperatorActionUploadTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-op-upload")

    private fun response(
        responseId: String,
        sessionId: String,
        action: String = "CONFIRM",
        delivery: String = "LOCAL_ONLY",
    ) = OperatorAction(
        responseId = responseId,
        candidateId = "cand-1",
        siteId = "site-1",
        sessionId = sessionId,
        action = action,
        note = "짧은 메모",
        evidenceSourceId = "obs-1",
        evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
        policyId = "policy-1",
        policyVersion = "v3",
        actedAtUtc = "2026-10-20T00:01:00Z",
        delivery = delivery,
    )

    private fun manifestArtifacts(fake: FakeCore): List<ObjectNode> =
        fake.ingests.values.single().manifest.get("artifacts")
            .mapNotNull { it as? ObjectNode }

    @Test
    fun responses_travel_as_required_flight_log_with_verifiable_digest() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val log = OperatorActionLog(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val edgeId = Fixture.uuid(13)
        seedFlightEvidence(store, spec, boot, edgeId, ByteArray(16) { it.toByte() })
        val session = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
        log.append(response("r-1", session))
        log.append(response("r-2", session, action = "HOLD"))
        // A different session's response must NOT travel with this boot.
        log.append(
            response(
                "r-other", "unrelated-session",
                action = "ESCALATE_REQUEST", delivery = "REQUESTED",
            ),
        )

        val fake = FakeCore()
        val report =
            EvidenceUploader(CoreApi(fake), store, operatorActions = log).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots.single().committed)

        val entries = manifestArtifacts(fake)
        val opEntry = entries.singleOrNull {
            it.get("source_kind").asText() == OperatorActionArtifacts.SOURCE_KIND
        } ?: error("FLIGHT_LOG missing from manifest")
        assertEquals("application/x-ndjson", opEntry.get("media_type").asText())
        assertTrue(opEntry.get("required").asBoolean())
        assertTrue(opEntry.get("segment_index").isNull)
        val expected = OperatorActionArtifacts.snapshotBytes(log, session)
        assertEquals(2, String(expected, Charsets.UTF_8).trim().lines().size)
        assertEquals(expected.size.toLong(), opEntry.get("size_bytes").asLong())
        assertEquals(sha256Hex(expected), opEntry.get("sha256").asText())
        val opId = opEntry.get("artifact_id").asText()
        assertContentEquals(expected, fake.latestObjectBytes(opId))
        assertContentEquals(expected, store.artifactBytes(opId))
        assertTrue(store.isArtifactSynced(opId))
        assertTrue(report.boots.single().synced.contains(opId))
    }

    @Test
    fun empty_log_omits_the_artifact_and_completeness_is_unchanged() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val log = OperatorActionLog(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val edgeId = Fixture.uuid(13)
        seedFlightEvidence(store, spec, boot, edgeId, ByteArray(16) { it.toByte() })

        val fake = FakeCore()
        val report =
            EvidenceUploader(CoreApi(fake), store, operatorActions = log).uploadFlight(spec)
        assertEquals("COMPLETE", report.uploadState)
        assertTrue(report.boots.single().committed)
        val entries = manifestArtifacts(fake)
        assertTrue(entries.none {
            it.get("source_kind").asText() == OperatorActionArtifacts.SOURCE_KIND
        })
        // Only the Edge artifact traveled; nothing empty was synthesized.
        assertEquals(1, entries.size)
        assertEquals(edgeId, entries.single().get("artifact_id").asText())
    }

    @Test
    fun bytes_survive_restart_and_rerun_is_idempotent() {
        val root = tempRoot()
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val fake = FakeCore()
        run {
            val store = FileRcStore(root)
            val log = OperatorActionLog(root)
            seedFlightEvidence(store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() })
            log.append(response("r-1", OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)))
            val report =
                EvidenceUploader(CoreApi(fake), store, operatorActions = log).uploadFlight(spec)
            assertEquals("COMPLETE", report.uploadState)
        }

        // "Restart": fresh store + fresh log over the same directory.
        val restartedStore = FileRcStore(root)
        val restartedLog = OperatorActionLog(root)
        assertEquals(1, restartedLog.readAll().size)
        val session = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
        val expected = OperatorActionArtifacts.snapshotBytes(restartedLog, session)
        assertContentEquals(
            expected,
            restartedStore.artifactBytes(
                OperatorActionArtifacts.artifactIdFor(
                    spec.flightId, boot, sha256Hex(expected),
                ),
            ),
        )

        val again = EvidenceUploader(
            CoreApi(fake), restartedStore, operatorActions = restartedLog,
        ).uploadFlight(spec)
        assertEquals("COMPLETE", again.uploadState)
        assertEquals(0, again.boots.single().chunksSent)
        // Same manifest digest: the server resumed the existing session.
        assertEquals(1, fake.ingests.size)
        assertContentEquals(
            expected,
            fake.latestObjectBytes(
                OperatorActionArtifacts.artifactIdFor(
                    spec.flightId, boot, sha256Hex(expected),
                ),
            ),
        )
        assertTrue(
            restartedStore.isArtifactSynced(
                OperatorActionArtifacts.artifactIdFor(
                    spec.flightId, boot, sha256Hex(expected),
                ),
            ),
        )
    }

    @Test
    fun incomplete_local_operator_bytes_are_missing_never_complete() {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        seedFlightEvidence(store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() })
        // Announced with its digest, but no bytes ever arrived — and no log
        // is wired, so nothing repairs it (the uploader must not invent
        // bytes for a manifest claim).
        val opBytes = "response that never arrived\n".toByteArray(Charsets.UTF_8)
        val opId = Fixture.uuid(44)
        store.announceArtifact(
            artifactAnnounceRecord(
                spec.flightId, boot, opId, opBytes,
                sourceKind = OperatorActionArtifacts.SOURCE_KIND,
                mediaType = OperatorActionArtifacts.MEDIA_TYPE,
            ),
            boot,
        )

        val fake = FakeCore()
        val report = EvidenceUploader(CoreApi(fake), store).uploadFlight(spec)
        val bootReport = report.boots.single()
        assertFalse(bootReport.committed)
        assertEquals("PARTIAL", bootReport.status)
        assertEquals(listOf(opId), bootReport.missingArtifacts)
        assertEquals(listOf(opId), bootReport.incompleteLocal)
        assertTrue(bootReport.synced.isEmpty())
        assertFalse(store.isArtifactSynced(opId))
        assertEquals("PARTIAL", report.uploadState)
        assertEquals(SyncOutcome.RECOVERABLE_PENDING, classifyPartial(report))
    }

    @Test
    fun server_side_loss_reports_missing_not_complete() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val log = OperatorActionLog(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        seedFlightEvidence(store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() })
        val session = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
        log.append(response("r-1", session))

        // Local bytes are complete and verified, but the server drops them.
        val fake = FakeCore()
        fake.ignoreChunkWrites = true
        val report =
            EvidenceUploader(CoreApi(fake), store, operatorActions = log).uploadFlight(spec)
        val bootReport = report.boots.single()
        assertFalse(bootReport.committed)
        assertEquals("PARTIAL", bootReport.status)
        val opId = OperatorActionArtifacts.artifactIdFor(
            spec.flightId, boot,
            sha256Hex(OperatorActionArtifacts.snapshotBytes(log, session)),
        )
        // (ignoreChunkWrites drops the Edge snapshot too, so the missing
        // list holds both artifacts — the operator one must be among them.)
        assertTrue(bootReport.missingArtifacts.contains(opId))
        assertTrue(bootReport.incompleteLocal.isEmpty())
        assertEquals(SyncOutcome.SERVER_MISSING, classifyPartial(report))
        // Local evidence is intact: retrying the push is the honest fix.
        assertContentEquals(
            OperatorActionArtifacts.snapshotBytes(log, session),
            store.artifactBytes(opId),
        )
    }

    @Test
    fun new_response_after_upload_commits_a_new_manifest_and_keeps_history() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val log = OperatorActionLog(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        seedFlightEvidence(store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() })
        val session = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
        log.append(response("r-1", session))

        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store, operatorActions = log)
        assertEquals("COMPLETE", uploader.uploadFlight(spec).uploadState)
        assertEquals(1, fake.ingests.size)

        log.append(response("r-2", session, action = "HOLD"))
        val second = uploader.uploadFlight(spec)
        assertEquals("COMPLETE", second.uploadState)
        assertEquals(2, fake.ingests.size)
        assertTrue(fake.ingests.values.all { it.status == "COMMITTED" })

        // The new manifest carries only the latest snapshot (no duplication),
        // with a digest over the exact two-line bytes.
        val latest = fake.ingests.values.last().manifest
        val flightLogs = latest.get("artifacts").mapNotNull { it as? ObjectNode }
            .filter { it.get("source_kind").asText() == OperatorActionArtifacts.SOURCE_KIND }
        assertEquals(1, flightLogs.size)
        val expected = OperatorActionArtifacts.snapshotBytes(log, session)
        assertEquals(sha256Hex(expected), flightLogs.single().get("sha256").asText())
        assertContentEquals(expected, fake.latestObjectBytes(flightLogs.single().get("artifact_id").asText()))
        // History is append-only locally: both snapshots are still stored.
        assertEquals(
            2,
            store.artifactsFor(spec.flightId, boot).count {
                it.get("source_kind").asText() == OperatorActionArtifacts.SOURCE_KIND
            },
        )
    }
}
