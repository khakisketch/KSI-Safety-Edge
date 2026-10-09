// replay: R1 replay inputs + session runner (the shared product path).
//
// Covers: manifest parsing (valid + strict failures), frame source byte
// fidelity and failure modes, telemetry "no future samples" (a frame can
// never see a sample that had not arrived by its receive time), detection
// cadence (detector called exactly once per detection tick), evidence
// semantics (clean negative vs inference failure vs cadence skip), track
// provenance (OBSERVED/MISSED/HELD with since_observed_ns), PSDK-path
// parity (BoundedFrameQueue-driven records are byte-identical to direct
// records), invalid-config fail-closed, DfineDetectorPort status mapping,
// and the spatial eligibility gate (both attitude AND position must be
// source-verified; receive proximity never qualifies).

#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "ksi_safety/replay/frame_source.hpp"
#include "ksi_safety/replay/telemetry_source.hpp"
#include "ksi_safety/runtime/session_runner.hpp"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>

#include "support/check.hpp"

using ksi::safety::ApplyManifestVerifications;
using ksi::safety::BoundedFrameQueue;
using ksi::safety::ClockRegistry;
using ksi::safety::Detection;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::DfineConfig;
using ksi::safety::DfineDetectorPort;
using ksi::safety::FrameContext;
using ksi::safety::FrameDropReason;
using ksi::safety::FrameMetadata;
using ksi::safety::FramePacket;
using ksi::safety::LoadReplayManifest;
using ksi::safety::ModelProfile;
using ksi::safety::PoseQuality;
using ksi::safety::PoseReason;
using ksi::safety::Quaternion;
using ksi::safety::ReplayFrameSource;
using ksi::safety::ReplayManifest;
using ksi::safety::ReplayTelemetrySource;
using ksi::safety::RunnerConfig;
using ksi::safety::Sample;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::TickRecord;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::ToString;
using ksi::safety::TrackProvenance;
using ksi::safety::Vec3;

namespace {

constexpr std::int64_t kMs = 1000000LL;

// --- temp files ---------------------------------------------------------------

struct TempDir {
    std::string path;
    std::vector<std::string> files;

    TempDir() {
        char tmpl[] = "/tmp/ksi_replay_test_XXXXXX";
        char* dir = mkdtemp(tmpl);
        path = (dir != nullptr) ? std::string(dir) : std::string();
    }
    ~TempDir() {
        for (const std::string& file : files) {
            std::remove(file.c_str());
        }
        if (!path.empty()) {
            rmdir(path.c_str());
        }
    }
    std::string File(const std::string& name) {
        const std::string full = path + "/" + name;
        files.push_back(full);
        return full;
    }
};

bool WriteText(const std::string& path, const std::string& content) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        return false;
    }
    out.write(content.data(), static_cast<std::streamsize>(content.size()));
    return static_cast<bool>(out);
}

bool WriteRgb(const std::string& path, int width, int height,
              std::uint8_t seed) {
    std::string bytes(static_cast<std::size_t>(width) *
                          static_cast<std::size_t>(height) * 3U,
                      '\0');
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = static_cast<char>((i + seed) % 256U);
    }
    return WriteText(path, bytes);
}

// --- runner fixtures ----------------------------------------------------------

RunnerConfig RunnerCfg(std::uint64_t detect_every = 1) {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = detect_every;
    return config;
}

