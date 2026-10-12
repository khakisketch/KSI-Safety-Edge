#include "ksi_safety/transport/mop_endpoint.hpp"

#include <algorithm>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

namespace {

constexpr std::size_t kChunkSize = 512;  // harness `CHUNK_SIZE`

std::vector<std::string> CheckBundle(const JsonValue& bundle) {
    std::vector<std::string> reasons;
    if (!VerifyDigest(bundle, "bundle_sha256")) {
        reasons.emplace_back(
            "DIGEST_MISMATCH: bundle_sha256 does not match recomputed digest");
    }
    std::set<std::string> sites;
    auto collect = [&sites](const JsonValue* object) {
        if (object == nullptr || !object->IsObject()) return;
        const JsonValue* site = object->Find("site_id");
        if (site != nullptr && site->IsString()) sites.insert(site->strValue());
    };
    collect(&bundle);
    collect(bundle.Find("mission_snapshot"));
    collect(bundle.Find("risk_policy"));
    if (sites.size() > 1) reasons.emplace_back("BUNDLE_SITE_MISMATCH");
    return reasons;
}

}  // namespace

MopEdgeEndpoint::MopEdgeEndpoint(std::string flight_id, Clock clock,
                                 int boot_index, std::string boot_id)
    : flight_id_(std::move(flight_id)),
      clock_(std::move(clock)),
      boot_index_(boot_index) {
    edge_boot_id_ = !boot_id.empty()
                        ? std::move(boot_id)
                        : Uuid5(MopProtocol::Namespace(),
                                "boot|" + flight_id_ + "|" +
                                    std::to_string(boot_index_));
    // An explicit non-empty id is authoritative; an empty one means "learn
    // it from the wire" (the RC creates the per-flight UUID).
    flight_id_confirmed_ = !flight_id_.empty();
}

std::string MopEdgeEndpoint::NextMsgId(const std::string& channel,
                                       const std::string& type,
                                       const std::int64_t* seq) {
    ++msg_counter_;
    return MopProtocol::NewMsgId({"edge", flight_id_, edge_boot_id_,
                                  std::to_string(msg_counter_), channel, type,
                                  seq != nullptr ? std::to_string(*seq)
                                                 : "None"});
}

const JsonValue& MopEdgeEndpoint::Send(const std::string& channel,
                                       const std::string& type,
                                       JsonValue payload, bool reliable) {
    std::int64_t seq_value = 0;
    const std::int64_t* seq_ptr = nullptr;
    if (type != "ACK") {
        seq_value = ++seq_[channel];
        seq_ptr = &seq_value;
    }
    std::string ack_of_local;
    const std::string* ack_of = nullptr;
    if (type == "ACK") {
        const JsonValue* ack = payload.Find("ack_of");
        if (ack != nullptr && ack->IsString()) {
            ack_of_local = ack->strValue();
            ack_of = &ack_of_local;
        }
    }
    const std::string msg_id = NextMsgId(channel, type, seq_ptr);
    outbox_.push_back(MakeMopEnvelope(msg_id, channel, type, flight_id_,
                                      &edge_boot_id_, seq_ptr, ack_of,
                                      std::move(payload), clock_()));
    const JsonValue& stored = outbox_.back();
    if (reliable && type != "ACK") {
        unacked_.emplace(msg_id, UnackedEntry{stored, 1});
    }
    return stored;
}

void MopEdgeEndpoint::SendAck(const std::string& msg_id,
                              const std::string& channel) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("ack_of", JsonValue::Str(msg_id));
    Send(channel, "ACK", std::move(payload), /*reliable=*/false);
}

