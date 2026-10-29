package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.MissionState
import ksi.rc.Mop
import ksi.rc.SessionResult
import ksi.rc.SessionResultArtifacts

/**
 * Host tests for the session-verdict unit (contract §6): the verdict is
 * computed with `SessionVerdict.decide` from controller execution facts
 * merged with store-derived facts (`SessionFactsResolver`), persisted in
 * the session verdict log, and displayed with the preservation state on a
 * SEPARATE axis — a preserved FAILED session still shows FAILED, and
 * "업로드 완료" never implies safety.
 */
class RcSessionVerdictTest {

    private var counter = 0

    private inner class Fixture {
        val session = RcSession(
            storeDir = Files.createTempDirectory("rc-verdict").toFile(),
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
        val bootId = "00000000-0000-4000-8000-0000000000e1"

        fun edgeBytes(
            flightId: String,
            type: String,
            payload: com.fasterxml.jackson.databind.node.ObjectNode,
        ): ByteArray {
            counter += 1
            val env = Mop.makeEnvelope(
                msgId = Mop.newMsgId("edge-test", flightId, type, counter.toString()),
                channel = "CONTROL_RELIABLE",
                type = type,
                flightId = flightId,
                edgeBootId = bootId,
                seq = null,
                ackOf = null,
                payload = payload,
                sentMonotonicNs = 1_000_000L,
            )
            return Mop.json.writeValueAsBytes(env)
        }
    }

    @Test
    fun decide_persists_the_record_the_display_shows() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        assertEquals(MissionState.PREPARED, controller.state)

        val decided = f.session.decideVerdict(flightId, f.bootId)
        val sessionId = SessionResultArtifacts.sessionIdFor(flightId, f.bootId)
        assertEquals(sessionId, decided.sessionId)
        assertEquals(flightId, decided.flightId)
        assertEquals(f.bootId, decided.bootId)
        assertTrue(decided.factsJson.isNotBlank())

        val persisted = f.session.latestVerdict(sessionId)
        assertEquals(decided.result, persisted!!.result)
        assertEquals(decided.factsJson, persisted.factsJson)

        // The displayed verdict comes from the persisted record — an open
        // session with no basis is never pre-judged.
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = persisted.result,
                openSession = true,
                commitConfirmed = null,
            ),
        )
        assertTrue(panel.verdictLine.contains("미확인(진행 중)"))
    }

    @Test
    fun rejected_apply_decides_failed_and_preservation_stays_separate() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        controller.apply()
        val reasons = Mop.json.createArrayNode().add("policy_install_rejected")
        val rejection = Mop.json.createObjectNode()
        rejection.set<com.fasterxml.jackson.databind.JsonNode>("reasons", reasons)
        f.session.onLinkBytes(f.edgeBytes(flightId, "MISSION_APPLY_REJECTED", rejection))
        assertEquals(MissionState.REJECTED, controller.state)

        val decided = f.session.decideVerdict(flightId, f.bootId)
        assertEquals(SessionResult.FAILED, decided.result)

        // A fully preserved FAILED session still shows FAILED; "업로드
        // 완료" never implies safety.
        val panel = SessionResultPanel.render(
            SessionResultPanel.Input(
                result = decided.result,
                openSession = false,
                commitConfirmed = true,
                committedCount = 5,
                totalCount = 5,
            ),
        )
        assertTrue(panel.verdictLine.contains("FAILED"))
        assertTrue(panel.preservationLine.contains("세션 결과와 별도"))
        assertTrue(panel.uploadLine.contains("업로드 완료"))
        assertTrue(panel.uploadLine.contains("안전 확인 아님"))
    }

    @Test
    fun unknown_boot_basis_stays_unknown_never_invented() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()

        val decided = f.session.decideVerdict(
            flightId, "00000000-0000-4000-8000-0000000000e9",
        )
        assertEquals(SessionResult.UNKNOWN, decided.result)
    }

    @Test
    fun decided_verdict_ships_as_a_flight_log_artifact_undecided_ships_nothing() {        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val bootId = f.bootId
        val undecidedBoot = "00000000-0000-4000-8000-0000000000e8"

        // The uploader path takes the session verdict log (same object the
        // app passes via sessionResults) — construction typechecks the
        // wiring; the artifact assertion below proves the bytes.
        ksi.rc.EvidenceUploader(
            ksi.rc.CoreApi(AndroidCoreTransport("http://127.0.0.1:9", "test")),
            f.session.store,
            sessionResults = f.session.verdicts,
        )

        val decided = f.session.decideVerdict(flightId, bootId)
        val artifactId = ksi.rc.SessionResultArtifacts.ensureForBoot(
            f.session.store, f.session.verdicts, flightId, bootId,
        )
        assertTrue(artifactId != null)
        assertTrue(f.session.store.isArtifactComplete(artifactId!!))
        val bytes = f.session.store.artifactBytes(artifactId)!!
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains(decided.sessionId))
        assertTrue(text.contains(decided.result.name))

        // A boot with no decided verdict ships nothing — never an
        // UNKNOWN-by-default that could read as a verdict.
        assertEquals(
            null,
            ksi.rc.SessionResultArtifacts.ensureForBoot(
                f.session.store, f.session.verdicts, flightId, undecidedBoot,
            ),
        )
    }

    @Test
    fun persisted_snapshot_equals_the_displayed_snapshot() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )

        // The display snapshot and the persisted snapshot are the same
        // object graph: the screen will not re-request after this decide.
        val displayed = SessionVerdictSnapshot.build(
            controller.executionFacts(),
            ksi.rc.SessionFactsResolver.resolve(f.session.store, flightId, f.bootId),
        )
        f.session.decideVerdict(flightId, f.bootId)
        assertTrue(f.session.isVerdictCurrent(displayed))
        // A changed basis no longer matches.
        val changed = displayed.copy(observationCount = (displayed.observationCount ?: 0) + 1)
        assertFalse(f.session.isVerdictCurrent(changed))
    }

    @Test
    fun closing_opinion_count_is_explicit_zero_is_a_fact() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        f.session.controller!!.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )

        // Nothing recorded yet: explicit 0, not an error.
        assertEquals(0, f.session.opinionCountFor(flightId))

        val sessionId = ksi.rc.SessionResultArtifacts.sessionIdFor(flightId, f.bootId)
        f.session.operatorActions.append(
            ksi.rc.OperatorAction(
                responseId = "resp-1",
                candidateId = "cand-1",
                siteId = bundle.get("site_id").asText(),
                sessionId = sessionId,
                action = "CONFIRM",
                note = null,
                evidenceSourceId = "ev-1",
                evidenceReceivedAtUtc = "2026-10-10T07:00:00Z",
                policyId = null,
                policyVersion = null,
                actedAtUtc = "2026-10-10T07:01:00Z",
                delivery = "LOCAL_ONLY",
            ),
        )
        assertEquals(1, f.session.opinionCountFor(flightId))

        // Another flight's session never leaks in.
        f.session.operatorActions.append(
            ksi.rc.OperatorAction(
                responseId = "resp-2",
                candidateId = "cand-2",
                siteId = bundle.get("site_id").asText(),
                sessionId = "other-session",
                action = "HOLD",
                note = null,
                evidenceSourceId = "ev-2",
                evidenceReceivedAtUtc = "2026-10-10T07:00:00Z",
                policyId = null,
                policyVersion = null,
                actedAtUtc = "2026-10-10T07:02:00Z",
                delivery = "LOCAL_ONLY",
            ),
        )
        assertEquals(1, f.session.opinionCountFor(flightId))
    }
}
