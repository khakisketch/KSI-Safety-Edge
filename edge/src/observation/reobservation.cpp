// Bounded active-camera reobservation controller implementation.
//
// Pure injected-clock logic: every deadline is compared against caller
// provided now_monotonic_ns (which must never roll back). No clock reads,
// no RNG, no I/O, no flight or patrol interfaces. See the header for the
// authority/fencing contract.

#include "ksi_safety/observation/reobservation.hpp"

#include <cstdio>

namespace ksi::safety::observation {
namespace {

bool NonEmpty(const std::string& s) { return !s.empty(); }

bool PositiveFinite(std::int64_t value, std::int64_t cap) {
    return value > 0 && value <= cap;
}

std::string OwnerName(CameraOwner owner) {
    switch (owner) {
        case CameraOwner::kHuman: return "human";
        case CameraOwner::kDjiMission: return "dji_mission";
        case CameraOwner::kKsi: return "ksi";
        case CameraOwner::kUnknown: break;
    }
    return "unknown";
}

}  // namespace

bool ValidateConfig(const ReobserveConfig& config, std::string* error) {
    const std::int64_t cap = ReobserveConfig::kMaxDurationNs;
    auto fail = [&](const std::string& reason) {
        if (error != nullptr) *error = reason;
        return false;
    };
    if (!config.operational_thresholds_confirmed) {
        return fail("thresholds_not_confirmed");
    }
    if (!PositiveFinite(config.max_dwell_ns, cap)) {
        return fail("bad_max_dwell_ns");
    }
    if (!PositiveFinite(config.request_timeout_ns, cap)) {
        return fail("bad_request_timeout_ns");
    }
    if (!PositiveFinite(config.settle_timeout_ns, cap)) {
        return fail("bad_settle_timeout_ns");
    }
    if (!PositiveFinite(config.match_timeout_ns, cap)) {
        return fail("bad_match_timeout_ns");
    }
    if (!PositiveFinite(config.frame_freshness_ns, cap)) {
        return fail("bad_frame_freshness_ns");
    }
    if (!PositiveFinite(config.state_freshness_ns, cap)) {
        return fail("bad_state_freshness_ns");
    }
    if (!PositiveFinite(config.target_freshness_ns, cap)) {
        return fail("bad_target_freshness_ns");
    }
    if (!PositiveFinite(config.config_freshness_ns, cap)) {
        return fail("bad_config_freshness_ns");
    }
    if (!PositiveFinite(config.report_freshness_ns, cap)) {
        return fail("bad_report_freshness_ns");
    }
    if (config.max_retries < 0 ||
        config.max_retries > ReobserveConfig::kMaxRetriesCeiling) {
        return fail("bad_max_retries");
    }
    // Rejected, never silently repaired (no bound swapping).
    if (config.permitted_min_zoom < 0 ||
        config.permitted_min_zoom > config.permitted_max_zoom) {
        return fail("bad_permitted_zoom_range");
    }
    if (config.permitted_max_pitch_mdeg < 0 ||
        config.permitted_max_pitch_mdeg > ReobserveConfig::kMaxGimbalMdeg) {
        return fail("bad_permitted_pitch");
    }
    if (config.permitted_max_yaw_mdeg < 0 ||
        config.permitted_max_yaw_mdeg > ReobserveConfig::kMaxGimbalMdeg) {
        return fail("bad_permitted_yaw");
    }
    if (config.max_history_records < ReobserveConfig::kMinHistoryRecords ||
        config.max_history_records > ReobserveConfig::kMaxHistoryRecords) {
        return fail("bad_max_history_records");
    }
    // The whole-task deadline must cover every phase, else no task could
    // ever complete: overflow-safe comparison without forming the sum.
    if (config.request_timeout_ns >
        config.max_dwell_ns - config.settle_timeout_ns -
            config.match_timeout_ns) {
        return fail("dwell_does_not_cover_phases");
    }
    if (error != nullptr) error->clear();
    return true;
}

ReobservationController::ReobservationController(const ReobserveConfig& config,
                                                 InjectedCameraPort* port)
    : config_(config), port_(port) {
    config_ok_ = ValidateConfig(config_, &config_error_);
}

bool ReobservationController::IdentityEqual(const IdentityContext& a,
                                            const IdentityContext& b) {
    return a.flight_id == b.flight_id && a.boot_id == b.boot_id &&
           a.camera_id == b.camera_id && a.lens_id == b.lens_id;
}

bool ReobservationController::IdentityComplete(
    const IdentityContext& identity) {
    return NonEmpty(identity.flight_id) && NonEmpty(identity.boot_id) &&
           NonEmpty(identity.camera_id) && NonEmpty(identity.lens_id);
}

bool ReobservationController::AddOverflows(std::int64_t a, std::int64_t b) {
    if (b > 0 && a > INT64_MAX - b) return true;
    if (b < 0 && a < INT64_MIN - b) return true;
    return false;
}

bool ReobservationController::IdentityMatches(
    const IdentityContext& other) const {
    return IdentityEqual(authority_identity_, other);
}

bool ReobservationController::AdvanceTime(std::int64_t now_ns) {
    if (now_ns < 0) return false;
    if (last_now_ns_ < 0) {
        last_now_ns_ = now_ns;
        return true;
    }
    if (now_ns < last_now_ns_) {
        Append("time_rollback_rejected", "now_behind_last", active_task_id_,
               authority_generation_, last_now_ns_);
        return false;
    }
    last_now_ns_ = now_ns;
    return true;
}

void ReobservationController::Append(const std::string& kind,
                                     const std::string& detail,
                                     std::uint64_t task_id,
                                     std::uint64_t generation,
                                     std::int64_t now_ns) {
    if (history_full_ ||
        history_.size() >= static_cast<std::size_t>(config_.max_history_records)) {
        history_full_ = true;
        ++dropped_records_;
        return;
    }
    ActionRecord record;
    record.seq = next_seq_++;
    record.task_id = task_id;
    record.generation = generation;
    record.kind = kind;
    record.detail = detail;
    record.now_monotonic_ns = now_ns;
    history_.push_back(record);
}

bool ReobservationController::ReportsFresh(std::int64_t now_ns) const {
    if (last_owner_ns_ < 0 || last_supervision_ns_ < 0 ||
        last_evidence_ns_ < 0 || last_identity_ns_ < 0) {
        return false;
    }
    const std::int64_t window = config_.report_freshness_ns;
    return now_ns - last_owner_ns_ <= window &&
           now_ns - last_supervision_ns_ <= window &&
           now_ns - last_evidence_ns_ <= window &&
           now_ns - last_identity_ns_ <= window;
}

bool ReobservationController::VideoFresh(std::int64_t now_ns) const {
    return last_frame_ns_ >= 0 &&
           now_ns - last_frame_ns_ <= config_.frame_freshness_ns;
}

bool ReobservationController::AuthorityCore(std::int64_t now_ns) const {
    if (!authority_valid_ || authority_generation_ == 0) return false;
    if (now_ns >= authority_expires_ns_) return false;
    if (allowance_ != AutoAllowance::kEnabled) return false;
    if (ownership_hold_ || supervision_hold_) return false;
    if (actual_owner_ != CameraOwner::kKsi) return false;
    if (supervision_ == Supervision::kLost) return false;
    if (!evidence_capacity_ok_) return false;
    if (!IdentityEqual(authority_identity_, device_identity_)) return false;
    if (!ReportsFresh(now_ns)) return false;
    return true;
}

bool ReobservationController::GrantLive(std::int64_t now_ns) const {
    return AuthorityCore(now_ns) && VideoFresh(now_ns);
}

void ReobservationController::InvalidateAuthority(const std::string& reason,
                                                 std::int64_t now_ns) {
    if (task_state_ == TaskState::kAwaitingAck ||
        task_state_ == TaskState::kAwaitingSettle ||
        task_state_ == TaskState::kAwaitingMatch) {
        CancelInflight("authority_invalidated:" + reason, now_ns);
        task_state_ = TaskState::kDone;
        task_outcome_ = TaskOutcome::kCancelled;
        // Preserve failure truth: a cancelled re-look teaches nothing.
        assessment_ = Assessment::kUnclear;
        Append("task_cancelled", reason, active_task_id_,
               authority_generation_, now_ns);
    }
    authority_valid_ = false;
    Append("authority_invalidated", reason, active_task_id_,
           authority_generation_, now_ns);
}

void ReobservationController::CancelInflight(const std::string& reason,
                                            std::int64_t now_ns) {
    if (port_ != nullptr &&
        port_->capabilities().cancel_supported &&
        (task_state_ == TaskState::kAwaitingAck ||
         task_state_ == TaskState::kAwaitingSettle ||
         task_state_ == TaskState::kAwaitingMatch)) {
        port_->Cancel(active_task_id_, authority_generation_);
        Append("cancel_sent", reason, active_task_id_, authority_generation_,
               now_ns);
    }
    // Deliberately NO drive back to an old AI pose while manual/mission
    // holds the camera: moving the gimbal "home" would itself be actuation
    // under someone else's ownership.
}

void ReobservationController::Finish(TaskOutcome outcome, Assessment assessment,
                                     const std::string& kind,
                                     const std::string& detail,
                                     std::int64_t now_ns) {
    task_state_ = TaskState::kDone;
    task_outcome_ = outcome;
    assessment_ = assessment;
    last_skip_or_fail_reason_ = detail;
    Append(kind, detail, active_task_id_, authority_generation_, now_ns);
}

bool ReobservationController::GrantAuthority(const CameraGrant& grant,
                                            std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return false;
    if (history_full_) return false;
    // A new grant always fences the previous inflight task: the old
    // (task, generation) pair can never actuate again, and its late
    // callbacks are fenced by the task+generation checks in each handler.
    if (task_state_ == TaskState::kAwaitingAck ||
        task_state_ == TaskState::kAwaitingSettle ||
        task_state_ == TaskState::kAwaitingMatch) {
        CancelInflight("new_grant_fences_old_task", now_ns);
        task_state_ = TaskState::kDone;
        task_outcome_ = TaskOutcome::kCancelled;
        assessment_ = Assessment::kUnclear;
        Append("task_cancelled", "superseded_by_new_grant", active_task_id_,
               authority_generation_, now_ns);
    }
    // Generations must strictly advance: a replayed or decreasing generation
    // is rejected, so a stale grant can never restore a released camera.
    if (grant.generation == 0 || grant.generation <= max_generation_seen_) {
        authority_valid_ = false;
        Append("grant_rejected", "grant_generation_not_advancing",
               active_task_id_, grant.generation, now_ns);
        return false;
    }
    if (now_ns >= grant.expires_monotonic_ns ||
        grant.allowance != AutoAllowance::kEnabled ||
        !IdentityComplete(grant.identity)) {
        authority_valid_ = false;
        Append("grant_rejected", "grant_not_explicitly_enabled_live",
               active_task_id_, grant.generation, now_ns);
        return false;
    }
    max_generation_seen_ = grant.generation;
    authority_valid_ = true;
    authority_generation_ = grant.generation;
    authority_expires_ns_ = grant.expires_monotonic_ns;
    authority_identity_ = grant.identity;
    allowance_ = grant.allowance;
    // A fresh advancing grant is the ONLY thing that clears latched holds.
    ownership_hold_ = false;
    supervision_hold_ = false;
    task_state_ = TaskState::kIdle;
    task_outcome_ = TaskOutcome::kNone;
    assessment_ = Assessment::kUnavailable;
    active_task_id_ = 0;
    Append("grant_accepted", "generation_live", 0, authority_generation_,
           now_ns);
    return true;
}

void ReobservationController::NoteActualOwner(CameraOwner owner,
                                             std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    actual_owner_ = owner;
    last_owner_ns_ = now_ns;
    Append("owner_reported", OwnerName(owner), active_task_id_,
           authority_generation_, now_ns);
    if (owner != CameraOwner::kKsi) {
        // Latch: only a fresh advancing grant clears this hold.
        ownership_hold_ = true;
        if (task_state_ == TaskState::kAwaitingAck ||
            task_state_ == TaskState::kAwaitingSettle ||
            task_state_ == TaskState::kAwaitingMatch) {
            InvalidateAuthority(owner == CameraOwner::kHuman
                                    ? "manual_takeover"
                                    : (owner == CameraOwner::kDjiMission
                                           ? "mission_reservation"
                                           : "owner_not_ksi"),
                                now_ns);
        } else if (authority_valid_) {
            // No inflight task, but the stored grant must not silently
            // survive someone else holding the camera.
            authority_valid_ = false;
            Append("authority_invalidated", "owner_not_ksi_idle",
                   active_task_id_, authority_generation_, now_ns);
        }
    }
}

void ReobservationController::NoteSupervision(Supervision supervision,
                                             std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    supervision_ = supervision;
    last_supervision_ns_ = now_ns;
    Append("supervision_reported",
           supervision == Supervision::kLost ? "lost" : "present_hold_latched",
           active_task_id_, authority_generation_, now_ns);
    if (supervision == Supervision::kLost) {
        // Analysis continues externally; only motion authority is dropped.
        // The hold below means a later Present alone never resumes motion.
        supervision_hold_ = true;
        InvalidateAuthority("supervision_lost", now_ns);
    }
}

void ReobservationController::NoteDeviceIdentity(const IdentityContext& identity,
                                                std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    if (!IdentityComplete(identity)) {
        Append("device_identity_rejected", "identity_incomplete",
               active_task_id_, authority_generation_, now_ns);
        return;
    }
    const bool changed = last_identity_ns_ >= 0 &&
                         !IdentityEqual(device_identity_, identity);
    device_identity_ = identity;
    last_identity_ns_ = now_ns;
    Append("device_identity_reported", changed ? "epoch_changed" : "epoch_ok",
           active_task_id_, authority_generation_, now_ns);
    if (changed) {
        InvalidateAuthority("device_epoch_changed", now_ns);
    }
}

void ReobservationController::NoteEvidenceCapacity(bool capacity_ok,
                                                  std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    evidence_capacity_ok_ = capacity_ok;
    last_evidence_ns_ = now_ns;
    Append("evidence_capacity_reported", capacity_ok ? "ok" : "exhausted",
           active_task_id_, authority_generation_, now_ns);
    if (!capacity_ok && (authority_valid_ ||
                         task_state_ == TaskState::kAwaitingAck ||
                         task_state_ == TaskState::kAwaitingSettle ||
                         task_state_ == TaskState::kAwaitingMatch)) {
        // Every action needs audit room: exhaustion drops motion authority
        // immediately, with no automatic resume.
        InvalidateAuthority("evidence_capacity_lost", now_ns);
    }
}

void ReobservationController::OnReboot(std::int64_t now_ns) {
    // Best-effort Cancel FIRST, while the inflight (task, generation) pair
    // is still known — then drop live authority, tasks, and reports.
    // In-memory history survives; generation/task counters stay monotonic.
    if (last_now_ns_ >= 0 && now_ns < last_now_ns_) {
        Append("time_rollback_rejected", "reboot_behind_last",
               active_task_id_, authority_generation_, last_now_ns_);
        return;
    }
    if (now_ns >= 0) last_now_ns_ = now_ns;
    CancelInflight("reboot", now_ns);
    if (task_state_ == TaskState::kAwaitingAck ||
        task_state_ == TaskState::kAwaitingSettle ||
        task_state_ == TaskState::kAwaitingMatch) {
        task_state_ = TaskState::kDone;
        task_outcome_ = TaskOutcome::kCancelled;
        assessment_ = Assessment::kUnclear;
        Append("task_cancelled", "reboot", active_task_id_,
               authority_generation_, now_ns);
    }
    // New epoch: fresh grant + fresh reports required afterwards.
    authority_valid_ = false;
    authority_generation_ = 0;
    ownership_hold_ = false;
    supervision_hold_ = false;
    actual_owner_ = CameraOwner::kUnknown;
    supervision_ = Supervision::kLost;
    evidence_capacity_ok_ = false;
    last_owner_ns_ = -1;
    last_supervision_ns_ = -1;
    last_evidence_ns_ = -1;
    last_identity_ns_ = -1;
    last_frame_ns_ = -1;
    task_state_ = TaskState::kIdle;
    task_outcome_ = TaskOutcome::kNone;
    assessment_ = Assessment::kUnavailable;
    active_task_id_ = 0;
    lens_settled_fresh_ = false;
    fresh_frames_seen_ = false;
    settle_ns_ = -1;
    qualifying_frame_id_ = 0;
    qualifying_frame_ns_ = -1;
    Append("reboot", "cancelled_then_reports_reset_history_kept", 0, 0,
           now_ns);
}

bool ReobservationController::IntentSupported(
    const ImagingIntent& in) const {
    if (port_ == nullptr) return false;
    const PortCapabilities caps = port_->capabilities();
    if (in.zoom_step < config_.permitted_min_zoom ||
        in.zoom_step > config_.permitted_max_zoom) {
        return false;
    }
    if (in.gimbal_pitch_mdeg < -config_.permitted_max_pitch_mdeg ||
        in.gimbal_pitch_mdeg > config_.permitted_max_pitch_mdeg) {
        return false;
    }
    if (in.gimbal_yaw_mdeg < -config_.permitted_max_yaw_mdeg ||
        in.gimbal_yaw_mdeg > config_.permitted_max_yaw_mdeg) {
        return false;
    }
    if (in.zoom_step != 0) {
        if (!caps.zoom_supported) return false;
        if (in.zoom_step < caps.min_zoom_step ||
            in.zoom_step > caps.max_zoom_step) {
            return false;
        }
    }
    if (in.gimbal_pitch_mdeg != 0 || in.gimbal_yaw_mdeg != 0) {
        if (!caps.gimbal_supported) return false;
        if (in.gimbal_pitch_mdeg > caps.max_pitch_mdeg ||
            in.gimbal_pitch_mdeg < -caps.max_pitch_mdeg) {
            return false;
        }
        if (in.gimbal_yaw_mdeg > caps.max_yaw_mdeg ||
            in.gimbal_yaw_mdeg < -caps.max_yaw_mdeg) {
            return false;
        }
    }
    if (in.capture_still && !caps.still_supported) return false;
    return true;
}

bool ReobservationController::ResendOrFail(const std::string& fail_reason,
                                          std::int64_t now_ns) {
    if (attempts_ <= config_.max_retries && GrantLive(now_ns) &&
        port_ != nullptr &&
        port_->Request(active_task_id_, authority_generation_,
                        active_intent_)) {
        ++attempts_;
        ack_deadline_ns_ = now_ns + config_.request_timeout_ns;
        char detail[64];
        std::snprintf(detail, sizeof(detail), "attempt_%d", attempts_);
        Append("request_sent", detail, active_task_id_, authority_generation_,
               now_ns);
        return true;
    }
    Finish(TaskOutcome::kFailed, Assessment::kUnavailable, "task_failed",
           fail_reason, now_ns);
    return false;
}

StartOutcome ReobservationController::RequestReobservation(
    std::uint64_t task_id, const SuspectTarget& suspect,
    std::int64_t now_ns) {
    StartOutcome out;
    if (!AdvanceTime(now_ns)) {
        out.outcome = TaskOutcome::kRejected;
        out.reason = "time_rollback";
        return out;
    }
    if (history_full_) {
        out.outcome = TaskOutcome::kRejected;
        out.reason = "record_capacity_exhausted";
        return out;
    }
    if (!config_ok_) {
        out.outcome = TaskOutcome::kRejected;
        out.reason = "config_invalid";
        Append("request_rejected", config_error_.empty()
                                       ? "config_invalid"
                                       : "config_invalid:" + config_error_,
               task_id, authority_generation_, now_ns);
        return out;
    }
    // Task ids are strictly increasing and never reused: an old callback
    // carrying a previous task id can never affect a new task.
    if (task_id == 0 || task_id <= max_task_id_seen_) {
        out.outcome = TaskOutcome::kRejected;
        out.reason = "task_id_reused";
        Append("request_rejected", out.reason, task_id, authority_generation_,
               now_ns);
        return out;
    }
    // One inflight task at a time: bounded, no queue.
    if (task_state_ == TaskState::kAwaitingAck ||
        task_state_ == TaskState::kAwaitingSettle ||
        task_state_ == TaskState::kAwaitingMatch) {
        out.outcome = TaskOutcome::kRejected;
        out.reason = "task_already_inflight";
        Append("request_rejected", out.reason, task_id, authority_generation_,
               now_ns);
        return out;
    }
    max_task_id_seen_ = task_id;
    auto skip = [&](const std::string& reason) {
        active_task_id_ = task_id;
        out.outcome = TaskOutcome::kSkipped;
        out.reason = reason;
        Finish(TaskOutcome::kSkipped, Assessment::kUnavailable, "task_skipped",
               reason, now_ns);
        return out;
    };
    auto reject = [&](const std::string& reason) {
        active_task_id_ = task_id;
        out.outcome = TaskOutcome::kRejected;
        out.reason = reason;
        Finish(TaskOutcome::kRejected, Assessment::kUnavailable,
               "request_rejected", reason, now_ns);
        return out;
    };
    // ---- usefulness gate: never move without explicit need ----
    if (!suspect.require_more_detail) {
        return skip("no_need_require_more_detail_false");
    }
    if (suspect.wide_context_required) {
        return skip("no_need_wide_context_required");
    }
    if (suspect.quality == ImageQuality::kUsable) {
        return skip("no_need_quality_usable");
    }
    if (!NonEmpty(suspect.source_id) || !NonEmpty(suspect.target_id)) {
        return skip("no_need_unknown_source_or_target");
    }
    if (suspect.provenance == TargetProvenance::kLost) {
        return skip("no_need_target_lost");
    }
    if (suspect.observed_monotonic_ns > now_ns) {
        return skip("suspect_observation_in_future");
    }
    if (now_ns - suspect.observed_monotonic_ns > config_.config_freshness_ns) {
        return skip("stale_suspect_config");
    }
    // ---- authority gates: every one must pass, else zero output ----
    if (!authority_valid_ || authority_generation_ == 0) {
        return reject("no_authority");
    }
    if (now_ns >= authority_expires_ns_) {
        InvalidateAuthority("grant_expired", now_ns);
        active_task_id_ = task_id;
        out.outcome = TaskOutcome::kRejected;
        out.reason = "grant_expired";
        last_skip_or_fail_reason_ = out.reason;
        return out;
    }
    if (allowance_ != AutoAllowance::kEnabled) return reject("allowance_not_enabled");
    if (ownership_hold_) return reject("owner_hold_active");
    if (supervision_hold_) return reject("supervision_hold_active");
    if (actual_owner_ != CameraOwner::kKsi) return reject("owner_not_ksi");
    if (supervision_ == Supervision::kLost) return reject("supervision_lost");
    if (!evidence_capacity_ok_) return reject("evidence_capacity_unavailable");
    if (!IdentityEqual(authority_identity_, device_identity_)) {
        return reject("device_epoch_mismatch");
    }
    if (!ReportsFresh(now_ns)) return reject("reports_not_fresh");
    if (!VideoFresh(now_ns)) {
        return reject(last_frame_ns_ < 0 ? "no_fresh_video"
                                          : "stale_video_halts_motion");
    }
    if (!IdentityMatches(suspect.identity)) {
        return reject("identity_mismatch");
    }
    // Pick ONE permitted intent: smallest useful zoom step. Never patrol,
    // never chase: yaw/pitch stay zeroed within the validated box.
    ImagingIntent intent;
    intent.zoom_step = config_.permitted_min_zoom == 0
                           ? 1
                           : config_.permitted_min_zoom;
    if (intent.zoom_step > config_.permitted_max_zoom) {
        return reject("intent_unsupported");
    }
    intent.gimbal_pitch_mdeg = 0;
    intent.gimbal_yaw_mdeg = 0;
    intent.capture_still = true;
    if (!IntentSupported(intent)) {
        // A still the adapter cannot take must not block a useful zoom.
        intent.capture_still = false;
        if (!IntentSupported(intent) || intent.zoom_step == 0) {
            return reject("intent_unsupported");
        }
    }
    if (AddOverflows(now_ns, config_.max_dwell_ns) ||
        AddOverflows(now_ns, config_.request_timeout_ns)) {
        return reject("deadline_overflow");
    }
    if (port_ == nullptr ||
        !port_->Request(task_id, authority_generation_, intent)) {
        // Transport-not-sent: certain nothing moved; one bounded resend.
        active_task_id_ = task_id;
        active_suspect_ = suspect;
        active_intent_ = intent;
        task_state_ = TaskState::kAwaitingAck;
        task_outcome_ = TaskOutcome::kInProgress;
        assessment_ = Assessment::kUnavailable;
        task_start_ns_ = now_ns;
        ack_deadline_ns_ = now_ns + config_.request_timeout_ns;
        task_deadline_ns_ = now_ns + config_.max_dwell_ns;
        settle_deadline_ns_ = 0;
        match_deadline_ns_ = 0;
        attempts_ = 1;
        lens_settled_fresh_ = false;
        fresh_frames_seen_ = false;
        settle_ns_ = -1;
        qualifying_frame_id_ = 0;
        qualifying_frame_ns_ = -1;
        Append("request_transport_failed", "attempt_1", task_id,
               authority_generation_, now_ns);
        ResendOrFail("port_rejected_transport", now_ns);
        out.outcome = task_outcome_;
        out.reason = task_outcome_ == TaskOutcome::kInProgress
                         ? "request_sent"
                         : "port_rejected_transport";
        out.intent = intent;
        return out;
    }
    active_task_id_ = task_id;
    active_suspect_ = suspect;
    active_intent_ = intent;
    task_state_ = TaskState::kAwaitingAck;
    task_outcome_ = TaskOutcome::kInProgress;
    assessment_ = Assessment::kUnavailable;
    task_start_ns_ = now_ns;
    task_deadline_ns_ = now_ns + config_.max_dwell_ns;
    ack_deadline_ns_ = now_ns + config_.request_timeout_ns;
    settle_deadline_ns_ = 0;
    match_deadline_ns_ = 0;
    attempts_ = 1;
    lens_settled_fresh_ = false;
    fresh_frames_seen_ = false;
    settle_ns_ = -1;
    qualifying_frame_id_ = 0;
    qualifying_frame_ns_ = -1;
    out.outcome = TaskOutcome::kInProgress;
    out.reason = "request_sent";
    out.intent = intent;
    Append("request_sent", "attempt_1", task_id, authority_generation_,
           now_ns);
    return out;
}

void ReobservationController::OnRequestAck(std::uint64_t task_id,
                                          std::uint64_t generation,
                                          bool accepted,
                                          std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    if (task_id != active_task_id_ || generation != authority_generation_ ||
        (task_state_ != TaskState::kAwaitingAck &&
         task_state_ != TaskState::kAwaitingSettle &&
         task_state_ != TaskState::kAwaitingMatch)) {
        Append("stale_callback_fenced", "ack", task_id, generation, now_ns);
        return;
    }
    if (task_state_ != TaskState::kAwaitingAck) {
        Append("stale_callback_fenced", "ack_after_settle", task_id,
               generation, now_ns);
        return;
    }
    if (!AuthorityCore(now_ns)) {
        InvalidateAuthority("grant_not_live_at_ack", now_ns);
        return;
    }
    if (!VideoFresh(now_ns)) {
        // The camera may never have been confirmed moving, but the sent
        // request could still be live at the adapter: halt best-effort and
        // fail the task. The grant itself survives a transient video gap.
        CancelInflight("video_stale_at_ack", now_ns);
        Finish(TaskOutcome::kFailed, Assessment::kUnavailable, "task_failed",
               "video_stale_at_ack", now_ns);
        return;
    }
    if (accepted) {
        if (AddOverflows(now_ns, config_.settle_timeout_ns)) {
            Finish(TaskOutcome::kFailed, Assessment::kUnavailable,
                   "task_failed", "deadline_overflow", now_ns);
            return;
        }
        task_state_ = TaskState::kAwaitingSettle;
        settle_deadline_ns_ = now_ns + config_.settle_timeout_ns;
        Append("ack_accepted", "awaiting_settle", task_id, generation,
               now_ns);
        return;
    }
    // Explicit reject: certain the action was refused; bounded resend.
    ResendOrFail("ack_rejected_retries_exhausted", now_ns);
}

void ReobservationController::OnLensState(const LensState& state,
                                         std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    if (state.task_id != active_task_id_ ||
        state.generation != authority_generation_ ||
        task_state_ != TaskState::kAwaitingSettle) {
        Append("stale_callback_fenced", "lens", state.task_id,
               state.generation, now_ns);
        return;
    }
    if (!IdentityMatches(state.identity)) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "lens_identity_mismatch", now_ns);
        return;
    }
    if (state.observed_monotonic_ns > now_ns) {
        Append("lens_future_timestamp", "fenced_never_fresh", state.task_id,
               state.generation, now_ns);
        return;
    }
    if (state.observed_monotonic_ns < task_start_ns_) {
        Append("lens_predates_task", "ignored", state.task_id,
               state.generation, now_ns);
        return; // pre-task stamp: keep waiting; Poll fails the deadline.
    }
    if (now_ns - state.observed_monotonic_ns > config_.state_freshness_ns) {
        Append("lens_stale_ignored", "observed_too_old", state.task_id,
               state.generation, now_ns);
        return; // keep waiting until the settle deadline; Poll fails it.
    }
    if (!state.settled || state.zoom_step != active_intent_.zoom_step) {
        Append("lens_not_settled", "awaiting_settle", state.task_id,
               state.generation, now_ns);
        return;
    }
    lens_settled_fresh_ = true;
    settle_ns_ = now_ns;
    Append("settle_ok", "lens_settled_fresh", state.task_id, state.generation,
           now_ns);
    if (fresh_frames_seen_) {
        if (AddOverflows(now_ns, config_.match_timeout_ns)) {
            Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
                   "deadline_overflow", now_ns);
            return;
        }
        task_state_ = TaskState::kAwaitingMatch;
        match_deadline_ns_ = now_ns + config_.match_timeout_ns;
        Append("awaiting_match", "settled_with_frames", state.task_id,
               state.generation, now_ns);
    }
}

