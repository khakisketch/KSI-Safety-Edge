// preprocess_dump: host tool (no TensorRT). Runs PreprocessRgb on a raw RGB
// file and writes the 1x3x640x640 CHW float32 tensor, so the fidelity
// script can compare the ACTUAL C++ output against PIL/RTDetrImageProcessor.
//
// usage: preprocess_dump --input PATH --width W --height H [--out PATH]
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ksi_safety/perception/dfine.hpp"

int main(int argc, char** argv) {
    std::string input;
    std::string out_path = "preprocess_dump.f32";
    int width = 0;
    int height = 0;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--input") == 0 && i + 1 < argc) {
            input = argv[++i];
        } else if (std::strcmp(argv[i], "--width") == 0 && i + 1 < argc) {
            width = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--height") == 0 && i + 1 < argc) {
            height = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out_path = argv[++i];
        } else {
            std::fprintf(stderr,
                         "usage: preprocess_dump --input PATH --width W "
                         "--height H [--out PATH]\n");
            return 2;
        }
    }
    if (input.empty() || width <= 0 || height <= 0) {
        std::fprintf(stderr, "error: --input/--width/--height are required\n");
        return 2;
    }
    const std::size_t bytes = static_cast<std::size_t>(width) *
                              static_cast<std::size_t>(height) * 3U;
    std::vector<std::uint8_t> rgb(bytes);
    FILE* f = std::fopen(input.c_str(), "rb");
    if (f == nullptr) {
        std::fprintf(stderr, "error: cannot open input '%s'\n", input.c_str());
        return 1;
    }
    const std::size_t got = std::fread(rgb.data(), 1, bytes, f);
    std::fclose(f);
    if (got != bytes) {
        std::fprintf(stderr,
                     "error: input '%s' is %zu bytes, expected %zu "
                     "(%dx%d RGB)\n",
                     input.c_str(), got, bytes, width, height);
        return 1;
    }
    ksi::safety::DfineConfig cfg;
    std::vector<float> tensor(static_cast<std::size_t>(3) * cfg.input_width *
                              cfg.input_height);
    if (!ksi::safety::PreprocessRgb(rgb.data(), width, height, tensor.data(),
                                    cfg)) {
        std::fprintf(stderr, "error: PreprocessRgb refused the input\n");
        return 1;
    }
    FILE* o = std::fopen(out_path.c_str(), "wb");
    if (o == nullptr) {
        std::fprintf(stderr, "error: cannot write '%s'\n", out_path.c_str());
        return 1;
    }
    const std::size_t written =
        std::fwrite(tensor.data(), sizeof(float), tensor.size(), o);
    std::fclose(o);
    if (written != tensor.size()) {
        std::fprintf(stderr, "error: short write to '%s'\n", out_path.c_str());
        return 1;
    }
    std::printf("preprocess_dump: %dx%d -> 1x3x%dx%d, %zu floats -> %s\n",
                width, height, cfg.input_width, cfg.input_height,
                tensor.size(), out_path.c_str());
    return 0;
}
