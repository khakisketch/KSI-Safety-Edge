// Evidence-capacity behavior of the shared Edge path (post-defect regression).
//
// WHY THIS EXISTS
//   Autonomy evidence is a bounded, optional record. Flight-log integrity
//   (MOP journal / audit) is NOT optional. Treating both as one severity made
//   a full autonomy store pause detection for the rest of the flight: with the
//   device default 8 MiB and a 1080p window (5 x ~6.2 MB) the FIRST closed
//   window saturates the store. These tests pin the split:
//     * journal/integrity failure  -> pause work (unchanged, fail closed);
//     * autonomy store saturation   -> observation CONTINUES, the evidence gap
//       is explicit and sticky (never a silent healthy run, never auto-resume).
//
// [SIMULATED] small frames + real task-local filesystem. No device, model,
// accuracy, PPE, ACTIVE or field claim.

#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "support/check.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include <unistd.h>

using namespace ksi::safety;
namespace {

constexpr std::int64_t kMs = 1000000;
constexpr const char* kSaturated = "autonomy_evidence_saturated";

std::string NewDir() {
    std::filesystem::create_directories("/tmp/opencode/evidence-saturation-tests");
    char path[] = "/tmp/opencode/evidence-saturation-tests/run-XXXXXX";
    char* made = ::mkdtemp(path);
    CHECK(made != nullptr, "unique scratch directory");
    return made == nullptr ? std::string() : std::string(made);
}

bool HasReason(const SessionRunner& runner, const std::string& reason) {
    for (const auto& item : runner.Snapshot().degraded_reasons) {
        if (item == reason) return true;
    }
    return false;
}

std::uint64_t StoreBytes(const AutonomousProcessor& processor) {
    // Sum of committed asset bytes as reported by the coverage path is not
    // exposed; count the directory instead so growth is measured, not assumed.
    std::uint64_t total = 0;
    const std::filesystem::path assets =
        std::filesystem::path(processor.store_dir()) / "assets";
    std::error_code ec;
    for (const auto& entry : std::filesystem::directory_iterator(assets, ec)) {
        if (!entry.is_regular_file(ec)) continue;
        total += static_cast<std::uint64_t>(entry.file_size(ec));
    }
    return total;
}

struct Fixture {
    AutonomousConfig autonomy;
    RunnerConfig runtime;
    SessionConfig session;
    ModelProfile profile;
    std::uint32_t width = 320;
    std::uint32_t height = 180;
};

AutonomousConfig AutonomyConfig(std::uint64_t capacity) {
    AutonomousConfig config;
    config.enabled = true;
    config.flight_id = "sat-flight";
    config.edge_boot_id = "sat-boot";
    config.stream_id = "sat-stream";
    config.simulated_input = true;
    config.evidence_dir = NewDir();
    config.store_capacity_bytes = capacity;
    config.ring.max_frames = 4;
    config.ring.max_bytes = 4 * 1024 * 1024;
    config.ring.pre_frames = 1;
    config.ring.post_frames = 1;
    config.ring.window_deadline_ns = 500 * kMs;
    config.seal_reserve_bytes = 64 * 1024;  // test-scale seal headroom
    config.window_min_interval_ns = 0;      // every new lineage is an event
    return config;
}

Fixture MakeFixture(std::uint64_t capacity) {
    Fixture fixture;
    fixture.autonomy = AutonomyConfig(capacity);
    fixture.runtime.detect_every_n_frames = 1;
    fixture.session.tracker.min_hits = 1;
    fixture.profile.model_id = "simulated-not-a-model";
    return fixture;
}

class StubDetector final : public DetectorPort {
  public:
    explicit StubDetector(std::size_t* calls) : calls_(calls) {}
    DetectionBatch Detect(const FramePacket& frame) override {
        ++(*calls_);
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection detection;
        detection.class_id = 0;
        // Alternate between the left and right halves so every tick observes
        // a NEW lineage (IoU 0 between halves): each tick is its own event
        // under the event-gated window policy.
        const bool left = ((*calls_) % 2) == 1;
        detection.x_min = frame.metadata.width * (left ? 0.05F : 0.55F);
        detection.y_min = frame.metadata.height * 0.25F;
        detection.x_max = frame.metadata.width * (left ? 0.45F : 0.95F);
        detection.y_max = frame.metadata.height * 0.75F;
        detection.score = 0.9F;
        batch.detections.push_back(detection);
        return batch;
    }

  private:
    std::size_t* calls_;
};

class SteadyStub final : public DetectorPort {
  public:
    explicit SteadyStub(std::size_t* calls) : calls_(calls) {}
    DetectionBatch Detect(const FramePacket& frame) override {
        ++(*calls_);
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection detection;
        // The SAME box every tick: one continuous lineage, so the attention
        // cooldown (not new events) governs captures.
        detection.class_id = 0;
        detection.x_min = frame.metadata.width * 0.25F;
        detection.y_min = frame.metadata.height * 0.25F;
        detection.x_max = frame.metadata.width * 0.75F;
        detection.y_max = frame.metadata.height * 0.75F;
        detection.score = 0.9F;
        batch.detections.push_back(detection);
        return batch;
    }

