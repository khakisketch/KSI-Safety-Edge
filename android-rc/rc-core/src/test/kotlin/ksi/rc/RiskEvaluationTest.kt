package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installed-rule honesty: the Edge installs exactly the bundle's rules, so
 * a zero-rule bundle with `config_applied=true` means NO risk evaluation
 * is running. The RC must never show "risk evaluation running" on that
 * basis — the same class of error as showing 0 detections as safety.
 */
class RiskEvaluationTest {
    private inner class Fixture(val bundle: ObjectNode) {
        val store = InMemoryRcStore()
        var now = 0L
        val controller = MissionController("flt-1", bundle, store, { now })
        val edge = FakeEdge("flt-1", { now })

        fun pump(rounds: Int = 4) {
            now += 1
            ksi.rc.pump(controller.endpoint, edge, rounds)
        }

        fun prepareAndApply() {
            controller.prepare()
            pump()
            assertEquals(MissionState.PREPARED, controller.state)
            controller.apply()
            pump()
            assertEquals(MissionState.APPLIED, controller.state)
        }

        fun reportRunning(configApplied: Boolean?) {
            edge.runtimeState = "RUNNING"
            edge.runtimeEpoch = "epoch-1"
            edge.configApplied = configApplied
            edge.processedFrameCount = 5
            controller.startSession()
            pump()
            controller.reconcile()
            pump()
        }
    }

    private fun shadowBundle(zones: List<ObjectNode> = listOf(zone())): ObjectNode {
        val snapshot = MissionFixture.snapshot(
            "site-1", "mission-1", "rev-1", "flt-1", zones,
        )
        val policy = MissionFixture.policy("site-1", listOf("zr-1"))
        return MissionFixture.bundle("site-1", snapshot, policy, "bundle-1")
    }

    private fun zone(): ObjectNode =
        MissionFixture.zoneRevision("site-1", "zone-1", "zr-1", null)

    private fun zoneOnlyBundle(): ObjectNode {
        val base = shadowBundle()
        val policy = base.get("risk_policy") as ObjectNode
        val template = (policy.get("rules").get(0) as ObjectNode).deepCopy()
        template.put("rule_id", "zone-intrusion-shadow")
        template.put("kind", "ZONE_INTRUSION")
        template.put("mode", "SHADOW")
        template.putNull("threshold_m")
        template.put("required_geometry_level", "GEO_ESTIMATE")
        val rewritten = Mop.json.createArrayNode()
        rewritten.add(template)
        policy.set<JsonNode>("rules", rewritten)
        policy.put(
            "content_sha256",
            CanonicalJson.digestExcluding(policy, "content_sha256"),
        )
        return MissionFixture.bundle(
            "site-1",
            base.get("mission_snapshot") as ObjectNode,
            policy,
            base.get("bundle_id").asText(),
        )
    }

    private fun withRules(bundle: ObjectNode, modes: List<String>): ObjectNode {
        val policy = bundle.get("risk_policy") as ObjectNode
        val rules = policy.get("rules")
        check(rules.isArray && rules.size() == 1) {
            "fixture policy must carry exactly one rule to rewrite, found ${rules.size()}"
        }
        val template = rules.get(0).deepCopy() as ObjectNode
        val rewritten = Mop.json.createArrayNode()
        modes.forEachIndexed { index, mode ->
            val rule = template.deepCopy() as ObjectNode
            rule.put("rule_id", "rule-$index-$mode")
            rule.put("mode", mode)
            rewritten.add(rule)
        }
        policy.set<com.fasterxml.jackson.databind.JsonNode>("rules", rewritten)
        policy.put(
            "content_sha256",
            CanonicalJson.digestExcluding(policy, "content_sha256"),
        )
        return MissionFixture.bundle(
            "site-1",
            bundle.get("mission_snapshot") as ObjectNode,
            policy,
            bundle.get("bundle_id").asText(),
        )
    }

    @Test
    fun zero_rule_bundle_with_config_applied_has_no_risk_evaluation() {
        val f = Fixture(withRules(shadowBundle(), emptyList()))
        assertEquals(0, f.controller.appliedRuleCount)
        assertEquals(1, f.controller.appliedZoneCount)
        f.prepareAndApply()
        f.reportRunning(configApplied = true)
        assertFalse(
            f.controller.riskEvaluationAvailable,
            "zero installed rules: the Edge applied nothing to evaluate",
        )
        val reason = f.controller.riskEvaluationUnavailableReason
        assertNotNull(reason, "the zero-rule reason must be explicit for the UI")
        assertTrue(reason.contains("0"), "reason names the empty rule set: $reason")
        assertFalse(f.controller.executionFacts().riskEvaluationAvailable)
        assertEquals(0, f.controller.executionFacts().appliedRuleCount)
    }

    @Test
    fun shadow_rule_bundle_with_config_applied_enables_risk_evaluation() {
        val f = Fixture(shadowBundle())
        assertEquals(1, f.controller.appliedRuleCount)
        assertEquals(1, f.controller.appliedZoneCount)
        assertFalse(f.controller.hasActiveModeRule)
        f.prepareAndApply()
        f.reportRunning(configApplied = true)
        assertTrue(f.controller.riskEvaluationAvailable)
        assertNotNull(f.controller.executionFacts().configApplied)
        assertTrue(f.controller.executionFacts().riskEvaluationAvailable)
        assertEquals(1, f.controller.executionFacts().appliedRuleCount)
    }

