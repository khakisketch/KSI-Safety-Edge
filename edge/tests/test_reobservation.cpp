// Host test for the bounded active-camera reobservation controller
// (edge/include/ksi_safety/observation/reobservation.hpp).
//
// Fixture is explicitly SIMULATED: FakeCameraPort, injected clock/data only.
// No PSDK, no device, no trained helmet model. The "helmet-suspect" inputs
// below are hand-made structs with an explicit require_more_detail flag —
// this unit never runs a PPE classifier and never confirms PPE risk.
//
// Part 1: prior behavior (normal path, skips, refusals, fencing, timeouts).
// Part 2: adversarial coverage for the review holes — generation/task
// fencing, report/video freshness, future/pre-task timestamps, post-settle
// match binding, uncertain-timeout idempotency, bounded history, rollbacks,
// overflow, invalid configs, holds without auto-resume, reboot semantics.

#include "ksi_safety/observation/reobservation.hpp"

#include <cstdint>
#include <string>
#include <type_traits>
#include <utility>

#include "support/check.hpp"

using ksi::safety::observation::ActionRecord;
using ksi::safety::observation::Assessment;
using ksi::safety::observation::AutoAllowance;
using ksi::safety::observation::CameraGrant;
using ksi::safety::observation::CameraOwner;
using ksi::safety::observation::IdentityContext;
using ksi::safety::observation::ImageQuality;
using ksi::safety::observation::ImagingIntent;
using ksi::safety::observation::InjectedCameraPort;
using ksi::safety::observation::LensState;
using ksi::safety::observation::ObservedFrame;
using ksi::safety::observation::PortCapabilities;
using ksi::safety::observation::ReobserveConfig;
using ksi::safety::observation::ReobservationController;
using ksi::safety::observation::StartOutcome;
using ksi::safety::observation::Supervision;
using ksi::safety::observation::SuspectTarget;
using ksi::safety::observation::TargetMatch;
using ksi::safety::observation::TargetProvenance;
using ksi::safety::observation::TaskOutcome;
using ksi::safety::observation::TaskState;
using ksi::safety::observation::ValidateConfig;

// ---- by-construction API absence: any such member breaks the build ----
template <typename T, typename = void>
struct HasStopAnalysis : std::false_type {};
template <typename T>
struct HasStopAnalysis<
    T, std::void_t<decltype(std::declval<T&>().StopAnalysis())>>
    : std::true_type {};
template <typename T, typename = void>
struct HasWaypoint : std::false_type {};
template <typename T>
struct HasWaypoint<
    T, std::void_t<decltype(std::declval<T&>().RequestWaypoint(0))>>
    : std::true_type {};
template <typename T, typename = void>
struct HasPatrol : std::false_type {};
template <typename T>
struct HasPatrol<T,
                 std::void_t<decltype(std::declval<T&>().PausePatrol())>>
    : std::true_type {};

static_assert(!HasStopAnalysis<ReobservationController>::value,
              "no passive-analysis stop may exist on this unit");
static_assert(!HasWaypoint<ReobservationController>::value,
              "no flight/waypoint API may exist on this unit");
static_assert(!HasPatrol<ReobservationController>::value,
              "no patrol pause/cancel API may exist on this unit");

namespace {

constexpr std::int64_t kMs = 1000000LL;

class FakeCameraPort : public InjectedCameraPort {
  public:
    PortCapabilities caps;
    int requests = 0;
    int cancels = 0;
    std::uint64_t last_task = 0;
    std::uint64_t last_generation = 0;
    ImagingIntent last_intent;
    bool transport_ok = true;

