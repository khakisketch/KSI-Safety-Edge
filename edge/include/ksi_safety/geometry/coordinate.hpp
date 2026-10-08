// Geographic frame conversions (clean-room kernels, C++17).
//
// Frames:
//   NED: x=north, y=east, z=down.  ENU: x=east, y=north, z=up.
//   NedToEnu / EnuToNed both map (x,y,z) -> (y,x,-z) (an involution).
//
// Rotate(q, v) applies the quaternion rotation v' = q v q* to v. The input
// quaternion is normalized internally when its norm differs from 1 (zero
// quaternion is a fail-safe no-op returning v unchanged).
//
// Wgs84ToLocalEnu() is an equirectangular LOCAL approximation around
// `anchor`: with R = 6371008.8 m (mean Earth radius),
//   north = R * dlat, east = R * cos(mean_lat) * dlon, up = dalt.
// Error grows with offset (curvature neglected ~ d^2/2R vertically, and the
// cos(mean_lat) scale is exact only at the mean latitude); it is intended
// for offsets <= ~1 km (sub-metre error there) near the anchor. For larger
// areas use a proper projection (deferred).

#ifndef KSI_SAFETY_GEOMETRY_COORDINATE_HPP
#define KSI_SAFETY_GEOMETRY_COORDINATE_HPP

#include "ksi_safety/time/interpolation.hpp"

namespace ksi::safety {

Vec3 Rotate(const Quaternion& q, const Vec3& v);
Vec3 NedToEnu(const Vec3& ned);
Vec3 EnuToNed(const Vec3& enu);

struct LocalAnchor {
    double lat_rad = 0.0;
    double lon_rad = 0.0;
    double alt_m = 0.0;
};

Vec3 Wgs84ToLocalEnu(const LocalAnchor& anchor, double lat_rad, double lon_rad,
                      double alt_m);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_GEOMETRY_COORDINATE_HPP
