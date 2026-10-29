// Durable offline spool implementation. See durable_spool.hpp for contract.
//
// Journal layout (<dir>/spool.log), append-only, never pruned:
//   record := "KSS1" | BE32(payload_len) | payload (canonical JSON bytes)
//             | sha256hex(payload, 64 ASCII) | "KSSC"
// A torn write can only leave a PREFIX of a record (short framing, short
// payload, short digest, or missing commit marker): any record whose framed
// size exceeds the remaining file bytes is a torn tail and is truncated on
// open. A fully-present record with a bad magic/sha/commit is corruption
// and fails closed (it may be committed data; truncating it would be loss).
//
// Receipt layout (<dir>/acked.log), append-only text:
//   line := "v1" SP msg_id SP sha256hex(msg_id) LF
// A trailing line without its LF (or otherwise malformed at EOF) is a torn
// receipt and is truncated on open. A malformed line with more lines after
// it is interior corruption: fail closed.
#include "ksi_safety/transport/durable_spool.hpp"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "ksi_safety/transport/digest.hpp"

namespace ksi::safety {
namespace {

const char kDataMagic[4] = {'K', 'S', 'S', '1'};
const char kTombMagic[4] = {'K', 'S', 'S', 'T'};  // compacted ack record
const char kDataCommit[4] = {'K', 'S', 'S', 'C'};
constexpr std::size_t kHeaderSize = 8;    // magic(4) + BE32 len
constexpr std::size_t kDigestSize = 64;   // sha256 hex ASCII
constexpr std::size_t kCommitSize = 4;
constexpr std::size_t kOverhead = kHeaderSize + kDigestSize + kCommitSize;
constexpr std::uint32_t kMaxPayload = 16u * 1024u * 1024u;  // 16 MiB sanity
constexpr std::uint32_t kMaxTombId = 4096;  // msg_id sanity for tombstones
constexpr const char* kDataName = "spool.log";
constexpr const char* kAckName = "acked.log";
constexpr const char* kTmpSuffix = ".tmp";

void PutU32BE(std::string* out, std::uint32_t value) {
    out->push_back(static_cast<char>((value >> 24) & 0xFF));
    out->push_back(static_cast<char>((value >> 16) & 0xFF));
    out->push_back(static_cast<char>((value >> 8) & 0xFF));
    out->push_back(static_cast<char>(value & 0xFF));
}

bool GetU32BE(const std::string& data, std::size_t offset,
              std::uint32_t* value) {
    if (data.size() - offset < 4) return false;
    const auto* p =
        reinterpret_cast<const unsigned char*>(data.data() + offset);
    *value = (static_cast<std::uint32_t>(p[0]) << 24) |
             (static_cast<std::uint32_t>(p[1]) << 16) |
             (static_cast<std::uint32_t>(p[2]) << 8) |
             static_cast<std::uint32_t>(p[3]);
    return true;
}

// Full record bytes (deterministic re-frame: identical input => identical
// bytes, so compaction never alters unacked data).
std::string FrameRecord(const std::string& canonical,
                        const std::string& digest_hex) {
    std::string out;
    out.append(kDataMagic, 4);
    PutU32BE(&out, static_cast<std::uint32_t>(canonical.size()));
    out += canonical;
    out += digest_hex;
    out.append(kDataCommit, kCommitSize);
    return out;
}

// Tombstone bytes: same framing shape, distinct magic, id instead of
// payload. Compact and small, but carrying the full identity needed for
// dedup (same id + digest) vs conflict (same id, different digest).
std::string FrameTombstone(const std::string& msg_id,
                           const std::string& digest_hex) {
    std::string out;
    out.append(kTombMagic, 4);
    PutU32BE(&out, static_cast<std::uint32_t>(msg_id.size()));
    out += msg_id;
    out += digest_hex;
    out.append(kDataCommit, kCommitSize);
    return out;
}


bool ReadWholeFile(const std::string& path, std::string* out,
                   std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "open for read failed: " + path + ": " + ::strerror(errno);
        return false;
    }
    std::string data;
    char buf[65536];
    for (;;) {
        const ssize_t n = ::read(fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            *error =
                "read failed: " + path + ": " + ::strerror(errno);
            ::close(fd);
            return false;
        }
        if (n == 0) break;
        data.append(buf, static_cast<std::size_t>(n));
    }
    ::close(fd);
    *out = std::move(data);
    return true;
}

bool WriteAll(int fd, const char* data, std::size_t len, std::string* error) {    std::size_t done = 0;
    while (done < len) {
        const ssize_t n =
            ::write(fd, data + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            *error = "write failed: " + std::string(::strerror(errno));
            return false;
        }
        done += static_cast<std::size_t>(n);
    }
    return true;
}

// Rollback truncation is best effort: the caller already reports its own
// error, and the retry path dedups by msg_id even if these bytes survive.
// Centralised so -Werror=unused-result stays satisfied.
void TruncateBestEffort(int fd, std::uint64_t size) {
    if (::ftruncate(fd, static_cast<off_t>(size)) != 0) {
        // Intentionally ignored (see above).
    }
}

}  // namespace

