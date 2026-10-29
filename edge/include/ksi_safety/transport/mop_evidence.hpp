// Evidence-plane payload builders (A9-2): the SAME message shapes the
// validated M1 harness emits (`harness/src/ksi_harness/edge_replay.py` +
// `replay_evidence.py`), so the real Edge path reuses the existing
// generation boundary instead of inventing new semantics. Field names and
// id derivations follow those modules exactly; values that need capture
// time, geodetic positions or calibration stay null/WITHHELD unless the
// caller has verified data (never fabricated here).
#ifndef KSI_SAFETY_TRANSPORT_MOP_EVIDENCE_HPP
#define KSI_SAFETY_TRANSPORT_MOP_EVIDENCE_HPP

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

struct MopTelemetryRef {
    std::string topic;  // "aircraft_attitude" | "aircraft_position"
    std::int64_t edge_receive_monotonic_ns = 0;
    double sample_age_ms = 0.0;
    std::string timestamp_basis;  // "PSDK_LOCAL_RECEIVE"
    std::string quality;          // VALID | DEGRADED | UNKNOWN
};

struct MopDetectionItem {
    std::string detection_id;
    std::string class_id;  // raw detector label (e.g. "person", "coco-2")
    double score = 0.0;
    std::vector<double> bbox_xyxy_px;  // 4 entries
    // null when the detection was not tracked on this tick.
    std::string track_id;
    bool has_track_id = false;
};

struct MopObservationInput {
    std::string observation_id;
    std::string flight_id;
    std::string mission_revision_id;
    std::string edge_boot_id;
    std::string source_kind = "PSDK_EDGE";
    std::int64_t edge_frame_seq = 0;
    std::string edge_frame_id;
    std::string lens_id = "wide";
    std::int64_t frame_received_monotonic_ns = 0;
    std::string model_digest;
    std::string sync_quality;       // VALID | DEGRADED | UNKNOWN
    std::string geometry_status;    // UNVERIFIED (GEO_ESTIMATE) | WITHHELD
    std::vector<MopTelemetryRef> telemetry_refs;
    std::vector<MopDetectionItem> detections;
    std::vector<std::string> quality_reasons;
};

struct MopEventModelOutput {
    std::string rule_id;
    std::int64_t episode = 0;
    double distance_m = 0.0;
    std::string geometry;  // "GEO_ESTIMATE"
    bool synthetic_replay_geometry = false;
};

struct MopDetectionEventInput {
    std::string event_id;
    std::string flight_id;
    std::string observation_id;
    std::string episode_id;
    std::string event_type;  // PROXIMITY_CANDIDATE | ZONE_CANDIDATE | ...
    std::string mode = "SHADOW";
    std::int64_t detected_at_edge_monotonic_ns = 0;
    // null when no zone participates.
    std::string zone_revision_id;
    bool has_zone_revision_id = false;
    std::string policy_revision_id;
    std::vector<std::string> evidence_artifact_ids;
    MopEventModelOutput initial_model_output;
    std::string observation_quality = "VALID";
    std::string mission_snapshot_hash;
};

struct MopFlightSummaryInput {
    std::string summary_id;
    std::string flight_id;
    std::string edge_boot_id;
    std::string session_id;
    std::int64_t started_at_edge_monotonic_ns = 0;
    std::int64_t ended_at_edge_monotonic_ns = 0;
    double ai_uptime_ratio = 0.0;
    std::int64_t covered_frames = 0;
    std::int64_t skipped_frames = 0;
    std::int64_t person_count_max = 0;
    std::int64_t equipment_count_max = 0;
    std::vector<std::string> quality_reasons;
    std::int64_t created_at_edge_monotonic_ns = 0;
};

// --- builders ---------------------------------------------------------------

JsonValue MakeObservationPayload(const MopObservationInput& input);
JsonValue MakeDetectionEventPayload(const MopDetectionEventInput& input);
JsonValue MakeFlightSummaryPayload(const MopFlightSummaryInput& input);

// `applied_bundle_sha256` may be null (kHasAppliedBundle false).
JsonValue MakeHeartbeatPayload(double fps, bool has_applied_bundle,
                               const std::string& applied_bundle_sha256,
                               const std::vector<std::pair<
                                   std::string, std::vector<std::int64_t>>>&
                                   unrecoverable_gaps,
                               const std::string& edge_boot_id);

JsonValue MakeArtifactAnnouncePayload(const std::string& artifact_id,
                                      const std::string& flight_id,
                                      const std::string& source_kind,
                                      const std::string& sha256,
                                      std::int64_t size_bytes,
                                      const std::string& media_type,
                                      std::int64_t segment_index,
                                      bool required,
                                      const std::string& edge_boot_id);

JsonValue MakeArtifactChunkPayload(const std::string& artifact_id,
                                   std::int64_t offset,
                                   const std::uint8_t* data, std::size_t len);

class MopEdgeEndpoint;
class MopLink;

// Assessment delivery (Unit A closure): one sealed assessment JSON travels
// over the EXISTING generic artifact plane (ARTIFACT_ANNOUNCE +
// ARTIFACT_CHUNK on EVIDENCE_TRANSFER) — no new MOP message type, no
// contract change. The RC ingest/store/manifest/Core-ingest path is fully
// generic over artifacts, so no receiving-side contract change is needed
// either: identifier linking (assessment_id tick/flight/boot derivation,
// window trigger derivation) is documented on the consumer side.
//
// required=false: an assessment is a review aid, not L1 preservation.
// The Core commits COMMITTED without it (optional artifacts never block),
// and a missing assessment never flips a session to PARTIAL-by-itself.
constexpr const char* kAssessmentSourceKind = "EDGE_ASSESSMENT";
constexpr const char* kAssessmentMediaType =
    "application/vnd.ksi.assessment+json";

// Queues the announce + chunk envelopes for one assessment JSON. Uses the
// caller-provided flight/boot identity (the live endpoint identity —
// never a stale configured label); the asset id is the caller's stable
// derivation (AutonomousProcessor::AssessmentAssetId).
void EmitAssessmentArtifact(MopEdgeEndpoint* endpoint,
                            const std::string& asset_id,
                            const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& assessment_json);
void EmitAssessmentArtifact(MopLink* link, const std::string& asset_id,
                            const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& assessment_json);

// Emission gate (Unit A closure): an assessment goes out only with a
// confirmed flight identity, an installed mission (it cites the mission
// revisions), and a journal-writable spool. One predicate, one place —
// the device service and the host tool share it, and the host suite pins
// the truth table. A denied assessment is gated/dropped and COUNTED by
// the caller, never folded anywhere and never read as delivered (the
// sealed store copy is the retention path, not the MOP send).
struct AssessmentEmitGate {
    bool flight_confirmed = false;
    bool mission_installed = false;
    bool spool_writable = false;
};

bool AssessmentEmitAllowed(const AssessmentEmitGate& gate);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_EVIDENCE_HPP
