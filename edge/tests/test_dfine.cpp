// dfine: D-FINE preprocessing + output decode on constructed tensors.
// Hand-computed sigmoid/argmax/box cases, threshold filtering (incl. strict
// `>` boundary), query-count handling + order preservation (no sort, no NMS),
// preprocessing channel order / dims / gradient sanity, determinism, null and
// invalid-config posture, and proof the configured threshold is used.

#include "ksi_safety/perception/dfine.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "support/check.hpp"

using ksi::safety::DecodeDfine;
using ksi::safety::DfineConfig;
using ksi::safety::IsValidDfineConfig;
using ksi::safety::PreprocessRgb;

namespace {

bool Near(float a, float b, float tol) {
    return std::fabs(a - b) <= tol;
}

DfineConfig SmallConfig(float threshold) {
    DfineConfig cfg;
    cfg.num_classes = 3;
    cfg.num_queries = 2;
    cfg.score_threshold = threshold;
    return cfg;
}

// PIL-faithful reference resample (matches Pillow's 8bpc BILINEAR path):
// triangle weights over a max(1, scale) support, window clipped to the
// image and normalized; the horizontal intermediate is quantized to uint8
// (round-half-up, [0,255]) before the vertical pass. Independent loop
// structure of the same algorithm; used for equivalence checks.
struct RefCoeffs {
    std::vector<int> first;
    std::vector<std::vector<double>> w;
};

RefCoeffs RefPrecompute(int src, int dst) {
    RefCoeffs out;
    const double scale = static_cast<double>(src) / static_cast<double>(dst);
    const double fs = scale < 1.0 ? 1.0 : scale;
    for (int d = 0; d < dst; ++d) {
        const double center = (static_cast<double>(d) + 0.5) * scale;
        int lo = static_cast<int>(std::floor(center - fs + 0.5));
        int hi = static_cast<int>(std::floor(center + fs + 0.5));
        if (lo < 0) {
            lo = 0;
        }
        if (hi > src) {
            hi = src;
        }
        if (hi <= lo) {
            lo = std::min(std::max(0, static_cast<int>(center)), src - 1);
            hi = lo + 1;
        }
        std::vector<double> w;
        double sum = 0.0;
        for (int i = lo; i < hi; ++i) {
            double wt = 1.0 -
                        std::fabs((static_cast<double>(i) + 0.5 - center) / fs);
            if (wt < 0.0) {
                wt = 0.0;
            }
            w.push_back(wt);
            sum += wt;
        }
        if (sum != 0.0) {
            for (double& v : w) {
                v /= sum;
            }
        }
        out.first.push_back(lo);
        out.w.push_back(std::move(w));
    }
    return out;
}

std::uint8_t RefClip8(double v) {
    if (v <= 0.0) {
        return 0;
    }
    if (v >= 255.0) {
        return 255;
    }
    return static_cast<std::uint8_t>(std::floor(v + 0.5));
}

void ReferenceResample(const std::uint8_t* rgb, int src_w, int src_h,
                       float* out_chw, int dst_w, int dst_h) {
    constexpr float kRescale = 1.0F / 255.0F;
    const RefCoeffs xc = RefPrecompute(src_w, dst_w);
    const RefCoeffs yc = RefPrecompute(src_h, dst_h);
    std::vector<std::uint8_t> hbuf(static_cast<std::size_t>(src_h) *
                                   static_cast<std::size_t>(dst_w) * 3U);
    for (int y = 0; y < src_h; ++y) {
        const std::uint8_t* srow =
            rgb + static_cast<std::size_t>(y) *
                      static_cast<std::size_t>(src_w) * 3U;
        std::uint8_t* drow = hbuf.data() +
                             static_cast<std::size_t>(y) *
                                 static_cast<std::size_t>(dst_w) * 3U;
        for (int dx = 0; dx < dst_w; ++dx) {
            const int x0 = xc.first[static_cast<std::size_t>(dx)];
            const std::vector<double>& w = xc.w[static_cast<std::size_t>(dx)];
            double r = 0.0;
            double g = 0.0;
            double b = 0.0;
            for (std::size_t i = 0; i < w.size(); ++i) {
                const std::size_t s = (static_cast<std::size_t>(x0) + i) * 3U;
                r += w[i] * static_cast<double>(srow[s + 0]);
                g += w[i] * static_cast<double>(srow[s + 1]);
                b += w[i] * static_cast<double>(srow[s + 2]);
            }
            drow[static_cast<std::size_t>(dx) * 3U + 0] = RefClip8(r);
            drow[static_cast<std::size_t>(dx) * 3U + 1] = RefClip8(g);
            drow[static_cast<std::size_t>(dx) * 3U + 2] = RefClip8(b);
        }
    }
    const std::size_t plane = static_cast<std::size_t>(dst_w) *
                              static_cast<std::size_t>(dst_h);
    for (int dy = 0; dy < dst_h; ++dy) {
        const int y0 = yc.first[static_cast<std::size_t>(dy)];
        const std::vector<double>& w = yc.w[static_cast<std::size_t>(dy)];
        for (int dx = 0; dx < dst_w; ++dx) {
            double r = 0.0;
            double g = 0.0;
            double b = 0.0;
            for (std::size_t i = 0; i < w.size(); ++i) {
                const std::size_t s = ((static_cast<std::size_t>(y0) + i) *
                                           static_cast<std::size_t>(dst_w) +
                                       static_cast<std::size_t>(dx)) * 3U;
                r += w[i] * static_cast<double>(hbuf[s + 0]);
                g += w[i] * static_cast<double>(hbuf[s + 1]);
                b += w[i] * static_cast<double>(hbuf[s + 2]);
            }
            const std::size_t o = static_cast<std::size_t>(dy) *
                                      static_cast<std::size_t>(dst_w) +
                                  static_cast<std::size_t>(dx);
            out_chw[o] = static_cast<float>(RefClip8(r)) * kRescale;
            out_chw[plane + o] = static_cast<float>(RefClip8(g)) * kRescale;
            out_chw[2U * plane + o] =
                static_cast<float>(RefClip8(b)) * kRescale;
        }
    }
}

DfineConfig SizedConfig(int w, int h) {
    DfineConfig cfg;
    cfg.num_classes = 3;
    cfg.num_queries = 2;
    cfg.input_width = w;
    cfg.input_height = h;
    return cfg;
}

void FillPattern(std::vector<std::uint8_t>& rgb, int w, int h) {
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t b = (static_cast<std::size_t>(y) *
                                       static_cast<std::size_t>(w) +
                                   static_cast<std::size_t>(x)) *
                                  3;
            rgb[b + 0] =
                static_cast<std::uint8_t>((x * 37 + y * 51 + 11) % 256);
            rgb[b + 1] =
                static_cast<std::uint8_t>((x * 53 + y * 29 + 101) % 256);
            rgb[b + 2] =
                static_cast<std::uint8_t>((x * 17 + y * 71 + 197) % 256);
        }
    }
}

