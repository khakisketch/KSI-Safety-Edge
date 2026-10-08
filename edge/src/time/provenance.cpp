#include "ksi_safety/time/provenance.hpp"

namespace ksi::safety {

void ClockRegistry::Verify(const std::string& domain, TimeSourceKind kind,
                           const std::string& evidence_ref) {
    verified_[Key(domain, kind)] = evidence_ref;
}

bool ClockRegistry::IsVerified(const std::string& domain,
                               TimeSourceKind kind) const {
    return verified_.find(Key(domain, kind)) != verified_.end();
}

Timestamp ClockRegistry::Make(const std::string& domain, TimeSourceKind kind,
                              std::int64_t value_ns) const {
    Timestamp t;
    t.value_ns = value_ns;
    t.kind = kind;  // never upgraded: the caller declares the kind, the
                    // registry only attests whether it was verified.
    t.domain = domain;
    t.verified = IsVerified(domain, kind);
    return t;
}

}  // namespace ksi::safety
