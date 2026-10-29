// Unit A — bounded pre/post evidence ring and capture windows (Stage 1).
//
// WHY SEPARATE FROM SourceEvidenceRecorder: the recorder keeps the source
// frame of MATERIAL observations only (fresh detections) — that policy is
// pinned by its tests and stays. Autonomous assessment additionally needs
// the frames AROUND a trigger tick (before/during/after, INCLUDING ticks
// with zero detections), because a clean negative next to a candidate is
// evidence about that candidate. This ring holds recent input frames in
// volatile memory (bounded) and assembles truthful capture windows from it.
//
// BOUNDS (all explicit, no hidden defaults at the call site):
//   max_frames / max_bytes bound the volatile ring. Overflow OVERWRITES the
//   oldest volatile frame (normal ring behaviour) and counts it — it never
//   deletes sealed evidence (sealed bytes live in the EvidenceStore, which
//   has no eviction).
//   pre_frames / post_frames size one window; window_deadline_ns bounds how
//   long a window waits for its post frames (caller clock: HealthTick closes
//   overdue windows so no new frame is ever a precondition for closing).
//
// TRUTHFULNESS:
//   - early start (fewer than pre_frames available) → "prehistory_gap",
//     not silent padding;
//   - ring overwrote frames the window wanted → "ring_evicted_prehistory";
//   - input gaps inside the window (missing tick indices) → "input_gap";
//   - a deadline close with missing post frames → complete=false +
//     "deadline_exceeded_post_incomplete".
// A window is complete only when every wanted frame is present.
//
// NO CLOCK: Push/HealthTick take caller-provided monotonic nanoseconds.

#ifndef KSI_SAFETY_OBSERVATION_CONTEXT_RING_HPP
#define KSI_SAFETY_OBSERVATION_CONTEXT_RING_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/perception/detector.hpp"

namespace ksi::safety {

constexpr const char* kGapPrehistory = "prehistory_gap";
constexpr const char* kGapRingEvicted = "ring_evicted_prehistory";
constexpr const char* kGapInputGap = "input_gap";
constexpr const char* kGapDeadlinePostIncomplete = "deadline_exceeded_post_incomplete";
constexpr const char* kGapSessionStopPostIncomplete = "session_stop_post_incomplete";

struct ContextRingConfig {
    // Volatile ring bounds (0 = invalid; the owner must state them).
    std::size_t max_frames = 0;
    std::uint64_t max_bytes = 0;
    // Window shape: frames before and after the trigger tick.
    std::size_t pre_frames = 0;
    std::size_t post_frames = 0;
    // Caller-ns budget for post frames; <= 0 means "close on HealthTick only
    // when explicitly asked" — the owner still must state it. Non-positive is
    // rejected by IsValidContextRingConfig (a window must always be able to
    // close without new frames).
    std::int64_t window_deadline_ns = 0;
};

bool IsValidContextRingConfig(const ContextRingConfig& config);

// One retained input frame (volatile copy; sealed bytes go to the store).
struct RingFrame {
    std::uint64_t tick_index = 0;
    std::uint32_t frame_id = 0;
    std::int64_t receive_mono_ns = 0;
    DetectionStatus detection_status = DetectionStatus::kNotRun;
    bool has_detection = false;
    std::string lens_id;
    bool exposure_verified = false;
    std::vector<std::uint8_t> bytes;
};

struct WindowFrameRef {
    std::uint64_t tick_index = 0;
    std::uint32_t frame_id = 0;
    std::int64_t receive_mono_ns = 0;
    bool has_detection = false;
    bool present = false;  // false = wanted but missing (see missing_reasons)
};

struct CaptureWindow {
    std::string window_id;  // uuid5 win|flight|boot|trigger_tick
    std::uint64_t trigger_tick = 0;
    std::uint32_t trigger_frame_id = 0;
    std::int64_t trigger_recv_ns = 0;
    std::vector<WindowFrameRef> before;
    WindowFrameRef during;
    std::vector<WindowFrameRef> after;
    bool closed = false;
    bool complete = false;
    std::string close_reason;  // "post_complete" | "deadline" | "session_stop"
    std::vector<std::string> missing_reasons;
    std::size_t wanted_frames = 0;
    std::size_t present_frames = 0;
};

class ContextRing {
  public:
    explicit ContextRing(ContextRingConfig config, std::string flight_id,
                         std::string edge_boot_id);

    bool valid() const { return valid_; }
    const std::string& error() const { return error_; }

    // Retains one input frame (copies the bytes). Overwrites the oldest
    // volatile frames while over either bound; counts every eviction.
    // Always also feeds open windows their post frames.
    void Push(RingFrame frame);

    // Opens a window around trigger_tick (which must already have been
    // pushed): snapshots up to pre_frames predecessors from the ring.
    // Returns false + error when the trigger is unknown.
    bool BeginWindow(std::uint64_t trigger_tick, std::string* error);

    // Closes windows whose post span is satisfied OR whose deadline passed
    // at now_mono_ns (caller clock). Returns the ids closed by this call.
    std::vector<std::string> HealthTick(std::int64_t now_mono_ns);
    // Ids newly closed since the last drain (post-satisfied closes from
    // Push report here too: the ring never closes silently).
    std::vector<std::string> DrainNewlyClosed();

    // Closes every open window with reason "session_stop" (post frames still
    // missing are named, never silently dropped).
    void CloseAllForStop();

    bool HasOpenWindows() const;
    // Retained bytes for one tick (volatile; nullptr when evicted/unknown).
    // Used at window close to persist present frames.
    const RingFrame* FindRetainedFrame(std::uint64_t tick_index) const;
    bool FindWindow(const std::string& window_id, CaptureWindow* out) const;
    bool FindWindowForTrigger(std::uint64_t trigger_tick,
                              CaptureWindow* out) const;
    std::vector<CaptureWindow> windows() const { return windows_; }

    std::size_t retained_frames() const { return frames_.size(); }
    std::uint64_t retained_bytes() const { return retained_bytes_; }
    std::uint64_t evicted_frames() const { return evicted_frames_; }
    std::uint64_t pushed_frames() const { return pushed_frames_; }

  private:
    const RingFrame* FindFrame(std::uint64_t tick_index) const;
    void AttachPostFrames(const RingFrame& frame);
    void MaybeCloseSatisfied();
    void CloseWindow(CaptureWindow* window, const std::string& reason);

    ContextRingConfig config_;
    std::string flight_id_;
    std::string edge_boot_id_;
    bool valid_ = false;
    std::string error_;
    std::vector<RingFrame> frames_;  // ascending tick_index
    std::uint64_t retained_bytes_ = 0;
    std::uint64_t evicted_frames_ = 0;
    std::uint64_t pushed_frames_ = 0;
    // Lowest tick ever evicted (or lost before retention); U64 max when none.
    std::uint64_t eviction_floor_tick_ = 0;
    bool has_eviction_floor_ = false;
    std::vector<CaptureWindow> windows_;
    // Newly closed since the last drain (post-satisfied closes from Push
    // report here too: the ring never closes silently).
    std::vector<std::string> pending_closed_;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_OBSERVATION_CONTEXT_RING_HPP
