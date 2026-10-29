// Bounded active-camera reobservation controller (Manifold core, host-testable).
//
// Unit B (stage-2 plan: docs/plans/2026-10-23-active-reobservation-stage2.md).
// Decides whether a helmet-suspect deserves one bounded imaging re-look,
// issues at most one permitted zoom/gimbal/still intent per task through an
// INJECTED camera port, then separates request ACK from observed reality
// (lens state + settled fresh frames + external verified target match).
//
// This is a safe MOCK controller: every input is injected, every gate fails
// closed, and the only actuation surface is InjectedCameraPort (fake in host
// tests; the real PSDK adapter lives elsewhere and stays disabled there).
//
// Hard boundaries (by construction — this header contains no other ports):
//   - Only imaging requests into InjectedCameraPort. No waypoint / flight
//     controller / patrol pause-cancel / return-to-pose interfaces exist here.
//   - No PPE classifier, no human response, no Incident/Alert, no PPE-risk
//     confirmation. Output is machine Action history + assessment
//     (available / unclear only).
//   - No passive-analysis stop: there is deliberately NO StopAnalysis method
//     and no side effect on external analysis. Analysis continuation is the
//     parent's job; this unit only gates its own KSI camera requests.
//   - Local arbiter gates KSI requests ONLY. It proves nothing about real
//     DJI/RC precedence; a missing/untrusted/stale owner report means
//     DO NOT move.
//   - Pure injected time/data: no clock reads, no RNG, no I/O. Every method
//     takes now_monotonic_ns, which must never roll back (a rollback is
//     rejected and recorded). Identical input sequences give identical
//     outputs (snapshot string is deterministic).
//   - Target identity is an explicit input with provenance CONFIRMED vs
//     AMBIGUOUS/LOST. A matching track id after zoom never self-confirms:
//     the verdict additionally requires a match bound to a qualifying frame
//     observed AFTER the lens settled (post-action, post-settle evidence).
//   - Failure truth is preserved: ACK-accepted but actually-unknown stays a
//     failure/unknown, never rewritten to success by a late callback.
//   - History is in-memory ONLY with an explicit record capacity — never
//     called sealed/durable here. When the buffer is full the controller
//     fails closed for camera motion (no silent overwrite). The parent
//     persists the Snapshot() text if it needs a durable trace.
//   - Reboot drops live authority/tasks/reports (after a best-effort Cancel)
//     and keeps only the in-memory history. New epoch + fresh grant + fresh
//     reports are required afterwards.
//
// Authority model:
//   - Grant generations must STRICTLY ADVANCE within the controller lifetime
//     (replayed or decreasing generations are rejected, so a stale grant can
//     never restore a manually released camera). Generation and task-id
//     counters are monotonic across reboot: the parent must persist them or
//     use epoch-qualified increasing ids.
//   - Task ids must be strictly increasing and are never reused: an old
//     ACK/match carrying a previous task id can never affect a new task
//     (additionally fenced by task+ generation checks in every handler).
//   - Manual input, mission reservation, supervision loss, or evidence
//     exhaustion invalidates the authority, latches a hold (manual /
//     supervision), fences queued/delayed callbacks and the inflight task,
//     cancels the continuous action via the port when supported, and NEVER
//     drives the camera back to an old AI pose. Only a fresh advancing grant
//     clears a hold — never a repeated owner/supervision report, never time.
//   - Owner, supervision, evidence-capacity, and device-identity reports all
//     go stale without refresh (report_freshness_ns, enforced in Poll even
//     with zero frames), as does the video stream itself. A new motion
//     request additionally requires at least one fresh frame ever observed.
//   - Request resend semantics are absolute: (task_id, generation) is the
//     idempotency key. An EXPLICIT adapter reject or a transport-not-sent may
//     be resent within max_retries. An ACK TIMEOUT is uncertain (the action
//     may already have executed: resending could duplicate a physical
//     capture/movement), so it is resent ONLY when the adapter advertises
//     idempotent_retry_supported; otherwise the task fails unknown with no
//     automatic resend.
//
// Config validity: the struct defaults are MOCK placeholders, NOT
// operational thresholds. operational_thresholds_confirmed must be set true
// explicitly by the parent after providing real values, otherwise every
// request is rejected with config_invalid. Invalid ranges are rejected, not
// silently repaired (no bound swapping/clamping of policy).

