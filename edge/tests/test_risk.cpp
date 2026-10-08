// risk: policy contract strings, guards, evidence classification, and the
// doc 17 §3 episode state machine (enter/exit persistence, hysteresis,
// straddling intervals, zone verdicts, observation loss, lineage split,
// geometry gate, ACTIVE guard, OFF skip, determinism, applicability).

#include "ksi_safety/risk/engine.hpp"
#include "ksi_safety/risk/episode.hpp"
#include "ksi_safety/risk/policy.hpp"

#include <cstdint>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

#include "support/check.hpp"

using ksi::safety::EpisodeCloseReason;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::EpisodeState;
using ksi::safety::EvalDetail;
using ksi::safety::EvidenceSummary;
using ksi::safety::FrameInput;
using ksi::safety::GeometryLevel;
using ksi::safety::ProximityUnavailability;
using ksi::safety::RiskEngine;
using ksi::safety::RiskRule;
using ksi::safety::RuleEvalStatus;
using ksi::safety::RuleKind;
using ksi::safety::RuleMode;
using ksi::safety::RuleOutput;
using ksi::safety::TrackLineage;
using ksi::safety::ZoneRelationKind;

namespace {

constexpr std::uint64_t kMs = 1000000ULL;

std::uint64_t Ms(std::uint64_t ms) {
    return ms * kMs;
}

// Arbitrary monotonic origin (ms since an unspecified epoch).
constexpr std::uint64_t kT0 = 1000000ULL * 1000ULL;

TrackLineage Lin(std::uint64_t epoch, std::uint64_t person,
                 std::uint64_t equip) {
    TrackLineage lin;
    lin.tracker_epoch = epoch;
    lin.person_track_id = person;
    lin.equipment_track_id = equip;
    return lin;
}

RiskRule ProxRule(const std::string& id, RuleMode mode, double threshold_m,
                  double hysteresis_m, std::uint64_t enter_ms,
                  std::uint64_t exit_ms,
                  GeometryLevel required = GeometryLevel::kPixelOnly) {
    RiskRule rule;
    rule.rule_id = id;
    rule.kind = RuleKind::kPersonEquipmentProximity;
    rule.mode = mode;
    rule.threshold_m = threshold_m;
    rule.hysteresis_m = hysteresis_m;
    rule.enter_duration_ms = enter_ms;
    rule.exit_duration_ms = exit_ms;
    rule.required_geometry_level = required;
    return rule;
}

RiskRule ZoneRule(const std::string& id, RuleMode mode,
                  std::uint64_t enter_ms, std::uint64_t exit_ms,
                  GeometryLevel required = GeometryLevel::kPixelOnly) {
    RiskRule rule;
    rule.rule_id = id;
    rule.kind = RuleKind::kZoneIntrusion;
    rule.mode = mode;
    rule.enter_duration_ms = enter_ms;
    rule.exit_duration_ms = exit_ms;
    rule.required_geometry_level = required;
    return rule;
}

FrameInput BaseFrame(std::uint64_t t_ns, const TrackLineage& lin) {
    FrameInput frame;
    frame.received_mono_ns = t_ns;
    frame.lineage = lin;
    frame.geometry_level = GeometryLevel::kPixelOnly;
    return frame;
}

FrameInput NearFrame(std::uint64_t t_ns, const TrackLineage& lin, double lo,
                     double hi) {
    FrameInput frame = BaseFrame(t_ns, lin);
    frame.proximity.available = true;
    frame.proximity.lower_m = lo;
    frame.proximity.upper_m = hi;
    return frame;
}

FrameInput UnavailFrame(std::uint64_t t_ns, const TrackLineage& lin,
                        ProximityUnavailability why) {
    FrameInput frame = BaseFrame(t_ns, lin);
    frame.proximity.available = false;
    frame.proximity.unavailable_reason = why;
    return frame;
}

FrameInput ZoneFrame(std::uint64_t t_ns, const TrackLineage& lin,
                     ZoneRelationKind relation, const std::string& revision) {
    FrameInput frame = BaseFrame(t_ns, lin);
    frame.zone.relation = relation;
    frame.zone.revision_id = revision;
    return frame;
}

void AppendEvents(const std::vector<RuleOutput>& outputs,
                  std::vector<EpisodeEvent>* sink) {
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        for (std::size_t k = 0; k < outputs[i].events.size(); ++k) {
            sink->push_back(outputs[i].events[k]);
        }
    }
}

