// Assessment delivery (Unit A closure): one sealed assessment JSON over the
// EXISTING generic artifact plane (ARTIFACT_ANNOUNCE + ARTIFACT_CHUNK on
// EVIDENCE_TRANSFER) — no new MOP message type, no contract change.
//
// Chain under test: AutonomousProcessor seals the assessment ->
// EmitAssessmentArtifact queues announce+chunks -> framed bytes reassemble
// and verify (digest) -> the RC can parse disposition/reasons/rule + model
// digests and link by deterministic ids, all on host (no device, no PSDK).
// The pinned cross-language fixture (contracts/mop/edge-to-rc.mopv1.hex) is
// untouched: only pre-existing envelope types may appear here.

#include "ksi_safety/observation/assessment.hpp"
#include "ksi_safety/observation/autonomous_processor.hpp"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ksi_safety/evidence/store.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"

#include "support/check.hpp"

#if defined(_WIN32)
#include <process.h>
#define DELIVERY_PID _getpid()
#else
#include <unistd.h>
#define DELIVERY_PID ::getpid()
#endif

using ksi::safety::AssessmentContext;
using ksi::safety::AutonomousConfig;
using ksi::safety::AutonomousProcessor;
using ksi::safety::ContextRingConfig;
using ksi::safety::Detection;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::EvidenceStatus;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::GeometryLevel;
using ksi::safety::JsonValue;
using ksi::safety::ModelProfile;
using ksi::safety::MopByteTransport;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;
using ksi::safety::MopProtocol;
using ksi::safety::MopReassembler;
using ksi::safety::ParseJson;
using ksi::safety::ProcessSharedTick;
using ksi::safety::Quaternion;
using ksi::safety::RiskRule;
using ksi::safety::RuleKind;
using ksi::safety::RuleMode;
using ksi::safety::RunnerConfig;
using ksi::safety::Sample;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::SharedTickResult;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::Vec3;
using ksi::safety::ZoneRevision;

namespace {

constexpr std::int64_t kMs = 1000000LL;
constexpr const char* kScratch = "/tmp/opencode/ksi-assessment-delivery";

std::string ScratchDir(const char* tag, int seq) {
    char path[256];
    std::snprintf(path, sizeof(path), "%s/%s-%d-%d", kScratch, tag,
                  static_cast<int>(DELIVERY_PID), seq);
    char cmd[300];
    std::snprintf(cmd, sizeof(cmd), "mkdir -p %s", path);
    if (std::system(cmd) != 0) {
        std::fprintf(stderr, "scratch mkdir failed: %s\n", path);
    }
    return std::string(path);
}

struct FakeTransport : MopByteTransport {
    std::string written;

    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        written.append(reinterpret_cast<const char*>(data), len);
        return true;
    }
};

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;
        return value;
    }
};

Timestamp SensorTs(std::int64_t ns) {
    Timestamp ts;
    ts.value_ns = ns;
    ts.kind = TimeSourceKind::kDeviceSensor;
    ts.domain = "cam0";
    ts.verified = true;
    return ts;
}

Timestamp HostTs(std::int64_t ns) {
    Timestamp ts;
    ts.value_ns = ns;
    ts.kind = TimeSourceKind::kHostReceive;
    ts.domain = "host_mono";
    ts.verified = true;
    return ts;
}

Quaternion NadirQ() {
    Quaternion q;
    q.w = 0.0;
    q.x = 1.0;
    q.y = 0.0;
    q.z = 0.0;
    return q;
}

std::uint64_t g_sample_id = 1;

void PushNadirPose(SessionRunner* runner, std::int64_t exposure_ns) {
    for (int side = -1; side <= 1; side += 2) {
        const std::int64_t src = exposure_ns + side * 5 * kMs;
        Sample<Quaternion> att;
        att.sample_id = g_sample_id;
        att.seq = g_sample_id++;
        att.received = HostTs(src);
        att.has_source_time = true;
        att.source_time = SensorTs(src);
        att.value = NadirQ();
        runner->PushPose(att);
        Sample<Vec3> pos;
        pos.sample_id = g_sample_id;
        pos.seq = g_sample_id++;
        pos.received = HostTs(src);
        pos.has_source_time = true;
        pos.source_time = SensorTs(src);
        pos.value.x = 0.0;
        pos.value.y = 0.0;
        pos.value.z = 10.0;
        runner->PushPosition(pos);
    }
}

FramePacket MakeFrame(std::uint32_t frame_id, std::int64_t recv_ns) {
    FramePacket packet;
    packet.metadata.width = 8;
    packet.metadata.height = 6;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = recv_ns;
    packet.rgb.assign(8U * 6U * 3U, 7);
    return packet;
}