namespace {

bool IsHex64(const std::string& text) {
    if (text.size() != 64) return false;
    for (char ch : text) {
        const bool hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
        if (!hex) return false;
    }
    return true;
}

// Bytes that would be freed by compacting one acked full record into a
// tombstone (framed record minus framed tombstone).
uint64_t FramedBytes(uint64_t payload_len) {
    return static_cast<uint64_t>(kOverhead) + payload_len;
}

}  // namespace

const char* SpoolStatusName(SpoolStatus status) {
    switch (status) {
        case SpoolStatus::kOk: return "ok";
        case SpoolStatus::kDuplicate: return "duplicate";
        case SpoolStatus::kNotFound: return "not_found";
        case SpoolStatus::kConflict: return "conflict";
        case SpoolStatus::kFull: return "full";
        case SpoolStatus::kInvalid: return "invalid";
        case SpoolStatus::kCorrupt: return "corrupt";
        case SpoolStatus::kIoError: return "io_error";
    }
    return "unknown";
}

struct DurableSpool::Impl {
    std::string dir;
    std::uint64_t capacity = 0;
    SpoolHooks hooks;
    mutable std::mutex mutex;

    int data_fd = -1;
    int ack_fd = -1;
    std::uint64_t data_size = 0;
    std::uint64_t ack_size = 0;

    struct Record {
        std::string msg_id;
        std::string canonical;  // exact canonical envelope bytes ("" if tombstoned)
        std::string digest_hex;  // sha256 of the canonical bytes (always set)
        bool is_tombstone = false;  // acked payload replaced by (id, digest)
    };
    // Append order; index into records_ never reused.
    std::vector<Record> records;
    std::unordered_map<std::string, std::size_t> index_by_id;
    std::unordered_map<std::string, bool> acked;
    // Acked-but-uncompacted full-record bytes (O(1) pressure check).
    std::uint64_t reclaimable_bytes_ = 0;

    bool ok = false;
    SpoolStatus open_status = SpoolStatus::kIoError;
    std::string last_error;
    bool torn_repaired = false;

    int SyncFd(int fd) const {
        if (hooks.fsync) return hooks.fsync(fd);
        return ::fsync(fd);
    }

    bool SyncDir(std::string* error) const {
        const int fd = ::open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0) {
            *error = "open dir for sync failed: " + dir + ": " +
                     ::strerror(errno);
            return false;
        }
        const int rc = SyncFd(fd);
        const int saved = errno;
        ::close(fd);
        if (rc != 0) {
            *error = "directory fsync failed: " + dir + ": " +
                     ::strerror(saved);
            return false;
        }
        return true;
    }
};

namespace {

// Creates every component of path with mode 0700. Fails if an existing
// component is not a directory.
bool EnsureDirAll(const std::string& path, std::string* error) {
    if (path.empty() || path.find('\0') != std::string::npos) {
        *error = "refusing unsafe spool path";
        return false;
    }
    std::string prefix;
    std::string rest = path;
    if (!rest.empty() && rest[0] == '/') {
        prefix = "/";
        rest = rest.substr(1);
    }
    std::size_t pos = 0;
    while (pos <= rest.size()) {
        const std::size_t slash = rest.find('/', pos);
        const std::string comp = (slash == std::string::npos)
                                     ? rest.substr(pos)
                                     : rest.substr(pos, slash - pos);
        pos = (slash == std::string::npos) ? rest.size() + 1 : slash + 1;
        if (comp.empty() || comp == ".") continue;
        if (comp == "..") {
            *error = "refusing spool path with '..': " + path;
            return false;
        }
        if (!prefix.empty() && prefix.back() != '/') prefix += '/';
        prefix += comp;
        if (::mkdir(prefix.c_str(), 0700) != 0 && errno != EEXIST) {
            *error = "mkdir failed: " + prefix + ": " + ::strerror(errno);
            return false;
        }
        struct stat st;
        if (::stat(prefix.c_str(), &st) != 0) {
            *error = "stat failed: " + prefix + ": " + ::strerror(errno);
            return false;
        }
        if (!S_ISDIR(st.st_mode)) {
            *error = "spool path component is not a directory: " + prefix;
            return false;
        }
    }
    return true;
}

}  // namespace

