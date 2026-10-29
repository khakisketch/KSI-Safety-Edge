// Bounded command/state audit journal (offline forensics + FLIGHT_LOG ship).
//
// Records the events that reconstruct an execution WITHOUT replaying
// frames: accepted wire commands (msg ids), config install/rejection
// receipts, runtime state/input transitions, closeouts, identity changes.
// Event-driven only (never per-poll, never frame copies): the worker calls
// Record*/Note* and this unit decides what is new.
//
// Bounded: cap kMaxEvents (4096) with drop-oldest + dropped() count, so any
// bound is explicit in the shipped artifact instead of silently incomplete.
// No clock reads: the caller stamps mono_ns (caller-provided timestamps,
// like the rest of edge). File journaling is optional: OpenJournal appends
// every event as one JSON line + fsyncs; an fsync failure latches
// journalOk()==false (file keeps old bytes — fail closed) while the
// in-memory trail continues, so the failure is loud instead of silent.
// Host-testable (no PSDK, no transport, real FS in tests).

#ifndef KSI_SAFETY_TRANSPORT_AUDIT_TRAIL_HPP
#define KSI_SAFETY_TRANSPORT_AUDIT_TRAIL_HPP

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

// One audit event. kind is one of: identity|command|install|state|close.
struct AuditEvent {
    std::uint64_t seq = 0;  // journal order (0-based, stable)
    std::int64_t mono_ns = 0;
    std::string kind;
    std::string msg_id;  // wire command id, or "" when not applicable
    JsonValue detail = JsonValue::Obj();
};

// Fault injection for fsync failures. Empty fsync means real ::fsync.
struct AuditHooks {
    // Must behave like ::fsync: 0 on success, -1 with errno on failure.
    std::function<int(int)> fsync;
};

// Stable audit-log lineage for one flight+boot (identifies the log stream;
// independent of content, so it never participates in a content hash).
std::string AuditLineageId(const std::string& flight_id,
                           const std::string& edge_boot_id);
// Content-addressed artifact id: same bytes replay under the same id
// (RC dedups append-only artifacts by id), changed snapshots mint a new
// id — never the same id with different bytes.
std::string AuditArtifactId(const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& artifact_bytes);

// Write-ahead audit gate for one worker poll. The adapter feeds every
// required durability outcome in poll order; the first failure latches and
// every later claim in the same poll must be withheld (no Step, no
// confirm, no publish, no clean summary). Pure (no I/O): the PSDK adapter
// applies the verdict verbatim, and host tests enumerate it — a journal or
// spool failure anywhere upstream yields zero normal claims.
class AuditGate {
  public:
    AuditGate() = default;
    // Record one required durability outcome. False latches suspended
    // permanently for this poll; the first failing stage name is kept
    // for the closing log.
    void Require(bool ok, const char* stage) {
        if (!ok && !suspended_) {
            suspended_ = true;
            failed_stage_ = stage != nullptr ? stage : "";
        }
    }
    bool suspended() const { return suspended_; }
    const char* failedStage() const { return failed_stage_; }

  private:
    bool suspended_ = false;
    const char* failed_stage_ = "";
};

class AuditTrail {
  public:
    AuditTrail() = default;
    ~AuditTrail();
    // Non-copyable (owns a journal file handle); movable.
    AuditTrail(const AuditTrail&) = delete;
    AuditTrail& operator=(const AuditTrail&) = delete;
    AuditTrail(AuditTrail&& other) noexcept;
    AuditTrail& operator=(AuditTrail&& other) noexcept;
    // Closes any open journal and clears all state (fresh identity scope).
    void Reset();

    // Opens the per-boot journal <dir>/audit-<boot_id>.log for append +
    // fsync per record (0600, created on first use; boot id sanitised to
    // [A-Za-z0-9_.-] so the name can never escape the state dir). An
    // existing file is validated on open: a torn trailing line (no newline
    // terminator) is truncated (tornRepaired()), an interior malformed
    // line fails the open (fail closed — file kept byte-identical for
    // forensics). False when the file cannot be opened or validated;
    // in-memory recording continues (loud via journalOk()/journalError()).
    // Call once; the service fails Start loudly instead of running
    // without the journal.
    bool OpenJournal(const std::string& dir, const std::string& boot_id,
                     const AuditHooks& hooks, std::string* error);
    bool journalOpen() const { return journal_ != nullptr; }
    bool journalOk() const { return journal_ok_; }
    bool tornRepaired() const { return torn_repaired_; }
    const std::string& journalError() const { return journal_error_; }

