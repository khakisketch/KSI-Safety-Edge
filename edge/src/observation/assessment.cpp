// Unit A assessment builder: pure mapping of TickRecord (+ caller context)
// onto a typed Assessment. See the header for the lifecycle contract.

#include "ksi_safety/observation/assessment.hpp"

#include "ksi_safety/risk/episode.hpp"
#include "ksi_safety/risk/policy.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

const char* ToString(AssessmentDisposition disposition) {
    switch (disposition) {
        case AssessmentDisposition::kAssessed:
            return "ASSESSED";
        case AssessmentDisposition::kUndetermined:
            return "UNDETERMINED";
    }
    return "UNKNOWN";
}

namespace {

bool HasReason(const std::vector<std::string>& reasons, const std::string& r) {
    for (const auto& have : reasons) {
        if (have == r) return true;
    }
    return false;
}

void AddReason(std::vector<std::string>& reasons, const std::string& r) {
    if (!HasReason(reasons, r)) reasons.push_back(r);
}

void AddCondition(std::vector<std::string>& conditions, const std::string& c) {
    for (const auto& have : conditions) {
        if (have == c) return;
    }
    conditions.push_back(c);
}

AssessmentRuleEntry EntryFor(const RuleOutput& output) {
    AssessmentRuleEntry entry;
    entry.rule_id = output.rule_id;
    entry.status = ToString(output.status);
    entry.detail = ToString(output.detail);
    entry.effective_mode = ToString(output.effective_mode);
    entry.is_shadow = output.is_shadow;
    entry.event_count = output.events.size();
    for (const auto& event : output.events) {
        if (event.kind == EpisodeEventKind::kCreated) ++entry.created_episodes;
        if (event.kind == EpisodeEventKind::kClosed) ++entry.closed_episodes;
    }
    return entry;
}

}  // namespace