DurableSpool::DurableSpool(std::string dir_path, std::uint64_t capacity_bytes)
    : DurableSpool(std::move(dir_path), capacity_bytes, SpoolHooks{}) {}

DurableSpool::DurableSpool(std::string dir_path, std::uint64_t capacity_bytes,
                           SpoolHooks hooks)
    : impl_(new Impl()) {
    impl_->dir = std::move(dir_path);
    impl_->capacity = capacity_bytes;
    impl_->hooks = std::move(hooks);
    Impl& s = *impl_;

    auto fail = [&](SpoolStatus status, const std::string& message) {
        s.ok = false;
        s.open_status = status;
        s.last_error = message;
    };

    if (s.dir.empty() || s.dir.find('\0') != std::string::npos) {
        fail(SpoolStatus::kInvalid, "spool path is empty or unsafe");
        return;
    }
    if (s.capacity == 0) {
        fail(SpoolStatus::kInvalid, "spool capacity must be > 0 bytes");
        return;
    }
    std::string error;
    if (!EnsureDirAll(s.dir, &error)) {
        fail(SpoolStatus::kInvalid, error);
        return;
    }

    const std::string data_path = s.dir + "/" + kDataName;
    const std::string ack_path = s.dir + "/" + kAckName;

    struct stat st;
    const bool data_exists = (::stat(data_path.c_str(), &st) == 0);
    const bool ack_exists = (::stat(ack_path.c_str(), &st) == 0);

    s.data_fd =
        ::open(data_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC,
               0600);
    if (s.data_fd < 0) {
        fail(SpoolStatus::kIoError,
             "open spool.log failed: " + std::string(::strerror(errno)));
        return;
    }
    s.ack_fd =
        ::open(ack_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC,
               0600);
    if (s.ack_fd < 0) {
        ::close(s.data_fd);
        s.data_fd = -1;
        fail(SpoolStatus::kIoError,
             "open acked.log failed: " + std::string(::strerror(errno)));
        return;
    }
    if (!data_exists || !ack_exists) {
        // New file names must survive a crash: sync the directory.
        if (!s.SyncDir(&error)) {
            ::close(s.data_fd);
            ::close(s.ack_fd);
            s.data_fd = s.ack_fd = -1;
            fail(SpoolStatus::kIoError, error);
            return;
        }
    }

    // --- replay the data journal -------------------------------------------
    std::string journal;
    if (!ReadWholeFile(data_path, &journal, &error)) {
        fail(SpoolStatus::kIoError, error);
        return;
    }
    std::size_t offset = 0;
    const std::size_t total = journal.size();
    while (offset < total) {
        const std::size_t remaining = total - offset;
        if (remaining < kHeaderSize) {
            // Torn header: prefix of a record. Truncate.
            if (::ftruncate(s.data_fd, static_cast<off_t>(offset)) != 0) {
                fail(SpoolStatus::kIoError,
                     "torn tail truncate failed: " +
                         std::string(::strerror(errno)));
                return;
            }
            s.SyncFd(s.data_fd);  // best effort; re-repaired on next open
            s.torn_repaired = true;
            break;
        }
        const bool is_tombstone =
            std::memcmp(journal.data() + offset, kTombMagic, 4) == 0;
        if (!is_tombstone &&
            std::memcmp(journal.data() + offset, kDataMagic, 4) != 0) {
            fail(SpoolStatus::kCorrupt,
                 "spool.log: bad record magic (interior corruption)");
            return;
        }
        std::uint32_t len = 0;
        if (!GetU32BE(journal, offset + 4, &len)) {
            fail(SpoolStatus::kCorrupt, "spool.log: truncated length field");
            return;
        }
        if (!is_tombstone && len > kMaxPayload) {
            fail(SpoolStatus::kCorrupt, "spool.log: absurd record length");
            return;
        }
        if (is_tombstone && (len == 0 || len > kMaxTombId)) {
            fail(SpoolStatus::kCorrupt,
                 "spool.log: absurd tombstone id length");
            return;
        }
        const std::uint64_t need =
            static_cast<std::uint64_t>(kOverhead) + len;
        if (static_cast<std::uint64_t>(remaining) < need) {
            // Torn payload/digest/commit: truncate to last good record.
            // Same rule for tombstones: a torn tail is always truncated.
            if (::ftruncate(s.data_fd, static_cast<off_t>(offset)) != 0) {
                fail(SpoolStatus::kIoError,
                     "torn tail truncate failed: " +
                         std::string(::strerror(errno)));
                return;
            }
            s.SyncFd(s.data_fd);  // best effort; re-repaired on next open
            s.torn_repaired = true;
            break;
        }
        const std::string body =
            journal.substr(offset + kHeaderSize, len);
        const std::string stored_digest = journal.substr(
            offset + kHeaderSize + len, kDigestSize);
        const char* commit =
            journal.data() + offset + kHeaderSize + len + kDigestSize;
        if (std::memcmp(commit, kDataCommit, kCommitSize) != 0) {
            fail(SpoolStatus::kCorrupt,
                 "spool.log: bad commit marker (interior corruption)");
            return;
        }
        if (!IsHex64(stored_digest)) {
            fail(SpoolStatus::kCorrupt,
                 "spool.log: malformed digest field (interior corruption)");
            return;
        }
        if (is_tombstone) {
            // Tombstone: acked full payload replaced by (id, original
            // digest). The digest field is the ORIGINAL payload hash (used
            // for dedup vs conflict); the record is acked by construction.
            const std::string& tomb_id = body;
            if (tomb_id.empty()) {
                fail(SpoolStatus::kCorrupt,
                     "spool.log: tombstone without msg_id");
                return;
            }
            const auto seen = s.index_by_id.find(tomb_id);
            if (seen != s.index_by_id.end()) {
                Impl::Record& prev = s.records[seen->second];
                if (prev.digest_hex != stored_digest) {
                    fail(SpoolStatus::kCorrupt,
                         "spool.log: tombstone conflicts with recorded id " +
                             tomb_id);
                    return;
                }
                // Same id + same digest: the full record converts to the
                // tombstone form (it was acked before compaction).
                prev.canonical.clear();
                prev.is_tombstone = true;
                s.acked[tomb_id] = true;
            } else {
                Impl::Record record;
                record.msg_id = tomb_id;
                record.digest_hex = stored_digest;
                record.is_tombstone = true;
                s.index_by_id[tomb_id] = s.records.size();
                s.records.push_back(std::move(record));
                s.acked[tomb_id] = true;
            }
            offset += static_cast<std::size_t>(need);
            continue;
        }
        if (stored_digest != Sha256Hex(body)) {
            // Fully present but checksum mismatch: may be committed data.
            // Fail closed; never truncate.
            fail(SpoolStatus::kCorrupt,
                 "spool.log: record sha256 mismatch (interior corruption)");
            return;
        }
        const std::string& payload = body;
        JsonValue envelope;
        std::string parse_error;
        if (!ParseJson(payload, &envelope, &parse_error) ||
            !envelope.IsObject() || envelope.Find("msg_id") == nullptr ||
            !envelope.Find("msg_id")->IsString() ||
            envelope.Find("msg_id")->strValue().empty()) {
            fail(SpoolStatus::kCorrupt,
                 "spool.log: record is not an envelope with msg_id");
            return;
        }
        const std::string msg_id = envelope.Find("msg_id")->strValue();
        const auto seen = s.index_by_id.find(msg_id);
        if (seen != s.index_by_id.end()) {
            Impl::Record& prev = s.records[seen->second];
            const std::string& prev_digest = prev.digest_hex;
            if (prev_digest != stored_digest) {
                fail(SpoolStatus::kCorrupt,
                     "spool.log: msg_id recorded with different bytes");
                return;
            }
            // Same id + same digest already journalled (full or tombstoned
            // form): keep first position.
        } else {
            Impl::Record record;
            record.msg_id = msg_id;
            record.canonical = payload;
            record.digest_hex = stored_digest;
            s.index_by_id[msg_id] = s.records.size();
            s.records.push_back(std::move(record));
        }
        offset += static_cast<std::size_t>(need);
    }
    s.data_size = offset;

    // --- replay the receipt log --------------------------------------------
    std::string receipts;
    if (!ReadWholeFile(ack_path, &receipts, &error)) {
        fail(SpoolStatus::kIoError, error);
        return;
    }
    std::size_t line_start = 0;
    const std::size_t rtotal = receipts.size();
    while (line_start < rtotal) {
        const std::size_t nl = receipts.find('\n', line_start);
        if (nl == std::string::npos) {
            // Torn receipt (no terminator): truncate.
            if (::ftruncate(s.ack_fd, static_cast<off_t>(line_start)) != 0) {
                fail(SpoolStatus::kIoError,
                     "torn receipt truncate failed: " +
                         std::string(::strerror(errno)));
                return;
            }
            s.SyncFd(s.ack_fd);  // best effort
            s.torn_repaired = true;
            break;
        }
        const std::string line = receipts.substr(line_start, nl - line_start);
        const bool is_last = (nl + 1 == rtotal);
        const std::size_t sp1 = line.find(' ');
        const std::size_t sp2 =
            (sp1 == std::string::npos) ? std::string::npos
                                       : line.find(' ', sp1 + 1);
        const bool well_formed =
            sp1 != std::string::npos && sp2 != std::string::npos &&
            line.find(' ', sp2 + 1) == std::string::npos &&
            line.compare(0, 2, "v1") == 0 && sp1 == 2 && sp2 > sp1 + 1;
        std::string ack_id;
        bool checksum_ok = false;
        if (well_formed) {
            ack_id = line.substr(sp1 + 1, sp2 - sp1 - 1);
            checksum_ok =
                !ack_id.empty() &&
                line.substr(sp2 + 1) == Sha256Hex(ack_id);
        }
        if (!well_formed || !checksum_ok) {
            if (is_last) {
                // Trailing corrupt receipt: dropping an ack only causes a
                // same-ID replay (safe direction), so truncate the tail.
                if (::ftruncate(s.ack_fd,
                                static_cast<off_t>(line_start)) != 0) {
                    fail(SpoolStatus::kIoError,
                         "torn receipt truncate failed: " +
                             std::string(::strerror(errno)));
                    return;
                }
                s.SyncFd(s.ack_fd);  // best effort
                s.torn_repaired = true;
                break;
            }
            fail(SpoolStatus::kCorrupt,
                 "acked.log: malformed receipt (interior corruption)");
            return;
        }
        s.acked[ack_id] = true;
        line_start = nl + 1;
    }
    s.ack_size = line_start;

    // Receipts for unknown ids indicate a torn/mismatched directory pair.
    // Tombstoned ids are always registered (compacted only when acked), so
    // they satisfy this check by construction.
    for (const auto& entry : s.acked) {
        if (s.index_by_id.find(entry.first) == s.index_by_id.end()) {
            fail(SpoolStatus::kCorrupt,
                 "acked.log: receipt for unknown msg_id " + entry.first);
            return;
        }
    }
    for (const Impl::Record& record : s.records) {
        if (!record.is_tombstone &&
            s.acked.find(record.msg_id) != s.acked.end()) {
            s.reclaimable_bytes_ += FramedBytes(record.canonical.size());
        }
    }

    s.ok = true;
    s.open_status = SpoolStatus::kOk;
}

