// Detector abstraction: the perception pipeline calls Detect() EXACTLY ONCE
// per frame, regardless of telemetry presence or staleness. Implementations
// (a future detector adapter) stay behind this port; matching/interpolation
// grades never gate the call.

#ifndef KSI_SAFETY_PERCEPTION_DETECTOR_HPP
#define KSI_SAFETY_PERCEPTION_DETECTOR_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/capture/frame_queue.hpp"

namespace ksi::safety {

struct Detection {
    float x_min = 0.0F;
    float y_min = 0.0F;
    float x_max = 0.0F;
    float y_max = 0.0F;
    float score = 0.0F;
    std::uint32_t class_id = 0;
    std::string class_name;
};

struct DetectionBatch {
    std::vector<Detection> detections;
};

struct ModelProfile {
    std::string model_id;
    std::string model_digest;
    std::string backend;
};

class DetectorPort {
public:
    virtual ~DetectorPort() = default;
    virtual DetectionBatch Detect(const FramePacket& frame) = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_DETECTOR_HPP
