// session: SafetySession composition of tracker + spatial relations +
// two risk engines (proximity approach/exit, zone verdicts + revision
// filter, observation loss, epoch split, geometry gate, determinism).

#include "ksi_safety/session/session.hpp"

#include <cstdint>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

#include "support/check.hpp"

using ksi::safety::Detection;
using ksi::safety::EpisodeCloseReason;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::EvalDetail;
using ksi::safety::GeometryLevel;
using ksi::safety::GroundObservation;
using ksi::safety::GroundTrackKind;
using ksi::safety::Polygon2;
using ksi::safety::RiskRule;
using ksi::safety::RuleEvalStatus;
using ksi::safety::RuleKind;
using ksi::safety::RuleMode;
using ksi::safety::RuleOutput;
using ksi::safety::SafetySession;
using ksi::safety::SessionConfig;
using ksi::safety::SessionOutput;
using ksi::safety::TrackerConfig;
using ksi::safety::Vec2;
using ksi::safety::ZoneRevision;

namespace {

constexpr std::uint64_t kMs = 1000000ULL;
constexpr std::uint64_t kT0 = 1000000ULL * 1000ULL;  // Arbitrary origin.
constexpr std::uint32_t kPersonClass = 0;
constexpr std::uint32_t kEquipmentClass = 1;

std::uint64_t Ms(std::uint64_t ms) {
    return ms * kMs;
}

SessionConfig MakeConfig(std::uint64_t max_gap_ms, double envelope_m) {
    SessionConfig config;
    config.tracker.iou_threshold = 0.0F;  // Same-class pairs always match:
    config.tracker.max_age_frames = 5;    // box motion never perturbs ids.
    config.tracker.min_hits = 1;
    config.max_gap_ms = max_gap_ms;
    config.zone_envelope_m = envelope_m;
    return config;
}

Detection MakeDet(float x0, float y0, float x1, float y1,
                  std::uint32_t class_id) {
    Detection det;
    det.x_min = x0;
    det.y_min = y0;
    det.x_max = x1;
    det.y_max = y1;
    det.score = 0.9F;
    det.class_id = class_id;
    return det;
}

// Static boxes every tick: identical boxes keep stable track ids (person 1,
// equipment 2 on a fresh session per tracker rule A3); ground motion lives
// only in the observations.
std::vector<Detection> PairDetections() {
    std::vector<Detection> dets;
    dets.push_back(MakeDet(0.0F, 0.0F, 10.0F, 10.0F, kPersonClass));
    dets.push_back(MakeDet(50.0F, 50.0F, 60.0F, 60.0F, kEquipmentClass));
    return dets;
}

std::vector<Detection> SoloDetections() {
    std::vector<Detection> dets;
    dets.push_back(MakeDet(0.0F, 0.0F, 10.0F, 10.0F, kPersonClass));
    return dets;
}

GroundObservation MakePersonObs(std::uint64_t track_id, GeometryLevel level,
                                double x, double y) {
    GroundObservation obs;
    obs.track_id = track_id;
    obs.kind = GroundTrackKind::kPerson;
    obs.geometry_level = level;
    obs.person_footpoint.x = x;
    obs.person_footpoint.y = y;
    return obs;
}

Polygon2 Square(double x0, double y0, double x1, double y1) {
    Polygon2 poly;
    Vec2 a;
    a.x = x0;
    a.y = y0;
    Vec2 b;
    b.x = x1;
    b.y = y0;
    Vec2 c;
    c.x = x1;
    c.y = y1;
    Vec2 d;
    d.x = x0;
    d.y = y1;
    poly.push_back(a);
    poly.push_back(b);
    poly.push_back(c);
    poly.push_back(d);
    return poly;
}

GroundObservation MakeEquipObs(std::uint64_t track_id, GeometryLevel level,
                               const std::string& cls,
                               const Polygon2& footprint) {
    GroundObservation obs;
    obs.track_id = track_id;
    obs.kind = GroundTrackKind::kEquipment;
    obs.geometry_level = level;
    obs.equipment_footprint = footprint;
    obs.equipment_class = cls;
    return obs;
}

RiskRule MakeProxRule(const std::string& id, RuleMode mode, double threshold_m,
                      double hysteresis_m, std::uint64_t enter_ms,
                      std::uint64_t exit_ms, GeometryLevel required) {
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

RiskRule MakeZoneRule(const std::string& id, RuleMode mode,
                      std::uint64_t enter_ms, std::uint64_t exit_ms,
                      GeometryLevel required) {
    RiskRule rule;
    rule.rule_id = id;
    rule.kind = RuleKind::kZoneIntrusion;
    rule.mode = mode;
    rule.enter_duration_ms = enter_ms;
    rule.exit_duration_ms = exit_ms;
    rule.required_geometry_level = required;
    return rule;
}

ZoneRevision MakeZone(const std::string& revision_id, const Polygon2& poly) {
    ZoneRevision zone;
    zone.revision_id = revision_id;
    zone.polygons.push_back(poly);
    return zone;
}

void CollectRuleEvents(const std::vector<RuleOutput>& outputs,
                       const std::string& rule_id,
                       std::vector<EpisodeEvent>* sink) {
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        if (outputs[i].rule_id != rule_id) {
            continue;
        }
        for (std::size_t k = 0; k < outputs[i].events.size(); ++k) {
            sink->push_back(outputs[i].events[k]);
        }
    }
}

int CountKind(const std::vector<EpisodeEvent>& events,
              EpisodeEventKind kind) {
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

void LogSessionOutput(const SessionOutput& out,
                      std::vector<std::string>* log) {
    std::ostringstream tracks;
    tracks << "T:";
    for (std::size_t i = 0; i < out.tracks.size(); ++i) {
        tracks << out.tracks[i].track_id << "," << out.tracks[i].epoch << ";";
    }
    log->push_back(tracks.str());
    for (std::size_t i = 0; i < out.proximity.size(); ++i) {
        log->push_back("P:" + DescribeOutput(out.proximity[i]));
    }
    for (std::size_t i = 0; i < out.zone.size(); ++i) {
        log->push_back("Z:" + DescribeOutput(out.zone[i]));
    }
}

}  // namespace

int main() {
    // ---- 1) proximity approach: one create, then EXIT_CONFIRMED ----
    {
        std::vector<RiskRule> prox;
        prox.push_back(MakeProxRule("prox", RuleMode::kShadow, 3.0, 0.0, 0, 0,
                                    GeometryLevel::kPixelOnly));
        SafetySession session(MakeConfig(/*max_gap_ms=*/5000, /*env=*/1.0),
                              prox, std::vector<RiskRule>(),
                              std::vector<ZoneRevision>());
        const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);
        std::vector<EpisodeEvent> events;

        // Far: footpoint (0,0) is ~14.1 m from the footprint.
        std::vector<GroundObservation> far;
        far.push_back(MakePersonObs(1, GeometryLevel::kGeoEstimate, 0.0, 0.0));
        far.push_back(
            MakeEquipObs(2, GeometryLevel::kGeoEstimate, "excavator", equip));
        SessionOutput out0 = session.Tick(kT0, PairDetections(), far);
        CHECK(out0.tracks.size() == 2, "approach: two live tracks");
        CHECK(out0.tracks[0].track_id == 1 && out0.tracks[1].track_id == 2,
              "approach: deterministic track ids");
        CollectRuleEvents(out0.proximity, "prox", &events);
        CHECK(events.empty(), "approach: far first frame silent");

        // Near: footpoint 0.5 m from the footprint edge.
        std::vector<GroundObservation> near;
        near.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 9.5, 10.5));
        near.push_back(
            MakeEquipObs(2, GeometryLevel::kGeoEstimate, "excavator", equip));
        CollectRuleEvents(
            session.Tick(kT0 + Ms(100), PairDetections(), near).proximity,
            "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "approach: exactly one create");
        const std::uint64_t id = events.back().episode_id;

        // Sustained near: update, no duplicate create.
        CollectRuleEvents(
            session.Tick(kT0 + Ms(200), PairDetections(), near).proximity,
            "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "approach: no duplicate create");

        // Separate: far again with exit=0 closes immediately.
        CollectRuleEvents(
            session.Tick(kT0 + Ms(300), PairDetections(), far).proximity,
            "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kClosed) == 1,
              "approach: exit closes");
        CHECK(HasCloseReason(events, EpisodeCloseReason::kExitConfirmed),
              "approach: EXIT_CONFIRMED");

        CHECK(events.size() == 3, "approach: Created, Updated, Closed");
        if (events.size() == 3) {
            CHECK(events[0].kind == EpisodeEventKind::kCreated,
                  "approach: first is Created");
            CHECK(events[1].kind == EpisodeEventKind::kUpdated,
                  "approach: second is Updated");
            CHECK(events[2].kind == EpisodeEventKind::kClosed &&
                      events[2].has_close_reason &&
                      events[2].close_reason ==
                          EpisodeCloseReason::kExitConfirmed,
                  "approach: third is Closed EXIT_CONFIRMED");
            CHECK(events[0].episode_id == id &&
                      events[1].episode_id == id &&
                      events[2].episode_id == id,
                  "approach: single episode id throughout");
        }
    }
    // ---- 2) zone: inside create, boundary never confirms, leave closes,
    //         revision filter respected ----
    {
        std::vector<RiskRule> zone_rules;
        RiskRule filtered =
            MakeZoneRule("zf", RuleMode::kShadow, 0, 0,
                         GeometryLevel::kPixelOnly);
        filtered.zone_revision_ids.push_back("revA");
        zone_rules.push_back(filtered);
        RiskRule other = MakeZoneRule("zo", RuleMode::kShadow, 0, 0,
                                      GeometryLevel::kPixelOnly);
        other.zone_revision_ids.push_back("revB");
        zone_rules.push_back(other);
        std::vector<ZoneRevision> zones;
        zones.push_back(MakeZone("revA", Square(0.0, 0.0, 10.0, 10.0)));
        zones.push_back(MakeZone("revB", Square(20.0, 0.0, 30.0, 10.0)));
        SafetySession session(MakeConfig(/*max_gap_ms=*/5000, /*env=*/1.0),
                              std::vector<RiskRule>(), zone_rules, zones);
        std::vector<EpisodeEvent> zf_events;
        std::vector<EpisodeEvent> zo_events;

        // Inside revB only: zf sees Outside (revA) + NotApplicable (revB);
        // zo creates its episode on revB.
        std::vector<GroundObservation> in_b;
        in_b.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 25.0, 5.0));
        SessionOutput out_b =
            session.Tick(kT0, SoloDetections(), in_b);
        CollectRuleEvents(out_b.zone, "zf", &zf_events);
        CollectRuleEvents(out_b.zone, "zo", &zo_events);
        CHECK(zf_events.empty(), "filter: listed-outside revision silent");
        CHECK(CountKind(zo_events, EpisodeEventKind::kCreated) == 1,
              "filter: covering rule creates");
        const std::uint64_t zo_id = zo_events.back().episode_id;
        bool saw_not_applicable = false;
        bool saw_bad_detail = false;
        for (std::size_t i = 0; i < out_b.zone.size(); ++i) {
            if (out_b.zone[i].rule_id == "zf" &&
                out_b.zone[i].status == RuleEvalStatus::kNotApplicable) {
                saw_not_applicable = true;
                if (out_b.zone[i].detail !=
                    EvalDetail::kZoneRevisionNotListed) {
                    saw_bad_detail = true;
                }
            }
        }
        CHECK(saw_not_applicable, "filter: non-listed revision not applied");
        CHECK(!saw_bad_detail, "filter: reason is ZoneRevisionNotListed");

        // Envelope boundary of revA (0.5 m from the edge): never confirms.
        std::vector<GroundObservation> on_edge;
        on_edge.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 10.5, 5.0));
        SessionOutput out_edge =
            session.Tick(kT0 + Ms(100), SoloDetections(), on_edge);
        const std::size_t zf_before = zf_events.size();
        const std::size_t zo_before = zo_events.size();
        CollectRuleEvents(out_edge.zone, "zf", &zf_events);
        CollectRuleEvents(out_edge.zone, "zo", &zo_events);
        CHECK(CountKind(zf_events, EpisodeEventKind::kCreated) == 0 &&
                  CountKind(zo_events, EpisodeEventKind::kCreated) ==
                      static_cast<int>(zo_before > 0 ? 1 : 0),
              "boundary: no new create");
        CHECK(zf_events.size() == zf_before,
              "boundary: filtered rule still silent");
        CHECK(zo_events.size() == zo_before + 1 &&
                  zo_events.back().kind == EpisodeEventKind::kClosed &&
                  zo_events.back().episode_id == zo_id &&
                  zo_events.back().has_close_reason &&
                  zo_events.back().close_reason ==
                      EpisodeCloseReason::kExitConfirmed,
              "boundary: open episode exits confirmed on far revision");

        // Inside revA: filtered rule creates its first episode.
        std::vector<GroundObservation> in_a;
        in_a.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 5.0, 5.0));
        SessionOutput out_a =
            session.Tick(kT0 + Ms(200), SoloDetections(), in_a);
        const int zo_created_before =
            CountKind(zo_events, EpisodeEventKind::kCreated);
        const std::size_t zo_total_before = zo_events.size();
        CollectRuleEvents(out_a.zone, "zf", &zf_events);
        CollectRuleEvents(out_a.zone, "zo", &zo_events);
        CHECK(CountKind(zf_events, EpisodeEventKind::kCreated) == 1,
              "zone: inside creates filtered episode");
        const std::uint64_t zf_id = zf_events.back().episode_id;
        CHECK(CountKind(zo_events, EpisodeEventKind::kCreated) ==
                  zo_created_before,
              "zone: other rule creates nothing new");
        CHECK(zo_events.size() == zo_total_before,
              "zone: other rule silent inside revA");

        // Leave both zones: EXIT_CONFIRMED.
        std::vector<GroundObservation> away;
        away.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 50.0, 50.0));
        CollectRuleEvents(
            session.Tick(kT0 + Ms(300), SoloDetections(), away).zone, "zf",
            &zf_events);
        CHECK(CountKind(zf_events, EpisodeEventKind::kCreated) == 1,
              "zone: still exactly one create");
        CHECK(HasCloseReason(zf_events, EpisodeCloseReason::kExitConfirmed),
              "zone: leave -> EXIT_CONFIRMED");
        CHECK(zf_events.back().episode_id == zf_id,
              "zone: close carries the episode id");
    }
    // ---- 3) observation loss: gap sweep closes OBSERVATION_LOST ----
    {
        std::vector<RiskRule> prox;
        prox.push_back(MakeProxRule("prox", RuleMode::kShadow, 3.0, 0.0, 0,
                                    5000, GeometryLevel::kPixelOnly));
        SafetySession session(MakeConfig(/*max_gap_ms=*/2000, /*env=*/1.0),
                              prox, std::vector<RiskRule>(),
                              std::vector<ZoneRevision>());
        const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);
        std::vector<EpisodeEvent> events;
        std::vector<GroundObservation> near;
        near.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 9.5, 10.5));
        near.push_back(
            MakeEquipObs(2, GeometryLevel::kGeoEstimate, "excavator", equip));
        CollectRuleEvents(session.Tick(kT0, PairDetections(), near).proximity,
                          "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "loss setup: NEAR open");
        const std::uint64_t id = events.back().episode_id;

        // Observations stop (detections continue): no frames, no events.
        const std::vector<GroundObservation> none;
        SessionOutput quiet =
            session.Tick(kT0 + Ms(100), PairDetections(), none);
        CHECK(quiet.proximity.empty(), "loss: no frames without observations");
        CHECK(quiet.tracks.size() == 2, "loss: tracks still live");
        CollectRuleEvents(quiet.proximity, "prox", &events);
        CHECK(events.size() == 1, "loss: silence while unobserved");

        // Sweep inside the gap is silent; past max_gap closes the loss.
        CollectRuleEvents(session.Sweep(kT0 + Ms(500)).proximity, "prox",
                          &events);
        CHECK(events.size() == 1, "loss: sweep inside gap silent");
        CollectRuleEvents(session.Sweep(kT0 + Ms(2200)).proximity, "prox",
                          &events);
        CHECK(events.size() == 2, "loss: sweep past gap closes once");
        CHECK(events.back().kind == EpisodeEventKind::kClosed &&
                  events.back().episode_id == id &&
                  events.back().has_close_reason &&
                  events.back().close_reason ==
                      EpisodeCloseReason::kObservationLost,
              "loss: OBSERVATION_LOST on the open episode");
        CHECK(!HasCloseReason(events, EpisodeCloseReason::kExitConfirmed),
              "loss: never labelled EXIT_CONFIRMED");
    }
    // ---- 4) epoch change: old episode lost, new episode never merges ----
    {
        std::vector<RiskRule> prox;
        prox.push_back(MakeProxRule("prox", RuleMode::kShadow, 3.0, 0.0, 0,
                                    60000, GeometryLevel::kPixelOnly));
        SafetySession session(MakeConfig(/*max_gap_ms=*/2000, /*env=*/1.0),
                              prox, std::vector<RiskRule>(),
                              std::vector<ZoneRevision>());
        const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);
        std::vector<EpisodeEvent> events;
        std::vector<GroundObservation> near_old;
        near_old.push_back(
            MakePersonObs(1, GeometryLevel::kGeoEstimate, 9.5, 10.5));
        near_old.push_back(
            MakeEquipObs(2, GeometryLevel::kGeoEstimate, "excavator", equip));
        CollectRuleEvents(session.Tick(kT0, PairDetections(), near_old)
                              .proximity,
                          "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 1,
              "epoch setup: NEAR open");
        const std::uint64_t old_id = events.back().episode_id;

        session.ResetTracker();
        CHECK(session.TrackerEpoch() == 2, "epoch: reset bumps the epoch");
        CollectRuleEvents(session.Sweep(kT0 + Ms(5000)).proximity, "prox",
                          &events);
        CHECK(events.size() == 2, "epoch: sweep closes the old episode");
        CHECK(events.back().kind == EpisodeEventKind::kClosed &&
                  events.back().episode_id == old_id &&
                  events.back().has_close_reason &&
                  events.back().close_reason ==
                      EpisodeCloseReason::kObservationLost,
              "epoch: old episode -> OBSERVATION_LOST");

        // Discover the new-epoch ids (never reused), then feed near.
        const std::vector<GroundObservation> none;
        SessionOutput rediscover =
            session.Tick(kT0 + Ms(5100), PairDetections(), none);
        CHECK(rediscover.tracks.size() == 2, "epoch: new tracks live");
        CHECK(rediscover.tracks[0].epoch == 2 &&
                  rediscover.tracks[1].epoch == 2,
              "epoch: new tracks carry the new epoch");
        CHECK(rediscover.tracks[0].track_id != 1 &&
                  rediscover.tracks[1].track_id != 1 &&
                  rediscover.tracks[0].track_id != 2 &&
                  rediscover.tracks[1].track_id != 2,
              "epoch: ids never reused");
        std::vector<GroundObservation> near_new;
        near_new.push_back(MakePersonObs(rediscover.tracks[0].track_id,
                                         GeometryLevel::kGeoEstimate, 9.5,
                                         10.5));
        near_new.push_back(MakeEquipObs(rediscover.tracks[1].track_id,
                                        GeometryLevel::kGeoEstimate,
                                        "excavator", equip));
        CollectRuleEvents(
            session.Tick(kT0 + Ms(5200), PairDetections(), near_new)
                .proximity,
            "prox", &events);
        CHECK(CountKind(events, EpisodeEventKind::kCreated) == 2,
              "epoch: new epoch creates its own episode");
        CHECK(events.back().episode_id != old_id,
              "epoch: no merge across epochs");
        const std::uint64_t new_id = events.back().episode_id;

        // The new lineage is tracked independently to its own gap close.
        CollectRuleEvents(session.Sweep(kT0 + Ms(8000)).proximity, "prox",
                          &events);
        CHECK(events.back().kind == EpisodeEventKind::kClosed &&
                  events.back().episode_id == new_id &&
                  events.back().has_close_reason &&
                  events.back().close_reason ==
                      EpisodeCloseReason::kObservationLost,
              "epoch: new lineage closes on its own gap");
    }
    // ---- 5) geometry gate: below-required observations are WITHHELD ----
    {
        std::vector<RiskRule> prox;
        prox.push_back(MakeProxRule("prox", RuleMode::kShadow, 3.0, 0.0, 0, 0,
                                    GeometryLevel::kGeoEstimate));
        SafetySession session(MakeConfig(/*max_gap_ms=*/5000, /*env=*/1.0),
                              prox, std::vector<RiskRule>(),
                              std::vector<ZoneRevision>());
        const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);
        std::vector<GroundObservation> near_low;
        near_low.push_back(
            MakePersonObs(1, GeometryLevel::kPixelOnly, 9.5, 10.5));
        near_low.push_back(MakeEquipObs(2, GeometryLevel::kPixelOnly,
                                        "excavator", equip));
        bool saw_withheld = false;
        for (int i = 0; i < 3; ++i) {
            SessionOutput out = session.Tick(
                kT0 + Ms(static_cast<std::uint64_t>(i) * 500),
                PairDetections(), near_low);
            CHECK(!out.proximity.empty(), "gate: rule evaluated each tick");
            for (std::size_t k = 0; k < out.proximity.size(); ++k) {
                CHECK(out.proximity[k].status == RuleEvalStatus::kWithheld,
                      "gate: below-required -> WITHHELD");
                CHECK(out.proximity[k].events.empty(),
                      "gate: withheld emits nothing");
                if (out.proximity[k].detail ==
                    EvalDetail::kGeometryBelowRequired) {
                    saw_withheld = true;
                }
            }
            CHECK(out.zone.empty(), "gate: no zone rules, no zone outputs");
        }
        CHECK(saw_withheld, "gate: reason is GeometryBelowRequired");
        // Gap sweep stays silent: no episode ever opened.
        CHECK(session.Sweep(kT0 + Ms(60000)).proximity.empty(),
              "gate: nothing to lose after withheld frames");
    }
    // ---- 6) determinism: same script twice -> identical log ----
    {
        auto run_script = []() {
            std::vector<RiskRule> prox;
            prox.push_back(MakeProxRule("prox", RuleMode::kShadow, 3.0, 0.0,
                                        0, 0, GeometryLevel::kPixelOnly));
            std::vector<RiskRule> zone_rules;
            RiskRule zr = MakeZoneRule("zone", RuleMode::kShadow, 0, 0,
                                       GeometryLevel::kPixelOnly);
            zr.zone_revision_ids.push_back("revA");
            zone_rules.push_back(zr);
            std::vector<ZoneRevision> zones;
            zones.push_back(MakeZone("revA", Square(0.0, 0.0, 10.0, 10.0)));
            SafetySession session(
                MakeConfig(/*max_gap_ms=*/5000, /*env=*/1.0), prox,
                zone_rules, zones);
            const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);
            std::vector<std::string> log;
            auto step = [&](std::uint64_t t, double px, double py) {
                std::vector<GroundObservation> obs;
                obs.push_back(MakePersonObs(1, GeometryLevel::kGeoEstimate,
                                            px, py));
                obs.push_back(MakeEquipObs(2, GeometryLevel::kGeoEstimate,
                                           "excavator", equip));
                LogSessionOutput(
                    session.Tick(t, PairDetections(), obs), &log);
            };
            step(kT0, 5.0, 5.0);          // Zone inside (create), prox far.
            step(kT0 + Ms(100), 9.5, 10.5);  // Prox near (create), boundary.
            step(kT0 + Ms(200), 0.0, 0.0);   // Boundary corner, prox far.
            step(kT0 + Ms(300), 5.0, 5.0);   // Zone inside again, prox far.
            SessionOutput sweep = session.Sweep(kT0 + Ms(6000));
            LogSessionOutput(sweep, &log);
            SessionOutput end = session.EndSession(kT0 + Ms(7000));
            LogSessionOutput(end, &log);
            return log;
        };
        const std::vector<std::string> first = run_script();
        const std::vector<std::string> second = run_script();
        CHECK(!first.empty(), "determinism: script emits outputs");
        int creates = 0;
        int closes = 0;
        for (std::size_t i = 0; i < first.size(); ++i) {
            if (first[i].find("CREATED") != std::string::npos) {
                ++creates;
            }
            if (first[i].find("CLOSED") != std::string::npos) {
                ++closes;
            }
        }
        CHECK(creates >= 2, "determinism: script covers creates");
        CHECK(closes >= 1, "determinism: script covers closes");
        CHECK(first.size() == second.size(), "determinism: same log length");
        if (first.size() == second.size()) {
            for (std::size_t i = 0; i < first.size(); ++i) {
                CHECK(first[i] == second[i], "determinism: identical bytes");
            }
        }
    }
    if (edge_test::failures == 0) {
        std::printf("session: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
