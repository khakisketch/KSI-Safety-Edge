// Episode lifecycle types: doc 17 §3 pair-episode state machine and §4 zone
// intrusion evidence. Episodes are created once on NEAR entry, touched with
// evidence while open, and closed with exactly one reason; a close never
// claims a confirmed exit unless the exit was continuously observed.

#ifndef KSI_SAFETY_RISK_EPISODE_HPP
#define KSI_SAFETY_RISK_EPISODE_HPP

#include <cstdint>
#include <string>

#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/spatial/relation.hpp"

namespace ksi::safety {

// Per-(rule, lineage) machine state. Doc 17 CLOSED is a per-episode terminal
// reported via EpisodeEvent, not a stored lineage state: after any close the
// lineage rests in kFar and the next NEAR confirmation mints a new id.
enum class EpisodeState {
    kNoRelation,
    kFar,
    kNearCandidate,
    kNear,
    kExitPending,
    kUnknown,
};

const char* ToString(EpisodeState state);

enum class EpisodeEventKind {
    kCreated,
    kUpdated,
    kClosed,
};

const char* ToString(EpisodeEventKind kind);

// kExitConfirmed: far evidence persisted for exit_duration_ms.
// kExitNotObserved: far evidence arrived after an observation gap, so the
//   exit was not continuously observed (never labelled EXIT_CONFIRMED).
// kObservationLost: no valid evidence within max_gap_ms (never auto-"safe").
// kSessionEnd: EndSession closed the still-open episode.
enum class EpisodeCloseReason {
    kExitConfirmed,
    kExitNotObserved,
    kObservationLost,
    kSessionEnd,
};

const char* ToString(EpisodeCloseReason reason);

// Compact caller-visible evidence carried on every event: the proximity
// interval or the zone relation + revision, plus the frame geometry level.
struct EvidenceSummary {
    bool is_proximity = true;
    // Proximity half:
    bool interval_available = false;
    double lower_m = 0.0;
    double upper_m = 0.0;
    // Zone half:
    ZoneRelationKind zone_relation = ZoneRelationKind::kUnknownStatus;
    std::string zone_revision_id;
    // Both halves:
    GeometryLevel geometry_level = GeometryLevel::kPixelOnly;
};

struct EpisodeEvent {
    EpisodeEventKind kind = EpisodeEventKind::kCreated;
    // Deterministic monotonic counter owned by the engine (starts at 1).
    std::uint64_t episode_id = 0;
    std::string rule_id;
    // Effective mode for this event; is_shadow is true unless ACTIVE output
    // was explicitly allowed (mode ACTIVE + activation grant +
    // METRIC_VERIFIED requirement).
    RuleMode effective_mode = RuleMode::kShadow;
    bool is_shadow = true;
    bool has_close_reason = false;
    EpisodeCloseReason close_reason = EpisodeCloseReason::kExitConfirmed;
    std::uint64_t received_mono_ns = 0;
    EvidenceSummary evidence;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RISK_EPISODE_HPP
