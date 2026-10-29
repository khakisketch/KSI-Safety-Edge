// Host test for the G9 run-control decision unit
// (edge/include/ksi_safety/runtime/run_control.hpp): the logic extracted
// from the psdk-app SyncRunControl glue, which cannot run on the host
// (PSDK headers exist only on the Manifold device). The endpoint half
// (revision, order, dedup, epoch) is covered in test_runtime_control.cpp.
//
// Covers: every transition (install / park / stop+summary / start), the
// final-intent-wins rule (START-then-STOP in one poll ends stopped,
// STOP-then-START ends started — never a fixed order), APPLY+START in one
// poll (install then start), repeated START without reset, adoption/reboot
// without old-intent replay, refused live apply keeping the old facts,
// facts matching the runner snapshot, and determinism (same inputs ->
// same outputs, no clock, no RNG).

#include "ksi_safety/runtime/run_control.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"

#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

#include "support/check.hpp"

using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::JsonValue;
using ksi::safety::ModelProfile;
using ksi::safety::MopRuntimeFacts;
using ksi::safety::RunControl;
using ksi::safety::RunControlInputs;
using ksi::safety::RunControlOutcome;
using ksi::safety::RunnerConfig;
using ksi::safety::RuntimeSnapshot;
using ksi::safety::RuntimeState;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::Sha256HexOfFile;
using ksi::safety::SnapshotToFacts;

