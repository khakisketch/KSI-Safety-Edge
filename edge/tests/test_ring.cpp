// topic_ring: duplicate/rollback/nearest/bracket/overwrite contract tests.

#include "ksi_safety/time/topic_ring.hpp"

#include <cstdint>
#include <cstdio>

#include "support/check.hpp"

using ksi::safety::PushVerdict;
using ksi::safety::Sample;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::TopicRing;

namespace {

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

}  // namespace

int main() {
    // duplicate sample_id of the most recent sample -> kDuplicate, size kept.
    {
        TopicRing<double> ring(8);
        CHECK(ring.Push(Mk(1, 100)) == PushVerdict::kRecorded, "first recorded");
        CHECK(ring.size() == 1, "size 1");
        CHECK(ring.Push(Mk(1, 101)) == PushVerdict::kDuplicate, "dup id rejected");
        CHECK(ring.size() == 1, "size unchanged after duplicate");
        CHECK(ring.Push(Mk(2, 102)) == PushVerdict::kRecorded, "new id recorded");
        CHECK(ring.size() == 2, "size 2");
    }
    // received strictly less than previous -> kClockRollback (not stored).
    {
        TopicRing<double> ring(8);
        CHECK(ring.Push(Mk(1, 100)) == PushVerdict::kRecorded, "base recorded");
        CHECK(ring.Push(Mk(2, 99)) == PushVerdict::kClockRollback, "rollback");
        CHECK(ring.size() == 1, "size unchanged after rollback");
        CHECK(ring.Push(Mk(2, 100)) == PushVerdict::kRecorded,
              "equal ts is not a rollback");
        CHECK(ring.size() == 2, "size 2 after equal-ts push");
    }
    // nearest by receive: smallest |gap| wins; ties -> newest.
    {
        TopicRing<double> ring(8);
        ring.Push(Mk(1, 100));
        ring.Push(Mk(2, 110));
        ring.Push(Mk(3, 120));
        const Sample<double>* out = nullptr;
        std::int64_t gap = -1;
        CHECK(ring.FindNearestReceive(109, &out, &gap), "nearest found");
        CHECK(out != nullptr && out->sample_id == 2, "nearest is id 2");
        CHECK(gap == 1, "gap is 1");
        // tie: 105 is equidistant from 100 and 110 -> newest (id 2).
        out = nullptr;
        CHECK(ring.FindNearestReceive(105, &out, &gap), "tie found");
        CHECK(out != nullptr && out->sample_id == 2, "tie prefers newest");
        CHECK(gap == 5, "tie gap is 5");
        // empty ring -> false.
        TopicRing<double> empty(4);
        CHECK(!empty.FindNearestReceive(100, &out, &gap), "empty no nearest");
    }
    // capacity overwrite: oldest dropped, size stays at capacity.
    {
        TopicRing<double> ring(2);
        ring.Push(Mk(1, 100));
        ring.Push(Mk(2, 110));
        CHECK(ring.size() == 2, "full");
        CHECK(ring.Push(Mk(3, 120)) == PushVerdict::kRecorded, "overwrite ok");
        CHECK(ring.size() == 2, "size stays at capacity");
        const Sample<double>* out = nullptr;
        std::int64_t gap = -1;
        CHECK(ring.FindNearestReceive(100, &out, &gap), "lookup after overwrite");
        CHECK(out != nullptr && out->sample_id == 2, "oldest (id 1) dropped");
    }
    // bracket found only with both sides (source-time samples).
    {
        TopicRing<double> ring(8);
        ring.Push(MkSrc(1, 100, 1000, true));
        ring.Push(MkSrc(2, 110, 1100, true));
        const Sample<double>* before = nullptr;
        const Sample<double>* after = nullptr;
        CHECK(ring.FindBracketBySourceTime(1050, &before, &after),
              "bracket found");
        CHECK(before != nullptr && after != nullptr, "both sides set");
        if (before != nullptr && after != nullptr) {
            CHECK(before->sample_id == 1 && after->sample_id == 2,
                  "bracket sides correct");
        }
        // target outside on either side -> no bracket.
        CHECK(!ring.FindBracketBySourceTime(900, &before, &after),
              "no bracket below range");
        CHECK(!ring.FindBracketBySourceTime(1200, &before, &after),
              "no bracket above range");
        // samples without source times cannot bracket.
        TopicRing<double> nosrc(8);
        nosrc.Push(Mk(1, 100));
        nosrc.Push(Mk(2, 110));
        CHECK(!nosrc.FindBracketBySourceTime(105, &before, &after),
              "no bracket without source times");
    }
    if (edge_test::failures == 0) {
        std::printf("ring: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
