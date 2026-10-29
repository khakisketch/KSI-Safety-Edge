// Bounded data-only source-ROI observation planner implementation.
//
// Pure caller-clock logic: every comparison is formed as (now - stamp) so no
// addition can overflow (rollback is rejected first, so now >= stamp for all
// retained stamps within a view). No clock reads, no RNG, no I/O.

#include "ksi_safety/observation/planner.hpp"

#include <cstdio>
#include <algorithm>
#include <numeric>

namespace ksi::safety::observation {
namespace {

bool PositiveFiniteDuration(std::int64_t value) {
    return value > 0 && value <= PlannerConfig::kMaxDurationNs;
}

std::string KeyText(const TrackKey& key) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), "e%llu/t%llu/c%d",
                  static_cast<unsigned long long>(key.tracker_epoch),
                  static_cast<unsigned long long>(key.track_id),
                  static_cast<int>(key.class_id));
    return std::string(buf);
}

}  // namespace

bool ValidatePlannerConfig(const PlannerConfig& config, std::string* error) {
    auto fail = [&](const std::string& reason) {
        if (error != nullptr) *error = reason;
        return false;
    };
    if (config.max_targets == 0 ||
        config.max_targets > PlannerConfig::kMaxTargetsCeiling) {
        return fail("bad_max_targets");
    }
    if (config.max_jobs_per_tick == 0 ||
        config.max_jobs_per_tick > PlannerConfig::kMaxJobsCeiling) {
        return fail("bad_max_jobs_per_tick");
    }
    if (config.max_bytes_per_tick == 0 ||
        config.max_bytes_per_tick > PlannerConfig::kMaxBytesCeiling) {
        return fail("bad_max_bytes_per_tick");
    }
    if (!PositiveFiniteDuration(config.repeat_capture_interval_ns)) {
        return fail("bad_repeat_capture_interval_ns");
    }
    if (!PositiveFiniteDuration(config.target_lost_after_ns)) {
        return fail("bad_target_lost_after_ns");
    }
    if (!PositiveFiniteDuration(config.max_wait_ns)) {
        return fail("bad_max_wait_ns");
    }
    if (!PositiveFiniteDuration(config.retry_delay_ns)) {
        return fail("bad_retry_delay_ns");
    }
    if (error != nullptr) error->clear();
    return true;
}

ObservationPlanner::ObservationPlanner(const PlannerConfig& config)
    : config_(config) {
    config_ok_ = ValidatePlannerConfig(config_, &config_error_);
}

bool ObservationPlanner::EnumValid(SourceRoiReadiness readiness) {
    return readiness == SourceRoiReadiness::kUsable ||
           readiness == SourceRoiReadiness::kTooSmall ||
           readiness == SourceRoiReadiness::kInvalid;
}

bool ObservationPlanner::EnumValid(ObservationPriority priority) {
    return priority == ObservationPriority::kRoutine ||
           priority == ObservationPriority::kElevated;
}

void ObservationPlanner::ResetForView(const std::string& view_id,
                                      std::int64_t now_ns) {
    tracked_.clear();
    quarantine_.clear();
    last_view_id_ = view_id;
    last_now_ns_ = now_ns;
    has_view_ = true;
}

void ObservationPlanner::InsertQuarantine(const TrackKey& key,
                                          std::int64_t now_ns) {
    const TrackKeyLess less;
    for (auto& entry : quarantine_) {
        if (entry.first == key) {
            entry.second = now_ns;
            return;
        }
    }
    if (quarantine_.size() >= config_.max_targets) {
        // Allocation guard full: evict the oldest quarantine entry
        // (ties -> smallest key) so memory stays finite. Deterministic.
        std::size_t victim = 0;
        for (std::size_t i = 1; i < quarantine_.size(); ++i) {
            if (quarantine_[i].second < quarantine_[victim].second ||
                (quarantine_[i].second == quarantine_[victim].second &&
                 less(quarantine_[i].first, quarantine_[victim].first))) {
                victim = i;
            }
        }
        quarantine_[victim] = std::make_pair(key, now_ns);
        return;
    }
    quarantine_.push_back(std::make_pair(key, now_ns));
}

