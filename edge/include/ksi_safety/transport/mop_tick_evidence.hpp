// TickRecord -> OBSERVATION / DETECTION_EVENT / FLIGHT_SUMMARY payloads.
//
// Port of the validated bridge mapping (`harness/.../replay_evidence.py`)
// onto the REAL runner's TickRecord, so the PSDK application emits the same
// message shapes the M1 flow already verifies — no new semantics. All ids
// are deterministic uuid5 over the MOP namespace: the same tick always
// rebuilds byte-identical payloads (safe under envelope retries).
//
// Nothing here fabricates positions, capture times or distances:
// ENU-space observations carry `position_wgs84 = null` and a
// WITHHELD/UNVERIFIED geometry status, exactly like the replay bridge.
//
// Evidence rules preserved from the runner contract:
// - only detection ticks (kOk) produce an observation: a cadence skip or an
//   inference failure carries NO scene evidence and must never be reported
//   as an empty (negative) observation;
// - only CREATED episode events become DETECTION_EVENT messages;
// - summary skipped/covered counts come from the same tick statuses.
#ifndef KSI_SAFETY_TRANSPORT_MOP_TICK_EVIDENCE_HPP
#define KSI_SAFETY_TRANSPORT_MOP_TICK_EVIDENCE_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

class MopEdgeEndpoint;
class MopLink;

// Identity + mission provenance carried by every evidence payload. Filled
// from the applied mission bundle (mission_revision_id / snapshot hash /
// policy revision) once the RC applies one.
struct MopEvidenceContext {
    std::string flight_id;
    std::string edge_boot_id;
    std::string mission_revision_id;    // bundle mission_snapshot field
    std::string mission_snapshot_hash;  // bundle mission_snapshot.content_sha256
    std::string policy_revision_id;     // bundle risk_policy.revision_id
    std::string lens_id = "wide";
};

// Extracts the context fields from a server mission bundle (arrives in the
// MISSION_PREPARE payload). Returns false + reason when a required field is
// missing; flight/boot are filled by the caller from the live endpoint.
bool EvidenceContextFromBundle(const JsonValue& bundle,
                               MopEvidenceContext* out, std::string* error);

class MopTickEvidence {
 public:
    MopTickEvidence() = default;
    explicit MopTickEvidence(MopEvidenceContext context);

    // Starts a fresh session scope (new flight/boot or new applied bundle):
    // replaces the context and resets the summary aggregates.
    void SetContext(MopEvidenceContext context);
    const MopEvidenceContext& context() const { return context_; }

    struct TickPayloads {
        bool has_observation = false;
        JsonValue observation;          // the "observation" payload object
        std::vector<JsonValue> events;  // "event" payload objects (CREATED only)
    };

    // Consumes one tick: builds its payloads AND folds it into the session
    // aggregates. Call exactly once per processed tick.
    TickPayloads BuildTick(const TickRecord& record);

    void EmitTick(MopEdgeEndpoint* endpoint, const TickRecord& record);
    void EmitTick(MopLink* link, const TickRecord& record);

    // The RC's session derivation (uuid5 "session|flight|boot", the same
    // value MissionController computes), so the summary links to the RC's
    // flight session.
    static std::string SessionId(const std::string& flight_id,
                                 const std::string& edge_boot_id);

    // Summary over every consumed tick. False when no tick was seen.
    bool BuildSummary(const std::string& session_id, JsonValue* out) const;
    bool EmitSummary(MopEdgeEndpoint* endpoint, const std::string& session_id);
    bool EmitSummary(MopLink* link, const std::string& session_id);

    std::uint64_t observations_emitted() const { return observations_; }
    std::uint64_t ticks_seen() const { return frames_; }

 private:
    std::string SyncQuality(const TickRecord& record) const;
    std::string GeometryStatus(const TickRecord& record) const;
    std::string ObservationId(const TickRecord& record) const;

    MopEvidenceContext context_;
    std::uint64_t observations_ = 0;
    std::uint64_t frames_ = 0;
    std::uint64_t ok_frames_ = 0;
    std::int64_t first_recv_ns_ = 0;
    std::int64_t last_recv_ns_ = 0;
    std::int64_t person_max_ = 0;
    std::int64_t equipment_max_ = 0;
    bool any_sync_degraded_ = false;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_TICK_EVIDENCE_HPP
