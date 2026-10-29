// Bounded data-only source-ROI observation planner (Manifold core,
// host-testable).
//
// ROLE: prioritises REAL fresh CONFIRMED tracks for bounded high-resolution
// ORIGINAL RGB source-ROI capture. The parent (AutonomousProcessor / PSDK /
// replay / shared capture / evidence path) owns actuation, the master
// on/off toggle, eligible-class filtering and clipped-ROI computation. This
// unit only answers, per tick: which of the CURRENT candidates may be
// captured now, given finite slots, finite bytes, repeat suppression,
// starvation expiry and view identity. It never captures anything itself.
//
// WHAT THIS IS NOT (by construction — none of these interfaces exist here):
//   - not a trained PPE classifier (no model, no inference, no labels);
//   - not a risk engine (no RuleOutput attribution, no safety verdict);
//   - not a flight-route planner (no waypoints, no motion, no patrol);
//   - not a camera controller (no zoom/gimbal/still ports, no actuation);
//   - not verification: a SELECTED job means "capture this ROI now", never
//     "safe" / "verified" / Incident / Alert. Capturing an ROI teaches
//     nothing by itself; the parent must treat every capture as unverified
//     source pixels until its own (separate) evidence path says otherwise.
//   - not a freshness cache: a past SELECTION is never reused as verified
//     knowledge. A returning track must be fresh_observed + confirmed + usable
//     on the CURRENT tick to be selected again.
//
// PARENT CONTRACT (caller supplies everything; this unit reads no clock,
// performs no I/O, keeps no RGB, spawns no threads):
//   - now_monotonic_ns: caller clock, must never roll back within one view.
//     A rollback fails closed (ok=false, zero state mutation).
//   - view_id: opaque caller-built identity combining the flight / boot /
//     stream / lens / model / config epochs. ANY change invalidates ALL prior
//     planner state (tracked waits, cooldowns, expiry quarantines) and starts
//     a new timeline, so a rebooted / reconfigured device can never inherit
//     another epoch's suppression or freshness.
//   - candidates: the CURRENT tick's tracks only. Absent keys are missing
//     (never selected from memory). Held boxes (tracker-held without a fresh
//     observation) arrive as fresh_observed=false and are NEVER selected.
//   - the parent filters eligible classes and computes the clipped ROI; this
//     unit only gates on the readiness flag + cost the parent reports.
//
// SELECTION GATES (first failing gate wins, stable reason string):
//   duplicate input key ......... "invalid_duplicate_key" (ALL occurrences of
//                                 a key repeated in one tick fail closed; the
//                                 parent must not send duplicates)
//   malformed key ............... "invalid_bad_key" (negative class / zero epoch or id)
//   malformed enum .............. "invalid_bad_value"
//   not confirmed ............... "invalid_unconfirmed"
//   not fresh on this tick ....... "stale_not_fresh" (held/missed boxes)
//   ROI not usable .............. "quality_insufficient" (too_small / invalid
//                                 crop availability; NEVER a PPE-quality claim
//                                 and NEVER negative: cost is ignored, the
//                                 budget is untouched)
//   zero cost on usable ROI ..... "invalid_bad_cost"
//   wait-expired lineage ......... "expired" (see EXPIRY below)
//   selected too recently ........ "cooldown_repeat_suppressed" (see COOLDOWN)
//   cost exceeds the whole tick .. "budget_oversize" (unselectable under the
//                                 current config; parent must reconfigure/drop)
//   wait starved ................. "expired" (see EXPIRY)
//   allocation cap hit .......... "state_full" (see BOUNDS)
//   eligible but outbid this tick  "deferred_budget_slots" /
//                                 "deferred_budget_bytes" (see BUDGET)
//
// COOLDOWN (repeat suppression): anchored on the last SELECTED attempt, never
// on a verification outcome. This unit has NO completion API by design: the
// parent path is currently synchronous, so select-then-capture either happens
// inline or fails inline, and the budget is charged at selection either way
// (a later I/O failure never refunds). A key selected at time S is suppressed
// until now - S >= repeat_capture_interval_ns. There is deliberately no token
// generation, no async callback, and no "verification cache": returning after
// an actual gap still needs fresh_observed + confirmed + usable on the new
// tick; the old selection only suppresses, it never qualifies.
//
// EXPIRY (starvation + quarantine, never "safe"):
//   - A tracked key that waits (eligible but unselected, including deferred)
//     longer than max_wait_ns expires: state is closed as UNKNOWN (never
//     safe/verified), the decision is "expired", and the key enters a
//     quarantine until now - expired_at >= retry_delay_ns. Reappearance
//     inside quarantine reports "expired" again WITHOUT creating new wait
//     state, so an expired unchanged target is not revived every frame by a
//     tight loop. Only after the quarantine lapses may it start a fresh wait.
//   - Selection restarts seniority; starvation wait excludes the cooldown, so a
//     just-captured track cannot starve others on seniority beyond cooldown.
//   - Wait accrues while the lineage is retained, even across ticks where the
//     key is absent-but-not-yet-lost; absence beyond target_lost_after_ns
//     DROPS the lineage instead (missed targets are forgotten, a later return
//     is a brand-new wait with no inherited suppression beyond an active
//     quarantine — and still needs fresh/confirmed/usable to be selected).
//     Loss is evaluated before fresh presence; held/unconfirmed input cannot
//     refresh last_seen. A return after a long gap cannot hide that gap.
//
// BUDGET (finite, first-fit in rank order, deterministic):
//   - Rank: elevated priority first, then oldest wait_start, then smallest
//     (tracker_epoch, track_id, class_id). Elevated therefore beats routine
//     under slot/byte pressure; among equals the longest waiter wins; ties
//     break on key, never on input order.
//   - Greedy in rank order: select while jobs_selected < max_jobs_per_tick
//     AND total_bytes + cost <= max_bytes_per_tick (overflow-safe: costs are
//     pre-capped and the comparison is formed as total <= max - cost).
//     No knapsack reordering: determinism beats utilisation.
//   - Every selection charges the tick budget even if the parent's later I/O
//     fails; there is no refund, no automatic retry, no unbounded work.
//
// BOUNDS (finite memory; allocation guards, NOT safety thresholds):
//   - At most max_targets tracked lineages + max_targets quarantine entries.
//     An untracked eligible key arriving at capacity reports "state_full".
//   - All durations must be positive and within kMaxDurationNs; counts within
//     their ceilings. Overflow-prone inputs are rejected, never wrapped.
//   - Defaults in PlannerConfig are UNCALIBRATED placeholders (compile-time
//     sanity only). No default claims any hardware-calibrated value; the
//     parent must supply real budgets/TTLs for the device.
//   - Input vectors above kMaxTargetsCeiling are refused before allocation;
//     parent integration additionally bounds candidates to its policy cap.
//
// LIVENESS: no background threads, no hidden deadlines. State advances ONLY
// on Plan()/Advance() calls. Calling Plan() twice at the same now_ns with
// the same candidates cannot expire anything (expiry needs now to advance).
// Advance(now_ns) runs time-based expiry/drop WITHOUT new frames (frame gap
// / HealthTick / stop path); it never touches the view. Plan() with empty
// candidates and the CURRENT view likewise only expires — it never resets or
// clears the view; only a DIFFERENT non-empty view_id invalidates state.
//
// THREAD SAFETY: none. The parent drives one instance from one tick path.

