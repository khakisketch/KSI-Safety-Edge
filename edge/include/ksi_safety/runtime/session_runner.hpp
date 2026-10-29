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
#include "ksi_safety/spatial/ground_estimator.hpp"
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
    // R4 spatial estimation. When false, every tick records spatial as
    // DISABLED and no observations are built (detection/tracking unchanged).
    // When true, observations are estimated only on spatially eligible
    // ticks (verified pose + verified exposure) from FRESH tracks; the
    // session evaluates proximity/zone rules with them.
    bool enable_spatial = false;
    SpatialEstimatorConfig spatial;
    // P1 stream health: caller-clock thresholds for the frame-independent
    // HealthTick. Without any frame ingress for stale_after_ns the video
    // input degrades (video_input_stale); past lost_after_ns it is lost
    // (video_input_lost, contract §3). Non-positive disables that step.
    std::int64_t video_stale_after_ns = 1000000000LL;  // 1 s
    std::int64_t video_lost_after_ns = 5000000000LL;   // 5 s
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

// Tick-level spatial outcome (R4).
enum class SpatialTickReason {
    kDisabled,     // enable_spatial = false (or no fresh evidence on the tick)
    kNotEligible,  // no fresh detection evidence, or pose/exposure unverified:
                   // detection/tracking kept, spatial withheld
    kEstimated,    // eligible; per-track records in TickRecord::spatial
};

const char* ToString(SpatialTickReason reason);

// --- Runtime run-control vocabulary (Unit A / contract §2) ------------------
// REQUEST_ACCEPTED: commands accepted, nothing installed or started.
// CONFIG_APPLIED: the applied bundle's rules/zones are installed in the
//   engine (effective_config_hash identifies them).
// STARTING: start requested, no frame processed yet (count is 0).
// RUNNING: frames are being processed, inputs valid, no install holds.
// DEGRADED: processing continues but input/inference is incomplete or the
//   install holds verdicts (see degraded_reasons + input_state).
// STOPPED: processing ended, evidence closed out.
enum class RuntimeState {
    kRequestAccepted,
    kConfigApplied,
    kStarting,
    kRunning,
    kDegraded,
    kStopped,
};

const char* ToString(RuntimeState state);

// Contract §3 input health: each of video/telemetry/engine reports one of
// ok|stale|lost|failed|absent. The runner emits ok|stale|lost (video),
// ok|stale|absent (telemetry), ok|failed|absent (engine). "lost" video is
// reserved for stream starvation observed via the frame-independent
// HealthTick (caller clock), never guessed from a single tick.
struct RuntimeInputState {
    std::string video = "absent";
    std::string telemetry = "absent";
    std::string engine = "absent";
};

struct RuntimeInstallResult {
    bool ok = false;
    // On success: install holds (subset of the contract §3 code list —
    // ACTIVE rejections, zone holds). On failure: the fail-closed reason.
    std::vector<std::string> reasons;
    std::string effective_config_hash;
    std::uint64_t config_epoch = 0;
    bool config_applied = false;
};

