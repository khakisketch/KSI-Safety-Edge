// D-FINE input preprocessing + output decode (host-tested, no inference here).
//
// Semantics source (READ-ONLY, not modified):
//   SI dfine.py: Detect via RTDetrImageProcessor (resize 640x640, rescale
//   1/255, do_normalize=false, do_pad=false) + transformers
//   post_process_object_detection with threshold = score_floor
//   (DEFAULT_SCORE_FLOOR = 0.05, a diagnostic noise floor; real filtering is
//   the adapter's min_confidence, which lives outside this module).
//
// Implemented post-processing rules (kept diffable against the reference):
//   R1. Per-query sigmoid: score(q,c) = sigmoid(logit(q,c)) for every query q
//       and class c. Numerically stable sigmoid; ties in argmax resolve to
//       the lowest class index (first maximum wins, deterministic).
//   R2. Per-query best class: label(q) = argmax_c score(q,c). There is NO
//       global top-k over (query, class) pairs and NO NMS — D-FINE is NMS-free
//       with a fixed query count (default 300).
//   R3. Threshold filter: query q is kept iff score(q,label(q)) STRICTLY
//       exceeds config.score_threshold (mirrors transformers'
//       `keep = scores > threshold`). The threshold comes ONLY from
//       DfineConfig; no literal threshold appears in the decode logic.
//   R4. Box conversion: raw boxes are normalized cxcywh; output xyxy in
//       SOURCE pixels is x_min=(cx-w/2)*src_w, y_min=(cy-h/2)*src_h,
//       x_max=(cx+w/2)*src_w, y_max=(cy+h/2)*src_h. No clamping here —
//       clipping/validation is the (out-of-scope) adapter's job.
//   R5. Ordering: outputs preserve ascending query order (the reference zip
//       order). Scores are NOT sorted.
//   R6. Label id: raw COCO class index (argmax position). Ontology/class-map
//       mapping is NOT in scope, so Detection.class_name is left empty.
//   R7. Preprocessing: PIL-compatible resampling HWC uint8 RGB -> input_w x
//       input_h (do_pad=false means NO letterbox padding — aspect ratio is
//       NOT preserved), rescale /255, planar CHW float32 (R plane, then G,
//       then B). do_normalize=false means NO mean/std normalization.
//       Resampling mirrors PIL/RTDetrImageProcessor geometry: per output
//       pixel, source center c=(d+0.5)*scale; triangle (BILINEAR) weights
//       over a support of max(1, scale) (antialias-scaled on downscale);
//       the window is clipped to the image and weights renormalized. Like
//       PIL's 8bpc pipeline, the horizontal intermediate is quantized to
//       uint8 (round-half-up, [0,255] clip) before the vertical pass.
//       Golden values generated from actual PIL pin this in
//       tests/test_dfine.cpp. Identity-size input keeps a direct /255 fast
//       path (provably equivalent: single tap, Clip8(v) == v). Deterministic.
//   R8. Failure posture: null pointers / non-positive dimensions / invalid
//       config yield an empty vector (Decode) or false (PreprocessRgb) —
//       never an exception, never partial output.
//
// No ONNX/TensorRT execution here: Decode consumes caller-supplied tensors.
// Conformance against real model outputs is deferred (see README limits).

#ifndef KSI_SAFETY_PERCEPTION_DFINE_HPP
#define KSI_SAFETY_PERCEPTION_DFINE_HPP

#include <cstdint>
#include <vector>

#include "ksi_safety/perception/detector.hpp"

namespace ksi::safety {

// D-FINE-small-COCO defaults mirror the model preprocessor config
// (640x640, rescale 1/255, no normalize, no pad) and dfine.py's
// DEFAULT_SCORE_FLOOR (0.05). All are overridable per call site.
struct DfineConfig {
    int num_classes = 80;
    int num_queries = 300;
    int input_width = 640;
    int input_height = 640;
    float score_threshold = 0.05F;
};

// True iff dimensions/counts are positive and threshold is a finite
// probability in [0, 1].
bool IsValidDfineConfig(const DfineConfig& config);

// PIL-compatible resample + /255 rescale + RGB->CHW planarize into
// out_chw[3 * config.input_height * config.input_width] (R plane, G, B).
// rgb is HWC uint8 RGB of src_width x src_height. Direct resize: no padding.
// Returns false (no bytes written) on null pointers, non-positive dimensions,
// or invalid config.
bool PreprocessRgb(const std::uint8_t* rgb, int src_width, int src_height,
                   float* out_chw, const DfineConfig& config = DfineConfig{});

// Decode raw model tensors to source-pixel detections (rules R1-R6 above).
// logits: [config.num_queries x config.num_classes] row-major raw logits.
// boxes:  [config.num_queries x 4] row-major normalized (cx, cy, w, h).
// src_width/src_height: ORIGINAL image size for box rescaling (target_sizes).
// Returns empty on null pointers, non-positive source size, or invalid config.
std::vector<Detection> DecodeDfine(const float* logits, const float* boxes,
                                    int src_width, int src_height,
                                    const DfineConfig& config = DfineConfig{});

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_DFINE_HPP