    // (Re)sets the flight/boot identity; records an identity event on
    // change (including the first call). Returns false on journal failure.
    bool SetIdentity(const std::string& flight_id,
                     const std::string& edge_boot_id, std::int64_t now_mono_ns);

    // An accepted wire command (validation passed, before it is acted on).
    bool RecordCommand(const std::string& msg_id, const std::string& type,
                       std::uint64_t revision, std::uint64_t epoch,
                       std::int64_t now_mono_ns);

    // A config install receipt: outcome is installed|refused|parse_failed,
    // reason names the first cause ("" when installed).
    bool RecordInstall(const std::string& bundle_id,
                       const std::string& bundle_sha,
                       std::uint64_t applied_generation,
                       const std::string& outcome, const std::string& reason,
                       std::int64_t now_mono_ns);

    // Runtime snapshot transition: records on state/input/degraded/epoch
    // changes. Pure counter advances do NOT append full state records
    // (a 15 fps poll would exhaust the bound in minutes); instead a
    // periodic "checkpoint" record carries the counts (see
    // kCheckpointFrames). degraded_csv is ";"-joined degraded_reasons
    // (snapshot order). Returns true when a record was appended.
    bool NoteState(const std::string& runtime_state, const std::string& video,
                   const std::string& telemetry, const std::string& engine,
                   const std::string& degraded_csv,
                   std::uint64_t processed_frame_count,
                   std::uint64_t config_epoch, std::int64_t now_mono_ns);

    // Session close (clean stop): close_ts handed to RequestStop and the
    // summary tick count it covered.
    bool RecordClose(std::uint64_t close_ts, std::uint64_t summary_ticks,
                     std::int64_t now_mono_ns);

    // Full artifact bytes: meta header line (artifact marker, stable
    // lineage id, flight, boot, dropped bound, complete flag) + one JSON
    // line per retained event, oldest first. complete is false when the
    // bound dropped anything (never claim a partial memory tail complete —
    // the disk journal holds the full history).
    std::string ToJsonLines() const;
    // Shipment bytes for an explicit event list (prior-boot restore): same
    // shape, complete=true (the journal file is the full accepted record).
    static std::string FormatFileShipment(const std::string& flight_id,
                                          const std::string& edge_boot_id,
                                          const std::vector<JsonValue>& lines);
    // Reads + validates a journal file: every complete line must parse as
    // a JSON object. A torn trailing line (no newline terminator) is
    // truncated and reported via repaired (explicit torn-tail recovery);
    // an interior malformed line fails closed (returns false, file kept
    // byte-identical for forensics).
    static bool ReadJournalFile(const std::string& path,
                                std::vector<JsonValue>* lines,
                                bool* repaired, std::string* error);
    // Boot ids with a journal file in dir (filename stem of audit-*.log),
    // sorted. Lets a new process restore prior boots' histories without
    // guessing identities.
    static std::vector<std::string> ListBootJournals(const std::string& dir);

    std::size_t size() const { return events_.size(); }
    // Events dropped by the bound (oldest-first). Nonzero means the shipped
    // history is explicitly partial — never claimed complete.
    std::uint64_t dropped() const { return dropped_; }

  private:
    static constexpr std::size_t kMaxEvents = 4096;
    // Counter-only progress is journalled as a lightweight checkpoint at
    // most this often (processed frames) — never a full state record per
    // poll, which would exhaust the bound in minutes at frame rate.
    static constexpr std::uint64_t kCheckpointFrames = 1024;
    bool Append(AuditEvent event);
    std::string Serialize(const AuditEvent& event) const;

    std::vector<AuditEvent> events_;
    std::uint64_t next_seq_ = 0;
    std::uint64_t dropped_ = 0;
    std::string flight_id_;
    std::string edge_boot_id_;
    bool has_identity_ = false;
    bool has_state_ = false;
    // Last reported state (change detection for NoteState).
    std::string last_state_;
    std::string last_video_;
    std::string last_telemetry_;
    std::string last_engine_;
    std::string last_degraded_;
    std::uint64_t last_processed_ = 0;
    std::uint64_t last_epoch_ = 0;
    // Next processed count that earns a checkpoint record.
    std::uint64_t next_checkpoint_due_ = 0;
    std::FILE* journal_ = nullptr;
    bool journal_ok_ = true;
    bool torn_repaired_ = false;
    std::string journal_error_;
    AuditHooks hooks_;
};