// Point-in-time copy of the runtime facts MISSION_STATUS reports. Every
// field is explicit-presence: absence means "unknown", never false.
struct RuntimeSnapshot {
    RuntimeState state = RuntimeState::kRequestAccepted;
    bool config_applied = false;
    std::string effective_config_hash;
    std::uint64_t config_epoch = 0;
    std::uint64_t processed_frame_count = 0;
    std::int64_t last_processed_frame_mono_ns = 0;
    bool has_processed_frame = false;
    std::vector<std::string> degraded_reasons;
    RuntimeInputState input_state;
};

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
    SpatialTickReason spatial_status = SpatialTickReason::kDisabled;
    std::vector<SpatialObservationRecord> spatial;  // fresh tracks only
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

    // --- Runtime install + run control (Unit A / contract G9) ----------------
    //
    // The PSDK app gates live processing on SESSION_START/STOP: no start
    // means no processing, a stop ends it. The gate is enforced inside
    // ProcessFrame (a disabled tick processes no frame, touches no tracker
    // or episode state, and leaves tick_index_ alone), so the
    // "STOPPED => no frame is processed" invariant holds no matter what the
    // caller does. The counter processed_frame_count() advances only on
    // processed ticks.
    //
    // State machine: REQUEST_ACCEPTED (initial) -> CONFIG_APPLIED (install)
    // -> STARTING (start requested, no frame yet) -> RUNNING <-> DEGRADED
    // (per-tick input/install health) -> STOPPED. Reinstall while
    // RUNNING/DEGRADED/STARTING is rejected (stop first); reinstall after a
    // stop returns to CONFIG_APPLIED. Direct ProcessFrame calls outside run
    // control (the Replay driver) keep working: they process and count, but
    // never move the state.
    RuntimeInstallResult ApplyRuntimeConfig(
        std::vector<RiskRule> proximity_rules,
        std::vector<RiskRule> zone_rules, std::vector<ZoneRevision> zones,
        std::string effective_config_hash,
        std::vector<std::string> degraded_reasons);
    // Idempotent start: opens the gate, resets the processed-frame counter
    // for the new execution, enters STARTING. Allowed from any settled
    // state (including without an installed config — config_applied then
    // stays false and the run is reported as such).
    void RequestStart();
    // Clean stop: closes open episodes with kSessionEnd at now_mono_ns
    // (caller-provided data, no clock read), closes the gate, enters
    // STOPPED, and returns the closeout outputs for the evidence path.
    SessionOutput RequestStop(std::uint64_t now_mono_ns);
    // Identity loss (flight re-adoption / reboot without a clean stop):
    // closes the gate and parks the state at STOPPED without touching
    // episode state (the next install reconstructs the session).
    void AbortRuntime();
    // Low-level gate. The PSDK app sets false before its worker starts and
    // the run-control edges flip it; the Replay driver never touches it
    // (default true preserves the replay path exactly).
    void SetProcessingEnabled(bool enabled);
    bool processing_enabled() const { return processing_enabled_; }

    RuntimeSnapshot Snapshot() const;
    RuntimeState runtime_state() const { return runtime_state_; }
    std::uint64_t processed_frame_count() const {
        return processed_frame_count_;
    }
    std::uint64_t config_epoch() const { return config_epoch_; }
    bool config_applied() const { return config_applied_; }
    const std::string& effective_config_hash() const {
        return effective_config_hash_;
    }

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
    // The actually loaded model profile (id + file-bound digest). The
    // run-control install check compares enabled bundle profiles against
    // this — never asserts a requested model while the bytes differ.
    const ModelProfile& profile() const { return profile_; }

    // P1 stream health, independent of frames: re-evaluates the video input
    // against caller-provided now_mono_ns (no clock read inside). A started
    // session that receives no frames degrades (video_input_stale, then
    // video_input_lost) instead of reporting healthy inputs forever; a
    // restored frame recovers via the normal frame path. Never promotes or
    // demotes the run state beyond what RefreshRunHealth would conclude.
    void HealthTick(std::int64_t now_mono_ns);

    // Latches a durable-evidence failure (spool journal refused /
    // unwritable spool). Sticky: once latched the snapshot reports
    // session_capacity_exceeded and the state never returns to RUNNING.
    // New observation work must stop; the reason feeds the closing log.
    void NoteDurableFailure(const std::string& reason);
    bool hasDurableFailure() const { return !durable_failure_.empty(); }
    const std::string& durableFailureReason() const {
        return durable_failure_;
    }

    // Latches a sticky, NON-FATAL evidence gap (bounded autonomy store
    // saturated). Unlike NoteDurableFailure it never pauses processing: the
    // operator keeps receiving live observation/assessment facts, and the
    // record explicitly says which part could not be kept.
    void NoteEvidenceSaturation(const std::string& reason);
    bool hasEvidenceSaturation() const { return !evidence_saturation_.empty(); }
    const std::string& evidenceSaturationReason() const {
        return evidence_saturation_;
    }

  private:
    RunnerConfig config_;
    bool config_valid_ = false;
    std::uint64_t tick_index_ = 0;
    TopicRing<Quaternion> attitude_;
    TopicRing<Vec3> position_;
    SessionConfig session_config_;
    SafetySession session_;
    GroundEstimator estimator_;
    ModelProfile profile_;
    // --- run control (Unit A) -------------------------------------------------
    bool processing_enabled_ = true;
    RuntimeState runtime_state_ = RuntimeState::kRequestAccepted;
    std::uint64_t config_epoch_ = 0;
    bool config_applied_ = false;
    std::string effective_config_hash_;
    std::vector<std::string> persistent_degraded_;
    std::uint64_t processed_frame_count_ = 0;
    std::int64_t last_processed_frame_mono_ns_ = 0;
    bool has_processed_frame_ = false;
    bool detector_seen_ = false;
    DetectionStatus last_detection_status_ = DetectionStatus::kNotRun;
    // Engine verdict tracks the last *attempt*: a cadence skip (kNotRun) is
    // no new information and must never clear a recorded failure back to
    // ok, nor count as a successful inference.
    DetectionStatus last_attempt_status_ = DetectionStatus::kNotRun;
    std::uint64_t consecutive_non_ok_ = 0;
    PoseQuality last_attitude_quality_ = PoseQuality::kNone;
    PoseQuality last_position_quality_ = PoseQuality::kNone;
    RuntimeInputState input_state_;
    std::vector<std::string> dynamic_degraded_;
    // Sticky durable-evidence failure (spool journal refused / unwritable):
    // once set the run never reports RUNNING again. Set with the truthful
    // IO reason; reported as the contract code session_capacity_exceeded.
    std::string durable_failure_;
    // Sticky non-fatal evidence gap (autonomy store saturated). Reported on
    // facts; NEVER consulted by the processing gate.
    std::string evidence_saturation_;
    // Frame ingress, independent of the processing gate: even a gated
    // (dropped) frame proves the video stream is alive.
    bool has_ingress_ = false;
    std::int64_t last_ingress_mono_ns_ = 0;

    void RefreshRunHealth();
    // Rebuilds dynamic_degraded_ from the current input_state_ grades and
    // applies the STARTING/RUNNING/DEGRADED promotion rule. Shared by
    // RefreshRunHealth (frame path) and HealthTick (clock path) so the two
    // can never disagree on what the grades mean.
    void RecomputeHealth();
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RUNTIME_SESSION_RUNNER_HPP