DurableSpool::~DurableSpool() {
    if (impl_ == nullptr) return;
    if (impl_->data_fd >= 0) ::close(impl_->data_fd);
    if (impl_->ack_fd >= 0) ::close(impl_->ack_fd);
    delete impl_;
}

bool DurableSpool::ok() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->ok;
}

SpoolStatus DurableSpool::open_status() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->open_status;
}

std::string DurableSpool::last_error() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->last_error;
}

bool DurableSpool::torn_repaired() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->torn_repaired;
}

std::uint64_t DurableSpool::capacity_bytes() const { return impl_->capacity; }

AppendResult DurableSpool::Append(const JsonValue& envelope) {
    AppendResult result;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Impl& s = *impl_;
    if (!s.ok) {
        result.status = SpoolStatus::kCorrupt;
        result.error = s.last_error;
        return result;
    }
    if (!envelope.IsObject()) {
        result.status = SpoolStatus::kInvalid;
        result.error = "envelope must be a JSON object (payload opaque)";
        return result;
    }
    const JsonValue* msg_id_value = envelope.Find("msg_id");
    if (msg_id_value == nullptr || !msg_id_value->IsString() ||
        msg_id_value->strValue().empty()) {
        result.status = SpoolStatus::kInvalid;
        result.error = "envelope lacks a non-empty string msg_id";
        return result;
    }
    const std::string msg_id = msg_id_value->strValue();

    std::string canonical;
    try {
        canonical = WriteCanonicalJson(envelope);
    } catch (const std::exception& ex) {
        result.status = SpoolStatus::kInvalid;
        result.error =
            std::string("envelope not canonical-serialisable: ") + ex.what();
        return result;
    }
    if (canonical.size() > kMaxPayload) {
        result.status = SpoolStatus::kInvalid;
        result.error = "envelope exceeds 16 MiB record limit";
        return result;
    }
    const std::string digest = Sha256Hex(canonical);

    const auto seen = s.index_by_id.find(msg_id);
    if (seen != s.index_by_id.end()) {
        // Tombstoned ids carry only (id, digest): compare digests, since the
        // payload bytes were compacted away. Same outcome as the full form.
        if (s.records[seen->second].digest_hex != digest) {
            result.status = SpoolStatus::kConflict;
            result.error = "msg_id already queued with different bytes: " +
                           msg_id;
            return result;
        }
        result.status = SpoolStatus::kDuplicate;
        result.duplicate = true;
        result.digest_hex = digest;
        return result;
    }

    std::string framed;
    framed.append(kDataMagic, 4);
    PutU32BE(&framed, static_cast<std::uint32_t>(canonical.size()));
    framed += canonical;
    framed += digest;
    framed.append(kDataCommit, kCommitSize);

    if (s.data_size + s.ack_size + framed.size() > s.capacity) {
        result.status = SpoolStatus::kFull;
        result.error = "spool capacity exhausted (no drop-oldest; bytes_used=" +
                       std::to_string(s.data_size + s.ack_size) + ")";
        return result;
    }

    const std::uint64_t base = s.data_size;
    std::string io_error;
    if (!WriteAll(s.data_fd, framed.data(), framed.size(), &io_error)) {
        TruncateBestEffort(s.data_fd, base);  // best effort
        s.SyncFd(s.data_fd);                               // best effort
        result.status = SpoolStatus::kIoError;
        result.error = io_error;
        s.last_error = io_error;
        return result;
    }
    if (s.SyncFd(s.data_fd) != 0) {
        const std::string sync_error =
            "spool.log fsync failed: " + std::string(::strerror(errno));
        // Roll back: the record was NOT reported queued, so the caller will
        // retry; the retry dedups by msg_id even if these bytes survive.
        TruncateBestEffort(s.data_fd, base);  // best effort
        s.SyncFd(s.data_fd);                               // best effort
        result.status = SpoolStatus::kIoError;
        result.error = sync_error;
        s.last_error = sync_error;
        return result;
    }

    Impl::Record record;
    record.msg_id = msg_id;
    record.canonical = canonical;
    record.digest_hex = digest;
    s.index_by_id[msg_id] = s.records.size();
    s.records.push_back(std::move(record));
    s.data_size = base + framed.size();

    result.status = SpoolStatus::kOk;
    result.digest_hex = digest;
    return result;
}

