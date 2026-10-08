#include "ksi_safety/risk/engine.hpp"

namespace ksi::safety {

namespace {

constexpr std::uint64_t kNsPerMs = 1000000ULL;

// now - start >= duration_ms, compared exactly in the ns domain. Out-of-order
// timestamps (now < start) count as zero elapsed. A zero duration is
// immediate: the first qualifying frame satisfies it.
bool ElapsedGe(std::uint64_t now_ns, std::uint64_t start_ns,
               std::uint64_t duration_ms) {
    if (now_ns < start_ns) {
        return duration_ms == 0;
    }
    return (now_ns - start_ns) / kNsPerMs >= duration_ms;
}

// Strictly greater: a gap of exactly max_gap_ms is still continuous.
bool GapExceeded(std::uint64_t now_ns, std::uint64_t last_ns,
                 std::uint64_t max_gap_ms) {
    if (now_ns <= last_ns) {
        return false;
    }
    const std::uint64_t delta_ns = now_ns - last_ns;
    const std::uint64_t whole_ms = delta_ns / kNsPerMs;
    if (whole_ms != max_gap_ms) {
        return whole_ms > max_gap_ms;
    }
    return (delta_ns % kNsPerMs) > 0;
}

bool ContainsId(const std::vector<std::string>& list, const std::string& id) {
    for (std::size_t i = 0; i < list.size(); ++i) {
        if (list[i] == id) {
            return true;
        }
    }
    return false;
}

int GeometryRank(GeometryLevel level) {
    return static_cast<int>(level);
}

struct Classified {
    EvidenceClass cls = EvidenceClass::kUnknown;
    UnknownReason reason = UnknownReason::kIntervalStraddles;
};

Classified ClassifyProximity(const RiskRule& rule,
                             const ProximityEvidence& evidence) {
    Classified out;
    if (!evidence.available) {
        out.reason = (evidence.unavailable_reason ==
                      ProximityUnavailability::kPersonUnknown)
                         ? UnknownReason::kPersonUnknown
                         : UnknownReason::kEquipmentUnknown;
        return out;
    }
    if (evidence.lower_m > evidence.upper_m) {
        // Inverted interval is corrupt input: fail uncertain, never near.
        out.reason = UnknownReason::kIntervalStraddles;
        return out;
    }
    if (evidence.upper_m < rule.threshold_m) {
        out.cls = EvidenceClass::kNear;
        return out;
    }
    if (evidence.lower_m > rule.threshold_m + rule.hysteresis_m) {
        out.cls = EvidenceClass::kFar;
        return out;
    }
    out.reason = UnknownReason::kIntervalStraddles;
    return out;
}

Classified ClassifyZone(const ZoneEvidence& evidence) {
    Classified out;
    switch (evidence.relation) {
        case ZoneRelationKind::kInside:
            out.cls = EvidenceClass::kNear;
            return out;
        case ZoneRelationKind::kOutside:
            out.cls = EvidenceClass::kFar;
            return out;
        case ZoneRelationKind::kBoundaryUncertain:
            out.reason = UnknownReason::kBoundaryUncertain;
            return out;
        case ZoneRelationKind::kUnknownStatus:
            out.reason = UnknownReason::kZoneStatusUnknown;
            return out;
    }
    out.reason = UnknownReason::kZoneStatusUnknown;
    return out;
}

EvidenceSummary MakeSummary(const RiskRule& rule, const FrameInput& frame) {
    EvidenceSummary summary;
    summary.geometry_level = frame.geometry_level;
    if (rule.kind == RuleKind::kPersonEquipmentProximity) {
        summary.is_proximity = true;
        summary.interval_available = frame.proximity.available;
        summary.lower_m = frame.proximity.lower_m;
        summary.upper_m = frame.proximity.upper_m;
    } else {
        summary.is_proximity = false;
        summary.zone_relation = frame.zone.relation;
        summary.zone_revision_id = frame.zone.revision_id;
    }
    return summary;
}

}  // namespace

bool TrackLineage::operator==(const TrackLineage& other) const {
    return tracker_epoch == other.tracker_epoch &&
           person_track_id == other.person_track_id &&
           equipment_track_id == other.equipment_track_id;
}

bool TrackLineage::operator<(const TrackLineage& other) const {
    if (tracker_epoch != other.tracker_epoch) {
        return tracker_epoch < other.tracker_epoch;
    }
    if (person_track_id != other.person_track_id) {
        return person_track_id < other.person_track_id;
    }
    return equipment_track_id < other.equipment_track_id;
}

const char* ToString(UnknownReason reason) {
    switch (reason) {
        case UnknownReason::kPersonUnknown:
            return "PERSON_UNKNOWN";
        case UnknownReason::kEquipmentUnknown:
            return "EQUIPMENT_UNKNOWN";
        case UnknownReason::kIntervalStraddles:
            return "INTERVAL_STRADDLES";
        case UnknownReason::kBoundaryUncertain:
            return "BOUNDARY_UNCERTAIN";
        case UnknownReason::kZoneStatusUnknown:
            return "ZONE_STATUS_UNKNOWN";
        case UnknownReason::kGeometryInsufficient:
            return "GEOMETRY_INSUFFICIENT";
    }
    return "UNKNOWN";
}

const char* ToString(RuleEvalStatus status) {
    switch (status) {
        case RuleEvalStatus::kEvaluated:
            return "EVALUATED";
        case RuleEvalStatus::kSkippedOff:
            return "SKIPPED_OFF";
        case RuleEvalStatus::kNotApplicable:
            return "NOT_APPLICABLE";
        case RuleEvalStatus::kWithheld:
            return "WITHHELD";
        case RuleEvalStatus::kGapSweep:
            return "GAP_SWEEP";
        case RuleEvalStatus::kSessionEnd:
            return "SESSION_END";
    }
    return "UNKNOWN";
}

const char* ToString(EvalDetail detail) {
    switch (detail) {
        case EvalDetail::kNone:
            return "NONE";
        case EvalDetail::kRuleOff:
            return "RULE_OFF";
        case EvalDetail::kEquipmentClassNotListed:
            return "EQUIPMENT_CLASS_NOT_LISTED";
        case EvalDetail::kZoneRevisionNotListed:
            return "ZONE_REVISION_NOT_LISTED";
        case EvalDetail::kGeometryBelowRequired:
            return "GEOMETRY_BELOW_REQUIRED";
        case EvalDetail::kActivationMissing:
            return "ACTIVATION_MISSING";
        case EvalDetail::kActiveRequiresMetricVerified:
            return "ACTIVE_REQUIRES_METRIC_VERIFIED";
    }
    return "UNKNOWN";
}

RiskEngine::RiskEngine(std::uint64_t max_gap_ms) : max_gap_ms_(max_gap_ms) {}

void RiskEngine::AddRule(const RiskRule& rule) {
    RiskRule stored = rule;
    if (stored.hysteresis_m < 0.0) {
        stored.hysteresis_m = 0.0;
    }
    for (std::size_t i = 0; i < rules_.size(); ++i) {
        if (rules_[i].rule.rule_id == stored.rule_id) {
            rules_[i].rule = stored;
            rules_[i].tracks.clear();
            return;
        }
    }
    RuleEntry entry;
    entry.rule = stored;
    rules_.push_back(entry);
}

std::size_t RiskEngine::RuleCount() const {
    return rules_.size();
}

bool RiskEngine::GetLineageState(const std::string& rule_id,
                                 const TrackLineage& lineage,
                                 EpisodeState* state) const {
    if (state == nullptr) {
        return false;
    }
    for (std::size_t i = 0; i < rules_.size(); ++i) {
        if (rules_[i].rule.rule_id != rule_id) {
            continue;
        }
        const auto it = rules_[i].tracks.find(lineage);
        if (it == rules_[i].tracks.end()) {
            return false;
        }
        *state = it->second.state;
        return true;
    }
    return false;
}

std::vector<RuleOutput> RiskEngine::Evaluate(const FrameInput& frame) {
    std::vector<RuleOutput> outputs;
    for (std::size_t i = 0; i < rules_.size(); ++i) {
        outputs.push_back(EvaluateRule(rules_[i], frame));
    }
    return outputs;
}

void RiskEngine::PushClose(LineageTrack& track, const RiskRule& rule,
                           std::uint64_t received_mono_ns,
                           const EvidenceSummary& summary,
                           RuleMode effective_mode, bool is_shadow,
                           EpisodeCloseReason reason, RuleOutput& out) {
    EpisodeEvent event;
    event.kind = EpisodeEventKind::kClosed;
    event.episode_id = track.open_episode_id;
    event.rule_id = rule.rule_id;
    event.effective_mode = effective_mode;
    event.is_shadow = is_shadow;
    event.has_close_reason = true;
    event.close_reason = reason;
    event.received_mono_ns = received_mono_ns;
    event.evidence = summary;
    out.events.push_back(event);
    track.has_open_episode = false;
    track.open_episode_id = 0;
}

RuleOutput RiskEngine::EvaluateRule(RuleEntry& entry, const FrameInput& frame) {
    const RiskRule& rule = entry.rule;
    RuleOutput out;
    out.rule_id = rule.rule_id;
    out.effective_mode = RuleMode::kShadow;
    out.is_shadow = true;

    // OFF: skipped entirely (no evaluation, no episodes, no lineage state).
    if (rule.mode == RuleMode::kOff) {
        out.status = RuleEvalStatus::kSkippedOff;
        out.detail = EvalDetail::kRuleOff;
        out.effective_mode = RuleMode::kOff;
        return out;
    }

    // ACTIVE guard: without an explicit grant the rule evaluates as SHADOW
    // and must never emit an active-mode output. ACTIVE additionally
    // requires METRIC_VERIFIED (contract ACTIVE ⇒ METRIC_VERIFIED); a rule
    // violating that contract degrades to SHADOW rather than emitting.
    RuleMode effective_mode = RuleMode::kShadow;
    bool is_shadow = true;
    EvalDetail guard_detail = EvalDetail::kNone;
    if (rule.mode == RuleMode::kActive) {
        if (!frame.activation_granted) {
            guard_detail = EvalDetail::kActivationMissing;
        } else if (rule.required_geometry_level !=
                   GeometryLevel::kMetricVerified) {
            guard_detail = EvalDetail::kActiveRequiresMetricVerified;
        } else {
            effective_mode = RuleMode::kActive;
            is_shadow = false;
        }
    }
    out.effective_mode = effective_mode;
    out.is_shadow = is_shadow;

    // Applicability: the equipment-class filter applies to every rule; the
    // zone revision filter applies to zone rules.
    if (!rule.enabled_equipment_classes.empty() &&
        !ContainsId(rule.enabled_equipment_classes, frame.equipment_class)) {
        out.status = RuleEvalStatus::kNotApplicable;
        out.detail = EvalDetail::kEquipmentClassNotListed;
        return out;
    }
    if (rule.kind == RuleKind::kZoneIntrusion &&
        !rule.zone_revision_ids.empty() &&
        !ContainsId(rule.zone_revision_ids, frame.zone.revision_id)) {
        out.status = RuleEvalStatus::kNotApplicable;
        out.detail = EvalDetail::kZoneRevisionNotListed;
        return out;
    }

    // Geometry gate: below-required frames are WITHHELD and step the machine
    // as unknown evidence only (never confirm/close).
    if (GeometryRank(frame.geometry_level) <
        GeometryRank(rule.required_geometry_level)) {
        out.status = RuleEvalStatus::kWithheld;
        out.detail = EvalDetail::kGeometryBelowRequired;
        LineageTrack& track = entry.tracks[frame.lineage];
        StepMachine(entry, track, frame, EvidenceClass::kUnknown,
                    UnknownReason::kGeometryInsufficient, effective_mode,
                    is_shadow, out);
        return out;
    }

    const Classified classified =
        (rule.kind == RuleKind::kPersonEquipmentProximity)
            ? ClassifyProximity(rule, frame.proximity)
            : ClassifyZone(frame.zone);
    out.status = RuleEvalStatus::kEvaluated;
    out.detail = guard_detail;

    LineageTrack& track = entry.tracks[frame.lineage];

    // Observation-gap pre-check (evaluated frames only; withheld frames must
    // never close). The gap broke continuity, so the open episode cannot
    // continue as-is: far evidence after a gap is an unobserved exit, while
    // near/unknown evidence means the previous episode was lost. Either way
    // the lineage restarts from FAR and the current evidence is processed
    // fresh below (a near frame re-enters candidacy; it is NOT a
    // continuation, so no Updated and no id reuse).
    if (track.has_open_episode && track.has_valid_evidence &&
        GapExceeded(frame.received_mono_ns, track.last_valid_evidence_ns,
                    max_gap_ms_)) {
        const EvidenceSummary summary = MakeSummary(rule, frame);
        if (classified.cls == EvidenceClass::kFar) {
            PushClose(track, rule, frame.received_mono_ns, summary,
                      effective_mode, is_shadow,
                      EpisodeCloseReason::kExitNotObserved, out);
        } else {
            PushClose(track, rule, frame.received_mono_ns, summary,
                      effective_mode, is_shadow,
                      EpisodeCloseReason::kObservationLost, out);
        }
        track.state = EpisodeState::kFar;
    } else if (!track.has_open_episode &&
               track.state == EpisodeState::kNearCandidate &&
               track.has_valid_evidence &&
               GapExceeded(frame.received_mono_ns,
                           track.last_valid_evidence_ns, max_gap_ms_)) {
        // Candidacy requires persistence: a gapped candidacy restarts its
        // enter timer instead of confirming on stale evidence.
        track.enter_timer_start_ns = frame.received_mono_ns;
    }

    StepMachine(entry, track, frame, classified.cls, classified.reason,
                effective_mode, is_shadow, out);
    return out;
}

void RiskEngine::StepMachine(RuleEntry& entry, LineageTrack& track,
                             const FrameInput& frame, EvidenceClass cls,
                             UnknownReason /*reason*/,
                             RuleMode effective_mode, bool is_shadow,
                             RuleOutput& out) {
    const RiskRule& rule = entry.rule;
    const std::uint64_t now_ns = frame.received_mono_ns;
    track.last_summary = MakeSummary(rule, frame);
    track.last_effective_mode = effective_mode;
    track.last_is_shadow = is_shadow;
    if (cls != EvidenceClass::kUnknown) {
        track.last_valid_evidence_ns = now_ns;
        track.has_valid_evidence = true;
    }

    switch (track.state) {
        case EpisodeState::kNoRelation: {
            if (cls == EvidenceClass::kUnknown) {
                break;
            }
            // First valid evidence establishes FAR; a near frame on that
            // same frame may advance immediately below (doc-faithful).
            track.state = EpisodeState::kFar;
            [[fallthrough]];
        }
        case EpisodeState::kFar: {
            if (cls == EvidenceClass::kNear) {
                track.state = EpisodeState::kNearCandidate;
                track.enter_timer_start_ns = now_ns;
                if (ElapsedGe(now_ns, track.enter_timer_start_ns,
                              rule.enter_duration_ms)) {
                    track.state = EpisodeState::kNear;
                    track.has_open_episode = true;
                    track.open_episode_id = next_episode_id_++;
                    EpisodeEvent event;
                    event.kind = EpisodeEventKind::kCreated;
                    event.episode_id = track.open_episode_id;
                    event.rule_id = rule.rule_id;
                    event.effective_mode = effective_mode;
                    event.is_shadow = is_shadow;
                    event.received_mono_ns = now_ns;
                    event.evidence = track.last_summary;
                    out.events.push_back(event);
                }
            } else if (cls == EvidenceClass::kUnknown) {
                track.state = EpisodeState::kUnknown;
            }
            break;
        }
        case EpisodeState::kNearCandidate: {
            if (cls == EvidenceClass::kNear) {
                if (ElapsedGe(now_ns, track.enter_timer_start_ns,
                              rule.enter_duration_ms)) {
                    track.state = EpisodeState::kNear;
                    track.has_open_episode = true;
                    track.open_episode_id = next_episode_id_++;
                    EpisodeEvent event;
                    event.kind = EpisodeEventKind::kCreated;
                    event.episode_id = track.open_episode_id;
                    event.rule_id = rule.rule_id;
                    event.effective_mode = effective_mode;
                    event.is_shadow = is_shadow;
                    event.received_mono_ns = now_ns;
                    event.evidence = track.last_summary;
                    out.events.push_back(event);
                }
            } else if (cls == EvidenceClass::kFar) {
                track.state = EpisodeState::kFar;
            } else {
                // Timer reset; no episode existed.
                track.state = EpisodeState::kUnknown;
            }
            break;
        }
        case EpisodeState::kNear: {
            if (cls == EvidenceClass::kNear) {
                EpisodeEvent event;
                event.kind = EpisodeEventKind::kUpdated;
                event.episode_id = track.open_episode_id;
                event.rule_id = rule.rule_id;
                event.effective_mode = effective_mode;
                event.is_shadow = is_shadow;
                event.received_mono_ns = now_ns;
                event.evidence = track.last_summary;
                out.events.push_back(event);
            } else if (cls == EvidenceClass::kFar) {
                track.state = EpisodeState::kExitPending;
                track.exit_timer_start_ns = now_ns;
                if (ElapsedGe(now_ns, track.exit_timer_start_ns,
                              rule.exit_duration_ms)) {
                    PushClose(track, rule, now_ns, track.last_summary,
                              effective_mode, is_shadow,
                              EpisodeCloseReason::kExitConfirmed, out);
                    track.state = EpisodeState::kFar;
                }
            } else {
                // Episode stays open; the exit/enter timers are dropped.
                track.state = EpisodeState::kUnknown;
            }
            break;
        }
        case EpisodeState::kExitPending: {
            if (cls == EvidenceClass::kFar) {
                if (ElapsedGe(now_ns, track.exit_timer_start_ns,
                              rule.exit_duration_ms)) {
                    PushClose(track, rule, now_ns, track.last_summary,
                              effective_mode, is_shadow,
                              EpisodeCloseReason::kExitConfirmed, out);
                    track.state = EpisodeState::kFar;
                }
            } else if (cls == EvidenceClass::kNear) {
                track.state = EpisodeState::kNear;
                EpisodeEvent event;
                event.kind = EpisodeEventKind::kUpdated;
                event.episode_id = track.open_episode_id;
                event.rule_id = rule.rule_id;
                event.effective_mode = effective_mode;
                event.is_shadow = is_shadow;
                event.received_mono_ns = now_ns;
                event.evidence = track.last_summary;
                out.events.push_back(event);
            } else {
                // Exit timer reset; the episode stays open.
                track.state = EpisodeState::kUnknown;
            }
            break;
        }
        case EpisodeState::kUnknown: {
            if (cls == EvidenceClass::kNear) {
                if (track.has_open_episode) {
                    // Same lineage, continuity validated (any gap was
                    // already closed by the pre-check): the episode
                    // continues with no new create.
                    track.state = EpisodeState::kNear;
                    EpisodeEvent event;
                    event.kind = EpisodeEventKind::kUpdated;
                    event.episode_id = track.open_episode_id;
                    event.rule_id = rule.rule_id;
                    event.effective_mode = effective_mode;
                    event.is_shadow = is_shadow;
                    event.received_mono_ns = now_ns;
                    event.evidence = track.last_summary;
                    out.events.push_back(event);
                } else {
                    // No episode to continue: fresh candidacy, enter timing
                    // applies in full.
                    track.state = EpisodeState::kNearCandidate;
                    track.enter_timer_start_ns = now_ns;
                    if (ElapsedGe(now_ns, track.enter_timer_start_ns,
                                  rule.enter_duration_ms)) {
                        track.state = EpisodeState::kNear;
                        track.has_open_episode = true;
                        track.open_episode_id = next_episode_id_++;
                        EpisodeEvent event;
                        event.kind = EpisodeEventKind::kCreated;
                        event.episode_id = track.open_episode_id;
                        event.rule_id = rule.rule_id;
                        event.effective_mode = effective_mode;
                        event.is_shadow = is_shadow;
                        event.received_mono_ns = now_ns;
                        event.evidence = track.last_summary;
                        out.events.push_back(event);
                    }
                }
            } else if (cls == EvidenceClass::kFar) {
                track.state = EpisodeState::kFar;
                if (track.has_open_episode) {
                    // The exit was not continuously observed: never labelled
                    // EXIT_CONFIRMED.
                    PushClose(track, rule, now_ns, track.last_summary,
                              effective_mode, is_shadow,
                              EpisodeCloseReason::kExitNotObserved, out);
                }
            }
            // Unknown evidence while UNKNOWN: stay, episode unchanged.
            break;
        }
    }
}

std::vector<RuleOutput> RiskEngine::Tick(std::uint64_t now_mono_ns) {
    std::vector<RuleOutput> outputs;
    for (std::size_t i = 0; i < rules_.size(); ++i) {
        RuleEntry& entry = rules_[i];
        if (entry.rule.mode == RuleMode::kOff) {
            continue;
        }
        RuleOutput out;
        out.rule_id = entry.rule.rule_id;
        out.status = RuleEvalStatus::kGapSweep;
        out.detail = EvalDetail::kNone;
        for (auto& kv : entry.tracks) {
            LineageTrack& track = kv.second;
            if (!track.has_open_episode || !track.has_valid_evidence) {
                continue;
            }
            if (!GapExceeded(now_mono_ns, track.last_valid_evidence_ns,
                             max_gap_ms_)) {
                continue;
            }
            PushClose(track, entry.rule, now_mono_ns, track.last_summary,
                      track.last_effective_mode, track.last_is_shadow,
                      EpisodeCloseReason::kObservationLost, out);
            track.state = EpisodeState::kFar;
        }
        if (!out.events.empty()) {
            // No evaluation happened here: mirror the first closed episode's
            // mode so the rule-level fields stay consistent with the events.
            out.effective_mode = out.events[0].effective_mode;
            out.is_shadow = out.events[0].is_shadow;
            outputs.push_back(out);
        }
    }
    return outputs;
}

std::vector<RuleOutput> RiskEngine::EndSession(std::uint64_t now_mono_ns) {
    std::vector<RuleOutput> outputs;
    for (std::size_t i = 0; i < rules_.size(); ++i) {
        RuleEntry& entry = rules_[i];
        if (entry.rule.mode == RuleMode::kOff) {
            continue;
        }
        RuleOutput out;
        out.rule_id = entry.rule.rule_id;
        out.status = RuleEvalStatus::kSessionEnd;
        out.detail = EvalDetail::kNone;
        for (auto& kv : entry.tracks) {
            LineageTrack& track = kv.second;
            if (!track.has_open_episode) {
                continue;
            }
            PushClose(track, entry.rule, now_mono_ns, track.last_summary,
                      track.last_effective_mode, track.last_is_shadow,
                      EpisodeCloseReason::kSessionEnd, out);
            track.state = EpisodeState::kFar;
        }
        if (!out.events.empty()) {
            out.effective_mode = out.events[0].effective_mode;
            out.is_shadow = out.events[0].is_shadow;
            outputs.push_back(out);
        }
    }
    return outputs;
}

}  // namespace ksi::safety
