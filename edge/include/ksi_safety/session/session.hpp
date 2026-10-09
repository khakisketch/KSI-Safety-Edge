// Synthetic safety-session orchestrator v0 (host-tested, deterministic).
//
// Role: compose one W5 Tracker with the W3 spatial relations
// (MinDistancePersonEquipment, RelatePointToZone, RelatePolygonToZone) and
// two W5 RiskEngines (one proximity, one zone) into a single per-tick call.
// No I/O, standard library only, namespace `ksi::safety`; thresholds,
// durations, and envelopes come only from the caller-provided
// SessionConfig / RiskRule / ZoneRevision inputs (nothing is hardcoded).
//
// Why TWO risk engines: RiskEngine::Evaluate evaluates EVERY rule for one
// frame, so a single engine holding both rule kinds would step proximity
// rules on zone frames (and zone rules on pair frames), pushing unrelated
// (rule, lineage) tracks through UNKNOWN churn. Splitting by rule kind
// keeps each frame meaningful to every rule that sees it.
//
// Ground observations are caller-provided per track per tick: the session
// performs no pose composition and no footpoint estimation. A person
// observation carries a footpoint (Vec2); an equipment observation carries
// a footprint polygon plus an equipment-class string (empty allowed — an
// empty class simply fails rules that list required classes).
// Observations are keyed by tracker track_id: ids are deterministic
// (tracker rule A3: unmatched detections mint ids in input order, never
// reused), so the caller reads them back from SessionOutput::tracks.
// Duplicate observations for one track_id in one tick: the last wins.
// Observations for unknown (non-live) track ids are ignored.
//
// Proximity evidence uses the point distance for BOTH interval ends
// (lower_m == upper_m == distance). Interval expansion from pose/ground
// uncertainty is future work; the point value is exact, not a bound.
//
// Activation is never granted (activation_granted = false on every frame):
// this synthetic session is SHADOW-only, so ACTIVE rules degrade to
// effective SHADOW per the engine guard. Track snapshots use every live
// track (confirmed and unconfirmed); callers filter on `confirmed`.
// Sweep()/EndSession() carry no track snapshot (tracker state is
// untouched; the caller's copy from the last Tick() stays current).
//
// Multi-revision caveat: the engine keys episode state per (rule, lineage),
// not per revision, so one zone rule fed several candidate revisions in one
// tick steps a single lineage through each revision in zone order
// (last frame wins within the tick). Prefer one revision per zone rule
// when per-revision episodes matter.
//
// Determinism: no wall clock, no RNG, ordered containers and index loops
// only. Identical scripted inputs produce identical events and ids.
//
// Reference: doc 17 §§1-4; workstream principles (SHADOW-only until
// physically verified; no flight/mission code).

#ifndef KSI_SAFETY_SESSION_SESSION_HPP
#define KSI_SAFETY_SESSION_SESSION_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/perception/tracker.hpp"
#include "ksi_safety/risk/engine.hpp"
#include "ksi_safety/risk/episode.hpp"
#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/spatial/distance.hpp"
#include "ksi_safety/spatial/relation.hpp"

namespace ksi::safety {

// Session-wide configuration. Every field is caller-stated: the session
// logic supplies no threshold, duration, gap, or envelope default.
struct SessionConfig {
    TrackerConfig tracker;
    std::uint64_t max_gap_ms;
    double zone_envelope_m;
};

enum class GroundTrackKind {
    kPerson,
    kEquipment,
};

// Caller-provided ground truth for one tracker track on one tick.
struct GroundObservation {
    std::uint64_t track_id = 0;  // Tracker track_id live this tick.
    GroundTrackKind kind = GroundTrackKind::kPerson;
    GeometryLevel geometry_level = GeometryLevel::kPixelOnly;
    Vec2 person_footpoint;         // Person only.
    Polygon2 equipment_footprint;  // Equipment only.
    std::string equipment_class;   // Equipment only; empty allowed.
};

struct SessionOutput {
    std::vector<Track> tracks;        // Live tracks, ascending track_id.
    std::vector<RuleOutput> proximity;  // Proximity-engine outputs.
    std::vector<RuleOutput> zone;       // Zone-engine outputs.
};

class SafetySession {
  public:
    SafetySession(const SessionConfig& config,
                  const std::vector<RiskRule>& proximity_rules,
                  const std::vector<RiskRule>& zone_rules,
                  const std::vector<ZoneRevision>& zones);

    // One deterministic step: tracker.Update(detections), then proximity
    // pair frames and zone (track x revision) frames through the two
    // engines. received_mono_ns is caller-provided data (no clock read).
    SessionOutput Tick(std::uint64_t received_mono_ns,
                       const std::vector<Detection>& detections,
                       const std::vector<GroundObservation>& ground_observations);

    // Gap sweep without new evidence: both engines' Tick(now). No track
    // snapshot (tracker untouched).
    SessionOutput Sweep(std::uint64_t now_mono_ns);

    // Session end: both engines' EndSession(now). No track snapshot.
    SessionOutput EndSession(std::uint64_t now_mono_ns);

    // Tracker reset: epoch bump, all tracks dropped, id counter kept (no
    // reuse). W5 lineages never merge across epochs; the old epoch's open
    // episodes close only via the max-gap OBSERVATION_LOST path on Sweep.
    void ResetTracker();

    std::uint64_t TrackerEpoch() const;

    // Current live tracks (same shape/order as the snapshot Tick returns;
    // no state change). Used by the R1 session runner to carry tracker state
    // through ticks with no detection evidence.
    std::vector<Track> LiveTracks() const;

  private:
    SessionConfig config_;
    Tracker tracker_;
    RiskEngine proximity_engine_;
    RiskEngine zone_engine_;
    std::vector<RiskRule> zone_rules_;  // Retained for candidate revisions.
    std::vector<ZoneRevision> zones_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_SESSION_SESSION_HPP