namespace {

constexpr std::int64_t kMs = 1000000LL;

class OkDetector : public DetectorPort {
  public:
    int calls = 0;
    DetectionBatch Detect(const FramePacket&) override {
        ++calls;
        DetectionBatch batch;
        batch.status = DetectionStatus::kOk;
        return batch;
    }
};

RunnerConfig RunnerCfg() {
    RunnerConfig config;
    config.match.max_receive_gap_ns = 40 * kMs;
    config.match.max_bracket_interval_ns = 20 * kMs;
    config.detect_every_n_frames = 1;
    return config;
}

SessionConfig SessionCfg() {
    SessionConfig config;
    config.tracker.iou_threshold = 0.3F;
    config.tracker.max_age_frames = 5;
    config.tracker.min_hits = 1;
    config.max_gap_ms = 5000;
    config.zone_envelope_m = 1.0;
    return config;
}

ModelProfile Profile() {
    ModelProfile profile;
    profile.model_id = "test-model";
    profile.model_digest = "sha256:test";
    profile.backend = "fake";
    return profile;
}

// Runner whose profile binds a digest (mirrors the device binding the
// SHA-256 of the loaded engine file).
SessionRunner RunnerWithDigest(const std::string& digest) {
    ModelProfile profile = Profile();
    profile.model_digest = digest;
    return SessionRunner(RunnerCfg(), SessionCfg(), {}, {}, {}, profile);
}

// Bundle carrying enabled model profiles (validated shape, compared
// against the runner's ACTUAL profile at install).
JsonValue MakeModelBundle(const std::string& id, const std::string& digest,
                          bool enabled) {
    JsonValue profile = JsonValue::Obj();
    profile.Set("model_id", JsonValue::Str(id));
    profile.Set("model_digest", JsonValue::Str(digest));
    profile.Set("enabled", JsonValue::Bool(enabled));
    JsonValue::Array profiles;
    profiles.push_back(std::move(profile));
    JsonValue snapshot = JsonValue::Obj();
    snapshot.Set("zones", JsonValue::Arr());
    snapshot.Set("model_profiles", JsonValue::Arr(std::move(profiles)));
    JsonValue policy = JsonValue::Obj();
    policy.Set("rules", JsonValue::Arr());
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("mission_snapshot", std::move(snapshot));
    bundle.Set("risk_policy", std::move(policy));
    return bundle;
}

SessionRunner FreshRunner() {
    return SessionRunner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
}

FramePacket MakeFrame(std::uint32_t frame_id, std::int64_t recv_ns) {
    FramePacket packet;
    packet.metadata.width = 8;
    packet.metadata.height = 6;
    packet.metadata.frame_id = frame_id;
    packet.metadata.received_monotonic_ns = recv_ns;
    packet.rgb.assign(8U * 6U * 3U, 0);
    return packet;
}

FrameContext MakeCtx(const FramePacket& packet) {
    FrameContext ctx;
    ctx.flight_id = "F";
    ctx.boot_id = "B";
    ctx.stream_id = "S";
    ctx.frame_id = packet.metadata.frame_id;
    ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
    ctx.has_exposure = false;
    return ctx;
}

// Minimal valid bundle: explicit empty zones/rules arrays are the empty
// install (still hashed), per the bundle-config contract. Absent arrays
// are malformed — never an empty install.
JsonValue MakeEmptyBundle() {
    JsonValue snapshot = JsonValue::Obj();
    snapshot.Set("zones", JsonValue::Arr());
    JsonValue policy = JsonValue::Obj();
    policy.Set("rules", JsonValue::Arr());
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("mission_snapshot", std::move(snapshot));
    bundle.Set("risk_policy", std::move(policy));
    return bundle;
}

JsonValue MakeMalformedBundle() {
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("risk_policy", JsonValue::Obj());  // mission_snapshot missing
    return bundle;
}

// One coherent command snapshot: revision = starts + stops (endpoint
// semantics), order flag + desired intent from the latest command.
RunControlInputs Snapshot(std::uint64_t starts, std::uint64_t stops,
                          bool last_was_start, bool desired,
                          std::uint64_t generation,
                          std::uint64_t epoch = 0) {
    RunControlInputs in;
    in.command_epoch = epoch;
    in.command_revision = starts + stops;
    in.desired_running = desired;
    in.start_count = starts;
    in.stop_count = stops;
    in.last_command_was_start = last_was_start;
    in.applied_generation = generation;
    return in;
}

RunControlInputs NoEdges() { return Snapshot(0, 0, false, false, 0); }

// Composes a bundle generation onto an explicit command snapshot. The
// command counts always reflect the endpoint's live totals (the adapter
// fills every field every poll) — never zero them while a session is
// active, or the consumer reads a phantom command edge.
RunControlInputs WithBundle(RunControlInputs base, const JsonValue* bundle,
                            bool present, std::uint64_t generation) {
    base.applied_generation = generation;
    base.has_bundle = present;
    base.bundle = present ? bundle : nullptr;
    return base;
}

bool OutcomesEqual(const RunControlOutcome& a, const RunControlOutcome& b) {
    return a.bundle_installed == b.bundle_installed &&
           a.pending_outcome == b.pending_outcome &&
           a.pending_reasons == b.pending_reasons &&
           a.bundle_parse_failed == b.bundle_parse_failed &&
           a.bundle_errors == b.bundle_errors &&
           a.install_refused == b.install_refused &&
           a.install_reasons == b.install_reasons &&
           a.runtime_aborted == b.runtime_aborted &&
           a.stop_requested == b.stop_requested &&
           a.start_requested == b.start_requested &&
           a.summary_requested == b.summary_requested &&
           a.summary_close_ts == b.summary_close_ts &&
           a.facts_dirty == b.facts_dirty &&
           a.facts.config_epoch == b.facts.config_epoch &&
           a.facts.runtime_state == b.facts.runtime_state &&
           a.facts.config_applied == b.facts.config_applied &&
           a.facts.effective_config_hash == b.facts.effective_config_hash &&
           a.facts.processed_frame_count == b.facts.processed_frame_count &&
           a.facts.has_last_processed_mono_ns ==
               b.facts.has_last_processed_mono_ns &&
           a.facts.last_processed_frame_mono_ns ==
               b.facts.last_processed_frame_mono_ns &&
           a.facts.degraded_reasons == b.facts.degraded_reasons &&
           a.facts.input_state.video == b.facts.input_state.video &&
           a.facts.input_state.telemetry == b.facts.input_state.telemetry &&
           a.facts.input_state.engine == b.facts.input_state.engine;
}

bool SnapshotsEqual(const RuntimeSnapshot& a, const RuntimeSnapshot& b) {
    return a.state == b.state && a.config_applied == b.config_applied &&
           a.effective_config_hash == b.effective_config_hash &&
           a.config_epoch == b.config_epoch &&
           a.processed_frame_count == b.processed_frame_count &&
           a.last_processed_frame_mono_ns ==
               b.last_processed_frame_mono_ns &&
           a.has_processed_frame == b.has_processed_frame &&
           a.degraded_reasons == b.degraded_reasons &&
           a.input_state.video == b.input_state.video &&
           a.input_state.telemetry == b.input_state.telemetry &&
           a.input_state.engine == b.input_state.engine;
}

void CheckFactsMatchSnapshot(const MopRuntimeFacts& facts,
                             const RuntimeSnapshot& snapshot,
                             const char* label) {
    CHECK(facts.has_config_epoch &&
              facts.config_epoch == snapshot.config_epoch,
          label);
    CHECK(facts.has_state &&
              facts.runtime_state ==
                  std::string(ksi::safety::ToString(snapshot.state)),
          label);
    CHECK(facts.has_config_applied &&
              facts.config_applied == snapshot.config_applied,
          label);
    CHECK(facts.has_effective_config_hash &&
              facts.effective_config_hash == snapshot.effective_config_hash,
          label);
    CHECK(facts.has_processed_frame_count &&
              facts.processed_frame_count == snapshot.processed_frame_count,
          label);
    CHECK(facts.has_last_processed_mono_ns == snapshot.has_processed_frame,
          label);
    if (snapshot.has_processed_frame) {
        CHECK(facts.last_processed_frame_mono_ns ==
                  snapshot.last_processed_frame_mono_ns,
              label);
    }
    CHECK(facts.has_degraded_reasons &&
              facts.degraded_reasons == snapshot.degraded_reasons,
          label);
    CHECK(facts.has_input_state &&
              facts.input_state.video == snapshot.input_state.video &&
              facts.input_state.telemetry ==
                  snapshot.input_state.telemetry &&
              facts.input_state.engine == snapshot.input_state.engine,
          label);
}

}  // namespace