int CountKind(const std::vector<EpisodeEvent>& events, EpisodeEventKind kind) {
    int n = 0;
    for (std::size_t i = 0; i < events.size(); ++i) {
        if (events[i].kind == kind) {
            ++n;
        }
    }
    return n;
}

bool HasCloseReason(const std::vector<EpisodeEvent>& events,
                    EpisodeCloseReason reason) {
    for (std::size_t i = 0; i < events.size(); ++i) {
        if (events[i].kind == EpisodeEventKind::kClosed &&
            events[i].has_close_reason &&
            events[i].close_reason == reason) {
            return true;
        }
    }
    return false;
}

EpisodeState StateOf(RiskEngine& engine, const std::string& rule_id,
                     const TrackLineage& lin) {
    EpisodeState state = EpisodeState::kNoRelation;
    const bool ok = engine.GetLineageState(rule_id, lin, &state);
    CHECK(ok, "lineage state present");
    return state;
}

std::string DescribeEvent(const EpisodeEvent& e) {
    std::ostringstream ss;
    ss << ksi::safety::ToString(e.kind) << "|" << e.episode_id << "|"
       << e.rule_id << "|" << ksi::safety::ToString(e.effective_mode) << "|"
       << (e.is_shadow ? "S" : "A") << "|";
    if (e.has_close_reason) {
        ss << ksi::safety::ToString(e.close_reason);
    } else {
        ss << "-";
    }
    ss << "|" << e.received_mono_ns << "|";
    if (e.evidence.is_proximity) {
        ss << "P:" << (e.evidence.interval_available ? "Y" : "N") << ":"
           << e.evidence.lower_m << ":" << e.evidence.upper_m;
    } else {
        ss << "Z:" << ksi::safety::ToString(e.evidence.zone_relation) << ":"
           << e.evidence.zone_revision_id;
    }
    ss << "|" << ksi::safety::ToString(e.evidence.geometry_level);
    return ss.str();
}

std::string DescribeOutput(const RuleOutput& o) {
    std::ostringstream ss;
    ss << o.rule_id << "|" << ksi::safety::ToString(o.status) << "|"
       << ksi::safety::ToString(o.detail) << "|"
       << ksi::safety::ToString(o.effective_mode) << "|"
       << (o.is_shadow ? "S" : "A") << "|";
    for (std::size_t i = 0; i < o.events.size(); ++i) {
        ss << "[" << DescribeEvent(o.events[i]) << "]";
    }
    return ss.str();
}

}  // namespace

