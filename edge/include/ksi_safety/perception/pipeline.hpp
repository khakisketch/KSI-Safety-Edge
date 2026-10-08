// Perception pipeline: run detection once per frame and attach the
// time-match grade. Detection NEVER depends on the pose grade; spatial
// eligibility is a downstream gate (spatial_eligible is true only for
// kSourceVerified). No spatial computation happens here (later phases).

#ifndef KSI_SAFETY_PERCEPTION_PIPELINE_HPP
#define KSI_SAFETY_PERCEPTION_PIPELINE_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"

namespace ksi::safety {

struct PoseAttachment {
    PoseQuality quality = PoseQuality::kNone;
    PoseReason reason = PoseReason::kNoSamples;
};

struct PixelObservation {
    std::uint32_t frame_id = 0;
    std::string stream_id;
    std::int64_t receive_monotonic_ns = 0;
    std::string model_id;
    std::string model_digest;
    std::vector<Detection> detections;
    PoseAttachment pose;
    bool spatial_eligible = false;
};

class PerceptionPipeline {
public:
    explicit PerceptionPipeline(MatchPolicy policy);

    // The detector is called EXACTLY ONCE per Process call (when non-null).
    // A null detector yields empty detections without crashing; the pose is
    // still graded from the ring.
    PixelObservation Process(const FramePacket& frame, const FrameContext& ctx,
                             const TopicRing<PoseValue>& pose_ring,
                             DetectorPort* detector,
                             const ModelProfile& profile);

private:
    MatchPolicy policy_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_PIPELINE_HPP
