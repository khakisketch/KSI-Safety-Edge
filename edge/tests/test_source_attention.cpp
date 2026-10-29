// [SIMULATED] actual shared C++ worker path + real task-local filesystem.
// No DJI session, model accuracy, PPE verification, actuation or field approval.
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "support/check.hpp"
#include "source_context.hpp"

#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <string>
#include <vector>
#include <unistd.h>

using namespace ksi::safety;
namespace {
constexpr std::int64_t kMs = 1000000;
std::string NewDir() {
    std::filesystem::create_directories("/tmp/opencode/source-attention-tests");
    char path[] = "/tmp/opencode/source-attention-tests/run-XXXXXX";
    char* result = ::mkdtemp(path);
    CHECK(result != nullptr, "unique scratch directory");
    return result == nullptr ? "" : result;
}
AutonomousConfig Config() {
    AutonomousConfig cfg;
    cfg.enabled = true;
    cfg.flight_id = "sim-flight";
    cfg.edge_boot_id = "sim-boot";
    cfg.stream_id = "sim-stream";
    cfg.simulated_input = true;
    cfg.evidence_dir = NewDir();
    cfg.store_capacity_bytes = 16 * 1024 * 1024;
    cfg.ring.max_frames = 2;
    cfg.ring.max_bytes = 1024 * 1024;
    cfg.ring.pre_frames = 0;
    cfg.ring.post_frames = 0;
    cfg.ring.window_deadline_ns = 500 * kMs;
    std::string error;
    CHECK(ParseSourceAttentionPolicy("class=0,targets=4,jobs=1,bytes=128,repeat_ms=5000,lost_ms=1000,wait_ms=2000,retry_ms=500",
        &cfg.source_attention, &error), "simulated policy");
    return cfg;
}
TickRecord Record(std::uint64_t tick, std::int64_t ns) {
    TickRecord r;
    r.tick_index = tick;
    r.frame_id = static_cast<std::uint32_t>(tick);
    r.stream_id = "sim-stream";
    r.model_id = "simulated-detector";
    r.model_digest = "not-a-real-model";
    r.receive_mono_ns = ns;
    r.detection_status = DetectionStatus::kOk;
    Detection d;
    d.class_id = 0;
    d.score = .9F;
    r.detections.push_back(d);
    TrackRecord t;
    t.track.track_id = 1;
    t.track.epoch = 1;
    t.track.class_id = 0;
    t.track.confirmed = true;
    t.track.last_seen_ns = static_cast<std::uint64_t>(ns);
    t.track.box = {1, 1, 4, 3};  // original pixel coordinates; 18 RGB bytes
    t.provenance = TrackProvenance::kObserved;
    r.tracks.push_back(t);
    return r;
}
std::vector<std::uint8_t> Pixels() {
    std::vector<std::uint8_t> bytes(5 * 4 * 3);
    for (std::size_t i = 0; i < bytes.size(); ++i) bytes[i] = static_cast<std::uint8_t>(i);
    return bytes;
}
std::string ReadAsset(const AutonomousConfig& cfg, const ArtifactInfo& info) {
    std::ifstream file(cfg.evidence_dir + "/assets/" + info.asset_id, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}
JsonValue ReadReport(const AutonomousConfig& cfg, const ArtifactInfo& info) {
    JsonValue root;
    std::string error;
    CHECK(ParseJson(ReadAsset(cfg, info), &root, &error), "persisted JSON parses");
    return root;
}
const JsonValue& Field(const JsonValue& root, const char* name) {
    static JsonValue absent;
    const auto* found = root.Find(name);
    CHECK(found != nullptr, "required report field");
    return found ? *found : absent;
}
class Detector final : public DetectorPort {
 public:
    DetectionStatus status = DetectionStatus::kOk;
    std::size_t calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        ++calls;
        DetectionBatch batch;
        batch.status = status;
        if (status == DetectionStatus::kOk) {
            Detection d;
            d.class_id = 0;
            d.score = .95F;
            d.x_min = 1;
            d.y_min = 1;
            d.x_max = 4;
            d.y_max = 3;
            batch.detections.push_back(d);
        }
        return batch;
    }
};
}  // namespace

