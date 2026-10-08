// Deterministic IoU tracker v0 (host-tested, no inference here).
//
// Role: associate per-frame D-FINE `Detection`s into stable `Track`s whose
// (tracker_epoch, track_id) lineage feeds the risk engine's `TrackLineage`
// (see risk/engine.hpp). Episodes must never merge across epochs: `Reset()`
// bumps `tracker_epoch` and drops every track, and ids are never reused.
//
// Association rules (all thresholds come ONLY from `TrackerConfig`; no
// literals in the logic):
//   A1. Per-class greedy IoU matching: only pairs with the same `class_id`
//       and IoU >= `config.iou_threshold` are candidates. Candidates sort by
//       descending IoU; ties break by lower track_id, then lower detection
//       index (input order). The sorted list is consumed greedily: a pair is
//       matched iff both its track and its detection are still unmatched.
//   A2. Matched tracks adopt the detection box verbatim, set
//       `last_seen_ns` to the frame timestamp, increment `hits`, reset the
//       consecutive-miss counter, and increment `age`.
//   A3. Unmatched detections start NEW tracks with fresh monotonic ids
//       (counter starts at 1, never reused — including across `Reset()`).
//       New tracks have hits = 1, age = 1, last_seen_ns = frame timestamp.
//   A4. Unmatched tracks coast: the box is held, `age` increments, the
//       consecutive-miss counter increments, `hits`/`last_seen_ns` are
//       unchanged. A track with consecutive misses > `max_age_frames` is
//       removed and is NOT returned. So max_age_frames = N keeps a track
//       visible for exactly N missed frames; the (N+1)-th consecutive miss
//       drops it. max_age_frames = 0 removes a track on its first miss.
//   A5. Re-association while coasting keeps the SAME track_id (occlusion
//       case); `hits` resumes incrementing from its preserved value.
//   A6. Confirmation: `confirmed = (hits >= config.min_hits)`. `Update()`
//       returns EVERY live track — confirmed and unconfirmed — sorted by
//       ascending track_id; callers filter on `confirmed`. min_hits = 1
//       confirms every track immediately.
//   A7. IoU uses float32 arithmetic on xyxy boxes. A pair with a degenerate
//       box on either side (non-positive width or height, i.e. x_max <= x_min
//       or y_max <= y_min) has IoU 0 and therefore only matches when
//       iou_threshold == 0. Identical boxes have IoU exactly 1.
//   A8. `class_name` plays no role in association and is not stored; only
//       `class_id` scopes matching. Detection `score` is not stored either.
//   A9. Timestamps are caller-provided data: `last_seen_ns` copies the
//       matching frame's `received_mono_ns` verbatim. No monotonicity is
//       enforced and no clock is read.
//   A10. Invalid config (see `IsValidTrackerConfig`) is fail-closed: the
//       tracker holds no tracks and `Update()` returns empty, mirroring the
//       dfine module's invalid-config posture. Epoch counting still works.
//
// Determinism: no RNG, no wall clock, no iteration over unordered
// containers. Identical input sequences (config, detections per frame,
// timestamps) produce identical outputs, including track ids.
//
// Clean-room v0: no motion model (Kalman), no appearance features, no NMS,
// no global assignment optimisation — greedy IoU only. No I/O, standard
// library only, namespace `ksi::safety`.

#ifndef KSI_SAFETY_PERCEPTION_TRACKER_HPP
#define KSI_SAFETY_PERCEPTION_TRACKER_HPP

#include <cstdint>
#include <vector>

#include "ksi_safety/perception/detector.hpp"

namespace ksi::safety {

// Box in source-pixel xyxy coordinates (copied verbatim from Detection).
struct TrackBox {
    float x_min = 0.0F;
    float y_min = 0.0F;
    float x_max = 0.0F;
    float y_max = 0.0F;
};

struct TrackerConfig {
    // Minimum IoU for a track-detection pair to match (rule A1). Valid
    // range: finite, in [0, 1].
    float iou_threshold = 0.3F;
    // Coast lifetime: consecutive missed frames a track survives (rule A4).
    // Valid range: >= 0.
    int max_age_frames = 5;
    // Hits needed before a track reports confirmed (rule A6).
    // Valid range: >= 1.
    int min_hits = 3;
};

// True iff iou_threshold is finite in [0, 1], max_age_frames >= 0, and
// min_hits >= 1.
bool IsValidTrackerConfig(const TrackerConfig& config);

struct Track {
    std::uint64_t track_id = 0;  // 0 = invalid sentinel; live ids start at 1.
    std::uint32_t class_id = 0;
    TrackBox box;
    bool confirmed = false;  // hits >= config.min_hits (rule A6).
    int hits = 0;            // Lifetime matched frames (rule A2/A3/A5).
    int age = 0;             // Update() calls survived since creation (rule A2-A4).
    std::uint64_t last_seen_ns = 0;  // Frame timestamp of the last match.
    std::uint64_t epoch = 0;         // tracker_epoch at creation.
};

class Tracker {
  public:
    explicit Tracker(const TrackerConfig& config = TrackerConfig{});

    // Current tracker epoch. Starts at 1 (0 is the invalid sentinel, mirroring
    // track_id); `Reset()` increments it by exactly 1 (spec §7.2: the epoch
    // scopes frame/track validity, never merged across it).
    std::uint64_t Epoch() const;

    // Associates one frame's detections and returns every live track
    // (confirmed AND unconfirmed, rule A6), sorted by ascending track_id.
    // Fail-closed on invalid config: returns empty (rule A10).
    std::vector<Track> Update(std::uint64_t received_mono_ns,
                              const std::vector<Detection>& detections);

    // Explicit reset for stream/model switches: tracker_epoch += 1, all
    // tracks dropped, the id counter keeps counting (no reuse).
    void Reset();

  private:
    struct Entry {
        std::uint64_t track_id = 0;
        std::uint32_t class_id = 0;
        TrackBox box;
        int hits = 0;
        int age = 0;
        int misses = 0;  // Consecutive unmatched Update() calls.
        std::uint64_t last_seen_ns = 0;
        std::uint64_t epoch = 0;
    };

    TrackerConfig config_;
    std::uint64_t epoch_ = 1;
    std::uint64_t next_id_ = 1;
    std::vector<Entry> tracks_;  // Kept sorted by ascending track_id.
};

// Pure IoU helper (rule A7), exposed for tests.
float BoxIou(const TrackBox& a, const TrackBox& b);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_PERCEPTION_TRACKER_HPP