  private:
    std::size_t* calls_;
};

SharedTickResult Tick(SessionRunner* runner, AutonomousProcessor* processor,
                      DetectorPort* detector, std::uint32_t frame_id,
                      std::int64_t receive_ns, std::uint32_t width,
                      std::uint32_t height,
                      const std::vector<std::uint8_t>& rgb) {
    FramePacket packet;
    packet.metadata.width = width;
    packet.metadata.height = height;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = receive_ns;
    packet.rgb = rgb;
    FrameContext context;
    context.flight_id = "sat-flight";
    context.boot_id = "sat-boot";
    context.stream_id = "sat-stream";
    context.frame_id = frame_id;
    context.receive_monotonic_ns = receive_ns;
    return ProcessSharedTick(*runner, *processor, packet, context, detector,
                             rgb.data(), rgb.size());
}

// One window needs (pre + during + post) full frames. The device default
// capacity cannot hold the first 1080p window: that is the sizing bug, shown
// as arithmetic rather than a slow multi-GB test.
bool CapacityCoversOneWindow(std::uint64_t capacity, std::uint32_t width,
                             std::uint32_t height,
                             const ContextRingConfig& ring) {
    const std::uint64_t frame_bytes =
        static_cast<std::uint64_t>(width) * height * 3;
    const std::uint64_t frames = ring.pre_frames + 1 + ring.post_frames;
    return capacity >= frame_bytes * frames;
}

}  // namespace

