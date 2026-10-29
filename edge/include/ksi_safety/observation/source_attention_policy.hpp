// Trusted-installer opt-in policy for original-source ROI attention.
// Resource limits are engineering caps, NOT calibrated risk/PPE thresholds.
#ifndef KSI_SAFETY_OBSERVATION_SOURCE_ATTENTION_POLICY_HPP
#define KSI_SAFETY_OBSERVATION_SOURCE_ATTENTION_POLICY_HPP

#include <cstddef>
#include <cstdint>
#include <string>

#include "ksi_safety/observation/planner.hpp"

namespace ksi::safety {
struct SourceAttentionPolicy {
    bool enabled = false;
    std::uint32_t class_id = 0;
    std::size_t max_targets = 0;
    std::size_t max_jobs_per_tick = 0;
    std::size_t max_bytes_per_tick = 0;
    std::int64_t repeat_ns = 0;
    std::int64_t lost_ns = 0;
    std::int64_t wait_ns = 0;
    std::int64_t retry_ns = 0;
};

// Exact required keys, any order, no defaults silently enabling a model:
// class=0,targets=64,jobs=1,bytes=524288,repeat_ms=5000,lost_ms=1000,wait_ms=2000,retry_ms=500
// Numbers above are examples for a SIMULATED test, not Manifold performance promises.
bool ParseSourceAttentionPolicy(const std::string& text,
                                SourceAttentionPolicy* out, std::string* error);
bool IsValidSourceAttentionPolicy(const SourceAttentionPolicy& policy,
                                  std::string* error);
observation::PlannerConfig SourceAttentionPlannerConfig(const SourceAttentionPolicy& policy);
}  // namespace ksi::safety
#endif
