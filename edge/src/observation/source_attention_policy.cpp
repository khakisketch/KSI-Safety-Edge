#include "ksi_safety/observation/source_attention_policy.hpp"

#include <limits>
#include <map>
#include <set>

namespace ksi::safety {
namespace {
bool UnsignedDecimal(const std::string& text, std::uint64_t* value) {
    if (text.empty() || text.size() > 19) return false;
    std::uint64_t parsed = 0;
    for (const char c : text) {
        if (c < '0' || c > '9') return false;
        const auto digit = static_cast<std::uint64_t>(c - '0');
        if (parsed > (std::numeric_limits<std::uint64_t>::max() - digit) / 10) return false;
        parsed = parsed * 10 + digit;
    }
    *value = parsed;
    return true;
}
}  // namespace

observation::PlannerConfig SourceAttentionPlannerConfig(const SourceAttentionPolicy& p) {
    observation::PlannerConfig config;
    config.max_targets = static_cast<std::uint32_t>(p.max_targets);
    config.max_jobs_per_tick = static_cast<std::uint32_t>(p.max_jobs_per_tick);
    config.max_bytes_per_tick = p.max_bytes_per_tick;
    config.repeat_capture_interval_ns = p.repeat_ns;
    config.target_lost_after_ns = p.lost_ns;
    config.max_wait_ns = p.wait_ns;
    config.retry_delay_ns = p.retry_ns;
    return config;
}

bool IsValidSourceAttentionPolicy(const SourceAttentionPolicy& p,
                                  std::string* error) {
    if (!p.enabled) {
        if (error != nullptr) error->clear();
        return true;
    }
    // Allocation/work caps, not field-tuned thresholds. No geometry/alert gate changes.
    if (p.class_id > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max()) ||
        p.max_targets == 0 || p.max_targets > 4096 ||
        p.max_jobs_per_tick == 0 || p.max_jobs_per_tick > 64 ||
        p.max_jobs_per_tick > p.max_targets || p.max_bytes_per_tick == 0 ||
        p.max_bytes_per_tick > 16 * 1024 * 1024 || p.repeat_ns <= 0 ||
        p.lost_ns <= 0 || p.wait_ns <= 0 || p.retry_ns <= 0) {
        if (error != nullptr) *error = "source attention requires finite positive explicit budgets and times";
        return false;
    }
    if (!observation::ValidatePlannerConfig(SourceAttentionPlannerConfig(p), error)) return false;
    if (error != nullptr) error->clear();
    return true;
}

bool ParseSourceAttentionPolicy(const std::string& text,
                                SourceAttentionPolicy* out, std::string* error) {
    const auto fail = [&](const std::string& reason) {
        if (out != nullptr) *out = SourceAttentionPolicy{};
        if (error != nullptr) *error = reason;
        return false;
    };
    if (out == nullptr || text.empty() || text.size() > 1024) return fail("invalid source attention policy");
    const std::set<std::string> keys = {"class", "targets", "jobs", "bytes", "repeat_ms", "lost_ms", "wait_ms", "retry_ms"};
    std::map<std::string, std::uint64_t> values;
    std::size_t start = 0;
    while (start < text.size()) {
        const auto end = text.find(',', start);
        const auto item = text.substr(start, end == std::string::npos ? end : end - start);
        const auto equals = item.find('=');
        if (equals == std::string::npos) return fail("source attention expects key=value pairs");
        const auto key = item.substr(0, equals);
        std::uint64_t number = 0;
        if (keys.count(key) == 0 || values.count(key) != 0 ||
            !UnsignedDecimal(item.substr(equals + 1), &number)) return fail("unknown, duplicate or invalid source attention key: " + key);
        values.emplace(key, number);
        if (end == std::string::npos) break;
        start = end + 1;
        if (start == text.size()) return fail("trailing source attention separator");
    }
    if (values.size() != keys.size()) return fail("all source attention policy keys are required");
    if (values["class"] > std::numeric_limits<std::uint32_t>::max()) return fail("class id overflow");
    for (const char* key : {"repeat_ms", "lost_ms", "wait_ms", "retry_ms"}) {
        if (values[key] > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max() / 1000000)) return fail("source attention time overflow");
    }
    if (values["targets"] > 4096 || values["jobs"] > 64 || values["bytes"] > 16 * 1024 * 1024) return fail("source attention resource cap exceeded");
    SourceAttentionPolicy p;
    p.enabled = true;
    p.class_id = static_cast<std::uint32_t>(values["class"]);
    p.max_targets = static_cast<std::size_t>(values["targets"]);
    p.max_jobs_per_tick = static_cast<std::size_t>(values["jobs"]);
    p.max_bytes_per_tick = static_cast<std::size_t>(values["bytes"]);
    p.repeat_ns = static_cast<std::int64_t>(values["repeat_ms"]) * 1000000;
    p.lost_ns = static_cast<std::int64_t>(values["lost_ms"]) * 1000000;
    p.wait_ns = static_cast<std::int64_t>(values["wait_ms"]) * 1000000;
    p.retry_ns = static_cast<std::int64_t>(values["retry_ms"]) * 1000000;
    std::string reason;
    if (!IsValidSourceAttentionPolicy(p, &reason)) return fail(reason);
    *out = p;
    if (error != nullptr) error->clear();
    return true;
}
}  // namespace ksi::safety
