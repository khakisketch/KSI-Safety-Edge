// Readability arithmetic for the PPE gate (pure math, no policy inside).
//
// Role: turns (altitude, lens half-FOV, image width) into a ground sample
// distance, and a (target size, GSD) pair into the pixel span a reviewer
// would actually get. Whether that span is ENOUGH is the caller's policy
// decision (explicit min-pixels argument) — this unit holds no recognition
// threshold, no calibration, no camera claim. All inputs are caller data:
// no clock, no I/O, no RNG. Deterministic.
//
// Reference: docs/verification/2026-10-27-ppe-readability.md. The 8 px
// recognition bar used there is an analysis assumption, not a constant in
// this file.
#ifndef KSI_SAFETY_OBSERVATION_READABILITY_HPP
#define KSI_SAFETY_OBSERVATION_READABILITY_HPP

#include <cmath>
#include <cstddef>

namespace ksi::safety::observation {

// Pinhole ground sample distance: gsd = 2 * h * tan(half_fov) / width.
// False (and *out untouched-when-possible) on non-finite input, h <= 0,
// width_px <= 0, or half_fov outside (0, pi/2).
inline bool GroundSampleDistance(double altitude_m, double half_fov_rad,
                                 double image_width_px,
                                 double* gsd_m_per_px) {
    if (gsd_m_per_px == nullptr) return false;
    if (!std::isfinite(altitude_m) || !std::isfinite(half_fov_rad) ||
        !std::isfinite(image_width_px)) {
        return false;
    }
    if (altitude_m <= 0.0 || image_width_px <= 0.0) return false;
    if (half_fov_rad <= 0.0 || half_fov_rad >= 1.5707963267948966) {
        return false;
    }
    *gsd_m_per_px =
        2.0 * altitude_m * std::tan(half_fov_rad) / image_width_px;
    return std::isfinite(*gsd_m_per_px) && *gsd_m_per_px > 0.0;
}

// Pixel span of a known-size target at a GSD. False on non-finite input
// or gsd <= 0.
inline bool TargetPixels(double target_size_m, double gsd_m_per_px,
                         double* pixels_out) {
    if (pixels_out == nullptr) return false;
    if (!std::isfinite(target_size_m) || !std::isfinite(gsd_m_per_px)) {
        return false;
    }
    if (target_size_m <= 0.0 || gsd_m_per_px <= 0.0) return false;
    *pixels_out = target_size_m / gsd_m_per_px;
    return std::isfinite(*pixels_out);
}

// True iff the target spans at least min_pixels at this GSD. min_pixels is
// an explicit caller policy input (e.g. the analysis bar of 8 px lives with
// the caller, not here).
inline bool HasRecognitionDetail(double target_size_m, double gsd_m_per_px,
                                 double min_pixels) {
    if (!std::isfinite(min_pixels) || min_pixels <= 0.0) return false;
    double pixels = 0.0;
    if (!TargetPixels(target_size_m, gsd_m_per_px, &pixels)) return false;
    return pixels >= min_pixels;
}

}  // namespace ksi::safety::observation

#endif  // KSI_SAFETY_OBSERVATION_READABILITY_HPP
