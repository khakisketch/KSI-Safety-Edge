// MOP link (A9-2): binds the byte transport (PSDK MOP channel on device /
// a host fake) to framing + envelope verification + the Edge endpoint.
// Thread-safe: the app emits from its processing loop while the reader
// thread feeds inbound bytes; every public method takes the internal mutex.
#ifndef KSI_SAFETY_TRANSPORT_MOP_LINK_HPP
#define KSI_SAFETY_TRANSPORT_MOP_LINK_HPP

#include <cstddef>
#include <cstdint>
#include <mutex>

#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_frame.hpp"

namespace ksi::safety {

class MopByteTransport {
 public:
    virtual ~MopByteTransport() = default;

    // Write ALL bytes or return false. A partial write must be reported as
    // failure: frames already delivered cannot be recalled — the caller
    // tears the link down and reconnects (both ends restart with a fresh
    // reassembler; the envelope layer resends the SAME msg_id).
    virtual bool WriteAll(const std::uint8_t* data, std::size_t len) = 0;
};

class MopLink {
 public:
    MopLink(MopByteTransport* transport, MopEdgeEndpoint* endpoint)
        : transport_(transport), endpoint_(endpoint) {}

    // Serialise + frame + write every queued outbound envelope. Returns the
    // number of envelopes fully written; stops at the first write failure
    // (the failing envelope stays queued).
    std::size_t Flush();

    // Inbound transport bytes: reassemble frames, verify envelopes, hand
    // complete messages to the endpoint (ACKs/responses are queued).
    void Feed(const std::uint8_t* data, std::size_t len);

    // Retry pass (call from the app timer): retire still-unacked DATA seqs
    // that already had a retry chance (heartbeat declarations), then requeue
    // the rest (same msg_id, attempts++).
    void RetryOutstanding(int max_attempts);

    // Transport broken / reconnect: discard partial frame state (no resync
    // marker exists). Boot rotation stays an explicit caller decision.
    void ResetReassembly();

    std::uint64_t envelopes_sent() const;
    std::uint64_t messages_received() const;
    std::uint64_t receive_errors() const;
    std::uint64_t framing_errors() const;

    // Emission pass-throughs (serialised with receive/flush).
    void EmitObservation(JsonValue observation);
    void EmitEvent(JsonValue event);
    void EmitSummary(JsonValue summary);
    void EmitHeartbeat(double fps = 15.0);
    void EmitArtifact(const std::string& artifact_id,
                      const std::string& source_kind,
                      const std::string& media_type,
                      std::int64_t segment_index, bool required,
                      const std::string& bytes);

 private:
    MopByteTransport* transport_;
    MopEdgeEndpoint* endpoint_;
    MopReassembler reassembler_;
    mutable std::mutex mutex_;
    std::uint64_t envelopes_sent_ = 0;
    std::uint64_t messages_received_ = 0;
    std::uint64_t receive_errors_ = 0;
    std::uint64_t framing_errors_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_LINK_HPP