bool CheckEquivalence(int src_w, int src_h, int dst_w, int dst_h,
                      const char* label) {
    std::vector<std::uint8_t> rgb(static_cast<std::size_t>(src_w) * src_h * 3);
    FillPattern(rgb, src_w, src_h);
    std::vector<float> got(static_cast<std::size_t>(3) * dst_w * dst_h);
    std::vector<float> want(static_cast<std::size_t>(3) * dst_w * dst_h);
    const DfineConfig cfg = SizedConfig(dst_w, dst_h);
    if (!PreprocessRgb(rgb.data(), src_w, src_h, got.data(), cfg)) {
        CHECK(false, label);
        return false;
    }
    ReferenceResample(rgb.data(), src_w, src_h, want.data(), dst_w, dst_h);
    bool ok = true;
    for (std::size_t i = 0; i < want.size() && ok; ++i) {
        ok = Near(got[i], want[i], 1e-6F);
    }
    CHECK(ok, label);
    return ok;
}

// Golden-pattern fill for the PIL-generated golden tensors (see section 14).
void FillGoldenPattern(std::vector<std::uint8_t>& rgb, int w, int h) {
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t b = (static_cast<std::size_t>(y) *
                                       static_cast<std::size_t>(w) +
                                   static_cast<std::size_t>(x)) *
                                  3;
            rgb[b + 0] = static_cast<std::uint8_t>((x * 31 + y * 17) % 256);
            rgb[b + 1] = static_cast<std::uint8_t>((x * 13 + y * 29) % 256);
            rgb[b + 2] = static_cast<std::uint8_t>((x * 7 + y * 41) % 256);
        }
    }
}

