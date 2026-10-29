package ksi.safetyedge.rc

import java.io.File
import java.io.IOException
import java.nio.file.Files
import ksi.rc.Mop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fault containment (bounded regression: stricter counter validation must
 * not take down the primary RC app). After real activity, a corrupted
 * msg_counter reopens through the SAME [createAppSession] assembly seam
 * [RcApp] uses — no whole-app fatal, the store degrades with retained
 * facts, every KSI write refuses (no ACK, no commit, no id reuse), the
 * counter/file bytes stay identical, and the physical link/video seams stay
 * usable. A clean store keeps the normal path untouched.
 */
class RcSessionStorageDegradedTest {
    private val now = "2026-10-20T00:00:00Z"
    private val deviceId = "00000000-0000-4000-8000-0000000000aa"

    private fun healthySession(storeDir: File, link: FakeDroneLink): RcSession =
        RcSession(
            storeDir = storeDir,
            rcDeviceId = deviceId,
            wallClock = { now },
            link = link,
            coreConfig = { null },
        )

    private fun snapshotBytes(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { file ->
            root.toPath().relativize(file.toPath()).toString() to file.readBytes()
        }

    private fun assertBytesUnchanged(before: Map<String, ByteArray>, root: File) {
        val after = snapshotBytes(root)
        assertEquals("no files may appear or vanish", before.keys, after.keys)
        for ((name, bytes) in before) {
            assertTrue(
                "file $name must stay byte-identical",
                bytes.contentEquals(after[name]),
            )
        }
    }

    /** Seeds real activity (counter + flight + closeout history), then corrupts. */
    private fun seedThenCorrupt(): Triple<File, String, Map<String, ByteArray>> {
        val storeDir = Files.createTempDirectory("rc-degraded").toFile()
        val session = healthySession(storeDir, FakeDroneLink())
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        session.store.saveMissionBundle(bundle)
        val record = session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        session.controller!!.prepare()
        assertTrue(session.store.activeFlight() != null)
        File(storeDir, "msg_counter").writeBytes("garbage".toByteArray(Charsets.UTF_8))
        return Triple(storeDir, flightId, snapshotBytes(storeDir))
    }

    private fun edgeBytes(flightId: String, bootId: String): ByteArray {
        val payload = Mop.json.createObjectNode()
        payload.set<com.fasterxml.jackson.databind.JsonNode>(
            "observation",
            Mop.json.createObjectNode().put("observation_id", "obs-1"),
        )
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("edge-test", flightId, "obs", "1"),
            channel = "EVIDENCE_TRANSFER",
            type = "OBSERVATION",
            flightId = flightId,
            edgeBootId = bootId,
            seq = 1,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 1_000_000L,
        )
        return Mop.json.writeValueAsBytes(env)
    }

    // -- the core regression: corrupt counter reopens degraded, app lives ----

    @Test
    fun corrupt_counter_reopens_degraded_without_fatal_and_blocks_ksi() {
        val (storeDir, flightId, before) = seedThenCorrupt()

        // The RcApp assembly seam: must not throw for storage I/O.
        val link = FakeDroneLink()
        val session = createAppSession(storeDir, deviceId, { now }, link) { null }

        assertFalse("KSI persistence must read down", session.storageAvailable)
        assertNotNull(session.storageError)
        assertTrue(
            "error must name the counter: ${session.storageError?.message}",
            (session.storageError?.message ?: "").contains("msg_counter"),
        )
        assertEquals(
            "로컬 저장소 오류 · KSI 관측/기록 사용 불가 · 현재 상태 미확인",
            StorageHealth.UNAVAILABLE_BANNER,
        )
        assertFalse(session.storageStatus.available)
        assertTrue(
            "error detail must name the counter",
            (session.storageStatus.errorDetail ?: "").contains("msg_counter"),
        )
        // No auto-attach from unverified state: no controller, no new session.
        assertNull(session.controller)

        // Physical seams stay usable (no SDK load: fakes only).
        link.connect()
        assertEquals(DroneLink.State.CONNECTED, link.state)
        assertTrue(link.send("ping".toByteArray()))
        val video = FakeVideoSource()
        assertTrue(video.isSimulated)
        video.connect()
        assertEquals(VideoSource.State.CONNECTED, video.state)
        assertEquals(0, session.pumpOutbound())
        assertEquals(0, session.retransmitTick())
        assertEquals(0, session.retryUnacked())

        // KSI start path refuses (never overwrites/resets the counter).
        val startErr = org.junit.Assert.assertThrows(IOException::class.java) {
            session.startFlight("s", "m", "mr", "ms", "b", "bs", emptyList())
        }
        assertTrue(startErr.message!!.contains("로컬 저장소 오류"))
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.requestSessionStart()
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.attachController(Mop.json.createObjectNode(), flightId)
        }

