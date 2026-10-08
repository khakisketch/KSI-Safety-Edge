// Attitude interpolation over a VERIFIED source-time bracket.
//
// InterpolatePose() refuses (fail-closed) unless BOTH samples carry verified
// source times in the SAME domain, before.source_time < after.source_time,
// the bracket interval is within max_interval_ns, and the target lies inside
// [before, after]. Only then is t computed and the pose interpolated.
// Domain mismatch surfaces as kRefusedUnverified: a cross-domain bracket is
// not a verified bracket.

#ifndef KSI_SAFETY_TIME_INTERPOLATION_HPP
#define KSI_SAFETY_TIME_INTERPOLATION_HPP

#include <cstdint>

#include "ksi_safety/time/topic_ring.hpp"

namespace ksi::safety {

struct Quaternion {
    double w = 1.0;
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

struct Vec3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

struct PoseValue {
    Quaternion q;
    Vec3 position;
};

// Shortest-path SLERP of (assumed unit) quaternions; handles negative dot by
// negating the second input; identical inputs return the (normalized) input.
Quaternion Slerp(const Quaternion& a, const Quaternion& b, double t);
Vec3 Lerp(const Vec3& a, const Vec3& b, double t);
// Wrap a degree value into (-180, 180].
double WrapDegrees(double degrees);

enum class InterpolationVerdict {
    kInterpolated,
    kRefusedUnverified,
    kRefusedNoBracket,
    kRefusedIntervalTooWide,
    kRefusedTargetOutside,
};

const char* ToString(InterpolationVerdict verdict);

InterpolationVerdict InterpolatePose(const Sample<PoseValue>& before,
                                     const Sample<PoseValue>& after,
                                     std::int64_t target_source_ns,
                                     std::int64_t max_interval_ns,
                                     PoseValue* out);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TIME_INTERPOLATION_HPP
