// GroundEstimator (R4): turn FRESH tracks + a verified, interpolated pose
// into the session's GroundObservation inputs, using the existing W3
// projection and spatial kernels.
//
// Contract (all fail-closed — a withheld observation is never an estimate):
//   - Eligibility: attitude AND position must both be kSourceVerified (a
//     verified same-domain bracket around the frame's exposure) AND the
//     frame must carry a verified exposure. Anything less yields an
//     ineligible estimate: detection/tracking stay intact, spatial is
//     withheld (the user-mandated "keep Detection, withhold Spatial" rule).
//   - Pose at exposure: SLERP (attitude) + LERP (position) over the
//     verified bracket at the exposure source time. The attitude ring is
//     interpreted as camera->ENU orientation and the position ring as ENU
//     meters (the spatial pose contract for these rings); composing raw
//     PSDK topics into that pose is the (deferred, H1-gated) adapter's job.
//   - Person: the footpoint is the bbox bottom-center pixel projected to
//     the ground plane (GEO_ESTIMATE — an estimate, never METRIC_VERIFIED).
//   - Equipment: the footprint is the ground projection of the bbox bottom
//     edge (left/center/right). It is an ESTIMATE of the ground contact
//     line — the bbox is not claimed to be the real footprint.
//   - A track whose class is not mapped, or whose projection is refused
//     (bad pixel / near horizon / no intersection), is recorded WITHHELD
//     with its reason; nothing is fabricated.
//
// Only FRESH (observed-this-tick) tracks are estimated; held/missed tracks
// are the caller's to exclude (they are not new measurements). Pure logic:
// no clock, no I/O, no RNG; deterministic.

#ifndef KSI_SAFETY_SPATIAL_GROUND_ESTIMATOR_HPP
#define KSI_SAFETY_SPATIAL_GROUND_ESTIMATOR_HPP

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "ksi_safety/geometry/projection.hpp"
#include "ksi_safety/perception/tracker.hpp"
#include "ksi_safety/session/session.hpp"
#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"

namespace ksi::safety {

// Class mapping: which detection class is a person, and which classes are
// equipment (id -> class name used by risk rules). Caller-stated only.
struct SpatialClassMap {
    std::uint32_t person_class_id = 0;
    std::vector<std::pair<std::uint32_t, std::string>> equipment_classes;
};

struct SpatialEstimatorConfig {
    SpatialClassMap classes;
    CameraIntrinsics intrinsics;
    GroundPlaneRevision ground;
    // Horizon guard forwarded to ProjectPixelToGround (caller-stated).
    double min_ray_z = 0.0;
};

// Why a per-track spatial record carries no observation.
enum class SpatialWithholdReason {
    kOk,                 // has_observation = true
    kUnmappedClass,      // class id not in the map
    kProjectionRefused,  // bad pixel / near horizon / no ground intersection
};

const char* ToString(SpatialWithholdReason reason);

struct SpatialObservationRecord {
    std::uint64_t track_id = 0;
    std::uint32_t class_id = 0;
    bool has_observation = false;
    SpatialWithholdReason reason = SpatialWithholdReason::kUnmappedClass;
    // Filled when has_observation (session input shape):
    GroundObservation observation;
};

struct SpatialEstimate {
    // False when the tick is not spatially eligible (pose/exposure
    // unverified): records stays empty and the caller must withhold.
    bool eligible = false;
    std::vector<SpatialObservationRecord> records;  // one per input track
};

class GroundEstimator {
  public:
    explicit GroundEstimator(const SpatialEstimatorConfig& config);

    // `tracks` must contain ONLY fresh (observed-this-tick) tracks.
    // `attitude`/`position` are the tick's match results; `ctx` carries the
    // exposure. frame_width/height are the SOURCE frame pixels.
    SpatialEstimate Estimate(const std::vector<Track>& tracks,
                             const MatchResult<Quaternion>& attitude,
                             const MatchResult<Vec3>& position,
                             const FrameContext& ctx, std::uint32_t frame_width,
                             std::uint32_t frame_height) const;

  private:
    SpatialEstimatorConfig config_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_SPATIAL_GROUND_ESTIMATOR_HPP