// Identity-partitioned audit journals over one state dir. Owns the
// rotation policy the PSDK service applies every poll, so the SAME sequence
// is host-testable end to end (see test_audit_trail.cpp):
//   - nothing is recorded or shipped under an empty/unconfirmed flight;
//   - on the first confirmed (flight, boot), a per-boot journal file opens
//     and the identity is recorded;
//   - on (flight, boot) change the outgoing trail is handed back for
//     shipping under ITS ORIGINAL identity (never relabelled), then a
//     fresh journal + memory context starts for the new identity;
//   - prior-boot journals on disk are listed for restore, each shipped
//     under the identity read from ITS OWN records.
// No new policy restricting to a single flight per process: several
// confirmed flights accumulate as separate per-boot journals, each shipped
// whole. The service performs the link sends; all selection/rotation facts
// live here, in DJI-free core.
class AuditJournalSet {
  public:
    // Shipment for one closed identity scope (rotation or restore).
    struct Shipment {
        bool empty = true;  // nothing to ship
        std::string artifact_id;  // content-addressed (same bytes: same id)
        std::string flight_id;    // ORIGINAL identity, never relabelled
        std::string edge_boot_id;  // ORIGINAL identity, never relabelled
        std::string bytes;        // meta + JSON lines, oldest first
    };

    AuditJournalSet() = default;

    // State dir for audit-*.log (validated absolute; files created per
    // boot on demand). False when unusable.
    bool Open(const std::string& dir, const AuditHooks& hooks,
              std::string* error);
    // Ensures the active journal tracks (flight_id, edge_boot_id).
    // Unconfirmed/empty flight: quiet no-op true (no records, no files).
    // On change: closes the outgoing journal and returns its shippable
    // bytes under the OLD identity for the caller to emit; a fresh journal
    // + memory context starts for the new identity. False only on journal
    // failure (sticky; see journalOk()).
    Shipment EnsureIdentity(const std::string& flight_id,
                            const std::string& edge_boot_id, bool confirmed,
                            std::int64_t now_mono_ns);
    // Record into the active identity scope. Quiet no-ops (true) before
    // the first confirmed identity; false only on journal failure.
    bool RecordCommand(const std::string& msg_id, const std::string& type,
                       std::uint64_t revision, std::uint64_t epoch,
                       std::int64_t now_mono_ns);
    bool RecordInstall(const std::string& bundle_id,
                       const std::string& bundle_sha,
                       std::uint64_t applied_generation,
                       const std::string& outcome, const std::string& reason,
                       std::int64_t now_mono_ns);
    bool NoteState(const std::string& runtime_state, const std::string& video,
                   const std::string& telemetry, const std::string& engine,
                   const std::string& degraded_csv,
                   std::uint64_t processed_frame_count,
                   std::uint64_t config_epoch, std::int64_t now_mono_ns);
    bool RecordClose(std::uint64_t close_ts, std::uint64_t summary_ticks,
                     std::int64_t now_mono_ns);
    // Current-trail ship bytes (active identity). Empty when nothing was
    // recorded under it.
    std::string ToJsonLines() const;
    Shipment CurrentShipment() const;
    // Prior-boot journals on disk (sorted), excluding nothing (the caller
    // skips its active boot if desired).
    std::vector<std::string> ListBoots() const;
    // Whole-history shipment for a prior boot's journal file, under the
    // identity read from the file itself (never relabelled). Returns false
    // (with reason) on torn/unreadable content; torn tails are truncated
    // explicitly. Fills a full Shipment (bytes + content-addressed id).
    bool ShipBootFile(const std::string& boot_id, Shipment* shipment,
                      bool* repaired_out, std::string* error);
    // Active-identity introspection (tests + artifact ids).
    bool hasIdentity() const { return has_identity_; }
    const std::string& flight_id() const { return flight_id_; }
    const std::string& edge_boot_id() const { return edge_boot_id_; }
    std::size_t size() const { return trail_.size(); }
    std::uint64_t dropped() const { return trail_.dropped(); }
    bool journalOk() const { return trail_ok_; }
    std::string lastError() const { return last_error_; }
    bool tornRepaired() const { return torn_repaired_any_; }

  private:

    std::string dir_;
    AuditHooks hooks_;
    bool open_ = false;
    AuditTrail trail_;
    bool has_identity_ = false;
    std::string flight_id_;
    std::string edge_boot_id_;
    bool trail_ok_ = true;
    std::string last_error_;
    bool torn_repaired_any_ = false;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_AUDIT_TRAIL_HPP