    @Test
    fun rules_present_but_config_unreported_means_no_risk_evaluation() {
        val f = Fixture(shadowBundle())
        f.prepareAndApply()
        // The Edge reports execution but never reports config_applied:
        // the RC must not assume the bundle made it into the engine.
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.processedFrameCount = 5
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertFalse(f.controller.riskEvaluationAvailable)
        assertFalse(f.controller.executionFacts().riskEvaluationAvailable)
    }

    @Test
    fun legacy_edge_without_runtime_fields_never_claims_risk_evaluation() {
        val f = Fixture(shadowBundle())
        f.prepareAndApply()
        // Pure legacy `running` bool: execution is active, but the install
        // receipt was never reported — unknown, never optimistic.
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        assertEquals(MissionState.RUNNING, f.controller.state)
        assertFalse(
            f.controller.riskEvaluationAvailable,
            "legacy Edge: no config_applied report means no risk claim",
        )
        assertFalse(f.controller.executionFacts().riskEvaluationAvailable)
    }

    @Test
    fun active_only_rules_are_not_available_with_explicit_reason() {
        val f = Fixture(withRules(shadowBundle(), listOf("ACTIVE")))
        assertEquals(1, f.controller.appliedRuleCount)
        assertEquals(1, f.controller.appliedActiveRuleCount)
        assertTrue(f.controller.hasActiveModeRule)
        assertEquals(0, f.controller.appliedShadowRuleCount)
        f.prepareAndApply()
        f.reportRunning(configApplied = true)
        // ACTIVE installs are refused edge-side: an ACTIVE-only bundle has
        // NO installed SHADOW evaluation. The raw count stays visible for
        // display, but availability is false with an explicit reason — and
        // the reason never labels the raw count "(SHADOW)".
        assertFalse(
            f.controller.riskEvaluationAvailable,
            "ACTIVE rules are refused, never evaluated",
        )
        val reason = f.controller.riskEvaluationUnavailableReason
        assertNotNull(reason, "the ACTIVE/OFF-only reason must be explicit for the UI")
        assertTrue(reason.contains("ACTIVE/OFF-only"), "reason names the inert set: $reason")
        assertFalse(reason.contains("(SHADOW)"))
        assertFalse(f.controller.executionFacts().riskEvaluationAvailable)
        // Raw counts are preserved for display (not for availability).
        assertEquals(1, f.controller.executionFacts().appliedRuleCount)
    }

    @Test
    fun off_only_rules_are_not_available_with_explicit_reason() {
        val f = Fixture(withRules(shadowBundle(), listOf("OFF")))
        assertEquals(1, f.controller.appliedRuleCount)
        assertEquals(0, f.controller.appliedActiveRuleCount)
        assertEquals(0, f.controller.appliedShadowRuleCount)
        assertEquals(1, f.controller.appliedOffRuleCount)
        f.prepareAndApply()
        f.reportRunning(configApplied = true)
        assertFalse(
            f.controller.riskEvaluationAvailable,
            "OFF rules are installed but inert, never evaluated",
        )
        val reason = f.controller.riskEvaluationUnavailableReason
        assertNotNull(reason, "the ACTIVE/OFF-only reason must be explicit for the UI")
        assertTrue(reason.contains("ACTIVE/OFF-only"), "reason names the inert set: $reason")
    }

    @Test
    fun zone_geometry_unavailable_withholds_zone_intrusion_judgment() {
        val f = Fixture(zoneOnlyBundle())
        assertEquals(1, f.controller.appliedShadowZoneRuleCount)
        assertEquals(1, f.controller.appliedEnabledZoneCount)
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 5
        f.edge.inputState = mapOf("engine" to "ok")
        f.edge.degradedReasons = listOf("zone_geometry_unavailable")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        // Install bound, config applied, SHADOW zone rule present — but the
        // Edge withholds zone verdicts: no zone-intrusion judgment is valid.
        assertFalse(
            f.controller.riskEvaluationAvailable,
            "a held zone never reads as a valid intrusion judgment",
        )
        val reason = f.controller.riskEvaluationUnavailableReason
        assertNotNull(reason)
        assertTrue(
            reason.contains("withheld") || reason.contains("zone_geometry_unavailable"),
            "reason names the withheld zone judgments: $reason",
        )
    }

    @Test
    fun proximity_shadow_still_available_under_zone_geometry_hold() {
        val f = Fixture(shadowBundle())
        assertEquals(1, f.controller.appliedShadowProximityRuleCount)
        f.prepareAndApply()
        f.edge.runtimeState = "RUNNING"
        f.edge.runtimeEpoch = "epoch-1"
        f.edge.configApplied = true
        f.edge.processedFrameCount = 5
        f.edge.inputState = mapOf("engine" to "ok")
        f.edge.degradedReasons = listOf("zone_geometry_unavailable")
        f.controller.startSession()
        f.pump()
        f.controller.reconcile()
        f.pump()
        // The hold withholds zone verdicts only — proximity evaluation on
        // installed SHADOW rules still runs.
        assertTrue(
            f.controller.riskEvaluationAvailable,
            "a zone hold is not a loss of proximity evaluation",
        )
        assertNull(f.controller.riskEvaluationUnavailableReason)
    }

    @Test
    fun missing_sections_count_as_zero_never_optimistic() {
        val bundle = Mop.json.createObjectNode()
        bundle.put("schema_version", 1)
        bundle.put("bundle_id", "bundle-bare")
        bundle.put("site_id", "site-1")
        bundle.put(
            "bundle_sha256",
            CanonicalJson.digestExcluding(bundle, "bundle_sha256"),
        )
        val f = Fixture(bundle)
        assertEquals(0, f.controller.appliedRuleCount)
        assertEquals(0, f.controller.appliedZoneCount)
        assertFalse(f.controller.riskEvaluationAvailable)
    }
}
