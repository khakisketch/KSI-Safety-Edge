// Ground-plane relations: person footpoint / equipment footprint distances
// and zone relations (clean-room, C++17).
//
// Per workstream principles, distances are computed from the actual person
// footpoint and equipment footprint polygon (never bbox centres), and results
// here carry no warning semantics on their own: SHADOW-only until physical
// capture-time verification exists.

#ifndef KSI_SAFETY_SPATIAL_RELATION_HPP
#define KSI_SAFETY_SPATIAL_RELATION_HPP

#include <string>
#include <vector>

#include "ksi_safety/spatial/distance.hpp"

namespace ksi::safety {

enum class GroundStatus {
    kUnknown,
    kObserved,
};

struct PersonGround {
    GroundStatus status = GroundStatus::kUnknown;
    Vec2 footpoint;
};

struct EquipmentGround {
    GroundStatus status = GroundStatus::kUnknown;
    Polygon2 footprint;
};

enum class PairReason {
    kOk,
    kPersonUnknown,
    kEquipmentUnknown,
    kInvalidPolygon,
};

const char* ToString(PairReason reason);

struct PairRelation {
    bool has_distance = false;
    double distance_m = 0.0;
    PairReason reason = PairReason::kPersonUnknown;
};

// Requires an observed person and an observed equipment footprint with >= 3
// vertices; distance = DistancePointToPolygon(footpoint, footprint).
// Otherwise has_distance=false with the corresponding reason.
PairRelation MinDistancePersonEquipment(const PersonGround& person,
                                        const EquipmentGround& equipment);

struct ZoneRevision {
    std::string revision_id;
    std::vector<Polygon2> polygons;
};

enum class ZoneRelationKind {
    kInside,
    kOutside,
    kBoundaryUncertain,
    kUnknownStatus,
};

enum class ZoneReason {
    kOk,
    kPersonUnknown,
    kEquipmentUnknown,
    kNoPolygons,
    kEnvelopeCross,
};

const char* ToString(ZoneRelationKind kind);
const char* ToString(ZoneReason reason);

struct ZoneRelationResult {
    ZoneRelationKind relation = ZoneRelationKind::kUnknownStatus;
    std::string revision_id;
    ZoneReason reason = ZoneReason::kNoPolygons;
};

// Rules (both entry points, in order):
//  1. ground status kUnknown -> kUnknownStatus (kPersonUnknown /
//     kEquipmentUnknown).
//  2. zone has no polygons -> kUnknownStatus / kNoPolygons.
//  3. boundary distance (point: min over zone edges via
//     DistancePointToSegment; equipment: min over zone polygons x equipment
//     edges via DistancePolygonToSegment) <= envelope_m (negative envelope
//     treated as 0) -> kBoundaryUncertain / kEnvelopeCross. Touching geometry
//     has distance 0, so it always lands here.
//  4. containment -> kInside / kOk, else kOutside / kOk. Point: inside any
//     zone polygon (PolygonContains). Equipment polygon: ALL of its vertices
//     inside a single zone polygon -> kInside; else any-vertex-either-way
//     containment (one region strictly inside the other without boundary
//     contact) -> kBoundaryUncertain / kEnvelopeCross; else kOutside.
//     (Partial edge overlap always returns at step 3: intersecting edges have
//     distance 0 <= envelope.)
// revision_id is always propagated from the zone.
ZoneRelationResult RelatePointToZone(const PersonGround& person,
                                     const ZoneRevision& zone,
                                     double envelope_m);
ZoneRelationResult RelatePolygonToZone(const EquipmentGround& equipment,
                                       const ZoneRevision& zone,
                                       double envelope_m);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_SPATIAL_RELATION_HPP
