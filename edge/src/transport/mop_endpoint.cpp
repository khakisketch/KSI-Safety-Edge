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
                                 int boot_index, std::string boot_id,
                                 std::string process_unique)
    : flight_id_(std::move(flight_id)),
      clock_(std::move(clock)),
      boot_index_(boot_index),
      process_unique_(std::move(process_unique)) {
    edge_boot_id_ =
        !boot_id.empty() ? std::move(boot_id) : DeriveBootId();
    // An explicit non-empty id is authoritative; an empty one means "learn
    // it from the wire" (the RC creates the per-flight UUID).
    flight_id_confirmed_ = !flight_id_.empty();
}

std::string MopEdgeEndpoint::DeriveBootId() const {
    std::string base = "boot|" + flight_id_ + "|" + std::to_string(boot_index_);
    if (!process_unique_.empty()) {
        base += "|" + process_unique_;
    }
    return Uuid5(MopProtocol::Namespace(), base);
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
    JsonValue envelope = MakeMopEnvelope(msg_id, channel, type, flight_id_,
                                      &edge_boot_id_, seq_ptr, ack_of,
                                      std::move(payload), clock_());
    // Durable journal BEFORE queue: an evidence envelope must survive a
    // crash between queue and send. Protocol chatter (CONTROL responses,
    // ACKs, heartbeats) is regenerated on demand and never journalled.
    // No journal => no queue => no success is ever reported unjournalled.
    if (spool_ != nullptr) {
        if (type != "ACK" && (channel == "EVENT_RELIABLE" ||
                              channel == "EVIDENCE_TRANSFER")) {
            AppendResult journal = spool_->Append(envelope);
            if (journal.status == SpoolStatus::kFull) {
                // Pressure path (NOT per frame: only when the journal is
                // actually full): reclaim acked payloads once, then retry
                // the single envelope once. A second miss means genuinely
                // full of unacked data — refuse honestly below.
                const CompactResult compacted = spool_->Compact();
                if (compacted.ok() &&
                    compacted.records_compacted > 0) {
                    journal = spool_->Append(envelope);
                }
            }
            if (!journal.ok()) {
                ++spool_refused_;
                FailSpool(std::string("spool journal refused (") +
                          SpoolStatusName(journal.status) +
                          "): " + journal.error);
                static const JsonValue kDropped = JsonValue::Obj();
                return kDropped;
            }
        }
    }
    outbox_.push_back(std::move(envelope));
    const JsonValue& stored = outbox_.back();
    // Reliable envelopes wait for the RC's ACK (they are journalled data or
    // CONTROL replies). PROTOCOL CHATTER — the heartbeat — is sent with
    // reliable=false: it is regenerated every second, so tracking it as
    // "awaiting an ACK" made the Edge re-send the SAME heartbeat forever and
    // the RC saw nothing but duplicates.
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
    // WHY: a command that never gets a reply (PREPARE -> PREPARED) leaves
    // the RC waiting forever with no visible cause. One line per received
    // type makes the handshake visible on both ends.
    std::fprintf(stderr, "mop: received %s (msg_id=%s)\n", type.c_str(),
                 msg_id.c_str());
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
    if (!known) {
        JsonValue out = JsonValue::Obj();
        JsonValue::Array list;
        list.push_back(JsonValue::Str(
            "UNKNOWN_BUNDLE: bundle not prepared or digest differs"));
        out.Set("reasons", JsonValue::Arr(std::move(list)));
        Send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED", std::move(out));
        // CONTROL responses are never spool-journalled, so the envelope is
        // always queued here (no kDropped path).
        last_responses_[msg_id] = outbox_.back();
        SendAck(msg_id, request_channel->strValue());
        return "stored";
    }
    if (deferred_apply_) {
        // Device path: validate now, install later. No bundle switch, no
        // generation bump, no MISSION_APPLIED — those happen exactly once,
        // in ConfirmPendingApply, after the REAL install receipt. The ACK
        // below tells the RC the request arrived; APPLIED means installed.
        // A newer request overwrites an older pending one (final intent).
        has_pending_apply_ = true;
        pending_bundle_id_ = bundle_id;
        pending_bundle_sha_ = bundle_sha;
        pending_msg_id_ = msg_id;
        NoteAcceptedCommand(msg_id, "MISSION_APPLY");
        SendAck(msg_id, request_channel->strValue());
        return "stored";
    }
    const JsonValue& response = [&]() -> const JsonValue& {
        const std::string application_id = Uuid5(
            MopProtocol::Namespace(),
            "apply|" + flight_id_ + "|" + bundle_id + "|" + edge_boot_id_);
        applied_bundle_sha256_ = bundle_sha;
        applied_bundle_id_ = bundle_id;
        has_applied_bundle_ = true;
        // MISSION_APPLY is not SESSION_START: `running_` ("start requested
        // and not stopped") is untouched — only a SESSION_START opens the
        // execution gate. The service glue notices the new generation and
        // installs the bundle into the engine.
        ++applied_generation_;
        last_application_id_ = application_id;
        has_application_ = true;
        JsonValue out = JsonValue::Obj();
        out.Set("application_id", JsonValue::Str(application_id));
        out.Set("effective_edge_seq", JsonValue::Int(seq("EVENT_RELIABLE")));
        applications_[msg_id] = out;
        NoteAcceptedCommand(msg_id, "MISSION_APPLY");
        return Send("CONTROL_RELIABLE", "MISSION_APPLIED", std::move(out));
    }();
    last_responses_[msg_id] = response;
    SendAck(msg_id, request_channel->strValue());
    return "stored";
}

