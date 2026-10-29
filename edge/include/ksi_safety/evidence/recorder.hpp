// Source-evidence recorder: the shared capture path for observation origins.
//
// WHY THIS IS A LIBRARY AND NOT APP CODE
//   The source frame of an observation is the ORIGIN of that judgement. The
//   device app (psdk-app) captured it, but the host Edge (tools/mop_server)
//   and the replay tool must be able to produce the SAME evidence from the
//   SAME code, otherwise "the host test" and "the device run" are different
//   products. So the capture policy lives here and every entry point uses it.
//
// POLICY (explicit, not incidental):
//   - only observations that actually saw something (a fresh detection) are
//     kept: an empty frame carries no review value and would fill the device
//     in seconds;
//   - the asset id and the observation id are derived from the SAME flight,
//     boot and tick, so the link between the stored bytes and the judgement
//     is a derivation, not a guess;
//   - a full store refuses the frame and reports it: a missing frame must be
//     visible, a silently missing frame must not.
//
// The recorder owns its store and its captured-asset list; it never reads a
// clock (the caller stamps monotonic time) and never deletes anything.
#ifndef KSI_SAFETY_EVIDENCE_RECORDER_HPP
#define KSI_SAFETY_EVIDENCE_RECORDER_HPP

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ksi_safety/evidence/store.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

namespace ksi::safety {

// What the recorder needs to know about the frame it is storing.
struct SourceFrame {
    std::string flight_id;
    std::string edge_boot_id;
    std::uint64_t frame_id = 0;
    std::int64_t received_monotonic_ns = 0;
    // The recorder never modifies the frame bytes.
    const std::uint8_t* rgb = nullptr;
    std::size_t rgb_size = 0;
};

class SourceEvidenceRecorder {
 public:
    // `capacity_bytes` bounds the whole store (0 = unbounded). The recorder
    // keeps the store open; a store that failed to open is reported by
    // ok()/last_error() and the recorder then accepts nothing.
    explicit SourceEvidenceRecorder(std::string dir, std::uint64_t capacity_bytes);
    SourceEvidenceRecorder(std::string dir, std::uint64_t capacity_bytes,
                           EvidenceStoreHooks hooks);
    ~SourceEvidenceRecorder();
    SourceEvidenceRecorder(const SourceEvidenceRecorder&) = delete;
    SourceEvidenceRecorder& operator=(const SourceEvidenceRecorder&) = delete;

    bool ok() const;
    const std::string& last_error() const;

    // Stores the source frame of one material observation.
    // Returns the store status (kDuplicate = already kept, kFull = refused
    // because the store is full, kInvalid = bad input, ...).
    EvidenceStatus Capture(const TickRecord& record, const SourceFrame& frame);

    // Seals one immutable revision covering everything captured so far.
    // Returns false with *error set when nothing could be sealed; the stored
    // bytes are never removed by a failed seal.
    bool Seal(const std::string& flight_id, const std::string& edge_boot_id,
              std::int64_t sealed_monotonic_ns, std::string* revision_id_out,
              std::string* manifest_out, std::string* error);

    // Assets captured so far, in capture order.
    std::vector<SourceAsset> captured() const;
    std::size_t captured_count() const;
    std::uint64_t bytes_used() const;
    std::uint64_t capacity_bytes() const;

 private:
    struct Impl;
    Impl* impl_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_EVIDENCE_RECORDER_HPP
