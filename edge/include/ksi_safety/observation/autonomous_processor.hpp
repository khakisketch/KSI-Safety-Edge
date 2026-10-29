// Unit A — passive autonomous assessment/evidence processor (Stage 1).
//
// ROLE: the shared wiring between the product processing path
// (SessionRunner::ProcessFrame) and the machine assessment/evidence output.
// Every Edge entry point (PSDK device worker, host mop_server, replay_run)
// drives autonomy through ProcessSharedTick below, so the host test and the
// device run execute the SAME code — not a standalone unused class.
//
// PER TICK (when enabled; disabled by default — explicit opt-in only):
//   1. runner processes the frame (detection -> tracking -> spatial ->
//      existing RiskEngine rules; no parallel risk logic here);
//   2. the tick's RuleOutputs are mapped to a typed Assessment (assessed vs
//      undetermined + missing reasons; SHADOW only; zero incidents / alerts /
//      human responses / camera / flight outputs);
//   3. the raw input frame (bytes included, even zero-detection frames) is
//      retained in the bounded volatile ring;
//   4. trigger ticks (fresh detections, inference failures, withheld
//      spatial verdicts) open pre/post capture windows; clean negatives join
//      open windows as context without opening their own;
//   5. closed windows + assessments persist as versioned JSON/raw artifacts
//      through the SAME EvidenceStore (atomic write, digest verify,
//      capacity refusal, sealed revisions) the legacy recorder uses.
//
// WHAT IT NEVER DOES: invent operator responses, link machine output to RC
// facts, emit MOP commands, move cameras/flights, grant ACTIVE, upgrade a
// receive-grade timestamp, or evict sealed bytes. Custody on every artifact:
// manifold-sealed locally; rc_received/core_retained are separate facts and
// stay unverified here.
//
// NO CLOCK INSIDE: all times are caller-provided. HealthTick(now) closes
// overdue windows so a missing frame stream still terminates windows
// (never hangs waiting for new frames).
//
// RESOURCE BOUNDS (explicit, finite): ring max frames/bytes, window
// deadline, store capacity, per-artifact byte cap. Overflow and write
// failures latch a sticky refusal (visible in Coverage + Snapshot) instead
// of silent loss; the host wiring forwards it to
// SessionRunner::NoteDurableFailure so the run degrades honestly.

#ifndef KSI_SAFETY_OBSERVATION_AUTONOMOUS_PROCESSOR_HPP
#define KSI_SAFETY_OBSERVATION_AUTONOMOUS_PROCESSOR_HPP

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ksi_safety/evidence/store.hpp"
#include "ksi_safety/observation/assessment.hpp"
#include "ksi_safety/observation/context_ring.hpp"
#include "ksi_safety/observation/source_attention_policy.hpp"
#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/runtime/session_runner.hpp"

