package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Host tests for the REAL apply path (product gap G11): prepare ->
 * apply -> MISSION_APPLIED reports APPLIED with the confirmed digest, and
 * an Edge rejection reports REJECTED with reasons — never a silent or
 * estimated state. The preparation model names each outcome honestly.
 */
class RcSessionLocalApplyTest {

    private var counter = 0

    private inner class Fixture {
        val link = FakeDroneLink()
        val session = RcSession(
            storeDir = Files.createTempDirectory("rc-local-apply").toFile(),
            rcDeviceId = "00000000-0000-4000-8000-0000000000aa",
            wallClock = { "2026-10-10T07:00:00Z" },
            link = link,
            coreConfig = { null },
        )
        val bootId = "00000000-0000-4000-8000-0000000000e1"

        fun edgeBytes(
            flightId: String,
            type: String,
            payload: com.fasterxml.jackson.databind.node.ObjectNode,
        ): ByteArray {
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
    }

    @Test
    fun prepare_apply_reports_applied_with_the_pinned_digest() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!

        controller.prepare()
        assertEquals(MissionState.PREPARING, controller.state)
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        assertEquals(MissionState.PREPARED, controller.state)

        controller.apply()
        assertEquals(MissionState.APPLYING, controller.state)
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-1")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, controller.state)
        assertEquals(
            bundle.get("bundle_sha256").asText(),
            controller.lastApplication!!.get("bundle_sha256").asText(),
        )

        // The flight record follows the confirmed digest (STARTED -> APPLIED).
        f.session.syncFlightState()
        assertEquals("APPLIED", f.session.store.flight(flightId)!!.get("state").asText())

        val panel = MissionPreparationModel.render(
            MissionPreparationModel.Input(
                syncResult = MissionPreparationModel.SyncResult.SUCCESS_LOCAL,
                siteId = bundle.get("site_id").asText(),
                bundleId = bundle.get("bundle_id").asText(),
                bundleDigest = bundle.get("bundle_sha256").asText(),
                bundleDigestVerified = true,
                missionRevisionId = "rev",
                zoneRevisionIds = emptyList(),
                controllerState = controller.state.name,
                applyConfirmed = true,
                applicationId = controller.lastApplication!!.get("application_id").asText(),
            ),
        )
        assertTrue(panel.applyLine.startsWith("Edge 적용 · APPLIED 확인"))
    }

    @Test
    fun edge_rejection_reports_rejected_with_reasons() {
        val f = Fixture()
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        val record = f.session.startFlightFromBundle(bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!

        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
            ),
        )
        controller.apply()
        val reasons = Mop.json.createArrayNode()
            .add("policy_install_rejected")
            .add("unknown_zone_revision")
        val rejection = Mop.json.createObjectNode()
        rejection.set<com.fasterxml.jackson.databind.JsonNode>("reasons", reasons)
        f.session.onLinkBytes(f.edgeBytes(flightId, "MISSION_APPLY_REJECTED", rejection))

        assertEquals(MissionState.REJECTED, controller.state)
        assertEquals(
            listOf("policy_install_rejected", "unknown_zone_revision"),
            controller.lastRejectionReasons,
        )

