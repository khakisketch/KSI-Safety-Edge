// Host-only synthetic measurement, NOT Manifold calibration or detection GT.
// Caller clocks stay injected; wall timing lives only in this diagnostic tool.
//
// Variants (same synthetic 1080p stream, same 64 tracks, 30 fps timestamps):
//   pipeline_only         SessionRunner::ProcessFrame + tracker, no autonomy
//   autonomy_windows      P1 event windows (trigger + 1 post frame, one window
//                         per interval) written to the store
//   autonomy_with_roi     P1 windows + one original-resolution ROI crop/tick
//
// Run it against a disk path and against tmpfs to separate CPU from storage:
//   source_attention_bench /tmp/opencode/bench
//   source_attention_bench /dev/shm/bench
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/observation/source_roi.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <vector>

using namespace ksi::safety;
namespace {

using Clock = std::chrono::steady_clock;

struct Stats {
    std::vector<double> tick_ms;
};

JsonValue Percentiles(const std::vector<double>& values) {
    std::vector<double> sorted = values;
    std::sort(sorted.begin(), sorted.end());
    JsonValue result = JsonValue::Obj();
    if (sorted.empty()) return result;
    result.Set("samples", JsonValue::Int(static_cast<std::int64_t>(sorted.size())));
    result.Set("p50_ms", JsonValue::Double(sorted[sorted.size() / 2]));
    result.Set("p95_ms", JsonValue::Double(sorted[(sorted.size() - 1) * 95 / 100]));
    result.Set("max_ms", JsonValue::Double(sorted.back()));
    return result;
}

double Sum(const std::vector<double>& values) {
    double total = 0.0;
    for (double value : values) total += value;
    return total;
}

void Require(bool ok, const char* what) {
    if (!ok) throw std::runtime_error(what);
}

std::uint64_t CountFiles(const std::string& dir) {
    std::uint64_t count = 0;
    std::error_code ec;
    for (const auto& entry : std::filesystem::directory_iterator(dir, ec)) {
        if (entry.is_regular_file(ec)) ++count;
    }
    return count;
}

class StubDetector final : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket& frame) override {
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection detection;
        detection.class_id = 0;
        detection.score = 0.9F;
        // Source-pixel boxes, as the real D-FINE decode produces.
        detection.x_min = frame.metadata.width * 0.25F;
        detection.y_min = frame.metadata.height * 0.25F;
        detection.x_max = frame.metadata.width * 0.75F;
        detection.y_max = frame.metadata.height * 0.75F;
        batch.detections.push_back(detection);
        return batch;
    }
};

enum class Mode { kPipeline, kWindows, kRoi };

AutonomousConfig BaseConfig(const std::string& dir, bool attention) {
    AutonomousConfig cfg;
    cfg.enabled = true;
    cfg.flight_id = "synthetic-flight";
    cfg.edge_boot_id = "synthetic-boot";
    cfg.stream_id = "synthetic-stream";
    cfg.simulated_input = true;
    cfg.evidence_dir = dir;
    cfg.store_capacity_bytes = 2ULL * 1024 * 1024 * 1024;
    cfg.ring.max_frames = 16;
    cfg.ring.max_bytes = 256ULL * 1024 * 1024;
    cfg.ring.pre_frames = 0;  // P1 event evidence: trigger + one post frame
    cfg.ring.post_frames = 1;
    cfg.ring.window_deadline_ns = 500000000;
    cfg.source_attention.enabled = attention;
    return cfg;
}

