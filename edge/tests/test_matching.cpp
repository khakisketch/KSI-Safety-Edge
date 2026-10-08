// matching: ordered quality-grade policy tests, incl. the key regression
// (dense 400 Hz ring + delayed frame WITHOUT exposure must stay
// kReceiveNearest/kCaptureTimeUnverified - a small receive gap must NOT
// imply capture alignment).

#include "ksi_safety/time/matching.hpp"

#include <cstdint>
#include <cstdio>

#include "support/check.hpp"

using ksi::safety::FrameContext;
using ksi::safety::MatchFrame;
using ksi::safety::MatchPolicy;
using ksi::safety::MatchResult;
using ksi::safety::PoseQuality;
using ksi::safety::PoseReason;
using ksi::safety::Sample;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::TopicRing;

namespace {

const std::int64_t kMs = 1000000LL;  // one millisecond in ns

Timestamp Recv(std::int64_t ns) {
    Timestamp t;
    t.value_ns = ns;
    t.kind = TimeSourceKind::kHostReceive;
    t.domain = "host_mono";
    t.verified = true;
    return t;
}

Timestamp Src(std::int64_t ns, bool verified) {
    Timestamp t;
    t.value_ns = ns;
    t.kind = TimeSourceKind::kDeviceSensor;
    t.domain = "cam0";
    t.verified = verified;
    return t;
}

Sample<double> Mk(std::uint64_t id, std::int64_t recv_ns) {
    Sample<double> s;
    s.sample_id = id;
    s.seq = id;
    s.received = Recv(recv_ns);
    s.value = static_cast<double>(id);
    return s;
}

Sample<double> MkSrc(std::uint64_t id, std::int64_t recv_ns,
                     std::int64_t src_ns, bool verified) {
    Sample<double> s = Mk(id, recv_ns);
    s.has_source_time = true;
    s.source_time = Src(src_ns, verified);
    return s;
}

FrameContext Ctx(std::int64_t recv_ns) {
    FrameContext c;
    c.flight_id = "FLT-1";
    c.boot_id = "BOOT-1";
    c.stream_id = "cam0";
    c.frame_id = 7;
    c.receive_monotonic_ns = recv_ns;
    return c;
}

MatchPolicy Policy() {
    MatchPolicy p;
    p.max_receive_gap_ns = 50 * kMs;
    p.max_bracket_interval_ns = 20 * kMs;
    return p;
}

}  // namespace

