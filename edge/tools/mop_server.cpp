// 가상 Manifold(호스트 Edge 대역) — TCP 위에서 **실제 C++ Edge 엔진**을 돌린다.
//
// 왜: 실제 Manifold 3가 연결되지 않은 동안에도 RC↔Edge **프로토콜·계약 계층**을
// 실행 가능한 상태로 검증하기 위해서다. 이 도구는 psdk-app(Manifold에서 도는
// 앱)이 하는 일 중 **바이트 전송 계층만**을 TCP로 대체한다. 메시지 처리·프레이밍·
// 엔벨로프 검증·상태 머신은 모두 `edge/src/transport/`의 실코드다.
//
// 하는 것:
//   - RC(가상 RC 포함)의 MOP 바이트를 받아 MopLink로 재조립·검증·처리한다.
//   - Edge의 응답(ACK / PREPARED / APPLIED / STATUS / SUMMARY)을 같은 프레이밍으로 돌려준다.
//   - 실행 상태(§2 6종 어휘 + 선택 필드)를 주기적으로 보고한다.
//
// 하지 않는 것(정직):
//   - 카메라·자세·추론 엔진이 없다. 그래서 입력은 `absent`이고 처리 프레임 수는 0이다.
//     상태를 "가짜 정상"으로 보고하지 않는다(§3-2 N7, §6-2).
//   - 관측/사건/증거를 지어내지 않는다. 증거는 실제 틱에서만 나온다.
//
// 사용:
//   mop_server --port 5050 [--flight-id <uuid>] [--max-seconds N]

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "ksi_safety/evidence/recorder.hpp"
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/perception/detector.hpp"
#include "ksi_safety/replay/frame_source.hpp"
#include "ksi_safety/replay/policy_file.hpp"
#include "ksi_safety/time/provenance.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "ksi_safety/perception/tensorrt_runtime.hpp"
#include "ksi_safety/runtime/run_control.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_link.hpp"

namespace {

using ksi::safety::JsonValue;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;
using ksi::safety::MopRuntimeFacts;
using ksi::safety::MopTickEvidence;

std::int64_t MonotonicNowNs() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
}

// TCP 전송 계층. 바이트 전송만 하며 MOP의 어떤 의미도 모른다.
class TcpByteTransport : public ksi::safety::MopByteTransport {
 public:
    explicit TcpByteTransport(int fd) : fd_(fd) {}
    ~TcpByteTransport() override {
        if (fd_ >= 0) ::close(fd_);
    }

    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        std::size_t offset = 0;
        while (offset < len) {
            const ssize_t written =
                ::send(fd_, data + offset, len - offset, MSG_NOSIGNAL);
            if (written <= 0) {
                // WHY: a silent write failure looks like "the RC is not
                // reading" from the other side. Say what the kernel said.
                std::fprintf(stderr,
                             "mop_server: send failed: %s (fd=%d, %zu/%zu)\n",
                             std::strerror(errno), fd_, offset, len);
                return false;
            }
            offset += static_cast<std::size_t>(written);
        }
        return true;
    }

 private:
    int fd_;
};

// 이 호스트 대역은 카메라·자세·엔진이 없다. 상태를 정직하게 보고하기 위한 값.
MopRuntimeFacts BuildHonestFacts(const MopEdgeEndpoint& endpoint) {
    MopRuntimeFacts facts;
    facts.has_state = true;
    if (!endpoint.hasAppliedBundle()) {
        facts.runtime_state = "REQUEST_ACCEPTED";
    } else if (!endpoint.running()) {
        facts.runtime_state = "CONFIG_APPLIED";
    } else {
        // 입력(영상·자세·엔진)이 전부 없는 상태 → 분석은 '돌고' 있지만 완전하지 않다.
        facts.runtime_state = "DEGRADED";
        facts.has_degraded_reasons = true;
        facts.degraded_reasons.push_back("video_input_lost");
        facts.degraded_reasons.push_back("telemetry_absent");
        facts.degraded_reasons.push_back("engine_absent");
    }
    facts.has_config_applied = true;
    facts.config_applied = endpoint.hasAppliedBundle();
    if (endpoint.hasAppliedBundle()) {
        facts.has_effective_config_hash = true;
        facts.effective_config_hash = endpoint.appliedBundleSha256();
    }
    facts.has_processed_frame_count = true;
    facts.processed_frame_count = 0;  // 이 호스트 대역은 프레임을 처리하지 않는다.
    facts.has_input_state = true;
    facts.input_state.video = "absent";
    facts.input_state.telemetry = "absent";
    facts.input_state.engine = "absent";
    return facts;
}

