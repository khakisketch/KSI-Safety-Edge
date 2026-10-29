// Unit A (contract G9): bundle -> engine install, real start/stop control,
// runtime facts on MISSION_STATUS.
//
// Covers: bundle-config parsing (valid, ACTIVE rejected, malformed fails
// closed, WGS84 zones install held and report zone_geometry_unavailable,
// never "no intrusion"), the runner state machine
// (REQUEST_ACCEPTED->CONFIG_APPLIED->STARTING->RUNNING->DEGRADED->STOPPED),
// the STOPPED gate (processed_frame_count frozen, no tracker/session step),
// MISSION_STATUS optional fields with wire types, and degraded reasons on
// degraded input. MOP-link loss never stops local observation (the runner
// has no link dependency — asserted structurally).

#include "ksi_safety/runtime/bundle_config.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <string>
#include <thread>
#include <vector>

#include "support/check.hpp"

using ksi::safety::AcceptedCommand;

using ksi::safety::BundleRuntimeConfig;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::DigestExcluding;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::GeometryLevel;
using ksi::safety::JsonValue;
using ksi::safety::ModelProfile;
using ksi::safety::MopByteTransport;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;
using ksi::safety::MopProtocol;
using ksi::safety::MopRuntimeFacts;
using ksi::safety::MopSessionCommands;
using ksi::safety::ParseBundleRuntimeConfig;
using ksi::safety::ParseJson;
using ksi::safety::PoseQuality;
using ksi::safety::PoseReason;
using ksi::safety::Quaternion;
using ksi::safety::RiskRule;
using ksi::safety::RunnerConfig;
using ksi::safety::RuntimeSnapshot;
using ksi::safety::RuntimeState;
using ksi::safety::Sample;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::TickRecord;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::ToString;
using ksi::safety::Uuid5;
using ksi::safety::Vec3;
using ksi::safety::WriteCanonicalJson;
using ksi::safety::ZoneRevision;

namespace {

constexpr std::int64_t kMs = 1000000LL;

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;  // +1 ms per emitted envelope
        return value;
    }
};

struct FakeTransport : MopByteTransport {
    std::string written;
    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        written.append(reinterpret_cast<const char*>(data), len);
        return true;
    }
};

class OkDetector : public DetectorPort {
  public:
    int calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        ++calls;
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        return batch;
    }
};

class FailDetector : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket&) override {
        DetectionBatch batch;
        batch.status = DetectionStatus::kInferenceFailure;
        return batch;
    }
};

RunnerConfig RunnerCfg(std::uint64_t detect_every = 1) {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = detect_every;
    return config;
}

SessionConfig SessionCfg() {
    SessionConfig config;
    config.tracker.iou_threshold = 0.3F;
    config.tracker.max_age_frames = 5;
    config.tracker.min_hits = 1;
    config.max_gap_ms = 5000;
    config.zone_envelope_m = 1.0;
    return config;
}

ModelProfile Profile() {
    ModelProfile profile;
    profile.model_id = "test-model";
    profile.model_digest = "sha256:test";
    profile.backend = "fake";
    return profile;
}

FramePacket MakeFrame(std::uint32_t frame_id, std::int64_t recv_ns) {
    FramePacket packet;
    packet.metadata.width = 8;
    packet.metadata.height = 6;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = recv_ns;
    packet.rgb.assign(8U * 6U * 3U, 0);
    return packet;
}

FrameContext MakeCtx(const FramePacket& packet) {
    FrameContext ctx;
    ctx.flight_id = "F";
    ctx.boot_id = "B";
    ctx.stream_id = "S";
    ctx.frame_id = packet.metadata.frame_id;
    ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
    ctx.has_exposure = false;
    return ctx;
}

Timestamp VerifiedTs(std::int64_t ns) {
    Timestamp ts;
    ts.value_ns = ns;
    ts.kind = TimeSourceKind::kDeviceSensor;
    ts.domain = "cam0";
    ts.verified = true;
    return ts;
}

// --- bundle fixtures (built as JsonValue, then round-tripped through the
// real JSON parser so the wire path is exercised) --------------------------

