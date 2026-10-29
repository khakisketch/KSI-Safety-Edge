// Bounded source-ROI capture on the SAME worker/shared tick as inference.
// No new model, network path, camera/flight port, risk or verification output.
#include "ksi_safety/observation/autonomous_processor.hpp"

#include <limits>
#include <algorithm>

#include "ksi_safety/observation/source_roi.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

namespace ksi::safety {
namespace {
using namespace observation;

JsonValue KeyJson(const TrackKey& key) {
    JsonValue value = JsonValue::Obj();
    // Decimal strings retain all 64 bits, including beyond JSON/JS safe ints.
    value.Set("tracker_epoch", JsonValue::Str(std::to_string(key.tracker_epoch)));
    value.Set("track_id", JsonValue::Str(std::to_string(key.track_id)));
    value.Set("class_id", JsonValue::Int(key.class_id));
    return value;
}

TrackKey KeyFor(const Track& track) {
    return {track.epoch, track.track_id, static_cast<std::int32_t>(track.class_id)};
}

JsonValue RoiJson(const SourceRoi& roi) {
    JsonValue value = JsonValue::Obj();
    value.Set("x", JsonValue::Int(roi.x));
    value.Set("y", JsonValue::Int(roi.y));
    value.Set("width", JsonValue::Int(roi.width));
    value.Set("height", JsonValue::Int(roi.height));
    value.Set("bytes", JsonValue::Int(static_cast<std::int64_t>(roi.byte_size)));
    value.Set("clipped", JsonValue::Bool(roi.clipped));
    return value;
}

JsonValue BaseReport(const AutonomousConfig& config, const ObservationPlanner& planner,
                     std::int64_t now_ns, const char* event) {
    JsonValue root = JsonValue::Obj();
    root.Set("schema", JsonValue::Str("ksi.source-attention/v1"));
    root.Set("event", JsonValue::Str(event));
    root.Set("flight_id", JsonValue::Str(config.flight_id));
    root.Set("edge_boot_id", JsonValue::Str(config.edge_boot_id));
    root.Set("stream_id", JsonValue::Str(config.stream_id));
    root.Set("mission_revision_id", JsonValue::Str(config.mission_revision_id));
    root.Set("policy_revision_id", JsonValue::Str(config.policy_revision_id));
    root.Set("effective_config_hash", JsonValue::Str(config.effective_config_hash));
    root.Set("config_epoch", JsonValue::Str(std::to_string(config.config_epoch)));
    root.Set("lens_id", JsonValue::Str(config.lens_id));
    root.Set("lens_identity_grade", JsonValue::Str("configured_not_telemetry_verified"));
    root.Set("reported_mono_ns", JsonValue::Str(std::to_string(now_ns)));
    root.Set("simulated_input", JsonValue::Bool(config.simulated_input));
    root.Set("mode", JsonValue::Str("SHADOW"));
    root.Set("purpose", JsonValue::Str("unverified_original_source_roi_evidence"));
    root.Set("ppe_verification", JsonValue::Str("UNDETERMINED"));
    root.Set("ppe_reason", JsonValue::Str("verifier_not_configured"));
    root.Set("risk_verdict", JsonValue::Str("not_produced_by_source_attention"));
    root.Set("view_id", JsonValue::Str(planner.last_view_id()));
    root.Set("counter_scope", JsonValue::Str("current_planner_instance"));
    root.Set("tracked_targets", JsonValue::Int(static_cast<std::int64_t>(planner.tracked_size())));
    root.Set("quarantined_targets", JsonValue::Int(static_cast<std::int64_t>(planner.quarantine_size())));
    root.Set("wait_expiries", JsonValue::Str(std::to_string(planner.expiries())));
    root.Set("lost_drops", JsonValue::Str(std::to_string(planner.drops())));
    JsonValue policy = JsonValue::Obj();
    const auto& p = config.source_attention;
    policy.Set("class_id", JsonValue::Int(p.class_id));
    policy.Set("max_targets", JsonValue::Int(static_cast<std::int64_t>(p.max_targets)));
    policy.Set("max_jobs_per_tick", JsonValue::Int(static_cast<std::int64_t>(p.max_jobs_per_tick)));
    policy.Set("max_bytes_per_tick", JsonValue::Int(static_cast<std::int64_t>(p.max_bytes_per_tick)));
    policy.Set("repeat_ns", JsonValue::Str(std::to_string(p.repeat_ns)));
    policy.Set("lost_ns", JsonValue::Str(std::to_string(p.lost_ns)));
    policy.Set("wait_ns", JsonValue::Str(std::to_string(p.wait_ns)));
    policy.Set("retry_ns", JsonValue::Str(std::to_string(p.retry_ns)));
    policy.Set("calibrated_for_hardware", JsonValue::Bool(false));
    root.Set("resource_policy", std::move(policy));
    JsonValue outputs = JsonValue::Obj();
    for (const char* name : {"incidents_created", "alerts_created", "human_responses",
                             "camera_commands", "flight_commands"}) {
        outputs.Set(name, JsonValue::Int(0));
    }
    root.Set("outputs", std::move(outputs));
    JsonValue custody = JsonValue::Obj();
    custody.Set("rc_receipt", JsonValue::Str("unverified"));
    custody.Set("core_retention", JsonValue::Str("unverified"));
    root.Set("custody", std::move(custody));
    return root;
}

bool ValidRgb(const std::uint8_t* rgb, std::size_t size,
               std::uint32_t width, std::uint32_t height) {
    return rgb != nullptr && width != 0 && height != 0 &&
        width <= std::numeric_limits<std::size_t>::max() / 3 / height &&
        size == static_cast<std::size_t>(width) * height * 3;
}
}  // namespace

void AutonomousProcessor::ResetSourceAttention() {
    ++attention_generation_;
    attention_source_view_.clear();
    attention_last_source_receive_ns_ = -1;
    attention_last_report_signature_.clear();
    attention_last_report_ns_ = -1;
    if (valid_ && config_.source_attention.enabled) {
        attention_ = std::make_unique<ObservationPlanner>(
            SourceAttentionPlannerConfig(config_.source_attention));
    }
}

void AutonomousProcessor::CaptureSourceAttention(
    const TickRecord& record, const std::uint8_t* rgb, std::size_t size,
    std::uint32_t width, std::uint32_t height, const FrameContext* context,
    const ArtifactInfo& current_frame,
    SharedTickResult* result) {
    if (!attention_) return;  // Default-off compatibility: no extra artifacts/work.
    // Nothing can be kept: do not schedule, crop or hash. The saturation fact
    // already reaches the run through ProcessSharedTick.
    if (coverage_.evidence_saturated) {
        ++coverage_.attention_reports_suppressed;
        return;
    }
    JsonValue view = JsonValue::Obj();
    view.Set("flight", JsonValue::Str(config_.flight_id));
    view.Set("boot", JsonValue::Str(config_.edge_boot_id));
    view.Set("source_flight", JsonValue::Str(context ? context->flight_id : config_.flight_id));
    view.Set("source_boot", JsonValue::Str(context ? context->boot_id : config_.edge_boot_id));
    view.Set("stream", JsonValue::Str(record.stream_id));
    view.Set("lens", JsonValue::Str(config_.lens_id));
    view.Set("generation", JsonValue::Str(std::to_string(attention_generation_)));
    view.Set("model", JsonValue::Str(record.model_id));
    view.Set("model_digest", JsonValue::Str(record.model_digest));
    view.Set("config", JsonValue::Str(config_.effective_config_hash));
    view.Set("config_epoch", JsonValue::Str(std::to_string(config_.config_epoch)));
    view.Set("mission", JsonValue::Str(config_.mission_revision_id));
    view.Set("policy", JsonValue::Str(config_.policy_revision_id));
    view.Set("width", JsonValue::Int(width));
    view.Set("height", JsonValue::Int(height));
    const std::string view_id = DigestSha256Json(view);
    if (attention_source_view_ != view_id) {
        attention_source_view_ = view_id;
        attention_last_source_receive_ns_ = -1;
    }
    const bool source_time_valid = record.receive_mono_ns >= 0 &&
        record.receive_mono_ns >= attention_last_source_receive_ns_;
    if (source_time_valid) attention_last_source_receive_ns_ = record.receive_mono_ns;
    // Device HealthTick runs with the caller's current clock BEFORE a queued
    // frame is popped. Its receive stamp is naturally older, not a planner
    // clock rollback. Keep scheduling/attempt deadlines on the latest supplied
    // clock, but validate SOURCE order and age separately (never relabel age 0).
    const auto plan_now = std::max({record.receive_mono_ns, attention_->last_now_ns(),
                                    attention_last_health_ns_});
    const auto source_age = source_time_valid ? plan_now - record.receive_mono_ns : -1;
    const bool source_recent = source_time_valid && source_age <= config_.source_attention.lost_ns;
    const bool context_matches = context == nullptr ||
        (context->frame_id == record.frame_id &&
         context->receive_monotonic_ns == record.receive_mono_ns &&
         context->stream_id == record.stream_id &&
         context->flight_id == config_.flight_id && context->boot_id == config_.edge_boot_id);
    const bool source_ready = ValidRgb(rgb, size, width, height) && context_matches;
    std::vector<ObservationCandidate> candidates;
    candidates.reserve(config_.source_attention.max_targets);
    std::size_t eligible_count = 0;
    for (const auto& item : record.tracks) {
        if (item.track.class_id != config_.source_attention.class_id) continue;
        ++eligible_count;
        if (eligible_count > config_.source_attention.max_targets) continue;
        ObservationCandidate candidate;
        candidate.key = KeyFor(item.track);
        candidate.confirmed = item.track.confirmed;
        candidate.fresh_observed = source_recent && context_matches && record.detection_status == DetectionStatus::kOk &&
            item.provenance == TrackProvenance::kObserved && item.since_observed_ns == 0 &&
            record.receive_mono_ns >= 0 &&
            item.track.last_seen_ns == static_cast<std::uint64_t>(record.receive_mono_ns);
        // RuleOutput currently has no honest per-target urgency attribution.
        candidate.priority = ObservationPriority::kRoutine;
        SourceRoi roi;
        if (source_ready && DescribeSourceRoi(item.track.box, width, height, &roi, nullptr)) {
            candidate.readiness = SourceRoiReadiness::kUsable;
            candidate.estimated_cost_bytes = roi.byte_size;
        }
        candidates.push_back(candidate);
    }
    if (!source_time_valid) {
        result->attention_plan.error = "source_frame_time_rollback_or_negative";
    } else if (eligible_count > config_.source_attention.max_targets) {
        // Refuse the WHOLE batch, not an arbitrary first N targets. No
        // unbounded planner inputs/results; no hidden discarded capture jobs.
        attention_->Plan(plan_now, view_id, {});
        result->attention_plan.error = "candidate_input_cap_exceeded";
    } else {
        result->attention_plan = attention_->Plan(plan_now, view_id, candidates);
    }
    // Event-driven reporting: an attempted capture always writes its report
    // (crop provenance must never be separated from the crop). Otherwise only
    // a decision change or the bounded heartbeat is persisted — an identical
    // steady-state tick is summarized, not written once per frame.
    std::string signature;
    signature.reserve(64 + result->attention_plan.decisions.size() * 48);
    signature += result->attention_plan.ok
                     ? std::string("ok|")
                     : "err:" + result->attention_plan.error + "|";
    for (const auto& decision : result->attention_plan.decisions) {
        signature += std::to_string(decision.key.tracker_epoch) + "/" +
                     std::to_string(decision.key.track_id) + "/" +
                     std::to_string(decision.key.class_id) + "=" +
                     decision.reason + ";";
    }
    const bool attempted = !result->attention_plan.jobs.empty();
    const bool changed = signature != attention_last_report_signature_;
    const bool due =
        attention_last_report_ns_ < 0 ||
        (config_.attention_report_interval_ns > 0 &&
         plan_now - attention_last_report_ns_ >=
             config_.attention_report_interval_ns);
    if (!attempted && !changed && !due) {
        ++coverage_.attention_reports_suppressed;
        return;
    }
    attention_last_report_signature_ = signature;
    attention_last_report_ns_ = plan_now;

    JsonValue report = BaseReport(config_, *attention_, plan_now, "frame");
    report.Set("source_receive_mono_ns", JsonValue::Str(std::to_string(record.receive_mono_ns)));
    report.Set("source_age_at_plan_ns", source_time_valid ? JsonValue::Str(std::to_string(source_age)) : JsonValue::Null());
    report.Set("source_recent_at_plan", JsonValue::Bool(source_recent));
    report.Set("planner_clock", JsonValue::Str("latest_caller_health_or_source_receive"));
    report.Set("view_identity", std::move(view));
    report.Set("tick_index", JsonValue::Str(std::to_string(record.tick_index)));
    report.Set("frame_id", JsonValue::Int(record.frame_id));
    report.Set("stream_id", JsonValue::Str(record.stream_id));
    report.Set("source_width", JsonValue::Int(width));
    report.Set("source_height", JsonValue::Int(height));
    report.Set("source_rgb24_valid", JsonValue::Bool(source_ready));
    report.Set("source_context_matches", JsonValue::Bool(context_matches));
    report.Set("exposure_verified", JsonValue::Bool(record.exposure_verified));
    report.Set("time_grade", JsonValue::Str(record.exposure_verified ? "verified_exposure" : "receive_only"));
    report.Set("model_id", JsonValue::Str(record.model_id));
    report.Set("model_digest", JsonValue::Str(record.model_digest));
    report.Set("detection_status", JsonValue::Str(ToString(record.detection_status)));
    report.Set("candidate_count", JsonValue::Str(std::to_string(eligible_count)));
    report.Set("planner_ok", JsonValue::Bool(result->attention_plan.ok));
    report.Set("planner_error", JsonValue::Str(result->attention_plan.error));
    report.Set("selected_bytes", JsonValue::Int(static_cast<std::int64_t>(result->attention_plan.total_bytes)));
    const bool need_digest = !result->attention_plan.jobs.empty();
    const bool reuse_digest = need_digest && !current_frame.sha256_hex.empty();
    report.Set("source_rgb_sha256", !need_digest ? JsonValue::Null() : JsonValue::Str(
        reuse_digest ? current_frame.sha256_hex : Sha256Hex(rgb, size)));
    report.Set("source_frame_asset_id", reuse_digest ? JsonValue::Str(current_frame.asset_id) : JsonValue::Null());
    report.Set("source_rgb_digest_origin", JsonValue::Str(!need_digest ? "not_required_no_capture" :
        (reuse_digest ? "same_tick_committed_source_frame" : "current_source_bytes")));
    JsonValue decisions = JsonValue::Arr();
    JsonValue captures = JsonValue::Arr();
    for (const auto& decision : result->attention_plan.decisions) {
        JsonValue value = KeyJson(decision.key);
        value.Set("selected", JsonValue::Bool(decision.selected));
        value.Set("reason", JsonValue::Str(decision.reason));
        value.Set("estimated_bytes", JsonValue::Str(std::to_string(decision.estimated_cost_bytes)));
        decisions.Push(std::move(value));
    }
    const std::string observation_id = record.detection_status == DetectionStatus::kOk &&
        !record.detections.empty() ? MopTickEvidence::ObservationIdFor(
            config_.flight_id, config_.edge_boot_id, record.tick_index) : "";
    for (const auto& job : result->attention_plan.jobs) {
        JsonValue capture = KeyJson(job.key);
        SourceRoi roi;
        bool described = false;
        for (const auto& item : record.tracks) {
            if (KeyFor(item.track) == job.key) {
                described = DescribeSourceRoi(item.track.box, width, height, &roi, nullptr);
                if (described) {
                    const auto& box = item.track.box;
                    capture.Set("track_box_source_xyxy", JsonValue::Arr({
                        JsonValue::Double(box.x_min), JsonValue::Double(box.y_min),
                        JsonValue::Double(box.x_max), JsonValue::Double(box.y_max)}));
                }
                break;
            }
        }
        std::vector<std::uint8_t> crop;
        std::string error;
        ArtifactInfo info;
        bool stored = false;
        if (described && CopySourceRoiRgb(rgb, size, width, height, roi,
                static_cast<std::size_t>(job.estimated_cost_bytes), &crop, &error)) {
            info.asset_id = Uuid5(MopProtocol::Namespace(), "source-roi-v1|" +
                config_.flight_id + "|" + config_.edge_boot_id + "|" +
                std::to_string(record.tick_index) + "|" + view_id + "|" +
                std::to_string(job.key.tracker_epoch) + "|" + std::to_string(job.key.track_id));
            stored = WriteArtifact(info.asset_id, kMediaSourceRoiV1, observation_id,
                record.frame_id, record.receive_mono_ns, crop.data(), crop.size(), &info);
            if (!stored) error = EvidenceStatusName(info.write_status);
        } else if (error.empty()) {
            error = "source_roi_unavailable";
        }
        capture.Set("roi", RoiJson(roi));
        capture.Set("stored", JsonValue::Bool(stored));
        capture.Set("error", JsonValue::Str(error));
        capture.Set("asset_id", JsonValue::Str(info.asset_id));
        capture.Set("sha256", JsonValue::Str(info.sha256_hex));
        capture.Set("size_bytes", JsonValue::Str(std::to_string(info.size_bytes)));
        captures.Push(std::move(capture));
        result->attention_crops.push_back(info);
    }
    report.Set("observation_id", JsonValue::Str(observation_id));
    report.Set("decisions", std::move(decisions));
    report.Set("captures", std::move(captures));
    const std::string json = WriteCanonicalJson(report);
    const std::string id = Uuid5(MopProtocol::Namespace(), "source-attention-v1|" +
        config_.flight_id + "|" + config_.edge_boot_id + "|" + std::to_string(record.tick_index));
    WriteArtifact(id, kMediaSourceAttentionV1, observation_id, record.frame_id,
        record.receive_mono_ns, reinterpret_cast<const std::uint8_t*>(json.data()),
        json.size(), &result->attention_artifact);
}

void AutonomousProcessor::AdvanceSourceAttention(std::int64_t now_ns, bool stopping) {
    if (!attention_) return;
    if (now_ns < 0 || now_ns < attention_last_health_ns_) {
        LatchWriteFailure("source attention lifecycle clock rollback");
        return;
    }
    // Keep caller time even before the first view/frame so an old initial
    // queue item cannot be mistaken for a zero-age source on startup.
    attention_last_health_ns_ = now_ns;
    if (attention_->last_view_id().empty()) return;
    const auto old_expiries = attention_->expiries();
    const auto old_drops = attention_->drops();
    if (!attention_->Advance(now_ns)) {
        LatchWriteFailure("source attention lifecycle clock rollback");
        return;
    }
    if (!stopping && old_expiries == attention_->expiries() && old_drops == attention_->drops()) return;
    JsonValue report = BaseReport(config_, *attention_, now_ns, stopping ? "stop" : "no_frame_health");
    report.Set("frame_id", JsonValue::Null());
    report.Set("remaining_targets_closed_unknown", JsonValue::Int(stopping
        ? static_cast<std::int64_t>(attention_->tracked_size() + attention_->quarantine_size()) : 0));
    const std::string json = WriteCanonicalJson(report);
    const std::string id = Uuid5(MopProtocol::Namespace(), "source-attention-lifecycle-v1|" +
        config_.flight_id + "|" + config_.edge_boot_id + "|" +
        std::to_string(attention_generation_) + "|" + std::to_string(now_ns) + "|" +
        std::to_string(attention_->expiries()) + "|" + std::to_string(attention_->drops()) +
        (stopping ? "|stop" : "|health"));
    WriteArtifact(id, kMediaSourceAttentionV1, "", 0, now_ns,
        reinterpret_cast<const std::uint8_t*>(json.data()), json.size(), nullptr);
}
}  // namespace ksi::safety
