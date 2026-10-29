// MOP link (A9-2): binds the byte transport (PSDK MOP channel on device /
// a host fake) to framing + envelope verification + the Edge endpoint.
//
// Threading: the app emits from its processing loop while the reader thread
// feeds inbound bytes. The endpoint object itself is NOT thread-safe —
// EVERY cross-thread touch (including the service's snapshots, confirms
// and facts) goes through this link: either a MopLink method or
// WithEndpoint below, both of which hold the internal mutex. Lock order
// everywhere: service evidence_mutex_ -> MopLink -> channel leaf. The link
// never takes the evidence lock; leaf locks never take anything. No cycles.
#ifndef KSI_SAFETY_TRANSPORT_MOP_LINK_HPP
#define KSI_SAFETY_TRANSPORT_MOP_LINK_HPP

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <utility>

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

    // Serialise + frame + write queued outbound envelopes. Returns the
    // number of envelopes fully written; stops at the first write failure
    // (the failing envelope stays queued).
    //
    // BOUNDED BY DESIGN: one call writes at most `max_envelopes` envelopes
    // and at most `max_bytes` bytes, then releases the link mutex. A bulk
    // evidence backlog must never keep the reader, the status query or the
    // stop command waiting behind it (the whole point of a control lane).
    // The caller flushes again to drain the rest — the queue is durable in
    // the spool meanwhile.
    //
    // WRITER LOCK: only one writer at a time (the byte stream must not be
    // interleaved), but the LINK lock is released while the write blocks.
    // A slow or stalled transport therefore cannot hold up the reader, the
    // status query or the stop command — the reason this split exists.
    //
    // PRIORITY (opt-in): with `priority_lane` the next envelope is the
    // highest-priority one waiting (CONTROL_RELIABLE > HEARTBEAT >
    // EVENT_RELIABLE > EVIDENCE_TRANSFER), and the order inside a class is
    // preserved. Channels carry independent seq spaces, so crossing classes
    // never reorders one channel's own stream.
    //
    // DEFAULT IS FIFO ON PURPOSE: the pinned cross-language wire fixture
    // (`contracts/mop/edge-to-rc.mopv1.hex`) pins the byte order, so the
    // normal path keeps it byte-identical. The runtime turns the lane on
    // only when a backlog exists — exactly when a human is waiting behind
    // bulk evidence.
    std::size_t Flush(std::size_t max_envelopes = 32,
                      std::size_t max_bytes = 256U * 1024U,
                      bool priority_lane = false);

    // Envelopes queued but not yet written (backlog visibility: the
    // runtime reports it instead of claiming the link is idle).
    std::size_t backlog() const;

    // A write that took longer than the stall budget is a **transport
    // stall**: the peer stopped reading. The envelope is put back (the
    // same msg_id is retried), the stall is latched, and the caller can
    // degrade the run instead of sitting in a blocking write forever.
    //
    // WHY: a blocked write freezes the whole analysis loop. Measured on the
    // Manifold 3: a peer that stops reading stops the frame processing —
    // the link mutex is not held, but the run is.
    void set_write_stall_budget(std::int64_t budget_ms) {
        write_stall_budget_ms_ = budget_ms;
    }
    // True once a write exceeded the budget (latched until the link is
    // reset). The runtime reports it and pauses the run.
    bool writeStalled() const;
    const std::string& writeStallReason() const;
    // Number of write stalls observed (latched counter).
    std::uint64_t writeStalls() const;
    // Clears the stall latch (after the caller has torn the link down and
    // reconnected).
    void clearWriteStall();

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

    // Runs f(endpoint) with the link's serialization mutex held and returns
    // its result. The ONLY sanctioned path for direct endpoint access
    // outside MopLink's own methods (service snapshots, confirms, facts).
    // Never call link methods from inside f (non-recursive mutex). Callers
    // holding the service evidence lock keep the global order
    // evidence -> link.
    template <typename F>
    auto WithEndpoint(F&& f) -> decltype(f(std::declval<MopEdgeEndpoint*>())) {
        std::lock_guard<std::mutex> guard(mutex_);
        return f(endpoint_);
    }

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
    // Serialises writers (one at a time). Lock order: writer_mutex_ ->
    // mutex_. Nothing takes writer_mutex_ while holding mutex_, so there
    // is no cycle.
    std::mutex writer_mutex_;
    // A write slower than this is a transport stall (0 = no budget).
    std::int64_t write_stall_budget_ms_ = 5000;
    bool write_stalled_ = false;
    std::string write_stall_reason_;
    std::uint64_t write_stalls_ = 0;
    std::uint64_t envelopes_sent_ = 0;
    std::uint64_t messages_received_ = 0;
    std::uint64_t receive_errors_ = 0;
    std::uint64_t framing_errors_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_LINK_HPP
