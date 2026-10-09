// Replay policy v1: the scenario-side input for spatial estimation and risk
// rules (R4). Line format (UTF-8, '#' comments, blank lines ignored):
//
//   spatial intrinsics <fx> <fy> <cx> <cy> <ref_width> <ref_height>
//   spatial ground <up_m>
//   spatial min-ray-z <value>
//   spatial person-class <class_id>
//   spatial equipment-class <class_id> <name>
//   zone <revision_id> <x> <y> <x> <y> <x> <y> [<x> <y> ...]
//   proximity <rule_id> <threshold_m> <hysteresis_m> <enter_ms> <exit_ms> <geometry> [class ...]
//   zone-rule <rule_id> <enter_ms> <exit_ms> <geometry> [class ...]
//
// - `spatial` lines configure the GroundEstimator (camera intrinsics, ground
//   plane, horizon guard, class map). At least one spatial line sets
//   has_spatial=true; intrinsics are required when spatial is used.
// - `zone` lines accumulate polygons per revision_id (one polygon per line).
// - Rules from a replay file are SHADOW-only: mode is not a scenario input,
//   so ACTIVE never comes from a replay file. `proximity` builds a
//   PERSON_EQUIPMENT_PROXIMITY rule; `zone-rule` builds a ZONE_INTRUSION
//   rule with zone_revision_ids = every revision declared in the file.
// - geometry tokens: PIXEL_ONLY | TIME_UNVERIFIED | GEO_ESTIMATE |
//   METRIC_VERIFIED.
// - Trailing class tokens are the rule's enabled_equipment_classes
//   (empty = every class applicable).
//
// Strict: failures name their line; unknown keywords and malformed numbers
// fail closed. No clock, no I/O beyond reading the file.

#ifndef KSI_SAFETY_REPLAY_POLICY_FILE_HPP
#define KSI_SAFETY_REPLAY_POLICY_FILE_HPP

#include <string>
#include <vector>

#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/spatial/ground_estimator.hpp"
#include "ksi_safety/spatial/relation.hpp"

namespace ksi::safety {

struct ReplayPolicy {
    bool has_spatial = false;
    SpatialEstimatorConfig spatial;
    std::vector<ZoneRevision> zones;
    std::vector<RiskRule> proximity_rules;
    std::vector<RiskRule> zone_rules;
};

// Parses the policy file at `path`. On failure returns false and sets
// `*error` ("line N: ..." for line-level problems); `*out` must not be used
// on failure.
bool LoadReplayPolicy(const std::string& path, ReplayPolicy* out,
                      std::string* error);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_REPLAY_POLICY_FILE_HPP
