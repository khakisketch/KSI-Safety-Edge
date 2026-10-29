#include "ksi_safety/transport/mop_evidence.hpp"

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_link.hpp"

namespace ksi::safety {

namespace {

JsonValue BboxToJson(const std::vector<double>& bbox) {
    JsonValue::Array array;
    array.reserve(bbox.size());
    for (const double value : bbox) {
        array.push_back(JsonValue::Double(value));
    }
    return JsonValue::Arr(std::move(array));
}

JsonValue StringsToJson(const std::vector<std::string>& values) {
    JsonValue::Array array;
    array.reserve(values.size());
    for (const auto& value : values) {
        array.push_back(JsonValue::Str(value));
    }
    return JsonValue::Arr(std::move(array));
}

}  // namespace

JsonValue MakeObservationPayload(const MopObservationInput& input) {
    JsonValue observation = JsonValue::Obj();
    observation.Set("schema_version", JsonValue::Int(1));
    observation.Set("observation_id", JsonValue::Str(input.observation_id));
    observation.Set("flight_id", JsonValue::Str(input.flight_id));
    observation.Set("mission_revision_id",
                    JsonValue::Str(input.mission_revision_id));
    observation.Set("edge_boot_id", JsonValue::Str(input.edge_boot_id));
    observation.Set("source_kind", JsonValue::Str(input.source_kind));
    observation.Set("edge_frame_seq", JsonValue::Int(input.edge_frame_seq));
    observation.Set("edge_frame_id", JsonValue::Str(input.edge_frame_id));
    observation.Set("lens_id", JsonValue::Str(input.lens_id));
    observation.Set("frame_received_monotonic_ns",
                    JsonValue::Int(input.frame_received_monotonic_ns));
    observation.Set("model_digest", JsonValue::Str(input.model_digest));
    observation.Set("sync_quality", JsonValue::Str(input.sync_quality));
    observation.Set("geometry_status", JsonValue::Str(input.geometry_status));
    JsonValue::Array telemetry;
    telemetry.reserve(input.telemetry_refs.size());
    for (const auto& ref : input.telemetry_refs) {
        JsonValue item = JsonValue::Obj();
        item.Set("topic", JsonValue::Str(ref.topic));
        item.Set("source_timestamp_ns", JsonValue::Null());
        item.Set("edge_receive_monotonic_ns",
                 JsonValue::Int(ref.edge_receive_monotonic_ns));
        item.Set("sample_age_ms", JsonValue::Double(ref.sample_age_ms));
        item.Set("timestamp_basis", JsonValue::Str(ref.timestamp_basis));
        item.Set("quality", JsonValue::Str(ref.quality));
        telemetry.push_back(std::move(item));
    }
    observation.Set("telemetry_refs", JsonValue::Arr(std::move(telemetry)));
    JsonValue::Array detections;
    detections.reserve(input.detections.size());
    for (const auto& detection : input.detections) {
        JsonValue item = JsonValue::Obj();
        item.Set("detection_id", JsonValue::Str(detection.detection_id));
        item.Set("class_id", JsonValue::Str(detection.class_id));
        item.Set("score", JsonValue::Double(detection.score));
        item.Set("bbox_xyxy_px", BboxToJson(detection.bbox_xyxy_px));
        item.Set("track_id", detection.has_track_id
                                 ? JsonValue::Str(detection.track_id)
                                 : JsonValue::Null());
        item.Set("position_wgs84", JsonValue::Null());
        detections.push_back(std::move(item));
    }
    observation.Set("detections", JsonValue::Arr(std::move(detections)));
    observation.Set("quality_reasons", StringsToJson(input.quality_reasons));
    observation.Set("zone_revision_ids", JsonValue::Arr());
    return observation;
}

JsonValue MakeDetectionEventPayload(const MopDetectionEventInput& input) {
    JsonValue event = JsonValue::Obj();
    event.Set("schema_version", JsonValue::Int(1));
    event.Set("event_id", JsonValue::Str(input.event_id));
    event.Set("flight_id", JsonValue::Str(input.flight_id));
    event.Set("observation_id", JsonValue::Str(input.observation_id));
    event.Set("episode_id", JsonValue::Str(input.episode_id));
    event.Set("event_type", JsonValue::Str(input.event_type));
    event.Set("mode", JsonValue::Str(input.mode));
    event.Set("detected_at_edge_monotonic_ns",
              JsonValue::Int(input.detected_at_edge_monotonic_ns));
    event.Set("zone_revision_id", input.has_zone_revision_id
                                      ? JsonValue::Str(input.zone_revision_id)
                                      : JsonValue::Null());
    event.Set("policy_revision_id", JsonValue::Str(input.policy_revision_id));
    event.Set("evidence_artifact_ids",
              StringsToJson(input.evidence_artifact_ids));
    JsonValue model = JsonValue::Obj();
    model.Set("rule_id", JsonValue::Str(input.initial_model_output.rule_id));
    model.Set("episode", JsonValue::Int(input.initial_model_output.episode));
    model.Set("distance_m",
              JsonValue::Double(input.initial_model_output.distance_m));
    model.Set("geometry", JsonValue::Str(input.initial_model_output.geometry));
    model.Set("synthetic_replay_geometry",
              JsonValue::Bool(input.initial_model_output.synthetic_replay_geometry));
    event.Set("initial_model_output", std::move(model));
    event.Set("observation_quality", JsonValue::Str(input.observation_quality));
    event.Set("mission_snapshot_hash",
              JsonValue::Str(input.mission_snapshot_hash));
    return event;
}

JsonValue MakeFlightSummaryPayload(const MopFlightSummaryInput& input) {
    JsonValue summary = JsonValue::Obj();
    summary.Set("schema_version", JsonValue::Int(1));
    summary.Set("summary_id", JsonValue::Str(input.summary_id));
    summary.Set("flight_id", JsonValue::Str(input.flight_id));
    summary.Set("edge_boot_id", JsonValue::Str(input.edge_boot_id));
    summary.Set("session_id", JsonValue::Str(input.session_id));
    summary.Set("started_at_edge_monotonic_ns",
                JsonValue::Int(input.started_at_edge_monotonic_ns));
    summary.Set("ended_at_edge_monotonic_ns",
                JsonValue::Int(input.ended_at_edge_monotonic_ns));
    summary.Set("ai_uptime_ratio", JsonValue::Double(input.ai_uptime_ratio));
    summary.Set("covered_frames", JsonValue::Int(input.covered_frames));
    summary.Set("skipped_frames", JsonValue::Int(input.skipped_frames));
    summary.Set("gaps", JsonValue::Arr());
    summary.Set("min_verified_person_equipment_distance_m", JsonValue::Null());
    summary.Set("person_count_max", JsonValue::Int(input.person_count_max));
    summary.Set("equipment_count_max",
                JsonValue::Int(input.equipment_count_max));
    summary.Set("quality_reasons", StringsToJson(input.quality_reasons));
    summary.Set("created_at_edge_monotonic_ns",
                JsonValue::Int(input.created_at_edge_monotonic_ns));
    return summary;
}

JsonValue MakeHeartbeatPayload(
    double fps, bool has_applied_bundle,
    const std::string& applied_bundle_sha256,
    const std::vector<std::pair<std::string, std::vector<std::int64_t>>>&
        unrecoverable_gaps,
    const std::string& edge_boot_id) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("fps", JsonValue::Double(fps));
    payload.Set("applied_bundle_sha256",
                has_applied_bundle ? JsonValue::Str(applied_bundle_sha256)
                                   : JsonValue::Null());
    JsonValue::Array gaps;
    gaps.reserve(unrecoverable_gaps.size());
    for (const auto& gap : unrecoverable_gaps) {
        JsonValue item = JsonValue::Obj();
        item.Set("channel", JsonValue::Str(gap.first));
        JsonValue::Array seqs;
        seqs.reserve(gap.second.size());
        for (const auto seq : gap.second) {
            seqs.push_back(JsonValue::Int(seq));
        }
        item.Set("seqs", JsonValue::Arr(std::move(seqs)));
        gaps.push_back(std::move(item));
    }
    payload.Set("unrecoverable_gaps", JsonValue::Arr(std::move(gaps)));
    payload.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    return payload;
}

