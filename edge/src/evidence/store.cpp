#include "ksi_safety/evidence/store.hpp"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

namespace {

constexpr const char* kIndexName = "index.ndjson";
constexpr const char* kAssetsDir = "assets";
constexpr const char* kRevisionsDir = "revisions";
constexpr const char* kTmpSuffix = ".tmp";

bool IsSafeName(const std::string& name) {
    if (name.empty() || name.size() > 128) return false;
    for (const char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '-' || c == '_' ||
                        c == '.';
        if (!ok) return false;
    }
    return name != "." && name != "..";
}

bool IsHex64(const std::string& value) {
    if (value.size() != 64) return false;
    for (const char c : value) {
        const bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        if (!ok) return false;
    }
    return true;
}

bool EnsureDir(const std::string& path, std::string* error) {
    if (::mkdir(path.c_str(), 0700) == 0) return true;
    if (errno == EEXIST) {
        struct stat st{};
        if (::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) return true;
    }
    if (error != nullptr) {
        *error = "mkdir failed: " + path + ": " + std::strerror(errno);
    }
    return false;
}

std::string JoinPath(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

bool ReadWholeFile(const std::string& path, std::string* out,
                   std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (error != nullptr) {
            *error = "open failed: " + path + ": " + std::strerror(errno);
        }
        return false;
    }
    out->clear();
    char buffer[8192];
    for (;;) {
        const ssize_t got = ::read(fd, buffer, sizeof(buffer));
        if (got < 0) {
            if (error != nullptr) {
                *error = "read failed: " + path + ": " + std::strerror(errno);
            }
            ::close(fd);
            return false;
        }
        if (got == 0) break;
        out->append(buffer, static_cast<std::size_t>(got));
    }
    ::close(fd);
    return true;
}

bool WriteAllFd(int fd, const char* data, std::size_t len) {
    std::size_t offset = 0;
    while (offset < len) {
        const ssize_t written = ::write(fd, data + offset, len - offset);
        if (written <= 0) return false;
        offset += static_cast<std::size_t>(written);
    }
    return true;
}

}  // namespace

const char* EvidenceStatusName(EvidenceStatus status) {
    switch (status) {
        case EvidenceStatus::kOk:
            return "OK";
        case EvidenceStatus::kDuplicate:
            return "DUPLICATE";
        case EvidenceStatus::kConflict:
            return "CONFLICT";
        case EvidenceStatus::kFull:
            return "FULL";
        case EvidenceStatus::kInvalid:
            return "INVALID";
        case EvidenceStatus::kIoError:
            return "IO_ERROR";
        case EvidenceStatus::kCorrupt:
            return "CORRUPT";
    }
    return "UNKNOWN";
}

struct EvidenceStore::Impl {
    std::string dir;
    std::string assets_dir;
    std::string revisions_dir;
    std::string index_path;
    std::uint64_t capacity = 0;
    EvidenceStoreHooks hooks;
    mutable std::mutex mutex;

    bool ok = false;
    EvidenceStatus open_status = EvidenceStatus::kIoError;
    std::string last_error;
    bool torn_repaired = false;
    std::uint64_t temp_removed = 0;

    // asset_id -> record (append-only index, rebuilt on open).
    std::map<std::string, SourceAsset> assets;
    std::uint64_t asset_bytes = 0;
    std::uint64_t index_bytes = 0;
    std::uint64_t revision_bytes = 0;

    int SyncFd(int fd) const {
        if (hooks.fsync) return hooks.fsync(fd);
        return ::fsync(fd);
    }

    int DoRename(const char* from, const char* to) const {
        if (hooks.rename) return hooks.rename(from, to);
        return ::rename(from, to);
    }

