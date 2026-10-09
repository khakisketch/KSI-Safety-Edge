#include "ksi_safety/replay/frame_source.hpp"

#include <cstddef>
#include <cstdint>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

namespace ksi::safety {
namespace {

std::string DirOf(const std::string& path) {
    const std::size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return ".";
    }
    if (slash == 0) {
        return "/";
    }
    return path.substr(0, slash);
}

std::string ResolvePath(const std::string& dir, const std::string& path) {
    if (!path.empty() && path[0] == '/') {
        return path;
    }
    return dir + "/" + path;
}

}  // namespace

ReplayFrameSource::ReplayFrameSource(const std::string& manifest_path,
                                     std::string flight_id,
                                     std::string boot_id,
                                     std::string stream_id)
    : dir_(DirOf(manifest_path)),
      flight_id_(std::move(flight_id)),
      boot_id_(std::move(boot_id)),
      stream_id_(std::move(stream_id)) {
    ok_ = LoadReplayManifest(manifest_path, &manifest_, &error_);
}

bool ReplayFrameSource::ReadFrame(std::size_t index, FramePacket* packet,
                                  FrameContext* ctx) const {
    if (!ok_ || packet == nullptr || ctx == nullptr ||
        index >= manifest_.frames.size()) {
        return false;
    }
    const ReplayFrameEntry& entry = manifest_.frames[index];
    const std::size_t expected = static_cast<std::size_t>(entry.width) *
                                 static_cast<std::size_t>(entry.height) * 3U;
    std::vector<std::uint8_t> rgb(expected);
    std::ifstream in(ResolvePath(dir_, entry.path), std::ios::binary);
    if (!in) {
        return false;
    }
    in.read(reinterpret_cast<char*>(rgb.data()),
            static_cast<std::streamsize>(expected));
    if (in.gcount() != static_cast<std::streamsize>(expected)) {
        return false;  // short file
    }
    char extra = 0;
    if (in.read(&extra, 1)) {
        return false;  // trailing bytes: not exactly width*height*3
    }

    packet->metadata.width = entry.width;
    packet->metadata.height = entry.height;
    packet->metadata.frame_id = entry.frame_id;
    packet->metadata.received_monotonic_ns = entry.receive_mono_ns;
    packet->metadata.sensor_timestamp_ns = 0;  // no sensor clock in replay v1
    packet->metadata.received_utc_ms = 0;      // no wall clock in replay v1
    packet->rgb = std::move(rgb);

    *ctx = FrameContext{};
    ctx->flight_id = flight_id_;
    ctx->boot_id = boot_id_;
    ctx->stream_id = stream_id_;
    ctx->frame_id = entry.frame_id;
    ctx->receive_monotonic_ns = entry.receive_mono_ns;
    ctx->has_exposure = false;  // receive grade only (never a capture claim)
    return true;
}

}  // namespace ksi::safety
