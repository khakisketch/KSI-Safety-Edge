package ksi.safetyedge.rc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests invoking the PRODUCTION close coordinator ([FlightCloseCoordinator])
 * with steps backed by a real [RcSession] (only the recorder files and the
 * upload trigger are faked): STOP pending never ends or enqueues the
 * flight; a confirmed STOPPED finalizes recorder → ingest → close-out →
 * end → enqueue in that order; failures retain everything for retry.
 */
class FlightCloseCoordinatorTest {

    private var counter = 0

    private inner class Fixture {
        val link = FakeDroneLink()
        val session = RcSession(
            storeDir = Files.createTempDirectory("rc-close-coord").toFile(),
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = link,
            coreConfig = { null },
        )
        val flightId: String
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val bootId = "00000000-0000-4000-8000-0000000000e1"
        val calls = mutableListOf<String>()
        var enqueued = 0
        val coordinator = FlightCloseCoordinator(
            maxReconcileAttempts = 3,
            reconcileIntervalMs = 0L,
            sleep = {},
        )

        init {
            val record = session.startFlightFromBundle(bundle)
            flightId = record.get("flight_id").asText()
        }

        fun controller() = session.controller!!

        fun edgeBytes(type: String, payload: com.fasterxml.jackson.databind.node.ObjectNode): ByteArray {
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

        fun feed(type: String, payload: com.fasterxml.jackson.databind.node.ObjectNode) {
            session.onLinkBytes(edgeBytes(type, payload))
        }

        fun driveToPrepared() {
            controller().prepare()
            feed(
                "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            )
            assertEquals(MissionState.PREPARED, controller().state)
        }

        fun driveToPending() {
            driveToPrepared()
            controller().apply()
            feed(
                "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            )
            controller().startSession()
            val startMsgId = controller().endpoint.retransmissions()
                .first { it.get("type").asText() == "SESSION_START" }
                .get("msg_id").asText()
            feed("ACK", Mop.json.createObjectNode().put("ack_of", startMsgId))
            assertEquals(MissionState.SESSION_PENDING, controller().state)
        }

        fun confirmStop() {
            controller().stopSession()
            val stopMsgId = controller().endpoint.retransmissions()
                .first { it.get("type").asText() == "SESSION_STOP" }
                .get("msg_id").asText()
            feed("ACK", Mop.json.createObjectNode().put("ack_of", stopMsgId))
            // Bound to our install (digest + install hash): a foreign or
            // digest-less report stays display-only and never stops us.
            val status = Mop.json.createObjectNode()
                .put("running", false)
                .put("runtime_state", "STOPPED")
                .put("config_applied", true)
            status.put("applied_bundle_sha256", bundle.get("bundle_sha256").asText())
            ksi.rc.EffectiveConfigHash.expectedFor(bundle)?.let {
                status.put("effective_config_hash", it)
            }
            feed("MISSION_STATUS", status)
            assertEquals(MissionState.STOPPED, controller().state)
            assertEquals(ksi.rc.EdgeRuntimeState.STOPPED, controller().boundRuntimeStatus?.state)
        }

        fun steps(
            reconcile: () -> Boolean = { session.reconcileOnce() },
            files: List<File> = emptyList(),
            writeFailed: Boolean? = null,
        ): FlightCloseCoordinator.Steps = object : FlightCloseCoordinator.Steps {
            override fun requestStop(): FlightCloseout.StopRequest {
                calls.add("requestStop")
                return session.requestEdgeStop()
            }

            override fun reconcileOnce(): Boolean {
                calls.add("reconcileOnce")
                return reconcile()
            }

            override fun finalizeRecorder(): List<File> {
                calls.add("finalizeRecorder")
                return files
            }

            override fun ingest(files: List<File>): FlightCloseCoordinator.IngestSummary {
                calls.add("ingest")
                val r = session.ingestFinalizedSegments(flightId, files)
                return FlightCloseCoordinator.IngestSummary(r.ingested, r.total, r.problems)
            }

            override fun recorderWriteFailed(): Boolean? {
                calls.add("recorderWriteFailed")
                return writeFailed
            }

            override fun persistUnresolved(
                reason: String,
                ingest: FlightCloseCoordinator.IngestSummary,
                videoFailed: Boolean?,
            ): String {
                calls.add("persistUnresolved")
                val record = session.persistUnresolvedCloseout(
                    flightId, reason,
                    RcSession.SegmentIngest(ingest.ingested, ingest.total, ingest.problems),
                    videoFailed,
                )
                return "unresolved:${record.outcome}:${record.stopReason.take(12)}"
            }

            override fun finalizeAndEnd(
                ingest: FlightCloseCoordinator.IngestSummary,
                videoFailed: Boolean?,
            ): FlightCloseCoordinator.FinalizeResult? {
                calls.add("finalizeAndEnd")
                val summary = session.finalizeAndEndFlight(
                    RcSession.SegmentIngest(ingest.ingested, ingest.total, ingest.problems),
                    videoFailed,
                ) ?: return null
                return FlightCloseCoordinator.FinalizeResult(
                    "clean:${summary.verdict}",
                    summary.closeout.outcome,
                )
            }

            override fun closeBlockedReason(): String? {
                calls.add("closeBlockedReason")
                return session.closeBlockedReason(flightId)
            }

            override fun enqueueUpload() {
                calls.add("enqueueUpload")
                enqueued += 1
            }
        }

        fun playableSegment(dir: File, index: Long): File {
            val file = File(dir, SegmentNames.fileName(index))
            val head = byteArrayOf(
                0, 0, 0, 16,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            )
            val body = ByteArray(2048) { i ->
                when {
                    i < head.size -> head[i]
                    i == 100 -> 'm'.code.toByte()
                    i == 101 -> 'o'.code.toByte()
                    i == 102 -> 'o'.code.toByte()
                    i == 103 -> 'v'.code.toByte()
                    i == 500 -> 'm'.code.toByte()
                    i == 501 -> 'd'.code.toByte()
                    i == 502 -> 'a'.code.toByte()
                    i == 503 -> 't'.code.toByte()
                    else -> 0
                }
            }
            file.writeBytes(body)
            return file
        }
    }

    @Test
    fun no_session_open_finalizes_without_waiting() {
        val f = Fixture()
        val outcome = f.coordinator.close(
            FlightCloseCoordinator.Mode.FULL, f.steps(),
        )

        // Explicit never-started draft: the record closes (no session ever
        // opened, no intent, no boots), but nothing is enqueued — there is
        // nothing to upload, and scheduling one would imply work.
        assertTrue(outcome is FlightCloseCoordinator.Outcome.CleanClosed)
        assertEquals(null, f.session.activeFlight)
        assertEquals(0, f.enqueued)
        assertFalse(f.calls.contains("reconcileOnce"))
        assertFalse(f.calls.contains("enqueueUpload"))
        val closeout = f.session.latestCloseout(f.flightId)!!
        assertEquals(CloseoutRecord.DRAFT, closeout.outcome)
        assertTrue((outcome as FlightCloseCoordinator.Outcome.CleanClosed).note.contains("동기화 불필요"))
    }

    @Test
    fun confirmed_stop_finalizes_in_order_then_enqueues() {
        val f = Fixture()
        f.driveToPending()
        var polls = 0
        val steps = f.steps(reconcile = {
            polls += 1
            if (polls >= 2) f.confirmStop()
            f.session.reconcileOnce()
        })
        val outcome = f.coordinator.close(FlightCloseCoordinator.Mode.FULL, steps)

        assertTrue(outcome is FlightCloseCoordinator.Outcome.CleanClosed)
        assertEquals(
            listOf(
                "requestStop", "reconcileOnce", "reconcileOnce",
                "finalizeRecorder", "ingest", "recorderWriteFailed",
                "finalizeAndEnd", "enqueueUpload",
            ),
            f.calls,
        )
        assertEquals(null, f.session.activeFlight)
        assertEquals(1, f.enqueued)
        val closeout = f.session.latestCloseout(f.flightId)!!
        assertEquals(CloseoutRecord.CLEAN, closeout.outcome)
        assertEquals(true, closeout.stopConfirmed)
    }

    @Test
    fun unconfirmed_stop_never_ends_nor_enqueues_and_blocks_new_flights() {
        val f = Fixture()
        f.driveToPending()
        val garbage = Files.createTempDirectory("rc-close-seg").toFile()
        val seg = File(garbage, SegmentNames.fileName(0))
        seg.writeBytes(ByteArray(2048) { 7 })
        val outcome = f.coordinator.close(
            FlightCloseCoordinator.Mode.FULL, f.steps(files = listOf(seg)),
        )

        assertTrue(outcome is FlightCloseCoordinator.Outcome.RecordingFinalizedUnresolved)
        // No end, no enqueue: the flight stays active and blocks new ones.
        assertTrue(f.session.activeFlight != null)
        assertEquals(0, f.enqueued)
        assertFalse(f.calls.contains("enqueueUpload"))
        try {
            f.session.startFlightFromBundle(f.bundle)
            throw AssertionError("new flight must stay blocked while unresolved")
        } catch (exc: IllegalStateException) {
            assertTrue(exc.message!!.contains("already active"))
        }
        val closeout = f.session.latestCloseout(f.flightId)!!
        assertEquals(CloseoutRecord.UNRESOLVED, closeout.outcome)
        assertTrue(closeout.stopReason.contains("재조정"))
        // Verdict basis kept (STOP unconfirmed reads unresolved, not clean).
        assertTrue(closeout.verdictResult == null || closeout.verdictResult != "SUCCESS")
        // Unlinked segments kept local and counted as unverified, not evidence.
        assertEquals(0, closeout.segmentsIngested)
        assertEquals(1, closeout.segmentsTotal)
        assertTrue(closeout.segmentsUnverified)
        // Reconcile was bounded, not endless.
        assertEquals(3, f.calls.count { it == "reconcileOnce" })
    }

    @Test
    fun recording_only_mode_skips_the_wait_but_keeps_the_block() {
        val f = Fixture()
        f.driveToPending()
        val outcome = f.coordinator.close(
            FlightCloseCoordinator.Mode.RECORDING_ONLY, f.steps(),
        )

        assertTrue(outcome is FlightCloseCoordinator.Outcome.RecordingFinalizedUnresolved)
        assertTrue((outcome as FlightCloseCoordinator.Outcome.RecordingFinalizedUnresolved)
            .reason.contains("RC 기록만 마감"))
        assertFalse(f.calls.contains("reconcileOnce"))
        assertFalse(f.calls.contains("finalizeAndEnd"))
        assertFalse(f.calls.contains("enqueueUpload"))
        assertTrue(f.session.activeFlight != null)
        assertEquals(
            CloseoutRecord.UNRESOLVED,
            f.session.latestCloseout(f.flightId)!!.outcome,
        )
    }

    @Test
    fun failure_retains_everything_for_retry() {
        val f = Fixture()
        f.driveToPending()
        val steps = f.steps()
        val failing = object : FlightCloseCoordinator.Steps by steps {
            override fun finalizeRecorder(): List<File> =
                throw IllegalStateException("recorder busy")
        }
        val outcome = f.coordinator.close(FlightCloseCoordinator.Mode.FULL, failing)

        assertTrue(outcome is FlightCloseCoordinator.Outcome.Failed)
        assertTrue(f.session.activeFlight != null)
        assertEquals(0, f.enqueued)
        assertEquals(null, f.session.latestCloseout(f.flightId))
    }

    @Test
    fun concurrent_completion_is_not_a_failure() {
        val f = Fixture()
        val steps = f.steps()
        val first = f.coordinator.close(FlightCloseCoordinator.Mode.FULL, steps)
        assertTrue(first is FlightCloseCoordinator.Outcome.CleanClosed)
        // A never-started close is a DRAFT: ended, but nothing to upload.
        assertEquals(CloseoutRecord.DRAFT, f.session.latestCloseout(f.flightId)!!.outcome)
        f.calls.clear()
        val second = f.coordinator.close(FlightCloseCoordinator.Mode.FULL, steps)
        assertTrue(second is FlightCloseCoordinator.Outcome.CleanClosed)
        // No end, no enqueue on either pass.
        assertEquals(0, f.enqueued)
        assertEquals(null, f.session.activeFlight)
    }

    @Test
    fun recording_failure_forces_failed_verdict_never_clean_video() {
        val f = Fixture()
        f.driveToPending()
        // One good segment ingests, but the recorder reported a write
        // failure at finalization: the close-out must carry it and the
        // verdict must not read SUCCESS on the failed capture.
        val dir = Files.createTempDirectory("rc-close-video").toFile()
        val seg = f.playableSegment(dir, 0)
        var polls = 0
        val steps = f.steps(
            files = listOf(seg),
            writeFailed = true,
            reconcile = {
                polls += 1
                if (polls >= 2) f.confirmStop()
                f.session.reconcileOnce()
            },
        )
        val outcome = f.coordinator.close(FlightCloseCoordinator.Mode.FULL, steps)
        assertTrue(outcome is FlightCloseCoordinator.Outcome.CleanClosed)

        val closeout = f.session.latestCloseout(f.flightId)!!
        assertEquals(true, closeout.videoCaptureFailed)
        assertEquals(1, closeout.segmentsIngested)
        assertEquals(1, closeout.segmentsTotal)
        // Known recorder failure forces FAILED — never SUCCESS, even
        // though one segment verified completely.
        assertEquals("FAILED", closeout.verdictResult)
        assertTrue(f.session.latestVerdict(
            ksi.rc.SessionResultArtifacts.sessionIdFor(f.flightId, f.bootId),
        )!!.result == ksi.rc.SessionResult.FAILED)
    }

    @Test
    fun blocked_close_reports_reason_instead_of_silent_skip() {
        val f = Fixture()
        f.driveToPending()
        // Unresolved close-out stands, controller not STOPPED: the gate
        // refuses with the reason instead of silently skipping.
        f.session.persistUnresolvedCloseout(
            f.flightId, "STOPPED 미확인 — 미확인 종료",
            RcSession.SegmentIngest(0, 0, emptyList()), null,
        )
        assertTrue(f.session.closeBlockedReason(f.flightId)!!.contains("미확인 종료"))
        assertNull(
            f.session.finalizeAndEndFlight(RcSession.SegmentIngest(0, 0, emptyList()), null),
        )
        assertTrue(f.session.store.activeFlight() != null)
    }
}
