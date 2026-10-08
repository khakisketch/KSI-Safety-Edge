// interpolation: refusal tiers + SLERP math tests.

#include "ksi_safety/time/interpolation.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>

#include "support/check.hpp"

using ksi::safety::InterpolatePose;
using ksi::safety::InterpolationVerdict;
using ksi::safety::Lerp;
using ksi::safety::PoseValue;
using ksi::safety::Quaternion;
using ksi::safety::Sample;
using ksi::safety::Slerp;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::Vec3;
using ksi::safety::WrapDegrees;

namespace {

const double kPi = 3.14159265358979323846;

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

double QuatZDegrees(const Quaternion& q) {
    return WrapDegrees(2.0 * std::atan2(q.z, q.w) * 180.0 / kPi);
}

Timestamp Src(std::int64_t ns, const char* domain, bool verified) {
    Timestamp t;
    t.value_ns = ns;
    t.kind = TimeSourceKind::kDeviceSensor;
    t.domain = domain;
    t.verified = verified;
    return t;
}

Timestamp Recv(std::int64_t ns) {
    Timestamp t;
    t.value_ns = ns;
    t.kind = TimeSourceKind::kHostReceive;
    t.domain = "host_mono";
    t.verified = true;
    return t;
}

Sample<PoseValue> Mk(std::uint64_t id, std::int64_t recv_ns,
                     std::int64_t src_ns, const char* domain, bool verified,
                     const Quaternion& q, const Vec3& p) {
    Sample<PoseValue> s;
    s.sample_id = id;
    s.seq = id;
    s.received = Recv(recv_ns);
    s.has_source_time = true;
    s.source_time = Src(src_ns, domain, verified);
    s.value.q = q;
    s.value.position = p;
    return s;
}

bool Near(double a, double b, double tol) {
    return std::fabs(a - b) <= tol;
}

}  // namespace