#ifndef KSI_SAFETY_OBSERVATION_PLANNER_HPP
#define KSI_SAFETY_OBSERVATION_PLANNER_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace ksi::safety::observation {

// Target-specific safety priority CANNOT be derived here: the parent cannot
// attribute RuleOutputs to individual tracks, so this unit must NOT guess it.
// kRoutine is the default for every current device candidate; kElevated is
// reserved for a future parent that can attribute priority honestly.
enum class ObservationPriority {
    kRoutine = 0,
    kElevated = 1,
};

// Source-crop availability reported by the parent for THIS candidate on THIS
// tick. This is ROI geometry readiness only: kUsable means the parent has a
// clipped ROI and a positive cost; kTooSmall/kInvalid mean the crop cannot be
// captured. It is NEVER a PPE-quality or safety claim.
enum class SourceRoiReadiness {
    kUsable = 0,
    kTooSmall = 1,
    kInvalid = 2,
};

// Identity of one tracked target. tracker_epoch disambiguates tracker
// restarts so ids are never confused across epochs.
struct TrackKey {
    std::uint64_t tracker_epoch = 0;
    std::uint64_t track_id = 0;
    // Negative class_id is malformed (fail closed).
    std::int32_t class_id = -1;
};

inline bool operator==(const TrackKey& a, const TrackKey& b) {
    return a.tracker_epoch == b.tracker_epoch && a.track_id == b.track_id &&
           a.class_id == b.class_id;
}

