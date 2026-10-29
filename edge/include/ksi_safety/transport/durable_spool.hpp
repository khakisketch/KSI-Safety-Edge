// Durable offline spool for verified KSI envelopes (MOP drop survival).
//
// Role: Edge observation CONTINUES through a MOP link drop. This spool
// durably records every verified envelope BEFORE it is reported as queued,
// and replays the same msg_ids in order after reconnect or process restart.
// The Edge run-control / MOP endpoint owner (not this component) decides
// what to enqueue, when to retry, and when to ACK; this component only
// guarantees: no silent loss, no false success, no silent corruption.
//
// Durability contract
//  - Append serialises the envelope to RFC 8785 canonical bytes (the SAME
//    bytes WriteCanonicalJson produces everywhere), appends one framed
//    record to <dir>/spool.log, fsyncs the file, and only then reports
//    success. Payload bytes are opaque; flight_id / edge_boot_id / msg_id /
//    channel / seq are preserved bit-for-bit.
//  - Acknowledge appends one receipt line to <dir>/acked.log, fsyncs, and
//    only then marks the msg_id delivered. Unknown msg_ids are rejected
//    (kNotFound); only an exact msg_id match delivers.
//  - At-least-once: data is never pruned. Acked records stay in the journal
//    as compact digest tombstones (see Compact); there is no "drop oldest",
//    and no deletion of unacked data under any circumstance. Unacked bytes
//    are always preserved bit-for-bit with identical msg_id/seq/flight/boot.
 //  - Tombstones: Compact() replaces acked full payloads with small
//    "KSST" records carrying (msg_id, original sha256). Dedup/conflict
//    semantics are identical before and after compaction (same id + same
//    bytes is a duplicate; same id + different bytes is a conflict), and a
//    torn tail truncates the same way. Interior corruption still fails
//    closed.
//  - Torn tail: a crash mid-write leaves a truncated trailing record. On
//    open this is detected (framing claims more bytes than the file holds,
//    or a receipt line lacks its terminator) and the tail is truncated back
//    to the last committed record. torn_repaired() reports this; all
//    previously committed records are preserved.
//  - Interior corruption (bad magic / length / sha256 / commit marker in a
//    fully-present record, or a bad receipt line with more data after it)
//    is NEVER silently skipped: the spool opens failed (ok() == false) and
//    every mutating/query operation fails closed with kCorrupt until the
//    operator archives the directory and starts a fresh spool.
//  - A fully-present trailing record with a bad checksum is ALSO fail
//    closed (not truncated): it may be a previously committed record, and
//    truncating it would be silent loss.
//
// Capacity
//  - capacity_bytes bounds total journal bytes (spool.log + acked.log).
//    Append/Acknowledge beyond capacity fail with kFull and change nothing.
//    bytes_used() grows with appends and SHRINKS on Compact() (tombstones
//    replace acked payloads); it never exceeds capacity. When the journal
//    is full, the operator archives the directory (only once
//    pending_count() == 0 and the receiver has confirmed the replayed
//    range) — there is no automatic deletion and no compaction of unacked
//    data. Tombstone growth is still bounded: a full journal of
//    tombstones fails honestly with kFull.
//
// Threading: all methods are safe to call from multiple threads (internal
// mutex). No background threads, no clock, no RNG. Callers supply all ids
// and timestamps inside the envelopes they append.
//
// Errors: fsync behaviour is injectable via SpoolHooks for fault tests; an
// fsync failure makes the in-flight Append/Acknowledge return kIoError
// WITHOUT changing in-memory state (the caller retries; the retry is
// idempotent by msg_id). A transient fsync failure never bricks the spool;
// only unreadable/corrupt content sets ok() == false.
#ifndef KSI_SAFETY_TRANSPORT_DURABLE_SPOOL_HPP
#define KSI_SAFETY_TRANSPORT_DURABLE_SPOOL_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

enum class SpoolStatus {
    kOk,        // success (or idempotent duplicate where noted)
    kDuplicate, // same msg_id + same bytes appended / acked again
    kNotFound,  // Acknowledge for an unknown msg_id
    kConflict,  // same msg_id with DIFFERENT bytes (refused, state unchanged)
    kFull,      // capacity_bytes would be exceeded (refused, state unchanged)
    kInvalid,   // malformed envelope / empty msg_id / bad path / bad capacity
    kCorrupt,   // spool content untrustworthy; fail closed
    kIoError,   // read/write/sync failure; in-memory state unchanged
};

const char* SpoolStatusName(SpoolStatus status);

// Fault injection for fsync/rename failures. Empty members mean the real
// syscalls. Used by host fault tests (Mid-append fsync failure, atomic
// replace failure): any failure returns kIoError with in-memory and
// on-disk state unchanged (no false success, no partial commit).
struct SpoolHooks {
    // Must behave like ::fsync: 0 on success, -1 with errno on failure.
    std::function<int(int)> fsync;
    // Must behave like ::rename: 0 on success, -1 with errno on failure.
    std::function<int(const char*, const char*)> rename;
};

