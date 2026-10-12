#include "ksi_safety/transport/mop_link.hpp"

#include <string>
#include <vector>

#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

std::size_t MopLink::Flush() {
    std::lock_guard<std::mutex> guard(mutex_);
    std::size_t written = 0;
    while (!endpoint_->outbox().empty()) {
        const std::string payload =
            WriteCanonicalJson(endpoint_->outbox().front());
        const std::vector<std::string> frames = MopFrameCodec::Encode(payload);
        bool ok = true;
        for (const auto& frame : frames) {
            if (!transport_->WriteAll(
                    reinterpret_cast<const std::uint8_t*>(frame.data()),
                    frame.size())) {
                ok = false;
                break;
            }
        }
        if (!ok) break;  // envelope stays queued; caller tears down/reconnects
        endpoint_->outbox().pop_front();
        ++envelopes_sent_;
        ++written;
    }
    return written;
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
