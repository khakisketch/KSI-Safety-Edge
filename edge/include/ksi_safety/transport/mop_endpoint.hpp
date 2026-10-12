// Edge side of the KSI MOP exchange (A9-2) — ported semantics from the
// validated harness `EdgeReplay` (harness/src/ksi_harness/edge_replay.py):
// mission handling (PREPARE/APPLY/STATUS_QUERY/SESSION), per-channel seqs,
// deterministic msg ids, unacked bookkeeping with retry/requeue and
// unrecoverable DATA-seq declarations, boot rotation.
//
// Single-threaded by contract: MopLink serialises every call (the PSDK app
// emits from its processing loop and receives on the reader thread; the
// link owns the mutex).
#ifndef KSI_SAFETY_TRANSPORT_MOP_ENDPOINT_HPP
#define KSI_SAFETY_TRANSPORT_MOP_ENDPOINT_HPP

#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

class MopEdgeEndpoint {
 public:
    using Clock = std::function<std::int64_t()>;

    MopEdgeEndpoint(std::string flight_id, Clock clock, int boot_index = 0,
                    std::string boot_id = "");

    const std::string& flight_id() const { return flight_id_; }
    const std::string& edge_boot_id() const { return edge_boot_id_; }

    // True once the flight identity is settled: an explicit non-empty
    // constructor id, or the first RC envelope has been adopted (the RC
    // creates its per-flight UUID; the Edge learns it from the wire).
    bool flightIdConfirmed() const { return flight_id_confirmed_; }
    std::uint64_t adoptions() const { return adoptions_; }

    // The applied bundle (MISSION_APPLY result), or nullptr. Used by the
    // app to extract the evidence context (mission/policy revisions).
    const JsonValue* appliedBundle() const;

    // Sends one message: appended to the outbox; reliable non-ACK messages
    // stay unacked until an ACK envelope arrives. Returns the outbox entry.
    const JsonValue& Send(const std::string& channel, const std::string& type,
                          JsonValue payload, bool reliable = true);

    std::deque<JsonValue>& outbox() { return outbox_; }
    std::size_t outboxSize() const { return outbox_.size(); }

    // One RC→Edge envelope (already digest-verified by the link).
    // Returns "stored" | "dedup" | "ack" | "error".
    std::string Receive(const JsonValue& envelope);

    // Still-unacked DATA seqs become unrecoverable gaps after retries
    // (heartbeat declarations; control flow reconciles via STATUS_QUERY).
    void MarkUnrecoverable(int max_attempts = 1);

    // Retry: copies every unacked envelope to the outbox (attempts++).
    void RequeueUnacked();

    // New boot: rotated id, cleared applied state + seqs, prepared bundles
    // and dedup memory kept (harness parity). Returns the new boot id.
    std::string Reboot(const std::string& next_boot_id = "");

    // --- emission helpers (existing message shapes only) --------------------
    void EmitObservation(JsonValue observation);
    void EmitEvent(JsonValue event);
    void EmitSummary(JsonValue summary);
    void EmitHeartbeat(double fps = 15.0);
    // ARTIFACT_ANNOUNCE + 512-byte ARTIFACT_CHUNK messages; the sha256 is
    // computed over exactly `bytes` (never fabricated).
    void EmitArtifact(const std::string& artifact_id,
                      const std::string& source_kind,
                      const std::string& media_type,
                      std::int64_t segment_index, bool required,
                      const std::string& bytes);

    // --- introspection (tests / service status) ------------------------------
    std::int64_t seq(const std::string& channel) const;
    std::size_t unackedCount() const { return unacked_.size(); }
    bool running() const { return running_; }
    bool hasAppliedBundle() const { return has_applied_bundle_; }
    const std::string& appliedBundleSha256() const {
        return applied_bundle_sha256_;
    }
    std::vector<JsonValue> UnackedCopies() const;

 private:
    struct UnackedEntry {
        JsonValue envelope;
        int attempts = 1;
    };

    std::string NextMsgId(const std::string& channel, const std::string& type,
                          const std::int64_t* seq);
    void SendAck(const std::string& msg_id, const std::string& channel);
    // The RC's flight identity becomes authoritative on the wire: adopt it
    // (rotating the boot + clearing session state — seq/unacked must never
    // span flights) before dispatching the envelope that carried it.
    void AdoptFlightId(const std::string& new_id);
    std::string OnPrepare(const JsonValue& envelope, const JsonValue& payload);
    std::string OnApply(const JsonValue& envelope, const JsonValue& payload);
    std::string OnStatusQuery(const JsonValue& envelope);
    std::string OnSession(const JsonValue& envelope,
                          const std::string& type);
    std::string OnAck(const JsonValue& payload);

    std::string flight_id_;
    Clock clock_;
    int boot_index_ = 0;
    std::string edge_boot_id_;
    int msg_counter_ = 0;

    std::map<std::string, std::int64_t> seq_;
    std::deque<JsonValue> outbox_;
    std::map<std::string, UnackedEntry> unacked_;
    std::set<std::string> seen_msg_ids_;
    std::map<std::string, JsonValue> prepared_;
    std::map<std::string, JsonValue> last_responses_;
    std::map<std::string, JsonValue> applications_;
    std::map<std::string, std::set<std::int64_t>> unrecoverable_;

    std::string applied_bundle_sha256_;
    std::string applied_bundle_id_;
    bool has_applied_bundle_ = false;
    std::string last_application_id_;
    bool has_application_ = false;
    bool running_ = false;
    bool flight_id_confirmed_ = false;
    std::uint64_t adoptions_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_ENDPOINT_HPP
