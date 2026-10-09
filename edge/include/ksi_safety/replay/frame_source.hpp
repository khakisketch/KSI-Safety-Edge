// ReplayFrameSource: reads frames from a replay manifest, one at a time,
// and fills the SAME FramePacket/FrameContext types the PSDK capture path
// produces — the shared SessionRunner cannot tell the sources apart.
//
// Provenance rules (R1):
// - width/height/frame_id/received_monotonic_ns mirror the manifest entry.
// - sensor_timestamp_ns stays 0 (replay v1 has no sensor clock) and
//   has_exposure stays false: receive time is never promoted to a capture
//   time (R3 concerns; receive-nearest matching grades stay receive grade).
// - A frame file must be exactly width*height*3 bytes of raw RGB; a missing
//   file, a short read, or trailing bytes fails ReadFrame.
//
// No clock is read; the replay timeline is entirely manifest data.

#ifndef KSI_SAFETY_REPLAY_FRAME_SOURCE_HPP
#define KSI_SAFETY_REPLAY_FRAME_SOURCE_HPP

#include <cstddef>
#include <string>

#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/replay/replay_manifest.hpp"
#include "ksi_safety/time/matching.hpp"

namespace ksi::safety {

class ReplayFrameSource {
  public:
    // Loads and validates the manifest. `ok()` is false when the manifest
    // cannot be read/parsed (`error()` says why). flight/boot/stream ids are
    // stamped into every FrameContext (they identify the replay run, exactly
    // as the PSDK app stamps its own).
    ReplayFrameSource(const std::string& manifest_path, std::string flight_id,
                      std::string boot_id, std::string stream_id);

    bool ok() const { return ok_; }
    const std::string& error() const { return error_; }
    std::size_t frame_count() const { return manifest_.frames.size(); }
    const ReplayManifest& manifest() const { return manifest_; }

    // Reads frame `index` (0-based) into `*packet` and `*ctx`. Fails on an
    // out-of-range index, null outputs, an unreadable frame file, or a file
    // size other than width*height*3.
    bool ReadFrame(std::size_t index, FramePacket* packet,
                   FrameContext* ctx) const;

  private:
    ReplayManifest manifest_;
    std::string dir_;
    std::string flight_id_;
    std::string boot_id_;
    std::string stream_id_;
    bool ok_ = false;
    std::string error_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_REPLAY_FRAME_SOURCE_HPP
