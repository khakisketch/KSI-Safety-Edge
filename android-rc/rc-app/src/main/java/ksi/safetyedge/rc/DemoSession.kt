package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.util.UUID
import ksi.rc.Mop

/**
 * Physically isolated demo bench (A4 review fix): its own store directory
 * (`rc-demo-store`), its own demo flight ids, its own FakeDroneLink, and NO
 * Core configuration — demo data can never reach the upload path and never
 * mixes with operational evidence. The UI labels everything it produces.
 */
class DemoSession(
    filesDir: File,
    wallClock: () -> String,
) {
    /** Deterministic demo mission identities (never used off this bench). */
    private val siteId = Mop.newMsgId("demo", "site")
    private val missionId = Mop.newMsgId("demo", "mission")
    private val missionRevisionId = Mop.newMsgId("demo", "mission-rev")
    private val missionSha256 = "d".repeat(64)
    private val zoneRevisionId = Mop.newMsgId("demo", "zone-rev")

    val session = RcSession(
        storeDir = File(filesDir, "rc-demo-store"),
        rcDeviceId = Mop.newMsgId("demo", "rc-device"),
        wallClock = wallClock,
        link = FakeDroneLink(),
        coreConfig = { null }, // demo never uploads
    )

    private var feed: DemoFeed? = null
    private val artifactBytes = ByteArray(8192) { (it % 251).toByte() }

    init {
        attachCurrentOrStart()
    }

    fun activeFlight(): ObjectNode? = session.activeFlight

    /** New demo flight (new id) — old demo evidence stays in its own store. */
    fun startNewFlight(): ObjectNode {
        session.endFlight()
        return startFlight()
    }

    fun tick(): Int {
        val current = feed ?: attachCurrentOrStart().let { feed!! }
        var count = 0
        for (bytes in current.tick(artifactBytes)) {
            session.onLinkBytes(bytes)
            count += 1
        }
        session.pumpOutbound()
        return count
    }

    fun missionSummary(): String = session.missionSummary()

    private fun attachCurrentOrStart(): ObjectNode {
        val active = session.activeFlight ?: return startFlight()
        attach(active.get("flight_id").asText())
        return active
    }

    private fun startFlight(): ObjectNode {
        val bundle = demoBundle()
        val record = session.startFlight(
            siteId = siteId,
            missionId = missionId,
            missionRevisionId = missionRevisionId,
            missionSha256 = missionSha256,
            bundleId = bundle.get("bundle_id").asText(),
            bundleSha256 = bundle.get("bundle_sha256").asText(),
            zoneRevisionIds = listOf(zoneRevisionId),
        )
        attach(record.get("flight_id").asText())
        return record
    }

    private fun attach(flightId: String) {
        session.attachController(demoBundle(), flightId)
        feed = DemoFeed(flightId, demoBootId(flightId), missionRevisionId)
    }

    private fun demoBootId(flightId: String): String {
        require(flightId.length == 36) { "flight id is not a uuid" }
        return flightId.replaceRange(24, 36, "b007b007b007")
    }

    private fun demoBundle(): ObjectNode = DemoFeed.demoBundle(
        bundleId = Mop.newMsgId("demo", "bundle"),
        siteId = siteId,
    )
}
