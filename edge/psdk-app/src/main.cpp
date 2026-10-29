// edge/psdk-app — KSI Safety Edge PSDK application, v0.
//
// Scope: RGB liveview ingress (bounded, callback-safe) + FC subscription
// provenance, processed by the SAME SessionRunner the Replay driver uses
// (edge/src/runtime/session_runner.cpp): detection cadence gate -> detector
// -> temporal matching -> session, written as NDJSON. The detector is the
// real D-FINE path when built with TensorRT and given --engine; otherwise
// every tick stays NOT_RUN. No capture-time claims: RGB liveview carries no
// verified exposure time, so match grades stay at RECEIVE_*.
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
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

extern "C" {
#include "platform/logger_console_glue.h"
#include "platform/osal_glue.h"
#include "platform/socket_glue.h"
#include "platform/usb_bulk_glue.h"
}

#include "ksi_safety/evidence/recorder.hpp"
#include "ksi_safety/evidence/store.hpp"
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/capture/frame_queue.hpp"
#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "ksi_safety/perception/tensorrt_runtime.hpp"
#include "ksi_safety/runtime/run_control.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/time/interpolation.hpp"
#include "ksi_safety/time/matching.hpp"
#include "ksi_safety/time/provenance.hpp"
#include "ksi_safety/time/topic_ring.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

#include "app_config.hpp"
#include "mop_service.hpp"
#include "source_context.hpp"

namespace {

using ksi::safety::BoundedFrameQueue;
using ksi::safety::EvidenceStatus;
using ksi::safety::EvidenceStore;
using ksi::safety::EvidenceStatusName;
using ksi::safety::SourceAsset;
using ksi::safety::RunControl;
using ksi::safety::RunControlInputs;
using ksi::safety::RunControlOutcome;
using ksi::safety::RuntimeState;
using ksi::safety::SnapshotToFacts;
using ksi::safety::ClockRegistry;
using ksi::safety::DetectorPort;
using ksi::safety::DfineConfig;
using ksi::safety::DfineDetectorPort;
using ksi::safety::FrameContext;
using ksi::safety::FrameDropReason;
using ksi::safety::FrameMetadata;
using ksi::safety::FramePacket;
using ksi::safety::ModelProfile;
using ksi::safety::PushVerdict;
using ksi::safety::Quaternion;
using ksi::safety::RiskRule;
using ksi::safety::RunnerConfig;
using ksi::safety::Sample;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::Sha256HexOfFile;
using ksi::safety::TensorRtRuntime;
using ksi::safety::TickRecord;
using ksi::safety::TimeSourceKind;
using ksi::safety::ToString;
using ksi::safety::TrackerConfig;
using ksi::safety::Vec3;
using ksi::safety::ZoneRevision;

constexpr int kAppVersion = 0;
constexpr const char* kDefaultOutPath = "/tmp/ksi-safety-edge.ndjson";
constexpr std::size_t kFrameSlots = 4U;
constexpr std::size_t kMaxRgbFrameBytes = 3840U * 2160U * 3U;
constexpr std::size_t kAttitudeRingCapacity = 2048U;  // 400 Hz x ~5 s
constexpr std::size_t kPositionRingCapacity = 512U;  // 50 Hz x ~10 s
constexpr std::int64_t kNsPerSec = 1000000000LL;
constexpr std::int64_t kNsPerMs = 1000000LL;
constexpr std::int64_t kPollIntervalNs = 200000000LL;  // 200 ms signal latency

// NOTE (retired): KSI_SAFETY_EDGE_DURATION_SEC no longer exists. A stale
// cache entry with the old 60 s default must NOT force an operational
// timeout — the argvless service is unbounded by default. Diagnostic runs
// use KSI_SAFETY_EDGE_DIAG_DURATION_SEC instead (see CMakeLists).
#ifndef KSI_SAFETY_EDGE_MOP_CHANNEL
#define KSI_SAFETY_EDGE_MOP_CHANNEL 0
#endif
#ifndef KSI_SAFETY_EDGE_DIAG_DURATION_SEC
#define KSI_SAFETY_EDGE_DIAG_DURATION_SEC 60
#endif
#ifndef KSI_SAFETY_EDGE_ENGINE_PATH
#define KSI_SAFETY_EDGE_ENGINE_PATH ""
#endif
#ifndef KSI_SAFETY_EDGE_MODEL_ID
#define KSI_SAFETY_EDGE_MODEL_ID ""
#endif
#ifndef KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR
#define KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR ""
#endif
#ifndef KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY
#define KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY ""
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

std::string ConfiguredRgbStreamId() {
    // Describes the source actually passed to StartImageStream below, NOT
    // inferred lens/zoom telemetry. One configured stream in this process.
    return "psdk-rgb-port1-source-" +
        std::to_string(static_cast<int>(KSI_SAFETY_EDGE_CAMERA_SOURCE));
}

struct AppState {
    ClockRegistry clocks;
    BoundedFrameQueue frames{kFrameSlots, kMaxRgbFrameBytes};
    // R1: the shared product runner (same code path as the Replay driver).
    // Telemetry callbacks push under the two mutexes below; the runner
    // itself is not thread-safe.
    std::unique_ptr<SessionRunner> runner;
    std::unique_ptr<TensorRtRuntime> runtime;
    std::unique_ptr<DetectorPort> detector;  // null when no engine is given
    std::mutex attitude_mutex;
    std::mutex position_mutex;
    std::FILE* out = nullptr;

    // A9-2: MOP evidence path (null when --mop-channel is not given).
    MopService* mop = nullptr;

    // Source-evidence recorder: the bytes an observation was computed from.
    // Separate lifetime from the MOP spool (delivery guarantee): the spool
    // may be compacted after the RC acked a message, this one is the origin.
    // The capture policy lives in the shared library (edge/src/evidence),
    // so the device app, the host Edge and the replay tool produce the same
    // evidence from the same code.
    std::unique_ptr<ksi::safety::SourceEvidenceRecorder> evidence;

    // Unit A passive autonomy (opt-in via --autonomy-evidence-dir; null =
    // off). Same shared wiring as the host tools (ProcessSharedTick).
    // autonomy_identity is "flight|boot": adoption/reboot recreates the
    // processor so asset ids never merge lineages across identities.
    std::unique_ptr<ksi::safety::AutonomousProcessor> autonomy;
    std::string autonomy_identity;
    bool autonomy_saturation_logged = false;
    // Resolved startup config for the worker thread (autonomy opt-in only;
    // argv memory outlives the process).
    ksi_safety_edge_app::AppConfig app_args;

