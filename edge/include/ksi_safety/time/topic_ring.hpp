// Per-topic sample ring: dedup, rollback detection, receive-nearest and
// source-time bracket lookups over a bounded window.
//
// Semantics:
// - Push: a sample whose sample_id equals the most recent sample's id is a
//   duplicate (kDuplicate, not stored). A sample whose received.value_ns is
//   strictly less than the previous sample's is a clock rollback
//   (kClockRollback, not stored). Otherwise the sample is recorded; when the
//   ring is at capacity the oldest sample is dropped (documented overwrite).
// - FindNearestReceive: smallest |received - target| wins; ties resolve to
//   the NEWEST sample (the scan runs newest-first with a strict less-than
//   comparison, so an equally good older sample never displaces the newest).
// - FindBracketBySourceTime: predecessor/successor by source_time among
//   samples that HAVE source times (has_source_time). Verification tiers are
//   NOT checked here; MatchFrame() applies the verified-source policy on top.
//   A bracket exists only when both sides exist and
//   before.source_time <= target <= after.source_time; otherwise false.
// - Returned pointers alias ring storage and stay valid across later
//   Push/pop operations at the ends (std::deque guarantee) but are
//   invalidated if the aliased sample itself is evicted or the ring dies.

#ifndef KSI_SAFETY_TIME_TOPIC_RING_HPP
#define KSI_SAFETY_TIME_TOPIC_RING_HPP

#include <cstddef>
#include <cstdint>
#include <deque>
#include <stdexcept>

#include "ksi_safety/time/provenance.hpp"

namespace ksi::safety {

template <typename T>
struct Sample {
    std::uint64_t sample_id = 0;
    std::uint64_t seq = 0;
    Timestamp received;  // kHostReceive
    bool has_source_time = false;
    Timestamp source_time;  // kDeviceSensor, verified only if registry said so
    T value{};
};

enum class PushVerdict {
    kRecorded,
    kDuplicate,
    kClockRollback,
};

template <typename T>
class TopicRing {
public:
    explicit TopicRing(std::size_t capacity) : capacity_(capacity) {
        if (capacity_ == 0) {
            throw std::invalid_argument("topic ring requires non-zero capacity");
        }
    }

    PushVerdict Push(const Sample<T>& sample) {
        if (!samples_.empty()) {
            if (sample.sample_id == samples_.back().sample_id) {
                return PushVerdict::kDuplicate;
            }
            if (sample.received.value_ns < samples_.back().received.value_ns) {
                return PushVerdict::kClockRollback;
            }
        }
        if (samples_.size() == capacity_) {
            samples_.pop_front();  // oldest dropped (documented overwrite)
        }
        samples_.push_back(sample);
        return PushVerdict::kRecorded;
    }

    // Nearest sample by host-receive timestamp. gap_ns is |received - target|.
    bool FindNearestReceive(std::int64_t target_ns, const Sample<T>** out,
                            std::int64_t* gap_ns) const {
        if (samples_.empty() || out == nullptr || gap_ns == nullptr) {
            return false;
        }
        const Sample<T>* best = nullptr;
        std::int64_t best_gap = 0;
        // Newest-first with strict `<`: ties keep the newest sample.
        for (std::size_t i = samples_.size(); i > 0; --i) {
            const Sample<T>& cand = samples_[i - 1];
            const std::int64_t diff = cand.received.value_ns - target_ns;
            const std::int64_t gap =
                diff >= 0 ? diff : (diff == INT64_MIN ? INT64_MAX : -diff);
            if (best == nullptr || gap < best_gap) {
                best = &cand;
                best_gap = gap;
            }
        }
        *out = best;
        *gap_ns = best_gap;
        return true;
    }

    // Predecessor/successor by source_time (samples with source times only).
    bool FindBracketBySourceTime(std::int64_t target_ns,
                                 const Sample<T>** before,
                                 const Sample<T>** after) const {
        if (before == nullptr || after == nullptr) {
            return false;
        }
        const Sample<T>* pred = nullptr;
        const Sample<T>* succ = nullptr;
        for (const Sample<T>& cand : samples_) {
            if (!cand.has_source_time) {
                continue;
            }
            const std::int64_t src = cand.source_time.value_ns;
            if (src <= target_ns &&
                (pred == nullptr || src > pred->source_time.value_ns)) {
                pred = &cand;
            }
            if (src >= target_ns &&
                (succ == nullptr || src < succ->source_time.value_ns)) {
                succ = &cand;
            }
        }
        if (pred == nullptr || succ == nullptr) {
            return false;
        }
        *before = pred;
        *after = succ;
        return true;
    }

    std::size_t size() const { return samples_.size(); }
    bool empty() const { return samples_.empty(); }
    std::size_t capacity() const { return capacity_; }
    // Oldest-first element access (index 0 == oldest). For policy scans.
    const Sample<T>& At(std::size_t index) const { return samples_.at(index); }

private:
    std::size_t capacity_;
    std::deque<Sample<T>> samples_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TIME_TOPIC_RING_HPP
