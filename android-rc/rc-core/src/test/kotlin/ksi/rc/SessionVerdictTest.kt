package ksi.rc

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session verdict persistence (contract §6): the RC fills the two facts
 * the controller never sees — hash-verified local video bytes and the
 * Edge's declared gaps — decides the verdict facts-only, persists it
 * locally, and ships it as a FLIGHT_LOG artifact. Nulls appear only where
 * the RC truly cannot tell, and a preserved FAILED session stays FAILED.
 */
class SessionVerdictTest {
    private val spec = uploadSpec()
    private val boot = Fixture.uuid(12)

    private fun tempRoot(): java.nio.file.Path =
        Files.createTempDirectory("rc-session-verdict")

    private fun tempStore(root: java.nio.file.Path = tempRoot()): FileRcStore =
        FileRcStore(root)

    private fun storeObservation(store: FileRcStore, bootId: String = boot) {
        store.storeObservation(
            observationRecord(
                spec.flightId, bootId, spec.missionRevisionId,
                Mop.newMsgId("verdict-observation", spec.flightId, bootId), 1, 1000,
            ),
            bootId,
        )
    }

    private fun fullExecution() = ExecutionFacts(
        configApplied = true,
        runtimeConfirmed = true,
        frameProcessingObserved = true,
        stopConfirmed = true,
        runtimeEpochChanged = false,
        linkInterrupted = false,
    )

    private fun ingestVideoSegment(store: FileRcStore, bootId: String = boot): String {
        val file = File.createTempFile("rc-proxy-seg", ".mp4")
        file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        return SegmentIngestor(store).ingestSegment(
            spec.flightId, bootId, 0, file,
            mediaType = "video/mp4", required = true,
        )
    }

    @Test
    fun unknown_boot_yields_nulls_never_fabricated() {
        val store = tempStore()
        val facts = SessionFactsResolver.resolve(store, spec.flightId, boot)
        assertNull(facts.observationCount)
        assertNull(facts.videoEvidencePresent)
        assertNull(facts.declaredGaps)
    }

    @Test
    fun observation_count_includes_zero() {
        val store = tempStore()
        // Boot known via a real HEARTBEAT envelope (the only path Edge
        // gap declarations arrive on) but with no observations: 0 is a
        // recorded fact, not a missing basis.
        val endpoint = MopEndpoint(spec.flightId, store, { 0L }, EvidenceIngest(store))
        val heartbeat = Mop.json.createObjectNode()
        val group = heartbeat.putArray("unrecoverable_gaps").addObject()
        group.put("channel", "EVENT_RELIABLE")
        group.putArray("seqs").add(9)
        feedEnvelope(
            endpoint,
            edgeEnvelope(
                "HEARTBEAT", spec.flightId, boot, "HEARTBEAT", null, heartbeat, "hb1",
            ),
        )
        assertEquals(true, boot in store.bootsFor(spec.flightId))
        assertEquals(0, SessionFactsResolver.observationCount(store, spec.flightId, boot))

        storeObservation(store)
        assertEquals(1, SessionFactsResolver.observationCount(store, spec.flightId, boot))
    }

    @Test
    fun video_evidence_needs_hash_verified_complete_bytes() {
        val store = tempStore()
        storeObservation(store)
        // No segment announced: the recorder may never have run — null.
        assertNull(SessionFactsResolver.videoEvidencePresent(store, spec.flightId, boot))

        // Announced but bytes missing: positively missing, not unknown.
        val announce = artifactAnnounceRecord(
            spec.flightId, boot, Fixture.uuid(2001), ByteArray(16) { it.toByte() },
            sourceKind = "RC_PROXY_SEGMENT",
        )
        store.announceArtifact(announce, boot)
        assertEquals(false, SessionFactsResolver.videoEvidencePresent(store, spec.flightId, boot))

        // Complete + hash-verified bytes: present.
        val store2 = tempStore()
        storeObservation(store2)
        ingestVideoSegment(store2)
        assertEquals(true, SessionFactsResolver.videoEvidencePresent(store2, spec.flightId, boot))

        // Edge stills are not video evidence.
        val store3 = tempStore()
        storeObservation(store3)
        store3.announceArtifact(
            artifactAnnounceRecord(
                spec.flightId, boot, Fixture.uuid(2002), ByteArray(8) { it.toByte() },
                sourceKind = "EDGE_SNAPSHOT",
            ),
            boot,
        )
        assertNull(SessionFactsResolver.videoEvidencePresent(store3, spec.flightId, boot))
    }

