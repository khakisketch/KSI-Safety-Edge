// SessionRunner: the product processing path shared by the PSDK application
// and the Replay driver (R1). Input sources differ (live PSDK callbacks vs
// replay files); everything after ingestion is ONE code path:
//
//   PushPose / PushPosition   telemetry samples, arrival order. The runner
//                             is NOT thread-safe: the caller serialises
//                             (the PSDK app pushes under its per-ring
//                             mutexes; the replay driver is single-threaded).
//   ProcessFrame              one frame tick:
//     1. detection cadence gate  (detect_every_n_frames; skipped ticks are
//                                kNotRun and carry NO scene evidence)
//     2. detector->Detect()      EXACTLY ONCE on detection ticks, never
//                                gated by telemetry state
//     3. temporal matching       attitude + position rings (MatchFrame)
//     4. session step            kOk ticks: SafetySession::Tick with the
//                                fresh detections (clean negatives advance
//                                tracker misses); skip/failure ticks: no
//                                tracker update at all (state carried).
//                                EVERY tick ends with SafetySession::Sweep
//                                at the tick time (gap clock only).
//
// Evidence rules (user-mandated, R1/R2):
//   - A cadence skip or an inference failure is an observation gap: the
//     tracker state is carried unchanged and every track is recorded with
//     kHeld provenance ("last bbox maintained") — that is NOT a new
//     measurement and NOT motion tracking.
//   - A clean negative (kOk, zero detections) IS evidence and advances the
//     tracker's miss counters (kMissed provenance for unmatched tracks).
//   - v0 has NO motion model and therefore NO prediction: a track between
//     detection ticks is HELD state (the last matched box), never a
//     predicted position. If a motion model lands, it must record a
//     distinct PREDICTED provenance instead of overloading kHeld.
//   - Tracker age/misses advance per DETECTION tick (evidence tick), not
//     per frame tick: a cadence skip is not evaluated by the tracker at
//     all. "Consecutive missed frames" in the tracker rules therefore read
//     as "consecutive detection ticks without a match".
//   - spatial_eligible is true only when BOTH the attitude and the position
//     match are kSourceVerified: spatial work is gated on verified capture
//     alignment, and receive-time proximity never qualifies. No spatial
//     computation happens in R1 (R4 connects the estimator).
//
// Determinism: caller-provided timestamps only, no clock reads, no RNG,
// ordered containers. Identical inputs produce identical TickRecords.
// The session is SHADOW-only: activation is never granted here, and R1
// configures no risk rules (episodes arrive with R4 observations).

#ifndef KSI_SAFETY_RUNTIME_SESSION_RUNNER_HPP
#define KSI_SAFETY_RUNTIME_SESSION_RUNNER_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/perception/tracker.hpp"
#include "ksi_safety/risk/engine.hpp"
#include "ksi_safety/risk/episode.hpp"
#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/session/session.hpp"
#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"
#include "ksi_safety/time/topic_ring.hpp"

namespace ksi::safety {

struct RunnerConfig {
    MatchPolicy match;
    // Detection cadence: a detection runs on tick 0 and every Nth tick
    // after that; 1 = every frame. Values < 1 are invalid (fail-closed:
    // IsValidRunnerConfig false and the runner never runs a detection).
    std::uint64_t detect_every_n_frames = 1;
    // Telemetry ring capacities (must be > 0; TopicRing contract).
    std::size_t attitude_ring_capacity = 2048;
    std::size_t position_ring_capacity = 512;
};

// True iff detect_every_n_frames >= 1 and both ring capacities are > 0.
bool IsValidRunnerConfig(const RunnerConfig& config);

// Provenance of one live track in one tick (R2 semantics, recorded from R1).
enum class TrackProvenance {
    kObserved,  // matched a detection on this tick's run (new measurement)
    kMissed,    // detection ran this tick; this track matched nothing
                // (negative evidence; tracker miss advanced)
    kHeld,      // no detection evidence this tick (cadence skip / failure);
                // last state carried unchanged — NOT a new measurement
};

const char* ToString(TrackProvenance provenance);

struct TrackRecord {
    Track track;
    TrackProvenance provenance = TrackProvenance::kHeld;
    // receive_mono_ns - track.last_seen_ns; 0 exactly when kObserved.
    std::int64_t since_observed_ns = 0;
};

struct TickRecord {
    std::uint64_t tick_index = 0;
    std::uint32_t frame_id = 0;
    std::string stream_id;
    std::int64_t receive_mono_ns = 0;
    // Model provenance (part of the observation's source identity).
    std::string model_id;
    std::string model_digest;
    // Capture time: replay v1 and the current PSDK RGB stream both carry no
    // verified exposure time; false means "receive grade only".
    bool exposure_verified = false;
    DetectionStatus detection_status = DetectionStatus::kNotRun;
    std::vector<Detection> detections;  // fresh detections only
    PoseQuality attitude_quality = PoseQuality::kNone;
    PoseReason attitude_reason = PoseReason::kNoSamples;
    std::int64_t attitude_gap_ns = 0;
    PoseQuality position_quality = PoseQuality::kNone;
    PoseReason position_reason = PoseReason::kNoSamples;
    std::int64_t position_gap_ns = 0;
    bool spatial_eligible = false;
    std::vector<TrackRecord> tracks;
    std::vector<RuleOutput> proximity;  // Tick outputs, then sweep outputs
    std::vector<RuleOutput> zone;
};

class SessionRunner {
  public:
    SessionRunner(const RunnerConfig& config,
                  const SessionConfig& session_config,
                  const std::vector<RiskRule>& proximity_rules,
                  const std::vector<RiskRule>& zone_rules,
                  const std::vector<ZoneRevision>& zones,
                  const ModelProfile& profile);

    // Telemetry ingress. Order = arrival order; the rings reject duplicates
    // and receive-time rollbacks (PushVerdict returned to the caller).
    PushVerdict PushPose(const Sample<Quaternion>& sample);
    PushVerdict PushPosition(const Sample<Vec3>& sample);

    // One frame tick. The detector is called at most once (cadence gate).
    TickRecord ProcessFrame(const FramePacket& frame, const FrameContext& ctx,
                            DetectorPort* detector);

    // Tracker reset (model/stream switch): tracker_epoch += 1, all tracks
    // dropped, ids are never reused. Held/observed records after a reset
    // show the new epoch; lineages never merge across epochs (the old
    // epoch's open episodes close only via the max-gap OBSERVATION_LOST
    // path on the next Sweep).
    void ResetTracker();

    std::uint64_t TickCount() const { return tick_index_; }
    std::size_t AttitudeSamples() const { return attitude_.size(); }
    std::size_t PositionSamples() const { return position_.size(); }

  private:
    RunnerConfig config_;
    bool config_valid_ = false;
    std::uint64_t tick_index_ = 0;
    TopicRing<Quaternion> attitude_;
    TopicRing<Vec3> position_;
    SafetySession session_;
    ModelProfile profile_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RUNTIME_SESSION_RUNNER_HPP
