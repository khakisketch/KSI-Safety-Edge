// Bounded command/state audit journal. See audit_trail.hpp for the contract.

#include "ksi_safety/transport/audit_trail.hpp"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <utility>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {
namespace {

bool WriteAllBytes(int fd, const char* data, std::size_t len) {
    std::size_t done = 0;
    while (done < len) {
        const ssize_t n = ::write(fd, data + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        done += static_cast<std::size_t>(n);
    }
    return true;
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
            *error = "read failed: " + path + ": " + ::strerror(errno);
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

// Journal file name for one boot. Sanitised so the name can never escape
// the state dir (only [A-Za-z0-9_.-] pass through).
std::string JournalFileName(const std::string& boot_id) {
    std::string safe;
    for (char ch : boot_id) {
        const bool ok = (ch >= 'a' && ch <= 'z') ||
                        (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
                        ch == '_' || ch == '.' || ch == '-';
        safe.push_back(ok ? ch : '_');
    }
    if (safe.empty()) safe = "noid";
    return "audit-" + safe + ".log";
}

bool HasBootSuffix(const std::string& name) {
    const std::string prefix = "audit-";
    const std::string suffix = ".log";
    return name.size() > prefix.size() + suffix.size() &&
           name.compare(0, prefix.size(), prefix) == 0 &&
           name.compare(name.size() - suffix.size(), suffix.size(), suffix) ==
               0;
}

}  // namespace

std::string AuditLineageId(const std::string& flight_id,
                           const std::string& edge_boot_id) {
    return Uuid5(MopProtocol::Namespace(),
                 "audit-log|" + flight_id + "|" + edge_boot_id);
}

std::string AuditArtifactId(const std::string& flight_id,
                            const std::string& edge_boot_id,
                            const std::string& artifact_bytes) {
    return Uuid5(MopProtocol::Namespace(),
                 "audit|" + flight_id + "|" + edge_boot_id + "|" +
                     Sha256Hex(artifact_bytes));
}

AuditTrail::~AuditTrail() {
    if (journal_ != nullptr) {
        ::fclose(journal_);
        journal_ = nullptr;
    }
}

AuditTrail::AuditTrail(AuditTrail&& other) noexcept
    : events_(std::move(other.events_)),
      next_seq_(other.next_seq_),
      dropped_(other.dropped_),
      flight_id_(std::move(other.flight_id_)),
      edge_boot_id_(std::move(other.edge_boot_id_)),
      has_identity_(other.has_identity_),
      has_state_(other.has_state_),
      last_state_(std::move(other.last_state_)),
      last_video_(std::move(other.last_video_)),
      last_telemetry_(std::move(other.last_telemetry_)),
      last_engine_(std::move(other.last_engine_)),
      last_degraded_(std::move(other.last_degraded_)),
      last_processed_(other.last_processed_),
      last_epoch_(other.last_epoch_),
      next_checkpoint_due_(other.next_checkpoint_due_),
      journal_(other.journal_),
      journal_ok_(other.journal_ok_),
      torn_repaired_(other.torn_repaired_),
      journal_error_(std::move(other.journal_error_)),
      hooks_(std::move(other.hooks_)) {
    other.journal_ = nullptr;
    other.journal_ok_ = true;
}

AuditTrail& AuditTrail::operator=(AuditTrail&& other) noexcept {
    if (this != &other) {
        if (journal_ != nullptr) {
            ::fclose(journal_);
        }
        events_ = std::move(other.events_);
        next_seq_ = other.next_seq_;
        dropped_ = other.dropped_;
        flight_id_ = std::move(other.flight_id_);
        edge_boot_id_ = std::move(other.edge_boot_id_);
        has_identity_ = other.has_identity_;
        has_state_ = other.has_state_;
        last_state_ = std::move(other.last_state_);
        last_video_ = std::move(other.last_video_);
        last_telemetry_ = std::move(other.last_telemetry_);
        last_engine_ = std::move(other.last_engine_);
        last_degraded_ = std::move(other.last_degraded_);
        last_processed_ = other.last_processed_;
        last_epoch_ = other.last_epoch_;
        next_checkpoint_due_ = other.next_checkpoint_due_;
        journal_ = other.journal_;
        journal_ok_ = other.journal_ok_;
        torn_repaired_ = other.torn_repaired_;
        journal_error_ = std::move(other.journal_error_);
        hooks_ = std::move(other.hooks_);
        other.journal_ = nullptr;
        other.journal_ok_ = true;
    }
    return *this;
}

void AuditTrail::Reset() {
    if (journal_ != nullptr) {
        ::fclose(journal_);
        journal_ = nullptr;
    }
    events_.clear();
    next_seq_ = 0;
    dropped_ = 0;
    flight_id_.clear();
    edge_boot_id_.clear();
    has_identity_ = false;
    has_state_ = false;
    last_state_.clear();
    last_video_.clear();
    last_telemetry_.clear();
    last_engine_.clear();
    last_degraded_.clear();
    last_processed_ = 0;
    last_epoch_ = 0;
    next_checkpoint_due_ = 0;
    journal_ok_ = true;
    torn_repaired_ = false;
    journal_error_.clear();
}

bool AuditTrail::OpenJournal(const std::string& dir,
                             const std::string& boot_id,
                             const AuditHooks& hooks, std::string* error) {
    auto fail = [&](const std::string& message) {
        journal_ok_ = false;
        journal_error_ = message;
        if (error != nullptr) *error = message;
        return false;
    };
    if (journal_ != nullptr) {
        return fail("audit journal already open");
    }
    if (dir.empty() || dir[0] != '/') {
        return fail("audit journal dir must be absolute: " + dir);
    }
    if (boot_id.empty()) {
        return fail("audit journal needs a boot id");
    }
    hooks_ = hooks;
    const std::string path = dir + "/" + JournalFileName(boot_id);
    const bool existed = (::access(path.c_str(), F_OK) == 0);
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND |
                                          O_CLOEXEC,
                          0600);
    if (fd < 0) {
        return fail("cannot open audit journal " + path + ": " +
                    ::strerror(errno));
    }
    // Validate an existing file before appending: a torn trailing line is
    // truncated (explicit repair); interior corruption fails the open so a
    // damaged history is never silently extended.
    bool repaired = false;
    if (existed) {
        std::vector<JsonValue> kept;
        std::string read_error;
        if (!ReadJournalFile(path, &kept, &repaired, &read_error)) {
            ::close(fd);
            return fail(read_error);
        }
        if (repaired) {
            torn_repaired_ = true;
        }
    } else {
        // New file name must survive a crash: sync the directory.
        const int dir_fd =
            ::open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (dir_fd >= 0) {
            const int rc = hooks_.fsync ? hooks_.fsync(dir_fd)
                                        : ::fsync(dir_fd);
            ::close(dir_fd);
            if (rc != 0) {
                ::close(fd);
                return fail("audit journal dir fsync failed: " + dir);
            }
        }
    }
    journal_ = ::fdopen(fd, "a");
    if (journal_ == nullptr) {
        const std::string why = ::strerror(errno);
        ::close(fd);
        return fail("cannot stream audit journal " + path + ": " + why);
    }
    return true;
}

bool AuditTrail::ReadJournalFile(const std::string& path,
                                 std::vector<JsonValue>* lines,
                                 bool* repaired, std::string* error) {
    auto fail = [&](const std::string& message) {
        if (error != nullptr) *error = message;
        return false;
    };
    if (lines == nullptr) {
        return fail("no output for journal lines");
    }
    lines->clear();
    if (repaired != nullptr) *repaired = false;
    std::string content;
    std::string read_error;
    if (!ReadWholeFile(path, &content, &read_error)) {
        return fail(read_error);
    }
    // Torn tail: bytes after the last newline never formed a record.
    // Truncate explicitly (the only rewrite this journal ever performs).
    if (!content.empty() && content.back() != '\n') {
        const std::size_t last_nl = content.find_last_of('\n');
        const std::size_t keep = (last_nl == std::string::npos)
                                     ? 0
                                     : last_nl + 1;
        const int fd = ::open(path.c_str(), O_WRONLY | O_CLOEXEC);
        if (fd < 0 || ::ftruncate(fd, static_cast<off_t>(keep)) != 0) {
            if (fd >= 0) ::close(fd);
            return fail("torn audit tail cannot be truncated: " + path);
        }
        ::close(fd);
        content.resize(keep);
        if (repaired != nullptr) *repaired = true;
    }
    std::size_t start = 0;
    while (start < content.size()) {
        const std::size_t nl = content.find('\n', start);
        if (nl == std::string::npos) break;  // unreachable: tail handled
        JsonValue value;
        std::string parse_error;
        if (!ParseJson(content.substr(start, nl - start), &value,
                       &parse_error) ||
            !value.IsObject()) {
            return fail("audit journal interior corruption at line " +
                        std::to_string(lines->size()) + " in " + path +
                        ": " + parse_error);
        }
        lines->push_back(std::move(value));
        start = nl + 1;
    }
    return true;
}

std::vector<std::string> AuditTrail::ListBootJournals(const std::string& dir) {
    std::vector<std::string> boots;
    DIR* handle = ::opendir(dir.c_str());
    if (handle == nullptr) {
        return boots;
    }
    while (const dirent* entry = ::readdir(handle)) {
        const std::string name = entry->d_name;
        if (!HasBootSuffix(name)) continue;
        boots.push_back(name.substr(6, name.size() - 6 - 4));
    }
    ::closedir(handle);
    std::sort(boots.begin(), boots.end());
    boots.erase(std::unique(boots.begin(), boots.end()), boots.end());
    return boots;
}

bool AuditTrail::SetIdentity(const std::string& flight_id,
                             const std::string& edge_boot_id,
                             std::int64_t now_mono_ns) {
    if (has_identity_ && flight_id == flight_id_ &&
        edge_boot_id == edge_boot_id_) {
        return true;
    }
    flight_id_ = flight_id;
    edge_boot_id_ = edge_boot_id;
    has_identity_ = true;
    AuditEvent event;
    event.mono_ns = now_mono_ns;
    event.kind = "identity";
    JsonValue detail = JsonValue::Obj();
    detail.Set("flight_id", JsonValue::Str(flight_id));
    detail.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    event.detail = std::move(detail);
    return Append(std::move(event));
}

bool AuditTrail::RecordCommand(const std::string& msg_id,
                               const std::string& type,
                               std::uint64_t revision, std::uint64_t epoch,
                               std::int64_t now_mono_ns) {
    AuditEvent event;
    event.mono_ns = now_mono_ns;
    event.kind = "command";
    event.msg_id = msg_id;
    JsonValue detail = JsonValue::Obj();
    detail.Set("type", JsonValue::Str(type));
    detail.Set("revision", JsonValue::Int(static_cast<std::int64_t>(revision)));
    detail.Set("epoch", JsonValue::Int(static_cast<std::int64_t>(epoch)));
    event.detail = std::move(detail);
    return Append(std::move(event));
}

bool AuditTrail::RecordInstall(const std::string& bundle_id,
                               const std::string& bundle_sha,
                               std::uint64_t applied_generation,
                               const std::string& outcome,
                               const std::string& reason,
                               std::int64_t now_mono_ns) {
    AuditEvent event;
    event.mono_ns = now_mono_ns;
    event.kind = "install";
    JsonValue detail = JsonValue::Obj();
    detail.Set("bundle_id", JsonValue::Str(bundle_id));
    detail.Set("bundle_sha256", JsonValue::Str(bundle_sha));
    detail.Set("applied_generation",
               JsonValue::Int(static_cast<std::int64_t>(applied_generation)));
    detail.Set("outcome", JsonValue::Str(outcome));
    detail.Set("reason", JsonValue::Str(reason));
    event.detail = std::move(detail);
    return Append(std::move(event));
}

bool AuditTrail::NoteState(const std::string& runtime_state,
                           const std::string& video,
                           const std::string& telemetry,
                           const std::string& engine,
                           const std::string& degraded_csv,
                           std::uint64_t processed_frame_count,
                           std::uint64_t config_epoch,
                           std::int64_t now_mono_ns) {
    const bool same_state =
        has_state_ && runtime_state == last_state_ && video == last_video_ &&
        telemetry == last_telemetry_ && engine == last_engine_ &&
        degraded_csv == last_degraded_ && config_epoch == last_epoch_;
    if (same_state && processed_frame_count < next_checkpoint_due_) {
        return true;  // unchanged and below the checkpoint: no record
    }
    const bool checkpoint =
        has_state_ && same_state &&
        processed_frame_count >= next_checkpoint_due_;
    last_state_ = runtime_state;
    last_video_ = video;
    last_telemetry_ = telemetry;
    last_engine_ = engine;
    last_degraded_ = degraded_csv;
    last_processed_ = processed_frame_count;
    last_epoch_ = config_epoch;
    has_state_ = true;
    next_checkpoint_due_ = processed_frame_count + kCheckpointFrames;
    AuditEvent event;
    event.mono_ns = now_mono_ns;
    // Counter-only progress journals as a lightweight checkpoint, not a
    // full state record: bounded history that still shows the run advanced.
    event.kind = checkpoint ? "checkpoint" : "state";
    JsonValue detail = JsonValue::Obj();
    detail.Set("runtime_state", JsonValue::Str(runtime_state));
    JsonValue input = JsonValue::Obj();
    input.Set("video", JsonValue::Str(video));
    input.Set("telemetry", JsonValue::Str(telemetry));
    input.Set("engine", JsonValue::Str(engine));
    detail.Set("input_state", std::move(input));
    detail.Set("degraded_reasons", JsonValue::Str(degraded_csv));
    detail.Set("processed_frame_count",
               JsonValue::Int(static_cast<std::int64_t>(processed_frame_count)));
    detail.Set("config_epoch",
               JsonValue::Int(static_cast<std::int64_t>(config_epoch)));
    event.detail = std::move(detail);
    return Append(std::move(event));
}

bool AuditTrail::RecordClose(std::uint64_t close_ts,
                             std::uint64_t summary_ticks,
                             std::int64_t now_mono_ns) {
    AuditEvent event;
    event.mono_ns = now_mono_ns;
    event.kind = "close";
    JsonValue detail = JsonValue::Obj();
    detail.Set("close_ts",
               JsonValue::Int(static_cast<std::int64_t>(close_ts)));
    detail.Set("summary_ticks",
               JsonValue::Int(static_cast<std::int64_t>(summary_ticks)));
    event.detail = std::move(detail);
    return Append(std::move(event));
}

std::string AuditTrail::ToJsonLines() const {
    std::string out;
    JsonValue meta = JsonValue::Obj();
    meta.Set("artifact", JsonValue::Str("edge-audit-v1"));
    meta.Set("lineage_id",
             JsonValue::Str(AuditLineageId(flight_id_, edge_boot_id_)));
    meta.Set("flight_id", JsonValue::Str(flight_id_));
    meta.Set("edge_boot_id", JsonValue::Str(edge_boot_id_));
    meta.Set("dropped",
             JsonValue::Int(static_cast<std::int64_t>(dropped_)));
    meta.Set("complete", JsonValue::Bool(dropped_ == 0));
    meta.Set("events", JsonValue::Int(static_cast<std::int64_t>(events_.size())));
    out += WriteCanonicalJson(meta);
    out += '\n';
    for (const AuditEvent& event : events_) {
        out += Serialize(event);
        out += '\n';
    }
    return out;
}

std::string AuditTrail::FormatFileShipment(
    const std::string& flight_id, const std::string& edge_boot_id,
    const std::vector<JsonValue>& lines) {
    std::string out;
    JsonValue meta = JsonValue::Obj();
    meta.Set("artifact", JsonValue::Str("edge-audit-v1"));
    meta.Set("lineage_id",
             JsonValue::Str(AuditLineageId(flight_id, edge_boot_id)));
    meta.Set("flight_id", JsonValue::Str(flight_id));
    meta.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    // The journal file is the full accepted record (writes precede memory
    // and are never pruned): no dropped bound applies to file shipments.
    meta.Set("dropped", JsonValue::Null());
    meta.Set("complete", JsonValue::Bool(true));
    meta.Set("events",
             JsonValue::Int(static_cast<std::int64_t>(lines.size())));
    out += WriteCanonicalJson(meta);
    out += '\n';
    for (const JsonValue& line : lines) {
        out += WriteCanonicalJson(line);
        out += '\n';
    }
    return out;
}

bool AuditTrail::Append(AuditEvent event) {
    event.seq = next_seq_++;
    if (events_.size() >= kMaxEvents) {
        // Bounded: drop oldest, count it. The shipped meta line carries
        // the dropped count so the history is explicitly partial.
        events_.erase(events_.begin());
        ++dropped_;
    }
    // Journal first (write-ahead): the file fsyncs before the in-memory
    // record is visible. A failed fsync keeps the old file bytes AND skips
    // the memory record (fail closed, loud via the return value).
    if (journal_ != nullptr && journal_ok_) {
        const std::string line = Serialize(event) + "\n";
        const int fd = ::fileno(journal_);
        int sync_rc;
        if (hooks_.fsync) {
            sync_rc = hooks_.fsync(fd);
        } else {
            sync_rc = ::fsync(fd);
        }
        if (!WriteAllBytes(fd, line.data(), line.size()) ||
            ::fflush(journal_) != 0 || sync_rc != 0) {
            journal_ok_ = false;
            journal_error_ = std::string("audit journal fsync failed: ") +
                             ::strerror(errno);
            return false;
        }
    }
    events_.push_back(std::move(event));
    return true;
}

std::string AuditTrail::Serialize(const AuditEvent& event) const {
    JsonValue out = JsonValue::Obj();
    out.Set("seq", JsonValue::Int(static_cast<std::int64_t>(event.seq)));
    out.Set("t_mono_ns", JsonValue::Int(event.mono_ns));
    out.Set("kind", JsonValue::Str(event.kind));
    out.Set("msg_id", JsonValue::Str(event.msg_id));
    out.Set("detail", event.detail);
    return WriteCanonicalJson(out);
}


// --- Identity-partitioned journals (one file per boot) ---------------------

bool AuditJournalSet::Open(const std::string& dir, const AuditHooks& hooks,
                           std::string* error) {
    if (dir.empty() || dir[0] != '/') {
        if (error != nullptr) {
            *error = "audit journal dir must be absolute: " + dir;
        }
        return false;
    }
    dir_ = dir;
    hooks_ = hooks;
    open_ = true;
    return true;
}

AuditJournalSet::Shipment AuditJournalSet::EnsureIdentity(
    const std::string& flight_id, const std::string& edge_boot_id,
    bool confirmed, std::int64_t now_mono_ns) {
    Shipment empty;
    if (!open_ || !confirmed || flight_id.empty()) {
        return empty;  // nothing is recorded under an unconfirmed flight
    }
    if (has_identity_ && flight_id == flight_id_ &&
        edge_boot_id == edge_boot_id_) {
        return empty;
    }
    // Identity change (including the very first confirmed identity):
    // hand the outgoing trail back for shipping under ITS ORIGINAL
    // identity, then start a fresh journal + memory context. The outgoing
    // file stays on disk for forensics and reconnect-time restore.
    Shipment outgoing;
    if (has_identity_ && trail_.size() > 0) {
        outgoing.empty = false;
        outgoing.flight_id = flight_id_;
        outgoing.edge_boot_id = edge_boot_id_;
        outgoing.bytes = trail_.ToJsonLines();
        outgoing.artifact_id = AuditArtifactId(
            outgoing.flight_id, outgoing.edge_boot_id, outgoing.bytes);
    }
    trail_.Reset();
    flight_id_ = flight_id;
    edge_boot_id_ = edge_boot_id;
    has_identity_ = true;
    std::string error;
    if (!trail_.OpenJournal(dir_, edge_boot_id, hooks_, &error)) {
        trail_ok_ = false;
        last_error_ = error;
        return outgoing;
    }
    if (trail_.tornRepaired()) {
        torn_repaired_any_ = true;
    }
    trail_.SetIdentity(flight_id, edge_boot_id, now_mono_ns);
    return outgoing;
}

bool AuditJournalSet::RecordCommand(const std::string& msg_id,
                                    const std::string& type,
                                    std::uint64_t revision,
                                    std::uint64_t epoch,
                                    std::int64_t now_mono_ns) {
    if (!has_identity_) {
        return true;
    }
    if (!trail_.RecordCommand(msg_id, type, revision, epoch, now_mono_ns)) {
        trail_ok_ = false;
        last_error_ = trail_.journalError();
        return false;
    }
    return true;
}

bool AuditJournalSet::RecordInstall(
    const std::string& bundle_id, const std::string& bundle_sha,
    std::uint64_t applied_generation, const std::string& outcome,
    const std::string& reason, std::int64_t now_mono_ns) {
    if (!has_identity_) {
        return true;
    }
    if (!trail_.RecordInstall(bundle_id, bundle_sha, applied_generation,
                              outcome, reason, now_mono_ns)) {
        trail_ok_ = false;
        last_error_ = trail_.journalError();
        return false;
    }
    return true;
}

bool AuditJournalSet::NoteState(
    const std::string& runtime_state, const std::string& video,
    const std::string& telemetry, const std::string& engine,
    const std::string& degraded_csv, std::uint64_t processed_frame_count,
    std::uint64_t config_epoch, std::int64_t now_mono_ns) {
    if (!has_identity_) {
        return true;
    }
    if (!trail_.NoteState(runtime_state, video, telemetry, engine,
                          degraded_csv, processed_frame_count, config_epoch,
                          now_mono_ns)) {
        trail_ok_ = false;
        last_error_ = trail_.journalError();
        return false;
    }
    return true;
}

bool AuditJournalSet::RecordClose(std::uint64_t close_ts,
                                  std::uint64_t summary_ticks,
                                  std::int64_t now_mono_ns) {
    if (!has_identity_) {
        return true;
    }
    if (!trail_.RecordClose(close_ts, summary_ticks, now_mono_ns)) {
        trail_ok_ = false;
        last_error_ = trail_.journalError();
        return false;
    }
    return true;
}

std::string AuditJournalSet::ToJsonLines() const {
    return trail_.ToJsonLines();
}

AuditJournalSet::Shipment AuditJournalSet::CurrentShipment() const {
    Shipment out;
    if (!has_identity_ || trail_.size() == 0) {
        return out;
    }
    out.empty = false;
    out.flight_id = flight_id_;
    out.edge_boot_id = edge_boot_id_;
    out.bytes = trail_.ToJsonLines();
    out.artifact_id =
        AuditArtifactId(out.flight_id, out.edge_boot_id, out.bytes);
    return out;
}

std::vector<std::string> AuditJournalSet::ListBoots() const {
    if (!open_) {
        return {};
    }
    return AuditTrail::ListBootJournals(dir_);
}

bool AuditJournalSet::ShipBootFile(const std::string& boot_id,
                                   Shipment* shipment, bool* repaired_out,
                                   std::string* error) {
    if (shipment != nullptr) {
        *shipment = Shipment{};
    }
    if (repaired_out != nullptr) {
        *repaired_out = false;
    }
    if (!open_) {
        if (error != nullptr) {
            *error = "audit store not open";
        }
        return false;
    }
    std::string safe;
    for (char ch : boot_id) {
        const bool ok = (ch >= 'a' && ch <= 'z') ||
                        (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
                        ch == '_' || ch == '.' || ch == '-';
        safe.push_back(ok ? ch : '_');
    }
    if (safe.empty()) {
        safe = "noid";
    }
    const std::string path = dir_ + "/audit-" + safe + ".log";
    std::vector<JsonValue> lines;
    bool repaired = false;
    if (!AuditTrail::ReadJournalFile(path, &lines, &repaired, error)) {
        return false;
    }
    if (repaired_out != nullptr) {
        *repaired_out = repaired;
    }
    // Flight/boot come from the journal's own identity record — never from
    // the caller's current endpoint (no relabelling across restarts).
    std::string flight_id;
    std::string record_boot;
    for (const JsonValue& line : lines) {
        const JsonValue* kind = line.Find("kind");
        if (kind == nullptr || !kind->IsString() ||
            kind->strValue() != "identity") {
            continue;
        }
        const JsonValue* detail = line.Find("detail");
        const JsonValue* flight =
            detail != nullptr ? detail->Find("flight_id") : nullptr;
        const JsonValue* boot =
            detail != nullptr ? detail->Find("edge_boot_id") : nullptr;
        if (flight != nullptr && flight->IsString()) {
            flight_id = flight->strValue();
        }
        if (boot != nullptr && boot->IsString() &&
            !boot->strValue().empty()) {
            record_boot = boot->strValue();
            break;
        }
    }
    if (record_boot.empty()) {
        if (error != nullptr) {
            *error = "audit journal has no identity record: " + path;
        }
        return false;
    }
    if (shipment != nullptr) {
        shipment->empty = lines.empty();
        shipment->flight_id = flight_id;
        shipment->edge_boot_id = record_boot;
        shipment->bytes =
            AuditTrail::FormatFileShipment(flight_id, record_boot, lines);
        shipment->artifact_id = AuditArtifactId(
            flight_id, record_boot, shipment->bytes);
    }
    return true;
}

}  // namespace ksi::safety
