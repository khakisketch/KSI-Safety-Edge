// Unit A context ring: bounded volatile retention + truthful windows.

#include "ksi_safety/observation/context_ring.hpp"

#include <cstdint>
#include <limits>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"

namespace ksi::safety {

bool IsValidContextRingConfig(const ContextRingConfig& config) {
    if (config.max_frames == 0) return false;
    if (config.max_bytes == 0) return false;
    if (config.window_deadline_ns <= 0) return false;
    if (config.pre_frames > config.max_frames) return false;
    if (config.post_frames > config.max_frames) return false;
    return true;
}

ContextRing::ContextRing(ContextRingConfig config, std::string flight_id,
                         std::string edge_boot_id)
    : config_(config),
      flight_id_(std::move(flight_id)),
      edge_boot_id_(std::move(edge_boot_id)) {
    valid_ = IsValidContextRingConfig(config_);
    if (!valid_) error_ = "invalid context ring config (finite bounds required)";
}

namespace {

void AddMissing(CaptureWindow* window, const std::string& reason) {
    for (const auto& have : window->missing_reasons) {
        if (have == reason) return;
    }
    window->missing_reasons.push_back(reason);
}

WindowFrameRef RefFrom(const RingFrame& frame) {
    WindowFrameRef ref;
    ref.tick_index = frame.tick_index;
    ref.frame_id = frame.frame_id;
    ref.receive_mono_ns = frame.receive_mono_ns;
    ref.has_detection = frame.has_detection;
    ref.present = true;
    return ref;
}

}  // namespace

void ContextRing::Push(RingFrame frame) {
    if (!valid_) return;
    ++pushed_frames_;
    // Volatile overflow overwrites the OLDEST retained frame (never sealed
    // bytes — those live in the EvidenceStore, which has no eviction).
    while (!frames_.empty() &&
           (frames_.size() >= config_.max_frames ||
            retained_bytes_ + frame.bytes.size() > config_.max_bytes)) {
        retained_bytes_ -= frames_.front().bytes.size();
        if (!has_eviction_floor_ ||
            frames_.front().tick_index < eviction_floor_tick_) {
            eviction_floor_tick_ = frames_.front().tick_index;
            has_eviction_floor_ = true;
        }
        frames_.erase(frames_.begin());
        ++evicted_frames_;
    }
    retained_bytes_ += frame.bytes.size();
    frames_.push_back(std::move(frame));
    AttachPostFrames(frames_.back());
    MaybeCloseSatisfied();
}

const RingFrame* ContextRing::FindFrame(std::uint64_t tick_index) const {
    for (const auto& frame : frames_) {
        if (frame.tick_index == tick_index) return &frame;
    }
    return nullptr;
}

const RingFrame* ContextRing::FindRetainedFrame(
    std::uint64_t tick_index) const {
    return FindFrame(tick_index);
}

bool ContextRing::BeginWindow(std::uint64_t trigger_tick, std::string* error) {
    if (!valid_) {
        if (error != nullptr) *error = error_;
        return false;
    }
    const RingFrame* trigger = FindFrame(trigger_tick);
    if (trigger == nullptr) {
        if (error != nullptr) *error = "trigger tick is not retained";
        return false;
    }
    for (const auto& window : windows_) {
        if (window.trigger_tick == trigger_tick) {
            if (error != nullptr) *error = "window already open for trigger";
            return false;
        }
    }
    CaptureWindow window;
    window.window_id = Uuid5(MopProtocol::Namespace(),
                             "win|" + flight_id_ + "|" + edge_boot_id_ + "|" +
                                 std::to_string(trigger_tick));
    window.trigger_tick = trigger_tick;
    window.trigger_frame_id = trigger->frame_id;
    window.trigger_recv_ns = trigger->receive_mono_ns;
    // Predecessors: exact tick predecessors wanted (input gaps named).
    for (std::size_t i = config_.pre_frames; i > 0; --i) {
        const std::uint64_t wanted =
            trigger_tick >= i ? trigger_tick - i : 0;
        const bool underflow = trigger_tick < i;
        if (!underflow) {
            if (const RingFrame* prev = FindFrame(wanted)) {
                window.before.push_back(RefFrom(*prev));
                continue;
            }
        }
        WindowFrameRef missing;
        missing.tick_index = underflow ? 0 : wanted;
        missing.present = false;
        window.before.push_back(missing);
        if (underflow) {
            AddMissing(&window, kGapPrehistory);
        } else if (has_eviction_floor_ && wanted >= eviction_floor_tick_) {
            // Wanted tick at/after the eviction floor but not retained: the
            // ring overwrote history this window needed.
            AddMissing(&window, kGapRingEvicted);
        } else {
            AddMissing(&window, kGapInputGap);
        }
    }
    window.during = RefFrom(*trigger);
    window.wanted_frames =
        config_.pre_frames + 1 + config_.post_frames;
    windows_.push_back(std::move(window));
    return true;
}

void ContextRing::AttachPostFrames(const RingFrame& frame) {
    for (auto& window : windows_) {
        if (window.closed) continue;
        if (frame.tick_index <= window.trigger_tick) continue;
        const std::uint64_t offset = frame.tick_index - window.trigger_tick;
        if (offset > config_.post_frames || offset == 0) continue;
        // Fill in tick order; a skipped tick leaves a named hole.
        for (std::uint64_t want = window.after.size() + 1; want < offset;
             ++want) {
            WindowFrameRef missing;
            missing.tick_index = window.trigger_tick + want;
            missing.present = false;
            window.after.push_back(missing);
            AddMissing(&window, kGapInputGap);
        }
        window.after.push_back(RefFrom(frame));
    }
}

void ContextRing::MaybeCloseSatisfied() {
    for (auto& window : windows_) {
        if (window.closed) continue;
        if (window.after.size() >= config_.post_frames) {
            CloseWindow(&window, "post_complete");
            pending_closed_.push_back(window.window_id);
        }
    }
}

void ContextRing::CloseWindow(CaptureWindow* window,
                              const std::string& reason) {
    window->closed = true;
    window->close_reason = reason;
    if (reason != "post_complete") {
        if (reason == "deadline") {
            AddMissing(window, kGapDeadlinePostIncomplete);
        } else if (reason == "session_stop") {
            AddMissing(window, kGapSessionStopPostIncomplete);
        }
    }
    window->present_frames = 0;
    for (const auto& ref : window->before) {
        if (ref.present) ++window->present_frames;
    }
    if (window->during.present) ++window->present_frames;
    for (const auto& ref : window->after) {
        if (ref.present) ++window->present_frames;
    }
    window->complete =
        window->missing_reasons.empty() &&
        window->present_frames == window->wanted_frames;
}

std::vector<std::string> ContextRing::HealthTick(std::int64_t now_mono_ns) {
    std::vector<std::string> closed;
    if (!valid_) return closed;
    for (auto& window : windows_) {
        if (window.closed) continue;
        if (window.after.size() >= config_.post_frames) {
            CloseWindow(&window, "post_complete");
            pending_closed_.push_back(window.window_id);
            closed.push_back(window.window_id);
            continue;
        }
        // No new frame is ever a precondition for closing: the deadline is
        // measured against the caller clock from the trigger tick.
        if (now_mono_ns - window.trigger_recv_ns >=
            config_.window_deadline_ns) {
            CloseWindow(&window, "deadline");
            pending_closed_.push_back(window.window_id);
            closed.push_back(window.window_id);
        }
    }
    return closed;
}

std::vector<std::string> ContextRing::DrainNewlyClosed() {
    std::vector<std::string> closed;
    closed.swap(pending_closed_);
    return closed;
}

void ContextRing::CloseAllForStop() {
    for (auto& window : windows_) {
        if (window.closed) continue;
        CloseWindow(&window, "session_stop");
        pending_closed_.push_back(window.window_id);
    }
}

bool ContextRing::HasOpenWindows() const {
    for (const auto& window : windows_) {
        if (!window.closed) return true;
    }
    return false;
}

bool ContextRing::FindWindow(const std::string& window_id,
                             CaptureWindow* out) const {
    for (const auto& window : windows_) {
        if (window.window_id == window_id) {
            if (out != nullptr) *out = window;
            return true;
        }
    }
    return false;
}

bool ContextRing::FindWindowForTrigger(std::uint64_t trigger_tick,
                                       CaptureWindow* out) const {
    for (const auto& window : windows_) {
        if (window.trigger_tick == trigger_tick) {
            if (out != nullptr) *out = window;
            return true;
        }
    }
    return false;
}

}  // namespace ksi::safety