    @Test
    fun declared_gaps_are_edge_declarations_only() {
        val store = tempStore()
        // Unknown boot: cannot tell.
        assertNull(SessionFactsResolver.declaredGaps(store, spec.flightId, boot))

        storeObservation(store)
        // Known boot, nothing declared: false (not unknown).
        assertEquals(false, SessionFactsResolver.declaredGaps(store, spec.flightId, boot))

        // The RC's own synthesized bookkeeping is not an Edge declaration.
        val synthesized = Mop.json.createObjectNode()
        synthesized.put("schema_version", 1)
        synthesized.put("edge_boot_id", boot)
        synthesized.put("channel", "EVENT_RELIABLE")
        synthesized.put("seq", 5L)
        synthesized.put("reason", "SYNTHESIZED:EVENT_RELIABLE")
        synthesized.put("destination", "RC_MANIFEST")
        synthesized.put("flight_id", spec.flightId)
        store.recordGap(synthesized)
        assertEquals(false, SessionFactsResolver.declaredGaps(store, spec.flightId, boot))

        // The Edge's declaration counts.
        val declared = Mop.json.createObjectNode()
        declared.put("schema_version", 1)
        declared.put("edge_boot_id", boot)
        declared.put("channel", "EVENT_RELIABLE")
        declared.put("seq", 9L)
        declared.put("reason", "UNRECOVERABLE:EVENT_RELIABLE")
        declared.put("destination", "RC_MANIFEST")
        declared.put("flight_id", spec.flightId)
        store.recordGap(declared)
        assertEquals(true, SessionFactsResolver.declaredGaps(store, spec.flightId, boot))
    }

    @Test
    fun decide_persists_locally_and_ships_as_flight_log() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val results = SessionResultLog(root)
        storeObservation(store)
        ingestVideoSegment(store)

        val record = SessionVerdict.decide(
            store, results, fullExecution(), spec.flightId, boot, runtimeEpoch = "epoch-1",
        )
        assertEquals(SessionResult.SUCCESS, record.result)
        assertEquals("epoch-1", record.runtimeEpoch)
        assertEquals(record, results.latest(record.sessionId))

        // The facts round-trip: re-derive the same verdict from the log.
        val facts = Mop.json.readTree(record.factsJson)
        assertEquals(true, facts.get("config_applied").asBoolean())
        assertEquals(true, facts.get("video_evidence_present").asBoolean())
        assertEquals(false, facts.get("declared_gaps").asBoolean())
        assertEquals(1, facts.get("observation_count").asInt())
        assertEquals(
            SessionResult.SUCCESS,
            SessionResultClassifier.classify(
                SessionFacts(
                    configApplied = facts.get("config_applied").asBoolean(),
                    runtimeConfirmed = facts.get("runtime_confirmed").asBoolean(),
                    frameProcessingObserved = facts.get("frame_processing_observed").asBoolean(),
                    observationCount = facts.get("observation_count").asInt(),
                    videoEvidencePresent = facts.get("video_evidence_present").asBoolean(),
                    declaredGaps = facts.get("declared_gaps").asBoolean(),
                    linkInterrupted = facts.get("link_interrupted").asBoolean(),
                    runtimeEpochChanged = facts.get("runtime_epoch_changed").asBoolean(),
                    stopConfirmed = facts.get("stop_confirmed").asBoolean(),
                ),
            ),
        )

        // Shipped as a FLIGHT_LOG artifact through the existing path.
        val artifactId = SessionResultArtifacts.ensureForBoot(store, results, spec.flightId, boot)
        assertNotNull(artifactId)
        val announced = store.artifactsFor(spec.flightId, boot)
            .first { it.get("artifact_id")?.asText() == artifactId }
        assertEquals("FLIGHT_LOG", announced.get("source_kind").asText())

