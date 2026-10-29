// Source-evidence recorder: the shared capture policy (every Edge entry
// point uses this code, so the host test and the device run are one product).
//
// Pinned here:
//   ① only a MATERIAL observation (fresh detection) keeps its source frame;
//   ② the asset id and the observation id are derived from the same
//      (flight, boot, tick) — a derivation, not a guess;
//   ③ a full store refuses the frame and says so (no silent loss);
//   ④ sealing produces one immutable revision that names the assets.

#include "ksi_safety/evidence/recorder.hpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "support/check.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

using ksi::safety::EvidenceStatus;
using ksi::safety::SourceEvidenceRecorder;
using ksi::safety::SourceFrame;
using ksi::safety::TickRecord;

namespace {

std::string TempDir(const char* tag) {
    const char* base = std::getenv("TMPDIR");
    if (base == nullptr || base[0] == '\0') base = "/tmp";
    char pattern[192];
    std::snprintf(pattern, sizeof(pattern), "%s/recorder-%s-XXXXXX", base, tag);
    char* made = ::mkdtemp(pattern);
    return made == nullptr ? std::string() : std::string(made);
}

TickRecord MaterialTick(std::uint64_t tick_index) {
    TickRecord record;
    record.tick_index = tick_index;
    record.detection_status = ksi::safety::DetectionStatus::kOk;
    ksi::safety::Detection detection;
    detection.class_id = 0;
    detection.score = 0.9f;
    record.detections.push_back(detection);
    return record;
}

SourceFrame Frame(const std::string& flight, const std::string& boot,
                  const std::vector<std::uint8_t>& bytes) {
    SourceFrame frame;
    frame.flight_id = flight;
    frame.edge_boot_id = boot;
    frame.frame_id = 42;
    frame.received_monotonic_ns = 1000;
    frame.rgb = const_cast<std::uint8_t*>(bytes.data());
    frame.rgb_size = bytes.size();
    return frame;
}

}  // namespace

int main() {
    // 1) A material observation keeps its source frame, with a derived link.
    {
        SourceEvidenceRecorder recorder(TempDir("basic"), 0);
        CHECK(recorder.ok(), "the recorder opens");
        const std::vector<std::uint8_t> bytes = {1, 2, 3, 4, 5};
        const TickRecord record = MaterialTick(7);
        const SourceFrame frame = Frame("flight-1", "boot-1", bytes);
        CHECK(recorder.Capture(record, frame) == EvidenceStatus::kOk,
              "a material observation is captured");
        CHECK(recorder.captured_count() == 1, "one asset is kept");

        const auto assets = recorder.captured();
        const std::string expected_observation =
            ksi::safety::MopTickEvidence::ObservationIdFor("flight-1", "boot-1", 7);
        CHECK(assets[0].observation_id == expected_observation,
              "the asset names the same observation the MOP path names");
        CHECK(assets[0].sha256_hex.size() == 64, "the asset carries a digest");
        CHECK(assets[0].size_bytes == bytes.size(), "the asset carries its size");
        CHECK(assets[0].media_type == "application/x-raw-rgb24",
              "the asset says what it is");
    }

    // 2) An observation that saw nothing keeps nothing.
    {
        SourceEvidenceRecorder recorder(TempDir("empty"), 0);
        TickRecord record = MaterialTick(1);
        record.detections.clear();
        const std::vector<std::uint8_t> bytes = {9, 9};
        CHECK(recorder.Capture(record, Frame("f", "b", bytes)) ==
                  EvidenceStatus::kInvalid,
              "an empty observation is not evidence");
        CHECK(recorder.captured_count() == 0, "nothing was kept");
    }

    // 3) A tick that never ran the detector keeps nothing.
    {
        SourceEvidenceRecorder recorder(TempDir("notrun"), 0);
        TickRecord record;
        record.tick_index = 1;
        record.detection_status = ksi::safety::DetectionStatus::kNotRun;
        const std::vector<std::uint8_t> bytes = {1};
        CHECK(recorder.Capture(record, Frame("f", "b", bytes)) ==
                  EvidenceStatus::kInvalid,
              "a tick that did not run is not evidence");
    }

    // 4) Missing identity is refused (no half-identified evidence).
    {
        SourceEvidenceRecorder recorder(TempDir("noid"), 0);
        const std::vector<std::uint8_t> bytes = {1};
        CHECK(recorder.Capture(MaterialTick(1), Frame("", "b", bytes)) ==
                  EvidenceStatus::kInvalid,
              "a frame without a flight id is refused");
        CHECK(recorder.Capture(MaterialTick(1), Frame("f", "", bytes)) ==
                  EvidenceStatus::kInvalid,
              "a frame without a boot id is refused");
    }

    // 5) A full store refuses the frame instead of dropping it silently.
    {
        SourceEvidenceRecorder recorder(TempDir("full"), 64);
        const std::vector<std::uint8_t> bytes(200, 7);
        const EvidenceStatus status =
            recorder.Capture(MaterialTick(1), Frame("f", "b", bytes));
        CHECK(status == EvidenceStatus::kFull, "a full store refuses the write");
        CHECK(recorder.captured_count() == 0, "nothing was claimed as kept");
    }

    // 6) Sealing produces one immutable revision that names the assets.
    {
        SourceEvidenceRecorder recorder(TempDir("seal"), 0);
        const std::vector<std::uint8_t> bytes = {4, 5, 6};
        CHECK(recorder.Capture(MaterialTick(3), Frame("flight-1", "boot-1", bytes)) ==
                  EvidenceStatus::kOk,
              "captured");
        std::string revision_id;
        std::string manifest;
        std::string error;
        CHECK(recorder.Seal("flight-1", "boot-1", 4242, &revision_id, &manifest,
                            &error),
              "the revision is sealed");
        CHECK(!revision_id.empty(), "the revision has an id");
        CHECK(manifest.find("\"assets\":[") != std::string::npos,
              "the manifest lists assets");
        CHECK(manifest.find("\"flight_id\":\"flight-1\"") != std::string::npos,
              "the manifest names the flight");
        // Sealing again with the same time is idempotent (same revision id).
        std::string again_id;
        CHECK(recorder.Seal("flight-1", "boot-1", 4242, &again_id, nullptr,
                            &error),
              "re-sealing the same revision is idempotent");
        CHECK(again_id == revision_id, "the revision id is stable");
        // A different seal time is a different revision.
        std::string other_id;
        CHECK(recorder.Seal("flight-1", "boot-1", 9999, &other_id, nullptr,
                            &error),
              "a later seal is its own revision");
        CHECK(other_id != revision_id, "a different seal time is a new revision");
    }

    // 7) Sealing nothing is an honest failure, not an empty "success".
    {
        SourceEvidenceRecorder recorder(TempDir("nothing"), 0);
        std::string error;
        CHECK(!recorder.Seal("flight-1", "boot-1", 1, nullptr, nullptr, &error),
              "sealing nothing fails");
        CHECK(!error.empty(), "the failure says why");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
