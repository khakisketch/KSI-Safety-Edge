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
    if (edge_test::failures == 0) {
        std::printf("dfine: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