    bool SyncDir(const std::string& path, std::string* error) const {
        const int fd = ::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0) {
            if (error != nullptr) {
                *error = "open dir for sync failed: " + path + ": " +
                         std::strerror(errno);
            }
            return false;
        }
        const int rc = SyncFd(fd);
        const int saved = errno;
        ::close(fd);
        if (rc != 0) {
            if (error != nullptr) {
                *error = "dir fsync failed: " + path + ": " +
                         std::strerror(saved);
            }
            return false;
        }
        return true;
    }

    std::uint64_t TotalBytes() const {
        return asset_bytes + index_bytes + revision_bytes;
    }

    bool CapacityAllows(std::uint64_t additional) const {
        if (capacity == 0) return true;
        return TotalBytes() + additional <= capacity;
    }

    std::string AssetPath(const std::string& asset_id) const {
        return JoinPath(assets_dir, asset_id);
    }

    // Writes `bytes` as the asset file atomically. Returns false with
    // *error set; nothing is committed on failure.
    bool WriteAssetFile(const std::string& asset_id, const std::uint8_t* bytes,
                        std::size_t len, std::string* error) const {
        const std::string final_path = AssetPath(asset_id);
        const std::string tmp_path = final_path + kTmpSuffix;
        const int fd = ::open(tmp_path.c_str(),
                              O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd < 0) {
            if (error != nullptr) {
                *error = "open tmp failed: " + tmp_path + ": " +
                         std::strerror(errno);
            }
            return false;
        }
        bool ok = WriteAllFd(fd, reinterpret_cast<const char*>(bytes), len);
        if (ok) {
            const int rc = SyncFd(fd);
            if (rc != 0) {
                if (error != nullptr) {
                    *error = "fsync failed: " + tmp_path + ": " +
                             std::strerror(errno);
                }
                ok = false;
            }
        }
        ::close(fd);
        if (!ok) {
            ::unlink(tmp_path.c_str());
            return false;
        }
        if (DoRename(tmp_path.c_str(), final_path.c_str()) != 0) {
            if (error != nullptr) {
                *error = "rename failed: " + tmp_path + " -> " + final_path +
                         ": " + std::strerror(errno);
            }
            ::unlink(tmp_path.c_str());
            return false;
        }
        return SyncDir(assets_dir, error);
    }

    // Appends one index line (canonical JSON + newline) and fsyncs it.
    bool AppendIndex(const SourceAsset& asset, std::string* error) {
        JsonValue record = JsonValue::Obj();
        record.Set("asset_id", JsonValue::Str(asset.asset_id));
        record.Set("sha256", JsonValue::Str(asset.sha256_hex));
        record.Set("size_bytes", JsonValue::Int(static_cast<std::int64_t>(asset.size_bytes)));
        record.Set("media_type", JsonValue::Str(asset.media_type));
        record.Set("flight_id", JsonValue::Str(asset.flight_id));
        record.Set("edge_boot_id", JsonValue::Str(asset.edge_boot_id));
        record.Set("frame_id", JsonValue::Int(static_cast<std::int64_t>(asset.frame_id)));
        record.Set("received_monotonic_ns",
                   JsonValue::Int(asset.received_monotonic_ns));
        record.Set("observation_id", JsonValue::Str(asset.observation_id));
        const std::string line = WriteCanonicalJson(record) + "\n";
        const int fd = ::open(index_path.c_str(),
                              O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (fd < 0) {
            if (error != nullptr) {
                *error = "open index failed: " + index_path + ": " +
                         std::strerror(errno);
            }
            return false;
        }
        bool ok = WriteAllFd(fd, line.data(), line.size());
        if (ok) {
            const int rc = SyncFd(fd);
            if (rc != 0) {
                if (error != nullptr) {
                    *error = "index fsync failed: " + index_path + ": " +
                             std::strerror(errno);
                }
                ok = false;
            }
        }
        ::close(fd);
        if (!ok) return false;
        index_bytes += line.size();
        return SyncDir(dir, error);
    }

    bool RemoveTempFiles();
    bool LoadIndex();
    // Caller holds the store mutex (the public VerifyAsset and SealRevision
    // both do): re-reads the stored bytes and compares the digest.
    bool VerifyAssetLocked(const std::string& asset_id, bool* ok_out);
    bool ParseAssetLine(const std::string& line, SourceAsset* out) const;
};