#ifndef KSI_SAFETY_OBSERVATION_REOBSERVATION_HPP
#define KSI_SAFETY_OBSERVATION_REOBSERVATION_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace ksi::safety::observation {

// Who the device reports as actually holding the camera. Distinct from the
// local KSI allowance: the arbiter below only gates KSI requests.
enum class CameraOwner {
    kUnknown = 0,   // no trusted report -> DO NOT actuate
    kHuman = 1,     // manual stick/RC input holds the camera
    kDjiMission = 2,// DJI mission reserves the camera
    kKsi = 3,       // camera released to KSI (only actuable state)
};

enum class AutoAllowance {
    kInhibited = 0, // automatic camera motion not allowed at all
    kPaused = 1,    // temporarily paused (e.g. supervision degraded)
    kEnabled = 2,   // operator explicitly enabled automatic re-looks
};

enum class Supervision {
    kPresent = 0,
    kLost = 1, // link/supervision lost: analysis may continue, motion stops
};

enum class TargetProvenance {
    kConfirmed = 0, // external verifier pins this observation to the origin
    kAmbiguous = 1, // similar person / weak link -> never confirm
    kLost = 2,      // target gone / out of view -> never chase
};

enum class ImageQuality {
    kUnknown = 0,
    kPoor = 1,
    kMarginal = 2,
    kUsable = 3,
};

enum class TaskState {
    kIdle = 0,
    kAwaitingAck = 1,
    kAwaitingSettle = 2,
    kAwaitingMatch = 3,
    kDone = 4,
};

enum class TaskOutcome {
    kNone = 0,
    kInProgress = 1,
    kSucceededVerified = 2, // ACK + fresh settled frames + CONFIRMED match
    kSkipped = 3,           // no need / not allowed: zero camera output
    kRejected = 4,          // need exists but authority/capability refuses
    kFailed = 5,            // ACK/timeout/settle/match/evidence failure
    kCancelled = 6,         // fenced by newer authority / manual / mission
};

enum class Assessment {
    kUnavailable = 0, // nothing new learned (skipped/rejected/failed)
    kAvailable = 1,   // verified re-look frames exist for the parent
    kUnclear = 2,     // motion happened but identity link is ambiguous/lost
};

// Identity context that must match across grant, target, lens, and frames.
// All four fields are required non-empty; any mismatch (flight/boot/camera/
// lens) fails closed, never chases.
struct IdentityContext {
    std::string flight_id;
    std::string boot_id;
    std::string camera_id;
    std::string lens_id;
};

// One permitted imaging intent. Bounded integers; the controller rejects
// anything outside the validated Config bounds (no silent policy repair).
struct ImagingIntent {
    int zoom_step = 0;          // 0 = no zoom change; otherwise discrete step
    int gimbal_pitch_mdeg = 0;  // millidegrees, bounded by Config
    int gimbal_yaw_mdeg = 0;    // millidegrees, bounded by Config
    bool capture_still = false; // every action needs evidence capacity
};

struct PortCapabilities {
    bool zoom_supported = false;
    bool gimbal_supported = false;
    bool still_supported = false;
    bool cancel_supported = false;
    // True ONLY when the adapter dedups resends keyed by (task_id,
    // generation) and reports the ORIGINAL outcome for a duplicate. The
    // controller resends after an uncertain ACK timeout only when this is
    // true; otherwise the task fails unknown with no automatic resend.
    bool idempotent_retry_supported = false;
    int min_zoom_step = 0;
    int max_zoom_step = 0;
    int max_pitch_mdeg = 0;
    int max_yaw_mdeg = 0;
};

// Injected camera port. The ONLY actuation surface. Fake in host tests;
// the real PSDK adapter lives elsewhere and stays disabled there.
class InjectedCameraPort {
  public:
    virtual ~InjectedCameraPort() = default;
    virtual PortCapabilities capabilities() const = 0;
    // Transport handoff (not success): the async ACK arrives later via
    // OnRequestAck. Return false = transport could not even queue (certain
    // nothing was sent; a bounded resend is allowed).
    virtual bool Request(std::uint64_t task_id, std::uint64_t generation,
                         const ImagingIntent& intent) = 0;
    // Best-effort halt of a continuous action; no-op when unsupported.
    virtual void Cancel(std::uint64_t task_id, std::uint64_t generation) = 0;
};