inline bool operator!=(const TrackKey& a, const TrackKey& b) {
    return !(a == b);
}

struct TrackKeyLess {
    bool operator()(const TrackKey& a, const TrackKey& b) const {
        if (a.tracker_epoch != b.tracker_epoch)
            return a.tracker_epoch < b.tracker_epoch;
        if (a.track_id != b.track_id) return a.track_id < b.track_id;
        return a.class_id < b.class_id;
    }
};

// One candidate on the CURRENT tick. No RGB is retained; the parent keeps
// the pixels and computes the clipped ROI.
struct ObservationCandidate {
    TrackKey key;
    // True only when this track was freshly observed on the current frame.
    // Held boxes (tracker-held without fresh evidence) arrive false.
    bool fresh_observed = false;
    // Externally confirmed track (parent's tracker verdict, not a PPE claim).
    bool confirmed = false;
    SourceRoiReadiness readiness = SourceRoiReadiness::kInvalid;
    // Parent-estimated capture cost in bytes. Must be > 0 when usable.
    std::uint64_t estimated_cost_bytes = 0;
    ObservationPriority priority = ObservationPriority::kRoutine;
};

// Static bounds. All durations are nanoseconds on the caller clock.
// Defaults are UNCALIBRATED placeholders, not hardware values.
struct PlannerConfig {
    std::uint32_t max_targets = 64;        // tracked lineage cap
    std::uint32_t max_jobs_per_tick = 4;   // slot cap per Plan()
    std::uint64_t max_bytes_per_tick = 1u << 20;  // byte cap per Plan() (1 MiB)
    std::int64_t repeat_capture_interval_ns = 2000000000LL;  // 2 s cooldown
    std::int64_t target_lost_after_ns = 1000000000LL;        // 1 s drop grace
    std::int64_t max_wait_ns = 5000000000LL;                 // 5 s starvation
    std::int64_t retry_delay_ns = 1000000000LL;              // 1 s quarantine
    static constexpr std::uint32_t kMaxTargetsCeiling = 4096;
    static constexpr std::uint32_t kMaxJobsCeiling = 1024;
    static constexpr std::uint64_t kMaxBytesCeiling = 1ULL << 30;  // 1 GiB
    static constexpr std::int64_t kMaxDurationNs = 3600000000000LL;  // 1 h
};

// Stable reason strings (additive; never redefine).
constexpr const char* kReasonSelected = "selected";
constexpr const char* kReasonCooldown = "cooldown_repeat_suppressed";
constexpr const char* kReasonQualityInsufficient = "quality_insufficient";
constexpr const char* kReasonStale = "stale_not_fresh";
constexpr const char* kReasonInvalidUnconfirmed = "invalid_unconfirmed";
constexpr const char* kReasonInvalidBadKey = "invalid_bad_key";
constexpr const char* kReasonInvalidBadValue = "invalid_bad_value";
constexpr const char* kReasonInvalidBadCost = "invalid_bad_cost";
constexpr const char* kReasonInvalidDuplicate = "invalid_duplicate_key";
constexpr const char* kReasonDeferredSlots = "deferred_budget_slots";
constexpr const char* kReasonDeferredBytes = "deferred_budget_bytes";
constexpr const char* kReasonBudgetOversize = "budget_oversize";
constexpr const char* kReasonStateFull = "state_full";
constexpr const char* kReasonExpired = "expired";

// Validates ranges only (positive, finite, within ceilings). Invalid configs
// are reported, never silently repaired. Passing validation does NOT mean the
// values are calibrated for any hardware.
bool ValidatePlannerConfig(const PlannerConfig& config, std::string* error);

