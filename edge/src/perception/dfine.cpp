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

// PIL-compatible resampling coefficients (Pillow ImagingResample, BILINEAR
// filter): per output index, source center c=(d+0.5)*scale; triangle filter
// weights over a support of max(1, scale) (antialias-scaled when
// downscaling); the window is clipped to the image and the weights are
// renormalized over the clipped window. This mirrors RTDetrImageProcessor
// (which calls PIL `resize((640,640), BILINEAR)`).
struct AxisCoeffs {
    std::vector<int> first;              // first source index per dst index
    std::vector<std::vector<double>> w;  // normalized weights per dst index
};

AxisCoeffs PrecomputeCoeffs(int src, int dst) {
    AxisCoeffs out;
    out.first.resize(static_cast<std::size_t>(dst));
    out.w.resize(static_cast<std::size_t>(dst));
    const double scale = static_cast<double>(src) / static_cast<double>(dst);
    const double filterscale = scale < 1.0 ? 1.0 : scale;
    const double support = filterscale;  // triangle support 1.0 * filterscale
    for (int d = 0; d < dst; ++d) {
        const double center = (static_cast<double>(d) + 0.5) * scale;
        int lo = static_cast<int>(std::floor(center - support + 0.5));
        int hi = static_cast<int>(std::floor(center + support + 0.5));
        if (lo < 0) {
            lo = 0;
        }
        if (hi > src) {
            hi = src;
        }
        if (hi <= lo) {  // defensive; cannot happen for src >= 1
            lo = std::min(std::max(0, static_cast<int>(center)), src - 1);
            hi = lo + 1;
        }
        std::vector<double> w(static_cast<std::size_t>(hi - lo), 0.0);
        double sum = 0.0;
        for (int i = 0; i < hi - lo; ++i) {
            const double t =
                (static_cast<double>(lo + i) + 0.5 - center) / filterscale;
            double wt = 1.0 - std::fabs(t);
            if (wt < 0.0) {
                wt = 0.0;
            }
            w[static_cast<std::size_t>(i)] = wt;
            sum += wt;
        }
        if (sum != 0.0) {
            for (double& v : w) {
                v /= sum;
            }
        }
        out.first[static_cast<std::size_t>(d)] = lo;
        out.w[static_cast<std::size_t>(d)] = std::move(w);
    }
    return out;
}

// PIL's 8bpc pipeline clips intermediates to uint8 with round-half-up.
// Matching it keeps our tensor aligned with the validated reference
// preprocessing (golden tests pin actual PIL values; see dfine.hpp R7).
std::uint8_t Clip8(double v) {
    if (v <= 0.0) {
        return 0;
    }
    if (v >= 255.0) {
        return 255;
    }
    return static_cast<std::uint8_t>(std::floor(v + 0.5));
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
    // Identity fast path: at scale 1 the PIL path reduces to a single tap
    // with weight 1 (Clip8(v) == v), i.e. pure /255 + channel split. Exact.
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
    // PIL-compatible separable resample with a uint8 intermediate (PIL's
    // 8bpc pipeline quantizes the horizontal result before the vertical
    // pass; matching that keeps our tensor equal to the reference on the
    // pinned golden cases).
    const AxisCoeffs xc = PrecomputeCoeffs(src_width, dst_w);
    const AxisCoeffs yc = PrecomputeCoeffs(src_height, dst_h);
    std::vector<std::uint8_t> hbuf(static_cast<std::size_t>(src_height) *
                                   static_cast<std::size_t>(dst_w) * 3U);
    for (int y = 0; y < src_height; ++y) {
        const std::uint8_t* srow =
            rgb + static_cast<std::size_t>(y) *
                      static_cast<std::size_t>(src_width) * 3U;
        std::uint8_t* drow = hbuf.data() +
                             static_cast<std::size_t>(y) *
                                 static_cast<std::size_t>(dst_w) * 3U;
        for (int dx = 0; dx < dst_w; ++dx) {
            const std::size_t k = static_cast<std::size_t>(dx);
            const int x0 = xc.first[k];
            const std::vector<double>& w = xc.w[k];
            double r = 0.0;
            double g = 0.0;
            double b = 0.0;
            for (std::size_t i = 0; i < w.size(); ++i) {
                const std::size_t s = (static_cast<std::size_t>(x0) + i) * 3U;
                const double ww = w[i];
                r += ww * static_cast<double>(srow[s + 0]);
                g += ww * static_cast<double>(srow[s + 1]);
                b += ww * static_cast<double>(srow[s + 2]);
            }
            drow[k * 3U + 0] = Clip8(r);
            drow[k * 3U + 1] = Clip8(g);
            drow[k * 3U + 2] = Clip8(b);
        }
    }
    float* plane_r = out_chw;
    float* plane_g = out_chw + static_cast<std::size_t>(dst_w) *
                                    static_cast<std::size_t>(dst_h);
    float* plane_b = plane_g + static_cast<std::size_t>(dst_w) *
                                   static_cast<std::size_t>(dst_h);
    for (int dy = 0; dy < dst_h; ++dy) {
        const std::size_t k = static_cast<std::size_t>(dy);
        const int y0 = yc.first[k];
        const std::vector<double>& w = yc.w[k];
        const std::size_t dst_row = k * static_cast<std::size_t>(dst_w);
        for (int dx = 0; dx < dst_w; ++dx) {
            double r = 0.0;
            double g = 0.0;
            double b = 0.0;
            for (std::size_t i = 0; i < w.size(); ++i) {
                const std::size_t s = ((static_cast<std::size_t>(y0) + i) *
                                           static_cast<std::size_t>(dst_w) +
                                       static_cast<std::size_t>(dx)) * 3U;
                const double ww = w[i];
                r += ww * static_cast<double>(hbuf[s + 0]);
                g += ww * static_cast<double>(hbuf[s + 1]);
                b += ww * static_cast<double>(hbuf[s + 2]);
            }
            const std::size_t o = dst_row + static_cast<std::size_t>(dx);
            plane_r[o] = static_cast<float>(Clip8(r)) * kRescale;
            plane_g[o] = static_cast<float>(Clip8(g)) * kRescale;
            plane_b[o] = static_cast<float>(Clip8(b)) * kRescale;
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