JsonValue GeoPolygon() {
    // A well-formed WGS84 GeoJSON Polygon ring (lon/lat, closed).
    const double ring[][2] = {{127.0, 37.5},
                              {127.001, 37.5},
                              {127.001, 37.501},
                              {127.0, 37.501},
                              {127.0, 37.5}};
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

JsonValue MakeZone(const std::string& revision, bool enabled) {
    JsonValue zone = JsonValue::Obj();
    zone.Set("zone_id", JsonValue::Str("zone-" + revision));
    zone.Set("revision_id", JsonValue::Str(revision));
    zone.Set("rule_kind", JsonValue::Str("PERSON_ENTRY"));
    zone.Set("geometry_wgs84", GeoPolygon());
    zone.Set("zone_sha256", JsonValue::Str(std::string(64, 'b')));
    zone.Set("enabled", JsonValue::Bool(enabled));
    return zone;
}

JsonValue MakeRule(const std::string& rule_id, const std::string& kind,
                   const std::string& mode, const JsonValue& threshold,
                   const std::string& geometry) {
    JsonValue rule = JsonValue::Obj();
    rule.Set("rule_id", JsonValue::Str(rule_id));
    rule.Set("kind", JsonValue::Str(kind));
    rule.Set("mode", JsonValue::Str(mode));
    rule.Set("threshold_m", threshold);
    rule.Set("hysteresis_m", JsonValue::Double(1.0));
    rule.Set("enter_duration_ms", JsonValue::Int(2000));
    rule.Set("exit_duration_ms", JsonValue::Int(1000));
    rule.Set("required_geometry_level", JsonValue::Str(geometry));
    rule.Set("enabled_equipment_classes", JsonValue::Arr());
    rule.Set("zone_revision_ids", JsonValue::Arr());
    return rule;
}

JsonValue MakeValidBundle() {
    JsonValue::Array zones;
    zones.push_back(MakeZone("zone-rev-1", true));
    zones.push_back(MakeZone("zone-rev-2", false));  // disabled: not installed
    JsonValue snapshot = JsonValue::Obj();
    snapshot.Set("mission_revision_id", JsonValue::Str("mis-rev-1"));
    snapshot.Set("zones", JsonValue::Arr(std::move(zones)));
    JsonValue::Array rules;
    JsonValue prox = MakeRule("prox-1", "PERSON_EQUIPMENT_PROXIMITY", "SHADOW",
                              JsonValue::Double(5.0), "GEO_ESTIMATE");
    // Proximity rule scoped to one equipment class (class-list semantics).
    JsonValue::Array classes;
    classes.push_back(JsonValue::Str("excavator"));
    prox.Set("enabled_equipment_classes", JsonValue::Arr(std::move(classes)));
    rules.push_back(std::move(prox));
    JsonValue zone_rule = MakeRule("zone-1", "ZONE_INTRUSION", "SHADOW",
                                   JsonValue::Null(), "PIXEL_ONLY");
    JsonValue::Array revisions;
    revisions.push_back(JsonValue::Str("zone-rev-1"));
    zone_rule.Set("zone_revision_ids", JsonValue::Arr(std::move(revisions)));
    rules.push_back(std::move(zone_rule));
    // ACTIVE must be rejected, never installed (§4.5).
    rules.push_back(MakeRule("act-1", "PERSON_EQUIPMENT_PROXIMITY", "ACTIVE",
                             JsonValue::Double(3.0), "METRIC_VERIFIED"));
    JsonValue policy = JsonValue::Obj();
    policy.Set("revision_id", JsonValue::Str("pol-rev-1"));
    policy.Set("rules", JsonValue::Arr(std::move(rules)));
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("mission_snapshot", std::move(snapshot));
    bundle.Set("risk_policy", std::move(policy));
    bundle.Set("bundle_sha256",
               JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
    return bundle;
}

// Bundle -> canonical JSON text -> the real strict parser.
bool ParseBundleText(const JsonValue& bundle, JsonValue* out) {
    std::string error;
    return ParseJson(WriteCanonicalJson(bundle), out, &error);
}

// JsonValue arrays expose no mutable element access, so mutations go
// through copy-mutate-set (the parser path stays untouched).
JsonValue WithRuleField(JsonValue bundle, std::size_t index,
                        const std::string& key, JsonValue value) {
    JsonValue* policy = bundle.FindMutable("risk_policy");
    JsonValue::Array rules = policy->Find("rules")->arrayValue();
    rules[index].Set(key, std::move(value));
    policy->Set("rules", JsonValue::Arr(std::move(rules)));
    return bundle;
}

JsonValue WithZoneField(JsonValue bundle, std::size_t index,
                         const std::string& key, JsonValue value) {
    JsonValue* snapshot = bundle.FindMutable("mission_snapshot");
    JsonValue::Array zones = snapshot->Find("zones")->arrayValue();
    zones[index].Set(key, std::move(value));
    snapshot->Set("zones", JsonValue::Arr(std::move(zones)));
    return bundle;
}

JsonValue WithoutRuleKey(JsonValue bundle, const std::string& key) {
    JsonValue* policy = bundle.FindMutable("risk_policy");
    policy->Erase(key);
    return bundle;
}

JsonValue WithoutSnapshotKey(JsonValue bundle, const std::string& key) {
    JsonValue* snapshot = bundle.FindMutable("mission_snapshot");
    snapshot->Erase(key);
    return bundle;
}

JsonValue WithoutZoneKey(JsonValue bundle, std::size_t index,
                         const std::string& key) {
    JsonValue* snapshot = bundle.FindMutable("mission_snapshot");
    JsonValue::Array zones = snapshot->Find("zones")->arrayValue();
    zones[index].Erase(key);
    snapshot->Set("zones", JsonValue::Arr(std::move(zones)));
    return bundle;
}

bool HasCode(const std::vector<std::string>& reasons, const std::string& code) {
    for (const auto& reason : reasons) {
        if (reason == code) return true;
    }
    return false;
}

bool IsHex64(const std::string& text) {
    if (text.size() != 64) return false;
    for (const char ch : text) {
        const bool hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
        if (!hex) return false;
    }
    return true;
}

// Feed one envelope through a real link; returns the queued MISSION_STATUS
// payload (or nullptr).
const JsonValue* QueryStatus(MopEdgeEndpoint* endpoint, MopLink* link,
                             StepClock* clock, const std::string& flight,
                             std::int64_t tag) {
    const std::string msg_id =
        Uuid5(MopProtocol::Namespace(),
              "rc|" + flight + "|" + std::to_string(tag) + "|STATUS");
    JsonValue envelope = ksi::safety::MakeMopEnvelope(
        msg_id, "CONTROL_RELIABLE", "MISSION_STATUS_QUERY", flight, nullptr,
        nullptr, nullptr, JsonValue::Obj(), (*clock)());
    const std::string bytes = [&envelope] {
        std::string out;
        for (const auto& frame :
             ksi::safety::MopFrameCodec::Encode(WriteCanonicalJson(envelope))) {
            out += frame;
        }
        return out;
    }();
    link->Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
               bytes.size());
    // Latest MISSION_STATUS wins (the outbox accumulates across queries).
    const std::deque<JsonValue>& outbox = endpoint->outbox();
    for (std::size_t i = outbox.size(); i > 0; --i) {
        const JsonValue& queued = outbox[i - 1];
        const JsonValue* type = queued.Find("type");
        if (type != nullptr && type->IsString() &&
            type->strValue() == "MISSION_STATUS") {
            return queued.Find("payload");
        }
    }
    return nullptr;
}

}  // namespace