    PortCapabilities capabilities() const override { return caps; }
    bool Request(std::uint64_t task_id, std::uint64_t generation,
                 const ImagingIntent& intent) override {
        ++requests;
        last_task = task_id;
        last_generation = generation;
        last_intent = intent;
        return transport_ok;
    }
    void Cancel(std::uint64_t task_id, std::uint64_t generation) override {
        ++cancels;
        last_task = task_id;
        last_generation = generation;
    }
};

PortCapabilities FullCaps() {
    PortCapabilities caps;
    caps.zoom_supported = true;
    caps.gimbal_supported = true;
    caps.still_supported = true;
    caps.cancel_supported = true;
    caps.idempotent_retry_supported = false; // strict default: no auto-resend
    caps.min_zoom_step = 0;
    caps.max_zoom_step = 4;
    caps.max_pitch_mdeg = 30000;
    caps.max_yaw_mdeg = 60000;
    return caps;
}

ReobserveConfig TestConfig() {
    ReobserveConfig config;
    config.max_dwell_ns = 8 * 1000 * kMs;
    config.request_timeout_ns = 1500 * kMs;
    config.settle_timeout_ns = 2000 * kMs;
    config.match_timeout_ns = 2000 * kMs;
    config.frame_freshness_ns = 500 * kMs;
    config.state_freshness_ns = 1000 * kMs;
    config.target_freshness_ns = 1000 * kMs;
    config.config_freshness_ns = 5000 * kMs;
    config.report_freshness_ns = 2000 * kMs;
    config.max_retries = 1;
    config.permitted_min_zoom = 0;
    config.permitted_max_zoom = 2;
    config.permitted_max_pitch_mdeg = 15000;
    config.permitted_max_yaw_mdeg = 30000;
    config.max_history_records = 256;
    config.operational_thresholds_confirmed = true; // explicit mock values
    return config;
}

IdentityContext TestIdentity() {
    IdentityContext identity;
    identity.flight_id = "SIM-FLIGHT-1";
    identity.boot_id = "SIM-BOOT-1";
    identity.camera_id = "SIM-CAM-A";
    identity.lens_id = "SIM-LENS-1";
    return identity;
}

CameraGrant LiveGrant(std::uint64_t generation, std::int64_t now_ns) {
    CameraGrant grant;
    grant.generation = generation;
    grant.expires_monotonic_ns = now_ns + 60 * 1000 * kMs;
    grant.identity = TestIdentity();
    grant.allowance = AutoAllowance::kEnabled;
    return grant;
}

SuspectTarget NeedMoreDetail(std::int64_t now_ns) {
    SuspectTarget suspect;  // SIMULATED: hand-made, no trained model.
    suspect.source_id = "sim-pass1-helmet-suspect@v0";
    suspect.target_id = "SIM-T-7";
    suspect.identity = TestIdentity();
    suspect.quality = ImageQuality::kMarginal;
    suspect.require_more_detail = true;
    suspect.wide_context_required = false;
    suspect.observed_monotonic_ns = now_ns;
    suspect.provenance = TargetProvenance::kAmbiguous;
    return suspect;
}

// Full explicit setup: grant + device identity + KSI owner + supervision +
// evidence + one fresh frame. Every live report the gate requires.
void ArmKsi(ReobservationController& control, std::uint64_t generation,
            std::int64_t now_ns) {
    CHECK(control.GrantAuthority(LiveGrant(generation, now_ns), now_ns),
          "grant accepted");
    control.NoteDeviceIdentity(TestIdentity(), now_ns);
    control.NoteActualOwner(CameraOwner::kKsi, now_ns);
    control.NoteSupervision(Supervision::kPresent, now_ns);
    control.NoteEvidenceCapacity(true, now_ns);
    ObservedFrame frame;
    frame.frame_id = 1000 + generation;
    frame.frame_monotonic_ns = now_ns;
    frame.identity = TestIdentity();
    control.OnFrame(frame, now_ns);
}

LensState SettledLens(std::uint64_t task, std::uint64_t generation,
                      int zoom_step, std::int64_t now_ns,
                      std::int64_t observed_ns) {
    LensState state;
    state.task_id = task;
    state.generation = generation;
    state.identity = TestIdentity();
    state.zoom_step = zoom_step;
    state.settled = true;
    state.observed_monotonic_ns = observed_ns;
    (void)now_ns;
    return state;
}

ObservedFrame MakeFrame(std::uint64_t frame_id, std::int64_t frame_ns) {
    ObservedFrame frame;
    frame.frame_id = frame_id;
    frame.frame_monotonic_ns = frame_ns;
    frame.identity = TestIdentity();
    return frame;
}

TargetMatch BoundMatch(std::uint64_t task, std::uint64_t generation,
                       std::int64_t now_ns, std::uint64_t frame_id,
                       std::int64_t frame_ns) {
    TargetMatch match;
    match.task_id = task;
    match.generation = generation;
    match.target_id = "SIM-T-7";
    match.identity = TestIdentity();
    match.provenance = TargetProvenance::kConfirmed;
    match.observed_monotonic_ns = now_ns;
    match.based_on_frame_id = frame_id;
    match.based_on_frame_monotonic_ns = frame_ns;
    return match;
}

// Drives request -> ACK -> settled lens -> stream frame -> (AwaitingMatch),
// then observes one post-settle qualifying frame. Returns via out_* the
// qualifying frame binding the verdict must carry.
void DriveToQualifying(ReobservationController& control, FakeCameraPort& port,
                       std::uint64_t task, std::uint64_t gen,
                       std::int64_t t0, std::uint64_t f_settle,
                       std::uint64_t f_qual, std::uint64_t* out_qual_ns) {
    CHECK(control.RequestReobservation(task, NeedMoreDetail(t0), t0)
              .outcome == TaskOutcome::kInProgress,
          "request sent");
    control.OnRequestAck(task, gen, true, t0 + 100 * kMs);
    control.OnLensState(
        SettledLens(task, gen, port.last_intent.zoom_step, t0 + 200 * kMs,
                    t0 + 200 * kMs),
        t0 + 200 * kMs);
    control.OnFrame(MakeFrame(f_settle, t0 + 210 * kMs), t0 + 210 * kMs);
    CHECK(control.task_state() == TaskState::kAwaitingMatch,
          "driven to match wait");
    control.OnFrame(MakeFrame(f_qual, t0 + 250 * kMs), t0 + 250 * kMs);
    *out_qual_ns = t0 + 250 * kMs;
}

// Re-emits every live report plus a fresh frame: the parent must refresh
// these streams continuously or the controller fails closed.
void RefreshAll(ReobservationController& control, std::uint64_t frame_id,
                std::int64_t now_ns) {
    control.NoteDeviceIdentity(TestIdentity(), now_ns);
    control.NoteActualOwner(CameraOwner::kKsi, now_ns);
    control.NoteSupervision(Supervision::kPresent, now_ns);
    control.NoteEvidenceCapacity(true, now_ns);
    control.OnFrame(MakeFrame(frame_id, now_ns), now_ns);
}

bool Contains(const std::string& haystack, const std::string& needle) {
    return haystack.find(needle) != std::string::npos;
}

}  // namespace

