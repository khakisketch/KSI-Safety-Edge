#include "ksi_safety/perception/dfine_detector_port.hpp"

#include <cstddef>
#include <vector>

namespace ksi::safety {

DfineDetectorPort::DfineDetectorPort(const DfineConfig& config,
                                     DfineInferenceFn inference)
    : detector_(config, std::move(inference)) {}

DetectionBatch DfineDetectorPort::Detect(const FramePacket& frame) {
    DetectionBatch batch;
    const std::uint32_t width = frame.metadata.width;
    const std::uint32_t height = frame.metadata.height;
    const std::size_t expected =
        static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 3U;
    if (width == 0 || height == 0 || frame.rgb.size() < expected) {
        batch.status = DetectionStatus::kInferenceFailure;
        return batch;
    }
    std::vector<Detection> detections;
    if (!detector_.Detect(frame.rgb.data(), static_cast<int>(width),
                          static_cast<int>(height), &detections)) {
        batch.status = DetectionStatus::kInferenceFailure;
        return batch;
    }
    batch.status = DetectionStatus::kOk;
    batch.detections = std::move(detections);
    return batch;
}

}  // namespace ksi::safety
