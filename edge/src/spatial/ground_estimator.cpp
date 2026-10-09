#include "ksi_safety/spatial/ground_estimator.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ksi::safety {
namespace {

const std::string* LookupEquipmentClass(const SpatialClassMap& classes,
                                        std::uint32_t class_id) {
    for (std::size_t i = 0; i < classes.equipment_classes.size(); ++i) {
        if (classes.equipment_classes[i].first == class_id) {
            return &classes.equipment_classes[i].second;
        }
    }
    return nullptr;
}

}  // namespace

const char* ToString(SpatialWithholdReason reason) {
    switch (reason) {
        case SpatialWithholdReason::kOk:
            return "OK";
        case SpatialWithholdReason::kUnmappedClass:
            return "UNMAPPED_CLASS";
        case SpatialWithholdReason::kProjectionRefused:
            return "PROJECTION_REFUSED";
    }
    return "UNKNOWN";
}

GroundEstimator::GroundEstimator(const SpatialEstimatorConfig& config)
    : config_(config) {}

SpatialEstimate GroundEstimator::Estimate(
    const std::vector<Track>& tracks, const MatchResult<Quaternion>& attitude,
    const MatchResult<Vec3>& position, const FrameContext& ctx,
    std::uint32_t frame_width, std::uint32_t frame_height) const {
    SpatialEstimate estimate;
    // Eligibility: both brackets source-verified + a verified exposure.
    if (attitude.quality != PoseQuality::kSourceVerified ||
        position.quality != PoseQuality::kSourceVerified ||
        !ctx.has_exposure || !ctx.exposure.verified) {
        return estimate;  // eligible=false, no records
    }
    // Interpolate the pose at the exposure source time over the verified
    // bracket (MatchFrame guarantees before/after are non-null here).
    const std::int64_t interval_ns =
        attitude.after->source_time.value_ns -
        attitude.before->source_time.value_ns;
    double t = 0.0;
    if (interval_ns > 0) {
        t = static_cast<double>(ctx.exposure.value_ns -
                                attitude.before->source_time.value_ns) /
            static_cast<double>(interval_ns);
    }
    CameraPoseEnu pose;
    pose.camera_to_enu =
        Slerp(attitude.before->value, attitude.after->value, t);
    pose.position_enu =
        Lerp(position.before->value, position.after->value, t);
    estimate.eligible = true;

    for (std::size_t i = 0; i < tracks.size(); ++i) {
        const Track& track = tracks[i];
        SpatialObservationRecord record;
        record.track_id = track.track_id;
        record.class_id = track.class_id;
        if (track.class_id == config_.classes.person_class_id) {
            const double u =
                (static_cast<double>(track.box.x_min) +
                 static_cast<double>(track.box.x_max)) *
                0.5;
            const double v = static_cast<double>(track.box.y_max);
            const GroundProjectionResult projection = ProjectPixelToGround(
                u, v, frame_width, frame_height, config_.intrinsics, pose,
                config_.ground, PoseQuality::kSourceVerified,
                config_.min_ray_z);
            if (projection.verdict != ProjectionVerdict::kProjected) {
                record.reason = SpatialWithholdReason::kProjectionRefused;
            } else {
                record.has_observation = true;
                record.reason = SpatialWithholdReason::kOk;
                record.observation.track_id = track.track_id;
                record.observation.kind = GroundTrackKind::kPerson;
                record.observation.geometry_level = GeometryLevel::kGeoEstimate;
                record.observation.person_footpoint.x = projection.enu.x;
                record.observation.person_footpoint.y = projection.enu.y;
            }
        } else {
            const std::string* class_name =
                LookupEquipmentClass(config_.classes, track.class_id);
            if (class_name == nullptr) {
                record.reason = SpatialWithholdReason::kUnmappedClass;
            } else {
                // Bottom edge -> ground contact line (an estimate).
                const double us[3] = {
                    static_cast<double>(track.box.x_min),
                    (static_cast<double>(track.box.x_min) +
                     static_cast<double>(track.box.x_max)) *
                        0.5,
                    static_cast<double>(track.box.x_max)};
                const double v = static_cast<double>(track.box.y_max);
                Polygon2 footprint;
                bool projected = true;
                for (int k = 0; k < 3 && projected; ++k) {
                    const GroundProjectionResult projection =
                        ProjectPixelToGround(
                            us[k], v, frame_width, frame_height,
                            config_.intrinsics, pose, config_.ground,
                            PoseQuality::kSourceVerified, config_.min_ray_z);
                    if (projection.verdict != ProjectionVerdict::kProjected) {
                        projected = false;
                        break;
                    }
                    Vec2 point;
                    point.x = projection.enu.x;
                    point.y = projection.enu.y;
                    footprint.push_back(point);
                }
                if (!projected || footprint.size() < 3U) {
                    record.reason = SpatialWithholdReason::kProjectionRefused;
                } else {
                    record.has_observation = true;
                    record.reason = SpatialWithholdReason::kOk;
                    record.observation.track_id = track.track_id;
                    record.observation.kind = GroundTrackKind::kEquipment;
                    record.observation.geometry_level =
                        GeometryLevel::kGeoEstimate;
                    record.observation.equipment_footprint = footprint;
                    record.observation.equipment_class = *class_name;
                }
            }
        }
        estimate.records.push_back(record);
    }
    return estimate;
}

}  // namespace ksi::safety
