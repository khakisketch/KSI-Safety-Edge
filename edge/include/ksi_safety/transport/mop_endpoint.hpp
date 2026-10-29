// Edge side of the KSI MOP exchange (A9-2) — ported semantics from the
// validated harness `EdgeReplay` (harness/src/ksi_harness/edge_replay.py):
// mission handling (PREPARE/APPLY/STATUS_QUERY/SESSION), per-channel seqs,
// deterministic msg ids, unacked bookkeeping with retry/requeue and
// unrecoverable DATA-seq declarations, boot rotation.
//
// Single-threaded object: every cross-thread touch MUST go through
// MopLink (a MopLink method or MopLink::WithEndpoint), which serialises
// with its internal mutex. Direct concurrent access is a data race.
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

#include "ksi_safety/transport/durable_spool.hpp"
#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

// Runtime facts published by the execution layer (SessionRunner via the
// PSDK service glue) for MISSION_STATUS (contract §3). Every field is
// individually presence-flagged: absence on the wire means "unknown", never
// false/0. The endpoint never fabricates them — without a PublishRuntimeFacts
// call the payload keeps its legacy shape exactly.
struct MopRuntimeInputState {
    std::string video = "absent";      // ok|stale|lost|failed|absent
    std::string telemetry = "absent";  // ok|stale|lost|failed|absent
    std::string engine = "absent";     // ok|stale|lost|failed|absent
};

struct MopRuntimeFacts {
    // Epoch of the installed config; runtime_epoch derives deterministically
    // as uuid5("runtime|flight|boot|config_epoch") so a reboot (new boot id)
    // or reinstall (new epoch) is visible to the RC for re-sync.
    bool has_config_epoch = false;
    std::uint64_t config_epoch = 0;
    bool has_state = false;
    std::string runtime_state;  // §2 vocabulary (6 terms)
    bool has_config_applied = false;
    bool config_applied = false;
    // Installed config hash. Set-but-empty encodes explicit null (installed
    // without a hash, which the parser never produces — null in practice).
    bool has_effective_config_hash = false;
    std::string effective_config_hash;
    bool has_processed_frame_count = false;
    std::uint64_t processed_frame_count = 0;
    // Last processed frame time (monotonic ns, endpoint-clock domain); the
    // age is computed at STATUS time so no clock is read on the hot path.
    bool has_last_processed_mono_ns = false;
    std::int64_t last_processed_frame_mono_ns = 0;
    bool has_degraded_reasons = false;
    std::vector<std::string> degraded_reasons;
    bool has_input_state = false;
    MopRuntimeInputState input_state;
};

// One accepted RC command (validation passed, before it is acted on).
// The audit trail journals these write-ahead of the transitions they cause,
// so offline input outages stay reconstructible without frame copies.
struct AcceptedCommand {
    std::string msg_id;
    // SESSION_START | SESSION_STOP | MISSION_APPLY
    std::string type;
    // Endpoint-wide accept order (resets on adoption/reboot).
    std::uint64_t seq = 0;
};

// Atomic run-command snapshot for the G9 run control (contract §2).
//
// Why this exists: the worker poll used to sample session_start_count() and
// session_stop_count() in two separate calls. A SESSION_STOP landing between
// the samples fused a stale START with a fresh STOP into one poll, and the
// fixed stop-before-start order then wrongly ended STARTED; likewise an
// APPLY racing a START in the same poll silently dropped the START (the
// bundle edge resynchronised both counters). This struct is filled by ONE
// call, so the revision, the counts, the order flag and the desired intent
// can never contradict each other.
//
//   desired_running: intent of the latest accepted command (START=true).
//   command_revision: bumps once per accepted SESSION_START/STOP (wire
//     retries dedup by msg_id before this, so a retry never advances it).
//   command_epoch: bumps on flight adoption and reboot; the revision and
//     the counts reset alongside, so a consumer re-baselines instead of
//     replaying pre-change intent.
//   start/stop_count: accepted-command totals (legacy wire introspection;
//     deltas split a revision move into starts vs stops).
//   last_command_was_start: type of the latest accepted command — orders a
//     START+STOP pair that lands in the same poll so the FINAL operator
//     intent wins (never a fixed order).
//   applied_generation + has_bundle: the MISSION_APPLY generation and
//     whether a bundle is currently applied (adoption/reboot clear it).
struct MopSessionCommands {
    bool desired_running = false;
    std::uint64_t command_revision = 0;
    std::uint64_t command_epoch = 0;
    std::uint64_t start_count = 0;
    std::uint64_t stop_count = 0;
    bool last_command_was_start = false;
    std::uint64_t applied_generation = 0;
    bool has_bundle = false;
};

