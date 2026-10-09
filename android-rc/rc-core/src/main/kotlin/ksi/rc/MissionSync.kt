package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * A5 mission synchronizer: downloads site / zone-revision / mission-snapshot
 * / bundle records from the Core into the local [MissionStore], VERIFYING
 * every digest before anything is stored. It never re-implements the server
 * contract: records are stored exactly as the server serves them, and
 * [MissionStore] enforces immutability per revision id.
 *
 * Conflicts: a re-download of identical content is a no-op; different
 * content under the same revision id is an integrity failure (throws).
 * Newer server revisions are additive — flights keep the revisions they
 * pinned at start.
 */
class MissionSync(
    private val core: CoreApi,
    private val store: MissionStore,
) {
    /** Stores every visible site; returns the server's list. */
    fun syncSites(): List<ObjectNode> {
        val items = itemsOf(core.listSites())
        items.forEach { store.saveSite(it) }
        return items
    }

    /** Stores zone revisions for a site; returns ids NEWLY stored. */
    fun syncZoneRevisions(siteId: String): List<String> {
        val newIds = mutableListOf<String>()
        for (revision in itemsOf(core.listZoneRevisions(siteId))) {
            val id = revision.get("revision_id")?.asText() ?: continue
            val known = store.zoneRevision(id) != null
            store.saveZoneRevision(revision)
            if (!known) newIds.add(id)
        }
        return newIds
    }

    /** Server zone revision ids not present locally (update signal). */
    fun checkZoneUpdates(siteId: String): List<String> {
        val local = store.zoneRevisions(siteId)
            .mapNotNull { it.get("revision_id")?.asText() }.toSet()
        return itemsOf(core.listZoneRevisions(siteId))
            .mapNotNull { it.get("revision_id")?.asText() }
            .filter { it !in local }
    }

    /** Downloads + digest-verifies a mission snapshot, then stores it. */
    fun syncMissionSnapshot(missionId: String): ObjectNode {
        val snapshot = core.getMissionSnapshot(missionId)
        check(CanonicalJson.verifyDigest(snapshot, "content_sha256")) {
            "mission snapshot digest mismatch ($missionId)"
        }
        store.saveMissionSnapshot(snapshot)
        return snapshot
    }

    /** Server bundles for a site (server order = newest last). */
    fun listSiteBundles(siteId: String): List<ObjectNode> =
        itemsOf(core.listMissionBundles())
            .filter { it.get("site_id")?.asText() == siteId }

    /** Local (stored) bundles for a site. */
    fun localSiteBundles(siteId: String): List<ObjectNode> =
        store.missionBundles().filter { it.get("site_id")?.asText() == siteId }

    /**
     * Downloads + digest-verifies a bundle (and its embedded snapshot),
     * then stores both. This is the record Prepare/Apply sends to the Edge.
     */
    fun syncBundle(bundleId: String): ObjectNode {
        val bundle = core.getMissionBundle(bundleId)
        check(CanonicalJson.verifyDigest(bundle, "bundle_sha256")) {
            "bundle digest mismatch ($bundleId)"
        }
        val snapshot = bundle.get("mission_snapshot") as? ObjectNode
            ?: throw IllegalStateException("bundle without mission_snapshot")
        check(CanonicalJson.verifyDigest(snapshot, "content_sha256")) {
            "embedded mission snapshot digest mismatch ($bundleId)"
        }
        store.saveMissionSnapshot(snapshot)
        store.saveMissionBundle(bundle)
        return bundle
    }

    private fun itemsOf(view: ObjectNode): List<ObjectNode> =
        view.get("items")?.mapNotNull { it as? ObjectNode } ?: emptyList()
}
