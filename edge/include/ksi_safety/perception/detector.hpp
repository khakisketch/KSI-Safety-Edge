// Detector abstraction: consumers (the W2 perception pipeline, the R1
// session runner) call Detect() EXACTLY ONCE per frame when they decide to
// run detection for that frame; matching/interpolation grades never gate
// the call itself.
//
// Evidence semantics (R1): "no attempt" and "attempt failed" are different
// from "attempt found nothing". `DetectionStatus` carries that distinction:
//   kNotRun            - no inference was attempted this tick (cadence skip
//                        or no detector bound): NO scene evidence.
//   kOk                - inference ran; `detections` (possibly empty) are
//                        valid evidence (an empty kOk batch is a clean
//                        negative).
//   kInferenceFailure  - an attempt failed (preprocess refusal, runtime
//                        failure, output mismatch): an observation gap, NOT
//                        a negative.
// Consumers must never turn kNotRun/kInferenceFailure into "no objects
// present" (tracker misses, evidence summaries, or coverage claims).

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

enum class DetectionStatus {
    kNotRun,
    kOk,
    kInferenceFailure,
};

const char* ToString(DetectionStatus status);

struct DetectionBatch {
    DetectionStatus status = DetectionStatus::kNotRun;
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