        // Persistence writes refuse: no ACK-able receipt, no commit claim.
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.decideVerdict(flightId, "boot-1", null)
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.persistUnresolvedCloseout(
                flightId, "x", RcSession.SegmentIngest(0, 0, emptyList()), null,
            )
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.ingestFinalizedSegments(flightId, emptyList())
        }
        val commit = Mop.json.createObjectNode()
        commit.put("manifest_sha256", "sha-9")
        commit.put("durable_commit_id", "commit-9")
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.store.recordCommitReceipt(commit)
        }
        // Inbound bytes cannot be received: counted, never stored, never ACKed.
        session.onLinkBytes(edgeBytes(flightId, "boot-1"))
        assertEquals(0, session.stored)
        assertEquals(1, session.failed)
        assertEquals(StorageHealth.UNAVAILABLE_BANNER, session.lastError)
        assertTrue(link.sentBytes.none {
            String(it, Charsets.UTF_8).contains("ACK")
        })

        // Close path stays blocked without inventing anything.
        assertNull(session.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null))
        assertTrue(session.hasOpenStartIntent(flightId))
        assertTrue(
            (session.closeBlockedReason(flightId) ?: "").contains("로컬 저장소 오류"),
        )
        assertFalse(session.repairCloseoutArtifact(flightId))
        val stop = session.requestEdgeStop()
        assertTrue(stop.sessionOpen)
        assertFalse(stop.assessment.clean)

        // Upload/query surfaces stay off (banner explains; nothing fabricated).
        assertNull(session.uploader())
        assertNull(session.missionSync())
        assertNull(session.query())
        assertNull(session.responseSync())
        assertNull(session.syncCoordinator())

        // Retained facts are still readable (poisoned real store, not a mock).
        assertEquals(flightId, session.activeFlight?.get("flight_id")?.asText())

        // Nothing changed on disk: no reset to 0, no new identity.
        assertBytesUnchanged(before, storeDir)
        assertEquals("garbage", File(storeDir, "msg_counter").readText())
    }

    @Test
    fun file_as_dir_root_reopens_degraded_without_throwing() {
        val file = Files.createTempFile("rc-store-blocking", ".dat").toFile()
        file.writeText("not a directory")
        val session = createAppSession(file, deviceId, { now }, FakeDroneLink()) { null }
        assertFalse(session.storageAvailable)
        assertTrue(
            (session.storageError?.message ?: "").contains("directory"),
        )
        assertNull(session.controller)
        org.junit.Assert.assertThrows(IOException::class.java) {
            session.startFlight("s", "m", "mr", "ms", "b", "bs", emptyList())
        }
        assertNull(session.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null))
        assertTrue(session.hasOpenStartIntent("any-flight"))
    }

    // -- unavailable facts never render healthy/zero ----------------------------

    @Test
    fun degraded_ui_model_never_reports_healthy_or_zero() {
        val input = OperatorStatus.Input(
            hasActiveFlight = true,
            linkState = "CONNECTED",
            linkSimulated = true,
            aircraftConnected = null,
            internet = "no internet",
            recorderState = null,
            mopHeartbeatAgeMs = null,
            observationAgeMs = null,
            eventCount = 0,
            artifactsTotal = 0,
            artifactsComplete = 0,
            artifactsSynced = 0,
            pendingAnnotations = 0,
            coveredFrames = null,
            skippedFrames = null,
            coreConfigured = false,
            storageAvailable = false,
            storageError = "msg_counter corrupt",
        )
        val items = OperatorStatus.evaluate(input)
        val storage = items.first()
        assertEquals("로컬 저장소 오류", storage.label)
        assertEquals(OperatorStatus.Severity.PROBLEM, storage.severity)
        assertTrue("detail: ${storage.detail}", storage.detail.contains("현재 상태 미확인"))
        assertTrue(items.any { it.label == "로컬 증거 상태 미확인" })
        assertTrue(items.any { it.label == "Edge 실행 상태 미확인" })
        assertTrue(
            "unavailable data must not pose as empty-but-healthy: ${items.map { it.label }}",
            items.none { it.label == "증거 동기화 완료" || it.label == "로컬 증거 없음" },
        )
        // Physical facts still render (independent of KSI storage).
        assertTrue(items.any { it.label.startsWith("MOP") })
        assertTrue(items.any { it.label == "기체 상태 알 수 없음" })

        val overview = CockpitStatusPresenter.present(
            items = items,
            hasActiveFlight = true,
            unsyncedArtifactCount = 0,
            edge = null,
        )
        assertEquals("로컬 저장소 오류", overview.priority?.label)
        assertEquals("상태 미확인", overview.evidence.value)
        assertEquals("상태 미확인", overview.observation.value)
    }

    // -- clean store: normal path unaffected -------------------------------------

    @Test
    fun clean_store_app_path_unaffected() {
        val storeDir = Files.createTempDirectory("rc-clean").toFile()
        val link = FakeDroneLink()
        val session = createAppSession(storeDir, deviceId, { now }, link) { null }
        assertTrue(session.storageAvailable)
        assertNull(session.storageError)
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        session.store.saveMissionBundle(bundle)
        val record = session.startFlightFromBundle(bundle)
        assertNotNull(session.controller)
        session.controller!!.prepare()
        link.connect()
        assertEquals(1, session.retransmitTick())
        assertEquals(1, link.sentBytes.size)
        val flightId = record.get("flight_id").asText()
        assertEquals(flightId, session.activeFlight?.get("flight_id")?.asText())

        // Healthy writes land on the real FS: an unresolved close-out
        // persists, and a reopened session restores everything (restart is
        // still real FS, not a mock).
        val unresolved = session.persistUnresolvedCloseout(
            flightId,
            "STOPPED 미확인 — 미확인 종료",
            RcSession.SegmentIngest(0, 0, emptyList()),
            null,
        )
        assertEquals(CloseoutRecord.UNRESOLVED, unresolved.outcome)
        assertEquals(
            CloseoutRecord.UNRESOLVED,
            session.latestCloseout(flightId)?.outcome,
        )
        val reopened = createAppSession(storeDir, deviceId, { now }, FakeDroneLink()) { null }
        assertTrue(reopened.storageAvailable)
        assertEquals(
            CloseoutRecord.UNRESOLVED,
            reopened.latestCloseout(flightId)?.outcome,
        )
        assertEquals(flightId, reopened.activeFlight?.get("flight_id")?.asText())
        // Restart restore re-attaches the controller from the stored bundle.
        assertNotNull(reopened.controller)
        reopened.controller!!.prepare()
    }

    /**
     * Cold-start disk-full shape: every durable root is unusable (a
     * directory stands where each managed file belongs) AND the process
     * temp dir is unusable. Assembly must still not throw and must not
     * allocate anything — no temp fallback, no mkdir, no reads.
     */
    @Test
    fun full_unusable_log_root_with_unusable_tmpdir_reopens_without_allocation() {
        val storeDir = Files.createTempDirectory("rc-diskfull").toFile()
        for (name in listOf(
            "messages.ndjson",
            "session_results.ndjson",
            "operator_actions.ndjson",
            "closeouts.ndjson",
            "session_intents.ndjson",
        )) {
            assertTrue("setup: $name must become a directory", File(storeDir, name).mkdir())
        }
        File(storeDir, "msg_counter").writeText("7\n")
        // Disk-full simulation: nothing under this root may be created.
        // (mkdirs then fails silently; every durable read still throws.)
        assertTrue("setup: root must become read-only", storeDir.setWritable(false))
        val before = snapshotEntries(storeDir)

        val sysTmp = System.getProperty("java.io.tmpdir")
        // A regular file as tmpdir: ANY temp allocation would throw here.
        val tmpBlocker = Files.createTempFile("rc-tmp-block", ".dat").toFile()
        System.setProperty("java.io.tmpdir", tmpBlocker.absolutePath)
        try {
            val link = FakeDroneLink()
            val session = createAppSession(storeDir, deviceId, { now }, link) { null }

            assertFalse("everything durable is down", session.storageAvailable)
            assertNotNull(session.storageError)
            assertNull("no auto-attach from unreadable state", session.controller)

            // Every holder is the zero-allocation readonly shape: reads are
            // empty, every write throws the recorded reason.
            assertNull(session.verdicts.latest("sess-1"))
            org.junit.Assert.assertThrows(IOException::class.java) {
                session.verdicts.decide(
                    ksi.rc.SessionResultRecord(
                        sessionId = "sess-1",
                        flightId = "flt-1",
                        bootId = "boot-1",
                        runtimeEpoch = null,
                        result = ksi.rc.SessionResult.UNKNOWN,
                        factsJson = "{}",
                        decidedAtUtc = now,
                    ),
                )
            }
            assertTrue(session.operatorActions.readAll().isEmpty())
            org.junit.Assert.assertThrows(IOException::class.java) {
                session.operatorActions.append(testOpAction("r-1"))
            }
            assertNull(session.closeouts.latestForFlight("flt-1"))
            org.junit.Assert.assertThrows(IOException::class.java) {
                session.closeouts.record(testCloseout("flt-1"))
            }
            assertNull(session.intents.latestForFlight("flt-1"))
            org.junit.Assert.assertThrows(IOException::class.java) {
                session.intents.record(SessionStartIntent("flt-1", null, now))
            }
            org.junit.Assert.assertThrows(IOException::class.java) {
                session.store.recordCommitReceipt(
                    Mop.json.createObjectNode()
                        .put("manifest_sha256", "sha-9")
                        .put("durable_commit_id", "commit-9"),
                )
            }

            // KSI decisions stay blocked; physical seams stay alive.
            assertNull(session.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null))
            assertTrue(session.hasOpenStartIntent("flt-1"))
            link.connect()
            assertTrue(link.send("ping".toByteArray()))
            val video = FakeVideoSource()
            video.connect()
            assertEquals(VideoSource.State.CONNECTED, video.state)

            // Nothing changed on disk: no dirs removed, no files added.
            assertEquals(before, snapshotEntries(storeDir))
        } finally {
            if (sysTmp != null) {
                System.setProperty("java.io.tmpdir", sysTmp)
            } else {
                System.clearProperty("java.io.tmpdir")
            }
            storeDir.setWritable(true)
        }
    }
}

