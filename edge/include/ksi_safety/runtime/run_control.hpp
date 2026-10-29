// G9 run control, DJI-free decision half (host-tested).
//
// The PSDK app (edge/psdk-app/src/main.cpp) polls one coherent run-command
// snapshot from the MOP endpoint (MopSessionCommands: desired intent plus a
// monotonic revision/order/identity, with the applied-bundle facts) and
// turns it into SessionRunner transitions plus MISSION_STATUS runtime
// facts. That glue used to live inline in main.cpp, where it could not be
// host-tested (PSDK headers exist only on the Manifold device). This unit
// owns the DECISION logic with plain inputs and no I/O:
//
//   inputs : atomic command snapshot (never two separately sampled
//            counters fused into one poll) + bundle JSON + runner state
//   outputs: runner transitions + the facts the caller publishes
//
// Ordering rule: the FINAL operator intent wins. A START and STOP landing
// in the same poll are applied in wire order (via last_command_was_start),
// so START-then-STOP ends stopped and STOP-then-START ends started — never
// a fixed stop-before-start. An APPLY racing a START installs first, then
// starts. A repeated START while already running is a no-op (no counter
// reset, no frame loss). Adoption/reboot re-baselines the command tracking
// (new epoch) and parks the runtime via the cleared bundle — old intent
// never replays.
//
// No DJI types, no PSDK headers, no I/O (diagnostics are returned, not
// printed), no clock reads: the stop close timestamp derives from the
// runner's own last-processed-frame time (caller-provided data), exactly
// like the rest of edge. Identical input sequences produce identical
// outputs. The device-side adapter stays a thin reader/publisher and is
// verified on the A9-4 ground session only.

#ifndef KSI_SAFETY_RUNTIME_RUN_CONTROL_HPP
#define KSI_SAFETY_RUNTIME_RUN_CONTROL_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"

namespace ksi::safety {

// Maps a runner snapshot to the MISSION_STATUS runtime facts. Pure:
// explicit presence is preserved (absent stays absent, never false/0),
// matching the wire contract the endpoint enforces.
MopRuntimeFacts SnapshotToFacts(const RuntimeSnapshot& snapshot);

// Command facts for one poll: one coherent MopSessionCommands snapshot
// (taken by the adapter under a single lock) plus the bundle for the
// current applied_generation when it moved, plus — on the deferred device
// path — the validated-but-uninstalled pending request. has_bundle is
// false for Null (adoption/reboot cleared the bundle — park the runtime);
// bundle points at the applied object only when the generation is new and
// a bundle is present. has_pending_bundle carries the MISSION_APPLY payload
// awaiting the real install receipt; pending_bundle points at it.
struct RunControlInputs {
    std::uint64_t command_epoch = 0;
    std::uint64_t command_revision = 0;
    bool desired_running = false;
    std::uint64_t start_count = 0;
    std::uint64_t stop_count = 0;
    bool last_command_was_start = false;
    std::uint64_t applied_generation = 0;
    bool has_bundle = false;
    const JsonValue* bundle = nullptr;
    bool has_pending_bundle = false;
    const JsonValue* pending_bundle = nullptr;
};

struct RunControlOutcome {
    bool bundle_installed = false;
    // Deferred pending path (see inputs): "" when no pending request was
    // present, otherwise installed|refused|parse_failed with the reasons.
    // Kept separate from the generation-edge fields so the adapter can
    // audit (and confirm/reject) each install path independently.
    std::string pending_outcome;
    std::vector<std::string> pending_reasons;
    // Malformed bundle: nothing installed, the old install is kept.
    bool bundle_parse_failed = false;
    std::vector<std::string> bundle_errors;
    // Live reinstall while STARTING/RUNNING/DEGRADED: refused, the live
    // execution keeps its config — the published facts still describe the
    // OLD install (never relabelled as the new one); the RC detects the
    // stale install via the unchanged effective_config_hash.
    bool install_refused = false;
    std::vector<std::string> install_reasons;
    // Null bundle after adoption/reboot: the runtime is parked.
    bool runtime_aborted = false;
    bool stop_requested = false;
    bool start_requested = false;
    // Set once per stop edge; the caller emits the final summary (the
    // evidence layer dedups it against its own tick count, so a stop with
    // no new ticks is a no-op there).
    bool summary_requested = false;
    // Timestamp handed to RequestStop (the runner's last processed frame
    // time, or 0 when nothing was processed yet). Caller-provided data —
    // no clock is read here.
    std::uint64_t summary_close_ts = 0;
    // True when any edge fired (including a failed bundle edge, which still
    // republishes); facts then holds the post-transition snapshot mapping.
    bool facts_dirty = false;
    MopRuntimeFacts facts;
};

// Intent-driven run control. Remembers the last consumed command epoch,
// revision, counts and applied generation; a poll matching all of them
// produces no transition. An epoch change (adoption/reboot) re-baselines
// the command tracking without acting on intent; the generation edge that
// always accompanies it parks the runtime.
class RunControl {
  public:
    RunControl() = default;

    RunControlOutcome Step(SessionRunner& runner, const RunControlInputs& in);

    std::uint64_t last_command_epoch() const { return last_command_epoch_; }
    std::uint64_t last_command_revision() const {
        return last_command_revision_;
    }
    std::uint64_t last_applied_generation() const {
        return last_generation_;
    }

  private:
    std::uint64_t last_command_epoch_ = 0;
    std::uint64_t last_command_revision_ = 0;
    std::uint64_t last_starts_ = 0;
    std::uint64_t last_stops_ = 0;
    std::uint64_t last_generation_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_RUNTIME_RUN_CONTROL_HPP