// The REAL runtime facts of a running Edge (the production mapping), used
// once the host service actually analyses frames. The RC reads these to tell
// "분석 중" from "분석 안 함" — a mock would let the RC believe a lie.
MopRuntimeFacts BuildRunnerFacts(const MopEdgeEndpoint& endpoint,
                                 const ksi::safety::SessionRunner* runner) {
    if (runner == nullptr) return BuildHonestFacts(endpoint);
    const ksi::safety::MopRuntimeFacts facts =
        ksi::safety::SnapshotToFacts(runner->Snapshot());
    MopRuntimeFacts out = facts;
    out.has_config_applied = true;
    out.config_applied = endpoint.hasAppliedBundle();
    if (endpoint.hasAppliedBundle()) {
        out.has_effective_config_hash = true;
        out.effective_config_hash = endpoint.appliedBundleSha256();
    }
    // The host service has NO live camera/telemetry inputs: the frames come
    // from a recorded corpus, so the input state says "stale", not "ok".
    out.has_input_state = true;
    out.input_state.video = "stale";
    out.input_state.telemetry = "stale";
    out.input_state.engine = "ok";
    return out;
}

}  // namespace

namespace {

// Strict uuid shape (8-4-4-4-12 lowercase/uppercase hex, 36 chars). The
// frozen collection-manifest.v1 schema requires uuid-format boot ids at
// Core open; a non-uuid host label ("host-boot") is rejected there, so the
// host tool validates early with a line that names the flag.
bool IsUuidShape(const std::string& value) {
    if (value.size() != 36) return false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        const char ch = value[i];
        const bool hyphen = (i == 8 || i == 13 || i == 18 || i == 23);
        if (hyphen) {
            if (ch != '-') return false;
        } else {
            const bool hex = (ch >= '0' && ch <= '9') ||
                             (ch >= 'a' && ch <= 'f') ||
                             (ch >= 'A' && ch <= 'F');
            if (!hex) return false;
        }
    }
    return true;
}

// SIGTERM/SIGINT must end the run CLEANLY: the evidence is sealed on the way
// out, so the collector can export it. A hard kill leaves the bytes on disk
// but NO sealed revision — captured evidence that cannot be exported.
std::atomic<bool> g_stop_requested{false};

void OnStopSignal(int) { g_stop_requested.store(true, std::memory_order_relaxed); }

}  // namespace