struct AppendResult {
    SpoolStatus status = SpoolStatus::kIoError;
    // Lowercase hex sha256 of the canonical envelope bytes. Set on kOk and
    // kDuplicate; empty otherwise.
    std::string digest_hex;
    bool duplicate = false;  // true when status == kDuplicate
    std::string error;       // human-readable detail on failure
    bool ok() const {
        return status == SpoolStatus::kOk ||
               status == SpoolStatus::kDuplicate;
    }
};

struct AckResult {
    SpoolStatus status = SpoolStatus::kIoError;
    bool duplicate = false;  // true when the msg_id was already acked
    std::string error;
    bool ok() const {
        return status == SpoolStatus::kOk ||
               status == SpoolStatus::kDuplicate;
    }
};

struct PendingMessage {
    std::string msg_id;
    JsonValue envelope;     // exact copy of the appended envelope
    std::string digest_hex;  // sha256 of the canonical envelope bytes
    std::uint64_t order_index = 0;  // append order (0-based, stable)
};

struct CompactResult {
    SpoolStatus status = SpoolStatus::kIoError;
    // Acked full payloads replaced by tombstones (0 == nothing to do).
    std::size_t records_compacted = 0;
    // Journal bytes freed by this compaction.
    std::uint64_t bytes_reclaimed = 0;
    std::string error;  // human-readable detail on failure
    bool ok() const { return status == SpoolStatus::kOk; }
};

class DurableSpool {
 public:
    // Opens (or creates) the spool directory at dir_path with the given
    // byte capacity. Creates <dir>/spool.log and <dir>/acked.log on first
    // use (0600 files, 0700 dirs, directory fsync). Replays both journals;
    // a torn trailing record is truncated (torn_repaired() == true),
    // interior corruption leaves ok() == false. Never throws: check ok().
    explicit DurableSpool(std::string dir_path, std::uint64_t capacity_bytes);
    DurableSpool(std::string dir_path, std::uint64_t capacity_bytes,
                 SpoolHooks hooks);
    ~DurableSpool();

    DurableSpool(const DurableSpool&) = delete;
    DurableSpool& operator=(const DurableSpool&) = delete;

    // True when the spool opened cleanly and content is trustworthy.
    // When false, Append/Acknowledge return kCorrupt and Pending is empty.
    bool ok() const;
    SpoolStatus open_status() const;
    std::string last_error() const;
    // True when open truncated a torn trailing record/receipt.
    bool torn_repaired() const;

    // Durably queue one verified envelope. The envelope must be an object
    // with a non-empty string "msg_id". Same msg_id + same canonical bytes
    // is idempotent (kDuplicate, no new bytes written). Same msg_id with
    // different bytes is kConflict. Reports success only after fsync.
    AppendResult Append(const JsonValue& envelope);

    // Durably mark msg_id delivered. Unknown ids are kNotFound; re-acking
    // an acked id is kDuplicate (success). Reports success only after the
    // receipt fsync. Never deletes journal data.
    AckResult Acknowledge(const std::string& msg_id);

    // Atomically replace every acked full payload with a compact digest
    // tombstone (msg_id + original sha256 preserved): same ids stay
    // dedupable/ackable, same-bytes duplicates stay duplicates, different
    // bytes stay conflicts, and unacked bytes are never touched. The ACK
    // log is left as-is. Procedure: build the new image in memory, write
    // it to spool.log.tmp + fsync, rename over spool.log, fsync the
    // directory. Any failure returns kIoError with in-memory AND on-disk
    // state unchanged (no partial commit, no false success). No-op (kOk,
    // zero counts) when nothing is reclaimable.
    CompactResult Compact();

    // Upper bound on bytes a Compact() call would reclaim right now (O(1)
    // counter, no scan): acked-but-uncompacted payload bytes. The endpoint
    // uses it as a pressure check before declaring durable failure.
    std::uint64_t reclaimable_bytes() const;

    // Ensure room for bytes_needed more journal bytes, compacting acked
    // payloads once on pressure. Returns kOk when there is room, kFull
    // only when genuinely full of unacked data (or tombstones), kCorrupt
    // when the spool is untrustworthy, kIoError when the journal/rename
    // fails (state unchanged). Callers must NOT treat kFull as license to
    // drop: it means stop accepting new work and report it.
    SpoolStatus EnsureCapacity(std::size_t bytes_needed);

    // Ordered copies of all unacked envelopes (append order, stable across
    // restarts). Empty when !ok() (fail closed).
    std::vector<PendingMessage> Pending() const;

    std::size_t pending_count() const;
    std::size_t total_records() const;  // ever appended (incl. acked)
    std::size_t acked_count() const;
    std::uint64_t bytes_used() const;  // spool.log + acked.log bytes
    std::uint64_t capacity_bytes() const;

  private:
    struct Impl;
    Impl* impl_;
    // In-place compaction worker (caller holds the mutex).
    static CompactResult CompactLocked(Impl& s);
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_DURABLE_SPOOL_HPP
