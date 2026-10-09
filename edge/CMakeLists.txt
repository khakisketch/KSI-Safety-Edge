cmake_minimum_required(VERSION 3.16...3.28)

project(ksi_safety_edge VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(KSI_SAFETY_EDGE_ENABLE_SANITIZERS
  "Enable AddressSanitizer + UndefinedBehaviorSanitizer (Debug recommended)" OFF)

option(KSI_SAFETY_EDGE_ENABLE_TENSORRT
  "Enable TensorRT runtime adapter (requires NvInfer + CUDA)" OFF)

find_package(Threads REQUIRED)

add_library(ksi_safety_edge_core STATIC
  src/capture/frame_queue.cpp
  src/time/provenance.cpp
  src/time/topic_ring.cpp
  src/time/matching.cpp
  src/time/interpolation.cpp
  src/perception/pipeline.cpp
  src/perception/dfine.cpp
  src/perception/dfine_detector.cpp
  src/perception/dfine_detector_port.cpp
  src/perception/detector.cpp
  src/perception/tensorrt_runtime.cpp
  src/perception/tracker.cpp
  src/runtime/session_runner.cpp
  src/replay/replay_manifest.cpp
  src/replay/frame_source.cpp
  src/replay/telemetry_source.cpp
  src/replay/policy_file.cpp
  src/geometry/coordinate.cpp
  src/geometry/projection.cpp
  src/spatial/distance.cpp
  src/spatial/relation.cpp
  src/spatial/ground_estimator.cpp
  src/risk/policy.cpp
  src/risk/episode.cpp
  src/risk/engine.cpp
  src/session/session.cpp
)
target_include_directories(ksi_safety_edge_core PUBLIC include)
target_link_libraries(ksi_safety_edge_core PUBLIC Threads::Threads)
target_compile_options(ksi_safety_edge_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
  target_compile_options(ksi_safety_edge_core PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all -g)
  target_link_options(ksi_safety_edge_core PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all)
endif()

if(KSI_SAFETY_EDGE_ENABLE_TENSORRT)
  find_path(KSI_TRT_INCLUDE_DIR NvInfer.h
    HINTS /usr/include/aarch64-linux-gnu /usr/include)
  if(NOT KSI_TRT_INCLUDE_DIR)
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON but NvInfer.h was not found")
  endif()
  find_library(KSI_NVINFER_LIB nvinfer
    HINTS /usr/local/cuda-11.4 /usr/lib/aarch64-linux-gnu
    PATH_SUFFIXES lib64 lib)
  if(NOT KSI_NVINFER_LIB)
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON but library 'nvinfer' was not found")
  endif()
  find_library(KSI_CUDART_LIB cudart
    HINTS /usr/local/cuda-11.4 /usr/lib/aarch64-linux-gnu
    PATH_SUFFIXES lib64 lib)
  if(NOT KSI_CUDART_LIB)
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON but library 'cudart' was not found")
  endif()
  find_path(KSI_CUDA_INCLUDE_DIR cuda_runtime_api.h
    HINTS /usr/local/cuda-11.4 /usr/local/cuda
    PATH_SUFFIXES include)
  if(NOT KSI_CUDA_INCLUDE_DIR)
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON but 'cuda_runtime_api.h' was not found")
  endif()
  target_include_directories(ksi_safety_edge_core PRIVATE
    ${KSI_TRT_INCLUDE_DIR} ${KSI_CUDA_INCLUDE_DIR})
  target_compile_definitions(ksi_safety_edge_core PUBLIC
    KSI_SAFETY_EDGE_HAS_TENSORRT=1)
  target_link_libraries(ksi_safety_edge_core PRIVATE
    ${KSI_NVINFER_LIB} ${KSI_CUDART_LIB})
  add_executable(dfine_trt_smoke tools/dfine_trt_smoke.cpp)
  target_link_libraries(dfine_trt_smoke PRIVATE ksi_safety_edge_core)
  target_compile_options(dfine_trt_smoke PRIVATE -Wall -Wextra -Wpedantic
    -Werror)
endif()

# --- Host tools (no TensorRT dependency) ------------------------------------------
add_executable(preprocess_dump tools/preprocess_dump.cpp)
target_link_libraries(preprocess_dump PRIVATE ksi_safety_edge_core)
target_compile_options(preprocess_dump PRIVATE -Wall -Wextra -Wpedantic -Werror)
if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
  target_compile_options(preprocess_dump PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all -g)
  target_link_options(preprocess_dump PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all)
endif()

add_executable(replay_run tools/replay_run.cpp)
target_link_libraries(replay_run PRIVATE ksi_safety_edge_core)
target_compile_options(replay_run PRIVATE -Wall -Wextra -Wpedantic -Werror)
if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
  target_compile_options(replay_run PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all -g)
  target_link_options(replay_run PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all)
endif()

enable_testing()

function(ksi_safety_edge_test name source)
  add_executable(${name} ${source})
  target_include_directories(${name} PRIVATE include tests)
  target_link_libraries(${name} PRIVATE ksi_safety_edge_core)
  target_compile_options(${name} PRIVATE -Wall -Wextra -Wpedantic -Werror)
  if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
    target_compile_options(${name} PRIVATE
      -fsanitize=address,undefined -fno-sanitize-recover=all -g)
    target_link_options(${name} PRIVATE
      -fsanitize=address,undefined -fno-sanitize-recover=all)
  endif()
  add_test(NAME ${name} COMMAND ${name})
endfunction()

ksi_safety_edge_test(capture_queue tests/test_capture_queue.cpp)
ksi_safety_edge_test(provenance tests/test_provenance.cpp)
ksi_safety_edge_test(ring tests/test_ring.cpp)
ksi_safety_edge_test(matching tests/test_matching.cpp)
ksi_safety_edge_test(interpolation tests/test_interpolation.cpp)
ksi_safety_edge_test(perception tests/test_perception.cpp)
ksi_safety_edge_test(dfine tests/test_dfine.cpp)
ksi_safety_edge_test(tracker tests/test_tracker.cpp)
ksi_safety_edge_test(geometry tests/test_geometry.cpp)
ksi_safety_edge_test(spatial tests/test_spatial.cpp)
ksi_safety_edge_test(ground_estimator tests/test_ground_estimator.cpp)
ksi_safety_edge_test(risk tests/test_risk.cpp)
ksi_safety_edge_test(session tests/test_session.cpp)
ksi_safety_edge_test(chain tests/test_chain.cpp)
ksi_safety_edge_test(replay tests/test_replay.cpp)
ksi_safety_edge_test(tensorrt tests/test_tensorrt.cpp)
