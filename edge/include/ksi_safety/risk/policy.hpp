// Risk policy input: mirrors contracts/schemas/risk-policy.v1.schema.json
// rule fields (kind/mode/threshold/hysteresis/enter/exit durations/
// required_geometry_level/enabled_equipment_classes/zone_revision_ids).
//
// Reference: KSI_Safety_Edge_v1.1_Spatial_Design/docs/17_RISK_POLICY_AND_EPISODES.md
// §1 (layer split) and §2 (policy fields). Thresholds and durations live only
// in policy input here; this module hardcodes none.

#ifndef KSI_SAFETY_RISK_POLICY_HPP
#define KSI_SAFETY_RISK_POLICY_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace ksi::safety {

// Contract strings (risk-policy.v1): "PERSON_EQUIPMENT_PROXIMITY",
// "ZONE_INTRUSION".
enum class RuleKind {
    kPersonEquipmentProximity,
    kZoneIntrusion,
};

// Contract strings: "OFF", "SHADOW", "ACTIVE".
enum class RuleMode {
    kOff,
    kShadow,
    kActive,
};

// Ordered least -> most verified. Contract strings: "PIXEL_ONLY",
// "TIME_UNVERIFIED", "GEO_ESTIMATE", "METRIC_VERIFIED". Numeric order is the
// geometry-gate order: a frame evaluates only when its level is >= the rule's
// required level.
enum class GeometryLevel {
    kPixelOnly = 0,
    kTimeUnverified = 1,
    kGeoEstimate = 2,
    kMetricVerified = 3,
};

const char* ToString(RuleKind kind);
const char* ToString(RuleMode mode);
const char* ToString(GeometryLevel level);

struct RiskRule {
    std::string rule_id;
    RuleKind kind = RuleKind::kPersonEquipmentProximity;
    RuleMode mode = RuleMode::kShadow;
    // Proximity only; ignored for zone rules.
    double threshold_m = 0.0;
    // Proximity only; a negative value is normalised to 0 on AddRule.
    double hysteresis_m = 0.0;
    std::uint64_t enter_duration_ms = 0;
    std::uint64_t exit_duration_ms = 0;
    GeometryLevel required_geometry_level = GeometryLevel::kMetricVerified;
    // Empty = every equipment class is applicable.
    std::vector<std::string> enabled_equipment_classes;
    // Zone rules only; empty = every zone revision is applicable.
    std::vector<std::string> zone_revision_ids;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RISK_POLICY_HPP
