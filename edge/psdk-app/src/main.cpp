// edge/psdk-app — KSI Safety Edge PSDK application, v0.
//
// Scope: RGB liveview ingress (bounded, callback-safe) + FC subscription
// provenance + receive-grade temporal matching, written as NDJSON. No
// detector, no capture-time claims: every match grade stays at RECEIVE_*
// because neither frames nor telemetry carry a verified sensor timestamp.
//
// Callback discipline (from the SI psdk-tap from which the platform glue
// came): timestamp FIRST, then a bounded try-push only. No file I/O, no
// blocking, no allocation beyond the bounded slots in any PSDK callback.

#include <dji_core.h>
#include <dji_fc_subscription.h>
#include <dji_liveview.h>
#include <dji_typedef.h>
#include <dji_version.h>

// Deployment input, not a source (see CMakeLists credential gate + README).
#include "dji_sdk_app_info.h"

#include <atomic>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <thread>

extern "C" {
#include "platform/logger_console_glue.h"
#include "platform/osal_glue.h"
#include "platform/socket_glue.h"
#include "platform/usb_bulk_glue.h"
}

#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"
#include "ksi_safety/time/provenance.hpp"
#include "ksi_safety/time/topic_ring.hpp"

namespace {

using ksi::safety::BoundedFrameQueue;
using ksi::safety::ClockRegistry;
using ksi::safety::FrameContext;
using ksi::safety::FrameDropReason;
using ksi::safety::FrameMetadata;
using ksi::safety::FramePacket;
using ksi::safety::MatchFrame;
using ksi::safety::MatchPolicy;
using ksi::safety::Quaternion;
using ksi::safety::Sample;
using ksi::safety::TimeSourceKind;
using ksi::safety::ToString;
using ksi::safety::TopicRing;
using ksi::safety::Vec3;

constexpr int kAppVersion = 0;
constexpr const char* kDefaultOutPath = "/tmp/ksi-safety-edge.ndjson";
constexpr std::size_t kFrameSlots = 4U;
constexpr std::size_t kMaxRgbFrameBytes = 3840U * 2160U * 3U;
constexpr std::size_t kAttitudeRingCapacity = 2048U;  // 400 Hz x ~5 s
constexpr std::size_t kPositionRingCapacity = 512U;  // 50 Hz x ~10 s
constexpr std::int64_t kNsPerSec = 1000000000LL;
constexpr std::int64_t kNsPerMs = 1000000LL;
constexpr std::int64_t kPollIntervalNs = 200000000LL;  // 200 ms signal latency

#ifndef KSI_SAFETY_EDGE_DURATION_SEC
#define KSI_SAFETY_EDGE_DURATION_SEC 60
#endif
#ifndef KSI_SAFETY_EDGE_CAMERA_SOURCE
#define KSI_SAFETY_EDGE_CAMERA_SOURCE DJI_LIVEVIEW_CAMERA_SOURCE_M4E_VIS
#endif
// DPK install verification (dji_app_mgr) compares the package control-file
// Version against the version the running app REPORTS via
// DjiCore_SetFirmwareVersion; these must match exactly. The value is injected
// by CMake from KSI_SAFETY_EDGE_DPK_FIRMWARE_VERSION.
#ifndef KSI_SAFETY_EDGE_FW_VERSION
#define KSI_SAFETY_EDGE_FW_VERSION "00.01.00.00"
#endif
#ifndef KSI_SAFETY_EDGE_ALIAS
#define KSI_SAFETY_EDGE_ALIAS "KSI Safety Edge"
#endif

volatile std::sig_atomic_t g_signal_requested = 0;

void OnSignal(int signum) {
    (void)signum;
    g_signal_requested = 1;
}

std::int64_t MonotonicNowNs() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return 0;
    }
    return static_cast<std::int64_t>(value.tv_sec) * kNsPerSec +
           static_cast<std::int64_t>(value.tv_nsec);
}