const JsonValue* MopEdgeEndpoint::pendingBundle() const {
    if (!has_pending_apply_) return nullptr;
    const auto it = prepared_.find(pending_bundle_id_);
    if (it == prepared_.end() || !it->second.IsObject()) return nullptr;
    return &it->second;
}

bool MopEdgeEndpoint::ConfirmPendingApply() {
    if (!has_pending_apply_) return false;
    has_pending_apply_ = false;
    const std::string bundle_id = pending_bundle_id_;
    const std::string bundle_sha = pending_bundle_sha_;
    const std::string msg_id = pending_msg_id_;
    pending_bundle_id_.clear();
    pending_bundle_sha_.clear();
    pending_msg_id_.clear();
    const auto it = prepared_.find(bundle_id);
    if (it == prepared_.end() || !it->second.IsObject()) {
        // Adoption or reboot cleared the prepared set after the snapshot:
        // nothing to confirm. The caller parks instead of running it.
        return false;
    }
    applied_bundle_sha256_ = bundle_sha;
    applied_bundle_id_ = bundle_id;
    has_applied_bundle_ = true;
    // No applied_generation_ bump here: the installer already consumed the
    // pending bundle, so there is nothing new for the poll loop to install.
    // (Legacy immediate-apply bumps because the switch is the news.)
    const std::string application_id = Uuid5(
        MopProtocol::Namespace(),
        "apply|" + flight_id_ + "|" + bundle_id + "|" + edge_boot_id_);
    last_application_id_ = application_id;
    has_application_ = true;
    JsonValue out = JsonValue::Obj();
    out.Set("application_id", JsonValue::Str(application_id));
    out.Set("effective_edge_seq", JsonValue::Int(seq("EVENT_RELIABLE")));
    applications_[msg_id] = out;
    const JsonValue& response =
        Send("CONTROL_RELIABLE", "MISSION_APPLIED", std::move(out));
    // A retried request (same msg_id) after the confirm resends this.
    last_responses_[msg_id] = response;
    return true;
}

