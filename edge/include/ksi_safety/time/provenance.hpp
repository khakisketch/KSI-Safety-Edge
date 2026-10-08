// Timestamp provenance: which clock domains are trusted, and for what kind.
//
// A Timestamp is only `verified=true` when its (domain, kind) pair was
// explicitly verified in the ClockRegistry. Device timestamps are NEVER
// auto-promoted: Make() cannot upgrade kind, and verifying one kind never
// verifies the other kind of the same domain.

#ifndef KSI_SAFETY_TIME_PROVENANCE_HPP
#define KSI_SAFETY_TIME_PROVENANCE_HPP

#include <cstdint>
#include <map>
#include <string>
#include <utility>

namespace ksi::safety {

enum class TimeSourceKind {
    kDeviceSensor,  // timestamp produced by the device sensor clock
    kHostReceive,   // timestamp taken on host receive (monotonic)
};

struct Timestamp {
    std::int64_t value_ns = 0;
    TimeSourceKind kind = TimeSourceKind::kHostReceive;
    std::string domain;
    bool verified = false;
};

class ClockRegistry {
public:
    // Record that (domain, kind) is backed by evidence_ref (e.g. a
    // calibration or PSDK documentation reference). Verifying one kind never
    // verifies the other kind of the same domain.
    void Verify(const std::string& domain, TimeSourceKind kind,
                const std::string& evidence_ref);

    bool IsVerified(const std::string& domain, TimeSourceKind kind) const;

    // Build a timestamp. verified=true ONLY if (domain, kind) was verified.
    // kind is preserved as passed: verifying a host-receive domain still
    // yields kHostReceive, never kDeviceSensor.
    Timestamp Make(const std::string& domain, TimeSourceKind kind,
                   std::int64_t value_ns) const;

private:
    using Key = std::pair<std::string, TimeSourceKind>;
    // Presence in the map IS the verification; the evidence string is
    // retained for auditability.
    std::map<Key, std::string> verified_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TIME_PROVENANCE_HPP
