// Risk evaluation engine: per-rule applicability/geometry/ACTIVE guards,
// near/far/unknown evidence classification, and the doc 17 §3 episode state
// machine stepped per (rule, lineage) frame. Pure logic: caller-provided
// timestamps only (no wall clock), standard library only, deterministic.
//
// Reference: doc 17 §§1-4; contracts/schemas/risk-policy.v1.schema.json
// (ACTIVE ⇒ METRIC_VERIFIED); workstream principles (detection independent of
// alignment; SHADOW-only until physically verified; no hardcoded thresholds).

#ifndef KSI_SAFETY_RISK_ENGINE_HPP
#define KSI_SAFETY_RISK_ENGINE_HPP

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "ksi_safety/risk/episode.hpp"
#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/spatial/relation.hpp"

namespace ksi::safety {

// Tracker lineage: a tracker reassignment (any component change) is a new
// lineage. Episodes never merge across lineages; the old lineage's open
// episode closes only via the max-gap OBSERVATION_LOST path.
struct TrackLineage {
    std::uint64_t tracker_epoch = 0;
    std::uint64_t person_track_id = 0;
    std::uint64_t equipment_track_id = 0;

    bool operator==(const TrackLineage& other) const;
    bool operator<(const TrackLineage& other) const;
};

enum class ProximityUnavailability {
    kPersonUnknown,
    kEquipmentUnknown,
};

struct ProximityEvidence {
    bool available = false;
    double lower_m = 0.0;
    double upper_m = 0.0;
    ProximityUnavailability unavailable_reason =
        ProximityUnavailability::kPersonUnknown;
};

struct ZoneEvidence {
    ZoneRelationKind relation = ZoneRelationKind::kUnknownStatus;
    std::string revision_id;
};

// One caller-provided frame for every rule. received_mono_ns is the frame's
// receive timestamp; enter/exit/gap durations are measured against it.
struct FrameInput {
    std::uint64_t received_mono_ns = 0;
    TrackLineage lineage;
    GeometryLevel geometry_level = GeometryLevel::kPixelOnly;
    std::string equipment_class;
    bool activation_granted = false;
    ProximityEvidence proximity;
    ZoneEvidence zone;
};

// Why evidence classified unknown (state machine input + diagnostics).
enum class UnknownReason {
    kPersonUnknown,
    kEquipmentUnknown,
    kIntervalStraddles,
    kBoundaryUncertain,
    kZoneStatusUnknown,
    kGeometryInsufficient,
};

const char* ToString(UnknownReason reason);

enum class RuleEvalStatus {
    kEvaluated,
    kSkippedOff,
    kNotApplicable,
    kWithheld,
    kGapSweep,
    kSessionEnd,
};

const char* ToString(RuleEvalStatus status);

enum class EvalDetail {
    kNone,
    kRuleOff,
    kEquipmentClassNotListed,
    kZoneRevisionNotListed,
    kGeometryBelowRequired,
    kActivationMissing,
    kActiveRequiresMetricVerified,
};

const char* ToString(EvalDetail detail);

struct RuleOutput {
    std::string rule_id;
    RuleEvalStatus status = RuleEvalStatus::kEvaluated;
    EvalDetail detail = EvalDetail::kNone;
    RuleMode effective_mode = RuleMode::kShadow;
    bool is_shadow = true;
    std::vector<EpisodeEvent> events;
};

// Classified-evidence alphabet feeding the episode state machine.
enum class EvidenceClass {
    kNear,
    kFar,
    kUnknown,
};

class RiskEngine {
  public:
    // max_gap_ms has no default: the caller must state it explicitly.
    explicit RiskEngine(std::uint64_t max_gap_ms);

    // Adds a rule; a repeated rule_id replaces the previous rule (tracked
    // lineage state for that id is reset). Negative hysteresis_m is
    // normalised to 0.
    void AddRule(const RiskRule& rule);
    std::size_t RuleCount() const;

    // White-box accessor for tests: current machine state, or false when the
    // rule id is unknown or the lineage was never evaluated/stepped.
    bool GetLineageState(const std::string& rule_id,
                         const TrackLineage& lineage,
                         EpisodeState* state) const;

    // Evaluates every rule for one frame, in rule-addition order.
    std::vector<RuleOutput> Evaluate(const FrameInput& frame);
    // Closes open episodes whose now - last_valid_evidence > max_gap_ms.
    // Only rules with at least one close appear in the result.
    std::vector<RuleOutput> Tick(std::uint64_t now_mono_ns);
    // Closes every open episode with kSessionEnd. Only rules with at least
    // one close appear in the result.
    std::vector<RuleOutput> EndSession(std::uint64_t now_mono_ns);

  private:
    struct LineageTrack {
        EpisodeState state = EpisodeState::kNoRelation;
        bool has_open_episode = false;
        std::uint64_t open_episode_id = 0;
        std::uint64_t enter_timer_start_ns = 0;
        std::uint64_t exit_timer_start_ns = 0;
        // Last near/far frame time; unknown/withheld frames never move it.
        std::uint64_t last_valid_evidence_ns = 0;
        bool has_valid_evidence = false;
        EvidenceSummary last_summary;
        RuleMode last_effective_mode = RuleMode::kShadow;
        bool last_is_shadow = true;
    };
    struct RuleEntry {
        RiskRule rule;
        std::map<TrackLineage, LineageTrack> tracks;
    };

    RuleOutput EvaluateRule(RuleEntry& entry, const FrameInput& frame);
    // Steps the doc 17 §3 machine for one classified frame. Unknown edges
    // (incl. geometry-insufficient) only move to/stay in UNKNOWN: they never
    // confirm (no Created) and never close.
    void StepMachine(RuleEntry& entry, LineageTrack& track,
                     const FrameInput& frame, EvidenceClass cls,
                     UnknownReason reason, RuleMode effective_mode,
                     bool is_shadow, RuleOutput& out);
    // Appends a Closed event and clears the open episode. The caller sets the
    // resulting lineage state (kFar in every path).
    static void PushClose(LineageTrack& track, const RiskRule& rule,
                          std::uint64_t received_mono_ns,
                          const EvidenceSummary& summary,
                          RuleMode effective_mode, bool is_shadow,
                          EpisodeCloseReason reason, RuleOutput& out);

    std::vector<RuleEntry> rules_;
    std::uint64_t max_gap_ms_ = 0;
    std::uint64_t next_episode_id_ = 1;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RISK_ENGINE_HPP
