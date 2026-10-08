// capture_queue: BoundedFrameQueue contract tests (ported SI module).
//
// Covers: accept/pop roundtrip incl. metadata; size mismatch -> kMalformed;
// oversize -> kFrameTooLarge; capacity-2 third push -> kQueueFull (FIFO kept).
// try_lock contention paths are not exercised (single-threaded contract only).

#include "ksi_safety/capture/frame_queue.hpp"

#include <cstdint>
#include <cstdio>
#include <vector>

#include "support/check.hpp"

namespace {

using ksi::safety::BoundedFrameQueue;
using ksi::safety::FrameDropReason;
using ksi::safety::FrameMetadata;
using ksi::safety::FramePacket;

FrameMetadata Meta(std::uint32_t w, std::uint32_t h, std::uint32_t id,
                   std::int64_t recv_ns) {
    FrameMetadata m;
    m.width = w;
    m.height = h;
    m.frame_id = id;
    m.received_monotonic_ns = recv_ns;
    return m;
}

}  // namespace

int main() {
    // accept/pop roundtrip incl. metadata
    {
        BoundedFrameQueue q(2, 64);
        std::vector<std::uint8_t> rgb(2 * 2 * 3, 0x7e);
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 42, 100)) ==
                  FrameDropReason::kAccepted,
              "push accepted");
        FramePacket out;
        CHECK(q.TryPop(&out), "pop ok");
        CHECK(out.rgb == rgb, "bytes roundtrip");
        CHECK(out.metadata.frame_id == 42 && out.metadata.width == 2 &&
                  out.metadata.height == 2,
              "metadata roundtrip");
        CHECK(out.metadata.received_monotonic_ns == 100, "recv ts roundtrip");
        CHECK(!q.TryPop(&out), "empty pop returns false");
        CHECK(q.TryPop(nullptr) == false, "null pop returns false");
    }
    // size mismatch -> kMalformed
    {
        BoundedFrameQueue q(2, 1024);
        std::vector<std::uint8_t> rgb(10, 1);
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 1, 10)) ==
                  FrameDropReason::kMalformed,
              "size mismatch malformed");
        CHECK(q.stats().malformed == 1, "malformed counted");
    }
    // degenerate inputs -> kMalformed
    {
        BoundedFrameQueue q(2, 1024);
        std::vector<std::uint8_t> rgb(12, 1);
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(0, 2, 1, 10)) ==
                  FrameDropReason::kMalformed,
              "zero width malformed");
        CHECK(q.PushFromCallback(nullptr, 12, Meta(2, 2, 1, 10)) ==
                  FrameDropReason::kMalformed,
              "null data malformed");
        CHECK(q.PushFromCallback(rgb.data(), 0, Meta(2, 2, 1, 10)) ==
                  FrameDropReason::kMalformed,
              "zero bytes malformed");
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(),
                                 Meta(2, 2, 1, 0)) ==
                  FrameDropReason::kMalformed,
              "non-positive recv ts malformed");
    }
    // oversize -> kFrameTooLarge (boundary: bytes == max is accepted)
    {
        BoundedFrameQueue q(2, 12);
        std::vector<std::uint8_t> rgb(2 * 2 * 3, 1);
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 7, 10)) ==
                  FrameDropReason::kAccepted,
              "boundary size accepted");
        FramePacket o;
        CHECK(q.TryPop(&o), "pop boundary");
        BoundedFrameQueue q2(2, 11);
        CHECK(q2.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 7, 10)) ==
                  FrameDropReason::kFrameTooLarge,
              "oversize too-large");
        CHECK(q2.stats().frame_too_large == 1, "too-large counted");
    }
    // capacity-2 third push -> kQueueFull, FIFO order preserved
    {
        BoundedFrameQueue q(2, 64);
        std::vector<std::uint8_t> rgb(2 * 2 * 3, 3);
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 1, 1)) ==
                  FrameDropReason::kAccepted,
              "slot1");
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 2, 2)) ==
                  FrameDropReason::kAccepted,
              "slot2");
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 3, 3)) ==
                  FrameDropReason::kQueueFull,
              "third push full");
        CHECK(q.stats().queue_full == 1, "full counted");
        FramePacket o;
        CHECK(q.TryPop(&o) && o.metadata.frame_id == 1, "fifo order");
        CHECK(q.PushFromCallback(rgb.data(), rgb.size(), Meta(2, 2, 3, 3)) ==
                  FrameDropReason::kAccepted,
              "space reusable after pop");
    }
    if (edge_test::failures == 0) {
        std::printf("capture_queue: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
