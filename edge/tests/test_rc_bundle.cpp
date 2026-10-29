// RC-produced bundle -> C++ parser (Unit A follow-up).
//
// The bundle that actually reaches the Manifold is produced by the RC
// (Kotlin). This test feeds the REAL pinned RC bytes —
// contracts/mop/rc-to-edge.mopv1.hex — through the production frame reader
// (MopReassembler, no second framing implementation) and envelope verifier,
// extracts the MISSION_PREPARE bundle, and runs ParseBundleRuntimeConfig on
// it. The RC side is the contract source: the parser accepts what the RC
// sends.
//
// Authority split (do NOT weaken the schema for the stub): the pinned
// fixture (MopCrossLanguageTest.makeBundle, a framing stub) carries
// mission_snapshot WITHOUT zones and risk_policy WITHOUT rules. That shape
// stays byte-valid TRANSPORT — digest-verified PREPARE — but is NOT valid
// runtime input: the production schema requires both arrays, so the strict
// parser rejects it. Explicit [] is the empty install. The full RC shapes —
// the demo feed (zones:[] / rules:[]) and MissionFixture (GeoJSON zones +
// SHADOW rules) — are mirrored byte-faithfully below and must parse with
// rules held SHADOW, revision ids present, zones held with zero local
// polygons, and a produced effective_config_hash.

#include "ksi_safety/runtime/bundle_config.hpp"
#include "ksi_safety/spatial/relation.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_frame.hpp"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "support/check.hpp"

using ksi::safety::BundleRuntimeConfig;
using ksi::safety::DigestExcluding;
using ksi::safety::JsonValue;
using ksi::safety::MopReassembler;
using ksi::safety::ParseBundleRuntimeConfig;
using ksi::safety::ParseJson;
using ksi::safety::VerifyDigest;
using ksi::safety::VerifyMopEnvelope;
using ksi::safety::WriteCanonicalJson;