FrameContext MakeCtx(const FramePacket& packet) {
    FrameContext ctx;
    ctx.flight_id = "flt-A";
    ctx.boot_id = "boot-A";
    ctx.stream_id = "S";
    ctx.frame_id = packet.metadata.frame_id;
    ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
    ctx.has_exposure = true;
    ctx.exposure = SensorTs(packet.metadata.received_monotonic_ns);
    return ctx;
}

RunnerConfig SpatialRunnerCfg() {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = 1;
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

ModelProfile StubProfile() {
    ModelProfile profile;
    profile.model_id = "stub-detector";
    profile.model_digest = "stub-digest-not-a-model";
    profile.backend = "stub";
    return profile;
}

ContextRingConfig RingCfg() {
    ContextRingConfig config;
    config.max_frames = 16;
    config.max_bytes = 1 << 20;
    config.pre_frames = 2;
    config.post_frames = 2;
    config.window_deadline_ns = 500 * kMs;
    return config;
}

AutonomousConfig AutoCfg(const std::string& dir) {
    AutonomousConfig config;
    config.enabled = true;
    config.ring = RingCfg();
    config.store_capacity_bytes = 8 << 20;
    config.evidence_dir = dir;
    config.flight_id = "flt-A";
    config.edge_boot_id = "boot-A";
    config.stream_id = "S";
    config.mission_revision_id = "mis-A";
    config.policy_revision_id = "pol-A";
    config.simulated_input = true;
    return config;
}

class PersonStub : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket&) override {
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection det;
        det.class_id = 0;
        det.score = 0.99F;
        det.x_min = 0.375F;
        det.y_min = 0.5F;
        det.x_max = 0.625F;
        det.y_max = 1.0F;
        batch.detections.push_back(det);
        return batch;
    }
};

void InstallZone(SessionRunner* runner) {
    RiskRule rule;
    rule.rule_id = "zone-intrusion-1";
    rule.kind = RuleKind::kZoneIntrusion;
    rule.mode = RuleMode::kShadow;
    rule.required_geometry_level = GeometryLevel::kPixelOnly;
    ZoneRevision zone;
    zone.revision_id = "z1";
    ksi::safety::Polygon2 poly;
    ksi::safety::Vec2 a, b, c, d;
    a.x = -2.0;
    a.y = -8.0;
    b.x = 2.0;
    b.y = -8.0;
    c.x = 2.0;
    c.y = -4.0;
    d.x = -2.0;
    d.y = -4.0;
    poly.push_back(a);
    poly.push_back(b);
    poly.push_back(c);
    poly.push_back(d);
    zone.polygons.push_back(poly);
    CHECK(runner->ApplyRuntimeConfig({}, {rule}, {zone}, "hash-zone-A", {}).ok,
          "zone install holds");
    runner->RequestStart();
}

const JsonValue* Field(const JsonValue& node, const char* key) {
    return node.Find(key);
}

// Test-only base64 decoder (chunk reassembly check; the product path never
// decodes — RC stores the bytes and verifies the sha256).
std::string Base64Decode(const std::string& input) {
    auto value = [](char ch) -> int {
        if (ch >= 'A' && ch <= 'Z') return ch - 'A';
        if (ch >= 'a' && ch <= 'z') return ch - 'a' + 26;
        if (ch >= '0' && ch <= '9') return ch - '0' + 52;
        if (ch == '+') return 62;
        if (ch == '/') return 63;
        return -1;
    };
    std::string out;
    unsigned int bits = 0;
    std::uint32_t acc = 0;
    for (const char ch : input) {
        if (ch == '=') break;
        const int v = value(ch);
        if (v < 0) return "";
        acc = (acc << 6) | static_cast<std::uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<char>((acc >> bits) & 0xFF));
        }
    }
    return out;
}

bool TypeAllowed(const std::string& type) {
    for (const auto& known : MopProtocol::EdgeToRc()) {
        if (known == type) return true;
    }
    return false;
}

}  // namespace