AckResult DurableSpool::Acknowledge(const std::string& msg_id) {
    AckResult result;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Impl& s = *impl_;
    if (!s.ok) {
        result.status = SpoolStatus::kCorrupt;
        result.error = s.last_error;
        return result;
    }
    if (msg_id.empty()) {
        result.status = SpoolStatus::kInvalid;
        result.error = "msg_id must not be empty";
        return result;
    }
    if (s.index_by_id.find(msg_id) == s.index_by_id.end()) {
        result.status = SpoolStatus::kNotFound;
        result.error = "unknown msg_id: " + msg_id;
        return result;
    }
    if (s.acked.find(msg_id) != s.acked.end()) {
        result.status = SpoolStatus::kDuplicate;
        result.duplicate = true;
        return result;
    }

    const std::string line = "v1 " + msg_id + " " + Sha256Hex(msg_id) + "\n";
    if (s.data_size + s.ack_size + line.size() > s.capacity) {
        result.status = SpoolStatus::kFull;
        result.error =
            "spool capacity exhausted; msg stays pending (never pruned)";
        return result;
    }

    const std::uint64_t base = s.ack_size;
    std::string io_error;
    if (!WriteAll(s.ack_fd, line.data(), line.size(), &io_error)) {
        TruncateBestEffort(s.ack_fd, base);  // best effort
        s.SyncFd(s.ack_fd);                               // best effort
        result.status = SpoolStatus::kIoError;
        result.error = io_error;
        s.last_error = io_error;
        return result;
    }
    if (s.SyncFd(s.ack_fd) != 0) {
        const std::string sync_error =
            "acked.log fsync failed: " + std::string(::strerror(errno));
        TruncateBestEffort(s.ack_fd, base);  // best effort
        s.SyncFd(s.ack_fd);                               // best effort
        result.status = SpoolStatus::kIoError;
        result.error = sync_error;
        s.last_error = sync_error;
        return result;
    }

    s.acked[msg_id] = true;
    s.ack_size = base + line.size();
    const auto& rec = s.records[s.index_by_id[msg_id]];
    if (!rec.is_tombstone) {
        // Newly acked full payload: becomes reclaimable on next Compact.
        s.reclaimable_bytes_ +=
            FramedBytes(static_cast<std::uint64_t>(rec.canonical.size()));
    }
    result.status = SpoolStatus::kOk;
    return result;
}