namespace ksi::safety {

// Persisted artifact media types (internal, NOT frozen contracts).
// The assessment media is one of the collector-recognised exact strings
// (parent maps it to EDGE_ASSESSMENT; raw RGB maps to EDGE_SOURCE_FRAME;
// anything else lands truthfully as EDGE_EVIDENCE_ARTIFACT).
constexpr const char* kMediaAssessmentV1 = "application/vnd.ksi.assessment+json";
constexpr const char* kMediaCoverageV1 = "application/x-ksi-coverage-v1+json";
constexpr const char* kMediaSourceFrame = "application/x-raw-rgb24";
// Distinct from full-frame and assessment v1 semantics; generic collection only.
constexpr const char* kMediaSourceRoiV1 = "application/x-ksi-source-roi-rgb24-v1";
constexpr const char* kMediaSourceAttentionV1 = "application/x-ksi-source-attention-v1+json";

struct AutonomousConfig {
    // Master opt-in. Default false: autonomy never silently auto-enables on
    // an installed device; every entry point must set this explicitly.
    bool enabled = false;
    ContextRingConfig ring;
    // EvidenceStore capacity bound for autonomy artifacts. 0 = unbounded
    // (host harness only; the device path must state a finite bound).
    std::uint64_t store_capacity_bytes = 0;
    // Headroom kept free so the closing revision (which lists every kept
    // asset) can still be sealed. Admission stops at capacity - reserve; a
    // full store that cannot seal turns kept bytes into unexportable files.
    // Engineering reserve, NOT a calibrated or safety threshold.
    std::uint64_t seal_reserve_bytes = 512 * 1024;
    // Per-artifact guard: an assessment/coverage JSON larger than this is
    // refused rather than written (fail-closed, counted).
    std::size_t max_artifact_bytes = 1024 * 1024;
    // Empty = volatile only (assessments built + ring kept, nothing sealed).
    std::string evidence_dir;
    std::string flight_id;
    std::string edge_boot_id;
    std::string stream_id;
    std::string mission_revision_id;
    std::string policy_revision_id;
    std::string effective_config_hash;
    std::uint64_t config_epoch = 0;
    std::string lens_id = "wide";
    // A human currently holds the camera: spatial conclusions are withheld,
    // analysis continues (validity of the manual input affects spatial only).
    bool camera_manual_override = false;
    bool simulated_input = false;
    // Fixed true: Unit A assesses SHADOW candidates only.
    bool shadow_only = true;
    // Fault injection for the store (fsync/rename failures; host tests).
    // Default = the real syscalls.
    EvidenceStoreHooks store_hooks;
    SourceAttentionPolicy source_attention;
    // Event admission bound: at most one capture window opens per interval
    // (first trigger wins; later event ticks inside the interval are counted
    // as merged, never silently dropped). 0 = no interval bound. This bounds
    // evidence writes under continuous detection; it is an engineering
    // admission bound, NOT a calibrated or safety threshold.
    std::int64_t window_min_interval_ns = 1000000000LL;
    // Steady-state attention report cadence. A report is always written for an
    // attempted capture, a decision change, or a lifecycle edge; identical
    // steady-state ticks are summarized by this heartbeat. 0 = only on those
    // events. Reports are diagnostic provenance, not per-frame evidence.
    std::int64_t attention_report_interval_ns = 10000000000LL;
};

bool IsValidAutonomousConfig(const AutonomousConfig& config,
                             std::string* error);

// One persisted artifact's identity (returned so the collector path — owned
// by the parent session — can fetch the EXACT bytes from the store).
struct ArtifactInfo {
    std::string asset_id;
    std::string media_type;
    std::uint64_t size_bytes = 0;
    std::string sha256_hex;
    EvidenceStatus write_status = EvidenceStatus::kOk;
};

struct SharedTickResult {
    bool runner_processed = false;  // false = gate closed / latched: dropped
    bool autonomy_observed = false;
    TickRecord record;  // the runner's tick (valid iff runner_processed)
    Assessment assessment;
    bool window_opened = false;
    std::string window_id;
    std::vector<std::string> windows_closed;
    ArtifactInfo assessment_artifact;
    observation::PlanResult attention_plan;
    ArtifactInfo attention_artifact;
    std::vector<ArtifactInfo> attention_crops;
    // Sticky write refusal currently latched (empty when healthy).
    std::string write_failure;
};

// Machine coverage over the execution (persisted as kMediaCoverageV1 at
// closeout; also printable for host stdout/CLI repro).
struct AutonomousCoverage {
    std::uint64_t processed_ticks = 0;
    std::uint64_t ok_ticks = 0;
    std::uint64_t notrun_ticks = 0;
    std::uint64_t failure_ticks = 0;
    std::uint64_t assessments_written = 0;
    std::uint64_t assessments_assessed = 0;
    std::uint64_t assessments_undetermined = 0;
    std::uint64_t windows_opened = 0;
    std::uint64_t windows_complete = 0;
    std::uint64_t windows_partial = 0;
    std::uint64_t window_frames_written = 0;
    std::uint64_t windows_merged = 0;
    std::uint64_t ring_evicted = 0;
    std::uint64_t writes_refused = 0;
    std::uint64_t digest_conflicts = 0;
    std::uint64_t idempotent_duplicates = 0;
    // The bounded autonomy store could not accept more artifacts (capacity
    // reached, or a stated capacity below one capture window). Observation
    // CONTINUES: this is an explicit, sticky evidence gap, not a reason to
    // stop detecting. Integrity/write faults use write_failure instead.
    bool evidence_saturated = false;
    std::string saturation_reason;
    std::uint64_t evidence_refusals = 0;
    std::uint64_t attention_reports_suppressed = 0;
    std::string write_failure;
    std::string ToJson(const AutonomousConfig& config,
                       const std::vector<CaptureWindow>& windows) const;
};

class AutonomousProcessor {
  public:
    explicit AutonomousProcessor(AutonomousConfig config);
    ~AutonomousProcessor();
    // Owns the evidence store through a raw pointer: copying would
    // double-free it. One processor per runtime/session.
    AutonomousProcessor(const AutonomousProcessor&) = delete;
    AutonomousProcessor& operator=(const AutonomousProcessor&) = delete;