int main() {
    // 1) The device default 8 MiB cannot hold one 1080p window.
    {
        ContextRingConfig ring;
        ring.pre_frames = 2;
        ring.post_frames = 2;
        CHECK(!CapacityCoversOneWindow(8ULL * 1024 * 1024, 1920, 1080, ring),
              "8 MiB default cannot hold a 1080p pre/post window");
        CHECK(CapacityCoversOneWindow(64ULL * 1024 * 1024 * 1024, 1920, 1080,
                                      ring),
              "a stated 64 GiB budget does fit the same window");
    }

    // 2) Saturation mid-flight must NOT stop observation.
    {
        // Fits the preflight (one window = 3 x 172,800 B) but not two windows:
        // the store saturates while frames keep arriving.
        Fixture fixture = MakeFixture(600 * 1024);
        AutonomousProcessor processor(fixture.autonomy);
        CHECK(processor.valid(), "bounded processor valid");
        SessionRunner runner(fixture.runtime, fixture.session, {}, {}, {},
                             fixture.profile);
        std::size_t detector_calls = 0;
        StubDetector detector(&detector_calls);
        const std::vector<std::uint8_t> rgb(
            static_cast<std::size_t>(fixture.width) * fixture.height * 3, 17);

        for (std::uint32_t i = 1; i <= 6; ++i) {
            const SharedTickResult result =
                Tick(&runner, &processor, &detector, i, i * 100 * kMs,
                     fixture.width, fixture.height, rgb);
            CHECK(result.runner_processed, "every frame is processed");
            if (processor.hasEvidenceSaturation()) break;
        }
        CHECK(processor.hasEvidenceSaturation(),
              "store overflow is reported as saturation");
        CHECK(!processor.hasWriteFailure(),
              "saturation is not an integrity/write failure");
        CHECK(!runner.hasDurableFailure(),
              "saturation does not latch a durable-evidence pause");
        CHECK(HasReason(runner, kSaturated),
              "the evidence gap is visible on the run facts");
        CHECK(StoreBytes(processor) <= fixture.autonomy.store_capacity_bytes,
              "committed bytes stay inside the declared bound");

        const std::uint64_t frozen_bytes = StoreBytes(processor);
        const std::uint64_t processed_before = runner.processed_frame_count();
        const std::size_t calls_before = detector_calls;
        for (std::uint32_t i = 7; i <= 10; ++i) {
            Tick(&runner, &processor, &detector, i, i * 100 * kMs,
                 fixture.width, fixture.height, rgb);
        }
        CHECK(runner.processed_frame_count() == processed_before + 4,
              "observation keeps processing after saturation");
        CHECK(detector_calls == calls_before + 4,
              "detector still runs every frame");
        CHECK(StoreBytes(processor) == frozen_bytes,
              "saturated store performs no further writes (no retry storm)");
        CHECK(processor.coverage().evidence_refusals > 0,
              "refused artifacts are counted, not silently dropped");
        CHECK(processor.coverage().evidence_saturated,
              "coverage records the saturation fact");

        // Sticky: a later frame does not silently resume the record with a
        // gap in the middle, and the finish still seals what it did keep.
        CHECK(processor.hasEvidenceSaturation(), "saturation is sticky");
        std::string revision, manifest, error;
        CHECK(processor.Seal(&revision, &manifest, &error),
              "sealed revision still covers the artifacts that were kept");
        if (revision.empty()) {
            std::fprintf(stderr, "seal error: %s\n", error.c_str());
        }
    }

    // 3) Integrity failures stay fatal (fail closed, unchanged).
    {
        Fixture fixture = MakeFixture(8 * 1024 * 1024);
        fixture.autonomy.store_hooks.rename = [](const char*, const char*) {
            errno = EIO;
            return -1;
        };
        AutonomousProcessor processor(fixture.autonomy);
        SessionRunner runner(fixture.runtime, fixture.session, {}, {}, {},
                             fixture.profile);
        std::size_t detector_calls = 0;
        StubDetector detector(&detector_calls);
        const std::vector<std::uint8_t> rgb(
            static_cast<std::size_t>(fixture.width) * fixture.height * 3, 3);
        Tick(&runner, &processor, &detector, 1, 100 * kMs, fixture.width,
             fixture.height, rgb);
        CHECK(processor.hasWriteFailure(), "rename refusal is a write failure");
        CHECK(!processor.hasEvidenceSaturation(),
              "integrity faults are not relabelled as saturation");
        CHECK(runner.hasDurableFailure(),
              "integrity fault still pauses the run (unchanged)");
        const std::uint64_t processed = runner.processed_frame_count();
        Tick(&runner, &processor, &detector, 2, 200 * kMs, fixture.width,
             fixture.height, rgb);
        CHECK(runner.processed_frame_count() == processed,
              "paused run stays paused for integrity faults");
    }

    // 4) A capacity below one window is refused as a configuration fact on the
    //    first tick, without stopping observation.
    {
        Fixture fixture = MakeFixture(100 * 1024);  // < one 320x180 frame
        AutonomousProcessor processor(fixture.autonomy);
        SessionRunner runner(fixture.runtime, fixture.session, {}, {}, {},
                             fixture.profile);
        std::size_t detector_calls = 0;
        StubDetector detector(&detector_calls);
        const std::vector<std::uint8_t> rgb(
            static_cast<std::size_t>(fixture.width) * fixture.height * 3, 5);
        Tick(&runner, &processor, &detector, 1, 100 * kMs, fixture.width,
             fixture.height, rgb);
        CHECK(processor.hasEvidenceSaturation(), "tiny capacity is detected");
        CHECK(processor.coverage().saturation_reason ==
                  "evidence_capacity_below_one_window",
              "the reason names the configuration problem");
        CHECK(!runner.hasDurableFailure(), "observation is not paused");
        for (std::uint32_t i = 2; i <= 4; ++i) {
            Tick(&runner, &processor, &detector, i, i * 100 * kMs,
                 fixture.width, fixture.height, rgb);
        }
        CHECK(runner.processed_frame_count() == 4,
              "every frame is still observed");
        CHECK(StoreBytes(processor) == 0, "nothing is written under-capacity");
    }

    // 5) Attention reports are event-driven, not once per frame.
    {
        Fixture fixture = MakeFixture(8 * 1024 * 1024);
        fixture.autonomy.source_attention.enabled = false;
        std::string error;
        CHECK(ParseSourceAttentionPolicy(
                  "class=0,targets=8,jobs=1,bytes=262144,repeat_ms=5000,"
                  "lost_ms=1000,wait_ms=2000,retry_ms=500",
                  &fixture.autonomy.source_attention, &error),
              "simulated attention policy");
        fixture.autonomy.attention_report_interval_ns = 60 * 1000 * kMs;
        AutonomousProcessor processor(fixture.autonomy);
        SessionRunner runner(fixture.runtime, fixture.session, {}, {}, {},
                             fixture.profile);
        std::size_t detector_calls = 0;
        SteadyStub detector(&detector_calls);
        const std::vector<std::uint8_t> rgb(
            static_cast<std::size_t>(fixture.width) * fixture.height * 3, 11);
        std::size_t reports = 0;
        std::size_t crops = 0;
        for (std::uint32_t i = 1; i <= 12; ++i) {
            SharedTickResult result =
                Tick(&runner, &processor, &detector, i, i * 33 * kMs,
                     fixture.width, fixture.height, rgb);
            if (!result.attention_artifact.asset_id.empty()) ++reports;
            crops += result.attention_crops.size();
        }
        CHECK(crops == 1, "one crop for a continuously visible track");
        CHECK(reports <= 3,
              "steady-state reports are bounded, not one per frame");
    }

    if (edge_test::failures == 0) {
        std::printf(
            "evidence_saturation: severity split, sticky gap, cadence pass\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
