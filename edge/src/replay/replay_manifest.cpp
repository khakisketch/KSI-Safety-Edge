#include "ksi_safety/replay/replay_manifest.hpp"

#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

namespace ksi::safety {
namespace {

bool ParseDouble(std::istringstream* iss, double* out) {
    double value = 0.0;
    if (!(*iss >> value) || !std::isfinite(value)) {
        return false;
    }
    *out = value;
    return true;
}

bool ParseInt64(std::istringstream* iss, std::int64_t* out) {
    long long value = 0;
    if (!(*iss >> value)) {
        return false;
    }
    *out = static_cast<std::int64_t>(value);
    return true;
}

bool ParseUint64(std::istringstream* iss, std::uint64_t* out) {
    unsigned long long value = 0;
    if (!(*iss >> value)) {
        return false;
    }
    *out = static_cast<std::uint64_t>(value);
    return true;
}

bool ParseUint32(std::istringstream* iss, std::uint32_t* out) {
    unsigned long long value = 0;
    if (!(*iss >> value) ||
        value > static_cast<unsigned long long>(
                    std::numeric_limits<std::uint32_t>::max())) {
        return false;
    }
    *out = static_cast<std::uint32_t>(value);
    return true;
}

// True when one more whitespace-separated token exists (too many fields).
bool HasExtraField(std::istringstream* iss) {
    std::string extra;
    return static_cast<bool>(*iss >> extra);
}

bool Fail(const std::string& line_label, const std::string& what,
          std::string* error) {
    if (error != nullptr) {
        *error = line_label + ": " + what;
    }
    return false;
}

// Parses "<domain|-> <source_ns|0>" into has_source_time/domain/source_ns.
// '-' requires source_ns == 0; a named domain requires source_ns > 0.
bool ParseSourceTime(const std::string& line_label, std::istringstream* iss,
                     bool* has_source_time, std::string* domain,
                     std::int64_t* source_ns, std::string* error) {
    std::string domain_token;
    std::int64_t source_value = 0;
    if (!(*iss >> domain_token)) {
        return Fail(line_label, "missing source-time domain", error);
    }
    if (!ParseInt64(iss, &source_value)) {
        return Fail(line_label, "missing or bad source_ns", error);
    }
    if (domain_token == "-") {
        if (source_value != 0) {
            return Fail(line_label, "domain '-' requires source_ns 0", error);
        }
        *has_source_time = false;
        domain->clear();
        *source_ns = 0;
        return true;
    }
    if (source_value <= 0) {
        return Fail(line_label,
                    "named source domain requires source_ns > 0", error);
    }
    *has_source_time = true;
    *domain = domain_token;
    *source_ns = source_value;
    return true;
}

}  // namespace

bool LoadReplayManifest(const std::string& path, ReplayManifest* out,
                        std::string* error) {
    if (out == nullptr) {
        if (error != nullptr) {
            *error = "null output manifest";
        }
        return false;
    }
    *out = ReplayManifest{};
    std::ifstream in(path);
    if (!in) {
        if (error != nullptr) {
            *error = "cannot open manifest: " + path;
        }
        return false;
    }
    std::string line;
    std::int64_t line_number = 0;
    while (std::getline(in, line)) {
        ++line_number;
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        std::istringstream iss(line);
        std::string keyword;
        if (!(iss >> keyword)) {
            continue;  // blank line
        }
        if (keyword.empty() || keyword[0] == '#') {
            continue;  // comment
        }
        const std::string label =
            "line " + std::to_string(line_number);
        if (keyword == "verify") {
            ReplayVerificationEntry entry;
            std::string kind_token;
            if (!(iss >> entry.domain) || !(iss >> kind_token) ||
                !(iss >> entry.evidence_ref)) {
                return Fail(label, "malformed verify line", error);
            }
            if (HasExtraField(&iss)) {
                return Fail(label, "too many fields on verify line", error);
            }
            if (kind_token == "device_sensor") {
                entry.kind = TimeSourceKind::kDeviceSensor;
            } else if (kind_token == "host_receive") {
                entry.kind = TimeSourceKind::kHostReceive;
            } else {
                return Fail(label, "unknown verify kind: " + kind_token,
                            error);
            }
            if (entry.domain == "-") {
                return Fail(label, "verify domain must be named", error);
            }
            out->verifications.push_back(entry);
        } else if (keyword == "frame") {
            ReplayFrameEntry entry;
            if (!ParseUint32(&iss, &entry.frame_id) ||
                !ParseInt64(&iss, &entry.receive_mono_ns) ||
                !ParseUint32(&iss, &entry.width) ||
                !ParseUint32(&iss, &entry.height) || !(iss >> entry.path)) {
                return Fail(label, "malformed frame line", error);
            }
            if (HasExtraField(&iss)) {
                return Fail(label, "too many fields on frame line", error);
            }
            if (entry.receive_mono_ns < 0) {
                return Fail(label, "frame recv_ns must be >= 0", error);
            }
            if (entry.width == 0 || entry.height == 0) {
                return Fail(label, "frame width/height must be > 0", error);
            }
            if (entry.path.empty()) {
                return Fail(label, "empty frame path", error);
            }
            out->frames.push_back(entry);
        } else if (keyword == "exposure") {
            ReplayExposureEntry entry;
            if (!ParseUint32(&iss, &entry.frame_id) ||
                !(iss >> entry.domain) ||
                !ParseInt64(&iss, &entry.source_ns)) {
                return Fail(label, "malformed exposure line", error);
            }
            if (HasExtraField(&iss)) {
                return Fail(label, "too many fields on exposure line", error);
            }
            if (entry.domain == "-") {
                return Fail(label, "exposure domain must be named", error);
            }
            if (entry.source_ns <= 0) {
                return Fail(label, "exposure source_ns must be > 0", error);
            }
            out->exposures.push_back(entry);
        } else if (keyword == "pose" || keyword == "position") {
            const bool is_pose = keyword == "pose";
            ReplayPoseEntry pose_entry;
            ReplayPositionEntry position_entry;
            std::uint64_t sample_id = 0;
            std::uint64_t seq = 0;
            std::int64_t recv_ns = 0;
            bool has_source_time = false;
            std::string domain;
            std::int64_t source_ns = 0;
            if (!ParseUint64(&iss, &sample_id) || !ParseUint64(&iss, &seq) ||
                !ParseInt64(&iss, &recv_ns)) {
                return Fail(label, "malformed " + keyword + " line", error);
            }
            if (!ParseSourceTime(label, &iss, &has_source_time, &domain,
                                 &source_ns, error)) {
                return false;  // ParseSourceTime set the specific error.
            }
            if (is_pose) {
                if (!ParseDouble(&iss, &pose_entry.value.w) ||
                    !ParseDouble(&iss, &pose_entry.value.x) ||
                    !ParseDouble(&iss, &pose_entry.value.y) ||
                    !ParseDouble(&iss, &pose_entry.value.z)) {
                    return Fail(label, "malformed quaternion", error);
                }
            } else {
                if (!ParseDouble(&iss, &position_entry.value.x) ||
                    !ParseDouble(&iss, &position_entry.value.y) ||
                    !ParseDouble(&iss, &position_entry.value.z)) {
                    return Fail(label, "malformed position vector", error);
                }
            }
            if (HasExtraField(&iss)) {
                return Fail(label, "too many fields on " + keyword + " line",
                            error);
            }
            if (recv_ns < 0) {
                return Fail(label, keyword + " recv_ns must be >= 0", error);
            }
            if (is_pose) {
                pose_entry.sample_id = sample_id;
                pose_entry.seq = seq;
                pose_entry.receive_mono_ns = recv_ns;
                pose_entry.has_source_time = has_source_time;
                pose_entry.domain = domain;
                pose_entry.source_ns = source_ns;
                out->poses.push_back(pose_entry);
            } else {
                position_entry.sample_id = sample_id;
                position_entry.seq = seq;
                position_entry.receive_mono_ns = recv_ns;
                position_entry.has_source_time = has_source_time;
                position_entry.domain = domain;
                position_entry.source_ns = source_ns;
                out->positions.push_back(position_entry);
            }
        } else {
            return Fail(label, "unknown keyword: " + keyword, error);
        }
    }
    if (in.bad()) {
        if (error != nullptr) {
            *error = "read error on manifest: " + path;
        }
        return false;
    }
    // Exposure cross-checks (after the whole file, since exposure lines may
    // precede their frame): the frame must exist and be unique per exposure.
    for (std::size_t i = 0; i < out->exposures.size(); ++i) {
        const ReplayExposureEntry& exposure = out->exposures[i];
        bool frame_found = false;
        for (std::size_t f = 0; f < out->frames.size(); ++f) {
            if (out->frames[f].frame_id == exposure.frame_id) {
                frame_found = true;
                break;
            }
        }
        if (!frame_found) {
            if (error != nullptr) {
                *error = "exposure references unknown frame " +
                         std::to_string(exposure.frame_id);
            }
            return false;
        }
        for (std::size_t j = 0; j < i; ++j) {
            if (out->exposures[j].frame_id == exposure.frame_id) {
                if (error != nullptr) {
                    *error = "duplicate exposure for frame " +
                             std::to_string(exposure.frame_id);
                }
                return false;
            }
        }
    }
    return true;
}

void ApplyManifestVerifications(const ReplayManifest& manifest,
                                ClockRegistry* clocks) {
    if (clocks == nullptr) {
        return;
    }
    for (std::size_t i = 0; i < manifest.verifications.size(); ++i) {
        const ReplayVerificationEntry& entry = manifest.verifications[i];
        clocks->Verify(entry.domain, entry.kind, entry.evidence_ref);
    }
}

}  // namespace ksi::safety
