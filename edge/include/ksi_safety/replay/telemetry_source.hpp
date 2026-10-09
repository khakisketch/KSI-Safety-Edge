// ReplayTelemetrySource: pushes manifest telemetry into a SessionRunner in
// ARRIVAL ORDER, and never ahead of the current replay time.
//
// PushDue(now) delivers every pending sample whose receive time is <= now,
// in manifest order per topic (poses first, then positions), and nothing
// else; samples with a later receive time stay pending until a later call.
// This is the "no future samples" guarantee: matching for a frame processed
// at time T can only ever see samples that had arrived by T.
//
// Samples are built through the caller's ClockRegistry semantics: the
// receive timestamp is (receive_domain, kHostReceive) and a sample's source
// timestamp is (domain, kDeviceSensor). Both are verified ONLY if the
// registry says so (manifest verify lines / caller configuration); this
// source never promotes a timestamp's verification tier.
//
// The manifest must outlive this source (it is referenced, not copied).

#ifndef KSI_SAFETY_REPLAY_TELEMETRY_SOURCE_HPP
#define KSI_SAFETY_REPLAY_TELEMETRY_SOURCE_HPP

#include <cstddef>
#include <cstdint>
#include <string>

#include "ksi_safety/replay/replay_manifest.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/time/provenance.hpp"

namespace ksi::safety {

class ReplayTelemetrySource {
  public:
    struct PushResult {
        std::size_t poses_pushed = 0;
        std::size_t positions_pushed = 0;
        std::size_t duplicates = 0;  // ring: duplicate sample_id
        std::size_t rollbacks = 0;   // ring: receive-time rollback
    };

    ReplayTelemetrySource(const ReplayManifest& manifest,
                          std::string receive_domain,
                          const ClockRegistry& clocks);

    // Pushes all due samples into `runner` (must outlive the call). Returns
    // per-kind counts for the pushed window.
    PushResult PushDue(std::int64_t now_ns, SessionRunner& runner);

    bool has_pending() const { return pending_count() > 0; }
    std::size_t pending_count() const;

  private:
    const ReplayManifest& manifest_;
    std::string receive_domain_;
    const ClockRegistry& clocks_;
    std::size_t pose_index_ = 0;
    std::size_t position_index_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_REPLAY_TELEMETRY_SOURCE_HPP
