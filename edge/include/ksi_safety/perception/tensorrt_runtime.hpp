// TensorRT serialized-engine runtime for D-FINE (W9).
//
// Role: load a pre-built serialized TensorRT engine and execute one
// inference (preprocessed CHW float32 in -> raw logits + boxes out) behind
// the `DfineInferenceFn` boundary, so `DfineDetector::Detect()` produces
// `Detection`s (the Detection contract) from real weights with no change
// to preprocess/decode semantics.
//
// Binding contract (validated in the ctor; ANY mismatch -> not ok):
//   input  "pixel_values": 1 x 3 x 640 x 640 float32 (CHW planes R, G, B,
//                           exactly as `PreprocessRgb` produces).
//   output "logits":      1 x 300 x 80 float32 row-major raw logits.
//   output "pred_boxes":  1 x 300 x 4  float32 row-major normalized cxcywh.
// Device buffers are allocated once in the ctor; `Run()` does
// H2D -> enqueueV3 -> synchronize -> D2H. Uses the TensorRT 8.5 tensor
// API (`getTensorShape` / `setTensorAddress` / `enqueueV3`), not the
// deprecated bindings API.
//
// Detection-contract connection: `Run` matches `DfineInferenceFn`, so a
// `DfineDetector` constructed with a lambda forwarding to `Run` decodes
// engine outputs into `std::vector<Detection>` via the unchanged
// W4/W6-A path. There is intentionally NO `DetectorPort` wrapper here:
// that port binds `FramePacket` capture/time types and exactly-once
// pipeline semantics (see `dfine_detector.hpp`), and no spatial/session
// wiring belongs to this milestone.
//
// Failure modes (ALL fail-closed: `ok()==false` / `Run` returns false
// with both output vectors left empty -- never partial results, never an
// exception escaping to the caller, no I/O from this module):
//   T1. Empty engine path, or the file cannot be opened/read (or is empty).
//   T2. `IRuntime` creation or `deserializeCudaEngine` fails (bad bytes,
//       wrong TensorRT major, corrupt file).
//   T3. A required binding name is missing ("pixel_values", "logits",
//       "pred_boxes"), or a binding has the wrong direction.
//   T4. A binding has unexpected dimensions (must be exactly the shapes
//       above, static batch 1).
//   T5. A binding has a non-float32 data type. (Internal fp16 compute is
//       fine; engine I/O must stay float32. An engine built with half
//       I/O is rejected here rather than silently misread.)
//   T6. `IExecutionContext` creation, CUDA stream creation, or any
//       `cudaMalloc` fails (device unavailable / out of memory).
//   T7. `Run` called when `!ok()`, or with any null pointer.
//   T8. `Run` device failure: tensor address binding, H2D copy,
//       `enqueueV3`, stream synchronize, or D2H copy reports an error.
// Stub build (`KSI_SAFETY_EDGE_HAS_TENSORRT` undefined, i.e. the
// `KSI_SAFETY_EDGE_ENABLE_TENSORRT=OFF` default): `Available()` is false,
// the ctor never touches the filesystem and `ok()` is false, `Run`
// returns false (clearing any non-null outputs).
//
// Threading: one `TensorRtRuntime` owns one `IExecutionContext`, which
// is NOT thread-safe -- external synchronization is required for
// concurrent `Run()` calls on the same object. Distinct objects are
// independent. Deterministic: no RNG, no clock, no I/O after the ctor.

#ifndef KSI_SAFETY_PERCEPTION_TENSORRT_RUNTIME_HPP
#define KSI_SAFETY_PERCEPTION_TENSORRT_RUNTIME_HPP

#include <cstddef>
#include <string>
#include <vector>

namespace ksi::safety {

class TensorRtRuntime {
  public:
    // Tensor element counts (engine contract, NOT tunable thresholds).
    static constexpr std::size_t kInputSize = 3U * 640U * 640U;  // 1228800
    static constexpr std::size_t kLogitsSize = 300U * 80U;       // 24000
    static constexpr std::size_t kBoxesSize = 300U * 4U;         // 1200