// Static bounds. All durations are nanoseconds on the injected clock.
// Defaults are MOCK placeholders: set real values and flip
// operational_thresholds_confirmed, or every request is rejected.
struct ReobserveConfig {
    std::int64_t max_dwell_ns = 8000000000LL;      // whole-task deadline
    std::int64_t request_timeout_ns = 1500000000LL;// ACK wait
    std::int64_t settle_timeout_ns = 2000000000LL; // lens+frames settle wait
    std::int64_t match_timeout_ns = 2000000000LL;  // verified-match wait
    std::int64_t frame_freshness_ns = 500000000LL; // frames must be this fresh
    std::int64_t state_freshness_ns = 1000000000LL;// lens/state freshness
    std::int64_t target_freshness_ns = 1000000000LL; // match observation age
    std::int64_t config_freshness_ns = 5000000000LL;// suspect observation age
    std::int64_t report_freshness_ns = 2000000000LL;// owner/supervision/
                                                   // evidence/identity refresh
    int max_retries = 1; // bounded resends of certain-failed actions only
    int permitted_min_zoom = 0;
    int permitted_max_zoom = 2;
    int permitted_max_pitch_mdeg = 15000;
    int permitted_max_yaw_mdeg = 30000;
    int max_history_records = 256; // in-memory cap; full => fail closed
    // Parent-acknowledged real thresholds. False (default) => config invalid.
    bool operational_thresholds_confirmed = false;
    static constexpr int kMaxRetriesCeiling = 3;
    static constexpr std::int64_t kMaxDurationNs = 3600000000000LL; // 1 h
    static constexpr int kMinHistoryRecords = 16;
    static constexpr int kMaxHistoryRecords = 4096;
    static constexpr int kMaxGimbalMdeg = 90000;
};

// Validates ranges/times (positive, finite, within caps, dwell covering all
// phases, zoom/gimbal/history sane, thresholds explicitly confirmed).
// Invalid configs are reported, never silently repaired.
bool ValidateConfig(const ReobserveConfig& config, std::string* error);

// Explicit operator grant. Generation must exceed every generation seen so
// far; expiry must be in the future; all identity fields non-empty.
struct CameraGrant {
    std::uint64_t generation = 0; // 0 = no grant, never advancing
    std::int64_t expires_monotonic_ns = 0;
    IdentityContext identity;
    AutoAllowance allowance = AutoAllowance::kInhibited;
};

// Helmet-suspect observation asking for more detail. `require_more_detail`
// is the Pass-1 model's explicit "I need a closer look" flag — this unit
// never runs a PPE classifier and never confirms PPE risk.
struct SuspectTarget {
    std::string source_id;      // e.g. "helmet-suspect" producer + version
    std::string target_id;      // opaque origin id (never self-matched)
    IdentityContext identity;
    ImageQuality quality = ImageQuality::kUnknown;
    bool require_more_detail = false;
    bool wide_context_required = false; // danger proximity -> keep wide view
    std::int64_t observed_monotonic_ns = 0;
    TargetProvenance provenance = TargetProvenance::kAmbiguous;
};

// Observed lens reality (NOT the ACK). Freshness judged on observed_ns,
// which must satisfy task_start <= observed <= now (future or pre-task
// stamps are fenced, never treated as fresh).
struct LensState {
    std::uint64_t task_id = 0;
    std::uint64_t generation = 0;
    IdentityContext identity;
    int zoom_step = 0;
    bool settled = false;
    std::int64_t observed_monotonic_ns = 0;
};

struct ObservedFrame {
    std::uint64_t frame_id = 0; // 0 = unusable as match evidence
    std::int64_t frame_monotonic_ns = 0; // capture time on injected clock
    IdentityContext identity;
};

