// See dfine_detector.hpp for the failure-mode list (F1-F8) and the
// DetectorPort non-adaptation rationale.

#include "ksi_safety/perception/dfine_detector.hpp"

#include <cstddef>

namespace ksi::safety {

DfineDetector::DfineDetector(const DfineConfig& config,
                             DfineInferenceFn inference)
    : config_(config), inference_(inference) {}

bool DfineDetector::Detect(const std::uint8_t* rgb, int width, int height,
                           std::vector<Detection>* out) const {
    if (out == nullptr) {
        return false;  // F1.
    }
    out->clear();
    if (rgb == nullptr) {
        return false;  // F1.
    }
    if (width <= 0 || height <= 0) {
        return false;  // F2.
    }
    if (!IsValidDfineConfig(config_)) {
        return false;  // F3.
    }
    if (!static_cast<bool>(inference_)) {
        return false;  // F4.
    }
    const std::size_t plane = static_cast<std::size_t>(config_.input_width) *
                              static_cast<std::size_t>(config_.input_height);
    std::vector<float> input(plane * 3U);
    if (!PreprocessRgb(rgb, width, height, input.data(), config_)) {
        return false;  // F5.
    }
    std::vector<float> logits;
    std::vector<float> boxes;
    if (!inference_(input.data(), &logits, &boxes)) {
        return false;  // F6 (out already empty).
    }
    const std::size_t queries = static_cast<std::size_t>(config_.num_queries);
    const std::size_t classes = static_cast<std::size_t>(config_.num_classes);
    if (logits.size() != queries * classes ||
        boxes.size() != queries * 4U) {
        return false;  // F7/F8 (out already empty).
    }
    // Success: DecodeDfine cannot fail here (pointers non-null only if the
    // vectors are non-empty; dims/config already validated), and an empty
    // decode is a valid clean negative.
    *out = DecodeDfine(logits.empty() ? nullptr : logits.data(),
                       boxes.empty() ? nullptr : boxes.data(), width, height,
                       config_);
    return true;
}

}  // namespace ksi::safety
