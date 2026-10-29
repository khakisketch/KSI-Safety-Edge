#include "ksi_safety/transport/mop_tick_evidence.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/perception/tracker.hpp"
#include "ksi_safety/risk/engine.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_link.hpp"

namespace ksi::safety {

namespace {

// COCO NATIVE label names for the classes the corpus records (bridge map:
// 0 person / 2 car / 7 truck). Other ids stay raw ("coco-<id>") — no
// ontology mapping is invented here.
std::string ClassLabel(const Detection& detection) {
    if (!detection.class_name.empty()) return detection.class_name;
    switch (detection.class_id) {
        case 0: return "person";
        case 2: return "car";
        case 7: return "truck";
        default: break;
    }
    return "coco-" + std::to_string(detection.class_id);
}

std::string TelemetryQuality(PoseQuality quality) {
    switch (quality) {
        case PoseQuality::kSourceVerified: return "VALID";
        case PoseQuality::kReceiveNearest: return "DEGRADED";
        case PoseQuality::kNone: return "UNKNOWN";
    }
    return "UNKNOWN";  // unreachable; keeps -Wswitch exhaustive
}

bool BoxEquals(const Track& track, const Detection& detection) {
    return track.box.x_min == detection.x_min &&
           track.box.y_min == detection.y_min &&
           track.box.x_max == detection.x_max &&
           track.box.y_max == detection.y_max;
}

}  // namespace

bool EvidenceContextFromBundle(const JsonValue& bundle, MopEvidenceContext* out,
                               std::string* error) {
    auto fail = [&](const char* what) {
        if (error != nullptr) *error = what;
        return false;
    };
    if (!bundle.IsObject()) return fail("bundle must be an object");
    const JsonValue* snapshot = bundle.Find("mission_snapshot");
    if (snapshot == nullptr || !snapshot->IsObject()) {
        return fail("mission_snapshot missing");
    }
    const JsonValue* revision = snapshot->Find("mission_revision_id");
    if (revision == nullptr || !revision->IsString() ||
        revision->strValue().empty()) {
        return fail("mission_snapshot.mission_revision_id missing");
    }
    const JsonValue* content = snapshot->Find("content_sha256");
    if (content == nullptr || !content->IsString() ||
        content->strValue().empty()) {
        return fail("mission_snapshot.content_sha256 missing");
    }
    const JsonValue* policy = bundle.Find("risk_policy");
    if (policy == nullptr || !policy->IsObject()) {
        return fail("risk_policy missing");
    }
    const JsonValue* policy_revision = policy->Find("revision_id");
    if (policy_revision == nullptr || !policy_revision->IsString() ||
        policy_revision->strValue().empty()) {
        return fail("risk_policy.revision_id missing");
    }
    if (out != nullptr) {
        out->mission_revision_id = revision->strValue();
        out->mission_snapshot_hash = content->strValue();
        out->policy_revision_id = policy_revision->strValue();
    }
    return true;
}

MopTickEvidence::MopTickEvidence(MopEvidenceContext context)
    : context_(std::move(context)) {}

void MopTickEvidence::SetContext(MopEvidenceContext context) {
    context_ = std::move(context);
    observations_ = 0;
    frames_ = 0;
    ok_frames_ = 0;
    first_recv_ns_ = 0;
    last_recv_ns_ = 0;
    person_max_ = 0;
    equipment_max_ = 0;
    any_sync_degraded_ = false;
    durable_gap_ = false;
}

std::string MopTickEvidence::SessionId(const std::string& flight_id,
                                       const std::string& edge_boot_id) {
    return Uuid5(MopProtocol::Namespace(),
                 "session|" + flight_id + "|" + edge_boot_id);
}

std::string MopTickEvidence::SyncQuality(const TickRecord& record) const {
    const bool attitude_verified =
        record.attitude_quality == PoseQuality::kSourceVerified;
    const bool position_verified =
        record.position_quality == PoseQuality::kSourceVerified;
    if (attitude_verified && position_verified) return "VALID";
    if (record.attitude_quality == PoseQuality::kNone &&
        record.position_quality == PoseQuality::kNone) {
        return "UNKNOWN";
    }
    return "DEGRADED";
}

std::string MopTickEvidence::GeometryStatus(const TickRecord& record) const {
    return record.spatial_status == SpatialTickReason::kEstimated
               ? "UNVERIFIED"   // GEO_ESTIMATE only — never server-VERIFIED
               : "WITHHELD";
}

std::string MopTickEvidence::ObservationIdFor(const std::string& flight_id,
                                               const std::string& edge_boot_id,
                                               std::uint64_t tick_index) {
    return Uuid5(MopProtocol::Namespace(),
                 "obs|" + flight_id + "|" + edge_boot_id + "|" +
                     std::to_string(tick_index));
}

std::string MopTickEvidence::ObservationId(const TickRecord& record) const {
    return ObservationIdFor(context_.flight_id, context_.edge_boot_id,
                            record.tick_index);
}

MopTickEvidence::TickPayloads MopTickEvidence::BuildTick(
    const TickRecord& record) {
    TickPayloads payloads;
    ++frames_;
    const std::int64_t recv = static_cast<std::int64_t>(record.receive_mono_ns);
    if (frames_ == 1) first_recv_ns_ = recv;
    last_recv_ns_ = recv;

    const std::string quality = SyncQuality(record);
    if (quality != "VALID") any_sync_degraded_ = true;

    if (record.detection_status == DetectionStatus::kOk) {
        ++ok_frames_;
        // Person / equipment maxima over detection ticks (COCO ids:
        // person = 0; equipment = 2 | 7 — the bridge's counting rule).
        std::int64_t persons = 0;
        std::int64_t equipment = 0;
        for (const auto& detection : record.detections) {
            if (detection.class_id == 0) ++persons;
            if (detection.class_id == 2 || detection.class_id == 7) {
                ++equipment;
            }
        }
        person_max_ = std::max(person_max_, persons);
        equipment_max_ = std::max(equipment_max_, equipment);

        MopObservationInput observation;
        observation.observation_id = ObservationId(record);
        observation.flight_id = context_.flight_id;
        observation.mission_revision_id = context_.mission_revision_id;
        observation.edge_boot_id = context_.edge_boot_id;
        observation.edge_frame_seq =
            static_cast<std::int64_t>(record.tick_index);
        observation.edge_frame_id = std::to_string(record.frame_id);
        observation.lens_id = context_.lens_id;
        observation.frame_received_monotonic_ns = recv;
        std::string digest = record.model_digest;
        const std::string kPrefix = "sha256:";
        if (digest.rfind(kPrefix, 0) == 0) {
            digest = digest.substr(kPrefix.size());
        }
        observation.model_digest = digest;
        observation.sync_quality = quality;
        observation.geometry_status = GeometryStatus(record);
        struct TelemetrySpec {
            const char* topic;
            PoseQuality quality;
            std::int64_t gap_ns;
        };
        const TelemetrySpec kTelemetry[] = {
            {"aircraft_attitude", record.attitude_quality,
             record.attitude_gap_ns},
            {"aircraft_position", record.position_quality,
             record.position_gap_ns},
        };
        for (const auto& spec : kTelemetry) {
            MopTelemetryRef ref;
            ref.topic = spec.topic;
            ref.edge_receive_monotonic_ns = recv;
            ref.sample_age_ms =
                std::abs(static_cast<double>(spec.gap_ns)) / 1e6;
            ref.timestamp_basis = "PSDK_LOCAL_RECEIVE";
            ref.quality = TelemetryQuality(spec.quality);
            observation.telemetry_refs.push_back(std::move(ref));
        }
        // Track association: OBSERVED tracks carry this tick's measurement;
        // the bridge matched by exact box equality — deterministic, no
        // fuzzy matching (a missed match stays null, never guessed).
        std::size_t index = 0;
        for (const auto& detection : record.detections) {
            MopDetectionItem item;
            item.detection_id = Uuid5(
                MopProtocol::Namespace(),
                "det|" + context_.flight_id + "|" + context_.edge_boot_id +
                    "|" + std::to_string(record.tick_index) + "|" +
                    std::to_string(index));
            item.class_id = ClassLabel(detection);
            item.score = static_cast<double>(detection.score);
            item.bbox_xyxy_px = {static_cast<double>(detection.x_min),
                                 static_cast<double>(detection.y_min),
                                 static_cast<double>(detection.x_max),
                                 static_cast<double>(detection.y_max)};
            for (const auto& track : record.tracks) {
                if (track.provenance != TrackProvenance::kObserved) continue;
                if (track.track.class_id != detection.class_id) continue;
                if (!BoxEquals(track.track, detection)) continue;
                item.track_id = "trk-" + std::to_string(track.track.track_id);
                item.has_track_id = true;
                break;
            }
            observation.detections.push_back(std::move(item));
            ++index;
        }
        if (!record.exposure_verified) {
            observation.quality_reasons.emplace_back(
                "UNVERIFIED_FRAME_CAPTURE_TIME");
        }
        if (quality != "VALID") {
            observation.quality_reasons.emplace_back("TELEMETRY_SYNC_" +
                                                     quality);
        }
        payloads.has_observation = true;
        payloads.observation = MakeObservationPayload(observation);
        ++observations_;
    }
    // kNotRun / kInferenceFailure: no observation (no scene evidence); the
    // summary still counts the frame as skipped below.

    // Only CREATED episode events become DETECTION_EVENT messages (the
    // validated bridge rule); UPDATE/CLOSE stay inside the session record.
    const auto collect_events = [&](const std::vector<RuleOutput>& outputs,
                                    const char* event_type) {
        for (const auto& output : outputs) {
            for (const auto& event : output.events) {
                if (event.kind != EpisodeEventKind::kCreated) continue;
                MopDetectionEventInput input;
                input.event_id = Uuid5(
                    MopProtocol::Namespace(),
                    "event|" + context_.flight_id + "|" +
                        context_.edge_boot_id + "|" + event.rule_id + "|" +
                        std::to_string(event.episode_id));
                input.flight_id = context_.flight_id;
                input.observation_id = ObservationId(record);
                input.episode_id = Uuid5(
                    MopProtocol::Namespace(),
                    "episode|" + context_.flight_id + "|" +
                        context_.edge_boot_id + "|" + event.rule_id + "|" +
                        std::to_string(event.episode_id));
                input.event_type = event_type;
                input.mode = event.is_shadow ? "SHADOW" : "ACTIVE";
                input.detected_at_edge_monotonic_ns =
                    static_cast<std::int64_t>(event.received_mono_ns);
                if (!event.evidence.is_proximity &&
                    !event.evidence.zone_revision_id.empty()) {
                    input.zone_revision_id = event.evidence.zone_revision_id;
                    input.has_zone_revision_id = true;
                }
                input.policy_revision_id = context_.policy_revision_id;
                // Evidence artifacts (video/snapshots) land with A9-3; the
                // list stays honestly empty until real files exist.
                input.initial_model_output.rule_id = event.rule_id;
                input.initial_model_output.episode =
                    static_cast<std::int64_t>(event.episode_id);
                input.initial_model_output.distance_m = event.evidence.lower_m;
                input.initial_model_output.geometry = "GEO_ESTIMATE";
                input.initial_model_output.synthetic_replay_geometry = false;
                input.observation_quality = quality;
                input.mission_snapshot_hash = context_.mission_snapshot_hash;
                payloads.events.push_back(MakeDetectionEventPayload(input));
            }
        }
    };
    collect_events(record.proximity, "PROXIMITY_CANDIDATE");
    collect_events(record.zone, "ZONE_CANDIDATE");
    return payloads;
}

void MopTickEvidence::EmitTick(MopEdgeEndpoint* endpoint,
                               const TickRecord& record) {
    if (endpoint == nullptr) return;
    TickPayloads payloads = BuildTick(record);
    if (payloads.has_observation) {
        endpoint->EmitObservation(std::move(payloads.observation));
    }
    for (auto& event : payloads.events) {
        endpoint->EmitEvent(std::move(event));
    }
}

void MopTickEvidence::EmitTick(MopLink* link, const TickRecord& record) {
    if (link == nullptr) return;
    TickPayloads payloads = BuildTick(record);
    if (payloads.has_observation) {
        link->EmitObservation(std::move(payloads.observation));
    }
    for (auto& event : payloads.events) {
        link->EmitEvent(std::move(event));
    }
}

bool MopTickEvidence::BuildSummary(const std::string& session_id,
                                   JsonValue* out) const {
    if (frames_ == 0 || out == nullptr) return false;
    MopFlightSummaryInput summary;
    summary.summary_id = Uuid5(MopProtocol::Namespace(),
                               "summary|" + context_.flight_id + "|" +
                                   context_.edge_boot_id);
    summary.flight_id = context_.flight_id;
    summary.edge_boot_id = context_.edge_boot_id;
    summary.session_id = session_id;
    summary.started_at_edge_monotonic_ns = first_recv_ns_;
    summary.ended_at_edge_monotonic_ns = last_recv_ns_;
    summary.ai_uptime_ratio =
        std::round((static_cast<double>(ok_frames_) /
                    static_cast<double>(frames_)) *
                   1000.0) /
        1000.0;
    summary.covered_frames = static_cast<std::int64_t>(ok_frames_);
    summary.skipped_frames = static_cast<std::int64_t>(frames_ - ok_frames_);
    summary.person_count_max = person_max_;
    summary.equipment_count_max = equipment_max_;
    summary.quality_reasons.emplace_back("UNVERIFIED_FRAME_CAPTURE_TIME");
    if (any_sync_degraded_) {
        summary.quality_reasons.emplace_back("TELEMETRY_SYNC_DEGRADED");
    }
    if (durable_gap_) {
        // Some ticks in this session were refused a durable record (spool
        // unwritable): the summary must never read clean while the RC is
        // missing records. Counts above exclude the refused ticks (they
        // were never folded into the aggregates).
        summary.quality_reasons.emplace_back("DURABLE_EVIDENCE_GAP");
    }
    summary.created_at_edge_monotonic_ns = last_recv_ns_;
    *out = MakeFlightSummaryPayload(summary);
    return true;
}

bool MopTickEvidence::EmitSummary(MopEdgeEndpoint* endpoint,
                                  const std::string& session_id) {
    if (endpoint == nullptr) return false;
    JsonValue summary;
    if (!BuildSummary(session_id, &summary)) return false;
    endpoint->EmitSummary(std::move(summary));
    return true;
}

bool MopTickEvidence::EmitSummary(MopLink* link,
                                  const std::string& session_id) {
    if (link == nullptr) return false;
    JsonValue summary;
    if (!BuildSummary(session_id, &summary)) return false;
    link->EmitSummary(std::move(summary));
    return true;
}

}  // namespace ksi::safety
