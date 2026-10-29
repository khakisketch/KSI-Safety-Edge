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

const char* ToString(SpatialTickReason reason) {
    switch (reason) {
        case SpatialTickReason::kDisabled:
            return "DISABLED";
        case SpatialTickReason::kNotEligible:
            return "NOT_ELIGIBLE";
        case SpatialTickReason::kEstimated:
            return "ESTIMATED";
    }
    return "UNKNOWN";
}

const char* ToString(RuntimeState state) {
    switch (state) {
        case RuntimeState::kRequestAccepted:
            return "REQUEST_ACCEPTED";
        case RuntimeState::kConfigApplied:
            return "CONFIG_APPLIED";
        case RuntimeState::kStarting:
            return "STARTING";
        case RuntimeState::kRunning:
            return "RUNNING";
        case RuntimeState::kDegraded:
            return "DEGRADED";
        case RuntimeState::kStopped:
            return "STOPPED";
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
      session_config_(session_config),
      session_(session_config, proximity_rules, zone_rules, zones),
      estimator_(config.spatial),
      profile_(profile) {}

PushVerdict SessionRunner::PushPose(const Sample<Quaternion>& sample) {
    return attitude_.Push(sample);
}

PushVerdict SessionRunner::PushPosition(const Sample<Vec3>& sample) {
    return position_.Push(sample);
}

void SessionRunner::ResetTracker() {
    session_.ResetTracker();
}

// --- run control (Unit A) --------------------------------------------------------

namespace {

// Contract §3 degraded-reason codes produced from per-tick input health.
constexpr const char* kReasonVideoInputStale = "video_input_stale";
constexpr const char* kReasonVideoInputLost = "video_input_lost";
constexpr const char* kReasonTelemetryAbsent = "telemetry_absent";
constexpr const char* kReasonTelemetryStale = "telemetry_stale";
constexpr const char* kReasonEngineFailed = "engine_failed";
// Contract §3 code for lost durable evidence capacity (spool refused /
// unwritable): the persisted record set has a known gap.
constexpr const char* kReasonSessionCapacityExceeded =
    "session_capacity_exceeded";
// Distinct, additive code: the OPTIONAL bounded autonomy record reached its
// declared capacity. Observation continues; the gap is explicit. Deliberately
// NOT session_capacity_exceeded, which means the required flight record is
// lost and pauses new work.
constexpr const char* kReasonAutonomyEvidenceSaturated =
    "autonomy_evidence_saturated";

}  // namespace

RuntimeInstallResult SessionRunner::ApplyRuntimeConfig(
    std::vector<RiskRule> proximity_rules, std::vector<RiskRule> zone_rules,
    std::vector<ZoneRevision> zones, std::string effective_config_hash,
    std::vector<std::string> degraded_reasons) {
    RuntimeInstallResult result;
    result.effective_config_hash = effective_config_hash_;
    result.config_epoch = config_epoch_;
    result.config_applied = config_applied_;
    if (runtime_state_ == RuntimeState::kStarting ||
        runtime_state_ == RuntimeState::kRunning ||
        runtime_state_ == RuntimeState::kDegraded) {
        // Fail closed: a live execution keeps its installed config. Stop
        // first; the RC detects the stale install via the unchanged
        // effective_config_hash.
        result.ok = false;
        result.reasons.emplace_back("reinstall_while_running");
        return result;
    }
    session_ = SafetySession(session_config_, proximity_rules, zone_rules,
                             zones);
    ++config_epoch_;
    config_applied_ = true;
    effective_config_hash_ = std::move(effective_config_hash);
    persistent_degraded_ = std::move(degraded_reasons);
    processed_frame_count_ = 0;
    has_processed_frame_ = false;
    consecutive_non_ok_ = 0;
    last_detection_status_ = DetectionStatus::kNotRun;
    last_attitude_quality_ = PoseQuality::kNone;
    last_position_quality_ = PoseQuality::kNone;
    input_state_ = RuntimeInputState{};
    dynamic_degraded_.clear();
    runtime_state_ = RuntimeState::kConfigApplied;
    result.ok = true;
    result.reasons = persistent_degraded_;
    result.effective_config_hash = effective_config_hash_;
    result.config_epoch = config_epoch_;
    result.config_applied = true;
    return result;
}

void SessionRunner::RequestStart() {
    processing_enabled_ = true;
    processed_frame_count_ = 0;
    has_processed_frame_ = false;
    consecutive_non_ok_ = 0;
    last_detection_status_ = DetectionStatus::kNotRun;
    last_attitude_quality_ = PoseQuality::kNone;
    last_position_quality_ = PoseQuality::kNone;
    input_state_ = RuntimeInputState{};
    dynamic_degraded_.clear();
    runtime_state_ = RuntimeState::kStarting;
}

SessionOutput SessionRunner::RequestStop(std::uint64_t now_mono_ns) {
    SessionOutput out = session_.EndSession(now_mono_ns);
    processing_enabled_ = false;
    runtime_state_ = RuntimeState::kStopped;
    return out;
}

void SessionRunner::AbortRuntime() {
    processing_enabled_ = false;
    runtime_state_ = RuntimeState::kStopped;
}

void SessionRunner::SetProcessingEnabled(bool enabled) {
    processing_enabled_ = enabled;
}

void SessionRunner::RefreshRunHealth() {
    // Video: frame ingress through ProcessFrame. A cadence skip is normal
    // (up to detect_every_n_frames - 1 in a row); a longer kNotRun run
    // means detection should have produced but did not. An inference
    // failure is the engine's, not the video path's — it leaves the video
    // verdict untouched.
    if (last_detection_status_ == DetectionStatus::kOk) {
        consecutive_non_ok_ = 0;
        input_state_.video = "ok";
    } else if (last_detection_status_ == DetectionStatus::kNotRun) {
        ++consecutive_non_ok_;
        input_state_.video =
            (consecutive_non_ok_ >= config_.detect_every_n_frames &&
             config_.detect_every_n_frames > 0)
                ? "stale"
                : "ok";
    }
    // Telemetry: both rings verified is the only ok; empty rings are
    // absent, anything in between is stale (present but unverified).
    if (last_attitude_quality_ == PoseQuality::kSourceVerified &&
        last_position_quality_ == PoseQuality::kSourceVerified) {
        input_state_.telemetry = "ok";
    } else if (last_attitude_quality_ == PoseQuality::kNone &&
               last_position_quality_ == PoseQuality::kNone) {
        input_state_.telemetry = "absent";
    } else {
        input_state_.telemetry = "stale";
    }
    // Engine: no detector was ever bound (absent) and a failed *attempt*
    // (failed) both mean no inference is produced; input_state keeps the
    // cause apart while the degraded reason reports the effect. The verdict
    // tracks attempts, not ticks: a cadence skip (kNotRun) is no new
    // information — it must never clear a recorded failure, nor count as a
    // successful inference.
    if (!detector_seen_) {
        input_state_.engine = "absent";
    } else if (last_attempt_status_ == DetectionStatus::kInferenceFailure) {
        input_state_.engine = "failed";
    } else {
        input_state_.engine = "ok";
    }

    RecomputeHealth();
}

void SessionRunner::RecomputeHealth() {
    dynamic_degraded_.clear();
    if (input_state_.video == "stale") {
        dynamic_degraded_.push_back(kReasonVideoInputStale);
    } else if (input_state_.video == "lost") {
        dynamic_degraded_.push_back(kReasonVideoInputLost);
    }
    if (input_state_.telemetry == "absent") {
        dynamic_degraded_.push_back(kReasonTelemetryAbsent);
    } else if (input_state_.telemetry == "stale") {
        dynamic_degraded_.push_back(kReasonTelemetryStale);
    }
    if (input_state_.engine == "failed" || input_state_.engine == "absent") {
        dynamic_degraded_.push_back(kReasonEngineFailed);
    }
    if (!durable_failure_.empty()) {
        dynamic_degraded_.push_back(kReasonSessionCapacityExceeded);
    }
    if (!evidence_saturation_.empty()) {
        dynamic_degraded_.push_back(kReasonAutonomyEvidenceSaturated);
    }

    if (runtime_state_ == RuntimeState::kStarting ||
        runtime_state_ == RuntimeState::kRunning ||
        runtime_state_ == RuntimeState::kDegraded) {
        const bool degraded = !persistent_degraded_.empty() ||
                              !dynamic_degraded_.empty();
        runtime_state_ =
            degraded ? RuntimeState::kDegraded : RuntimeState::kRunning;
    }
}

void SessionRunner::NoteDurableFailure(const std::string& reason) {
    if (durable_failure_.empty()) {
        durable_failure_ = reason;
    }
    RecomputeHealth();
}

void SessionRunner::NoteEvidenceSaturation(const std::string& reason) {
    if (evidence_saturation_.empty()) {
        evidence_saturation_ = reason.empty() ? "store_capacity_exhausted"
                                              : reason;
        RecomputeHealth();
    }
}

void SessionRunner::HealthTick(std::int64_t now_mono_ns) {    const bool executing = runtime_state_ == RuntimeState::kStarting ||
                           runtime_state_ == RuntimeState::kRunning ||
                           runtime_state_ == RuntimeState::kDegraded;
    if (!has_ingress_) {
        // No frame ever ingressed: a session that expects video but receives
        // none is stale — a start with a dead stream must not report
        // healthy inputs forever. Idle states keep video absent.
        if (executing) {
            input_state_.video = "stale";
        } else {
            input_state_.video = "absent";
        }
    } else if (now_mono_ns >= last_ingress_mono_ns_) {
        const std::int64_t gap_ns = now_mono_ns - last_ingress_mono_ns_;
        if (config_.video_lost_after_ns > 0 &&
            gap_ns >= config_.video_lost_after_ns) {
            input_state_.video = "lost";
        } else if (config_.video_stale_after_ns > 0 &&
                   gap_ns >= config_.video_stale_after_ns) {
            input_state_.video = "stale";
        }
        // Fresh ingress: the video verdict belongs to the frame path
        // (RefreshRunHealth), which recovers it on the next processed tick.
    }
    RecomputeHealth();
}

RuntimeSnapshot SessionRunner::Snapshot() const {
    RuntimeSnapshot snapshot;
    snapshot.state = runtime_state_;
    snapshot.config_applied = config_applied_;
    snapshot.effective_config_hash = effective_config_hash_;
    snapshot.config_epoch = config_epoch_;
    snapshot.processed_frame_count = processed_frame_count_;
    snapshot.last_processed_frame_mono_ns = last_processed_frame_mono_ns_;
    snapshot.has_processed_frame = has_processed_frame_;
    snapshot.degraded_reasons = persistent_degraded_;
    for (const auto& reason : dynamic_degraded_) {
        snapshot.degraded_reasons.push_back(reason);
    }
    snapshot.input_state = input_state_;
    return snapshot;
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

    // Frame ingress proves the video stream is alive — recorded even when
    // the gate drops the frame (HealthTick's stream-loss verdict needs it).
    has_ingress_ = true;
    last_ingress_mono_ns_ = ctx.receive_monotonic_ns;

    if (!processing_enabled_ || !durable_failure_.empty()) {
        // G9 run control: SESSION_STOP (or never started) means NO frame is
        // processed. A latched durable-evidence failure (spool unwritable,
        // audit journal lost) pauses work the same way — WITHOUT declaring
        // a clean STOPPED: the run stays visibly DEGRADED (never RUNNING,
        // never a clean summary) until fresh revalidation, and there is no
        // silent auto-resume. The stub carries identity only: no detector
        // call, no tracker or episode step, no sweep, no counters, no state
        // change. detection_status stays kNotRun ("no attempt"), never a
        // negative.
        return out;
    }
    if (detector != nullptr) {
        detector_seen_ = true;
    }

    // 1) Detection attempt: exactly once on cadence ticks, never gated by
    // telemetry state (principle: AI stays up when alignment fails).
    const bool detect_this_tick =
        config_valid_ && (tick_index_ % config_.detect_every_n_frames) == 0U;
    if (detect_this_tick && detector != nullptr) {
        const DetectionBatch batch = detector->Detect(frame);
        out.detection_status = batch.status;
        out.detections = batch.detections;
        // The engine verdict tracks attempts, not ticks: a later cadence
        // skip must not clear a recorded failure back to ok.
        last_attempt_status_ = batch.status;
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
        const std::vector<Track> tracks =
            session_.UpdateTracks(now_ns, out.detections);
        // R4: build ground observations from the FRESH tracks (observed
        // this tick) with the verified, interpolated pose, then evaluate.
        std::vector<GroundObservation> observations;
        if (config_.enable_spatial) {
            std::vector<Track> fresh;
            fresh.reserve(tracks.size());
            for (const Track& track : tracks) {
                if (track.last_seen_ns == now_ns) {
                    fresh.push_back(track);
                }
            }
            const SpatialEstimate estimate = estimator_.Estimate(
                fresh, attitude, position, ctx, frame.metadata.width,
                frame.metadata.height);
            out.spatial_status = estimate.eligible
                                     ? SpatialTickReason::kEstimated
                                     : SpatialTickReason::kNotEligible;
            out.spatial = estimate.records;
            for (const SpatialObservationRecord& record : estimate.records) {
                if (record.has_observation) {
                    observations.push_back(record.observation);
                }
            }
        }
        const SessionOutput session_out =
            session_.Evaluate(now_ns, tracks, observations);
        out.proximity = session_out.proximity;
        out.zone = session_out.zone;
        out.tracks.reserve(tracks.size());
        for (const Track& track : tracks) {
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
        if (config_.enable_spatial) {
            // No fresh detection evidence this tick: spatial withheld.
            out.spatial_status = SpatialTickReason::kNotEligible;
        }
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

    // G9 run health: count the processed frame, latch the per-tick input
    // grades, and re-evaluate RUNNING <-> DEGRADED. States outside run
    // control (e.g. the Replay driver's direct calls) are left alone.
    ++processed_frame_count_;
    last_processed_frame_mono_ns_ =
        static_cast<std::int64_t>(ctx.receive_monotonic_ns);
    has_processed_frame_ = true;
    last_detection_status_ = out.detection_status;
    last_attitude_quality_ = out.attitude_quality;
    last_position_quality_ = out.position_quality;
    RefreshRunHealth();
    return out;
}

}  // namespace ksi::safety
