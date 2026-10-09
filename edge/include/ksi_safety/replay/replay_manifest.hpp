// Replay manifest v1: the file-based input contract for the Replay driver
// (R1/R3). The manifest describes frames and telemetry samples in ARRIVAL
// ORDER; nothing here is read ahead of its arrival time (the driver pushes
// telemetry up to the current frame time only — see ReplayTelemetrySource).
//
// Format (UTF-8 text, one record per line; '#' comments and blank lines
// ignored; whitespace-separated fields):
//
//   verify <domain> <device_sensor|host_receive> <evidence_ref>
//   frame <frame_id> <recv_ns> <width> <height> <path>
//   exposure <frame_id> <domain> <source_ns>
//   pose <sample_id> <seq> <recv_ns> <domain|-> <source_ns|0> <w> <x> <y> <z>
//   position <sample_id> <seq> <recv_ns> <domain|-> <source_ns|0> <x> <y> <z>
//
// - recv_ns is the replay-clock receive time (monotonic ns, >= 0); manifest
//   order per topic is the arrival order the runner sees.
// - A '-' domain with source_ns 0 means "no source time"
//   (has_source_time=false). A named domain requires source_ns > 0 and marks
//   the sample as carrying a device-source timestamp in that domain.
// - exposure declares a SIMULATED verified capture time for one frame: a
//   device-sensor timestamp in `domain` (verified ONLY if a verify line says
//   so — the data never verifies itself). It exists to exercise the
//   capture-time alignment policy (R3). It is NOT evidence of a real DJI
//   capture time, and the live PSDK path carries no exposure at all.
//   At most one exposure per frame; the frame must exist.
// - verify lines register (domain, kind) as VERIFIED in the clock registry
//   with the given evidence reference; unlisted pairs stay unverified.
//   evidence_ref is a single token (no spaces).
// - frame paths are relative to the manifest's directory (absolute allowed)
//   and name raw RGB files: exactly width*height*3 bytes, no header.
//
// The parser is strict: unknown keywords, wrong field counts, non-numeric
// or out-of-range fields, and missing tokens fail with a line number;
// exposure cross-checks (unknown frame / duplicate frame) fail by frame id.

#ifndef KSI_SAFETY_REPLAY_REPLAY_MANIFEST_HPP
#define KSI_SAFETY_REPLAY_REPLAY_MANIFEST_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/provenance.hpp"

namespace ksi::safety {

struct ReplayFrameEntry {
    std::uint32_t frame_id = 0;
    std::int64_t receive_mono_ns = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::string path;  // raw RGB, relative to the manifest directory
};

// SIMULATED verified capture time for one frame (scenario input, R3). The
// exposure is a device-sensor timestamp in `domain`; it is verified ONLY if
// the registry says so (verify lines). Not real DJI capture evidence.
struct ReplayExposureEntry {
    std::uint32_t frame_id = 0;
    std::string domain;
    std::int64_t source_ns = 0;
};

struct ReplayPoseEntry {
    std::uint64_t sample_id = 0;
    std::uint64_t seq = 0;
    std::int64_t receive_mono_ns = 0;
    bool has_source_time = false;
    std::string domain;       // source-time domain (empty when none)
    std::int64_t source_ns = 0;
    Quaternion value;
};

struct ReplayPositionEntry {
    std::uint64_t sample_id = 0;
    std::uint64_t seq = 0;
    std::int64_t receive_mono_ns = 0;
    bool has_source_time = false;
    std::string domain;
    std::int64_t source_ns = 0;
    Vec3 value;
};

struct ReplayVerificationEntry {
    std::string domain;
    TimeSourceKind kind = TimeSourceKind::kHostReceive;
    std::string evidence_ref;
};

struct ReplayManifest {
    std::vector<ReplayVerificationEntry> verifications;
    std::vector<ReplayFrameEntry> frames;
    std::vector<ReplayExposureEntry> exposures;
    std::vector<ReplayPoseEntry> poses;
    std::vector<ReplayPositionEntry> positions;
};

// Parses the manifest at `path`. On failure returns false and sets `*error`
// to "line N: ..." (or a file-level message); `*out` may hold partial
// records and must not be used. On success `*out` holds the full manifest.
bool LoadReplayManifest(const std::string& path, ReplayManifest* out,
                        std::string* error);

// Registers every verify line in `clocks` (Verify(domain, kind, ref)).
void ApplyManifestVerifications(const ReplayManifest& manifest,
                                ClockRegistry* clocks);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_REPLAY_REPLAY_MANIFEST_HPP