int main() {
    // ================= Part 1: prior behavior =================
    // ---- 1) normal path: ACK + settled lens + post-settle bound CONFIRMED --
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 1000 * kMs;
        ArmKsi(control, 11, t0);
        const int requests_before = port.requests;
        StartOutcome out =
            control.RequestReobservation(1, NeedMoreDetail(t0), t0);
        CHECK(out.outcome == TaskOutcome::kInProgress, "request sent");
        CHECK(port.requests == requests_before + 1, "exactly one port call");
        CHECK(port.last_intent.zoom_step >= 1 &&
                  port.last_intent.zoom_step <= 2,
              "one permitted zoom step picked");
        control.OnRequestAck(1, 11, true, t0 + 100 * kMs);
        CHECK(control.task_state() == TaskState::kAwaitingSettle,
              "ACK moves to settle");
        control.OnLensState(SettledLens(1, 11, port.last_intent.zoom_step,
                                        t0 + 200 * kMs, t0 + 200 * kMs),
                            t0 + 200 * kMs);
        control.OnFrame(MakeFrame(10, t0 + 210 * kMs), t0 + 210 * kMs);
        CHECK(control.task_state() == TaskState::kAwaitingMatch,
              "settle plus frames moves to match");
        control.OnFrame(MakeFrame(11, t0 + 250 * kMs), t0 + 250 * kMs);
        control.OnTargetMatch(
            BoundMatch(1, 11, t0 + 300 * kMs, 11, t0 + 250 * kMs),
            t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kSucceededVerified,
              "verified CONFIRMED match succeeds");
        CHECK(control.assessment() == Assessment::kAvailable,
              "assessment available (never PPE risk confirmed)");
        const std::string snap = control.Snapshot();
        CHECK(!Contains(snap, "incident") && !Contains(snap, "alert") &&
                  !Contains(snap, "human_response"),
              "no human/incident/alert vocabulary in records");
    }

    // ---- 2) no need: require_more_detail=false -> SKIPPED, zero output ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 2000 * kMs;
        ArmKsi(control, 21, t0);
        SuspectTarget suspect = NeedMoreDetail(t0);
        suspect.require_more_detail = false;
        StartOutcome out = control.RequestReobservation(2, suspect, t0);
        CHECK(out.outcome == TaskOutcome::kSkipped, "no-need skipped");
        CHECK(port.requests == 0, "skipped emits zero camera output");
        CHECK(control.assessment() == Assessment::kUnavailable,
              "skip teaches nothing");
    }

    // ---- 3) danger proximity: wide context required -> SKIPPED ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 3000 * kMs;
        ArmKsi(control, 31, t0);
        SuspectTarget suspect = NeedMoreDetail(t0);
        suspect.wide_context_required = true;  // keep wide view, no zoom
        StartOutcome out = control.RequestReobservation(3, suspect, t0);
        CHECK(out.outcome == TaskOutcome::kSkipped, "wide-context skipped");
        CHECK(port.requests == 0, "no zoom that would harm proximity watch");
    }

    // ---- 4) no authority at all -> rejected, zero output ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 4000 * kMs;
        StartOutcome out = control.RequestReobservation(4, NeedMoreDetail(t0),
                                                        t0);
        CHECK(out.outcome == TaskOutcome::kRejected, "no grant rejected");
        CHECK(out.reason == "no_authority", "reason names missing authority");
        CHECK(port.requests == 0, "refused emits zero camera output");
    }

    // ---- 5) grant but owner never reported (UNKNOWN) -> do not actuate ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 5000 * kMs;
        CHECK(control.GrantAuthority(LiveGrant(51, t0), t0),
              "grant accepted");
        control.NoteDeviceIdentity(TestIdentity(), t0);
        // No NoteActualOwner: owner unproven -> must not move.
        StartOutcome out = control.RequestReobservation(5, NeedMoreDetail(t0),
                                                        t0);
        CHECK(out.outcome == TaskOutcome::kRejected, "unknown owner refused");
        CHECK(out.reason == "owner_not_ksi", "reason names the owner guard");
        CHECK(port.requests == 0, "unproven owner means zero output");
    }

    // ---- 6) unsupported intent (no zoom support) -> rejected ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        port.caps.zoom_supported = false;
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 6000 * kMs;
        ArmKsi(control, 61, t0);
        StartOutcome out = control.RequestReobservation(6, NeedMoreDetail(t0),
                                                        t0);
        CHECK(out.outcome == TaskOutcome::kRejected, "unsupported refused");
        CHECK(out.reason == "intent_unsupported", "reason names capability");
        CHECK(port.requests == 0, "unsupported emits zero camera output");
    }

    // ---- 7) explicit ACK rejection with bounded retries (max_retries=1) ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobserveConfig config = TestConfig();
        config.max_retries = 1;
        ReobservationController control(config, &port);
        const std::int64_t t0 = 7000 * kMs;
        ArmKsi(control, 71, t0);
        CHECK(control.RequestReobservation(7, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        CHECK(port.requests == 1, "first attempt sent");
        control.OnRequestAck(7, 71, false, t0 + 100 * kMs);
        CHECK(port.requests == 2, "exactly one bounded retry");
        CHECK(control.task_state() == TaskState::kAwaitingAck,
              "still awaiting after first rejection");
        control.OnRequestAck(7, 71, false, t0 + 200 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "retries exhausted fails");
        CHECK(port.requests == 2, "zero unbounded retries");
        control.OnRequestAck(7, 71, true, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "late ACK cannot resurrect a failed task");
    }

    // ---- 8) uncertain ACK timeout (no idempotency cap): fail, NO resend ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();  // idempotent_retry_supported == false
        ReobserveConfig config = TestConfig();
        config.max_retries = 1;  // even with budget: uncertain => no resend
        ReobservationController control(config, &port);
        const std::int64_t t0 = 8000 * kMs;
        ArmKsi(control, 81, t0);
        CHECK(control.RequestReobservation(8, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.OnFrame(MakeFrame(810, t0 + 1200 * kMs), t0 + 1200 * kMs);
        control.Poll(t0 + 1500 * kMs + 1);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "uncertain timeout fails with no auto-resend");
        CHECK(port.requests == 1, "no automatic resend of uncertain action");
        CHECK(control.assessment() == Assessment::kUnclear,
              "uncertain timeout is unknown, never success");
        CHECK(Contains(control.Snapshot(), "ack_timeout_uncertain_no_retry"),
              "reason recorded");
    }

    // ---- 9) ACK accepted but no reality -> settle timeout, truth kept ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 9000 * kMs;
        ArmKsi(control, 91, t0);
        CHECK(control.RequestReobservation(9, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.OnRequestAck(9, 91, true, t0 + 100 * kMs);
        RefreshAll(control, 910, t0 + 2000 * kMs);  // keep stream+reports live
        control.Poll(t0 + 100 * kMs + 2000 * kMs + 1);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "ACK alone never succeeds without observed reality");
        CHECK(control.task_outcome() != TaskOutcome::kSucceededVerified,
              "failure truth preserved");
    }

    // ---- 10) manual takeover mid-request: fence, cancel, late ACK fenced --
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 10000 * kMs;
        ArmKsi(control, 101, t0);
        CHECK(control.RequestReobservation(10, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.NoteActualOwner(CameraOwner::kHuman, t0 + 50 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "manual takeover cancels the inflight task");
        CHECK(port.cancels == 1, "continuous action cancelled via port");
        CHECK(control.authority_valid() == false, "authority invalidated");
        const int cancels = port.cancels;
        control.OnRequestAck(10, 101, true, t0 + 100 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "delayed ACK fenced, task stays cancelled");
        CHECK(port.cancels == cancels, "fenced callback actuates nothing");
        CHECK(port.requests == 1, "no new camera output after takeover");
    }

    // ---- 11) DJI mission reservation suppresses KSI motion ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 11000 * kMs;
        ArmKsi(control, 111, t0);
        control.NoteActualOwner(CameraOwner::kDjiMission, t0 + 10 * kMs);
        StartOutcome out =
            control.RequestReobservation(11, NeedMoreDetail(t0 + 20 * kMs),
                                         t0 + 20 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected, "mission suppresses");
        CHECK(port.requests == 0, "no conflicting request issued");
    }

    // ---- 12) target LOST / AMBIGUOUS / wrong-id / stale-HELD ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 12000 * kMs;
        ArmKsi(control, 121, t0);
        std::uint64_t qual_ns = 0;
        DriveToQualifying(control, port, 12, 121, t0, 112, 212, &qual_ns);
        TargetMatch lost = BoundMatch(12, 121, t0 + 300 * kMs, 212,
                                      static_cast<std::int64_t>(qual_ns));
        lost.provenance = TargetProvenance::kLost;
        control.OnTargetMatch(lost, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "lost target fails");
        CHECK(control.assessment() == Assessment::kUnclear,
              "lost target is unclear, never confirmed");
    }
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 13000 * kMs;
        ArmKsi(control, 131, t0);
        std::uint64_t qual_ns = 0;
        DriveToQualifying(control, port, 13, 131, t0, 113, 213, &qual_ns);
        TargetMatch ambiguous = BoundMatch(13, 131, t0 + 300 * kMs, 213,
                                           static_cast<std::int64_t>(qual_ns));
        ambiguous.provenance = TargetProvenance::kAmbiguous;
        control.OnTargetMatch(ambiguous, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "ambiguous match fails");
        CHECK(control.assessment() == Assessment::kUnclear,
              "similar person never confirmed as the origin target");
    }
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 14000 * kMs;
        ArmKsi(control, 141, t0);
        std::uint64_t qual_ns = 0;
        DriveToQualifying(control, port, 14, 141, t0, 114, 214, &qual_ns);
        TargetMatch impostor = BoundMatch(14, 141, t0 + 300 * kMs, 214,
                                           static_cast<std::int64_t>(qual_ns));
        impostor.target_id = "SIM-T-OTHER";  // same look, different identity
        control.OnTargetMatch(impostor, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "same-id assumption rejected: wrong id never chases");
    }
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 15000 * kMs;
        ArmKsi(control, 151, t0);
        std::uint64_t qual_ns = 0;
        DriveToQualifying(control, port, 15, 151, t0, 115, 215, &qual_ns);
        // Observed after task start but older than the freshness window.
        TargetMatch stale = BoundMatch(15, 151, t0 + 1500 * kMs, 215,
                                       static_cast<std::int64_t>(qual_ns));
        stale.observed_monotonic_ns = t0 + 100 * kMs;
        control.OnTargetMatch(stale, t0 + 1500 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "stale observation never chases a HELD box");
        CHECK(Contains(control.Snapshot(), "match_observation_stale"),
              "stale reason recorded, not silently dropped");
    }

    // ---- 13) supervision loss: motion stops, analysis continues ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 16000 * kMs;
        ArmKsi(control, 161, t0);
        CHECK(control.RequestReobservation(16, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        bool passive_analysis_running = true;  // parent-owned, external
        control.NoteSupervision(Supervision::kLost, t0 + 50 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "supervision loss cancels motion");
        CHECK(passive_analysis_running == true,
              "passive analysis continues (unit has no stop for it)");
        CHECK(control.authority_valid() == false,
              "no hidden auto-resume: fresh grant required");
        StartOutcome again = control.RequestReobservation(
            17, NeedMoreDetail(t0 + 60 * kMs), t0 + 60 * kMs);
        CHECK(again.outcome == TaskOutcome::kRejected,
              "no motion without a fresh explicit grant");
    }

    // ---- 14) stale video halts new motion ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 17000 * kMs;
        ArmKsi(control, 171, t0);
        // No new frames arrive: at +600ms the t0 frame is beyond the
        // freshness window, so motion is refused. (An old frame stamp can
        // never regress freshness either — it is simply ignored.)
        control.OnFrame(MakeFrame(200, t0 - 5000 * kMs), t0);
        StartOutcome out = control.RequestReobservation(
            18, NeedMoreDetail(t0 + 600 * kMs), t0 + 600 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected, "stale video halts");
        CHECK(out.reason == "stale_video_halts_motion",
              "reason names stale video");
        CHECK(port.requests == 0, "blind motion never issued");
    }

    // ---- 15) stale suspect config -> skip ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 18000 * kMs;
        ArmKsi(control, 181, t0);
        SuspectTarget suspect = NeedMoreDetail(t0 - 60 * 1000 * kMs);
        StartOutcome out = control.RequestReobservation(19, suspect, t0);
        CHECK(out.outcome == TaskOutcome::kSkipped, "stale config skipped");
        CHECK(port.requests == 0, "stale config moves nothing");
    }

    // ---- 16) repeated grant fences the old task; late callbacks fenced ---
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 19000 * kMs;
        ArmKsi(control, 191, t0);
        CHECK(control.RequestReobservation(20, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "first task sent");
        CHECK(control.GrantAuthority(LiveGrant(192, t0 + 10 * kMs),
                                     t0 + 10 * kMs),
              "fresh advancing grant fences the old task");
        CHECK(control.task_state() == TaskState::kIdle,
              "new generation starts idle after fencing old task");
        CHECK(Contains(control.Snapshot(), "task_cancelled"),
              "old task cancellation kept in history");
        control.OnRequestAck(20, 191, true, t0 + 20 * kMs);
        CHECK(control.task_state() == TaskState::kIdle,
              "old-generation ACK fenced");
        control.NoteDeviceIdentity(TestIdentity(), t0 + 20 * kMs);
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 20 * kMs);
        StartOutcome out = control.RequestReobservation(
            21, NeedMoreDetail(t0 + 20 * kMs), t0 + 20 * kMs);
        CHECK(out.outcome == TaskOutcome::kInProgress,
              "new generation can start its own task");
        CHECK(port.last_generation == 192, "new task carries new generation");
    }

    // ---- 17) device epoch change invalidates authority ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 20000 * kMs;
        ArmKsi(control, 201, t0);
        CHECK(control.RequestReobservation(22, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        IdentityContext rebooted = TestIdentity();
        rebooted.boot_id = "SIM-BOOT-2";  // device rebooted under us
        control.NoteDeviceIdentity(rebooted, t0 + 50 * kMs);
        CHECK(control.authority_valid() == false, "epoch change invalidates");
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "inflight task fenced on epoch change");
    }

    // ---- 18) grant expiry via Poll with zero frames ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 21000 * kMs;
        CameraGrant short_grant = LiveGrant(211, t0);
        short_grant.expires_monotonic_ns = t0 + 100 * kMs;
        CHECK(control.GrantAuthority(short_grant, t0), "short grant live");
        control.NoteDeviceIdentity(TestIdentity(), t0);
        control.NoteActualOwner(CameraOwner::kKsi, t0);
        control.NoteSupervision(Supervision::kPresent, t0);
        control.NoteEvidenceCapacity(true, t0);
        control.OnFrame(MakeFrame(2110, t0), t0);
        control.Poll(t0 + 101 * kMs);  // expiry needs no new frames
        CHECK(control.authority_valid() == false, "expired grant dropped");
    }

    // ---- 19) explicit-reject retry ceiling (max_retries=3) ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobserveConfig config = TestConfig();
        config.max_retries = 3;
        ReobservationController control(config, &port);
        const std::int64_t t0 = 22000 * kMs;
        ArmKsi(control, 221, t0);
        CHECK(control.RequestReobservation(23, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        for (int i = 0; i < 10; ++i) {
            if (control.task_state() == TaskState::kDone) break;
            control.OnRequestAck(23, 221, false,
                                 t0 + (100 + i * 100) * kMs);
        }
        CHECK(port.requests == 1 + ReobserveConfig::kMaxRetriesCeiling,
              "retries never exceed the ceiling");
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "bounded retries end in failure, not forever");
    }

    // ---- 20) evidence exhaustion drops authority; no auto resume ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 23000 * kMs;
        ArmKsi(control, 231, t0);
        CHECK(control.RequestReobservation(24, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.NoteEvidenceCapacity(false, t0 + 10 * kMs);  // store overload
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "exhaustion cancels active motion");
        CHECK(control.authority_valid() == false, "authority dropped");
        CHECK(Contains(control.Snapshot(), "evidence_capacity_lost"),
              "cause recorded");
        StartOutcome out = control.RequestReobservation(
            25, NeedMoreDetail(t0 + 20 * kMs), t0 + 20 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected,
              "no motion without a fresh grant after exhaustion");
        CHECK(port.requests == 1, "no actuation without evidence room");
    }

    // ---- 21) reboot cancels inflight FIRST, resets reports, keeps gens ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 24000 * kMs;
        ArmKsi(control, 241, t0);
        CHECK(control.RequestReobservation(26, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        const std::size_t records_before = control.history().size();
        CHECK(records_before > 0, "history recorded before reboot");
        control.OnReboot(t0 + 50 * kMs);
        CHECK(port.cancels == 1, "reboot cancelled inflight before drop");
        CHECK(control.authority_valid() == false, "reboot drops authority");
        CHECK(control.task_state() == TaskState::kIdle,
              "reboot drops the live task");
        CHECK(control.history().size() > records_before,
              "cancel + reboot kept in history");
        // Stale pre-reboot generation can never come back.
        CHECK(!control.GrantAuthority(LiveGrant(241, t0 + 60 * kMs),
                                      t0 + 60 * kMs),
              "pre-reboot generation rejected after reboot");
        StartOutcome out = control.RequestReobservation(
            27, NeedMoreDetail(t0 + 60 * kMs), t0 + 60 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected,
              "reboot never restores motion authority");
        // Full fresh setup on a NEW generation works again.
        CHECK(control.GrantAuthority(LiveGrant(242, t0 + 70 * kMs),
                                     t0 + 70 * kMs),
              "new generation accepted");
        control.NoteDeviceIdentity(TestIdentity(), t0 + 70 * kMs);
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 70 * kMs);
        control.NoteSupervision(Supervision::kPresent, t0 + 70 * kMs);
        control.NoteEvidenceCapacity(true, t0 + 70 * kMs);
        control.OnFrame(MakeFrame(2420, t0 + 70 * kMs), t0 + 70 * kMs);
        CHECK(control.RequestReobservation(28, NeedMoreDetail(t0 + 70 * kMs),
                                            t0 + 70 * kMs)
                  .outcome == TaskOutcome::kInProgress,
              "fresh epoch setup actuates again");
    }

    // ---- 22) late cancel ACK cannot mutate the new task ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 25000 * kMs;
        ArmKsi(control, 251, t0);
        CHECK(control.RequestReobservation(29, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "first task sent");
        control.NoteActualOwner(CameraOwner::kHuman, t0 + 10 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kCancelled,
              "first task cancelled by manual input");
        CHECK(control.GrantAuthority(LiveGrant(252, t0 + 20 * kMs),
                                     t0 + 20 * kMs),
              "fresh explicit grant after manual");
        control.NoteDeviceIdentity(TestIdentity(), t0 + 20 * kMs);
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 20 * kMs);
        CHECK(control.RequestReobservation(30, NeedMoreDetail(t0 + 20 * kMs),
                                            t0 + 20 * kMs)
                  .outcome == TaskOutcome::kInProgress,
              "second task sent under the fresh grant");
        control.OnRequestAck(29, 251, true, t0 + 30 * kMs);  // late, old task
        CHECK(control.active_task_id() == 30,
              "late old-task ACK never touches the new task");
        CHECK(control.task_state() == TaskState::kAwaitingAck,
              "new task still awaits its own ACK");
    }

    // ---- 23) determinism: identical sequences, identical snapshots ----
    {
        auto drive = []() {
            FakeCameraPort port;
            port.caps = FullCaps();
            ReobservationController control(TestConfig(), &port);
            const std::int64_t t0 = 26000 * kMs;
            ArmKsi(control, 261, t0);
            control.RequestReobservation(31, NeedMoreDetail(t0), t0);
            control.OnRequestAck(31, 261, true, t0 + 100 * kMs);
            control.OnLensState(
                SettledLens(31, 261, 1, t0 + 200 * kMs, t0 + 200 * kMs),
                t0 + 200 * kMs);
            control.OnFrame(MakeFrame(310, t0 + 210 * kMs), t0 + 210 * kMs);
            control.OnFrame(MakeFrame(311, t0 + 250 * kMs), t0 + 250 * kMs);
            control.OnTargetMatch(
                BoundMatch(31, 261, t0 + 300 * kMs, 311, t0 + 250 * kMs),
                t0 + 300 * kMs);
            return control.Snapshot();
        };
        CHECK(drive() == drive(), "deterministic snapshot");
    }

    // ---- 24) identity mismatch on suspect refuses, never chases ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 27000 * kMs;
        ArmKsi(control, 271, t0);
        SuspectTarget suspect = NeedMoreDetail(t0);
        suspect.identity.camera_id = "SIM-CAM-OTHER";
        StartOutcome out = control.RequestReobservation(32, suspect, t0);
        CHECK(out.outcome == TaskOutcome::kRejected,
              "identity mismatch refused");
        CHECK(out.reason == "identity_mismatch", "reason names the guard");
        CHECK(port.requests == 0, "mismatched identity moves nothing");
    }

    // ================= Part 2: adversarial review holes =================
    // ---- A) generations strictly advance; lens_id required ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 30000 * kMs;
        CHECK(control.GrantAuthority(LiveGrant(301, t0), t0), "gen 301 live");
        CHECK(!control.GrantAuthority(LiveGrant(301, t0 + 1 * kMs),
                                      t0 + 1 * kMs),
              "replayed generation rejected");
        CHECK(!control.GrantAuthority(LiveGrant(300, t0 + 2 * kMs),
                                      t0 + 2 * kMs),
              "decreasing generation rejected");
        CHECK(control.authority_valid() == false, "authority stays invalid");
        CameraGrant no_lens = LiveGrant(302, t0 + 3 * kMs);
        no_lens.identity.lens_id.clear();  // was never required before
        CHECK(!control.GrantAuthority(no_lens, t0 + 3 * kMs),
              "lens-less grant rejected");
        CHECK(Contains(control.Snapshot(), "grant_generation_not_advancing"),
              "replay reason recorded");
    }

    // ---- B) stale grant cannot restore a manually released camera ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 31000 * kMs;
        ArmKsi(control, 311, t0);
        control.NoteActualOwner(CameraOwner::kHuman, t0 + 10 * kMs);
        CHECK(control.authority_valid() == false, "manual release drops");
        CHECK(!control.GrantAuthority(LiveGrant(311, t0 + 20 * kMs),
                                      t0 + 20 * kMs),
              "stale generation cannot restore manual release");
        CHECK(control.authority_valid() == false, "still released");
        // A fresh generation is accepted but the human-owned camera
        // still gates motion.
        CHECK(control.GrantAuthority(LiveGrant(312, t0 + 30 * kMs),
                                     t0 + 30 * kMs),
              "fresh generation accepted");
        control.NoteDeviceIdentity(TestIdentity(), t0 + 30 * kMs);
        control.NoteSupervision(Supervision::kPresent, t0 + 30 * kMs);
        control.NoteEvidenceCapacity(true, t0 + 30 * kMs);
        control.OnFrame(MakeFrame(3120, t0 + 30 * kMs), t0 + 30 * kMs);
        StartOutcome held = control.RequestReobservation(
            33, NeedMoreDetail(t0 + 30 * kMs), t0 + 30 * kMs);
        CHECK(held.outcome == TaskOutcome::kRejected &&
                  held.reason == "owner_not_ksi",
              "human ownership still gates after fresh grant");
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 40 * kMs);
        StartOutcome free = control.RequestReobservation(
            34, NeedMoreDetail(t0 + 40 * kMs), t0 + 40 * kMs);
        CHECK(free.outcome == TaskOutcome::kInProgress,
              "returned camera plus fresh grant actuates");
    }

    // ---- C) layered fresh-report gating from safe defaults ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 32000 * kMs;
        CHECK(control.GrantAuthority(LiveGrant(321, t0), t0), "grant live");
        control.NoteDeviceIdentity(TestIdentity(), t0);
        // Fail-closed defaults: no owner yet, supervision Lost, no
        // evidence, no video.
        StartOutcome o1 = control.RequestReobservation(40, NeedMoreDetail(t0),
                                                       t0);
        CHECK(o1.reason == "owner_not_ksi", "unreported owner gates");
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 1 * kMs);
        StartOutcome o2 = control.RequestReobservation(
            41, NeedMoreDetail(t0 + 1 * kMs), t0 + 1 * kMs);
        CHECK(o2.reason == "supervision_lost", "default Lost gates");
        control.NoteSupervision(Supervision::kPresent, t0 + 2 * kMs);
        StartOutcome o3 = control.RequestReobservation(
            42, NeedMoreDetail(t0 + 2 * kMs), t0 + 2 * kMs);
        CHECK(o3.reason == "evidence_capacity_unavailable",
              "default exhaustion gates every action");
        control.NoteEvidenceCapacity(true, t0 + 3 * kMs);
        StartOutcome o4 = control.RequestReobservation(
            43, NeedMoreDetail(t0 + 3 * kMs), t0 + 3 * kMs);
        CHECK(o4.reason == "no_fresh_video", "absent video gates");
        control.OnFrame(MakeFrame(3210, t0 + 4 * kMs), t0 + 4 * kMs);
        StartOutcome o5 = control.RequestReobservation(
            44, NeedMoreDetail(t0 + 4 * kMs), t0 + 4 * kMs);
        CHECK(o5.outcome == TaskOutcome::kInProgress,
              "all fresh reports plus video actuate");
        CHECK(port.requests == 1, "exactly one actuation after full setup");
    }

    // ---- D) reports go stale without refresh, even with zero frames ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 33000 * kMs;
        ArmKsi(control, 331, t0);
        CHECK(control.authority_valid(), "authority live after setup");
        control.Poll(t0 + 2000 * kMs + 1);  // past report window, no refresh
        CHECK(control.authority_valid() == false, "stale reports drop motion");
        CHECK(Contains(control.Snapshot(), "report_stale"),
              "staleness cause recorded");
        StartOutcome out = control.RequestReobservation(
            45, NeedMoreDetail(t0 + 2000 * kMs + 1), t0 + 2000 * kMs + 1);
        CHECK(out.outcome == TaskOutcome::kRejected, "stale setup refuses");
    }

    // ---- E) future timestamps fenced; pre-task stamps never fresh ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 34000 * kMs;
        CHECK(control.GrantAuthority(LiveGrant(341, t0), t0), "grant live");
        control.NoteDeviceIdentity(TestIdentity(), t0);
        control.NoteActualOwner(CameraOwner::kKsi, t0);
        control.NoteSupervision(Supervision::kPresent, t0);
        control.NoteEvidenceCapacity(true, t0);
        // A frame from the "future" must not seed video freshness.
        control.OnFrame(MakeFrame(3410, t0 + 5000 * kMs), t0);
        CHECK(Contains(control.Snapshot(), "frame_future_timestamp"),
              "future frame fenced");
        StartOutcome out = control.RequestReobservation(46, NeedMoreDetail(t0),
                                                        t0);
        CHECK(out.outcome == TaskOutcome::kRejected &&
                  out.reason == "no_fresh_video",
              "future frame never counts as fresh video");
    }
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 35000 * kMs;
        ArmKsi(control, 351, t0);
        CHECK(control.RequestReobservation(47, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.OnRequestAck(47, 351, true, t0 + 100 * kMs);
        // Pre-task lens stamp: ignored, never settles.
        control.OnLensState(SettledLens(47, 351, port.last_intent.zoom_step,
                                        t0 + 150 * kMs, t0 - 1 * kMs),
                            t0 + 150 * kMs);
        CHECK(control.task_state() == TaskState::kAwaitingSettle,
              "pre-task lens never settles");
        CHECK(Contains(control.Snapshot(), "lens_predates_task"),
              "pre-task cause recorded");
        // Future lens stamp: fenced as well.
        control.OnLensState(SettledLens(47, 351, port.last_intent.zoom_step,
                                        t0 + 160 * kMs, t0 + 9000 * kMs),
                            t0 + 160 * kMs);
        CHECK(control.task_state() == TaskState::kAwaitingSettle,
              "future lens never settles");
        // The real lens still completes the task path.
        control.OnLensState(SettledLens(47, 351, port.last_intent.zoom_step,
                                        t0 + 200 * kMs, t0 + 200 * kMs),
                            t0 + 200 * kMs);
        control.OnFrame(MakeFrame(471, t0 + 210 * kMs), t0 + 210 * kMs);
        control.OnFrame(MakeFrame(472, t0 + 250 * kMs), t0 + 250 * kMs);
        control.OnTargetMatch(
            BoundMatch(47, 351, t0 + 300 * kMs, 472, t0 + 250 * kMs),
            t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kSucceededVerified,
              "real stamps still succeed after fenced ones");
    }
    {
        // Future-dated verdict fails closed.
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 36000 * kMs;
        ArmKsi(control, 361, t0);
        std::uint64_t qual_ns = 0;
        DriveToQualifying(control, port, 48, 361, t0, 481, 482, &qual_ns);
        TargetMatch future = BoundMatch(48, 361, t0 + 9000 * kMs, 482,
                                        static_cast<std::int64_t>(qual_ns));
        control.OnTargetMatch(future, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "future verdict fails");
        CHECK(Contains(control.Snapshot(), "match_future_timestamp"),
              "future cause recorded");
    }

    // ---- F) verdict must bind the post-settle frame, not an earlier one ---
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 37000 * kMs;
        ArmKsi(control, 371, t0);
        std::uint64_t qual_ns = 0;
        // f_settle=491 observed pre-settle; f_qual=492 observed post-settle.
        DriveToQualifying(control, port, 49, 371, t0, 491, 492, &qual_ns);
        // Bind to the EARLIER unmapped frame: must fail.
        TargetMatch stale_bind =
            BoundMatch(49, 371, t0 + 300 * kMs, 491, t0 + 210 * kMs);
        control.OnTargetMatch(stale_bind, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "pre-settle frame binding fails");
        CHECK(Contains(control.Snapshot(),
                       "match_not_bound_to_post_settle_frame"),
              "binding cause recorded");
    }
    {
        // No qualifying frame at all (match window opened, but no post-
        // settle frame): the verdict must fail, not borrow old frames.
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 37500 * kMs;
        ArmKsi(control, 372, t0);
        CHECK(control.RequestReobservation(50, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.OnRequestAck(50, 372, true, t0 + 100 * kMs);
        control.OnLensState(SettledLens(50, 372, port.last_intent.zoom_step,
                                        t0 + 200 * kMs, t0 + 200 * kMs),
                            t0 + 200 * kMs);
        control.OnFrame(MakeFrame(501, t0 + 190 * kMs),
                        t0 + 200 * kMs);  // pre-settle stream proof only
        CHECK(control.task_state() == TaskState::kAwaitingMatch,
              "match window opens on stream proof");
        TargetMatch borrowed =
            BoundMatch(50, 372, t0 + 300 * kMs, 501, t0 + 190 * kMs);
        control.OnTargetMatch(borrowed, t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "borrowed pre-settle frame never verifies");
    }

    // ---- G) task ids strictly increase, never reused ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 38000 * kMs;
        ArmKsi(control, 381, t0);
        CHECK(control.RequestReobservation(51, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "task 51 sent");
        StartOutcome dup = control.RequestReobservation(
            51, NeedMoreDetail(t0 + 1 * kMs), t0 + 1 * kMs);
        CHECK(dup.outcome == TaskOutcome::kRejected &&
                  dup.reason == "task_id_reused",
              "reused task id rejected even while inflight");
        StartOutcome zero = control.RequestReobservation(
            0, NeedMoreDetail(t0 + 2 * kMs), t0 + 2 * kMs);
        CHECK(zero.reason == "task_id_reused", "zero task id rejected");
        // Finish task 51, then prove the old id stays fenced.
        control.OnRequestAck(51, 381, true, t0 + 100 * kMs);
        control.OnLensState(SettledLens(51, 381, port.last_intent.zoom_step,
                                        t0 + 200 * kMs, t0 + 200 * kMs),
                            t0 + 200 * kMs);
        control.OnFrame(MakeFrame(511, t0 + 210 * kMs), t0 + 210 * kMs);
        control.OnFrame(MakeFrame(512, t0 + 250 * kMs), t0 + 250 * kMs);
        control.OnTargetMatch(
            BoundMatch(51, 381, t0 + 300 * kMs, 512, t0 + 250 * kMs),
            t0 + 300 * kMs);
        CHECK(control.task_outcome() == TaskOutcome::kSucceededVerified,
              "task 51 succeeds");
        StartOutcome replay = control.RequestReobservation(
            51, NeedMoreDetail(t0 + 400 * kMs), t0 + 400 * kMs);
        CHECK(replay.reason == "task_id_reused",
              "completed task id never reusable");
    }

    // ---- H) uncertain timeout WITH idempotency cap: bounded resend ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        port.caps.idempotent_retry_supported = true;  // explicit guarantee
        ReobserveConfig config = TestConfig();
        config.max_retries = 1;
        ReobservationController control(config, &port);
        const std::int64_t t0 = 39000 * kMs;
        ArmKsi(control, 391, t0);
        CHECK(control.RequestReobservation(52, NeedMoreDetail(t0), t0)
                  .outcome == TaskOutcome::kInProgress,
              "request sent");
        control.OnFrame(MakeFrame(521, t0 + 1200 * kMs), t0 + 1200 * kMs);
        control.Poll(t0 + 1500 * kMs + 1);
        CHECK(port.requests == 2, "idempotent resend allowed once");
        CHECK(control.task_state() == TaskState::kAwaitingAck,
              "still awaiting after resend");
        CHECK(port.last_task == 52 && port.last_generation == 391,
              "resend reuses the same idempotency key");
        RefreshAll(control, 522, t0 + 1500 * kMs + 1 + 1400 * kMs);
        control.Poll(t0 + 1500 * kMs + 1 + 1500 * kMs + 1);
        CHECK(control.task_outcome() == TaskOutcome::kFailed,
              "retries still bounded under idempotency");
        CHECK(port.requests == 2, "no third send");
    }

    // ---- I) transport-not-sent: certain nothing moved, bounded resend ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        port.transport_ok = false;  // queue refuses everything
        ReobserveConfig config = TestConfig();
        config.max_retries = 1;
        ReobservationController control(config, &port);
        const std::int64_t t0 = 40000 * kMs;
        ArmKsi(control, 401, t0);
        StartOutcome out =
            control.RequestReobservation(53, NeedMoreDetail(t0), t0);
        CHECK(out.outcome == TaskOutcome::kFailed, "transport failure fails");
        CHECK(out.reason == "port_rejected_transport", "reason recorded");
        CHECK(port.requests == 2, "bounded resend of certain-nothing-sent");
    }

    // ---- J) bounded history: full buffer fails closed, never overwrites ---
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobserveConfig config = TestConfig();
        config.max_history_records = 16;
        ReobservationController control(config, &port);
        const std::int64_t t0 = 41000 * kMs;
        ArmKsi(control, 411, t0);  // ~6 records
        for (int i = 0; i < 20; ++i) {
            control.NoteActualOwner(CameraOwner::kKsi, t0 + (1 + i) * kMs);
        }
        CHECK(control.history_full(), "buffer fills and latches full");
        CHECK(control.dropped_records() > 0, "drops counted, not silent");
        CHECK(control.history().size() <= 16, "hard cap respected");
        StartOutcome out = control.RequestReobservation(
            54, NeedMoreDetail(t0 + 100 * kMs), t0 + 100 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected &&
                  out.reason == "record_capacity_exhausted",
              "memory-full fails closed for camera");
        CHECK(port.requests == 0, "no motion when records cannot be kept");
        CHECK(Contains(control.Snapshot(), "history_full=1"),
              "snapshot exposes the full flag to the parent");
    }

    // ---- K) monotonic clock: rollbacks rejected without state change ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 42000 * kMs;
        ArmKsi(control, 421, t0);
        control.NoteActualOwner(CameraOwner::kHuman, t0 + 50 * kMs);
        CHECK(control.task_state() == TaskState::kIdle, "setup idle");
        // A stale-timestamped owner report cannot undo the manual hold.
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 10 * kMs);
        CHECK(Contains(control.Snapshot(), "time_rollback_rejected"),
              "rollback recorded");
        StartOutcome out = control.RequestReobservation(
            55, NeedMoreDetail(t0 + 60 * kMs), t0 + 60 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected,
              "rolled-back KSI report changes nothing");
    }

    // ---- L) deadline overflow near the clock limit fails closed ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = INT64_MAX - 1000;
        CameraGrant grant = LiveGrant(431, 0);
        grant.expires_monotonic_ns = INT64_MAX - 100;
        CHECK(control.GrantAuthority(grant, t0), "grant at clock limit");
        control.NoteDeviceIdentity(TestIdentity(), t0);
        control.NoteActualOwner(CameraOwner::kKsi, t0);
        control.NoteSupervision(Supervision::kPresent, t0);
        control.NoteEvidenceCapacity(true, t0);
        control.OnFrame(MakeFrame(4310, t0), t0);
        StartOutcome out =
            control.RequestReobservation(56, NeedMoreDetail(t0), t0);
        CHECK(out.outcome == TaskOutcome::kRejected &&
                  out.reason == "deadline_overflow",
              "overflow fails closed, never wraps");
        CHECK(port.requests == 0, "no motion on overflow");
    }

    // ---- M) invalid configs rejected, never silently repaired ----
    {
        std::string error;
        CHECK(ValidateConfig(TestConfig(), &error), "mock config valid");
        ReobserveConfig unconfirmed = TestConfig();
        unconfirmed.operational_thresholds_confirmed = false;
        CHECK(!ValidateConfig(unconfirmed, &error) &&
                  error == "thresholds_not_confirmed",
              "unconfirmed mock thresholds invalid");
        ReobserveConfig swapped = TestConfig();
        swapped.permitted_min_zoom = 3;
        swapped.permitted_max_zoom = 2;
        CHECK(!ValidateConfig(swapped, &error) &&
                  error == "bad_permitted_zoom_range",
              "swapped zoom range rejected, not repaired");
        ReobserveConfig zero = TestConfig();
        zero.request_timeout_ns = 0;
        CHECK(!ValidateConfig(zero, &error), "zero timeout rejected");
        ReobserveConfig retries = TestConfig();
        retries.max_retries = 99;
        CHECK(!ValidateConfig(retries, &error), "uncapped retries rejected");
        ReobserveConfig dwell = TestConfig();
        dwell.max_dwell_ns = 1;
        CHECK(!ValidateConfig(dwell, nullptr), "uncoverable dwell rejected");
        // An invalid config rejects every request by construction.
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController bad(TestConfig(), &port);
        (void)bad;
        FakeCameraPort port2;
        port2.caps = FullCaps();
        ReobservationController invalid(unconfirmed, &port2);
        CHECK(!invalid.config_ok(), "config_ok false");
        CHECK(invalid.config_error() == "thresholds_not_confirmed",
              "parent-visible cause");
        StartOutcome out = invalid.RequestReobservation(
            57, NeedMoreDetail(43000 * kMs), 43000 * kMs);
        CHECK(out.outcome == TaskOutcome::kRejected &&
                  out.reason == "config_invalid",
              "invalid config moves nothing");
        CHECK(port2.requests == 0, "zero output on invalid config");
    }

    // ---- N) supervision hold: Present alone never resumes; grant does ----
    {
        FakeCameraPort port;
        port.caps = FullCaps();
        ReobservationController control(TestConfig(), &port);
        const std::int64_t t0 = 44000 * kMs;
        ArmKsi(control, 441, t0);
        control.NoteSupervision(Supervision::kLost, t0 + 10 * kMs);
        CHECK(control.authority_valid() == false, "loss drops authority");
        control.NoteSupervision(Supervision::kPresent, t0 + 20 * kMs);
        CHECK(control.authority_valid() == false,
              "Present alone never resumes");
        StartOutcome held = control.RequestReobservation(
            58, NeedMoreDetail(t0 + 20 * kMs), t0 + 20 * kMs);
        CHECK(held.outcome == TaskOutcome::kRejected,
              "hold requires a fresh grant");
        CHECK(control.GrantAuthority(LiveGrant(442, t0 + 30 * kMs),
                                     t0 + 30 * kMs),
              "fresh grant clears the hold");
        control.NoteDeviceIdentity(TestIdentity(), t0 + 30 * kMs);
        control.NoteActualOwner(CameraOwner::kKsi, t0 + 30 * kMs);
        control.NoteSupervision(Supervision::kPresent, t0 + 30 * kMs);
        control.NoteEvidenceCapacity(true, t0 + 30 * kMs);
        control.OnFrame(MakeFrame(4420, t0 + 30 * kMs), t0 + 30 * kMs);
        StartOutcome free = control.RequestReobservation(
            59, NeedMoreDetail(t0 + 30 * kMs), t0 + 30 * kMs);
        CHECK(free.outcome == TaskOutcome::kInProgress,
              "fresh grant plus fresh reports actuate");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