Assessment BuildAssessment(const TickRecord& record,
                           const AssessmentContext& context) {
    Assessment out;
    out.assessment_id = Uuid5(MopProtocol::Namespace(),
                              "assess|" + context.flight_id + "|" +
                                  context.edge_boot_id + "|" +
                                  std::to_string(record.tick_index));
    out.tick_index = record.tick_index;
    out.frame_id = record.frame_id;
    out.receive_mono_ns = record.receive_mono_ns;
    out.time_grade =
        record.exposure_verified ? "exposure_verified" : "receive_only";
    out.flight_id = context.flight_id;
    out.edge_boot_id = context.edge_boot_id;
    out.stream_id = context.stream_id.empty() ? record.stream_id
                                              : context.stream_id;
    out.lens_id = context.lens_id;
    out.model_id = record.model_id;
    out.model_digest = record.model_digest;
    out.mission_revision_id = context.mission_revision_id;
    out.policy_revision_id = context.policy_revision_id;
    out.effective_config_hash = context.effective_config_hash;
    out.config_epoch = context.config_epoch;
    out.simulated_input = context.simulated_input;
    out.mode = "SHADOW";
    out.detection_status = ToString(record.detection_status);
    out.detection_count = record.detections.size();
    out.track_count = record.tracks.size();
    out.incidents_created = 0;
    out.alerts_created = 0;
    out.human_responses = 0;
    out.camera_commands = 0;
    out.flight_commands = 0;
    // Analysis is never stopped by a manual camera: only spatial conclusions
    // may be withheld (set below); the pipeline itself continues.
    out.analysis_continues = true;
    out.camera_manual_override = context.camera_manual_override;

    for (const auto& rule_out : record.proximity) {
        out.rules.push_back(EntryFor(rule_out));
    }
    for (const auto& rule_out : record.zone) {
        out.rules.push_back(EntryFor(rule_out));
    }

    // --- disposition -------------------------------------------------------
    // A fresh detection never equals risk: detections are recorded, rules
    // decide evaluability, and withheld geometry stays withheld.
    if (record.detection_status == DetectionStatus::kNotRun) {
        out.disposition = AssessmentDisposition::kUndetermined;
        AddReason(out.missing_reasons, kMissingDetectionNotRun);
    } else if (record.detection_status == DetectionStatus::kInferenceFailure) {
        out.disposition = AssessmentDisposition::kUndetermined;
        AddReason(out.missing_reasons, kMissingDetectionFailure);
    } else if (record.detections.empty()) {
        // A clean negative (kOk, zero detections) IS evidence: there is
        // nothing to evaluate, and that is an assessed outcome — not a gap.
        // No spatial claim is possible or needed without tracks.
        out.disposition = AssessmentDisposition::kAssessed;
        AddCondition(out.observed_conditions, kObservedCleanNegative);
        AddReason(out.missing_reasons, kMissingNoSpatialClaim);
    } else if (out.rules.empty()) {
        // Detections exist but no rule was configured to judge them: an
        // observation without an applicable rule is not a risk assessment.
        out.disposition = AssessmentDisposition::kUndetermined;
        AddCondition(out.observed_conditions, kObservedDetections);
        AddReason(out.missing_reasons, kMissingNoApplicableRules);
        // Identity/authority switches are still recorded even with no rules:
        // the next spatial conclusion must not silently span the switch.
        if (context.lens_switch_observed) {
            AddReason(out.missing_reasons, kMissingLensSwitch);
        }
        if (context.camera_manual_override) {
            AddReason(out.missing_reasons, kMissingCameraManualSpatial);
            AddCondition(out.observed_conditions, kObservedAnalysisContinues);
        }
    } else {
        AddCondition(out.observed_conditions, kObservedDetections);
        bool any_evaluated = false;
        bool any_withheld = false;
        for (const auto& entry : out.rules) {
            if (entry.status == ToString(RuleEvalStatus::kEvaluated)) {
                any_evaluated = true;
            } else if (entry.status == ToString(RuleEvalStatus::kWithheld)) {
                any_withheld = true;
            }
        }
        // Manual camera / lens switch withholds spatial conclusions only.
        if (context.camera_manual_override || context.lens_switch_observed) {
            any_withheld = true;
            AddReason(out.missing_reasons, context.camera_manual_override
                                               ? kMissingCameraManualSpatial
                                               : kMissingLensSwitch);
            AddCondition(out.observed_conditions, kObservedAnalysisContinues);
        }
        if (any_withheld && !any_evaluated) {
            out.disposition = AssessmentDisposition::kUndetermined;
            AddReason(out.missing_reasons, kMissingGeometryInsufficient);
        } else if (any_withheld) {
            // Mixed: some rules evaluated, some withheld. The assessed part
            // stands; the withheld part is named. Overall stays UNDETERMINED
            // so a partial view is never read as a complete one.
            out.disposition = AssessmentDisposition::kUndetermined;
            AddReason(out.missing_reasons, kMissingGeometryInsufficient);
        } else if (any_evaluated) {
            out.disposition = AssessmentDisposition::kAssessed;
        } else {
            // All rules skipped-off / not-applicable / gap-sweep: nothing
            // applicable judged this tick.
            out.disposition = AssessmentDisposition::kUndetermined;
            AddReason(out.missing_reasons, kMissingNoApplicableRules);
        }
        if (any_evaluated) {
            AddCondition(out.observed_conditions, kObservedRuleEvaluated);
        }
    }

    if (out.rules.empty() && !record.detections.empty()) {
        AddCondition(out.observed_conditions, kObservedNoRulesConfigured);
    }
    // Held tracks (no fresh evidence this tick) are carried state, never new
    // measurements: named so a reader cannot mistake them for re-detection.
    bool any_held = false;
    for (const auto& track : record.tracks) {
        if (track.provenance == TrackProvenance::kHeld) {
            any_held = true;
            break;
        }
    }
    if (any_held) AddCondition(out.observed_conditions, kObservedTracksHeld);

    return out;
}