int main() {
    const Quaternion q0 = QuatZ(0.0);
    const Quaternion q90 = QuatZ(90.0);
    const Vec3 p0{0.0, 0.0, 0.0};
    const Vec3 p1{10.0, 0.0, 0.0};
    const Sample<PoseValue> before =
        Mk(1, 1000, 1000, "cam0", true, q0, p0);
    const Sample<PoseValue> after =
        Mk(2, 2000, 2000, "cam0", true, q90, p1);

    // half-way SLERP: 0 deg -> 90 deg about z at t=0.5 ~= 45 deg.
    {
        PoseValue out;
        InterpolationVerdict v =
            InterpolatePose(before, after, 1500, 100000, &out);
        CHECK(v == InterpolationVerdict::kInterpolated, "halfway ok");
        CHECK(Near(QuatZDegrees(out.q), 45.0, 1e-6), "halfway angle 45deg");
        CHECK(Near(out.position.x, 5.0, 1e-9) &&
                  Near(out.position.y, 0.0, 1e-9) &&
                  Near(out.position.z, 0.0, 1e-9),
              "halfway position lerped");
        // endpoints: target == before -> t=0, target == after -> t=1.
        PoseValue lo;
        CHECK(InterpolatePose(before, after, 1000, 100000, &lo) ==
                  InterpolationVerdict::kInterpolated,
              "target at before ok");
        CHECK(Near(QuatZDegrees(lo.q), 0.0, 1e-6), "t=0 angle");
        PoseValue hi;
        CHECK(InterpolatePose(before, after, 2000, 100000, &hi) ==
                  InterpolationVerdict::kInterpolated,
              "target at after ok");
        CHECK(Near(QuatZDegrees(hi.q), 90.0, 1e-6), "t=1 angle");
    }
    // wrap: 359.5 deg -> 0.5 deg midpoint ~= 0 deg (shortest path).
    {
        const Sample<PoseValue> w0 =
            Mk(1, 1000, 1000, "cam0", true, QuatZ(359.5), p0);
        const Sample<PoseValue> w1 =
            Mk(2, 2000, 2000, "cam0", true, QuatZ(0.5), p1);
        PoseValue out;
        InterpolationVerdict v =
            InterpolatePose(w0, w1, 1500, 100000, &out);
        CHECK(v == InterpolationVerdict::kInterpolated, "wrap ok");
        CHECK(Near(QuatZDegrees(out.q), 0.0, 1e-6), "wrap midpoint 0deg");
        CHECK(Near(WrapDegrees(359.5), -0.5, 1e-9), "wrap 359.5 -> -0.5");
        CHECK(Near(WrapDegrees(765.0), 45.0, 1e-9), "wrap 765 -> 45");
    }
    // SLERP unit behavior: identical inputs -> same; q vs -q -> same rotation.
    {
        Quaternion same = Slerp(q90, q90, 0.3);
        CHECK(Near(same.w, q90.w, 1e-12) && Near(same.z, q90.z, 1e-12),
              "identical inputs identity");
        Quaternion neg{-q90.w, -q90.x, -q90.y, -q90.z};
        Quaternion mid = Slerp(q90, neg, 0.5);
        const double dot = mid.w * q90.w + mid.z * q90.z;
        CHECK(Near(std::fabs(dot), 1.0, 1e-9), "neg-dot shortest path");
        Vec3 v = Lerp(p0, p1, 0.25);
        CHECK(Near(v.x, 2.5, 1e-12), "lerp quarter");
    }
    // refuses: unverified either side.
    {
        PoseValue out;
        Sample<PoseValue> bad_before = before;
        bad_before.source_time.verified = false;
        CHECK(InterpolatePose(bad_before, after, 1500, 100000, &out) ==
                  InterpolationVerdict::kRefusedUnverified,
              "refuse unverified before");
        Sample<PoseValue> bad_after = after;
        bad_after.source_time.verified = false;
        CHECK(InterpolatePose(before, bad_after, 1500, 100000, &out) ==
                  InterpolationVerdict::kRefusedUnverified,
              "refuse unverified after");
        Sample<PoseValue> no_src = before;
        no_src.has_source_time = false;
        CHECK(InterpolatePose(no_src, after, 1500, 100000, &out) ==
                  InterpolationVerdict::kRefusedUnverified,
              "refuse missing source time");
    }
    // refuses: same-domain requirement.
    {
        PoseValue out;
        Sample<PoseValue> other = after;
        other.source_time.domain = "cam1";
        CHECK(InterpolatePose(before, other, 1500, 100000, &out) ==
                  InterpolationVerdict::kRefusedUnverified,
              "refuse cross-domain bracket");
    }
    // refuses: degenerate bracket (before >= after).
    {
        PoseValue out;
        CHECK(InterpolatePose(after, before, 1500, 100000, &out) ==
                  InterpolationVerdict::kRefusedNoBracket,
              "refuse inverted bracket");
        CHECK(InterpolatePose(before, before, 1000, 100000, &out) ==
                  InterpolationVerdict::kRefusedNoBracket,
              "refuse zero-width bracket");
    }
    // refuses: interval too wide.
    {
        PoseValue out;
        CHECK(InterpolatePose(before, after, 1500, 500, &out) ==
                  InterpolationVerdict::kRefusedIntervalTooWide,
              "refuse wide interval");
    }
    // refuses: target outside [before, after].
    {
        PoseValue out;
        CHECK(InterpolatePose(before, after, 999, 100000, &out) ==
                  InterpolationVerdict::kRefusedTargetOutside,
              "refuse target below");
        CHECK(InterpolatePose(before, after, 2001, 100000, &out) ==
                  InterpolationVerdict::kRefusedTargetOutside,
              "refuse target above");
    }
    // null output guard.
    {
        CHECK(InterpolatePose(before, after, 1500, 100000, nullptr) ==
                  InterpolationVerdict::kRefusedNoBracket,
              "null out refused");
    }
    if (edge_test::failures == 0) {
        std::printf("interpolation: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
