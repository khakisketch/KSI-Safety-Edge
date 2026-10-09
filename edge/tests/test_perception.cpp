// perception: detector-once binding + pose-grade attachment tests.

#include "ksi_safety/perception/pipeline.hpp"

#include <cstdint>
#include <cstdio>

#include "support/check.hpp"

using ksi::safety::Detection;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::MatchPolicy;
using ksi::safety::ModelProfile;
using ksi::safety::PerceptionPipeline;
using ksi::safety::PixelObservation;
using ksi::safety::PoseQuality;
using ksi::safety::PoseReason;
using ksi::safety::PoseValue;
using ksi::safety::Quaternion;
using ksi::safety::Sample;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;
using ksi::safety::TopicRing;
using ksi::safety::Vec3;

namespace {

const std::int64_t kMs = 1000000LL;

class FakeDetector : public DetectorPort {
public:
    int calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        ++calls;
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        Detection a;
        a.x_min = 1.0F;
        a.y_min = 2.0F;
        a.x_max = 3.0F;
        a.y_max = 4.0F;
        a.score = 0.9F;
        a.class_id = 1;
        a.class_name = "person";
        Detection b = a;
        b.class_id = 2;
        b.class_name = "equipment";
        batch.detections.push_back(a);
        batch.detections.push_back(b);
        return batch;
    }
};

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

Sample<PoseValue> Mk(std::uint64_t id, std::int64_t recv_ns,
                     std::int64_t src_ns, bool verified) {
    Sample<PoseValue> s;
    s.sample_id = id;
    s.seq = id;
    s.received = Recv(recv_ns);
    s.has_source_time = true;
    s.source_time = Src(src_ns, verified);
    s.value.q = Quaternion{};
    s.value.position = Vec3{};
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

ModelProfile Profile() {
    ModelProfile m;
    m.model_id = "person-det-v1";
    m.model_digest = "sha256:abc123";
    m.backend = "fake";
    return m;
}

}  // namespace

int main() {
    // Empty telemetry ring -> detections still returned, pose kNone/kNoSamples.
    {
        PerceptionPipeline pipe(Policy());
        FakeDetector det;
        TopicRing<PoseValue> ring(8);
        FramePacket frame;
        PixelObservation o =
            pipe.Process(frame, Ctx(1500), ring, &det, Profile());
        CHECK(det.calls == 1, "detector called once on empty ring");
        CHECK(o.detections.size() == 2, "detections returned w/o telemetry");
        CHECK(o.detection_status == DetectionStatus::kOk,
              "ok status propagated");
        CHECK(o.pose.quality == PoseQuality::kNone, "empty pose none");
        CHECK(o.pose.reason == PoseReason::kNoSamples, "empty pose no-samples");
        CHECK(!o.spatial_eligible, "empty not spatial eligible");
        CHECK(o.model_id == "person-det-v1", "model id propagated");
        CHECK(o.model_digest == "sha256:abc123", "model digest propagated");
        CHECK(o.frame_id == 7 && o.stream_id == "cam0",
              "frame identity propagated");
    }
    // Dense receive-only case -> kReceiveNearest, still not eligible.
    {
        PerceptionPipeline pipe(Policy());
        FakeDetector det;
        TopicRing<PoseValue> ring(64);
        const std::int64_t step = 2500000LL;  // 400 Hz
        for (std::uint64_t i = 1; i <= 9; ++i) {
            Sample<PoseValue> s = Mk(i, static_cast<std::int64_t>(i) * step,
                                     static_cast<std::int64_t>(i) * step, true);
            ring.Push(s);
        }
        FrameContext c = Ctx(9 * step + 100000);
        c.has_exposure = false;
        FramePacket frame;
        PixelObservation o = pipe.Process(frame, c, ring, &det, Profile());
        CHECK(det.calls == 1, "detector called once on dense ring");
        CHECK(o.detections.size() == 2, "dense detections returned");
        CHECK(o.pose.quality == PoseQuality::kReceiveNearest,
              "dense pose receive-nearest");
        CHECK(o.pose.reason == PoseReason::kCaptureTimeUnverified,
              "dense reason capture-unverified");
        CHECK(!o.spatial_eligible, "dense not spatial eligible");
    }
    // Fully verified + bracket -> kSourceVerified + eligible.
    {
        PerceptionPipeline pipe(Policy());
        FakeDetector det;
        TopicRing<PoseValue> ring(8);
        ring.Push(Mk(1, 1000, 1000, true));
        ring.Push(Mk(2, 2000, 2000, true));
        FrameContext c = Ctx(1500);
        c.has_exposure = true;
        c.exposure = Src(1500, true);
        FramePacket frame;
        PixelObservation o = pipe.Process(frame, c, ring, &det, Profile());
        CHECK(det.calls == 1, "detector called once on verified");
        CHECK(o.pose.quality == PoseQuality::kSourceVerified,
              "verified pose source-verified");
        CHECK(o.pose.reason == PoseReason::kOk, "verified reason ok");
        CHECK(o.spatial_eligible, "verified spatial eligible");
    }
    // Detector call count == 1 per frame across frames; null detector safe.
    {
        PerceptionPipeline pipe(Policy());
        FakeDetector det;
        TopicRing<PoseValue> ring(8);
        FramePacket frame;
        for (int i = 0; i < 3; ++i) {
            pipe.Process(frame, Ctx(1500), ring, &det, Profile());
        }
        CHECK(det.calls == 3, "exactly one call per frame");
        PixelObservation o =
            pipe.Process(frame, Ctx(1500), ring, nullptr, Profile());
        CHECK(o.detections.empty(), "null detector -> empty detections");
        CHECK(o.detection_status == DetectionStatus::kNotRun,
              "null detector -> not-run status");
        CHECK(o.pose.quality == PoseQuality::kNone, "null-det pose still graded");
        CHECK(!o.spatial_eligible, "null-det not eligible on empty ring");
        CHECK(o.model_digest == "sha256:abc123",
              "model digest kept with null detector");
    }
    // Failure status passes through unchanged (R1 evidence semantics: a
    // failed attempt is an observation gap, never a negative).
    {
        class FailDetector : public DetectorPort {
        public:
            DetectionBatch Detect(const FramePacket&) override {
                DetectionBatch batch;
                batch.status = DetectionStatus::kInferenceFailure;
                return batch;
            }
        };
        PerceptionPipeline pipe(Policy());
        FailDetector fail;
        TopicRing<PoseValue> ring(8);
        FramePacket frame;
        PixelObservation o =
            pipe.Process(frame, Ctx(1500), ring, &fail, Profile());
        CHECK(o.detection_status == DetectionStatus::kInferenceFailure,
              "failure status passes through");
        CHECK(o.detections.empty(), "failure carries no detections");
    }
    if (edge_test::failures == 0) {
        std::printf("perception: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