std::string Assessment::ToJson() const {
    JsonValue root = JsonValue::Obj();
    root.Set("schema", JsonValue::Str(schema));
    root.Set("assessment_id", JsonValue::Str(assessment_id));
    root.Set("tick_index", JsonValue::Int(static_cast<std::int64_t>(tick_index)));
    root.Set("frame_id", JsonValue::Int(static_cast<std::int64_t>(frame_id)));
    root.Set("receive_mono_ns", JsonValue::Int(receive_mono_ns));
    root.Set("time_grade", JsonValue::Str(time_grade));
    root.Set("flight_id", JsonValue::Str(flight_id));
    root.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    root.Set("stream_id", JsonValue::Str(stream_id));
    root.Set("lens_id", JsonValue::Str(lens_id));
    JsonValue model = JsonValue::Obj();
    model.Set("model_id", JsonValue::Str(model_id));
    model.Set("model_digest", JsonValue::Str(model_digest));
    root.Set("model", std::move(model));
    JsonValue config = JsonValue::Obj();
    config.Set("mission_revision_id", JsonValue::Str(mission_revision_id));
    config.Set("policy_revision_id", JsonValue::Str(policy_revision_id));
    config.Set("effective_config_hash", JsonValue::Str(effective_config_hash));
    config.Set("config_epoch", JsonValue::Int(static_cast<std::int64_t>(config_epoch)));
    root.Set("config", std::move(config));
    root.Set("mode", JsonValue::Str(mode));
    root.Set("simulated_input", JsonValue::Bool(simulated_input));
    JsonValue observations = JsonValue::Obj();
    observations.Set("detection_status", JsonValue::Str(detection_status));
    observations.Set("detection_count",
                     JsonValue::Int(static_cast<std::int64_t>(detection_count)));
    observations.Set("track_count",
                     JsonValue::Int(static_cast<std::int64_t>(track_count)));
    root.Set("observations", std::move(observations));
    JsonValue rules_json = JsonValue::Arr();
    for (const auto& entry : rules) {
        JsonValue rule = JsonValue::Obj();
        rule.Set("rule_id", JsonValue::Str(entry.rule_id));
        rule.Set("status", JsonValue::Str(entry.status));
        rule.Set("detail", JsonValue::Str(entry.detail));
        rule.Set("effective_mode", JsonValue::Str(entry.effective_mode));
        rule.Set("is_shadow", JsonValue::Bool(entry.is_shadow));
        rule.Set("event_count",
                 JsonValue::Int(static_cast<std::int64_t>(entry.event_count)));
        rule.Set("created_episodes",
                 JsonValue::Int(static_cast<std::int64_t>(entry.created_episodes)));
        rule.Set("closed_episodes",
                 JsonValue::Int(static_cast<std::int64_t>(entry.closed_episodes)));
        rules_json.Push(std::move(rule));
    }
    root.Set("rules", std::move(rules_json));
    root.Set("disposition", JsonValue::Str(ToString(disposition)));
    JsonValue observed = JsonValue::Arr();
    for (const auto& condition : observed_conditions) {
        observed.Push(JsonValue::Str(condition));
    }
    root.Set("observed_conditions", std::move(observed));
    JsonValue missing = JsonValue::Arr();
    for (const auto& reason : missing_reasons) {
        missing.Push(JsonValue::Str(reason));
    }
    root.Set("missing_reasons", std::move(missing));
    JsonValue analysis = JsonValue::Obj();
    analysis.Set("continues", JsonValue::Bool(analysis_continues));
    analysis.Set("camera_manual_override",
                 JsonValue::Bool(camera_manual_override));
    root.Set("analysis", std::move(analysis));
    // Fixed zeros: Unit A creates none of these. They are fields (not
    // absence) so a reader never has to guess whether "no incident" was
    // checked.
    JsonValue outputs = JsonValue::Obj();
    outputs.Set("incidents_created", JsonValue::Int(incidents_created));
    outputs.Set("alerts_created", JsonValue::Int(alerts_created));
    outputs.Set("human_responses", JsonValue::Int(human_responses));
    outputs.Set("camera_commands", JsonValue::Int(camera_commands));
    outputs.Set("flight_commands", JsonValue::Int(flight_commands));
    root.Set("outputs", std::move(outputs));
    // Custody: machine assessments are sealed locally. RC receipt and Core
    // retention are separate facts recorded elsewhere — never claimed here.
    JsonValue custody = JsonValue::Obj();
    // Sealed by the Edge code locally (host harness or device alike);
    // RC receipt and Core retention are separate facts, never claimed here.
    custody.Set("edge_sealed_locally", JsonValue::Bool(true));
    custody.Set("rc_received", JsonValue::Bool(false));
    custody.Set("rc_receipt", JsonValue::Str("unverified"));
    custody.Set("core_retained", JsonValue::Bool(false));
    root.Set("custody", std::move(custody));
    return WriteCanonicalJson(root);
}

}  // namespace ksi::safety