int main() {
    // ---- 1) contract-string alignment ----
    {
        using ksi::safety::ToString;
        CHECK(std::string(ToString(RuleKind::kPersonEquipmentProximity)) ==
                  "PERSON_EQUIPMENT_PROXIMITY",
              "kind proximity string");
        CHECK(std::string(ToString(RuleKind::kZoneIntrusion)) ==
                  "ZONE_INTRUSION",
              "kind zone string");
        CHECK(std::string(ToString(RuleMode::kOff)) == "OFF", "mode off");
        CHECK(std::string(ToString(RuleMode::kShadow)) == "SHADOW",
              "mode shadow");
        CHECK(std::string(ToString(RuleMode::kActive)) == "ACTIVE",
              "mode active");
        CHECK(std::string(ToString(GeometryLevel::kPixelOnly)) ==
                  "PIXEL_ONLY",
              "geometry pixel");
        CHECK(std::string(ToString(GeometryLevel::kTimeUnverified)) ==
                  "TIME_UNVERIFIED",
              "geometry time");
        CHECK(std::string(ToString(GeometryLevel::kGeoEstimate)) ==
                  "GEO_ESTIMATE",
              "geometry estimate");
        CHECK(std::string(ToString(GeometryLevel::kMetricVerified)) ==
                  "METRIC_VERIFIED",
              "geometry metric");
        CHECK(std::string(ToString(EpisodeCloseReason::kExitConfirmed)) ==
                  "EXIT_CONFIRMED",
              "close exit confirmed");
        CHECK(std::string(ToString(EpisodeCloseReason::kExitNotObserved)) ==
                  "EXIT_NOT_OBSERVED",
              "close exit not observed");
        CHECK(std::string(ToString(EpisodeCloseReason::kObservationLost)) ==
                  "OBSERVATION_LOST",
              "close observation lost");
        CHECK(std::string(ToString(EpisodeCloseReason::kSessionEnd)) ==
                  "SESSION_END",
              "close session end");
    }
    // ---- 2) proximity happy path: exactly one create, then updates ----
    {
        RiskEngine engine(/*max_gap_ms=*/5000);
        engine.AddRule(
            ProxRule("prox", RuleMode::kShadow, 3.0, 1.0, 2000, 2000));
        const TrackLineage lin = Lin(1, 1, 1);
        std::vector<EpisodeEvent> events;

        // First valid evidence establishes FAR (doc 17 NO_RELATION -> FAR).
        AppendEvents(engine.Evaluate(NearFrame(kT0, lin, 10.0, 11.0)),
                     &events);
        CHECK(events.empty(), "far first frame: no events");
        CHECK(StateOf(engine, "prox", lin) == EpisodeState::kFar,
              "first valid evidence -> FAR");

        // Enter accumulation: candidate at t+100, still candidate at
        // t+1100, confirmed at t+2100 (received - start >= enter 2000ms).
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(100), lin, 1.0, 2.0)),
            &events);
        CHECK(events.empty(), "candidate: no create yet");
        CHECK(StateOf(engine, "prox", lin) == EpisodeState::kNearCandidate,
              "near from FAR -> NEAR_CANDIDATE");
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(1100), lin, 1.0, 2.0)),
            &events);
        CHECK(events.empty(), "enter not yet elapsed: still no create");
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(2100), lin, 1.0, 2.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "exactly one create on enter");
        const std::uint64_t id = events.back().episode_id;
        CHECK(events.back().rule_id == "prox", "create carries rule id");
        CHECK(events.back().is_shadow, "shadow rule -> shadow event");

        // Continuous near frames: updates only, no duplicate create.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(2200), lin, 1.0, 2.0)),
            &events);
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(2300), lin, 1.0, 2.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "no duplicate create");
        CHECK(CountKind(events, EpisodeEventKind::kUpdated) == 2,
              "near stay emits updates");
        CHECK(events.back().episode_id == id, "updates reuse episode id");

        // Zero enter duration is immediate, even on the very first frame
        // (NO_RELATION -> FAR -> NEAR_CANDIDATE -> NEAR in one step).
        RiskEngine fast(/*max_gap_ms=*/5000);
        fast.AddRule(ProxRule("fast", RuleMode::kShadow, 3.0, 1.0, 0, 0));
        std::vector<EpisodeEvent> fast_events;
        AppendEvents(fast.Evaluate(NearFrame(kT0, lin, 1.0, 2.0)),
                     &fast_events);
        CHECK(CountKind(fast_events, EpisodeEventKind::kCreated) == 1,
              "enter=0 confirms on first frame");
    }
    // ---- 3) hysteresis + exit persistence + return ----
    {
        RiskEngine engine(/*max_gap_ms=*/10000);
        engine.AddRule(ProxRule("h", RuleMode::kShadow, 3.0, 1.0, 0, 2000));
        const TrackLineage lin = Lin(1, 2, 2);
        std::vector<EpisodeEvent> events;
        AppendEvents(engine.Evaluate(NearFrame(kT0, lin, 1.0, 2.0)), &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "hysteresis setup: immediate NEAR");
        const std::uint64_t id = events.back().episode_id;

        // Inside the (threshold, threshold+hysteresis] band the interval
        // straddles: unknown, episode stays open, no exit, no close.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(100), lin, 3.2, 3.5)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "band interval: no exit, no close");
        CHECK(StateOf(engine, "h", lin) == EpisodeState::kUnknown,
              "band interval -> UNKNOWN, episode open");

        // Near again: episode continues with the same id.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(200), lin, 1.0, 2.0)),
            &events);
        CHECK(events.back().kind == EpisodeEventKind::kUpdated &&
                  events.back().episode_id == id,
              "return to near continues episode");
        CHECK(StateOf(engine, "h", lin) == EpisodeState::kNear,
              "return to near -> NEAR");

        // Completely beyond threshold+hysteresis: EXIT_PENDING, no close
        // until exit_duration elapses.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(300), lin, 4.5, 5.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "exit pending: no close yet");
        CHECK(StateOf(engine, "h", lin) == EpisodeState::kExitPending,
              "beyond hysteresis -> EXIT_PENDING");

        // Returning near cancels the exit without closing.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(400), lin, 1.0, 2.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "exit cancelled: still no close");
        CHECK(StateOf(engine, "h", lin) == EpisodeState::kNear,
              "near from EXIT_PENDING -> NEAR");

        // Sustained far: EXIT_PENDING at +500, confirmed at +2500.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(500), lin, 4.5, 5.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "second exit pending: no close yet");
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(1500), lin, 4.5, 5.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "exit not yet elapsed");
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(2500), lin, 4.5, 5.0)),
            &events);
        CHECK(HasCloseReason(events, EpisodeCloseReason::kExitConfirmed),
              "sustained far -> EXIT_CONFIRMED");
        CHECK(StateOf(engine, "h", lin) == EpisodeState::kFar,
              "after close lineage rests in FAR");
    }
    // ---- 4) straddling interval from FAR never confirms ----
    {
        RiskEngine engine(/*max_gap_ms=*/10000);
        engine.AddRule(
            ProxRule("s", RuleMode::kShadow, 3.0, 1.0, 3000, 1000));
        const TrackLineage lin = Lin(1, 3, 3);
        std::vector<EpisodeEvent> events;
        AppendEvents(engine.Evaluate(NearFrame(kT0, lin, 10.0, 11.0)),
                     &events);
        // Straddle well past enter_duration: still no confirmation.
        for (int i = 1; i <= 4; ++i) {
            AppendEvents(
                engine.Evaluate(
                    NearFrame(kT0 + Ms(static_cast<std::uint64_t>(i) * 1000),
                              lin, 2.5, 3.5)),
                &events);
        }
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 0,
              "straddle from FAR never confirms");
        CHECK(StateOf(engine, "s", lin) == EpisodeState::kUnknown,
              "straddle from FAR -> UNKNOWN");

        // Fresh near re-enters candidacy (no episode to continue), then
        // confirms after the full enter duration.
        AppendEvents(engine.Evaluate(NearFrame(kT0 + Ms(5000), lin, 1.0, 2.0)),
                     &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 0,
              "fresh candidacy needs full enter duration");
        CHECK(StateOf(engine, "s", lin) == EpisodeState::kNearCandidate,
              "near from episode-less UNKNOWN -> NEAR_CANDIDATE");
        AppendEvents(engine.Evaluate(NearFrame(kT0 + Ms(8000), lin, 1.0, 2.0)),
                     &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "enter elapsed -> create");
        const std::uint64_t id = events.back().episode_id;

        // NEAR + straddle: the episode stays open (no close); the next near
        // continues the same episode.
        AppendEvents(engine.Evaluate(NearFrame(kT0 + Ms(8100), lin, 2.5, 3.5)),
                     &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "NEAR + straddle: no close");
        AppendEvents(engine.Evaluate(NearFrame(kT0 + Ms(8200), lin, 1.0, 2.0)),
                     &events);
        CHECK(events.back().kind == EpisodeEventKind::kUpdated &&
                  events.back().episode_id == id,
              "NEAR + straddle keeps the episode open");
    }
    // ---- 5) zone verdicts ----
    {
        RiskEngine engine(/*max_gap_ms=*/10000);
        engine.AddRule(ZoneRule("z", RuleMode::kShadow, 2000, 1000));
        const TrackLineage lin = Lin(1, 4, 4);
        std::vector<EpisodeEvent> events;

        // Boundary-uncertain can never confirm NEAR.
        for (int i = 0; i < 3; ++i) {
            AppendEvents(
                engine.Evaluate(ZoneFrame(
                    kT0 + Ms(static_cast<std::uint64_t>(i) * 500), lin,
                    ZoneRelationKind::kBoundaryUncertain, "rev1")),
                &events);
        }
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 0,
              "boundary-uncertain never confirms NEAR");

        // Sustained inside: candidacy then create.
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(3000), lin,
                                      ZoneRelationKind::kInside, "rev1")),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 0,
              "inside candidacy: no create yet");
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(5000), lin,
                                      ZoneRelationKind::kInside, "rev1")),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "sustained inside -> NEAR");
        CHECK(events.back().evidence.is_proximity == false &&
                  events.back().evidence.zone_revision_id == "rev1",
              "zone create carries relation + revision");
        const std::uint64_t id1 = events.back().episode_id;

        // Sustained outside: EXIT_CONFIRMED path.
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(5100), lin,
                                      ZoneRelationKind::kOutside, "rev1")),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 0,
              "outside first frame: exit pending");
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(6100), lin,
                                      ZoneRelationKind::kOutside, "rev1")),
            &events);
        CHECK(HasCloseReason(events, EpisodeCloseReason::kExitConfirmed),
              "sustained outside -> EXIT_CONFIRMED");

        // Re-enter, then boundary-uncertain while NEAR: UNKNOWN, not closed.
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(7000), lin,
                                      ZoneRelationKind::kInside, "rev1")),
            &events);
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(9000), lin,
                                      ZoneRelationKind::kInside, "rev1")),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 2,
              "second episode created with new id");
        const std::uint64_t id2 = events.back().episode_id;
        CHECK(id2 != id1, "episodes after close mint new ids");
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(9100), lin,
                                      ZoneRelationKind::kBoundaryUncertain,
                                      "rev1")),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 1,
              "boundary-uncertain while NEAR: not closed");
        CHECK(StateOf(engine, "z", lin) == EpisodeState::kUnknown,
              "boundary-uncertain while NEAR -> UNKNOWN");
        AppendEvents(
            engine.Evaluate(ZoneFrame(kT0 + Ms(9200), lin,
                                      ZoneRelationKind::kInside, "rev1")),
            &events);
        CHECK(events.back().kind == EpisodeEventKind::kUpdated &&
                  events.back().episode_id == id2,
              "inside after uncertain continues episode");
    }
    // ---- 6) observation loss ----
    {
        // Tick beyond max_gap closes OBSERVATION_LOST (never EXIT_CONFIRMED,
        // never auto-"safe").
        RiskEngine engine(/*max_gap_ms=*/2000);
        engine.AddRule(ProxRule("g", RuleMode::kShadow, 3.0, 1.0, 0, 5000));
        const TrackLineage lin = Lin(1, 5, 5);
        std::vector<EpisodeEvent> events;
        AppendEvents(engine.Evaluate(NearFrame(kT0, lin, 1.0, 2.0)), &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "gap setup: NEAR open");
        const std::uint64_t id = events.back().episode_id;

        std::vector<RuleOutput> tick = engine.Tick(kT0 + Ms(2000));
        CHECK(tick.empty(), "gap of exactly max_gap is still continuous");
        tick = engine.Tick(kT0 + Ms(2001));
        AppendEvents(tick, &events);
        CHECK(HasCloseReason(events, EpisodeCloseReason::kObservationLost),
              "no evidence past max_gap -> OBSERVATION_LOST");
        CHECK(events.back().episode_id == id, "gap closes the open episode");
        CHECK(!HasCloseReason(events, EpisodeCloseReason::kExitConfirmed),
              "loss is never labelled EXIT_CONFIRMED");
        CHECK(StateOf(engine, "g", lin) == EpisodeState::kFar,
              "after loss lineage rests in FAR");

        // Far evidence after a gap without continuous exit closes as
        // EXIT_NOT_OBSERVED (never EXIT_CONFIRMED).
        RiskEngine engine2(/*max_gap_ms=*/2000);
        engine2.AddRule(ProxRule("g", RuleMode::kShadow, 3.0, 1.0, 0, 5000));
        std::vector<EpisodeEvent> events2;
        AppendEvents(engine2.Evaluate(NearFrame(kT0, lin, 1.0, 2.0)),
                     &events2);
        AppendEvents(
            engine2.Evaluate(NearFrame(kT0 + Ms(5000), lin, 10.0, 11.0)),
            &events2);
        CHECK(CountKind(events2, EpisodeEventKind::kClosed) == 1,
              "far after gap closes once");
        CHECK(HasCloseReason(events2, EpisodeCloseReason::kExitNotObserved),
              "far after gap -> EXIT_NOT_OBSERVED");
        CHECK(!HasCloseReason(events2, EpisodeCloseReason::kExitConfirmed),
              "gapped exit is never EXIT_CONFIRMED");
    }
    // ---- 7) tracker epoch change splits lineages ----
    {
        RiskEngine engine(/*max_gap_ms=*/2000);
        engine.AddRule(ProxRule("e", RuleMode::kShadow, 3.0, 1.0, 0, 5000));
        const TrackLineage old_lin = Lin(1, 7, 7);
        const TrackLineage new_lin = Lin(2, 7, 7);
        std::vector<EpisodeEvent> events;
        AppendEvents(engine.Evaluate(NearFrame(kT0, old_lin, 1.0, 2.0)),
                     &events);
        const std::uint64_t old_id = events.back().episode_id;
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(100), new_lin, 1.0, 2.0)),
            &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 2,
              "new lineage mints its own episode");
        const std::uint64_t new_id = events.back().episode_id;
        CHECK(new_id != old_id, "no merge across lineages");

        // The old lineage's episode outlives the reassignment until the gap
        // expires, then closes OBSERVATION_LOST.
        std::vector<RuleOutput> tick = engine.Tick(kT0 + Ms(2050));
        AppendEvents(tick, &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 1,
              "only the gapped lineage closes");
        CHECK(events.back().episode_id == old_id &&
                  events.back().has_close_reason &&
                  events.back().close_reason ==
                      EpisodeCloseReason::kObservationLost,
              "old lineage -> OBSERVATION_LOST");

        // The new lineage is still open and continues with its own id.
        AppendEvents(
            engine.Evaluate(NearFrame(kT0 + Ms(2100), new_lin, 1.0, 2.0)),
            &events);
        CHECK(events.back().kind == EpisodeEventKind::kUpdated &&
                  events.back().episode_id == new_id,
              "new lineage continues undisturbed");
    }
    // ---- 8) geometry gate ----
    {
        RiskEngine engine(/*max_gap_ms=*/5000);
        engine.AddRule(ProxRule("geo", RuleMode::kShadow, 3.0, 1.0, 1000,
                                1000, GeometryLevel::kGeoEstimate));
        const TrackLineage lin = Lin(1, 8, 8);
        // Below-required frames are WITHHELD: no create even past enter.
        for (int i = 0; i < 4; ++i) {
            FrameInput frame =
                NearFrame(kT0 + Ms(static_cast<std::uint64_t>(i) * 500), lin,
                            1.0, 2.0);
            frame.geometry_level = GeometryLevel::kPixelOnly;
            const std::vector<RuleOutput> outs = engine.Evaluate(frame);
            CHECK(outs.size() == 1, "one rule output");
            CHECK(outs[0].status == RuleEvalStatus::kWithheld &&
                      outs[0].detail == EvalDetail::kGeometryBelowRequired,
                  "below-required -> WITHHELD");
            CHECK(outs[0].events.empty(), "withheld: no events");
        }
        // Geometry met: normal evaluation resumes.
        FrameInput ok = NearFrame(kT0 + Ms(2000), lin, 1.0, 2.0);
        ok.geometry_level = GeometryLevel::kGeoEstimate;
        std::vector<EpisodeEvent> events;
        AppendEvents(engine.Evaluate(ok), &events);
        CHECK(events.empty(), "fresh candidacy after gate opens");
        FrameInput ok2 = NearFrame(kT0 + Ms(3000), lin, 1.0, 2.0);
        ok2.geometry_level = GeometryLevel::kGeoEstimate;
        AppendEvents(engine.Evaluate(ok2), &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "level met -> normal confirmation");
    }
    // ---- 9) ACTIVE guard, both directions ----
    {
        RiskEngine engine(/*max_gap_ms=*/5000);
        engine.AddRule(ProxRule("a", RuleMode::kActive, 5.0, 0.0, 0, 1000,
                                GeometryLevel::kMetricVerified));
        const TrackLineage denied = Lin(1, 9, 9);
        const TrackLineage granted = Lin(1, 10, 10);
        std::vector<EpisodeEvent> events;

        // ACTIVE without a grant: effective SHADOW, shadow-labelled output,
        // zero active outputs.
        FrameInput d0 = NearFrame(kT0, denied, 1.0, 2.0);
        d0.geometry_level = GeometryLevel::kMetricVerified;
        d0.activation_granted = false;
        std::vector<RuleOutput> deny = engine.Evaluate(d0);
        CHECK(deny[0].status == RuleEvalStatus::kEvaluated &&
                  deny[0].detail == EvalDetail::kActivationMissing,
              "ACTIVE without grant -> effective SHADOW + reason");
        CHECK(deny[0].effective_mode == RuleMode::kShadow &&
                  deny[0].is_shadow,
              "denied output is shadow");
        AppendEvents(deny, &events);
        FrameInput d1 = NearFrame(kT0 + Ms(100), denied, 1.0, 2.0);
        d1.geometry_level = GeometryLevel::kMetricVerified;
        AppendEvents(engine.Evaluate(d1), &events);
        for (std::size_t i = 0; i < events.size(); ++i) {
            CHECK(events[i].is_shadow &&
                      events[i].effective_mode != RuleMode::kActive,
                  "zero active outputs without grant");
        }

        // ACTIVE + grant + METRIC_VERIFIED: the active path emits.
        FrameInput g0 = NearFrame(kT0 + Ms(200), granted, 1.0, 2.0);
        g0.geometry_level = GeometryLevel::kMetricVerified;
        g0.activation_granted = true;
        std::vector<RuleOutput> allow = engine.Evaluate(g0);
        CHECK(allow[0].effective_mode == RuleMode::kActive &&
                  !allow[0].is_shadow,
              "grant + METRIC_VERIFIED -> ACTIVE allowed");
        CHECK(allow[0].events.size() == 1 &&
                  allow[0].events[0].kind == EpisodeEventKind::kCreated &&
                  allow[0].events[0].effective_mode == RuleMode::kActive &&
                  !allow[0].events[0].is_shadow,
              "active create emitted");
    }
    // ---- 10) OFF rule: no evaluations, no episodes ----
    {
        RiskEngine engine(/*max_gap_ms=*/5000);
        engine.AddRule(ProxRule("off", RuleMode::kOff, 3.0, 1.0, 0, 0));
        const TrackLineage lin = Lin(1, 11, 11);
        for (int i = 0; i < 3; ++i) {
            const std::vector<RuleOutput> outs = engine.Evaluate(
                NearFrame(kT0 + Ms(static_cast<std::uint64_t>(i) * 100), lin,
                            1.0, 2.0));
            CHECK(outs[0].status == RuleEvalStatus::kSkippedOff,
                  "OFF -> skipped");
            CHECK(outs[0].events.empty(), "OFF -> no events");
        }
        EpisodeState ignored = EpisodeState::kFar;
        CHECK(!engine.GetLineageState("off", lin, &ignored),
              "OFF leaves no lineage state");
        CHECK(engine.Tick(kT0 + Ms(100000)).empty(), "OFF tick silent");
        CHECK(engine.EndSession(kT0 + Ms(100000)).empty(),
              "OFF session-end silent");
    }
    // ---- 11) determinism: same script twice -> identical outputs ----
    {
        auto run_script = []() {
            RiskEngine engine(/*max_gap_ms=*/2000);
            engine.AddRule(
                ProxRule("r-prox", RuleMode::kShadow, 3.0, 1.0, 1000, 1000));
            engine.AddRule(ZoneRule("r-zone", RuleMode::kShadow, 500, 500));
            // Separate lineages so each rule runs a full lifecycle.
            const TrackLineage lin_p = Lin(1, 12, 12);
            const TrackLineage lin_z = Lin(1, 13, 13);
            std::vector<std::string> log;
            auto feed = [&](const FrameInput& frame) {
                const std::vector<RuleOutput> outs = engine.Evaluate(frame);
                for (std::size_t i = 0; i < outs.size(); ++i) {
                    log.push_back(DescribeOutput(outs[i]));
                }
            };
            auto geo = [](FrameInput frame) {
                frame.geometry_level = GeometryLevel::kGeoEstimate;
                frame.equipment_class = "excavator";
                return frame;
            };
            feed(geo(NearFrame(kT0, lin_p, 10.0, 11.0)));
            feed(geo(ZoneFrame(kT0 + Ms(100), lin_z,
                               ZoneRelationKind::kOutside, "revA")));
            feed(geo(NearFrame(kT0 + Ms(500), lin_p, 1.0, 2.0)));
            feed(geo(ZoneFrame(kT0 + Ms(200), lin_z,
                               ZoneRelationKind::kInside, "revA")));
            feed(geo(ZoneFrame(kT0 + Ms(700), lin_z,
                               ZoneRelationKind::kInside, "revA")));
            feed(geo(NearFrame(kT0 + Ms(1500), lin_p, 1.0, 2.0)));
            feed(geo(NearFrame(kT0 + Ms(1600), lin_p, 1.0, 2.0)));
            feed(geo(ZoneFrame(kT0 + Ms(800), lin_z,
                               ZoneRelationKind::kBoundaryUncertain, "revA")));
            feed(geo(NearFrame(kT0 + Ms(1700), lin_p, 2.5, 3.5)));
            feed(geo(ZoneFrame(kT0 + Ms(900), lin_z,
                               ZoneRelationKind::kInside, "revA")));
            feed(geo(NearFrame(kT0 + Ms(1800), lin_p, 1.0, 2.0)));
            feed(geo(UnavailFrame(kT0 + Ms(1900), lin_p,
                                  ProximityUnavailability::kPersonUnknown)));
            const std::vector<RuleOutput> tick =
                engine.Tick(kT0 + Ms(5000));
            for (std::size_t i = 0; i < tick.size(); ++i) {
                log.push_back(DescribeOutput(tick[i]));
            }
            const std::vector<RuleOutput> end =
                engine.EndSession(kT0 + Ms(6000));
            for (std::size_t i = 0; i < end.size(); ++i) {
                log.push_back(DescribeOutput(end[i]));
            }
            return log;
        };
        const std::vector<std::string> first = run_script();
        const std::vector<std::string> second = run_script();
        CHECK(!first.empty(), "determinism script emits outputs");
        int creates = 0;
        for (std::size_t i = 0; i < first.size(); ++i) {
            if (first[i].find("CREATED") != std::string::npos) {
                ++creates;
            }
        }
        CHECK(creates == 2, "determinism script covers two creates");
        CHECK(first.size() == second.size(), "deterministic output count");
        if (first.size() == second.size()) {
            for (std::size_t i = 0; i < first.size(); ++i) {
                CHECK(first[i] == second[i], "deterministic output bytes");
            }
        }
    }
    // ---- 12) applicability filters ----
    {
        RiskEngine engine(/*max_gap_ms=*/5000);
        RiskRule prox =
            ProxRule("cls", RuleMode::kShadow, 3.0, 1.0, 0, 1000);
        prox.enabled_equipment_classes.push_back("excavator");
        engine.AddRule(prox);
        RiskRule zone = ZoneRule("rev", RuleMode::kShadow, 0, 1000);
        zone.zone_revision_ids.push_back("revA");
        engine.AddRule(zone);
        const TrackLineage lin = Lin(1, 13, 13);

        FrameInput wrong_class = NearFrame(kT0, lin, 1.0, 2.0);
        wrong_class.equipment_class = "dozer";
        const std::vector<RuleOutput> o1 = engine.Evaluate(wrong_class);
        CHECK(o1[0].status == RuleEvalStatus::kNotApplicable &&
                  o1[0].detail == EvalDetail::kEquipmentClassNotListed,
              "unlisted class -> not applicable");
        CHECK(o1[0].events.empty(), "unlisted class -> no evaluation");

        FrameInput wrong_rev =
            ZoneFrame(kT0 + Ms(100), lin, ZoneRelationKind::kInside, "revB");
        const std::vector<RuleOutput> o2 = engine.Evaluate(wrong_rev);
        CHECK(o2[1].status == RuleEvalStatus::kNotApplicable &&
                  o2[1].detail == EvalDetail::kZoneRevisionNotListed,
              "unlisted revision -> not applicable");
        CHECK(o2[1].events.empty(), "unlisted revision -> no evaluation");

        FrameInput ok_class = NearFrame(kT0 + Ms(200), lin, 1.0, 2.0);
        ok_class.equipment_class = "excavator";
        const std::vector<RuleOutput> o3 = engine.Evaluate(ok_class);
        CHECK(o3[0].status == RuleEvalStatus::kEvaluated,
              "listed class evaluates");
        FrameInput ok_rev = ZoneFrame(kT0 + Ms(300), lin,
                                       ZoneRelationKind::kInside, "revA");
        const std::vector<RuleOutput> o4 = engine.Evaluate(ok_rev);
        CHECK(o4[1].status == RuleEvalStatus::kEvaluated,
              "listed revision evaluates");
    }
    if (edge_test::failures == 0) {
        std::printf("risk: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
