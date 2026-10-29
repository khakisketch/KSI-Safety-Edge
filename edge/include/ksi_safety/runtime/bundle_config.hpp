// Mission-bundle -> runtime config (Unit A / contract G9.1).
//
// Reads mission_snapshot.zones[] (WGS84 GeoJSON) and risk_policy.rules[]
// from an applied mission bundle into the engine types (RiskRule,
// ZoneRevision). Rule-kind/mode/geometry/class-list semantics mirror the
// replay policy file (edge/src/replay/policy_file.cpp); the wire field
// names mirror contracts/schemas/risk-policy.v1.schema.json and
// mission-snapshot.v1.schema.json.
//
// Zone geometry honesty (§4.6, requirement 5): bundle zone polygons arrive
// as WGS84 GeoJSON while ZoneRevision carries local-plane Polygon2, and no
// site-frame conversion inputs exist on this host. Installed zones therefore
// hold ZERO local polygons. A zone rule installed against such held zones
// can never report a verdict: RelatePointToZone/RelatePolygonToZone map an
// empty zone to kUnknownStatus/kNoPolygons, the engine classifies that as
// unknown evidence, and unknown edges never confirm and never close. The
// parser reports zone_geometry_unavailable whenever a live (non-OFF) zone
// rule has no convertible local geometry, so the runtime surfaces the hold
// instead of "no intrusion".
//
// Production mission-bundle schema requires the zones/rules arrays: an
// absent array is malformed (fail closed, never an empty install). The
// pinned rc-to-edge framing stub omits both keys — it stays byte-valid
// TRANSPORT (digest-verified PREPARE) but is NOT valid runtime input.
// Explicit [] is the empty install (0 rules, still hashed).
// Present-but-wrong-typed arrays also fail closed, as does every other
// malformed field.
//
// ACTIVE is never installed (§4.5): ACTIVE-mode rules are skipped and
// reported as policy_install_rejected. Malformed bundles fail closed
// (ok=false, nothing installed).
//
// No wall clock, no RNG: the effective config hash is the SHA-256 of the
// canonical (JCS) bytes of a normalized config object with sorted arrays,
// so identical bundles hash identically.

#ifndef KSI_SAFETY_RUNTIME_BUNDLE_CONFIG_HPP
#define KSI_SAFETY_RUNTIME_BUNDLE_CONFIG_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/spatial/relation.hpp"
#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

// Degraded-reason codes from the operation contract §3 (subset produced by
// the install step; the runner adds input-health reasons at tick time).
constexpr const char* kDegradedPolicyInstallRejected = "policy_install_rejected";
constexpr const char* kDegradedZoneGeometryUnavailable =
    "zone_geometry_unavailable";

// One enabled-model candidate from mission_snapshot.model_profiles[].
struct BundleModelProfile {
    std::string model_id;
    // Canonical digest as written in the bundle (may carry a "sha256:"
    // prefix — normalized before compare).
    std::string model_digest;
    bool enabled = false;
};

// Strips a single "sha256:" prefix for digest comparison (schema wants raw
// 64-hex; bundles in the wild may prefix it). No case folding, no guessing.
std::string NormalizeModelDigest(const std::string& digest);

struct BundleRuntimeConfig {
    std::vector<RiskRule> proximity_rules;
    std::vector<RiskRule> zone_rules;
    // Installed zone revisions. Polygons are ALWAYS empty (held — see the
    // header note): local-plane coordinates cannot be derived from WGS84
    // GeoJSON without site-frame conversion inputs.
    std::vector<ZoneRevision> zones;
    // Enabled model profiles the bundle was built for (absent key = no
    // constraint; present = validated shape, compared against the actually
    // loaded engine before install — no asserted model without the bytes).
    std::vector<BundleModelProfile> model_profiles;
    // Canonical SHA-256 (lowercase hex) of the normalized installed config,
    // bound to the bundle/policy/mission revisions. Empty when !ok.
    std::string effective_config_hash;
    // Persistent install holds (subset of the contract §3 code list).
    std::vector<std::string> degraded_reasons;
    // Rule ids refused because mode == ACTIVE (never installed).
    std::vector<std::string> rejected_rule_ids;
    bool ok = false;
    // Fail-closed diagnostics when !ok.
    std::vector<std::string> errors;
};

// Parses an applied mission bundle (the stored MISSION_PREPARE payload).
// Never throws; malformed input yields ok=false with errors set.
BundleRuntimeConfig ParseBundleRuntimeConfig(const JsonValue& bundle);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RUNTIME_BUNDLE_CONFIG_HPP
