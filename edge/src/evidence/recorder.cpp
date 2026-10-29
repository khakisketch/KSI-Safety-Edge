#include "ksi_safety/evidence/recorder.hpp"

#include <mutex>
#include <utility>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

namespace {

// The asset id is derived from the same (flight, boot, tick) as the
// observation id, so the link between the stored bytes and the judgement is a
// derivation — never a guess.
std::string DeriveAssetId(const std::string& flight_id,
                          const std::string& edge_boot_id,
                          std::uint64_t tick_index) {
    return Uuid5(MopProtocol::Namespace(),
                 "src|" + flight_id + "|" + edge_boot_id + "|" +
                     std::to_string(tick_index));
}

}  // namespace

struct SourceEvidenceRecorder::Impl {
    std::unique_ptr<EvidenceStore> store;
    std::vector<SourceAsset> captured;
    mutable std::mutex mutex;
};

SourceEvidenceRecorder::SourceEvidenceRecorder(std::string dir,
                                               std::uint64_t capacity_bytes)
    : SourceEvidenceRecorder(std::move(dir), capacity_bytes, EvidenceStoreHooks{}) {}

SourceEvidenceRecorder::SourceEvidenceRecorder(std::string dir,
                                               std::uint64_t capacity_bytes,
                                               EvidenceStoreHooks hooks)
    : impl_(new Impl()) {
    impl_->store = std::make_unique<EvidenceStore>(std::move(dir), capacity_bytes,
                                                    std::move(hooks));
}

SourceEvidenceRecorder::~SourceEvidenceRecorder() { delete impl_; }

bool SourceEvidenceRecorder::ok() const {
    return impl_->store != nullptr && impl_->store->ok();
}

const std::string& SourceEvidenceRecorder::last_error() const {
    static const std::string kNoStore = "evidence store is not open";
    return impl_->store == nullptr ? kNoStore : impl_->store->lastError();
}

EvidenceStatus SourceEvidenceRecorder::Capture(const TickRecord& record,
                                               const SourceFrame& frame) {
    if (impl_->store == nullptr || !impl_->store->ok()) {
        return EvidenceStatus::kCorrupt;
    }
    // Only material observations keep their source frame.
    if (record.detection_status != DetectionStatus::kOk) {
        return EvidenceStatus::kInvalid;
    }
    if (record.detections.empty()) return EvidenceStatus::kInvalid;
    if (frame.rgb == nullptr || frame.rgb_size == 0) {
        return EvidenceStatus::kInvalid;
    }
    if (frame.flight_id.empty() || frame.edge_boot_id.empty()) {
        return EvidenceStatus::kInvalid;
    }
    SourceAsset asset;
    asset.asset_id = DeriveAssetId(frame.flight_id, frame.edge_boot_id,
                                   record.tick_index);
    asset.sha256_hex = Sha256Hex(frame.rgb, frame.rgb_size);
    asset.size_bytes = frame.rgb_size;
    asset.media_type = "application/x-raw-rgb24";
    asset.flight_id = frame.flight_id;
    asset.edge_boot_id = frame.edge_boot_id;
    asset.frame_id = frame.frame_id;
    asset.received_monotonic_ns = frame.received_monotonic_ns;
    asset.observation_id = MopTickEvidence::ObservationIdFor(
        frame.flight_id, frame.edge_boot_id, record.tick_index);
    const EvidenceStatus status =
        impl_->store->PutSourceAsset(asset, frame.rgb, frame.rgb_size);
    if (status == EvidenceStatus::kOk) {
        std::lock_guard<std::mutex> guard(impl_->mutex);
        impl_->captured.push_back(asset);
    }
    return status;
}

bool SourceEvidenceRecorder::Seal(const std::string& flight_id,
                                  const std::string& edge_boot_id,
                                  std::int64_t sealed_monotonic_ns,
                                  std::string* revision_id_out,
                                  std::string* manifest_out,
                                  std::string* error) {
    if (impl_->store == nullptr || !impl_->store->ok()) {
        if (error != nullptr) *error = "evidence store is not usable";
        return false;
    }
    std::vector<SourceAsset> assets;
    {
        std::lock_guard<std::mutex> guard(impl_->mutex);
        assets = impl_->captured;
    }
    if (assets.empty()) {
        if (error != nullptr) *error = "no source frames were captured";
        return false;
    }
    const std::string revision_id =
        Uuid5(MopProtocol::Namespace(),
              "rev|" + flight_id + "|" + edge_boot_id + "|" +
                  std::to_string(sealed_monotonic_ns));
    std::string manifest;
    std::string seal_error;
    if (!impl_->store->SealRevision(revision_id, flight_id, edge_boot_id, assets,
                                    sealed_monotonic_ns, &manifest, &seal_error)) {
        if (error != nullptr) *error = seal_error;
        return false;
    }
    if (revision_id_out != nullptr) *revision_id_out = revision_id;
    if (manifest_out != nullptr) *manifest_out = manifest;
    return true;
}

std::vector<SourceAsset> SourceEvidenceRecorder::captured() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    return impl_->captured;
}

std::size_t SourceEvidenceRecorder::captured_count() const {
    std::lock_guard<std::mutex> guard(impl_->mutex);
    return impl_->captured.size();
}

std::uint64_t SourceEvidenceRecorder::bytes_used() const {
    return impl_->store == nullptr ? 0 : impl_->store->bytesUsed();
}

std::uint64_t SourceEvidenceRecorder::capacity_bytes() const {
    return impl_->store == nullptr ? 0 : impl_->store->capacityBytes();
}

}  // namespace ksi::safety
