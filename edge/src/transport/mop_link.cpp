#include "ksi_safety/transport/mop_link.hpp"

#include <chrono>
#include <string>
#include <vector>

#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

namespace {

// Send priority per channel. Lower runs first.
//
// WHY THIS ORDER: the RC's commands and our answers to them (CONTROL_RELIABLE)
// are the only messages a human is waiting on; the heartbeat keeps the link's
// freshness fact alive; observations/events are the evidence itself; artifact
// bytes are bulk and may wait. Channels carry independent seq spaces, so
// picking across classes never reorders one channel's own stream.
int PriorityOf(const JsonValue& envelope) {
    const JsonValue* channel = envelope.Find("channel");
    const std::string name =
        (channel != nullptr && channel->IsString()) ? channel->strValue()
                                                    : std::string();
    if (name == "CONTROL_RELIABLE") return 0;
    if (name == "HEARTBEAT") return 1;
    if (name == "EVIDENCE_TRANSFER") return 3;
    if (name == "EVENT_RELIABLE") return 2;
    return 2;  // unknown channel: never starved, never first
}

}  // namespace

std::size_t MopLink::Flush(std::size_t max_envelopes, std::size_t max_bytes,
                           bool priority_lane) {
    // One writer at a time (the byte stream must stay ordered), but the
    // LINK lock is NOT held while the transport blocks. A stalled write can
    // therefore not freeze the reader, the status query or the stop command.
    std::lock_guard<std::mutex> writer(writer_mutex_);
    std::size_t written = 0;
    std::size_t written_bytes = 0;
    while (written < max_envelopes) {
        // 1) Dequeue under the link lock (short, no I/O).
        JsonValue envelope;
        {
            std::lock_guard<std::mutex> guard(mutex_);
            if (endpoint_->outbox().empty()) break;
            // Pick the highest-priority waiting envelope; ties keep queue
            // order. Without the lane the queue stays strictly FIFO (pinned
            // wire bytes).
            std::size_t index = 0;
            if (priority_lane) {
                int best = PriorityOf(endpoint_->outbox().front());
                for (std::size_t i = 1; i < endpoint_->outbox().size(); ++i) {
                    const int priority = PriorityOf(endpoint_->outbox()[i]);
                    if (priority < best) {
                        best = priority;
                        index = i;
                    }
                }
            }
            envelope = std::move(endpoint_->outbox()[index]);
            endpoint_->outbox().erase(endpoint_->outbox().begin() +
                                      static_cast<std::ptrdiff_t>(index));
        }
        // 2) Serialise + frame + write WITHOUT the link lock.
        const std::string payload = WriteCanonicalJson(envelope);
        const std::vector<std::string> frames = MopFrameCodec::Encode(payload);
        std::size_t frame_bytes = 0;
        for (const auto& frame : frames) frame_bytes += frame.size();
        if (written > 0 && written_bytes + frame_bytes > max_bytes) {
            // Budget exhausted: the envelope goes back to the head, so the
            // order survives and the next flush sends it.
            std::lock_guard<std::mutex> guard(mutex_);
            endpoint_->outbox().push_front(std::move(envelope));
            break;
        }
        bool ok = true;
        bool stalled = false;
        std::int64_t elapsed_ms = 0;
        const auto write_started = std::chrono::steady_clock::now();
        for (const auto& frame : frames) {
            if (!transport_->WriteAll(
                    reinterpret_cast<const std::uint8_t*>(frame.data()),
                    frame.size())) {
                ok = false;
                break;
            }
        }
        if (ok) {
            // A write that took too long means the peer stopped reading.
            // Latch it so the runtime can degrade the run instead of
            // believing the link is healthy while nothing moves.
            elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - write_started)
                             .count();
            if (write_stall_budget_ms_ > 0 && elapsed_ms > write_stall_budget_ms_) {
                stalled = true;
            }
        }
        if (!ok || stalled) {
            // Failed or stalled write: the envelope goes back to the head,
            // unchanged, so the SAME msg_id is retried (the endpoint's retry
            // rules and the RC's dedup by msg_id both depend on that).
            std::lock_guard<std::mutex> guard(mutex_);
            if (stalled) {
                write_stalled_ = true;
                ++write_stalls_;
                write_stall_reason_ = "transport write stalled (" +
                                     std::to_string(elapsed_ms) +
                                     "ms > budget " +
                                     std::to_string(write_stall_budget_ms_) +
                                     "ms): the peer is not reading";
            }
            endpoint_->outbox().push_front(std::move(envelope));
            break;  // caller tears down/reconnects
        }
        {
            std::lock_guard<std::mutex> guard(mutex_);
            ++envelopes_sent_;
        }
        ++written;
        written_bytes += frame_bytes;
    }
    return written;
}

bool MopLink::writeStalled() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return write_stalled_;
}

const std::string& MopLink::writeStallReason() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return write_stall_reason_;
}

std::uint64_t MopLink::writeStalls() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return write_stalls_;
}

void MopLink::clearWriteStall() {
    std::lock_guard<std::mutex> guard(mutex_);
    write_stalled_ = false;
    write_stall_reason_.clear();
}

std::size_t MopLink::backlog() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return endpoint_->outbox().size();
}

void MopLink::Feed(const std::uint8_t* data, std::size_t len) {
    std::lock_guard<std::mutex> guard(mutex_);
    const std::uint64_t framing_before = reassembler_.errors();
    std::vector<std::string> messages;
    reassembler_.Feed(data, len, &messages);
    framing_errors_ += reassembler_.errors() - framing_before;
    for (const auto& message : messages) {
        JsonValue envelope;
        std::string error;
        if (!ParseJson(message, &envelope, &error)) {
            ++receive_errors_;
            continue;
        }
        JsonValue payload;
        if (!VerifyMopEnvelope(envelope, &payload, &error)) {
            ++receive_errors_;  // digest/version rejects get no ACK (harness parity)
            continue;
        }
        if (endpoint_->Receive(envelope) == "error") {
            ++receive_errors_;
        }
        ++messages_received_;
    }
}

void MopLink::RetryOutstanding(int max_attempts) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->MarkUnrecoverable(max_attempts);
    endpoint_->RequeueUnacked();
}

void MopLink::ResetReassembly() {
    std::lock_guard<std::mutex> guard(mutex_);
    reassembler_.Reset();
}

std::uint64_t MopLink::envelopes_sent() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return envelopes_sent_;
}

std::uint64_t MopLink::messages_received() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return messages_received_;
}

std::uint64_t MopLink::receive_errors() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return receive_errors_;
}

std::uint64_t MopLink::framing_errors() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return framing_errors_;
}

void MopLink::EmitObservation(JsonValue observation) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->EmitObservation(std::move(observation));
}

void MopLink::EmitEvent(JsonValue event) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->EmitEvent(std::move(event));
}

void MopLink::EmitSummary(JsonValue summary) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->EmitSummary(std::move(summary));
}

void MopLink::EmitHeartbeat(double fps) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->EmitHeartbeat(fps);
}

void MopLink::EmitArtifact(const std::string& artifact_id,
                           const std::string& source_kind,
                           const std::string& media_type,
                           std::int64_t segment_index, bool required,
                           const std::string& bytes) {
    std::lock_guard<std::mutex> guard(mutex_);
    endpoint_->EmitArtifact(artifact_id, source_kind, media_type,
                            segment_index, required, bytes);
}

}  // namespace ksi::safety