        val panel = MissionPreparationModel.render(
            MissionPreparationModel.Input(
                syncResult = MissionPreparationModel.SyncResult.SUCCESS_LOCAL,
                siteId = bundle.get("site_id").asText(),
                bundleId = bundle.get("bundle_id").asText(),
                bundleDigest = bundle.get("bundle_sha256").asText(),
                bundleDigestVerified = true,
                missionRevisionId = "rev",
                zoneRevisionIds = emptyList(),
                controllerState = controller.state.name,
                applyConfirmed = false,
                applicationId = null,
                rejectionReasons = controller.lastRejectionReasons,
            ),
        )
        assertTrue(panel.applyLine.contains("거부됨"))
        assertTrue(panel.applyLine.contains("policy_install_rejected"))
        assertTrue(panel.applyLine.contains("unknown_zone_revision"))
    }

    @Test
    fun preparing_prepared_and_applying_states_are_named_not_merged() {
        val preparing = MissionPreparationModel.applyLine(
            controllerState = "PREPARING",
            applyConfirmed = null,
            applicationId = null,
            rejectionReasons = emptyList(),
            linkUp = true,
        )
        val prepared = MissionPreparationModel.applyLine(
            controllerState = "PREPARED",
            applyConfirmed = null,
            applicationId = null,
            rejectionReasons = emptyList(),
            linkUp = true,
        )
        val applying = MissionPreparationModel.applyLine(
            controllerState = "APPLYING",
            applyConfirmed = null,
            applicationId = null,
            rejectionReasons = emptyList(),
            linkUp = true,
        )

        assertTrue(preparing.contains("PREPARING"))
        assertTrue(prepared.contains("PREPARED"))
        assertTrue(applying.contains("APPLYING"))
        assertTrue(
            "intermediate states must never read as confirmed",
            !preparing.contains("APPLIED 확인") &&
                !prepared.contains("APPLIED 확인") &&
                !applying.contains("APPLIED 확인"),
        )
    }

    /**
     * Full LOCAL path end to end: stored zone revisions + stored snapshot
     * -> composed RC_LOCAL/SHADOW bundle -> pinned flight -> prepare ->
     * apply -> APPLIED with the confirmed digest. Nothing in this chain
     * invents geometry or leaves the SIMULATED bench path.
     */
    @Test
    fun composed_local_bundle_flows_through_prepare_apply_applied() {
        val siteId = "00000000-0000-4000-8000-000000000021"
        val zoneA = "00000000-0000-4000-8000-000000000022"
        val revA = "00000000-0000-4000-8000-000000000023"
        val f = Fixture()
        val revision = zoneRevision(siteId, zoneA, revA)
        f.session.store.saveZoneRevision(revision)
        f.session.store.saveMissionSnapshot(missionSnapshot(siteId, listOf(revision)))

        val composed = f.session.composeLocalBundle(siteId, "2026-10-10T07:00:00Z")
        assertEquals(listOf(revA), composed.zoneRevisionIds)
        assertEquals(1, composed.ruleCount)
        assertEquals("RC_LOCAL", composed.bundle.get("origin").asText())
        val rules = composed.bundle.get("risk_policy").get("rules")
        assertEquals(1, rules.size())
        assertEquals("SHADOW", rules.get(0).get("mode").asText())

        val record = f.session.startFlightFromBundle(composed.bundle)
        val flightId = record.get("flight_id").asText()
        val controller = f.session.controller!!
        controller.prepare()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", composed.bundle.get("bundle_sha256").asText()),
            ),
        )
        controller.apply()
        f.session.onLinkBytes(
            f.edgeBytes(
                flightId, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", "app-local-1")
                    .put("effective_edge_seq", 0L),
            ),
        )
        assertEquals(MissionState.APPLIED, controller.state)
        assertEquals(
            composed.bundle.get("bundle_sha256").asText(),
            controller.lastApplication!!.get("bundle_sha256").asText(),
        )
        f.session.syncFlightState()
        assertEquals("APPLIED", f.session.store.flight(flightId)!!.get("state").asText())
        // The applied bundle's own SHADOW rule count is what the runtime
        // panel reports — one SHADOW zone rule, never an invented ACTIVE
        // install, never the raw count labeled SHADOW.
        assertEquals(1, controller.appliedShadowRuleCount)
        assertEquals(1, controller.appliedShadowZoneRuleCount)
        assertEquals(0, controller.appliedShadowProximityRuleCount)
        assertEquals(0, controller.appliedActiveRuleCount)
        assertEquals(0, controller.appliedOffRuleCount)
    }

    private fun zoneRevision(
        siteId: String,
        zoneId: String,
        revisionId: String,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("zone_id", zoneId)
        node.put("revision_id", revisionId)
        node.putNull("base_revision_id")
        node.put("site_id", siteId)
        val geometry = node.putObject("geometry_wgs84")
        geometry.put("type", "Polygon")
        val ring = geometry.putArray("coordinates").addArray()
        ring.addArray().add(127.0).add(37.0)
        ring.addArray().add(127.0008).add(37.0)
        ring.addArray().add(127.0008).add(37.0007)
        ring.addArray().add(127.0).add(37.0)
        node.put("rule_kind", "PERSON_ENTRY")
        return node
    }

    private fun missionSnapshot(
        siteId: String,
        zones: List<com.fasterxml.jackson.databind.node.ObjectNode>,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("mission_id", "00000000-0000-4000-8000-000000000024")
        node.put("flight_id", "00000000-0000-4000-8000-000000000025")
        node.put("org_id", "00000000-0000-4000-8000-000000000026")
        node.put("site_id", siteId)
        node.put("mission_revision_id", "00000000-0000-4000-8000-000000000027")
        node.put("origin", "SERVER")
        node.put("created_at_utc", "2026-10-10T00:00:00Z")
        val zoneArr = node.putArray("zones")
        for (zone in zones) {
            val entry = zoneArr.addObject()
            entry.put("zone_id", zone.get("zone_id").asText())
            entry.put("revision_id", zone.get("revision_id").asText())
            entry.put("rule_kind", zone.get("rule_kind").asText())
            entry.set<com.fasterxml.jackson.databind.JsonNode>(
                "geometry_wgs84", zone.get("geometry_wgs84").deepCopy(),
            )
            entry.put("zone_sha256", "0".repeat(64))
            entry.put("enabled", true)
        }
        node.putArray("model_profiles").addObject()
            .put("model_id", "ksi-person-detector")
            .put("model_digest", "a".repeat(64))
            .put("enabled", true)
        node.putObject("recording_policy").put("rc_proxy", true)
        node.put(
            "content_sha256",
            ksi.rc.CanonicalJson.digestExcluding(node, "content_sha256"),
        )
        return node
    }
}
