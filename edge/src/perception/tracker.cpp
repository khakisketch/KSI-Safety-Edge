// See tracker.hpp for the association rules (A1-A10).

#include "ksi_safety/perception/tracker.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace ksi::safety {
namespace {

TrackBox BoxOf(const Detection& det) {
    TrackBox box;
    box.x_min = det.x_min;
    box.y_min = det.y_min;
    box.x_max = det.x_max;
    box.y_max = det.y_max;
    return box;
}

struct Candidate {
    float iou = 0.0F;
    std::uint64_t track_id = 0;
    std::size_t track_pos = 0;  // Index into tracks_.
    std::size_t det_idx = 0;    // Index into the input detections.
};

}  // namespace

bool IsValidTrackerConfig(const TrackerConfig& config) {
    return std::isfinite(config.iou_threshold) &&
           config.iou_threshold >= 0.0F && config.iou_threshold <= 1.0F &&
           config.max_age_frames >= 0 && config.min_hits >= 1;
}

float BoxIou(const TrackBox& a, const TrackBox& b) {
    const float a_w = a.x_max - a.x_min;
    const float a_h = a.y_max - a.y_min;
    const float b_w = b.x_max - b.x_min;
    const float b_h = b.y_max - b.y_min;
    if (!(a_w > 0.0F) || !(a_h > 0.0F) || !(b_w > 0.0F) || !(b_h > 0.0F)) {
        return 0.0F;  // Rule A7: degenerate boxes never overlap.
    }
    const float ix_min = a.x_min > b.x_min ? a.x_min : b.x_min;
    const float iy_min = a.y_min > b.y_min ? a.y_min : b.y_min;
    const float ix_max = a.x_max < b.x_max ? a.x_max : b.x_max;
    const float iy_max = a.y_max < b.y_max ? a.y_max : b.y_max;
    const float iw = ix_max - ix_min;
    const float ih = iy_max - iy_min;
    if (!(iw > 0.0F) || !(ih > 0.0F)) {
        return 0.0F;
    }
    const float inter = iw * ih;
    const float union_area = a_w * a_h + b_w * b_h - inter;
    if (!(union_area > 0.0F)) {
        return 0.0F;
    }
    return inter / union_area;
}

Tracker::Tracker(const TrackerConfig& config) : config_(config) {}

std::uint64_t Tracker::Epoch() const {
    return epoch_;
}

std::vector<Track> Tracker::Update(
    std::uint64_t received_mono_ns,
    const std::vector<Detection>& detections) {
    std::vector<Track> out;
    if (!IsValidTrackerConfig(config_)) {
        return out;  // Rule A10: fail-closed, no state change.
    }

    // Rule A1: per-class candidates at or above the configured threshold.
    std::vector<Candidate> candidates;
    for (std::size_t t = 0; t < tracks_.size(); ++t) {
        for (std::size_t d = 0; d < detections.size(); ++d) {
            if (tracks_[t].class_id != detections[d].class_id) {
                continue;
            }
            const float iou = BoxIou(tracks_[t].box, BoxOf(detections[d]));
            if (iou >= config_.iou_threshold) {
                Candidate c;
                c.iou = iou;
                c.track_id = tracks_[t].track_id;
                c.track_pos = t;
                c.det_idx = d;
                candidates.push_back(c);
            }
        }
    }
    // Descending IoU; ties: lower track_id, then lower detection index.
    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& l, const Candidate& r) {
                  if (l.iou != r.iou) {
                      return l.iou > r.iou;
                  }
                  if (l.track_id != r.track_id) {
                      return l.track_id < r.track_id;
                  }
                  return l.det_idx < r.det_idx;
              });

    std::vector<bool> track_matched(tracks_.size(), false);
    std::vector<bool> det_matched(detections.size(), false);
    for (const Candidate& c : candidates) {
        if (!track_matched[c.track_pos] && !det_matched[c.det_idx]) {
            track_matched[c.track_pos] = true;
            det_matched[c.det_idx] = true;
            Entry& e = tracks_[c.track_pos];
            e.box = BoxOf(detections[c.det_idx]);  // Rule A2.
            e.last_seen_ns = received_mono_ns;
            e.hits += 1;
            e.misses = 0;
        }
    }

    // Rules A2/A4: age every survivor; coast the unmatched.
    std::vector<Entry> survivors;
    survivors.reserve(tracks_.size() + detections.size());
    for (std::size_t t = 0; t < tracks_.size(); ++t) {
        Entry e = tracks_[t];
        e.age += 1;
        if (!track_matched[t]) {
            e.misses += 1;
        }
        if (e.misses <= config_.max_age_frames) {
            survivors.push_back(e);
        }
    }

    // Rule A3: unmatched detections start new tracks (input order => id order).
    for (std::size_t d = 0; d < detections.size(); ++d) {
        if (det_matched[d]) {
            continue;
        }
        Entry e;
        e.track_id = next_id_++;
        e.class_id = detections[d].class_id;
        e.box = BoxOf(detections[d]);
        e.hits = 1;
        e.age = 1;
        e.misses = 0;
        e.last_seen_ns = received_mono_ns;
        e.epoch = epoch_;
        survivors.push_back(e);
    }

    // Ascending track_id keeps both storage and output deterministic
    // (creation order already is, but the coast/new interleave is not).
    std::sort(survivors.begin(), survivors.end(),
              [](const Entry& l, const Entry& r) {
                  return l.track_id < r.track_id;
              });
    tracks_.swap(survivors);

    // Rule A6: report every live track with its confirmation flag.
    out.reserve(tracks_.size());
    for (const Entry& e : tracks_) {
        Track t;
        t.track_id = e.track_id;
        t.class_id = e.class_id;
        t.box = e.box;
        t.confirmed = (e.hits >= config_.min_hits);
        t.hits = e.hits;
        t.age = e.age;
        t.last_seen_ns = e.last_seen_ns;
        t.epoch = e.epoch;
        out.push_back(t);
    }
    return out;
}

void Tracker::Reset() {
    epoch_ += 1;
    tracks_.clear();
    // next_id_ intentionally untouched: ids are never reused.
}

}  // namespace ksi::safety
