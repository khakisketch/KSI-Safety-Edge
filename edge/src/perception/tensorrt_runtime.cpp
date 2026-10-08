// See tensorrt_runtime.hpp for the contract and failure-mode list (T1-T8).

#include "ksi_safety/perception/tensorrt_runtime.hpp"

#ifdef KSI_SAFETY_EDGE_HAS_TENSORRT
#include <NvInfer.h>
#include <cuda_runtime_api.h>
#endif

#include <cstdio>
#include <fstream>

namespace ksi::safety {

bool TensorRtRuntime::Available() noexcept {
#ifdef KSI_SAFETY_EDGE_HAS_TENSORRT
    return true;
#else
    return false;
#endif
}

#ifdef KSI_SAFETY_EDGE_HAS_TENSORRT

namespace {

// Silent logger: the library performs no I/O; diagnostics belong to the
// caller (e.g. the smoke tool prints its own messages).
class TrtLogger : public nvinfer1::ILogger {
  public:
    void log(Severity /*severity*/, const char* /*msg*/) noexcept override {}
};

TrtLogger& Logger() {
    static TrtLogger logger;
    return logger;
}

void FreeAll(nvinfer1::IRuntime* rt, nvinfer1::ICudaEngine* eng,
             nvinfer1::IExecutionContext* ctx, cudaStream_t stream,
             void* d_in, void* d_logits, void* d_boxes) noexcept {
    if (d_in != nullptr) {
        (void)cudaFree(d_in);
    }
    if (d_logits != nullptr) {
        (void)cudaFree(d_logits);
    }
    if (d_boxes != nullptr) {
        (void)cudaFree(d_boxes);
    }
    if (stream != nullptr) {
        (void)cudaStreamDestroy(stream);
    }
    // TensorRT 8.x: `destroy()` is deprecated in favour of `delete`.
    delete ctx;
    delete eng;
    delete rt;
}

bool DimsIs(const nvinfer1::Dims& dims, int n0, int n1, int n2, int n3) {
    if (dims.nbDims == 3) {
        return n3 < 0 && dims.d[0] == n0 && dims.d[1] == n1 &&
               dims.d[2] == n2;
    }
    if (dims.nbDims == 4) {
        return dims.d[0] == n0 && dims.d[1] == n1 && dims.d[2] == n2 &&
               dims.d[3] == n3;
    }
    return false;
}

}  // namespace

TensorRtRuntime::TensorRtRuntime(const std::string& engine_path) {
    nvinfer1::IRuntime* rt = nullptr;
    nvinfer1::ICudaEngine* eng = nullptr;
    nvinfer1::IExecutionContext* ctx = nullptr;
    cudaStream_t stream = nullptr;
    void* d_in = nullptr;
    void* d_logits = nullptr;
    void* d_boxes = nullptr;
    bool valid = false;
    try {
        std::vector<char> blob;
        {
            if (engine_path.empty()) {
                valid = false;  // T1.
                goto done;
            }
            std::ifstream file(engine_path,
                               std::ios::binary | std::ios::ate);
            if (!file) {
                valid = false;  // T1.
                goto done;
            }
            const std::ifstream::pos_type size = file.tellg();
            if (size <= 0) {
                valid = false;  // T1.
                goto done;
            }
            file.seekg(0, std::ios::beg);
            blob.resize(static_cast<std::size_t>(size));
            if (!file.read(blob.data(), size)) {
                valid = false;  // T1.
                goto done;
            }
        }
        rt = nvinfer1::createInferRuntime(Logger());
        if (rt == nullptr) {
            goto done;  // T2.
        }
        eng = rt->deserializeCudaEngine(blob.data(), blob.size());
        if (eng == nullptr) {
            goto done;  // T2.
        }
        {
            // Unknown tensor names report kNONE direction: a wrong engine
            // (missing/renamed tensors) fails here.
            if (eng->getTensorIOMode("pixel_values") !=
                    nvinfer1::TensorIOMode::kINPUT ||
                eng->getTensorIOMode("logits") !=
                    nvinfer1::TensorIOMode::kOUTPUT ||
                eng->getTensorIOMode("pred_boxes") !=
                    nvinfer1::TensorIOMode::kOUTPUT) {
                goto done;  // T3.
            }
            if (!DimsIs(eng->getTensorShape("pixel_values"), 1, 3, 640,
                        640) ||
                !DimsIs(eng->getTensorShape("logits"), 1, 300, 80, -1) ||
                !DimsIs(eng->getTensorShape("pred_boxes"), 1, 300, 4, -1)) {
                goto done;  // T4.
            }
            // NOTE: the element count is pinned independently of the shape
            // spelling (3-D vs 4-D with a trailing 1): `kLogitsSize` /
            // `kBoxesSize` fix the device/host transfer sizes below, and
            // `DfineDetector` re-checks the vector sizes before decode.
            if (eng->getTensorDataType("pixel_values") !=
                    nvinfer1::DataType::kFLOAT ||
                eng->getTensorDataType("logits") !=
                    nvinfer1::DataType::kFLOAT ||
                eng->getTensorDataType("pred_boxes") !=
                    nvinfer1::DataType::kFLOAT) {
                goto done;  // T5.
            }
        }
        ctx = eng->createExecutionContext();
        if (ctx == nullptr) {
            goto done;  // T6.
        }
        if (cudaStreamCreate(&stream) != cudaSuccess) {
            stream = nullptr;
            goto done;  // T6.
        }
        if (cudaMalloc(&d_in, kInputSize * sizeof(float)) != cudaSuccess) {
            d_in = nullptr;
            goto done;  // T6.
        }
        if (cudaMalloc(&d_logits, kLogitsSize * sizeof(float)) !=
            cudaSuccess) {
            d_logits = nullptr;
            goto done;  // T6.
        }
        if (cudaMalloc(&d_boxes, kBoxesSize * sizeof(float)) != cudaSuccess) {
            d_boxes = nullptr;
            goto done;  // T6.
        }
        valid = true;
    done:;
    } catch (...) {
        valid = false;
    }
    if (!valid) {
        FreeAll(rt, eng, ctx, stream, d_in, d_logits, d_boxes);
        return;
    }
    runtime_ = rt;
    engine_ = eng;
    context_ = ctx;
    stream_ = stream;
    device_input_ = d_in;
    device_logits_ = d_logits;
    device_boxes_ = d_boxes;
    ok_ = true;
}

TensorRtRuntime::~TensorRtRuntime() {
    FreeAll(static_cast<nvinfer1::IRuntime*>(runtime_),
            static_cast<nvinfer1::ICudaEngine*>(engine_),
            static_cast<nvinfer1::IExecutionContext*>(context_),
            static_cast<cudaStream_t>(stream_), device_input_, device_logits_,
            device_boxes_);
}

bool TensorRtRuntime::Run(const float* input_chw, std::vector<float>* logits,
                           std::vector<float>* boxes) noexcept {
    if (logits != nullptr) {
        try {
            logits->clear();
        } catch (...) {
        }
    }
    if (boxes != nullptr) {
        try {
            boxes->clear();
        } catch (...) {
        }
    }
    if (input_chw == nullptr || logits == nullptr || boxes == nullptr) {
        return false;  // T7 (outputs already empty).
    }
    if (!ok_) {
        return false;  // T7.
    }
    try {
        auto* ctx = static_cast<nvinfer1::IExecutionContext*>(context_);
        auto stream = static_cast<cudaStream_t>(stream_);
        if (ctx == nullptr || stream == nullptr ||
            device_input_ == nullptr || device_logits_ == nullptr ||
            device_boxes_ == nullptr) {
            return false;  // T7 (defensive; ctor guarantees this).
        }
        logits->resize(kLogitsSize);
        boxes->resize(kBoxesSize);
        if (!ctx->setTensorAddress("pixel_values", device_input_) ||
            !ctx->setTensorAddress("logits", device_logits_) ||
            !ctx->setTensorAddress("pred_boxes", device_boxes_)) {
            goto fail;  // T8.
        }
        if (cudaMemcpyAsync(device_input_, input_chw,
                            kInputSize * sizeof(float), cudaMemcpyHostToDevice,
                            stream) != cudaSuccess) {
            goto fail;  // T8.
        }
        if (!ctx->enqueueV3(stream)) {
            goto fail;  // T8.
        }
        if (cudaMemcpyAsync(logits->data(), device_logits_,
                            kLogitsSize * sizeof(float),
                            cudaMemcpyDeviceToHost,
                            stream) != cudaSuccess) {
            goto fail;  // T8.
        }
        if (cudaMemcpyAsync(boxes->data(), device_boxes_,
                            kBoxesSize * sizeof(float), cudaMemcpyDeviceToHost,
                            stream) != cudaSuccess) {
            goto fail;  // T8.
        }
        if (cudaStreamSynchronize(stream) != cudaSuccess) {
            goto fail;  // T8.
        }
        return true;
    } catch (...) {
    }
fail:
    try {
        logits->clear();
        boxes->clear();
    } catch (...) {
    }
    return false;
}

#else  // Stub build: no TensorRT/CUDA dependency.

TensorRtRuntime::TensorRtRuntime(const std::string& /*engine_path*/) {}

TensorRtRuntime::~TensorRtRuntime() {}

bool TensorRtRuntime::Run(const float* /*input_chw*/,
                           std::vector<float>* logits,
                           std::vector<float>* boxes) noexcept {
    if (logits != nullptr) {
        try {
            logits->clear();
        } catch (...) {
        }
    }
    if (boxes != nullptr) {
        try {
            boxes->clear();
        } catch (...) {
        }
    }
    return false;
}

#endif

}  // namespace ksi::safety
