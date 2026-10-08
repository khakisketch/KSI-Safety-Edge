// tensorrt: TensorRtRuntime contract in BOTH builds (no external files).
//   TRT OFF (stub): Available()==false, ctor never ok, Run false + empty.
//   TRT ON:         Available()==true, nonexistent/empty engine -> not ok
//                   with graceful (non-crashing) Run false + empty.

#include "ksi_safety/perception/tensorrt_runtime.hpp"

#include <cstdio>
#include <vector>

#include "support/check.hpp"

using ksi::safety::TensorRtRuntime;

int main() {
#ifdef KSI_SAFETY_EDGE_HAS_TENSORRT
    CHECK(TensorRtRuntime::Available(), "TRT build reports available");
#else
    CHECK(!TensorRtRuntime::Available(), "stub build reports unavailable");
#endif

    // Nonexistent engine: ctor not ok, Run fail-closed (false + empty).
    {
        TensorRtRuntime rt("/nonexistent-ksi-safety-edge.engine");
        CHECK(!rt.ok(), "missing engine file not ok");
        std::vector<float> in(TensorRtRuntime::kInputSize, 0.0F);
        std::vector<float> logits(7, 1.0F);
        std::vector<float> boxes(7, 1.0F);
        CHECK(!rt.Run(in.data(), &logits, &boxes), "Run false when not ok");
        CHECK(logits.empty() && boxes.empty(), "failed Run leaves no output");
    }
    // Empty path: same posture.
    {
        TensorRtRuntime rt("");
        CHECK(!rt.ok(), "empty engine path not ok");
        std::vector<float> in(TensorRtRuntime::kInputSize, 0.0F);
        std::vector<float> logits;
        std::vector<float> boxes;
        CHECK(!rt.Run(in.data(), &logits, &boxes), "Run false on empty path");
        CHECK(logits.empty() && boxes.empty(), "empty outputs");
    }
    // Null posture: never crashes, always false.
    {
        TensorRtRuntime rt("/nonexistent-ksi-safety-edge.engine");
        CHECK(!rt.Run(nullptr, nullptr, nullptr), "all-null Run false");
        std::vector<float> logits;
        std::vector<float> boxes;
        CHECK(!rt.Run(nullptr, &logits, &boxes), "null input Run false");
        CHECK(logits.empty() && boxes.empty(), "null input, empty outputs");
        std::vector<float> in(TensorRtRuntime::kInputSize, 0.0F);
        CHECK(!rt.Run(in.data(), nullptr, &boxes), "null logits Run false");
        CHECK(!rt.Run(in.data(), &logits, nullptr), "null boxes Run false");
    }
    // Contract sizes mirror the engine binding shapes.
    CHECK(TensorRtRuntime::kInputSize == 3U * 640U * 640U, "input size");
    CHECK(TensorRtRuntime::kLogitsSize == 300U * 80U, "logits size");
    CHECK(TensorRtRuntime::kBoxesSize == 300U * 4U, "boxes size");

    if (edge_test::failures == 0) {
        std::printf("tensorrt: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
