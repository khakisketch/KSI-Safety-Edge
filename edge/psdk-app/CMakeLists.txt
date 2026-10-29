# edge/psdk-app — KSI Safety Edge PSDK application (v0).
#
# Standalone project: configure from this directory (not from edge/).
# The core library (edge/) is pulled in via add_subdirectory below.
#
# Device build (Manifold 3, PSDK at /open_app/psdk):
#   cmake -S psdk-app -B psdk-app/build -DKSI_PSDK_ROOT=/open_app/psdk \
#     -DKSI_PSDK_APP_INFO_HEADER=$PWD/psdk-app/config/dji_sdk_app_info.h \
#     -DKSI_SAFETY_EDGE_DPK_USER_APP_ID=191589 \
#     [-DKSI_SAFETY_EDGE_ENGINE_PATH=/open_app/models/dfine.engine \
#      -DKSI_SAFETY_EDGE_MODEL_ID=<real-model-id>]
#   cmake --build psdk-app/build -j4
#   cmake --build psdk-app/build --target ksi_safety_edge_dpk
#
# The argvless DPK binary starts the operational MOP service (channel
# KSI_SAFETY_EDGE_MOP_CHANNEL, unbounded, gate closed until APPLY+START).
# Engine/model installer options above are optional: empty means the engine
# is absent (ticks stay NOT_RUN/degraded, never fabricated).
#
# cmake range follows the edge/ convention (device cmake 3.16.3, CI 3.28).
cmake_minimum_required(VERSION 3.16...3.28)
project(ksi_safety_edge_psdk_app VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
# GNU extensions are required — pthread_setname_np/sigaction and friends live
# outside ISO C11 (same reason as the SI psdk-tap tree this glue came from).
set(CMAKE_C_EXTENSIONS ON)
add_compile_definitions(_GNU_SOURCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "build type" FORCE)
endif()

# Lets ctest discover the core tests pulled in via add_subdirectory below.
enable_testing()

# --- Credential gate (fail-closed) ---------------------------------------------
# dji_sdk_app_info.h is a build input, not a source: without it configure fails
# here instead of silently building a stub. An external owner-only path is
# accepted so DPK builds never force the credential into the worktree.
set(KSI_PSDK_APP_INFO_HEADER "${CMAKE_CURRENT_SOURCE_DIR}/config/dji_sdk_app_info.h"
  CACHE FILEPATH "DJI PSDK credential header (untracked deployment input)")
if(NOT EXISTS "${KSI_PSDK_APP_INFO_HEADER}")
  message(FATAL_ERROR
    "DJI credential header not found: ${KSI_PSDK_APP_INFO_HEADER}\n"
    "Copy config/dji_sdk_app_info.h.example to dji_sdk_app_info.h and fill it "
    "with the developer.dji.com values, or pass "
    "-DKSI_PSDK_APP_INFO_HEADER=<outside path>.\n"
    "This filename is gitignored (`**/dji_sdk_app_info.h`) — it can never be "
    "committed. See edge/psdk-app/README.md.")
endif()
get_filename_component(_app_info_name "${KSI_PSDK_APP_INFO_HEADER}" NAME)
if(NOT _app_info_name STREQUAL "dji_sdk_app_info.h")
  message(FATAL_ERROR "KSI_PSDK_APP_INFO_HEADER must be named dji_sdk_app_info.h")
endif()
get_filename_component(KSI_PSDK_APP_INFO_DIR "${KSI_PSDK_APP_INFO_HEADER}" DIRECTORY)

# --- PSDK location (fail-closed) -------------------------------------------------
set(KSI_PSDK_ROOT "/open_app/psdk" CACHE PATH "PSDK tree path")
if(NOT EXISTS "${KSI_PSDK_ROOT}/psdk_lib/include/dji_core.h")
  message(FATAL_ERROR
    "PSDK not found at ${KSI_PSDK_ROOT} (psdk_lib/include/dji_core.h missing). "
    "Pass -DKSI_PSDK_ROOT=<path> — never silently build a stub.")
endif()
set(KSI_PSDK_LIB_PATH
  "${KSI_PSDK_ROOT}/psdk_lib/lib/aarch64-linux-gnu-gcc/libpayloadsdk.a")
if(NOT EXISTS "${KSI_PSDK_LIB_PATH}")
  message(FATAL_ERROR "PSDK static library not found: ${KSI_PSDK_LIB_PATH}")
endif()
add_library(psdk_static STATIC IMPORTED)
set_target_properties(psdk_static PROPERTIES IMPORTED_LOCATION "${KSI_PSDK_LIB_PATH}")

# --- Options ---------------------------------------------------------------------
set(KSI_SAFETY_EDGE_DPK_USER_APP_ID "" CACHE STRING
  "DJI-issued user_app_id for this DPK (empty means no DPK target)")
set(KSI_SAFETY_EDGE_DPK_FIRMWARE_VERSION "00.01.00.00" CACHE STRING
  "DPK firmware version (form 00.00.00.00) — must equal the version the app
   reports via DjiCore_SetFirmwareVersion, or dji_app_mgr install verification fails")
if(NOT KSI_SAFETY_EDGE_DPK_FIRMWARE_VERSION MATCHES
   "^[0-9][0-9]\\.[0-9][0-9]\\.[0-9][0-9]\\.[0-9][0-9]$")
  message(FATAL_ERROR
    "KSI_SAFETY_EDGE_DPK_FIRMWARE_VERSION must have the form 00.00.00.00")
endif()
set(KSI_SAFETY_EDGE_CAMERA_SOURCE "DJI_LIVEVIEW_CAMERA_SOURCE_M4E_VIS" CACHE STRING
  "E_DjiLiveViewCameraSource token for the RGB image stream")
# Operational startup defaults (trusted installer input). The DPK ships
# `"bin"` with `"userconfig": []`, so the installed app starts argvless:
# argvless MUST start the persistent MOP service (channel below), with NO
# deadline and NO automatic processing. The legacy 60 s capture workflow is
# available only as an explicit diagnostic (--standalone, or --mop-channel
# -1 with a finite --duration-sec).
#
# RETIRED: KSI_SAFETY_EDGE_DURATION_SEC. A stale cache entry with the old
# 60 s default must NOT force an operational timeout; unset it so old
# build dirs cannot leak it back.
unset(KSI_SAFETY_EDGE_DURATION_SEC CACHE)
set(KSI_SAFETY_EDGE_MOP_CHANNEL 0 CACHE STRING
  "Default MOP channel for the argvless operational service (RC pipeline 0)")
if(NOT KSI_SAFETY_EDGE_MOP_CHANNEL MATCHES "^[0-9]+$" OR
   KSI_SAFETY_EDGE_MOP_CHANNEL GREATER 65535)
  message(FATAL_ERROR
    "KSI_SAFETY_EDGE_MOP_CHANNEL must be 0..65535")
endif()
set(KSI_SAFETY_EDGE_DIAG_DURATION_SEC 60 CACHE STRING
  "Default finite duration in seconds for explicit diagnostic runs")
if(NOT KSI_SAFETY_EDGE_DIAG_DURATION_SEC MATCHES "^[1-9][0-9]*$")
  message(FATAL_ERROR
    "KSI_SAFETY_EDGE_DIAG_DURATION_SEC must be a positive integer")
endif()
set(KSI_SAFETY_EDGE_ENGINE_PATH "" CACHE STRING "Deploy-default TensorRT engine path (empty = engine absent; CLI --engine overrides).")
set(KSI_SAFETY_EDGE_MODEL_ID "" CACHE STRING "Deploy-default model id (empty = keep the code default; CLI --model-id overrides).")
set(KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR "" CACHE STRING
  "Deploy-default autonomy evidence directory (empty = passive autonomy off;
    non-empty enables SHADOW assessment/evidence for the argvless service).")
set(KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY "" CACHE STRING
  "Opt-in explicit source-ROI budgets/times; empty=off, no camera commands or PPE verification.")
# These deployment strings become literal C++ strings; reject metacharacters
# rather than emitting a malformed build or accepting an implicit profile.
if(NOT KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY MATCHES "^[a-z0-9_=,]*$")
  message(FATAL_ERROR "SOURCE_ATTENTION_POLICY must contain only strict key=value numeric policy pairs")
endif()

find_package(Threads REQUIRED)

# --- Optional libusb-1.0 (USB bulk host-mode only) ------------------------------
# The Manifold 3 path (isUsbHost==false, FunctionFS gadget) never needs libusb.
# Without it the host-mode branch compiles as a NONSUPPORT stub (vendor-sample
# convention, see platform/usb_bulk_glue.h) and configure still passes.
find_package(PkgConfig QUIET)
set(KSI_SAFETY_EDGE_HAVE_LIBUSB FALSE)
if(PKG_CONFIG_FOUND)
  pkg_check_modules(LIBUSB QUIET libusb-1.0)
  if(LIBUSB_FOUND)
    set(KSI_SAFETY_EDGE_HAVE_LIBUSB TRUE)
  endif()
endif()
if(NOT KSI_SAFETY_EDGE_HAVE_LIBUSB)
  message(STATUS
    "psdk-app: libusb-1.0 dev headers not found — USB bulk host-mode "
    "(isUsbHost=true) builds as a NONSUPPORT stub. The Manifold 3 "
    "FunctionFS path is unaffected.")
endif()

# --- Core library (read-only consumer) -------------------------------------------
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/core-build)

# --- Application ------------------------------------------------------------------
set(KSI_SAFETY_EDGE_BINARY "ksi_safety_edge")
add_executable(${KSI_SAFETY_EDGE_BINARY}
  src/main.cpp
  src/mop_service.cpp
  platform/osal_glue.c
  platform/socket_glue.c
  platform/usb_bulk_glue.c
  platform/logger_console_glue.c
)
target_include_directories(${KSI_SAFETY_EDGE_BINARY} PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${KSI_PSDK_APP_INFO_DIR}
)
# SYSTEM — vendor headers (anonymous unions etc.) must not trip our -Wpedantic.
target_include_directories(${KSI_SAFETY_EDGE_BINARY} SYSTEM PRIVATE
  ${KSI_PSDK_ROOT}/psdk_lib/include
)
target_compile_options(${KSI_SAFETY_EDGE_BINARY} PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_compile_definitions(${KSI_SAFETY_EDGE_BINARY} PRIVATE
  KSI_SAFETY_EDGE_CAMERA_SOURCE=${KSI_SAFETY_EDGE_CAMERA_SOURCE}
  KSI_SAFETY_EDGE_MOP_CHANNEL=${KSI_SAFETY_EDGE_MOP_CHANNEL}
  KSI_SAFETY_EDGE_DIAG_DURATION_SEC=${KSI_SAFETY_EDGE_DIAG_DURATION_SEC}
  KSI_SAFETY_EDGE_ENGINE_PATH="${KSI_SAFETY_EDGE_ENGINE_PATH}"
  KSI_SAFETY_EDGE_MODEL_ID="${KSI_SAFETY_EDGE_MODEL_ID}"
  KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR="${KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR}"
  KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY="${KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY}"
  KSI_SAFETY_EDGE_FW_VERSION="${KSI_SAFETY_EDGE_DPK_FIRMWARE_VERSION}"
  KSI_SAFETY_EDGE_ALIAS="KSI Safety Edge"
)
target_link_libraries(${KSI_SAFETY_EDGE_BINARY} PRIVATE
  ksi_safety_edge_core psdk_static Threads::Threads m dl)
target_link_options(${KSI_SAFETY_EDGE_BINARY} PRIVATE
  -static-libstdc++ -static-libgcc)

if(KSI_SAFETY_EDGE_HAVE_LIBUSB)
  target_compile_definitions(${KSI_SAFETY_EDGE_BINARY} PRIVATE LIBUSB_INSTALLED)
  target_include_directories(${KSI_SAFETY_EDGE_BINARY} SYSTEM PRIVATE ${LIBUSB_INCLUDE_DIRS})
  target_link_directories(${KSI_SAFETY_EDGE_BINARY} PRIVATE ${LIBUSB_LIBRARY_DIRS})
  target_link_libraries(${KSI_SAFETY_EDGE_BINARY} PRIVATE ${LIBUSB_LIBRARIES})
endif()

# --- DPK package (opt-in via user_app_id) -----------------------------------------
# Empty KSI_SAFETY_EDGE_DPK_USER_APP_ID means: no DPK target at all (the DPK
# service runs argv-less, so the id is a DJI-issued deployment value, not code).
if(KSI_SAFETY_EDGE_DPK_USER_APP_ID)
  if(NOT KSI_SAFETY_EDGE_DPK_USER_APP_ID MATCHES "^[A-Za-z0-9._-]+$")
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_DPK_USER_APP_ID must contain only letters, digits, dot, "
      "underscore, or hyphen")
  endif()
  string(LENGTH "${KSI_SAFETY_EDGE_DPK_USER_APP_ID}" _user_app_id_length)
  if(_user_app_id_length GREATER 16)
    message(FATAL_ERROR
      "KSI_SAFETY_EDGE_DPK_USER_APP_ID must be at most 16 ASCII bytes "
      "(DJI build_dpk.sh limit)")
  endif()
  set(KSI_SAFETY_EDGE_DPK_BINARY "../${KSI_SAFETY_EDGE_BINARY}")
  configure_file(config/app.json.in "${CMAKE_CURRENT_BINARY_DIR}/dpk/app.json" @ONLY)
  add_custom_target(ksi_safety_edge_dpk
    COMMAND "${KSI_PSDK_ROOT}/tools/build_dpk/build_dpk.sh"
            -i "${CMAKE_CURRENT_BINARY_DIR}/dpk/app.json"
            -o "${CMAKE_CURRENT_BINARY_DIR}/dpk/out"
    DEPENDS ${KSI_SAFETY_EDGE_BINARY}
    COMMENT "Generating official Manifold 3 .dpk with DJI build_dpk.sh"
    VERBATIM
  )
else()
  message(STATUS
    "psdk-app: KSI_SAFETY_EDGE_DPK_USER_APP_ID is empty — no DPK target. "
    "Pass the DJI-issued id to enable it.")
endif()
