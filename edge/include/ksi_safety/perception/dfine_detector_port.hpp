// DfineDetectorPort: binds the W8 `DfineDetector` (preprocess -> injected
// inference -> decode) to the `DetectorPort` contract, so the R1 session
// runner drives the real D-FINE path through the same port test stubs use.
//
// Failure mapping (all fail-closed):
//   - `DfineDetector::Detect` true  -> kOk with the decoded detections
//     (empty = clean negative; still valid evidence).
//   - `DfineDetector::Detect` false -> kInferenceFailure with no detections.
//     This covers every path where no usable result exists: preprocess
//     refusal (F1-F5), runtime failure (F6), and output-size mismatch (F7).
//     A failed attempt is an observation gap, never a negative.
//   - A frame whose metadata/rgb cannot hold width*height*3 bytes is also
//     kInferenceFailure (defensive; capture ingress already drops malformed
//     frames).
//
// The port is a thin, stateless bridge: no thresholds, no clock, no I/O.

#ifndef KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_PORT_HPP
#define KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_PORT_HPP

#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/perception/dfine_detector.hpp"

namespace ksi::safety {

class DfineDetectorPort : public DetectorPort {
  public:
    DfineDetectorPort(const DfineConfig& config, DfineInferenceFn inference);

    DetectionBatch Detect(const FramePacket& frame) override;

  private:
    DfineDetector detector_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_DFINE_DETECTOR_PORT_HPP
