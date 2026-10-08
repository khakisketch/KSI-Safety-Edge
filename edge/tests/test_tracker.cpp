// tracker: deterministic per-class greedy IoU association — same-id
// continuity, threshold boundary (>=), coast + re-association, removal +
// id non-reuse, class separation, min_hits gating, tie-break order,
// determinism, Reset epoch semantics, empty input, invalid-config posture,
// and a DecodeDfine -> tracker integration case.

#include "ksi_safety/perception/tracker.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

#include "ksi_safety/perception/dfine.hpp"
#include "support/check.hpp"

using ksi::safety::BoxIou;
using ksi::safety::DecodeDfine;
using ksi::safety::Detection;
using ksi::safety::DfineConfig;
using ksi::safety::IsValidTrackerConfig;
using ksi::safety::Track;
using ksi::safety::Tracker;
using ksi::safety::TrackerConfig;

namespace {

constexpr std::uint64_t kMs = 1000000ULL;

Detection Det(float x_min, float y_min, float x_max, float y_max,
              std::uint32_t class_id = 0) {
    Detection d;
    d.x_min = x_min;
    d.y_min = y_min;
    d.x_max = x_max;
    d.y_max = y_max;
    d.score = 0.9F;
    d.class_id = class_id;
    return d;
}

TrackerConfig Cfg(float iou, int max_age, int min_hits) {
    TrackerConfig cfg;
    cfg.iou_threshold = iou;
    cfg.max_age_frames = max_age;
    cfg.min_hits = min_hits;
    return cfg;
}

std::string Describe(const Track& t) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%llu|%u|%.3f|%.3f|%.3f|%.3f|%d|%d|%d|%llu|%llu",
                  static_cast<unsigned long long>(t.track_id), t.class_id,
                  static_cast<double>(t.box.x_min),
                  static_cast<double>(t.box.y_min),
                  static_cast<double>(t.box.x_max),
                  static_cast<double>(t.box.y_max), t.confirmed ? 1 : 0,
                  t.hits, t.age,
                  static_cast<unsigned long long>(t.last_seen_ns),
                  static_cast<unsigned long long>(t.epoch));
    return std::string(buf);
}

}  // namespace

