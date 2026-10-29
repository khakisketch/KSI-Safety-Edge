package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import ksi.rc.LocalBundleRequest
import ksi.rc.LocalBundleResult
import ksi.rc.MissionBundleBuilder
import ksi.rc.MissionStore

/**
 * RC-local mission-bundle composition (product gap G11).
 *
 * Pure Kotlin, no Android: host-tested. Builds a SHADOW-rule bundle from
 * the RC's OWN stored zone revisions plus the stored mission snapshot
 * basis, then saves it with [MissionStore.saveMissionBundle] so the
 * existing startFlightFromBundle -> prepare -> apply path can install it.
 * Nothing is invented: zone polygons stay WGS84 GeoJSON from the stored
 * revisions, and revisions without usable geometry are excluded with a
 * reported reason (see [MissionBundleBuilder]).
 *
 * The EXISTING empty demo bundle ([DemoFeed.demoBundle]) stays as the
 * SIMULATED bench path; this LOCAL path is explicit and separate.
 */
object LocalBundleComposer {

    /**
     * Latest stored mission snapshot for the site to inherit the
     * mission/org/profile/recording basis from (its zones are REPLACED by
     * the pinned selection). Null when the RC holds no snapshot for the
     * site — the caller must sync first, never invent one.
     */
    fun baseSnapshotFor(store: MissionStore, siteId: String): ObjectNode? =
        store.missionSnapshots().lastOrNull {
            it.get("site_id")?.asText() == siteId
        }

    /** Build from stored revisions and persist the composed bundle. */
    fun composeAndSave(store: MissionStore, request: LocalBundleRequest): LocalBundleResult {
        val result = MissionBundleBuilder.build(store, request)
        store.saveMissionBundle(result.bundle)
        return result
    }
}