// One capture job for the parent to execute synchronously. Carries no token:
// there is no async completion API, so no token is needed. selected ==
// budget-charged at Plan() time, regardless of later I/O fate.
struct CaptureJob {
    TrackKey key;
    ObservationPriority priority = ObservationPriority::kRoutine;
    std::uint64_t estimated_cost_bytes = 0;
};

// Per-candidate decision. Exactly one entry per input occurrence (duplicates
// each get invalid_duplicate_key), sorted by key for input-order independence.
struct PlanDecision {
    TrackKey key;
    bool selected = false;
    std::string reason;
    std::uint64_t estimated_cost_bytes = 0;
};

struct PlanResult {
    // False + error set when the tick itself is unusable (bad clock/view).
    // No state is mutated then and decisions/jobs are empty.
    bool ok = false;
    std::string error;
    std::vector<PlanDecision> decisions;
    std::vector<CaptureJob> jobs;  // rank order; size <= max_jobs_per_tick
    std::uint64_t total_bytes = 0;  // sum(costs) <= max_bytes_per_tick
};

// Bounded scheduler. Owns no threads, reads no clock, performs no I/O.
class ObservationPlanner {
  public:
    explicit ObservationPlanner(const PlannerConfig& config);

    bool config_ok() const { return config_ok_; }
    const std::string& config_error() const { return config_error_; }

    // Ranks the CURRENT candidates and selects a finite bounded set.
    // Deterministic: identical (now, view, candidate-set) inputs give
    // identical results regardless of input vector order.
    PlanResult Plan(std::int64_t now_ns, const std::string& view_id,
                    const std::vector<ObservationCandidate>& candidates);

    // Time-based expiry/drop with no new frames (frame gap / HealthTick /
    // stop path). Never touches the view. False + no mutation on rollback
    // or negative now; true (possibly no-op) when no view was ever seen.
    bool Advance(std::int64_t now_ns);

    std::size_t tracked_size() const;
    std::size_t quarantine_size() const;
    std::int64_t last_now_ns() const { return last_now_ns_; }
    const std::string& last_view_id() const { return last_view_id_; }

    std::uint64_t plans() const { return plans_; }
    std::uint64_t selections() const { return selections_; }
    std::uint64_t expiries() const { return expiries_; }
    std::uint64_t drops() const { return drops_; }

    // Deterministic multi-line state dump for host fixtures. Never persisted
    // here; the parent decides what (if anything) to seal.
    std::string Snapshot() const;

  private:
    struct TrackState {
        std::int64_t wait_start_ns = 0;    // seniority + starvation clock
        std::int64_t last_seen_ns = 0;     // presence clock (lost-drop)
        std::int64_t last_selected_ns = -1;  // cooldown anchor (-1 = never)
    };

    struct Ranked {
        std::size_t index = 0;  // index into the unique-candidate list
        ObservationPriority priority = ObservationPriority::kRoutine;
        std::int64_t wait_start_ns = 0;
        TrackKey key;
    };

    void ResetForView(const std::string& view_id, std::int64_t now_ns);
    // Drop lineages absent too long; purge lapsed quarantines; expire
    // starved waits (moving them into quarantine). Returns counts.
    void ExpireTimeBased(std::int64_t now_ns, std::uint64_t* expired_out,
                         std::uint64_t* dropped_out);
    void InsertQuarantine(const TrackKey& key, std::int64_t now_ns);
    static bool EnumValid(SourceRoiReadiness readiness);
    static bool EnumValid(ObservationPriority priority);

    PlannerConfig config_;
    bool config_ok_ = false;
    std::string config_error_;

    bool has_view_ = false;
    std::string last_view_id_;
    std::int64_t last_now_ns_ = -1;  // monotonic guard within one view

    // Ordered maps: deterministic iteration, bounded sizes.
    std::vector<std::pair<TrackKey, TrackState>> tracked_;  // sorted by key
    std::vector<std::pair<TrackKey, std::int64_t>> quarantine_;  // key->t

    std::uint64_t plans_ = 0;
    std::uint64_t selections_ = 0;
    std::uint64_t expiries_ = 0;
    std::uint64_t drops_ = 0;
};

}  // namespace ksi::safety::observation

#endif  // KSI_SAFETY_OBSERVATION_PLANNER_HPP
