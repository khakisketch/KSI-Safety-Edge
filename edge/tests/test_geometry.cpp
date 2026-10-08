// geometry: coordinate frames + pinhole ground projection tests.

#include "ksi_safety/geometry/coordinate.hpp"
#include "ksi_safety/geometry/projection.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>

#include "support/check.hpp"

using ksi::safety::CameraIntrinsics;
using ksi::safety::CameraPoseEnu;
using ksi::safety::EnuToNed;
using ksi::safety::GroundPlaneRevision;
using ksi::safety::GroundProjectionResult;
using ksi::safety::LocalAnchor;
using ksi::safety::NedToEnu;
using ksi::safety::PoseQuality;
using ksi::safety::ProjectPixelToGround;
using ksi::safety::ProjectionVerdict;
using ksi::safety::Quaternion;
using ksi::safety::Rotate;
using ksi::safety::Vec3;
using ksi::safety::Wgs84ToLocalEnu;

namespace {

const double kPi = 3.14159265358979323846;
const double kEarthR = 6371008.8;

bool Near(double a, double b, double tol) {
    return std::fabs(a - b) <= tol;
}

// Rotation about z by `degrees`.
Quaternion QuatZ(double degrees) {
    const double half = degrees * kPi / 360.0;
    Quaternion q;
    q.w = std::cos(half);
    q.x = 0.0;
    q.y = 0.0;
    q.z = std::sin(half);
    return q;
}

// Rotation about y by `radians`.
Quaternion QuatYRad(double radians) {
    Quaternion q;
    q.w = std::cos(radians / 2.0);
    q.x = 0.0;
    q.y = std::sin(radians / 2.0);
    q.z = 0.0;
    return q;
}

// 180 deg about x: +z -> -z (nadir camera attitude).
Quaternion QuatX180() {
    Quaternion q;
    q.w = 0.0;
    q.x = 1.0;
    q.y = 0.0;
    q.z = 0.0;
    return q;
}

CameraIntrinsics Calib(double fx, double fy, double cx, double cy,
                       std::uint32_t rw, std::uint32_t rh) {
    CameraIntrinsics c;
    c.fx = fx;
    c.fy = fy;
    c.cx = cx;
    c.cy = cy;
    c.ref_width = rw;
    c.ref_height = rh;
    return c;
}

}  // namespace

