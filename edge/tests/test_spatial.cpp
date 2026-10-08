// spatial: 2D distances, person/equipment relations, zone relations,
// projection-to-distance integration GT.

#include "ksi_safety/geometry/coordinate.hpp"
#include "ksi_safety/geometry/projection.hpp"
#include "ksi_safety/spatial/distance.hpp"
#include "ksi_safety/spatial/relation.hpp"

#include <cmath>
#include <cstdio>
#include <string>

#include "support/check.hpp"

using ksi::safety::CameraIntrinsics;
using ksi::safety::CameraPoseEnu;
using ksi::safety::DistancePointToPolygon;
using ksi::safety::DistancePointToSegment;
using ksi::safety::DistancePolygonToPolygon;
using ksi::safety::DistancePolygonToSegment;
using ksi::safety::EquipmentGround;
using ksi::safety::GroundPlaneRevision;
using ksi::safety::GroundProjectionResult;
using ksi::safety::GroundStatus;
using ksi::safety::MinDistancePersonEquipment;
using ksi::safety::PairReason;
using ksi::safety::PairRelation;
using ksi::safety::PersonGround;
using ksi::safety::Polygon2;
using ksi::safety::PolygonContains;
using ksi::safety::PoseQuality;
using ksi::safety::ProjectPixelToGround;
using ksi::safety::ProjectionVerdict;
using ksi::safety::Quaternion;
using ksi::safety::RelatePointToZone;
using ksi::safety::RelatePolygonToZone;
using ksi::safety::Vec2;
using ksi::safety::ZoneReason;
using ksi::safety::ZoneRelationKind;
using ksi::safety::ZoneRelationResult;
using ksi::safety::ZoneRevision;

namespace {

const double kPi = 3.14159265358979323846;

bool Near(double a, double b, double tol) {
    return std::fabs(a - b) <= tol;
}

Vec2 P(double x, double y) {
    Vec2 v;
    v.x = x;
    v.y = y;
    return v;
}

Polygon2 Square(double x0, double y0, double x1, double y1) {
    Polygon2 s;
    s.push_back(P(x0, y0));
    s.push_back(P(x1, y0));
    s.push_back(P(x1, y1));
    s.push_back(P(x0, y1));
    return s;
}

PersonGround ObservedPerson(double x, double y) {
    PersonGround p;
    p.status = GroundStatus::kObserved;
    p.footpoint = P(x, y);
    return p;
}

EquipmentGround ObservedEquipment(const Polygon2& poly) {
    EquipmentGround e;
    e.status = GroundStatus::kObserved;
    e.footprint = poly;
    return e;
}

ZoneRevision Zone(const std::string& id, const Polygon2& poly) {
    ZoneRevision z;
    z.revision_id = id;
    z.polygons.push_back(poly);
    return z;
}

Quaternion QuatYRad(double radians) {
    Quaternion q;
    q.w = std::cos(radians / 2.0);
    q.x = 0.0;
    q.y = std::sin(radians / 2.0);
    q.z = 0.0;
    return q;
}

Quaternion QuatX180() {
    Quaternion q;
    q.w = 0.0;
    q.x = 1.0;
    q.y = 0.0;
    q.z = 0.0;
    return q;
}

}  // namespace

