// 2D planar distance kernels (clean-room, C++17).
//
// All distances are Euclidean in the ground plane. Polygons are simple vertex
// lists (edges implicitly close last->first). Boundary counts as inside for
// containment, and touching/intersecting geometry has distance 0.

#ifndef KSI_SAFETY_SPATIAL_DISTANCE_HPP
#define KSI_SAFETY_SPATIAL_DISTANCE_HPP

#include <vector>

namespace ksi::safety {

struct Vec2 {
    double x = 0.0;
    double y = 0.0;
};

using Polygon2 = std::vector<Vec2>;

double DistancePointToSegment(Vec2 p, Vec2 a, Vec2 b);
// Ray casting; points on an edge count as inside.
bool PolygonContains(Vec2 p, const Polygon2& poly);
// 0 when inside/on edge. Empty polygon -> +inf; 1 vertex -> point distance;
// 2 vertices -> segment distance.
double DistancePointToPolygon(Vec2 p, const Polygon2& poly);
// 0 when the polygons intersect or one contains the other.
double DistancePolygonToPolygon(const Polygon2& a, const Polygon2& b);
// Minimum over polygon edges vs the segment; 0 on intersection/touch.
double DistancePolygonToSegment(const Polygon2& poly, Vec2 a, Vec2 b);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_SPATIAL_DISTANCE_HPP
