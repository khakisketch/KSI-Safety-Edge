#include "ksi_safety/runtime/session_runner.hpp"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace ksi::safety {
namespace {

void AppendOutputs(std::vector<RuleOutput>* sink,
                   const std::vector<RuleOutput>& fresh) {
    for (std::size_t i = 0; i < fresh.size(); ++i) {
        sink->push_back(fresh[i]);
    }
}

}  // namespace

bool IsValidRunnerConfig(const RunnerConfig& config) {
    return config.detect_every_n_frames >= 1 &&
           config.attitude_ring_capacity > 0 &&
           config.position_ring_capacity > 0;
}

const char* ToString(TrackProvenance provenance) {
    switch (provenance) {
        case TrackProvenance::kObserved:
            return "OBSERVED";
        case TrackProvenance::kMissed:
            return "MISSED";
        case TrackProvenance::kHeld:
            return "HELD";
    }
    return "UNKNOWN";
}

SessionRunner::SessionRunner(const RunnerConfig& config,
                             const SessionConfig& session_config,
                             const std::vector<RiskRule>& proximity_rules,
                             const std::vector<RiskRule>& zone_rules,
                             const std::vector<ZoneRevision>& zones,
                             const ModelProfile& profile)
    : config_(config),
      config_valid_(IsValidRunnerConfig(config)),
      attitude_(config.attitude_ring_capacity),
      position_(config.position_ring_capacity),
      session_(session_config, proximity_rules, zone_rules, zones),
      profile_(profile) {}

PushVerdict SessionRunner::PushPose(const Sample<Quaternion>& sample) {
    return attitude_.Push(sample);
}

PushVerdict SessionRunner::PushPosition(const Sample<Vec3>& sample) {
    return position_.Push(sample);
}

TickRecord SessionRunner::ProcessFrame(const FramePacket& frame,
                                       const FrameContext& ctx,
                                       DetectorPort* detector) {
    TickRecord out;
    out.tick_index = tick_index_;
    out.frame_id = ctx.frame_id;
    out.stream_id = ctx.stream_id;
    out.receive_mono_ns = ctx.receive_monotonic_ns;
    out.model_id = profile_.model_id;
    out.model_digest = profile_.model_digest;
    out.exposure_verified = ctx.has_exposure && ctx.exposure.verified;

    // 1) Detection attempt: exactly once on cadence ticks, never gated by
    // telemetry state (principle: AI stays up when alignment fails).
    const bool detect_this_tick =
        config_valid_ && (tick_index_ % config_.detect_every_n_frames) == 0U;
    if (detect_this_tick && detector != nullptr) {
        const DetectionBatch batch = detector->Detect(frame);
        out.detection_status = batch.status;
        out.detections = batch.detections;
    }

    // 2) Temporal matching (both rings). Receive-nearest never promotes to
    // source-verified; only verified same-domain brackets do (MatchFrame).
    const MatchResult<Quaternion> attitude =
        MatchFrame(ctx, attitude_, config_.match);
    out.attitude_quality = attitude.quality;
    out.attitude_reason = attitude.reason;
    out.attitude_gap_ns = attitude.nearest_gap_ns;
    const MatchResult<Vec3> position =
        MatchFrame(ctx, position_, config_.match);
    out.position_quality = position.quality;
    out.position_reason = position.reason;
    out.position_gap_ns = position.nearest_gap_ns;
    out.spatial_eligible =
        attitude.quality == PoseQuality::kSourceVerified &&
        position.quality == PoseQuality::kSourceVerified;

    // 3) Session step. Only a kOk detection run is evidence; skip/failure
    // ticks must not touch the tracker (they are observation gaps, not
    // negatives). Every tick then sweeps the episode gap clock.
    const std::uint64_t now_ns =
        static_cast<std::uint64_t>(ctx.receive_monotonic_ns);
    if (out.detection_status == DetectionStatus::kOk) {
        const SessionOutput session_out =
            session_.Tick(now_ns, out.detections, {});
        out.proximity = session_out.proximity;
        out.zone = session_out.zone;
        out.tracks.reserve(session_out.tracks.size());
        for (const Track& track : session_out.tracks) {
            TrackRecord record;
            record.track = track;
            record.provenance = track.last_seen_ns == now_ns
                                    ? TrackProvenance::kObserved
                                    : TrackProvenance::kMissed;
            record.since_observed_ns =
                static_cast<std::int64_t>(now_ns - track.last_seen_ns);
            out.tracks.push_back(record);
        }
    } else {
        const std::vector<Track> live = session_.LiveTracks();
        out.tracks.reserve(live.size());
        for (const Track& track : live) {
            TrackRecord record;
            record.track = track;
            record.provenance = TrackProvenance::kHeld;
            record.since_observed_ns =
                static_cast<std::int64_t>(now_ns - track.last_seen_ns);
            out.tracks.push_back(record);
        }
    }
    const SessionOutput sweep = session_.Sweep(now_ns);
    AppendOutputs(&out.proximity, sweep.proximity);
    AppendOutputs(&out.zone, sweep.zone);

    ++tick_index_;
    return out;
}

}  // namespace ksi::safety