void ReobservationController::OnFrame(const ObservedFrame& frame,
                                     std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    // Future stamps never count as fresh and never move recency backwards.
    if (frame.frame_monotonic_ns > now_ns) {
        Append("frame_future_timestamp", "fenced", active_task_id_,
               authority_generation_, now_ns);
        return;
    }
    // Frame recency is tracked even idle: stale/absent video must halt the
    // NEXT motion request. Frames never actuate anything by themselves, and
    // the parent's passive analysis continues regardless of this unit.
    if (IdentityEqual(authority_identity_, frame.identity) ||
        last_identity_ns_ < 0 ||
        IdentityEqual(device_identity_, frame.identity)) {
        if (frame.frame_monotonic_ns >= last_frame_ns_) {
            last_frame_ns_ = frame.frame_monotonic_ns;
        }
    }
    if (task_state_ == TaskState::kAwaitingSettle) {
        if (!IdentityMatches(frame.identity)) return;
        // Only post-action stamps prove the stream is alive after the move.
        if (frame.frame_monotonic_ns < task_start_ns_) return;
        if (now_ns - frame.frame_monotonic_ns > config_.frame_freshness_ns) {
            return; // stale frame: ignored for settle, analysis unaffected.
        }
        fresh_frames_seen_ = true;
        Append("frame_fresh", "settling", active_task_id_,
               authority_generation_, now_ns);
        if (lens_settled_fresh_) {
            if (AddOverflows(now_ns, config_.match_timeout_ns)) {
                Finish(TaskOutcome::kFailed, Assessment::kUnclear,
                       "task_failed", "deadline_overflow", now_ns);
                return;
            }
            task_state_ = TaskState::kAwaitingMatch;
            match_deadline_ns_ = now_ns + config_.match_timeout_ns;
            Append("awaiting_match", "frames_with_settle", active_task_id_,
                   authority_generation_, now_ns);
        }
        return;
    }
    if (task_state_ == TaskState::kAwaitingMatch) {
        if (!IdentityMatches(frame.identity)) return;
        // Qualifying evidence must be POST-SETTLE: an earlier unmapped frame
        // can open the match window but can never satisfy the verdict.
        if (frame.frame_id == 0) return;
        if (frame.frame_monotonic_ns < settle_ns_) return;
        if (now_ns - frame.frame_monotonic_ns > config_.frame_freshness_ns) {
            return;
        }
        qualifying_frame_id_ = frame.frame_id;
        qualifying_frame_ns_ = frame.frame_monotonic_ns;
        Append("frame_qualifying", "post_settle_evidence", active_task_id_,
               authority_generation_, now_ns);
    }
}

