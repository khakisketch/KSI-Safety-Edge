package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * RC-store-derived session facts (Unit B contract §6 inputs the app does
 * not have to guess). Every field is nullable: null means the RC truly
 * cannot tell, never false/0.
 */
data class StoreSessionFacts(
    /** Locally stored observations for the boot, including 0. Null when the
     *  boot itself is unknown to the store (no record basis at all). */
    val observationCount: Int?,
    /** Hash-verified complete local video bytes for the boot (see
     *  [SessionFactsResolver.videoEvidencePresent]). */
    val videoEvidencePresent: Boolean?,
    /** The Edge's declared unrecoverable gaps for the boot (see
     *  [SessionFactsResolver.declaredGaps]). */
    val declaredGaps: Boolean?,
)

/**
 * Resolves the store-side inputs of [SessionFacts] — the two facts the
 * controller never sees (video evidence, declared gaps) plus the
 * observation count — from what the RC genuinely holds. Pure reads, no
 * store writes, no contract changes.
 */
object SessionFactsResolver {
    /** Only RC proxy video segments count as local video evidence: Edge
     *  snapshots are stills, and FLIGHT_LOG snapshots are verdicts and
     *  operator records, not video. */
    const val VIDEO_SOURCE_KIND = "RC_PROXY_SEGMENT"

    /** The RC's own manifest bookkeeping rows — not Edge declarations. */
    const val SYNTHESIZED_REASON_PREFIX = "SYNTHESIZED:"

    fun resolve(store: UploadStore, flightId: String, bootId: String): StoreSessionFacts =
        StoreSessionFacts(
            observationCount = observationCount(store, flightId, bootId),
            videoEvidencePresent = videoEvidencePresent(store, flightId, bootId),
            declaredGaps = declaredGaps(store, flightId, bootId),
        )

    fun observationCount(store: UploadStore, flightId: String, bootId: String): Int? {
        if (bootId !in store.bootsFor(flightId)) return null
        return store.observationsFor(flightId, bootId).size
    }

    /**
     * True only when at least one RC proxy segment was announced for the
     * boot AND every announced segment reads back through
     * [UploadStore.artifactBytes] (coverage + size + sha256 — the verified
     * level, not the cheap completeness level). False when segments exist
     * but some bytes are missing or unverifiable (positively missing).
     * Null when no segment was announced at all: the recorder may never
     * have run, so the RC cannot tell — absence of a record is not
     * evidence of absence.
     */
    fun videoEvidencePresent(store: UploadStore, flightId: String, bootId: String): Boolean? {
        val segments = store.artifactsFor(flightId, bootId).filter {
            it.get("source_kind")?.asText() == VIDEO_SOURCE_KIND
        }
        if (segments.isEmpty()) return null
        return segments.all { segment ->
            val artifactId = segment.get("artifact_id")?.takeIf { it.isTextual }?.asText()
                ?: return@all false
            store.artifactBytes(artifactId) != null
        }
    }

    /**
     * True when the Edge declared at least one unrecoverable gap for the
     * boot ([EvidenceIngest] HEARTBEAT declarations). `SYNTHESIZED:*` rows
     * are the RC's own manifest bookkeeping for already-declared channels
     * and never count as Edge declarations. False when the boot is known
     * to the store and the Edge declared nothing. Null when the boot is
     * unknown (nothing was ever received — cannot tell).
     */
    fun declaredGaps(store: UploadStore, flightId: String, bootId: String): Boolean? {
        val edgeDeclared = store.gapsForBoot(bootId).filter { gap ->
            gap.get("reason")?.asText()?.startsWith(SYNTHESIZED_REASON_PREFIX) != true
        }
        if (edgeDeclared.isNotEmpty()) return true
        return if (bootId in store.bootsFor(flightId)) false else null
    }
}