// External verified target match. The verifier is outside this unit; only
// kConfirmed counts, and only when bound to a qualifying frame observed
// AFTER the lens settled (post-action, post-settle evidence — never an
// earlier unmapped frame).
struct TargetMatch {
    std::uint64_t task_id = 0;
    std::uint64_t generation = 0;
    std::string target_id;
    IdentityContext identity;
    TargetProvenance provenance = TargetProvenance::kLost;
    std::int64_t observed_monotonic_ns = 0;
    // Frame this verdict is based on: must equal the latest qualifying
    // post-settle frame the controller itself observed.
    std::uint64_t based_on_frame_id = 0;
    std::int64_t based_on_frame_monotonic_ns = -1;
};

// One in-memory machine-action history entry (NOT sealed/durable storage;
// the parent persists Snapshot() if it needs a durable trace).
struct ActionRecord {
    std::uint64_t seq = 0;
    std::uint64_t task_id = 0;
    std::uint64_t generation = 0;
    std::string kind;
    std::string detail;
    std::int64_t now_monotonic_ns = 0;
};

struct StartOutcome {
    TaskOutcome outcome = TaskOutcome::kNone;
    std::string reason; // stable snake_case token for tests/parents
    ImagingIntent intent;
};

// Bounded controller. Single inflight task; owns no threads, reads no
// clock, performs no I/O. The parent drives Poll() on its own tick.
class ReobservationController {
  public:
    ReobservationController(const ReobserveConfig& config,
                            InjectedCameraPort* port);

    bool config_ok() const { return config_ok_; }
    const std::string& config_error() const { return config_error_; }

    // ---- authority & live reports (all injected, all timestamped) ----
    bool GrantAuthority(const CameraGrant& grant, std::int64_t now_ns);
    void NoteActualOwner(CameraOwner owner, std::int64_t now_ns);
    void NoteSupervision(Supervision supervision, std::int64_t now_ns);
    void NoteDeviceIdentity(const IdentityContext& identity,
                            std::int64_t now_ns);
    void NoteEvidenceCapacity(bool capacity_ok, std::int64_t now_ns);
    // Best-effort Cancel of any inflight action FIRST, then drops live
    // authority/tasks/reports (keeps in-memory history). Fresh epoch +
    // grant + reports are required afterwards. Generation/task counters stay
    // monotonic across the reboot.
    void OnReboot(std::int64_t now_ns);

    // ---- task API ----
    // Usefulness gate + single permitted intent + bounded port Request.
    // Emits zero camera output unless every gate passes.
    StartOutcome RequestReobservation(std::uint64_t task_id,
                                      const SuspectTarget& suspect,
                                      std::int64_t now_ns);

    // Async adapter callbacks. Stale (task/generation/identity/time) inputs
    // are fenced: recorded as "*_fenced"/"*_rejected", never mutate live
    // state.
    void OnRequestAck(std::uint64_t task_id, std::uint64_t generation,
                      bool accepted, std::int64_t now_ns);
    void OnLensState(const LensState& state, std::int64_t now_ns);
    void OnFrame(const ObservedFrame& frame, std::int64_t now_ns);
    void OnTargetMatch(const TargetMatch& match, std::int64_t now_ns);

    // Timeout/expiry/report-staleness pump. Works with no frames at all
    // (pure now_ns). Never auto-resumes motion.
    void Poll(std::int64_t now_ns);

    // ---- readouts (no human response / Incident / Alert types exist) ----
    TaskState task_state() const { return task_state_; }
    TaskOutcome task_outcome() const { return task_outcome_; }
    Assessment assessment() const { return assessment_; }
    std::uint64_t active_task_id() const { return active_task_id_; }
    std::uint64_t authority_generation() const { return authority_generation_; }
    bool authority_valid() const { return authority_valid_; }
    bool history_full() const { return history_full_; }
    std::uint64_t dropped_records() const { return dropped_records_; }
    const std::vector<ActionRecord>& history() const { return history_; }
    // Deterministic multi-line snapshot for the parent fixture. The parent
    // — not this unit — decides what to persist.
    std::string Snapshot() const;

