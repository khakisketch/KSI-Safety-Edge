// Unit A autonomous processor: shared tick wiring + bounded persistence.

#include "ksi_safety/observation/autonomous_processor.hpp"

#include <cstdint>
#include <limits>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

namespace ksi::safety {

bool IsValidAutonomousConfig(const AutonomousConfig& config,
                             std::string* error) {
    auto fail = [&](const std::string& reason) {
        if (error != nullptr) *error = reason;
        return false;
    };
    if (!config.shadow_only) return fail("unit A is SHADOW-only");
    if (!IsValidContextRingConfig(config.ring)) {
        return fail("invalid context ring config (finite bounds required)");
    }
    if (config.ring.max_frames < config.ring.pre_frames +
                                     1 + config.ring.post_frames) {
        return fail("ring max_frames must cover a full window span");
    }
    if (config.max_artifact_bytes == 0) return fail("max_artifact_bytes is 0");
    if (config.attention_report_interval_ns < 0) {
        return fail("attention_report_interval_ns is negative");
    }
    if (config.window_min_interval_ns < 0) {
        return fail("window_min_interval_ns is negative");
    }
    if (config.window_min_interval_ns >
        observation::PlannerConfig::kMaxDurationNs) {
        return fail("window_min_interval_ns exceeds the 1-hour duration cap");
    }
    if (config.seal_reserve_bytes == 0) {
        return fail("seal_reserve_bytes is 0 (unsealable store)");
    }
    if (config.store_capacity_bytes != 0 &&
        config.store_capacity_bytes <= config.seal_reserve_bytes) {
        return fail("store capacity must exceed the seal reserve");
    }
    if (config.flight_id.empty()) return fail("flight_id is empty");
    if (config.edge_boot_id.empty()) return fail("edge_boot_id is empty");
    if (!IsValidSourceAttentionPolicy(config.source_attention, error)) return false;
    if (config.source_attention.enabled &&
        (config.evidence_dir.empty() || config.store_capacity_bytes == 0)) {
        return fail("source attention requires a durable evidence directory and finite store capacity");
    }
    return true;
}

AutonomousProcessor::AutonomousProcessor(AutonomousConfig config)
    : config_(std::move(config)),
      ring_(config_.ring, config_.flight_id, config_.edge_boot_id) {
    std::string error;
    if (!IsValidAutonomousConfig(config_, &error)) {
        valid_ = false;
        error_ = error;
        return;
    }
    if (!ring_.valid()) {
        valid_ = false;
        error_ = ring_.error();
        return;
    }
    if (!config_.evidence_dir.empty()) {
        store_ = new EvidenceStore(config_.evidence_dir,
                                   config_.store_capacity_bytes,
                                   config_.store_hooks);
        if (!store_->ok()) {
            valid_ = false;
            error_ = "evidence store is not usable: " + store_->lastError();
            delete store_;
            store_ = nullptr;
            return;
        }
    }
    valid_ = true;
    ResetSourceAttention();
}

AutonomousProcessor::~AutonomousProcessor() { delete store_; }

void AutonomousProcessor::SetEnabled(bool enabled) {
    if (enabled != config_.enabled) {
        ResetSourceAttention();
        ResetWindowTrigger();
    }
    config_.enabled = enabled;
}

void AutonomousProcessor::SetRuntimeConfigIdentity(std::uint64_t epoch,
                                                  const std::string& hash) {
    config_.config_epoch = epoch;
    config_.effective_config_hash = hash;
}

void AutonomousProcessor::SetMissionPolicyRevisions(
    const std::string& mission_revision_id,
    const std::string& policy_revision_id) {
    config_.mission_revision_id = mission_revision_id;
    config_.policy_revision_id = policy_revision_id;
}
void AutonomousProcessor::SetCameraManualOverride(bool override) {
    config_.camera_manual_override = override;
}

void AutonomousProcessor::SetLensId(const std::string& lens_id,
                                    bool switch_observed) {
    config_.lens_id = lens_id;
    // A lens switch resets the spatial lineage for the NEXT assessed tick:
    // its spatial conclusions are withheld (recorded, not merged across the
    // switch), while detection/tracking analysis continues.
    if (switch_observed) {
        lens_switch_pending_ = true;
        ResetSourceAttention();
    }
}

std::string AutonomousProcessor::AssessmentAssetId(
    const std::string& flight_id, const std::string& edge_boot_id,
    std::uint64_t tick_index) {
    return Uuid5(MopProtocol::Namespace(), "assess|" + flight_id + "|" +
                                               edge_boot_id + "|" +
                                               std::to_string(tick_index));
}

std::string AutonomousProcessor::WindowFrameAssetId(
    const std::string& flight_id, const std::string& edge_boot_id,
    std::uint64_t trigger_tick, std::uint64_t frame_tick) {
    return Uuid5(MopProtocol::Namespace(),
                 "win|" + flight_id + "|" + edge_boot_id + "|" +
                     std::to_string(trigger_tick) + "|" +
                     std::to_string(frame_tick));
}

std::string AutonomousProcessor::CoverageAssetId(
    const std::string& flight_id, const std::string& edge_boot_id,
    std::int64_t sealed_mono_ns) {
    return Uuid5(MopProtocol::Namespace(),
                 "coverage|" + flight_id + "|" + edge_boot_id + "|" +
                     std::to_string(sealed_mono_ns));
}

void AutonomousProcessor::LatchWriteFailure(const std::string& reason) {
    if (coverage_.write_failure.empty()) coverage_.write_failure = reason;
    ++coverage_.writes_refused;
}

void AutonomousProcessor::SaturateEvidence(const std::string& reason) {
    if (coverage_.evidence_saturated) return;
    coverage_.evidence_saturated = true;
    coverage_.saturation_reason = reason;
}

void AutonomousProcessor::PreflightEvidenceCapacity(std::uint32_t width,
                                                    std::uint32_t height,
                                                    std::size_t rgb_size) {
    if (capacity_preflight_done_) return;
    // 0 = unbounded (host harness): nothing to preflight.
    if (config_.store_capacity_bytes == 0) {
        capacity_preflight_done_ = true;
        return;
    }
    std::size_t frame_bytes = rgb_size;
    if (frame_bytes == 0 && width != 0 && height != 0 &&
        static_cast<std::size_t>(width) <=
            std::numeric_limits<std::size_t>::max() / 3 / height) {
        frame_bytes = static_cast<std::size_t>(width) * height * 3;
    }
    // No frame yet: decide on the first tick that actually carries source
    // bytes, so a byte-less diagnostic tick cannot skip the check forever.
    if (frame_bytes == 0) return;
    capacity_preflight_done_ = true;
    const std::uint64_t span =
        static_cast<std::uint64_t>(config_.ring.pre_frames) + 1 +
        config_.ring.post_frames;
    const std::uint64_t reserve = config_.seal_reserve_bytes;
    if (span != 0 &&
        static_cast<std::uint64_t>(frame_bytes) <=
            std::numeric_limits<std::uint64_t>::max() / span &&
        config_.store_capacity_bytes <
            static_cast<std::uint64_t>(frame_bytes) * span + reserve) {
        SaturateEvidence("evidence_capacity_below_one_window");
    }
}

bool AutonomousProcessor::WriteArtifact(const std::string& asset_id,
                                        const std::string& media_type,
                                        const std::string& observation_id,
                                        std::uint64_t frame_id,
                                        std::int64_t recv_ns,
                                        const std::uint8_t* bytes,
                                        std::size_t len,
                                        ArtifactInfo* info) {
    ArtifactInfo local;
    local.asset_id = asset_id;
    local.media_type = media_type;
    local.size_bytes = static_cast<std::uint64_t>(len);
    if (coverage_.evidence_saturated) {
        // Sticky: no further filesystem work, no retry storm, no eviction of
        // sealed bytes. The gap is already reported (coverage + run facts).
        local.write_status = EvidenceStatus::kFull;
        ++coverage_.evidence_refusals;
        if (info != nullptr) *info = local;
        return false;
    }
    if (store_ == nullptr) {
        local.write_status = EvidenceStatus::kIoError;
        LatchWriteFailure("evidence store is not configured (volatile only)");
        if (info != nullptr) *info = local;
        return false;
    }
    // Keep the seal reserve free: admission stops BEFORE the store's hard
    // bound so the closing revision can still list (and prove) what was kept.
    if (config_.store_capacity_bytes != 0 &&
        config_.seal_reserve_bytes < config_.store_capacity_bytes) {
        const std::uint64_t usable =
            config_.store_capacity_bytes - config_.seal_reserve_bytes;
        const std::uint64_t used = store_->bytesUsed();
        if (used >= usable || static_cast<std::uint64_t>(len) > usable - used) {
            SaturateEvidence("store_capacity_reserved_headroom_reached");
            local.write_status = EvidenceStatus::kFull;
            ++coverage_.evidence_refusals;
            if (info != nullptr) *info = local;
            return false;
        }
    }
    if (len > config_.max_artifact_bytes &&
        media_type != kMediaSourceFrame && media_type != kMediaSourceRoiV1) {
        local.write_status = EvidenceStatus::kInvalid;
        LatchWriteFailure("artifact exceeds max_artifact_bytes: " + asset_id);
        if (info != nullptr) *info = local;
        return false;
    }
    SourceAsset asset;
    asset.asset_id = asset_id;
    asset.sha256_hex = Sha256Hex(bytes, len);
    asset.size_bytes = static_cast<std::uint64_t>(len);
    asset.media_type = media_type;
    asset.flight_id = config_.flight_id;
    asset.edge_boot_id = config_.edge_boot_id;
    asset.frame_id = frame_id;
    asset.received_monotonic_ns = recv_ns;
    asset.observation_id = observation_id;
    const EvidenceStatus status = store_->PutSourceAsset(asset, bytes, len);
    local.sha256_hex = asset.sha256_hex;
    local.write_status = status;
    if (status == EvidenceStatus::kOk) {
        artifacts_.push_back(local);
        written_ids_.push_back(asset_id);
    } else if (status == EvidenceStatus::kDuplicate) {
        ++coverage_.idempotent_duplicates;
        // Idempotent re-write (e.g. reprocessed tick after a restart with
        // identical bytes): not a failure, still addressable.
        ArtifactInfo existing;
        for (const auto& known : artifacts_) {
            if (known.asset_id == asset_id) {
                existing = known;
                break;
            }
        }
        if (existing.asset_id.empty()) {
            existing = local;
            existing.write_status = EvidenceStatus::kDuplicate;
            artifacts_.push_back(existing);
            written_ids_.push_back(asset_id);
        }
    } else if (status == EvidenceStatus::kConflict) {
        ++coverage_.digest_conflicts;
        LatchWriteFailure("digest conflict (same id, different bytes): " +
                          asset_id);
    } else if (status == EvidenceStatus::kFull) {
        // Capacity is a bounded, expected end state for an OPTIONAL record:
        // stop admitting artifacts, keep observing, and report the gap.
        SaturateEvidence("store_capacity_exhausted");
        local.write_status = EvidenceStatus::kFull;
        ++coverage_.evidence_refusals;
    } else {
        LatchWriteFailure("evidence store write failed (" +
                          std::string(EvidenceStatusName(status)) +
                          "): " + asset_id);
    }
    if (info != nullptr) *info = local;
    return status == EvidenceStatus::kOk ||
           status == EvidenceStatus::kDuplicate;
}

AssessmentContext AutonomousProcessor::MakeContext() const {
    AssessmentContext context;
    context.flight_id = config_.flight_id;
    context.edge_boot_id = config_.edge_boot_id;
    context.stream_id = config_.stream_id;
    context.mission_revision_id = config_.mission_revision_id;
    context.policy_revision_id = config_.policy_revision_id;
    context.effective_config_hash = config_.effective_config_hash;
    context.config_epoch = config_.config_epoch;
    context.lens_id = config_.lens_id;
    context.lens_switch_observed = lens_switch_pending_;
    context.camera_manual_override = config_.camera_manual_override;
    context.simulated_input = config_.simulated_input;
    return context;
}

void AutonomousProcessor::ResetWindowTrigger() {
    prev_fresh_confirmed_.clear();
    prev_detection_status_ = DetectionStatus::kNotRun;
    last_window_open_ns_ = -1;
}

void AutonomousProcessor::PersistClosedWindow(const CaptureWindow& window,
                                              const TickRecord* current_tick,
                                              ArtifactInfo* current_frame) {
    if (window.complete) {
        ++coverage_.windows_complete;
    } else {
        ++coverage_.windows_partial;
    }
    std::vector<WindowFrameRef> refs = window.before;
    refs.push_back(window.during);
    for (const auto& ref : window.after) refs.push_back(ref);
    for (const auto& ref : refs) {
        if (!ref.present) continue;
        const RingFrame* retained_frame =
            ring_.FindRetainedFrame(ref.tick_index);
        if (retained_frame == nullptr || retained_frame->bytes.empty()) {
            ++coverage_.writes_refused;
            continue;
        }
        const std::string frame_asset = WindowFrameAssetId(
            config_.flight_id, config_.edge_boot_id, window.trigger_tick,
            ref.tick_index);
        ArtifactInfo frame_info;
        WriteArtifact(frame_asset, kMediaSourceFrame, "", ref.frame_id,
                      ref.receive_mono_ns, retained_frame->bytes.data(),
                      retained_frame->bytes.size(), &frame_info);
        if (frame_info.write_status == EvidenceStatus::kOk) {
            ++coverage_.window_frames_written;
        }
        // Same-tick only: these retained bytes were copied from OnTick's RGB
        // input. Reuse their already-committed digest, never an old frame's.
        if (current_tick != nullptr && current_frame != nullptr &&
            ref.tick_index == current_tick->tick_index && ref.frame_id == current_tick->frame_id &&
            ref.receive_mono_ns == current_tick->receive_mono_ns &&
            (frame_info.write_status == EvidenceStatus::kOk ||
             frame_info.write_status == EvidenceStatus::kDuplicate)) {
            *current_frame = frame_info;
        }
    }
}

SharedTickResult AutonomousProcessor::OnTick(const TickRecord& record,
                                             const std::uint8_t* rgb,
                                             std::size_t rgb_size,
                                             std::uint32_t width,
                                             std::uint32_t height,
                                             const FrameContext* source_context) {
    SharedTickResult result;
    result.runner_processed = true;
    if (!enabled()) return result;
    result.autonomy_observed = true;
    ++coverage_.processed_ticks;
    PreflightEvidenceCapacity(width, height, rgb_size);
    switch (record.detection_status) {
        case DetectionStatus::kOk:
            ++coverage_.ok_ticks;
            break;
        case DetectionStatus::kNotRun:
            ++coverage_.notrun_ticks;
            break;
        case DetectionStatus::kInferenceFailure:
            ++coverage_.failure_ticks;
            break;
    }

    AssessmentContext context = MakeContext();
    result.assessment = BuildAssessment(record, context);
    if (result.assessment.disposition == AssessmentDisposition::kAssessed) {
        ++coverage_.assessments_assessed;
    } else {
        ++coverage_.assessments_undetermined;
    }
    lens_switch_pending_ = false;

    // Retain the source bytes (zero-detection frames included — a clean
    // negative next to a candidate is evidence about that candidate).
    RingFrame retained;
    retained.tick_index = record.tick_index;
    retained.frame_id = record.frame_id;
    retained.receive_mono_ns = record.receive_mono_ns;
    retained.detection_status = record.detection_status;
    retained.has_detection = !record.detections.empty();
    retained.lens_id = config_.lens_id;
    retained.exposure_verified = record.exposure_verified;
    if (rgb != nullptr && rgb_size > 0) {
        retained.bytes.assign(rgb, rgb + rgb_size);
    }
    ring_.Push(retained);
    coverage_.ring_evicted = ring_.evicted_frames();

    // Event-gated window triggers (P1 evidence policy): a window opens only
    // for a NEW event — a freshly observed confirmed lineage that was not
    // fresh on the previous tick (new, returning, or tracker-epoch-changed
    // target), or an inference-failure rising edge. Continuous detection of
    // the same targets opens nothing new, so a steady stream costs one small
    // window per event instead of one full window per frame. Clean negatives
    // join open windows as context without opening their own (otherwise every
    // blank tick would seal a window). Per-tick assessments and ROI attention
    // continue regardless of whether a window opened.
    std::vector<FreshKey> fresh_confirmed;
    for (const auto& item : record.tracks) {
        if (item.track.confirmed &&
            item.provenance == TrackProvenance::kObserved) {
            fresh_confirmed.push_back(FreshKey{item.track.epoch,
                                               item.track.track_id,
                                               item.track.class_id});
        }
    }
    bool new_event = record.detection_status ==
                         DetectionStatus::kInferenceFailure &&
                     prev_detection_status_ !=
                         DetectionStatus::kInferenceFailure;
    for (const auto& key : fresh_confirmed) {
        bool seen_before = false;
        for (const auto& prev : prev_fresh_confirmed_) {
            if (key == prev) {
                seen_before = true;
                break;
            }
        }
        if (!seen_before) {
            new_event = true;
            break;
        }
    }
    prev_fresh_confirmed_ = fresh_confirmed;
    prev_detection_status_ = record.detection_status;
    if (new_event) {
        const bool interval_ok =
            config_.window_min_interval_ns <= 0 || last_window_open_ns_ < 0 ||
            (record.receive_mono_ns >= last_window_open_ns_ &&
             record.receive_mono_ns - last_window_open_ns_ >=
                 config_.window_min_interval_ns);
        if (!interval_ok) {
            // Admission bound, not a silent drop: counted in coverage.
            ++coverage_.windows_merged;
        } else {
            std::string error;
            if (ring_.BeginWindow(record.tick_index, &error)) {
                result.window_opened = true;
                result.window_id = ring_.windows().back().window_id;
                ++coverage_.windows_opened;
                last_window_open_ns_ = record.receive_mono_ns;
            }
        }
    }

    // Persist the assessment artifact (exact bytes the collector exports).
    const std::string json = result.assessment.ToJson();
    const std::string asset_id =
        AssessmentAssetId(config_.flight_id, config_.edge_boot_id,
                          record.tick_index);
    std::string observation_id;
    if (record.detection_status == DetectionStatus::kOk &&
        !record.detections.empty()) {
        observation_id = MopTickEvidence::ObservationIdFor(
            config_.flight_id, config_.edge_boot_id, record.tick_index);
    }
    // No invented link: ticks without fresh detections carry no
    // observation id (empty, not a guess).
    WriteArtifact(asset_id, kMediaAssessmentV1, observation_id,
                  record.frame_id, record.receive_mono_ns,
                  reinterpret_cast<const std::uint8_t*>(json.data()),
                  json.size(), &result.assessment_artifact);
    if (result.assessment_artifact.write_status == EvidenceStatus::kOk) {
        ++coverage_.assessments_written;
    }

    // Newly closed windows persist their PRESENT frames (before/during/
    // after, blanks included) with window-scoped asset ids. Bytes resolve
    // from the volatile ring (the config validation guarantees the ring
    // span covers a full window); a frame evicted before its window closed
    // is re-marked missing here instead of being silently skipped.
    ring_.HealthTick(record.receive_mono_ns);
    // Drain includes post-satisfied closes from the Push above: the ring
    // never closes silently, so every close is counted + persisted here.
    result.windows_closed = ring_.DrainNewlyClosed();
    ArtifactInfo current_frame;
    for (const auto& closed_id : result.windows_closed) {
        CaptureWindow window;
        if (!ring_.FindWindow(closed_id, &window)) continue;
        PersistClosedWindow(window, &record, &current_frame);
    }
    CaptureSourceAttention(record, rgb, rgb_size, width, height, source_context, current_frame, &result);
    result.write_failure = coverage_.write_failure;
    return result;
}

std::vector<std::string> AutonomousProcessor::HealthTick(
    std::int64_t now_mono_ns) {
    if (!enabled()) return {};
    AdvanceSourceAttention(now_mono_ns, false);
    ring_.HealthTick(now_mono_ns);
    std::vector<std::string> closed = ring_.DrainNewlyClosed();
    for (const auto& closed_id : closed) {
        CaptureWindow window;
        if (!ring_.FindWindow(closed_id, &window)) continue;
        PersistClosedWindow(window);
    }
    return closed;
}

void AutonomousProcessor::RequestStop(std::int64_t now_mono_ns) {
    AdvanceSourceAttention(now_mono_ns, true);
    ResetSourceAttention();
    ring_.CloseAllForStop();
    for (const auto& closed_id : ring_.DrainNewlyClosed()) {
        CaptureWindow window;
        if (!ring_.FindWindow(closed_id, &window)) continue;
        PersistClosedWindow(window);
    }
    if (coverage_written_) return;
    const std::string json =
        coverage_.ToJson(config_, ring_.windows());
    ArtifactInfo info;
    WriteArtifact(CoverageAssetId(config_.flight_id, config_.edge_boot_id,
                                  now_mono_ns),
                  kMediaCoverageV1, "", 0, now_mono_ns,
                  reinterpret_cast<const std::uint8_t*>(json.data()),
                  json.size(), &info);
    coverage_written_ = true;
}

void AutonomousProcessor::AbortRuntime() {
    ResetSourceAttention();
    ResetWindowTrigger();
    parked_ = true;
}

std::vector<CaptureWindow> AutonomousProcessor::windows() const {
    return ring_.windows();
}

bool AutonomousProcessor::HasOpenWindows() const {
    return ring_.HasOpenWindows();
}

bool AutonomousProcessor::FindWindow(const std::string& window_id,
                                     CaptureWindow* out) const {
    return ring_.FindWindow(window_id, out);
}

bool AutonomousProcessor::FindWindowForTrigger(std::uint64_t trigger_tick,
                                               CaptureWindow* out) const {
    return ring_.FindWindowForTrigger(trigger_tick, out);
}

bool AutonomousProcessor::Seal(std::string* revision_id_out,
                               std::string* manifest_out,
                               std::string* error) {
    if (store_ == nullptr) {
        if (error != nullptr) *error = "no evidence store configured";
        return false;
    }
    if (artifacts_.empty()) {
        if (error != nullptr) *error = "no autonomous artifacts were written";
        return false;
    }
    std::vector<SourceAsset> required;
    for (const auto& info : artifacts_) {
        SourceAsset asset;
        if (!store_->HasAsset(info.asset_id, &asset)) {
            if (error != nullptr) {
                *error = "artifact missing from store: " + info.asset_id;
            }
            return false;
        }
        required.push_back(asset);
    }
    const std::string revision_id =
        Uuid5(MopProtocol::Namespace(), "rev-auto|" + config_.flight_id +
                                           "|" + config_.edge_boot_id + "|" +
                                           std::to_string(required.size()));
    std::string manifest;
    std::string seal_error;
    // sealed_monotonic_ns: caller data would be ideal; the store takes the
    // value for the manifest only (no clock read inside the store either).
    // Use the last observed tick time so the seal stays a pure function of
    // processed data.
    std::int64_t sealed_ns = 0;
    for (const auto& asset : required) {
        if (asset.received_monotonic_ns > sealed_ns) {
            sealed_ns = asset.received_monotonic_ns;
        }
    }
    if (!store_->SealRevision(revision_id, config_.flight_id,
                              config_.edge_boot_id, required, sealed_ns,
                              &manifest, &seal_error)) {
        if (error != nullptr) *error = seal_error;
        return false;
    }
    if (revision_id_out != nullptr) *revision_id_out = revision_id;
    if (manifest_out != nullptr) *manifest_out = manifest;
    return true;
}

bool AutonomousProcessor::VerifyAll(std::string* error) const {
    if (store_ == nullptr) {
        if (error != nullptr) *error = "no evidence store configured";
        return false;
    }
    for (const auto& info : artifacts_) {
        bool ok = false;
        if (!store_->VerifyAsset(info.asset_id, &ok) || !ok) {
            if (error != nullptr) {
                *error = "asset failed verification: " + info.asset_id;
            }
            return false;
        }
    }
    return true;
}

std::string AutonomousCoverageToJson(const AutonomousConfig& config,
                                     const AutonomousCoverage& coverage,
                                     const std::vector<CaptureWindow>& windows) {
    JsonValue root = JsonValue::Obj();
    root.Set("schema", JsonValue::Str("ksi.coverage/v1"));
    root.Set("flight_id", JsonValue::Str(config.flight_id));
    root.Set("edge_boot_id", JsonValue::Str(config.edge_boot_id));
    root.Set("stream_id", JsonValue::Str(config.stream_id));
    root.Set("mission_revision_id",
             JsonValue::Str(config.mission_revision_id));
    root.Set("policy_revision_id", JsonValue::Str(config.policy_revision_id));
    root.Set("mode", JsonValue::Str("SHADOW"));
    root.Set("simulated_input", JsonValue::Bool(config.simulated_input));
    JsonValue ticks = JsonValue::Obj();
    ticks.Set("processed",
              JsonValue::Int(static_cast<std::int64_t>(coverage.processed_ticks)));
    ticks.Set("ok", JsonValue::Int(static_cast<std::int64_t>(coverage.ok_ticks)));
    ticks.Set("not_run",
              JsonValue::Int(static_cast<std::int64_t>(coverage.notrun_ticks)));
    ticks.Set("inference_failure",
              JsonValue::Int(static_cast<std::int64_t>(coverage.failure_ticks)));
    root.Set("ticks", std::move(ticks));
    JsonValue assessments = JsonValue::Obj();
    assessments.Set("written", JsonValue::Int(static_cast<std::int64_t>(
                                       coverage.assessments_written)));
    assessments.Set("assessed", JsonValue::Int(static_cast<std::int64_t>(
                                        coverage.assessments_assessed)));
    assessments.Set("undetermined",
                    JsonValue::Int(static_cast<std::int64_t>(
                        coverage.assessments_undetermined)));
    root.Set("assessments", std::move(assessments));
    JsonValue window_json = JsonValue::Obj();
    window_json.Set("opened",
                    JsonValue::Int(static_cast<std::int64_t>(coverage.windows_opened)));
    window_json.Set("complete",
                    JsonValue::Int(static_cast<std::int64_t>(coverage.windows_complete)));
    window_json.Set("partial",
                    JsonValue::Int(static_cast<std::int64_t>(coverage.windows_partial)));
    window_json.Set("frames_written",
                    JsonValue::Int(static_cast<std::int64_t>(
                        coverage.window_frames_written)));
    window_json.Set("merged",
                    JsonValue::Int(static_cast<std::int64_t>(
                        coverage.windows_merged)));
    JsonValue detail = JsonValue::Arr();
    for (const auto& window : windows) {
        JsonValue entry = JsonValue::Obj();
        entry.Set("window_id", JsonValue::Str(window.window_id));
        entry.Set("trigger_tick",
                  JsonValue::Int(static_cast<std::int64_t>(window.trigger_tick)));
        entry.Set("closed", JsonValue::Bool(window.closed));
        entry.Set("complete", JsonValue::Bool(window.complete));
        entry.Set("close_reason", JsonValue::Str(window.close_reason));
        entry.Set("wanted_frames",
                  JsonValue::Int(static_cast<std::int64_t>(window.wanted_frames)));
        entry.Set("present_frames",
                  JsonValue::Int(static_cast<std::int64_t>(window.present_frames)));
        JsonValue missing = JsonValue::Arr();
        for (const auto& reason : window.missing_reasons) {
            missing.Push(JsonValue::Str(reason));
        }
        entry.Set("missing_reasons", std::move(missing));
        detail.Push(std::move(entry));
    }
    window_json.Set("windows", std::move(detail));
    root.Set("windows", std::move(window_json));
    root.Set("ring_evicted",
             JsonValue::Int(static_cast<std::int64_t>(coverage.ring_evicted)));
    root.Set("writes_refused",
             JsonValue::Int(static_cast<std::int64_t>(coverage.writes_refused)));
    root.Set("digest_conflicts",
             JsonValue::Int(static_cast<std::int64_t>(coverage.digest_conflicts)));
    root.Set("idempotent_duplicates",
             JsonValue::Int(static_cast<std::int64_t>(
                 coverage.idempotent_duplicates)));
    // Distinct from write_failure: the record is incomplete because the
    // declared capacity ended, and observation was NOT paused for it.
    JsonValue evidence = JsonValue::Obj();
    evidence.Set("saturated", JsonValue::Bool(coverage.evidence_saturated));
    evidence.Set("reason", JsonValue::Str(coverage.saturation_reason));
    evidence.Set("refused_artifacts",
                 JsonValue::Int(static_cast<std::int64_t>(
                     coverage.evidence_refusals)));
    evidence.Set("observation_continues", JsonValue::Bool(true));
    evidence.Set("sealed_bytes_evicted", JsonValue::Bool(false));
    evidence.Set("attention_reports_suppressed",
                 JsonValue::Int(static_cast<std::int64_t>(
                     coverage.attention_reports_suppressed)));
    root.Set("evidence", std::move(evidence));
    root.Set("write_failure", JsonValue::Str(coverage.write_failure));
    // Fixed zeros + custody, same contract as the assessment envelope.
    JsonValue outputs = JsonValue::Obj();
    outputs.Set("incidents_created", JsonValue::Int(0));
    outputs.Set("alerts_created", JsonValue::Int(0));
    outputs.Set("human_responses", JsonValue::Int(0));
    outputs.Set("camera_commands", JsonValue::Int(0));
    outputs.Set("flight_commands", JsonValue::Int(0));
    root.Set("outputs", std::move(outputs));
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

std::string AutonomousCoverage::ToJson(
    const AutonomousConfig& config,
    const std::vector<CaptureWindow>& windows) const {
    return AutonomousCoverageToJson(config, *this, windows);
}

SharedTickResult ProcessSharedTick(SessionRunner& runner,
                                   AutonomousProcessor& processor,
                                   const FramePacket& frame,
                                   const FrameContext& ctx,
                                   DetectorPort* detector,
                                   const std::uint8_t* rgb,
                                   std::size_t rgb_size) {
    SharedTickResult result;
    const std::uint64_t before = runner.processed_frame_count();
    const TickRecord record = runner.ProcessFrame(frame, ctx, detector);
    result.assessment = Assessment{};
    if (runner.processed_frame_count() == before) {
        // Gate closed (no SESSION_START / after SESSION_STOP) or a latched
        // durable failure paused work: the frame was dropped unprocessed, so
        // autonomy observes nothing (no invented tick, no counter).
        result.runner_processed = false;
        return result;
    }
    if (runner.config_applied()) {
        processor.SetRuntimeConfigIdentity(runner.config_epoch(), runner.effective_config_hash());
    }
    result = processor.OnTick(record, rgb, rgb_size, frame.metadata.width,
                              frame.metadata.height, &ctx);
    result.record = record;
    result.runner_processed = true;
    if (processor.hasWriteFailure()) {
        // Truthful degradation: refused evidence must be visible on the run
        // facts, never a silent gap beside a healthy-looking run. Integrity /
        // I/O faults stay fatal: new observation work stops.
        runner.NoteDurableFailure(processor.coverage().write_failure);
        result.write_failure = processor.coverage().write_failure;
    } else if (processor.hasEvidenceSaturation()) {
        // Capacity is a bounded, expected end state of an OPTIONAL record.
        // Observation keeps running; the gap is sticky and explicit.
        runner.NoteEvidenceSaturation(processor.saturationReason());
    }
    return result;
}

}  // namespace ksi::safety
