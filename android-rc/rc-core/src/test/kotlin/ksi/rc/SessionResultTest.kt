package ksi.rc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit B contract §6: session verdicts from local facts only. COMMITTED
 * (preservation) is not an input — a fully preserved failed session still
 * classifies FAILED/INTERRUPTED — and missing basis yields UNKNOWN, never
 * a fabricated verdict.
 */
class SessionResultTest {
    private fun fullFacts() = SessionFacts(
        configApplied = true,
        runtimeConfirmed = true,
        frameProcessingObserved = true,
        observationCount = 3,
        videoEvidencePresent = true,
        declaredGaps = false,
        linkInterrupted = false,
        runtimeEpochChanged = false,
        stopConfirmed = true,
    )

    @Test
    fun success_needs_every_fact_present_and_clean() {
        assertEquals(SessionResult.SUCCESS, SessionResultClassifier.classify(fullFacts()))
    }

    @Test
    fun processed_frames_without_received_observation_records_are_not_success() {
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(fullFacts().copy(observationCount = 0)),
        )
    }

    @Test
    fun positively_observed_recorder_failure_is_not_lost_when_runtime_basis_is_unknown() {
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(SessionFacts(recordingFailure = true)),
        )
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(SessionFacts(evidenceFailure = true)),
        )
    }

    @Test
    fun missing_basis_is_unknown_never_fabricated() {
        assertEquals(SessionResult.UNKNOWN, SessionResultClassifier.classify(SessionFacts()))
        // Partial basis (running seen, frame counts never reported) is unknown.
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(
                fullFacts().copy(frameProcessingObserved = null, videoEvidencePresent = null),
            ),
        )
        // Legacy Edge: closed cleanly but never reported any execution basis.
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(
                SessionFacts(stopConfirmed = true, linkInterrupted = false,
                    runtimeEpochChanged = false),
            ),
        )
        // A still-open session is never pre-judged.
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(
                SessionFacts(configApplied = true, runtimeConfirmed = true,
                    frameProcessingObserved = true),
            ),
        )
    }

    @Test
    fun failed_is_derivable_even_with_everything_preserved() {
        // Committed preservation is not a classifier input (structural:
        // the signature has no preservation parameter), but spell out the
        // failed derivations a preserved session can still take.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(SessionFacts(configApplied = false)),
        )
        // Closed without confirmed execution.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(
                fullFacts().copy(runtimeConfirmed = false, frameProcessingObserved = false),
            ),
        )
        // Declared unrecoverable gaps: required records missing.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(fullFacts().copy(declaredGaps = true)),
        )
        // Positively missing video evidence.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(fullFacts().copy(videoEvidencePresent = false)),
        )
        // Confirmed execution but no observation records at all.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(fullFacts().copy(observationCount = null)),
        )
        // Known loss mid-session, even before closure.
        assertEquals(
            SessionResult.FAILED,
            SessionResultClassifier.classify(
                SessionFacts(configApplied = true, declaredGaps = true),
            ),
        )
    }

    @Test
    fun interruption_beats_cleanliness_only_while_unclosed() {
        assertEquals(
            SessionResult.INTERRUPTED,
            SessionResultClassifier.classify(
                SessionFacts(
                    configApplied = true, runtimeConfirmed = true,
                    frameProcessingObserved = true, runtimeEpochChanged = true,
                ),
            ),
        )
        assertEquals(
            SessionResult.INTERRUPTED,
            SessionResultClassifier.classify(
                SessionFacts(
                    configApplied = true, runtimeConfirmed = true,
                    frameProcessingObserved = true, linkInterrupted = true,
                ),
            ),
        )
        // Interruption flags that stay set after a clean, fully-recorded
        // close cannot read as clean SUCCESS — but the session was not cut
        // before closing either, so the verdict stays UNKNOWN, honestly.
        assertEquals(
            SessionResult.UNKNOWN,
            SessionResultClassifier.classify(fullFacts().copy(linkInterrupted = true)),
        )
    }

    @Test
    fun verdict_is_stored_locally_and_shipped_as_flight_log() {
        val root = Files.createTempDirectory("rc-session-result")
        val store = FileRcStore(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val log = SessionResultLog(root)
        val sessionId = SessionResultArtifacts.sessionIdFor(spec.flightId, boot)

        // Undecided: nothing announced, manifest unchanged.
        assertNull(log.latest(sessionId))
        assertEquals(0, SessionResultArtifacts.snapshotBytes(log, sessionId).size)

        val facts = fullFacts()
        log.decide(
            SessionResultRecord(
                sessionId = sessionId,
                flightId = spec.flightId,
                bootId = boot,
                runtimeEpoch = "epoch-1",
                result = SessionResultClassifier.classify(facts),
                factsJson = Mop.json.writeValueAsString(
                    Mop.json.createObjectNode().put("observationCount", 3),
                ),
                decidedAtUtc = log.decidedAtNowUtc(),
            ),
        )
        assertEquals(SessionResult.SUCCESS, log.latest(sessionId)?.result)

        // Materialize through the standard artifact pipeline.
        val artifactId = SessionResultArtifacts.ensureForBoot(store, log, spec.flightId, boot)
        assertNotNull(artifactId)
        val announced = store.artifactsFor(spec.flightId, boot)
            .first { it.get("artifact_id")?.asText() == artifactId }
        assertEquals(SessionResultArtifacts.SOURCE_KIND, announced.get("source_kind").asText())
        assertEquals(true, announced.get("required")?.asBoolean())
        assertEquals(SessionResultArtifacts.MEDIA_TYPE, announced.get("media_type")?.asText())

        // Re-running with the same verdict reuses the artifact (idempotent).
        assertEquals(
            artifactId,
            SessionResultArtifacts.ensureForBoot(store, log, spec.flightId, boot),
        )

        // The verdict survives an RC restart (same directory reload).
        val reloaded = SessionResultLog(root)
        assertEquals(SessionResult.SUCCESS, reloaded.latest(sessionId)?.result)

        // Lineage check used by the manifest builder.
        val sha = announced.get("sha256").asText()
        assertTrue(SessionResultArtifacts.isLineage(spec.flightId, boot, artifactId, sha))
    }

    @Test
    fun verdict_lineage_does_not_evict_operator_responses() {
        val root = Files.createTempDirectory("rc-result-lineage")
        val store = FileRcStore(root)
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val sessionId = SessionResultArtifacts.sessionIdFor(spec.flightId, boot)

        // Operator responses exist for this boot.
        val actions = OperatorActionLog(root)
        actions.append(
            OperatorAction(
                responseId = Fixture.uuid(801),
                candidateId = Fixture.uuid(802),
                siteId = spec.siteId,
                sessionId = sessionId,
                action = OperatorActionLog.CONFIRM,
                note = null,
                evidenceSourceId = Fixture.uuid(803),
                evidenceReceivedAtUtc = "2026-10-21T00:00:00Z",
                policyId = null,
                policyVersion = null,
                actedAtUtc = "2026-10-21T00:01:00Z",
                delivery = OperatorActionLog.LOCAL_ONLY,
            ),
        )
        OperatorActionArtifacts.ensureForBoot(store, actions, spec.flightId, boot)

        // And a decided verdict.
        val results = SessionResultLog(root)
        results.decide(
            SessionResultRecord(
                sessionId = sessionId,
                flightId = spec.flightId,
                bootId = boot,
                runtimeEpoch = null,
                result = SessionResult.INTERRUPTED,
                factsJson = "{}",
                decidedAtUtc = results.decidedAtNowUtc(),
            ),
        )
        SessionResultArtifacts.ensureForBoot(store, results, spec.flightId, boot)

        // The manifest carries the latest of EACH lineage — both ship.
        val manifest = ManifestBuilder(store).build(spec, boot)
        val shipped = manifest.get("artifacts").map { it.get("source_kind").asText() }
        assertEquals(2, shipped.count { it == SessionResultArtifacts.SOURCE_KIND },
            "both FLIGHT_LOG lineages must ship: $shipped")
    }
}