// On-device boot breadcrumbs. dji_app_mgr's install verification starts the
// app briefly and then kills it; this file survives to show exactly how far
// startup got (dev-only diagnostic, never a product data path).
FILE* g_boot_log = nullptr;

void BootStage(const char* stage) {
    if (g_boot_log == nullptr) {
        return;
    }
    std::fprintf(g_boot_log, "%lld\t%s\n",
                 static_cast<long long>(MonotonicNowNs()), stage);
    std::fflush(g_boot_log);
}

std::int64_t WallNowMs() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_REALTIME, &value) != 0) {
        return 0;
    }
    return static_cast<std::int64_t>(value.tv_sec) * 1000LL +
           static_cast<std::int64_t>(value.tv_nsec) / 1000000LL;
}

void FormatWallUtc(std::int64_t wall_ms, char* out, std::size_t out_size) {
    std::time_t seconds = static_cast<std::time_t>(wall_ms / 1000LL);
    std::tm broken{};
    gmtime_r(&seconds, &broken);
    std::strftime(out, out_size, "%Y-%m-%dT%H:%M:%SZ", &broken);
}

struct AppState {
    ClockRegistry clocks;
    BoundedFrameQueue frames{kFrameSlots, kMaxRgbFrameBytes};
    TopicRing<Quaternion> attitude{kAttitudeRingCapacity};
    TopicRing<Vec3> position{kPositionRingCapacity};
    std::mutex attitude_mutex;
    std::mutex position_mutex;
    std::FILE* out = nullptr;

    std::atomic<std::uint64_t> frame_seq{0};
    std::atomic<std::uint64_t> att_seq{0};
    std::atomic<std::uint64_t> pos_seq{0};

    std::atomic<std::uint64_t> frames_received{0};
    std::atomic<std::uint64_t> frames_dropped{0};
    std::atomic<std::uint64_t> hard_sync{0};
    std::atomic<std::uint64_t> hard_sync_sdk_ts_seen{0};
    std::atomic<std::uint64_t> hard_sync_malformed{0};
    std::atomic<std::uint64_t> att_ring_contended{0};
    std::atomic<std::uint64_t> position_fused{0};
    std::atomic<std::uint64_t> position_malformed{0};
    std::atomic<std::uint64_t> pos_ring_contended{0};
    std::atomic<std::uint64_t> velocity{0};
    std::atomic<std::uint64_t> gimbal{0};
    std::atomic<std::uint64_t> gps_details{0};
    std::atomic<std::uint64_t> samples_written{0};

    std::atomic<bool> worker_stop{false};
};

// PSDK subscription/liveview callbacks carry no user context (vendor
// constraint), and this process owns exactly one app instance, so a
// module-level pointer is the same answer the SI tap gave.
AppState* g_state = nullptr;

// --- PSDK callbacks: timestamp first, bounded try-push only -------------------

void OnRgbImage(E_DjiLiveViewCameraPosition position, const std::uint8_t* buf,
                std::uint32_t len, T_DjiLiveviewImageInfo image_info) {
    const std::int64_t recv_mono_ns = MonotonicNowNs();
    const std::int64_t recv_utc_ms = WallNowMs();
    (void)position;
    if (g_state == nullptr) {
        return;
    }
    FrameMetadata meta;
    meta.width = image_info.width;
    meta.height = image_info.height;
    meta.frame_id = image_info.frameId;
    meta.received_monotonic_ns = recv_mono_ns;
    meta.sensor_timestamp_ns = 0;  // RGB liveview provides no sensor timestamp.
    meta.received_utc_ms = recv_utc_ms;
    const FrameDropReason verdict =
        g_state->frames.PushFromCallback(buf, len, meta);
    if (verdict == FrameDropReason::kAccepted) {
        g_state->frames_received.fetch_add(1, std::memory_order_relaxed);
    } else {
        g_state->frames_dropped.fetch_add(1, std::memory_order_relaxed);
    }
}