namespace {

JsonValue AssetToJson(const SourceAsset& asset) {
    JsonValue record = JsonValue::Obj();
    record.Set("asset_id", JsonValue::Str(asset.asset_id));
    record.Set("sha256", JsonValue::Str(asset.sha256_hex));
    record.Set("size_bytes", JsonValue::Int(static_cast<std::int64_t>(asset.size_bytes)));
    record.Set("media_type", JsonValue::Str(asset.media_type));
    record.Set("flight_id", JsonValue::Str(asset.flight_id));
    record.Set("edge_boot_id", JsonValue::Str(asset.edge_boot_id));
    record.Set("frame_id", JsonValue::Int(static_cast<std::int64_t>(asset.frame_id)));
    record.Set("received_monotonic_ns",
               JsonValue::Int(asset.received_monotonic_ns));
    record.Set("observation_id", JsonValue::Str(asset.observation_id));
    return record;
}

}  // namespace

bool EvidenceStore::Impl::ParseAssetLine(const std::string& line,
                                         SourceAsset* out) const {
    JsonValue record;
    std::string error;
    if (!ParseJson(line, &record, &error)) return false;
    if (!record.IsObject()) return false;
    const JsonValue* asset_id = record.Find("asset_id");
    const JsonValue* digest = record.Find("sha256");
    const JsonValue* size = record.Find("size_bytes");
    if (asset_id == nullptr || !asset_id->IsString()) return false;
    if (digest == nullptr || !digest->IsString()) return false;
    if (size == nullptr || !size->IsInt()) return false;
    if (out == nullptr) return true;
    out->asset_id = asset_id->strValue();
    out->sha256_hex = digest->strValue();
    out->size_bytes = static_cast<std::uint64_t>(size->intValue());
    const JsonValue* media = record.Find("media_type");
    if (media != nullptr && media->IsString()) out->media_type = media->strValue();
    const JsonValue* flight = record.Find("flight_id");
    if (flight != nullptr && flight->IsString()) out->flight_id = flight->strValue();
    const JsonValue* boot = record.Find("edge_boot_id");
    if (boot != nullptr && boot->IsString()) out->edge_boot_id = boot->strValue();
    const JsonValue* frame = record.Find("frame_id");
    if (frame != nullptr && frame->IsInt()) {
        out->frame_id = static_cast<std::uint64_t>(frame->intValue());
    }
    const JsonValue* received = record.Find("received_monotonic_ns");
    if (received != nullptr && received->IsInt()) {
        out->received_monotonic_ns = received->intValue();
    }
    const JsonValue* observation = record.Find("observation_id");
    if (observation != nullptr && observation->IsString()) {
        out->observation_id = observation->strValue();
    }
    return true;
}

bool EvidenceStore::Impl::VerifyAssetLocked(const std::string& asset_id,
                                           bool* ok_out) {
    if (ok_out != nullptr) *ok_out = false;
    const auto found = assets.find(asset_id);
    if (found == assets.end()) return false;
    std::string error;
    const std::string digest =
        Sha256HexOfFile(AssetPath(asset_id).c_str(), &error);
    if (digest.empty()) {
        last_error = error;
        return true;  // known, but unverifiable right now
    }
    const bool matches = digest == found->second.sha256_hex;
    if (ok_out != nullptr) *ok_out = matches;
    return true;
}

bool EvidenceStore::Impl::RemoveTempFiles() {
    const std::string dir_path = assets_dir;
    DIR* dir = ::opendir(dir_path.c_str());
    if (dir == nullptr) return true;
    while (const struct dirent* entry = ::readdir(dir)) {
        const std::string name = entry->d_name;
        if (name.size() <= 4) continue;
        if (name.compare(name.size() - 4, 4, kTmpSuffix) != 0) continue;
        const std::string path = JoinPath(dir_path, name);
        if (::unlink(path.c_str()) == 0) ++temp_removed;
    }
    ::closedir(dir);
    return true;
}

