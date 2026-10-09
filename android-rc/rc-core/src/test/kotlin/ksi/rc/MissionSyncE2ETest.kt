package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A5 end-to-end against a REAL Core: the web seeds a site + zone revision +
 * mission snapshot + bundle; the RC syncs, digest-VERIFIES and stores them;
 * after a store restart it starts a flight from the verified bundle and
 * runs Prepare/Apply with NO server interaction (the FakeEdge loop is the
 * offline link); evidence then uploads on the normal A3-b path and the
 * server-side records carry the pinned mission/bundle digests. Finally the
 * server publishes a new zone revision: the update is detected and stored
 * additively while the flight's pin stays immutable.
 *
 * Skips (returns) when the repo venv is unavailable.
 */
class MissionSyncE2ETest {
    @Test
    fun offline_mission_apply_after_verified_download() {
        val repo = findRepoRoot()
        val python = repo?.resolve(".venv/bin/python")
        if (repo == null || python == null || !Files.isExecutable(python)) {
            println("MissionSyncE2ETest: repo venv not found; skipping")
            return
        }
        val tmp = Files.createTempDirectory("rc-a5-e2e")
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
            val counting = CountingTransport(JdkCoreTransport(base, "tok-rc"))
            waitForHealth(counting)
            val core = CoreApi(counting)
            val transport = counting

            // -- web-side seeding (the RC never creates missions) -------------
            val site = postJson(
                transport, "/sites",
                Mop.json.createObjectNode()
                    .put("org_id", "org-a").put("name", "e2e-a5-site"),
            )
            val siteId = site.get("site_id").asText()
            val zoneId = Fixture.uuid(700)
            val zoneV1Request = MissionFixture.zoneRevision(
                siteId, zoneId, Fixture.uuid(701), null,
            )
            // The server assigns the zone revision id (client's is ignored).
            val zoneRevV1 = postJson(
                transport, "/sites/$siteId/zone-revisions", zoneV1Request,
            ).get("revision_id").asText()
            val zoneInfoV1 = zoneV1Request.deepCopy().put("revision_id", zoneRevV1)
            val missionId = Fixture.uuid(710)
            val missionRev = Fixture.uuid(711)
            val snapshot = MissionFixture.snapshot(
                siteId, missionId, missionRev, Fixture.uuid(712), listOf(zoneInfoV1),
            )
            postJson(transport, "/missions", snapshot)
            val bundleId = Fixture.uuid(713)
            val bundle = MissionFixture.bundle(
                siteId, snapshot,
                MissionFixture.policy(siteId, listOf(zoneRevV1)), bundleId,
            )
            postJson(transport, "/mission-bundles", bundle)
            val snapshotSha = snapshot.get("content_sha256").asText()
            val bundleSha = bundle.get("bundle_sha256").asText()

            // -- RC online: sync + verify + store ------------------------------
            val storeRoot = tmp.resolve("rc-store")
            val store = FileRcStore(storeRoot)
            val onlineSync = MissionSync(core, store)
            assertEquals(1, onlineSync.syncSites().size)
            assertEquals(listOf(zoneRevV1), onlineSync.syncZoneRevisions(siteId))
            onlineSync.syncMissionSnapshot(missionId)
            val serverBundles = onlineSync.listSiteBundles(siteId)
            assertEquals(1, serverBundles.size)
            assertEquals(
                bundleSha,
                onlineSync.syncBundle(bundleId).get("bundle_sha256").asText(),
            )

            // -- restart then OFFLINE: everything from the local store ---------
            // Single-writer rule: after the restart, ALL later use (sync too)
            // goes through the ONE live store instance.
            val offlineStore = FileRcStore(storeRoot)
            val sync = MissionSync(core, offlineStore)
            val localBundle = assertNotNull(offlineStore.missionBundle(bundleId))
            assertEquals(bundleSha, localBundle.get("bundle_sha256").asText())

            val flightId = UUID.randomUUID().toString()
            val bootId = Fixture.uuid(120)
            val flightRecord = Mop.json.createObjectNode()
            flightRecord.put("schema_version", 1)
            flightRecord.put("flight_id", flightId)
            flightRecord.put("site_id", siteId)
            flightRecord.put("mission_id", missionId)
            flightRecord.put("mission_revision_id", missionRev)
            flightRecord.put("mission_sha256", snapshotSha)
            flightRecord.put("bundle_id", bundleId)
            flightRecord.put("bundle_sha256", bundleSha)
            flightRecord.putArray("zone_revision_ids").add(zoneRevV1)
            flightRecord.put("started_at_utc", "2026-10-10T01:00:00Z")
            flightRecord.putNull("ended_at_utc")
            flightRecord.put("state", "STARTED")
            offlineStore.startFlight(flightRecord)

            // Prepare/Apply needs no server: the link loop is local.
            val offlineWindowStart = counting.methods.size
            val controller = MissionController(
                flightId, localBundle, offlineStore, { 0L },
                EvidenceIngest(offlineStore),
            )
            val edge = FakeEdge(flightId, { 0L }, bootId)
            controller.prepare()
            pump(controller.endpoint, edge)
            controller.apply()
            pump(controller.endpoint, edge)
            assertEquals(MissionState.APPLIED, controller.state)
            assertEquals(
                offlineWindowStart, counting.methods.size,
                "offline apply must not make any Core call",
            )
            assertEquals(
                bundleSha,
                offlineStore.applicationsFor(flightId).single()
                    .get("bundle_sha256").asText(),
                "the applied application must carry the pinned bundle digest",
            )

            // -- evidence + upload (existing A3-b path) -------------------------
            val artifactId = Fixture.uuid(121)
            val artifactBytes = ByteArray(16) { it.toByte() }
            val observationId = Fixture.uuid(122)
            feedEnvelope(
                controller.endpoint,
                edgeEnvelope(
                    "OBSERVATION", flightId, bootId, "EVENT_RELIABLE", 1,
                    wrap(
                        "observation",
                        observationRecord(flightId, bootId, missionRev, observationId, 1, 1000),
                    ),
                    "obs1",
                ),
            )
            feedEnvelope(
                controller.endpoint,
                edgeEnvelope(
                    "DETECTION_EVENT", flightId, bootId, "EVENT_RELIABLE", 2,
                    wrap(
                        "event",
                        detectionEventRecord(
                            flightId, Fixture.uuid(123), observationId,
                            Fixture.uuid(124), 1500, listOf(artifactId),
                            missionSnapshotHash = snapshotSha,
                        ),
                    ),
                    "event1",
                ),
            )
            feedEnvelope(
                controller.endpoint,
                edgeEnvelope(
                    "FLIGHT_SUMMARY", flightId, bootId, "EVENT_RELIABLE", 3,
                    wrap(
                        "summary",
                        flightSummaryRecord(
                            flightId, bootId,
                            Uuid5.of(Mop.NAMESPACE, "session|$flightId|$bootId").toString(),
                            Fixture.uuid(125), 1000, 1500,
                        ),
                    ),
                    "summary1",
                ),
            )
            feedEnvelope(
                controller.endpoint,
                edgeEnvelope(
                    "ARTIFACT_ANNOUNCE", flightId, bootId, "EVIDENCE_TRANSFER", 1,
                    artifactAnnounceRecord(flightId, bootId, artifactId, artifactBytes),
                    "announce1",
                ),
            )
            val chunk = Mop.json.createObjectNode()
            chunk.put("artifact_id", artifactId)
            chunk.put("offset", 0L)
            chunk.put("data_b64", java.util.Base64.getEncoder().encodeToString(artifactBytes))
            feedEnvelope(
                controller.endpoint,
                edgeEnvelope(
                    "ARTIFACT_CHUNK", flightId, bootId, "EVIDENCE_TRANSFER", 2,
                    chunk, "chunk2",
                ),
            )

            val spec = FlightUploadSpec(
                flightId = flightId,
                siteId = siteId,
                rcDeviceId = Fixture.uuid(130),
                missionId = missionId,
                missionRevisionId = missionRev,
                missionSha256 = snapshotSha,
                bundleSha256 = bundleSha,
            )
            val report = EvidenceUploader(core, offlineStore).uploadFlight(spec)
            assertEquals("COMPLETE", report.uploadState)
            assertTrue(report.boots.single().committed)

            // -- server-side records carry the pinned mission refs -------------
            val view = core.getFlight(flightId)
            assertEquals("COMPLETE", view.get("upload_state").asText())
            assertEquals(missionRev, view.get("mission_revision_id").asText())
            assertEquals(bundleSha, view.get("bundle_sha256").asText())
            val serverEvent = core.getEvents(flightId).get("items").single()
            assertEquals(snapshotSha, serverEvent.get("mission_snapshot_hash").asText())

            // -- a newer server revision is additive; the pin survives ---------
            val zoneV2Request = MissionFixture.zoneRevision(
                siteId, zoneId, Fixture.uuid(702), zoneRevV1, lon = 127.001,
            )
            val zoneRevV2 = postJson(
                transport, "/sites/$siteId/zone-revisions", zoneV2Request,
            ).get("revision_id").asText()
            assertEquals(listOf(zoneRevV2), sync.checkZoneUpdates(siteId))
            assertEquals(listOf(zoneRevV2), sync.syncZoneRevisions(siteId))
            assertEquals(2, offlineStore.zoneRevisions(siteId).size)
            val pinned = assertNotNull(offlineStore.flight(flightId))
            assertEquals(1, pinned.get("zone_revision_ids").size())
            assertEquals(
                zoneRevV1, pinned.get("zone_revision_ids")[0].asText(),
                "the flight pin must not drift when the server publishes v2",
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** Records every transport call so the offline window can be asserted. */
    private class CountingTransport(
        private val delegate: CoreTransport,
    ) : CoreTransport {
        val methods = mutableListOf<String>()

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): CoreHttpResponse {
            methods.add(method)
            return delegate.request(method, path, headers, body)
        }
    }

    private fun wrap(field: String, node: ObjectNode): ObjectNode =
        Mop.json.createObjectNode().apply { set<com.fasterxml.jackson.databind.JsonNode>(field, node) }

    private fun postJson(
        transport: CoreTransport,
        path: String,
        node: ObjectNode,
    ): ObjectNode {
        val response = transport.request(
            "POST", path, emptyMap(), Mop.json.writeValueAsBytes(node),
        )
        check(response.status in 200..299) {
            "POST $path -> ${response.status}: ${response.bodyText}"
        }
        return Mop.json.readTree(response.body) as ObjectNode
    }

    private fun waitForHealth(transport: CoreTransport) {
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