void ObservationPlanner::ExpireTimeBased(std::int64_t now_ns,
                                         std::uint64_t* expired_out,
                                         std::uint64_t* dropped_out) {
    std::uint64_t expired = 0;
    std::uint64_t dropped = 0;
    // 1) Drop lineages absent beyond the lost grace (missed targets are
    //    forgotten; a later return starts a brand-new wait).
    {
        std::vector<std::pair<TrackKey, TrackState>> kept;
        kept.reserve(tracked_.size());
        for (const auto& entry : tracked_) {
            if (now_ns - entry.second.last_seen_ns >
                config_.target_lost_after_ns) {
                ++dropped;
            } else {
                kept.push_back(entry);
            }
        }
        tracked_.swap(kept);
    }
    // 2) Purge lapsed quarantines (quarantine active while
    //    now - expired_at < retry_delay_ns).
    {
        std::vector<std::pair<TrackKey, std::int64_t>> kept;
        kept.reserve(quarantine_.size());
        for (const auto& entry : quarantine_) {
            if (now_ns - entry.second < config_.retry_delay_ns) {
                kept.push_back(entry);
            }
        }
        quarantine_.swap(kept);
    }
    // 3) Expire starved waits into quarantine (closed UNKNOWN, never safe).
    {
        std::vector<std::pair<TrackKey, TrackState>> kept;
        kept.reserve(tracked_.size());
        for (const auto& entry : tracked_) {
            // Suppression is not a waiting job. Never let wait expiry erase
            // the repeat interval (especially when max_wait < repeat).
            const auto& state = entry.second;
            const auto elapsed = now_ns - state.wait_start_ns;
            const auto cooldown = state.last_selected_ns >= 0
                ? config_.repeat_capture_interval_ns : 0;
            if (elapsed >= cooldown && elapsed - cooldown >= config_.max_wait_ns) {
                InsertQuarantine(entry.first, now_ns);
                ++expired;
            } else {
                kept.push_back(entry);
            }
        }
        tracked_.swap(kept);
    }
    drops_ += dropped;
    expiries_ += expired;
    if (expired_out != nullptr) *expired_out = expired;
    if (dropped_out != nullptr) *dropped_out = dropped;
}