bool EvidenceStore::Impl::LoadIndex() {
    assets.clear();
    asset_bytes = 0;
    index_bytes = 0;
    std::string content;
    std::string error;
    if (!ReadWholeFile(index_path, &content, &error)) {
        // A fresh store has no index yet: that is empty, not a failure.
        struct stat st{};
        if (::stat(index_path.c_str(), &st) != 0 && errno == ENOENT) {
            ok = true;
            open_status = EvidenceStatus::kOk;
            return true;
        }
        last_error = error;
        open_status = EvidenceStatus::kIoError;
        return false;
    }
    index_bytes = content.size();
    if (content.empty()) {
        ok = true;
        open_status = EvidenceStatus::kOk;
        return true;
    }
    std::size_t offset = 0;
    bool repaired = false;
    while (offset < content.size()) {
        const std::size_t newline = content.find('\n', offset);
        if (newline == std::string::npos) {
            // Torn tail: the last line was never terminated. Truncate it back
            // to the last committed record (it was never durable).
            repaired = true;
            break;
        }
        const std::string line = content.substr(offset, newline - offset);
        SourceAsset asset;
        if (!ParseAssetLine(line, &asset)) {
            last_error = "index interior line is malformed: " + line;
            open_status = EvidenceStatus::kCorrupt;
            return false;
        }
        const auto existing = assets.find(asset.asset_id);
        if (existing == assets.end()) {
            asset_bytes += asset.size_bytes;
            assets.emplace(asset.asset_id, asset);
        } else if (existing->second.sha256_hex != asset.sha256_hex) {
            // Same id, different digest inside our own index: untrustworthy.
            last_error = "index conflict for asset " + asset.asset_id;
            open_status = EvidenceStatus::kCorrupt;
            return false;
        }
        offset = newline + 1;
    }
    if (repaired) {
        if (::truncate(index_path.c_str(), static_cast<off_t>(offset)) != 0) {
            last_error = "index truncate failed: " + std::string(std::strerror(errno));
            open_status = EvidenceStatus::kIoError;
            return false;
        }
        index_bytes = offset;
        torn_repaired = true;
    }
    ok = true;
    open_status = EvidenceStatus::kOk;
    return true;
}

EvidenceStore::EvidenceStore(std::string dir, std::uint64_t capacity_bytes)
    : EvidenceStore(std::move(dir), capacity_bytes, EvidenceStoreHooks{}) {}

EvidenceStore::EvidenceStore(std::string dir, std::uint64_t capacity_bytes,
                             EvidenceStoreHooks hooks)
    : impl_(new Impl()) {
    impl_->dir = std::move(dir);
    impl_->capacity = capacity_bytes;
    impl_->hooks = std::move(hooks);
    impl_->assets_dir = JoinPath(impl_->dir, kAssetsDir);
    impl_->revisions_dir = JoinPath(impl_->dir, kRevisionsDir);
    impl_->index_path = JoinPath(impl_->dir, kIndexName);

    std::string error;
    if (impl_->dir.empty()) {
        impl_->last_error = "evidence store dir is empty";
        impl_->open_status = EvidenceStatus::kInvalid;
        return;
    }
    if (!EnsureDir(impl_->dir, &error) ||
        !EnsureDir(impl_->assets_dir, &error) ||
        !EnsureDir(impl_->revisions_dir, &error)) {
        impl_->last_error = error;
        impl_->open_status = EvidenceStatus::kIoError;
        return;
    }
    impl_->RemoveTempFiles();
    if (!impl_->LoadIndex()) {
        if (impl_->last_error.empty()) impl_->last_error = "index load failed";
        return;
    }
    // Revision sizes count against the bound too.
    DIR* rev_dir = ::opendir(impl_->revisions_dir.c_str());
    if (rev_dir != nullptr) {
        while (const struct dirent* entry = ::readdir(rev_dir)) {
            const std::string name = entry->d_name;
            if (!IsSafeName(name)) continue;
            struct stat st{};
            const std::string path = JoinPath(impl_->revisions_dir, name);
            if (::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
                impl_->revision_bytes += static_cast<std::uint64_t>(st.st_size);
            }
        }
        ::closedir(rev_dir);
    }
}

EvidenceStore::~EvidenceStore() { delete impl_; }

bool EvidenceStore::ok() const { return impl_->ok; }

EvidenceStatus EvidenceStore::openStatus() const { return impl_->open_status; }

const std::string& EvidenceStore::lastError() const { return impl_->last_error; }

bool EvidenceStore::tornRepaired() const { return impl_->torn_repaired; }