namespace {

bool IsHex64(const std::string& text) {
    if (text.size() != 64) return false;
    for (const char ch : text) {
        const bool hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
        if (!hex) return false;
    }
    return true;
}

bool Unhex(const std::string& hex, std::string* bytes) {
    if (hex.size() % 2 != 0) return false;
    auto nibble = [](char ch) -> int {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        return -1;
    };
    std::string out;
    for (std::size_t i = 0; i < hex.size(); i += 2) {
        const int hi = nibble(hex[i]);
        const int lo = nibble(hex[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.push_back(static_cast<char>((hi << 4) | lo));
    }
    *bytes = std::move(out);
    return true;
}

bool HasCode(const std::vector<std::string>& reasons, const std::string& code) {
    for (const auto& reason : reasons) {
        if (reason == code) return true;
    }
    return false;
}

// MissionFixture.zoneRevision-shaped GeoJSON (closed 4-position ring).
JsonValue FixtureGeoPolygon(double lon) {
    const double ring[][2] = {
        {lon, 37.0}, {lon + 0.0008, 37.0}, {lon + 0.0008, 37.0007}, {lon, 37.0},
    };
    JsonValue::Array positions;
    for (const auto& point : ring) {
        JsonValue::Array pair;
        pair.push_back(JsonValue::Double(point[0]));
        pair.push_back(JsonValue::Double(point[1]));
        positions.push_back(JsonValue::Arr(std::move(pair)));
    }
    JsonValue::Array rings;
    rings.push_back(JsonValue::Arr(std::move(positions)));
    JsonValue geo = JsonValue::Obj();
    geo.Set("type", JsonValue::Str("Polygon"));
    geo.Set("coordinates", JsonValue::Arr(std::move(rings)));
    return geo;
}

}  // namespace

int main() {
#ifndef KSI_MOP_CONTRACTS_DIR
    CHECK(false, "KSI_MOP_CONTRACTS_DIR not defined (see CMakeLists)");
    return 1;
#endif
    // ---- 1) the real RC bytes: unhex + production frame reader ------------
    const std::string path =
        std::string(KSI_MOP_CONTRACTS_DIR) + "/rc-to-edge.mopv1.hex";
    std::ifstream stream(path);
    CHECK(stream.good(), "rc-to-edge fixture readable");
    std::string hex_line;
    std::getline(stream, hex_line);
    std::string bytes;
    CHECK(Unhex(hex_line, &bytes), "fixture unhexes");
    MopReassembler reassembler;
    std::vector<std::string> messages;
    reassembler.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                     bytes.size(), &messages);
    CHECK(reassembler.errors() == 0, "RC stream reassembles cleanly");
    std::vector<JsonValue> prepares;
    for (const auto& message : messages) {
        JsonValue envelope;
        std::string error;
        CHECK(ParseJson(message, &envelope, &error), "RC message parses");
        JsonValue payload;
        CHECK(VerifyMopEnvelope(envelope, &payload, &error),
              "RC envelope digest-verifies (authentic RC bytes)");
        const JsonValue* type = envelope.Find("type");
        CHECK(type != nullptr && type->IsString(), "RC envelope has a type");
        if (type->strValue() == "MISSION_PREPARE") {
            prepares.push_back(std::move(payload));
        }
    }
    CHECK(prepares.size() == 1, "exactly one MISSION_PREPARE on the wire");
    const JsonValue* bundle = prepares[0].Find("bundle");
    CHECK(bundle != nullptr && bundle->IsObject(),
          "PREPARE payload carries the bundle object");

    // ---- 2) the framing-stub bundle: valid transport, NOT runtime input --
    CHECK(VerifyDigest(*bundle, "bundle_sha256"),
          "RC bundle digest verifies (untampered)");
    // The stub stays byte-valid on the wire (it reassembled cleanly above
    // and its digest verifies), but the production schema requires the
    // zones/rules arrays, so the strict runtime parser rejects it — absent
    // arrays are malformed, never an empty install.
    const BundleRuntimeConfig stub = ParseBundleRuntimeConfig(*bundle);
    CHECK(!stub.ok, "array-less stub is NOT valid runtime input");
    CHECK(!stub.errors.empty(), "rejection names the missing arrays");
    CHECK(stub.effective_config_hash.empty() &&
              stub.proximity_rules.empty() && stub.zone_rules.empty() &&
              stub.zones.empty(),
          "rejected stub installs nothing and hashes nothing");

    // ---- 3) the RC demo-feed shape (present-but-empty arrays) --------------
    {
        JsonValue snapshot = JsonValue::Obj();
        snapshot.Set("schema_version", JsonValue::Int(1));
        snapshot.Set("mission_revision_id", JsonValue::Str("mis-demo"));
        snapshot.Set("site_id", JsonValue::Str("site-demo"));
        snapshot.Set("zones", JsonValue::Arr());
        snapshot.Set("content_sha256", JsonValue::Str(std::string(64, 'd')));
        JsonValue policy = JsonValue::Obj();
        policy.Set("schema_version", JsonValue::Int(1));
        policy.Set("revision_id", JsonValue::Str("pol-demo"));
        policy.Set("site_id", JsonValue::Str("site-demo"));
        policy.Set("rules", JsonValue::Arr());
        policy.Set("content_sha256", JsonValue::Str(std::string(64, 'e')));
        JsonValue demo = JsonValue::Obj();
        demo.Set("schema_version", JsonValue::Int(1));
        demo.Set("bundle_id", JsonValue::Str("bundle-demo"));
        demo.Set("site_id", JsonValue::Str("site-demo"));
        demo.Set("mission_snapshot", std::move(snapshot));
        demo.Set("risk_policy", std::move(policy));
        demo.Set("bundle_sha256",
                 JsonValue::Str(DigestExcluding(demo, "bundle_sha256")));
        const BundleRuntimeConfig parsed = ParseBundleRuntimeConfig(demo);
        CHECK(parsed.ok, "demo-feed shape (empty arrays) parses");
        CHECK(parsed.proximity_rules.empty() && parsed.zone_rules.empty() &&
                  parsed.zones.empty(),
              "demo-feed shape installs as empty");
        CHECK(IsHex64(parsed.effective_config_hash), "demo install hashed");
    }

    // ---- 4) the MissionFixture shape: SHADOW rules + GeoJSON zones ---------
    // Mirrors android-rc MissionFixture.policy/snapshot exactly (field names
    // and value kinds): one PERSON_EQUIPMENT_PROXIMITY SHADOW rule scoped to
    // the excavator class and referencing the zone revision, one ZONE_INTRUSION
    // SHADOW rule, one enabled zone with a closed WGS84 ring.
    {
        JsonValue zone = JsonValue::Obj();
        zone.Set("zone_id", JsonValue::Str("zone-1"));
        zone.Set("revision_id", JsonValue::Str("zone-rev-1"));
        zone.Set("rule_kind", JsonValue::Str("PERSON_ENTRY"));
        zone.Set("geometry_wgs84", FixtureGeoPolygon(127.0));
        zone.Set("zone_sha256", JsonValue::Str(std::string(64, 'f')));
        zone.Set("enabled", JsonValue::Bool(true));
        JsonValue::Array zones;
        zones.push_back(std::move(zone));
        JsonValue snapshot = JsonValue::Obj();
        snapshot.Set("schema_version", JsonValue::Int(1));
        snapshot.Set("mission_id", JsonValue::Str("mission-1"));
        snapshot.Set("mission_revision_id", JsonValue::Str("mis-rev-1"));
        snapshot.Set("site_id", JsonValue::Str("site-1"));
        snapshot.Set("zones", JsonValue::Arr(std::move(zones)));

        JsonValue prox = JsonValue::Obj();
        prox.Set("rule_id", JsonValue::Str("excavator-proximity-shadow"));
        prox.Set("kind", JsonValue::Str("PERSON_EQUIPMENT_PROXIMITY"));
        prox.Set("mode", JsonValue::Str("SHADOW"));
        prox.Set("threshold_m", JsonValue::Double(4.0));
        prox.Set("hysteresis_m", JsonValue::Double(1.0));
        prox.Set("enter_duration_ms", JsonValue::Int(1200));
        prox.Set("exit_duration_ms", JsonValue::Int(1500));
        prox.Set("required_geometry_level",
                 JsonValue::Str("METRIC_VERIFIED"));
        JsonValue::Array classes;
        classes.push_back(JsonValue::Str("excavator"));
        prox.Set("enabled_equipment_classes", JsonValue::Arr(std::move(classes)));
        JsonValue::Array prox_revisions;
        prox_revisions.push_back(JsonValue::Str("zone-rev-1"));
        prox.Set("zone_revision_ids",
                 JsonValue::Arr(std::move(prox_revisions)));
        JsonValue zone_rule = JsonValue::Obj();
        zone_rule.Set("rule_id", JsonValue::Str("zone-intrusion-shadow"));
        zone_rule.Set("kind", JsonValue::Str("ZONE_INTRUSION"));
        zone_rule.Set("mode", JsonValue::Str("SHADOW"));
        zone_rule.Set("threshold_m", JsonValue::Null());
        zone_rule.Set("hysteresis_m", JsonValue::Double(0.0));
        zone_rule.Set("enter_duration_ms", JsonValue::Int(3000));
        zone_rule.Set("exit_duration_ms", JsonValue::Int(1000));
        zone_rule.Set("required_geometry_level", JsonValue::Str("PIXEL_ONLY"));
        zone_rule.Set("enabled_equipment_classes", JsonValue::Arr());
        JsonValue::Array zone_revisions;
        zone_revisions.push_back(JsonValue::Str("zone-rev-1"));
        zone_rule.Set("zone_revision_ids",
                      JsonValue::Arr(std::move(zone_revisions)));
        JsonValue::Array rules;
        rules.push_back(std::move(prox));
        rules.push_back(std::move(zone_rule));
        JsonValue policy = JsonValue::Obj();
        policy.Set("schema_version", JsonValue::Int(1));
        policy.Set("revision_id", JsonValue::Str("pol-rev-1"));
        policy.Set("site_id", JsonValue::Str("site-1"));
        policy.Set("rules", JsonValue::Arr(std::move(rules)));

        JsonValue full = JsonValue::Obj();
        full.Set("schema_version", JsonValue::Int(1));
        full.Set("bundle_id", JsonValue::Str("bundle-1"));
        full.Set("site_id", JsonValue::Str("site-1"));
        full.Set("mission_snapshot", std::move(snapshot));
        full.Set("risk_policy", std::move(policy));
        full.Set("bundle_sha256",
                 JsonValue::Str(DigestExcluding(full, "bundle_sha256")));

        const BundleRuntimeConfig parsed = ParseBundleRuntimeConfig(full);
        CHECK(parsed.ok, "MissionFixture-shaped bundle parses");
        CHECK(parsed.proximity_rules.size() == 1 &&
                  parsed.proximity_rules[0].rule_id ==
                      "excavator-proximity-shadow",
              "proximity rule parsed");
        CHECK(parsed.proximity_rules[0].mode ==
                  ksi::safety::RuleMode::kShadow,
              "SHADOW mode preserved (never promoted)");
        CHECK(parsed.proximity_rules[0].threshold_m == 4.0,
              "threshold carried");
        CHECK(parsed.proximity_rules[0].enabled_equipment_classes.size() == 1 &&
                  parsed.proximity_rules[0].enabled_equipment_classes[0] ==
                      "excavator",
              "equipment class list carried");
        CHECK(parsed.proximity_rules[0].zone_revision_ids.size() == 1 &&
                  parsed.proximity_rules[0].zone_revision_ids[0] ==
                      "zone-rev-1",
              "policy zone revision ids present");
        CHECK(parsed.zone_rules.size() == 1 &&
                  parsed.zone_rules[0].rule_id == "zone-intrusion-shadow",
              "zone rule parsed");
        CHECK(parsed.zones.size() == 1 &&
                  parsed.zones[0].revision_id == "zone-rev-1",
              "zone revision installed");
        CHECK(parsed.zones[0].polygons.empty(),
              "NO fabricated local polygons: WGS84 stays held");
        ksi::safety::PersonGround person;
        person.status = ksi::safety::GroundStatus::kObserved;
        person.footpoint.x = 127.0004;
        person.footpoint.y = 37.0003;  // inside the WGS84 ring — still unknown
        const ksi::safety::ZoneRelationResult relation =
            ksi::safety::RelatePointToZone(person, parsed.zones[0], 1.0);
        CHECK(relation.relation == ksi::safety::ZoneRelationKind::kUnknownStatus,
              "held zone withholds the verdict even inside the WGS84 ring");
        CHECK(parsed.rejected_rule_ids.empty(), "nothing ACTIVE to reject");
        CHECK(HasCode(parsed.degraded_reasons, "zone_geometry_unavailable"),
              "zone hold reported");
        CHECK(!HasCode(parsed.degraded_reasons, "policy_install_rejected"),
              "no spurious ACTIVE rejection");
        CHECK(IsHex64(parsed.effective_config_hash),
              "effective_config_hash produced");
        const BundleRuntimeConfig again = ParseBundleRuntimeConfig(full);
        CHECK(again.ok &&
                  again.effective_config_hash == parsed.effective_config_hash,
              "config hash deterministic");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
