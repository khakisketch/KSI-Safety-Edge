cmake_minimum_required(VERSION 3.16...3.28)

project(ksi_safety_edge VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(KSI_SAFETY_EDGE_ENABLE_SANITIZERS
  "Enable AddressSanitizer + UndefinedBehaviorSanitizer (Debug recommended)" OFF)

find_package(Threads REQUIRED)

add_library(ksi_safety_edge_core STATIC
  src/capture/frame_queue.cpp
  src/time/provenance.cpp
  src/time/topic_ring.cpp
  src/time/matching.cpp
  src/time/interpolation.cpp
  src/perception/pipeline.cpp
  src/perception/dfine.cpp
  src/perception/tracker.cpp
  src/geometry/coordinate.cpp
  src/geometry/projection.cpp
  src/spatial/distance.cpp
  src/spatial/relation.cpp
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
ksi_safety_edge_test(risk tests/test_risk.cpp)
ksi_safety_edge_test(session tests/test_session.cpp)
