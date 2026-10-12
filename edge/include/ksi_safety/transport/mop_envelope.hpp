// KSI MOP envelope contract (protocol v1) — ported from the validated
// Python harness (`harness/src/ksi_harness/mop.py`) and the Kotlin port
// (`android-rc/rc-core/.../Mop.kt`). This adds NO new message semantics;
// it is the same envelope the RC side already implements, with
// `payload_sha256` over RFC 8785 canonical bytes of the payload.
#ifndef KSI_SAFETY_TRANSPORT_MOP_ENVELOPE_HPP
#define KSI_SAFETY_TRANSPORT_MOP_ENVELOPE_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/transport/json.hpp"

namespace ksi::safety {

struct MopProtocol {
    static constexpr int kVersion = 1;

    static const std::vector<std::string>& Channels();
    static const std::vector<std::string>& DataChannels();
    static const std::vector<std::string>& RcToEdge();
    static const std::vector<std::string>& EdgeToRc();
    static bool IsChannel(const std::string& channel);

    // uuid5(NAMESPACE_DNS, "ksi-safety-edge-harness") — the SAME namespace
    // Python and Kotlin use.
    static const std::string& Namespace();

    // uuid5(NAMESPACE, parts joined with '|').
    static std::string NewMsgId(const std::vector<std::string>& parts);
};

// Builds one envelope. `edge_boot_id`, `seq` and `ack_of` may be null
// (kNullable* pointers). Payload must be an object.
JsonValue MakeMopEnvelope(const std::string& msg_id,
                          const std::string& channel,
                          const std::string& type,
                          const std::string& flight_id,
                          const std::string* edge_boot_id,
                          const std::int64_t* seq,
                          const std::string* ack_of,
                          JsonValue payload,
                          std::int64_t sent_monotonic_ns);

// Validates version/payload/digest; on success copies the payload into
// `payload_out`. Returns false with `*reason` set otherwise.
bool VerifyMopEnvelope(const JsonValue& envelope, JsonValue* payload_out,
                       std::string* reason);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_ENVELOPE_HPP
