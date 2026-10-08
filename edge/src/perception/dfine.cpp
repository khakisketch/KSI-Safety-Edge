// See dfine.hpp for the rule list (R1-R8) and semantics provenance.

#include "ksi_safety/perception/dfine.hpp"

#include <cmath>
#include <cstddef>
#include <vector>

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
    constexpr float kRescale = 1.0F / 255.0F;
    // Speed structure (same float32 sampling math as before): per-column
    // x0/x1/fx depend only on dx, per-row y0/y1/fy (+ row base offsets) only
    // on dy, so each is computed once. A single dy/dx pass then reuses the
    // base source index for all 3 channels (better locality than the old
    // channel-outer loop, which recomputed geometry 3x). The identity-size
    // fast path below is exact: with scale 1, (d+0.5)*1-0.5 == d gives
    // fx=fy=0 and x0=x1=dx, y0=y1=dy, i.e. pure /255 + channel split.
    if (src_width == dst_w && src_height == dst_h) {
        float* plane_r = out_chw;
        float* plane_g = out_chw + static_cast<std::size_t>(dst_w) *
                                        static_cast<std::size_t>(dst_h);
        float* plane_b = plane_g + static_cast<std::size_t>(dst_w) *
                                       static_cast<std::size_t>(dst_h);
        const std::size_t pixels = static_cast<std::size_t>(dst_w) *
                                   static_cast<std::size_t>(dst_h);
        for (std::size_t i = 0; i < pixels; ++i) {
            const std::size_t s = i * 3;
            plane_r[i] = static_cast<float>(rgb[s + 0]) * kRescale;
            plane_g[i] = static_cast<float>(rgb[s + 1]) * kRescale;
            plane_b[i] = static_cast<float>(rgb[s + 2]) * kRescale;
        }
        return true;
    }
    const float scale_x = static_cast<float>(src_width) / static_cast<float>(dst_w);
    const float scale_y = static_cast<float>(src_height) / static_cast<float>(dst_h);
    std::vector<int> x0_of(dst_w), x1_of(dst_w);
    std::vector<float> fx_of(dst_w);
    for (int dx = 0; dx < dst_w; ++dx) {
        const float sx = (static_cast<float>(dx) + 0.5F) * scale_x - 0.5F;
        int x0 = static_cast<int>(std::floor(sx));
        float fx = sx - static_cast<float>(x0);
        if (x0 < 0) {
            x0 = 0;
            fx = 0.0F;
        } else if (x0 >= src_width - 1) {
            x0 = src_width - 1;
            fx = 0.0F;
        }
        x0_of[static_cast<std::size_t>(dx)] = x0;
        x1_of[static_cast<std::size_t>(dx)] =
            (x0 + 1 <= src_width - 1) ? x0 + 1 : x0;
        fx_of[static_cast<std::size_t>(dx)] = fx;
    }
    std::vector<int> y0_of(dst_h), y1_of(dst_h);
    std::vector<float> fy_of(dst_h);
    std::vector<std::size_t> row0_base(dst_h), row1_base(dst_h);
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
        y0_of[static_cast<std::size_t>(dy)] = y0;
        y1_of[static_cast<std::size_t>(dy)] = y1;
        fy_of[static_cast<std::size_t>(dy)] = fy;
        row0_base[static_cast<std::size_t>(dy)] =
            static_cast<std::size_t>(y0) * static_cast<std::size_t>(src_width);
        row1_base[static_cast<std::size_t>(dy)] =
            static_cast<std::size_t>(y1) * static_cast<std::size_t>(src_width);
    }
    float* plane_r = out_chw;
    float* plane_g = out_chw + static_cast<std::size_t>(dst_w) *
                                    static_cast<std::size_t>(dst_h);
    float* plane_b = plane_g + static_cast<std::size_t>(dst_w) *
                                   static_cast<std::size_t>(dst_h);
    for (int dy = 0; dy < dst_h; ++dy) {
        const std::size_t d = static_cast<std::size_t>(dy);
        const float fy = fy_of[d];
        const std::size_t r0 = row0_base[d];
        const std::size_t r1 = row1_base[d];
        const std::size_t dst_row = d * static_cast<std::size_t>(dst_w);
        for (int dx = 0; dx < dst_w; ++dx) {
            const std::size_t k = static_cast<std::size_t>(dx);
            const std::size_t x0 = static_cast<std::size_t>(x0_of[k]);
            const std::size_t x1 = static_cast<std::size_t>(x1_of[k]);
            const float fx = fx_of[k];
            const std::size_t b00 = (r0 + x0) * 3;
            const std::size_t b10 = (r0 + x1) * 3;
            const std::size_t b01 = (r1 + x0) * 3;
            const std::size_t b11 = (r1 + x1) * 3;
            const std::size_t o = dst_row + k;
            for (int c = 0; c < 3; ++c) {
                const std::size_t cc = static_cast<std::size_t>(c);
                const float v00 = static_cast<float>(rgb[b00 + cc]);
                const float v10 = static_cast<float>(rgb[b10 + cc]);
                const float v01 = static_cast<float>(rgb[b01 + cc]);
                const float v11 = static_cast<float>(rgb[b11 + cc]);
                const float top = v00 + (v10 - v00) * fx;
                const float bottom = v01 + (v11 - v01) * fx;
                const float v = (top + (bottom - top) * fy) * kRescale;
                if (c == 0) {
                    plane_r[o] = v;
                } else if (c == 1) {
                    plane_g[o] = v;
                } else {
                    plane_b[o] = v;
                }
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