int main(int argc, char** argv) {
    int port = 5050;
    std::string bind_address = "0.0.0.0";
    std::string flight_id;
    // Host-only edge identity override (opt-in). Empty keeps the historical
    // labels ("host-boot" for replay/autonomy stores, pid-clock unique for
    // the MOP endpoint); a provided value must be uuid-shaped so the sealed
    // revision, manifest and transcript agree with what Core accepts.
    // Never silently invented for real runs: this tool is host-only.
    std::string boot_id;
    int max_seconds = 120;
    // Real-Edge mode: run the SAME SessionRunner the device app uses over a
    // replay manifest, keep the source frames in the evidence store, and
    // emit real observations over MOP. This is how the Edge side is
    // exercised without an aircraft.
    std::string replay_manifest;
    std::string evidence_dir;
    bool stub_detector = false;
    std::string model_id = "stub-detector";
    std::string model_digest;
    int frame_interval_ms = 200;
    // Real inference on the device (TensorRT): the engine is the D-FINE
    // profile the bundle pins. No engine = no detections (never a stub
    // presented as a real one).
    std::string engine_path;
    std::string policy_path;
    double score_threshold = 0.05;
    // Unit A passive autonomy (opt-in only; default off).
    std::string autonomy_evidence_dir;
    ksi::safety::SourceAttentionPolicy source_attention;
    long long autonomy_pre_frames = 0;
    long long autonomy_post_frames = 1;
    long long autonomy_window_deadline_ms = 500;
    long long autonomy_window_min_interval_ms = 1000;
    long long autonomy_capacity_bytes = 8LL * 1024 * 1024;

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        const bool has_next = (i + 1) < argc;
        if (arg == "--port" && has_next) {
            const std::string value = argv[++i];
            if (value.empty() || value.size() > 5 ||
                value.find_first_not_of("0123456789") != std::string::npos ||
                std::stoul(value) == 0 || std::stoul(value) > 65535) {
                std::fprintf(stderr, "--port must be an integer from 1 to 65535\n");
                return 2;
            }
            port = std::stoi(value);
        } else if (arg == "--bind-address" && has_next) {
            bind_address = argv[++i];
        } else if (arg == "--flight-id" && has_next) {
            flight_id = argv[++i];
        } else if (arg == "--boot-id" && has_next) {
            boot_id = argv[++i];
            if (!IsUuidShape(boot_id)) {
                std::fprintf(stderr,
                             "--boot-id must be uuid-shaped (8-4-4-4-12 hex), "
                             "got '%s'\n",
                             boot_id.c_str());
                return 2;
            }
        } else if (arg == "--max-seconds" && has_next) {
            max_seconds = std::atoi(argv[++i]);
        } else if (arg == "--replay-manifest" && has_next) {
            replay_manifest = argv[++i];
        } else if (arg == "--evidence-dir" && has_next) {
            evidence_dir = argv[++i];
        } else if (arg == "--stub-detector") {
            stub_detector = true;
        } else if (arg == "--model-id" && has_next) {
            model_id = argv[++i];
        } else if (arg == "--model-digest" && has_next) {
            model_digest = argv[++i];
        } else if (arg == "--frame-interval-ms" && has_next) {
            frame_interval_ms = std::atoi(argv[++i]);
        } else if (arg == "--engine" && has_next) {
            engine_path = argv[++i];
        } else if (arg == "--source-attention-policy" && has_next) {
            std::string error;
            if (!ksi::safety::ParseSourceAttentionPolicy(argv[++i], &source_attention, &error)) {
                std::fprintf(stderr, "%s\n", error.c_str());
                return 2;
            }
        } else if (arg == "--autonomy-evidence-dir" && has_next) {
            autonomy_evidence_dir = argv[++i];
        } else if (arg == "--autonomy-pre" && has_next) {
            autonomy_pre_frames = std::atoll(argv[++i]);
        } else if (arg == "--autonomy-post" && has_next) {
            autonomy_post_frames = std::atoll(argv[++i]);
        } else if (arg == "--autonomy-window-deadline-ms" && has_next) {
            autonomy_window_deadline_ms = std::atoll(argv[++i]);
        } else if (arg == "--autonomy-window-min-interval-ms" && has_next) {
            autonomy_window_min_interval_ms = std::atoll(argv[++i]);
            if (autonomy_window_min_interval_ms < 0 ||
                autonomy_window_min_interval_ms > 3600000) {
                std::fprintf(stderr,
                             "--autonomy-window-min-interval-ms must be "
                             "0..3600000 (0 = no interval bound)\n");
                return 2;
            }
        } else if (arg == "--autonomy-capacity-bytes" && has_next) {
            autonomy_capacity_bytes = std::atoll(argv[++i]);
        } else if (arg == "--policy" && has_next) {
            policy_path = argv[++i];
        } else if (arg == "--score-threshold" && has_next) {
            score_threshold = std::atof(argv[++i]);
        } else {
            std::fprintf(stderr,
                         "usage: mop_server [--port N] [--bind-address IPv4] [--flight-id UUID]"
                         " [--max-seconds N]\n"
                         "       [--boot-id UUID (host-only edge identity; "
                         "uuid shape required)]\n"
                         "       [--replay-manifest PATH] [--evidence-dir DIR]"
                         " [--stub-detector] [--model-id ID]"
                         " [--model-digest HEX] [--frame-interval-ms N]\n"
                         "       [--engine PATH] [--policy PATH]"
                         " [--score-threshold F]\n"
                         "       [--autonomy-evidence-dir DIR] [--autonomy-pre N]"
                         " [--autonomy-post N]\n"
                         "       [--autonomy-window-deadline-ms N]"
                         " [--autonomy-window-min-interval-ms N]\n"
                         " [--autonomy-capacity-bytes N]\n");
            return 2;
        }
    }

    if (source_attention.enabled && (autonomy_evidence_dir.empty() || autonomy_capacity_bytes <= 0)) {
        std::fprintf(stderr, "source attention requires non-empty --autonomy-evidence-dir and finite positive capacity\n");
        return 2;
    }
    // SIGTERM/SIGINT → clean stop (seal on the way out).
    ::signal(SIGTERM, OnStopSignal);
    ::signal(SIGINT, OnStopSignal);

    const int listener = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) {
        std::fprintf(stderr, "socket failed: %s\n", std::strerror(errno));
        return 1;
    }
    int reuse = 1;
    ::setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    sockaddr_in address{};
    address.sin_family = AF_INET;
    if (::inet_pton(AF_INET, bind_address.c_str(), &address.sin_addr) != 1) {
        std::fprintf(stderr, "invalid IPv4 bind address: %s\n", bind_address.c_str());
        ::close(listener);
        return 2;
    }
    address.sin_port = htons(static_cast<std::uint16_t>(port));
    if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        std::fprintf(stderr, "bind failed: %s\n", std::strerror(errno));
        return 1;
    }
    if (::listen(listener, 4) != 0) {
        std::fprintf(stderr, "listen failed: %s\n", std::strerror(errno));
        return 1;
    }
    std::fprintf(stderr, "mop_server: listening on %s:%d\n", bind_address.c_str(), port);

    // 프로세스 고유값 → 재시작해도 새 boot id(=새 runtime_epoch)가 나온다.
    // 클라이언트를 여러 번 받아도 **같은 boot id**를 쓴다(재연결이 새 부트가
    // 아니라는 사실이 중요하다).
    const std::string process_unique =
        std::to_string(static_cast<long long>(::getpid())) + "-" +
        std::to_string(MonotonicNowNs());

    // --max-seconds bounds the WHOLE run (accept window included), not one
    // peer session: the soak runner and the host checks `wait` on this
    // process, so a run that never exits would hang the harness — and a
    // plain kill() cannot reach a server parked in accept().
    const auto run_deadline =
        std::chrono::steady_clock::now() + std::chrono::seconds(max_seconds);

    for (;;) {
    int client = -1;
    // Interruptible accept: poll() in short slices so SIGTERM/SIGINT or the
    // run deadline ends the process instead of parking forever in accept()
    // (measured: plain accept() ignored kill() between peers — two host
    // mop_server processes were left running after the loopback check).
    while (!g_stop_requested.load(std::memory_order_relaxed) &&
           std::chrono::steady_clock::now() < run_deadline) {
        ::pollfd pfd{};
        pfd.fd = listener;
        pfd.events = POLLIN;
        const int ready = ::poll(&pfd, 1, 250);
        if (ready > 0 && (pfd.revents & POLLIN) != 0) {
            client = ::accept(listener, nullptr, nullptr);
            if (client < 0 &&
                (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                continue;  // peer vanished (or signal) between poll and accept
            }
            break;
        }
    }
    if (client < 0) {
        if (!g_stop_requested.load(std::memory_order_relaxed)) {
            std::fprintf(stderr, "mop_server: run deadline reached with no peer\n");
        } else {
            std::fprintf(stderr, "mop_server: stopped before a peer connected\n");
        }
        break;
    }
    if (g_stop_requested.load(std::memory_order_relaxed)) {
        std::fprintf(stderr, "mop_server: stopped before serving the peer\n");
        ::close(client);
        break;
    }
    std::fprintf(stderr, "mop_server: peer connected\n");

    // One host edge identity for the whole run: the endpoint takes the
    // explicit --boot-id verbatim (4th ctor arg); replay frames and autonomy
    // evidence cite the endpoint's own id back, so transcript, sealed
    // revision and manifest agree. Empty keeps the historical derivation
    // (host-only labels; never a real-run uuid).
    MopEdgeEndpoint endpoint(flight_id, MonotonicNowNs, 0, boot_id,
                             process_unique);
    const std::string edge_boot_id = endpoint.edge_boot_id();
    // Store labels keep the historical default ("host-boot") unless the flag
    // is given — then they cite the endpoint's own id, and all three agree.
    const std::string store_boot_id =
        boot_id.empty() ? "host-boot" : edge_boot_id;
    if (!boot_id.empty()) {
        std::fprintf(stderr, "mop_server: host edge_boot_id %s\n",
                     edge_boot_id.c_str());
    }
    TcpByteTransport transport(client);
    MopLink link(&transport, &endpoint);

    // --- Real-Edge mode -----------------------------------------------------
    // The SAME SessionRunner the device app runs, over a replay manifest.
    // Nothing here is a mock of the Edge: the runner, the evidence path and
    // the source-evidence capture are the production code.
    // Lives as long as the frame source that points at it.
    ksi::safety::ClockRegistry clocks;
    // The TensorRT runtime is captured BY REFERENCE by the inference lambda,
    // so it must outlive the detector that calls it. A block-scoped runtime
    // left the lambda pointing at a dead frame (AddressSanitizer on the
    // Manifold: use-after-scope when the first real inference ran).
    std::unique_ptr<ksi::safety::TensorRtRuntime> runtime;
    std::unique_ptr<ksi::safety::ReplayFrameSource> frames;
    std::unique_ptr<ksi::safety::SessionRunner> runner;
    std::unique_ptr<ksi::safety::DetectorPort> detector;
    std::unique_ptr<ksi::safety::DfineDetectorPort> dfine_port;
    std::unique_ptr<ksi::safety::SourceEvidenceRecorder> recorder;
    std::unique_ptr<ksi::safety::AutonomousProcessor> autonomy;
    // Live-identity label ("flight|boot") the processor was built under;
    // adoption moves recreate it so asset ids never merge lineages.
    std::string autonomy_identity;
    // Opt-in flag (parsed with the other autonomy options; the processor
    // itself is built in the loop under the live endpoint identity).
    bool want_autonomy = false;
    MopTickEvidence tick_evidence;
    std::string last_context_key;
    std::size_t next_frame = 0;
    if (!replay_manifest.empty()) {
        if (model_digest.empty()) {
            // A synthetic detector has no model: the digest identifies the
            // SYNTHETIC detector, not a trained model. It is never presented
            // as a detection-performance claim.
            model_digest = ksi::safety::Sha256Hex(
                std::string("stub-detector-v1"));
            std::fprintf(stderr,
                         "[SIMULATED] stub detector: model_digest identifies "
                         "the synthetic detector, not a trained model\n");
        }
        // The clock registry MUST outlive the frame source: the source keeps
        // a pointer to it. Declaring it inside this block left the frame
        // source pointing at a dead stack frame (caught by AddressSanitizer
        // on the Manifold: "stack-use-after-scope" in ClockRegistry::Make).
        frames = std::make_unique<ksi::safety::ReplayFrameSource>(
            replay_manifest, flight_id, store_boot_id, "host_mono",
            &clocks);
        ksi::safety::ApplyManifestVerifications(frames->manifest(), &clocks);
        if (!frames->ok()) {
            std::fprintf(stderr, "manifest error: %s\n", frames->error().c_str());
            return 1;
        }
        if (!engine_path.empty()) {
            runtime = std::make_unique<ksi::safety::TensorRtRuntime>(engine_path);
            if (!runtime->ok()) {
                std::fprintf(stderr,
                             "engine load failed (no detections, no stub): %s\n",
                             engine_path.c_str());
                return 1;
            }
            ksi::safety::DfineConfig dfine_config;
            dfine_config.score_threshold =
                static_cast<float>(score_threshold);
            dfine_port = std::make_unique<ksi::safety::DfineDetectorPort>(
                dfine_config,
                [&runtime](const float* input, std::vector<float>* logits,
                           std::vector<float>* boxes) {
                    return runtime->Run(input, logits, boxes);
                });
            detector = std::move(dfine_port);
            std::fprintf(stderr, "mop_server: real TensorRT engine: %s\n",
                         engine_path.c_str());
        }
        if (stub_detector) {
            class StubDetector : public ksi::safety::DetectorPort {
             public:
              ksi::safety::DetectionBatch Detect(
                  const ksi::safety::FramePacket& frame) override {
                ksi::safety::DetectionBatch batch;
                batch.status = ksi::safety::DetectionStatus::kOk;
                ksi::safety::Detection detection;
                detection.class_id = 0;
                detection.score = 0.99F;
                // Detection/TrackBox coordinates are SOURCE PIXELS, not
                // normalized fractions (same contract as DecodeDfine).
                detection.x_min = 0.25F * frame.metadata.width;
                detection.y_min = 0.25F * frame.metadata.height;
                detection.x_max = 0.75F * frame.metadata.width;
                detection.y_max = 0.75F * frame.metadata.height;
                batch.detections.push_back(detection);
                return batch;
              }
            };
            detector = std::make_unique<StubDetector>();
        }
        ksi::safety::RunnerConfig runner_config;
        runner_config.detect_every_n_frames = 1;
        ksi::safety::SessionConfig session_config;
        ksi::safety::ModelProfile profile;
        profile.model_id = model_id;
        profile.model_digest = model_digest;
        profile.backend = !engine_path.empty() ? "tensorrt"
                         : (stub_detector ? "stub" : "unknown");
        ksi::safety::ReplayPolicy policy;
        if (!policy_path.empty()) {
            std::string policy_error;
            if (!ksi::safety::LoadReplayPolicy(policy_path, &policy,
                                               &policy_error)) {
                std::fprintf(stderr, "policy error: %s\n", policy_error.c_str());
                return 1;
            }
        }
        runner = std::make_unique<ksi::safety::SessionRunner>(
            runner_config, session_config, policy.proximity_rules,
            policy.zone_rules, policy.zones, profile);
        if (!evidence_dir.empty()) {
            recorder = std::make_unique<ksi::safety::SourceEvidenceRecorder>(
                evidence_dir, 0);
            if (!recorder->ok()) {
                std::fprintf(stderr, "evidence store failed to open: %s\n",
                             recorder->last_error().c_str());
                return 1;
            }
        }
        // Unit A passive autonomy (opt-in only; default off): explicit
        // APPLY/START for the host demo, then the SAME shared wiring.
        // The processor itself is (re)created in the loop below under the
        // LIVE endpoint identity.
        if (!replay_manifest.empty() && !autonomy_evidence_dir.empty()) {
            if (!runner
                     ->ApplyRuntimeConfig(policy.proximity_rules,
                                          policy.zone_rules, policy.zones,
                                          "mop-host-policy", {})
                     .ok) {
                std::fprintf(stderr, "autonomy install refused\n");
                return 1;
            }
            runner->RequestStart();
            want_autonomy = true;
            std::fprintf(stderr,
                         "[SIMULATED] mop autonomy on: passive assessment "
                         "only (SHADOW, synthetic input)\n");
        }
        std::fprintf(stderr,
                     "mop_server: real-Edge mode (%zu frames, evidence %s)\n",
                     frames->frame_count(),
                     evidence_dir.empty() ? "off" : evidence_dir.c_str());
    }

    std::atomic<bool> running{true};
    std::thread reader([&]() {
        std::vector<std::uint8_t> buffer(4096);
        while (running.load(std::memory_order_relaxed)) {
            const ssize_t got = ::recv(client, buffer.data(), buffer.size(), 0);
            if (got <= 0) break;
            link.Feed(buffer.data(), static_cast<std::size_t>(got));
        }
        running.store(false, std::memory_order_relaxed);
    });

    auto next_frame_at = std::chrono::steady_clock::now();
    auto next_heartbeat_at = std::chrono::steady_clock::now();
    auto next_retry_at = std::chrono::steady_clock::now();
    while (running.load(std::memory_order_relaxed) &&
           !g_stop_requested.load(std::memory_order_relaxed) &&
           std::chrono::steady_clock::now() < run_deadline) {
        // Log the control messages we receive: the mission handshake
        // (PREPARE -> PREPARED -> APPLY -> APPLIED) is where a mismatched
        // bundle digest shows up, and without this line it is invisible.
        link.WithEndpoint([](ksi::safety::MopEdgeEndpoint* ep) {
            static std::size_t last_logged = 0;
            const std::size_t pending = ep->outboxSize();
            const std::size_t unacked = ep->unackedCount();
            if (pending != last_logged || unacked != 0) {
                last_logged = pending;
                std::fprintf(stderr,
                             "mop_server: outbound queue=%zu unacked=%zu\n",
                             pending, unacked);
            }
        });
        link.WithEndpoint([&endpoint, &runner](MopEdgeEndpoint* ep) {
            ep->PublishRuntimeFacts(BuildRunnerFacts(*ep, runner.get()));
        });
        // A live Edge answers with a heartbeat: the RC tells "연결" from
        // "하트비트 수신" and knows the link is alive without sending traffic.
        if (std::chrono::steady_clock::now() >= next_heartbeat_at) {
            next_heartbeat_at = std::chrono::steady_clock::now() +
                                std::chrono::milliseconds(1000);
            link.WithEndpoint([](MopEdgeEndpoint* ep) { ep->EmitHeartbeat(1.0); });
        }

        // --- one frame of real-Edge work ---------------------------------
        if (runner != nullptr && next_frame < frames->frame_count() &&
            std::chrono::steady_clock::now() >= next_frame_at) {
            // The Edge does not analyse before the mission is applied: the
            // frames WAIT for the bundle instead of being consumed early
            // (an observation without mission provenance is not evidence).
            ksi::safety::FramePacket packet;
            ksi::safety::FrameContext ctx;
            if (frames->ReadFrame(next_frame, &packet, &ctx)) {
                // The evidence context follows the INSTALLED bundle: an
                // observation without mission provenance is not evidence.
                std::string ctx_flight;
                std::string ctx_boot;
                ksi::safety::JsonValue bundle_copy;
                bool has_bundle = false;
                link.WithEndpoint([&](MopEdgeEndpoint* ep) {
                    ctx_flight = ep->flight_id();
                    ctx_boot = ep->edge_boot_id();
                    const ksi::safety::JsonValue* bundle = ep->appliedBundle();
                    if (bundle != nullptr) {
                        bundle_copy = *bundle;
                        has_bundle = true;
                    }
                });
                if (has_bundle) {
                    const ksi::safety::JsonValue* id = bundle_copy.Find("bundle_id");
                    const std::string bundle_id =
                        id != nullptr && id->IsString() ? id->strValue()
                                                        : std::string();
                    const std::string key = bundle_id + "|" + ctx_flight + "|" + ctx_boot;
                    if (key != last_context_key) {
                        ksi::safety::MopEvidenceContext context;
                        std::string error;
                        if (ksi::safety::EvidenceContextFromBundle(
                                bundle_copy, &context, &error)) {
                            context.flight_id = ctx_flight;
                            context.edge_boot_id = ctx_boot;
                            tick_evidence.SetContext(std::move(context));
                            last_context_key = key;
                            std::fprintf(stderr,
                                         "mop_server: evidence context set "
                                         "(bundle %s)\n",
                                         bundle_id.c_str());
                        }
                    }
                }
                // Not ready yet: KEEP the frame for the next tick (an
                // observation without mission provenance is not evidence, so
                // the frame must not be consumed). NOTE: this must NOT
                // `continue` the poll loop — that would skip the flush
                // below and the outbox would grow without ever being sent
                // (the RC would see no heartbeat and no status).
                const bool mission_ready =
                    !tick_evidence.context().mission_revision_id.empty();
                if (!mission_ready) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(20));
                } else {
                    // Unit A processor under the LIVE endpoint identity:
                    // adoption may move (flight|boot) after the peer
                    // connects, and asset ids must never merge lineages
                    // across identities (same rule as the device path).
                    // Machine assessments cite the mission actually
                    // installed (never a requested-but-uninstalled one).
                    const std::string live_identity =
                        ctx_flight + "|" + ctx_boot;
                    if (want_autonomy &&
                        (autonomy == nullptr ||
                         autonomy_identity != live_identity)) {
                        if (autonomy != nullptr) {
                            autonomy->RequestStop(MonotonicNowNs());
                            std::string old_revision, old_manifest,
                                old_error;
                            if (autonomy->Seal(&old_revision, &old_manifest,
                                               &old_error)) {
                                std::fprintf(
                                    stderr,
                                    "mop_server: autonomy revision sealed "
                                    "on identity move: %s (%zu)\n",
                                    old_revision.c_str(),
                                    autonomy->artifacts().size());
                            }
                            std::fprintf(stderr,
                                         "mop_server: autonomy identity moved "
                                         "(%s): processor recreated\n",
                                         live_identity.c_str());
                        }
                        ksi::safety::AutonomousConfig autonomy_config;
                        autonomy_config.enabled = true;
                        autonomy_config.source_attention = source_attention;
                        autonomy_config.ring.max_frames = 64;
                        autonomy_config.ring.max_bytes = 64ULL * 1024 * 1024;
                        autonomy_config.ring.pre_frames =
                            static_cast<std::size_t>(autonomy_pre_frames);
                        autonomy_config.ring.post_frames =
                            static_cast<std::size_t>(autonomy_post_frames);
                        autonomy_config.ring.window_deadline_ns =
                            autonomy_window_deadline_ms * 1000000LL;
                        autonomy_config.window_min_interval_ns =
                            autonomy_window_min_interval_ms * 1000000LL;
                        autonomy_config.store_capacity_bytes =
                            static_cast<std::uint64_t>(autonomy_capacity_bytes);
                        autonomy_config.evidence_dir = autonomy_evidence_dir;
                        autonomy_config.flight_id = ctx_flight;
                        autonomy_config.edge_boot_id = ctx_boot;
                        autonomy_config.mission_revision_id =
                            tick_evidence.context().mission_revision_id;
                        autonomy_config.policy_revision_id =
                            tick_evidence.context().policy_revision_id;
                        autonomy_config.effective_config_hash =
                            "mop-host-policy";
                        autonomy_config.config_epoch = runner->config_epoch();
                        autonomy_config.simulated_input = true;
                        std::string autonomy_error;
                        if (!ksi::safety::IsValidAutonomousConfig(
                                autonomy_config, &autonomy_error)) {
                            std::fprintf(stderr,
                                         "mop_server: bad autonomy config: "
                                         "%s (autonomy off)\n",
                                         autonomy_error.c_str());
                            want_autonomy = false;
                        } else {
                            autonomy = std::make_unique<
                                ksi::safety::AutonomousProcessor>(
                                autonomy_config);
                            if (!autonomy->valid()) {
                                std::fprintf(stderr,
                                             "mop_server: autonomy failed: %s "
                                             "(autonomy off)\n",
                                             autonomy->error().c_str());
                                autonomy.reset();
                                want_autonomy = false;
                            } else {
                                autonomy_identity = live_identity;
                            }
                        }
                    }
                    if (autonomy != nullptr) {
                        autonomy->SetMissionPolicyRevisions(
                            tick_evidence.context().mission_revision_id,
                            tick_evidence.context().policy_revision_id);
                    }
                    ksi::safety::TickRecord record;
                    ksi::safety::SharedTickResult shared;
                    bool has_shared = false;
                    if (autonomy != nullptr && !packet.rgb.empty()) {
                        shared = ksi::safety::ProcessSharedTick(
                            *runner, *autonomy, packet, ctx, detector.get(),
                            packet.rgb.data(), packet.rgb.size());
                        record = shared.record;
                        has_shared = true;
                    } else {
                        record = runner->ProcessFrame(packet, ctx,
                                                      detector.get());
                    }
                    if (recorder != nullptr && !packet.rgb.empty()) {
                        ksi::safety::SourceFrame source;
                        source.flight_id = ctx_flight;
                        source.edge_boot_id = ctx_boot;
                        source.frame_id = record.frame_id;
                        source.received_monotonic_ns = record.receive_mono_ns;
                        source.rgb = packet.rgb.data();
                        source.rgb_size = packet.rgb.size();
                        recorder->Capture(record, source);
                    }
                    tick_evidence.EmitTick(&link, record);
                    // Unit A closure: the sealed assessment JSON rides the
                    // existing artifact plane (ANNOUNCE + CHUNKs, no new MOP
                    // type), under the same live identity as the envelopes
                    // above. Withheld geometry still ships its fact; the
                    // sealed revision stays the Core-receipt path for bytes.
                    if (has_shared && shared.autonomy_observed &&
                        shared.assessment_artifact.write_status ==
                            ksi::safety::EvidenceStatus::kOk) {
                        ksi::safety::AssessmentEmitGate gate;
                        gate.mission_installed = true;  // branch requires it
                        link.WithEndpoint([&](MopEdgeEndpoint* ep) {
                            gate.flight_confirmed = ep->flightIdConfirmed();
                            gate.spool_writable = ep->CheckSpoolWritable();
                        });
                        if (ksi::safety::AssessmentEmitAllowed(gate)) {
                            ksi::safety::EmitAssessmentArtifact(
                                &link, shared.assessment_artifact.asset_id,
                                shared.assessment.flight_id,
                                shared.assessment.edge_boot_id,
                                shared.assessment.ToJson());
                        } else {
                            // Gated/dropped: counted here as not-deliverable
                            // (never folded, never claimed sent); the sealed
                            // store copy is the retention path.
                            std::fprintf(stderr,
                                         "mop_server: assessment %s gated "
                                         "(not delivered, sealed copy kept)\n",
                                         shared.assessment_artifact.asset_id
                                             .c_str());
                        }
                    }
                    ++next_frame;
                }
            }
            next_frame_at = std::chrono::steady_clock::now() +
                            std::chrono::milliseconds(frame_interval_ms);
        }
        const std::size_t flushed = link.Flush();
        if (flushed > 0) {
            std::fprintf(stderr, "mop_server: flushed %zu envelope(s)\n", flushed);
        }
        // Retry at a sane cadence: re-queueing every unacked envelope on
        // every poll (100ms) buries the link in duplicates faster than the
        // peer's ACKs arrive. The product service retries on its own timer.
        if (std::chrono::steady_clock::now() >= next_retry_at) {
            next_retry_at = std::chrono::steady_clock::now() +
                            std::chrono::seconds(2);
            link.RetryOutstanding(3);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    running.store(false, std::memory_order_relaxed);
    // Seal BEFORE joining the reader. The reader may sit in recv() until the
    // peer disconnects, so joining first would hang the exit path and the
    // captured evidence would stay UNSEALED — bytes on disk that no
    // collector can export (measured on the Manifold 3).
    if (recorder != nullptr) {
        std::string revision_id;
        std::string manifest;
        std::string error;
        std::string ctx_flight;
        std::string ctx_boot;
        link.WithEndpoint([&](MopEdgeEndpoint* ep) {
            ctx_flight = ep->flight_id();
            ctx_boot = ep->edge_boot_id();
        });
        if (recorder->Seal(ctx_flight, ctx_boot, MonotonicNowNs(), &revision_id,
                           &manifest, &error)) {
            std::fprintf(stderr, "mop_server: evidence revision sealed: %s (%zu)%s\n",
                         revision_id.c_str(), recorder->captured_count(),
                         g_stop_requested.load(std::memory_order_relaxed)
                             ? " (stop requested)" : "");
        } else if (error.rfind("no source frames", 0) != 0) {
            std::fprintf(stderr, "mop_server: evidence revision NOT sealed: %s\n",
                         error.c_str());
        }
    }
    if (autonomy != nullptr) {
        autonomy->RequestStop(MonotonicNowNs());
        const ksi::safety::AutonomousCoverage& coverage =
            autonomy->coverage();
        std::fprintf(stderr,
                     "mop_server: autonomy ticks=%llu assessed=%llu "
                     "undetermined=%llu windows=%llu complete=%llu "
                     "partial=%llu refused=%llu\n",
                     static_cast<unsigned long long>(
                         coverage.processed_ticks),
                     static_cast<unsigned long long>(
                         coverage.assessments_assessed),
                     static_cast<unsigned long long>(
                         coverage.assessments_undetermined),
                     static_cast<unsigned long long>(
                         coverage.windows_opened),
                     static_cast<unsigned long long>(
                         coverage.windows_complete),
                     static_cast<unsigned long long>(
                         coverage.windows_partial),
                     static_cast<unsigned long long>(
                         coverage.writes_refused));
        if (coverage.evidence_saturated) {
            std::fprintf(stderr,
                         "mop_server: autonomy evidence SATURATED (%s): "
                         "observation continued, %llu artifacts refused\n",
                         coverage.saturation_reason.c_str(),
                         static_cast<unsigned long long>(
                             coverage.evidence_refusals));
        }
        if (coverage.windows_merged > 0) {
            std::fprintf(stderr,
                         "mop_server: autonomy windows merged by interval "
                         "bound: %llu\n",
                         static_cast<unsigned long long>(
                             coverage.windows_merged));
        }
        std::string revision_id;
        std::string manifest;
        std::string error;
        if (autonomy->Seal(&revision_id, &manifest, &error)) {
            std::fprintf(stderr,
                         "mop_server: autonomy revision sealed: %s (%zu)\n",
                         revision_id.c_str(), autonomy->artifacts().size());
        } else {
            std::fprintf(stderr,
                         "mop_server: autonomy revision NOT sealed: %s\n",
                         error.c_str());
        }
    }
    // Wake the reader (it may be blocked in recv()): a half-close makes
    // recv() return so the join below cannot hang.
    ::shutdown(client, SHUT_RDWR);
    reader.join();
    std::fprintf(stderr,
                 "mop_server: done (sent=%llu received=%llu)\n",
                 static_cast<unsigned long long>(link.envelopes_sent()),
                 static_cast<unsigned long long>(link.messages_received()));
    // The peer is gone: keep the run alive for the next RC (a reconnect is
    // not a new boot and must not need a restart) — but only until the run
    // deadline or a stop signal, whichever comes first. A host run that
    // parks forever would hang the harness `wait` and leak a process.
    if (g_stop_requested.load(std::memory_order_relaxed) ||
        std::chrono::steady_clock::now() >= run_deadline) {
        std::fprintf(stderr, "mop_server: run over, exiting cleanly\n");
        break;
    }
    std::fprintf(stderr, "mop_server: peer disconnected, waiting for the next peer\n");
    }
    ::close(listener);
    std::fprintf(stderr, "mop_server: exit 0\n");
    return 0;
}
