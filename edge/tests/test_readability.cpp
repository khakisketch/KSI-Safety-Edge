// Readability arithmetic tests: pins the GSD table from
// docs/verification/2026-10-27-ppe-readability.md as executable checks,
// plus malformed-input refusal. Pure math, no device, no model.

#include "ksi_safety/observation/readability.hpp"

#include <cmath>
#include <limits>

#include "support/check.hpp"

using ksi::safety::observation::GroundSampleDistance;
using ksi::safety::observation::HasRecognitionDetail;
using ksi::safety::observation::TargetPixels;

namespace {

bool Near(double got, double want, double tol) {
    return std::fabs(got - want) <= tol;
}

double HalfFov(double full_deg) {
    return (full_deg / 2.0) * 3.141592653589793 / 180.0;
}

}  // namespace

int main() {
    // ---- doc table: 82 deg HFOV, 1920 px, helmet 0.25 m -----------------
    {
        double gsd = 0.0;
        CHECK(GroundSampleDistance(50.0, HalfFov(82.0), 1920.0, &gsd),
              "valid inputs accepted");
        CHECK(Near(gsd, 0.0453, 0.0005), "50 m GSD is 4.53 cm/px");
        double pixels = 0.0;
        CHECK(TargetPixels(0.25, gsd, &pixels), "helmet span computed");
        CHECK(Near(pixels, 5.5, 0.1), "helmet is 5.5 px at 50 m");
        CHECK(!HasRecognitionDetail(0.25, gsd, 8.0),
              "unreadable at patrol altitude with an 8 px bar");
        double gsd30 = 0.0;
        CHECK(GroundSampleDistance(30.0, HalfFov(82.0), 1920.0, &gsd30),
              "30 m accepted");
        CHECK(HasRecognitionDetail(0.25, gsd30, 8.0),
              "readable at 30 m with the same bar");
    }
    // ---- zoom divisor is the caller's, not this unit's --------------------
    {
        double gsd = 0.0;
        CHECK(GroundSampleDistance(50.0, HalfFov(82.0), 1920.0, &gsd),
              "baseline GSD");
        // A 10.6x optical zoom divides the effective GSD; the unit only
        // divides what the caller passes.
        CHECK(HasRecognitionDetail(0.25, gsd / 10.6, 8.0),
              "zoomed GSD is readable");
    }
    // ---- malformed inputs fail closed --------------------------------------
    {
        double out = -1.0;
        CHECK(!GroundSampleDistance(-50.0, HalfFov(82.0), 1920.0, &out),
              "negative altitude refused");
        CHECK(!GroundSampleDistance(50.0, 0.0, 1920.0, &out),
              "zero fov refused");
        CHECK(!GroundSampleDistance(50.0, 1.58, 1920.0, &out),
              "beyond-horizon fov refused");
        CHECK(!GroundSampleDistance(50.0, HalfFov(82.0), 0.0, &out),
              "zero width refused");
        CHECK(!GroundSampleDistance(std::numeric_limits<double>::infinity(),
                                    HalfFov(82.0), 1920.0, &out),
              "infinite altitude refused");
        CHECK(!GroundSampleDistance(std::numeric_limits<double>::quiet_NaN(),
                                    HalfFov(82.0), 1920.0, &out),
              "NaN altitude refused");
        CHECK(!GroundSampleDistance(50.0, HalfFov(82.0), 1920.0, nullptr),
              "null output refused");
        CHECK(!TargetPixels(0.25, 0.0, &out), "zero GSD refused");
        CHECK(!TargetPixels(-0.25, 0.0453, &out), "negative size refused");
        CHECK(!HasRecognitionDetail(0.25, 0.0453, 0.0),
              "zero bar refused (no invented threshold)");
        CHECK(!HasRecognitionDetail(0.25, 0.0453, -8.0),
              "negative bar refused");
    }
    if (edge_test::failures == 0) {
        std::printf("readability: GSD table pinned, malformed refused\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