JsonValue RunVariant(const std::string& base, Mode mode, int samples) {
    constexpr std::uint32_t width = 1920;
    constexpr std::uint32_t height = 1080;
    const std::vector<std::uint8_t> rgb(
        static_cast<std::size_t>(width) * height * 3, 37);
    const std::string dir = base + "/" +
        (mode == Mode::kPipeline ? "pipeline"
                                 : (mode == Mode::kWindows ? "windows" : "roi"));
    std::filesystem::create_directories(dir);
    SourceAttentionPolicy policy;
    Require(ParseSourceAttentionPolicy(
                "class=0,targets=64,jobs=1,bytes=2097152,repeat_ms=5000,"
                "lost_ms=1000,wait_ms=2000,retry_ms=500",
                &policy, nullptr),
            "policy");

    RunnerConfig runtime;
    runtime.detect_every_n_frames = 1;
    SessionConfig session;
    session.tracker.min_hits = 1;
    ModelProfile profile;
    profile.model_id = "SIMULATED_NOT_INFERENCE";
    SessionRunner runner(runtime, session, {}, {}, {}, profile);
    StubDetector detector;

    AutonomousConfig cfg = BaseConfig(dir, mode == Mode::kRoi);
    cfg.source_attention = policy;
    cfg.source_attention.enabled = (mode == Mode::kRoi);
    std::unique_ptr<AutonomousProcessor> processor;
    if (mode != Mode::kPipeline) {
        processor = std::make_unique<AutonomousProcessor>(cfg);
        Require(processor->valid(), "processor valid");
    }

    Stats stats;
    std::uint64_t crops = 0;
    std::uint64_t frames = 0;
    for (int i = 0; i < samples; ++i) {
        FramePacket packet;
        packet.metadata.width = width;
        packet.metadata.height = height;
        packet.metadata.frame_id = static_cast<std::uint32_t>(i + 1);
        packet.metadata.received_monotonic_ns =
            static_cast<std::int64_t>(i + 1) * 33333333;
        packet.rgb = rgb;
        FrameContext context;
        context.flight_id = cfg.flight_id;
        context.boot_id = cfg.edge_boot_id;
        context.stream_id = cfg.stream_id;
        context.frame_id = packet.metadata.frame_id;
        context.receive_monotonic_ns = packet.metadata.received_monotonic_ns;

        const auto start = Clock::now();
        if (mode == Mode::kPipeline) {
            (void)runner.ProcessFrame(packet, context, &detector);
        } else {
            const SharedTickResult result = ProcessSharedTick(
                runner, *processor, packet, context, &detector, rgb.data(),
                rgb.size());
            Require(result.write_failure.empty(), "no write failure");
            crops += result.attention_crops.size();
            frames += result.windows_closed.size();
        }
        stats.tick_ms.push_back(
            std::chrono::duration<double, std::milli>(Clock::now() - start)
                .count());
    }
    JsonValue out = Percentiles(stats.tick_ms);
    out.Set("total_seconds", JsonValue::Double(Sum(stats.tick_ms) / 1000.0));
    out.Set("closed_windows", JsonValue::Int(static_cast<std::int64_t>(frames)));
    out.Set("roi_crops", JsonValue::Int(static_cast<std::int64_t>(crops)));
    out.Set("asset_files", JsonValue::Int(
        static_cast<std::int64_t>(CountFiles(dir + "/assets"))));
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const std::string base =
            argc > 1 ? argv[1] : "/tmp/opencode/source-attention-bench";
        const int samples = argc > 2 ? std::atoi(argv[2]) : 8;
        if (samples < 1) throw std::runtime_error("samples must be >= 1");
        std::filesystem::create_directories(base);
        char pattern[512];
        std::snprintf(pattern, sizeof(pattern), "%s/run-XXXXXX", base.c_str());
        Require(::mkdtemp(pattern) != nullptr, "unique run dir");
        const std::string dir(pattern);

        JsonValue root = JsonValue::Obj();
        root.Set("environment", JsonValue::Str("HOST_SYNTHETIC_NOT_MANIFOLD"));
        root.Set("run_dir", JsonValue::Str(dir));
        root.Set("samples", JsonValue::Int(samples));
        root.Set("frame", JsonValue::Str("1920x1080 RGB24"));
        root.Set("frame_bytes",
                 JsonValue::Int(static_cast<std::int64_t>(1920) * 1080 * 3));
        root.Set("window", JsonValue::Str("P1: trigger+1 post, 1s interval"));
        root.Set("pipeline_only", RunVariant(dir, Mode::kPipeline, samples));
        root.Set("autonomy_windows", RunVariant(dir, Mode::kWindows, samples));
        root.Set("autonomy_with_roi", RunVariant(dir, Mode::kRoi, samples));

        SourceAttentionPolicy policy;
        Require(ParseSourceAttentionPolicy(
                    "class=0,targets=64,jobs=1,bytes=2097152,repeat_ms=5000,"
                    "lost_ms=1000,wait_ms=2000,retry_ms=500",
                    &policy, nullptr),
                "policy");
        observation::ObservationPlanner planner(
            SourceAttentionPlannerConfig(policy));
        std::vector<observation::ObservationCandidate> candidates;
        for (std::uint64_t id = 1; id <= 64; ++id) {
            observation::ObservationCandidate candidate;
            candidate.key = {1, id, 0};
            candidate.fresh_observed = true;
            candidate.confirmed = true;
            candidate.readiness = observation::SourceRoiReadiness::kUsable;
            candidate.estimated_cost_bytes = 192 * 256 * 3;
            candidates.push_back(candidate);
        }
        std::vector<double> planner_ms;
        for (int i = 0; i < 500; ++i) {
            const auto start = Clock::now();
            Require(planner
                        .Plan(static_cast<std::int64_t>(i) * 33333333,
                              "synthetic", candidates)
                        .ok,
                    "plan ok");
            planner_ms.push_back(
                std::chrono::duration<double, std::milli>(Clock::now() - start)
                    .count());
        }
        root.Set("planner", Percentiles(planner_ms));

        const std::vector<std::uint8_t> rgb(
            static_cast<std::size_t>(1920) * 1080 * 3, 37);
        SourceRoi roi;
        Require(DescribeSourceRoi({100, 100, 292, 356}, 1920, 1080, &roi,
                                  nullptr),
                "roi");
        std::vector<double> copy_ms;
        std::vector<double> hash_ms;
        for (int i = 0; i < 200; ++i) {
            const auto start = Clock::now();
            std::vector<std::uint8_t> crop;
            Require(CopySourceRoiRgb(rgb.data(), rgb.size(), 1920, 1080, roi,
                                     policy.max_bytes_per_tick, &crop, nullptr),
                    "crop");
            copy_ms.push_back(
                std::chrono::duration<double, std::milli>(Clock::now() - start)
                    .count());
        }
        for (int i = 0; i < 20; ++i) {
            const auto start = Clock::now();
            Require(Sha256Hex(rgb.data(), rgb.size()).size() == 64, "hash");
            hash_ms.push_back(
                std::chrono::duration<double, std::milli>(Clock::now() - start)
                    .count());
        }
        root.Set("roi_copy", Percentiles(copy_ms));
        root.Set("full_source_sha256", Percentiles(hash_ms));
        std::puts(WriteCanonicalJson(root).c_str());
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "source_attention_bench: %s\n", e.what());
        return 1;
    }
}
