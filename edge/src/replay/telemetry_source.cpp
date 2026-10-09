#include "ksi_safety/replay/telemetry_source.hpp"

#include <cstddef>
#include <cstdint>
#include <utility>

namespace ksi::safety {

ReplayTelemetrySource::ReplayTelemetrySource(const ReplayManifest& manifest,
                                             std::string receive_domain,
                                             const ClockRegistry& clocks)
    : manifest_(manifest),
      receive_domain_(std::move(receive_domain)),
      clocks_(clocks) {}

ReplayTelemetrySource::PushResult ReplayTelemetrySource::PushDue(
    std::int64_t now_ns, SessionRunner& runner) {
    PushResult result;
    while (pose_index_ < manifest_.poses.size() &&
           manifest_.poses[pose_index_].receive_mono_ns <= now_ns) {
        const ReplayPoseEntry& entry = manifest_.poses[pose_index_];
        Sample<Quaternion> sample;
        sample.sample_id = entry.sample_id;
        sample.seq = entry.seq;
        sample.received = clocks_.Make(
            receive_domain_, TimeSourceKind::kHostReceive,
            entry.receive_mono_ns);
        if (entry.has_source_time) {
            sample.has_source_time = true;
            sample.source_time = clocks_.Make(
                entry.domain, TimeSourceKind::kDeviceSensor, entry.source_ns);
        }
        sample.value = entry.value;
        const PushVerdict verdict = runner.PushPose(sample);
        if (verdict == PushVerdict::kRecorded) {
            ++result.poses_pushed;
        } else if (verdict == PushVerdict::kDuplicate) {
            ++result.duplicates;
        } else {
            ++result.rollbacks;
        }
        ++pose_index_;
    }
    while (position_index_ < manifest_.positions.size() &&
           manifest_.positions[position_index_].receive_mono_ns <= now_ns) {
        const ReplayPositionEntry& entry = manifest_.positions[position_index_];
        Sample<Vec3> sample;
        sample.sample_id = entry.sample_id;
        sample.seq = entry.seq;
        sample.received = clocks_.Make(
            receive_domain_, TimeSourceKind::kHostReceive,
            entry.receive_mono_ns);
        if (entry.has_source_time) {
            sample.has_source_time = true;
            sample.source_time = clocks_.Make(
                entry.domain, TimeSourceKind::kDeviceSensor, entry.source_ns);
        }
        sample.value = entry.value;
        const PushVerdict verdict = runner.PushPosition(sample);
        if (verdict == PushVerdict::kRecorded) {
            ++result.positions_pushed;
        } else if (verdict == PushVerdict::kDuplicate) {
            ++result.duplicates;
        } else {
            ++result.rollbacks;
        }
        ++position_index_;
    }
    return result;
}

std::size_t ReplayTelemetrySource::pending_count() const {
    return (manifest_.poses.size() - pose_index_) +
           (manifest_.positions.size() - position_index_);
}

}  // namespace ksi::safety
