package ksi.safetyedge.rc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * L1 closure 3/3 (user review 2026-10-10): the WorkManager layer actually
 * executes — enqueue under the real WorkManager test harness, constraint
 * gating, worker decisions (unreachable -> retry, unconfigured/unknown ->
 * success, already-synced short-circuit), all against the REAL RcSession
 * (FileRcStore) with no mocks of the app's own code.
 *
 * Host note: Robolectric has no Linux/aarch64 native runtime (the SQLite
 * shadow refuses to load even in 4.17), so these tests SKIP on this build
 * host and run in the x86_64 container via
 * `bash scripts/run_workmanager_tests.sh` (evidence archived under
 * docs/verification/evidence/2026-10-10-wm/). ConscryptMode.OFF avoids a
 * separate native dependency that these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RcApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncWorkerTest {
    private lateinit var context: Context
    private lateinit var app: RcApp

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "Robolectric native runtime unsupported on Linux aarch64; " +
                "run scripts/run_workmanager_tests.sh (x86_64 container)",
            !(System.getProperty("os.name", "").contains("Linux") &&
                System.getProperty("os.arch") == "aarch64"),
        )
        context = ApplicationProvider.getApplicationContext()
        app = context as RcApp
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun startFlight(): String {
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = app.session.startFlightFromBundle(bundle)
        app.session.endFlight()
        return record.get("flight_id").asText()
    }

    private fun configureUnreachableCore() {
        // Port 1 is never listening: reachability fails fast.
        app.config.coreUrl = "http://127.0.0.1:1/api/v1/edge"
        app.config.coreToken = "tok"
    }

    @Test
    fun unconfigured_core_finishes_without_retry() {
        val flightId = startFlight()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FLIGHT_ID to flightId))
            .build()
        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun unknown_flight_finishes_without_retry() {
        configureUnreachableCore()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                workDataOf(SyncWorker.KEY_FLIGHT_ID to "00000000-0000-4000-8000-0000000000ff"),
            )
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun unreachable_core_requests_retry_and_attempt_count_feeds_policy() {
        configureUnreachableCore()
        val flightId = startFlight()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FLIGHT_ID to flightId))
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun already_synced_flight_is_skipped_via_durable_receipts() {
        configureUnreachableCore()
        val flightId = startFlight()
        // No artifacts exist yet: the worker proceeds (nothing is "synced"
        // for an evidence-bearing flight). With every artifact synced, the
        // short-circuit fires.
        val store = app.session.store
        val bytes = ByteArray(8) { it.toByte() }
        val artifactId = ksi.rc.SegmentIngestor(store)
            .ingestSegment(
                flightId, "00000000-0000-4000-8000-0000000000c1", 0,
                java.io.File.createTempFile("seg", ".mp4").apply { writeBytes(bytes) },
            )
        store.markArtifactSynced(artifactId)

        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FLIGHT_ID to flightId))
            .build()
        val result = worker.doWork()
        // Compare the RESULT CLASS + returned state, not full equality:
        // Result.success(data) carries outputData that a bare
        // Result.success() (no data) never equals — a latent test bug that
        // only surfaced when the suite first ran on the x86_64 CI runner
        // (C65).
        assertTrue(
            "expected success, got ${result.javaClass.simpleName} " +
                result.outputData.keyValueMap,
            result is androidx.work.ListenableWorker.Result.Success,
        )
        assertEquals("ALREADY_SYNCED", result.outputData.getString(SyncWorker.KEY_STATE))
    }

    @Test
    fun late_annotation_prevents_the_already_synced_short_circuit() {
        configureUnreachableCore()
        val flightId = startFlight()
        val store = app.session.store

        // Every artifact is synced...
        val bytes = ByteArray(8) { it.toByte() }
        val artifactId = ksi.rc.SegmentIngestor(store).ingestSegment(
            flightId, "00000000-0000-4000-8000-0000000000c1", 0,
            java.io.File.createTempFile("seg", ".mp4").apply { writeBytes(bytes) },
        )
        store.markArtifactSynced(artifactId)

        // ...but a LATE operator opinion is still pending (regression:
        // previously this returned success(ALREADY_SYNCED) and the opinion
        // never reached the server).
        val annotation = com.fasterxml.jackson.databind.node.JsonNodeFactory
            .instance.objectNode()
        annotation.put("schema_version", 1)
        annotation.put("annotation_id", java.util.UUID.randomUUID().toString())
        annotation.put("event_id", "00000000-0000-4000-8000-0000000000e1")
        annotation.put("flight_id", flightId)
        annotation.put("actor_id", "00000000-0000-4000-8000-0000000000aa")
        annotation.put("client_id", "00000000-0000-4000-8000-0000000000aa")
        annotation.put("annotation_type", "FALSE_POSITIVE_SUSPECTED")
        annotation.putNull("reason")
        annotation.putArray("evidence_ids")
        annotation.put("created_at_local", "2026-10-11T00:00:00Z")
        annotation.putNull("supersedes_annotation_id")
        store.queueAnnotation(annotation)

        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_FLIGHT_ID to flightId))
            .build()
        // With an unreachable Core it must now RETRY (attempt the sync)
        // instead of short-circuiting as "already synced".
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun scheduler_enqueues_gated_work_and_the_test_driver_runs_it() {
        val flightId = startFlight()
        SyncScheduler.enqueue(context, flightId)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PREFIX + flightId)
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        // The network constraint keeps it from running until satisfied —
        // WorkManager itself holds the work (this is the constraint gate).
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)

        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(infos[0].id)

        // Await the real execution of the enqueued worker.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val current = workManager.getWorkInfoById(infos[0].id)
                .get(5, TimeUnit.SECONDS)
            if (current.state == WorkInfo.State.SUCCEEDED ||
                current.state == WorkInfo.State.FAILED
            ) {
                assertEquals(WorkInfo.State.SUCCEEDED, current.state)
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("enqueued sync work did not execute")
    }
}