SessionConfig SessionCfg(int max_age_frames = 5) {
    SessionConfig config;
    config.tracker.iou_threshold = 0.3F;
    config.tracker.max_age_frames = max_age_frames;
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

FramePacket MakeFrame(std::uint32_t frame_id, std::int64_t recv_ns,
                      std::uint8_t seed = 0) {
    FramePacket packet;
    packet.metadata.width = 8;
    packet.metadata.height = 6;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = recv_ns;
    packet.rgb.assign(8U * 6U * 3U, seed);
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

Timestamp RecvTs(std::int64_t ns) {
    Timestamp ts;
    ts.value_ns = ns;
    ts.kind = TimeSourceKind::kHostReceive;
    ts.domain = "host_mono";
    ts.verified = true;
    return ts;
}

Sample<Quaternion> PoseSample(std::uint64_t id, std::int64_t recv_ns) {
    Sample<Quaternion> sample;
    sample.sample_id = id;
    sample.seq = id;
    sample.received = RecvTs(recv_ns);
    sample.has_source_time = false;
    return sample;
}

Sample<Quaternion> VerifiedPoseSample(std::uint64_t id, std::int64_t recv_ns,
                                      std::int64_t src_ns, bool verified) {
    Sample<Quaternion> sample = PoseSample(id, recv_ns);
    sample.has_source_time = true;
    sample.source_time.kind = TimeSourceKind::kDeviceSensor;
    sample.source_time.domain = "cam0";
    sample.source_time.value_ns = src_ns;
    sample.source_time.verified = verified;
    return sample;
}

Sample<Vec3> VerifiedPositionSample(std::uint64_t id, std::int64_t recv_ns,
                                    std::int64_t src_ns, bool verified) {
    Sample<Vec3> sample;
    sample.sample_id = id;
    sample.seq = id;
    sample.received = RecvTs(recv_ns);
    sample.has_source_time = true;
    sample.source_time.kind = TimeSourceKind::kDeviceSensor;
    sample.source_time.domain = "cam0";
    sample.source_time.value_ns = src_ns;
    sample.source_time.verified = verified;
    return sample;
}

class CountingOkDetector : public DetectorPort {
  public:
    int calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        ++calls;
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection det;
        det.x_min = 1.0F;
        det.y_min = 1.0F;
        det.x_max = 5.0F;
        det.y_max = 5.0F;
        det.score = 0.9F;
        det.class_id = 0;
        batch.detections.push_back(det);
        return batch;
    }
};

// Scripted detector: per-call steps (0 = ok with one detection,
// 1 = inference failure, 2 = ok with no detections; past the script: 2).
class ScriptedDetector : public DetectorPort {
  public:
    std::vector<int> script;
    int calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        const int step =
            calls < static_cast<int>(script.size()) ? script[calls] : 2;
        ++calls;
        DetectionBatch batch;
        if (step == 1) {
            batch.status = DetectionStatus::kInferenceFailure;
            return batch;
        }
        batch.status = DetectionStatus::kOk;
        if (step == 0) {
            Detection det;
            det.x_min = 1.0F;
            det.y_min = 1.0F;
            det.x_max = 5.0F;
            det.y_max = 5.0F;
            det.score = 0.9F;
            det.class_id = 0;
            batch.detections.push_back(det);
        }
        return batch;
    }
};

std::string Describe(const TickRecord& record) {
    std::ostringstream ss;
    ss << record.tick_index << "|" << record.frame_id << "|"
       << record.stream_id << "|" << record.receive_mono_ns << "|"
       << record.model_id << "|" << record.model_digest << "|"
       << (record.exposure_verified ? 1 : 0) << "|"
       << ToString(record.detection_status) << "|" << record.detections.size();
    for (const Detection& det : record.detections) {
        ss << "|d:" << det.class_id << "," << det.score << "," << det.x_min
           << "," << det.y_min << "," << det.x_max << "," << det.y_max;
    }
    ss << "|a:" << ToString(record.attitude_quality) << ","
       << ToString(record.attitude_reason) << "," << record.attitude_gap_ns;
    ss << "|p:" << ToString(record.position_quality) << ","
       << ToString(record.position_reason) << "," << record.position_gap_ns;
    ss << "|e:" << (record.spatial_eligible ? 1 : 0);
    for (const auto& track_record : record.tracks) {
        ss << "|t:" << track_record.track.track_id << ","
           << track_record.track.class_id << ","
           << ToString(track_record.provenance) << ","
           << track_record.since_observed_ns << "," << track_record.track.hits
           << "," << track_record.track.age << ","
           << (track_record.track.confirmed ? 1 : 0) << ","
           << track_record.track.last_seen_ns << ","
           << track_record.track.box.x_min;
    }
    for (const auto& output : record.proximity) {
        ss << "|x:" << output.rule_id << "," << ToString(output.status) << ","
           << output.events.size();
    }
    for (const auto& output : record.zone) {
        ss << "|z:" << output.rule_id << "," << ToString(output.status) << ","
           << output.events.size();
    }
    return ss.str();
}

}  // namespace

