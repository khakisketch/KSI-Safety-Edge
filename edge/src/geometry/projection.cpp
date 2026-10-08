#include "ksi_safety/geometry/projection.hpp"

#include <cmath>

#include "ksi_safety/geometry/coordinate.hpp"

namespace ksi::safety {

const char* ToString(ProjectionVerdict verdict) {
    switch (verdict) {
        case ProjectionVerdict::kProjected:
            return "PROJECTED";
        case ProjectionVerdict::kNoPose:
            return "NO_POSE";
        case ProjectionVerdict::kPoseUnverified:
            return "POSE_UNVERIFIED";
        case ProjectionVerdict::kBadCalibration:
            return "BAD_CALIBRATION";
        case ProjectionVerdict::kBadPixel:
            return "BAD_PIXEL";
        case ProjectionVerdict::kNearHorizon:
            return "NEAR_HORIZON";
        case ProjectionVerdict::kNoGroundIntersection:
            return "NO_GROUND_INTERSECTION";
    }
    return "UNKNOWN";
}

GroundProjectionResult ProjectPixelToGround(
    double u, double v, std::uint32_t frame_width, std::uint32_t frame_height,
    const CameraIntrinsics& intrinsics, const CameraPoseEnu& pose,
    const GroundPlaneRevision& plane, PoseQuality pose_quality,
    double min_ray_z) {
    GroundProjectionResult out;
    // 1. Only a source-verified pose may produce ground coordinates.
    if (pose_quality != PoseQuality::kSourceVerified) {
        out.verdict = ProjectionVerdict::kPoseUnverified;
        return out;
    }
    // 2. Calibration sanity (checked before any scaling divides by ref dims).
    if (!std::isfinite(intrinsics.fx) || !std::isfinite(intrinsics.fy) ||
        !std::isfinite(intrinsics.cx) || !std::isfinite(intrinsics.cy) ||
        !(intrinsics.fx > 0.0) || !(intrinsics.fy > 0.0) ||
        !(intrinsics.cx >= 0.0) || !(intrinsics.cy >= 0.0) ||
        intrinsics.ref_width == 0 || intrinsics.ref_height == 0) {
        out.verdict = ProjectionVerdict::kBadCalibration;
        return out;
    }
    // 3. Pixel must lie inside the frame.
    const double fw = static_cast<double>(frame_width);
    const double fh = static_cast<double>(frame_height);
    if (!(u >= 0.0) || !(v >= 0.0) || !(u < fw) || !(v < fh)) {
        out.verdict = ProjectionVerdict::kBadPixel;
        return out;
    }
    // 4. Scale intrinsics from calibration resolution to frame resolution,
    // then build the normalized camera ray (optical axis = +z).
    const double sx = fw / static_cast<double>(intrinsics.ref_width);
    const double sy = fh / static_cast<double>(intrinsics.ref_height);
    const double fx = intrinsics.fx * sx;
    const double fy = intrinsics.fy * sy;
    const double cx = intrinsics.cx * sx;
    const double cy = intrinsics.cy * sy;
    Vec3 cam;
    cam.x = (u - cx) / fx;
    cam.y = (v - cy) / fy;
    cam.z = 1.0;
    const double cn =
        std::sqrt(cam.x * cam.x + cam.y * cam.y + cam.z * cam.z);
    cam.x /= cn;
    cam.y /= cn;
    cam.z /= cn;
    const Vec3 ray = Rotate(pose.camera_to_enu, cam);
    // 5. Grazing rays are numerically unstable: refuse near the horizon.
    if (!(std::fabs(ray.z) >= min_ray_z)) {
        out.verdict = ProjectionVerdict::kNearHorizon;
        return out;
    }
    // 6. Intersect the flat ground plane z = up_m.
    const double lambda = (plane.up_m - pose.position_enu.z) / ray.z;
    if (!(lambda > 0.0) || !std::isfinite(lambda)) {
        out.verdict = ProjectionVerdict::kNoGroundIntersection;
        return out;
    }
    out.verdict = ProjectionVerdict::kProjected;
    out.enu.x = pose.position_enu.x + lambda * ray.x;
    out.enu.y = pose.position_enu.y + lambda * ray.y;
    out.enu.z = pose.position_enu.z + lambda * ray.z;
    return out;
}

}  // namespace ksi::safety
