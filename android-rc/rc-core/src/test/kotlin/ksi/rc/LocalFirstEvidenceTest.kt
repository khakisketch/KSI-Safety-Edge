package ksi.rc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local-first query contract (A3-b): local hits are served without any
 * server round-trip; the server is consulted only when the local store has
 * nothing for that query, and the source is always reported.
 */
class LocalFirstEvidenceTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-query")

    private class Seeded(
        val store: FileRcStore,
        val fake: FakeCore,
        val spec: FlightUploadSpec,
        val artifactId: String,
        val bytes: ByteArray,
    )

    private fun seedUploaded(): Seeded {
        val store = FileRcStore(tempRoot())
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val artifactId = Fixture.uuid(13)
        val bytes = ByteArray(16) { it.toByte() }
        seedFlightEvidence(store, spec, boot, artifactId, bytes)
        val fake = FakeCore()
        val uploader = EvidenceUploader(CoreApi(fake), store)
        assertEquals("COMPLETE", uploader.uploadFlight(spec).uploadState)
        return Seeded(store, fake, spec, artifactId, bytes)
    }

    @Test
    fun local_hits_never_touch_the_server() {
        val seeded = seedUploaded()
        val query = LocalFirstEvidence(seeded.store, CoreApi(seeded.fake))
        val requestsBefore = seeded.fake.requests.size

        val events = query.events(seeded.spec.flightId)
        assertEquals(EvidenceSource.LOCAL, events.source)
        assertEquals(1, events.items.size)

        val observations = query.observations(seeded.spec.flightId)
        assertEquals(EvidenceSource.LOCAL, observations.source)
        assertEquals(2, observations.items.size)

        val artifact = query.artifactBytes(seeded.artifactId)
        assertNotNull(artifact)
        assertEquals(EvidenceSource.LOCAL, artifact.source)
        assertContentEquals(seeded.bytes, artifact.items)

        assertEquals(
            requestsBefore, seeded.fake.requests.size,
            "local hits must not call the server",
        )
    }

    @Test
    fun server_is_consulted_only_when_local_is_empty() {
        val seeded = seedUploaded()
        val empty = FileRcStore(tempRoot())
        val query = LocalFirstEvidence(empty, CoreApi(seeded.fake))

        val events = query.events(seeded.spec.flightId)
        assertEquals(EvidenceSource.SERVER, events.source)
        assertEquals(1, events.items.size)

        val observations = query.observations(seeded.spec.flightId)
        assertEquals(EvidenceSource.SERVER, observations.source)
        assertEquals(2, observations.items.size)

        val artifact = query.artifactBytes(seeded.artifactId)
        assertNotNull(artifact)
        assertEquals(EvidenceSource.SERVER, artifact.source)
        assertContentEquals(seeded.bytes, artifact.items)

        // Neither side has it: an honest null, not an empty byte array.
        assertNull(query.artifactBytes(Fixture.uuid(999)))
    }

    @Test
    fun server_fallback_follows_pagination_cursors() {
        val seeded = seedUploaded()
        seeded.fake.pageSize = 1 // force multiple pages
        val empty = FileRcStore(tempRoot())
        val query = LocalFirstEvidence(empty, CoreApi(seeded.fake))

        val observations = query.observations(seeded.spec.flightId)
        assertEquals(EvidenceSource.SERVER, observations.source)
        assertEquals(
            2, observations.items.size,
            "every page must be concatenated (no silent truncation)",
        )
        val events = query.events(seeded.spec.flightId)
        assertEquals(1, events.items.size)

        val observationGets = seeded.fake.requests.count {
            it.startsWith("GET /flights/${seeded.spec.flightId}/observations")
        }
        assertTrue(observationGets >= 2, "the client must follow the cursor")
    }
}