PlanResult ObservationPlanner::Plan(
    std::int64_t now_ns, const std::string& view_id,
    const std::vector<ObservationCandidate>& candidates) {
    PlanResult result;
    if (!config_ok_) {
        result.error = "bad_config:" + config_error_;
        return result;
    }
    if (now_ns < 0) {
        result.error = "bad_now";
        return result;
    }
    if (view_id.empty()) {
        result.error = "bad_view_id";
        return result;
    }
    if (candidates.size() > PlannerConfig::kMaxTargetsCeiling) {
        result.error = "candidate_input_cap_exceeded";
        return result;
    }
    if (has_view_ && view_id == last_view_id_) {
        if (now_ns < last_now_ns_) {
            // Time rollback within one view: fail closed, mutate nothing.
            result.error = "time_rollback";
            return result;
        }
    } else {
        // New timeline: any view change invalidates ALL prior state.
        ResetForView(view_id, now_ns);
    }
    last_now_ns_ = now_ns;
    ++plans_;

    const TrackKeyLess less;
    // ---- duplicate detection (input-order independent outcome: every
    // occurrence of a repeated key fails closed) ----
    std::vector<std::size_t> key_counts(candidates.size(), 0);
    for (std::size_t i = 0; i < candidates.size(); ++i) {
        std::size_t count = 0;
        for (std::size_t j = 0; j < candidates.size(); ++j) {
            if (candidates[j].key == candidates[i].key) ++count;
        }
        key_counts[i] = count;
    }

    // ---- presence refresh + time-based expiry/drop (uses retained stamps;
    // now >= every retained stamp: same-view monotonicity was enforced
    // above, and a view change cleared all stamps) ----
    // Expire BEFORE refreshing: a fresh return after a long gap is a new
    // observation, not continuity. Held/invalid input cannot extend presence.
    ExpireTimeBased(now_ns, nullptr, nullptr);
    for (std::size_t i = 0; i < candidates.size(); ++i) {
        if (key_counts[i] != 1 || !candidates[i].fresh_observed ||
            !candidates[i].confirmed || !EnumValid(candidates[i].readiness) ||
            !EnumValid(candidates[i].priority)) continue;
        for (auto& entry : tracked_) {
            if (entry.first == candidates[i].key) {
                entry.second.last_seen_ns = now_ns;
                break;
            }
        }
    }

    auto find_tracked = [&](const TrackKey& key) -> TrackState* {
        for (auto& entry : tracked_) {
            if (entry.first == key) return &entry.second;
        }
        return nullptr;
    };
    auto quarantined = [&](const TrackKey& key) -> bool {
        for (const auto& entry : quarantine_) {
            if (entry.first == key &&
                now_ns - entry.second < config_.retry_delay_ns) {
                return true;
            }
        }
        return false;
    };

    struct Unique {
        ObservationCandidate candidate;
    };
    std::vector<Unique> uniques;
    uniques.reserve(candidates.size());
    std::vector<PlanDecision> decisions;
    decisions.reserve(candidates.size());

    // First pass: gates that need no ranking. Duplicates and malformed
    // inputs are decided immediately; survivors go to the ranked budget.
    std::vector<std::size_t> eligible;  // indices into uniques
    // Admission at the state cap must obey priority/key, not vector order.
    std::vector<std::size_t> admission(candidates.size());
    std::iota(admission.begin(), admission.end(), 0);
    std::stable_sort(admission.begin(), admission.end(), [&](std::size_t a, std::size_t b) {
        if (candidates[a].priority != candidates[b].priority)
            return candidates[a].priority > candidates[b].priority;
        return less(candidates[a].key, candidates[b].key);
    });
    for (const auto i : admission) {
        const ObservationCandidate& cand = candidates[i];
        PlanDecision decided;
        decided.key = cand.key;
        decided.estimated_cost_bytes = cand.estimated_cost_bytes;
        if (key_counts[i] != 1) {
            decided.selected = false;
            decided.reason = kReasonInvalidDuplicate;
            decisions.push_back(decided);
            continue;
        }
        if (cand.key.class_id < 0 || cand.key.tracker_epoch == 0 || cand.key.track_id == 0) {
            decided.selected = false;
            decided.reason = kReasonInvalidBadKey;
            decisions.push_back(decided);
            continue;
        }
        if (!EnumValid(cand.readiness) || !EnumValid(cand.priority)) {
            decided.selected = false;
            decided.reason = kReasonInvalidBadValue;
            decisions.push_back(decided);
            continue;
        }
        if (!cand.confirmed) {
            decided.selected = false;
            decided.reason = kReasonInvalidUnconfirmed;
            decisions.push_back(decided);
            continue;
        }
        if (!cand.fresh_observed) {
            // Held/missed box on this tick: never select last-held pixels.
            decided.selected = false;
            decided.reason = kReasonStale;
            decisions.push_back(decided);
            continue;
        }
        if (cand.readiness != SourceRoiReadiness::kUsable) {
            // Crop unavailable/too small: quality gate only, budget
            // untouched, cost ignored (never negative, never charged).
            decided.selected = false;
            decided.reason = kReasonQualityInsufficient;
            decisions.push_back(decided);
            continue;
        }
        if (cand.estimated_cost_bytes == 0) {
            decided.selected = false;
            decided.reason = kReasonInvalidBadCost;
            decisions.push_back(decided);
            continue;
        }
        if (quarantined(cand.key)) {
            // Wait-expired lineage still inside its quarantine: report
            // expired without creating new wait state (no per-frame revive).
            decided.selected = false;
            decided.reason = kReasonExpired;
            decisions.push_back(decided);
            continue;
        }
        TrackState* state = find_tracked(cand.key);
        if (state != nullptr && state->last_selected_ns >= 0 &&
            now_ns - state->last_selected_ns <
                config_.repeat_capture_interval_ns) {
            // Cooldown anchored on the last SELECTED attempt (never on a
            // verification outcome: this unit performs no verification).
            decided.selected = false;
            decided.reason = kReasonCooldown;
            decisions.push_back(decided);
            continue;
        }
        if (cand.estimated_cost_bytes > config_.max_bytes_per_tick) {
            // Can never fit this tick under the current config.
            decided.selected = false;
            decided.reason = kReasonBudgetOversize;
            decisions.push_back(decided);
            continue;
        }
        {
            const std::int64_t wait_start =
                (state != nullptr) ? state->wait_start_ns : now_ns;
            const auto elapsed = now_ns - wait_start;
            const auto cooldown = (state != nullptr && state->last_selected_ns >= 0)
                ? config_.repeat_capture_interval_ns : 0;
            if (elapsed >= cooldown && elapsed - cooldown >= config_.max_wait_ns) {
                // Starved: close UNKNOWN (never safe), quarantine the
                // lineage so it cannot immediately revive next frame.
                if (state != nullptr) {
                    // Erase from tracked_ (linear, bounded).
                    std::vector<std::pair<TrackKey, TrackState>> kept;
                    kept.reserve(tracked_.size());
                    for (const auto& entry : tracked_) {
                        if (entry.first != cand.key) kept.push_back(entry);
                    }
                    tracked_.swap(kept);
                }
                InsertQuarantine(cand.key, now_ns);
                ++expiries_;
                decided.selected = false;
                decided.reason = kReasonExpired;
                decisions.push_back(decided);
                continue;
            }
        }
        if (state == nullptr &&
            tracked_.size() >= config_.max_targets) {
            // Allocation guard: finite memory, fail closed for newcomers.
            decided.selected = false;
            decided.reason = kReasonStateFull;
            decisions.push_back(decided);
            continue;
        }
        // Survivor: ensure wait state exists BEFORE ranking so seniority is
        // well-defined (first_seen == now for brand-new lineages).
        if (state == nullptr) {
            TrackState fresh;
            fresh.wait_start_ns = now_ns;
            fresh.last_seen_ns = now_ns;
            fresh.last_selected_ns = -1;
            tracked_.push_back(std::make_pair(cand.key, fresh));
        }
        uniques.push_back(Unique{cand});
        eligible.push_back(uniques.size() - 1);
    }

    // ---- deterministic ranking: elevated first, oldest wait, stable key ----
    std::vector<Ranked> order;
    order.reserve(eligible.size());
    for (std::size_t e : eligible) {
        Ranked r;
        r.index = e;
        r.priority = uniques[e].candidate.priority;
        r.key = uniques[e].candidate.key;
        for (const auto& entry : tracked_) {
            if (entry.first == r.key) {
                r.wait_start_ns = entry.second.wait_start_ns;
                break;
            }
        }
        order.push_back(r);
    }
    // Insertion sort: deterministic, no <algorithm> ordering surprises on
    // equal elements (strict weak ordering holds via the key tiebreak, but
    // insertion sort is stable regardless).
    for (std::size_t i = 1; i < order.size(); ++i) {
        Ranked tmp = order[i];
        std::size_t j = i;
        auto before = [&](const Ranked& a, const Ranked& b) {
            if (a.priority != b.priority) return a.priority > b.priority;
            if (a.wait_start_ns != b.wait_start_ns)
                return a.wait_start_ns < b.wait_start_ns;
            return less(a.key, b.key);
        };
        while (j > 0 && before(tmp, order[j - 1])) {
            order[j] = order[j - 1];
            --j;
        }
        order[j] = tmp;
    }

    // ---- greedy budget in rank order (first-fit; determinism over use) ----
    std::uint64_t total = 0;
    std::uint32_t selected_count = 0;
    std::vector<bool> is_selected(uniques.size(), false);
    std::vector<std::string> defer_reason(uniques.size());
    for (const Ranked& r : order) {
        const std::uint64_t cost =
            uniques[r.index].candidate.estimated_cost_bytes;
        if (selected_count >= config_.max_jobs_per_tick) {
            defer_reason[r.index] = kReasonDeferredSlots;
            continue;
        }
        // Overflow-safe: cost <= max_bytes was enforced above, so
        // (max - cost) cannot underflow.
        if (total > config_.max_bytes_per_tick - cost) {
            defer_reason[r.index] = kReasonDeferredBytes;
            continue;
        }
        is_selected[r.index] = true;
        total += cost;
        ++selected_count;
    }

    // ---- commit selections (charge budget, anchor cooldown, restart wait) --
    for (std::size_t u = 0; u < uniques.size(); ++u) {
        const ObservationCandidate& cand = uniques[u].candidate;
        PlanDecision decided;
        decided.key = cand.key;
        decided.estimated_cost_bytes = cand.estimated_cost_bytes;
        if (is_selected[u]) {
            decided.selected = true;
            decided.reason = kReasonSelected;
            for (auto& entry : tracked_) {
                if (entry.first == cand.key) {
                    entry.second.last_selected_ns = now_ns;
                    entry.second.wait_start_ns = now_ns;  // restart seniority
                    entry.second.last_seen_ns = now_ns;
                    break;
                }
            }
        } else {
            decided.selected = false;
            decided.reason = defer_reason[u];
        }
        decisions.push_back(decided);
    }
    // Jobs follow RANK order (not input order): deterministic selection order
    // independent of how the parent packed the candidate vector.
    for (const Ranked& r : order) {
        if (!is_selected[r.index]) continue;
        const ObservationCandidate& cand = uniques[r.index].candidate;
        CaptureJob job;
        job.key = cand.key;
        job.priority = cand.priority;
        job.estimated_cost_bytes = cand.estimated_cost_bytes;
        result.jobs.push_back(job);
        ++selections_;
    }

    // ---- input-order independent output: sort decisions by key ----
    for (std::size_t i = 1; i < decisions.size(); ++i) {
        PlanDecision tmp = decisions[i];
        std::size_t j = i;
        while (j > 0 && (less(tmp.key, decisions[j - 1].key) ||
               (tmp.key == decisions[j - 1].key &&
                tmp.estimated_cost_bytes < decisions[j - 1].estimated_cost_bytes))) {
            decisions[j] = decisions[j - 1];
            --j;
        }
        decisions[j] = tmp;
    }
    result.decisions.swap(decisions);
    result.total_bytes = total;
    result.ok = true;
    return result;
}