JsonValue MakeArtifactAnnouncePayload(const std::string& artifact_id,
                                      const std::string& flight_id,
                                      const std::string& source_kind,
                                      const std::string& sha256,
                                      std::int64_t size_bytes,
                                      const std::string& media_type,
                                      std::int64_t segment_index,
                                      bool required,
                                      const std::string& edge_boot_id) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("artifact_id", JsonValue::Str(artifact_id));
    payload.Set("flight_id", JsonValue::Str(flight_id));
    payload.Set("source_kind", JsonValue::Str(source_kind));
    payload.Set("sha256", JsonValue::Str(sha256));
    payload.Set("size_bytes", JsonValue::Int(size_bytes));
    payload.Set("media_type", JsonValue::Str(media_type));
    payload.Set("segment_index", JsonValue::Int(segment_index));
    payload.Set("required", JsonValue::Bool(required));
    payload.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    return payload;
}

JsonValue MakeArtifactChunkPayload(const std::string& artifact_id,
                                   std::int64_t offset,
                                   const std::uint8_t* data, std::size_t len) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("artifact_id", JsonValue::Str(artifact_id));
    payload.Set("offset", JsonValue::Int(offset));
    payload.Set("data_b64", JsonValue::Str(Base64Encode(data, len)));
    return payload;
}

void EmitAssessmentArtifact(MopEdgeEndpoint* endpoint,
                            const std::string& asset_id,
                            const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& assessment_json) {
    if (endpoint == nullptr) return;
    // Explicit identity (not the endpoint's cached one): the caller passes
    // the LIVE identity the assessment was sealed under, so a stale label
    // can never be stamped here. Emits the announce + chunk envelopes of
    // the existing artifact plane.
    endpoint->EmitArtifactAs(asset_id, kAssessmentSourceKind,
                             kAssessmentMediaType,
                             /*segment_index=*/0, /*required=*/false,
                             assessment_json, flight_id, edge_boot_id);
}

void EmitAssessmentArtifact(MopLink* link, const std::string& asset_id,
                            const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& assessment_json) {
    if (link == nullptr) return;
    link->WithEndpoint([&](MopEdgeEndpoint* endpoint) {
        endpoint->EmitArtifactAs(asset_id, kAssessmentSourceKind,
                                 kAssessmentMediaType,
                                 /*segment_index=*/0, /*required=*/false,
                                 assessment_json, flight_id, edge_boot_id);
    });
}

bool AssessmentEmitAllowed(const AssessmentEmitGate& gate) {
    return gate.flight_confirmed && gate.mission_installed &&
           gate.spool_writable;
}

}  // namespace ksi::safety
