package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A5 mission sync contracts: download + digest-verify + store, restart
 * restore, additive revisions with immutable flight pins, and integrity
 * rejection of any same-id/different-content rewrite.
 */
class MissionSyncTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-mission-sync")

    private class Seeded(
        val fake: FakeCore,
        val store: FileRcStore,
        val siteId: String,
        /** Zone revision payload as the SERVER stored it (server-assigned id). */
        val zoneV1: ObjectNode,
        val missionId: String,
        val snapshot: ObjectNode,
        val bundle: ObjectNode,
    )

    private fun seed(root: Path): Seeded {
        val fake = FakeCore()
        val site = postToFake(
            fake, "/sites",
            Mop.json.createObjectNode().put("org_id", "org-a").put("name", "site-sync"),
        )
        val siteId = site.get("site_id").asText()
        val zoneRequest = MissionFixture.zoneRevision(
            siteId, Fixture.uuid(700), Fixture.uuid(701), null,
        )
        // The server assigns the revision id (client's is informational).
        val serverRevisionId = postToFake(
            fake, "/sites/$siteId/zone-revisions", zoneRequest,
        ).get("revision_id").asText()
        val zoneV1 = zoneRequest.deepCopy().put("revision_id", serverRevisionId)
        val missionId = Fixture.uuid(710)
        val snapshot = MissionFixture.snapshot(
            siteId, missionId, Fixture.uuid(711), Fixture.uuid(100),
            listOf(zoneV1),
        )
        postToFake(fake, "/missions", snapshot)
        val bundle = MissionFixture.bundle(
            siteId, snapshot,
            MissionFixture.policy(siteId, listOf(zoneV1.get("revision_id").asText())),
            Fixture.uuid(712),
        )
        postToFake(fake, "/mission-bundles", bundle)
        return Seeded(
            fake, FileRcStore(root), siteId, zoneV1, missionId, snapshot, bundle,
        )
    }

    @Test
    fun sync_verifies_stores_and_restores_after_restart() {
        val root = tempRoot()
        val seeded = seed(root)
        val sync = MissionSync(CoreApi(seeded.fake), seeded.store)

        val sites = sync.syncSites()
        assertEquals(1, sites.size)
        assertEquals(1, sync.syncZoneRevisions(seeded.siteId).size)
        val snapshot = sync.syncMissionSnapshot(seeded.missionId)
        assertTrue(CanonicalJson.verifyDigest(snapshot, "content_sha256"))
        val bundles = sync.listSiteBundles(seeded.siteId)
        assertEquals(1, bundles.size)
        val bundle = sync.syncBundle(bundles[0].get("bundle_id").asText())
        assertTrue(CanonicalJson.verifyDigest(bundle, "bundle_sha256"))

        // Restart: everything is restored from disk.
        val restarted = FileRcStore(root)
        assertEquals(1, restarted.sites().size)
        assertEquals(1, restarted.zoneRevisions(seeded.siteId).size)
        assertNotNull(
            restarted.missionSnapshot(snapshot.get("mission_revision_id").asText()),
        )
        val restoredBundle = assertNotNull(
            restarted.missionBundle(bundle.get("bundle_id").asText()),
        )
        assertEquals(
            seeded.bundle.get("bundle_sha256").asText(),
            restoredBundle.get("bundle_sha256").asText(),
        )
        // The embedded snapshot arrived with the bundle too.
        assertNotNull(
            restarted.missionSnapshot(
                bundle.get("mission_snapshot").get("mission_revision_id").asText(),
            ),
        )
    }

    @Test
    fun identical_redownload_is_a_noop() {
        val seeded = seed(tempRoot())
        val sync = MissionSync(CoreApi(seeded.fake), seeded.store)
        sync.syncZoneRevisions(seeded.siteId)
        val again = sync.syncZoneRevisions(seeded.siteId)
        assertTrue(again.isEmpty(), "known revisions are not re-reported as new")
        sync.syncBundle(seeded.bundle.get("bundle_id").asText())
        // Second download of the same bytes: no-op, no error.
        sync.syncBundle(seeded.bundle.get("bundle_id").asText())
        assertEquals(1, seeded.store.missionBundles().size)
    }

    @Test
    fun tampered_digests_are_rejected_before_storage() {
        val seeded = seed(tempRoot())
        val sync = MissionSync(CoreApi(seeded.fake), seeded.store)

        // Server-side corruption: a snapshot whose content no longer hashes
        // to its claimed digest.
        val tampered = seeded.fake.missions[seeded.missionId]!!.deepCopy()
        (tampered.get("zones") as com.fasterxml.jackson.databind.node.ArrayNode)
            .remove(0)
        seeded.fake.missions[seeded.missionId] = tampered
        assertFailsWith<IllegalStateException> {
            sync.syncMissionSnapshot(seeded.missionId)
        }
        assertNull(
            seeded.store.missionSnapshot(
                seeded.snapshot.get("mission_revision_id").asText(),
            ),
            "a tampered snapshot must not be stored",
        )

        // Same for bundles: recompute nothing, just corrupt a field.
        val tamperedBundle = seeded.fake.bundles[
            seeded.bundle.get("bundle_id").asText()
        ]!!.deepCopy()
        tamperedBundle.put("created_by", "tampered")
        seeded.fake.bundles[seeded.bundle.get("bundle_id").asText()] = tamperedBundle
        assertFailsWith<IllegalStateException> {
            sync.syncBundle(seeded.bundle.get("bundle_id").asText())
        }
        assertNull(seeded.store.missionBundle(seeded.bundle.get("bundle_id").asText()))
    }

    @Test
    fun new_revisions_are_additive_and_flight_pins_survive() {
        val seeded = seed(tempRoot())
        val sync = MissionSync(CoreApi(seeded.fake), seeded.store)
        sync.syncZoneRevisions(seeded.siteId)

        // A flight pinned to zone v1 exists.
        val flight = Mop.json.createObjectNode()
        flight.put("schema_version", 1)
        flight.put("flight_id", Fixture.uuid(100))
        flight.put("site_id", seeded.siteId)
        flight.put("mission_id", seeded.missionId)
        flight.put("mission_revision_id", seeded.snapshot.get("mission_revision_id").asText())
        flight.put("mission_sha256", seeded.snapshot.get("content_sha256").asText())
        flight.put("bundle_id", seeded.bundle.get("bundle_id").asText())
        flight.put("bundle_sha256", seeded.bundle.get("bundle_sha256").asText())
        flight.putArray("zone_revision_ids").add(seeded.zoneV1.get("revision_id").asText())
        flight.put("started_at_utc", "2026-10-10T01:00:00Z")
        flight.putNull("ended_at_utc")
        flight.put("state", "STARTED")
        seeded.store.startFlight(flight)

        // The server publishes v2 (CAS from v1): detected as an update, then
        // downloaded additively.
        val zoneV2Request = MissionFixture.zoneRevision(
            seeded.siteId, Fixture.uuid(700), Fixture.uuid(702),
            seeded.zoneV1.get("revision_id").asText(), lon = 127.001,
        )
        val zoneRevV2 = postToFake(
            seeded.fake, "/sites/${seeded.siteId}/zone-revisions", zoneV2Request,
        ).get("revision_id").asText()
        assertEquals(
            listOf(zoneRevV2),
            sync.checkZoneUpdates(seeded.siteId),
        )
        assertEquals(
            listOf(zoneRevV2),
            sync.syncZoneRevisions(seeded.siteId),
        )
        assertEquals(2, seeded.store.zoneRevisions(seeded.siteId).size)
        assertEquals(emptyList(), sync.checkZoneUpdates(seeded.siteId))

        // The flight keeps its pinned v1 snapshot forever.
        val pinned = seeded.store.flight(Fixture.uuid(100))!!
        assertEquals(1, pinned.get("zone_revision_ids").size())
        assertEquals(
            seeded.zoneV1.get("revision_id").asText(),
            pinned.get("zone_revision_ids")[0].asText(),
        )
    }

    @Test
    fun same_revision_with_different_content_fails_integrity() {
        val store = FileRcStore(tempRoot())
        val revision = MissionFixture.zoneRevision(
            Fixture.uuid(700), Fixture.uuid(701), Fixture.uuid(702), null,
        )
        store.saveZoneRevision(revision)
        // Identical re-save: fine.
        store.saveZoneRevision(revision)
        // Rewritten geometry under the same revision id: integrity failure.
        val rewritten = revision.deepCopy()
        (rewritten.get("geometry_wgs84") as ObjectNode).remove("coordinates")
        assertFailsWith<IllegalStateException> {
            store.saveZoneRevision(rewritten)
        }
    }
}
