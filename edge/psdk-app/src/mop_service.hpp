// MopService (A9-2, DEVICE GATE): the PSDK-side MOP channel adapter.
//
// All message logic (framing, envelope verification, missions, evidence
// emission, retry/gap bookkeeping) lives in the host-tested transport core
// (edge/src/transport + edge/tests/test_mop_*.cpp). This file only moves
// bytes over the real DJI MOP channel:
//
//   DjiMopChannel_Init -> Create(RELIABLE) -> Bind(channelId) -> Accept
//   reader thread : DjiMopChannel_RecvData -> MopLink::Feed
//   timer thread  : MopLink::Flush + RetryOutstanding + periodic HEARTBEAT
//
// The channel id must match the MSDK side (`PipelineManager.connectPipeline`
// id — the RC app uses 0, `MSDK_DEFAULT_PIPELINE_ID`).
//
// DEVICE GATE: this file is compiled only inside the psdk-app target (PSDK
// headers required) and its behavior is verified on the RC ground session;
// host verification covers the transport core it drives.
#ifndef KSI_PSDK_APP_MOP_SERVICE_HPP
#define KSI_PSDK_APP_MOP_SERVICE_HPP

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

struct MopServiceConfig {
    // Flight id carried in every envelope. EMPTY means "learn it from the
    // wire": the RC creates the per-flight UUID (MissionStore) and the Edge
    // adopts it from the first RC envelope before emitting anything.
    std::string flight_id = "";
    // Must match the MSDK pipeline id on the RC side.
    std::uint16_t channel_id = 0;
    double heartbeat_fps = 15.0;
    int retry_max_attempts = 3;
    std::int64_t heartbeat_period_ms = 5000;
    std::int64_t flush_period_ms = 200;
};

class MopService : public ksi::safety::MopByteTransport {
 public:
    explicit MopService(MopServiceConfig config);
    ~MopService() override;

    MopService(const MopService&) = delete;
    MopService& operator=(const MopService&) = delete;

    // Creates/binds the channel and starts the reader (which blocks in
    // Accept until the RC connects) + timer threads. Non-blocking.
    bool Start();
    void Stop();

    // MopByteTransport: writes one whole frame, looping over the SDK's
    // partial writes. False = transport broken (the link tears down and
    // resends after a fresh connection).
    bool WriteAll(const std::uint8_t* data, std::size_t len) override;

    // Emission surface for the app loop (only emit while connected()).
    ksi::safety::MopLink* link() { return link_.get(); }

    // The app path: one processed tick -> observation/event evidence (gated
    // on an accepted mission context — the RC must have applied a bundle).
    void EmitTick(const ksi::safety::TickRecord& record);
    // Shutdown path: FLIGHT_SUMMARY for the session (no-op without ticks).
    void EmitFinalSummary();

    ksi::safety::MopTickEvidence* evidence() { return evidence_.get(); }

    bool connected() const { return connected_.load(); }
    bool flightIdConfirmed() const;
    std::uint64_t skipped_evidence_ticks() const {
        return skipped_ticks_.load();
    }
    std::uint64_t bytes_in() const { return bytes_in_.load(); }
    std::uint64_t bytes_out() const { return bytes_out_.load(); }

 private:
    void ReaderLoop();
    void TimerLoop();
    void CloseHandles();
    // Pulls mission/policy provenance from the applied bundle into the
    // evidence context, and clears the context when the flight identity
    // changed without one (no stale-flight evidence). Caller MUST hold
    // [evidence_mutex_].
    void RefreshMissionContextLocked();

    MopServiceConfig config_;
    ksi::safety::MopEdgeEndpoint endpoint_;
    std::unique_ptr<ksi::safety::MopLink> link_;
    std::unique_ptr<ksi::safety::MopTickEvidence> evidence_;
    std::string last_context_key_;
    // Review fix (F1): the endpoint is ALSO touched directly (gates +
    // applied-bundle reads) outside MopLink's lock; this mutex is the
    // single-threaded contract's enforcement point for those paths and for
    // `evidence_`/`last_context_key_`. Lock order: evidence_mutex_ ->
    // MopLink's mutex -> write_mutex_ (never the reverse).
    mutable std::mutex evidence_mutex_;
    std::atomic<std::uint64_t> skipped_ticks_{0};
    std::thread reader_;
    std::thread timer_;
    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::atomic<std::uint64_t> bytes_in_{0};
    std::atomic<std::uint64_t> bytes_out_{0};
    std::mutex write_mutex_;
    void* listen_handle_ = nullptr;  // T_DjiMopChannelHandle
    void* conn_handle_ = nullptr;    // T_DjiMopChannelHandle
};

#endif  // KSI_PSDK_APP_MOP_SERVICE_HPP
