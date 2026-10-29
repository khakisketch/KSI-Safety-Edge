// Host test for the bounded data-only source-ROI observation planner
// (edge/include/ksi_safety/observation/planner.hpp).
//
// Fixture is explicitly SIMULATED: hand-made candidates with caller clocks
// and view strings only. No PSDK, no device, no trained model, no RGB bytes
// anywhere (the planner never sees pixels). "Confirmed" below means the
// simulated tracker verdict flag, never a PPE/safety claim; "selected" means
// "capture this source ROI now", never safe/verified/Incident/Alert.

#include "ksi_safety/observation/planner.hpp"

#include <algorithm>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "support/check.hpp"

using ksi::safety::observation::CaptureJob;
using ksi::safety::observation::ObservationCandidate;
using ksi::safety::observation::ObservationPlanner;
using ksi::safety::observation::ObservationPriority;
using ksi::safety::observation::PlanDecision;
using ksi::safety::observation::PlanResult;
using ksi::safety::observation::PlannerConfig;
using ksi::safety::observation::SourceRoiReadiness;
using ksi::safety::observation::TrackKey;
using ksi::safety::observation::ValidatePlannerConfig;

namespace {

constexpr std::int64_t kMs = 1000000LL;

PlannerConfig TestConfig() {
    PlannerConfig config;
    config.max_targets = 8;
    config.max_jobs_per_tick = 2;
    config.max_bytes_per_tick = 1000;
    config.repeat_capture_interval_ns = 2000 * kMs;
    config.target_lost_after_ns = 1000 * kMs;
    config.max_wait_ns = 5000 * kMs;
    config.retry_delay_ns = 1000 * kMs;
    return config;
}

TrackKey Key(std::uint64_t track_id, std::uint64_t epoch = 7,
              std::int32_t class_id = 1) {
    TrackKey key;
    key.tracker_epoch = epoch;
    key.track_id = track_id;
    key.class_id = class_id;
    return key;
}

// SIMULATED fresh confirmed usable candidate (parent-claimed crop + cost).
ObservationCandidate Cand(std::uint64_t track_id, std::uint64_t cost = 100,
                          ObservationPriority priority =
                              ObservationPriority::kRoutine) {
    ObservationCandidate cand;
    cand.key = Key(track_id);
    cand.fresh_observed = true;
    cand.confirmed = true;
    cand.readiness = SourceRoiReadiness::kUsable;
    cand.estimated_cost_bytes = cost;
    cand.priority = priority;
    return cand;
}

bool Contains(const std::string& haystack, const std::string& needle) {
    return haystack.find(needle) != std::string::npos;
}

const PlanDecision* Find(const PlanResult& result, std::uint64_t track_id) {
    for (const PlanDecision& d : result.decisions) {
        if (d.key.track_id == track_id) return &d;
    }
    return nullptr;
}

bool ReasonHasNoVerificationWords(const std::string& reason) {
    return !Contains(reason, "verif") && !Contains(reason, "safe") &&
           !Contains(reason, "incident") && !Contains(reason, "alert");
}

}  // namespace