T_DjiReturnCode OnHardSync(const std::uint8_t* data, std::uint16_t data_size,
                           const T_DjiDataTimestamp* timestamp) {
    const std::int64_t recv_mono_ns = MonotonicNowNs();
    if (g_state == nullptr) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
    }
    if (timestamp != nullptr) {
        // Raw SDK timestamp observed, but its unit/domain meaning is not
        // verified — counted only, never promoted to a source time
        // (fail-closed: has_source_time stays false below).
        g_state->hard_sync_sdk_ts_seen.fetch_add(1, std::memory_order_relaxed);
    }
    if (data == nullptr || data_size != sizeof(T_DjiFcSubscriptionHardSync)) {
        g_state->hard_sync_malformed.fetch_add(1, std::memory_order_relaxed);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
    }
    const auto* hard_sync = reinterpret_cast<const T_DjiFcSubscriptionHardSync*>(data);
    Sample<Quaternion> sample;
    sample.sample_id = g_state->att_seq.fetch_add(1, std::memory_order_relaxed);
    sample.seq = sample.sample_id;
    sample.received = g_state->clocks.Make("host_mono", TimeSourceKind::kHostReceive,
                                           recv_mono_ns);
    // q0=w, q1=x, q2=y, q3=z per dji_fc_subscription.h — mapped, not invented.
    sample.value.w = static_cast<double>(hard_sync->q.q0);
    sample.value.x = static_cast<double>(hard_sync->q.q1);
    sample.value.y = static_cast<double>(hard_sync->q.q2);
    sample.value.z = static_cast<double>(hard_sync->q.q3);
    sample.has_source_time = false;  // unverified by design (see above).
    if (g_state->attitude_mutex.try_lock()) {
        g_state->attitude.Push(sample);
        g_state->attitude_mutex.unlock();
        g_state->hard_sync.fetch_add(1, std::memory_order_relaxed);
    } else {
        g_state->att_ring_contended.fetch_add(1, std::memory_order_relaxed);
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode OnPositionFused(const std::uint8_t* data, std::uint16_t data_size,
                                const T_DjiDataTimestamp* timestamp) {
    const std::int64_t recv_mono_ns = MonotonicNowNs();
    (void)timestamp;  // unverified by design — counted nowhere, used nowhere.
    if (g_state == nullptr) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
    }
    if (data == nullptr || data_size != sizeof(T_DjiFcSubscriptionPositionFused)) {
        g_state->position_malformed.fetch_add(1, std::memory_order_relaxed);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
    }
    const auto* pos = reinterpret_cast<const T_DjiFcSubscriptionPositionFused*>(data);
    Sample<Vec3> sample;
    sample.sample_id = g_state->pos_seq.fetch_add(1, std::memory_order_relaxed);
    sample.seq = sample.sample_id;
    sample.received = g_state->clocks.Make("host_mono", TimeSourceKind::kHostReceive,
                                           recv_mono_ns);
    // Raw as read — latitude/longitude in rad, altitude in m (WGS 84). No
    // site-frame conversion is performed or implied here.
    sample.value.x = static_cast<double>(pos->latitude);
    sample.value.y = static_cast<double>(pos->longitude);
    sample.value.z = static_cast<double>(pos->altitude);
    sample.has_source_time = false;  // unverified by design.
    if (g_state->position_mutex.try_lock()) {
        g_state->position.Push(sample);
        g_state->position_mutex.unlock();
        g_state->position_fused.fetch_add(1, std::memory_order_relaxed);
    } else {
        g_state->pos_ring_contended.fetch_add(1, std::memory_order_relaxed);
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode OnVelocity(const std::uint8_t* data, std::uint16_t data_size,
                           const T_DjiDataTimestamp* timestamp) {
    (void)timestamp;
    if (g_state != nullptr && data != nullptr &&
        data_size == sizeof(T_DjiFcSubscriptionVelocity)) {
        g_state->velocity.fetch_add(1, std::memory_order_relaxed);
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode OnGimbal(const std::uint8_t* data, std::uint16_t data_size,
                         const T_DjiDataTimestamp* timestamp) {
    (void)timestamp;
    if (g_state != nullptr && data != nullptr &&
        data_size == sizeof(T_DjiFcSubscriptionGimbalAngles)) {
        g_state->gimbal.fetch_add(1, std::memory_order_relaxed);
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode OnGpsDetails(const std::uint8_t* data, std::uint16_t data_size,
                             const T_DjiDataTimestamp* timestamp) {
    (void)timestamp;
    if (g_state != nullptr && data != nullptr &&
        data_size == sizeof(T_DjiFcSubscriptionGpsDetails)) {
        g_state->gps_details.fetch_add(1, std::memory_order_relaxed);
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

// --- Worker (non-callback thread: matching + file I/O live here) --------------

void WriteMatchAtt(std::FILE* out, const ksi::safety::MatchResult<Quaternion>& m) {
    std::fprintf(out, "\"att\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld}",
                 ToString(m.quality), ToString(m.reason),
                 static_cast<long long>(m.nearest_gap_ns));
}

void WriteMatchPos(std::FILE* out, const ksi::safety::MatchResult<Vec3>& m) {
    std::fprintf(out, "\"pos\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld}",
                 ToString(m.quality), ToString(m.reason),
                 static_cast<long long>(m.nearest_gap_ns));
}

void WorkerMain(AppState* state) {
    const MatchPolicy policy{/*max_receive_gap_ns=*/50LL * kNsPerMs,
                             /*max_bracket_interval_ns=*/20LL * kNsPerMs};
    FramePacket packet;
    for (;;) {
        if (!state->frames.TryPop(&packet)) {
            if (state->worker_stop.load(std::memory_order_relaxed)) {
                return;  // queue drained after stop: exit.
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        FrameContext ctx;
        ctx.frame_id = packet.metadata.frame_id;
        ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
        ctx.has_exposure = false;  // recv-grade only: no verified exposure time.

        ksi::safety::MatchResult<Quaternion> att;
        {
            std::lock_guard<std::mutex> lock(state->attitude_mutex);
            att = MatchFrame(ctx, state->attitude, policy);
        }
        ksi::safety::MatchResult<Vec3> pos;
        {
            std::lock_guard<std::mutex> lock(state->position_mutex);
            pos = MatchFrame(ctx, state->position, policy);
        }
        const std::uint64_t i =
            state->samples_written.fetch_add(1, std::memory_order_relaxed);
        std::fprintf(state->out,
                     "{\"type\":\"frame\",\"i\":%llu,\"frame_id\":%u,\"w\":%u,\"h\":%u,"
                     "\"recv_mono_ns\":%lld,",
                     static_cast<unsigned long long>(i),
                     packet.metadata.frame_id, packet.metadata.width,
                     packet.metadata.height,
                     static_cast<long long>(packet.metadata.received_monotonic_ns));
        WriteMatchAtt(state->out, att);
        std::fputc(',', state->out);
        WriteMatchPos(state->out, pos);
        std::fputs("}\n", state->out);
        if ((i & 15U) == 0U) {
            std::fflush(state->out);
        }
    }
}

// --- Startup helpers ------------------------------------------------------------

bool IsPlaceholderCredential() {
    return std::strcmp(USER_APP_NAME, "your_app_name") == 0 ||
           std::strcmp(USER_APP_ID, "your_app_id") == 0 ||
           std::strcmp(USER_APP_KEY, "your_app_key") == 0 ||
           std::strcmp(USER_APP_LICENSE, "your_app_license") == 0;
    // NOTE: an empty USER_DEVELOPER_ACCOUNT is allowed (known open item —
    // the DPK still builds and installs; diagnosis stays possible).
}

bool FillUserInfo(T_DjiUserInfo* user_info) {
    std::memset(user_info, 0, sizeof(*user_info));
    if (std::strlen(USER_APP_NAME) >= sizeof(user_info->appName) ||
        std::strlen(USER_APP_ID) >= sizeof(user_info->appId) ||
        std::strlen(USER_APP_KEY) >= sizeof(user_info->appKey) ||
        std::strlen(USER_APP_LICENSE) >= sizeof(user_info->appLicense) ||
        std::strlen(USER_DEVELOPER_ACCOUNT) >= sizeof(user_info->developerAccount) ||
        std::strlen(USER_BAUD_RATE) >= sizeof(user_info->baudRate)) {
        std::fprintf(stderr, "dji_sdk_app_info.h field exceeds its limit\n");
        return false;
    }
    std::strncpy(user_info->appName, USER_APP_NAME, sizeof(user_info->appName) - 1);
    std::memcpy(user_info->appId, USER_APP_ID, std::strlen(USER_APP_ID));
    std::memcpy(user_info->appKey, USER_APP_KEY, std::strlen(USER_APP_KEY));
    std::memcpy(user_info->appLicense, USER_APP_LICENSE, std::strlen(USER_APP_LICENSE));
    std::memcpy(user_info->baudRate, USER_BAUD_RATE, std::strlen(USER_BAUD_RATE));
    std::strncpy(user_info->developerAccount, USER_DEVELOPER_ACCOUNT,
                 sizeof(user_info->developerAccount) - 1);
    return true;
}

struct CliArgs {
    const char* out_path = kDefaultOutPath;
    long long duration_sec = KSI_SAFETY_EDGE_DURATION_SEC;
};

bool ParseArgs(int argc, char** argv, CliArgs* args) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            args->out_path = argv[++i];
        } else if (std::strcmp(argv[i], "--duration-sec") == 0 && i + 1 < argc) {
            args->duration_sec = std::atoll(argv[++i]);
        } else if (std::strcmp(argv[i], "--help") == 0) {
            std::fprintf(stderr, "usage: %s [--out PATH] [--duration-sec N]\n",
                         argv[0]);
            std::exit(0);
        } else {
            std::fprintf(stderr, "unknown argument: %s\nusage: %s [--out PATH] "
                                 "[--duration-sec N]\n",
                         argv[i], argv[0]);
            return false;
        }
    }
    if (args->out_path[0] == '\0' || args->duration_sec <= 0) {
        std::fprintf(stderr, "--out must be non-empty and --duration-sec > 0\n");
        return false;
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    CliArgs args;
    // Subscription/shutdown flags live at function scope so the fail-closed
    // `goto shutdown` / `goto finish` paths never cross an initialization.
    bool fc_init = false;
    bool sub_hard_sync = false, sub_pos = false, sub_vel = false, sub_gps = false,
         sub_gimbal = false;
    bool liveview_init = false, image_started = false;
    if (!ParseArgs(argc, argv, &args)) {
        return 2;
    }
    if (IsPlaceholderCredential()) {
        std::fprintf(stderr, "config/dji_sdk_app_info.h still holds placeholder values — "
                             "fill it with the developer.dji.com app data "
                             "(see edge/psdk-app/README.md).\n");
        return 2;
    }

    AppState state;
    g_state = &state;

    g_boot_log = std::fopen("/tmp/ksi-safety-edge-boot.log", "w");
    BootStage("start");

    state.out = std::fopen(args.out_path, "w");
    if (state.out == nullptr) {
        std::fprintf(stderr, "cannot open output file: %s\n", args.out_path);
        return 2;
    }
    BootStage("out_open");

    // Meta line first, before any callback is registered: anchor clocks early.
    char started_at_utc[32];
    FormatWallUtc(WallNowMs(), started_at_utc, sizeof(started_at_utc));
    const std::int64_t anchor_mono_ns = MonotonicNowNs();
    char anchor_wall_utc[32];
    FormatWallUtc(WallNowMs(), anchor_wall_utc, sizeof(anchor_wall_utc));
    char psdk_version[32];
    std::snprintf(psdk_version, sizeof(psdk_version), "%d.%d.%d", DJI_VERSION_MAJOR,
                  DJI_VERSION_MINOR, DJI_VERSION_MODIFY);
    std::fprintf(state.out,
                 "{\"type\":\"meta\",\"version\":%d,\"psdk\":\"%s\","
                 "\"started_at_utc\":\"%s\",\"anchor_mono_ns\":%lld,"
                 "\"anchor_wall_utc\":\"%s\","
                 "\"capabilities\":{\"frame_sensor_ts\":false,"
                 "\"telemetry_sensor_ts\":false,"
                 "\"source_time\":{\"verified\":false,\"domain\":\"psdk_fc_local\"}},"
                 "\"note\":\"recv-grade only; no capture-time claims\"}\n",
                 kAppVersion, psdk_version, started_at_utc,
                 static_cast<long long>(anchor_mono_ns), anchor_wall_utc);
    std::fflush(state.out);

    // The only verified clock in this app: our own receive path.
    state.clocks.Verify("host_mono", TimeSourceKind::kHostReceive,
                        "in-process CLOCK_MONOTONIC");

    BootStage("glues_begin");
    const char* stop_reason = "duration_exceeded";
    T_DjiReturnCode rc = KsiOsalGlue_Register();
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "Osal handler registration failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto finish;
    }
    (void)KsiLoggerConsoleGlue_Register();  // diagnostic only; never fatal.
    rc = KsiSocketGlue_Register();
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "Socket handler registration failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto finish;
    }
    rc = KsiUsbBulkGlue_Register();
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "UsbBulk handler registration failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto finish;
    }
    BootStage("glues_ok");

    {
        T_DjiUserInfo user_info;
        if (!FillUserInfo(&user_info)) {
            stop_reason = "error";
            goto finish;
        }
        // Blocks until the aircraft link answers (normally 2-4 s). Without an
        // attached aircraft this fails or waits here — expected, see README.
        BootStage("core_init_begin");
        rc = DjiCore_Init(&user_info);
        if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
            std::fprintf(stderr, "DjiCore_Init failed: 0x%08X\n",
                         static_cast<unsigned>(rc));
            BootStage("core_init_fail");
            stop_reason = "error";
            goto finish;
        }
        BootStage("core_init_ok");
        // DPK install verification: dji_app_mgr briefly starts the app and
        // checks the reported identity/version against the package control
        // file. DjiCore_SetFirmwareVersion is the required report — without
        // it the install fails with "verify app user_app_id or version info
        // error" (verified on device 2026-10-08).
        rc = DjiCore_SetAlias(KSI_SAFETY_EDGE_ALIAS);
        if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
            std::fprintf(stderr, "DjiCore_SetAlias failed: 0x%08X\n",
                         static_cast<unsigned>(rc));
            stop_reason = "error";
            goto finish;
        }
        BootStage("alias_ok");
        {
            unsigned fw_major = 0U;
            unsigned fw_minor = 0U;
            unsigned fw_modify = 0U;
            unsigned fw_debug = 0U;
            if (std::sscanf(KSI_SAFETY_EDGE_FW_VERSION, "%u.%u.%u.%u",
                            &fw_major, &fw_minor, &fw_modify, &fw_debug) != 4) {
                std::fprintf(stderr, "invalid firmware version macro: %s\n",
                             KSI_SAFETY_EDGE_FW_VERSION);
                stop_reason = "error";
                goto finish;
            }
            T_DjiFirmwareVersion firmware_version = {};
            firmware_version.majorVersion = static_cast<std::uint8_t>(fw_major);
            firmware_version.minorVersion = static_cast<std::uint8_t>(fw_minor);
            firmware_version.modifyVersion = static_cast<std::uint8_t>(fw_modify);
            firmware_version.debugVersion = static_cast<std::uint8_t>(fw_debug);
            rc = DjiCore_SetFirmwareVersion(firmware_version);
            if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
                std::fprintf(stderr, "DjiCore_SetFirmwareVersion failed: 0x%08X\n",
                             static_cast<unsigned>(rc));
                stop_reason = "error";
                goto finish;
            }
        }
        BootStage("fw_ok");
        // Required so dji_app_mgr (DPK service identity check) gets a response;
        // without it an installed DPK rolls back (SI HIL lesson, 2026-08-12).
        rc = DjiCore_ApplicationStart();
        if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
            std::fprintf(stderr, "DjiCore_ApplicationStart failed: 0x%08X\n",
                         static_cast<unsigned>(rc));
            stop_reason = "error";
            goto finish;
        }
        BootStage("app_start_ok");
    }

    {
        struct sigaction action{};
        action.sa_handler = OnSignal;
        (void)sigemptyset(&action.sa_mask);
        action.sa_flags = 0;
        (void)sigaction(SIGINT, &action, nullptr);
        (void)sigaction(SIGTERM, &action, nullptr);
    }

    // Telemetry first so rings accumulate before the first frame arrives.
    rc = DjiFcSubscription_Init();
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "DjiFcSubscription_Init failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    fc_init = true;
    // Subscribe in a fixed order; unsubscribe uses the SAME order (FIFO rule in
    // dji_fc_subscription.h: subscription is enqueue, unsubscription dequeue).
    rc = DjiFcSubscription_SubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_HARD_SYNC,
                                          DJI_DATA_SUBSCRIPTION_TOPIC_400_HZ, OnHardSync);
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "subscribe HARD_SYNC failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    sub_hard_sync = true;
    rc = DjiFcSubscription_SubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_POSITION_FUSED,
                                          DJI_DATA_SUBSCRIPTION_TOPIC_50_HZ,
                                          OnPositionFused);
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "subscribe POSITION_FUSED failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    sub_pos = true;
    rc = DjiFcSubscription_SubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_VELOCITY,
                                          DJI_DATA_SUBSCRIPTION_TOPIC_50_HZ, OnVelocity);
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "subscribe VELOCITY failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    sub_vel = true;
    rc = DjiFcSubscription_SubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_GPS_DETAILS,
                                          DJI_DATA_SUBSCRIPTION_TOPIC_5_HZ, OnGpsDetails);
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "subscribe GPS_DETAILS failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    sub_gps = true;
    rc = DjiFcSubscription_SubscribeTopic(
        DJI_FC_SUBSCRIPTION_TOPIC_GIMBAL_ANGLES_ON_POS_NO1,
        DJI_DATA_SUBSCRIPTION_TOPIC_50_HZ, OnGimbal);
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "subscribe GIMBAL failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    sub_gimbal = true;
    BootStage("fc_ok");

    rc = DjiLiveview_Init();
    if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        std::fprintf(stderr, "DjiLiveview_Init failed: 0x%08X\n",
                     static_cast<unsigned>(rc));
        stop_reason = "error";
        goto shutdown;
    }
    liveview_init = true;
    BootStage("liveview_init_ok");

    {
        std::thread worker(WorkerMain, &state);
        rc = DjiLiveview_StartImageStream(DJI_LIVEVIEW_CAMERA_POSITION_NO_1,
                                          KSI_SAFETY_EDGE_CAMERA_SOURCE,
                                          PIXFMT_RGB_PACKED, OnRgbImage);
        if (rc != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
            std::fprintf(stderr, "StartImageStream failed: 0x%08X\n",
                         static_cast<unsigned>(rc));
            stop_reason = "error";
            state.worker_stop.store(true, std::memory_order_relaxed);
            worker.join();
            goto shutdown;
        }
        image_started = true;
        BootStage("stream_ok");
        const std::int64_t deadline_ns =
            MonotonicNowNs() + args.duration_sec * kNsPerSec;
        while (!state.worker_stop.load(std::memory_order_relaxed)) {
            const std::int64_t now_ns = MonotonicNowNs();
            if (now_ns >= deadline_ns) {
                stop_reason = "duration_exceeded";
                break;
            }
            std::int64_t wait_ns = deadline_ns - now_ns;
            if (wait_ns > kPollIntervalNs) {
                wait_ns = kPollIntervalNs;
            }
            std::this_thread::sleep_for(
                std::chrono::nanoseconds(wait_ns));
            if (g_signal_requested != 0) {
                stop_reason = "signal";
                break;
            }
        }
        state.worker_stop.store(true, std::memory_order_relaxed);
        worker.join();
    }