    bool valid() const { return valid_; }
    const std::string& error() const { return error_; }
    bool enabled() const { return config_.enabled && valid_ && !parked_; }
    void SetEnabled(bool enabled);
    void SetRuntimeConfigIdentity(std::uint64_t epoch, const std::string& hash);
    void SetCameraManualOverride(bool override);
    // The INSTALLED mission/policy revisions (mop_server path: frames wait
    // for the bundle, so autonomy must name the installed revisions, never
    // a requested-but-uninstalled one).
    void SetMissionPolicyRevisions(const std::string& mission_revision_id,
                                   const std::string& policy_revision_id);
    void SetLensId(const std::string& lens_id, bool switch_observed);

    // Consumes one PROCESSED tick + its source bytes. Copies the bytes into
    // the bounded ring (zero-detection frames included). Pure caller data.
    SharedTickResult OnTick(const TickRecord& record, const std::uint8_t* rgb,
                            std::size_t rgb_size, std::uint32_t width = 0,
                            std::uint32_t height = 0,
                            const FrameContext* source_context = nullptr);

    // Closes overdue windows without needing new frames. Returns ids closed.
    std::vector<std::string> HealthTick(std::int64_t now_mono_ns);

    // Clean stop: closes open windows (named incomplete), writes the
    // coverage artifact. Seal() afterwards for the immutable revision.
    void RequestStop(std::int64_t now_mono_ns);
    // Identity loss (reboot without a clean stop): parks the processor. No
    // auto-resume: the next start must SetEnabled + Configure explicitly.
    void AbortRuntime();
    bool parked() const { return parked_; }

    const AutonomousCoverage& coverage() const { return coverage_; }
    std::vector<CaptureWindow> windows() const;
    bool HasOpenWindows() const;
    bool FindWindow(const std::string& window_id, CaptureWindow* out) const;
    bool FindWindowForTrigger(std::uint64_t trigger_tick,
                              CaptureWindow* out) const;
    std::vector<ArtifactInfo> artifacts() const { return artifacts_; }

    // Seals one immutable revision over every artifact written so far.
    // False + error when nothing was written (never an empty revision).
    bool Seal(std::string* revision_id_out, std::string* manifest_out,
              std::string* error);
    // Re-reads and re-hashes every written asset: true when all verify.
    bool VerifyAll(std::string* error) const;
    std::string store_dir() const { return config_.evidence_dir; }
    bool hasWriteFailure() const { return !coverage_.write_failure.empty(); }
    // Sticky capacity exhaustion (observation keeps running; see coverage).
    bool hasEvidenceSaturation() const { return coverage_.evidence_saturated; }
    const std::string& saturationReason() const {
        return coverage_.saturation_reason;
    }

    // Asset-id derivations (stable; shared with tests so conflicts and
    // re-collection address the EXACT bytes).
    static std::string AssessmentAssetId(const std::string& flight_id,
                                         const std::string& edge_boot_id,
                                         std::uint64_t tick_index);
    static std::string WindowFrameAssetId(const std::string& flight_id,
                                          const std::string& edge_boot_id,
                                          std::uint64_t trigger_tick,
                                          std::uint64_t frame_tick);
    static std::string CoverageAssetId(const std::string& flight_id,
                                       const std::string& edge_boot_id,
                                       std::int64_t sealed_mono_ns);

