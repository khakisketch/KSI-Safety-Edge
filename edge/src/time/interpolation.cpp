#include "ksi_safety/time/interpolation.hpp"

#include <cmath>

namespace ksi::safety {

namespace {

double Norm(const Quaternion& q) {
    return std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
}

Quaternion Normalized(const Quaternion& q) {
    const double n = Norm(q);
    if (n == 0.0) {
        return Quaternion{};  // identity; degenerate input, fail-safe
    }
    Quaternion out;
    out.w = q.w / n;
    out.x = q.x / n;
    out.y = q.y / n;
    out.z = q.z / n;
    return out;
}

}  // namespace

Quaternion Slerp(const Quaternion& a, const Quaternion& b, double t) {
    Quaternion an = Normalized(a);
    Quaternion bn = Normalized(b);
    double dot = an.w * bn.w + an.x * bn.x + an.y * bn.y + an.z * bn.z;
    // Shortest path: negate one input when the dot product is negative.
    if (dot < 0.0) {
        bn.w = -bn.w;
        bn.x = -bn.x;
        bn.y = -bn.y;
        bn.z = -bn.z;
        dot = -dot;
    }
    // Identical (or near-identical) inputs: linear blend + normalize is exact
    // and avoids dividing by sin(theta) ~ 0.
    if (dot > 0.9995) {
        Quaternion out;
        out.w = an.w + (bn.w - an.w) * t;
        out.x = an.x + (bn.x - an.x) * t;
        out.y = an.y + (bn.y - an.y) * t;
        out.z = an.z + (bn.z - an.z) * t;
        return Normalized(out);
    }
    const double theta = std::acos(dot);
    const double sin_theta = std::sin(theta);
    const double wa = std::sin((1.0 - t) * theta) / sin_theta;
    const double wb = std::sin(t * theta) / sin_theta;
    Quaternion out;
    out.w = wa * an.w + wb * bn.w;
    out.x = wa * an.x + wb * bn.x;
    out.y = wa * an.y + wb * bn.y;
    out.z = wa * an.z + wb * bn.z;
    return Normalized(out);
}

Vec3 Lerp(const Vec3& a, const Vec3& b, double t) {
    Vec3 out;
    out.x = a.x + (b.x - a.x) * t;
    out.y = a.y + (b.y - a.y) * t;
    out.z = a.z + (b.z - a.z) * t;
    return out;
}

double WrapDegrees(double degrees) {
    double r = std::fmod(degrees + 180.0, 360.0);
    if (r <= 0.0) {
        r += 360.0;
    }
    return r - 180.0;  // (-180, 180]; -180 maps to 180
}

const char* ToString(InterpolationVerdict verdict) {
    switch (verdict) {
        case InterpolationVerdict::kInterpolated:
            return "INTERPOLATED";
        case InterpolationVerdict::kRefusedUnverified:
            return "REFUSED_UNVERIFIED";
        case InterpolationVerdict::kRefusedNoBracket:
            return "REFUSED_NO_BRACKET";
        case InterpolationVerdict::kRefusedIntervalTooWide:
            return "REFUSED_INTERVAL_TOO_WIDE";
        case InterpolationVerdict::kRefusedTargetOutside:
            return "REFUSED_TARGET_OUTSIDE";
    }
    return "UNKNOWN";
}

InterpolationVerdict InterpolatePose(const Sample<PoseValue>& before,
                                     const Sample<PoseValue>& after,
                                     std::int64_t target_source_ns,
                                     std::int64_t max_interval_ns,
                                     PoseValue* out) {
    if (out == nullptr) {
        return InterpolationVerdict::kRefusedNoBracket;
    }
    // Both sides must carry verified source times in the SAME domain.
    if (!before.has_source_time || !after.has_source_time ||
        !before.source_time.verified || !after.source_time.verified ||
        before.source_time.domain != after.source_time.domain) {
        return InterpolationVerdict::kRefusedUnverified;
    }
    const std::int64_t t0 = before.source_time.value_ns;
    const std::int64_t t1 = after.source_time.value_ns;
    if (!(t0 < t1)) {
        return InterpolationVerdict::kRefusedNoBracket;
    }
    if (t1 - t0 > max_interval_ns) {
        return InterpolationVerdict::kRefusedIntervalTooWide;
    }
    if (target_source_ns < t0 || target_source_ns > t1) {
        return InterpolationVerdict::kRefusedTargetOutside;
    }
    const double t =
        static_cast<double>(target_source_ns - t0) / static_cast<double>(t1 - t0);
    out->q = Slerp(before.value.q, after.value.q, t);
    out->position = Lerp(before.value.position, after.value.position, t);
    return InterpolationVerdict::kInterpolated;
}

}  // namespace ksi::safety