shutdown:
    // FIFO teardown: image stream first, then topics in subscription order.
    if (image_started) {
        (void)DjiLiveview_StopImageStream(DJI_LIVEVIEW_CAMERA_POSITION_NO_1,
                                           KSI_SAFETY_EDGE_CAMERA_SOURCE);
    }
    if (liveview_init) {
        (void)DjiLiveview_Deinit();
    }
    if (sub_hard_sync) {
        (void)DjiFcSubscription_UnSubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_HARD_SYNC);
    }
    if (sub_pos) {
        (void)DjiFcSubscription_UnSubscribeTopic(
            DJI_FC_SUBSCRIPTION_TOPIC_POSITION_FUSED);
    }
    if (sub_vel) {
        (void)DjiFcSubscription_UnSubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_VELOCITY);
    }
    if (sub_gps) {
        (void)DjiFcSubscription_UnSubscribeTopic(DJI_FC_SUBSCRIPTION_TOPIC_GPS_DETAILS);
    }
    if (sub_gimbal) {
        (void)DjiFcSubscription_UnSubscribeTopic(
            DJI_FC_SUBSCRIPTION_TOPIC_GIMBAL_ANGLES_ON_POS_NO1);
    }
    if (fc_init) {
        (void)DjiFcSubscription_DeInit();
    }
    // Best effort only — behavior without an attached aircraft is out of scope.
    (void)DjiCore_DeInit();