int main() {
    // ---- 1) no edges -> no processing ------------------------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const RunControlOutcome out = control.Step(runner, NoEdges());
        CHECK(!out.bundle_installed && !out.bundle_parse_failed &&
                  !out.install_refused && !out.runtime_aborted &&
                  !out.stop_requested && !out.start_requested &&
                  !out.summary_requested && !out.facts_dirty,
              "quiet poll produces no transition and no facts");
        CHECK(runner.runtime_state() == RuntimeState::kRequestAccepted,
              "quiet poll leaves the runner alone");
        CHECK(runner.processed_frame_count() == 0, "nothing processed");
    }

    // ---- 2) new applied generation -> install + facts --------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        const RunControlOutcome out =
            control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                 &bundle, true, 1));
        CHECK(out.bundle_installed, "bundle edge installs");
        CHECK(!out.bundle_parse_failed && !out.install_refused &&
                  !out.runtime_aborted,
              "clean install reports no failure");
        CHECK(!out.stop_requested && !out.start_requested &&
                  !out.summary_requested,
              "bundle edge alone starts/stops nothing");
        CHECK(out.facts_dirty, "bundle edge republishes facts");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "installed runner is CONFIG_APPLIED");
        CHECK(runner.config_epoch() == 1, "first install is epoch 1");
        CheckFactsMatchSnapshot(out.facts, runner.Snapshot(),
                                "facts match the installed snapshot");
        CHECK(out.facts.config_applied, "facts report config_applied");
        CHECK(out.facts.runtime_state == "CONFIG_APPLIED",
              "facts report CONFIG_APPLIED");
    }

    // ---- 3) malformed bundle fails closed --------------------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bad = MakeMalformedBundle();
        const RunControlOutcome out =
            control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                 &bad, true, 1));
        CHECK(out.bundle_parse_failed && !out.bundle_installed,
              "malformed bundle installs nothing");
        CHECK(!out.bundle_errors.empty(), "parse diagnostics returned");
        CHECK(runner.runtime_state() == RuntimeState::kRequestAccepted,
              "failed parse keeps the old (empty) state");
        CHECK(!runner.config_applied(), "nothing applied on parse failure");
        CHECK(out.facts_dirty, "failed bundle edge still republishes");
        // Same generation repeated: quiet (generation is consumed even on
        // parse failure — the RC re-applies as a new generation).
        const RunControlOutcome quiet =
            control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                 &bad, true, 1));
        CHECK(!quiet.bundle_parse_failed && !quiet.facts_dirty,
              "consumed generation stays quiet");
    }

    // ---- 4) Null bundle after adoption/reboot parks the runtime ---------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the park test");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started before the identity loss");
        OkDetector detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        CHECK(runner.processed_frame_count() == 1, "one frame processed");
        // Identity loss: generation moves, no bundle applied.
        RunControlInputs park = Snapshot(0, 0, false, false, 2, 1);
        const RunControlOutcome parked = control.Step(runner, park);
        CHECK(parked.runtime_aborted, "Null bundle parks the runtime");
        CHECK(!parked.start_requested && !parked.stop_requested,
              "park replays no old intent");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "parked runner is STOPPED");
        OkDetector gated;
        FramePacket late = MakeFrame(2, 20 * kMs);
        runner.ProcessFrame(late, MakeCtx(late), &gated);
        CHECK(gated.calls == 0, "parked runner never calls the detector");
        CHECK(runner.processed_frame_count() == 1,
              "parked runner processes nothing further");
        // The old install is gone: a fresh bundle installs at a new epoch
        // with a reset counter, never under the old identity.
        const RunControlOutcome reinstall =
            control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0, 1),
                                                 &bundle, true, 3));
        CHECK(reinstall.bundle_installed &&
                  runner.config_epoch() == 2,
              "reinstall after park bumps the epoch");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "reinstall parks at CONFIG_APPLIED");
        CHECK(runner.processed_frame_count() == 0, "counter reset");
    }

    // ---- 5) stop edge -> close + exactly one summary request ------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the stop test");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started");
        OkDetector detector;
        const std::int64_t recv = 50 * kMs;
        FramePacket frame = MakeFrame(5, recv);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        CHECK(runner.processed_frame_count() == 1, "one frame processed");
        const RunControlOutcome out =
            control.Step(runner, Snapshot(1, 1, false, false, 1));
        CHECK(out.stop_requested && out.summary_requested,
              "stop closes and requests the summary");
        CHECK(!out.start_requested, "stop edge alone starts nothing");
        CHECK(out.summary_close_ts == static_cast<std::uint64_t>(recv),
              "close timestamp is the last processed frame time");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "stopped runner is STOPPED");
        CheckFactsMatchSnapshot(out.facts, runner.Snapshot(),
                                "stop facts match the snapshot");
        // Gate closed: no further processing, detector never called again.
        FramePacket late = MakeFrame(6, 60 * kMs);
        runner.ProcessFrame(late, MakeCtx(late), &detector);
        CHECK(detector.calls == 1, "stopped runner stays silent");
        CHECK(runner.processed_frame_count() == 1, "count frozen");
        // Same snapshot again: the summary is NOT requested twice.
        const RunControlOutcome repeat =
            control.Step(runner, Snapshot(1, 1, false, false, 1));
        CHECK(!repeat.stop_requested && !repeat.summary_requested &&
                  !repeat.facts_dirty,
              "repeated poll requests no second summary");
    }

    // ---- 6) stop with nothing processed yet closes at 0 -----------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const RunControlOutcome out =
            control.Step(runner, Snapshot(0, 1, false, false, 0));
        CHECK(out.stop_requested && out.summary_requested,
              "stop on an idle runner still closes");
        CHECK(out.summary_close_ts == 0U, "no frame yet means close at 0");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "idle stop parks at STOPPED");
    }

    // ---- 7) start edge opens the gate -----------------------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the start test");
        // A stop first so the start visibly reopens a closed gate.
        CHECK(control.Step(runner, Snapshot(0, 1, false, false, 1))
                  .stop_requested,
              "stopped");
        const RunControlOutcome out =
            control.Step(runner, Snapshot(1, 1, true, true, 1));
        CHECK(out.start_requested && !out.stop_requested,
              "start edge opens the gate");
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "restarted runner is STARTING");
        CHECK(runner.processed_frame_count() == 0, "counter reset");
        CheckFactsMatchSnapshot(out.facts, runner.Snapshot(),
                                "start facts match the snapshot");
    }

    // ---- 8) start without any install is allowed (reported as such) -----
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const RunControlOutcome out =
            control.Step(runner, Snapshot(1, 0, true, true, 0));
        CHECK(out.start_requested, "start works with no install");
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "starting without config");
        CHECK(!out.facts.config_applied, "facts report no config");
    }

    // ---- 9a) START-then-STOP in one poll ends stopped --------------------
    // (the fixed stop-before-start order used to end this STARTED — that
    // encoded the operator's stale intent, not the final one).
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the order test");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "running before the pair lands");
        OkDetector detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        const RunControlOutcome out =
            control.Step(runner, Snapshot(2, 1, false, false, 1));
        CHECK(out.stop_requested, "the final STOP is honored");
        CHECK(out.summary_requested, "the stop asks for a summary");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "START-then-STOP ends stopped");
        CHECK(!runner.processing_enabled(), "gate closed");
    }

    // ---- 9b) STOP-then-START in one poll ends started --------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const RunControlOutcome out =
            control.Step(runner, Snapshot(1, 1, true, true, 0));
        CHECK(out.stop_requested && out.start_requested,
              "both commands honored in wire order");
        CHECK(out.summary_requested, "the stop half still asks for summary");
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "STOP-then-START ends started");
        CHECK(runner.processing_enabled(), "gate open after STOP-then-START");
    }

    // ---- 10) live reinstall refused; facts keep the OLD install ---------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "first install");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started");
        const std::string hash_before = runner.effective_config_hash();
        RunControlInputs live =
            WithBundle(Snapshot(1, 0, true, true, 1), &bundle, true, 2);
        const RunControlOutcome refused = control.Step(runner, live);
        CHECK(refused.install_refused && !refused.bundle_installed,
              "live reinstall refused");
        CHECK(refused.install_reasons.size() == 1 &&
                  refused.install_reasons[0] == "reinstall_while_running",
              "refusal names the reason");
        CHECK(runner.config_epoch() == 1, "epoch unchanged while live");
        CHECK(runner.effective_config_hash() == hash_before,
              "live install keeps the old hash");
        CHECK(refused.facts.effective_config_hash == hash_before &&
                  refused.facts.config_epoch == 1 &&
                  refused.facts.config_applied,
              "refused apply cannot relabel the old config as new");
        // Clean stop, then the same bundle installs at a new epoch.
        CHECK(control.Step(runner, Snapshot(1, 1, false, false, 2))
                  .stop_requested,
              "stopped");
        const JsonValue bundle2 = MakeEmptyBundle();
        const RunControlOutcome installed =
            control.Step(runner, WithBundle(Snapshot(1, 1, false, false, 2),
                                                 &bundle2, true, 3));
        CHECK(installed.bundle_installed && runner.config_epoch() == 2,
              "reinstall after stop bumps the epoch");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "reinstall parks at CONFIG_APPLIED");
    }

    // ---- 11) APPLY+START in one poll: install then start ----------------
    // (the old counter resync silently dropped a START racing the APPLY).
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs both =
            WithBundle(Snapshot(1, 0, true, true, 0), &bundle, true, 1);
        const RunControlOutcome out = control.Step(runner, both);
        CHECK(out.bundle_installed, "bundle installs");
        CHECK(out.start_requested, "the racing START is honored, not dropped");
        CHECK(!out.stop_requested && !out.summary_requested,
              "no stop fabricated");
        CHECK(runner.runtime_state() == RuntimeState::kStarting,
              "runner ends STARTING on the fresh install");
        CHECK(runner.processing_enabled(), "gate open");
    }

    // ---- 12) START-APPLY-STOP in one poll ends stopped -------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs all =
            WithBundle(Snapshot(1, 1, false, false, 0), &bundle, true, 1);
        const RunControlOutcome out = control.Step(runner, all);
        CHECK(out.bundle_installed, "bundle installs first");
        CHECK(out.start_requested && out.stop_requested,
              "both intents honored in wire order");
        CHECK(out.summary_requested, "stop asks for a summary");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "START-APPLY-STOP ends stopped");
        CHECK(!runner.processing_enabled(), "gate closed");
    }

    // ---- 13) duplicate repeated START never resets ----------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the repeat test");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started");
        OkDetector detector;
        for (int i = 0; i < 2; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(1 + i),
                                          (10 + i) * kMs);
            runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        }
        CHECK(runner.processed_frame_count() == 2, "two frames processed");
        // Re-poll of the same snapshot: silent (wire retries dedup to this).
        const RunControlOutcome repeat =
            control.Step(runner, Snapshot(1, 0, true, true, 1));
        CHECK(!repeat.start_requested && !repeat.facts_dirty,
              "repeated snapshot is a no-op");
        CHECK(runner.processed_frame_count() == 2, "counter kept");
        // A DISTINCT repeated START while running: still no reset.
        const RunControlOutcome again =
            control.Step(runner, Snapshot(2, 0, true, true, 1));
        CHECK(!again.start_requested, "no second RequestStart while running");
        CHECK(runner.processed_frame_count() == 2, "no frame counter loss");
        CHECK(runner.runtime_state() == RuntimeState::kDegraded ||
                  runner.runtime_state() == RuntimeState::kRunning,
              "run continues (health-dependent state)");
    }

    // ---- 14) adoption/reboot replays no old intent -----------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install before the identity change");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started before the identity change");
        // Reboot: epoch up, revision/counts reset, bundle cleared. The
        // pre-reboot START must not replay as a fresh RequestStart.
        RunControlInputs rebooted = Snapshot(0, 0, false, false, 2, 1);
        const RunControlOutcome out = control.Step(runner, rebooted);
        CHECK(out.runtime_aborted, "cleared bundle parks the runtime");
        CHECK(!out.start_requested && !out.stop_requested &&
                  !out.summary_requested,
              "no old intent replays across the epoch");
        CHECK(runner.runtime_state() == RuntimeState::kStopped,
              "parked after the identity change");
        // Settled: further polls on the new identity are quiet.
        const RunControlOutcome quiet =
            control.Step(runner, Snapshot(0, 0, false, false, 2, 1));
        CHECK(!quiet.facts_dirty, "new identity settles quiet");
        CHECK(control.last_command_epoch() == 1 &&
                  control.last_applied_generation() == 2,
              "new identity baselined");
    }

    // ---- 15) polling without change is a no-op ---------------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        CHECK(control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                  &bundle, true, 1))
                  .bundle_installed,
              "install for the noop test");
        for (int i = 0; i < 3; ++i) {
            const RunControlOutcome out =
                control.Step(runner, Snapshot(0, 0, false, false, 1));
            CHECK(!out.bundle_installed && !out.stop_requested &&
                      !out.start_requested && !out.summary_requested &&
                      !out.facts_dirty,
                  "steady-state poll changes nothing");
        }
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "runner untouched by idle polls");
    }

    // ---- 16) SnapshotToFacts mapping, absent and present -----------------
    {
        SessionRunner runner = FreshRunner();
        const RuntimeSnapshot bare = runner.Snapshot();
        const MopRuntimeFacts bare_facts = SnapshotToFacts(bare);
        CHECK(!bare_facts.has_last_processed_mono_ns,
              "no processed frame yet => age absent (never 0)");
        CheckFactsMatchSnapshot(bare_facts, bare, "bare mapping matches");
        OkDetector detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        CheckFactsMatchSnapshot(SnapshotToFacts(runner.Snapshot()),
                                runner.Snapshot(), "live mapping matches");
    }

    // ---- 20) model profile match: real bytes, prefix normalized ---------
    // The bundle's enabled model profile must match the ACTUALLY LOADED
    // engine (id + file digest). Raw hex and "sha256:"-prefixed digests
    // compare equal; no TensorRT needed (pure parse + compare).
    {
        const std::string engine_path =
            "/tmp/opencode/ksi-edge-model-test.bin";
        {
            std::FILE* file = std::fopen(engine_path.c_str(), "wb");
            CHECK(file != nullptr, "engine stand-in writable");
            std::fwrite("fake-engine-bytes", 1, 17, file);
            std::fclose(file);
        }
        std::string hash_error;
        const std::string hex = Sha256HexOfFile(engine_path.c_str(),
                                                &hash_error);
        CHECK(!hex.empty() && hash_error.empty(), "engine file hashed");
        CHECK(hex.size() == 64, "hash is 64 hex chars");
        SessionRunner runner = RunnerWithDigest("sha256:" + hex);
        RunControl control;
        // Bundle carries the raw digest (no prefix): matches after
        // normalization.
        const JsonValue bundle = MakeModelBundle("test-model", hex, true);
        RunControlInputs in = Snapshot(0, 0, false, false, 0);
        in.has_pending_bundle = true;
        in.pending_bundle = &bundle;
        const RunControlOutcome out = control.Step(runner, in);
        CHECK(out.pending_outcome == "installed",
              "matching model profile installs (prefix normalized)");
        CHECK(runner.config_epoch() == 1, "install epoch bumped");
        CHECK(out.pending_reasons.empty(), "no refusal reasons on match");
        std::remove(engine_path.c_str());
    }

    // ---- 21) model mismatch refuses, never asserts the wrong model -------
    {
        SessionRunner runner = RunnerWithDigest(
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        RunControl control;
        const JsonValue bundle = MakeModelBundle(
            "test-model",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            true);
        RunControlInputs in = Snapshot(0, 0, false, false, 0);
        in.has_pending_bundle = true;
        in.pending_bundle = &bundle;
        const RunControlOutcome out = control.Step(runner, in);
        CHECK(out.pending_outcome == "refused",
              "mismatched engine digest refuses the install");
        CHECK(out.pending_reasons.size() == 1 &&
                  out.pending_reasons[0] == "model_profile_mismatch",
              "refusal names the model mismatch");
        CHECK(runner.config_epoch() == 0 && !runner.config_applied(),
              "refused install installs nothing");
        // Disabled profiles never constrain (even when mismatched).
        const JsonValue disabled = MakeModelBundle(
            "test-model",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            false);
        RunControlInputs free = Snapshot(0, 0, false, false, 0);
        free.has_pending_bundle = true;
        free.pending_bundle = &disabled;
        CHECK(control.Step(runner, free).pending_outcome == "installed",
              "disabled profiles do not constrain the install");
        // Malformed model entries fail the parse (fail closed).
        JsonValue bad_id = MakeModelBundle("", std::string(64, 'a'), true);
        RunControlInputs bad_in = Snapshot(0, 0, false, false, 0);
        bad_in.has_pending_bundle = true;
        bad_in.pending_bundle = &bad_id;
        CHECK(control.Step(runner, bad_in).pending_outcome == "parse_failed",
              "empty model_id fails closed");
    }

    // ---- 22) no engine + constrained bundle refuses; unconstrained ok ----
    {
        ModelProfile bare = Profile();
        bare.model_digest = "";  // no engine loaded: nothing bound
        SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, bare);
        RunControl control;
        const JsonValue constrained = MakeModelBundle(
            "test-model", std::string(64, 'c'), true);
        RunControlInputs in = Snapshot(0, 0, false, false, 0);
        in.has_pending_bundle = true;
        in.pending_bundle = &constrained;
        const RunControlOutcome refused = control.Step(runner, in);
        CHECK(refused.pending_outcome == "refused" &&
                  refused.pending_reasons.size() == 1 &&
                  refused.pending_reasons[0] == "model_profile_mismatch",
              "no engine cannot satisfy an enabled model profile");
        // Legacy unconstrained bundles still install without an engine.
        const JsonValue plain = MakeEmptyBundle();
        RunControlInputs free = Snapshot(0, 0, false, false, 1);
        free.has_pending_bundle = true;
        free.pending_bundle = &plain;
        CHECK(control.Step(runner, free).pending_outcome == "installed",
              "unconstrained bundle installs with no engine");
        // And no engine means no successful observation (kNotRun ticks
        // carry no scene evidence, never an empty-digest observation).
        OkDetector detector;
        FramePacket frame = MakeFrame(1, 10 * kMs);
        const auto tick =
            runner.ProcessFrame(frame, MakeCtx(frame), nullptr);
        CHECK(tick.detection_status == DetectionStatus::kNotRun,
              "no detector means no inference attempt");
        CHECK(runner.Snapshot().input_state.engine == "absent",
              "engine reports absent with no detector bound");
        // A kNotRun tick folds no observation (never an empty-digest
        // stamp), but the tick itself is still counted, not lost.
        ksi::safety::MopTickEvidence no_engine_evidence;
        const auto no_obs = no_engine_evidence.BuildTick(tick);
        CHECK(!no_obs.has_observation,
              "kNotRun tick builds no observation");
        CHECK(no_engine_evidence.ticks_seen() == 1,
              "skipped tick still counted, not lost");
    }

    // ---- 20) pending + generation in one poll: single install --------
    // A new pending bundle and a new applied generation arriving together
    // install exactly once (the generation edge is consumed silently after
    // a pending install): no epoch bump, no counter reset, no double
    // install — so the audit trail cannot double-count one decision.
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs both = WithBundle(Snapshot(0, 0, false, false, 0),
                                           &bundle, true, 1);
        both.has_pending_bundle = true;
        both.pending_bundle = &bundle;
        const RunControlOutcome out = control.Step(runner, both);
        CHECK(out.bundle_installed && out.pending_outcome == "installed",
              "pending path installs");
        CHECK(runner.config_epoch() == 1,
              "exactly one install per poll (epoch 1, not 2)");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "runner holds the single install");
    }

    // ---- 21) failed confirm publishes the CURRENT parked facts ------------
    // The adapter aborts on an identity-moved confirm and must publish the
    // post-abort snapshot — never the stale pre-abort install facts.
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs install = WithBundle(Snapshot(0, 0, false, false, 1),
                                              &bundle, true, 1);
        install.has_pending_bundle = true;
        install.pending_bundle = &bundle;
        CHECK(control.Step(runner, install).pending_outcome == "installed",
              "installed before the (simulated) identity move");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 1))
                  .start_requested,
              "started");
        OkDetector detector;
        FramePacket frame = MakeFrame(7, 70 * kMs);
        runner.ProcessFrame(frame, MakeCtx(frame), &detector);
        CHECK(runner.processed_frame_count() == 1, "one frame processed");
        const MopRuntimeFacts stale = SnapshotToFacts(runner.Snapshot());
        // Adapter path on failed confirm: abort, then publish the CURRENT
        // snapshot — not the pre-abort install facts.
        runner.AbortRuntime();
        const MopRuntimeFacts current = SnapshotToFacts(runner.Snapshot());
        CHECK(current.runtime_state == "STOPPED",
              "post-abort facts report STOPPED");
        CHECK(current.config_applied, "install retained, run parked");
        CHECK(current.processed_frame_count == 1,
              "processed count preserved through the park");
        CHECK(stale.runtime_state != current.runtime_state,
              "stale pre-abort facts differ from the published ones");
        CHECK(stale.processed_frame_count ==
                  current.processed_frame_count,
              "counts agree; only the state moved");
    }

    // ---- 17) deterministic: same inputs -> same outputs ------------------
    {
        const JsonValue bundle = MakeEmptyBundle();
        const JsonValue bad = MakeMalformedBundle();
        auto drive = [&](SessionRunner& runner, RunControl& control,
                         std::vector<RunControlOutcome>* outs) {
            outs->push_back(control.Step(runner, NoEdges()));
            outs->push_back(
                control.Step(runner, WithBundle(Snapshot(0, 0, false, false, 0),
                                                   &bundle, true, 1)));
            outs->push_back(
                control.Step(runner, Snapshot(1, 0, true, true, 1)));
            OkDetector detector;
            FramePacket frame = MakeFrame(3, 30 * kMs);
            runner.ProcessFrame(frame, MakeCtx(frame), &detector);
            outs->push_back(
                control.Step(runner, Snapshot(1, 1, false, false, 1)));
            outs->push_back(
                control.Step(runner,
                               WithBundle(Snapshot(1, 0, true, true, 1), &bad,
                                          true, 2)));
            RunControlInputs rebooted = WithBundle(
                Snapshot(0, 0, false, false, 0, 1), nullptr, false, 3);
            rebooted.command_epoch = 1;
            outs->push_back(control.Step(runner, rebooted));
        };
        SessionRunner first = FreshRunner();
        SessionRunner second = FreshRunner();
        RunControl control_a;
        RunControl control_b;
        std::vector<RunControlOutcome> outs_a;
        std::vector<RunControlOutcome> outs_b;
        drive(first, control_a, &outs_a);
        drive(second, control_b, &outs_b);
        CHECK(outs_a.size() == outs_b.size(), "same number of outcomes");
        for (std::size_t i = 0; i < outs_a.size() && i < outs_b.size();
             ++i) {
            CHECK(OutcomesEqual(outs_a[i], outs_b[i]),
                  "identical inputs give identical outcomes");
        }
        CHECK(SnapshotsEqual(first.Snapshot(), second.Snapshot()),
              "identical inputs give identical runner state");
    }

    // ---- 18) pending install (deferred device apply) -----------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs in = Snapshot(0, 0, false, false, 0);
        in.has_pending_bundle = true;
        in.pending_bundle = &bundle;
        const RunControlOutcome out = control.Step(runner, in);
        CHECK(out.pending_outcome == "installed",
              "pending bundle installs through the pending path");
        CHECK(!out.install_refused && !out.bundle_parse_failed,
              "clean pending install reports no failure");
        CHECK(runner.runtime_state() == RuntimeState::kConfigApplied,
              "pending install parks at CONFIG_APPLIED");
        CHECK(runner.config_epoch() == 1, "pending install is epoch 1");
        // The service confirms synchronously; a repeated poll with the
        // pending still present attempts again (same config, new epoch) —
        // never silently skipped.
        const RunControlOutcome again = control.Step(runner, in);
        CHECK(again.pending_outcome == "installed",
              "unconfirmed pending is attempted every poll");
    }

    // ---- 19) pending refused while live -------------------------------------
    {
        SessionRunner runner = FreshRunner();
        RunControl control;
        const JsonValue bundle = MakeEmptyBundle();
        RunControlInputs install = Snapshot(0, 0, false, false, 0);
        install.has_pending_bundle = true;
        install.pending_bundle = &bundle;
        CHECK(control.Step(runner, install).pending_outcome == "installed",
              "first pending install lands");
        CHECK(control.Step(runner, Snapshot(1, 0, true, true, 0))
                  .start_requested,
              "started");
        const std::string hash_before = runner.effective_config_hash();
        RunControlInputs live = Snapshot(1, 0, true, true, 0);
        live.has_pending_bundle = true;
        live.pending_bundle = &bundle;
        const RunControlOutcome refused = control.Step(runner, live);
        CHECK(refused.install_refused && !refused.bundle_installed &&
                  refused.pending_outcome != "installed",
              "live pending install refused, never half-installed");
        CHECK(runner.effective_config_hash() == hash_before &&
                  runner.config_epoch() == 1,
              "refused pending keeps the old install and epoch");
        CHECK(refused.facts.effective_config_hash == hash_before,
              "refused pending publishes the OLD hash, never relabelled");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
