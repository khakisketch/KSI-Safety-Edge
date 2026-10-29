#include "ksi_safety/observation/source_roi.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace ksi::safety {
namespace {
bool RgbSize(std::uint32_t width, std::uint32_t height, std::size_t* out) {
    if (width == 0 || height == 0) return false;
    const auto max = std::numeric_limits<std::size_t>::max();
    if (static_cast<std::size_t>(width) > max / 3 / height) return false;
    *out = static_cast<std::size_t>(width) * height * 3;
    return true;
}
}  // namespace

bool DescribeSourceRoi(const TrackBox& box, std::uint32_t frame_width,
                       std::uint32_t frame_height, SourceRoi* out,
                       std::string* error) {
    if (out != nullptr) *out = SourceRoi{};
    const auto fail = [&](const char* reason) {
        if (error != nullptr) *error = reason;
        return false;
    };
    std::size_t frame_bytes = 0;
    if (out == nullptr || !RgbSize(frame_width, frame_height, &frame_bytes)) {
        return fail("invalid_source_dimensions");
    }
    if (!std::isfinite(box.x_min) || !std::isfinite(box.y_min) ||
        !std::isfinite(box.x_max) || !std::isfinite(box.y_max) ||
        box.x_max <= box.x_min || box.y_max <= box.y_min) {
        return fail("invalid_source_box");
    }
    // Convert through double AFTER clamp; even huge finite model coordinates
    // must not cause an undefined float-to-integer conversion.
    const double left = std::clamp(static_cast<double>(box.x_min), 0.0,
                                   static_cast<double>(frame_width));
    const double top = std::clamp(static_cast<double>(box.y_min), 0.0,
                                  static_cast<double>(frame_height));
    const double right = std::clamp(static_cast<double>(box.x_max), 0.0,
                                    static_cast<double>(frame_width));
    const double bottom = std::clamp(static_cast<double>(box.y_max), 0.0,
                                     static_cast<double>(frame_height));
    if (left >= right || top >= bottom) return fail("source_box_outside_frame");
    SourceRoi roi;
    roi.x = static_cast<std::uint32_t>(std::floor(left));
    roi.y = static_cast<std::uint32_t>(std::floor(top));
    roi.width = static_cast<std::uint32_t>(std::ceil(right)) - roi.x;
    roi.height = static_cast<std::uint32_t>(std::ceil(bottom)) - roi.y;
    if (!RgbSize(roi.width, roi.height, &roi.byte_size)) {
        return fail("invalid_crop_dimensions");
    }
    roi.clipped = left != box.x_min || top != box.y_min ||
                  right != box.x_max || bottom != box.y_max;
    *out = roi;
    if (error != nullptr) error->clear();
    return true;
}

bool CopySourceRoiRgb(const std::uint8_t* source, std::size_t source_size,
                      std::uint32_t frame_width, std::uint32_t frame_height,
                      const SourceRoi& roi, std::size_t max_bytes,
                      std::vector<std::uint8_t>* out, std::string* error) {
    if (out != nullptr) out->clear();
    const auto fail = [&](const char* reason) {
        if (error != nullptr) *error = reason;
        return false;
    };
    std::size_t frame_bytes = 0;
    if (source == nullptr || out == nullptr ||
        !RgbSize(frame_width, frame_height, &frame_bytes) || source_size != frame_bytes) {
        return fail("source_rgb_size_mismatch");
    }
    std::size_t crop_bytes = 0;
    if (!RgbSize(roi.width, roi.height, &crop_bytes) ||
        crop_bytes != roi.byte_size || roi.x >= frame_width || roi.y >= frame_height ||
        roi.width > frame_width - roi.x || roi.height > frame_height - roi.y) {
        return fail("invalid_crop_bounds");
    }
    if (max_bytes == 0 || crop_bytes > max_bytes) return fail("crop_byte_budget_exceeded");
    out->resize(crop_bytes);
    const std::size_t row_bytes = static_cast<std::size_t>(roi.width) * 3;
    for (std::uint32_t row = 0; row < roi.height; ++row) {
        const std::size_t offset =
            (static_cast<std::size_t>(roi.y + row) * frame_width + roi.x) * 3;
        std::memcpy(out->data() + static_cast<std::size_t>(row) * row_bytes,
                    source + offset, row_bytes);
    }
    if (error != nullptr) error->clear();
    return true;
}

}  // namespace ksi::safety