finish: {
    BootStage(stop_reason);
    BootStage("finish");
    const auto qstats = state.frames.stats();
    std::fprintf(state.out,
                 "{\"type\":\"stat\",\"frames_received\":%llu,\"frames_dropped\":%llu,"
                 "\"queue_busy\":%llu,\"queue_full\":%llu,\"frame_too_large\":%llu,"
                 "\"malformed\":%llu,\"hard_sync\":%llu,\"hard_sync_sdk_ts_seen\":%llu,"
                 "\"hard_sync_malformed\":%llu,\"att_ring_contended\":%llu,"
                 "\"position_fused\":%llu,\"position_malformed\":%llu,"
                 "\"pos_ring_contended\":%llu,\"velocity\":%llu,\"gimbal\":%llu,"
                 "\"gps_details\":%llu,\"samples_written\":%llu,"
                 "\"stop_reason\":\"%s\"}\n",
                 static_cast<unsigned long long>(state.frames_received.load()),
                 static_cast<unsigned long long>(state.frames_dropped.load()),
                 static_cast<unsigned long long>(qstats.queue_busy),
                 static_cast<unsigned long long>(qstats.queue_full),
                 static_cast<unsigned long long>(qstats.frame_too_large),
                 static_cast<unsigned long long>(qstats.malformed),
                 static_cast<unsigned long long>(state.hard_sync.load()),
                 static_cast<unsigned long long>(state.hard_sync_sdk_ts_seen.load()),
                 static_cast<unsigned long long>(state.hard_sync_malformed.load()),
                 static_cast<unsigned long long>(state.att_ring_contended.load()),
                 static_cast<unsigned long long>(state.position_fused.load()),
                 static_cast<unsigned long long>(state.position_malformed.load()),
                 static_cast<unsigned long long>(state.pos_ring_contended.load()),
                 static_cast<unsigned long long>(state.velocity.load()),
                 static_cast<unsigned long long>(state.gimbal.load()),
                 static_cast<unsigned long long>(state.gps_details.load()),
                 static_cast<unsigned long long>(state.samples_written.load()),
                 stop_reason);
    std::fclose(state.out);
    state.out = nullptr;
}
    g_state = nullptr;
    return std::strcmp(stop_reason, "error") == 0 ? 1 : 0;
}
