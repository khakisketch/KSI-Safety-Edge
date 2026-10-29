// Source-evidence store: durability, honesty and refusal contracts.
//
// The store persists the bytes an observation was computed from. These tests
// pin the guarantees that make that evidence usable later:
//   1) an asset is written atomically (temp -> fsync -> rename -> fsync dir);
//   2) a declared digest that does not match the bytes is refused;
//   3) the same asset id with different bytes is refused (never overwritten);
//   4) the same asset id with the same bytes is idempotent;
//   5) a torn index tail is truncated on open; an interior malformed line
//      fails closed;
//   6) a leftover temp file is never treated as evidence;
//   7) capacity is an explicit bound that refuses writes instead of evicting;
//   8) a sealed revision lists exactly the verified assets and is immutable.
//
// Real filesystem, real fsync: the point of this store is durability.

#include "ksi_safety/evidence/store.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"
#include "support/check.hpp"

using ksi::safety::EvidenceStatus;
using ksi::safety::EvidenceStore;
using ksi::safety::SourceAsset;
using ksi::safety::Sha256Hex;

namespace {

std::string TempDir(const char* tag) {
    // Portable base dir: the test runs on the host AND on the Manifold 3
    // (where /tmp/opencode does not exist).
    const char* base = std::getenv("TMPDIR");
    if (base == nullptr || base[0] == '\0') base = "/tmp";
    char pattern[192];
    std::snprintf(pattern, sizeof(pattern), "%s/evidence-%s-XXXXXX", base, tag);
    char* made = ::mkdtemp(pattern);
    return made == nullptr ? std::string() : std::string(made);
}

SourceAsset MakeAsset(const std::string& id, const std::string& bytes,
                       std::uint64_t frame_id) {
    SourceAsset asset;
    asset.asset_id = id;
    asset.sha256_hex = Sha256Hex(
        reinterpret_cast<const std::uint8_t*>(bytes.data()), bytes.size());
    asset.size_bytes = bytes.size();
    asset.media_type = "application/x-raw-rgb24";
    asset.flight_id = "flight-1";
    asset.edge_boot_id = "boot-1";
    asset.frame_id = frame_id;
    asset.received_monotonic_ns = 1000 + static_cast<std::int64_t>(frame_id);
    asset.observation_id = "obs-" + id;
    return asset;
}

}  // namespace