bool MopEdgeEndpoint::RejectPendingApply(
    const std::vector<std::string>& reasons) {
    if (!has_pending_apply_) return false;
    has_pending_apply_ = false;
    const std::string msg_id = pending_msg_id_;
    pending_bundle_id_.clear();
    pending_bundle_sha_.clear();
    pending_msg_id_.clear();
    JsonValue out = JsonValue::Obj();
    JsonValue::Array list;
    for (const auto& reason : reasons) {
        list.push_back(JsonValue::Str(reason));
    }
    out.Set("reasons", JsonValue::Arr(std::move(list)));
    const JsonValue& response =
        Send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED", std::move(out));
    last_responses_[msg_id] = response;
    return true;
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
    // Optional runtime facts (§3): only present fields are emitted; absence
    // is "unknown", never false/0.
    if (has_runtime_facts_) {
        const MopRuntimeFacts& facts = runtime_facts_;
        if (facts.has_config_epoch) {
            out.Set("runtime_epoch",
                    JsonValue::Str(Uuid5(MopProtocol::Namespace(),
                                        "runtime|" + flight_id_ + "|" +
                                            edge_boot_id_ + "|" +
                                            std::to_string(
                                                facts.config_epoch))));
        }
        if (facts.has_state) {
            out.Set("runtime_state", JsonValue::Str(facts.runtime_state));
        }
        if (facts.has_config_applied) {
            out.Set("config_applied", JsonValue::Bool(facts.config_applied));
        }
        if (facts.has_effective_config_hash) {
            out.Set("effective_config_hash",
                    facts.effective_config_hash.empty()
                        ? JsonValue::Null()
                        : JsonValue::Str(facts.effective_config_hash));
        }
        if (facts.has_processed_frame_count) {
            out.Set("processed_frame_count",
                    JsonValue::Int(static_cast<std::int64_t>(
                        facts.processed_frame_count)));
        }
        if (facts.has_last_processed_mono_ns) {
            std::int64_t age_ms =
                (clock_() - facts.last_processed_frame_mono_ns) / 1000000LL;
            if (age_ms < 0) age_ms = 0;
            out.Set("last_processed_frame_age_ms", JsonValue::Int(age_ms));
        } else if (facts.has_processed_frame_count) {
            out.Set("last_processed_frame_age_ms", JsonValue::Null());
        }
        if (facts.has_degraded_reasons) {
            JsonValue::Array list;
            for (const auto& reason : facts.degraded_reasons) {
                list.push_back(JsonValue::Str(reason));
            }
            out.Set("degraded_reasons", JsonValue::Arr(std::move(list)));
        }
        if (facts.has_input_state) {
            JsonValue input = JsonValue::Obj();
            input.Set("video", JsonValue::Str(facts.input_state.video));
            input.Set("telemetry",
                      JsonValue::Str(facts.input_state.telemetry));
            input.Set("engine", JsonValue::Str(facts.input_state.engine));
            out.Set("input_state", std::move(input));
        }
    }
    Send("CONTROL_RELIABLE", "MISSION_STATUS", std::move(out));
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    SendAck(request_msg_id->strValue(), request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnSession(const JsonValue& envelope,
                                       const std::string& type) {
    if (type == "SESSION_START") {
        running_ = true;
        ++session_start_count_;
        desired_running_ = true;
        last_command_was_start_ = true;
    } else {
        running_ = false;
        ++session_stop_count_;
        desired_running_ = false;
        last_command_was_start_ = false;
    }
    // One revision per accepted command. Wire retries never reach here
    // (dedup by msg_id in Receive), so a retry advances neither the counts
    // nor the revision — a repeated poll is a no-op downstream.
    ++command_revision_;
    const JsonValue* request_msg_id = envelope.Find("msg_id");
    const JsonValue* request_channel = envelope.Find("channel");
    NoteAcceptedCommand(request_msg_id->strValue(), type);
    SendAck(request_msg_id->strValue(), request_channel->strValue());
    return "stored";
}

std::string MopEdgeEndpoint::OnAck(const JsonValue& payload) {
    const JsonValue* ack_of = payload.Find("ack_of");
    if (ack_of != nullptr && ack_of->IsString()) {
        const std::size_t before = unacked_.size();
        const bool retired = unacked_.erase(ack_of->strValue()) != 0;
        std::fprintf(stderr,
                     "mop: ack retired=%s unacked=%zu->%zu ack_of=%s\\n",
                     retired ? "yes" : "NO", before, unacked_.size(),
                     ack_of->strValue().c_str());
        // Only an RC persist-ACK marks a journalled envelope delivered.
        // Unknown ids (pre-spool envelopes, protocol chatter) are ignored:
        // at-least-once replay stays safe because the RC dedups by msg_id.
        if (spool_ != nullptr) {
            spool_->Acknowledge(ack_of->strValue());
        }
    }
    return "ack";
}

void MopEdgeEndpoint::FailSpool(const std::string& reason) {
    if (!spool_failed_) {
        spool_failed_ = true;
        spool_fail_reason_ = reason;
    }
}

bool MopEdgeEndpoint::spoolFailed(std::string* reason_out) const {
    if (reason_out != nullptr && spool_failed_) {
        *reason_out = spool_fail_reason_;
    }
    return spool_failed_;
}

bool MopEdgeEndpoint::CheckSpoolWritable() {
    if (spool_failed_) {
        return false;
    }
    if (spool_ == nullptr) {
        return true;  // legacy in-memory behaviour (no durability claim)
    }
    if (!spool_->ok()) {
        FailSpool("spool unusable: " + spool_->last_error());
        return false;
    }
    // Admission bound: one maximal envelope must fit. Fails closed early
    // (bounded waste) instead of folding ticks whose exact bytes can never
    // be journalled — every refused tick would otherwise poison the
    // summary with unjournalled records. Allowed reclaim happens FIRST:
    // acked payloads compact to tombstones before any failure is declared,
    // so only a journal genuinely full of UNACKED data (or tombstones)
    // latches the run.
    constexpr std::uint64_t kMaxEnvelope = 256ULL * 1024ULL;
    const SpoolStatus room = spool_->EnsureCapacity(kMaxEnvelope);
    if (room == SpoolStatus::kOk) {
        return true;
    }
    if (room == SpoolStatus::kFull) {
        FailSpool("spool capacity exhausted (full of unacked data)");
        return false;
    }
    FailSpool("spool unusable: " + spool_->last_error());
    return false;
}

std::size_t MopEdgeEndpoint::ReplaySpool() {    if (spool_ == nullptr) return 0;
    std::set<std::string> present;
    for (const JsonValue& queued : outbox_) {
        const JsonValue* id = queued.Find("msg_id");
        if (id != nullptr && id->IsString()) present.insert(id->strValue());
    }
    for (const auto& entry : unacked_) {
        present.insert(entry.first);
    }
    std::size_t requeued = 0;
    for (const PendingMessage& pending : spool_->Pending()) {
        if (present.count(pending.msg_id) != 0) continue;
        outbox_.push_back(pending.envelope);
        const JsonValue& stored = outbox_.back();
        const JsonValue* type = stored.Find("type");
        if (type != nullptr && type->IsString() &&
            type->strValue() != "ACK") {
            unacked_.emplace(pending.msg_id, UnackedEntry{stored, 1});
        }
        present.insert(pending.msg_id);
        ++requeued;
    }
    return requeued;
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
    // WHY: an unacked entry that is never retired is re-queued forever: the
    // outbox grows, the peer sees nothing but duplicates, and the queue can
    // run away. Log the shape so the stuck kind is visible.
    std::size_t logged = 0;
    for (auto& entry : unacked_) {
        ++entry.second.attempts;
        outbox_.push_back(entry.second.envelope);
        if (logged < 3) {
            const JsonValue* type = entry.second.envelope.Find("type");
            const JsonValue* channel = entry.second.envelope.Find("channel");
            std::fprintf(stderr, "mop: requeue %s/%s attempts=%u\\n",
                         channel != nullptr ? channel->strValue().c_str() : "?",
                         type != nullptr ? type->strValue().c_str() : "?",
                         static_cast<unsigned>(entry.second.attempts));
            ++logged;
        }
    }
    if (!unacked_.empty()) {
        std::fprintf(stderr, "mop: requeued %zu unacked envelope(s)\\n",
                     unacked_.size());
    }
}

std::vector<JsonValue> MopEdgeEndpoint::UnackedCopies() const {
    std::vector<JsonValue> copies;
    copies.reserve(unacked_.size());
    for (const auto& entry : unacked_) copies.push_back(entry.second.envelope);
    return copies;
}

void MopEdgeEndpoint::PublishRuntimeFacts(MopRuntimeFacts facts) {
    runtime_facts_ = std::move(facts);
    has_runtime_facts_ = true;
}

void MopEdgeEndpoint::ClearRuntimeFacts() {
    runtime_facts_ = MopRuntimeFacts{};
    has_runtime_facts_ = false;
}

MopSessionCommands MopEdgeEndpoint::session_commands() const {
    MopSessionCommands out;
    out.desired_running = desired_running_;
    out.command_revision = command_revision_;
    out.command_epoch = command_epoch_;
    out.start_count = session_start_count_;
    out.stop_count = session_stop_count_;
    out.last_command_was_start = last_command_was_start_;
    out.applied_generation = applied_generation_;
    out.has_bundle = appliedBundle() != nullptr;
    return out;
}

void MopEdgeEndpoint::NoteAcceptedCommand(const std::string& msg_id,
                                          const std::string& type) {
    ++command_seq_;
    AcceptedCommand entry;
    entry.msg_id = msg_id;
    entry.type = type;
    entry.seq = command_seq_;
    accepted_commands_.push_back(std::move(entry));
    while (accepted_commands_.size() > 512) {
        accepted_commands_.pop_front();
        ++commands_dropped_;
    }
}

std::vector<AcceptedCommand> MopEdgeEndpoint::AcceptedCommandsSince(
    std::uint64_t since_seq, bool* overflow) const {
    std::vector<AcceptedCommand> out;
    bool gap = false;
    for (const AcceptedCommand& entry : accepted_commands_) {
        if (entry.seq <= since_seq) {
            continue;
        }
        if (out.empty() && !gap && entry.seq != since_seq + 1) {
            gap = true;  // dense seqs: anything skipped was dropped
        }
        out.push_back(entry);
    }
    if (overflow != nullptr) {
        *overflow = gap;
    }
    return out;
}

std::string MopEdgeEndpoint::Reboot(const std::string& next_boot_id) {
    ++boot_index_;
    edge_boot_id_ = !next_boot_id.empty() ? next_boot_id : DeriveBootId();
    has_pending_apply_ = false;
    pending_bundle_id_.clear();
    pending_bundle_sha_.clear();
    pending_msg_id_.clear();
    applied_bundle_sha256_.clear();
    applied_bundle_id_.clear();
    has_applied_bundle_ = false;
    last_application_id_.clear();
    has_application_ = false;
    running_ = false;
    desired_running_ = false;
    last_command_was_start_ = false;
    command_revision_ = 0;
    ++command_epoch_;
    accepted_commands_.clear();
    command_seq_ = 0;
    session_start_count_ = 0;
    session_stop_count_ = 0;
    ++applied_generation_;
    ClearRuntimeFacts();
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
    edge_boot_id_ = DeriveBootId();
    has_pending_apply_ = false;
    pending_bundle_id_.clear();
    pending_bundle_sha_.clear();
    pending_msg_id_.clear();
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
    desired_running_ = false;
    last_command_was_start_ = false;
    command_revision_ = 0;
    ++command_epoch_;
    accepted_commands_.clear();
    command_seq_ = 0;
    session_start_count_ = 0;
    session_stop_count_ = 0;
    ++applied_generation_;
    ClearRuntimeFacts();
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
    // A heartbeat is PROTOCOL CHATTER: it is regenerated every second, so it
    // must NOT wait for an ACK. Tracking it as "unacked" made the Edge
    // re-send the SAME heartbeat forever — the RC (which does not ACK
    // chatter) saw nothing but duplicates, so its freshness fact
    // ("최근 수신 시각") never advanced and the screen said "하트비트 미수신"
    // while data was flowing.
    const std::string payload_text =
        WriteCanonicalJson(MakeHeartbeatPayload(fps, has_applied_bundle_,
                                                 applied_bundle_sha256_, gaps,
                                                 edge_boot_id_));
    std::fprintf(stderr, "mop: emit HEARTBEAT (%zu bytes)\n",
                 payload_text.size());
    Send("HEARTBEAT", "HEARTBEAT",
         MakeHeartbeatPayload(fps, has_applied_bundle_, applied_bundle_sha256_,
                              gaps, edge_boot_id_),
         /*reliable=*/false);
}

void MopEdgeEndpoint::EmitArtifact(const std::string& artifact_id,
                                   const std::string& source_kind,
                                   const std::string& media_type,
                                   std::int64_t segment_index, bool required,
                                   const std::string& bytes) {
    EmitArtifactAs(artifact_id, source_kind, media_type, segment_index,
                   required, bytes, flight_id_, edge_boot_id_);
}

// Prior-boot history ships under its ORIGINAL identity: the announce and
// the artifact bytes carry the old flight/boot, never relabelled under the
// current process identity. Journal bytes (not the live endpoint state)
// are the source of truth here.
void MopEdgeEndpoint::EmitArtifactAs(
    const std::string& artifact_id, const std::string& source_kind,
    const std::string& media_type, std::int64_t segment_index, bool required,
    const std::string& bytes, const std::string& flight_id,
    const std::string& edge_boot_id) {
    const std::string sha256 = Sha256Hex(bytes);
    Send("EVIDENCE_TRANSFER", "ARTIFACT_ANNOUNCE",
         MakeArtifactAnnouncePayload(artifact_id, flight_id, source_kind,
                                     sha256,
                                     static_cast<std::int64_t>(bytes.size()),
                                     media_type, segment_index, required,
                                     edge_boot_id));
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
