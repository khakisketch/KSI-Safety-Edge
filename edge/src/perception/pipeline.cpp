#include "ksi_safety/perception/pipeline.hpp"

namespace ksi::safety {

PerceptionPipeline::PerceptionPipeline(MatchPolicy policy) : policy_(policy) {}

PixelObservation PerceptionPipeline::Process(const FramePacket& frame,
                                             const FrameContext& ctx,
                                             const TopicRing<PoseValue>& pose_ring,
                                             DetectorPort* detector,
                                             const ModelProfile& profile) {
    // Detection runs EXACTLY ONCE per frame and never depends on the pose
    // grade (principle: AI stays up when alignment fails). A null detector is
    // tolerated (empty detections) so pipeline wiring can be tested without a
    // model; the pose is still graded.
    DetectionBatch batch;
    if (detector != nullptr) {
        batch = detector->Detect(frame);
    }

    const MatchResult<PoseValue> match = MatchFrame(ctx, pose_ring, policy_);

    PixelObservation out;
    out.frame_id = ctx.frame_id;
    out.stream_id = ctx.stream_id;
    out.receive_monotonic_ns = ctx.receive_monotonic_ns;
    out.model_id = profile.model_id;
    out.model_digest = profile.model_digest;
    out.detections = std::move(batch.detections);
    out.pose.quality = match.quality;
    out.pose.reason = match.reason;
    // Only a source-verified bracket makes this frame eligible for spatial
    // reasoning. Nothing spatial is computed here (later phases).
    out.spatial_eligible = (match.quality == PoseQuality::kSourceVerified);
    return out;
}

}  // namespace ksi::safety