int main() {
    // ---- 1) manifest parse: valid file ----
    {
        TempDir dir;
        const std::string manifest_path = dir.File("ok.manifest");
        CHECK(WriteText(manifest_path,
                        "# comment line\n"
                        "\n"
                        "verify host_mono host_receive replay_clock\n"
                        "verify cam0 device_sensor cam0_calib\n"
                        "frame 1 1000 8 6 f1.rgb\n"
                        "frame 2 1100 8 6 f2.rgb\n"
                        "pose 1 1 900 cam0 900 1.0 0.1 0.2 0.3\n"
                        "pose 2 2 950 - 0 1.0 0.0 0.0 0.0\n"
                        "position 1 1 920 cam0 920 10.0 20.0 30.0\n"),
              "manifest written");
        ReplayManifest manifest;
        std::string error;
        CHECK(LoadReplayManifest(manifest_path, &manifest, &error),
              "valid manifest loads");
        CHECK(manifest.frames.size() == 2, "two frames parsed");
        CHECK(manifest.poses.size() == 2, "two poses parsed");
        CHECK(manifest.positions.size() == 1, "one position parsed");
        CHECK(manifest.verifications.size() == 2, "two verifies parsed");
        if (manifest.frames.size() == 2) {
            CHECK(manifest.frames[0].frame_id == 1 &&
                      manifest.frames[0].width == 8 &&
                      manifest.frames[0].height == 6 &&
                      manifest.frames[0].receive_mono_ns == 1000 &&
                      manifest.frames[0].path == "f1.rgb",
                  "frame fields parsed");
        }
        if (manifest.poses.size() == 2) {
            CHECK(manifest.poses[0].has_source_time &&
                      manifest.poses[0].domain == "cam0" &&
                      manifest.poses[0].source_ns == 900 &&
                      manifest.poses[0].value.x == 0.1,
                  "pose source time parsed");
            CHECK(!manifest.poses[1].has_source_time &&
                      manifest.poses[1].source_ns == 0,
                  "pose without source time parsed");
        }
        ClockRegistry clocks;
        ApplyManifestVerifications(manifest, &clocks);
        CHECK(clocks.IsVerified("cam0", TimeSourceKind::kDeviceSensor),
              "cam0 device sensor verified via manifest");
        CHECK(clocks.IsVerified("host_mono", TimeSourceKind::kHostReceive),
              "host_mono host receive verified via manifest");
        CHECK(!clocks.IsVerified("cam0", TimeSourceKind::kHostReceive),
              "one kind does not verify the other");
        CHECK(!clocks.IsVerified("other", TimeSourceKind::kDeviceSensor),
              "unlisted domain stays unverified");
    }
    // ---- 2) manifest parse: strict failures (each names its line) ----
    {
        struct BadCase {
            const char* name;
            const char* text;
        };
        const BadCase cases[] = {
            {"unknown keyword", "bogus 1 2\n"},
            {"missing frame field", "frame 1 1000 8\n"},
            {"negative frame recv", "frame 1 -5 8 6 f.rgb\n"},
            {"zero frame width", "frame 1 1000 0 6 f.rgb\n"},
            {"dash with source", "pose 1 1 100 - 100 1 0 0 0\n"},
            {"named domain zero source", "pose 1 1 100 cam0 0 1 0 0 0\n"},
            {"unknown verify kind", "verify d bogus x\n"},
            {"frame extra field", "frame 1 1000 8 6 f.rgb extra\n"},
            {"short position", "position 1 1 100 - 0 1 2\n"},
            {"bad quaternion", "pose 1 1 100 - 0 1 0 nan 0\n"},
        };
        for (const BadCase& bad : cases) {
            TempDir dir;
            const std::string manifest_path = dir.File("bad.manifest");
            CHECK(WriteText(manifest_path, bad.text), "bad manifest written");
            ReplayManifest manifest;
            std::string error;
            const bool loaded = LoadReplayManifest(manifest_path, &manifest,
                                                   &error);
            CHECK(!loaded, (std::string("rejected: ") + bad.name).c_str());
            CHECK(error.find("line 1") != std::string::npos,
                  (std::string("line number reported: ") + bad.name).c_str());
        }
    }
    // ---- 3) frame source: fidelity and failure modes ----
    {
        TempDir dir;
        const std::string f1 = dir.File("f1.rgb");
        const std::string f2 = dir.File("f2.rgb");
        CHECK(WriteRgb(f1, 8, 6, 7), "f1 written");
        CHECK(WriteRgb(f2, 8, 6, 9), "f2 written");
        const std::string manifest_path = dir.File("frames.manifest");
        CHECK(WriteText(manifest_path,
                        "frame 10 1000 8 6 f1.rgb\n"
                        "frame 11 1100 8 6 f2.rgb\n"),
              "frames manifest written");
        ReplayFrameSource source(manifest_path, "FLT", "BOOT", "STREAM");
        CHECK(source.ok(), "frame source ok");
        CHECK(source.frame_count() == 2, "frame count 2");
        FramePacket packet;
        FrameContext ctx;
        CHECK(source.ReadFrame(0, &packet, &ctx), "frame 0 readable");
        CHECK(packet.metadata.width == 8 && packet.metadata.height == 6 &&
                  packet.metadata.frame_id == 10 &&
                  packet.metadata.received_monotonic_ns == 1000 &&
                  packet.metadata.sensor_timestamp_ns == 0,
              "frame 0 metadata");
        CHECK(packet.rgb.size() == 8U * 6U * 3U &&
                  packet.rgb[0] == 7 && packet.rgb[100] ==
                  static_cast<std::uint8_t>((100 + 7) % 256),
              "frame 0 bytes exact");
        CHECK(ctx.flight_id == "FLT" && ctx.boot_id == "BOOT" &&
                  ctx.stream_id == "STREAM" && ctx.frame_id == 10 &&
                  ctx.receive_monotonic_ns == 1000 && !ctx.has_exposure,
              "frame 0 context");
        CHECK(!source.ReadFrame(2, &packet, &ctx), "out-of-range fails");

        const std::string missing_manifest = dir.File("missing.manifest");
        CHECK(WriteText(missing_manifest, "frame 1 1000 8 6 nope.rgb\n"),
              "missing manifest written");
        ReplayFrameSource missing(missing_manifest, "F", "B", "S");
        CHECK(missing.ok(), "missing-frame manifest still loads");
        CHECK(!missing.ReadFrame(0, &packet, &ctx),
              "missing frame file fails");

        const std::string short_rgb = dir.File("short.rgb");
        CHECK(WriteText(short_rgb, "0123456789"), "short file written");
        const std::string short_manifest = dir.File("short.manifest");
        CHECK(WriteText(short_manifest, "frame 1 1000 8 6 short.rgb\n"),
              "short manifest written");
        ReplayFrameSource short_source(short_manifest, "F", "B", "S");
        CHECK(!short_source.ReadFrame(0, &packet, &ctx),
              "short frame file fails");

        const std::string long_rgb = dir.File("long.rgb");
        CHECK(WriteRgb(long_rgb, 8, 6, 1), "long base written");
        {
            std::ofstream append(long_rgb, std::ios::binary | std::ios::app);
            append.put('x');
        }
        const std::string long_manifest = dir.File("long.manifest");
        CHECK(WriteText(long_manifest, "frame 1 1000 8 6 long.rgb\n"),
              "long manifest written");
        ReplayFrameSource long_source(long_manifest, "F", "B", "S");
        CHECK(!long_source.ReadFrame(0, &packet, &ctx),
              "trailing-byte frame file fails");
    }
    // ---- 4) telemetry: no future samples ----
    {
        TempDir dir;
        const std::string manifest_path = dir.File("telemetry.manifest");
        CHECK(WriteText(manifest_path,
                        "verify host_mono host_receive replay_clock\n"
                        "pose 1 1 100000000 - 0 1 0 0 0\n"
                        "position 1 1 120000000 - 0 1 2 3\n"
                        "pose 2 2 160000000 - 0 1 0 0 0\n"),
              "telemetry manifest written");
        ReplayManifest manifest;
        std::string error;
        CHECK(LoadReplayManifest(manifest_path, &manifest, &error),
              "telemetry manifest loads");
        ClockRegistry clocks;
        ApplyManifestVerifications(manifest, &clocks);
        ReplayTelemetrySource telemetry(manifest, "host_mono", clocks);
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());

        FramePacket frame = MakeFrame(1, 150 * kMs);
        FrameContext ctx = MakeCtx(frame);
        ReplayTelemetrySource::PushResult pushed =
            telemetry.PushDue(ctx.receive_monotonic_ns, runner);
        CHECK(pushed.poses_pushed == 1 && pushed.positions_pushed == 1,
              "due samples pushed (pose 100, position 120)");
        CHECK(pushed.duplicates == 0 && pushed.rollbacks == 0,
              "no duplicates/rollbacks");
        CHECK(telemetry.pending_count() == 1, "pose 160 still pending");
        CHECK(runner.AttitudeSamples() == 1 && runner.PositionSamples() == 1,
              "rings hold only arrived samples");
        TickRecord record = runner.ProcessFrame(frame, ctx, nullptr);
        CHECK(record.attitude_quality == PoseQuality::kNone &&
                  record.attitude_reason == PoseReason::kOutsideMaxGap,
              "future pose 160 not visible: gap 50ms > 40ms");
        CHECK(record.position_quality == PoseQuality::kReceiveNearest &&
                  record.position_reason == PoseReason::kCaptureTimeUnverified,
              "position 120 visible at gap 30ms");

        FramePacket frame2 = MakeFrame(2, 170 * kMs);
        FrameContext ctx2 = MakeCtx(frame2);
        pushed = telemetry.PushDue(ctx2.receive_monotonic_ns, runner);
        CHECK(pushed.poses_pushed == 1 && telemetry.pending_count() == 0,
              "pose 160 arrives at 170");
        record = runner.ProcessFrame(frame2, ctx2, nullptr);
        CHECK(record.attitude_quality == PoseQuality::kReceiveNearest &&
                  record.attitude_gap_ns == 10 * kMs,
              "pose 160 visible at gap 10ms");
    }
    // ---- 5) detection cadence: exactly once per detection tick ----
    {
        SessionRunner runner(RunnerCfg(2), SessionCfg(), {}, {}, {},
                             Profile());
        CountingOkDetector detector;
        const DetectionStatus expected[] = {
            DetectionStatus::kOk, DetectionStatus::kNotRun,
            DetectionStatus::kOk, DetectionStatus::kNotRun,
            DetectionStatus::kOk};
        for (int i = 0; i < 5; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          1000 + 100LL * i);
            const FrameContext ctx = MakeCtx(frame);
            const TickRecord record =
                runner.ProcessFrame(frame, ctx, &detector);
            CHECK(record.detection_status == expected[i],
                  "cadence status per tick");
            if (i == 1 || i == 3) {
                CHECK(record.tracks.size() == 1 &&
                          record.tracks[0].provenance ==
                              TrackProvenance::kHeld,
                      "skip tick holds the track");
            }
        }
        CHECK(detector.calls == 3, "detector called once per detection tick");
    }
    // ---- 6) evidence semantics: failure is not a negative ----
    {
        SessionRunner runner(RunnerCfg(), SessionCfg(2), {}, {}, {},
                             Profile());
        ScriptedDetector detector;
        detector.script = {0, 1, 2, 2, 2};  // det, failure, empty, empty, empty
        for (int i = 0; i < 5; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          1000 + 100LL * i);
            const FrameContext ctx = MakeCtx(frame);
            const TickRecord record =
                runner.ProcessFrame(frame, ctx, &detector);
            if (i == 0) {
                CHECK(record.tracks.size() == 1 &&
                          record.tracks[0].provenance ==
                              TrackProvenance::kObserved &&
                          record.tracks[0].since_observed_ns == 0,
                      "detection tick observes the track");
            } else if (i == 1) {
                CHECK(record.detection_status ==
                          DetectionStatus::kInferenceFailure,
                      "failure status recorded");
                CHECK(record.tracks.size() == 1 &&
                          record.tracks[0].provenance ==
                              TrackProvenance::kHeld &&
                          record.tracks[0].since_observed_ns == 100,
                      "failure holds the track (no miss)");
            } else if (i == 2 || i == 3) {
                CHECK(record.tracks.size() == 1 &&
                          record.tracks[0].provenance ==
                              TrackProvenance::kMissed &&
                          record.tracks[0].since_observed_ns == 100LL * i,
                      "clean negative advances a miss, track survives");
            } else {
                CHECK(record.tracks.empty(),
                      "third consecutive miss drops the track at tick 4");
            }
        }
        // Control: the same sequence WITHOUT the failure drops one tick
        // earlier (tick 3) — proving the failure did not count as a miss.
        SessionRunner control(RunnerCfg(), SessionCfg(2), {}, {}, {},
                              Profile());
        ScriptedDetector control_detector;
        control_detector.script = {0, 2, 2, 2};
        for (int i = 0; i < 4; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          1000 + 100LL * i);
            const FrameContext ctx = MakeCtx(frame);
            const TickRecord record =
                control.ProcessFrame(frame, ctx, &control_detector);
            if (i == 3) {
                CHECK(record.tracks.empty(),
                      "control without failure drops at tick 3");
            }
        }
    }
    // ---- 7) skip ticks do not advance misses (cadence vs negative) ----
    {
        SessionRunner runner(RunnerCfg(2), SessionCfg(1), {}, {}, {},
                             Profile());
        ScriptedDetector detector;
        detector.script = {0, 2, 2};  // det, empty, empty
        for (int i = 0; i < 5; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          1000 + 100LL * i);
            const FrameContext ctx = MakeCtx(frame);
            const TickRecord record =
                runner.ProcessFrame(frame, ctx, &detector);
            if (i == 2) {
                CHECK(record.tracks.size() == 1,
                      "first clean negative survives (max_age 1)");
            } else if (i == 3) {
                CHECK(record.tracks.size() == 1 &&
                          record.tracks[0].provenance ==
                              TrackProvenance::kHeld,
                      "skip tick after miss still holds the track");
            } else if (i == 4) {
                CHECK(record.tracks.empty(),
                      "second clean negative drops at tick 4");
            }
        }
    }
    // ---- 8) PSDK-path parity: queue-driven == direct, byte-identical ----
    {
        constexpr int kFrames = 4;
        auto drive_direct = [](std::vector<std::string>* log) {
            SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {},
                                 Profile());
            CountingOkDetector detector;
            for (int i = 0; i < kFrames; ++i) {
                runner.PushPose(PoseSample(static_cast<std::uint64_t>(i + 1),
                                           1000 + 100LL * i));
                FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                              1000 + 100LL * i);
                const FrameContext ctx = MakeCtx(frame);
                log->push_back(Describe(
                    runner.ProcessFrame(frame, ctx, &detector)));
            }
            CHECK(detector.calls == kFrames, "direct path detected all");
        };
        auto drive_psdk_queue = [](std::vector<std::string>* log) {
            SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {},
                                 Profile());
            CountingOkDetector detector;
            BoundedFrameQueue queue(4, 8U * 6U * 3U);
            for (int i = 0; i < kFrames; ++i) {
                runner.PushPose(PoseSample(static_cast<std::uint64_t>(i + 1),
                                           1000 + 100LL * i));
                FramePacket source = MakeFrame(static_cast<std::uint32_t>(i),
                                               1000 + 100LL * i);
                FrameMetadata metadata = source.metadata;
                const FrameDropReason verdict = queue.PushFromCallback(
                    source.rgb.data(), source.rgb.size(), metadata);
                CHECK(verdict == FrameDropReason::kAccepted,
                      "psdk-path frame accepted");
                FramePacket packet;
                CHECK(queue.TryPop(&packet), "psdk-path frame popped");
                const FrameContext ctx = MakeCtx(packet);
                log->push_back(Describe(
                    runner.ProcessFrame(packet, ctx, &detector)));
            }
            CHECK(detector.calls == kFrames, "psdk path detected all");
        };
        std::vector<std::string> direct_log;
        std::vector<std::string> queue_log;
        std::vector<std::string> direct_again;
        drive_direct(&direct_log);
        drive_psdk_queue(&queue_log);
        drive_direct(&direct_again);
        CHECK(direct_log.size() == kFrames && queue_log.size() == kFrames,
              "both paths produced records");
        bool identical = direct_log.size() == queue_log.size();
        for (std::size_t i = 0; identical && i < direct_log.size(); ++i) {
            identical = direct_log[i] == queue_log[i];
        }
        CHECK(identical, "PSDK queue path == direct path (same product code)");
        bool deterministic = direct_log.size() == direct_again.size();
        for (std::size_t i = 0; deterministic && i < direct_log.size(); ++i) {
            deterministic = direct_log[i] == direct_again[i];
        }
        CHECK(deterministic, "same inputs reproduce identical records");
    }
    // ---- 9) invalid config fail-closed ----
    {
        CHECK(!ksi::safety::IsValidRunnerConfig(RunnerCfg(0)),
              "detect_every 0 invalid");
        SessionRunner runner(RunnerCfg(0), SessionCfg(), {}, {}, {},
                             Profile());
        CountingOkDetector detector;
        for (int i = 0; i < 3; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          1000 + 100LL * i);
            const FrameContext ctx = MakeCtx(frame);
            const TickRecord record =
                runner.ProcessFrame(frame, ctx, &detector);
            CHECK(record.detection_status == DetectionStatus::kNotRun,
                  "invalid config never detects");
        }
        CHECK(detector.calls == 0, "invalid config never calls the detector");

        bool threw = false;
        try {
            RunnerConfig bad = RunnerCfg();
            bad.attitude_ring_capacity = 0;
            SessionRunner invalid(bad, SessionCfg(), {}, {}, {}, Profile());
            (void)invalid;
        } catch (const std::invalid_argument&) {
            threw = true;
        }
        CHECK(threw, "zero ring capacity throws (TopicRing contract)");
    }
    // ---- 10) DfineDetectorPort status mapping ----
    {
        DfineConfig config;
        config.num_classes = 3;
        config.num_queries = 4;
        int inference_calls = 0;
        DfineDetectorPort ok_port(
            config,
            [&inference_calls](const float* /*in*/, std::vector<float>* logits,
                               std::vector<float>* boxes) {
                ++inference_calls;
                logits->assign(4U * 3U, -6.0F);
                (*logits)[0] = 6.0F;  // query 0, class 0
                boxes->assign(4U * 4U, 0.0F);
                (*boxes)[0] = 0.5F;
                (*boxes)[1] = 0.5F;
                (*boxes)[2] = 0.25F;
                (*boxes)[3] = 0.25F;
                return true;
            });
        FramePacket frame = MakeFrame(1, 1000, 3);
        DetectionBatch batch = ok_port.Detect(frame);
        CHECK(batch.status == DetectionStatus::kOk, "ok path status");
        CHECK(batch.detections.size() == 1, "ok path one detection");
        if (batch.detections.size() == 1) {
            CHECK(batch.detections[0].class_id == 0 &&
                      batch.detections[0].score > 0.99F,
                  "ok path detection values");
            CHECK(batch.detections[0].x_min > 2.9F &&
                      batch.detections[0].x_min < 3.1F &&
                      batch.detections[0].y_max > 3.7F &&
                      batch.detections[0].y_max < 3.8F,
                  "ok path box scaled to source pixels");
        }

        DfineDetectorPort fail_port(
            config,
            [](const float* /*in*/, std::vector<float>* /*logits*/,
               std::vector<float>* /*boxes*/) { return false; });
        batch = fail_port.Detect(frame);
        CHECK(batch.status == DetectionStatus::kInferenceFailure,
              "runtime failure -> kInferenceFailure");
        CHECK(batch.detections.empty(), "failure carries no detections");

        DfineDetectorPort malformed_port(
            config,
            [&inference_calls](const float* /*in*/,
                               std::vector<float>* /*logits*/,
                               std::vector<float>* /*boxes*/) {
                ++inference_calls;
                return true;
            });
        FramePacket empty_rgb = MakeFrame(2, 1100);
        empty_rgb.rgb.clear();
        batch = malformed_port.Detect(empty_rgb);
        CHECK(batch.status == DetectionStatus::kInferenceFailure,
              "short rgb -> kInferenceFailure");
        FramePacket zero_dim = MakeFrame(3, 1200);
        zero_dim.metadata.width = 0;
        batch = malformed_port.Detect(zero_dim);
        CHECK(batch.status == DetectionStatus::kInferenceFailure,
              "zero dimension -> kInferenceFailure");
    }
    // ---- 11) spatial gate: both attitude and position source-verified ----
    {
        // Verified bracket on both rings + verified exposure -> eligible.
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {},
                             Profile());
        runner.PushPose(VerifiedPoseSample(1, 1000, 1000, true));
        runner.PushPose(VerifiedPoseSample(2, 2000, 2000, true));
        runner.PushPosition(VerifiedPositionSample(1, 1000, 1000, true));
        runner.PushPosition(VerifiedPositionSample(2, 2000, 2000, true));
        FramePacket frame = MakeFrame(1, 1500);
        FrameContext ctx = MakeCtx(frame);
        ctx.has_exposure = true;
        ctx.exposure = VerifiedPoseSample(9, 1500, 1500, true).source_time;
        TickRecord record = runner.ProcessFrame(frame, ctx, nullptr);
        CHECK(record.attitude_quality == PoseQuality::kSourceVerified &&
                  record.position_quality == PoseQuality::kSourceVerified,
              "both rings source-verified");
        CHECK(record.spatial_eligible,
              "both verified -> spatial eligible");

        // Position ring missing -> not eligible (attitude alone is not enough).
        SessionRunner att_only(RunnerCfg(), SessionCfg(), {}, {}, {},
                               Profile());
        att_only.PushPose(VerifiedPoseSample(1, 1000, 1000, true));
        att_only.PushPose(VerifiedPoseSample(2, 2000, 2000, true));
        record = att_only.ProcessFrame(frame, ctx, nullptr);
        CHECK(record.attitude_quality == PoseQuality::kSourceVerified &&
                  record.position_quality == PoseQuality::kNone,
              "missing position ring graded none");
        CHECK(!record.spatial_eligible,
              "attitude alone is not spatially eligible");

        // Unverified position source times -> not eligible.
        SessionRunner unverified(RunnerCfg(), SessionCfg(), {}, {}, {},
                                 Profile());
        unverified.PushPose(VerifiedPoseSample(1, 1000, 1000, true));
        unverified.PushPose(VerifiedPoseSample(2, 2000, 2000, true));
        unverified.PushPosition(VerifiedPositionSample(1, 1000, 1000, false));
        unverified.PushPosition(VerifiedPositionSample(2, 2000, 2000, false));
        record = unverified.ProcessFrame(frame, ctx, nullptr);
        CHECK(record.position_quality == PoseQuality::kReceiveNearest &&
                  record.position_reason == PoseReason::kSourceTimeUnverified,
              "unverified position source time stays receive grade");
        CHECK(!record.spatial_eligible, "unverified position not eligible");

        // Unverified exposure -> receive grade even with verified rings.
        SessionRunner no_exposure(RunnerCfg(), SessionCfg(), {}, {}, {},
                                  Profile());
        no_exposure.PushPose(VerifiedPoseSample(1, 1000, 1000, true));
        no_exposure.PushPose(VerifiedPoseSample(2, 2000, 2000, true));
        no_exposure.PushPosition(
            VerifiedPositionSample(1, 1000, 1000, true));
        no_exposure.PushPosition(
            VerifiedPositionSample(2, 2000, 2000, true));
        record = no_exposure.ProcessFrame(frame, MakeCtx(frame), nullptr);
        CHECK(record.attitude_reason == PoseReason::kCaptureTimeUnverified,
              "no exposure keeps receive grade");
        CHECK(!record.spatial_eligible,
              "receive proximity is never spatially eligible");
    }
    if (edge_test::failures == 0) {
        std::printf("replay: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