int main() {
    // ---- 1) bundle -> config: valid --------------------------------------
    {
        JsonValue parsed = JsonValue::Obj();
        CHECK(ParseBundleText(MakeValidBundle(), &parsed),
              "valid bundle parses as JSON");
        const BundleRuntimeConfig config = ParseBundleRuntimeConfig(parsed);
        CHECK(config.ok, "valid bundle installs");
        CHECK(config.errors.empty(), "no errors on valid bundle");
        CHECK(config.proximity_rules.size() == 1 &&
                  config.proximity_rules[0].rule_id == "prox-1",
              "one proximity rule installed");
        CHECK(config.proximity_rules[0].threshold_m == 5.0,
              "proximity threshold carried");
        CHECK(config.proximity_rules[0].enabled_equipment_classes.size() == 1 &&
                  config.proximity_rules[0].enabled_equipment_classes[0] ==
                      "excavator",
              "equipment class list carried");
        CHECK(config.zone_rules.size() == 1 &&
                  config.zone_rules[0].rule_id == "zone-1",
              "one zone rule installed");
        CHECK(config.zone_rules[0].mode == ksi::safety::RuleMode::kShadow,
              "zone rule stays SHADOW");
        CHECK(config.zone_rules[0].zone_revision_ids.size() == 1 &&
                  config.zone_rules[0].zone_revision_ids[0] == "zone-rev-1",
              "zone revision list carried");
        CHECK(config.zones.size() == 1 &&
                  config.zones[0].revision_id == "zone-rev-1",
              "only the enabled zone installs (disabled skipped)");
        CHECK(config.zones[0].polygons.empty(),
              "installed zone holds zero local polygons (no conversion)");
        CHECK(config.rejected_rule_ids.size() == 1 &&
                  config.rejected_rule_ids[0] == "act-1",
              "ACTIVE rule refused, never installed");
        CHECK(config.degraded_reasons.size() == 2 &&
                  config.degraded_reasons[0] == "policy_install_rejected" &&
                  config.degraded_reasons[1] == "zone_geometry_unavailable",
              "install holds reported with contract codes");
        CHECK(IsHex64(config.effective_config_hash),
              "effective config hash is 64 hex chars");
        const BundleRuntimeConfig again =
            ParseBundleRuntimeConfig(parsed);
        CHECK(again.ok &&
                  again.effective_config_hash == config.effective_config_hash,
              "config hash is deterministic (no clock, sorted canonical)");
    }

    // ---- 2) held zone honesty: withheld, never "no intrusion" ------------
    {
        JsonValue parsed = JsonValue::Obj();
        CHECK(ParseBundleText(MakeValidBundle(), &parsed), "bundle parses");
        const BundleRuntimeConfig config = ParseBundleRuntimeConfig(parsed);
        CHECK(config.ok && !config.zones.empty(), "held zone installed");
        ksi::safety::PersonGround person;
        person.status = ksi::safety::GroundStatus::kObserved;
        person.footpoint.x = 0.0;
        person.footpoint.y = 0.0;
        const ksi::safety::ZoneRelationResult relation =
            ksi::safety::RelatePointToZone(person, config.zones[0], 1.0);
        CHECK(relation.relation ==
                  ksi::safety::ZoneRelationKind::kUnknownStatus,
              "held zone withholds the verdict (never inside/outside)");
    }

    // ---- 3) ACTIVE-only bundle: installs nothing, still reports ----------
    {
        JsonValue bundle = MakeValidBundle();
        JsonValue* policy = bundle.FindMutable("risk_policy");
        JsonValue::Array rules;
        rules.push_back(MakeRule("act-only", "PERSON_EQUIPMENT_PROXIMITY",
                                 "ACTIVE", JsonValue::Double(2.0),
                                 "METRIC_VERIFIED"));
        policy->Set("rules", JsonValue::Arr(std::move(rules)));
        bundle.Set("bundle_sha256",
                   JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
        JsonValue parsed = JsonValue::Obj();
        CHECK(ParseBundleText(bundle, &parsed), "active-only parses");
        const BundleRuntimeConfig config = ParseBundleRuntimeConfig(parsed);
        CHECK(config.ok, "active-only bundle still installs (with holds)");
        CHECK(config.proximity_rules.empty() && config.zone_rules.empty(),
              "no rule installed from an ACTIVE-only bundle");
        CHECK(HasCode(config.degraded_reasons, "policy_install_rejected"),
              "ACTIVE rejection reported");
        CHECK(IsHex64(config.effective_config_hash), "hash still produced");
    }

    // ---- 4) malformed bundles fail closed --------------------------------
    {
        // Each mutator breaks exactly one contract term; every result must
        // be !ok with a diagnostic, installing nothing.
        int cases = 0;
        int failed_closed = 0;
        auto expect_fail = [&](JsonValue bundle, const char* label) {
            ++cases;
            bundle.Set("bundle_sha256",
                       JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
            JsonValue parsed = JsonValue::Obj();
            if (!ParseBundleText(bundle, &parsed)) {
                ++failed_closed;  // strict JSON itself rejects it
                return;
            }
            const BundleRuntimeConfig config = ParseBundleRuntimeConfig(parsed);
            if (!config.ok && !config.errors.empty() &&
                config.proximity_rules.empty() && config.zone_rules.empty() &&
                config.zones.empty() && config.effective_config_hash.empty()) {
                ++failed_closed;
            } else {
                std::fprintf(stderr, "NOT fail-closed: %s\n", label);
            }
        };
        {
            JsonValue bundle = WithRuleField(
                MakeValidBundle(), 0, "kind",
                JsonValue::Str("PERSON_ZONE_MAGIC"));
            expect_fail(std::move(bundle), "unknown kind");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 0, "mode",
                                             JsonValue::Str("MONITOR"));
            expect_fail(std::move(bundle), "unknown mode");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 0,
                                             "threshold_m", JsonValue::Null());
            expect_fail(std::move(bundle), "null proximity threshold");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 1,
                                             "threshold_m",
                                             JsonValue::Double(2.0));
            expect_fail(std::move(bundle), "numeric zone threshold");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 2,
                                             "required_geometry_level",
                                             JsonValue::Str("GEO_ESTIMATE"));
            expect_fail(std::move(bundle), "active without metric verified");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 0,
                                             "hysteresis_m",
                                             JsonValue::Double(-0.5));
            expect_fail(std::move(bundle), "negative hysteresis");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 0,
                                             "enter_duration_ms",
                                             JsonValue::Int(-1));
            expect_fail(std::move(bundle), "negative duration");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 0,
                                             "required_geometry_level",
                                             JsonValue::Str("ULTRA"));
            expect_fail(std::move(bundle), "unknown geometry level");
        }
        {
            JsonValue bundle = WithRuleField(MakeValidBundle(), 1, "rule_id",
                                             JsonValue::Str("prox-1"));
            expect_fail(std::move(bundle), "duplicate rule id");
        }
        {
            JsonValue bundle = WithoutZoneKey(MakeValidBundle(), 0,
                                              "revision_id");
            expect_fail(std::move(bundle), "zone without revision");
        }
        {
            JsonValue geometry = GeoPolygon();
            geometry.Set("type", JsonValue::Str("Point"));
            JsonValue bundle =
                WithZoneField(MakeValidBundle(), 0, "geometry_wgs84",
                              std::move(geometry));
            expect_fail(std::move(bundle), "non-polygon geometry");
        }
        {
            JsonValue::Array short_ring;
            for (int i = 0; i < 3; ++i) {
                JsonValue::Array pair;
                pair.push_back(JsonValue::Double(127.0 + i));
                pair.push_back(JsonValue::Double(37.5));
                short_ring.push_back(JsonValue::Arr(std::move(pair)));
            }
            JsonValue::Array rings;
            rings.push_back(JsonValue::Arr(std::move(short_ring)));
            JsonValue geometry = JsonValue::Obj();
            geometry.Set("type", JsonValue::Str("Polygon"));
            geometry.Set("coordinates", JsonValue::Arr(std::move(rings)));
            JsonValue bundle =
                WithZoneField(MakeValidBundle(), 0, "geometry_wgs84",
                              std::move(geometry));
            expect_fail(std::move(bundle), "degenerate ring");
        }
        {
            // Wrong-typed arrays fail closed (absent arrays are malformed
            // too — only explicit [] is the empty install, which the RC
            // demo feed uses).
            JsonValue typed = MakeValidBundle();
            typed.FindMutable("mission_snapshot")
                ->Set("zones", JsonValue::Str("nope"));
            expect_fail(std::move(typed), "zones wrong type");
        }
        {
            JsonValue typed = MakeValidBundle();
            typed.FindMutable("risk_policy")->Set("rules", JsonValue::Int(7));
            expect_fail(std::move(typed), "rules wrong type");
        }
        CHECK(failed_closed == cases,
              "every malformed bundle fails closed (nothing installed)");
    }

    // ---- 4b) absent arrays are malformed, never an empty install --------
    // (the pinned RC framing stub omits zones/rules — valid transport, but
    // the production runtime schema requires them; only explicit [] is the
    // empty install).
    {
        JsonValue parsed = JsonValue::Obj();
        CHECK(ParseBundleText(WithoutSnapshotKey(MakeValidBundle(), "zones"),
                              &parsed),
              "bundle without zones parses as JSON");
        BundleRuntimeConfig no_zones = ParseBundleRuntimeConfig(parsed);
        CHECK(!no_zones.ok && !no_zones.errors.empty(),
              "absent zones rejected (never an empty install)");
        CHECK(no_zones.proximity_rules.empty() &&
                  no_zones.zone_rules.empty() && no_zones.zones.empty() &&
                  no_zones.effective_config_hash.empty(),
              "rejected bundle installs and hashes nothing");
        CHECK(ParseBundleText(WithoutRuleKey(MakeValidBundle(), "rules"),
                              &parsed),
              "bundle without rules parses as JSON");
        BundleRuntimeConfig no_rules = ParseBundleRuntimeConfig(parsed);
        CHECK(!no_rules.ok && !no_rules.errors.empty(),
              "absent rules rejected (never an empty install)");
        CHECK(no_rules.proximity_rules.empty() &&
                  no_rules.zone_rules.empty() &&
                  no_rules.effective_config_hash.empty(),
              "rejected bundle installs and hashes nothing");
    }

    // ---- 5) runner state machine + STOPPED gate ---------------------------

    {
        JsonValue parsed = JsonValue::Obj();
        CHECK(ParseBundleText(MakeValidBundle(), &parsed), "bundle parses");
        const BundleRuntimeConfig bundle_config =
            ParseBundleRuntimeConfig(parsed);
        CHECK(bundle_config.ok, "bundle installs");
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
        CHECK(runner.runtime_state() == RuntimeState::kRequestAccepted,
              "initial state is REQUEST_ACCEPTED");
        CHECK(runner.processed_frame_count() == 0, "no frames processed yet");

        // Replay parity: a fresh runner processes direct calls (the Replay
        // driver path), and direct calls never move the run-control state.
        OkDetector replay_detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &replay_detector);
        CHECK(replay_detector.calls == 1, "fresh runner processes frames");
        CHECK(runner.processed_frame_count() == 1, "fresh runner counts");
        CHECK(runner.runtime_state() == RuntimeState::kRequestAccepted,
              "direct calls never move the state");

        // Identity loss without a clean stop parks the gate at STOPPED: no
        // frame is processed, the detector is never called, the counter
        // and the tick index freeze.
        runner.AbortRuntime();
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "abort parks at STOPPED");
        OkDetector gated_detector;
        const std::uint64_t tick_before = runner.TickCount();
        for (int i = 0; i < 3; ++i) {
            frame = MakeFrame(static_cast<std::uint32_t>(2 + i),
                              (20 + i) * kMs);
            const TickRecord gated =
                runner.ProcessFrame(frame, MakeCtx(frame), &gated_detector);
            CHECK(gated.detection_status == DetectionStatus::kNotRun,
                  "gated tick reports no attempt (never a negative)");
        }
        CHECK(gated_detector.calls == 0,
              "STOPPED runner never calls the detector");
        CHECK(runner.processed_frame_count() == 1,
              "processed_frame_count does not advance while STOPPED");
        CHECK(runner.TickCount() == tick_before,
              "STOPPED ticks do not advance the tick index");

        // Install => CONFIG_APPLIED.
        ksi::safety::RuntimeInstallResult installed = runner.ApplyRuntimeConfig(
            bundle_config.proximity_rules, bundle_config.zone_rules,
            bundle_config.zones, bundle_config.effective_config_hash,
            bundle_config.degraded_reasons);
        CHECK(installed.ok, "install accepted while idle");
        CHECK(installed.config_epoch == 1, "first install is epoch 1");
        CHECK(installed.config_applied, "config reported applied");
        CHECK(installed.effective_config_hash ==
                  bundle_config.effective_config_hash,
              "install echoes the effective hash");
        CHECK(installed.reasons == bundle_config.degraded_reasons,
              "install echoes the holds");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "state is CONFIG_APPLIED after install");

        // Start => STARTING with a zeroed counter.
        runner.RequestStart();
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "state is STARTING after start");
        CHECK(runner.processed_frame_count() == 0,
              "STARTING carries processed_frame_count 0");

        // First frame (no telemetry => DEGRADED with telemetry_absent).
        OkDetector live_detector;
        frame = MakeFrame(5, 50 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &live_detector);
        CHECK(live_detector.calls == 1, "started runner calls the detector");
        CHECK(runner.processed_frame_count() == 1,
              "processed_frame_count advances once started");
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "missing telemetry degrades the run");
        const RuntimeSnapshot degraded = runner.Snapshot();
        CHECK(HasCode(degraded.degraded_reasons, "telemetry_absent"),
              "telemetry_absent reported");
        CHECK(HasCode(degraded.degraded_reasons, "policy_install_rejected"),
              "install holds persist into the run");
        CHECK(degraded.input_state.telemetry == "absent",
              "input_state.telemetry is absent");
        CHECK(degraded.input_state.video == "ok",
              "input_state.video is ok (frames flow)");
        CHECK(degraded.input_state.engine == "ok",
              "input_state.engine is ok (detector bound, attempts clean)");

        // Reinstall while live is rejected (stop first).
        ksi::safety::RuntimeInstallResult rejected = runner.ApplyRuntimeConfig(
            {}, {}, {}, "other-hash", {});
        CHECK(!rejected.ok, "reinstall while live is rejected");
        CHECK(rejected.reasons.size() == 1 &&
                  rejected.reasons[0] == "reinstall_while_running",
              "rejection names the reason");
        CHECK(runner.effective_config_hash() ==
                  bundle_config.effective_config_hash,
              "live install keeps the old hash");

        // Stop => STOPPED; episode closeout; gate closed.
        const ksi::safety::SessionOutput closeout =
            runner.RequestStop(25 * kMs);
        (void)closeout;
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "state is STOPPED after stop");
        const std::uint64_t stopped_count = runner.processed_frame_count();
        const std::uint64_t stopped_tick = runner.TickCount();
        for (int i = 0; i < 3; ++i) {
            frame = MakeFrame(static_cast<std::uint32_t>(6 + i),
                              (60 + i) * kMs);
            runner.ProcessFrame(frame, MakeCtx(frame), &live_detector);
        }
        CHECK(live_detector.calls == 1,
              "stopped runner never calls the detector again");
        CHECK(runner.processed_frame_count() == stopped_count,
              "processed_frame_count frozen while STOPPED");
        CHECK(runner.TickCount() == stopped_tick,
              "stopped ticks do not advance the tick index");

        // Restart => STARTING with a reset counter.
        runner.RequestStart();
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "restart returns to STARTING");
        CHECK(runner.processed_frame_count() == 0,
              "restart resets the counter");
        frame = MakeFrame(9, 90 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &live_detector);
        CHECK(runner.processed_frame_count() == 1,
              "counter advances after restart");

        // Reinstall after a clean stop => CONFIG_APPLIED with a new epoch.
        const ksi::safety::SessionOutput second_close =
            runner.RequestStop(95 * kMs);
        (void)second_close;
        const ksi::safety::RuntimeInstallResult second =
            runner.ApplyRuntimeConfig({}, {}, {}, "hash-epoch-2", {});
        CHECK(second.ok && second.config_epoch == 2,
              "reinstall after stop bumps the epoch");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "reinstall parks at CONFIG_APPLIED");
    }

    // ---- 6) clean run reaches RUNNING -------------------------------------
    {
        // Empty install (no rules, no holds) + verified telemetry + a clean
        // detector: the only healthy combination the host can observe.
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
        const ksi::safety::RuntimeInstallResult installed =
            runner.ApplyRuntimeConfig({}, {}, {}, "clean-hash", {});
        CHECK(installed.ok && installed.reasons.empty(),
              "clean install has no holds");
        runner.RequestStart();
        OkDetector detector;
        const std::int64_t recv = 100 * kMs;
        Sample<Quaternion> pose;
        pose.sample_id = 1;
        pose.seq = 1;
        pose.received = VerifiedTs(recv - kMs);
        pose.has_source_time = true;
        pose.source_time = VerifiedTs(recv);
        Sample<Vec3> position;
        position.sample_id = 2;
        position.seq = 2;
        position.received = VerifiedTs(recv - kMs);
        position.has_source_time = true;
        position.source_time = VerifiedTs(recv);
        CHECK(runner.PushPose(pose) == ksi::safety::PushVerdict::kRecorded,
              "pose recorded");
        CHECK(runner.PushPosition(position) ==
                  ksi::safety::PushVerdict::kRecorded,
              "position recorded");
        FramePacket frame = MakeFrame(1, recv);
        FrameContext ctx = MakeCtx(frame);
        ctx.has_exposure = true;
        ctx.exposure = VerifiedTs(recv);
        runner.ProcessFrame(frame, ctx, &detector);
        const RuntimeSnapshot snapshot = runner.Snapshot();
        CHECK(runner.runtime_state() == RuntimeState::kRunning,
              "healthy tick is RUNNING");
        CHECK(snapshot.degraded_reasons.empty(),
              "no degraded reasons on a healthy tick");
        CHECK(snapshot.input_state.telemetry == "ok" &&
                  snapshot.input_state.video == "ok" &&
                  snapshot.input_state.engine == "ok",
              "all inputs ok on a healthy tick");
        CHECK(snapshot.has_processed_frame &&
                  snapshot.last_processed_frame_mono_ns == recv,
              "last processed frame latched");
    }

    // ---- 7) inference failure degrades with engine_failed -----------------
    {
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
        CHECK(runner.ApplyRuntimeConfig({}, {}, {}, "h", {}).ok,
              "install for failure test");
        runner.RequestStart();
        FailDetector failing;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &failing);
        const RuntimeSnapshot snapshot = runner.Snapshot();
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "inference failure degrades the run");
        CHECK(HasCode(snapshot.degraded_reasons, "engine_failed"),
              "engine_failed reported");
        CHECK(snapshot.input_state.engine == "failed",
              "input_state.engine is failed");
    }

    // ---- 8) MOP-link loss never stops local observation -------------------
    {
        // Structural: the runner owns no transport. Frames process (and the
        // counter advances) with no endpoint, no link, no connection.
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
        CHECK(runner.ApplyRuntimeConfig({}, {}, {}, "h", {}).ok, "installed");
        runner.RequestStart();
        OkDetector detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        CHECK(runner.processed_frame_count() == 1,
              "local observation needs no MOP link");
    }

    // ---- 9) MISSION_STATUS carries the optional runtime facts -------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-rt", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        const JsonValue* bare = QueryStatus(&endpoint, &link, &clock, "flt-rt",
                                            1);
        CHECK(bare != nullptr, "legacy STATUS answers without facts");
        CHECK(bare->Find("runtime_epoch") == nullptr,
              "absent facts stay absent (never false/0)");
        CHECK(bare->Find("runtime_state") == nullptr,
              "absent runtime_state stays absent");
        CHECK(bare->Find("config_applied") == nullptr,
              "absent config_applied stays absent");
        CHECK(bare->Find("processed_frame_count") == nullptr,
              "absent count stays absent");
        CHECK(bare->Find("degraded_reasons") == nullptr,
              "absent reasons stay absent");
        CHECK(bare->Find("input_state") == nullptr,
              "absent input_state stays absent");
        CHECK(bare->Find("running") != nullptr &&
                  bare->Find("running")->IsBool(),
              "legacy running field kept");

        MopRuntimeFacts facts;
        facts.has_config_epoch = true;
        facts.config_epoch = 1;
        facts.has_state = true;
        facts.runtime_state = "RUNNING";
        facts.has_config_applied = true;
        facts.config_applied = true;
        facts.has_effective_config_hash = true;
        facts.effective_config_hash = std::string(64, 'c');
        facts.has_processed_frame_count = true;
        facts.processed_frame_count = 42;
        facts.has_last_processed_mono_ns = true;
        facts.last_processed_frame_mono_ns = clock.now - 5 * kMs;
        facts.has_degraded_reasons = true;
        facts.degraded_reasons.push_back("telemetry_stale");
        facts.has_input_state = true;
        facts.input_state.video = "ok";
        facts.input_state.telemetry = "stale";
        facts.input_state.engine = "ok";
        endpoint.PublishRuntimeFacts(facts);
        const JsonValue* status =
            QueryStatus(&endpoint, &link, &clock, "flt-rt", 2);
        CHECK(status != nullptr, "STATUS answers with facts");
        const JsonValue* epoch = status->Find("runtime_epoch");
        CHECK(epoch != nullptr && epoch->IsString() &&
                  epoch->strValue() ==
                      Uuid5(MopProtocol::Namespace(),
                            "runtime|flt-rt|" + endpoint.edge_boot_id() +
                                "|1"),
              "runtime_epoch derives deterministically (flight|boot|epoch)");
        const JsonValue* state = status->Find("runtime_state");
        CHECK(state != nullptr && state->IsString() &&
                  state->strValue() == "RUNNING",
              "runtime_state carried");
        const JsonValue* applied = status->Find("config_applied");
        CHECK(applied != nullptr && applied->IsBool() &&
                  applied->boolValue(),
              "config_applied carried as bool");
        const JsonValue* hash = status->Find("effective_config_hash");
        CHECK(hash != nullptr && hash->IsString() &&
                  hash->strValue() == std::string(64, 'c'),
              "effective_config_hash carried");
        const JsonValue* count = status->Find("processed_frame_count");
        CHECK(count != nullptr && count->IsInt() &&
                  count->intValue() == 42,
              "processed_frame_count carried as int");
        const JsonValue* age = status->Find("last_processed_frame_age_ms");
        CHECK(age != nullptr && age->IsInt() && age->intValue() >= 5,
              "last_processed_frame_age_ms carried as int");
        const JsonValue* reasons = status->Find("degraded_reasons");
        CHECK(reasons != nullptr && reasons->IsArray() &&
                  reasons->Size() == 1 &&
                  reasons->arrayValue()[0].strValue() == "telemetry_stale",
              "degraded_reasons carried");
        const JsonValue* input = status->Find("input_state");
        CHECK(input != nullptr && input->IsObject() &&
                  input->Find("video")->strValue() == "ok" &&
                  input->Find("telemetry")->strValue() == "stale" &&
                  input->Find("engine")->strValue() == "ok",
              "input_state carried");

        // Null spellings: installed-but-countless, and set-but-hashless.
        MopRuntimeFacts sparse;
        sparse.has_config_epoch = true;
        sparse.config_epoch = 2;
        sparse.has_processed_frame_count = true;
        sparse.processed_frame_count = 0;
        sparse.has_effective_config_hash = true;  // empty => null
        endpoint.PublishRuntimeFacts(sparse);
        const JsonValue* sparse_status =
            QueryStatus(&endpoint, &link, &clock, "flt-rt", 3);
        CHECK(sparse_status != nullptr, "sparse STATUS answers");
        const JsonValue* sparse_age =
            sparse_status->Find("last_processed_frame_age_ms");
        CHECK(sparse_age != nullptr && sparse_age->IsNull(),
              "no processed frame yet => age null (not 0)");
        const JsonValue* sparse_hash =
            sparse_status->Find("effective_config_hash");
        CHECK(sparse_hash != nullptr && sparse_hash->IsNull(),
              "set-but-empty hash => null");
        const JsonValue* sparse_epoch =
            sparse_status->Find("runtime_epoch");
        CHECK(sparse_epoch != nullptr && sparse_epoch->IsString() &&
                  sparse_epoch->strValue() != epoch->strValue(),
              "config epoch bump rotates runtime_epoch");
    }

    // ---- 10) session counters, dedup, reboot ------------------------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-c", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        auto send_session = [&](const std::string& type,
                                const std::string& msg_id) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, "flt-c", nullptr, nullptr,
                nullptr, JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                       bytes.size());
        };
        const std::string start1 =
            Uuid5(MopProtocol::Namespace(), "rc|flt-c|1|START");
        const std::string start2 =
            Uuid5(MopProtocol::Namespace(), "rc|flt-c|2|START");
        send_session("SESSION_START", start1);
        send_session("SESSION_START", start1);  // retry: dedup, no recount
        send_session("SESSION_START", start2);
        CHECK(endpoint.session_start_count() == 2,
              "distinct START commands counted once each (dedup)");
        CHECK(endpoint.running(), "running after SESSION_START");
        send_session("SESSION_STOP", Uuid5(MopProtocol::Namespace(),
                                           "rc|flt-c|3|STOP"));
        CHECK(endpoint.session_stop_count() == 1, "STOP counted");
        CHECK(!endpoint.running(), "not running after SESSION_STOP");
        // Command revision/order track the session intent: retries dedup
        // before the revision, the latest command sets the desired intent
        // and the order flag.
        CHECK(endpoint.session_commands().command_revision == 3,
              "two STARTs (one deduped retry) + one STOP = revision 3");
        CHECK(!endpoint.session_commands().desired_running,
              "latest command (STOP) is the desired intent");
        CHECK(!endpoint.session_commands().last_command_was_start,
              "order flag names the latest command");
        const std::uint64_t generation = endpoint.applied_generation();
        endpoint.Reboot();
        CHECK(endpoint.session_start_count() == 0 &&
                  endpoint.session_stop_count() == 0,
              "reboot resets session counters");
        CHECK(!endpoint.running(), "reboot clears running");
        CHECK(!endpoint.hasRuntimeFacts(), "reboot clears runtime facts");
        CHECK(endpoint.applied_generation() == generation + 1,
              "reboot bumps the applied generation");
        // Reboot clears the intent and resets the revision while bumping
        // the epoch, so a consumer re-baselines instead of replaying.
        CHECK(endpoint.session_commands().command_revision == 0 &&
                  !endpoint.session_commands().desired_running &&
                  endpoint.session_commands().command_epoch == 1,
              "reboot resets revision/intent and bumps the epoch");
    }

    // ---- 10b) atomic command snapshot: revision, dedup, epoch ------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-s", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        auto send_session = [&](const std::string& type,
                                const std::string& flight,
                                const std::string& msg_id) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, flight, nullptr, nullptr,
                nullptr, JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                       bytes.size());
        };
        MopSessionCommands bare = endpoint.session_commands();
        CHECK(!bare.desired_running && bare.command_revision == 0 &&
                  bare.command_epoch == 0 && bare.start_count == 0 &&
                  bare.stop_count == 0 && !bare.has_bundle,
              "fresh endpoint snapshots idle, epoch 0, no bundle");
        const std::string start1 =
            Uuid5(MopProtocol::Namespace(), "rc|flt-s|1|START");
        send_session("SESSION_START", "flt-s", start1);
        MopSessionCommands after_start = endpoint.session_commands();
        CHECK(after_start.desired_running &&
                  after_start.command_revision == 1 &&
                  after_start.start_count == 1 && after_start.stop_count == 0 &&
                  after_start.last_command_was_start,
              "START snapshot: desired running, revision 1, order flag set");
        send_session("SESSION_START", "flt-s", start1);  // wire retry
        MopSessionCommands after_retry = endpoint.session_commands();
        CHECK(after_retry.command_revision == 1 &&
                  after_retry.start_count == 1 &&
                  after_retry.desired_running,
              "same msg_id dedups: no revision advance, no recount");
        send_session("SESSION_STOP", "flt-s",
                     Uuid5(MopProtocol::Namespace(), "rc|flt-s|2|STOP"));
        MopSessionCommands after_stop = endpoint.session_commands();
        CHECK(!after_stop.desired_running &&
                  after_stop.command_revision == 2 &&
                  after_stop.start_count == 1 && after_stop.stop_count == 1 &&
                  !after_stop.last_command_was_start,
              "STOP snapshot stays coherent: START+STOP, desired stopped");
        // Reboot: intent cleared, revision reset, epoch up — a consumer
        // re-baselines instead of replaying the pre-reboot START.
        endpoint.Reboot();
        MopSessionCommands after_reboot = endpoint.session_commands();
        CHECK(!after_reboot.desired_running &&
                  after_reboot.command_revision == 0 &&
                  after_reboot.start_count == 0 &&
                  after_reboot.stop_count == 0 &&
                  after_reboot.command_epoch ==
                      after_stop.command_epoch + 1,
              "reboot clears intent, resets revision, bumps the epoch");
        // Adoption (empty-id endpoint learns the RC flight): same shape —
        // epoch up, counts fresh, then the adopting command applies.
        MopEdgeEndpoint learner("", [&clock] { return clock(); });
        FakeTransport transport2;
        MopLink link2(&transport2, &learner);
        auto send_learn = [&](const std::string& type,
                              const std::string& flight,
                              const std::string& msg_id) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, flight, nullptr, nullptr,
                nullptr, JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link2.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                       bytes.size());
        };
        send_learn("SESSION_START", "flt-new",
                   Uuid5(MopProtocol::Namespace(), "rc|flt-new|1|START"));
        CHECK(learner.flightIdConfirmed(), "flight adopted from the wire");
        MopSessionCommands adopted = learner.session_commands();
        CHECK(adopted.desired_running && adopted.command_revision == 1 &&
                  adopted.start_count == 1 && adopted.command_epoch == 1,
              "adoption bumps the epoch and applies the adopting command");
    }

    // ---- 10c) deferred install: APPLIED only on the real receipt ---------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-d", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        auto send = [&](const std::string& type, const std::string& msg_id,
                        JsonValue payload) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, "flt-d", nullptr, nullptr,
                nullptr, std::move(payload), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size());
        };
        auto has_type = [&](const std::string& type) {
            for (const JsonValue& queued : endpoint.outbox()) {
                const JsonValue* found = queued.Find("type");
                if (found != nullptr && found->IsString() &&
                    found->strValue() == type) {
                    return true;
                }
            }
            return false;
        };
        auto make_stub = [](const std::string& id) {
            JsonValue bundle = JsonValue::Obj();
            bundle.Set("bundle_id", JsonValue::Str(id));
            bundle.Set("mission_snapshot", JsonValue::Obj());
            bundle.Set("risk_policy", JsonValue::Obj());
            bundle.Set(
                "bundle_sha256",
                JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
            return bundle;
        };
        // Legacy default: immediate apply (pure-protocol behaviour).
        CHECK(!endpoint.deferredApply(), "deferred off by default");
        endpoint.setDeferredApply(true);

        const JsonValue bundle_a = make_stub("bundle-a");
        const std::string sha_a = bundle_a.Find("bundle_sha256")->strValue();
        JsonValue prepare = JsonValue::Obj();
        prepare.Set("bundle", bundle_a);
        send("MISSION_PREPARE",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|1|PREPARE"),
             std::move(prepare));
        CHECK(has_type("MISSION_PREPARED"), "valid PREPARE answers PREPARED");
        JsonValue apply = JsonValue::Obj();
        apply.Set("bundle_id", JsonValue::Str("bundle-a"));
        apply.Set("bundle_sha256", JsonValue::Str(sha_a));
        send("MISSION_APPLY",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|2|APPLY"),
             std::move(apply));
        // Accepted at transport level (ACK), but nothing switches yet: no
        // APPLIED, no bundle, no generation move. ACK != installed.
        CHECK(!has_type("MISSION_APPLIED"),
              "no MISSION_APPLIED before the install receipt");
        CHECK(endpoint.hasPendingApply(), "validated apply pends");
        CHECK(!endpoint.hasAppliedBundle(),
              "no bundle switch before the install receipt");
        CHECK(endpoint.session_commands().applied_generation == 0,
              "no generation move before the install receipt");
        CHECK(endpoint.pendingBundle() != nullptr &&
                  endpoint.pendingBundle()->Find("bundle_id")->strValue() ==
                      "bundle-a",
              "pending bundle is the validated request");
        // A retried APPLY (same msg_id) before the confirm: dedup, still no
        // APPLIED — the confirm will answer it.
        send("MISSION_APPLY",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|2|APPLY"),
             JsonValue::Obj());
        CHECK(!has_type("MISSION_APPLIED"),
              "retry before confirm still sends no APPLIED");
        // Install receipt: switch + answer, exactly once.
        CHECK(endpoint.ConfirmPendingApply(), "install receipt confirms");
        CHECK(endpoint.hasAppliedBundle(), "bundle switches on confirm");
        CHECK(endpoint.appliedBundleSha256() == sha_a,
              "confirmed sha matches the validated request");
        CHECK(has_type("MISSION_APPLIED"),
              "MISSION_APPLIED only after the actual install");
        CHECK(!endpoint.hasPendingApply(), "confirm clears the pending");
        CHECK(endpoint.session_commands().applied_generation == 0,
              "confirm does not bump the installer's generation");
        // Second bundle: refused install -> REJECTED, old bundle kept.
        const JsonValue bundle_b = make_stub("bundle-b");
        const std::string sha_b = bundle_b.Find("bundle_sha256")->strValue();
        JsonValue prepare_b = JsonValue::Obj();
        prepare_b.Set("bundle", bundle_b);
        send("MISSION_PREPARE",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|3|PREPARE"),
             std::move(prepare_b));
        JsonValue apply_b = JsonValue::Obj();
        apply_b.Set("bundle_id", JsonValue::Str("bundle-b"));
        apply_b.Set("bundle_sha256", JsonValue::Str(sha_b));
        send("MISSION_APPLY",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|4|APPLY"),
             std::move(apply_b));
        CHECK(endpoint.hasPendingApply(), "second apply pends");
        CHECK(endpoint.RejectPendingApply({"reinstall_while_running"}),
              "refusal answers the pending request");
        CHECK(has_type("MISSION_APPLY_REJECTED"),
              "refused install answers REJECTED with reasons");
        CHECK(endpoint.appliedBundleSha256() == sha_a,
              "refused apply never relabels the old bundle");
        CHECK(!endpoint.ConfirmPendingApply(),
              "nothing left to confirm after a reject");
        // Reboot clears a pending request: the post-reboot confirm fails
        // and the caller parks instead of running it.
        JsonValue apply_c = JsonValue::Obj();
        apply_c.Set("bundle_id", JsonValue::Str("bundle-b"));
        apply_c.Set("bundle_sha256", JsonValue::Str(sha_b));
        send("MISSION_APPLY",
             Uuid5(MopProtocol::Namespace(), "rc|flt-d|5|APPLY"),
             std::move(apply_c));
        CHECK(endpoint.hasPendingApply(), "third apply pends");
        endpoint.Reboot();
        CHECK(!endpoint.hasPendingApply(), "reboot clears the pending");
        CHECK(!endpoint.ConfirmPendingApply(),
              "post-reboot confirm fails (park, never run)");
    }

    // ---- 10d) boot identity: entropy separates, legacy stays stable ------
    {
        StepClock clock;
        MopEdgeEndpoint with_a("flt-u", [&clock] { return clock(); }, 0, "",
                               "P-A");
        MopEdgeEndpoint with_b("flt-u", [&clock] { return clock(); }, 0, "",
                               "P-B");
        CHECK(with_a.edge_boot_id() != with_b.edge_boot_id(),
              "per-process entropy separates same-flight boots");
        MopEdgeEndpoint legacy_c("flt-u", [&clock] { return clock(); });
        MopEdgeEndpoint legacy_d("flt-u", [&clock] { return clock(); });
        CHECK(legacy_c.edge_boot_id() == legacy_d.edge_boot_id(),
              "empty entropy keeps the legacy derivation (fixtures)");
        const std::string boot_a0 = with_a.edge_boot_id();
        with_a.Reboot();
        CHECK(with_a.edge_boot_id() != boot_a0,
              "reboot derives a fresh boot under entropy");
        CHECK(with_a.session_commands().command_epoch == 1,
              "reboot bumps the epoch under entropy");
    }

    // ---- 10e) link-serialised access stays coherent under threads --------
    // Feeder thread drives commands through link.Feed (reader path) while
    // the poll thread reads one coherent session_commands() snapshot per
    // iteration via MopLink::WithEndpoint (the ONLY sanctioned cross-thread
    // endpoint access). Every sampled snapshot must satisfy the endpoint's
    // invariants; TSan (where available) must stay clean.
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-t", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        constexpr int kCommands = 50;
        auto send_type = [&](const std::string& type,
                             const std::string& msg_id) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, "flt-t", nullptr, nullptr,
                nullptr, JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size());
        };
        std::atomic<bool> feed_done{false};
        std::thread feeder([&] {
            for (int i = 0; i < kCommands; ++i) {
                const bool start = (i % 2) == 0;
                send_type(start ? "SESSION_START" : "SESSION_STOP",
                          Uuid5(MopProtocol::Namespace(),
                                "rc|flt-t|" + std::to_string(i) + "|S"));
            }
            feed_done.store(true);
        });
        std::uint64_t saw_revision = 0;
        bool coherent = true;
        while (!feed_done.load() ||
               saw_revision < static_cast<std::uint64_t>(kCommands)) {
            link.WithEndpoint([&](MopEdgeEndpoint* ep) {
                const MopSessionCommands sc = ep->session_commands();
                // One snapshot, never fused: revision splits exactly into
                // starts+stops, and the desired intent always matches the
                // latest command's type (alternating START first).
                if (sc.command_revision !=
                    sc.start_count + sc.stop_count) {
                    coherent = false;
                }
                if (sc.desired_running !=
                    (sc.start_count > sc.stop_count)) {
                    coherent = false;
                }
                if (sc.desired_running != sc.last_command_was_start) {
                    coherent = false;
                }
                if (sc.start_count != sc.stop_count &&
                    sc.start_count != sc.stop_count + 1) {
                    coherent = false;
                }
                saw_revision = sc.command_revision;
            });
        }
        feeder.join();
        CHECK(coherent, "every sampled snapshot is internally coherent");
        const MopSessionCommands final = endpoint.session_commands();
        CHECK(final.command_revision ==
                  static_cast<std::uint64_t>(kCommands),
              "all commands accepted exactly once");
        CHECK(final.start_count == 25 && final.stop_count == 25 &&
                  !final.desired_running,
              "final intent is the last STOP");
        // The audit drain sees every command id exactly once, in order.
        bool overflow = true;
        const std::vector<AcceptedCommand> drained =
            endpoint.AcceptedCommandsSince(0, &overflow);
        CHECK(!overflow && drained.size() ==
                                 static_cast<std::size_t>(kCommands),
              "command log drains completely with no gap");
        for (int i = 0; i < kCommands; ++i) {
            const std::string want =
                Uuid5(MopProtocol::Namespace(),
                      "rc|flt-t|" + std::to_string(i) + "|S");
            if (drained[static_cast<std::size_t>(i)].msg_id != want) {
                coherent = false;
            }
            const bool want_start = (i % 2) == 0;
            if ((drained[static_cast<std::size_t>(i)].type ==
                 "SESSION_START") != want_start) {
                coherent = false;
            }
        }
        CHECK(coherent, "drained command ids match the sent wire order");
    }

    // ---- 10f) accepted-command log overflow is explicit ------------
    // 513 accepted commands before a poll: the bounded ring (512) drops
    // the oldest and flags overflow instead of silently losing history.
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-o", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        auto send_session = [&](int i) {
            const std::string type =
                (i % 2 == 0) ? "SESSION_START" : "SESSION_STOP";
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                Uuid5(MopProtocol::Namespace(),
                      "rc|flt-o|" + std::to_string(i) + "|S"),
                "CONTROL_RELIABLE", type, "flt-o", nullptr, nullptr, nullptr,
                JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size());
        };
        for (int i = 0; i < 513; ++i) {
            send_session(i);
        }
        bool overflow = false;
        const std::vector<AcceptedCommand> drained =
            endpoint.AcceptedCommandsSince(0, &overflow);
        CHECK(overflow, "overflow flag set when the ring dropped commands");
        CHECK(drained.size() == 512, "ring is bounded at 512");
        CHECK(drained.front().seq == 2,
              "oldest retained follows the single dropped command");
        CHECK(drained.back().seq == 513, "newest command retained");
        bool overflow2 = true;
        const std::vector<AcceptedCommand> tail =
            endpoint.AcceptedCommandsSince(512, &overflow2);
        CHECK(!overflow2 && tail.size() == 1 &&
                  tail[0].msg_id ==
                      Uuid5(MopProtocol::Namespace(), "rc|flt-o|512|S"),
              "partial drain stays exact with no false overflow");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
