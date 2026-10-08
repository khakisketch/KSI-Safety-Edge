// Pinhole pixel -> ground-plane projection in ENU (clean-room kernel).
//
// Input pose is GIVEN in ENU (CameraPoseEnu): composition of raw telemetry
// topics into this pose is the PSDK adapter's job and is deferred. Only a
// kSourceVerified pose is accepted (see ProjectPixelToGround rules); anything
// else is kPoseUnverified per the workstream principles (receive-time
// proximity is not capture-time alignment).
//
// No lens distortion, single flat ground plane (up_m), no multi-level
// terrain, no uncertainty-interval calibration.

#ifndef KSI_SAFETY_GEOMETRY_PROJECTION_HPP
#define KSI_SAFETY_GEOMETRY_PROJECTION_HPP

#include <cstdint>
#include <string>

#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"

namespace ksi::safety {

struct CameraIntrinsics {
    double fx = 0.0;
    double fy = 0.0;
    double cx = 0.0;
    double cy = 0.0;
    std::uint32_t ref_width = 0;
    std::uint32_t ref_height = 0;
};

struct CameraPoseEnu {
    Vec3 position_enu;
    Quaternion camera_to_enu;
};

struct GroundPlaneRevision {
    std::string revision_id;
    double up_m = 0.0;
};

enum class ProjectionVerdict {
    kProjected,
    kNoPose,
    kPoseUnverified,
    kBadCalibration,
    kBadPixel,
    kNearHorizon,
    kNoGroundIntersection,
};

const char* ToString(ProjectionVerdict verdict);

struct GroundProjectionResult {
    ProjectionVerdict verdict = ProjectionVerdict::kNoPose;
    Vec3 enu;
};

// Rules (checked in this order):
//  1. pose_quality != kSourceVerified -> kPoseUnverified.
//  2. intrinsics non-finite/non-positive fx,fy,cx,cy or ref dims 0 ->
//     kBadCalibration.
//  3. u,v outside [0,frame_w) x [0,frame_h) -> kBadPixel.
//  4. effective intrinsics scaled by frame/ref; camera ray
//     ((u-cx')/fx',(v-cy')/fy',1) normalized, rotated to ENU.
//  5. |ray.z| < min_ray_z -> kNearHorizon.
//  6. lambda = (plane.up_m - pos.z)/ray.z; lambda <= 0 or non-finite ->
//     kNoGroundIntersection; else kProjected at pos + lambda*ray.
GroundProjectionResult ProjectPixelToGround(
    double u, double v, std::uint32_t frame_width, std::uint32_t frame_height,
    const CameraIntrinsics& intrinsics, const CameraPoseEnu& pose,
    const GroundPlaneRevision& plane, PoseQuality pose_quality,
    double min_ray_z);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_GEOMETRY_PROJECTION_HPP