class MopEdgeEndpoint {
 public:
    using Clock = std::function<std::int64_t()>;

    // process_unique: per-process entropy mixed into every derived boot id
    // (empty = legacy exact derivation, used by fixtures/tests). The
    // production adapter passes OS entropy so a process restart with the
    // same flight id still gets a new boot id (and thus a new
    // runtime_epoch) instead of repeating uuid5("boot|flight|0").
    MopEdgeEndpoint(std::string flight_id, Clock clock, int boot_index = 0,
                    std::string boot_id = "",
                    std::string process_unique = "");

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
    // stay unacked until an ACK envelope arrives. Returns the outbox entry
    // (or a static empty object when a spool journal refusal drops an
    // evidence envelope — see setSpool; callers that need the queued entry
    // only use non-journalled channels).
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
    // Same as EmitArtifact but stamps the given flight/boot identity instead
    // of the endpoint's current one: prior-boot audit journals ship under
    // their ORIGINAL identity (restored history is never relabelled).
    void EmitArtifactAs(const std::string& artifact_id,
                        const std::string& source_kind,
                        const std::string& media_type,
                        std::int64_t segment_index, bool required,
                        const std::string& bytes,
                        const std::string& flight_id,
                        const std::string& edge_boot_id);

    // --- introspection (tests / service status) ------------------------------
    std::int64_t seq(const std::string& channel) const;
    std::size_t unackedCount() const { return unacked_.size(); }
    // "start requested and not stopped" — execution evidence lives in the
    // runtime facts (runtime_state / processed_frame_count), never here.
    // Legacy wire-compat introspection; run control consumes
    // session_commands() instead (one coherent snapshot, never two
    // separately sampled counters).
    bool running() const { return running_; }
    std::uint64_t session_start_count() const { return session_start_count_; }
    std::uint64_t session_stop_count() const { return session_stop_count_; }
    // One coherent run-command snapshot (see MopSessionCommands): revision,
    // counts, order flag, desired intent, generation and bundle presence
    // from a single call — never fused from separate samples.
    MopSessionCommands session_commands() const;
    // Accepted commands with seq in (since_seq, current], oldest first,
    // for the write-ahead audit (command IDs the poll has not consumed
    // yet). overflow=true when the bounded ring dropped some in between
    // (the audit records the gap explicitly — never silently incomplete).
    std::vector<AcceptedCommand> AcceptedCommandsSince(
        std::uint64_t since_seq, bool* overflow) const;
    // Bumps on every MISSION_APPLY acceptance, reboot, and flight adoption:
    // the service glue re-reads the applied bundle when it changes.
    // (A deferred-apply confirmation does NOT bump it: the installer already
    // consumed the pending bundle, so there is nothing new to install.)
    std::uint64_t applied_generation() const { return applied_generation_; }
    bool hasAppliedBundle() const { return has_applied_bundle_; }
    const std::string& appliedBundleSha256() const {
        return applied_bundle_sha256_;
    }
    // --- Deferred install confirmation (device path, G9 provenance) -------
    // Default off: OnApply switches the bundle and answers MISSION_APPLIED
    // immediately (the pure-protocol behaviour the fixtures verify). With
    // setDeferredApply(true), OnApply only validates + records the request
    // as pending: no bundle switch, no generation bump, no MISSION_APPLIED.
    // The service confirms (real install receipt -> switch + MISSION_APPLIED)
    // or rejects (refused/failed install -> MISSION_APPLY_REJECTED) after
    // the runner actually installs. The transport ACK is still sent at
    // request time; ACK means "received", never "installed". Evidence
    // context therefore binds only to successfully installed bundles: a
    // refused/failed apply can never relabel the old provenance.
    void setDeferredApply(bool deferred) { deferred_apply_ = deferred; }
    bool deferredApply() const { return deferred_apply_; }
    bool hasPendingApply() const { return has_pending_apply_; }
    // The validated pending bundle, or nullptr. Valid only while
    // hasPendingApply().
    const JsonValue* pendingBundle() const;
    // Install succeeded: switch the bundle and answer MISSION_APPLIED.
    // False when nothing was pending or the bundle vanished (adoption or
    // reboot cleared it) — the caller must then park, never run it.
    bool ConfirmPendingApply();
    // Install refused/failed: answer MISSION_APPLY_REJECTED with the
    // installer's reasons and drop the pending request.
    bool RejectPendingApply(const std::vector<std::string>& reasons);
    // --- Durable offline spool (MOP-drop survival) ---------------------------
    // Nullable and never owned. When set, evidence envelopes are journalled
    // at Send time (fsync before queue), RC ACKs mark them delivered, and
    // ReplaySpool() requeues unacked envelopes after reconnect or process
    // restart with their exact original ids/seqs. Null = legacy in-memory
    // behaviour (all existing fixtures).
    void setSpool(DurableSpool* spool) { spool_ = spool; }
    DurableSpool* spool() const { return spool_; }
    // Requeues every spooled-but-unacked envelope not already queued, in
    // original append order with identical bytes. Returns the requeue count.
    // Call after (re)connect and after process restart (fresh endpoint).
    // Old records keep their original flight/boot ids — replay never
    // relabels them under a new identity.
    std::size_t ReplaySpool();
    // Journal refusals since boot (spool full/corrupt/IO/conflict at Send).
    // Refused envelopes are dropped, never silently unjournalled.
    std::uint64_t spool_refused() const { return spool_refused_; }
    // Sticky durable-evidence failure (journal refusal or unwritable spool).
    // Once true the run must never report RUNNING again: new observation
    // work stops and the runtime degrades with session_capacity_exceeded.
    // reason_out carries the truthful IO cause (full/corrupt/IO/conflict).
    bool spoolFailed(std::string* reason_out) const;
    // Gate for building new evidence (single-consumer poll). True when an
    // envelope may be built: no spool (legacy), or spool healthy with room
    // for another maximal envelope. Allowed reclaim happens first (acked
    // payloads compact to tombstones via EnsureCapacity); only a journal
    // genuinely full of UNACKED data (or tombstones) latches spoolFailed().
    // False latches spoolFailed() — callers must skip the tick (dropped,
    // counted upstream) instead of building records that can never be
    // journalled.
    bool CheckSpoolWritable();
    // Runtime facts for MISSION_STATUS (absent = unknown). Cleared on
    // reboot and flight adoption alongside the rest of the session scope.
    void PublishRuntimeFacts(MopRuntimeFacts facts);
    void ClearRuntimeFacts();
    bool hasRuntimeFacts() const { return has_runtime_facts_; }
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
    // Per-process entropy mixed into every derived boot id ("" = legacy
    // exact derivation, used by fixtures/tests).
    std::string process_unique_;
    std::string DeriveBootId() const;
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
    // Deferred install confirmation (device path): a validated but not yet
    // installed request. Cleared on adoption/reboot with the session scope.
    bool deferred_apply_ = false;
    bool has_pending_apply_ = false;
    std::string pending_bundle_id_;
    std::string pending_bundle_sha_;
    std::string pending_msg_id_;
    bool running_ = false;
    // G9 command ordering (see MopSessionCommands): the latest accepted
    // intent plus a monotonic revision. Wire retries dedup by msg_id before
    // OnSession, so they never advance the revision; adoption and reboot
    // reset the intent and bump the epoch (counts reset alongside).
    bool desired_running_ = false;
    std::uint64_t command_revision_ = 0;
    std::uint64_t command_epoch_ = 0;
    bool last_command_was_start_ = false;
    std::uint64_t session_start_count_ = 0;
    std::uint64_t session_stop_count_ = 0;
    std::uint64_t applied_generation_ = 0;
    // Accepted-command log for the write-ahead audit (bounded ring, 512;
    // overflow counted, never silently incomplete). Cleared on
    // adoption/reboot with the rest of the session scope.
    std::deque<AcceptedCommand> accepted_commands_;
    std::uint64_t command_seq_ = 0;
    std::uint64_t commands_dropped_ = 0;
    void NoteAcceptedCommand(const std::string& msg_id,
                             const std::string& type);
    MopRuntimeFacts runtime_facts_;
    bool has_runtime_facts_ = false;
    // Durable offline spool (nullable, never owned). See setSpool().
    DurableSpool* spool_ = nullptr;
    std::uint64_t spool_refused_ = 0;
    bool spool_failed_ = false;
    std::string spool_fail_reason_;
    void FailSpool(const std::string& reason);
    bool flight_id_confirmed_ = false;
    std::uint64_t adoptions_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_ENDPOINT_HPP