CompactResult DurableSpool::CompactLocked(Impl& s) {
    CompactResult result;
    // Count reclaimable acked full records first: a no-op when there is
    // nothing to compact keeps the hot path O(1) (no file IO at all).
    std::size_t reclaimable = 0;
    for (const DurableSpool::Impl::Record& record : s.records) {
        if (!record.is_tombstone &&
            s.acked.find(record.msg_id) != s.acked.end()) {
            ++reclaimable;
        }
    }
    if (reclaimable == 0) {
        result.status = SpoolStatus::kOk;
        return result;
    }
    // Build the new image in memory: unacked payloads verbatim (re-framed
    // deterministically, so bit-identical), acked payloads as tombstones.
    std::string image;
    for (const DurableSpool::Impl::Record& record : s.records) {
        if (record.is_tombstone) {
            image += FrameTombstone(record.msg_id, record.digest_hex);
        } else if (s.acked.find(record.msg_id) != s.acked.end()) {
            image += FrameTombstone(record.msg_id, record.digest_hex);
        } else {
            image += FrameRecord(record.canonical, record.digest_hex);
        }
    }
    const std::string tmp_path = s.dir + "/spool.log" + kTmpSuffix;
    const int tmp_fd = ::open(tmp_path.c_str(),
                              O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (tmp_fd < 0) {
        result.status = SpoolStatus::kIoError;
        result.error =
            "compaction temp open failed: " + std::string(::strerror(errno));
        return result;
    }
    std::string io_error;
    bool io_ok =
        WriteAll(tmp_fd, image.data(), image.size(), &io_error);
    int sync_rc = 0;
    if (io_ok) {
        sync_rc = s.SyncFd(tmp_fd);
        if (sync_rc != 0) {
            io_error = "compaction temp fsync failed: " +
                       std::string(::strerror(errno));
            io_ok = false;
        }
    }
    if (!io_ok) {
        ::close(tmp_fd);
        ::unlink(tmp_path.c_str());  // best effort, original untouched
        result.status = SpoolStatus::kIoError;
        result.error = io_error;
        s.last_error = io_error;
        return result;
    }
    const std::string data_path = s.dir + "/" + kDataName;
    const int rename_rc = s.hooks.rename
                                ? s.hooks.rename(tmp_path.c_str(),
                                                 data_path.c_str())
                                : ::rename(tmp_path.c_str(),
                                           data_path.c_str());
    if (rename_rc != 0) {
        const std::string rename_error =
            "compaction atomic rename failed: " +
            std::string(::strerror(errno));
        ::close(tmp_fd);
        ::unlink(tmp_path.c_str());  // best effort, original untouched
        result.status = SpoolStatus::kIoError;
        result.error = rename_error;
        s.last_error = rename_error;
        return result;
    }
    ::close(tmp_fd);
    // Durability of the rename itself: fsync the directory. A failure here
    // leaves the outcome genuinely unknown (the rename may or may not
    // survive a crash), so fail closed rather than risk silent divergence
    // between memory and disk: the journal on disk stays valid either way
    // (old or new generation, both fully framed), but this handle stops
    // trusting new writes until an operator archives and restarts.
    std::string dir_error;
    if (!s.SyncDir(&dir_error)) {
        ::close(s.data_fd);
        s.data_fd = -1;
        s.ok = false;
        s.open_status = SpoolStatus::kIoError;
        result.status = SpoolStatus::kIoError;
        result.error = dir_error;
        s.last_error = dir_error;
        return result;
    }
    // Atomically switch over: reopen on the new inode, swap the in-memory
    // records for their tombstoned form, update the counters.
    ::close(s.data_fd);
    s.data_fd = ::open((s.dir + "/" + kDataName).c_str(),
                       O_WRONLY | O_APPEND | O_CLOEXEC);
    if (s.data_fd < 0) {
        // The new journal is durable on disk; without a writable fd this
        // process can no longer append. Fail closed (operator archives).
        s.ok = false;
        s.open_status = SpoolStatus::kIoError;
        result.status = SpoolStatus::kIoError;
        result.error = std::string("compaction reopen failed: ") +
                       ::strerror(errno);
        s.last_error = result.error;
        return result;
    }
    std::size_t converted = 0;
    for (DurableSpool::Impl::Record& record : s.records) {
        if (!record.is_tombstone &&
            s.acked.find(record.msg_id) != s.acked.end()) {
            record.canonical.clear();
            record.is_tombstone = true;
            ++converted;
        }
    }
    const std::uint64_t freed =
        s.reclaimable_bytes_ > 0 ? s.reclaimable_bytes_ : 0;
    s.data_size = image.size();
    s.reclaimable_bytes_ = 0;  // pressure counter restarts after a compact
    result.records_compacted = converted;
    result.bytes_reclaimed = freed;
    result.status = SpoolStatus::kOk;
    return result;
}

uint64_t DurableSpool::reclaimable_bytes() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->reclaimable_bytes_;
}