std::uint64_t EvidenceStore::tempFilesRemoved() const { return impl_->temp_removed; }

EvidenceStatus EvidenceStore::PutSourceAsset(const SourceAsset& asset,
                                             const std::uint8_t* bytes,
                                             std::size_t len) {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    if (!impl_->ok) return EvidenceStatus::kCorrupt;
    if (!IsSafeName(asset.asset_id) || !IsHex64(asset.sha256_hex)) {
        return EvidenceStatus::kInvalid;
    }
    if (bytes == nullptr && len > 0) return EvidenceStatus::kInvalid;
    if (asset.size_bytes != static_cast<std::uint64_t>(len)) {
        return EvidenceStatus::kInvalid;
    }
    const std::string actual = Sha256Hex(bytes, len);
    if (actual != asset.sha256_hex) {
        // A declared digest that does not match the bytes must never be
        // recorded as evidence.
        return EvidenceStatus::kInvalid;
    }
    const auto existing = impl_->assets.find(asset.asset_id);
    if (existing != impl_->assets.end()) {
        if (existing->second.sha256_hex == asset.sha256_hex) {
            return EvidenceStatus::kDuplicate;
        }
        return EvidenceStatus::kConflict;
    }
    if (!impl_->CapacityAllows(static_cast<std::uint64_t>(len) + 256)) {
        return EvidenceStatus::kFull;
    }
    std::string error;
    if (!impl_->WriteAssetFile(asset.asset_id, bytes, len, &error)) {
        impl_->last_error = error;
        return EvidenceStatus::kIoError;
    }
    if (!impl_->AppendIndex(asset, &error)) {
        impl_->last_error = error;
        // The bytes are on disk but not indexed: the index line is the
        // record of truth, so the asset is not usable evidence yet. It is
        // left on disk (never deleted here) and reported.
        return EvidenceStatus::kIoError;
    }
    impl_->asset_bytes += asset.size_bytes;
    impl_->assets.emplace(asset.asset_id, asset);
    return EvidenceStatus::kOk;
}

std::size_t EvidenceStore::indexCount() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    return impl_->assets.size();
}

bool EvidenceStore::ListAssets(std::vector<SourceAsset>* out) const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    if (out == nullptr) return false;
    out->clear();
    for (const auto& entry : impl_->assets) out->push_back(entry.second);
    return true;
}

bool EvidenceStore::HasAsset(const std::string& asset_id,
                             SourceAsset* out) const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    const auto found = impl_->assets.find(asset_id);
    if (found == impl_->assets.end()) return false;
    if (out != nullptr) *out = found->second;
    return true;
}

bool EvidenceStore::VerifyAsset(const std::string& asset_id,
                                bool* ok_out) const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    if (ok_out != nullptr) *ok_out = false;
    return impl_->VerifyAssetLocked(asset_id, ok_out);
}

std::uint64_t EvidenceStore::bytesUsed() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    return impl_->TotalBytes();
}

std::uint64_t EvidenceStore::capacityBytes() const {
    return impl_->capacity;
}

std::uint64_t EvidenceStore::assetBytes() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    return impl_->asset_bytes;
}