  private:
    bool AdvanceTime(std::int64_t now_ns);
    void Append(const std::string& kind, const std::string& detail,
                std::uint64_t task_id, std::uint64_t generation,
                std::int64_t now_ns);
    void InvalidateAuthority(const std::string& reason, std::int64_t now_ns);
    void CancelInflight(const std::string& reason, std::int64_t now_ns);
    void Finish(TaskOutcome outcome, Assessment assessment,
                const std::string& kind, const std::string& detail,
                std::int64_t now_ns);
    // Full liveness: valid grant + allowance + holds clear + KSI owner +
    // supervision + evidence + fresh reports + fresh video.
    bool GrantLive(std::int64_t now_ns) const;
    // AuthorityCore is GrantLive without the video check: stale reports or
    // lost conditions invalidate the authority, while a merely stale video
    // stream fails the inflight task but preserves the grant.
    bool AuthorityCore(std::int64_t now_ns) const;
    bool ReportsFresh(std::int64_t now_ns) const;
    bool VideoFresh(std::int64_t now_ns) const;
    bool IdentityMatches(const IdentityContext& other) const;
    static bool IdentityEqual(const IdentityContext& a,
                              const IdentityContext& b);
    static bool IdentityComplete(const IdentityContext& identity);
    static bool AddOverflows(std::int64_t a, std::int64_t b);
    bool IntentSupported(const ImagingIntent& in) const;
    // Bounded resend of a CERTAIN-failed send (explicit reject or
    // transport-not-sent). Returns true when resent.
    bool ResendOrFail(const std::string& fail_reason, std::int64_t now_ns);

    ReobserveConfig config_;
    InjectedCameraPort* port_; // not owned; must outlive the controller
    bool config_ok_ = false;
    std::string config_error_;

    std::int64_t last_now_ns_ = -1; // monotonic injected-time guard

    bool authority_valid_ = false;
    std::uint64_t authority_generation_ = 0;
    std::uint64_t max_generation_seen_ = 0; // monotonic, survives reboot
    std::uint64_t max_task_id_seen_ = 0;    // monotonic, survives reboot
    std::int64_t authority_expires_ns_ = 0;
    IdentityContext authority_identity_;
    AutoAllowance allowance_ = AutoAllowance::kInhibited;

    // Latched holds: only a fresh advancing grant clears them.
    bool ownership_hold_ = false;   // set by manual/mission/unknown reports
    bool supervision_hold_ = false; // set by supervision loss

    CameraOwner actual_owner_ = CameraOwner::kUnknown;
    Supervision supervision_ = Supervision::kLost; // fail-closed default
    IdentityContext device_identity_;
    bool evidence_capacity_ok_ = false; // fail-closed default

    // Freshness of each report stream (-1 = never reported).
    std::int64_t last_owner_ns_ = -1;
    std::int64_t last_supervision_ns_ = -1;
    std::int64_t last_evidence_ns_ = -1;
    std::int64_t last_identity_ns_ = -1;
    std::int64_t last_frame_ns_ = -1; // -1 = no frame ever observed

    TaskState task_state_ = TaskState::kIdle;
    TaskOutcome task_outcome_ = TaskOutcome::kNone;
    Assessment assessment_ = Assessment::kUnavailable;
    std::uint64_t active_task_id_ = 0;
    SuspectTarget active_suspect_;
    ImagingIntent active_intent_;
    std::int64_t task_start_ns_ = 0;
    std::int64_t task_deadline_ns_ = 0;
    std::int64_t ack_deadline_ns_ = 0;
    std::int64_t settle_deadline_ns_ = 0;
    std::int64_t match_deadline_ns_ = 0;
    std::int64_t settle_ns_ = -1; // when the lens settled for this task
    int attempts_ = 0;
    bool lens_settled_fresh_ = false;
    bool fresh_frames_seen_ = false; // stream alive post-ACK (entry only)
    // Latest qualifying post-settle frame; the verdict must bind to it.
    std::uint64_t qualifying_frame_id_ = 0;
    std::int64_t qualifying_frame_ns_ = -1;
    std::string last_skip_or_fail_reason_;

    std::vector<ActionRecord> history_; // bounded in-memory, never "sealed"
    bool history_full_ = false;
    std::uint64_t dropped_records_ = 0;
    std::uint64_t next_seq_ = 1;
};

}  // namespace ksi::safety::observation

#endif  // KSI_SAFETY_OBSERVATION_REOBSERVATION_HPP