SpoolStatus DurableSpool::EnsureCapacity(std::size_t bytes_needed) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Impl& s = *impl_;
    if (!s.ok) {
        return SpoolStatus::kCorrupt;
    }
    if (s.data_size + s.ack_size + bytes_needed <= s.capacity) {
        return SpoolStatus::kOk;
    }
    // Pressure path: reclaim acked payloads once, then recheck. A second
    // miss means genuinely full of unacked data (or tombstones).
    if (s.reclaimable_bytes_ > 0) {
        CompactResult compacted = CompactLocked(s);
        if (compacted.status != SpoolStatus::kOk) {
            return compacted.status;
        }
    }
    if (s.data_size + s.ack_size + bytes_needed > s.capacity) {
        return SpoolStatus::kFull;
    }
    return SpoolStatus::kOk;
}

CompactResult DurableSpool::Compact() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Impl& s = *impl_;
    if (!s.ok) {
        CompactResult out;
        out.status = SpoolStatus::kCorrupt;
        out.error = s.last_error;
        return out;
    }
    return CompactLocked(s);
}




std::vector<PendingMessage> DurableSpool::Pending() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const Impl& s = *impl_;
    std::vector<PendingMessage> out;
    if (!s.ok) return out;  // fail closed on corruption
    std::uint64_t order = 0;
    for (const Impl::Record& record : s.records) {
        if (record.is_tombstone) continue;  // acked by construction
        if (s.acked.find(record.msg_id) != s.acked.end()) continue;
        PendingMessage message;
        message.msg_id = record.msg_id;
        std::string error;
        if (!ParseJson(record.canonical, &message.envelope, &error)) {
            continue;  // unreachable: parsed at replay; never serve partial
        }
        message.digest_hex = record.digest_hex;
        message.order_index = order++;
        out.push_back(std::move(message));
    }
    return out;
}

std::size_t DurableSpool::pending_count() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const Impl& s = *impl_;
    if (!s.ok) return 0;
    return s.records.size() - s.acked.size();
}

std::size_t DurableSpool::total_records() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->records.size();
}

std::size_t DurableSpool::acked_count() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->acked.size();
}

std::uint64_t DurableSpool::bytes_used() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->data_size + impl_->ack_size;
}

}  // namespace ksi::safety