int main() {
    // ---- 1) config validation: positive, finite, capped, never repaired --
    {
        std::string error;
        CHECK(ValidatePlannerConfig(TestConfig(), &error), "test cfg valid");
        PlannerConfig bad = TestConfig();
        bad.max_targets = 0;
        CHECK(!ValidatePlannerConfig(bad, &error), "zero targets rejected");
        bad = TestConfig();
        bad.max_jobs_per_tick = 0;
        CHECK(!ValidatePlannerConfig(bad, &error), "zero jobs rejected");
        bad = TestConfig();
        bad.max_bytes_per_tick = 0;
        CHECK(!ValidatePlannerConfig(bad, &error), "zero bytes rejected");
        bad = TestConfig();
        bad.repeat_capture_interval_ns = 0;
        CHECK(!ValidatePlannerConfig(bad, &error), "zero cooldown rejected");
        bad = TestConfig();
        bad.target_lost_after_ns = -5;
        CHECK(!ValidatePlannerConfig(bad, &error), "negative ttl rejected");
        bad = TestConfig();
        bad.max_wait_ns = PlannerConfig::kMaxDurationNs + 1;
        CHECK(!ValidatePlannerConfig(bad, &error), "over-cap wait rejected");
        bad = TestConfig();
        bad.retry_delay_ns = PlannerConfig::kMaxDurationNs + 1;
        CHECK(!ValidatePlannerConfig(bad, &error), "over-cap retry rejected");
        bad = TestConfig();
        bad.max_targets = PlannerConfig::kMaxTargetsCeiling + 1;
        CHECK(!ValidatePlannerConfig(bad, &error), "over-cap targets rej");
        bad = TestConfig();
        bad.max_bytes_per_tick = PlannerConfig::kMaxBytesCeiling + 1;
        CHECK(!ValidatePlannerConfig(bad, &error), "over-cap bytes rejected");
        // Invalid config => planner refuses every tick, fail closed.
        ObservationPlanner broken(bad);
        CHECK(!broken.config_ok(), "broken config flagged");
        PlanResult r = broken.Plan(1000, "v1", {Cand(1)});
        CHECK(!r.ok && r.decisions.empty() && r.jobs.empty(),
              "bad config plans nothing");
    }

    // ---- 2) basic selection within budget; finite result -------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 10000 * kMs;
        PlanResult r = planner.Plan(t0, "view-1", {Cand(1, 400), Cand(2, 400)});
        CHECK(r.ok, "plan ok");
        CHECK(r.jobs.size() == 2, "both fit slots+bytes");
        CHECK(r.total_bytes == 800, "bytes summed");
        for (const PlanDecision& d : r.decisions) {
            CHECK(d.selected && d.reason == "selected", "both selected");
            CHECK(ReasonHasNoVerificationWords(d.reason), "no verify words");
        }
    }

    // ---- 3) repeat suppression: same tick, inside interval, after ----------
    {
        auto cfg = TestConfig();
        cfg.target_lost_after_ns = 3000 * kMs;  // isolate cooldown, not a lost return
        ObservationPlanner planner(cfg);
        const std::int64_t t0 = 20000 * kMs;
        PlanResult first = planner.Plan(t0, "view-1", {Cand(1, 100)});
        CHECK(first.ok && first.jobs.size() == 1, "first tick selects");
        // Same now, same candidates: suppressed, never double-charged.
        PlanResult same = planner.Plan(t0, "view-1", {Cand(1, 100)});
        CHECK(same.ok && same.jobs.empty(), "same-tick repeat suppressed");
        CHECK(Find(same, 1)->reason == "cooldown_repeat_suppressed",
              "cooldown reason names suppression");
        // Inside the interval: still suppressed (cooldown is last SELECTED
        // attempt, not a verification cache — no completion API exists).
        PlanResult inside =
            planner.Plan(t0 + 1999 * kMs, "view-1", {Cand(1, 100)});
        CHECK(inside.jobs.empty() &&
                  Find(inside, 1)->reason == "cooldown_repeat_suppressed",
              "inside interval suppressed");
        // After the interval with fresh+confirmed+usable: eligible again.
        PlanResult after =
            planner.Plan(t0 + 2000 * kMs, "view-1", {Cand(1, 100)});
        CHECK(after.jobs.size() == 1 &&
                  Find(after, 1)->reason == "selected",
              "after interval reselects when still fresh");
    }

    // ---- 4) elevated beats routine under slot pressure --------------------
    {
        ObservationPlanner planner(TestConfig());  // 2 slots
        const std::int64_t t0 = 30000 * kMs;
        std::vector<ObservationCandidate> cands = {
            Cand(1, 100, ObservationPriority::kRoutine),
            Cand(2, 100, ObservationPriority::kRoutine),
            Cand(3, 100, ObservationPriority::kElevated)};
        PlanResult r = planner.Plan(t0, "view-1", cands);
        CHECK(r.ok && r.jobs.size() == 2, "slots bound the jobs");
        CHECK(Find(r, 3)->selected, "elevated wins under pressure");
        CHECK(!Find(r, 2)->selected &&
                  Find(r, 2)->reason == "deferred_budget_slots",
              "newest routine deferred with slot reason");
        CHECK(Find(r, 1)->selected, "oldest routine keeps seniority");
    }

    // ---- 5) finite byte budget: split reasons, oversize, no overflow ------
    {
        ObservationPlanner planner(TestConfig());  // 1000 bytes, 2 slots
        planner.Plan(40000 * kMs, "view-1", {Cand(9, 100)});  // seed timeline
        const std::int64_t t1 = 40000 * kMs + 2000 * kMs;  // past cooldown
        // 600 + 600: first fits, second is byte-deferred (rank order).
        PlanResult r = planner.Plan(t1, "view-1", {Cand(1, 600), Cand(2, 600)});
        CHECK(r.jobs.size() == 1 && r.total_bytes == 600, "bytes bound jobs");
        CHECK(Find(r, 2)->reason == "deferred_budget_bytes",
              "byte pressure names bytes");
        // Oversize single cost: never selectable under this config.
        PlanResult big = planner.Plan(t1, "view-1", {Cand(3, 1001)});
        CHECK(big.jobs.empty() && Find(big, 3)->reason == "budget_oversize",
              "oversize cost fails closed");
        // UINT64_MAX cost: no wraparound, still just oversize.
        ObservationCandidate huge = Cand(4);
        huge.estimated_cost_bytes = UINT64_MAX;
        PlanResult wr = planner.Plan(t1, "view-1", {huge});
        CHECK(wr.jobs.empty() && wr.total_bytes == 0, "huge cost safe");
        CHECK(Find(wr, 4)->reason == "budget_oversize", "huge is oversize");
    }

    // ---- 6) confirmed + fresh gates ----------------------------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 50000 * kMs;
        ObservationCandidate unconfirmed = Cand(1);
        unconfirmed.confirmed = false;
        ObservationCandidate held = Cand(2);
        held.fresh_observed = false;  // tracker-held, no fresh evidence
        ObservationCandidate small = Cand(3);
        small.readiness = SourceRoiReadiness::kTooSmall;
        ObservationCandidate invalid = Cand(4);
        invalid.readiness = SourceRoiReadiness::kInvalid;
        ObservationCandidate zerocost = Cand(5);
        zerocost.estimated_cost_bytes = 0;
        PlanResult r = planner.Plan(t0, "view-1",
                                    {unconfirmed, held, small, invalid,
                                     zerocost});
        CHECK(r.ok && r.jobs.empty(), "gates select nothing");
        CHECK(Find(r, 1)->reason == "invalid_unconfirmed", "unconfirmed out");
        CHECK(Find(r, 2)->reason == "stale_not_fresh", "held box never taken");
        CHECK(Find(r, 3)->reason == "quality_insufficient" &&
                  Find(r, 4)->reason == "quality_insufficient",
              "crop unavailable is quality, never negative budget");
        CHECK(r.total_bytes == 0, "gated crops charge zero bytes");
        CHECK(Find(r, 5)->reason == "invalid_bad_cost", "zero cost invalid");
    }

    // ---- 7) last-held box is not selected right after a capture ------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 60000 * kMs;
        CHECK(planner.Plan(t0, "view-1", {Cand(1, 100)}).jobs.size() == 1,
              "captured");
        // Next frame the tracker only holds the box (no fresh observation):
        // must not reselect from memory, even past the cooldown.
        ObservationCandidate held = Cand(1, 100);
        held.fresh_observed = false;
        PlanResult r =
            planner.Plan(t0 + 3000 * kMs, "view-1", {held});
        CHECK(r.jobs.empty() && Find(r, 1)->reason == "stale_not_fresh",
              "no selecting the last-held box");
    }

    // ---- 8) missing / lost: grace retains, expiry drops, return is new ----
    {
        PlannerConfig cfg = TestConfig();
        cfg.repeat_capture_interval_ns = 100 * kMs;  // short cooldown
        ObservationPlanner planner(cfg);
        const std::int64_t t0 = 70000 * kMs;
        CHECK(planner.Plan(t0, "view-1", {Cand(1, 100)}).jobs.size() == 1,
              "selected");
        CHECK(planner.tracked_size() == 1, "lineage retained");
        // Absent but inside the lost grace: state kept.
        PlanResult gap =
            planner.Plan(t0 + 500 * kMs, "view-1", {});
        CHECK(gap.ok && gap.jobs.empty(), "empty tick plans nothing");
        CHECK(planner.tracked_size() == 1, "grace retains lineage");
        // Absent beyond the grace: dropped and forgotten.
        CHECK(planner.Plan(t0 + 500 * kMs + 1001 * kMs, "view-1", {}).ok,
              "drop tick ok");
        CHECK(planner.tracked_size() == 0, "lost lineage dropped");
        // Return after an actual gap: brand-new wait, needs fresh proof.
        ObservationCandidate stale_return = Cand(1, 100);
        stale_return.fresh_observed = false;
        PlanResult ret = planner.Plan(t0 + 3000 * kMs, "view-1",
                                      {stale_return});
        CHECK(ret.jobs.empty() && Find(ret, 1)->reason == "stale_not_fresh",
              "gap return without freshness is stale, not verified knowledge");
        PlanResult fresh_ret =
            planner.Plan(t0 + 3000 * kMs, "view-1", {Cand(1, 100)});
        CHECK(fresh_ret.jobs.size() == 1, "fresh return starts a new wait");
    }

    // ---- 9) starvation expiry closes UNKNOWN + quarantine blocks revive ----
    {
        PlannerConfig cfg = TestConfig();
        cfg.max_jobs_per_tick = 1;
        cfg.max_wait_ns = 3000 * kMs;
        cfg.retry_delay_ns = 2000 * kMs;
        cfg.target_lost_after_ns = 10000 * kMs;  // keep lineages while starving
        ObservationPlanner planner(cfg);
        const std::int64_t t0 = 80000 * kMs;
        // Track 1 selected; track 2 starts waiting under 1-slot pressure.
        PlanResult first =
            planner.Plan(t0, "view-1", {Cand(1, 100), Cand(2, 100)});
        CHECK(first.jobs.size() == 1 && Find(first, 1)->selected,
              "one slot, oldest wins");
        CHECK(Find(first, 2)->reason == "deferred_budget_slots",
              "track 2 deferred");
        // Keep starving track 2 with an elevated hog for two more ticks.
        planner.Plan(t0 + 1000 * kMs, "view-1",
                     {Cand(51, 100, ObservationPriority::kElevated),
                      Cand(2, 100)});
        planner.Plan(t0 + 2000 * kMs, "view-1",
                     {Cand(52, 100, ObservationPriority::kElevated),
                      Cand(2, 100)});
        // At +3000 track 2 waited >= max_wait: expired, closed UNKNOWN.
        PlanResult exp = planner.Plan(t0 + 3000 * kMs, "view-1", {Cand(2, 100)});
        const PlanDecision* d = Find(exp, 2);
        CHECK(d != nullptr && !d->selected && d->reason == "expired",
              "starved wait expires");
        CHECK(ReasonHasNoVerificationWords(d->reason), "expiry never safe");
        // Cooldown time is NOT starvation wait; only track 2 expires here.
        CHECK(planner.quarantine_size() == 1, "quarantine holds starved waiter");
        // Immediate reappearance inside quarantine: expired again, no revive.
        PlanResult revive =
            planner.Plan(t0 + 3500 * kMs, "view-1", {Cand(2, 100)});
        CHECK(!Find(revive, 2)->selected &&
                  Find(revive, 2)->reason == "expired",
              "no per-frame revive of expired target");
        // After the quarantine lapses: fresh wait allowed again.
        PlanResult back =
            planner.Plan(t0 + 5100 * kMs, "view-1", {Cand(2, 100)});
        CHECK(Find(back, 2)->selected, "post-quarantine fresh wait selects");
    }

    // ---- 10) view / lens / epoch / model / config reset --------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 90000 * kMs;
        CHECK(planner.Plan(t0, "flightA/bootA/streamA/lensA/modelA/cfgA",
                           {Cand(1, 100)})
                  .jobs.size() == 1,
              "selected under view A");
        // Same track under a new lens epoch: all prior state invalidated,
        // so no cooldown is inherited — but freshness is still required.
        PlanResult switched =
            planner.Plan(t0, "flightA/bootA/streamA/lensB/modelA/cfgA",
                         {Cand(1, 100)});
        CHECK(switched.ok && switched.jobs.size() == 1,
              "view change resets suppression, fresh still selects");
        CHECK(planner.last_view_id() == "flightA/bootA/streamA/lensB/modelA/cfgA",
              "view recorded");
        // A new timeline resets the clock guard (boot-epoch clocks restart).
        PlanResult rewound =
            planner.Plan(t0 - 5000 * kMs, "flightB/bootB/streamA/lensA/modelA/cfgA",
                         {Cand(1, 100)});
        CHECK(rewound.ok, "new view starts a new timeline");
        // Empty view is malformed: fail closed, keep the old view.
        PlanResult noview = planner.Plan(t0, "", {Cand(1, 100)});
        CHECK(!noview.ok, "empty view rejected");
        // Empty candidates on the CURRENT view only expire; view is kept.
        CHECK(planner.Plan(t0, "flightB/bootB/streamA/lensA/modelA/cfgA", {}).ok,
              "empty tick ok");
        CHECK(planner.last_view_id() == "flightB/bootB/streamA/lensA/modelA/cfgA",
              "no-frame tick never resets the view");
    }

    // ---- 11) clock rollback fails closed -----------------------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 100000 * kMs;
        CHECK(planner.Plan(t0, "view-1", {Cand(1, 100)}).jobs.size() == 1,
              "selected");
        PlanResult back = planner.Plan(t0 - 1, "view-1", {Cand(1, 100)});
        CHECK(!back.ok && back.error == "time_rollback" &&
                  back.decisions.empty() && back.jobs.empty(),
              "rollback rejected with zero mutation");
        CHECK(planner.last_now_ns() == t0, "clock guard unmoved");
        // Negative stamps are malformed too.
        PlanResult neg = planner.Plan(-1, "view-1", {Cand(1, 100)});
        CHECK(!neg.ok, "negative now rejected");
        CHECK(!planner.Advance(t0 - 1), "Advance rollback refused");
        CHECK(planner.Advance(t0), "Advance at same now is a no-op ok");
    }

    // ---- 12) malformed / duplicate keys fail closed -------------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 110000 * kMs;
        ObservationCandidate badclass = Cand(1);
        badclass.key.class_id = -5;
        ObservationCandidate badenum = Cand(2);
        badenum.readiness = static_cast<SourceRoiReadiness>(99);
        ObservationCandidate badprio = Cand(3);
        badprio.priority = static_cast<ObservationPriority>(42);
        // Duplicate key with DIFFERENT contents: every occurrence fails
        // closed (order-independent: no "first wins" ambiguity).
        ObservationCandidate dup_a = Cand(4, 100);
        ObservationCandidate dup_b = Cand(4, 100);
        dup_b.estimated_cost_bytes = 200;
        dup_b.fresh_observed = false;
        PlanResult r = planner.Plan(t0, "view-1",
                                    {badclass, badenum, badprio, dup_a, dup_b});
        CHECK(r.ok && r.jobs.empty(), "malformed selects nothing");
        CHECK(Find(r, 1)->reason == "invalid_bad_key", "bad class fails");
        CHECK(Find(r, 2)->reason == "invalid_bad_value", "bad enum fails");
        CHECK(Find(r, 3)->reason == "invalid_bad_value", "bad prio fails");
        CHECK(r.decisions.size() == 5, "one decision per occurrence");
        int dup_hits = 0;
        for (const PlanDecision& d : r.decisions) {
            if (d.key.track_id == 4) {
                CHECK(d.reason == "invalid_duplicate_key", "dup fails closed");
                ++dup_hits;
            }
        }
        CHECK(dup_hits == 2, "both duplicate occurrences reported");
        CHECK(planner.tracked_size() == 0, "malformed creates no state");
        auto reversed = planner.Plan(t0, "view-1", {dup_b, dup_a});
        CHECK(reversed.decisions.size() == 2 &&
              reversed.decisions.front().estimated_cost_bytes == 100 &&
              reversed.decisions.back().estimated_cost_bytes == 200,
              "duplicate refusals deterministic even with conflicting costs");
    }

    // ---- 13) deterministic regardless of candidate input order ---------------
    {
        ObservationPlanner left(TestConfig());
        ObservationPlanner right(TestConfig());
        const std::int64_t t0 = 120000 * kMs;
        std::vector<ObservationCandidate> fwd = {
            Cand(1, 300), Cand(2, 300),
            Cand(3, 300, ObservationPriority::kElevated), Cand(4, 300)};
        std::vector<ObservationCandidate> rev = fwd;
        std::reverse(rev.begin(), rev.end());
        PlanResult a = left.Plan(t0, "view-1", fwd);
        PlanResult b = right.Plan(t0, "view-1", rev);
        CHECK(a.jobs.size() == b.jobs.size(), "same job count");
        CHECK(a.decisions.size() == b.decisions.size(), "same decision count");
        for (std::size_t i = 0; i < a.decisions.size(); ++i) {
            CHECK(a.decisions[i].key == b.decisions[i].key &&
                      a.decisions[i].selected == b.decisions[i].selected &&
                      a.decisions[i].reason == b.decisions[i].reason,
                  "decisions input-order independent");
        }
        for (std::size_t i = 0; i < a.jobs.size(); ++i) {
            CHECK(a.jobs[i].key == b.jobs[i].key, "jobs input-order indep");
        }
    }

    // ---- 14) no frame deadline closure: same-now repeats expire nothing -----
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 130000 * kMs;
        planner.Plan(t0, "view-1", {Cand(1, 100), Cand(2, 100)});
        const std::string before = planner.Snapshot();
        // Same now, no time passing: state (waits, seniority) is untouched.
        for (int i = 0; i < 5; ++i) {
            PlanResult r = planner.Plan(t0, "view-1", {Cand(1, 100)});
            (void)r;  // cooldown path; must not consume waits/expiry
        }
        CHECK(planner.tracked_size() == 2, "no hidden deadline closure");
        CHECK(planner.Snapshot() == before, "same-now snapshot stable");
    }

    // ---- 15) Advance() expires/drops with no frames; never touches view -----
    {
        PlannerConfig cfg = TestConfig();
        cfg.max_wait_ns = 2000 * kMs;
        cfg.target_lost_after_ns = 10000 * kMs;  // retain, so waits can starve
        cfg.retry_delay_ns = 1000 * kMs;
        ObservationPlanner planner(cfg);
        const std::int64_t t0 = 140000 * kMs;
        CHECK(planner.Advance(t0), "Advance before any view is a safe no-op");
        planner.Plan(t0, "view-1", {Cand(1, 100), Cand(2, 100)});
        CHECK(planner.Advance(t0 + 4000 * kMs), "advance ok (cooldown + wait)");
        CHECK(planner.tracked_size() == 0, "waits expire via Advance");
        CHECK(planner.quarantine_size() == 2, "expiry quarantines both");
        CHECK(planner.Advance(t0 + 5000 * kMs), "advance ok");
        CHECK(planner.quarantine_size() == 0, "quarantine lapses via Advance");
        CHECK(planner.last_view_id() == "view-1", "Advance keeps the view");
        CHECK(planner.expiries() == 2, "expiry counter counts");
    }

    // ---- 16) finite memory: allocation cap fails closed ---------------------
    {
        PlannerConfig cfg = TestConfig();
        cfg.max_targets = 2;
        ObservationPlanner planner(cfg);
        const std::int64_t t0 = 150000 * kMs;
        PlanResult r =
            planner.Plan(t0, "view-1", {Cand(1, 100), Cand(2, 100), Cand(3, 100)});
        CHECK(r.jobs.size() == 2, "slots still bound jobs");
        const PlanDecision* d = Find(r, 3);
        CHECK(d != nullptr && !d->selected && d->reason == "state_full",
              "newcomer at capacity fails closed");
        CHECK(planner.tracked_size() == 2, "memory stays finite");
        // A known lineage still competes (no eviction of live waits).
        PlanResult again = planner.Plan(t0 + 3000 * kMs, "view-1",
                                        {Cand(1, 100), Cand(2, 100)});
        CHECK(again.jobs.size() == 2, "live lineages unaffected by the cap");
    }

    // ---- 17) tracker-epoch disambiguation -----------------------------------
    {
        ObservationPlanner planner(TestConfig());
        const std::int64_t t0 = 160000 * kMs;
        ObservationCandidate a = Cand(1, 100);
        ObservationCandidate b = Cand(1, 100);
        b.key.tracker_epoch = 8;  // same id, new tracker epoch: new lineage
        PlanResult r = planner.Plan(t0, "view-1", {a, b});
        CHECK(r.jobs.size() == 2, "epochs never merge lineages");
        CHECK(planner.tracked_size() == 2, "separate lineages tracked");
    }

    // ---- 18) snapshot determinism -------------------------------------------
    {
        auto drive = []() {
            ObservationPlanner planner(TestConfig());
            const std::int64_t t0 = 170000 * kMs;
            planner.Plan(t0, "view-1", {Cand(1, 100), Cand(2, 100)});
            planner.Plan(t0 + 500 * kMs, "view-1", {Cand(2, 100)});
            return planner.Snapshot();
        };
        CHECK(drive() == drive(), "deterministic snapshot");
        CHECK(!Contains(drive(), "incident") && !Contains(drive(), "alert") &&
                  !Contains(drive(), "safe") && !Contains(drive(), "verif"),
              "snapshot carries no verification vocabulary");
    }

    // Lost continuity is evaluated before fresh presence; held tracks do not
    // refresh it. A short wait deadline must never bypass a long cooldown.
    {
        auto cfg = TestConfig();
        cfg.max_wait_ns = 100 * kMs;
        cfg.repeat_capture_interval_ns = 5000 * kMs;
        cfg.target_lost_after_ns = 1000 * kMs;
        ObservationPlanner planner(cfg);
        const auto t0 = 200000 * kMs;
        CHECK(planner.Plan(t0, "v", {Cand(1)}).jobs.size() == 1, "initial selection");
        for (int i = 1; i < 10; ++i) {
            auto r = planner.Plan(t0 + i * 100 * kMs, "v", {Cand(1)});
            CHECK(r.jobs.empty() && Find(r, 1)->reason == "cooldown_repeat_suppressed",
                  "short max_wait cannot erase cooldown");
        }
        auto held = Cand(1);
        held.fresh_observed = false;
        planner.Plan(t0 + 1500 * kMs, "v", {held});
        planner.Plan(t0 + 2000 * kMs, "v", {held});
        CHECK(planner.tracked_size() == 0, "held presence cannot retain lineage");
        CHECK(planner.Plan(t0 + 2100 * kMs, "v", {Cand(1)}).jobs.size() == 1,
              "fresh return after real gap starts new observation");
        auto too_many = std::vector<ObservationCandidate>(PlannerConfig::kMaxTargetsCeiling + 1, Cand(1));
        const auto before = planner.Snapshot();
        CHECK(!planner.Plan(t0 + 2200 * kMs, "v", too_many).ok, "input allocation cap");
        CHECK(planner.Snapshot() == before, "over-cap input does not mutate state");
    }
    {
        auto cfg = TestConfig();
        cfg.max_targets = 1;
        cfg.max_jobs_per_tick = 1;
        ObservationPlanner a(cfg), b(cfg);
        auto low = Cand(1);
        auto high = Cand(2, 100, ObservationPriority::kElevated);
        CHECK(a.Plan(0, "v", {low, high}).jobs.front().key == high.key,
              "priority governs state admission");
        CHECK(b.Plan(0, "v", {high, low}).jobs.front().key == high.key,
              "state admission independent of vector order");
    }
    if (edge_test::failures == 0) {
        std::printf("observation_planner: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