int main() {
    // ---- 1) same-id association across frames ----
    {
        Tracker tr(Cfg(0.3F, 5, 1));
        CHECK(tr.Epoch() == 1, "initial epoch is 1");
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f1.size() == 1, "first frame: one track");
        const std::uint64_t id = f1[0].track_id;
        CHECK(id != 0, "live id is never the 0 sentinel");
        CHECK(f1[0].confirmed, "min_hits=1 confirms immediately");
        CHECK(f1[0].hits == 1 && f1[0].age == 1, "new track hits/age are 1");
        CHECK(f1[0].last_seen_ns == 1000 * kMs, "last_seen is frame time");
        CHECK(f1[0].epoch == 1, "track carries creation epoch");
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(11, 10, 21, 20)});
        CHECK(f2.size() == 1 && f2[0].track_id == id, "same id, small shift");
        CHECK(f2[0].box.x_min == 11.0F && f2[0].box.x_max == 21.0F,
              "match adopts the new box");
        CHECK(f2[0].hits == 2 && f2[0].age == 2, "hits/age advance on match");
        CHECK(f2[0].last_seen_ns == 1001 * kMs, "last_seen advances");
        const std::vector<Track> f3 =
            tr.Update(1002 * kMs, {Det(12, 11, 22, 21)});
        CHECK(f3.size() == 1 && f3[0].track_id == id, "id stable third frame");
    }
    // ---- 2) IoU threshold boundary: >= matches, below does not ----
    {
        // A=[0,0,3,3] vs B=[1,0,4,3]: inter=6, union=12, IoU exactly 0.5.
        CHECK(BoxIou({0, 0, 3, 3}, {1, 0, 4, 3}) == 0.5F, "boundary IoU is 0.5");
        Tracker at(Cfg(0.5F, 5, 1));
        const std::vector<Track> f1 =
            at.Update(1000 * kMs, {Det(0, 0, 3, 3)});
        const std::uint64_t id = f1[0].track_id;
        const std::vector<Track> f2 =
            at.Update(1001 * kMs, {Det(1, 0, 4, 3)});
        CHECK(f2.size() == 1 && f2[0].track_id == id,
              "IoU == threshold still matches (>=)");
        Tracker above(Cfg(0.51F, 5, 1));
        const std::vector<Track> g1 =
            above.Update(1000 * kMs, {Det(0, 0, 3, 3)});
        const std::vector<Track> g2 =
            above.Update(1001 * kMs, {Det(1, 0, 4, 3)});
        CHECK(g2.size() == 2, "IoU below threshold starts a new track");
        if (g2.size() == 2) {
            CHECK(g2[0].track_id == g1[0].track_id, "old track coasts");
            CHECK(g2[1].track_id != g1[0].track_id, "shifted box is a new id");
        }
    }
    // ---- 3) occlusion coast + re-association keeps the id ----
    {
        Tracker tr(Cfg(0.3F, 2, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        const std::uint64_t id = f1[0].track_id;
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, std::vector<Detection>{});
        CHECK(f2.size() == 1 && f2[0].track_id == id, "1-miss coast same id");
        CHECK(f2[0].box.x_min == 10.0F && f2[0].box.x_max == 20.0F,
              "coast holds the box");
        CHECK(f2[0].hits == 1, "coast does not add hits");
        CHECK(f2[0].last_seen_ns == 1000 * kMs, "coast keeps last_seen");
        const std::vector<Track> f3 =
            tr.Update(1002 * kMs, std::vector<Detection>{});
        CHECK(f3.size() == 1 && f3[0].track_id == id, "2-miss coast same id");
        const std::vector<Track> f4 =
            tr.Update(1003 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f4.size() == 1 && f4[0].track_id == id,
              "re-association keeps the SAME id");
        CHECK(f4[0].hits == 2, "hits resume after the gap");
        CHECK(f4[0].last_seen_ns == 1003 * kMs, "last_seen jumps to re-match");
    }
    // ---- 4) removal after max_age + next detection gets a NEW id ----
    {
        Tracker tr(Cfg(0.3F, 1, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        const std::uint64_t id = f1[0].track_id;
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, std::vector<Detection>{});
        CHECK(f2.size() == 1 && f2[0].track_id == id,
              "max_age=1 survives exactly one miss");
        const std::vector<Track> f3 =
            tr.Update(1002 * kMs, std::vector<Detection>{});
        CHECK(f3.empty(), "second consecutive miss removes the track");
        const std::vector<Track> f4 =
            tr.Update(1003 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f4.size() == 1, "returning box creates a track");
        if (!f4.empty()) {
            CHECK(f4[0].track_id != id, "removed id is NEVER reused");
            CHECK(f4[0].hits == 1, "re-created track restarts at hits=1");
        }
    }
    // ---- 5) class separation: no cross-class match ----
    {
        Tracker tr(Cfg(0.1F, 5, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20, 0),
                                   Det(10, 10, 20, 20, 1)});
        CHECK(f1.size() == 2, "identical boxes, classes split into two ids");
        const std::uint64_t id0 = f1[0].track_id;
        const std::uint64_t id1 = f1[1].track_id;
        CHECK(id0 != id1, "class tracks get distinct ids");
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(11, 10, 21, 20, 0),
                                   Det(11, 10, 21, 20, 1)});
        CHECK(f2.size() == 2, "no cross-class merge on overlap");
        if (f2.size() == 2) {
            CHECK(f2[0].track_id == id0 && f2[0].class_id == 0,
                  "class 0 continuity");
            CHECK(f2[1].track_id == id1 && f2[1].class_id == 1,
                  "class 1 continuity");
        }
        // One class missing: only that class coasts, the other still matches.
        const std::vector<Track> f3 =
            tr.Update(1002 * kMs, {Det(12, 10, 22, 20, 1)});
        CHECK(f3.size() == 2, "missing class coasts, present class matches");
        if (f3.size() == 2) {
            CHECK(f3[0].track_id == id0 && f3[0].hits == 2,
                  "absent class held without new hits");
            CHECK(f3[1].track_id == id1 && f3[1].hits == 3,
                  "present class advances");
        }
    }
    // ---- 6) min_hits gating: Update returns unconfirmed tracks too ----
    {
        Tracker tr(Cfg(0.3F, 5, 3));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f1.size() == 1, "unconfirmed tracks ARE returned");
        CHECK(!f1[0].confirmed && f1[0].hits == 1, "hits=1 < min_hits=3");
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f2.size() == 1 && !f2[0].confirmed && f2[0].hits == 2,
              "hits=2 still unconfirmed, same id");
        const std::vector<Track> f3 =
            tr.Update(1002 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f3.size() == 1 && f3[0].confirmed && f3[0].hits == 3,
              "hits=3 confirms");
    }
    // ---- 7) greedy tie-break: equal IoU -> lower track_id wins ----
    {
        Tracker tr(Cfg(0.1F, 5, 1));
        // Two disjoint tracks; one detection overlaps each at IoU 0.2.
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs,
                      {Det(0, 0, 2, 2, 0), Det(4, 0, 6, 2, 0)});
        CHECK(f1.size() == 2, "two seed tracks");
        const std::uint64_t low = f1[0].track_id;
        const std::uint64_t high = f1[1].track_id;
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(1, 0, 5, 2, 0)});
        CHECK(f2.size() == 2, "one match + one coast");
        bool low_matched = false;
        bool high_coasted = false;
        for (std::size_t i = 0; i < f2.size(); ++i) {
            if (f2[i].track_id == low && f2[i].hits == 2) {
                low_matched = true;
            }
            if (f2[i].track_id == high && f2[i].hits == 1) {
                high_coasted = true;
            }
        }
        CHECK(low_matched, "tied IoU: lower track_id wins the detection");
        CHECK(high_coasted, "loser coasts unchanged");
    }
    // ---- 8) determinism: identical runs -> identical ids/boxes ----
    {
        auto run = []() {
            Tracker tr(Cfg(0.3F, 2, 2));
            std::vector<std::string> log;
            const std::vector<std::vector<Detection>> frames = {
                {Det(10, 10, 20, 20, 0), Det(50, 50, 60, 60, 1)},
                {Det(11, 10, 21, 20, 0), Det(50, 50, 60, 60, 1)},
                {},
                {Det(12, 10, 22, 20, 0)},
                {Det(30, 30, 40, 40, 0)},
                {},
                {},
                {},
            };
            std::uint64_t t = 1000 * kMs;
            for (std::size_t f = 0; f < frames.size(); ++f) {
                const std::vector<Track> out = tr.Update(t, frames[f]);
                t += kMs;
                for (std::size_t i = 0; i < out.size(); ++i) {
                    log.push_back(Describe(out[i]));
                }
                log.push_back("---");
            }
            return log;
        };
        const std::vector<std::string> a = run();
        const std::vector<std::string> b = run();
        CHECK(!a.empty(), "determinism script emits output");
        CHECK(a.size() == b.size(), "deterministic output length");
        if (a.size() == b.size()) {
            for (std::size_t i = 0; i < a.size(); ++i) {
                CHECK(a[i] == b[i], "deterministic output bytes");
            }
        }
    }
    // ---- 9) Reset(): epoch bump, fresh state, id non-reuse ----
    {
        Tracker tr(Cfg(0.3F, 5, 1));
        CHECK(tr.Epoch() == 1, "epoch starts at 1");
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        const std::uint64_t id = f1[0].track_id;
        tr.Reset();
        CHECK(tr.Epoch() == 2, "Reset bumps the epoch by 1");
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f2.size() == 1, "post-reset detection starts a track");
        if (!f2.empty()) {
            CHECK(f2[0].track_id != id, "ids are never reused across Reset");
            CHECK(f2[0].hits == 1 && f2[0].age == 1, "post-reset state fresh");
            CHECK(f2[0].epoch == 2, "new track carries the new epoch");
        }
        tr.Reset();
        CHECK(tr.Epoch() == 3, "Reset increments again, never merges epochs");
        CHECK(tr.Update(1002 * kMs, std::vector<Detection>{}).empty(),
              "reset state holds nothing to coast");
    }
    // ---- 10) empty-input handling ----
    {
        Tracker tr(Cfg(0.3F, 5, 1));
        CHECK(tr.Update(1000 * kMs, std::vector<Detection>{}).empty(),
              "empty on a fresh tracker stays empty");
        CHECK(tr.Epoch() == 1, "empty frames do not move the epoch");
    }
    // ---- 11) invalid config is fail-closed ----
    {
        CHECK(IsValidTrackerConfig(TrackerConfig{}), "documented defaults valid");
        TrackerConfig bad = TrackerConfig{};
        bad.iou_threshold = 2.0F;
        CHECK(!IsValidTrackerConfig(bad), "threshold > 1 invalid");
        bad = TrackerConfig{};
        bad.max_age_frames = -1;
        CHECK(!IsValidTrackerConfig(bad), "negative max_age invalid");
        bad = TrackerConfig{};
        bad.min_hits = 0;
        CHECK(!IsValidTrackerConfig(bad), "min_hits 0 invalid");
        TrackerConfig bad_thr = TrackerConfig{};
        bad_thr.iou_threshold = 2.0F;
        Tracker tr(bad_thr);
        CHECK(tr.Update(1000 * kMs, {Det(10, 10, 20, 20)}).empty(),
              "invalid config: no tracks, never partial");
        tr.Reset();
        CHECK(tr.Epoch() == 2, "epoch still counts under invalid config");
        CHECK(tr.Update(1001 * kMs, {Det(10, 10, 20, 20)}).empty(),
              "still empty after reset");
    }
    // ---- 12) integration: DecodeDfine output keeps its id ----
    {
        DfineConfig dcfg;
        dcfg.num_classes = 2;
        dcfg.num_queries = 2;
        dcfg.score_threshold = 0.5F;
        // q0: class 0 at sigmoid(3)=0.953; q1: all below the floor.
        const float logits[4] = {3.0F, -3.0F, -3.0F, -3.0F};
        // q0 box: cxcywh (0.25, 0.5, 0.5, 0.5) on 100x100.
        const float boxes[8] = {0.25F, 0.5F, 0.5F, 0.5F,
                                0.9F, 0.9F, 0.05F, 0.05F};
        const std::vector<Detection> dets =
            DecodeDfine(logits, boxes, 100, 100, dcfg);
        CHECK(dets.size() == 1, "constructed tensors yield one detection");
        Tracker tr(Cfg(0.3F, 5, 1));
        const std::vector<Track> f1 = tr.Update(1000 * kMs, dets);
        CHECK(f1.size() == 1, "decode output starts a track");
        const std::vector<Track> f2 = tr.Update(1001 * kMs, dets);
        CHECK(f2.size() == 1 && f1.size() == 1 &&
                  f2[0].track_id == f1[0].track_id,
              "identical decode output keeps the id");
        if (f1.size() == 1 && f2.size() == 1) {
            CHECK(f2[0].class_id == dets[0].class_id, "class flows through");
            CHECK(f2[0].box.x_min == dets[0].x_min &&
                      f2[0].box.y_max == dets[0].y_max,
                  "decode box flows through");
        }
    }
    // ---- 13) runner-flagged gaps pinned: max_age=0, degenerate boxes,
    // exact first id, NaN/inf config ----
    {
        Tracker tr(Cfg(0.3F, 0, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f1.size() == 1, "max_age=0: track exists while matched");
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, std::vector<Detection>{});
        CHECK(f2.empty(), "max_age=0: removed on the FIRST miss");
    }
    {
        CHECK(BoxIou({10, 10, 10, 20}, {10, 10, 20, 20}) == 0.0F,
              "zero-width box has IoU 0 vs a valid box");
        CHECK(BoxIou({10, 10, 20, 20}, {10, 10, 20, 20}) == 1.0F,
              "identical valid boxes have IoU exactly 1");
        Tracker tr(Cfg(0.3F, 5, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 10, 20)});
        const std::uint64_t id = f1[0].track_id;
        const std::vector<Track> f2 =
            tr.Update(1001 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f2.size() == 2, "degenerate seed never matches a valid box");
        if (f2.size() == 2) {
            CHECK(f2[0].track_id == id, "degenerate track coasts");
            CHECK(f2[1].track_id != id, "valid box starts a new id");
        }
    }
    {
        Tracker tr(Cfg(0.3F, 5, 1));
        const std::vector<Track> f1 =
            tr.Update(1000 * kMs, {Det(10, 10, 20, 20)});
        CHECK(f1.size() == 1 && f1[0].track_id == 1,
              "first id is exactly 1 (0 stays a sentinel)");
        TrackerConfig nan_cfg = TrackerConfig{};
        nan_cfg.iou_threshold = std::nanf("");
        CHECK(!IsValidTrackerConfig(nan_cfg), "NaN threshold invalid");
        TrackerConfig inf_cfg = TrackerConfig{};
        inf_cfg.iou_threshold = std::numeric_limits<float>::infinity();
        CHECK(!IsValidTrackerConfig(inf_cfg), "inf threshold invalid");
    }
    if (edge_test::failures == 0) {
        std::printf("tracker: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
