package ksi.rc

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structured operator-response route against the REAL `ksi_core` server:
 * a persisted RC action posts through the uploader and reads back through
 * the real `GET /flights/{flight}/operator-responses` with its digest
 * intact; same-content replays are duplicates server-side, never new
 * records. Skips (returns) when the repo venv is unavailable, like
 * [CoreUploadE2ETest].
 */
class OperatorResponseE2ETest {
    @Test
    fun persisted_action_round_trips_through_real_core() {
        val repo = findRepoRoot()
        val python = repo?.resolve(".venv/bin/python")
        if (repo == null || python == null || !Files.isExecutable(python)) {
            println("OperatorResponseE2ETest: repo venv not found; skipping real-core E2E")
            return
        }
        val tmp = Files.createTempDirectory("rc-op-response-e2e")
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            python.toString(), "-m", "uvicorn", "ksi_core.app:create_app",
            "--factory", "--host", "127.0.0.1", "--port", port.toString(),
        )
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()["KSI_DB_URL"] = "sqlite:///$tmp/core.db"
                environment()["KSI_OBJECT_ROOT"] = "$tmp/objects"
                environment()["KSI_DEV_TOKENS"] =
                    """{"tok-rc":{"org_id":"org-a","scopes":["rc","read"]}}"""
            }
            .start()
        try {
            val base = "http://127.0.0.1:$port/api/v1/edge"
            val transport = JdkCoreTransport(base, "tok-rc")
            val core = CoreApi(transport)
            waitForHealth(transport)

            // -- Kotlin RC: seed evidence + one persisted operator action ----
            val store = FileRcStore(tmp.resolve("rc-store"))
            val log = OperatorActionLog(tmp.resolve("rc-store"))
            val spec = uploadSpec()
            val boot = Fixture.uuid(12)
            seedFlightEvidence(
                store, spec, boot, Fixture.uuid(13), ByteArray(16) { it.toByte() },
            )
            val sessionId = OperatorActionArtifacts.sessionIdFor(spec.flightId, boot)
            val action = OperatorAction(
                responseId = Fixture.uuid(801),
                candidateId = Fixture.uuid(700),
                siteId = spec.siteId,
                sessionId = sessionId,
                action = "ESCALATE_REQUEST",
                note = "지상팀 전달 요청 — 전송 아님",
                evidenceSourceId = Fixture.uuid(701),
                evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
                policyId = Fixture.uuid(702),
                policyVersion = Fixture.uuid(703),
                actedAtUtc = "2026-10-20T00:01:00Z",
                delivery = "REQUESTED",
            )
            log.append(action)

            // -- Upload through the real uploader path ------------------------
            val report = EvidenceUploader(core, store, operatorActions = log)
                .uploadFlight(spec)
            assertEquals("COMPLETE", report.uploadState)
            assertEquals(1, report.responsesUploaded)
            assertEquals(0, report.responsesPending)
            assertTrue(report.responseFailures.isEmpty())

            // -- The persisted RC action reads back from the real Core -------
            val listed = core.listOperatorResponses(spec.flightId).get("items")
            assertEquals(1, listed.size())
            val sent = OperatorResponsePayload.fromAction(action, spec.flightId)
            assertTrue(
                CanonicalJson.canonicalBytes(listed[0])
                    .contentEquals(CanonicalJson.canonicalBytes(sent)),
                "Core GET must return the exact persisted payload",
            )
            assertTrue(CanonicalJson.verifyDigest(listed[0], "content_sha256"))
            assertEquals("REQUESTED", listed[0].get("delivery_state").asText())

            // -- Same content under a new batch id: duplicates, not records --
            val items = Mop.json.createArrayNode().add(sent)
            val replay = core.postOperatorResponses(Mop.newMsgId("replay", spec.flightId), items)
            assertEquals(0, replay.get("accepted_ids").size())
            assertEquals(1, replay.get("duplicate_ids").size())
            assertEquals(
                Fixture.uuid(801),
                replay.get("duplicate_ids").single().asText(),
            )
            assertEquals(
                1,
                core.listOperatorResponses(spec.flightId).get("items").size(),
                "replay must not create a second server record",
            )

            // -- Re-run: durable receipt short-circuits, server unchanged ----
            val again = EvidenceUploader(core, store, operatorActions = log)
                .uploadFlight(spec)
            assertEquals(0, again.responsesUploaded)
            assertEquals(
                1,
                core.listOperatorResponses(spec.flightId).get("items").size(),
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    private fun waitForHealth(transport: JdkCoreTransport) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (true) {
            try {
                if (transport.request("GET", "/healthz", emptyMap(), null).status == 200) {
                    return
                }
            } catch (_: Exception) {
                // not up yet
            }
            if (System.nanoTime() > deadline) {
                throw AssertionError("core subprocess did not become healthy")
            }
            Thread.sleep(150)
        }
    }

    private fun findRepoRoot(): java.nio.file.Path? {
        var dir: java.nio.file.Path? =
            java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve("core/src/ksi_core/app.py"))) return dir
            dir = dir.parent
        }
        return null
    }
}
