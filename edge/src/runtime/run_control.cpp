// G9 run control decision logic — see run_control.hpp for the contract.

#include "ksi_safety/runtime/run_control.hpp"

#include "ksi_safety/runtime/bundle_config.hpp"

namespace ksi::safety {
namespace {

// Parses the bundle and installs it — but only when the actually loaded
// engine satisfies the bundle's enabled model profiles. A mismatch refuses
// the install (model_profile_mismatch) instead of asserting a requested
// model while the engine bytes differ. Absent model_profiles = no
// constraint (legacy bundles install as today).
struct InstallOutcome {
    bool installed = false;
    bool parse_failed = false;
    std::vector<std::string> errors;
    bool refused = false;
    std::vector<std::string> reasons;
};

InstallOutcome TryInstall(SessionRunner& runner, const JsonValue& bundle) {
    InstallOutcome out;
    const BundleRuntimeConfig config = ParseBundleRuntimeConfig(bundle);
    if (!config.ok) {
        out.parse_failed = true;
        out.errors = config.errors;
        return out;
    }
    bool constrained = false;
    bool matched = false;
    for (const BundleModelProfile& want : config.model_profiles) {
        if (!want.enabled) {
            continue;
        }
        constrained = true;
        const ModelProfile& have = runner.profile();
        if (want.model_id == have.model_id &&
            NormalizeModelDigest(want.model_digest) ==
                NormalizeModelDigest(have.model_digest)) {
            matched = true;
            break;
        }
    }
    if (constrained && !matched) {
        out.refused = true;
        out.reasons.push_back("model_profile_mismatch");
        return out;
    }
    RuntimeInstallResult installed = runner.ApplyRuntimeConfig(
        config.proximity_rules, config.zone_rules, config.zones,
        config.effective_config_hash, config.degraded_reasons);
    if (installed.ok) {
        out.installed = true;
    } else {
        out.refused = true;
        out.reasons = installed.reasons;
    }
    return out;
}

// Closes the gate and asks for the final summary. Unconditional on the
// runner state (a stop edge always closes, even an idle run — the evidence
// layer dedups the summary when no new ticks arrived).
void DoStop(SessionRunner& runner, RunControlOutcome* out) {
    const RuntimeSnapshot before = runner.Snapshot();
    const std::uint64_t close_ts =
        (before.has_processed_frame &&
         before.last_processed_frame_mono_ns > 0)
            ? static_cast<std::uint64_t>(before.last_processed_frame_mono_ns)
            : 0U;
    runner.RequestStop(close_ts);
    out->stop_requested = true;
    out->summary_requested = true;
    out->summary_close_ts = close_ts;
    out->facts_dirty = true;
}

// Opens the gate, unless it is already open: a repeated START while
// STARTING/RUNNING/DEGRADED is a no-op (no counter reset, no frame loss).
// The endpoint dedups wire retries by msg_id, so a retried START never even
// reaches this point; this guard covers a distinct repeated START.
void EnsureStarted(SessionRunner& runner, RunControlOutcome* out) {
    const RuntimeState state = runner.runtime_state();
    if (state == RuntimeState::kStarting ||
        state == RuntimeState::kRunning ||
        state == RuntimeState::kDegraded) {
        return;
    }
    runner.RequestStart();
    out->start_requested = true;
    out->facts_dirty = true;
}

}  // namespace

MopRuntimeFacts SnapshotToFacts(const RuntimeSnapshot& snapshot) {
    MopRuntimeFacts facts;
    facts.has_config_epoch = true;
    facts.config_epoch = snapshot.config_epoch;
    facts.has_state = true;
    facts.runtime_state = ToString(snapshot.state);
    facts.has_config_applied = true;
    facts.config_applied = snapshot.config_applied;
    facts.has_effective_config_hash = true;
    facts.effective_config_hash = snapshot.effective_config_hash;
    facts.has_processed_frame_count = true;
    facts.processed_frame_count = snapshot.processed_frame_count;
    if (snapshot.has_processed_frame) {
        facts.has_last_processed_mono_ns = true;
        facts.last_processed_frame_mono_ns =
            snapshot.last_processed_frame_mono_ns;
    }
    facts.has_degraded_reasons = true;
    facts.degraded_reasons = snapshot.degraded_reasons;
    facts.has_input_state = true;
    facts.input_state.video = snapshot.input_state.video;
    facts.input_state.telemetry = snapshot.input_state.telemetry;
    facts.input_state.engine = snapshot.input_state.engine;
    return facts;
}

RunControlOutcome RunControl::Step(SessionRunner& runner,
                                   const RunControlInputs& in) {
    RunControlOutcome out;
    // 1) Identity change (flight adoption / reboot): re-baseline the
    // command tracking without acting on intent. The generation edge that
    // always accompanies the identity change parks the runtime below, so
    // no pre-change intent can replay.
    if (in.command_epoch != last_command_epoch_) {
        last_command_epoch_ = in.command_epoch;
        last_command_revision_ = in.command_revision;
        last_starts_ = in.start_count;
        last_stops_ = in.stop_count;
    }
    // 2) Pending install (deferred device path): a validated MISSION_APPLY
    // awaiting the real install receipt. Attempted every poll while present;
    // the adapter confirms (switch + MISSION_APPLIED) or rejects
    // (MISSION_APPLY_REJECTED) synchronously, which clears it. Runs before
    // the generation edge so a racing identity change still parks below.
    // At most one install runs per Step: a pending success consumes the
    // generation edge's install below (the Null park still always runs).
    bool installed_this_step = false;
    if (in.has_pending_bundle && in.pending_bundle != nullptr) {
        InstallOutcome install = TryInstall(runner, *in.pending_bundle);
        if (install.installed) {
            out.pending_outcome = "installed";
            out.bundle_installed = true;
            installed_this_step = true;
        } else if (install.parse_failed) {
            out.pending_outcome = "parse_failed";
            out.bundle_parse_failed = true;
            out.bundle_errors = install.errors;
            out.pending_reasons = install.errors;
        } else {
            out.pending_outcome = "refused";
            out.install_refused = true;
            out.install_reasons = install.reasons;
            out.pending_reasons = install.reasons;
        }
        out.facts_dirty = true;
    }
    // 3) New applied bundle: install it into the engine. A Null bundle
    // means adoption/reboot cleared the mission: park the runtime — the old
    // install must not run under the new identity, and the RC re-applies.
    // Consumed even when the parse fails or the install is refused (the RC
    // re-applies as a new generation); a refused install keeps the old
    // config and the published facts keep describing it.
    if (in.applied_generation != last_generation_) {
        last_generation_ = in.applied_generation;
        if (in.has_bundle && in.bundle != nullptr) {
            if (installed_this_step) {
                // The pending install above already installed this poll:
                // consume the generation silently instead of reinstalling
                // (which would spuriously bump the epoch and reset counts).
            } else {
                InstallOutcome install = TryInstall(runner, *in.bundle);
                out.bundle_installed = install.installed;
                out.bundle_parse_failed = install.parse_failed;
                out.bundle_errors = install.errors;
                out.install_refused = install.refused;
                out.install_reasons = install.reasons;
            }
        } else {
            runner.AbortRuntime();
            out.runtime_aborted = true;
        }
        out.facts_dirty = true;
    }
    // 4) Session intent. The revision singles out "something new"; the
    // counts split it into starts vs stops; last_command_was_start orders a
    // START+STOP pair that landed in the same poll so the FINAL operator
    // intent wins: START-then-STOP ends stopped, STOP-then-START ends
    // started. An APPLY handled above installs first, then the intent runs
    // against the fresh install.
    if (in.command_revision != last_command_revision_ ||
        in.start_count != last_starts_ || in.stop_count != last_stops_) {
        last_command_revision_ = in.command_revision;
        const bool stop_new = in.stop_count != last_stops_;
        const bool start_new = in.start_count != last_starts_;
        last_starts_ = in.start_count;
        last_stops_ = in.stop_count;
        if (stop_new && start_new) {
            if (in.last_command_was_start) {
                DoStop(runner, &out);         // stop first...
                EnsureStarted(runner, &out);  // ...then start: ends started
            } else {
                EnsureStarted(runner, &out);
                DoStop(runner, &out);  // ends stopped
            }
        } else if (stop_new) {
            DoStop(runner, &out);
        } else if (start_new) {
            EnsureStarted(runner, &out);
        }
        // Revision-only movement (no count delta) cannot come from the
        // endpoint — nothing to do, and the revision is consumed above.
    }
    if (out.facts_dirty) {
        out.facts = SnapshotToFacts(runner.Snapshot());
    }
    return out;
}

}  // namespace ksi::safety