bool EvidenceStore::SealRevision(const std::string& revision_id,
                                  const std::string& flight_id,
                                  const std::string& edge_boot_id,
                                  const std::vector<SourceAsset>& required,
                                  std::int64_t sealed_monotonic_ns,
                                  std::string* manifest_out,
                                  std::string* error) {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    if (!impl_->ok) {
        if (error != nullptr) *error = impl_->last_error;
        return false;
    }
    if (!IsSafeName(revision_id)) {
        if (error != nullptr) *error = "invalid revision id";
        return false;
    }
    JsonValue assets = JsonValue::Arr();
    for (const SourceAsset& asset : required) {
        const auto found = impl_->assets.find(asset.asset_id);
        if (found == impl_->assets.end()) {
            if (error != nullptr) {
                *error = "revision references a missing asset: " + asset.asset_id;
            }
            return false;
        }
        bool verified = false;
        if (!impl_->VerifyAssetLocked(asset.asset_id, &verified)) {
            if (error != nullptr) *error = "asset unreadable: " + asset.asset_id;
            return false;
        }
        if (!verified) {
            if (error != nullptr) {
                *error = "asset digest mismatch: " + asset.asset_id;
            }
            return false;
        }
        assets.Push(AssetToJson(found->second));
    }
    JsonValue manifest = JsonValue::Obj();
    manifest.Set("schema_version", JsonValue::Int(1));
    manifest.Set("revision_id", JsonValue::Str(revision_id));
    manifest.Set("flight_id", JsonValue::Str(flight_id));
    manifest.Set("edge_boot_id", JsonValue::Str(edge_boot_id));
    manifest.Set("sealed_at_monotonic_ns", JsonValue::Int(sealed_monotonic_ns));
    manifest.Set("assets", assets);
    manifest.Set("revision_sha256", JsonValue::Str(DigestExcluding(manifest, "revision_sha256")));
    const std::string bytes = WriteCanonicalJson(manifest);
    // Charge exactly what this seal writes (the manifest). Charging the
    // covered asset bytes a second time blocked sealing on a store that was
    // merely near its bound, which left kept assets unexportable forever.
    if (!impl_->CapacityAllows(static_cast<std::uint64_t>(bytes.size()))) {
        if (error != nullptr) *error = "capacity exceeded while sealing";
        return false;
    }

    const std::string path = JoinPath(impl_->revisions_dir, revision_id + ".json");
    // A sealed revision is IMMUTABLE. Re-sealing the same id with the same
    // content is idempotent; re-sealing it with different content would
    // silently replace the record of what the session covered, so it is
    // refused and the existing revision is kept.
    {
        std::string existing;
        std::string read_error;
        if (ReadWholeFile(path, &existing, &read_error)) {
            if (existing == bytes) {
                if (manifest_out != nullptr) *manifest_out = bytes;
                return true;  // idempotent re-seal
            }
            if (error != nullptr) {
                *error = "revision already sealed with different content: " +
                         revision_id;
            }
            return false;
        }
    }
    const std::string tmp = path + kTmpSuffix;
    const int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        if (error != nullptr) {
            *error = "open revision tmp failed: " + std::string(std::strerror(errno));
        }
        return false;
    }
    bool ok = WriteAllFd(fd, bytes.data(), bytes.size());
    if (ok) {
        const int rc = impl_->SyncFd(fd);
        if (rc != 0) {
            if (error != nullptr) {
                *error = "revision fsync failed: " + std::string(std::strerror(errno));
            }
            ok = false;
        }
    }
    ::close(fd);
    if (!ok) {
        ::unlink(tmp.c_str());
        return false;
    }
    if (impl_->DoRename(tmp.c_str(), path.c_str()) != 0) {
        if (error != nullptr) {
            *error = "revision rename failed: " + std::string(std::strerror(errno));
        }
        ::unlink(tmp.c_str());
        return false;
    }
    if (!impl_->SyncDir(impl_->revisions_dir, error)) return false;
    impl_->revision_bytes += bytes.size();
    if (manifest_out != nullptr) *manifest_out = bytes;
    return true;
}

bool EvidenceStore::ReadRevision(const std::string& revision_id,
                                std::string* manifest_out) const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    if (!IsSafeName(revision_id)) return false;
    const std::string path =
        JoinPath(impl_->revisions_dir, revision_id + ".json");
    std::string content;
    std::string error;
    if (!ReadWholeFile(path, &content, &error)) return false;
    if (manifest_out != nullptr) *manifest_out = content;
    return true;
}

std::vector<std::string> EvidenceStore::sealedRevisions() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    std::vector<std::string> out;
    DIR* dir = ::opendir(impl_->revisions_dir.c_str());
    if (dir == nullptr) return out;
    while (const struct dirent* entry = ::readdir(dir)) {
        const std::string name = entry->d_name;
        if (!IsSafeName(name)) continue;
        if (name.size() < 6) continue;
        if (name.compare(name.size() - 5, 5, ".json") != 0) continue;
        out.push_back(name.substr(0, name.size() - 5));
    }
    ::closedir(dir);
    std::sort(out.begin(), out.end());
    return out;
}

}  // namespace ksi::safety