    // G9 run control: all edge state (consumed command epoch/revision and
    // applied generation) is owned by the RunControl below; the service
    // tracks what it already reported. The worker poll takes one coherent
    // snapshot per iteration — never two separately sampled counters.
    RunControl run_control;

    std::atomic<std::uint64_t> frame_seq{0};
    std::atomic<std::uint64_t> att_seq{0};
    std::atomic<std::uint64_t> pos_seq{0};

    std::atomic<std::uint64_t> frames_received{0};
    std::atomic<std::uint64_t> frames_dropped{0};
    std::atomic<std::uint64_t> hard_sync{0};
    std::atomic<std::uint64_t> hard_sync_sdk_ts_seen{0};
    std::atomic<std::uint64_t> hard_sync_malformed{0};
    std::atomic<std::uint64_t> att_ring_contended{0};
    std::atomic<std::uint64_t> att_rejected{0};
    std::atomic<std::uint64_t> position_fused{0};
    std::atomic<std::uint64_t> position_malformed{0};
    std::atomic<std::uint64_t> pos_ring_contended{0};
    std::atomic<std::uint64_t> pos_rejected{0};
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
        const PushVerdict verdict = g_state->runner->PushPose(sample);
        g_state->attitude_mutex.unlock();
        if (verdict == PushVerdict::kRecorded) {
            g_state->hard_sync.fetch_add(1, std::memory_order_relaxed);
        } else {
            g_state->att_rejected.fetch_add(1, std::memory_order_relaxed);
        }
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
        const PushVerdict verdict = g_state->runner->PushPosition(sample);
        g_state->position_mutex.unlock();
        if (verdict == PushVerdict::kRecorded) {
            g_state->position_fused.fetch_add(1, std::memory_order_relaxed);
        } else {
            g_state->pos_rejected.fetch_add(1, std::memory_order_relaxed);
        }
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

// --- G9 run control: RC commands drive the Manifold execution -------------
// NOTE: the run-control DECISION logic (command snapshot -> runner
// transitions + runtime facts) lives in ksi::safety::RunControl
// (edge/include/ksi_safety/runtime/run_control.hpp) and is host-tested in
// edge/tests/test_run_control.cpp (the endpoint half — revision, order,
// dedup, epoch — in edge/tests/test_runtime_control.cpp). What follows is
// a thin DJI-facing adapter only: it takes ONE coherent command snapshot
// under a single service lock, delegates to RunControl, publishes the
// resulting facts, and requests the final summary. Sampling the session
// counters and the bundle generation in separate calls is forbidden here:
// a command landing between the samples fuses contradictory facts. The
// adapter itself is verified on device (A9-4), never on the host.
//
// The RC owns requests and confirmations; the runner owns execution. The
// FINAL operator intent wins: START-then-STOP in one poll ends stopped,
// STOP-then-START ends started, APPLY+START installs then starts.
//
// No start means no processing: WorkerMain drops frames while the gate is
// closed. MOP-link loss never reaches the runner — it only gates EmitTick
// inside MopService, so local observation continues while disconnected.

void PublishSnapshot(AppState* state) {
    MopService* mop = state->mop;
    if (mop == nullptr || state->runner == nullptr) {
        return;
    }
    mop->PublishRuntimeFacts(
        SnapshotToFacts(state->runner->Snapshot()));
}

// Bundle identity for the audit journal (empty when absent/unknown).
std::string BundleIdOf(const ksi::safety::JsonValue* bundle) {
    if (bundle == nullptr) return "";
    const ksi::safety::JsonValue* id = bundle->Find("bundle_id");
    return (id != nullptr && id->IsString()) ? id->strValue() : "";
}

std::string BundleShaOf(const ksi::safety::JsonValue* bundle) {
    if (bundle == nullptr) return "";
    const ksi::safety::JsonValue* sha = bundle->Find("bundle_sha256");
    return (sha != nullptr && sha->IsString()) ? sha->strValue() : "";
}

std::string FirstReason(const std::vector<std::string>& reasons) {
    return reasons.empty() ? "" : reasons[0];
}

// --- Source evidence (the origin of an observation) ---------------------------

// Bounds the evidence store on the device. The store REFUSES writes past
// this bound (it never evicts and never silently drops): when the bound is
// reached the run says so instead of pretending the frames exist.
constexpr std::uint64_t kEvidenceCapacityBytes = 128ULL * 1024ULL * 1024ULL;

// Keeps the source frame of a material observation.
//
// WHY: the MOP envelope carries the judgement (class, box, digest), not the
// pixels the detector consumed. Without the frame a reviewer can check the
// number but never the picture behind it, and the RC cannot tell an
// observation apart from a mis-detection.
//
// POLICY (explicit, not incidental):
//   - only observations that actually saw something (a fresh detection) are
//     kept: an empty frame carries no review value and would fill the
//     device in seconds;
//   - the asset id and the observation id are derived from the SAME flight,
//     boot and tick, so the link between the stored bytes and the judgement
//     is a derivation, not a guess;
//   - a full store refuses the frame and reports it: a missing frame is
//     visible, a silently missing frame is not.
void CaptureSourceFrame(AppState* state, const ksi::safety::TickRecord& record,
                         const FramePacket& packet) {
    if (state->evidence == nullptr || state->mop == nullptr) return;
    if (packet.rgb.empty()) return;
    ksi::safety::SourceFrame frame;
    frame.flight_id = state->mop->flightId();
    frame.edge_boot_id = state->mop->edgeBootId();
    frame.frame_id = packet.metadata.frame_id;
    frame.received_monotonic_ns = packet.metadata.received_monotonic_ns;
    frame.rgb = packet.rgb.data();
    frame.rgb_size = packet.rgb.size();
    const EvidenceStatus status = state->evidence->Capture(record, frame);
    if (status == EvidenceStatus::kOk) return;
    if (status == EvidenceStatus::kDuplicate) return;  // already kept
    if (status == EvidenceStatus::kInvalid) return;     // nothing material to keep
    if (status == EvidenceStatus::kFull) {
        std::fprintf(stderr,
                     "Evidence store full (%llu bytes): the source frame of "
                     "this observation is NOT retained\n",
                     static_cast<unsigned long long>(state->evidence->capacity_bytes()));
        return;
    }
    std::fprintf(stderr, "Evidence store refused a source frame (%s)\n",
                 EvidenceStatusName(status));
}

// Seals the session's captured frames into one immutable revision.
//
// The revision is what a collector exports: it names the exact asset ids and
// digests the session covered, so the Core can tell "this frame is the one
// behind that observation" without guessing. Sealing never deletes: the
// bytes stay until a retention decision says otherwise.
void SealEvidenceRevision(AppState* state, std::int64_t now_ns) {
    if (state->evidence == nullptr || state->mop == nullptr) return;
    const std::string flight_id = state->mop->flightId();
    const std::string boot_id = state->mop->edgeBootId();
    std::string revision_id;
    std::string manifest;
    std::string error;
    if (!state->evidence->Seal(flight_id, boot_id, now_ns, &revision_id, &manifest,
                               &error)) {
        if (error.rfind("no source frames", 0) == 0) return;  // nothing to seal
        std::fprintf(stderr, "Evidence revision NOT sealed: %s\n", error.c_str());
        return;
    }
    std::fprintf(stderr, "Evidence revision sealed: %s (%zu source frames)\n",
                 revision_id.c_str(), state->evidence->captured_count());
}

// Unit A passive autonomy, explicit opt-in only (--autonomy-evidence-dir).
// Identity comes from the live MOP service when present (adoption changes
// recreate the processor: asset ids never merge lineages), else from the
// standalone diagnostic labels. Invalid config disables with a reason;
// never a silent partial run.
void MaybeInitAutonomy(AppState* state,
                         const ksi_safety_edge_app::AppConfig& args) {
    if (args.autonomy_evidence_dir == nullptr) return;
    std::string flight = "standalone-flight";
    std::string boot = "standalone-boot";
    if (state->mop != nullptr) {
        const auto source_identity = state->mop->SourceIdentitySnapshot();
        flight = source_identity.flight_id;
        boot = source_identity.edge_boot_id;
        if (flight.empty() || boot.empty()) return;  // no identity yet
    }
    const std::string identity = flight + "|" + boot;
    if (state->autonomy != nullptr && state->autonomy_identity == identity) {
        return;
    }
    if (state->autonomy != nullptr) {
        const std::int64_t now_ns = MonotonicNowNs();
        state->autonomy->RequestStop(now_ns);
        std::string revision_id, manifest, error;
        if (state->autonomy->Seal(&revision_id, &manifest, &error)) {
            std::fprintf(stderr, "Autonomy revision sealed: %s (%zu)\n",
                         revision_id.c_str(),
                         state->autonomy->artifacts().size());
        }
        std::fprintf(stderr,
                     "Autonomy identity moved (%s): processor recreated\n",
                     identity.c_str());
    }
    ksi::safety::AutonomousConfig config;
    config.enabled = true;
    config.ring.max_frames = 64;
    config.ring.max_bytes = 64ULL * 1024 * 1024;
    config.ring.pre_frames = static_cast<std::size_t>(
        args.autonomy_pre_frames < 0 ? 0 : args.autonomy_pre_frames);
    config.ring.post_frames = static_cast<std::size_t>(
        args.autonomy_post_frames < 0 ? 0 : args.autonomy_post_frames);
    config.ring.window_deadline_ns =
        args.autonomy_window_deadline_ms * 1000000LL;
    config.window_min_interval_ns =
        args.autonomy_window_min_interval_ms * 1000000LL;
    config.store_capacity_bytes =
        static_cast<std::uint64_t>(args.autonomy_capacity_bytes < 0
                                       ? 0
                                       : args.autonomy_capacity_bytes);
    config.evidence_dir = args.autonomy_evidence_dir;
    config.flight_id = flight;
    config.edge_boot_id = boot;
    config.stream_id = ConfiguredRgbStreamId();
    if (state->runner != nullptr) {
        config.effective_config_hash = state->runner->effective_config_hash();
        config.config_epoch = state->runner->config_epoch();
    }
    config.camera_manual_override = args.camera_manual;
    config.source_attention = args.source_attention;
    config.simulated_input = false;  // device input, never synthetic
    std::string error;
    if (!ksi::safety::IsValidAutonomousConfig(config, &error)) {
        std::fprintf(stderr, "Autonomy disabled (bad config): %s\n",
                     error.c_str());
        state->autonomy.reset();
        state->autonomy_identity.clear();
        return;
    }
    auto processor =
        std::make_unique<ksi::safety::AutonomousProcessor>(config);
    if (!processor->valid()) {
        std::fprintf(stderr, "Autonomy disabled: %s\n",
                     processor->error().c_str());
        state->autonomy.reset();
        state->autonomy_identity.clear();
        return;
    }
    state->autonomy = std::move(processor);
    state->autonomy_identity = identity;
    state->autonomy_saturation_logged = false;
    std::fprintf(stderr, "Autonomy on (SHADOW, passive): %s\n",
                 identity.c_str());
}

// Seals the autonomy artifacts (assessments + windows + coverage). Best
// effort at stop/shutdown: unsealed autonomy bytes are unexportable.
void SealAutonomyRevision(AppState* state, std::int64_t now_ns) {
    if (state->autonomy == nullptr) return;
    state->autonomy->RequestStop(now_ns);
    std::string revision_id, manifest, error;
    if (!state->autonomy->Seal(&revision_id, &manifest, &error)) {
        if (error.rfind("no autonomous artifacts", 0) == 0) return;
        std::fprintf(stderr, "Autonomy revision NOT sealed: %s\n",
                     error.c_str());
        return;
    }
    std::fprintf(stderr, "Autonomy revision sealed: %s (%zu artifacts)\n",
                 revision_id.c_str(), state->autonomy->artifacts().size());
}

void SyncRunControl(AppState* state) {
    MopService* mop = state->mop;
    if (mop == nullptr || state->runner == nullptr) {
        return;
    }
    // Write-ahead audit gate (see ksi::safety::AuditGate): every required
    // durability outcome is journalled BEFORE the claim that depends on
    // it — Step (commands), confirm/reject (install receipt), publish
    // (state), summary/ship (close). The first failure suspends the rest
    // of the poll: no Step, no confirm, no publish, no clean summary. The
    // run latches DEGRADED and processing pauses via the in-runner durable
    // gate; nothing here declares a clean STOPPED and there is no silent
    // auto-resume (the latch is sticky).
    const std::int64_t now_ns = MonotonicNowNs();
    ksi::safety::AuditGate gate;
    auto suspend_poll = [&]() {
        if (state->runner->hasDurableFailure()) {
            return;
        }
        std::string why = std::string("durable evidence unavailable [stage: ") +
                          gate.failedStage() + "]";
        std::string detail;
        if (mop->spoolFailed(&detail) && !detail.empty()) {
            why = detail;
        } else if (mop->auditFailed(&detail) && !detail.empty()) {
            why = detail;
        } else if (std::string(gate.failedStage()) == "commands-overflow") {
            why = "accepted commands dropped by bounded log";
        }
        state->runner->NoteDurableFailure(why);
        // Announce the suspension with the CURRENT snapshot (never a stale
        // pre-transition one): the RC must see DEGRADED, not frozen
        // healthy facts.
        mop->PublishRuntimeFacts(
            SnapshotToFacts(state->runner->Snapshot()));
        std::fprintf(stderr,
                     "Mop: durable evidence failed, run suspended: %s\n",
                     why.c_str());
    };
    std::string spool_reason;
    gate.Require(!mop->spoolFailed(&spool_reason), "spool");
    gate.Require(mop->AuditIdentity(now_ns), "identity");
    MopService::MopRunCommandSnapshot snap;
    mop->TakeRunCommandSnapshot(&snap);
    bool commands_overflow = false;
    const std::vector<ksi::safety::AcceptedCommand> accepted =
        mop->TakeAcceptedCommands(&commands_overflow);
    for (const auto& command : accepted) {
        gate.Require(mop->AuditCommand(command, snap.session.command_epoch,
                                       now_ns),
                     "commands");
    }
    if (commands_overflow) {
        // The bounded log dropped commands: journal the gap marker, then
        // suspend — acting on a partial command history could invert the
        // operator's final intent, and the missing commands are a durable
        // evidence gap the RC must see.
        mop->AuditCommand(ksi::safety::AcceptedCommand{"", "COMMANDS_OVERFLOW",
                                                       0},
                          snap.session.command_epoch, now_ns);
        gate.Require(false, "commands-overflow");
    }
    if (gate.suspended()) {
        suspend_poll();
        return;
    }
    RunControlInputs in;
    in.command_epoch = snap.session.command_epoch;
    in.command_revision = snap.session.command_revision;
    in.desired_running = snap.session.desired_running;
    in.start_count = snap.session.start_count;
    in.stop_count = snap.session.stop_count;
    in.last_command_was_start = snap.session.last_command_was_start;
    in.applied_generation = snap.session.applied_generation;
    in.has_bundle = snap.bundle_changed && snap.session.has_bundle;
    in.bundle = in.has_bundle ? &snap.bundle : nullptr;
    in.has_pending_bundle = snap.has_pending_bundle;
    in.pending_bundle = in.has_pending_bundle ? &snap.pending_bundle : nullptr;
    RunControlOutcome out = state->run_control.Step(*state->runner, in);
    // Install receipts are audited write-ahead of every claim below
    // (confirm / reject / publish / summary / ship): the history carries
    // the outcome with the bundle identity before the RC can observe it.
    if (!out.pending_outcome.empty()) {
        gate.Require(mop->AuditInstall(BundleIdOf(in.pending_bundle),
                                       BundleShaOf(in.pending_bundle),
                                       in.applied_generation,
                                       out.pending_outcome,
                                       FirstReason(out.pending_reasons),
                                       now_ns),
                     "install");
    } else if (out.bundle_installed || out.bundle_parse_failed ||
               out.install_refused) {
        const std::string outcome =
            out.bundle_installed
                ? "installed"
                : (out.bundle_parse_failed ? "parse_failed" : "refused");
        const std::string reason = FirstReason(
            out.bundle_parse_failed ? out.bundle_errors : out.install_reasons);
        gate.Require(mop->AuditInstall(BundleIdOf(in.bundle),
                                       BundleShaOf(in.bundle),
                                       in.applied_generation, outcome, reason,
                                       now_ns),
                     "install");
    }
    if (out.runtime_aborted) {
        gate.Require(mop->AuditInstall("", "", in.applied_generation,
                                       "parked", "", now_ns),
                     "install-parked");
    }
    if (gate.suspended()) {
        // The install receipt (or park record) could not be journalled: an
        // installed-but-uncommitted config must never run or be claimed.
        // Report truthfully so the RC is not left hanging (installer
        // reasons when it ruled, audit failure when it installed), and
        // leave everything else unclaimed. No AbortRuntime here (the gate
        // already pauses processing); no clean STOPPED is declared.
        if (out.pending_outcome == "installed") {
            mop->RejectPendingApply(
                std::vector<std::string>{"audit journal failed"});
            std::fprintf(stderr, "Mop: pending bundle install uncommitted "
                                 "(audit journal failed); rejected\n");
        } else if (out.pending_outcome == "refused" ||
                   out.pending_outcome == "parse_failed") {
            mop->RejectPendingApply(out.pending_reasons);
            std::fprintf(stderr, "Mop: pending bundle install refused "
                                 "(%s); journal failed too\n",
                         out.pending_reasons.empty()
                             ? "unknown"
                             : out.pending_reasons[0].c_str());
        }
        suspend_poll();
        return;
    }
    // Deferred install receipt: confirm switches the bundle and answers
    // MISSION_APPLIED; a refusal/failure answers REJECTED and keeps the old
    // install and provenance. A failed confirm means the identity changed
    // mid-install — park instead of running the wrong flight's config, and
    // publish the CURRENT (post-abort) snapshot, never the stale facts.
    if (out.pending_outcome == "refused" ||
        out.pending_outcome == "parse_failed") {
        mop->RejectPendingApply(out.pending_reasons);
        std::fprintf(stderr,
                     "Mop: pending bundle rejected (%s); keeping installed\n",
                     out.pending_reasons.empty()
                         ? "unknown"
                         : out.pending_reasons[0].c_str());
    }
    if (out.pending_outcome == "installed" && !mop->ConfirmPendingApply()) {
        std::fprintf(stderr,
                     "Mop: install confirmed by runner but identity moved; "
                     "parking the runtime\n");
        state->runner->AbortRuntime();
        out.facts = SnapshotToFacts(state->runner->Snapshot());
    }
    // Unit A provenance: machine assessments cite the mission actually
    // installed (never a requested-but-uninstalled bundle). A failed
    // confirm parks the runner above, so its gate drops every frame and
    // autonomy observes nothing unprovenanced.
    if (state->autonomy != nullptr && out.pending_outcome == "installed" &&
        in.pending_bundle != nullptr) {
        ksi::safety::MopEvidenceContext evidence_context;
        if (ksi::safety::EvidenceContextFromBundle(*in.pending_bundle,
                                                   &evidence_context,
                                                   nullptr)) {
            state->autonomy->SetMissionPolicyRevisions(
                evidence_context.mission_revision_id,
                evidence_context.policy_revision_id);
        }
    }
    if (out.bundle_parse_failed && out.pending_outcome.empty()) {
        std::fprintf(stderr,
                     "Mop: applied bundle fails semantic parse; keeping the "
                     "installed config\n");
        for (const auto& error : out.bundle_errors) {
            std::fprintf(stderr, "Mop:   bundle error: %s\n", error.c_str());
        }
    }
    if (out.install_refused && out.pending_outcome.empty()) {
        std::fprintf(stderr,
                     "Mop: bundle install refused (keeping the installed "
                     "config): %s\n",
                     out.install_reasons.empty()
                         ? "unknown"
                         : out.install_reasons[0].c_str());
    }
    // State transition audit runs write-ahead of the publish claim,
    // so the facts the RC observes are already in the offline history.
    gate.Require(mop->AuditState(state->runner->Snapshot(), now_ns),
                 "state");
    if (gate.suspended()) {
        suspend_poll();
        return;
    }
    if (out.facts_dirty) {
        mop->PublishRuntimeFacts(out.facts);
    }
    if (out.summary_requested) {
        gate.Require(mop->AuditClose(out.summary_close_ts,
                                    state->runner->processed_frame_count(),
                                    now_ns),
                     "close");
        if (gate.suspended()) {
            suspend_poll();
            return;
        }
        mop->EmitFinalSummary();  // emits only on new ticks (idempotent)
        mop->ShipAuditTrail();    // close ships the history as FLIGHT_LOG
        // Seal what the session actually kept: the revision names the
        // frames a collector may export and the digests that prove them.
        SealEvidenceRevision(state, now_ns);
        // Same for the autonomy artifacts (assessments + windows).
        SealAutonomyRevision(state, now_ns);
    }
    // Backstop: journal or spool failures observed anywhere above already
    // suspended this poll; a failure racing in afterwards latches here for
    // the next poll (the in-runner gate already pauses processing).
    {
        std::string audit_reason;
        if ((mop->auditFailed(&audit_reason) ||
             mop->spoolFailed(&audit_reason)) &&
            !state->runner->hasDurableFailure()) {
            state->runner->NoteDurableFailure(
                audit_reason.empty() ? "audit journal failed" : audit_reason);
            std::fprintf(stderr,
                         "Mop: audit journal failed, run degraded: %s\n",
                         audit_reason.c_str());
        }
    }
    // Unit A backstop: refused autonomy evidence degrades the run the
    // same way (never a healthy-looking run beside a silent evidence gap).
    if (state->autonomy != nullptr && state->autonomy->hasWriteFailure() &&
        !state->runner->hasDurableFailure()) {
        state->runner->NoteDurableFailure(
            state->autonomy->coverage().write_failure);
        std::fprintf(stderr, "Autonomy evidence refused, run degraded: %s\n",
                     state->autonomy->coverage().write_failure.c_str());
    }
    // Capacity is NOT that case: observation continues and the gap is
    // explicit. Name the concrete cause once so the installer can size the
    // store instead of reading an opaque code.
    if (state->autonomy != nullptr &&
        state->autonomy->hasEvidenceSaturation() &&
        !state->autonomy_saturation_logged) {
        state->autonomy_saturation_logged = true;
        std::fprintf(stderr,
                     "Autonomy evidence saturated (%s): observation continues, "
                     "artifacts are NOT being kept\n",
                     state->autonomy->saturationReason().c_str());
    }
    // A stalled outbound write is a link loss, not progress: the peer is not
    // reading. The run degrades so the RC sees a broken link instead of a
    // frozen "analysis running" state (measured on the Manifold 3).
    {
        std::string stall_reason;
        if (mop->writeStalled(&stall_reason) &&
            !state->runner->hasDurableFailure()) {
            state->runner->NoteDurableFailure(
                stall_reason.empty() ? "MOP write stalled (peer not reading)"
                                     : stall_reason);
            std::fprintf(stderr,
                         "Mop: outbound link stalled, run degraded: %s\n",
                         stall_reason.c_str());
        }
    }
}

// --- Worker (non-callback thread: runner + file I/O live here) ----------------

void WorkerMain(AppState* state) {
    FramePacket packet;
    for (;;) {
        SyncRunControl(state);
        // P1 stream health, independent of frames: a dead RGB stream must
        // degrade the reported inputs instead of looking healthy forever.
        // Caller clock only (the runner never reads a clock itself).
        const std::int64_t loop_now_ns = MonotonicNowNs();
        state->runner->HealthTick(loop_now_ns);
        // Unit A autonomy (opt-in): identity-tracked init + deadline closes
        // that need no new frames.
        if (state->app_args.autonomy_evidence_dir != nullptr) {
            MaybeInitAutonomy(state, state->app_args);
            if (state->autonomy != nullptr) {
                state->autonomy->HealthTick(loop_now_ns);
            }
        }
        if (!state->frames.TryPop(&packet)) {
            if (state->worker_stop.load(std::memory_order_relaxed)) {
                return;  // queue drained after stop: exit.
            }
            // Idle: still publish the health verdict so the RC sees a stream
            // loss (DEGRADED) without waiting for the next processed frame.
            // Pre-start the STATUS payload keeps its legacy shape.
            const RuntimeState gate = state->runner->runtime_state();
            if (gate == RuntimeState::kStarting ||
                gate == RuntimeState::kRunning ||
                gate == RuntimeState::kDegraded ||
                gate == RuntimeState::kStopped) {
                PublishSnapshot(state);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        if (state->runner->processing_enabled()) {
            const auto source_identity = state->mop != nullptr
                ? state->mop->SourceIdentitySnapshot()
                : ksi_safety_edge_app::SourceIdentity{"standalone-flight", "standalone-boot"};
            const FrameContext ctx = ksi_safety_edge_app::MakeReceiveOnlySourceContext(
                packet.metadata, source_identity, ConfiguredRgbStreamId());
            // Unit A: the SAME shared wiring as the host tools. The gate
            // still rules: a dropped frame reaches neither the tracker nor
            // the autonomy processor (no invented ticks).
            TickRecord record;
            ksi::safety::SharedTickResult shared;
            bool has_shared = false;
            const auto tick_start = std::chrono::steady_clock::now();
            if (state->autonomy != nullptr && !packet.rgb.empty()) {
                shared = ksi::safety::ProcessSharedTick(
                    *state->runner, *state->autonomy, packet, ctx,
                    state->detector.get(), packet.rgb.data(),
                    packet.rgb.size());
                record = shared.record;
                has_shared = true;
            } else {
                record = state->runner->ProcessFrame(packet, ctx,
                                                     state->detector.get());
            }
            const std::uint64_t i =
                state->samples_written.fetch_add(1, std::memory_order_relaxed);
            // Wall time of THIS processed tick (detection + tracking + any
            // synchronous evidence I/O). Diagnostic only: it is not a
            // Manifold performance claim and not evidence. A device run
            // compares it with the bounded-queue drop counter to decide the
            // evidence policy; see docs/verification/2026-10-27-tick-cost-finding.md.
            const long long tick_us =
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - tick_start)
                    .count();
            // Counts-only local diagnostic log (detection counts, match
            // grades, track sizes): NOT evidence and NOT a reconstruction
            // source. Real evidence is the MOP journal (spool) + summaries;
            // this file cannot replay observations, episodes, or digests.
            std::fprintf(
                state->out,
                "{\"type\":\"frame\",\"i\":%llu,\"frame_id\":%u,\"w\":%u,"
                "\"h\":%u,\"recv_mono_ns\":%lld,"
                "\"detect\":\"%s\",\"dets\":%zu,"
                "\"att\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
                "\"pos\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
                "\"spatial_eligible\":%s,\"tracks\":%zu,\"tick_us\":%lld,"
                "\"drops\":%llu}\n",
                static_cast<unsigned long long>(i), packet.metadata.frame_id,
                packet.metadata.width, packet.metadata.height,
                static_cast<long long>(packet.metadata.received_monotonic_ns),
                ToString(record.detection_status), record.detections.size(),
                ToString(record.attitude_quality), ToString(record.attitude_reason),
                static_cast<long long>(record.attitude_gap_ns),
                ToString(record.position_quality), ToString(record.position_reason),
                static_cast<long long>(record.position_gap_ns),
                record.spatial_eligible ? "true" : "false",
                record.tracks.size(), tick_us,
                static_cast<unsigned long long>(
                    state->frames_dropped.load(std::memory_order_relaxed)));
            if ((i & 15U) == 0U) {
                std::fflush(state->out);
            }
            // A9-2: real evidence for this tick goes to the RC over MOP (no-op
            // when the service is disabled or no mission has been applied yet).
            if (state->mop != nullptr) {
                const bool observed = state->mop->EmitTick(record);
                if (observed) {
                    CaptureSourceFrame(state, record, packet);
                }
                // Unit A closure: the sealed assessment JSON rides the
                // existing artifact plane (ANNOUNCE + CHUNKs, no new MOP
                // type). Independent of the observation envelope above:
                // withheld geometry still ships its assessed/undetermined
                // fact; the RC viewer shows disposition + reasons, and the
                // sealed revision stays the Core-receipt path for bytes.
                // A false return is gated/dropped (counted inside the
                // service as skipped/dropped_assessments — never
                // deliverable, never folded into a delivered count); the
                // sealed store copy is the retention path, so there is
                // nothing to retry here and no silent loss to report.
                if (has_shared && shared.autonomy_observed &&
                    shared.assessment_artifact.write_status ==
                        ksi::safety::EvidenceStatus::kOk) {
                    const bool assessment_sent =
                        state->mop->EmitAssessmentArtifact(
                            shared.assessment_artifact.asset_id,
                            shared.assessment.flight_id,
                            shared.assessment.edge_boot_id,
                            shared.assessment.ToJson());
                    (void)assessment_sent;
                }
            }
            PublishSnapshot(state);
        }
        // Gate closed (no SESSION_START yet, or after SESSION_STOP): the
        // frame is dropped unprocessed — no detection, no tracker/session
        // step, no counter, no evidence.
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

// Startup configuration lives in the DJI-free helper
// (app_config.hpp) so host tests exercise the exact argv decision the
// deployed DPK runs. DeployDefaults come from the CMake deployment cache
// (trusted installer); CLI flags override them for development.
using ksi_safety_edge_app::AppConfig;
using ksi_safety_edge_app::DeployDefaults;
using ksi_safety_edge_app::ParseStatus;
using ksi_safety_edge_app::ResolveAppConfig;

DeployDefaults AppDeployDefaults() {
    DeployDefaults deploy;
    deploy.mop_channel = KSI_SAFETY_EDGE_MOP_CHANNEL;
    deploy.diag_duration_sec = KSI_SAFETY_EDGE_DIAG_DURATION_SEC;
    deploy.engine_path = KSI_SAFETY_EDGE_ENGINE_PATH;
    deploy.model_id = KSI_SAFETY_EDGE_MODEL_ID;
    deploy.autonomy_evidence_dir = KSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR;
    deploy.source_attention_policy = KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY;
    return deploy;
}

void PrintUsage(const char* prog) {
    std::fprintf(stderr,
                 "usage: %s [--out PATH] [--duration-sec N] [--engine PATH] "
                 "[--model-id ID] [--detect-every N] [--mop-channel N] "
                 "[--standalone] [--flight-id ID] [--state-dir DIR]\n"
                 "  argvless (DPK default): MOP service on the deploy "
                 "channel (0), unbounded, gate closed until APPLY+START\n"
                 "  --standalone: diagnostic capture (finite, auto-processed, "
                 "no MOP); --duration-sec 0 is unbounded service only\n"
                 "  --autonomy-evidence-dir DIR [--autonomy-pre N] "
                 "[--autonomy-post N]\n"
                 "  [--autonomy-window-deadline-ms N] "
                  "[--autonomy-window-min-interval-ms N]\n"
                  "  [--autonomy-capacity-bytes N] [--camera-manual]\n"
                  "       [--source-attention-policy KEY=VALUE,...] (source ROI only, no PPE verdict or camera motion)\n"
                 "  (autonomy: passive SHADOW assessment only, opt-in, never "
                 "auto-enabled)\n",
                 prog);
}

bool ParseArgs(int argc, char** argv, AppConfig* args) {
    std::string error;
    const ParseStatus status =
        ResolveAppConfig(argc, argv, AppDeployDefaults(), args, &error);
    if (status == ParseStatus::kHelp) {
        PrintUsage(argv[0]);
        std::exit(0);
    }
    if (status != ParseStatus::kOk) {
        std::fprintf(stderr, "%s\n", error.c_str());
        PrintUsage(argv[0]);
        return false;
    }
    return true;
}
}  // namespace

int main(int argc, char** argv) {
    AppConfig args;
    // Subscription/shutdown flags live at function scope so the fail-closed
    // `goto shutdown` / `goto finish` paths never cross an initialization.
    // (No initialised declaration may be added between a goto site and its
    // label — assign, don't initialise, after this block.)
    bool fc_init = false;
    bool sub_hard_sync = false, sub_pos = false, sub_vel = false, sub_gps = false,
         sub_gimbal = false;
    bool liveview_init = false, image_started = false;
    bool mop_requested = false;
    std::unique_ptr<MopService> mop;
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
    state.app_args = args;
    g_state = &state;

    // R1: the shared product runner + optional real D-FINE detector. This is
    // the same processing code path as edge/tools/replay_run.cpp; only the
    // input source differs. Risk rules/zones arrive at runtime: the RC's
    // MISSION_APPLY bundle is parsed and installed via ApplyRuntimeConfig
    // in SyncRunControl (G9), so the runner starts empty and gated.
    //
    // P1 model digest: the reported model_digest is the SHA-256 of the
    // ACTUAL loaded engine bytes (never fabricated, never empty on an
    // inference path). A missing/unreadable engine file stops the app with
    // a reason instead of stamping observations with an empty digest
    // (Core422 rejects those). No engine means no observations at all —
    // every tick stays NOT_RUN and the engine input reports absent.
    std::string engine_digest;
#if defined(KSI_SAFETY_EDGE_HAS_TENSORRT)
    if (args.engine_path != nullptr) {
        std::string hash_error;
        engine_digest = Sha256HexOfFile(args.engine_path, &hash_error);
        if (engine_digest.empty()) {
            std::fprintf(stderr, "engine hash failed (%s): %s\n",
                         args.engine_path, hash_error.c_str());
            return 2;
        }
    }
#else
    if (args.engine_path != nullptr) {
        std::fprintf(stderr,
                     "built without TensorRT; configure with "
                     "-DKSI_SAFETY_EDGE_ENABLE_TENSORRT=ON to use --engine\n");
        return 2;
    }
#endif
    {
        RunnerConfig runner_config;
        runner_config.match.max_receive_gap_ns = 50LL * kNsPerMs;
        runner_config.match.max_bracket_interval_ns = 20LL * kNsPerMs;
        runner_config.detect_every_n_frames =
            static_cast<std::uint64_t>(args.detect_every);
        runner_config.attitude_ring_capacity = kAttitudeRingCapacity;
        runner_config.position_ring_capacity = kPositionRingCapacity;
        // P1 stream health: the worker feeds HealthTick every loop with the
        // receive clock; a dead RGB stream degrades instead of reporting
        // healthy inputs forever.
        runner_config.video_stale_after_ns = 1000000000LL;  // 1 s
        runner_config.video_lost_after_ns = 5000000000LL;   // 5 s
        SessionConfig session_config;
        session_config.tracker = TrackerConfig{};
        session_config.max_gap_ms = 5000;
        session_config.zone_envelope_m = 1.0;
        ModelProfile profile;
        // Effective model identity: CLI --model-id wins, else the trusted
        // deploy default, else the existing code default (never invent an
        // operational model name when none was provided).
        profile.model_id =
            (args.model_id != nullptr && args.model_id[0] != '\0')
                ? args.model_id
                : "dfine-s-coco";
        profile.backend = "tensorrt";
        if (!engine_digest.empty()) {
            profile.model_digest = "sha256:" + engine_digest;
        }
        state.runner = std::make_unique<SessionRunner>(
            runner_config, session_config, std::vector<RiskRule>(),
            std::vector<RiskRule>(), std::vector<ZoneRevision>(), profile);
    }
#if defined(KSI_SAFETY_EDGE_HAS_TENSORRT)
    if (args.engine_path != nullptr) {
        state.runtime = std::make_unique<TensorRtRuntime>(args.engine_path);
        if (!state.runtime->ok()) {
            std::fprintf(stderr, "engine load failed: %s\n", args.engine_path);
            return 2;
        }
        state.detector = std::make_unique<DfineDetectorPort>(
            DfineConfig{},
            [runtime = state.runtime.get()](const float* input,
                                            std::vector<float>* logits,
                                            std::vector<float>* boxes) {
                return runtime->Run(input, logits, boxes);
            });
    }
#endif

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
                 "\"note\":\"recv-grade only; no capture-time claims; counts-only diagnostic, not evidence\"}\n",
                 kAppVersion, psdk_version, started_at_utc,
                 static_cast<long long>(anchor_mono_ns), anchor_wall_utc);
    std::fflush(state.out);

    // The only verified clock in this app: our own receive path.
    state.clocks.Verify("host_mono", TimeSourceKind::kHostReceive,
                        "in-process CLOCK_MONOTONIC");

    BootStage("glues_begin");
    // Unbounded operational service stops on signal; a finite run
    // (standalone default or explicit bound) reports the deadline.
    const char* stop_reason =
        (args.has_deadline && args.duration_sec > 0) ? "duration_exceeded"
                                                   : "signal";
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

    // Operational MOP channel service (the real RC <-> Edge byte
    // transport; message logic is host-tested in edge/tests/test_mop_*).
    // The argvless DPK default IS this service (no deadline, no automatic
    // processing): the RC commands the run after MISSION_APPLY. P0 gate
    // fallback: the decision key is whether MOP was REQUESTED, not whether
    // the pointer happens to be null. A requested-but-failed MOP service
    // must stay gated and report the failure — silently falling back to
    // automatic inference would run an uncommanded session.
    mop_requested = args.mop_service;
    if (mop_requested) {
        MopServiceConfig mop_config;
        mop_config.flight_id = args.flight_id;
        mop_config.channel_id = static_cast<std::uint16_t>(args.mop_channel);
        mop_config.spool_dir = args.state_dir;
        auto service = std::make_unique<MopService>(mop_config);
        if (service->Start()) {
            mop = std::move(service);
            state.mop = mop.get();
            // Source-evidence store: the bytes an observation was computed
            // from. Separate lifetime from the MOP spool (a delivery
            // guarantee that may compact acked envelopes) — this one is the
            // origin and is only ever removed by a retention decision.
            state.evidence = std::make_unique<ksi::safety::SourceEvidenceRecorder>(
                std::string(args.state_dir) + "/evidence",
                kEvidenceCapacityBytes);
            if (!state.evidence->ok()) {
                std::fprintf(stderr,
                             "Evidence store unavailable (%s): observations keep "
                             "their ids but no source frame is retained\n",
                             state.evidence->last_error().c_str());
                state.evidence.reset();
            }
            BootStage("mop_started");
        } else {
            std::fprintf(stderr, "MOP service failed to start (channel %d)\n",
                         args.mop_channel);
        }
    }
    if (mop_requested && mop == nullptr) {
        std::fprintf(stderr,
                     "MOP requested but unavailable: staying gated, no "
                     "automatic inference (fix the channel and restart)\n");
        stop_reason = "error";
        goto shutdown;
    }
    // G9: under MOP control nothing processes before SESSION_START (the
    // gate opens on the start edge in SyncRunControl). Standalone
    // diagnostic mode keeps the legacy always-process behavior (replay
    // driver, manual debug runs). The helper guarantees auto_process is
    // true only when the MOP service is off: never silent auto-analysis in
    // operational mode.
    state.runner->SetProcessingEnabled(args.auto_process);

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
        // Operational service runs until a termination signal: SESSION_STOP
        // ends analysis (the gate closes in the runner) but the service
        // stays ready for the next session — stopping the process here
        // would strand the RC. A finite --duration-sec (standalone default,
        // or an explicit operational bound) stops with diagnostics instead.
        // The helper bounds duration_sec so deadline_ns cannot overflow.
        const bool bounded = args.has_deadline && args.duration_sec > 0;
        const std::int64_t deadline_ns =
            bounded ? MonotonicNowNs() + args.duration_sec * kNsPerSec : 0;
        while (!state.worker_stop.load(std::memory_order_relaxed)) {
            const std::int64_t now_ns = MonotonicNowNs();
            if (bounded && now_ns >= deadline_ns) {
                stop_reason = "duration_exceeded";
                break;
            }
            std::int64_t wait_ns = kPollIntervalNs;
            if (bounded) {
                const std::int64_t remaining_ns = deadline_ns - now_ns;
                if (remaining_ns < wait_ns) {
                    wait_ns = remaining_ns > 0 ? remaining_ns : 0;
                }
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
    if (mop) {
        // Final session evidence before the channel closes (best effort).
        mop->EmitFinalSummary();
        // Seal the SOURCE evidence too. Without a sealed revision the
        // captured frames are bytes on disk that no collector can export —
        // an abrupt stop would silently produce unexportable evidence.
        SealEvidenceRevision(&state, MonotonicNowNs());
        SealAutonomyRevision(&state, MonotonicNowNs());
        // Shutdown ships the audit history as a FLIGHT_LOG artifact if the
        // stop edge did not already (spool-journalled; best effort on a
        // closing channel — the journal file itself retains the history).
        mop->ShipAuditTrail();
        const std::uint64_t skipped = mop->skipped_evidence_ticks();
        if (skipped > 0) {
            // Skipped means no flight identity or no applied mission —
            // disconnection alone never skips (those ticks are recorded and
            // spool-journalled for replay).
            std::fprintf(stderr,
                         "Mop: %llu ticks had no evidence path "
                         "(no flight identity or no applied mission)\n",
                         static_cast<unsigned long long>(skipped));
        }
        const std::uint64_t offline = mop->offline_recorded_ticks();
        if (offline > 0) {
            std::fprintf(stderr,
                         "Mop: %llu ticks recorded while disconnected "
                         "(aggregates folded, envelopes spooled for replay; "
                         "journal refusals: %llu)\n",
                         static_cast<unsigned long long>(offline),
                         static_cast<unsigned long long>(
                             mop->spool_refused()));
        }
        const std::uint64_t dropped = mop->durable_dropped_ticks();
        if (dropped > 0 || state.runner->hasDurableFailure()) {
            // CLOSING FAILURE LOG: ticks refused a durable record are NOT
            // in the aggregates and never reach the RC — the run's degraded
            // reasons and the DURABLE_EVIDENCE_GAP summary flag say so, and
            // this log states the counts. Never a clean close on a gap.
            std::fprintf(stderr,
                         "Mop: DURABLE EVIDENCE FAILURE: %llu ticks refused "
                         "a journal record (%s); spool refusals: %llu; run "
                         "degraded, no clean summary\n",
                         static_cast<unsigned long long>(dropped),
                         state.runner->hasDurableFailure()
                             ? state.runner->durableFailureReason().c_str()
                             : "unknown",
                         static_cast<unsigned long long>(
                             mop->spool_refused()));
        }
        mop->Stop();
        mop.reset();
    }
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
                 "\"att_rejected\":%llu,"
                 "\"position_fused\":%llu,\"position_malformed\":%llu,"
                 "\"pos_ring_contended\":%llu,\"pos_rejected\":%llu,"
                 "\"velocity\":%llu,\"gimbal\":%llu,"
                 "\"gps_details\":%llu,\"samples_written\":%llu,"
                 "\"detector\":\"%s\","
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
                 static_cast<unsigned long long>(state.att_rejected.load()),
                 static_cast<unsigned long long>(state.position_fused.load()),
                 static_cast<unsigned long long>(state.position_malformed.load()),
                 static_cast<unsigned long long>(state.pos_ring_contended.load()),
                 static_cast<unsigned long long>(state.pos_rejected.load()),
                 static_cast<unsigned long long>(state.velocity.load()),
                 static_cast<unsigned long long>(state.gimbal.load()),
                 static_cast<unsigned long long>(state.gps_details.load()),
                 static_cast<unsigned long long>(state.samples_written.load()),
                 args.engine_path != nullptr ? "tensorrt" : "none",
                 stop_reason);
    std::fclose(state.out);
    state.out = nullptr;
}
    g_state = nullptr;
    return std::strcmp(stop_reason, "error") == 0 ? 1 : 0;
}
