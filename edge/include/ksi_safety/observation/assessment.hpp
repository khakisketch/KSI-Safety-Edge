// Unit A — passive autonomous assessment (Stage 1, plan 2026-10-23).
//
// WHAT: turns one shared SessionRunner TickRecord into a typed machine
// Assessment. The assessment records what was observed, which rules could be
// evaluated against it, and — when they could not — WHY (typed missing
// reasons). It is pure logic: caller-provided data only, no clock reads, no
// RNG, no I/O. Deterministic: identical inputs yield identical assessments.
//
// WHAT IT IS NOT (lifecycle separation, plan §2):
//   - not an Incident: incidents_created is always 0 (Unit A cannot create
//     one; SHADOW candidates are never promoted here);
//   - not an Alert: alerts_created is always 0 (no recipients, no routing,
//     no delivery state);
//   - not an operator response: human_responses is always 0; no ack, review
//     or field-action history is fabricated or linked;
//   - not a camera/flight command: camera_commands and flight_commands are
//     always 0 (passive observation never moves anything).
//
// MODE: SHADOW only. The assessment reports the effective mode it SAW on the
// rule outputs; it never grants activation and never relabels a withheld
// verdict as a risk finding.
//
// MANUAL CAMERA: analysis continues while a human holds the camera. Only the
// spatial conclusions are withheld then (validity of the pixels for geometry
// is what the manual input affects); detection/tracking facts stay.
//
// ARTIFACT FORMAT (internal versioned envelope, NOT a frozen contract —
// contracts/ is untouched by Unit A):
//   {"schema":"ksi.assessment/v1", ...}  (see Assessment::ToJson)
// media_type when persisted via the existing EvidenceStore:
//   "application/vnd.ksi.assessment+json" (collector maps this exact media
//   to EDGE_ASSESSMENT; see the processor header).
// asset_id derivation: uuid5("assess|<flight>|<boot>|<tick>")
// (flight/boot/tick switches therefore never merge lineages by accident).

#ifndef KSI_SAFETY_OBSERVATION_ASSESSMENT_HPP
#define KSI_SAFETY_OBSERVATION_ASSESSMENT_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/runtime/session_runner.hpp"

namespace ksi::safety {

// Fixed vocabulary for why an assessment (or part of it) is UNDETERMINED.
// New reasons are added, never redefined.
constexpr const char* kMissingDetectionNotRun = "detection_not_run";
constexpr const char* kMissingDetectionFailure = "detection_inference_failure";
constexpr const char* kMissingNoApplicableRules = "no_applicable_rules";
constexpr const char* kMissingGeometryInsufficient = "geometry_insufficient_spatial_withheld";
constexpr const char* kMissingCameraManualSpatial = "camera_manual_spatial_withheld";
constexpr const char* kMissingLensSwitch = "lens_switch_lineage_reset";
constexpr const char* kMissingNoSpatialClaim = "no_tracks_no_spatial_claim";

// Fixed vocabulary for what WAS observed (never a risk claim by itself).
constexpr const char* kObservedCleanNegative = "clean_negative_no_detections";
constexpr const char* kObservedDetections = "fresh_detections_present";
constexpr const char* kObservedTracksHeld = "tracks_held_no_new_evidence";
constexpr const char* kObservedRuleEvaluated = "rule_evaluated";
constexpr const char* kObservedNoRulesConfigured = "observation_only_no_rules";
constexpr const char* kObservedAnalysisContinues = "analysis_continues_manual_camera";

enum class AssessmentDisposition {
    kAssessed,      // every applicable rule produced an evaluable verdict
    kUndetermined,  // at least one applicable conclusion is withheld + reason
};

const char* ToString(AssessmentDisposition disposition);

// Caller-provided identity/provenance for one assessment. No clock.
struct AssessmentContext {
    std::string flight_id;
    std::string edge_boot_id;
    std::string stream_id;
    std::string mission_revision_id;
    std::string policy_revision_id;
    std::string effective_config_hash;
    std::uint64_t config_epoch = 0;
    std::string lens_id = "wide";
    // True on (and just after) a lens/manual-camera switch tick: spatial
    // conclusions are withheld for this tick only; analysis continues.
    bool lens_switch_observed = false;
    // A human currently holds the camera: spatial conclusions withheld,
    // detection/tracking conclusions stand.
    bool camera_manual_override = false;
    // True when the input is synthetic/stub (host replay without a real
    // engine). Recorded honestly on the artifact; never a performance claim.
    bool simulated_input = false;
};

// One rule's contribution, read off the EXISTING RiskEngine outputs carried
// by the TickRecord. No parallel risk logic lives here.
struct AssessmentRuleEntry {
    std::string rule_id;
    std::string status;   // ToString(RuleEvalStatus)
    std::string detail;   // ToString(EvalDetail)
    std::string effective_mode;
    bool is_shadow = true;
    std::size_t event_count = 0;
    std::size_t created_episodes = 0;
    std::size_t closed_episodes = 0;
};

struct Assessment {
    std::string schema = "ksi.assessment/v1";
    std::string assessment_id;  // uuid5 assess|flight|boot|tick
    std::uint64_t tick_index = 0;
    std::uint32_t frame_id = 0;
    std::int64_t receive_mono_ns = 0;
    // "exposure_verified" | "receive_only": the source time grade actually
    // bound on the tick. Never upgraded here.
    std::string time_grade = "receive_only";
    std::string flight_id;
    std::string edge_boot_id;
    std::string stream_id;
    std::string lens_id;
    std::string model_id;
    std::string model_digest;
    std::string mission_revision_id;
    std::string policy_revision_id;
    std::string effective_config_hash;
    std::uint64_t config_epoch = 0;
    bool simulated_input = false;
    // Fixed: Unit A assesses in SHADOW only.
    std::string mode = "SHADOW";
    std::string detection_status;
    std::size_t detection_count = 0;
    std::size_t track_count = 0;
    // Fixed zeros: Unit A creates none of these (see header contract).
    int incidents_created = 0;
    int alerts_created = 0;
    int human_responses = 0;
    int camera_commands = 0;
    int flight_commands = 0;
    bool analysis_continues = true;
    bool camera_manual_override = false;
    AssessmentDisposition disposition = AssessmentDisposition::kUndetermined;
    std::vector<std::string> observed_conditions;
    std::vector<std::string> missing_reasons;
    std::vector<AssessmentRuleEntry> rules;

    std::string ToJson() const;
};

// Pure: builds the assessment for one tick. Never touches stores, links,
// clocks, or the risk engine (outputs are read, not recomputed).
Assessment BuildAssessment(const TickRecord& record,
                           const AssessmentContext& context);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_OBSERVATION_ASSESSMENT_HPP