/** Auditable serialization of the facts a verdict was derived from. */
fun SessionFacts.toNode(): ObjectNode {
    val node = Mop.json.createObjectNode()
    if (configApplied == null) node.putNull("config_applied")
    else node.put("config_applied", configApplied)
    if (runtimeConfirmed == null) node.putNull("runtime_confirmed")
    else node.put("runtime_confirmed", runtimeConfirmed)
    if (frameProcessingObserved == null) node.putNull("frame_processing_observed")
    else node.put("frame_processing_observed", frameProcessingObserved)
    if (observationCount == null) node.putNull("observation_count")
    else node.put("observation_count", observationCount)
    if (videoEvidencePresent == null) node.putNull("video_evidence_present")
    else node.put("video_evidence_present", videoEvidencePresent)
    if (declaredGaps == null) node.putNull("declared_gaps")
    else node.put("declared_gaps", declaredGaps)
    if (linkInterrupted == null) node.putNull("link_interrupted")
    else node.put("link_interrupted", linkInterrupted)
    if (runtimeEpochChanged == null) node.putNull("runtime_epoch_changed")
    else node.put("runtime_epoch_changed", runtimeEpochChanged)
    if (stopConfirmed == null) node.putNull("stop_confirmed")
    else node.put("stop_confirmed", stopConfirmed)
    if (recordingFailure == null) node.putNull("recording_failure")
    else node.put("recording_failure", recordingFailure)
    if (evidenceFailure == null) node.putNull("evidence_failure")
    else node.put("evidence_failure", evidenceFailure)
    return node
}

/**
 * Facts-only verdict persistence (contract §6). Merges the controller's
 * execution facts with the store-derived facts, classifies via
 * [SessionResultClassifier], and persists the verdict in [SessionResultLog];
 * shipping reuses the existing FLIGHT_LOG artifact path
 * ([SessionResultArtifacts.ensureForBoot], already wired into
 * [EvidenceUploader]) — no new `source_kind`, no contract change.
 *
 * Takes NO preservation/COMMITTED input on purpose: a fully preserved
 * failed session still classifies FAILED/INTERRUPTED, so L1 success
 * counting (SUCCESS only) can never be inflated by upload success.
 *
 * Sealed-verdict preservation: a refresh that classifies UNKNOWN (no new
 * evidence basis at all — e.g. controller latches reset by an RC process
 * restart) NEVER overwrites a sealed (non-UNKNOWN) prior verdict. The
 * prior record is returned as the historical snapshot, unmodified.
 * Genuine new evidence (any non-UNKNOWN verdict, including a FAILED that
 * supersedes a SUCCESS) is still appended — the log stays append-only
 * with latest-wins. A stored verdict is history, never liveness: the app
 * must read current execution only from the controller's live
 * `runtimeStatus`, never by adopting an old RUNNING-derived record as
 * current.
 */
object SessionVerdict {
    fun decide(
        store: UploadStore,
        results: SessionResultLog,
        execution: ExecutionFacts,
        flightId: String,
        bootId: String,
        runtimeEpoch: String? = null,
        decidedAtUtc: String = results.decidedAtNowUtc(),
        localVideoFailure: Boolean? = null,
    ): SessionResultRecord {
        val stored = SessionFactsResolver.resolve(store, flightId, bootId)
        val facts = SessionFacts(
            configApplied = execution.configApplied,
            runtimeConfirmed = execution.runtimeConfirmed,
            frameProcessingObserved = execution.frameProcessingObserved,
            observationCount = stored.observationCount,
            videoEvidencePresent = if (localVideoFailure == true) false else stored.videoEvidencePresent,
            declaredGaps = stored.declaredGaps,
            linkInterrupted = execution.linkInterrupted,
            runtimeEpochChanged = execution.runtimeEpochChanged,
            stopConfirmed = execution.stopConfirmed,
            recordingFailure = localVideoFailure,
            evidenceFailure = execution.durableEvidenceFailure,
        )
        val result = SessionResultClassifier.classify(facts)
        val sessionId = SessionResultArtifacts.sessionIdFor(flightId, bootId)
        val prior = results.latest(sessionId)
        if (prior != null && prior.result != SessionResult.UNKNOWN &&
            result == SessionResult.UNKNOWN
        ) {
            // No new evidence basis: the sealed verdict stands. (This is
            // the RC-restart refresh case: IDLE latches classify UNKNOWN,
            // which must not clobber the sealed outcome.)
            return prior
        }
        val record = SessionResultRecord(
            sessionId = sessionId,
            flightId = flightId,
            bootId = bootId,
            runtimeEpoch = runtimeEpoch,
            result = result,
            factsJson = Mop.json.writeValueAsString(facts.toNode()),
            decidedAtUtc = decidedAtUtc,
        )
        results.decide(record)
        return record
    }
}
