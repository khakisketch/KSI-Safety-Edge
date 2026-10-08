// dfine_trt_smoke: W9 device-side smoke for the C++ TensorRT adapter.
//
// Real-image C++ inference through `DfineDetector` with
// `TensorRtRuntime::Run` as the inference fn, compared against a Python/ORT
// reference expectation file (class/score/bbox), plus a continuous-run
// latency/RSS read. Fail-closed throughout: any setup failure prints a
// clean message + `SMOKE_RESULT FAIL` and exits nonzero (no crash).
//
// Usage:
//   dfine_trt_smoke --engine PATH --input PATH --expected PATH [--iters N]
//     --engine   serialized TensorRT engine (e.g. dfine-s-coco.fp16...engine)
//     --input    exactly 640*640*3 = 1228800 bytes of raw RGB888
//     --expected lines "class score xmin ymin xmax ymax" (`#` comments
//                skipped; first 12 used; at least 1 required)
//     --iters    continuous-run repeat count (default 100)
//
// Acceptance policy (revised 2026-10-09, Primary decision): the fp16
// product engine is numerically exact at high confidence but its noise
// floor re-ranks tail detections (expected score < 0.2), so gating ALL
// top-12 lines is unreasonable. Gating set = expected lines with score >=
// 0.2 (the top-1 expected line must be gating and must pass); each needs
// a same-class match with IoU >= 0.5 AND |dScore| <= 0.05. Tail lines
// (score < 0.2) are informational only: best same-class match printed,
// plus a summary count with IoU >= 0.5. Rationale: operating thresholds
// are policy-driven later; high-confidence detections are the acceptance
// bar. Exit 0 iff every gating line passes (and >= 1 expected line).
//
// Output (stable, parseable):
//   det <class> <score> <xmin> <ymin> <xmax> <ymax>   (one per top-12 det)
//   match ... PASS|FAIL                               (one per gating line)
//   tail ...                                          (one per tail line)
//   tail_summary lines=N iou_ge_0_5=M
//   latency_ms mean=.. p95=.. qps=.. iters=N
//   rss_kb before=.. after=..
//   SMOKE_RESULT PASS|FAIL                             (always last)
//
// Built ONLY when KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "ksi_safety/perception/dfine_detector.hpp"
#include "ksi_safety/perception/tensorrt_runtime.hpp"

namespace {

using ksi::safety::DfineDetector;
using ksi::safety::Detection;
using ksi::safety::TensorRtRuntime;

constexpr std::size_t kRgbBytes = 640U * 640U * 3U;  // 1228800
constexpr std::size_t kTopK = 12;
constexpr double kIouPass = 0.5;
constexpr double kScoreDeltaPass = 0.05;
// Gating floor: expected lines at/above this score must match; below it
// is the fp16 noise floor (informational only, never gating).
constexpr double kGateScoreFloor = 0.2;

struct Expected {
    std::uint32_t cls = 0;
    double score = 0.0;
    double xmin = 0.0;
    double ymin = 0.0;
    double xmax = 0.0;
    double ymax = 0.0;
};

void Usage() {
    std::fprintf(stderr,
                 "usage: dfine_trt_smoke --engine PATH --input PATH "
                 "--expected PATH [--iters N]\n");
}

double IoU(const Expected& e, const Detection& d) {
    const double ix0 = std::max(e.xmin, static_cast<double>(d.x_min));
    const double iy0 = std::max(e.ymin, static_cast<double>(d.y_min));
    const double ix1 = std::min(e.xmax, static_cast<double>(d.x_max));
    const double iy1 = std::min(e.ymax, static_cast<double>(d.y_max));
    const double iw = ix1 - ix0;
    const double ih = iy1 - iy0;
    if (iw <= 0.0 || ih <= 0.0) {
        return 0.0;
    }
    const double inter = iw * ih;
    const double ea = std::max(0.0, e.xmax - e.xmin) *
                      std::max(0.0, e.ymax - e.ymin);
    const double da = std::max(0.0, static_cast<double>(d.x_max - d.x_min)) *
                      std::max(0.0, static_cast<double>(d.y_max - d.y_min));
    const double uni = ea + da - inter;
    if (uni <= 0.0) {
        return 0.0;
    }
    return inter / uni;
}

// VmRSS in kB from /proc/self/status; -1 when unreadable.
long ReadVmRssKb() {
    std::ifstream status("/proc/self/status");
    if (!status) {
        return -1;
    }
    std::string line;
    while (std::getline(status, line)) {
        if (line.compare(0, 6, "VmRSS:") == 0) {
            long value = -1;
            if (std::sscanf(line.c_str() + 6, "%ld", &value) == 1) {
                return value;
            }
            return -1;
        }
    }
    return -1;
}

}  // namespace