void ReobservationController::OnTargetMatch(const TargetMatch& match,
                                           std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    if (match.task_id != active_task_id_ ||
        match.generation != authority_generation_ ||
        task_state_ != TaskState::kAwaitingMatch) {
        Append("stale_callback_fenced", "match", match.task_id,
               match.generation, now_ns);
        return;
    }
    if (!IdentityMatches(match.identity)) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_identity_mismatch", now_ns);
        return;
    }
    if (match.target_id != active_suspect_.target_id) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_wrong_target_never_chase", now_ns);
        return;
    }
    if (match.observed_monotonic_ns > now_ns) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_future_timestamp", now_ns);
        return;
    }
    if (match.observed_monotonic_ns < task_start_ns_) {
        // A HELD box with a pre-task observation is never chased.
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_predates_task", now_ns);
        return;
    }
    if (now_ns - match.observed_monotonic_ns > config_.target_freshness_ns) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_observation_stale", now_ns);
        return;
    }
    // The verdict must bind to the qualifying post-settle frame the
    // controller itself observed — never to an earlier unmapped frame.
    if (match.based_on_frame_id == 0 || qualifying_frame_id_ == 0 ||
        match.based_on_frame_id != qualifying_frame_id_ ||
        match.based_on_frame_monotonic_ns != qualifying_frame_ns_) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_not_bound_to_post_settle_frame", now_ns);
        return;
    }
    switch (match.provenance) {
        case TargetProvenance::kConfirmed:
            Finish(TaskOutcome::kSucceededVerified, Assessment::kAvailable,
                   "task_succeeded", "target_verified", now_ns);
            break;
        case TargetProvenance::kAmbiguous:
            Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
                   "match_ambiguous_never_confirm", now_ns);
            break;
        case TargetProvenance::kLost:
            Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
                   "match_target_lost", now_ns);
            break;
    }
}

