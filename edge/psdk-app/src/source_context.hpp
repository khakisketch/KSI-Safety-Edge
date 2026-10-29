// DJI-free device-worker provenance wiring, exercised by host shared-path tests.
#pragma once

#include <string>
#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/time/matching.hpp"

namespace ksi_safety_edge_app {
struct SourceIdentity {
    std::string flight_id;
    std::string edge_boot_id;
};

inline ksi::safety::FrameContext MakeReceiveOnlySourceContext(
    const ksi::safety::FrameMetadata& metadata, const SourceIdentity& identity,
    const std::string& configured_stream_id) {
    ksi::safety::FrameContext ctx;
    ctx.flight_id = identity.flight_id;
    ctx.boot_id = identity.edge_boot_id;
    ctx.stream_id = configured_stream_id;
    ctx.frame_id = metadata.frame_id;
    ctx.receive_monotonic_ns = metadata.received_monotonic_ns;
    // Current PSDK RGB has NO verified exposure clock. Never promote a raw
    // timestamp merely because the metadata structure has a timestamp slot.
    ctx.has_exposure = false;
    return ctx;
}
}  // namespace ksi_safety_edge_app