int main() {
    // ---- distance kernels ----
    {
        Polygon2 sq = Square(10.0, 10.0, 20.0, 20.0);
        // Perpendicular to the x=10 edge: distance 10.
        CHECK(Near(DistancePointToPolygon(P(0.0, 15.0), sq), 10.0, 1e-9),
              "point to square edge = 10");
        // Diagonal to the corner (10,10): sqrt(200).
        CHECK(Near(DistancePointToPolygon(P(0.0, 0.0), sq), std::sqrt(200.0),
                   1e-9),
              "point to square corner = sqrt(200)");
        // Inside -> 0.
        CHECK(Near(DistancePointToPolygon(P(15.0, 15.0), sq), 0.0, 1e-12),
              "inside point distance 0");
        CHECK(PolygonContains(P(15.0, 15.0), sq), "inside contains");
        CHECK(!PolygonContains(P(0.0, 0.0), sq), "outside not contained");
        CHECK(PolygonContains(P(10.0, 15.0), sq), "edge counts as inside");
        // Segment helper incl. degenerate.
        CHECK(Near(DistancePointToSegment(P(0.0, 0.0), P(1.0, 0.0), P(1.0, 0.0)),
                   1.0, 1e-12),
              "degenerate segment is point distance");
        CHECK(Near(DistancePointToSegment(P(0.0, 1.0), P(-1.0, 0.0), P(1.0, 0.0)),
                   1.0, 1e-12),
              "perpendicular segment distance");
        // Two squares with a gap of 3.
        Polygon2 a = Square(0.0, 0.0, 10.0, 10.0);
        Polygon2 b = Square(13.0, 0.0, 23.0, 10.0);
        CHECK(Near(DistancePolygonToPolygon(a, b), 3.0, 1e-9),
              "square gap 3");
        // Overlap -> 0.
        Polygon2 c = Square(5.0, 5.0, 15.0, 15.0);
        CHECK(Near(DistancePolygonToPolygon(a, c), 0.0, 1e-12),
              "overlapping squares 0");
        // Containment -> 0.
        Polygon2 inner = Square(2.0, 2.0, 4.0, 4.0);
        CHECK(Near(DistancePolygonToPolygon(a, inner), 0.0, 1e-12),
              "contained square 0");
        // Polygon vs segment: crossing -> 0, offset -> gap.
        CHECK(Near(DistancePolygonToSegment(a, P(-5.0, 5.0), P(15.0, 5.0)), 0.0,
                   1e-12),
              "crossing segment 0");
        CHECK(Near(DistancePolygonToSegment(a, P(13.0, 0.0), P(13.0, 10.0)),
                   3.0, 1e-9),
              "offset segment gap 3");
    }
    // ---- person/equipment pair ----
    {
        PersonGround person = ObservedPerson(0.0, 0.0);
        EquipmentGround eq = ObservedEquipment(Square(10.0, 10.0, 20.0, 20.0));
        PairRelation r = MinDistancePersonEquipment(person, eq);
        CHECK(r.has_distance && r.reason == PairReason::kOk, "pair ok");
        CHECK(Near(r.distance_m, std::sqrt(200.0), 1e-9),
              "pair distance sqrt(200) to corner");
        // Person inside footprint -> 0.
        PairRelation inside =
            MinDistancePersonEquipment(ObservedPerson(15.0, 15.0), eq);
        CHECK(inside.has_distance && Near(inside.distance_m, 0.0, 1e-12),
              "person inside equipment 0");
        // Negatives.
        PersonGround unknown_person;
        PairRelation pu = MinDistancePersonEquipment(unknown_person, eq);
        CHECK(!pu.has_distance && pu.reason == PairReason::kPersonUnknown,
              "unknown person no distance");
        EquipmentGround unknown_eq;
        PairRelation eu = MinDistancePersonEquipment(person, unknown_eq);
        CHECK(!eu.has_distance && eu.reason == PairReason::kEquipmentUnknown,
              "unknown equipment no distance");
        EquipmentGround thin = ObservedEquipment(Polygon2(1, P(0.0, 0.0)));
        PairRelation inv = MinDistancePersonEquipment(person, thin);
        CHECK(!inv.has_distance && inv.reason == PairReason::kInvalidPolygon,
              "degenerate footprint invalid");
        CHECK(std::string(ksi::safety::ToString(PairReason::kInvalidPolygon)) ==
                  "INVALID_POLYGON",
              "pair reason string");
    }
    // ---- zone relations: point ----
    {
        ZoneRevision zone = Zone("z1", Square(0.0, 0.0, 10.0, 10.0));
        ZoneRelationResult in =
            RelatePointToZone(ObservedPerson(5.0, 5.0), zone, 0.0);
        CHECK(in.relation == ZoneRelationKind::kInside &&
                  in.reason == ZoneReason::kOk && in.revision_id == "z1",
              "point inside zone");
        ZoneRelationResult out =
            RelatePointToZone(ObservedPerson(-5.0, 5.0), zone, 0.0);
        CHECK(out.relation == ZoneRelationKind::kOutside &&
                  out.reason == ZoneReason::kOk,
              "point outside zone");
        // Envelope: (11,5) is 1 m from the x=10 edge; env 2 -> uncertain,
        // env 0.5 -> outside.
        ZoneRelationResult env = RelatePointToZone(ObservedPerson(11.0, 5.0),
                                                   zone, 2.0);
        CHECK(env.relation == ZoneRelationKind::kBoundaryUncertain &&
                  env.reason == ZoneReason::kEnvelopeCross,
              "point within envelope uncertain");
        ZoneRelationResult far = RelatePointToZone(ObservedPerson(11.0, 5.0),
                                                   zone, 0.5);
        CHECK(far.relation == ZoneRelationKind::kOutside, "point beyond env");
        // Unknown status.
        PersonGround unknown;
        ZoneRelationResult u = RelatePointToZone(unknown, zone, 0.0);
        CHECK(u.relation == ZoneRelationKind::kUnknownStatus &&
                  u.reason == ZoneReason::kPersonUnknown,
              "unknown person zone status");
        // No polygons.
        ZoneRevision empty;
        empty.revision_id = "empty";
        ZoneRelationResult np =
            RelatePointToZone(ObservedPerson(5.0, 5.0), empty, 0.0);
        CHECK(np.relation == ZoneRelationKind::kUnknownStatus &&
                  np.reason == ZoneReason::kNoPolygons &&
                  np.revision_id == "empty",
              "empty zone no polygons");
        CHECK(std::string(ksi::safety::ToString(
                  ZoneRelationKind::kBoundaryUncertain)) ==
                  "BOUNDARY_UNCERTAIN",
              "zone kind string");
    }
    // ---- zone relations: equipment polygon ----
    {
        ZoneRevision zone = Zone("z1", Square(0.0, 0.0, 10.0, 10.0));
        ZoneRelationResult in = RelatePolygonToZone(
            ObservedEquipment(Square(2.0, 2.0, 4.0, 4.0)), zone, 0.0);
        CHECK(in.relation == ZoneRelationKind::kInside, "equipment inside");
        CHECK(in.revision_id == "z1", "equipment zone revision propagated");
        ZoneRelationResult out = RelatePolygonToZone(
            ObservedEquipment(Square(20.0, 20.0, 30.0, 30.0)), zone, 0.0);
        CHECK(out.relation == ZoneRelationKind::kOutside, "equipment outside");
        // Straddling the x=10 boundary -> uncertain even with env 0.
        ZoneRelationResult straddle = RelatePolygonToZone(
            ObservedEquipment(Square(9.0, 4.0, 11.0, 6.0)), zone, 0.0);
        CHECK(straddle.relation == ZoneRelationKind::kBoundaryUncertain &&
                  straddle.reason == ZoneReason::kEnvelopeCross,
              "straddling equipment uncertain");
        EquipmentGround unknown;
        ZoneRelationResult u = RelatePolygonToZone(unknown, zone, 0.0);
        CHECK(u.relation == ZoneRelationKind::kUnknownStatus &&
                  u.reason == ZoneReason::kEquipmentUnknown,
              "unknown equipment zone status");
        // Zone strictly inside a large equipment footprint: overlap without
        // boundary contact -> uncertain, not outside.
        ZoneRevision tiny = Zone("tiny", Square(4.0, 4.0, 6.0, 6.0));
        ZoneRelationResult surrounds = RelatePolygonToZone(
            ObservedEquipment(Square(0.0, 0.0, 10.0, 10.0)), tiny, 0.0);
        CHECK(surrounds.relation == ZoneRelationKind::kBoundaryUncertain,
              "zone inside equipment uncertain");
        // Degenerate footprint: fail-closed unknown status.
        EquipmentGround thin = ObservedEquipment(Polygon2(2, P(0.0, 0.0)));
        ZoneRelationResult deg = RelatePolygonToZone(thin, zone, 0.0);
        CHECK(deg.relation == ZoneRelationKind::kUnknownStatus &&
                  deg.reason == ZoneReason::kEquipmentUnknown,
              "degenerate equipment zone unknown");
    }
    // ---- integration GT: nadir projection -> pair distance ----
    // Camera alt 60, fx=fy=1200, centre pixel -> footpoint (0,0); equipment
    // square centred at x=12 half-width 1 -> distance 11.
    {
        CameraPoseEnu pose;
        pose.position_enu.z = 60.0;
        pose.camera_to_enu = QuatX180();
        GroundPlaneRevision plane;
        plane.revision_id = "g1";
        CameraIntrinsics cal;
        cal.fx = 1200.0;
        cal.fy = 1200.0;
        cal.cx = 500.0;
        cal.cy = 500.0;
        cal.ref_width = 1000;
        cal.ref_height = 1000;
        GroundProjectionResult g = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, cal, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(g.verdict == ProjectionVerdict::kProjected, "gt nadir hit");
        PersonGround person = ObservedPerson(g.enu.x, g.enu.y);
        EquipmentGround eq =
            ObservedEquipment(Square(11.0, -1.0, 13.0, 1.0));
        PairRelation r = MinDistancePersonEquipment(person, eq);
        CHECK(r.has_distance && Near(r.distance_m, 11.0, 1e-6),
              "gt nadir pair distance 11");
    }
    // ---- integration GT: oblique end-to-end ----
    // Same camera pitched 45 deg toward +x at alt 60: centre pixel -> (60,0);
    // equipment square [71..73]x[-1..1] -> distance 11.
    {
        CameraPoseEnu pose;
        pose.position_enu.z = 60.0;
        pose.camera_to_enu = QuatYRad(3.0 * kPi / 4.0);
        GroundPlaneRevision plane;
        plane.revision_id = "g1";
        CameraIntrinsics cal;
        cal.fx = 1200.0;
        cal.fy = 1200.0;
        cal.cx = 500.0;
        cal.cy = 500.0;
        cal.ref_width = 1000;
        cal.ref_height = 1000;
        GroundProjectionResult g = ProjectPixelToGround(
            500.0, 500.0, 1000, 1000, cal, pose, plane,
            PoseQuality::kSourceVerified, 0.05);
        CHECK(g.verdict == ProjectionVerdict::kProjected &&
                  Near(g.enu.x, 60.0, 1e-9) && Near(g.enu.y, 0.0, 1e-9),
              "gt oblique footpoint (60,0)");
        PairRelation r = MinDistancePersonEquipment(
            ObservedPerson(g.enu.x, g.enu.y),
            ObservedEquipment(Square(71.0, -1.0, 73.0, 1.0)));
        CHECK(r.has_distance && Near(r.distance_m, 11.0, 1e-6),
              "gt oblique pair distance 11");
    }
    if (edge_test::failures == 0) {
        std::printf("spatial: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