int main() {
    // 1) A stored asset is readable, indexed and verifiable.
    {
        const std::string dir = TempDir("basic");
        EvidenceStore store(dir, 0);
        CHECK(store.ok(), "store opens cleanly");
        const std::string bytes = "frame-bytes-0001";
        const SourceAsset asset = MakeAsset("asset-1", bytes, 7);
        const EvidenceStatus status = store.PutSourceAsset(
            asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
            bytes.size());
        CHECK(status == EvidenceStatus::kOk, "asset stored");
        CHECK(store.indexCount() == 1, "index holds one asset");
        SourceAsset read;
        CHECK(store.HasAsset("asset-1", &read), "asset is listed");
        CHECK(read.sha256_hex == asset.sha256_hex, "digest is preserved");
        CHECK(read.size_bytes == bytes.size(), "size is preserved");
        CHECK(read.observation_id == asset.observation_id, "lineage is preserved");
        bool verified = false;
        CHECK(store.VerifyAsset("asset-1", &verified), "verification runs");
        CHECK(verified, "stored bytes still hash to the recorded digest");
        CHECK(store.bytesUsed() > 0, "usage is accounted");
    }

    // 2) A digest that does not match the bytes is refused (nothing written).
    {
        const std::string dir = TempDir("mismatch");
        EvidenceStore store(dir, 0);
        SourceAsset asset = MakeAsset("asset-bad", "real-bytes", 1);
        asset.sha256_hex = Sha256Hex(
            reinterpret_cast<const std::uint8_t*>("different"), 9);
        const std::string bytes = "real-bytes";
        const EvidenceStatus status = store.PutSourceAsset(
            asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
            bytes.size());
        CHECK(status == EvidenceStatus::kInvalid, "digest mismatch is refused");
        CHECK(store.indexCount() == 0, "nothing is indexed");
    }

    // 3) The same id with different bytes is a conflict; with the same bytes
    //    it is idempotent.
    {
        const std::string dir = TempDir("conflict");
        EvidenceStore store(dir, 0);
        const std::string bytes = "same-bytes";
        const SourceAsset asset = MakeAsset("asset-x", bytes, 2);
        CHECK(store.PutSourceAsset(
                  asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
                  bytes.size()) == EvidenceStatus::kOk, "first write ok");
        CHECK(store.PutSourceAsset(
                  asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
                  bytes.size()) == EvidenceStatus::kDuplicate,
              "same id + same digest is idempotent");
        const std::string other = "other-bytes";
        SourceAsset changed = asset;
        changed.sha256_hex = Sha256Hex(
            reinterpret_cast<const std::uint8_t*>(other.data()), other.size());
        changed.size_bytes = other.size();
        CHECK(store.PutSourceAsset(
                  changed,
                  reinterpret_cast<const std::uint8_t*>(other.data()),
                  other.size()) == EvidenceStatus::kConflict,
              "same id + different digest is refused");
        CHECK(store.indexCount() == 1, "the index still holds one asset");
    }

    // 4) A torn index tail is truncated on open; committed records survive.
    {
        const std::string dir = TempDir("torn");
        {
            EvidenceStore store(dir, 0);
            const std::string bytes = "committed";
            const SourceAsset asset = MakeAsset("asset-keep", bytes, 3);
            CHECK(store.PutSourceAsset(
                      asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size()) == EvidenceStatus::kOk, "write ok");
        }
        // Append an unterminated line (a crash mid-append).
        const std::string index_path = dir + "/index.ndjson";
        const int fd = ::open(index_path.c_str(),
                              O_WRONLY | O_APPEND | O_CLOEXEC, 0600);
        CHECK(fd >= 0, "index reopened");
        const char* torn = "{\"asset_id\":\"asset-torn\"";
        CHECK(::write(fd, torn, std::strlen(torn)) ==
                  static_cast<ssize_t>(std::strlen(torn)),
              "torn bytes appended");
        ::close(fd);
        {
            EvidenceStore store(dir, 0);
            CHECK(store.ok(), "store recovers from a torn tail");
            CHECK(store.tornRepaired(), "the torn tail is reported");
            CHECK(store.indexCount() == 1, "the committed record survives");
            SourceAsset read;
            CHECK(store.HasAsset("asset-keep", &read),
                  "the committed asset is still indexed");
        }
    }

    // 5) An interior malformed index line fails closed (kept for forensics).
    {
        const std::string dir = TempDir("corrupt");
        {
            EvidenceStore store(dir, 0);
            const std::string bytes = "committed";
            const SourceAsset asset = MakeAsset("asset-keep", bytes, 4);
            CHECK(store.PutSourceAsset(
                      asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size()) == EvidenceStatus::kOk, "write ok");
        }
        const std::string index_path = dir + "/index.ndjson";
        const int fd = ::open(index_path.c_str(),
                              O_WRONLY | O_APPEND | O_CLOEXEC, 0600);
        CHECK(fd >= 0, "index reopened");
        const char* broken = "not-json\n";
        CHECK(::write(fd, broken, std::strlen(broken)) ==
                  static_cast<ssize_t>(std::strlen(broken)),
              "broken line appended");
        ::close(fd);
        EvidenceStore store(dir, 0);
        CHECK(!store.ok(), "an interior malformed line fails closed");
        CHECK(store.openStatus() == EvidenceStatus::kCorrupt,
              "the status says corrupt");
        CHECK(store.PutSourceAsset(
                  MakeAsset("asset-new", "x", 1),
                  reinterpret_cast<const std::uint8_t*>("x"), 1) ==
                  EvidenceStatus::kCorrupt,
              "a corrupt store accepts nothing");
    }

    // 6) A leftover temp file is never evidence and is removed on open.
    {
        const std::string dir = TempDir("tmp");
        {
            EvidenceStore store(dir, 0);
            const std::string bytes = "committed";
            const SourceAsset asset = MakeAsset("asset-keep", bytes, 5);
            CHECK(store.PutSourceAsset(
                      asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size()) == EvidenceStatus::kOk, "write ok");
        }
        const std::string tmp_path = dir + "/assets/asset-keep.tmp";
        const int fd = ::open(tmp_path.c_str(),
                              O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        CHECK(fd >= 0, "temp file created");
        const char* partial = "half-written";
        CHECK(::write(fd, partial, std::strlen(partial)) > 0, "temp written");
        ::close(fd);
        EvidenceStore store(dir, 0);
        CHECK(store.ok(), "store opens with a leftover temp file");
        CHECK(store.tempFilesRemoved() == 1, "the temp file was removed");
        CHECK(store.indexCount() == 1, "the temp file was never indexed");
    }

    // 7) Capacity refuses writes instead of evicting anything.
    {
        const std::string dir = TempDir("full");
        EvidenceStore store(dir, 64);
        const std::string bytes(200, 'x');
        const SourceAsset asset = MakeAsset("asset-big", bytes, 6);
        const EvidenceStatus status = store.PutSourceAsset(
            asset, reinterpret_cast<const std::uint8_t*>(bytes.data()),
            bytes.size());
        CHECK(status == EvidenceStatus::kFull, "capacity refuses the write");
        CHECK(store.indexCount() == 0, "nothing was stored");
        SourceAsset read;
        CHECK(!store.HasAsset("asset-big", &read), "the asset is unknown");
    }

    // 8) Sealing a revision lists exactly the verified assets and is immutable.
    {
        const std::string dir = TempDir("seal");
        EvidenceStore store(dir, 0);
        const std::string first = "frame-a";
        const std::string second = "frame-b";
        const SourceAsset a = MakeAsset("asset-a", first, 1);
        const SourceAsset b = MakeAsset("asset-b", second, 2);
        CHECK(store.PutSourceAsset(
                  a, reinterpret_cast<const std::uint8_t*>(first.data()),
                  first.size()) == EvidenceStatus::kOk, "asset a stored");
        CHECK(store.PutSourceAsset(
                  b, reinterpret_cast<const std::uint8_t*>(second.data()),
                  second.size()) == EvidenceStatus::kOk, "asset b stored");
        std::string manifest;
        std::string error;
        CHECK(store.SealRevision("rev-1", "flight-1", "boot-1", {a, b},
                                 4242, &manifest, &error),
              "revision sealed");
        CHECK(manifest.find("\"revision_id\":\"rev-1\"") != std::string::npos,
              "the manifest names the revision");
        CHECK(manifest.find("asset-a") != std::string::npos &&
                  manifest.find("asset-b") != std::string::npos,
              "the manifest lists both assets");
        std::string read_back;
        CHECK(store.ReadRevision("rev-1", &read_back), "the revision is readable");
        CHECK(read_back == manifest, "the sealed revision is byte-identical");
        const std::vector<std::string> revisions = store.sealedRevisions();
        CHECK(revisions.size() == 1 && revisions[0] == "rev-1",
              "the revision is listed");
        // A sealed revision is immutable: re-sealing the same id with
        // DIFFERENT content is refused and the sealed bytes are kept.
        SourceAsset extra = MakeAsset("asset-c", "frame-c", 3);
        CHECK(store.PutSourceAsset(
                  extra, reinterpret_cast<const std::uint8_t*>("frame-c"), 7) ==
                  EvidenceStatus::kOk,
              "asset c stored");
        std::string error2;
        CHECK(!store.SealRevision("rev-1", "flight-1", "boot-1", {a, b, extra},
                                  9999, nullptr, &error2),
              "re-sealing the same revision id with new content is refused");
        std::string kept;
        CHECK(store.ReadRevision("rev-1", &kept), "the sealed revision is kept");
        CHECK(kept == manifest, "the sealed bytes are unchanged");
        // Re-sealing with the SAME content is idempotent.
        CHECK(store.SealRevision("rev-1", "flight-1", "boot-1", {a, b}, 4242,
                                  nullptr, &error2),
              "re-sealing the same content is idempotent");

        // A revision referencing a missing asset is refused and seals nothing.
        CHECK(!store.SealRevision("rev-2", "flight-1", "boot-1",
                                   {MakeAsset("asset-missing", "x", 9)}, 1,
                                   nullptr, &error),
              "a revision with a missing asset is refused");
        CHECK(store.sealedRevisions().size() == 1, "rev-2 was not sealed");
    }

    // 9) Sealing charges the manifest it writes, not the covered assets a
    //    second time: a store near its bound can still export what it kept.
    {
        const std::string payload_a(4096, 'a');
        const std::string payload_b(4096, 'b');
        const std::string payload_c(4096, 'c');
        const std::vector<SourceAsset> kept = {
            MakeAsset("asset-charge-a", payload_a, 1),
            MakeAsset("asset-charge-b", payload_b, 2),
            MakeAsset("asset-charge-c", payload_c, 3)};
        const std::uint8_t* bytes[3] = {
            reinterpret_cast<const std::uint8_t*>(payload_a.data()),
            reinterpret_cast<const std::uint8_t*>(payload_b.data()),
            reinterpret_cast<const std::uint8_t*>(payload_c.data())};

        const std::string base = TempDir("seal-charge");
        std::uint64_t used = 0;
        {
            EvidenceStore probe(base, 0);
            CHECK(probe.ok(), "unbounded store opens");
            for (std::size_t i = 0; i < kept.size(); ++i) {
                CHECK(probe.PutSourceAsset(kept[i], bytes[i],
                                           kept[i].size_bytes) ==
                          EvidenceStatus::kOk,
                      "asset stored before the seal-capacity check");
            }
            used = probe.bytesUsed();
        }
        // Headroom smaller than the 12 KiB of covered assets but larger than
        // the revision manifest. The old double charge refused this seal.
        {
            EvidenceStore tight(base + "-tight", used + 2048);
            CHECK(tight.ok(), "near-bound store opens");
            for (std::size_t i = 0; i < kept.size(); ++i) {
                CHECK(tight.PutSourceAsset(kept[i], bytes[i],
                                           kept[i].size_bytes) ==
                          EvidenceStatus::kOk,
                      "asset stored under the tight bound");
            }
            std::string error;
            CHECK(tight.SealRevision("rev-tight", "flight-1", "boot-1", kept, 5,
                                     nullptr, &error),
                  "a near-bound store can still seal what it kept");
        }
        // The bound still applies: no room for the manifest means no seal.
        {
            EvidenceStore tiny(base + "-tiny", used + 64);
            CHECK(tiny.ok(), "tiny store opens");
            for (std::size_t i = 0; i < kept.size(); ++i) {
                (void)tiny.PutSourceAsset(kept[i], bytes[i], kept[i].size_bytes);
            }
            std::string error;
            CHECK(!tiny.SealRevision("rev-tiny", "flight-1", "boot-1", kept, 5,
                                     nullptr, &error),
                  "a manifest that does not fit is still refused");
        }
    }

    return edge_test::failures == 0 ? 0 : 1;
}