void ReobservationController::Poll(std::int64_t now_ns) {
    if (!AdvanceTime(now_ns)) return;
    // Expiry fires even with zero frames (pure now_ns comparison).
    if (authority_valid_ && now_ns >= authority_expires_ns_) {
        InvalidateAuthority("grant_expired", now_ns);
        return;
    }
    // Reports and video go stale without refresh — enforced even idle and
    // even with zero frames. Never auto-resumes: a fresh grant is required
    // after every invalidation.
    if (authority_valid_ && !ReportsFresh(now_ns)) {
        InvalidateAuthority("report_stale", now_ns);
        return;
    }
    if (task_state_ == TaskState::kIdle ||
        task_state_ == TaskState::kDone) {
        return;
    }
    if (!authority_valid_) return;
    if (!AuthorityCore(now_ns)) {
        InvalidateAuthority("authority_not_live", now_ns);
        return;
    }
    if (!VideoFresh(now_ns)) {
        // Dead stream halts the camera now; the grant survives so the
        // parent can retry with a new task once video recovers.
        CancelInflight("video_stale", now_ns);
        Finish(TaskOutcome::kFailed,
               task_state_ == TaskState::kAwaitingAck
                   ? Assessment::kUnavailable
                   : Assessment::kUnclear,
               "task_failed", "video_stale_halts_motion", now_ns);
        return;
    }
    if (now_ns >= task_deadline_ns_) {
        CancelInflight("max_dwell_exceeded", now_ns);
        Finish(TaskOutcome::kFailed, Assessment::kUnavailable, "task_failed",
               "max_dwell_exceeded", now_ns);
        return;
    }
    if (task_state_ == TaskState::kAwaitingAck &&
        now_ns >= ack_deadline_ns_) {
        // Uncertain outcome: the action may already have executed, so a
        // resend could DUPLICATE a physical capture/movement. Resend only
        // under an explicit adapter idempotency guarantee; otherwise fail
        // unknown with no automatic resend.
        const bool idempotent =
            port_ != nullptr &&
            port_->capabilities().idempotent_retry_supported;
        if (idempotent && attempts_ <= config_.max_retries &&
            GrantLive(now_ns) && port_ != nullptr &&
            port_->Request(active_task_id_, authority_generation_,
                            active_intent_)) {
            ++attempts_;
            ack_deadline_ns_ = now_ns + config_.request_timeout_ns;
            char detail[64];
            std::snprintf(detail, sizeof(detail), "attempt_%d", attempts_);
            Append("request_sent", detail, active_task_id_,
                   authority_generation_, now_ns);
            return;
        }
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "ack_timeout_uncertain_no_retry", now_ns);
        return;
    }
    if (task_state_ == TaskState::kAwaitingSettle &&
        now_ns >= settle_deadline_ns_) {
        // ACK may have been accepted, but without fresh settled reality the
        // truth stays failure/unknown — never upgraded by the ACK alone.
        CancelInflight("settle_timeout", now_ns);
        Finish(TaskOutcome::kFailed,
               lens_settled_fresh_ ? Assessment::kUnclear
                                   : Assessment::kUnavailable,
               "task_failed", "settle_timeout_no_fresh_reality", now_ns);
        return;
    }
    if (task_state_ == TaskState::kAwaitingMatch &&
        now_ns >= match_deadline_ns_) {
        Finish(TaskOutcome::kFailed, Assessment::kUnclear, "task_failed",
               "match_timeout", now_ns);
        return;
    }
}

std::string ReobservationController::Snapshot() const {
    std::string out;
    out += "authority_valid=";
    out += (authority_valid_ ? "1" : "0");
    out += " config_ok=";
    out += (config_ok_ ? "1" : "0");
    out += " generation=" + std::to_string(authority_generation_);
    out += " task=" + std::to_string(active_task_id_);
    out += " state=" + std::to_string(static_cast<int>(task_state_));
    out += " outcome=" + std::to_string(static_cast<int>(task_outcome_));
    out += " assessment=" + std::to_string(static_cast<int>(assessment_));
    out += " attempts=" + std::to_string(attempts_);
    out += " history_full=";
    out += (history_full_ ? "1" : "0");
    out += " dropped=" + std::to_string(dropped_records_);
    out += "\n";
    for (const ActionRecord& record : history_) {
        out += std::to_string(record.seq) + " t" +
               std::to_string(record.task_id) + " g" +
               std::to_string(record.generation) + " " + record.kind + " " +
               record.detail + " @" + std::to_string(record.now_monotonic_ns) +
               "\n";
    }
    return out;
}

}  // namespace ksi::safety::observation
