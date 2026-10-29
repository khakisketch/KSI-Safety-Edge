// Host test for P1 stream health: the frame-independent HealthTick and the
// attempt-based engine verdict (edge/include/ksi_safety/runtime/
// session_runner.hpp).
//
// Covers: a started session with no frames degrades (video_input_stale,
// never forever-healthy STARTING); ingress gaps escalate stale -> lost
// (video_input_lost, contract §3) after the configured thresholds; a
// restored frame recovers when the other inputs are okay; gated (dropped)
// frames still prove the stream alive; idle states keep video absent;
// a cadence skip after an inference failure never clears engine_failed.

#include "ksi_safety/runtime/session_runner.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/transport/json.hpp"

#include "support/check.hpp"

using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::ModelProfile;
using ksi::safety::PoseQuality;
using ksi::safety::Quaternion;
using ksi::safety::RunnerConfig;
using ksi::safety::RuntimeSnapshot;
using ksi::safety::RuntimeState;
using ksi::safety::Sample;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::Timestamp;
using ksi::safety::TimeSourceKind;
using ksi::safety::Vec3;

namespace {

constexpr std::int64_t kMs = 1000000LL;
constexpr std::int64_t kStaleAfter = 100 * kMs;
constexpr std::int64_t kLostAfter = 500 * kMs;

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

RunnerConfig HealthCfg(std::uint64_t detect_every = 1) {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = detect_every;
    config.video_stale_after_ns = kStaleAfter;
    config.video_lost_after_ns = kLostAfter;
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

SessionRunner FreshRunner(std::uint64_t detect_every = 1) {
    return SessionRunner(HealthCfg(detect_every), SessionCfg(), {}, {}, {},
                         Profile());
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

// Verified pose + position around recv_ns (the healthy-telemetry pattern).
// base_id keeps repeated pushes distinct: rings reject duplicate ids.
void PushVerifiedTelemetry(SessionRunner* runner, std::int64_t recv_ns,
                           std::uint64_t base_id = 1) {
    Sample<Quaternion> pose;
    pose.sample_id = base_id;
    pose.seq = base_id;
    pose.received = VerifiedTs(recv_ns - kMs);
    pose.has_source_time = true;
    pose.source_time = VerifiedTs(recv_ns);
    Sample<Vec3> position;
    position.sample_id = base_id + 1;
    position.seq = base_id + 1;
    position.received = VerifiedTs(recv_ns - kMs);
    position.has_source_time = true;
    position.source_time = VerifiedTs(recv_ns);
    runner->PushPose(pose);
    runner->PushPosition(position);
}

bool HasReason(const RuntimeSnapshot& snapshot, const std::string& code) {
    for (const auto& reason : snapshot.degraded_reasons) {
        if (reason == code) return true;
    }
    return false;
}

void InstallAndStart(SessionRunner* runner) {
    CHECK(runner->ApplyRuntimeConfig({}, {}, {}, "hash-health", {}).ok,
          "install for the health test");
    runner->RequestStart();
}

}  // namespace

int main() {
    // ---- 1) start with a dead stream degrades, never healthy --------------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "starting before any health tick");
        // No frame ever ingressed: stale, not forever-healthy STARTING.
        runner.HealthTick(10 * kMs);
        const RuntimeSnapshot snapshot = runner.Snapshot();
        CHECK(snapshot.input_state.video == "stale",
              "no ingress while started reads stale");
        CHECK(snapshot.state == RuntimeState::kDegraded,
              "dead stream at start degrades (never healthy STARTING)");
        CHECK(HasReason(snapshot, "video_input_stale"),
              "stale reason reported");
    }

    // ---- 2) idle states keep video absent ----------------------------------
    {
        SessionRunner runner = FreshRunner();
        runner.HealthTick(60 * 1000 * kMs);
        CHECK(runner.Snapshot().input_state.video == "absent",
              "idle runner keeps video absent");
        CHECK(runner.runtime_state() == RuntimeState::kRequestAccepted,
              "idle health tick moves no state");
    }

    // ---- 3) gap escalates stale -> lost ------------------------------------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        OkDetector detector;
        const std::int64_t t0 = 1000 * kMs;
        PushVerifiedTelemetry(&runner, t0);
        FramePacket frame = MakeFrame(1, t0);
        FrameContext ctx = MakeCtx(frame);
        ctx.has_exposure = true;
        ctx.exposure = VerifiedTs(t0);
        runner.ProcessFrame(frame, ctx, &detector);
        CHECK(runner.runtime_state() == RuntimeState::kRunning,
              "healthy tick runs");
        runner.HealthTick(t0 + kStaleAfter + kMs);
        CHECK(runner.Snapshot().input_state.video == "stale",
              "gap past stale threshold reads stale");
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "stale stream degrades");
        CHECK(HasReason(runner.Snapshot(), "video_input_stale"),
              "stale reason reported");
        runner.HealthTick(t0 + kLostAfter + kMs);
        const RuntimeSnapshot lost = runner.Snapshot();
        CHECK(lost.input_state.video == "lost",
              "gap past lost threshold reads lost");
        CHECK(HasReason(lost, "video_input_lost"),
              "lost reason reported (contract vocab)");
        CHECK(!HasReason(lost, "video_input_stale"),
              "lost replaces stale (never both)");
    }

    // ---- 4) restored frame recovers when other inputs are okay ------------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        OkDetector detector;
        const std::int64_t t0 = 1000 * kMs;
        PushVerifiedTelemetry(&runner, t0);
        FramePacket first = MakeFrame(1, t0);
        FrameContext ctx0 = MakeCtx(first);
        ctx0.has_exposure = true;
        ctx0.exposure = VerifiedTs(t0);
        runner.ProcessFrame(first, ctx0, &detector);
        CHECK(runner.runtime_state() == RuntimeState::kRunning,
              "healthy tick runs");
        runner.HealthTick(t0 + kLostAfter + kMs);
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "lost stream degrades");
        // Stream resumes with fresh verified telemetry alongside.
        const std::int64_t t1 = t0 + kLostAfter + 10 * kMs;
        PushVerifiedTelemetry(&runner, t1, 10);
        FramePacket revived = MakeFrame(2, t1);
        FrameContext ctx1 = MakeCtx(revived);
        ctx1.has_exposure = true;
        ctx1.exposure = VerifiedTs(t1);
        runner.ProcessFrame(revived, ctx1, &detector);
        const RuntimeSnapshot recovered = runner.Snapshot();
        CHECK(recovered.input_state.video == "ok",
              "resumed frame recovers video");
        CHECK(recovered.state == RuntimeState::kRunning,
              "run recovers when the other inputs are okay");
    }

    // ---- 5) gated frames still prove the stream alive ----------------------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        OkDetector detector;
        const std::int64_t t0 = 1000 * kMs;
        FramePacket frame = MakeFrame(1, t0);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        runner.RequestStop(static_cast<std::uint64_t>(t0));
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "stopped");
        // Dropped frames still ingress: the stream is alive, the gate is
        // closed. HealthTick must not stale the video or move the state.
        const std::int64_t t1 = t0 + kLostAfter + 10 * kMs;
        FramePacket dropped = MakeFrame(2, t1);
        runner.ProcessFrame(dropped, MakeCtx(dropped), &detector);
        CHECK(runner.processed_frame_count() == 1,
              "gated frame processes nothing");
        runner.HealthTick(t1 + kMs);
        CHECK(runner.Snapshot().input_state.video != "stale" &&
                  runner.Snapshot().input_state.video != "lost",
              "fresh gated ingress keeps video off stale/lost");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "health tick never moves STOPPED");
    }

    // ---- 6) cadence skip never clears an inference failure ----------------
    {
        SessionRunner runner = FreshRunner(2);  // detect on tick 0, 2, ...
        InstallAndStart(&runner);
        FailDetector failing;
        FramePacket first = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(first, MakeCtx(first), &failing);
        CHECK(runner.Snapshot().input_state.engine == "failed",
              "failed attempt reports engine failed");
        // Tick 1 is a cadence skip (kNotRun): no new information — the
        // failure verdict must stand, and the skip counts as no inference.
        FramePacket skip = MakeFrame(2, 20 * kMs);
        const auto skipped =
            runner.ProcessFrame(skip, MakeCtx(skip), &failing);
        CHECK(skipped.detection_status == DetectionStatus::kNotRun,
              "cadence tick attempts nothing");
        const RuntimeSnapshot held = runner.Snapshot();
        CHECK(held.input_state.engine == "failed",
              "NOT_RUN never clears engine_failed");
        CHECK(HasReason(held, "engine_failed"),
              "failure reason persists across the skip");
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "still degraded");
    }

    // ---- 7) successful attempt recovers the engine -------------------------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        FailDetector failing;
        FramePacket first = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(first, MakeCtx(first), &failing);
        CHECK(runner.Snapshot().input_state.engine == "failed",
              "failed attempt reports engine failed");
        OkDetector recovered;
        FramePacket second = MakeFrame(2, 20 * kMs);
        runner.ProcessFrame(second, MakeCtx(second), &recovered);
        CHECK(runner.Snapshot().input_state.engine == "ok",
              "successful attempt recovers the engine verdict");
    }

    // ---- 8) durable failure latches DEGRADED, never back to RUNNING ------
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        CHECK(!runner.hasDurableFailure(), "no failure latched initially");
        runner.NoteDurableFailure("spool journal refused (full)");
        CHECK(runner.hasDurableFailure(), "failure latched");
        CHECK(runner.durableFailureReason() ==
                  "spool journal refused (full)",
              "truthful IO reason retained");
        const RuntimeSnapshot latched = runner.Snapshot();
        CHECK(HasReason(latched, "session_capacity_exceeded"),
              "capacity-exceeded reason reported in the facts");
        CHECK(latched.state == RuntimeState::kDegraded,
              "durable failure degrades the run");
        // Even with healthy inputs afterwards, the run never reports
        // RUNNING again while the failure is latched.
        OkDetector detector;
        PushVerifiedTelemetry(&runner, 100 * kMs);
        FramePacket frame = MakeFrame(1, 100 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        const RuntimeSnapshot after = runner.Snapshot();
        CHECK(after.state != RuntimeState::kRunning,
              "latched failure never returns to RUNNING");
        CHECK(HasReason(after, "session_capacity_exceeded"),
              "reason persists across ticks");
    }

    // ---- 8) latched failure pauses work without stopping -----------
    // After NoteDurableFailure the gate in ProcessFrame stays closed: no
    // detector calls, no counter advance, no tick index advance — and the
    // state stays DEGRADED (never a clean STOPPED).
    {
        SessionRunner runner = FreshRunner();
        InstallAndStart(&runner);
        OkDetector detector;
        PushVerifiedTelemetry(&runner, 100 * kMs);
        FramePacket first = MakeFrame(1, 100 * kMs);
        FrameContext ctx0 = MakeCtx(first);
        ctx0.has_exposure = true;
        ctx0.exposure = VerifiedTs(100 * kMs);
        runner.ProcessFrame(first, ctx0, &detector);
        CHECK(runner.runtime_state() == RuntimeState::kRunning,
              "healthy tick runs before the failure");
        CHECK(detector.calls == 1, "detector ran once");
        const std::uint64_t tick_before = runner.TickCount();
        runner.NoteDurableFailure("spool journal refused (full)");
        for (int i = 0; i < 3; ++i) {
            FramePacket frame =
                MakeFrame(static_cast<std::uint32_t>(2 + i),
                          (200 + i) * kMs);
            const auto tick = runner.ProcessFrame(frame, MakeCtx(frame),
                                                  &detector);
            CHECK(tick.detection_status == DetectionStatus::kNotRun,
                  "gated tick attempts nothing");
        }
        CHECK(detector.calls == 1, "detector never called after the latch");
        CHECK(runner.processed_frame_count() == 1,
              "no frames advance after the failure");
        CHECK(runner.TickCount() == tick_before,
              "tick index frozen while suspended");
        CHECK(runner.runtime_state() == RuntimeState::kDegraded,
              "suspended, not cleanly stopped");
        CHECK(HasReason(runner.Snapshot(), "session_capacity_exceeded"),
              "gap reason persists while suspended");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