  private:
    bool WriteArtifact(const std::string& asset_id,
                       const std::string& media_type,
                       const std::string& observation_id,
                       std::uint64_t frame_id, std::int64_t recv_ns,
                       const std::uint8_t* bytes, std::size_t len,
                       ArtifactInfo* info);
    void LatchWriteFailure(const std::string& reason);
    // Enters the sticky, non-fatal evidence-saturation state (first reason
    // wins). No filesystem work happens afterwards.
    void SaturateEvidence(const std::string& reason);
    // First-tick capacity preflight: a stated capacity that cannot hold one
    // pre/post window can only ever produce truncated evidence, so it is
    // reported as a configuration fact instead of discovered mid-flight.
    void PreflightEvidenceCapacity(std::uint32_t width, std::uint32_t height,
                                   std::size_t rgb_size);
    AssessmentContext MakeContext() const;
    // Counts + persists one freshly closed window (present frames only).
    void PersistClosedWindow(const CaptureWindow& window,
                             const TickRecord* current_tick = nullptr,
                             ArtifactInfo* current_frame = nullptr);
    void CaptureSourceAttention(const TickRecord& record, const std::uint8_t* rgb,
                                std::size_t size, std::uint32_t width,
                                std::uint32_t height, const FrameContext* context,
                                const ArtifactInfo& current_frame,
                                SharedTickResult* result);
    void AdvanceSourceAttention(std::int64_t now_ns, bool stopping);
    void ResetSourceAttention();

    AutonomousConfig config_;
    bool valid_ = false;
    std::string error_;
    bool parked_ = false;
    bool lens_switch_pending_ = false;
    ContextRing ring_;
    EvidenceStore* store_ = nullptr;
    AutonomousCoverage coverage_;
    std::vector<ArtifactInfo> artifacts_;
    std::vector<std::string> written_ids_;
    bool coverage_written_ = false;
    std::unique_ptr<observation::ObservationPlanner> attention_;
    std::uint64_t attention_generation_ = 0;
    std::string attention_source_view_;
    std::int64_t attention_last_source_receive_ns_ = -1;
    std::int64_t attention_last_health_ns_ = -1;
    // Event-gated window triggers (P1 evidence policy). A window opens only
    // for a NEW event: a confirmed track freshly observed this tick whose
    // lineage was not freshly observed on the previous tick, or an inference
    // failure rising edge. Continuous detection opens nothing new; per-tick
    // assessments and ROI attention continue regardless. Bounded memory: the
    // previous tick's fresh-confirmed set only (never a growing history).
    struct FreshKey {
        std::uint64_t tracker_epoch = 0;
        std::uint64_t track_id = 0;
        std::uint32_t class_id = 0;
        bool operator==(const FreshKey& other) const {
            return tracker_epoch == other.tracker_epoch &&
                   track_id == other.track_id && class_id == other.class_id;
        }
    };
    void ResetWindowTrigger();
    std::vector<FreshKey> prev_fresh_confirmed_;
    DetectionStatus prev_detection_status_ = DetectionStatus::kNotRun;
    std::int64_t last_window_open_ns_ = -1;
    std::string attention_last_report_signature_;
    std::int64_t attention_last_report_ns_ = -1;
    bool capacity_preflight_done_ = false;
};

// THE shared wiring: one runner tick + autonomy observation, in that order.
// - calls runner.ProcessFrame exactly once (detector at most once inside);
// - forwards to processor.OnTick ONLY when the runner actually processed the
//   tick (gate closed / latched => dropped, autonomy sees nothing);
// - forwards a processor write failure to runner.NoteDurableFailure so the
//   run degrades instead of looking healthy while evidence is refused.
// No new commands, no camera/flight output, no clock reads.
SharedTickResult ProcessSharedTick(SessionRunner& runner,
                                   AutonomousProcessor& processor,
                                   const FramePacket& frame,
                                   const FrameContext& ctx,
                                   DetectorPort* detector,
                                   const std::uint8_t* rgb,
                                   std::size_t rgb_size);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_OBSERVATION_AUTONOMOUS_PROCESSOR_HPP
