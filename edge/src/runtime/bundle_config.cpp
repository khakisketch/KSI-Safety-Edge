#include "ksi_safety/runtime/bundle_config.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

#include "ksi_safety/transport/digest.hpp"

namespace ksi::safety {
namespace {

constexpr const char* kKindProximity = "PERSON_EQUIPMENT_PROXIMITY";
constexpr const char* kKindZone = "ZONE_INTRUSION";
constexpr const char* kModeOff = "OFF";
constexpr const char* kModeShadow = "SHADOW";
constexpr const char* kModeActive = "ACTIVE";

bool IsFiniteNumber(const JsonValue& value) {
    if (value.IsDouble()) return std::isfinite(value.doubleValue());
    return value.IsInt();
}

double AsDouble(const JsonValue& value) { return value.doubleValue(); }

bool ParseRuleKind(const std::string& text, RuleKind* out) {
    if (text == kKindProximity) {
        *out = RuleKind::kPersonEquipmentProximity;
        return true;
    }
    if (text == kKindZone) {
        *out = RuleKind::kZoneIntrusion;
        return true;
    }
    return false;
}

bool ParseRuleMode(const std::string& text, RuleMode* out) {
    if (text == kModeOff) {
        *out = RuleMode::kOff;
        return true;
    }
    if (text == kModeShadow) {
        *out = RuleMode::kShadow;
        return true;
    }
    if (text == kModeActive) {
        *out = RuleMode::kActive;
        return true;
    }
    return false;
}

bool ParseGeometryLevel(const std::string& text, GeometryLevel* out) {
    if (text == "PIXEL_ONLY") {
        *out = GeometryLevel::kPixelOnly;
    } else if (text == "TIME_UNVERIFIED") {
        *out = GeometryLevel::kTimeUnverified;
    } else if (text == "GEO_ESTIMATE") {
        *out = GeometryLevel::kGeoEstimate;
    } else if (text == "METRIC_VERIFIED") {
        *out = GeometryLevel::kMetricVerified;
    } else {
        return false;
    }
    return true;
}

bool HasReason(const std::vector<std::string>& reasons, const char* code) {
    for (const auto& reason : reasons) {
        if (reason == code) return true;
    }
    return false;
}

bool IsHex64Digest(const std::string& text) {
    if (text.size() != 64) return false;
    for (const char ch : text) {
        const bool hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
        if (!hex) return false;
    }
    return true;
}

// Validates (not converts) one GeoJSON Polygon ring set: {"type":"Polygon",
// "coordinates":[[[lon,lat],...],...]} with >= 1 ring of >= 4 positions.
bool CheckGeoJsonPolygon(const JsonValue& geometry, std::string* error) {
    auto fail = [&](const char* what) {
        if (error != nullptr) *error = what;
        return false;
    };
    if (geometry.IsNull()) return fail("geometry_wgs84 must be a Polygon object");
    if (!geometry.IsObject()) return fail("geometry_wgs84 must be an object");
    const JsonValue* type = geometry.Find("type");
    if (type == nullptr || !type->IsString() || type->strValue() != "Polygon") {
        return fail("geometry_wgs84.type must be \"Polygon\"");
    }
    const JsonValue* coords = geometry.Find("coordinates");
    if (coords == nullptr || !coords->IsArray() || coords->Size() == 0) {
        return fail("geometry_wgs84.coordinates must be a non-empty ring array");
    }
    for (const JsonValue& ring : coords->arrayValue()) {
        if (!ring.IsArray() || ring.Size() < 4) {
            return fail("geometry_wgs84 ring needs >= 4 positions");
        }
        for (const JsonValue& position : ring.arrayValue()) {
            if (!position.IsArray() || position.Size() != 2) {
                return fail("geometry_wgs84 position must be [lon, lat]");
            }
            const JsonValue& lon = position.arrayValue()[0];
            const JsonValue& lat = position.arrayValue()[1];
            if (!IsFiniteNumber(lon) || !IsFiniteNumber(lat)) {
                return fail("geometry_wgs84 position must hold numbers");
            }
            if (AsDouble(lon) < -180.0 || AsDouble(lon) > 180.0 ||
                AsDouble(lat) < -90.0 || AsDouble(lat) > 90.0) {
                return fail("geometry_wgs84 position out of WGS84 range");
            }
        }
    }
    return true;
}

bool RuleIdLess(const RiskRule& a, const RiskRule& b) {
    return a.rule_id < b.rule_id;
}

JsonValue RuleToCanonical(const RiskRule& rule) {
    JsonValue out = JsonValue::Obj();
    out.Set("rule_id", JsonValue::Str(rule.rule_id));
    out.Set("kind", JsonValue::Str(ToString(rule.kind)));
    out.Set("mode", JsonValue::Str(ToString(rule.mode)));
    out.Set("threshold_m", JsonValue::Double(rule.threshold_m));
    out.Set("hysteresis_m", JsonValue::Double(rule.hysteresis_m));
    out.Set("enter_duration_ms",
            JsonValue::Int(static_cast<std::int64_t>(rule.enter_duration_ms)));
    out.Set("exit_duration_ms",
            JsonValue::Int(static_cast<std::int64_t>(rule.exit_duration_ms)));
    out.Set("required_geometry_level",
            JsonValue::Str(ToString(rule.required_geometry_level)));
    std::vector<std::string> classes = rule.enabled_equipment_classes;
    std::sort(classes.begin(), classes.end());
    JsonValue::Array class_list;
    for (const auto& name : classes) class_list.push_back(JsonValue::Str(name));
    out.Set("enabled_equipment_classes", JsonValue::Arr(std::move(class_list)));
    std::vector<std::string> revisions = rule.zone_revision_ids;
    std::sort(revisions.begin(), revisions.end());
    JsonValue::Array revision_list;
    for (const auto& id : revisions) revision_list.push_back(JsonValue::Str(id));
    out.Set("zone_revision_ids", JsonValue::Arr(std::move(revision_list)));
    return out;
}

}  // namespace

std::string NormalizeModelDigest(const std::string& digest) {
    constexpr const char* kPrefix = "sha256:";
    constexpr std::size_t kPrefixLen = 7;
    if (digest.compare(0, kPrefixLen, kPrefix) == 0) {
        return digest.substr(kPrefixLen);
    }
    return digest;
}

BundleRuntimeConfig ParseBundleRuntimeConfig(const JsonValue& bundle) {
    BundleRuntimeConfig config;
    auto fail = [&](const std::string& what) {
        config.ok = false;
        config.errors.push_back(what);
        config.proximity_rules.clear();
        config.zone_rules.clear();
        config.zones.clear();
        config.effective_config_hash.clear();
        config.degraded_reasons.clear();
        config.rejected_rule_ids.clear();
        return config;
    };
    if (!bundle.IsObject()) return fail("bundle must be an object");

    const JsonValue* snapshot = bundle.Find("mission_snapshot");
    if (snapshot == nullptr || !snapshot->IsObject()) {
        return fail("mission_snapshot missing");
    }
    // Production mission-bundle schema requires the zones/rules arrays:
    // an absent array is malformed (fail closed), never an empty install.
    // The pinned rc-to-edge framing stub omits them — it stays byte-valid
    // TRANSPORT (digest-verified PREPARE) but is NOT valid runtime input.
    // Explicit [] is the empty install (0 rules, still hashed).
    // Present-but-wrong-typed also fails closed.
    const JsonValue* zones_node = snapshot->Find("zones");
    if (zones_node == nullptr || !zones_node->IsArray()) {
        return fail("mission_snapshot.zones must be an array");
    }
    const JsonValue* mission_revision = snapshot->Find("mission_revision_id");
    const std::string mission_revision_id =
        (mission_revision != nullptr && mission_revision->IsString())
            ? mission_revision->strValue()
            : std::string();

    const JsonValue* policy = bundle.Find("risk_policy");
    if (policy == nullptr || !policy->IsObject()) {
        return fail("risk_policy missing");
    }
    const JsonValue* rules_node = policy->Find("rules");
    if (rules_node == nullptr || !rules_node->IsArray()) {
        return fail("risk_policy.rules must be an array");
    }
    const JsonValue* policy_revision = policy->Find("revision_id");
    const std::string policy_revision_id =
        (policy_revision != nullptr && policy_revision->IsString())
            ? policy_revision->strValue()
            : std::string();

    const JsonValue* bundle_sha = bundle.Find("bundle_sha256");
    const std::string bundle_sha256 =
        (bundle_sha != nullptr && bundle_sha->IsString())
            ? bundle_sha->strValue()
            : std::string();

    // --- zones: validate WGS84, install held (no local polygons) ------------
    std::vector<std::string> seen_revisions;
    const JsonValue::Array& zone_list = zones_node->arrayValue();
    for (const JsonValue& zone : zone_list) {
        if (!zone.IsObject()) return fail("zone must be an object");
        const JsonValue* revision = zone.Find("revision_id");
        if (revision == nullptr || !revision->IsString() ||
            revision->strValue().empty()) {
            return fail("zone.revision_id missing");
        }
        const JsonValue* enabled = zone.Find("enabled");
        if (enabled == nullptr || !enabled->IsBool()) {
            return fail("zone.enabled missing");
        }
        const std::string& revision_id = revision->strValue();
        for (const auto& seen : seen_revisions) {
            if (seen == revision_id) return fail("duplicate zone revision_id");
        }
        seen_revisions.push_back(revision_id);
        if (!enabled->boolValue()) {
            continue;  // Disabled revisions are not installed.
        }
        const JsonValue* geometry = zone.Find("geometry_wgs84");
        std::string geometry_error;
        if (geometry == nullptr ||
            !CheckGeoJsonPolygon(*geometry, &geometry_error)) {
            return fail("zone geometry invalid: " + geometry_error);
        }
        // Held: no site-frame conversion inputs exist, so no local-plane
        // polygon can be derived honestly. The empty revision maps every
        // relation to kUnknownStatus (withheld), never "no intrusion".
        ZoneRevision held;
        held.revision_id = revision_id;
        config.zones.push_back(std::move(held));
    }

    // --- rules ----------------------------------------------------------------
    std::vector<std::string> seen_rule_ids;
    const JsonValue::Array& rule_list = rules_node->arrayValue();
    for (const JsonValue& node : rule_list) {
        if (!node.IsObject()) return fail("rule must be an object");
        const JsonValue* id = node.Find("rule_id");
        if (id == nullptr || !id->IsString() || id->strValue().empty()) {
            return fail("rule.rule_id missing");
        }
        const std::string& rule_id = id->strValue();
        for (const auto& seen : seen_rule_ids) {
            if (seen == rule_id) return fail("duplicate rule_id: " + rule_id);
        }
        seen_rule_ids.push_back(rule_id);
        const JsonValue* kind_node = node.Find("kind");
        RuleKind kind = RuleKind::kPersonEquipmentProximity;
        if (kind_node == nullptr || !kind_node->IsString() ||
            !ParseRuleKind(kind_node->strValue(), &kind)) {
            return fail("rule kind invalid: " + rule_id);
        }
        const JsonValue* mode_node = node.Find("mode");
        RuleMode mode = RuleMode::kShadow;
        if (mode_node == nullptr || !mode_node->IsString() ||
            !ParseRuleMode(mode_node->strValue(), &mode)) {
            return fail("rule mode invalid: " + rule_id);
        }
        RiskRule rule;
        rule.rule_id = rule_id;
        rule.kind = kind;
        rule.mode = mode;
        const JsonValue* threshold = node.Find("threshold_m");
        if (kind == RuleKind::kPersonEquipmentProximity) {
            if (threshold == nullptr || !IsFiniteNumber(*threshold) ||
                AsDouble(*threshold) <= 0.0) {
                return fail("proximity rule needs threshold_m > 0: " + rule_id);
            }
            rule.threshold_m = AsDouble(*threshold);
        } else {
            if (threshold != nullptr && !threshold->IsNull()) {
                return fail("zone rule threshold_m must be null: " + rule_id);
            }
            rule.threshold_m = 0.0;
        }
        const JsonValue* hysteresis = node.Find("hysteresis_m");
        if (hysteresis == nullptr || !IsFiniteNumber(*hysteresis) ||
            AsDouble(*hysteresis) < 0.0) {
            return fail("rule hysteresis_m invalid: " + rule_id);
        }
        rule.hysteresis_m = AsDouble(*hysteresis);
        const JsonValue* enter = node.Find("enter_duration_ms");
        const JsonValue* exit = node.Find("exit_duration_ms");
        if (enter == nullptr || !enter->IsInt() || enter->intValue() < 0) {
            return fail("rule enter_duration_ms invalid: " + rule_id);
        }
        if (exit == nullptr || !exit->IsInt() || exit->intValue() < 0) {
            return fail("rule exit_duration_ms invalid: " + rule_id);
        }
        rule.enter_duration_ms = static_cast<std::uint64_t>(enter->intValue());
        rule.exit_duration_ms = static_cast<std::uint64_t>(exit->intValue());
        const JsonValue* geometry = node.Find("required_geometry_level");
        if (geometry == nullptr || !geometry->IsString() ||
            !ParseGeometryLevel(geometry->strValue(),
                                &rule.required_geometry_level)) {
            return fail("rule required_geometry_level invalid: " + rule_id);
        }
        const JsonValue* classes = node.Find("enabled_equipment_classes");
        if (classes == nullptr || !classes->IsArray()) {
            return fail("rule enabled_equipment_classes missing: " + rule_id);
        }
        for (const JsonValue& entry : classes->arrayValue()) {
            if (!entry.IsString()) {
                return fail("rule enabled_equipment_classes holds strings: " +
                            rule_id);
            }
            rule.enabled_equipment_classes.push_back(entry.strValue());
        }
        const JsonValue* revisions = node.Find("zone_revision_ids");
        if (revisions == nullptr || !revisions->IsArray()) {
            return fail("rule zone_revision_ids missing: " + rule_id);
        }
        for (const JsonValue& entry : revisions->arrayValue()) {
            if (!entry.IsString()) {
                return fail("rule zone_revision_ids holds strings: " + rule_id);
            }
            rule.zone_revision_ids.push_back(entry.strValue());
        }

        if (mode == RuleMode::kActive) {
            // Contract §4.5: ACTIVE is never installed. The schema already
            // requires METRIC_VERIFIED for ACTIVE; enforce it here too so a
            // non-conforming bundle fails closed instead of degrading.
            if (rule.required_geometry_level !=
                GeometryLevel::kMetricVerified) {
                return fail("ACTIVE rule needs METRIC_VERIFIED: " + rule_id);
            }
            config.rejected_rule_ids.push_back(rule_id);
            if (!HasReason(config.degraded_reasons,
                           kDegradedPolicyInstallRejected)) {
                config.degraded_reasons.emplace_back(
                    kDegradedPolicyInstallRejected);
            }
            continue;  // Rejected: never installed, never activated.
        }
        if (kind == RuleKind::kPersonEquipmentProximity) {
            config.proximity_rules.push_back(rule);
        } else {
            config.zone_rules.push_back(std::move(rule));
        }
    }

    // --- model profiles: validated shape, compared at install time ---------
    // Absent key = no constraint (legacy bundles install as today).
    // Present = every entry must be well-formed; the enabled subset must
    // match the actually loaded engine before install (no asserted model
    // without the bytes — checked in RunControl against the runner profile).
    {
        const JsonValue* models_node = snapshot->Find("model_profiles");
        if (models_node != nullptr) {
            if (!models_node->IsArray()) {
                return fail("mission_snapshot.model_profiles must be an array");
            }
            for (const JsonValue& node : models_node->arrayValue()) {
                if (!node.IsObject()) {
                    return fail("model profile must be an object");
                }
                const JsonValue* id = node.Find("model_id");
                if (id == nullptr || !id->IsString() ||
                    id->strValue().empty()) {
                    return fail("model profile model_id missing");
                }
                const JsonValue* digest = node.Find("model_digest");
                if (digest == nullptr || !digest->IsString() ||
                    !IsHex64Digest(
                        NormalizeModelDigest(digest->strValue()))) {
                    return fail("model profile model_digest invalid: " +
                                id->strValue());
                }
                const JsonValue* enabled = node.Find("enabled");
                if (enabled == nullptr || !enabled->IsBool()) {
                    return fail("model profile enabled missing: " +
                                id->strValue());
                }
                BundleModelProfile profile;
                profile.model_id = id->strValue();
                profile.model_digest = digest->strValue();
                profile.enabled = enabled->boolValue();
                config.model_profiles.push_back(std::move(profile));
            }
        }
    }

    // --- zone hold: a live zone rule with no convertible local geometry -----
    bool live_zone_rule = false;
    for (const RiskRule& rule : config.zone_rules) {
        if (rule.mode != RuleMode::kOff) {
            live_zone_rule = true;
            break;
        }
    }
    bool convertible_zone = false;
    for (const ZoneRevision& zone : config.zones) {
        if (!zone.polygons.empty()) {
            convertible_zone = true;
            break;
        }
    }
    if (live_zone_rule && !convertible_zone &&
        !HasReason(config.degraded_reasons,
                   kDegradedZoneGeometryUnavailable)) {
        config.degraded_reasons.emplace_back(kDegradedZoneGeometryUnavailable);
    }

    // --- effective config hash (deterministic, sorted, no clock) ------------
    std::sort(config.proximity_rules.begin(), config.proximity_rules.end(),
              RuleIdLess);
    std::sort(config.zone_rules.begin(), config.zone_rules.end(), RuleIdLess);
    std::sort(config.zones.begin(), config.zones.end(),
              [](const ZoneRevision& a, const ZoneRevision& b) {
                  return a.revision_id < b.revision_id;
              });
    std::sort(config.rejected_rule_ids.begin(), config.rejected_rule_ids.end());
    JsonValue canonical = JsonValue::Obj();
    canonical.Set("bundle_sha256", JsonValue::Str(bundle_sha256));
    canonical.Set("mission_revision_id", JsonValue::Str(mission_revision_id));
    canonical.Set("policy_revision_id", JsonValue::Str(policy_revision_id));
    JsonValue::Array proximity_list;
    for (const RiskRule& rule : config.proximity_rules) {
        proximity_list.push_back(RuleToCanonical(rule));
    }
    canonical.Set("proximity_rules", JsonValue::Arr(std::move(proximity_list)));
    JsonValue::Array zone_rule_list;
    for (const RiskRule& rule : config.zone_rules) {
        zone_rule_list.push_back(RuleToCanonical(rule));
    }
    canonical.Set("zone_rules", JsonValue::Arr(std::move(zone_rule_list)));
    JsonValue::Array zone_entries;
    for (const ZoneRevision& zone : config.zones) {
        JsonValue entry = JsonValue::Obj();
        entry.Set("revision_id", JsonValue::Str(zone.revision_id));
        entry.Set("local_polygons", JsonValue::Int(0));  // always held
        zone_entries.push_back(std::move(entry));
    }
    canonical.Set("zones", JsonValue::Arr(std::move(zone_entries)));
    JsonValue::Array rejected_list;
    for (const auto& rule_id : config.rejected_rule_ids) {
        rejected_list.push_back(JsonValue::Str(rule_id));
    }
    canonical.Set("rejected_rule_ids",
                  JsonValue::Arr(std::move(rejected_list)));
    config.effective_config_hash = Sha256Hex(WriteCanonicalJson(canonical));

    config.ok = true;
    return config;
}

}  // namespace ksi::safety
