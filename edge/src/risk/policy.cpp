#include "ksi_safety/risk/policy.hpp"

namespace ksi::safety {

const char* ToString(RuleKind kind) {
    switch (kind) {
        case RuleKind::kPersonEquipmentProximity:
            return "PERSON_EQUIPMENT_PROXIMITY";
        case RuleKind::kZoneIntrusion:
            return "ZONE_INTRUSION";
    }
    return "UNKNOWN";
}

const char* ToString(RuleMode mode) {
    switch (mode) {
        case RuleMode::kOff:
            return "OFF";
        case RuleMode::kShadow:
            return "SHADOW";
        case RuleMode::kActive:
            return "ACTIVE";
    }
    return "UNKNOWN";
}

const char* ToString(GeometryLevel level) {
    switch (level) {
        case GeometryLevel::kPixelOnly:
            return "PIXEL_ONLY";
        case GeometryLevel::kTimeUnverified:
            return "TIME_UNVERIFIED";
        case GeometryLevel::kGeoEstimate:
            return "GEO_ESTIMATE";
        case GeometryLevel::kMetricVerified:
            return "METRIC_VERIFIED";
    }
    return "UNKNOWN";
}

}  // namespace ksi::safety
