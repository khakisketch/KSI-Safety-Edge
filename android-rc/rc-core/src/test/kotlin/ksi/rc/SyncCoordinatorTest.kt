package ksi.rc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A8 sync coordinator: reachability-gated, idempotent, resumable uploads.
 * The COMMITTED gate itself lives in EvidenceUploader; this layer only
 * decides "can we even talk to Core, and what did the attempt produce".
 */
class SyncCoordinatorTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-sync")

    private fun seededStore(root: Path): Pair<FileRcStore, FlightUploadSpec> {
        val store = FileRcStore(root)
        val spec = uploadSpec()
        seedFlightEvidence(
            store, spec, Fixture.uuid(12), Fixture.uuid(13), ByteArray(16) { it.toByte() },
        )
        return store to spec
    }

    @Test
    fun unreachable_core_fails_fast_without_attempting_an_upload() {
        val (store, spec) = seededStore(tempRoot())
        val fake = FakeCore().apply { healthy = false }
        val core = CoreApi(fake)
        val coordinator = SyncCoordinator(core, EvidenceUploader(core, store))

        val outcome = coordinator.syncOnce(spec)
        assertEquals(SyncOutcome.UNREACHABLE, outcome.state)
        assertTrue(fake.flights.isEmpty(), "no register attempt when unreachable")
        assertTrue(fake.ingests.isEmpty(), "no ingest session when unreachable")
        assertTrue(
            fake.requests.all { it == "GET /healthz" },
            "only the health probe may run while unreachable: ${fake.requests}",
        )
    }

    @Test
    fun reachable_sync_is_idempotent_and_resumes_after_failure() {
        val (store, spec) = seededStore(tempRoot())
        val fake = FakeCore()
        val core = CoreApi(fake)
        val coordinator = SyncCoordinator(core, EvidenceUploader(core, store, chunkBytes = 8))

        // Mid-upload transport failure -> FAILED (coordinator catches),
        // nothing marked, then the same syncOnce resumes to COMPLETE.
        fake.failAtChunkPut = 2
        val failed = coordinator.syncOnce(spec)
        assertEquals(SyncOutcome.FAILED, failed.state)
        assertTrue(!store.isArtifactSynced(Fixture.uuid(13)))

        fake.failAtChunkPut = null
        val resumed = coordinator.syncOnce(spec)
        assertEquals(SyncOutcome.COMPLETE, resumed.state)
        assertEquals(
            1, resumed.report!!.boots.count { it.committed },
        )
        val putsAfterResume = fake.chunkPuts

        // Idempotent re-run: COMPLETE again, nothing re-sent.
        val again = coordinator.syncOnce(spec)
        assertEquals(SyncOutcome.COMPLETE, again.state)
        assertEquals(putsAfterResume, fake.chunkPuts)
    }
}
