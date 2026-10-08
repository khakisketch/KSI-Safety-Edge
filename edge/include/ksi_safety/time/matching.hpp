// Temporal matching: attach a quality-graded pose sample to a frame.
//
// The decision order is the user-mandated separation of time concerns:
//   (a) no samples            -> kNone / kNoSamples
//   (b) nearest receive match -> gap > max -> kNone / kOutsideMaxGap
//   (c) frame has no verified exposure (capture time) ->
//          kReceiveNearest / kCaptureTimeUnverified (never higher, no matter
//          how small the receive gap is: proximity is not capture alignment)
//   (d) ring has no verified source times ->
//          kReceiveNearest / kSourceTimeUnverified
//   (d2) verified source times exist but none share the frame exposure's
//          clock domain -> kReceiveNearest / kDomainMismatch (two individually
//          verified clocks are not comparable without a mapping)
//   (e) bracket by VERIFIED, SAME-DOMAIN source time: missing ->
//          kReceiveNearest / kNoBracket; interval > max ->
//          kReceiveNearest / kBracketTooWide; else ->
//          kSourceVerified / kOk with before/after filled.
// A receive-proximity result is NEVER returned as kSourceVerified.

#ifndef KSI_SAFETY_TIME_MATCHING_HPP
#define KSI_SAFETY_TIME_MATCHING_HPP

#include <cstdint>
#include <string>

#include "ksi_safety/time/provenance.hpp"
#include "ksi_safety/time/topic_ring.hpp"

namespace ksi::safety {

struct FrameContext {
    std::string flight_id;
    std::string boot_id;
    std::string stream_id;
    std::uint32_t frame_id = 0;
    std::int64_t receive_monotonic_ns = 0;
    bool has_exposure = false;
    Timestamp exposure;  // device capture time; counts only if verified
};

struct MatchPolicy {
    std::int64_t max_receive_gap_ns = 0;
    std::int64_t max_bracket_interval_ns = 0;
};

enum class PoseQuality {
    kNone,
    kReceiveNearest,
    kSourceVerified,
};

enum class PoseReason {
    kOk,
    kNoSamples,
    kOutsideMaxGap,
    kCaptureTimeUnverified,
    kSourceTimeUnverified,
    kDomainMismatch,
    kNoBracket,
    kBracketTooWide,
};

const char* ToString(PoseQuality quality);
const char* ToString(PoseReason reason);

template <typename T>
struct MatchResult {
    PoseQuality quality = PoseQuality::kNone;
    PoseReason reason = PoseReason::kNoSamples;
    const Sample<T>* nearest = nullptr;
    std::int64_t nearest_gap_ns = 0;
    const Sample<T>* before = nullptr;
    const Sample<T>* after = nullptr;
    std::int64_t bracket_interval_ns = 0;
};

// The ordered policy. Template-defined here because callers instantiate it
// per telemetry value type (currently PoseValue; per-topic rings replace the
// single ring when the PSDK adapter lands).
template <typename T>
MatchResult<T> MatchFrame(const FrameContext& ctx, const TopicRing<T>& ring,
                          const MatchPolicy& policy) {
    // (a) no samples.
    if (ring.empty()) {
        return MatchResult<T>();
    }
    // (b) nearest receive match.
    MatchResult<T> result;
    if (!ring.FindNearestReceive(ctx.receive_monotonic_ns, &result.nearest,
                                 &result.nearest_gap_ns)) {
        return MatchResult<T>();  // unreachable for non-empty rings; fail safe
    }
    if (result.nearest_gap_ns > policy.max_receive_gap_ns) {
        result.quality = PoseQuality::kNone;
        result.reason = PoseReason::kOutsideMaxGap;
        return result;
    }
    // (c) frame capture (exposure) time must itself be verified; receive
    // proximity alone never promotes above kReceiveNearest.
    if (!ctx.has_exposure || !ctx.exposure.verified) {
        result.quality = PoseQuality::kReceiveNearest;
        result.reason = PoseReason::kCaptureTimeUnverified;
        return result;
    }
    // (d)/(d2) the ring must hold verified source times AND at least one in
    // the frame exposure's own clock domain: two individually verified clocks
    // are not a common time axis.
    bool any_verified_source = false;
    bool same_domain_verified = false;
    for (std::size_t i = 0; i < ring.size(); ++i) {
        const Sample<T>& s = ring.At(i);
        if (s.has_source_time && s.source_time.verified) {
            any_verified_source = true;
            if (s.source_time.domain == ctx.exposure.domain) {
                same_domain_verified = true;
                break;
            }
        }
    }
    if (!any_verified_source) {
        result.quality = PoseQuality::kReceiveNearest;
        result.reason = PoseReason::kSourceTimeUnverified;
        return result;
    }
    if (!same_domain_verified) {
        result.quality = PoseQuality::kReceiveNearest;
        result.reason = PoseReason::kDomainMismatch;
        return result;
    }
    // (e) bracket the exposure time with VERIFIED SAME-DOMAIN source times
    // only. (TopicRing::FindBracketBySourceTime is mechanical - it accepts any
    // sample with a source time - so the verified/domain scan is done here.)
    const Sample<T>* before = nullptr;
    const Sample<T>* after = nullptr;
    for (std::size_t i = 0; i < ring.size(); ++i) {
        const Sample<T>& cand = ring.At(i);
        if (!cand.has_source_time || !cand.source_time.verified ||
            cand.source_time.domain != ctx.exposure.domain) {
            continue;
        }
        const std::int64_t src = cand.source_time.value_ns;
        if (src <= ctx.exposure.value_ns &&
            (before == nullptr || src > before->source_time.value_ns)) {
            before = &cand;
        }
        if (src >= ctx.exposure.value_ns &&
            (after == nullptr || src < after->source_time.value_ns)) {
            after = &cand;
        }
    }
    if (before == nullptr || after == nullptr) {
        result.quality = PoseQuality::kReceiveNearest;
        result.reason = PoseReason::kNoBracket;
        return result;
    }
    result.before = before;
    result.after = after;
    result.bracket_interval_ns =
        after->source_time.value_ns - before->source_time.value_ns;
    if (result.bracket_interval_ns > policy.max_bracket_interval_ns) {
        result.before = nullptr;  // keep receive-only shape on refusal
        result.after = nullptr;
        result.bracket_interval_ns = 0;
        result.quality = PoseQuality::kReceiveNearest;
        result.reason = PoseReason::kBracketTooWide;
        return result;
    }
    result.quality = PoseQuality::kSourceVerified;
    result.reason = PoseReason::kOk;
    return result;
}

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TIME_MATCHING_HPP