std::string MopEdgeEndpoint::Receive(const JsonValue& envelope) {
    const JsonValue* msg_id_node = envelope.Find("msg_id");
    const JsonValue* type_node = envelope.Find("type");
    if (msg_id_node == nullptr || !msg_id_node->IsString() ||
        type_node == nullptr || !type_node->IsString()) {
        return "error";
    }
    const std::string msg_id = msg_id_node->strValue();
    const std::string type = type_node->strValue();
    // Flight identity handoff: adopt the RC's flight id before dispatching
    // (see AdoptFlightId). A MISSION_PREPARE for a DIFFERENT flight also
    // re-adopts (a new RC flight cycle against this same Edge process).
    const JsonValue* flight = envelope.Find("flight_id");
    if (flight != nullptr && flight->IsString() &&
        !flight->strValue().empty()) {
        const std::string& incoming = flight->strValue();
        if (incoming != flight_id_ &&
            (!flight_id_confirmed_ || type == "MISSION_PREPARE")) {
            AdoptFlightId(incoming);
        }
    }
    if (seen_msg_ids_.count(msg_id) != 0) {
        // Idempotent control: resend the stored response when the original
        // reply was lost (same application_id preserved).
        if (type == "MISSION_PREPARE" || type == "MISSION_APPLY") {
            const auto it = last_responses_.find(msg_id);
            if (it != last_responses_.end()) outbox_.push_back(it->second);
        }
        return "dedup";
    }
    // A confirmed identity never processes a command addressed to a
    // DIFFERENT flight (e.g. a stale retry from a previous flight). It is
    // terminal-ACKed with NO state change: processing it under the current
    // identity would misattribute evidence, and leaving it un-ACKed would
    // make the sender retry forever. Not added to `seen` so a redelivery
    // (lost ACK) is ACKed again.
    if (flight_id_confirmed_ && flight != nullptr && flight->IsString() &&
        !flight->strValue().empty() && flight->strValue() != flight_id_ &&
        type != "MISSION_PREPARE") {
        const JsonValue* channel_node = envelope.Find("channel");
        if (channel_node != nullptr && channel_node->IsString()) {
            SendAck(msg_id, channel_node->strValue());
        }
        return "error";
    }
    seen_msg_ids_.insert(msg_id);
    const JsonValue* payload = envelope.Find("payload");
    if (payload == nullptr || !payload->IsObject()) return "error";
    if (type == "MISSION_PREPARE") return OnPrepare(envelope, *payload);
    if (type == "MISSION_APPLY") return OnApply(envelope, *payload);
    if (type == "MISSION_STATUS_QUERY") return OnStatusQuery(envelope);
    if (type == "SESSION_START" || type == "SESSION_STOP") {
        return OnSession(envelope, type);
    }
    if (type == "ACK") return OnAck(*payload);
    return "error";
}

