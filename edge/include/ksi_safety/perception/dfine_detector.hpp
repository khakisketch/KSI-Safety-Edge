// D-FINE runtime adapter (host-tested, injected inference only).
//
// Role: bind the W4 `PreprocessRgb` + injected model inference +
// `DecodeDfine` stages into one fail-closed `Detect()` call, so the W8
// full-chain test can drive detector -> tracker -> session on synthetic
// frames without any real runtime.
//
// The inference function is caller-injected (`DfineInferenceFn`): this
// module performs NO ONNX/TensorRT execution. The real runtime adapter
// (ONNX-gated) is a later step; see the README limits.
//
// DetectorPort note: this adapter is intentionally STANDALONE and does not
// implement `DetectorPort`. That port takes a `FramePacket` (capture/time
// types) and returns a `DetectionBatch` under exactly-once pipeline call
// semantics; binding this adapter to it would drag capture/time headers
// into a module whose only job is RGB -> Detection maths, and would add
// no host-testable behaviour (the pipeline already tests its own call
// discipline with stub ports). A thin `DetectorPort` wrapper can be added
// when the capture path lands.
//
// Failure modes (ALL fail-closed: return false with `*out` left empty,
// never partial output, never an exception, no I/O):
//   F1. Null `rgb` or null `out` pointer.
//   F2. Non-positive `width` or `height`.
//   F3. Invalid `DfineConfig` (`!IsValidDfineConfig`).
//   F4. Empty inference function (no target bound).
//   F5. `PreprocessRgb` failure (implies F1/F2/F3, plus any internal
//       preprocessing refusal).
//   F6. Inference returns false (model/runtime failure signal).
//   F7. Output-size mismatch: `logits.size() !=
//       num_queries * num_classes`, or `boxes.size() != num_queries * 4`.
//   F8. Null `logits`/`boxes` vectors are empty (covered by F7 unless the
//       config expects zero elements, which F3 already rejects).
//
// Success (`true`) covers both "detections found" and "no detection above
// threshold" (empty `*out`): an empty decode is a valid negative, not a
// failure. Deterministic: no RNG, no clock, no I/O; identical inputs
// (pixels, config, inference outputs) produce identical outputs.

#ifndef KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_HPP
#define KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_HPP

#include <cstdint>
#include <functional>
#include <vector>

#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/perception/dfine.hpp"

namespace ksi::safety {

// Raw model inference behind the adapter. Input: preprocessed CHW float32
// of exactly 1 x 3 x config.input_height x config.input_width (R plane,
// then G, then B, as produced by `PreprocessRgb`). Outputs (caller-filled):
//   logits: [config.num_queries x config.num_classes] row-major raw logits.
//   boxes:  [config.num_queries x 4] row-major normalized (cx, cy, w, h).
// Returns false on model/runtime failure (adapter failure mode F6).
using DfineInferenceFn = std::function<bool(
    const float* input_chw, std::vector<float>* logits,
    std::vector<float>* boxes)>;

class DfineDetector {
  public:
    DfineDetector(const DfineConfig& config, DfineInferenceFn inference);

    // Run preprocess -> inference -> decode for one HWC uint8 RGB frame of
    // `width` x `height`. See F1-F8 above for every false path (all leave
    // `*out` empty). On success `*out` holds the `DecodeDfine` output in
    // ascending query order (possibly empty: a clean negative).
    bool Detect(const std::uint8_t* rgb, int width, int height,
                std::vector<Detection>* out) const;

  private:
    DfineConfig config_;
    DfineInferenceFn inference_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_HPP
