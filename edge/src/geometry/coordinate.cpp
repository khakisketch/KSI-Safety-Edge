#include "ksi_safety/geometry/coordinate.hpp"

#include <cmath>

namespace ksi::safety {

namespace {

const double kEarthRadiusM = 6371008.8;
const double kNormTol = 1e-12;

double QuatNorm(const Quaternion& q) {
    return std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
}

}  // namespace

Vec3 Rotate(const Quaternion& q, const Vec3& v) {
    const double n = QuatNorm(q);
    if (n == 0.0) {
        return v;  // degenerate input: fail-safe no-op
    }
    double w = q.w;
    double x = q.x;
    double y = q.y;
    double z = q.z;
    if (std::fabs(n - 1.0) > kNormTol) {
        w /= n;
        x /= n;
        y /= n;
        z /= n;
    }
    // v' = q v q* with v as a pure quaternion; expanded form:
    // t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t).
    const double tx = 2.0 * (y * v.z - z * v.y);
    const double ty = 2.0 * (z * v.x - x * v.z);
    const double tz = 2.0 * (x * v.y - y * v.x);
    Vec3 out;
    out.x = v.x + w * tx + (y * tz - z * ty);
    out.y = v.y + w * ty + (z * tx - x * tz);
    out.z = v.z + w * tz + (x * ty - y * tx);
    return out;
}

Vec3 NedToEnu(const Vec3& ned) {
    Vec3 enu;
    enu.x = ned.y;
    enu.y = ned.x;
    enu.z = -ned.z;
    return enu;
}

Vec3 EnuToNed(const Vec3& enu) {
    Vec3 ned;
    ned.x = enu.y;
    ned.y = enu.x;
    ned.z = -enu.z;
    return ned;
}

Vec3 Wgs84ToLocalEnu(const LocalAnchor& anchor, double lat_rad, double lon_rad,
                      double alt_m) {
    const double dlat = lat_rad - anchor.lat_rad;
    const double dlon = lon_rad - anchor.lon_rad;
    const double mean_lat = 0.5 * (lat_rad + anchor.lat_rad);
    Vec3 enu;
    enu.x = kEarthRadiusM * std::cos(mean_lat) * dlon;  // east
    enu.y = kEarthRadiusM * dlat;                       // north
    enu.z = alt_m - anchor.alt_m;                       // up
    return enu;
}

}  // namespace ksi::safety