std::string MopEdgeEndpoint::OnPrepare(const JsonValue& envelope,
                                       const JsonValue& payload) {
    const JsonValue* bundle = payload.Find("bundle");
    const JsonValue empty = JsonValue::Obj();
    const JsonValue& checked = (bundle != nullptr && bundle->IsObject())
                                   ? *bundle
                                   : empty;
    const std::vector<std::string> reasons = CheckBundle(checked);
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    const std::string msg_id = request_msg_id->strValue();
    const JsonValue* bundle_id = checked.Find("bundle_id");
    if (reasons.empty() && (bundle_id == nullptr || !bundle_id->IsString())) {
        // Harness parity: a digest-valid bundle missing its bundle_id raises
        // KeyError there -> transport "error" with NO response and NO ACK
        // (only a buggy sender reaches this; the message stays deduped).
        return "error";
    }
    const JsonValue& response = [&]() -> const JsonValue& {
        if (!reasons.empty()) {
            JsonValue out = JsonValue::Obj();
            JsonValue::Array list;
            for (const auto& reason : reasons) {
                list.push_back(JsonValue::Str(reason));
            }
            out.Set("reasons", JsonValue::Arr(std::move(list)));
            return Send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED",
                        std::move(out));
        }
        const JsonValue* bundle_sha = checked.Find("bundle_sha256");
        prepared_[bundle_id->strValue()] = checked;
        JsonValue out = JsonValue::Obj();
        out.Set("bundle_sha256", JsonValue::Str(bundle_sha != nullptr &&
                                                        bundle_sha->IsString()
                                                    ? bundle_sha->strValue()
                                                    : std::string()));
        out.Set("supported", JsonValue::Bool(true));
        out.Set("exclusions", JsonValue::Arr());
        return Send("CONTROL_RELIABLE", "MISSION_PREPARED", std::move(out));
    }();
    last_responses_[msg_id] = response;
    SendAck(msg_id, request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnApply(const JsonValue& envelope,
                                     const JsonValue& payload) {
    const JsonValue* bundle_id_node = payload.Find("bundle_id");
    const JsonValue* bundle_sha_node = payload.Find("bundle_sha256");
    const std::string bundle_id =
        bundle_id_node != nullptr && bundle_id_node->IsString()
            ? bundle_id_node->strValue()
            : std::string();
    const std::string bundle_sha =
        bundle_sha_node != nullptr && bundle_sha_node->IsString()
            ? bundle_sha_node->strValue()
            : std::string();
    const auto prepared = prepared_.find(bundle_id);
    const bool known =
        prepared != prepared_.end() && prepared->second.IsObject() &&
        prepared->second.Find("bundle_sha256") != nullptr &&
        prepared->second.Find("bundle_sha256")->IsString() &&
        prepared->second.Find("bundle_sha256")->strValue() == bundle_sha;
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    const std::string msg_id = request_msg_id->strValue();
    const JsonValue& response = [&]() -> const JsonValue& {
        if (!known) {
            JsonValue out = JsonValue::Obj();
            JsonValue::Array list;
            list.push_back(JsonValue::Str(
                "UNKNOWN_BUNDLE: bundle not prepared or digest differs"));
            out.Set("reasons", JsonValue::Arr(std::move(list)));
            return Send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED",
                        std::move(out));
        }
        const std::string application_id = Uuid5(
            MopProtocol::Namespace(),
            "apply|" + flight_id_ + "|" + bundle_id + "|" + edge_boot_id_);
        applied_bundle_sha256_ = bundle_sha;
        applied_bundle_id_ = bundle_id;
        has_applied_bundle_ = true;
        running_ = true;
        last_application_id_ = application_id;
        has_application_ = true;
        JsonValue out = JsonValue::Obj();
        out.Set("application_id", JsonValue::Str(application_id));
        out.Set("effective_edge_seq", JsonValue::Int(seq("EVENT_RELIABLE")));
        applications_[msg_id] = out;
        return Send("CONTROL_RELIABLE", "MISSION_APPLIED", std::move(out));
    }();
    last_responses_[msg_id] = response;
    SendAck(msg_id, request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnStatusQuery(const JsonValue& envelope) {
    JsonValue out = JsonValue::Obj();
    out.Set("applied_bundle_sha256",
            has_applied_bundle_ ? JsonValue::Str(applied_bundle_sha256_)
                                : JsonValue::Null());
    out.Set("application_id", has_application_
                                  ? JsonValue::Str(last_application_id_)
                                  : JsonValue::Null());
    out.Set("edge_boot_id", JsonValue::Str(edge_boot_id_));
    out.Set("running", JsonValue::Bool(running_));
    out.Set("effective_edge_seq", JsonValue::Int(seq("EVENT_RELIABLE")));
    Send("CONTROL_RELIABLE", "MISSION_STATUS", std::move(out));
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    SendAck(request_msg_id->strValue(), request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnSession(const JsonValue& envelope,
                                       const std::string& type) {
    running_ = type == "SESSION_START";
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    SendAck(request_msg_id->strValue(), request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnAck(const JsonValue& payload) {
    const JsonValue* ack_of = payload.Find("ack_of");
    if (ack_of != nullptr && ack_of->IsString()) {
        unacked_.erase(ack_of->strValue());
    }
    return "ack";
}

void MopEdgeEndpoint::MarkUnrecoverable(int max_attempts) {
    for (auto it = unacked_.begin(); it != unacked_.end();) {
        const JsonValue& env = it->second.envelope;
        const JsonValue* channel = env.Find("channel");
        const JsonValue* seq_node = env.Find("seq");
        const bool is_data =
            channel != nullptr && channel->IsString() &&
            (channel->strValue() == "EVENT_RELIABLE" ||
             channel->strValue() == "EVIDENCE_TRANSFER");
        if (is_data && seq_node != nullptr && seq_node->IsInt()) {
            if (it->second.attempts > 1 || it->second.attempts >= max_attempts) {
                unrecoverable_[channel->strValue()].insert(
                    seq_node->intValue());
                it = unacked_.erase(it);
                continue;
            }
        }
        ++it;
    }
}

void MopEdgeEndpoint::RequeueUnacked() {
    for (auto& entry : unacked_) {
        ++entry.second.attempts;
        outbox_.push_back(entry.second.envelope);
    }
}

std::vector<JsonValue> MopEdgeEndpoint::UnackedCopies() const {
    std::vector<JsonValue> copies;
    copies.reserve(unacked_.size());
    for (const auto& entry : unacked_) copies.push_back(entry.second.envelope);
    return copies;
}

std::string MopEdgeEndpoint::Reboot(const std::string& next_boot_id) {
    ++boot_index_;
    edge_boot_id_ = !next_boot_id.empty()
                        ? next_boot_id
                        : Uuid5(MopProtocol::Namespace(),
                                "boot|" + flight_id_ + "|" +
                                    std::to_string(boot_index_));
    applied_bundle_sha256_.clear();
    applied_bundle_id_.clear();
    has_applied_bundle_ = false;
    last_application_id_.clear();
    has_application_ = false;
    running_ = false;
    seq_.clear();
    unrecoverable_.clear();
    applications_.clear();
    return edge_boot_id_;
}

void MopEdgeEndpoint::AdoptFlightId(const std::string& new_id) {
    flight_id_ = new_id;
    flight_id_confirmed_ = true;
    ++adoptions_;
    // Fresh session scope for the (new) flight: rotate the boot and drop
    // every per-flight/per-boot state — seqs, retries and retry candidates
    // must never span flights, and stale ACKs may not resurrect new state.
    edge_boot_id_ = Uuid5(MopProtocol::Namespace(),
                          "boot|" + flight_id_ + "|" +
                              std::to_string(boot_index_));
    msg_counter_ = 0;
    seq_.clear();
    unacked_.clear();
    unrecoverable_.clear();
    applications_.clear();
    prepared_.clear();
    last_responses_.clear();
    seen_msg_ids_.clear();
    // Queued-but-unflushed envelopes carry the OLD identity (flight id in
    // every envelope, payloads reference that flight's provenance): they
    // must never reach the wire under the new identity. The response/ACK
    // for the adopting envelope is generated after this clear.
    outbox_.clear();
    applied_bundle_sha256_.clear();
    applied_bundle_id_.clear();
    has_applied_bundle_ = false;
    last_application_id_.clear();
    has_application_ = false;
    running_ = false;
}

const JsonValue* MopEdgeEndpoint::appliedBundle() const {
    if (!has_applied_bundle_) return nullptr;
    const auto it = prepared_.find(applied_bundle_id_);
    if (it == prepared_.end() || !it->second.IsObject()) return nullptr;
    return &it->second;
}

std::int64_t MopEdgeEndpoint::seq(const std::string& channel) const {
    const auto it = seq_.find(channel);
    return it != seq_.end() ? it->second : 0;
}

void MopEdgeEndpoint::EmitObservation(JsonValue observation) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("observation", std::move(observation));
    Send("EVENT_RELIABLE", "OBSERVATION", std::move(payload));
}

void MopEdgeEndpoint::EmitEvent(JsonValue event) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("event", std::move(event));
    Send("EVENT_RELIABLE", "DETECTION_EVENT", std::move(payload));
}

void MopEdgeEndpoint::EmitSummary(JsonValue summary) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("summary", std::move(summary));
    Send("EVENT_RELIABLE", "FLIGHT_SUMMARY", std::move(payload));
}

void MopEdgeEndpoint::EmitHeartbeat(double fps) {
    std::vector<std::pair<std::string, std::vector<std::int64_t>>> gaps;
    gaps.reserve(unrecoverable_.size());
    for (const auto& entry : unrecoverable_) {
        gaps.emplace_back(entry.first,
                          std::vector<std::int64_t>(entry.second.begin(),
                                                    entry.second.end()));
    }
    Send("HEARTBEAT", "HEARTBEAT",
         MakeHeartbeatPayload(fps, has_applied_bundle_, applied_bundle_sha256_,
                              gaps, edge_boot_id_));
}

void MopEdgeEndpoint::EmitArtifact(const std::string& artifact_id,
                                   const std::string& source_kind,
                                   const std::string& media_type,
                                   std::int64_t segment_index, bool required,
                                   const std::string& bytes) {
    const std::string sha256 = Sha256Hex(bytes);
    Send("EVIDENCE_TRANSFER", "ARTIFACT_ANNOUNCE",
         MakeArtifactAnnouncePayload(artifact_id, flight_id_, source_kind,
                                     sha256,
                                     static_cast<std::int64_t>(bytes.size()),
                                     media_type, segment_index, required,
                                     edge_boot_id_));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const std::size_t end = std::min(offset + kChunkSize, bytes.size());
        Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK",
             MakeArtifactChunkPayload(
                 artifact_id, static_cast<std::int64_t>(offset),
                 reinterpret_cast<const std::uint8_t*>(bytes.data() + offset),
                 end - offset));
        offset = end;
    }
}

}  // namespace ksi::safety
