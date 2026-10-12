#include "ksi_safety/transport/mop_envelope.hpp"

#include <algorithm>

#include "ksi_safety/transport/digest.hpp"

namespace ksi::safety {

namespace {
const std::vector<std::string> kChannels = {
    "CONTROL_RELIABLE", "EVENT_RELIABLE", "EVIDENCE_TRANSFER", "HEARTBEAT"};
const std::vector<std::string> kDataChannels = {"EVENT_RELIABLE",
                                                "EVIDENCE_TRANSFER"};
const std::vector<std::string> kRcToEdge = {
    "MISSION_PREPARE", "MISSION_APPLY", "MISSION_STATUS_QUERY",
    "SESSION_START", "SESSION_STOP"};
const std::vector<std::string> kEdgeToRc = {
    "MISSION_PREPARED", "MISSION_APPLY_REJECTED", "MISSION_APPLIED",
    "MISSION_STATUS",    "OBSERVATION",           "DETECTION_EVENT",
    "FLIGHT_SUMMARY",    "ARTIFACT_ANNOUNCE",     "ARTIFACT_CHUNK",
    "HEARTBEAT",         "ACK"};
}  // namespace

const std::vector<std::string>& MopProtocol::Channels() { return kChannels; }

const std::vector<std::string>& MopProtocol::DataChannels() {
    return kDataChannels;
}

const std::vector<std::string>& MopProtocol::RcToEdge() { return kRcToEdge; }

const std::vector<std::string>& MopProtocol::EdgeToRc() { return kEdgeToRc; }

bool MopProtocol::IsChannel(const std::string& channel) {
    return std::find(kChannels.begin(), kChannels.end(), channel) !=
           kChannels.end();
}

const std::string& MopProtocol::Namespace() {
    static const std::string kNamespace = Uuid5Dns("ksi-safety-edge-harness");
    return kNamespace;
}

std::string MopProtocol::NewMsgId(const std::vector<std::string>& parts) {
    std::string joined;
    for (std::size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) joined += '|';
        joined += parts[i];
    }
    return Uuid5(Namespace(), joined);
}

JsonValue MakeMopEnvelope(const std::string& msg_id,
                          const std::string& channel,
                          const std::string& type,
                          const std::string& flight_id,
                          const std::string* edge_boot_id,
                          const std::int64_t* seq,
                          const std::string* ack_of,
                          JsonValue payload,
                          std::int64_t sent_monotonic_ns) {
    JsonValue envelope = JsonValue::Obj();
    envelope.Set("protocol_version", JsonValue::Int(MopProtocol::kVersion));
    envelope.Set("msg_id", JsonValue::Str(msg_id));
    envelope.Set("channel", JsonValue::Str(channel));
    envelope.Set("type", JsonValue::Str(type));
    envelope.Set("flight_id", JsonValue::Str(flight_id));
    envelope.Set("edge_boot_id",
                 edge_boot_id != nullptr ? JsonValue::Str(*edge_boot_id)
                                         : JsonValue::Null());
    envelope.Set("seq", seq != nullptr ? JsonValue::Int(*seq)
                                       : JsonValue::Null());
    envelope.Set("ack_of",
                 ack_of != nullptr ? JsonValue::Str(*ack_of)
                                   : JsonValue::Null());
    envelope.Set("payload_sha256", JsonValue::Str(DigestSha256Json(payload)));
    envelope.Set("payload", std::move(payload));
    envelope.Set("sent_monotonic_ns", JsonValue::Int(sent_monotonic_ns));
    return envelope;
}

bool VerifyMopEnvelope(const JsonValue& envelope, JsonValue* payload_out,
                       std::string* reason) {
    auto fail = [&](const std::string& message) {
        if (reason != nullptr) *reason = message;
        return false;
    };
    if (!envelope.IsObject()) return fail("envelope must be an object");
    const JsonValue* version = envelope.Find("protocol_version");
    if (version == nullptr || !version->IsInt() ||
        version->intValue() != MopProtocol::kVersion) {
        return fail("bad protocol_version");
    }
    const JsonValue* payload = envelope.Find("payload");
    if (payload == nullptr || !payload->IsObject()) {
        return fail("payload must be an object");
    }
    // Contract: payload_sha256 is the JCS digest of the PAYLOAD alone
    // (Python mop.py / Kotlin Mop.verifyEnvelope — not envelope-minus-field).
    const JsonValue* claimed = envelope.Find("payload_sha256");
    if (claimed == nullptr || !claimed->IsString() ||
        claimed->strValue() != DigestSha256Json(*payload)) {
        return fail("payload_sha256 mismatch");
    }
    if (payload_out != nullptr) *payload_out = *payload;
    return true;
}

}  // namespace ksi::safety