        // Survives an RC restart (same directory reload).
        val reloaded = SessionResultLog(root)
        assertEquals(SessionResult.SUCCESS, reloaded.latest(record.sessionId)?.result)
    }

    @Test
    fun preserved_failed_session_stays_failed() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val results = SessionResultLog(root)
        storeObservation(store)

        // The Core durably committed everything the RC sent — preservation
        // success. The verdict input has no preservation parameter at all
        // (structural: decide() takes none), so it cannot inflate this.
        val receipt = Mop.json.createObjectNode()
        receipt.put("schema_version", 1)
        receipt.put("flight_id", spec.flightId)
        receipt.put("edge_boot_id", boot)
        receipt.put("manifest_sha256", Fixture.hex('d'))
        receipt.put("upload_id", Fixture.uuid(3001))
        receipt.put("durable_commit_id", Fixture.uuid(3002))
        store.recordCommitReceipt(receipt)

        // Execution failed (config never applied): FAILED despite COMMITTED.
        val record = SessionVerdict.decide(
            store, results,
            ExecutionFacts(
                configApplied = false,
                runtimeConfirmed = null,
                frameProcessingObserved = null,
                stopConfirmed = null,
                runtimeEpochChanged = false,
                linkInterrupted = false,
            ),
            spec.flightId, boot,
        )
        assertEquals(SessionResult.FAILED, record.result)
        assertFalse(record.factsJson.contains("commit"))

        // Declared unrecoverable gaps also stay FAILED after preservation.
        val declared = Mop.json.createObjectNode()
        declared.put("schema_version", 1)
        declared.put("edge_boot_id", boot)
        declared.put("channel", "EVENT_RELIABLE")
        declared.put("seq", 9L)
        declared.put("reason", "UNRECOVERABLE:EVENT_RELIABLE")
        declared.put("destination", "RC_MANIFEST")
        declared.put("flight_id", spec.flightId)
        store.recordGap(declared)
        val gapped = SessionVerdict.decide(
            store, results, fullExecution(), spec.flightId, boot,
        )
        assertEquals(SessionResult.FAILED, gapped.result)
    }

    @Test
    fun known_recorder_loss_supersedes_a_sealed_success_even_after_controller_restart() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val results = SessionResultLog(root)
        storeObservation(store)
        ingestVideoSegment(store)
        val sealed = SessionVerdict.decide(store, results, fullExecution(), spec.flightId, boot)
        assertEquals(SessionResult.SUCCESS, sealed.result)
        val failed = SessionVerdict.decide(
            store, results,
            ExecutionFacts(null, null, null, null, false, false),
            spec.flightId, boot, localVideoFailure = true,
        )
        assertEquals(SessionResult.FAILED, failed.result)
        assertTrue(Mop.json.readTree(failed.factsJson).get("recording_failure").asBoolean())
    }

    @Test
    fun sealed_verdict_survives_evidence_less_refresh() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val results = SessionResultLog(root)
        storeObservation(store)
        ingestVideoSegment(store)

        // Seal a SUCCESS.
        val sealed = SessionVerdict.decide(
            store, results, fullExecution(), spec.flightId, boot,
            runtimeEpoch = "epoch-1",
        )
        assertEquals(SessionResult.SUCCESS, sealed.result)

        // An RC restart wipes the controller latches (fresh IDLE facts):
        // the refresh classifies UNKNOWN — it must NOT overwrite the seal.
        val idle = ExecutionFacts(
            configApplied = null,
            runtimeConfirmed = null,
            frameProcessingObserved = null,
            stopConfirmed = null,
            runtimeEpochChanged = false,
            linkInterrupted = false,
        )
        val kept = SessionVerdict.decide(
            store, results, idle, spec.flightId, boot,
            runtimeEpoch = "epoch-1",
        )
        assertEquals(SessionResult.SUCCESS, kept.result)
        assertEquals(sealed.sessionId, kept.sessionId)
        assertEquals(sealed.decidedAtUtc, kept.decidedAtUtc)
    }

    @Test
    fun sealed_failed_survives_unknown_refresh_but_new_evidence_wins() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val results = SessionResultLog(root)
        storeObservation(store)

        val sealed = SessionVerdict.decide(
            store, results,
            ExecutionFacts(
                configApplied = false,
                runtimeConfirmed = null,
                frameProcessingObserved = null,
                stopConfirmed = null,
                runtimeEpochChanged = false,
                linkInterrupted = false,
            ),
            spec.flightId, boot,
        )
        assertEquals(SessionResult.FAILED, sealed.result)

        // UNKNOWN refresh: sealed FAILED stands.
        val idle = ExecutionFacts(
            configApplied = null,
            runtimeConfirmed = null,
            frameProcessingObserved = null,
            stopConfirmed = null,
            runtimeEpochChanged = false,
            linkInterrupted = false,
        )
        val kept = SessionVerdict.decide(
            store, results, idle, spec.flightId, boot,
        )
        assertEquals(SessionResult.FAILED, kept.result)
        assertEquals(sealed.decidedAtUtc, kept.decidedAtUtc)

        // Genuine new evidence supersedes: an UNKNOWN prior refreshed with
        // full facts decides, and a sealed verdict yields to new evidence.
        val fresh = SessionResultLog(root)
        val boot2 = Fixture.uuid(20)
        storeObservation(store, boot2)
        ingestVideoSegment(store, boot2)
        // A fresh boot must carry a fresh observation identity; reusing the
        // previous boot's id dedupes the record and is not new evidence.
        assertEquals(1, SessionFactsResolver.observationCount(store, spec.flightId, boot2))
        val decided = SessionVerdict.decide(
            store, fresh, fullExecution(), spec.flightId, boot2,
            runtimeEpoch = "epoch-9",
        )
        assertEquals(SessionResult.SUCCESS, decided.result)
    }
}
