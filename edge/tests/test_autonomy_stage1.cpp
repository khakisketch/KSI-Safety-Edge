// Unit A — Stage 1 autonomous observation tests (plan §6, A01-A10).
//
// Exercises the passive assessment/evidence engine through the SHARED entry
// point (ProcessSharedTick -> SessionRunner::ProcessFrame + autonomy), plus
// pure-mapping, failure, identity, timing, capacity and reboot-guard legs.
// SHADOW only: every leg asserts zero incidents/alerts/human/camera/flight
// outputs. No PSDK, no clock reads inside the units (caller data only).
// Stores live under /tmp/opencode/ksi-autonomy-edge-A/ (task-local scratch).

#include "ksi_safety/observation/assessment.hpp"
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/observation/context_ring.hpp"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ksi_safety/evidence/recorder.hpp"
#include "ksi_safety/evidence/store.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"

#include "support/check.hpp"

#if defined(_WIN32)
#include <process.h>
#define AUTO_PID _getpid()
#else
#include <unistd.h>
#define AUTO_PID ::getpid()
#endif

using ksi::safety::Assessment;
using ksi::safety::AssessmentContext;
using ksi::safety::AutonomousConfig;
using ksi::safety::AutonomousCoverage;
using ksi::safety::AutonomousProcessor;
using ksi::safety::BuildAssessment;
using ksi::safety::CaptureWindow;
using ksi::safety::ContextRing;
using ksi::safety::ContextRingConfig;
using ksi::safety::Detection;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::EvidenceStatus;
using ksi::safety::EvidenceStore;
using ksi::safety::EvidenceStoreHooks;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::GeometryLevel;
using ksi::safety::IsValidAutonomousConfig;
using ksi::safety::IsValidContextRingConfig;
using ksi::safety::JsonValue;
using ksi::safety::ModelProfile;
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
using ksi::safety::SourceAsset;
using ksi::safety::SourceEvidenceRecorder;
using ksi::safety::SourceFrame;
using ksi::safety::SpatialEstimatorConfig;
using ksi::safety::TickRecord;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::Vec3;
using ksi::safety::ZoneRevision;
using ksi::safety::kMediaAssessmentV1;

namespace {

constexpr std::int64_t kMs = 1000000LL;
constexpr const char* kScratch = "/tmp/opencode/ksi-autonomy-edge-A";

std::string ScratchDir(const char* tag, int seq) {
    char path[256];
    std::snprintf(path, sizeof(path), "%s/%s-%d-%d", kScratch, tag,
                  static_cast<int>(AUTO_PID), seq);
    // Best-effort fresh dir (removed dirs from earlier runs are fine).
    char cmd[300];
    std::snprintf(cmd, sizeof(cmd), "mkdir -p %s", path);
    if (std::system(cmd) != 0) {
        std::fprintf(stderr, "scratch mkdir failed: %s\n", path);
    }
    return std::string(path);
}

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

FramePacket MakeFrame(std::uint32_t frame_id, std::int64_t recv_ns,
                      std::uint8_t fill = 0) {
    FramePacket packet;
    packet.metadata.width = 8;
    packet.metadata.height = 6;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = recv_ns;
    packet.rgb.assign(8U * 6U * 3U, fill);
    return packet;
}

FrameContext MakeCtx(const FramePacket& packet, bool exposure_verified) {
    FrameContext ctx;
    ctx.flight_id = "flt-A";
    ctx.boot_id = "boot-A";
    ctx.stream_id = "S";
    ctx.frame_id = packet.metadata.frame_id;
    ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
    ctx.has_exposure = true;
    ctx.exposure = exposure_verified
                       ? SensorTs(packet.metadata.received_monotonic_ns)
                       : HostTs(packet.metadata.received_monotonic_ns);
    if (!exposure_verified) ctx.exposure.verified = false;
    return ctx;
}

SpatialEstimatorConfig SpatialCfg() {
    SpatialEstimatorConfig config;
    config.classes.person_class_id = 0;
    config.intrinsics.fx = 5.0;
    config.intrinsics.fy = 5.0;
    config.intrinsics.cx = 4.0;
    config.intrinsics.cy = 3.0;
    config.intrinsics.ref_width = 8;
    config.intrinsics.ref_height = 6;
    config.ground.up_m = 0.0;
    config.min_ray_z = 0.05;
    return config;
}

ZoneRevision ZoneAroundFootpoint() {
    // Person footpoint lands at ENU (0,-6) for the stub box below (nadir
    // closed form); the zone contains it.
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
    return zone;
}

RiskRule ZoneRule(GeometryLevel required) {
    RiskRule rule;
    rule.rule_id = "zone-intrusion-1";
    rule.kind = RuleKind::kZoneIntrusion;
    rule.mode = RuleMode::kShadow;
    rule.required_geometry_level = required;
    return rule;
}

RunnerConfig SpatialRunnerCfg() {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = 1;
    config.enable_spatial = true;
    config.spatial = SpatialCfg();
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
    config.simulated_input = true;  // [SIMULATED] stub detector input
    config.window_min_interval_ns = 0;  // trigger-shape legs: gate on events
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

class BlankStub : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket&) override {
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;  // clean negative
        return batch;
    }
};

