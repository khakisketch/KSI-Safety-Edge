package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A3-b real-Core integration (acceptance evidence for this stage): the
 * Kotlin RC side (FileRcStore + EvidenceIngest + EvidenceUploader +
 * LocalFirstEvidence) runs against a REAL `ksi_core` uvicorn subprocess on
 * a temp DB, then verifies the committed evidence back through the real
 * read API (flight COMPLETE, events/observations, coverage, evidence list,
 * object download). Skips (returns) when the repo venv is unavailable so a
 * bare `:rc-core:test` run stays green on machines without Python.
 *
 * This is NOT the MSDK flight path: DJI MOP transport, RC Plus 2 and the
 * real aircraft remain the separate H1 gate.
 */
class CoreUploadE2ETest {
    @Test
    fun kotlin_rc_uploads_and_reads_back_against_real_core() {
        val repo = findRepoRoot()
        val python = repo?.resolve(".venv/bin/python")
        if (repo == null || python == null || !Files.isExecutable(python)) {
            println("CoreUploadE2ETest: repo venv not found; skipping real-core E2E")
            return
        }
        val tmp = Files.createTempDirectory("rc-core-e2e")
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
            waitForHealth(transport)
            val core = CoreApi(transport)

            // -- Kotlin RC: mission apply + evidence ingest -------------------
            val store = FileRcStore(tmp.resolve("rc-store"))
            val boot = Fixture.uuid(12)
            val bundle = testBundle()
            val spec = FlightUploadSpec(
                flightId = Fixture.uuid(11),
                siteId = Fixture.uuid(2),
                rcDeviceId = Fixture.uuid(3),
                missionId = Fixture.uuid(4),
                missionRevisionId = Fixture.uuid(5),
                missionSha256 = Fixture.hex('b'),
                bundleSha256 = bundle.get("bundle_sha256").asText(),
            )
            val controller = MissionController(
                spec.flightId, bundle, store, { 0L }, EvidenceIngest(store),
            )
            val edge = FakeEdge(spec.flightId, { 0L }, boot)
            controller.prepare()
            pump(controller.endpoint, edge)
            controller.apply()
            pump(controller.endpoint, edge)
            assertEquals(MissionState.APPLIED, controller.state)
            val sessionId = assertNotNull(controller.sessionId)

            val artifactId = Fixture.uuid(13)
            val artifactBytes = ByteArray(16) { it.toByte() }
            val endpoint = controller.endpoint
            val observationId = Fixture.uuid(14)
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "OBSERVATION", spec.flightId, boot, "EVENT_RELIABLE", 1,
                    wrap(
                        "observation",
                        observationRecord(
                            spec.flightId, boot, spec.missionRevisionId,
                            observationId, 1, 1000,
                        ),
                    ),
                    "obs1",
                ),
            )
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "OBSERVATION", spec.flightId, boot, "EVENT_RELIABLE", 2,
                    wrap(
                        "observation",
                        observationRecord(
                            spec.flightId, boot, spec.missionRevisionId,
                            Fixture.uuid(15), 2, 2000,
                        ),
                    ),
                    "obs2",
                ),
            )
            val eventId = Fixture.uuid(16)
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "DETECTION_EVENT", spec.flightId, boot, "EVENT_RELIABLE", 3,
                    wrap(
                        "event",
                        detectionEventRecord(
                            spec.flightId, eventId, observationId,
                            Fixture.uuid(17), 1500, listOf(artifactId),
                        ),
                    ),
                    "event1",
                ),
            )
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "FLIGHT_SUMMARY", spec.flightId, boot, "EVENT_RELIABLE", 4,
                    wrap(
                        "summary",
                        flightSummaryRecord(
                            spec.flightId, boot, sessionId, Fixture.uuid(18),
                            1000, 2000,
                        ),
                    ),
                    "summary1",
                ),
            )
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "ARTIFACT_ANNOUNCE", spec.flightId, boot, "EVIDENCE_TRANSFER", 1,
                    artifactAnnounceRecord(
                        spec.flightId, boot, artifactId, artifactBytes,
                    ),
                    "announce1",
                ),
            )
            val chunk = Mop.json.createObjectNode()
            chunk.put("artifact_id", artifactId)
            chunk.put("offset", 0L)
            chunk.put(
                "data_b64",
                java.util.Base64.getEncoder().encodeToString(artifactBytes),
            )
            feedEnvelope(
                endpoint,
                edgeEnvelope(
                    "ARTIFACT_CHUNK", spec.flightId, boot, "EVIDENCE_TRANSFER", 2,
                    chunk, "chunk2",
                ),
            )

            // -- Upload to the real Core --------------------------------------
            val uploader = EvidenceUploader(core, store)
            val report = uploader.uploadFlight(spec)
            assertEquals("COMPLETE", report.uploadState)
            assertEquals(FlightUploadReport.TIME_BASIS, report.timeBasis)
            assertEquals(1, report.boots.size)
            assertTrue(report.boots[0].committed, "boot must commit: $report")
            assertTrue(report.boots[0].missingArtifacts.isEmpty())
            assertTrue(report.boots[0].synced.contains(artifactId))
            assertTrue(store.isArtifactSynced(artifactId))
            assertNotNull(report.boots[0].receivedStartedUtc)
            assertNotNull(report.boots[0].receivedEndedUtc)

            // -- Real read-back ------------------------------------------------
            val flight = core.getFlight(spec.flightId)
            assertEquals("COMPLETE", flight.get("upload_state").asText())
            assertEquals(
                1, flight.get("completeness").get("manifests_committed").asInt(),
            )

            val events = core.getEvents(spec.flightId).get("items")
            assertEquals(1, events.size())
            assertEquals(eventId, events[0].get("event_id").asText())
            assertEquals("SHADOW", events[0].get("mode").asText())

            val observations = core.getObservations(spec.flightId).get("items")
            assertEquals(2, observations.size())

            val evidence = core.getEvidence(eventId).get("items")
            assertEquals(1, evidence.size())
            assertEquals(artifactId, evidence[0].get("artifact_id").asText())
            assertEquals(sha256Hex(artifactBytes), evidence[0].get("sha256").asText())

            val coverage = core.getCoverage(spec.flightId)
            assertEquals("COMPLETE", coverage.get("data_completeness").asText())
            assertEquals(1, coverage.get("summaries").size())
            assertEquals(
                1.0, coverage.get("summaries")[0].get("ai_uptime_ratio").asDouble(),
            )

            assertContentEquals(artifactBytes, core.downloadObject(artifactId))

            // -- Local-first against the real Core -----------------------------
            val emptyStore = FileRcStore(tmp.resolve("empty-store"))
            val fallback = LocalFirstEvidence(emptyStore, core)
            val serverEvents = fallback.events(spec.flightId)
            assertEquals(EvidenceSource.SERVER, serverEvents.source)
            assertEquals(1, serverEvents.items.size)
            val serverArtifact = fallback.artifactBytes(artifactId)
            assertNotNull(serverArtifact)
            assertEquals(EvidenceSource.SERVER, serverArtifact.source)
            assertContentEquals(artifactBytes, serverArtifact.items)
            assertEquals(
                EvidenceSource.LOCAL,
                LocalFirstEvidence(store, core).events(spec.flightId).source,
            )

            // -- Idempotent re-upload stays COMPLETE ---------------------------
            val second = uploader.uploadFlight(spec)
            assertEquals("COMPLETE", second.uploadState)
            assertEquals(0, second.boots[0].chunksSent)
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun wrap(field: String, node: ObjectNode): ObjectNode =
        Mop.json.createObjectNode().apply { set<JsonNode>(field, node) }

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

    private fun findRepoRoot(): Path? {
        var dir: Path? =
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve("core/src/ksi_core/app.py"))) return dir
            dir = dir.parent
        }
        return null
    }
}