struct GoldenCase {
    int src_w;
    int src_h;
    int dst_w;
    int dst_h;
    const float* rgb;  // dst_h*dst_w*3, RGB order, /255 floats
};

bool CheckGolden(const GoldenCase& g, const char* label) {
    std::vector<std::uint8_t> rgb(static_cast<std::size_t>(g.src_w) *
                                  static_cast<std::size_t>(g.src_h) * 3U);
    FillGoldenPattern(rgb, g.src_w, g.src_h);
    std::vector<float> got(static_cast<std::size_t>(3) * g.dst_w * g.dst_h);
    const DfineConfig cfg = SizedConfig(g.dst_w, g.dst_h);
    if (!PreprocessRgb(rgb.data(), g.src_w, g.src_h, got.data(), cfg)) {
        CHECK(false, label);
        return false;
    }
    const std::size_t plane =
        static_cast<std::size_t>(g.dst_w) * static_cast<std::size_t>(g.dst_h);
    bool ok = true;
    for (int y = 0; y < g.dst_h && ok; ++y) {
        for (int x = 0; x < g.dst_w && ok; ++x) {
            const std::size_t px = static_cast<std::size_t>(y) *
                                       static_cast<std::size_t>(g.dst_w) +
                                   static_cast<std::size_t>(x);
            for (std::size_t c = 0; c < 3U && ok; ++c) {
                const float want = g.rgb[px * 3U + c];
                const float v = got[c * plane + px];
                ok = Near(v, want, 2e-6F);
            }
        }
    }
    CHECK(ok, label);
    return ok;
}

}  // namespace

