// Original-source RGB24 ROI extraction. No resize, enhancement, model or device command.
#ifndef KSI_SAFETY_OBSERVATION_SOURCE_ROI_HPP
#define KSI_SAFETY_OBSERVATION_SOURCE_ROI_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/perception/tracker.hpp"

namespace ksi::safety {

struct SourceRoi {
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::size_t byte_size = 0;
    bool clipped = false;
};

// Bounds are floor(min)/ceil(max), clipped to the ORIGINAL frame. A nonempty
// ROI means only addressable source pixels, never PPE visibility or safety.
bool DescribeSourceRoi(const TrackBox& box, std::uint32_t frame_width,
                       std::uint32_t frame_height, SourceRoi* out,
                       std::string* error);

// Validates exact source length and the caller's byte cap BEFORE allocation.
// Refusal clears output. Byte-exact row copies, no invented/resized pixels.
bool CopySourceRoiRgb(const std::uint8_t* source, std::size_t source_size,
                      std::uint32_t frame_width, std::uint32_t frame_height,
                      const SourceRoi& roi, std::size_t max_bytes,
                      std::vector<std::uint8_t>* out, std::string* error);

}  // namespace ksi::safety
#endif
