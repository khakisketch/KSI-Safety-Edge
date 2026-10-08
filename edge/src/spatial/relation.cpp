#include "ksi_safety/spatial/relation.hpp"

namespace ksi::safety {

const char* ToString(PairReason reason) {
    switch (reason) {
        case PairReason::kOk:
            return "OK";
        case PairReason::kPersonUnknown:
            return "PERSON_UNKNOWN";
        case PairReason::kEquipmentUnknown:
            return "EQUIPMENT_UNKNOWN";
        case PairReason::kInvalidPolygon:
            return "INVALID_POLYGON";
    }
    return "UNKNOWN";
}

const char* ToString(ZoneRelationKind kind) {
    switch (kind) {
        case ZoneRelationKind::kInside:
            return "INSIDE";
        case ZoneRelationKind::kOutside:
            return "OUTSIDE";
        case ZoneRelationKind::kBoundaryUncertain:
            return "BOUNDARY_UNCERTAIN";
        case ZoneRelationKind::kUnknownStatus:
            return "UNKNOWN_STATUS";
    }
    return "UNKNOWN";
}

const char* ToString(ZoneReason reason) {
    switch (reason) {
        case ZoneReason::kOk:
            return "OK";
        case ZoneReason::kPersonUnknown:
            return "PERSON_UNKNOWN";
        case ZoneReason::kEquipmentUnknown:
            return "EQUIPMENT_UNKNOWN";
        case ZoneReason::kNoPolygons:
            return "NO_POLYGONS";
        case ZoneReason::kEnvelopeCross:
            return "ENVELOPE_CROSS";
    }
    return "UNKNOWN";
}

PairRelation MinDistancePersonEquipment(const PersonGround& person,
                                        const EquipmentGround& equipment) {
    PairRelation out;
    if (person.status != GroundStatus::kObserved) {
        out.reason = PairReason::kPersonUnknown;
        return out;
    }
    if (equipment.status != GroundStatus::kObserved) {
        out.reason = PairReason::kEquipmentUnknown;
        return out;
    }
    if (equipment.footprint.size() < 3) {
        out.reason = PairReason::kInvalidPolygon;
        return out;
    }
    out.has_distance = true;
    out.distance_m =
        DistancePointToPolygon(person.footpoint, equipment.footprint);
    out.reason = PairReason::kOk;
    return out;
}

namespace {

// Minimum distance from a ground point to any zone polygon edge.
double PointBoundaryDistance(Vec2 p, const ZoneRevision& zone) {
    double d = -1.0;  // caller guarantees at least one polygon
    for (std::size_t k = 0; k < zone.polygons.size(); ++k) {
        const Polygon2& poly = zone.polygons[k];
        for (std::size_t i = 0; i < poly.size(); ++i) {
            const double e = DistancePointToSegment(
                p, poly[i], poly[(i + 1) % poly.size()]);
            if (d < 0.0 || e < d) {
                d = e;
            }
        }
    }
    return d;
}

// Minimum distance from any equipment edge to any zone polygon.
double EquipmentBoundaryDistance(const Polygon2& footprint,
                                 const ZoneRevision& zone) {
    double d = -1.0;
    for (std::size_t k = 0; k < zone.polygons.size(); ++k) {
        for (std::size_t i = 0; i < footprint.size(); ++i) {
            const double e = DistancePolygonToSegment(
                zone.polygons[k], footprint[i],
                footprint[(i + 1) % footprint.size()]);
            if (d < 0.0 || e < d) {
                d = e;
            }
        }
    }
    return d;
}

bool PointInZone(Vec2 p, const ZoneRevision& zone) {
    for (std::size_t k = 0; k < zone.polygons.size(); ++k) {
        if (zone.polygons[k].size() >= 3 && PolygonContains(p, zone.polygons[k])) {
            return true;
        }
    }
    return false;
}

// Equipment is "inside" only when every footprint vertex lies in a single
// zone polygon. Partial overlap (some vertex in, some out, or edges crossing
// a zone boundary) always returns from the envelope stage first because
// intersecting geometry has distance 0 <= envelope.
bool EquipmentInZone(const Polygon2& footprint, const ZoneRevision& zone) {
    for (std::size_t k = 0; k < zone.polygons.size(); ++k) {
        const Polygon2& poly = zone.polygons[k];
        if (poly.size() < 3) {
            continue;
        }
        bool all_in = true;
        for (std::size_t i = 0; i < footprint.size(); ++i) {
            if (!PolygonContains(footprint[i], poly)) {
                all_in = false;
                break;
            }
        }
        if (all_in) {
            return true;
        }
    }
    return false;
}

// Containment without boundary contact in either direction (one region
// strictly inside the other): geometrically this is overlap, reported as
// boundary-uncertain since the footprint is neither fully inside nor fully
// outside the zone.
bool EquipmentOverlapsZone(const Polygon2& footprint,
                           const ZoneRevision& zone) {
    for (std::size_t k = 0; k < zone.polygons.size(); ++k) {
        const Polygon2& poly = zone.polygons[k];
        if (poly.size() < 3) {
            continue;
        }
        for (std::size_t i = 0; i < footprint.size(); ++i) {
            if (PolygonContains(footprint[i], poly)) {
                return true;
            }
        }
        for (std::size_t j = 0; j < poly.size(); ++j) {
            if (PolygonContains(poly[j], footprint)) {
                return true;
            }
        }
    }
    return false;
}

}  // namespace

ZoneRelationResult RelatePointToZone(const PersonGround& person,
                                     const ZoneRevision& zone,
                                     double envelope_m) {
    ZoneRelationResult out;
    out.revision_id = zone.revision_id;
    if (person.status != GroundStatus::kObserved) {
        out.reason = ZoneReason::kPersonUnknown;
        return out;
    }
    if (zone.polygons.empty()) {
        out.reason = ZoneReason::kNoPolygons;
        return out;
    }
    const double env = envelope_m < 0.0 ? 0.0 : envelope_m;
    if (PointBoundaryDistance(person.footpoint, zone) <= env) {
        out.relation = ZoneRelationKind::kBoundaryUncertain;
        out.reason = ZoneReason::kEnvelopeCross;
        return out;
    }
    out.relation = PointInZone(person.footpoint, zone)
                       ? ZoneRelationKind::kInside
                       : ZoneRelationKind::kOutside;
    out.reason = ZoneReason::kOk;
    return out;
}

ZoneRelationResult RelatePolygonToZone(const EquipmentGround& equipment,
                                       const ZoneRevision& zone,
                                       double envelope_m) {
    ZoneRelationResult out;
    out.revision_id = zone.revision_id;
    if (equipment.status != GroundStatus::kObserved) {
        out.reason = ZoneReason::kEquipmentUnknown;
        return out;
    }
    if (zone.polygons.empty()) {
        out.reason = ZoneReason::kNoPolygons;
        return out;
    }
    if (equipment.footprint.size() < 3) {
        // Degenerate footprint: cannot relate; surfacing as unknown-status
        // with the equipment reason keeps the fail-closed shape.
        out.reason = ZoneReason::kEquipmentUnknown;
        return out;
    }
    const double env = envelope_m < 0.0 ? 0.0 : envelope_m;
    if (EquipmentBoundaryDistance(equipment.footprint, zone) <= env) {
        out.relation = ZoneRelationKind::kBoundaryUncertain;
        out.reason = ZoneReason::kEnvelopeCross;
        return out;
    }
    if (EquipmentInZone(equipment.footprint, zone)) {
        out.relation = ZoneRelationKind::kInside;
        out.reason = ZoneReason::kOk;
        return out;
    }
    if (EquipmentOverlapsZone(equipment.footprint, zone)) {
        out.relation = ZoneRelationKind::kBoundaryUncertain;
        out.reason = ZoneReason::kEnvelopeCross;
        return out;
    }
    out.relation = ZoneRelationKind::kOutside;
    out.reason = ZoneReason::kOk;
    return out;
}

}  // namespace ksi::safety