int main() {
    auto rgb = Pixels();
    {
        auto cfg = Config();
        AutonomousProcessor processor(cfg);
        CHECK(processor.valid(), "durable bounded processor valid");
        if (!processor.valid()) {
            std::fprintf(stderr, "processor config error: %s\n", processor.error().c_str());
            return 1;
        }
        auto record = Record(1, 100 * kMs);
        const auto result = processor.OnTick(record, rgb.data(), rgb.size(), 5, 4);
        CHECK(result.attention_plan.ok && result.attention_plan.jobs.size() == 1, "real source job selected");
        CHECK(result.attention_crops.size() == 1 && result.attention_crops[0].write_status == EvidenceStatus::kOk,
              "crop persisted through EvidenceStore");
        if (!result.attention_crops.empty()) {
            const auto& info = result.attention_crops.front();
            const auto bytes = ReadAsset(cfg, info);
            std::string expected;
            expected.append(reinterpret_cast<const char*>(rgb.data() + 18), 9);
            expected.append(reinterpret_cast<const char*>(rgb.data() + 33), 9);
            CHECK(bytes == expected && info.sha256_hex == Sha256Hex(expected), "byte-exact original rows and digest");
            CHECK(info.media_type == kMediaSourceRoiV1 && info.media_type != kMediaSourceFrame,
                  "crop cannot masquerade as full frame");
        }
        const auto report = ReadReport(cfg, result.attention_artifact);
        CHECK(Field(report, "schema").strValue() == "ksi.source-attention/v1", "separate internal version");
        CHECK(Field(report, "source_rgb_sha256").strValue() == Sha256Hex(rgb.data(), rgb.size()), "original frame digest provenance");
        CHECK(Field(report, "source_rgb_digest_origin").strValue() == "same_tick_committed_source_frame",
              "reuse only already committed current-frame digest");
        CHECK(Field(report, "ppe_verification").strValue() == "UNDETERMINED", "capture is not verification");
        CHECK(Field(report, "time_grade").strValue() == "receive_only", "no invented exposure time");
        CHECK(Field(report, "simulated_input").boolValue(), "simulated evidence labelled");
        for (const auto& output : Field(report, "outputs").objectValue()) {
            CHECK(output.second.intValue(-1) == 0, "zero actuation/alert/incident/human output");
        }
        CHECK(ReadAsset(cfg, result.assessment_artifact) == result.assessment.ToJson(), "assessment v1 unchanged");
        auto second = processor.OnTick(Record(2, 200 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(second.attention_plan.jobs.empty() && second.attention_crops.empty(), "same real track suppressed");
        auto held = Record(3, 300 * kMs);
        held.detection_status = DetectionStatus::kNotRun;
        held.detections.clear();
        held.tracks.front().provenance = TrackProvenance::kHeld;
        held.tracks.front().since_observed_ns = 100 * kMs;
        held.tracks.front().track.last_seen_ns = 200 * kMs;
        CHECK(processor.OnTick(held, rgb.data(), rgb.size(), 5, 4).attention_crops.empty(), "cadence skip never captures held box");
        processor.HealthTick(1300 * kMs);  // last FRESH observed at 200ms
        auto after_gap = processor.OnTick(Record(4, 1400 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(after_gap.attention_crops.size() == 1, "input loss expires stale identity without new frames");
        bool health_report = false;
        for (const auto& asset : processor.artifacts()) {
            if (asset.media_type == kMediaSourceAttentionV1 &&
                Field(ReadReport(cfg, asset), "event").strValue() == "no_frame_health") health_report = true;
        }
        CHECK(health_report, "frameless lifecycle persisted");
        processor.SetLensId("wide", true);  // observed switch even if label unchanged
        CHECK(processor.OnTick(Record(5, 1500 * kMs), rgb.data(), rgb.size(), 5, 4).attention_crops.size() == 1,
              "observed switch invalidates cooldown");
        processor.SetRuntimeConfigIdentity(2, "new-runtime-config");
        CHECK(processor.OnTick(Record(6, 1600 * kMs), rgb.data(), rgb.size(), 5, 4).attention_crops.size() == 1,
              "runtime epoch invalidates cooldown");
        auto model = Record(7, 1700 * kMs);
        model.model_digest = "new-model";
        CHECK(processor.OnTick(model, rgb.data(), rgb.size(), 5, 4).attention_crops.size() == 1,
              "model identity invalidates cooldown");
        processor.RequestStop(1800 * kMs);
        std::string revision, manifest, error;
        CHECK(processor.Seal(&revision, &manifest, &error) && processor.VerifyAll(&error), "all source and reports immutable and hash verified");
        CHECK(!processor.hasWriteFailure(), "healthy evidence writes");
        std::printf("SIMULATED source-attention evidence directory: %s\n", cfg.evidence_dir.c_str());
    }
    {
        auto cfg = Config();
        cfg.ring.post_frames = 1;
        cfg.ring.max_frames = 3;
        AutonomousProcessor processor(cfg);
        auto first = processor.OnTick(Record(1, 100 * kMs), rgb.data(), rgb.size(), 5, 4);
        auto first_report = ReadReport(cfg, first.attention_artifact);
        CHECK(Field(first_report, "source_rgb_digest_origin").strValue() == "current_source_bytes" &&
              Field(first_report, "source_rgb_sha256").strValue() == Sha256Hex(rgb.data(), rgb.size()),
              "open window fallback hashes actual current bytes, not stale cache");
        auto changed = rgb;
        changed.front() = 252;
        auto next = Record(2, 200 * kMs);
        next.tracks.front().track.track_id = 2;
        auto second = processor.OnTick(next, changed.data(), changed.size(), 5, 4);
        auto second_report = ReadReport(cfg, second.attention_artifact);
        CHECK(Field(second_report, "source_rgb_digest_origin").strValue() == "same_tick_committed_source_frame" &&
              Field(second_report, "source_rgb_sha256").strValue() == Sha256Hex(changed.data(), changed.size()),
              "closing old window reuses current after-frame, never old trigger hash");
    }
    // Freshness, class, geometry, source and per-tick caps fail closed.
    {
        auto cfg = Config();
        AutonomousProcessor processor(cfg);
        processor.HealthTick(2000 * kMs);
        auto result = processor.OnTick(Record(1, 100 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(result.attention_plan.ok && result.attention_crops.empty(),
              "initial old queued frame cannot bypass age gate before the first planner view");
    }
    {
        auto cfg = Config();
        AutonomousProcessor processor(cfg);
        processor.OnTick(Record(1, 100 * kMs), rgb.data(), rgb.size(), 5, 4);
        processor.HealthTick(250 * kMs);  // actual device loop: health BEFORE queued pop
        auto queued = Record(2, 200 * kMs);
        queued.tracks.front().track.track_id = 2;
        auto result = processor.OnTick(queued, rgb.data(), rgb.size(), 5, 4);
        CHECK(result.attention_plan.ok && result.attention_crops.size() == 1,
              "queued receive stamp behind HealthTick is not a planner clock rollback");
        auto report = ReadReport(cfg, result.attention_artifact);
        CHECK(Field(report, "source_age_at_plan_ns").strValue() == std::to_string(50 * kMs),
              "queue delay stays explicit, not relabeled fresh age zero");
        processor.HealthTick(2000 * kMs);
        auto stale = Record(3, 300 * kMs);
        stale.tracks.front().track.track_id = 3;
        auto refused = processor.OnTick(stale, rgb.data(), rgb.size(), 5, 4);
        CHECK(refused.attention_crops.empty(), "old queued source cannot qualify after the freshness budget");
        auto rollback = processor.OnTick(Record(4, 299 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(!rollback.attention_plan.ok && rollback.attention_crops.empty(), "real source-order rollback remains refused");
    }
    for (int scenario = 0; scenario < 10; ++scenario) {
        auto cfg = Config();
        auto record = Record(1, 100 * kMs);
        std::size_t size = rgb.size();
        std::uint32_t width = 5, height = 4;
        if (scenario == 0) record.tracks.front().track.confirmed = false;
        if (scenario == 1) record.tracks.front().track.class_id = 2;
        if (scenario == 2) record.tracks.front().track.box.x_min = std::numeric_limits<float>::quiet_NaN();
        if (scenario == 3) --size;
        if (scenario == 4) width = 0;
        if (scenario == 5) record.detection_status = DetectionStatus::kInferenceFailure;
        if (scenario == 6) record.tracks.front().track.last_seen_ns = 0;
        if (scenario == 7) cfg.source_attention.max_bytes_per_tick = 17;
        if (scenario == 8) {
            for (int i = 0; i < 4; ++i) {
                auto t = record.tracks.front();
                t.track.track_id = static_cast<std::uint64_t>(i + 2);
                record.tracks.push_back(t);
            }
        }
        if (scenario == 9) record.tracks.push_back(record.tracks.front());
        AutonomousProcessor processor(cfg);
        auto result = processor.OnTick(record, rgb.data(), size, width, height);
        CHECK(result.attention_plan.jobs.empty() && result.attention_crops.empty(), "invalid or unqualified crop refused");
        CHECK(!result.attention_artifact.asset_id.empty() && result.attention_artifact.write_status == EvidenceStatus::kOk,
              "refusal is durable report, not negative verdict");
    }
    {
        auto cfg = Config();
        cfg.source_attention.enabled = false;
        AutonomousProcessor processor(cfg);
        auto result = processor.OnTick(Record(1, 100 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(result.attention_artifact.asset_id.empty() && result.attention_crops.empty(), "default-off no extra output");
        for (const auto& info : processor.artifacts()) CHECK(info.media_type != kMediaSourceAttentionV1, "legacy artifact set retained");
        cfg.source_attention.enabled = true;
        cfg.evidence_dir.clear();
        CHECK(!IsValidAutonomousConfig(cfg, nullptr), "enabled volatile-only refused");
        cfg.evidence_dir = NewDir();
        cfg.store_capacity_bytes = 0;
        CHECK(!IsValidAutonomousConfig(cfg, nullptr), "unbounded storage refused");
    }
    // Crop-specific I/O failure: assessment/window writes succeed first;
    // attempt stays charged and the report must NOT claim a stored crop.
    {
        auto cfg = Config();
        cfg.store_hooks.rename = [](const char* from, const char* to) {
            std::error_code ec;
            if (std::filesystem::file_size(from, ec) == 18 && !ec) { errno = EIO; return -1; }
            return ::rename(from, to);
        };
        AutonomousProcessor processor(cfg);
        auto result = processor.OnTick(Record(1, 100 * kMs), rgb.data(), rgb.size(), 5, 4);
        CHECK(!result.write_failure.empty(), "crop fsync/rename refusal exposed");
        auto report = ReadReport(cfg, result.attention_artifact);
        const auto& captures = Field(report, "captures").arrayValue();
        CHECK(captures.size() == 1 && !Field(captures.front(), "stored").boolValue(true), "failed crop never marked stored");
        CHECK(processor.OnTick(Record(2, 200 * kMs), rgb.data(), rgb.size(), 5, 4).attention_plan.jobs.empty(),
              "failed IO cannot cause per-frame retry loop");
    }
    // The actual SessionRunner + tracker supplies source dimensions and current
    // observed provenance. One detection call; no separate attention inference.
    {
        auto cfg = Config();
        AutonomousProcessor processor(cfg);
        RunnerConfig runtime;
        runtime.enable_spatial = false;
        SessionConfig session;
        session.tracker.min_hits = 1;
        ModelProfile profile;
        profile.model_id = "simulated-detector";
        SessionRunner runner(runtime, session, {}, {}, {}, profile);
        Detector detector;
        FramePacket frame;
        frame.metadata.width = 5;
        frame.metadata.height = 4;
        frame.metadata.frame_id = 1;
        frame.metadata.received_monotonic_ns = 100 * kMs;
        frame.rgb = rgb;
        const ksi_safety_edge_app::SourceIdentity identity{cfg.flight_id, cfg.edge_boot_id};
        auto context = ksi_safety_edge_app::MakeReceiveOnlySourceContext(frame.metadata, identity, cfg.stream_id);
        CHECK(context.flight_id == cfg.flight_id && context.boot_id == cfg.edge_boot_id &&
              context.stream_id == cfg.stream_id && !context.has_exposure,
              "actual PSDK worker helper forwards identity without exposure fabrication");
        runner.SetProcessingEnabled(false);
        CHECK(!ProcessSharedTick(runner, processor, frame, context, &detector, rgb.data(), rgb.size()).runner_processed && detector.calls == 0,
              "closed gate neither infers nor captures");
        runner.SetProcessingEnabled(true);
        auto result = ProcessSharedTick(runner, processor, frame, context, &detector, rgb.data(), rgb.size());
        CHECK(result.runner_processed && detector.calls == 1 && result.attention_crops.size() == 1,
              "same shared path captures actual tracker ROI exactly once");
        CHECK(!result.attention_crops.empty() && result.attention_crops.front().size_bytes == 18,
              "shared tracker boxes are source pixels, not normalized coordinates");
        auto report = ReadReport(cfg, result.attention_artifact);
        CHECK(Field(report, "source_width").intValue() == 5 && Field(report, "source_height").intValue() == 4,
              "source dimensions forwarded, not detector input dimensions");
        context.flight_id = "different-flight";
        frame.metadata.frame_id = ++context.frame_id;
        frame.metadata.received_monotonic_ns = context.receive_monotonic_ns += 100 * kMs;
        auto mismatch = ProcessSharedTick(runner, processor, frame, context, &detector, rgb.data(), rgb.size());
        CHECK(mismatch.attention_crops.empty(), "actual source identity mismatch refused");
        processor.AbortRuntime();
        CHECK(!processor.enabled(), "abort parks autonomy");
    }
    {
        auto cfg = Config();
        AutonomousProcessor processor(cfg);
        RunnerConfig runtime;
        runtime.enable_spatial = false;
        SessionConfig session;
        session.tracker.min_hits = 1;
        ModelProfile profile;
        profile.model_id = "SIMULATED_DFINE_OUTPUT_NOT_MODEL_ACCURACY";
        SessionRunner runner(runtime, session, {}, {}, {}, profile);
        DfineConfig detector_config;  // retains the actual 640x640 preprocessing path
        detector_config.num_queries = 1;
        detector_config.num_classes = 1;
        int calls = 0;
        DfineDetectorPort detector(detector_config,
            [&](const float* tensor, std::vector<float>* logits, std::vector<float>* boxes) {
                CHECK(tensor != nullptr, "preprocessed CHW tensor");
                ++calls;
                *logits = {10};
                *boxes = {.5F, .5F, .5F, .5F};  // normalized MODEL outputs only
                return true;
            });
        FramePacket frame;
        frame.metadata.width = frame.metadata.height = 8;
        frame.metadata.frame_id = 1;
        frame.metadata.received_monotonic_ns = 100 * kMs;
        frame.rgb.resize(8 * 8 * 3, 33);
        FrameContext context;
        context.flight_id = cfg.flight_id;
        context.boot_id = cfg.edge_boot_id;
        context.stream_id = cfg.stream_id;
        context.frame_id = 1;
        context.receive_monotonic_ns = 100 * kMs;
        auto result = ProcessSharedTick(runner, processor, frame, context, &detector, frame.rgb.data(), frame.rgb.size());
        CHECK(calls == 1 && result.record.tracks.size() == 1 && result.attention_crops.size() == 1,
              "existing D-FINE decode -> real tracker -> shared original ROI path");
        CHECK(!result.attention_crops.empty() && result.attention_crops.front().size_bytes == 4 * 4 * 3,
              "crop uses original 8x8 frame, not resized 640x640 tensor or normalized box");
    }
    {
        auto cfg = Config();
        cfg.store_capacity_bytes = 1000;  // no room for one assessment
        cfg.seal_reserve_bytes = 100;
        AutonomousProcessor processor(cfg);
        RunnerConfig runtime;
        runtime.enable_spatial = false;
        SessionConfig session;
        session.tracker.min_hits = 1;
        SessionRunner runner(runtime, session, {}, {}, {}, ModelProfile{});
        Detector detector;
        FramePacket frame;
        frame.metadata.width = 5;
        frame.metadata.height = 4;
        frame.metadata.frame_id = 1;
        frame.metadata.received_monotonic_ns = 100 * kMs;
        frame.rgb = rgb;
        FrameContext context;
        context.flight_id = cfg.flight_id;
        context.boot_id = cfg.edge_boot_id;
        context.stream_id = cfg.stream_id;
        context.frame_id = 1;
        context.receive_monotonic_ns = 100 * kMs;
        auto result = ProcessSharedTick(runner, processor, frame, context, &detector, rgb.data(), rgb.size());
        CHECK(result.write_failure.empty(),
              "capacity is not reported as an integrity failure");
        CHECK(processor.hasEvidenceSaturation() &&
              !processor.hasWriteFailure() && !runner.hasDurableFailure(),
              "capacity saturates evidence without pausing observation");
        CHECK(result.attention_artifact.asset_id.empty() && result.attention_crops.empty(),
              "saturated store schedules no capture and writes no report");
        bool named = false;
        for (const auto& reason : runner.Snapshot().degraded_reasons) {
            if (reason == "autonomy_evidence_saturated") named = true;
        }
        CHECK(named, "run facts name the autonomy evidence gap");
        auto next = frame;
        next.metadata.frame_id = 2;
        next.metadata.received_monotonic_ns = 200 * kMs;
        context.frame_id = 2;
        context.receive_monotonic_ns = 200 * kMs;
        auto after = ProcessSharedTick(runner, processor, next, context, &detector, rgb.data(), rgb.size());
        CHECK(after.runner_processed, "observation continues under saturation");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
