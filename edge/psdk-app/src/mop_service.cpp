#include "mop_service.hpp"

#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <thread>
#include <utility>

extern "C" {
#include <dji_mop_channel.h>
#include <dji_typedef.h>
}

namespace {

constexpr std::size_t kRecvBufferBytes = 64U * 1024U;
constexpr std::int64_t kNsPerMs = 1000000LL;

std::int64_t MonotonicNowNs() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return 0;
    }
    return static_cast<std::int64_t>(value.tv_sec) * 1000000000LL +
           static_cast<std::int64_t>(value.tv_nsec);
}

bool SdkOk(T_DjiReturnCode code) {
    return code == DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

void SleepMs(std::int64_t ms) {
    if (ms <= 0) ms = 50;
    if (ms > 1000) ms = 1000;
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

}  // namespace

MopService::MopService(MopServiceConfig config)
    : config_(std::move(config)),
      endpoint_(
          config_.flight_id,
          [] { return MonotonicNowNs(); }) {
    link_ = std::make_unique<ksi::safety::MopLink>(this, &endpoint_);
}

MopService::~MopService() { Stop(); }

bool MopService::Start() {
    if (running_.load()) {
        return true;
    }
    if (!SdkOk(DjiMopChannel_Init())) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Init failed\n");
        return false;
    }
    T_DjiMopChannelHandle listen = nullptr;
    if (!SdkOk(DjiMopChannel_Create(&listen, DJI_MOP_CHANNEL_TRANS_RELIABLE)) ||
        listen == nullptr) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Create failed\n");
        return false;
    }
    listen_handle_ = listen;
    if (!SdkOk(DjiMopChannel_Bind(listen, config_.channel_id))) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Bind(%u) failed\n",
                     static_cast<unsigned>(config_.channel_id));
        CloseHandles();
        return false;
    }
    running_.store(true);
    reader_ = std::thread([this] { ReaderLoop(); });
    timer_ = std::thread([this] { TimerLoop(); });
    return true;
}

void MopService::Stop() {
    if (!running_.exchange(false)) {
        CloseHandles();
        return;
    }
    if (reader_.joinable()) reader_.join();
    if (timer_.joinable()) timer_.join();
    connected_.store(false);
    CloseHandles();
}

void MopService::CloseHandles() {
    if (conn_handle_ != nullptr) {
        (void)DjiMopChannel_Close(
            static_cast<T_DjiMopChannelHandle>(conn_handle_));
        (void)DjiMopChannel_Destroy(
            static_cast<T_DjiMopChannelHandle>(conn_handle_));
        conn_handle_ = nullptr;
    }
    if (listen_handle_ != nullptr) {
        (void)DjiMopChannel_Close(
            static_cast<T_DjiMopChannelHandle>(listen_handle_));
        (void)DjiMopChannel_Destroy(
            static_cast<T_DjiMopChannelHandle>(listen_handle_));
        listen_handle_ = nullptr;
    }
}

bool MopService::WriteAll(const std::uint8_t* data, std::size_t len) {
    std::lock_guard<std::mutex> guard(write_mutex_);
    // connected_ (seq_cst) is the release/acquire edge for conn_handle_:
    // check it FIRST, then read the handle written by the reader thread.
    if (!connected_.load()) {
        return false;
    }
    auto* handle = static_cast<T_DjiMopChannelHandle>(conn_handle_);
    if (handle == nullptr) {
        return false;
    }
    std::size_t offset = 0;
    while (offset < len) {
        const auto remaining = static_cast<std::uint32_t>(
            len - offset > 0xFFFFFFFFU ? 0xFFFFFFFFU : len - offset);
        std::uint32_t sent = 0;
        const T_DjiReturnCode rc = DjiMopChannel_SendData(
            handle, const_cast<std::uint8_t*>(data + offset), remaining, &sent);
        if (!SdkOk(rc) || sent == 0) {
            return false;  // partial write = broken stream (link policy)
        }
        offset += sent;
        bytes_out_.fetch_add(sent);
    }
    return true;
}

void MopService::ReaderLoop() {
    // Accept blocks until the RC (MSDK pipeline) connects to our channel id.
    // TODO(device): the PSDK API has no accept-timeout/cancel; verify on the
    // RC what happens when the app stops before the RC ever connects
    // (Stop() joins this thread, so a never-connected channel can delay
    // shutdown until the platform tears the process down).
    T_DjiMopChannelHandle accepted = nullptr;
    if (!SdkOk(DjiMopChannel_Accept(
            static_cast<T_DjiMopChannelHandle>(listen_handle_), &accepted)) ||
        accepted == nullptr) {
        std::fprintf(stderr, "Mop: Accept failed\n");
        return;
    }
    conn_handle_ = accepted;
    connected_.store(true);
    std::fprintf(stderr, "Mop: channel %u connected\n",
                 static_cast<unsigned>(config_.channel_id));

    auto* handle = static_cast<T_DjiMopChannelHandle>(conn_handle_);
    std::uint8_t buffer[kRecvBufferBytes];
    while (running_.load()) {
        std::uint32_t received = 0;
        const T_DjiReturnCode rc = DjiMopChannel_RecvData(
            handle, buffer, static_cast<std::uint32_t>(sizeof(buffer)),
            &received);
        if (SdkOk(rc) && received > 0) {
            bytes_in_.fetch_add(received);
            link_->Feed(buffer, received);
        } else {
            SleepMs(5);
        }
    }
    connected_.store(false);
    link_->ResetReassembly();
}

void MopService::TimerLoop() {
    std::int64_t last_heartbeat_ms = 0;
    while (running_.load()) {
        SleepMs(config_.flush_period_ms);
        if (!connected_.load()) {
            continue;
        }
        const std::int64_t now_ms = MonotonicNowNs() / kNsPerMs;
        if (now_ms - last_heartbeat_ms >= config_.heartbeat_period_ms) {
            last_heartbeat_ms = now_ms;
            link_->EmitHeartbeat(config_.heartbeat_fps);
        }
        link_->RetryOutstanding(config_.retry_max_attempts);
        link_->Flush();
    }
}