int main() {
    // ---- D1: sealed assessment -> artifact plane -> verified bytes ---------
    std::string first_json;
    std::string first_asset;
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("d1", 1)));
        CHECK(autonomy.valid(), "autonomy constructs");
        PersonStub stub;
        SharedTickResult first;
        SharedTickResult second;
        for (int i = 0; i < 2; ++i) {
            FramePacket packet = MakeFrame(static_cast<std::uint32_t>(i),
                                           (100 + i * 100) * kMs);
            PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
            const SharedTickResult result = ProcessSharedTick(
                runner, autonomy, packet, MakeCtx(packet), &stub,
                packet.rgb.data(), packet.rgb.size());
            CHECK(result.autonomy_observed, "autonomy observes the tick");
            CHECK(result.assessment_artifact.write_status ==
                      EvidenceStatus::kOk,
                  "assessment seals");
            if (i == 0) {
                first = result;
            } else {
                second = result;
            }
        }
        CHECK(first.assessment.assessment_id ==
                  AutonomousProcessor::AssessmentAssetId("flt-A", "boot-A",
                                                         first.record.tick_index),
              "assessment id is the stable assess|flight|boot|tick derivation");
        CHECK(first.assessment_artifact.asset_id ==
                  first.assessment.assessment_id,
              "sealed artifact id matches the assessment id");
        CHECK(second.assessment_artifact.asset_id !=
                  first.assessment_artifact.asset_id,
              "ticks seal distinct artifacts");
        first_json = first.assessment.ToJson();
        first_asset = first.assessment_artifact.asset_id;

        StepClock clock;
        MopEdgeEndpoint endpoint("flt-A", [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        ksi::safety::EmitAssessmentArtifact(&link, first_asset, "flt-A",
                                            "boot-A", first_json);
        const std::size_t flushed = link.Flush();
        CHECK(flushed > 1, "announce + chunk(s) queued and flushed");

        MopReassembler checker;
        std::vector<std::string> messages;
        checker.Feed(reinterpret_cast<const std::uint8_t*>(transport.written.data()),
                     transport.written.size(), &messages);
        CHECK(checker.errors() == 0, "assessment stream reassembles cleanly");
        CHECK(!messages.empty(), "envelopes reassembled");

        std::size_t verified = 0;
        bool saw_announce = false;
        std::string announced_sha;
        std::int64_t announced_size = -1;
        std::string announced_media;
        std::string announced_kind;
        bool announced_required = true;
        std::vector<std::pair<std::int64_t, std::string>> chunks;
        for (const auto& message : messages) {
            JsonValue envelope;
            std::string error;
            JsonValue payload;
            CHECK(ParseJson(message, &envelope, &error),
                  "envelope parses");
            CHECK(ksi::safety::VerifyMopEnvelope(envelope, &payload, &error),
                  "envelope digest verifies");
            const JsonValue* type = envelope.Find("type");
            const JsonValue* channel = envelope.Find("channel");
            CHECK(type != nullptr && type->IsString() &&
                      TypeAllowed(type->strValue()),
                  "only pre-existing MOP types appear (no new wire type)");
            CHECK(channel != nullptr && channel->IsString() &&
                      channel->strValue() == "EVIDENCE_TRANSFER",
                  "assessment rides the existing artifact channel");
            if (type->strValue() == "ARTIFACT_ANNOUNCE") {
                CHECK(!saw_announce, "exactly one announce");
                saw_announce = true;
                CHECK(Field(payload, "artifact_id")->strValue() == first_asset,
                      "announce names the sealed artifact");
                CHECK(Field(payload, "flight_id")->strValue() == "flt-A",
                      "announce carries the flight identity");
                CHECK(Field(payload, "edge_boot_id")->strValue() == "boot-A",
                      "announce carries the boot identity");
                announced_sha = Field(payload, "sha256")->strValue();
                announced_size = Field(payload, "size_bytes")->intValue();
                announced_media = Field(payload, "media_type")->strValue();
                announced_kind = Field(payload, "source_kind")->strValue();
                announced_required =
                    Field(payload, "required")->boolValue(true);
                ++verified;
            } else if (type->strValue() == "ARTIFACT_CHUNK") {
                CHECK(Field(payload, "artifact_id")->strValue() == first_asset,
                      "chunk names the sealed artifact");
                chunks.emplace_back(Field(payload, "offset")->intValue(),
                                    Field(payload, "data_b64")->strValue());
                ++verified;
            } else {
                CHECK(false, "unexpected envelope type on the artifact plane");
            }
        }
        CHECK(saw_announce, "announce present");
        CHECK(verified == messages.size(), "every envelope accounted for");
        CHECK(announced_media == ksi::safety::kAssessmentMediaType,
              "announce media is the assessment media");
        CHECK(announced_kind == ksi::safety::kAssessmentSourceKind,
              "announce source kind is EDGE_ASSESSMENT");
        CHECK(!announced_required,
              "assessment is optional: never blocks Core COMMITTED");
        CHECK(announced_size == static_cast<std::int64_t>(first_json.size()),
              "announce size matches the sealed bytes");
        CHECK(announced_sha == ksi::safety::Sha256Hex(first_json),
              "announce sha256 matches the sealed bytes");

        // Chunks reassemble to the EXACT sealed bytes (multi-chunk: the
        // assessment JSON is larger than one 512-byte chunk).
        std::sort(chunks.begin(), chunks.end(),
                  [](const auto& a, const auto& b) { return a.first < b.first; });
        std::string reassembled;
        for (const auto& chunk : chunks) {
            CHECK(static_cast<std::int64_t>(reassembled.size()) == chunk.first,
                  "chunks are contiguous from offset 0");
            reassembled += Base64Decode(chunk.second);
        }
        CHECK(reassembled == first_json, "chunks reassemble to sealed bytes");

        // The RC-side parse: disposition, reasons, rule + model digests and
        // the zero-output honesty markers are all in the bytes.
        JsonValue parsed;
        std::string parse_error;
        CHECK(ParseJson(reassembled, &parsed, &parse_error),
              "reassembled bytes parse as JSON");
        CHECK(Field(parsed, "assessment_id")->strValue() == first_asset,
              "RC links the bytes by the deterministic assessment id");
        const std::string disposition =
            Field(parsed, "disposition")->strValue();
        CHECK(disposition == "ASSESSED" || disposition == "UNDETERMINED",
              "disposition is a closed vocabulary");
        CHECK(Field(parsed, "schema")->strValue() == "ksi.assessment/v1",
              "versioned envelope marker survives the wire");
        CHECK(Field(parsed, "mode")->strValue() == "SHADOW",
              "SHADOW-only marker survives the wire");
        const JsonValue* outputs = Field(parsed, "outputs");
        CHECK(outputs != nullptr && outputs->IsObject(), "outputs present");
        CHECK(Field(*outputs, "incidents_created")->intValue() == 0,
              "no incident created by delivery");
        CHECK(Field(*outputs, "alerts_created")->intValue() == 0,
              "no alert created by delivery");
        CHECK(Field(*outputs, "human_responses")->intValue() == 0,
              "no human response fabricated by delivery");
        CHECK(Field(*outputs, "camera_commands")->intValue() == 0,
              "no camera command created by delivery");
        CHECK(Field(*outputs, "flight_commands")->intValue() == 0,
              "no flight command created by delivery");
        const JsonValue* custody = Field(parsed, "custody");
        CHECK(custody != nullptr && custody->IsObject(), "custody present");
        CHECK(Field(*custody, "edge_sealed_locally")->boolValue(false),
              "local seal fact survives");
        CHECK(!Field(*custody, "rc_received")->boolValue(true),
              "RC receipt stays unverified on the wire (a local fact)");
        CHECK(!Field(*custody, "core_retained")->boolValue(true),
              "Core retention never claimed by the Edge");
    }

    // ---- D2: endpoint overload emits identically ---------------------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("flt-A", [&clock] { return clock(); });
        ksi::safety::EmitAssessmentArtifact(&endpoint, "assess-D2", "flt-A",
                                            "boot-A", "{\"schema\":\"x\"}");
        CHECK(endpoint.outboxSize() == 2,
              "announce + one chunk queued on the endpoint");
    }

    // ---- D3: null sinks are safe (device paths guard, never crash) ---------
    {
        ksi::safety::EmitAssessmentArtifact(
            static_cast<MopEdgeEndpoint*>(nullptr), "a", "f", "b", "{}");
        ksi::safety::EmitAssessmentArtifact(static_cast<MopLink*>(nullptr),
                                            "a", "f", "b", "{}");
    }

    // ---- D4: the emission gate — gated/dropped is never deliverable -----
    // A denied assessment is counted by the caller (service counters /
    // host log), never folded anywhere and never read as delivered; the
    // sealed store copy is the retention path. All three inputs required.
    {
        using ksi::safety::AssessmentEmitAllowed;
        using ksi::safety::AssessmentEmitGate;
        CHECK(AssessmentEmitAllowed(AssessmentEmitGate{true, true, true}),
              "confirmed + installed + writable emits");
        CHECK(!AssessmentEmitAllowed(AssessmentEmitGate{false, true, true}),
              "unconfirmed identity never emits");
        CHECK(!AssessmentEmitAllowed(AssessmentEmitGate{true, false, true}),
              "missing mission never emits (the assessment cites it)");
        CHECK(!AssessmentEmitAllowed(AssessmentEmitGate{true, true, false}),
              "unwritable spool never emits (refused, not queued)");
        CHECK(!AssessmentEmitAllowed(AssessmentEmitGate{false, false, false}),
              "nothing emits with no gate facts");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
