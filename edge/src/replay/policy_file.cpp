#include "ksi_safety/replay/policy_file.hpp"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>

namespace ksi::safety {
namespace {

bool Fail(const std::string& label, const std::string& what,
          std::string* error) {
    if (error != nullptr) {
        *error = label + ": " + what;
    }
    return false;
}

bool ParseDouble(std::istringstream* iss, double* out) {
    double value = 0.0;
    if (!(*iss >> value) || !std::isfinite(value)) {
        return false;
    }
    *out = value;
    return true;
}

bool ParseUint32(std::istringstream* iss, std::uint32_t* out) {
    unsigned long long value = 0;
    if (!(*iss >> value) || value > 0xFFFFFFFFULL) {
        return false;
    }
    *out = static_cast<std::uint32_t>(value);
    return true;
}

bool ParseUint64(std::istringstream* iss, std::uint64_t* out) {
    unsigned long long value = 0;
    if (!(*iss >> value)) {
        return false;
    }
    *out = static_cast<std::uint64_t>(value);
    return true;
}

bool ParseGeometry(const std::string& token, GeometryLevel* out) {
    if (token == "PIXEL_ONLY") {
        *out = GeometryLevel::kPixelOnly;
    } else if (token == "TIME_UNVERIFIED") {
        *out = GeometryLevel::kTimeUnverified;
    } else if (token == "GEO_ESTIMATE") {
        *out = GeometryLevel::kGeoEstimate;
    } else if (token == "METRIC_VERIFIED") {
        *out = GeometryLevel::kMetricVerified;
    } else {
        return false;
    }
    return true;
}

}  // namespace

bool LoadReplayPolicy(const std::string& path, ReplayPolicy* out,
                      std::string* error) {
    if (out == nullptr) {
        if (error != nullptr) {
            *error = "null output policy";
        }
        return false;
    }
    *out = ReplayPolicy{};
    std::ifstream in(path);
    if (!in) {
        if (error != nullptr) {
            *error = "cannot open policy: " + path;
        }
        return false;
    }
    std::string line;
    std::int64_t line_number = 0;
    while (std::getline(in, line)) {
        ++line_number;
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        std::istringstream iss(line);
        std::string keyword;
        if (!(iss >> keyword)) {
            continue;  // blank line
        }
        if (keyword[0] == '#') {
            continue;  // comment
        }
        const std::string label = "line " + std::to_string(line_number);
        if (keyword == "spatial") {
            std::string what;
            if (!(iss >> what)) {
                return Fail(label, "missing spatial field name", error);
            }
            if (what == "intrinsics") {
                double fx = 0.0;
                double fy = 0.0;
                double cx = 0.0;
                double cy = 0.0;
                std::uint32_t ref_width = 0;
                std::uint32_t ref_height = 0;
                if (!ParseDouble(&iss, &fx) || !ParseDouble(&iss, &fy) ||
                    !ParseDouble(&iss, &cx) || !ParseDouble(&iss, &cy) ||
                    !ParseUint32(&iss, &ref_width) ||
                    !ParseUint32(&iss, &ref_height)) {
                    return Fail(label, "malformed spatial intrinsics", error);
                }
                if (fx <= 0.0 || fy <= 0.0 || ref_width == 0 ||
                    ref_height == 0) {
                    return Fail(label, "intrinsics must be positive", error);
                }
                out->spatial.intrinsics.fx = fx;
                out->spatial.intrinsics.fy = fy;
                out->spatial.intrinsics.cx = cx;
                out->spatial.intrinsics.cy = cy;
                out->spatial.intrinsics.ref_width = ref_width;
                out->spatial.intrinsics.ref_height = ref_height;
                out->has_spatial = true;
            } else if (what == "ground") {
                double up_m = 0.0;
                if (!ParseDouble(&iss, &up_m)) {
                    return Fail(label, "malformed spatial ground", error);
                }
                out->spatial.ground.up_m = up_m;
                out->has_spatial = true;
            } else if (what == "min-ray-z") {
                double value = 0.0;
                if (!ParseDouble(&iss, &value)) {
                    return Fail(label, "malformed spatial min-ray-z", error);
                }
                out->spatial.min_ray_z = value;
                out->has_spatial = true;
            } else if (what == "person-class") {
                std::uint32_t class_id = 0;
                if (!ParseUint32(&iss, &class_id)) {
                    return Fail(label, "malformed spatial person-class", error);
                }
                out->spatial.classes.person_class_id = class_id;
                out->has_spatial = true;
            } else if (what == "equipment-class") {
                std::uint32_t class_id = 0;
                std::string name;
                if (!ParseUint32(&iss, &class_id) || !(iss >> name)) {
                    return Fail(label, "malformed spatial equipment-class",
                                error);
                }
                out->spatial.classes.equipment_classes.push_back(
                    {class_id, name});
                out->has_spatial = true;
            } else {
                return Fail(label, "unknown spatial field: " + what, error);
            }
            std::string extra;
            if (iss >> extra) {
                return Fail(label, "too many fields on spatial line", error);
            }
        } else if (keyword == "zone") {
            ZoneRevision revision;
            if (!(iss >> revision.revision_id)) {
                return Fail(label, "missing zone revision_id", error);
            }
            std::vector<double> numbers;
            std::string token;
            while (iss >> token) {
                char* end = nullptr;
                const double value = std::strtod(token.c_str(), &end);
                if (end == nullptr || *end != '\0' ||
                    !std::isfinite(value)) {
                    return Fail(label, "malformed zone coordinate: " + token,
                                error);
                }
                numbers.push_back(value);
            }
            if (numbers.size() % 2U != 0U || numbers.size() < 6U) {
                return Fail(label, "zone polygon needs >= 3 x y pairs", error);
            }
            Polygon2 polygon;
            for (std::size_t i = 0; i + 1 < numbers.size(); i += 2U) {
                Vec2 point;
                point.x = numbers[i];
                point.y = numbers[i + 1];
                polygon.push_back(point);
            }
            // Accumulate: multiple lines with the same revision add polygons.
            bool found = false;
            for (std::size_t i = 0; i < out->zones.size(); ++i) {
                if (out->zones[i].revision_id == revision.revision_id) {
                    out->zones[i].polygons.push_back(polygon);
                    found = true;
                    break;
                }
            }
            if (!found) {
                revision.polygons.push_back(polygon);
                out->zones.push_back(revision);
            }
        } else if (keyword == "proximity" || keyword == "zone-rule") {
            RiskRule rule;
            rule.mode = RuleMode::kShadow;  // ACTIVE never comes from replay
            if (!(iss >> rule.rule_id)) {
                return Fail(label, "missing rule_id", error);
            }
            if (keyword == "proximity") {
                rule.kind = RuleKind::kPersonEquipmentProximity;
                if (!ParseDouble(&iss, &rule.threshold_m) ||
                    !ParseDouble(&iss, &rule.hysteresis_m) ||
                    !ParseUint64(&iss, &rule.enter_duration_ms) ||
                    !ParseUint64(&iss, &rule.exit_duration_ms)) {
                    return Fail(label, "malformed proximity rule", error);
                }
            } else {
                rule.kind = RuleKind::kZoneIntrusion;
                if (!ParseUint64(&iss, &rule.enter_duration_ms) ||
                    !ParseUint64(&iss, &rule.exit_duration_ms)) {
                    return Fail(label, "malformed zone-rule", error);
                }
            }
            std::string geometry;
            if (!(iss >> geometry) ||
                !ParseGeometry(geometry, &rule.required_geometry_level)) {
                return Fail(label, "unknown required geometry", error);
            }
            std::string class_name;
            while (iss >> class_name) {
                rule.enabled_equipment_classes.push_back(class_name);
            }
            if (keyword == "proximity") {
                out->proximity_rules.push_back(rule);
            } else {
                out->zone_rules.push_back(rule);
            }
        } else {
            return Fail(label, "unknown keyword: " + keyword, error);
        }
    }
    if (in.bad()) {
        if (error != nullptr) {
            *error = "read error on policy: " + path;
        }
        return false;
    }
    if (out->has_spatial && out->spatial.intrinsics.ref_width == 0) {
        if (error != nullptr) {
            *error = "spatial intrinsics are required when spatial is used";
        }
        return false;
    }
    // Zone rules list every declared revision (scenario convenience).
    for (std::size_t r = 0; r < out->zone_rules.size(); ++r) {
        for (std::size_t z = 0; z < out->zones.size(); ++z) {
            out->zone_rules[r].zone_revision_ids.push_back(
                out->zones[z].revision_id);
        }
    }
    return true;
}

}  // namespace ksi::safety