class FailStub : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket&) override {
        DetectionBatch batch;
        batch.status = DetectionStatus::kInferenceFailure;
        return batch;
    }
};

void InstallZone(SessionRunner* runner, GeometryLevel required) {
    std::vector<RiskRule> zone;
    zone.push_back(ZoneRule(required));
    std::vector<ZoneRevision> zones;
    zones.push_back(ZoneAroundFootpoint());
    CHECK(runner
              ->ApplyRuntimeConfig({}, zone, zones, "hash-zone-A", {})
              .ok,
          "zone install holds");
    runner->RequestStart();
}

bool HasStr(const std::vector<std::string>& list, const std::string& want) {
    for (const auto& item : list) {
        if (item == want) return true;
    }
    return false;
}

bool JsonHas(const std::string& json, const std::string& needle) {
    return json.find(needle) != std::string::npos;
}

bool AssessmentJsonParses(const std::string& json, JsonValue* out) {
    std::string error;
    if (!ParseJson(json, out, &error)) return false;
    return out->IsObject();
}

}  // namespace

int main() {
    // ---- T0 config validation: finite explicit bounds -------------------
    {
        AutonomousConfig bad;
        std::string error;
        CHECK(!IsValidAutonomousConfig(bad, &error) && !error.empty(),
              "empty config rejected");
        AutonomousConfig cfg = AutoCfg(ScratchDir("cfg", 0));
        cfg.shadow_only = false;
        CHECK(!IsValidAutonomousConfig(cfg, &error),
              "non-SHADOW rejected (unit A is SHADOW-only)");
        cfg = AutoCfg(ScratchDir("cfg", 1));
        cfg.ring.max_frames = 2;  // pre(2)+1+post(2) = 5 > 2
        CHECK(!IsValidAutonomousConfig(cfg, &error),
              "ring smaller than a window span rejected");
        cfg = AutoCfg(ScratchDir("cfg", 2));
        cfg.enabled = false;
        AutonomousProcessor disabled(cfg);
        CHECK(disabled.valid(), "disabled-by-default config still constructs");
        CHECK(!disabled.enabled(), "default processor is opt-in (off)");
        ContextRingConfig ring_bad;
        CHECK(!IsValidContextRingConfig(ring_bad),
              "zero ring bounds rejected");
    }

    // ---- T1 (A01): shared path, assessed zone candidate, sealed --------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a01", 1)));
        CHECK(autonomy.valid() && autonomy.enabled(), "autonomy constructs");
        PersonStub stub;
        // detect, blank, blank, detect, blank, blank: windows see pre/post
        // blanks around each trigger.
        DetectorPort* plan[6] = {&stub, nullptr, nullptr,
                                 &stub, nullptr, nullptr};
        BlankStub blank;
        for (int i = 0; i < 6; ++i) {
            DetectorPort* det = plan[i] != nullptr ? plan[i] : &blank;
            FramePacket packet =
                MakeFrame(static_cast<std::uint32_t>(i), (100 + i) * kMs,
                          static_cast<std::uint8_t>(i + 1));
            PushNadirPose(&runner,
                          packet.metadata.received_monotonic_ns);
            const SharedTickResult result = ProcessSharedTick(
                runner, autonomy, packet, MakeCtx(packet, true),
                det, packet.rgb.data(), packet.rgb.size());
            CHECK(result.runner_processed, "shared tick processed");
            CHECK(result.autonomy_observed, "autonomy observed the tick");
        }
        CHECK(autonomy.coverage().processed_ticks == 6, "six ticks observed");
        CHECK(autonomy.coverage().assessments_written == 6,
              "six assessment artifacts");
        CHECK(autonomy.coverage().assessments_assessed >= 1,
              "at least the trigger ticks assessed");
        CHECK(runner.processed_frame_count() == 6, "runner counted six");
        // The second trigger (tick 3) has full pre/post history: ticks 1,2
        // before and 4,5 after, all present including zero-detection ones.
        {
            CaptureWindow window;
            CHECK(autonomy.FindWindowForTrigger(3, &window),
                  "window opened on second trigger");
            CHECK(window.closed, "window closed by post frames");
            CHECK(window.complete, "full pre/post window is complete");
            CHECK(window.present_frames == window.wanted_frames,
                  "every wanted frame present (blanks included)");
            bool saw_blank = false;
            for (const auto& ref : window.before) {
                if (ref.present && !ref.has_detection) saw_blank = true;
            }
            for (const auto& ref : window.after) {
                if (ref.present && !ref.has_detection) saw_blank = true;
            }
            CHECK(saw_blank, "pre/post context keeps zero-detection frames");
        }
        // The first trigger (tick 0) can have no prehistory: explicit gap.
        {
            CaptureWindow early;
            CHECK(autonomy.FindWindowForTrigger(0, &early),
                  "window opened on first trigger");
            CHECK(HasStr(early.missing_reasons, ksi::safety::kGapPrehistory),
                  "early-start prehistory gap is explicit");
            CHECK(!early.complete,
                  "prehistory gap means partial, not complete");
        }
        autonomy.RequestStop(800 * kMs);
        std::string revision_id, manifest, error;
        CHECK(autonomy.Seal(&revision_id, &manifest, &error),
              "seal succeeds over autonomy artifacts");
        CHECK(autonomy.VerifyAll(&error), "every artifact re-verifies");
        CHECK(!revision_id.empty() && !manifest.empty(), "seal has identity");
        CHECK(JsonHas(manifest, revision_id), "manifest names the revision");
        // Exact collector media for the assessment artifact.
        bool saw_assessment_media = false;
        for (const auto& artifact : autonomy.artifacts()) {
            if (artifact.media_type == kMediaAssessmentV1) {
                saw_assessment_media = true;
                CHECK(artifact.media_type ==
                          std::string(
                              "application/vnd.ksi.assessment+json"),
                      "collector assessment media is exact");
            }
        }
        CHECK(saw_assessment_media, "assessment artifacts use vnd media");
    }

    // ---- T2 (A02): withheld geometry stays undetermined, never risk -----
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kMetricVerified);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a02", 2)));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 7);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        const SharedTickResult result = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        CHECK(result.runner_processed, "withheld tick still processed");
        CHECK(result.assessment.disposition ==
                  ksi::safety::AssessmentDisposition::kUndetermined,
              "insufficient geometry is UNDETERMINED, not risk");
        CHECK(HasStr(result.assessment.missing_reasons,
                     ksi::safety::kMissingGeometryInsufficient),
              "withheld reason names geometry insufficiency");
        CHECK(result.assessment.incidents_created == 0 &&
                  result.assessment.alerts_created == 0 &&
                  result.assessment.human_responses == 0,
              "withheld verdict creates no incident/alert/human response");
        CHECK(HasStr(result.assessment.observed_conditions,
                     ksi::safety::kObservedDetections),
              "detections kept as observations while spatial is withheld");
    }

    // ---- T3 (A03): SHADOW duplicates are idempotent, never incidents ----
    {
        const std::string dir = ScratchDir("a03", 3);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(dir));
        PersonStub stub;
        for (int i = 0; i < 3; ++i) {
            FramePacket packet = MakeFrame(0, (100 + i) * kMs, 9);
            PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
            const SharedTickResult result = ProcessSharedTick(
                runner, autonomy, packet, MakeCtx(packet, true), &stub,
                packet.rgb.data(), packet.rgb.size());
            CHECK(result.assessment.mode == std::string("SHADOW"),
                  "assessment stays SHADOW");
            CHECK(result.assessment.incidents_created == 0 &&
                      result.assessment.alerts_created == 0,
                  "SHADOW candidate never becomes incident/alert");
        }
        // Reopen the same dir (restart): retained bytes, idempotent writes.
        AutonomousProcessor reopened(AutoCfg(dir));
        CHECK(reopened.valid(), "reopen keeps the store usable");
        std::string reopen_error;
        CHECK(reopened.VerifyAll(&reopen_error),
              "reopened store verifies retained bytes");
    }

    // ---- T4 (A04): clean negative vs gap -------------------------------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a04", 4)));
        BlankStub blank;
        FailStub fail;
        FramePacket neg = MakeFrame(0, 100 * kMs, 3);
        PushNadirPose(&runner, neg.metadata.received_monotonic_ns);
        const SharedTickResult neg_result = ProcessSharedTick(
            runner, autonomy, neg, MakeCtx(neg, true), &blank, neg.rgb.data(),
            neg.rgb.size());
        CHECK(neg_result.assessment.disposition ==
                  ksi::safety::AssessmentDisposition::kAssessed,
              "clean negative is assessed (not a gap)");
        CHECK(HasStr(neg_result.assessment.observed_conditions,
                     ksi::safety::kObservedCleanNegative),
              "clean negative named as such");
        FramePacket gap = MakeFrame(1, 200 * kMs, 4);
        PushNadirPose(&runner, gap.metadata.received_monotonic_ns);
        const SharedTickResult gap_result = ProcessSharedTick(
            runner, autonomy, gap, MakeCtx(gap, true), &fail, gap.rgb.data(),
            gap.rgb.size());
        CHECK(gap_result.assessment.disposition ==
                  ksi::safety::AssessmentDisposition::kUndetermined,
              "inference failure is UNDETERMINED, never a negative");
        CHECK(HasStr(gap_result.assessment.missing_reasons,
                     ksi::safety::kMissingDetectionFailure),
              "failure reason is explicit");
        // A cadence skip (kNotRun) is a third, distinct state.
        RunnerConfig cadence = SpatialRunnerCfg();
        cadence.detect_every_n_frames = 2;
        SessionRunner runner2(cadence, SessionCfg(), {}, {}, {},
                              StubProfile());
        InstallZone(&runner2, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy2(AutoCfg(ScratchDir("a04b", 4)));
        FramePacket skip = MakeFrame(9, 900 * kMs, 5);
        PushNadirPose(&runner2, skip.metadata.received_monotonic_ns);
        // Tick 0 runs; tick 1 with the same runner skips by cadence.
        FramePacket first = MakeFrame(8, 800 * kMs, 5);
        PushNadirPose(&runner2, first.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner2, autonomy2, first,
                                MakeCtx(first, true), &blank,
                                first.rgb.data(), first.rgb.size());
        const SharedTickResult skip_result = ProcessSharedTick(
            runner2, autonomy2, skip, MakeCtx(skip, true), &blank,
            skip.rgb.data(), skip.rgb.size());
        CHECK(skip_result.assessment.detection_status ==
                  std::string("NOT_RUN"),
              "cadence skip reads NOT_RUN (not a negative)");
    }

    // ---- T5 (A05): deadline closes windows with no new frames ----------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a05", 5)));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 6);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        const SharedTickResult result = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        CHECK(result.window_opened, "trigger opens a window");
        CHECK(autonomy.HasOpenWindows(),
              "window stays open while post frames are pending");
        // No more frames: the deadline (500ms) closes it via HealthTick.
        const std::vector<std::string> closed =
            autonomy.HealthTick(100 * kMs + 600 * kMs);
        CHECK(!closed.empty(), "deadline closes the window without frames");
        CaptureWindow window;
        CHECK(autonomy.FindWindow(closed[0], &window) && window.closed,
              "closed window retrievable");
        CHECK(!window.complete, "post-missing window is partial, not clean");
        CHECK(HasStr(window.missing_reasons,
                     ksi::safety::kGapDeadlinePostIncomplete),
              "deadline incompleteness is explicit");
        // Starvation with no ticks at all: no hang, no phantom windows.
        AutonomousProcessor idle(AutoCfg(ScratchDir("a05b", 5)));
        CHECK(idle.HealthTick(10 * kMs).empty(), "idle health closes nothing");
        CHECK(idle.coverage().processed_ticks == 0, "idle observed nothing");
    }

    // ---- T6 (A06): manual camera withholds spatial only ----------------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousConfig cfg = AutoCfg(ScratchDir("a06", 6));
        AutonomousProcessor autonomy(cfg);
        autonomy.SetCameraManualOverride(true);
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 8);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        const SharedTickResult result = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        CHECK(result.runner_processed, "manual camera never stops analysis");
        CHECK(result.assessment.analysis_continues,
              "assessment says analysis continues");
        CHECK(HasStr(result.assessment.missing_reasons,
                     ksi::safety::kMissingCameraManualSpatial),
              "spatial withheld under manual camera");
        CHECK(HasStr(result.assessment.observed_conditions,
                     ksi::safety::kObservedDetections),
              "detections still recorded under manual camera");
        CHECK(result.assessment.camera_commands == 0 &&
                  result.assessment.flight_commands == 0,
              "no camera/flight output invented");
    }

    // ---- T7 (A07): capacity saturation is explicit, sticky and NON-fatal --
    {
        const std::string dir = ScratchDir("a07", 7);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousConfig cfg = AutoCfg(dir);
        cfg.store_capacity_bytes = 1400;  // first assessment fits, rest fails
        cfg.seal_reserve_bytes = 128;     // test-scale seal headroom
        AutonomousProcessor autonomy(cfg);
        CHECK(autonomy.valid(), "tiny store still opens");
        PersonStub stub;
        BlankStub blank;
        for (int i = 0; i < 5; ++i) {
            FramePacket packet = MakeFrame(static_cast<std::uint32_t>(i),
                                           (100 + i) * kMs, 11);
            PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
            (void)ProcessSharedTick(
                runner, autonomy, packet, MakeCtx(packet, true),
                i == 0 ? static_cast<DetectorPort*>(&stub)
                       : static_cast<DetectorPort*>(&blank),
                packet.rgb.data(), packet.rgb.size());
        }
        CHECK(autonomy.hasEvidenceSaturation(),
              "store overflow is reported as saturation");
        CHECK(!autonomy.hasWriteFailure(),
              "capacity is not an integrity/write fault");
        CHECK(autonomy.coverage().evidence_saturated &&
                  autonomy.coverage().evidence_refusals > 0,
              "coverage records the gap and counts refusals");
        // The run is visibly degraded but KEEPS observing: capacity is a
        // bounded end state of an optional record, not lost flight evidence.
        CHECK(runner.Snapshot().state ==
                  ksi::safety::RuntimeState::kDegraded,
              "saturated evidence still degrades the reported run");
        bool degraded_saturation = false;
        for (const auto& reason : runner.Snapshot().degraded_reasons) {
            if (reason == "autonomy_evidence_saturated") {
                degraded_saturation = true;
            }
        }
        CHECK(degraded_saturation,
              "facts name the autonomy gap, not session_capacity_exceeded");
        CHECK(!runner.hasDurableFailure(),
              "capacity never pauses observation work");
        const std::uint64_t processed = runner.processed_frame_count();
        FramePacket next = MakeFrame(99, 900 * kMs, 13);
        PushNadirPose(&runner, next.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner, autonomy, next,
                                MakeCtx(next, true), &blank,
                                next.rgb.data(), next.rgb.size());
        CHECK(runner.processed_frame_count() == processed + 1,
              "observation continues after saturation");
    }
    // ---- T7b (A07): integrity faults stay fatal (unchanged) --------------
    {
        AutonomousConfig fail_cfg = AutoCfg(ScratchDir("a07b", 7));
        fail_cfg.store_hooks.fsync = [](int) { return -1; };
        AutonomousProcessor failing(fail_cfg);
        CHECK(failing.valid(), "failing store opens (fails on write)");
        CHECK(!failing.hasWriteFailure(), "no failure before any write");
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 14);
        (void)ProcessSharedTick(runner, failing, packet, MakeCtx(packet, true),
                                &stub, packet.rgb.data(), packet.rgb.size());
        CHECK(failing.hasWriteFailure(), "I/O refusal is a write failure");
        CHECK(!failing.hasEvidenceSaturation(),
              "integrity faults are not relabelled as capacity");
        CHECK(runner.hasDurableFailure(),
              "integrity fault still pauses new work (fail closed)");
        const std::uint64_t processed = runner.processed_frame_count();
        FramePacket next = MakeFrame(1, 200 * kMs, 15);
        (void)ProcessSharedTick(runner, failing, next, MakeCtx(next, true),
                                &stub, next.rgb.data(), next.rgb.size());
        CHECK(runner.processed_frame_count() == processed,
              "paused run stays paused for integrity faults");
    }

    // ---- T8 (A08): stop/abort/restart guards ---------------------------
    {
        const std::string dir = ScratchDir("a08", 8);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(dir));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 12);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner, autonomy, packet,
                                MakeCtx(packet, true), &stub,
                                packet.rgb.data(), packet.rgb.size());
        autonomy.RequestStop(200 * kMs);
        bool saw_stop_close = false;
        for (const auto& window : autonomy.windows()) {
            if (window.closed && window.close_reason == "session_stop") {
                saw_stop_close = true;
            }
        }
        CHECK(saw_stop_close, "stop closes open windows (named)");
        std::string revision_id, manifest, error;
        CHECK(autonomy.Seal(&revision_id, &manifest, &error),
              "closeout seals (coverage + windows + assessments)");
        CHECK(autonomy.VerifyAll(&error), "sealed bytes verify");
        // Abort parks: no auto-resume, no silent processing.
        autonomy.AbortRuntime();
        CHECK(autonomy.parked(), "abort parks the processor");
        FramePacket late = MakeFrame(1, 300 * kMs, 13);
        PushNadirPose(&runner, late.metadata.received_monotonic_ns);
        const TickRecord late_record =
            runner.ProcessFrame(late, MakeCtx(late, true), &stub);
        const SharedTickResult parked =
            autonomy.OnTick(late_record, late.rgb.data(), late.rgb.size());
        CHECK(!parked.autonomy_observed, "parked processor observes nothing");
        // Reboot: a fresh processor on the same dir retains old bytes and
        // re-writes idempotently (same ids, same bytes => duplicates).
        AutonomousProcessor rebooted(AutoCfg(dir));
        CHECK(rebooted.valid(), "reboot reopens the same store");
        CHECK(rebooted.VerifyAll(&error), "old bytes retained across reboot");
        // Empty seal is refused honestly (never an empty revision).
        AutonomousProcessor fresh(AutoCfg(ScratchDir("a08b", 8)));
        CHECK(!fresh.Seal(&revision_id, &manifest, &error) &&
                  !error.empty(),
              "sealing nothing fails honestly");
    }

    // ---- T9 (A09): digest conflict refused, original intact ------------
    {
        const std::string dir = ScratchDir("a09", 9);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(dir));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 14);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner, autonomy, packet,
                                MakeCtx(packet, true), &stub,
                                packet.rgb.data(), packet.rgb.size());
        CHECK(!autonomy.artifacts().empty(),
              "assessment artifact was persisted");
        const std::string asset_id =
            AutonomousProcessor::AssessmentAssetId("flt-A", "boot-A", 0);
        // A second writer with DIFFERENT bytes under the same id conflicts:
        // the declared digest must match the rival bytes (else it is merely
        // invalid), while the stored bytes differ.
        EvidenceStore rival(dir, 8 << 20);
        CHECK(rival.ok(), "rival handle opens the same dir");
        SourceAsset conflict;
        conflict.asset_id = asset_id;
        const std::uint8_t other[1] = {0xFF};
        conflict.sha256_hex = ksi::safety::Sha256Hex(other, 1);
        conflict.size_bytes = 1;
        conflict.media_type = kMediaAssessmentV1;
        conflict.flight_id = "flt-A";
        conflict.edge_boot_id = "boot-A";
        CHECK(rival.PutSourceAsset(conflict, other, 1) ==
                  EvidenceStatus::kConflict,
              "same id + different bytes is a conflict (refused)");
        std::string error;
        CHECK(autonomy.VerifyAll(&error), "original bytes still verify");
    }

    // ---- T10 (A10): honesty markers, no RC/Core masquerade -------------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a10", 10)));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 15);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        const SharedTickResult result = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        JsonValue parsed;
        CHECK(AssessmentJsonParses(result.assessment.ToJson(), &parsed),
              "assessment is machine-readable JSON");
        const std::string json = result.assessment.ToJson();
        CHECK(JsonHas(json, "\"schema\":\"ksi.assessment/v1\""),
              "versioned envelope schema marker");
        CHECK(JsonHas(json, "\"simulated_input\":true"),
              "[SIMULATED] input honestly marked");
        CHECK(JsonHas(json, "\"rc_receipt\":\"unverified\""),
              "RC receipt stays unverified on host-only runs");
        CHECK(JsonHas(json, "\"core_retained\":false"),
              "Core retention never claimed by the edge");
        CHECK(!JsonHas(json, "operator-response") &&
                  !JsonHas(json, "human_ack") &&
                  !JsonHas(json, "MISSION_"),
              "no operator response / RC facts invented or linked");
        CHECK(!JsonHas(json, "ACTIVE"), "no ACTIVE promotion anywhere");
        // Time grade is receive-grade here? No: exposure verified above.
        CHECK(JsonHas(json, "\"time_grade\":\"exposure_verified\""),
              "verified exposure time grade recorded");
        // Without exposure verification the grade never upgrades.
        FramePacket noexp = MakeFrame(1, 200 * kMs, 16);
        PushNadirPose(&runner, noexp.metadata.received_monotonic_ns);
        const SharedTickResult noexp_result = ProcessSharedTick(
            runner, autonomy, noexp, MakeCtx(noexp, false), &stub,
            noexp.rgb.data(), noexp.rgb.size());
        CHECK(JsonHas(noexp_result.assessment.ToJson(),
                      "\"time_grade\":\"receive_only\""),
              "unverified capture stays receive-grade (no upgrade)");
    }

    // ---- T11 shared-runtime gate: no start, no processing ---------------
    {
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        runner.RequestStop(0);  // gate closed
        AutonomousProcessor autonomy(AutoCfg(ScratchDir("a11", 11)));
        PersonStub stub;
        FramePacket packet = MakeFrame(0, 100 * kMs, 17);
        const std::uint64_t before = runner.processed_frame_count();
        const SharedTickResult dropped = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        CHECK(!dropped.runner_processed, "closed gate drops the frame");
        CHECK(autonomy.coverage().processed_ticks == 0,
              "autonomy invents no tick for a dropped frame");
        CHECK(runner.processed_frame_count() == before,
              "runner counter untouched by a dropped frame");
    }

    // ---- T12 determinism: identical inputs, identical JSON --------------
    {
        TickRecord record;
        record.tick_index = 3;
        record.frame_id = 9;
        record.receive_mono_ns = 300 * kMs;
        record.detection_status = DetectionStatus::kOk;
        AssessmentContext ctx;
        ctx.flight_id = "F";
        ctx.edge_boot_id = "B";
        const Assessment first = BuildAssessment(record, ctx);
        const Assessment second = BuildAssessment(record, ctx);
        CHECK(first.ToJson() == second.ToJson(), "assessment is pure");
        CHECK(first.assessment_id == second.assessment_id,
              "assessment id is a stable derivation");
    }

    // ---- T13 legacy recorder policy retained ----------------------------
    {
        SourceEvidenceRecorder recorder(ScratchDir("legacy", 13), 0);
        CHECK(recorder.ok(), "legacy recorder opens");
        TickRecord blank;
        blank.tick_index = 1;
        blank.detection_status = DetectionStatus::kOk;  // clean negative
        const std::vector<std::uint8_t> bytes = {1, 2, 3};
        SourceFrame frame;
        frame.flight_id = "F";
        frame.edge_boot_id = "B";
        frame.frame_id = 1;
        frame.received_monotonic_ns = 100;
        frame.rgb = bytes.data();
        frame.rgb_size = bytes.size();
        CHECK(recorder.Capture(blank, frame) == EvidenceStatus::kInvalid,
              "legacy policy still refuses zero-detection frames");
        CHECK(recorder.captured_count() == 0, "legacy store keeps nothing");
    }

    // ---- T15 lens switch withholds one spatial tick, ids never merge ----
    {
        AssessmentContext ctx;
        ctx.flight_id = "F";
        ctx.edge_boot_id = "B";
        ctx.lens_switch_observed = true;
        TickRecord record;
        record.tick_index = 5;
        record.detection_status = DetectionStatus::kOk;
        Detection det;
        det.class_id = 0;
        record.detections.push_back(det);
        const Assessment switched = BuildAssessment(record, ctx);
        CHECK(switched.disposition ==
                  ksi::safety::AssessmentDisposition::kUndetermined,
              "lens-switch tick withholds the spatial conclusion");
        CHECK(HasStr(switched.missing_reasons,
                     ksi::safety::kMissingLensSwitch),
              "lens-switch reason recorded");
        AssessmentContext other = ctx;
        other.flight_id = "F2";
        other.lens_switch_observed = false;
        const Assessment moved = BuildAssessment(record, other);
        CHECK(moved.assessment_id != switched.assessment_id,
              "identity change never merges lineages");
    }

    // ---- T16 early start: prehistory gap is explicit --------------------
    {
        const std::string dir = ScratchDir("a16", 16);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousConfig cfg = AutoCfg(dir);
        AutonomousProcessor autonomy(cfg);
        PersonStub stub;
        // First-ever tick triggers immediately: no prehistory exists.
        FramePacket packet = MakeFrame(0, 100 * kMs, 18);
        PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
        const SharedTickResult result = ProcessSharedTick(
            runner, autonomy, packet, MakeCtx(packet, true), &stub,
            packet.rgb.data(), packet.rgb.size());
        CHECK(result.window_opened, "first tick opens a window");
        CaptureWindow window;
        CHECK(autonomy.FindWindow(result.window_id, &window),
              "window retrievable");
        CHECK(HasStr(window.missing_reasons, ksi::safety::kGapPrehistory),
              "early-start prehistory gap is explicit");
        CHECK(!window.complete, "prehistory gap means partial, not complete");
    }

    // ---- T17 event gating: one window per event, not per frame --------
    {
        const std::string dir = ScratchDir("a17", 17);
        SessionRunner runner(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                             StubProfile());
        InstallZone(&runner, GeometryLevel::kPixelOnly);
        AutonomousConfig cfg = AutoCfg(dir);
        cfg.window_min_interval_ns = 0;
        AutonomousProcessor autonomy(cfg);
        PersonStub stub;
        BlankStub blank;
        // Six consecutive detection ticks of the same stable track: the
        // first opens a window, the rest are the same event (no new window).
        int opened = 0;
        for (int i = 0; i < 6; ++i) {
            FramePacket packet = MakeFrame(static_cast<std::uint32_t>(i),
                                           (100 + i) * kMs, 19);
            PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
            const SharedTickResult result = ProcessSharedTick(
                runner, autonomy, packet, MakeCtx(packet, true), &stub,
                packet.rgb.data(), packet.rgb.size());
            if (result.window_opened) ++opened;
        }
        CHECK(opened == 1, "continuous detection opens exactly one window");
        CHECK(autonomy.coverage().windows_opened == 1, "coverage counts one");
        // A return after blanks is a new event: previous tick had no fresh
        // confirmed lineage.
        for (int i = 6; i < 8; ++i) {
            FramePacket packet = MakeFrame(static_cast<std::uint32_t>(i),
                                           (100 + i) * kMs, 20);
            PushNadirPose(&runner, packet.metadata.received_monotonic_ns);
            (void)ProcessSharedTick(runner, autonomy, packet,
                                    MakeCtx(packet, true), &blank,
                                    packet.rgb.data(), packet.rgb.size());
        }
        FramePacket back = MakeFrame(8, 108 * kMs, 21);
        PushNadirPose(&runner, back.metadata.received_monotonic_ns);
        const SharedTickResult revived = ProcessSharedTick(
            runner, autonomy, back, MakeCtx(back, true), &stub,
            back.rgb.data(), back.rgb.size());
        CHECK(revived.window_opened, "return after a gap is a new event");
        CHECK(autonomy.coverage().windows_opened == 2, "two events, two windows");
        // Inference-failure rising edge opens; a sustained failure does not
        // repeat.
        FailStub fail;
        FramePacket f1 = MakeFrame(9, 109 * kMs, 22);
        PushNadirPose(&runner, f1.metadata.received_monotonic_ns);
        CHECK(ProcessSharedTick(runner, autonomy, f1, MakeCtx(f1, true), &fail,
                                f1.rgb.data(), f1.rgb.size())
                  .window_opened,
              "failure rising edge opens a window");
        FramePacket f2 = MakeFrame(10, 110 * kMs, 23);
        PushNadirPose(&runner, f2.metadata.received_monotonic_ns);
        CHECK(!ProcessSharedTick(runner, autonomy, f2, MakeCtx(f2, true),
                                 &fail, f2.rgb.data(), f2.rgb.size())
                   .window_opened,
              "sustained failure opens nothing new");
        // Interval bound merges rapid re-triggers instead of writing more.
        AutonomousConfig cfg2 = AutoCfg(ScratchDir("a17b", 17));
        cfg2.window_min_interval_ns = 1000 * kMs;
        AutonomousProcessor paced(cfg2);
        SessionRunner runner2(SpatialRunnerCfg(), SessionCfg(), {}, {}, {},
                              StubProfile());
        InstallZone(&runner2, GeometryLevel::kPixelOnly);
        FramePacket p0 = MakeFrame(0, 100 * kMs, 24);
        PushNadirPose(&runner2, p0.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner2, paced, p0, MakeCtx(p0, true), &stub,
                                p0.rgb.data(), p0.rgb.size());
        FramePacket b1 = MakeFrame(1, 200 * kMs, 25);
        PushNadirPose(&runner2, b1.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner2, paced, b1, MakeCtx(b1, true), &blank,
                                b1.rgb.data(), b1.rgb.size());
        FramePacket p2 = MakeFrame(2, 300 * kMs, 26);
        PushNadirPose(&runner2, p2.metadata.received_monotonic_ns);
        CHECK(!ProcessSharedTick(runner2, paced, p2, MakeCtx(p2, true), &stub,
                                 p2.rgb.data(), p2.rgb.size())
                   .window_opened,
              "re-trigger inside the interval opens nothing");
        CHECK(paced.coverage().windows_merged == 1,
              "merged re-trigger is counted, not silently dropped");
        FramePacket p3 = MakeFrame(3, 1200 * kMs, 27);
        PushNadirPose(&runner2, p3.metadata.received_monotonic_ns);
        (void)ProcessSharedTick(runner2, paced, p3, MakeCtx(p3, true), &blank,
                                p3.rgb.data(), p3.rgb.size());
        FramePacket p4 = MakeFrame(4, 1300 * kMs, 28);
        PushNadirPose(&runner2, p4.metadata.received_monotonic_ns);
        CHECK(ProcessSharedTick(runner2, paced, p4, MakeCtx(p4, true), &stub,
                                p4.rgb.data(), p4.rgb.size())
                  .window_opened,
              "re-trigger after the interval opens again");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
