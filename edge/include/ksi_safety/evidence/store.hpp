// Source-evidence store (Edge): the origin of observation evidence.
//
// WHY THIS EXISTS
//   The MOP transport spool (edge/src/transport/durable_spool.cpp) keeps
//   *outbound envelopes* so a link drop loses nothing. That is a delivery
//   guarantee, NOT an evidence guarantee: the bytes it holds are the
//   messages we sent, and `Compact()` replaces acked payloads with digest
//   tombstones. The pixels the detector actually consumed were never
//   persisted anywhere.
//
//   This store persists the SOURCE: the exact frame bytes an observation was
//   computed from, plus the lineage needed to re-derive the judgement
//   (flight, boot, frame id, receipt time, observation id, model digest).
//   The RC can then show a reviewable basis for an observation it received
//   over MOP, and a collector can export the same evidence to the Core.
//
// CONTRACT
//   - Atomic asset write: temp file -> fsync(file) -> rename -> fsync(dir).
//     A crash leaves either nothing or the complete file. A leftover temp
//     file is never a committed asset; it is removed on open and counted.
//   - Never overwrite: the same asset id with the same digest is a
//     duplicate (idempotent, no new bytes); the same id with DIFFERENT
//     bytes is a conflict and is refused (the evidence of a given
//     observation must never change under the same id).
//   - Append-only index (`index.ndjson`, one JSON line per asset, fsync per
//     line). A torn trailing line (no terminator) is truncated on open and
//     reported; an interior malformed line fails closed (the file is kept
//     byte-identical for forensics).
//   - Capacity is an explicit bound: exceeding it REFUSES the write. It
//     never evicts, never overwrites, never silently drops. The caller
//     decides what to do (report degraded, stop accepting work).
//   - Sealed revisions are immutable: a revision lists the exact asset ids
//     and digests it covers, is written once, and is never edited.
//   - No clock reads inside the store: the caller stamps monotonic time.
//
// This module is PSDK-free and host-testable (real filesystem in tests).
#ifndef KSI_SAFETY_EVIDENCE_STORE_HPP
#define KSI_SAFETY_EVIDENCE_STORE_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace ksi::safety {

enum class EvidenceStatus {
    kOk,         // stored (or idempotent duplicate)
    kDuplicate,  // same asset id + same digest: already stored
    kConflict,   // same asset id, DIFFERENT digest: refused
    kFull,       // capacity would be exceeded: refused
    kInvalid,    // malformed input: refused
    kIoError,    // write/fsync/rename failed: nothing committed
    kCorrupt,    // index content untrustworthy: fail closed
};

const char* EvidenceStatusName(EvidenceStatus status);

// One persisted source frame and the lineage that ties it to an observation.
struct SourceAsset {
    std::string asset_id;        // uuid5 derived by the caller (stable id)
    std::string sha256_hex;      // digest of the bytes (verified on write)
    std::uint64_t size_bytes = 0;
    std::string media_type;      // e.g. "application/x-raw-rgb24"
    std::string flight_id;
    std::string edge_boot_id;
    std::uint64_t frame_id = 0;
    std::int64_t received_monotonic_ns = 0;
    std::string observation_id;  // links the frame to an observation
};

// Fault injection for fsync/rename failures (host tests).
struct EvidenceStoreHooks {
    // Must behave like ::fsync: 0 on success, -1 with errno on failure.
    std::function<int(int)> fsync;
    // Must behave like ::rename: 0 on success, -1 with errno on failure.
    std::function<int(const char*, const char*)> rename;
};

class EvidenceStore {
 public:
    // Opens (or creates) <dir>/assets + <dir>/revisions. `capacity_bytes`
    // bounds the whole store (assets + index + revisions); 0 means
    // unbounded. Existing files are validated on open: a torn index tail is
    // truncated (tornRepaired()), an interior malformed line fails closed.
    explicit EvidenceStore(std::string dir, std::uint64_t capacity_bytes);
    EvidenceStore(std::string dir, std::uint64_t capacity_bytes,
                  EvidenceStoreHooks hooks);
    ~EvidenceStore();
    EvidenceStore(const EvidenceStore&) = delete;
    EvidenceStore& operator=(const EvidenceStore&) = delete;

    // True when the store opened cleanly and its content is trustworthy.
    bool ok() const;
    EvidenceStatus openStatus() const;
    const std::string& lastError() const;
    // True when open truncated a torn index tail.
    bool tornRepaired() const;
    // Temp files removed on open (never committed assets).
    std::uint64_t tempFilesRemoved() const;

    // Stores the source bytes for `asset`. The digest in `asset.sha256_hex`
    // is verified against the bytes: a mismatch is kInvalid and NOTHING is
    // written (a wrong digest must never be recorded as evidence).
    EvidenceStatus PutSourceAsset(const SourceAsset& asset,
                                   const std::uint8_t* bytes,
                                   std::size_t len);

    // Index reads.
    std::size_t indexCount() const;
    bool ListAssets(std::vector<SourceAsset>* out) const;
    bool HasAsset(const std::string& asset_id, SourceAsset* out) const;
    // True when the stored bytes for `asset_id` still hash to the recorded
    // digest (the file is re-read and hashed; nothing is trusted).
    bool VerifyAsset(const std::string& asset_id, bool* ok_out) const;

    // Usage against the bound.
    std::uint64_t bytesUsed() const;
    std::uint64_t capacityBytes() const;
    std::uint64_t assetBytes() const;

    // Seals one immutable revision covering `required` assets. Writes
    // <dir>/revisions/<revision_id>.json (atomic, never edited). Every
    // listed asset must exist and verify. Returns false with *error set on
    // any failure (nothing is sealed).
    bool SealRevision(const std::string& revision_id,
                      const std::string& flight_id,
                      const std::string& edge_boot_id,
                      const std::vector<SourceAsset>& required,
                      std::int64_t sealed_monotonic_ns,
                      std::string* manifest_out,
                      std::string* error);
    bool ReadRevision(const std::string& revision_id,
                      std::string* manifest_out) const;
    std::vector<std::string> sealedRevisions() const;

 private:
    struct Impl;
    Impl* impl_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_EVIDENCE_STORE_HPP
