#include "ksi_safety/perception/detector.hpp"

namespace ksi::safety {

const char* ToString(DetectionStatus status) {
    switch (status) {
        case DetectionStatus::kNotRun:
            return "NOT_RUN";
        case DetectionStatus::kOk:
            return "OK";
        case DetectionStatus::kInferenceFailure:
            return "INFERENCE_FAILURE";
    }
    return "UNKNOWN";
}

}  // namespace ksi::safety