int main() {
    // ---- 1) hand-computed sigmoid + argmax + box scaling ----
    {
        const DfineConfig cfg = SmallConfig(0.4F);
        // q0: sigmoid(-1)=0.26894, sigmoid(2)=0.88080, sigmoid(0)=0.5.
        const float logits[6] = {-1.0F, 2.0F, 0.0F, -10.0F, -10.0F, -10.0F};
        const float boxes[8] = {0.5F, 0.5F, 0.5F, 0.25F, 0.0F, 0.0F, 0.1F, 0.1F};
        const std::vector<ksi::safety::Detection> dets =
            DecodeDfine(logits, boxes, 800, 400, cfg);
        CHECK(dets.size() == 1, "one query above floor, one below");
        if (dets.size() == 1) {
            CHECK(dets[0].class_id == 1, "argmax picks class 1");
            CHECK(Near(dets[0].score, 0.88079708F, 1e-5F), "sigmoid(2) score");
            CHECK(Near(dets[0].x_min, 200.0F, 1e-3F), "box x_min");
            CHECK(Near(dets[0].y_min, 150.0F, 1e-3F), "box y_min");
            CHECK(Near(dets[0].x_max, 600.0F, 1e-3F), "box x_max");
            CHECK(Near(dets[0].y_max, 250.0F, 1e-3F), "box y_max");
            CHECK(dets[0].class_name.empty(), "no ontology mapping here");
        }
    }
    // ---- 2) strict threshold boundary: score == threshold is dropped ----
    {
        const float logits[6] = {-100.0F, -100.0F, 0.0F,
                                 -100.0F, -100.0F, 0.0F};  // best = 0.5 exact
        const float boxes[8] = {0.5F, 0.5F, 1.0F, 1.0F, 0.5F, 0.5F, 1.0F, 1.0F};
        CHECK(DecodeDfine(logits, boxes, 100, 100, SmallConfig(0.5F)).empty(),
              "score == threshold dropped (strict >)");
        const std::vector<ksi::safety::Detection> kept =
            DecodeDfine(logits, boxes, 100, 100, SmallConfig(0.49F));
        CHECK(kept.size() == 2, "score just above threshold kept");
    }
    // ---- 3) all-below threshold -> empty ----
    {
        const DfineConfig cfg = SmallConfig(0.99F);
        const float logits[6] = {0.0F, 1.0F, -1.0F, 2.0F, 0.5F, -2.0F};
        const float boxes[8] = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F};
        CHECK(DecodeDfine(logits, boxes, 320, 240, cfg).empty(),
              "all below -> empty");
    }
    // ---- 4) query-count handling + order preserved (no score sort) ----
    {
        DfineConfig cfg;
        cfg.num_classes = 3;
        cfg.num_queries = 3;
        cfg.score_threshold = 0.5F;
        // q0 kept (0.731), q1 dropped, q2 kept higher (0.953).
        const float logits[9] = {1.0F, -9.0F, -9.0F, -9.0F, -9.0F, -9.0F,
                                 -9.0F, -9.0F, 3.0F};
        const float boxes[12] = {0.1F, 0.1F, 0.2F, 0.2F, 0.5F, 0.5F,
                                 0.2F, 0.2F, 0.7F, 0.7F, 0.2F, 0.2F};
        const std::vector<ksi::safety::Detection> dets =
            DecodeDfine(logits, boxes, 100, 100, cfg);
        CHECK(dets.size() == 2, "two of three queries kept");
        if (dets.size() == 2) {
            CHECK(dets[0].class_id == 0 && dets[1].class_id == 2,
                  "query order preserved, not score-sorted");
            CHECK(dets[0].score < dets[1].score,
                  "ascending scores prove no descending sort");
            CHECK(Near(dets[0].x_min, 0.0F, 1e-3F), "q0 box scaled");
            CHECK(Near(dets[1].x_min, 60.0F, 1e-3F), "q2 box scaled");
        }
    }
    // ---- 5) the configured threshold is actually used ----
    {
        DfineConfig loose = SmallConfig(0.0F);
        DfineConfig strict = SmallConfig(0.99F);
        const float logits[6] = {0.0F, 0.0F, 0.0F, 1.0F, -1.0F, 0.5F};
        const float boxes[8] = {0.5F, 0.5F, 0.2F, 0.2F, 0.5F, 0.5F, 0.2F, 0.2F};
        const std::vector<ksi::safety::Detection> many =
            DecodeDfine(logits, boxes, 64, 64, loose);
        const std::vector<ksi::safety::Detection> none =
            DecodeDfine(logits, boxes, 64, 64, strict);
        CHECK(many.size() == 2, "zero floor keeps both queries");
        CHECK(none.empty(), "near-one floor keeps neither");
        // Mid threshold splits the pair (sigmoid(1)=0.731 > 0.6 > 0.5).
        const std::vector<ksi::safety::Detection> one =
            DecodeDfine(logits, boxes, 64, 64, SmallConfig(0.6F));
        CHECK(one.size() == 1 && one[0].class_id == 0,
              "mid threshold selects the stronger query");
    }
    // ---- 6) default config mirrors the model contract ----
    {
        const DfineConfig cfg;
        CHECK(cfg.num_queries == 300, "fixed 300 queries (NMS-free)");
        CHECK(cfg.num_classes == 80, "80 COCO classes");
        CHECK(cfg.input_width == 640 && cfg.input_height == 640, "640x640");
        CHECK(Near(cfg.score_threshold, 0.05F, 1e-7F), "0.05 score floor");
        CHECK(IsValidDfineConfig(cfg), "default config valid");
        DfineConfig bad = cfg;
        bad.score_threshold = 2.0F;
        CHECK(!IsValidDfineConfig(bad), "threshold > 1 invalid");
        bad = cfg;
        bad.num_queries = 0;
        CHECK(!IsValidDfineConfig(bad), "zero queries invalid");
    }
    // ---- 7) uniform preprocess: value + channel order + dims ----
    {
        const DfineConfig cfg;  // 640x640 identity resize
        std::vector<std::uint8_t> rgb(static_cast<std::size_t>(640) * 640 * 3);
        for (int y = 0; y < 640; ++y) {
            for (int x = 0; x < 640; ++x) {
                const std::size_t b = (static_cast<std::size_t>(y) * 640 +
                                       static_cast<std::size_t>(x)) *
                                      3;
                rgb[b + 0] = static_cast<std::uint8_t>(x % 256);        // R
                rgb[b + 1] = static_cast<std::uint8_t>(y % 256);        // G
                rgb[b + 2] = static_cast<std::uint8_t>((x + y) % 256);  // B
            }
        }
        std::vector<float> out(static_cast<std::size_t>(3) * 640 * 640, -1.0F);
        CHECK(PreprocessRgb(rgb.data(), 640, 640, out.data(), cfg),
              "identity preprocess ok");
        bool order_ok = true;
        for (int y = 0; y < 640 && order_ok; y += 37) {
            for (int x = 0; x < 640 && order_ok; x += 53) {
                const std::size_t px =
                    static_cast<std::size_t>(y) * 640 + static_cast<std::size_t>(x);
                const float er = static_cast<float>(x % 256) / 255.0F;
                const float eg = static_cast<float>(y % 256) / 255.0F;
                const float eb = static_cast<float>((x + y) % 256) / 255.0F;
                order_ok = Near(out[px], er, 1e-7F) &&
                           Near(out[640 * 640 + px], eg, 1e-7F) &&
                           Near(out[2 * 640 * 640 + px], eb, 1e-7F);
            }
        }
        CHECK(order_ok, "CHW channel order + 1:1 sampling exact");

        std::vector<std::uint8_t> flat(static_cast<std::size_t>(4) * 3 * 3, 128);
        std::vector<float> flat_out(static_cast<std::size_t>(3) * 640 * 640);
        CHECK(PreprocessRgb(flat.data(), 4, 3, flat_out.data(), cfg),
              "upscale uniform ok");
        bool uniform_ok = true;
        for (std::size_t i = 0; i < flat_out.size() && uniform_ok; ++i) {
            uniform_ok = Near(flat_out[i], 128.0F / 255.0F, 1e-6F);
        }
        CHECK(uniform_ok, "uniform image -> uniform 128/255 planes");
    }
    // ---- 8) gradient sanity: monotone, edge-clamped endpoints ----
    {
        const DfineConfig cfg;
        std::uint8_t grad[6] = {0, 0, 0, 255, 255, 255};  // 2x1 black->white
        std::vector<float> out(static_cast<std::size_t>(3) * 640 * 640);
        CHECK(PreprocessRgb(grad, 2, 1, out.data(), cfg), "gradient ok");
        CHECK(Near(out[0], 0.0F, 1e-6F), "left edge replicates black");
        CHECK(Near(out[639], 1.0F, 1e-6F), "right edge replicates white");
        bool mono = true;
        for (int x = 1; x < 640 && mono; ++x) {
            mono = out[static_cast<std::size_t>(x)] >=
                   out[static_cast<std::size_t>(x) - 1];
        }
        CHECK(mono, "gradient row monotone non-decreasing");
    }
    // ---- 9) determinism: identical runs, identical bytes ----
    {
        const DfineConfig cfg = SmallConfig(0.3F);
        const float logits[6] = {0.5F, -0.5F, 1.5F, -2.0F, 0.1F, 0.2F};
        const float boxes[8] = {0.3F, 0.4F, 0.5F, 0.6F, 0.7F, 0.2F, 0.1F, 0.3F};
        const std::vector<ksi::safety::Detection> a =
            DecodeDfine(logits, boxes, 200, 100, cfg);
        const std::vector<ksi::safety::Detection> b =
            DecodeDfine(logits, boxes, 200, 100, cfg);
        CHECK(a.size() == b.size(), "deterministic count");
        bool same = a.size() == b.size();
        for (std::size_t i = 0; i < a.size() && same; ++i) {
            same = a[i].x_min == b[i].x_min && a[i].y_min == b[i].y_min &&
                   a[i].x_max == b[i].x_max && a[i].y_max == b[i].y_max &&
                   a[i].score == b[i].score && a[i].class_id == b[i].class_id &&
                   a[i].class_name == b[i].class_name;
        }
        CHECK(same, "deterministic decode bytes");

        std::vector<std::uint8_t> rgb(static_cast<std::size_t>(8) * 6 * 3);
        for (std::size_t i = 0; i < rgb.size(); ++i) {
            rgb[i] = static_cast<std::uint8_t>((i * 7 + 3) % 256);
        }
        std::vector<float> o1(static_cast<std::size_t>(3) * 640 * 640);
        std::vector<float> o2(static_cast<std::size_t>(3) * 640 * 640);
        CHECK(PreprocessRgb(rgb.data(), 8, 6, o1.data(), DfineConfig{}),
              "preprocess run 1");
        CHECK(PreprocessRgb(rgb.data(), 8, 6, o2.data(), DfineConfig{}),
              "preprocess run 2");
        CHECK(std::memcmp(o1.data(), o2.data(), o1.size() * sizeof(float)) == 0,
              "deterministic preprocess bytes");
    }
    // ---- 10) null / invalid posture: empty or false, never partial ----
    {
        const DfineConfig cfg = SmallConfig(0.1F);
        const float logits[6] = {5.0F, 5.0F, 5.0F, 5.0F, 5.0F, 5.0F};
        const float boxes[8] = {0.5F, 0.5F, 0.5F, 0.5F,
                                0.5F, 0.5F, 0.5F, 0.5F};
        CHECK(DecodeDfine(nullptr, boxes, 64, 64, cfg).empty(), "null logits");
        CHECK(DecodeDfine(logits, nullptr, 64, 64, cfg).empty(), "null boxes");
        CHECK(DecodeDfine(logits, boxes, 0, 64, cfg).empty(), "zero width");
        DfineConfig bad = cfg;
        bad.score_threshold = 0.05F;
        bad.num_classes = -1;
        CHECK(DecodeDfine(logits, boxes, 64, 64, bad).empty(), "bad config");
        std::vector<float> out(static_cast<std::size_t>(3) * 640 * 640,
                               -7.0F);
        CHECK(!PreprocessRgb(nullptr, 4, 4, out.data(), DfineConfig{}),
              "null rgb false");
        std::uint8_t px[3] = {1, 2, 3};
        CHECK(!PreprocessRgb(px, 1, 1, nullptr, DfineConfig{}),
              "null out false");
        CHECK(!PreprocessRgb(px, 0, 1, out.data(), DfineConfig{}),
              "zero src dim false");
        CHECK(out[0] == -7.0F, "rejected call writes nothing");
    }
    // ---- 11) argmax tie -> lowest class index (pinned) ----
    {
        const DfineConfig cfg = SmallConfig(0.4F);
        // q0: all three classes tie at sigmoid(2)=0.88080 -> class 0.
        // q1: classes 0/1 tie at sigmoid(2) -> class 0 (not 1).
        const float logits[6] = {2.0F, 2.0F, 2.0F, 2.0F, 2.0F, -3.0F};
        const float boxes[8] = {0.5F, 0.5F, 0.4F, 0.4F,
                                0.5F, 0.5F, 0.4F, 0.4F};
        const std::vector<ksi::safety::Detection> dets =
            DecodeDfine(logits, boxes, 100, 100, cfg);
        CHECK(dets.size() == 2, "tied queries above threshold kept");
        if (dets.size() == 2) {
            CHECK(dets[0].class_id == 0, "three-way tie -> lowest index 0");
            CHECK(dets[1].class_id == 0, "two-way tie -> lowest index 0");
            CHECK(Near(dets[0].score, 0.88079708F, 1e-5F),
                  "tie score is sigmoid(2)");
        }
    }
    // ---- 12) optimized preprocess matches reference bilinear ----
    {
        CheckEquivalence(3, 2, 5, 4, "3x2->5x4 matches reference");
        CheckEquivalence(7, 5, 4, 3, "7x5->4x3 matches reference");
        CheckEquivalence(1, 1, 4, 4, "1x1->4x4 matches reference");
    }
    // ---- 13) identity fast path matches the general formula ----
    {
        CheckEquivalence(5, 4, 5, 4, "5x4->5x4 identity matches reference");
    }
    // ---- 14) golden tensors generated from actual PIL (BILINEAR) ----
    {
        // Generated with the local Pillow from FillGoldenPattern's exact
        // pattern:
        //   Image.fromarray(pattern).resize((dw, dh), Image.BILINEAR)
        //   np.asarray(img, float64) / 255
        // Tolerance 2e-6 covers the 6-decimal constant rounding only: the
        // implementation reproduces the same uint8 values as PIL (verified
        // against these constants, which are the reference preprocessing
        // RTDetrImageProcessor uses).
        static const float kGolden8x6[3 * 4 * 3] = {
            0.133333F, 0.117647F, 0.133333F, 0.352941F, 0.211765F, 0.184314F,
            0.596078F, 0.313725F, 0.239216F, 0.811765F, 0.403922F, 0.286275F,
            0.254902F, 0.321569F, 0.423529F, 0.474510F, 0.415686F, 0.474510F,
            0.717647F, 0.517647F, 0.529412F, 0.717647F, 0.607843F, 0.576471F,
            0.372549F, 0.521569F, 0.709804F, 0.592157F, 0.615686F, 0.760784F,
            0.780392F, 0.717647F, 0.815686F, 0.435294F, 0.807843F, 0.862745F,
        };
        static const float kGolden3x2[4 * 5 * 3] = {
            0.000000F, 0.000000F, 0.000000F, 0.047059F, 0.019608F, 0.011765F,
            0.121569F, 0.050980F, 0.027451F, 0.196078F, 0.082353F, 0.043137F,
            0.243137F, 0.101961F, 0.054902F, 0.015686F, 0.027451F, 0.039216F,
            0.062745F, 0.047059F, 0.050980F, 0.137255F, 0.078431F, 0.066667F,
            0.211765F, 0.109804F, 0.082353F, 0.258824F, 0.129412F, 0.094118F,
            0.050980F, 0.086275F, 0.121569F, 0.098039F, 0.105882F, 0.133333F,
            0.172549F, 0.137255F, 0.149020F, 0.247059F, 0.168627F, 0.164706F,
            0.294118F, 0.188235F, 0.176471F, 0.066667F, 0.113725F, 0.160784F,
            0.113725F, 0.133333F, 0.172549F, 0.188235F, 0.164706F, 0.188235F,
            0.262745F, 0.196078F, 0.203922F, 0.309804F, 0.215686F, 0.215686F,
        };
        static const float kGolden5x4[4 * 5 * 3] = {
            0.000000F, 0.000000F, 0.000000F, 0.121569F, 0.050980F, 0.027451F,
            0.243137F, 0.101961F, 0.054902F, 0.364706F, 0.152941F, 0.082353F,
            0.486275F, 0.203922F, 0.109804F, 0.066667F, 0.113725F, 0.160784F,
            0.188235F, 0.164706F, 0.188235F, 0.309804F, 0.215686F, 0.215686F,
            0.431373F, 0.266667F, 0.243137F, 0.552941F, 0.317647F, 0.270588F,
            0.133333F, 0.227451F, 0.321569F, 0.254902F, 0.278431F, 0.349020F,
            0.376471F, 0.329412F, 0.376471F, 0.498039F, 0.380392F, 0.403922F,
            0.619608F, 0.431373F, 0.431373F, 0.200000F, 0.341176F, 0.482353F,
            0.321569F, 0.392157F, 0.509804F, 0.443137F, 0.443137F, 0.537255F,
            0.564706F, 0.494118F, 0.564706F, 0.686275F, 0.545098F, 0.592157F,
        };
        static const float kGolden7x5[3 * 4 * 3] = {
            0.090196F, 0.074510F, 0.086275F, 0.278431F, 0.152941F, 0.129412F,
            0.505882F, 0.247059F, 0.176471F, 0.694118F, 0.325490F, 0.219608F,
            0.196078F, 0.254902F, 0.337255F, 0.384314F, 0.333333F, 0.380392F,
            0.611765F, 0.427451F, 0.427451F, 0.800000F, 0.505882F, 0.470588F,
            0.301961F, 0.435294F, 0.588235F, 0.490196F, 0.513725F, 0.631373F,
            0.717647F, 0.607843F, 0.678431F, 0.905882F, 0.686275F, 0.721569F,
        };
        CheckGolden({8, 6, 4, 3, kGolden8x6}, "8x6->4x3 matches PIL golden");
        CheckGolden({3, 2, 5, 4, kGolden3x2}, "3x2->5x4 matches PIL golden");
        CheckGolden({5, 4, 5, 4, kGolden5x4}, "5x4->5x4 matches PIL golden");
        CheckGolden({7, 5, 4, 3, kGolden7x5}, "7x5->4x3 matches PIL golden");
    }
    if (edge_test::failures == 0) {
        std::printf("dfine: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