int main() {
    // (a) empty ring -> kNone/kNoSamples.
    {
        TopicRing<double> ring(8);
        MatchResult<double> r = MatchFrame(Ctx(1000), ring, Policy());
        CHECK(r.quality == PoseQuality::kNone, "empty quality none");
        CHECK(r.reason == PoseReason::kNoSamples, "empty reason no-samples");
        CHECK(r.nearest == nullptr, "empty nearest null");
    }
    // (b) nearest receive gap over max -> kNone/kOutsideMaxGap.
    {
        TopicRing<double> ring(8);
        ring.Push(Mk(1, 0));
        MatchResult<double> r = MatchFrame(Ctx(100 * kMs), ring, Policy());
        CHECK(r.quality == PoseQuality::kNone, "gap quality none");
        CHECK(r.reason == PoseReason::kOutsideMaxGap, "gap reason outside-max");
    }
    // (c) KEY REGRESSION: 400 Hz dense ring + delayed frame, no exposure ->
    // kReceiveNearest/kCaptureTimeUnverified even though the gap is tiny and
    // the ring HAS verified source times.
    {
        TopicRing<double> ring(64);
        const std::int64_t step = 2500000LL;  // 2.5 ms == 400 Hz
        for (std::uint64_t i = 1; i <= 9; ++i) {
            ring.Push(MkSrc(i, static_cast<std::int64_t>(i) * step,
                            static_cast<std::int64_t>(i) * step, true));
        }
        FrameContext c = Ctx(9 * step + 100000);  // 0.1 ms after newest
        c.has_exposure = false;
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kReceiveNearest,
              "dense-recv quality receive-nearest");
        CHECK(r.reason == PoseReason::kCaptureTimeUnverified,
              "dense-recv reason capture-unverified");
        CHECK(r.nearest != nullptr && r.nearest_gap_ns == 100000,
              "dense-recv nearest gap tiny yet not promoted");
        CHECK(r.before == nullptr && r.after == nullptr,
              "dense-recv no bracket filled");
        // Unverified exposure flag with has_exposure=true is the same grade.
        FrameContext c2 = c;
        c2.has_exposure = true;
        c2.exposure = Src(9 * step + 100000, false);
        MatchResult<double> r2 = MatchFrame(c2, ring, Policy());
        CHECK(r2.quality == PoseQuality::kReceiveNearest &&
                  r2.reason == PoseReason::kCaptureTimeUnverified,
              "unverified exposure stays receive-nearest");
    }
    // (d) exposure verified but ring source times unverified ->
    // kReceiveNearest/kSourceTimeUnverified.
    {
        TopicRing<double> ring(8);
        ring.Push(MkSrc(1, 1000, 1000, false));
        ring.Push(MkSrc(2, 2000, 2000, false));
        FrameContext c = Ctx(1500);
        c.has_exposure = true;
        c.exposure = Src(1500, true);
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kReceiveNearest,
              "src-unverified quality receive-nearest");
        CHECK(r.reason == PoseReason::kSourceTimeUnverified,
              "src-unverified reason source-unverified");
    }
    // (e1) full verified + bracket -> kSourceVerified/kOk, sides filled.
    {
        TopicRing<double> ring(8);
        ring.Push(MkSrc(1, 1000, 1000, true));
        ring.Push(MkSrc(2, 2000, 2000, true));
        FrameContext c = Ctx(1500);
        c.has_exposure = true;
        c.exposure = Src(1500, true);
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kSourceVerified, "ok quality verified");
        CHECK(r.reason == PoseReason::kOk, "ok reason ok");
        CHECK(r.nearest != nullptr, "ok nearest filled");
        CHECK(r.before != nullptr && r.after != nullptr, "ok sides filled");
        if (r.before != nullptr && r.after != nullptr) {
            CHECK(r.before->sample_id == 1 && r.after->sample_id == 2,
                  "ok sides correct");
            CHECK(r.bracket_interval_ns == 1000, "ok interval 1000ns");
        }
    }
    // (e2) bracket too wide -> kReceiveNearest/kBracketTooWide.
    {
        TopicRing<double> ring(8);
        ring.Push(MkSrc(1, 0, 0, true));
        ring.Push(MkSrc(2, 100 * kMs, 100 * kMs, true));
        FrameContext c = Ctx(50 * kMs - 1);  // inside receive gap (50ms max)
        c.has_exposure = true;
        c.exposure = Src(50 * kMs, true);
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kReceiveNearest,
              "wide quality receive-nearest");
        CHECK(r.reason == PoseReason::kBracketTooWide, "wide reason too-wide");
    }
    // (e3) exposure outside verified source span -> kReceiveNearest/kNoBracket.
    {
        TopicRing<double> ring(8);
        ring.Push(MkSrc(1, 1000, 1000, true));
        ring.Push(MkSrc(2, 2000, 2000, true));
        FrameContext c = Ctx(1500);
        c.has_exposure = true;
        c.exposure = Src(5000, true);  // above all source times
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kReceiveNearest,
              "nobracket quality receive-nearest");
        CHECK(r.reason == PoseReason::kNoBracket, "nobracket reason");
    }
    // (e4) verified samples in a DIFFERENT domain than the exposure ->
    // kReceiveNearest/kDomainMismatch: two individually verified clocks are
    // not a common time axis, so no verified bracket may be formed.
    {
        TopicRing<double> ring(8);
        Sample<double> s1 = MkSrc(1, 1000, 1000, true);
        s1.source_time.domain = "camB";
        Sample<double> s2 = MkSrc(2, 2000, 2000, true);
        s2.source_time.domain = "camB";
        ring.Push(s1);
        ring.Push(s2);
        FrameContext c = Ctx(1500);
        c.has_exposure = true;
        c.exposure = Src(1500, true);  // domain "cam0"
        MatchResult<double> r = MatchFrame(c, ring, Policy());
        CHECK(r.quality == PoseQuality::kReceiveNearest,
              "domain-mismatch quality receive-nearest");
        CHECK(r.reason == PoseReason::kDomainMismatch,
              "domain-mismatch reason");
        CHECK(r.before == nullptr && r.after == nullptr,
              "domain-mismatch no bracket sides");
    }
    if (edge_test::failures == 0) {
        std::printf("matching: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