bool ObservationPlanner::Advance(std::int64_t now_ns) {
    if (!config_ok_ || now_ns < 0) return false;
    if (!has_view_) return true;  // no timeline yet: pure no-op, never an error
    if (now_ns < last_now_ns_) return false;  // rollback: fail closed
    last_now_ns_ = now_ns;
    ExpireTimeBased(now_ns, nullptr, nullptr);
    return true;
}

std::size_t ObservationPlanner::tracked_size() const {
    return tracked_.size();
}

std::size_t ObservationPlanner::quarantine_size() const {
    return quarantine_.size();
}

std::string ObservationPlanner::Snapshot() const {
    std::string out = "view=";
    out += has_view_ ? last_view_id_ : "(none)";
    char buf[128];
    std::snprintf(buf, sizeof(buf), " now=%lld tracked=%llu quarantine=%llu",
                  static_cast<long long>(last_now_ns_),
                  static_cast<unsigned long long>(tracked_.size()),
                  static_cast<unsigned long long>(quarantine_.size()));
    out += buf;
    // Sorted copies for a deterministic dump.
    std::vector<std::pair<TrackKey, TrackState>> t = tracked_;
    const TrackKeyLess less;
    for (std::size_t i = 1; i < t.size(); ++i) {
        auto tmp = t[i];
        std::size_t j = i;
        while (j > 0 && less(tmp.first, t[j - 1].first)) {
            t[j] = t[j - 1];
            --j;
        }
        t[j] = tmp;
    }
    for (const auto& entry : t) {
        std::snprintf(
            buf, sizeof(buf), " | %s wait=%lld sel=%lld",
            KeyText(entry.first).c_str(),
            static_cast<long long>(entry.second.wait_start_ns),
            static_cast<long long>(entry.second.last_selected_ns));
        out += buf;
    }
    return out;
}

}  // namespace ksi::safety::observation
