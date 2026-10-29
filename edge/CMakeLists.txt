cmake_minimum_required(VERSION 3.16...3.28)

project(ksi_safety_edge VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Pin a default build type (same convention as psdk-app): an unset type means
# -O0, and optimization level changes FP codegen (observed: preprocess
# boundary pixels flip by 1 uint8 between -O0 and -O2), so recordings and
# measurements must come from an explicitly known build mode.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "build type" FORCE)
endif()

option(KSI_SAFETY_EDGE_ENABLE_SANITIZERS
  "Enable AddressSanitizer + UndefinedBehaviorSanitizer (Debug recommended)" OFF)

option(KSI_SAFETY_EDGE_ENABLE_TENSORRT
  "Enable TensorRT runtime adapter (requires NvInfer + CUDA)" OFF)

find_package(Threads REQUIRED)

add_library(ksi_safety_edge_core STATIC
  src/capture/frame_queue.cpp
  src/time/provenance.cpp
  src/time/topic_ring.cpp
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
  src/runtime/run_control.cpp
  src/runtime/bundle_config.cpp
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
  src/transport/digest.cpp
  src/transport/audit_trail.cpp
  src/transport/json.cpp
  src/transport/mop_frame.cpp
  src/transport/mop_envelope.cpp
  src/transport/mop_evidence.cpp
  src/transport/mop_tick_evidence.cpp
  src/transport/durable_spool.cpp
  src/transport/mop_endpoint.cpp
  src/transport/mop_link.cpp
  src/evidence/store.cpp
  src/evidence/recorder.cpp
  src/observation/assessment.cpp
  src/observation/context_ring.cpp
  src/observation/autonomous_processor.cpp
  src/observation/planner.cpp
  src/observation/source_roi.cpp
  src/observation/source_attention_policy.cpp
  src/observation/source_attention.cpp
  # Unit B (separate owner: edge/.../observation/reobservation.*) — registered
  # here per parent integration note; Unit A neither reads nor drives it.
  src/observation/reobservation.cpp
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

# 가상 Manifold(호스트 Edge 대역): TCP 위에서 실제 Edge 엔진을 돌린다.
# 실제 Manifold가 없는 동안 프로토콜·계약 계층을 실행 가능하게 검증하기 위한 것.
add_executable(mop_server tools/mop_server.cpp)
target_link_libraries(mop_server PRIVATE ksi_safety_edge_core)
target_compile_options(mop_server PRIVATE -Wall -Wextra -Wpedantic -Werror)
if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
  target_compile_options(mop_server PRIVATE
    -fsanitize=address,undefined -fno-sanitize-recover=all -g)
  target_link_options(mop_server PRIVATE
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

add_executable(source_attention_bench tools/source_attention_bench.cpp)
target_link_libraries(source_attention_bench PRIVATE ksi_safety_edge_core)
target_compile_options(source_attention_bench PRIVATE -Wall -Wextra -Wpedantic -Werror)
if(KSI_SAFETY_EDGE_ENABLE_SANITIZERS)
  target_compile_options(source_attention_bench PRIVATE -fsanitize=address,undefined -fno-sanitize-recover=all -g)
  target_link_options(source_attention_bench PRIVATE -fsanitize=address,undefined -fno-sanitize-recover=all)
endif()

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

ksi_safety_edge_test(evidence_store tests/test_evidence_store.cpp)
ksi_safety_edge_test(evidence_recorder tests/test_evidence_recorder.cpp)
ksi_safety_edge_test(mop_qos tests/test_mop_qos.cpp)
ksi_safety_edge_test(json_canonical tests/test_json_canonical.cpp)
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
ksi_safety_edge_test(mop_frame tests/test_mop_frame.cpp)
ksi_safety_edge_test(mop_json tests/test_mop_json.cpp)
ksi_safety_edge_test(mop_tick tests/test_mop_tick.cpp)
ksi_safety_edge_test(mop_e2e tests/test_mop_e2e.cpp)
ksi_safety_edge_test(rc_bundle tests/test_rc_bundle.cpp)
ksi_safety_edge_test(runtime_control tests/test_runtime_control.cpp)
ksi_safety_edge_test(run_control tests/test_run_control.cpp)
ksi_safety_edge_test(durable_spool tests/test_durable_spool.cpp)
ksi_safety_edge_test(audit_trail tests/test_audit_trail.cpp)
# The audit/config policy test includes the device adapter header (host-safe:
# no PSDK includes; inline-only use, no device link needed).
target_include_directories(audit_trail PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/psdk-app/src)
ksi_safety_edge_test(run_health tests/test_run_health.cpp)
ksi_safety_edge_test(app_config tests/test_app_config.cpp)
# The startup-config test includes the DJI-free device arg helper
# (host-safe: no PSDK includes, pure argv decision logic).
target_include_directories(app_config PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/psdk-app/src)
ksi_safety_edge_test(evidence_workflow tests/test_evidence_workflow.cpp)
ksi_safety_edge_test(autonomy_stage1 tests/test_autonomy_stage1.cpp)
ksi_safety_edge_test(observation_planner tests/test_observation_planner.cpp)
ksi_safety_edge_test(source_roi tests/test_source_roi.cpp)
ksi_safety_edge_test(readability tests/test_readability.cpp)
ksi_safety_edge_test(source_attention_policy tests/test_source_attention_policy.cpp)
ksi_safety_edge_test(source_attention tests/test_source_attention.cpp)
ksi_safety_edge_test(evidence_saturation tests/test_evidence_saturation.cpp)
target_include_directories(source_attention PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/psdk-app/src)
target_include_directories(source_attention_policy PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/psdk-app/src)
ksi_safety_edge_test(assessment_delivery tests/test_assessment_delivery.cpp)
# Unit B (separate owner). Registered, not wired: no fake-B to real camera,
# no coupling into the passive Unit A path.
ksi_safety_edge_test(reobservation tests/test_reobservation.cpp)
get_filename_component(KSI_MOP_CONTRACTS_DIR
  "${CMAKE_CURRENT_SOURCE_DIR}/../contracts/mop" ABSOLUTE)
target_compile_definitions(mop_e2e PRIVATE
  KSI_MOP_CONTRACTS_DIR="${KSI_MOP_CONTRACTS_DIR}"
  KSI_MOP_TEST_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
target_compile_definitions(rc_bundle PRIVATE
  KSI_MOP_CONTRACTS_DIR="${KSI_MOP_CONTRACTS_DIR}")
