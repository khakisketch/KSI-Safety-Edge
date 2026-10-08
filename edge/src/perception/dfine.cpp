// See dfine.hpp for the rule list (R1-R8) and semantics provenance.

#include "ksi_safety/perception/dfine.hpp"

#include <cmath>
#include <cstddef>

namespace ksi::safety {
namespace {

float Sigmoid(float value) {
    // Stable both tails; sigmoid(0) == 0.5 exactly in float32.
    if (value >= 0.0F) {
        const float e = std::exp(-value);
        return 1.0F / (1.0F + e);
    }
    const float e = std::exp(value);
    return e / (1.0F + e);
}

}  // namespace

bool IsValidDfineConfig(const DfineConfig& config) {
    return config.num_classes > 0 && config.num_queries > 0 &&
           config.input_width > 0 && config.input_height > 0 &&
           std::isfinite(config.score_threshold) &&
           config.score_threshold >= 0.0F && config.score_threshold <= 1.0F;
}

bool PreprocessRgb(const std::uint8_t* rgb, int src_width, int src_height,
                   float* out_chw, const DfineConfig& config) {
    if (rgb == nullptr || out_chw == nullptr) {
        return false;
    }
    if (src_width <= 0 || src_height <= 0 || !IsValidDfineConfig(config)) {
        return false;
    }
    const int dst_w = config.input_width;
    const int dst_h = config.input_height;
    const float scale_x = static_cast<float>(src_width) / static_cast<float>(dst_w);
    const float scale_y = static_cast<float>(src_height) / static_cast<float>(dst_h);
    constexpr float kRescale = 1.0F / 255.0F;
    for (int c = 0; c < 3; ++c) {
        float* plane = out_chw + static_cast<std::size_t>(c) *
                                        static_cast<std::size_t>(dst_w) *
                                        static_cast<std::size_t>(dst_h);
        for (int dy = 0; dy < dst_h; ++dy) {
            const float sy = (static_cast<float>(dy) + 0.5F) * scale_y - 0.5F;
            int y0 = static_cast<int>(std::floor(sy));
            float fy = sy - static_cast<float>(y0);
            if (y0 < 0) {
                y0 = 0;
                fy = 0.0F;
            } else if (y0 >= src_height - 1) {
                y0 = src_height - 1;
                fy = 0.0F;
            }
            const int y1 = (y0 + 1 <= src_height - 1) ? y0 + 1 : y0;
            for (int dx = 0; dx < dst_w; ++dx) {
                const float sx =
                    (static_cast<float>(dx) + 0.5F) * scale_x - 0.5F;
                int x0 = static_cast<int>(std::floor(sx));
                float fx = sx - static_cast<float>(x0);
                if (x0 < 0) {
                    x0 = 0;
                    fx = 0.0F;
                } else if (x0 >= src_width - 1) {
                    x0 = src_width - 1;
                    fx = 0.0F;
                }
                const int x1 = (x0 + 1 <= src_width - 1) ? x0 + 1 : x0;
                const std::size_t r00 = (static_cast<std::size_t>(y0) *
                                             static_cast<std::size_t>(src_width) +
                                         static_cast<std::size_t>(x0)) *
                                            static_cast<std::size_t>(3) +
                                        static_cast<std::size_t>(c);
                const std::size_t r10 = (static_cast<std::size_t>(y0) *
                                             static_cast<std::size_t>(src_width) +
                                         static_cast<std::size_t>(x1)) *
                                            static_cast<std::size_t>(3) +
                                        static_cast<std::size_t>(c);
                const std::size_t r01 = (static_cast<std::size_t>(y1) *
                                             static_cast<std::size_t>(src_width) +
                                         static_cast<std::size_t>(x0)) *
                                            static_cast<std::size_t>(3) +
                                        static_cast<std::size_t>(c);
                const std::size_t r11 = (static_cast<std::size_t>(y1) *
                                             static_cast<std::size_t>(src_width) +
                                         static_cast<std::size_t>(x1)) *
                                            static_cast<std::size_t>(3) +
                                        static_cast<std::size_t>(c);
                const float v00 = static_cast<float>(rgb[r00]);
                const float v10 = static_cast<float>(rgb[r10]);
                const float v01 = static_cast<float>(rgb[r01]);
                const float v11 = static_cast<float>(rgb[r11]);
                const float top = v00 + (v10 - v00) * fx;
                const float bottom = v01 + (v11 - v01) * fx;
                plane[static_cast<std::size_t>(dy) *
                          static_cast<std::size_t>(dst_w) +
                      static_cast<std::size_t>(dx)] =
                    (top + (bottom - top) * fy) * kRescale;
            }
        }
    }
    return true;
}

std::vector<Detection> DecodeDfine(const float* logits, const float* boxes,
                                    int src_width, int src_height,
                                    const DfineConfig& config) {
    std::vector<Detection> out;
    if (logits == nullptr || boxes == nullptr) {
        return out;
    }
    if (src_width <= 0 || src_height <= 0 || !IsValidDfineConfig(config)) {
        return out;
    }
    const float w = static_cast<float>(src_width);
    const float h = static_cast<float>(src_height);
    const std::size_t classes = static_cast<std::size_t>(config.num_classes);
    const std::size_t queries = static_cast<std::size_t>(config.num_queries);
    for (std::size_t q = 0; q < queries; ++q) {
        const float* row = logits + q * classes;
        // R1+R2: per-query sigmoid + argmax (first maximum wins ties).
        float best = Sigmoid(row[0]);
        std::size_t best_c = 0;
        for (std::size_t c = 1; c < classes; ++c) {
            const float s = Sigmoid(row[c]);
            if (s > best) {
                best = s;
                best_c = c;
            }
        }
        // R3: strict threshold from config only.
        if (!(best > config.score_threshold)) {
            continue;
        }
        // R4: normalized cxcywh -> source-pixel xyxy, no clamping.
        const float* box = boxes + q * 4U;
        const float cx = box[0];
        const float cy = box[1];
        const float bw = box[2];
        const float bh = box[3];
        Detection det;
        det.x_min = (cx - bw * 0.5F) * w;
        det.y_min = (cy - bh * 0.5F) * h;
        det.x_max = (cx + bw * 0.5F) * w;
        det.y_max = (cy + bh * 0.5F) * h;
        det.score = best;
        det.class_id = static_cast<std::uint32_t>(best_c);
        // R6: class_name intentionally empty (ontology mapping out of scope).
        out.push_back(det);
    }
    return out;
}

}  // namespace ksi::safety