    // Per-stage wall times from `RunTimed` (milliseconds, steady_clock).
    //   h2d_ms:     host->device input upload (synchronous copy).
    //   enqueue_ms: `enqueueV3` launch call only (async submit, no wait).
    //   sync_ms:    `cudaStreamSynchronize` after enqueue (GPU compute +
    //               queued work completing; typically dominant).
    //   d2h_ms:     device->host output download, both tensors
    //               (synchronous copies after the stream is idle).
    struct StageTimesMs {
        double h2d_ms = 0.0;
        double enqueue_ms = 0.0;
        double sync_ms = 0.0;
        double d2h_ms = 0.0;
    };

    // True iff this build links a real TensorRT backend.
    static bool Available() noexcept;

    // Load + validate the serialized engine at `engine_path` (T1-T6 leave
    // `ok()==false`; never throws).
    explicit TensorRtRuntime(const std::string& engine_path);
    ~TensorRtRuntime();

    TensorRtRuntime(const TensorRtRuntime&) = delete;
    TensorRtRuntime& operator=(const TensorRtRuntime&) = delete;

    bool ok() const noexcept { return ok_; }

    // Run one inference. `input_chw` must point to `kInputSize` floats
    // (R plane, then G, then B). On success fills `*logits` (`kLogitsSize`)
    // and `*boxes` (`kBoxesSize`) and returns true. On ANY failure (T7/T8)
    // returns false with both outputs empty. Never throws.
    bool Run(const float* input_chw, std::vector<float>* logits,
             std::vector<float>* boxes) noexcept;

    // Timed variant of `Run` (W9-FU, user-requested measurement): same
    // fail-closed semantics (T7/T8 -> false + empty outputs, never
    // throws), plus per-stage wall times in `*out` on success.
    // Attribution: each field times exactly the named API call(s) via
    // `steady_clock` (H2D `cudaMemcpy`, `enqueueV3`,
    // `cudaStreamSynchronize`, D2H `cudaMemcpy` x2), so every millisecond
    // is attributable to that stage. A null `out` is fail-closed too
    // (false + empty outputs); on any failure a non-null `*out` is
    // zeroed. Stub build: always false (outputs cleared, `*out` zeroed).
    //
    // PIPELINING CAVEAT: `Run` pipelines H2D -> enqueue -> D2H on one
    // stream behind a single terminal synchronize, so launch latencies
    // overlap queued work. `RunTimed` instead inserts host-visible
    // boundaries (synchronous H2D, sync-after-enqueue, synchronous D2H),
    // which serializes the stages. The stage SUM can therefore differ
    // from a pipelined `Run` wall time in either direction (on the W9-FU
    // Jetson run it came out slightly below the pipelined mean); that
    // difference is the cost of attribution, not a regression.
    // `enqueue_ms` times the `enqueueV3`
    // call and `sync_ms` the subsequent wait: whether the submit returns
    // immediately (GPU work observed in `sync_ms`) or blocks until
    // kernels complete (work observed in `enqueue_ms`, `sync_ms`
    // residual) depends on backend/stream behavior -- on the W9-FU
    // Jetson run `enqueueV3` blocked (~20 ms, `sync_ms` ~0.5 ms), so
    // read GPU compute as `enqueue_ms + sync_ms` combined.
    bool RunTimed(const float* input_chw, std::vector<float>* logits,
                  std::vector<float>* boxes, StageTimesMs* out) noexcept;

  private:
    bool ok_ = false;
    // Opaque backend handles (IRuntime/ICudaEngine/IExecutionContext,
    // CUDA stream + device buffers) -- void* so this header compiles
    // without TensorRT/CUDA headers. Null unless TRT is enabled AND the
    // ctor validated everything.
    void* runtime_ = nullptr;
    void* engine_ = nullptr;
    void* context_ = nullptr;
    void* stream_ = nullptr;
    void* device_input_ = nullptr;
    void* device_logits_ = nullptr;
    void* device_boxes_ = nullptr;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_TENSORRT_RUNTIME_HPP