/** Zero-allocation snapshot incl. directories (a removed dir must be noticed). */
private fun snapshotEntries(root: File): Map<String, String> =
    root.walkTopDown().associate { file ->
        val rel = root.toPath().relativize(file.toPath()).toString()
        rel to if (file.isDirectory) {
            "DIR"
        } else {
            file.readBytes().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }

private fun testOpAction(responseId: String): ksi.rc.OperatorAction = ksi.rc.OperatorAction(
    responseId = responseId,
    candidateId = "cand-1",
    siteId = "site-1",
    sessionId = "sess-1",
    action = "CONFIRM",
    note = null,
    evidenceSourceId = "obs-1",
    evidenceReceivedAtUtc = "2026-10-20T00:00:00Z",
    policyId = null,
    policyVersion = null,
    actedAtUtc = "2026-10-20T00:01:00Z",
    delivery = "LOCAL_ONLY",
)

private fun testCloseout(flightId: String): CloseoutRecord = CloseoutRecord(
    flightId = flightId,
    bootId = null,
    sessionId = null,
    outcome = CloseoutRecord.UNRESOLVED,
    stopConfirmed = null,
    stopReason = "STOPPED 미확인 — 미확인 종료",
    opinionCount = 0,
    segmentsIngested = 0,
    segmentsTotal = 0,
    segmentsUnverified = false,
    videoCaptureFailed = null,
    bundleId = null,
    bundleSha256 = null,
    verdictResult = null,
    verdictFactsJson = null,
    decidedAtUtc = "2026-10-20T00:00:00Z",
)

/**
 * Unavailable holder shapes on their own: empty reads, failing writes,
 * zero filesystem work.
 */
class UnavailableLogHolderTest {
    private val reason = IOException("disk full (test fault)")

    @Test
    fun closeout_log_unavailable_reads_empty_and_refuses_writes() {
        val log = CloseoutLog.unavailable(reason)
        assertNull(log.latestForFlight("flt-1"))
        assertEquals(0, log.recoveredPartialWrites)
        val err = org.junit.Assert.assertThrows(IOException::class.java) {
            log.record(testCloseout("flt-1"))
        }
        assertTrue((err.message ?: "").contains("disk full"))
    }

    @Test
    fun closeout_log_unavailable_repair_throws() {
        val log = CloseoutLog.unavailable(reason)
        val err = org.junit.Assert.assertThrows(IOException::class.java) { log.repairAfterFailure() }
        assertTrue((err.message ?: "").contains("disk full"))
    }

    @Test
    fun intent_log_unavailable_reads_empty_and_refuses_writes() {
        val log = SessionIntentLog.unavailable(reason)
        assertNull(log.latestForFlight("flt-1"))
        val err = org.junit.Assert.assertThrows(IOException::class.java) {
            log.record(SessionStartIntent("flt-1", null, "2026-10-20T00:00:00Z"))
        }
        assertTrue((err.message ?: "").contains("disk full"))
        val repairErr = org.junit.Assert.assertThrows(IOException::class.java) { log.repairAfterFailure() }
        assertTrue((repairErr.message ?: "").contains("disk full"))
    }
}