int main(int argc, char** argv) {
    std::string engine_path;
    std::string input_path;
    std::string expected_path;
    long iters = 100;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--engine" && i + 1 < argc) {
            engine_path = argv[++i];
        } else if (arg == "--input" && i + 1 < argc) {
            input_path = argv[++i];
        } else if (arg == "--expected" && i + 1 < argc) {
            expected_path = argv[++i];
        } else if (arg == "--iters" && i + 1 < argc) {
            if (std::sscanf(argv[++i], "%ld", &iters) != 1 || iters <= 0) {
                std::fprintf(stderr, "error: --iters must be positive\n");
                std::printf("SMOKE_RESULT FAIL\n");
                return 1;
            }
        } else {
            Usage();
            std::printf("SMOKE_RESULT FAIL\n");
            return 2;
        }
    }
    if (engine_path.empty() || input_path.empty() || expected_path.empty()) {
        Usage();
        std::printf("SMOKE_RESULT FAIL\n");
        return 2;
    }
    if (!TensorRtRuntime::Available()) {
        std::fprintf(stderr, "error: TensorRT backend not available\n");
        std::printf("SMOKE_RESULT FAIL\n");
        return 1;
    }
    TensorRtRuntime runtime(engine_path);
    if (!runtime.ok()) {
        std::fprintf(stderr, "error: cannot load/validate engine '%s'\n",
                     engine_path.c_str());
        std::printf("SMOKE_RESULT FAIL\n");
        return 1;
    }
    std::vector<std::uint8_t> rgb;
    {
        std::ifstream file(input_path, std::ios::binary | std::ios::ate);
        if (!file) {
            std::fprintf(stderr, "error: cannot open input '%s'\n",
                         input_path.c_str());
            std::printf("SMOKE_RESULT FAIL\n");
            return 1;
        }
        const std::ifstream::pos_type size = file.tellg();
        if (size != static_cast<std::ifstream::pos_type>(kRgbBytes)) {
            std::fprintf(stderr,
                         "error: input '%s' is %ld bytes, expected %zu "
                         "(640x640x3 raw RGB)\n",
                         input_path.c_str(), static_cast<long>(size),
                         kRgbBytes);
            std::printf("SMOKE_RESULT FAIL\n");
            return 1;
        }
        file.seekg(0, std::ios::beg);
        rgb.resize(kRgbBytes);
        if (!file.read(reinterpret_cast<char*>(rgb.data()),
                       static_cast<std::streamsize>(kRgbBytes))) {
            std::fprintf(stderr, "error: cannot read input '%s'\n",
                         input_path.c_str());
            std::printf("SMOKE_RESULT FAIL\n");
            return 1;
        }
    }
    std::vector<Expected> expected;
    {
        std::ifstream file(expected_path);
        if (!file) {
            std::fprintf(stderr, "error: cannot open expected '%s'\n",
                         expected_path.c_str());
            std::printf("SMOKE_RESULT FAIL\n");
            return 1;
        }
        std::string line;
        while (std::getline(file, line) && expected.size() < kTopK) {
            const std::size_t first =
                line.find_first_not_of(" \t\r\n");
            if (first == std::string::npos || line[first] == '#') {
                continue;
            }
            Expected e;
            unsigned cls = 0;
            if (std::sscanf(line.c_str() + first, "%u %lf %lf %lf %lf %lf",
                             &cls, &e.score, &e.xmin, &e.ymin, &e.xmax,
                             &e.ymax) != 6) {
                std::fprintf(stderr, "error: bad expected line: %s\n",
                             line.c_str());
                std::printf("SMOKE_RESULT FAIL\n");
                return 1;
            }
            e.cls = static_cast<std::uint32_t>(cls);
            expected.push_back(e);
        }
    }
    if (expected.empty()) {
        std::fprintf(stderr, "error: no expected detections in '%s'\n",
                     expected_path.c_str());
        std::printf("SMOKE_RESULT FAIL\n");
        return 1;
    }
    if (expected.front().score < kGateScoreFloor) {
        std::fprintf(stderr,
                     "error: top-1 expected score %.4f below gating floor "
                     "%.2f in '%s'\n",
                     expected.front().score, kGateScoreFloor,
                     expected_path.c_str());
        std::printf("SMOKE_RESULT FAIL\n");
        return 1;
    }

    DfineDetector detector(ksi::safety::DfineConfig{},
                           [&runtime](const float* in, std::vector<float>* l,
                                      std::vector<float>* b) {
                               return runtime.Run(in, l, b);
                           });

    const long rss_before = ReadVmRssKb();
    std::vector<Detection> dets;
    if (!detector.Detect(rgb.data(), 640, 640, &dets)) {
        std::fprintf(stderr, "error: Detect failed on '%s'\n",
                     input_path.c_str());
        std::printf("SMOKE_RESULT FAIL\n");
        return 1;
    }
    // Continuous run: repeat Detect, keep the last output for comparison.
    std::vector<double> ms;
    ms.reserve(static_cast<std::size_t>(iters));
    for (long i = 0; i < iters; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        if (!detector.Detect(rgb.data(), 640, 640, &dets)) {
            std::fprintf(stderr, "error: Detect failed at iter %ld\n", i);
            std::printf("SMOKE_RESULT FAIL\n");
            return 1;
        }
        const auto t1 = std::chrono::steady_clock::now();
        ms.push_back(
            std::chrono::duration<double, std::milli>(t1 - t0).count());
    }
    const long rss_after = ReadVmRssKb();

    std::stable_sort(dets.begin(), dets.end(),
                     [](const Detection& a, const Detection& b) {
                         return a.score > b.score;
                     });
    if (dets.size() > kTopK) {
        dets.resize(kTopK);
    }
    for (const Detection& d : dets) {
        std::printf("det %u %.4f %.1f %.1f %.1f %.1f\n", d.class_id,
                    static_cast<double>(d.score), static_cast<double>(d.x_min),
                    static_cast<double>(d.y_min),
                    static_cast<double>(d.x_max),
                    static_cast<double>(d.y_max));
    }

    bool all_pass = true;
    long tail_lines = 0;
    long tail_iou_ok = 0;
    for (const Expected& e : expected) {
        double best_iou = 0.0;
        const Detection* best = nullptr;
        for (const Detection& d : dets) {
            if (d.class_id != e.cls) {
                continue;
            }
            const double iou = IoU(e, d);
            if (best == nullptr || iou > best_iou) {
                best_iou = iou;
                best = &d;
            }
        }
        double det_score = 0.0;
        double dscore = e.score;
        double bx0 = 0.0;
        double by0 = 0.0;
        double bx1 = 0.0;
        double by1 = 0.0;
        if (best != nullptr) {
            det_score = best->score;
            dscore = std::fabs(det_score - e.score);
            bx0 = best->x_min;
            by0 = best->y_min;
            bx1 = best->x_max;
            by1 = best->y_max;
        }
        if (e.score < kGateScoreFloor) {
            ++tail_lines;
            tail_iou_ok += (best_iou >= kIouPass) ? 1 : 0;
            std::printf(
                "tail exp_class=%u exp_score=%.4f "
                "exp_box=[%.1f,%.1f,%.1f,%.1f] "
                "det_score=%.4f det_box=[%.1f,%.1f,%.1f,%.1f] iou=%.4f "
                "dscore=%.4f\n",
                e.cls, e.score, e.xmin, e.ymin, e.xmax, e.ymax, det_score,
                bx0, by0, bx1, by1, best_iou, dscore);
            continue;
        }
        const bool pass =
            best != nullptr && best_iou >= kIouPass && dscore <= kScoreDeltaPass;
        all_pass = all_pass && pass;
        std::printf(
            "match exp_class=%u exp_score=%.4f exp_box=[%.1f,%.1f,%.1f,%.1f] "
            "det_score=%.4f det_box=[%.1f,%.1f,%.1f,%.1f] iou=%.4f "
            "dscore=%.4f %s\n",
            e.cls, e.score, e.xmin, e.ymin, e.xmax, e.ymax, det_score, bx0,
            by0, bx1, by1, best_iou, dscore, pass ? "PASS" : "FAIL");
    }
    std::printf("tail_summary lines=%ld iou_ge_0_5=%ld\n", tail_lines,
                tail_iou_ok);

    std::sort(ms.begin(), ms.end());
    double sum = 0.0;
    for (double v : ms) {
        sum += v;
    }
    const double mean = sum / static_cast<double>(ms.size());
    const std::size_t p95_idx =
        (static_cast<std::size_t>(ms.size()) * 95U + 99U) / 100U - 1U;
    const double p95 = ms[p95_idx];
    const double qps = mean > 0.0 ? 1000.0 / mean : 0.0;
    std::printf("latency_ms mean=%.3f p95=%.3f qps=%.2f iters=%ld\n", mean,
                p95, qps, iters);
    std::printf("rss_kb before=%ld after=%ld\n", rss_before, rss_after);
    std::printf("SMOKE_RESULT %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass ? 0 : 1;
}