int main() {
    // ---- coordinate: rotation ----
    {
        Vec3 xhat;
        xhat.x = 1.0;
        Vec3 r = Rotate(QuatZ(90.0), xhat);
        CHECK(Near(r.x, 0.0, 1e-12) && Near(r.y, 1.0, 1e-12) &&
                  Near(r.z, 0.0, 1e-12),
              "90deg about z maps xhat to yhat");
        Vec3 yhat;
        yhat.y = 1.0;
        Vec3 r2 = Rotate(QuatZ(-90.0), xhat);
        CHECK(Near(r2.x, 0.0, 1e-12) && Near(r2.y, -1.0, 1e-12),
              "minus 90deg about z maps xhat to -yhat");
        (void)yhat;
        // Non-unit quaternion is normalized internally: 2*q90 == q90.
        Quaternion q = QuatZ(90.0);
        Quaternion scaled;
        scaled.w = 2.0 * q.w;
        scaled.x = 2.0 * q.x;
        scaled.y = 2.0 * q.y;
        scaled.z = 2.0 * q.z;
        Vec3 r3 = Rotate(scaled, xhat);
        CHECK(Near(r3.x, r.x, 1e-12) && Near(r3.y, r.y, 1e-12),
              "non-unit quaternion normalized internally");
        // Identity leaves vectors unchanged.
        Quaternion ident;
        Vec3 v;
        v.x = 1.0;
        v.y = 2.0;
        v.z = 3.0;
        Vec3 ri = Rotate(ident, v);
        CHECK(Near(ri.x, 1.0, 1e-12) && Near(ri.y, 2.0, 1e-12) &&
                  Near(ri.z, 3.0, 1e-12),
              "identity rotation is fixed point");
    }
    // ---- coordinate: NED <-> ENU roundtrip ----
    {
        Vec3 ned;
        ned.x = 1.0;
        ned.y = 2.0;
        ned.z = 3.0;  // north=1, east=2, down=3
        Vec3 enu = NedToEnu(ned);
        CHECK(Near(enu.x, 2.0, 1e-12) && Near(enu.y, 1.0, 1e-12) &&
                  Near(enu.z, -3.0, 1e-12),
              "NED->ENU maps (n,e,d) to (e,n,-d)");
        Vec3 back = EnuToNed(enu);
        CHECK(Near(back.x, ned.x, 1e-12) && Near(back.y, ned.y, 1e-12) &&
                  Near(back.z, ned.z, 1e-12),
              "ENU->NED roundtrips");
    }
    // ---- coordinate: WGS84 local ENU ----
    {
        LocalAnchor anchor;
        anchor.lat_rad = 37.0 * kPi / 180.0;
        anchor.lon_rad = 127.0 * kPi / 180.0;
        anchor.alt_m = 100.0;
        // 100 m north.
        Vec3 n = Wgs84ToLocalEnu(anchor, anchor.lat_rad + 100.0 / kEarthR,
                                 anchor.lon_rad, anchor.alt_m);
        CHECK(Near(n.x, 0.0, 0.5) && Near(n.y, 100.0, 0.5) &&
                  Near(n.z, 0.0, 0.5),
              "100m north within 0.5m");
        // 100 m east.
        const double dlon =
            100.0 / (kEarthR * std::cos(anchor.lat_rad));
        Vec3 e = Wgs84ToLocalEnu(anchor, anchor.lat_rad,
                                 anchor.lon_rad + dlon, anchor.alt_m + 5.0);
        CHECK(Near(e.x, 100.0, 0.5) && Near(e.y, 0.0, 0.5) &&
                  Near(e.z, 5.0, 0.5),
              "100m east + 5m up within 0.5m");
        // Anchor itself maps to origin.
        Vec3 o = Wgs84ToLocalEnu(anchor, anchor.lat_rad, anchor.lon_rad,
                                 anchor.alt_m);
        CHECK(Near(o.x, 0.0, 1e-9) && Near(o.y, 0.0, 1e-9) &&
                  Near(o.z, 0.0, 1e-9),
              "anchor maps to origin");
    }
    // ---- projection: nadir analytic GT ----
    // fx=fy=1000, cx=cy=500, 1000x1000, camera at (0,0,50) looking straight
    // down. Pixel (600,500): ray (0.1,0,-1) -> ground (5,0,0).
    {
        CameraPoseEnu pose;
        pose.position_enu.x = 0.0;
        pose.position_enu.y = 0.0;
        pose.position_enu.z = 50.0;
        pose.camera_to_enu = QuatX180();
        GroundPlaneRevision plane;
        plane.revision_id = "g1";
        plane.up_m = 0.0;
        GroundProjectionResult r = ProjectPixelToGround(
            600.0, 500.0, 1000, 1000, Calib(1000, 1000, 500, 500, 1000, 1000),
            pose, plane, PoseQuality::kSourceVerified, 0.05);
        CHECK(r.verdict == ProjectionVerdict::kProjected, "nadir projected");
        CHECK(Near(r.enu.x, 5.0, 1e-9) && Near(r.enu.y, 0.0, 1e-9) &&
                  Near(r.enu.z, 0.0, 1e-9),
              "nadir (600,500) -> (5,0,0)");
        // Centre pixel -> directly below.
        GroundProjectionResult c = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, Calib(1000, 1000, 500, 500, 1000, 1000),
            pose, plane, PoseQuality::kSourceVerified, 0.05);
        CHECK(c.verdict == ProjectionVerdict::kProjected &&
                  Near(c.enu.x, 0.0, 1e-9) && Near(c.enu.y, 0.0, 1e-9),
              "nadir centre -> origin");
    }
    // ---- projection: oblique 45-degree pitch analytic GT ----
    // Camera at (0,0,50), optical axis pitched 45 deg toward +x (rotation of
    // 135 deg about Y maps +z to (sin135,0,cos135)). Centre pixel ray hits
    // (50,0,0).
    {
        CameraPoseEnu pose;
        pose.position_enu.z = 50.0;
        pose.camera_to_enu = QuatYRad(3.0 * kPi / 4.0);
        GroundPlaneRevision plane;
        plane.revision_id = "g1";
        GroundProjectionResult r = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, Calib(1000, 1000, 500, 500, 1000, 1000),
            pose, plane, PoseQuality::kSourceVerified, 0.05);
        CHECK(r.verdict == ProjectionVerdict::kProjected, "oblique projected");
        CHECK(Near(r.enu.x, 50.0, 1e-9) && Near(r.enu.y, 0.0, 1e-9) &&
                  Near(r.enu.z, 0.0, 1e-9),
              "oblique centre -> (50,0,0)");
    }
    // ---- projection: refusals ----
    {
        CameraPoseEnu pose;
        pose.position_enu.z = 50.0;
        pose.camera_to_enu = QuatX180();
        GroundPlaneRevision plane;
        plane.revision_id = "g1";
        CameraIntrinsics good = Calib(1000, 1000, 500, 500, 1000, 1000);
        // Pixel outside frame.
        GroundProjectionResult bad = ProjectPixelToGround(
            1000.0, 500.0, 1000, 1000, good, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(bad.verdict == ProjectionVerdict::kBadPixel, "u==width bad");
        GroundProjectionResult bad2 = ProjectPixelToGround(
            500.0, -1.0, 1000, 1000, good, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(bad2.verdict == ProjectionVerdict::kBadPixel, "v<0 bad");
        // Unverified pose grades.
        GroundProjectionResult unv = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, pose, plane,
            PoseQuality::kReceiveNearest, 0.05);
        CHECK(unv.verdict == ProjectionVerdict::kPoseUnverified,
              "receive-nearest pose unverified");
        GroundProjectionResult none = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, pose, plane, PoseQuality::kNone,
            0.05);
        CHECK(none.verdict == ProjectionVerdict::kPoseUnverified,
              "no pose unverified");
        // Gate precedence: an unverified pose must refuse even when the
        // calibration is also invalid (pose gate is checked first).
        CameraIntrinsics zero_fx_prec = Calib(0, 1000, 500, 500, 1000, 1000);
        GroundProjectionResult prec = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, zero_fx_prec, pose, plane,
            PoseQuality::kReceiveNearest, 0.05);
        CHECK(prec.verdict == ProjectionVerdict::kPoseUnverified,
              "pose gate precedes calibration check");
        // Bad calibration.
        CameraIntrinsics zero_fx = Calib(0, 1000, 500, 500, 1000, 1000);
        GroundProjectionResult bc = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, zero_fx, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(bc.verdict == ProjectionVerdict::kBadCalibration, "fx=0 bad");
        CameraIntrinsics zero_ref = Calib(1000, 1000, 500, 500, 0, 1000);
        GroundProjectionResult br = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, zero_ref, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(br.verdict == ProjectionVerdict::kBadCalibration,
              "ref width 0 bad");
        // Upward camera: ray points away from the ground.
        CameraPoseEnu up;
        up.position_enu.z = 50.0;
        up.camera_to_enu = Quaternion();  // identity: +z stays +z (up)
        GroundProjectionResult nog = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, up, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(nog.verdict == ProjectionVerdict::kNoGroundIntersection,
              "upward camera no intersection");
        // Horizontal ray inside the min_ray_z band.
        CameraPoseEnu horiz;
        horiz.position_enu.z = 50.0;
        horiz.camera_to_enu = QuatYRad(kPi / 2.0);  // +z -> +x, ray.z = 0
        GroundProjectionResult hor = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, horiz, plane,
            PoseQuality::kSourceVerified, 0.1);
        CHECK(hor.verdict == ProjectionVerdict::kNearHorizon,
              "horizontal ray near horizon");
        // min_ray_z boundary: ray.z = -0.05 (down toward the plane) with a
        // 0.1 band -> horizon; with a 0.05 band the same ray must NOT be
        // horizon (0.05 < 0.05 is false) and must project.
        CameraPoseEnu graze;
        graze.position_enu.z = 50.0;
        graze.camera_to_enu = QuatYRad(kPi - std::acos(0.05));
        GroundProjectionResult g1 = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, graze, plane,
            PoseQuality::kSourceVerified, 0.1);
        CHECK(g1.verdict == ProjectionVerdict::kNearHorizon,
              "grazing ray inside band");
        GroundProjectionResult g2 = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, good, graze, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(g2.verdict == ProjectionVerdict::kProjected,
              "ray exactly on band edge projects");
        // Resolution scaling: calibration at 500x500 used on a 1000x1000
        // frame scales fx/fy/cx/cy by 2; pixel (1200,1000) is out of the
        // calibration frame but valid here -> ray (0.1,0,-1) -> (5,0,0).
        CameraIntrinsics half = Calib(500, 500, 250, 250, 500, 500);
        GroundProjectionResult sc = ProjectPixelToGround(
            600.0, 500.0, 1000, 1000, half, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(sc.verdict == ProjectionVerdict::kProjected &&
                  Near(sc.enu.x, 5.0, 1e-9),
              "intrinsics scale with frame/ref");
        // ToString covers every verdict.
        CHECK(std::string(ksi::safety::ToString(
                  ProjectionVerdict::kNoGroundIntersection)) ==
                  "NO_GROUND_INTERSECTION",
              "verdict string");
    }
    if (edge_test::failures == 0) {
        std::printf("geometry: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
