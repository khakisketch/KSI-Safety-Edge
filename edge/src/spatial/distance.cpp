#include "ksi_safety/spatial/distance.hpp"

#include <cmath>
#include <limits>

namespace ksi::safety {

namespace {

// Absolute tolerance for on-edge classification (metre-scale inputs).
const double kEdgeTol = 1e-9;

double Dot(Vec2 a, Vec2 b) { return a.x * b.x + a.y * b.y; }

Vec2 Sub(Vec2 a, Vec2 b) {
    Vec2 out;
    out.x = a.x - b.x;
    out.y = a.y - b.y;
    return out;
}

double Norm(Vec2 v) { return std::sqrt(v.x * v.x + v.y * v.y); }

double Cross(Vec2 a, Vec2 b) { return a.x * b.y - a.y * b.x; }

// Orientation of triplet (a,b,c): +1 ccw, -1 cw, 0 collinear.
int Orient(Vec2 a, Vec2 b, Vec2 c) {
    const double v = Cross(Sub(b, a), Sub(c, a));
    if (v > 0.0) {
        return 1;
    }
    if (v < 0.0) {
        return -1;
    }
    return 0;
}

bool OnSegment(Vec2 p, Vec2 a, Vec2 b) {
    const double minx = a.x < b.x ? a.x : b.x;
    const double maxx = a.x > b.x ? a.x : b.x;
    const double miny = a.y < b.y ? a.y : b.y;
    const double maxy = a.y > b.y ? a.y : b.y;
    return p.x >= minx && p.x <= maxx && p.y >= miny && p.y <= maxy;
}

bool SegmentsIntersect(Vec2 p1, Vec2 p2, Vec2 p3, Vec2 p4) {
    const int o1 = Orient(p1, p2, p3);
    const int o2 = Orient(p1, p2, p4);
    const int o3 = Orient(p3, p4, p1);
    const int o4 = Orient(p3, p4, p2);
    if (o1 != o2 && o3 != o4) {
        return true;
    }
    if (o1 == 0 && OnSegment(p3, p1, p2)) {
        return true;
    }
    if (o2 == 0 && OnSegment(p4, p1, p2)) {
        return true;
    }
    if (o3 == 0 && OnSegment(p1, p3, p4)) {
        return true;
    }
    if (o4 == 0 && OnSegment(p2, p3, p4)) {
        return true;
    }
    return false;
}

// Unsigned distance between segments p1-p2 and p3-p4 (0 if they touch).
double SegmentSegment(Vec2 p1, Vec2 p2, Vec2 p3, Vec2 p4) {
    if (SegmentsIntersect(p1, p2, p3, p4)) {
        return 0.0;
    }
    double d = DistancePointToSegment(p1, p3, p4);
    const double d2 = DistancePointToSegment(p2, p3, p4);
    if (d2 < d) {
        d = d2;
    }
    const double d3 = DistancePointToSegment(p3, p1, p2);
    if (d3 < d) {
        d = d3;
    }
    const double d4 = DistancePointToSegment(p4, p1, p2);
    if (d4 < d) {
        d = d4;
    }
    return d;
}

}  // namespace

double DistancePointToSegment(Vec2 p, Vec2 a, Vec2 b) {
    const Vec2 ab = Sub(b, a);
    const double len2 = Dot(ab, ab);
    if (len2 == 0.0) {
        return Norm(Sub(p, a));  // degenerate edge: point distance
    }
    double t = Dot(Sub(p, a), ab) / len2;
    if (t < 0.0) {
        t = 0.0;
    } else if (t > 1.0) {
        t = 1.0;
    }
    Vec2 proj;
    proj.x = a.x + t * ab.x;
    proj.y = a.y + t * ab.y;
    return Norm(Sub(p, proj));
}

bool PolygonContains(Vec2 p, const Polygon2& poly) {
    const std::size_t n = poly.size();
    if (n < 3) {
        return false;
    }
    // Boundary counts as inside: exact on-edge test first.
    for (std::size_t i = 0; i < n; ++i) {
        const Vec2 a = poly[i];
        const Vec2 b = poly[(i + 1) % n];
        if (DistancePointToSegment(p, a, b) <= kEdgeTol) {
            return true;
        }
    }
    // Ray casting (+x direction); count crossings with care at vertices by
    // testing straddling of the edge endpoints in y.
    bool inside = false;
    for (std::size_t i = 0; i < n; ++i) {
        const Vec2 a = poly[i];
        const Vec2 b = poly[(i + 1) % n];
        const bool straddles =
            (a.y > p.y) != (b.y > p.y);
        if (!straddles) {
            continue;
        }
        const double xhit = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y);
        if (xhit > p.x) {
            inside = !inside;
        }
    }
    return inside;
}

double DistancePointToPolygon(Vec2 p, const Polygon2& poly) {
    if (poly.empty()) {
        return std::numeric_limits<double>::infinity();
    }
    if (poly.size() == 1) {
        return Norm(Sub(p, poly[0]));
    }
    if (poly.size() == 2) {
        return DistancePointToSegment(p, poly[0], poly[1]);
    }
    if (PolygonContains(p, poly)) {
        return 0.0;
    }
    double d = std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const double e =
            DistancePointToSegment(p, poly[i], poly[(i + 1) % poly.size()]);
        if (e < d) {
            d = e;
        }
    }
    return d;
}

double DistancePolygonToPolygon(const Polygon2& a, const Polygon2& b) {
    if (a.empty() || b.empty()) {
        return std::numeric_limits<double>::infinity();
    }
    // Intersection or containment in either direction -> 0.
    for (std::size_t i = 0; i < a.size(); ++i) {
        const Vec2 a0 = a[i];
        const Vec2 a1 = a[(i + 1) % a.size()];
        for (std::size_t j = 0; j < b.size(); ++j) {
            if (SegmentsIntersect(a0, a1, b[j], b[(j + 1) % b.size()])) {
                return 0.0;
            }
        }
    }
    if (a.size() >= 3 && PolygonContains(b[0], a)) {
        return 0.0;
    }
    if (b.size() >= 3 && PolygonContains(a[0], b)) {
        return 0.0;
    }
    double d = std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < a.size(); ++i) {
        for (std::size_t j = 0; j < b.size(); ++j) {
            const double e = SegmentSegment(a[i], a[(i + 1) % a.size()], b[j],
                                            b[(j + 1) % b.size()]);
            if (e < d) {
                d = e;
            }
        }
    }
    return d;
}

double DistancePolygonToSegment(const Polygon2& poly, Vec2 a, Vec2 b) {
    if (poly.empty()) {
        return std::numeric_limits<double>::infinity();
    }
    if (poly.size() == 1) {
        return DistancePointToSegment(poly[0], a, b);
    }
    if (poly.size() == 2) {
        return SegmentSegment(poly[0], poly[1], a, b);
    }
    // Minimum edge-to-segment distance. SegmentSegment() is 0 exactly when
    // the two touch or cross, so containment of the segment inside the
    // polygon (no boundary contact) correctly yields the distance to the
    // nearest edge rather than 0.
    double d = std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const double e =
            SegmentSegment(poly[i], poly[(i + 1) % poly.size()], a, b);
        if (e < d) {
            d = e;
        }
    }
    return d;
}

}  // namespace ksi::safety
