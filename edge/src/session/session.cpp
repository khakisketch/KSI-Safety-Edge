// See session.hpp for the composition contract.
#include "ksi_safety/session/session.hpp"

#include <cstddef>
#include <map>

namespace ksi::safety {
namespace {

GeometryLevel MinLevel(GeometryLevel a, GeometryLevel b) {
    return static_cast<int>(a) <= static_cast<int>(b) ? a : b;
}

bool ListsRevision(const RiskRule& rule, const std::string& revision_id) {
    for (std::size_t i = 0; i < rule.zone_revision_ids.size(); ++i) {
        if (rule.zone_revision_ids[i] == revision_id) {
            return true;
        }
    }
    return false;
}

struct ObservedTrack {
    const Track* track = nullptr;
    const GroundObservation* observation = nullptr;
};

void AppendOutputs(std::vector<RuleOutput>* sink,
                   const std::vector<RuleOutput>& fresh) {
    for (std::size_t i = 0; i < fresh.size(); ++i) {
        sink->push_back(fresh[i]);
    }
}

}  // namespace

SafetySession::SafetySession(const SessionConfig& config,
                             const std::vector<RiskRule>& proximity_rules,
                             const std::vector<RiskRule>& zone_rules,
                             const std::vector<ZoneRevision>& zones)
    : config_(config),
      tracker_(config.tracker),
      proximity_engine_(config.max_gap_ms),
      zone_engine_(config.max_gap_ms),
      zone_rules_(zone_rules),
      zones_(zones) {
    for (std::size_t i = 0; i < proximity_rules.size(); ++i) {
        proximity_engine_.AddRule(proximity_rules[i]);
    }
    for (std::size_t i = 0; i < zone_rules.size(); ++i) {
        zone_engine_.AddRule(zone_rules[i]);
    }
}

std::vector<Track> SafetySession::UpdateTracks(
    std::uint64_t received_mono_ns,
    const std::vector<Detection>& detections) {
    return tracker_.Update(received_mono_ns, detections);
}

SessionOutput SafetySession::Evaluate(
    std::uint64_t received_mono_ns, const std::vector<Track>& tracks,
    const std::vector<GroundObservation>& ground_observations) {
    SessionOutput out;
    out.tracks = tracks;

    // Index observations by track_id (std::map: deterministic order;
    // duplicates: last wins).
    std::map<std::uint64_t, const GroundObservation*> observed;
    for (std::size_t i = 0; i < ground_observations.size(); ++i) {
        observed[ground_observations[i].track_id] = &ground_observations[i];
    }

    // Split live tracks by observation kind. Tracks without an observation
    // this tick take part in no frame.
    std::vector<ObservedTrack> persons;
    std::vector<ObservedTrack> equipments;
    std::vector<ObservedTrack> zoned;
    for (std::size_t i = 0; i < tracks.size(); ++i) {
        const std::map<std::uint64_t, const GroundObservation*>::const_iterator
            it = observed.find(tracks[i].track_id);
        if (it == observed.end()) {
            continue;
        }
        ObservedTrack entry;
        entry.track = &tracks[i];
        entry.observation = it->second;
        zoned.push_back(entry);
        if (it->second->kind == GroundTrackKind::kPerson) {
            persons.push_back(entry);
        } else {
            equipments.push_back(entry);
        }
    }

    const std::uint64_t epoch = tracker_.Epoch();

    // Proximity: every (person, equipment) pair observed this tick.
    // Both loops run in ascending track_id (tracker rule A6 output order).
    for (std::size_t p = 0; p < persons.size(); ++p) {
        for (std::size_t e = 0; e < equipments.size(); ++e) {
            PersonGround person;
            person.status = GroundStatus::kObserved;
            person.footpoint = persons[p].observation->person_footpoint;
            EquipmentGround equipment;
            equipment.status = GroundStatus::kObserved;
            equipment.footprint =
                equipments[e].observation->equipment_footprint;
            const PairRelation relation =
                MinDistancePersonEquipment(person, equipment);
            if (!relation.has_distance) {
                continue;
            }
            FrameInput frame;
            frame.received_mono_ns = received_mono_ns;
            frame.lineage.tracker_epoch = epoch;
            frame.lineage.person_track_id = persons[p].track->track_id;
            frame.lineage.equipment_track_id = equipments[e].track->track_id;
            frame.geometry_level = MinLevel(
                persons[p].observation->geometry_level,
                equipments[e].observation->geometry_level);
            frame.equipment_class =
                equipments[e].observation->equipment_class;
            frame.activation_granted = false;  // SHADOW-only session.
            frame.proximity.available = true;
            // Point distance on both interval ends; interval expansion from
            // uncertainty is future work (see header).
            frame.proximity.lower_m = relation.distance_m;
            frame.proximity.upper_m = relation.distance_m;
            AppendOutputs(&out.proximity,
                          proximity_engine_.Evaluate(frame));
        }
    }

    // Zone candidates: union of the zone rules' revision lists; an empty
    // list on ANY zone rule means every provided zone is a candidate (the
    // engine still applies each rule's own filter per frame).
    std::vector<const ZoneRevision*> candidates;
    bool any_rule_covers_all = false;
    for (std::size_t i = 0; i < zone_rules_.size(); ++i) {
        if (zone_rules_[i].zone_revision_ids.empty()) {
            any_rule_covers_all = true;
            break;
        }
    }
    for (std::size_t z = 0; z < zones_.size(); ++z) {
        if (any_rule_covers_all) {
            candidates.push_back(&zones_[z]);
            continue;
        }
        for (std::size_t r = 0; r < zone_rules_.size(); ++r) {
            if (ListsRevision(zone_rules_[r], zones_[z].revision_id)) {
                candidates.push_back(&zones_[z]);
                break;
            }
        }
    }

    // Zone: every observed live track x every candidate revision, in
    // ascending track_id x configured zone order.
    for (std::size_t t = 0; t < zoned.size(); ++t) {
        for (std::size_t z = 0; z < candidates.size(); ++z) {
            FrameInput frame;
            frame.received_mono_ns = received_mono_ns;
            frame.lineage.tracker_epoch = epoch;
            frame.lineage.person_track_id = zoned[t].track->track_id;
            frame.lineage.equipment_track_id = 0;
            frame.geometry_level = zoned[t].observation->geometry_level;
            frame.activation_granted = false;  // SHADOW-only session.
            if (zoned[t].observation->kind == GroundTrackKind::kPerson) {
                PersonGround person;
                person.status = GroundStatus::kObserved;
                person.footpoint = zoned[t].observation->person_footpoint;
                const ZoneRelationResult result = RelatePointToZone(
                    person, *candidates[z], config_.zone_envelope_m);
                frame.zone.relation = result.relation;
                frame.zone.revision_id = result.revision_id;
            } else {
                EquipmentGround equipment;
                equipment.status = GroundStatus::kObserved;
                equipment.footprint =
                    zoned[t].observation->equipment_footprint;
                const ZoneRelationResult result = RelatePolygonToZone(
                    equipment, *candidates[z], config_.zone_envelope_m);
                frame.zone.relation = result.relation;
                frame.zone.revision_id = result.revision_id;
            }
            AppendOutputs(&out.zone, zone_engine_.Evaluate(frame));
        }
    }

    return out;
}

SessionOutput SafetySession::Tick(
    std::uint64_t received_mono_ns,
    const std::vector<Detection>& detections,
    const std::vector<GroundObservation>& ground_observations) {
    const std::vector<Track> tracks =
        UpdateTracks(received_mono_ns, detections);
    return Evaluate(received_mono_ns, tracks, ground_observations);
}

SessionOutput SafetySession::Sweep(std::uint64_t now_mono_ns) {
    SessionOutput out;
    AppendOutputs(&out.proximity, proximity_engine_.Tick(now_mono_ns));
    AppendOutputs(&out.zone, zone_engine_.Tick(now_mono_ns));
    return out;
}

SessionOutput SafetySession::EndSession(std::uint64_t now_mono_ns) {
    SessionOutput out;
    AppendOutputs(&out.proximity, proximity_engine_.EndSession(now_mono_ns));
    AppendOutputs(&out.zone, zone_engine_.EndSession(now_mono_ns));
    return out;
}

void SafetySession::ResetTracker() {
    tracker_.Reset();
}

std::uint64_t SafetySession::TrackerEpoch() const {
    return tracker_.Epoch();
}

std::vector<Track> SafetySession::LiveTracks() const {
    return tracker_.Tracks();
}

}  // namespace ksi::safety
