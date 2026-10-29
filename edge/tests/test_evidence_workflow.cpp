// Genuine multilayer host workflow: the offline Edge lifecycle end to end.
//
// Wires the real host-testable units the way the device path does —
// MopEdgeEndpoint (deferred apply + spool journal) + MopLink + SessionRunner
// + RunControl + MopTickEvidence — through one coherent scenario:
//
//   0) file-hash helper: Sha256HexOfFile matches Sha256Hex, fails honestly.
//   1) apply -> pending -> ACTUAL install -> confirm -> MISSION_APPLIED
//      (ACK means received; APPLIED means installed).
//   2) start -> frame -> valid observation -> link flush -> RC ACK drain.
//   3) link drop -> more frames recorded (aggregates fold, envelopes
//      journalled) -> reconnect flushes the SAME bytes -> ACKs drain.
//   4) process restart with the same flight: new boot/runtime epoch, old
//      records retained with their ORIGINAL boot ids and replayed exactly.
//   5) refused live apply: REJECTED with reasons, old bundle + provenance
//      preserved, never relabelled.
//   6) no-frame health: a started session with a dead stream degrades.
//
// Spool directories live under /tmp/opencode (pid-suffixed, removed at the
// end). No PSDK, no clock reads inside the units (caller clocks only).

#include "ksi_safety/runtime/run_control.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/durable_spool.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <sys/types.h>

#include "ksi_safety/transport/audit_trail.hpp"

#include "support/check.hpp"

#if defined(_WIN32)
#include <process.h>
#define WF_PID _getpid()
#else
#include <unistd.h>
#define WF_PID ::getpid()
#endif

using ksi::safety::AcceptedCommand;
using ksi::safety::AuditTrail;
using ksi::safety::DetectionBatch;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::DigestExcluding;
using ksi::safety::DurableSpool;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::JsonValue;
using ksi::safety::ModelProfile;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;
using ksi::safety::MopProtocol;
using ksi::safety::MopRuntimeFacts;
using ksi::safety::MopSessionCommands;
using ksi::safety::MopTickEvidence;
using ksi::safety::SnapshotToFacts;
using ksi::safety::SpoolHooks;
using ksi::safety::Uuid5;
using ksi::safety::ParseJson;
using ksi::safety::PendingMessage;
using ksi::safety::RunControl;
using ksi::safety::RunControlInputs;
using ksi::safety::RunControlOutcome;
using ksi::safety::RunnerConfig;
using ksi::safety::RuntimeSnapshot;
using ksi::safety::RuntimeState;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::Sha256Hex;
using ksi::safety::Sha256HexOfFile;
using ksi::safety::WriteCanonicalJson;

namespace {

constexpr std::int64_t kMs = 1000000LL;
constexpr const char* kFlight = "flt-wf";

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;  // +1 ms per emitted envelope
        return value;
    }
};

struct FakeTransport : ksi::safety::MopByteTransport {
    bool fail = false;
    std::string written;
    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        if (fail) return false;
        written.append(reinterpret_cast<const char*>(data), len);
        return true;
    }
};

class OkDetector : public DetectorPort {
  public:
    DetectionBatch Detect(const FramePacket&) override {
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
    config.video_stale_after_ns = 100 * kMs;
    config.video_lost_after_ns = 500 * kMs;
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
    profile.model_id = "dfine-s-coco";
    profile.model_digest =
        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    profile.backend = "fake";
    return profile;
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
    ctx.flight_id = kFlight;
    ctx.boot_id = "B";
    ctx.stream_id = "S";
    ctx.frame_id = packet.metadata.frame_id;
    ctx.receive_monotonic_ns = packet.metadata.received_monotonic_ns;
    ctx.has_exposure = false;
    return ctx;
}

// Context-capable mission bundle: digest-verified TRANSPORT plus the
// mission/policy revisions the evidence context requires. Explicit empty
// zones/rules (the valid empty install).
JsonValue MakeMissionBundle(const std::string& bundle_id,
                            const std::string& mis_rev,
                            const std::string& pol_rev) {
    JsonValue snapshot = JsonValue::Obj();
    snapshot.Set("mission_revision_id", JsonValue::Str(mis_rev));
    snapshot.Set("content_sha256",
                 JsonValue::Str(std::string(64, 'c')));
    snapshot.Set("zones", JsonValue::Arr());
    JsonValue policy = JsonValue::Obj();
    policy.Set("revision_id", JsonValue::Str(pol_rev));
    policy.Set("rules", JsonValue::Arr());
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("bundle_id", JsonValue::Str(bundle_id));
    bundle.Set("mission_snapshot", std::move(snapshot));
    bundle.Set("risk_policy", std::move(policy));
    bundle.Set("bundle_sha256",
               JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
    return bundle;
}

// Feed one RC envelope through a link (framing + digest exactly like the
// device reader path).
void FeedEnvelope(MopLink* link, StepClock* clock, const std::string& type,
                  const std::string& msg_id, JsonValue payload,
                  const std::string& flight = kFlight) {
    JsonValue envelope = ksi::safety::MakeMopEnvelope(
        msg_id, "CONTROL_RELIABLE", type, flight, nullptr, nullptr, nullptr,
        std::move(payload), (*clock)());
    std::string bytes;
    for (const auto& frame :
         ksi::safety::MopFrameCodec::Encode(WriteCanonicalJson(envelope))) {
        bytes += frame;
    }
    link->Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
               bytes.size());
}

void FeedPrepare(MopLink* link, StepClock* clock, const std::string& tag,
                 const JsonValue& bundle) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("bundle", bundle);
    FeedEnvelope(link, clock, "MISSION_PREPARE",
                 Uuid5(MopProtocol::Namespace(),
                       "rc|" + std::string(kFlight) + "|" + tag + "|PREPARE"),
                 std::move(payload));
}

void FeedApply(MopLink* link, StepClock* clock, const std::string& tag,
               const std::string& bundle_id, const std::string& bundle_sha) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("bundle_id", JsonValue::Str(bundle_id));
    payload.Set("bundle_sha256", JsonValue::Str(bundle_sha));
    FeedEnvelope(link, clock, "MISSION_APPLY",
                 Uuid5(MopProtocol::Namespace(),
                       "rc|" + std::string(kFlight) + "|" + tag + "|APPLY"),
                 std::move(payload));
}

void FeedSession(MopLink* link, StepClock* clock, const std::string& type,
                 const std::string& tag) {
    FeedEnvelope(link, clock, type,
                 Uuid5(MopProtocol::Namespace(),
                       "rc|" + std::string(kFlight) + "|" + tag + "|" + type),
                 JsonValue::Obj());
}

void FeedAck(MopLink* link, StepClock* clock, const std::string& ack_of) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("ack_of", JsonValue::Str(ack_of));
    FeedEnvelope(link, clock, "ACK",
                 Uuid5(MopProtocol::Namespace(), "rc|ack|" + ack_of),
                 std::move(payload));
}

const JsonValue* FindOutboxType(MopEdgeEndpoint& endpoint,
                                const std::string& type) {
    for (const JsonValue& queued : endpoint.outbox()) {
        const JsonValue* found = queued.Find("type");
        if (found != nullptr && found->IsString() &&
            found->strValue() == type) {
            return &queued;
        }
    }
    return nullptr;
}

bool OutboxHasType(MopEdgeEndpoint& endpoint, const std::string& type) {
    return FindOutboxType(endpoint, type) != nullptr;
}

std::size_t CountOutboxType(MopEdgeEndpoint& endpoint,
                            const std::string& type) {
    std::size_t count = 0;
    for (const JsonValue& queued : endpoint.outbox()) {
        const JsonValue* found = queued.Find("type");
        if (found != nullptr && found->IsString() &&
            found->strValue() == type) {
            ++count;
        }
    }
    return count;
}

// True when a queued REJECTED envelope carries the given reason code.
bool OutboxRejectedWith(MopEdgeEndpoint& endpoint,
                        const std::string& code) {
    for (const JsonValue& queued : endpoint.outbox()) {
        const JsonValue* type = queued.Find("type");
        if (type == nullptr || !type->IsString() ||
            type->strValue() != "MISSION_APPLY_REJECTED") {
            continue;
        }
        const JsonValue* payload = queued.Find("payload");
        const JsonValue* reasons =
            payload != nullptr ? payload->Find("reasons") : nullptr;
        if (reasons == nullptr || !reasons->IsArray()) continue;
        for (const JsonValue& reason : reasons->arrayValue()) {
            if (reason.IsString() && reason.strValue() == code) return true;
        }
    }
    return false;
}

// Queued protocol chatter (PREPARED/APPLIED/ACKs) is device-verified
// elsewhere; drain it so evidence assertions count exactly.
void DrainLink(MopLink* link, FakeTransport* transport) {
    while (link->Flush() > 0) {
    }
    transport->written.clear();
}

// Adapter-mirror: one coherent snapshot -> RunControl inputs. The device
// adapter (SyncRunControl) does exactly this under a single service lock.
RunControlInputs AdapterInputs(MopEdgeEndpoint* endpoint) {
    RunControlInputs in;
    const MopSessionCommands commands = endpoint->session_commands();
    in.command_epoch = commands.command_epoch;
    in.command_revision = commands.command_revision;
    in.desired_running = commands.desired_running;
    in.start_count = commands.start_count;
    in.stop_count = commands.stop_count;
    in.last_command_was_start = commands.last_command_was_start;
    in.applied_generation = commands.applied_generation;
    in.has_bundle = false;  // generation edges handled explicitly below
    const JsonValue* pending = endpoint->pendingBundle();
    in.has_pending_bundle = (pending != nullptr);
    in.pending_bundle = pending;
    return in;
}

std::string SpoolDir(const std::string& name) {
    return "/tmp/opencode/ksi-spool-wf-" + name + "-" +
           std::to_string(static_cast<long long>(WF_PID));
}

int CountLines(const std::string& text) {
    int lines = 0;
    for (char ch : text) {
        if (ch == '\n') ++lines;
    }
    return lines;
}

bool AllLinesParse(const std::string& text) {
    std::size_t start = 0;
    while (start < text.size()) {
        const std::size_t nl = text.find('\n', start);
        if (nl == std::string::npos) return false;
        JsonValue value;
        std::string error;
        if (!ParseJson(text.substr(start, nl - start), &value, &error) ||
            !value.IsObject()) {
            return false;
        }
        start = nl + 1;
    }
    return true;
}

}  // namespace

int main() {
    // ---- 0) file-hash helper ------------------------------------------------
    {
        const std::string path = "/tmp/opencode/ksi-edge-test-sha256.bin";
        {
            std::FILE* file = std::fopen(path.c_str(), "wb");
            CHECK(file != nullptr, "test hash file writable");
            std::fwrite("abc", 1, 3, file);
            std::fclose(file);
        }
        std::string error;
        const std::string hashed = Sha256HexOfFile(path.c_str(), &error);
        CHECK(hashed == Sha256Hex("abc") &&
                  hashed ==
                      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              "file hash matches the one-shot hash (known vector)");
        CHECK(error.empty(), "no error on success");
        const std::string missing =
            Sha256HexOfFile("/tmp/opencode/ksi-edge-test-sha256-absent.bin",
                            &error);
        CHECK(missing.empty() && !error.empty(),
              "unreadable file fails honestly with a reason");
        std::remove(path.c_str());
    }

    // ---- 1) apply -> pending -> ACTUAL install -> confirm -> APPLIED -------
    StepClock clock;
    MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
    endpoint.setDeferredApply(true);
    FakeTransport transport;
    MopLink link(&transport, &endpoint);
    DurableSpool spool(SpoolDir("a"), 1ULL << 20);
    CHECK(spool.ok(), "spool opens");
    endpoint.setSpool(&spool);
    SessionRunner runner(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
    RunControl control;

    const JsonValue bundle_a =
        MakeMissionBundle("bundle-a", "mis-a", "pol-a");
    const std::string sha_a = bundle_a.Find("bundle_sha256")->strValue();
    FeedPrepare(&link, &clock, "p1", bundle_a);
    CHECK(OutboxHasType(endpoint, "MISSION_PREPARED"),
          "valid PREPARE answers PREPARED");
    // The RC ACKs every reliable response (protocol chatter included).
    {
        const JsonValue* prepared = FindOutboxType(endpoint, "MISSION_PREPARED");
        CHECK(prepared != nullptr, "PREPARED queued");
        FeedAck(&link, &clock, prepared->Find("msg_id")->strValue());
    }
    FeedApply(&link, &clock, "a1", "bundle-a", sha_a);
    // Deferred: ACK only — NO bundle switch, NO APPLIED, generation moved
    // nowhere. ACK means received; APPLIED will mean installed.
    CHECK(!OutboxHasType(endpoint, "MISSION_APPLIED"),
          "no APPLIED before the real install");
    CHECK(endpoint.hasPendingApply(), "apply recorded as pending");
    CHECK(!endpoint.hasAppliedBundle(),
          "no bundle switch before the install receipt");
    CHECK(endpoint.session_commands().applied_generation == 0,
          "generation still 0 before install");
    RunControlOutcome applied = control.Step(runner, AdapterInputs(&endpoint));
    CHECK(applied.pending_outcome == "installed",
          "pending bundle installs through RunControl");
    CHECK(runner.runtime_state() == ksi::safety::RuntimeState::kConfigApplied,
          "runner holds the installed config");
    CHECK(endpoint.ConfirmPendingApply(), "install receipt confirms");
    CHECK(endpoint.hasAppliedBundle(), "bundle switches on confirm");
    CHECK(OutboxHasType(endpoint, "MISSION_APPLIED"),
          "MISSION_APPLIED only after the actual install");
    CHECK(!endpoint.hasPendingApply(), "pending cleared by confirm");
    // The RC ACKs the APPLIED response (protocol chatter, not journalled).
    {
        const JsonValue* applied = FindOutboxType(endpoint, "MISSION_APPLIED");
        CHECK(applied != nullptr, "APPLIED queued");
        FeedAck(&link, &clock, applied->Find("msg_id")->strValue());
    }

    // ---- 2) start -> frame -> valid observation -> ACK drain ---------------
    FeedSession(&link, &clock, "SESSION_START", "s1");
    DrainLink(&link, &transport);  // SESSION_START's ACK is protocol chatter
    RunControlOutcome started =
        control.Step(runner, AdapterInputs(&endpoint));
    CHECK(started.start_requested, "start opens the gate");
    OkDetector detector;
    MopTickEvidence evidence;
    {
        ksi::safety::MopEvidenceContext context;
        std::string error;
        CHECK(ksi::safety::EvidenceContextFromBundle(bundle_a, &context,
                                                     &error),
              "evidence context binds to the INSTALLED bundle");
        context.flight_id = endpoint.flight_id();
        context.edge_boot_id = endpoint.edge_boot_id();
        evidence.SetContext(std::move(context));
    }
    FramePacket frame1 = MakeFrame(1, 100 * kMs);
    const auto record1 =
        runner.ProcessFrame(frame1, MakeCtx(frame1), &detector);
    CHECK(record1.detection_status == DetectionStatus::kOk,
          "detection tick is evidence");
    CHECK(!record1.model_digest.empty(), "model digest bound on the tick");
    evidence.EmitTick(&link, record1);
    CHECK(spool.pending_count() == 1, "observation journalled at queue time");
    CHECK(link.Flush() == 1, "connected flush delivers the observation");
    const std::string obs_id = spool.Pending()[0].msg_id;
    FeedAck(&link, &clock, obs_id);
    CHECK(spool.pending_count() == 0, "RC persist ACK delivers");
    CHECK(endpoint.unackedCount() == 0, "unacked drains on ACK");

    // ---- 3) link drop: record + journal, reconnect replays SAME bytes -----
    transport.fail = true;
    FramePacket frame2 = MakeFrame(2, 200 * kMs);
    FramePacket frame3 = MakeFrame(3, 300 * kMs);
    evidence.EmitTick(
        &link, runner.ProcessFrame(frame2, MakeCtx(frame2), &detector));
    evidence.EmitTick(
        &link, runner.ProcessFrame(frame3, MakeCtx(frame3), &detector));
    CHECK(evidence.ticks_seen() == 3, "offline ticks fold into aggregates");
    CHECK(link.Flush() == 0, "down link delivers nothing");
    CHECK(endpoint.outboxSize() == 2, "offline envelopes stay queued");
    CHECK(spool.pending_count() == 2, "offline envelopes journalled anyway");
    // The journalled bytes carry the ORIGINAL boot id, not a relabel.
    const std::string pre_boot = endpoint.edge_boot_id();
    CHECK(spool.Pending()[0].envelope.Find("edge_boot_id")->strValue() ==
              pre_boot,
          "journalled envelope keeps the original boot id");
    transport.fail = false;
    const std::size_t sent = link.Flush();
    CHECK(sent == 2, "reconnect flushes the queued envelopes");
    CHECK(transport.written.size() > 0, "bytes hit the wire on reconnect");
    const std::string written_after_reconnect = transport.written;
    // Reconnect replays the SAME bytes (no duplicates, no renames).
    CHECK(link.Flush() == 0, "second flush sends nothing new");
    CHECK(transport.written.size() == written_after_reconnect.size(),
          "no duplicate bytes on steady flush");
    // ACK the replayed pair (leave the second unacked for the restart leg).
    const std::string first_id = spool.Pending()[0].msg_id;
    const std::string second_id = spool.Pending()[1].msg_id;
    FeedAck(&link, &clock, first_id);
    CHECK(spool.pending_count() == 1, "one ACK delivers one envelope");
    // Final summary covers every tick including the offline ones.
    JsonValue summary;
    CHECK(evidence.BuildSummary(
              MopTickEvidence::SessionId(endpoint.flight_id(),
                                         endpoint.edge_boot_id()),
              &summary),
          "summary builds over offline + online ticks");
    evidence.EmitSummary(&link, MopTickEvidence::SessionId(
                                     endpoint.flight_id(),
                                     endpoint.edge_boot_id()));
    {
        const JsonValue* queued = FindOutboxType(endpoint, "FLIGHT_SUMMARY");
        CHECK(queued != nullptr, "summary queued");
        const JsonValue* payload = queued->Find("payload");
        const JsonValue* body =
            payload != nullptr ? payload->Find("summary") : nullptr;
        const JsonValue* covered =
            body != nullptr ? body->Find("covered_frames") : nullptr;
        CHECK(covered != nullptr && covered->IsInt() &&
                  covered->intValue() == 3,
              "summary covers all three ticks (offline included)");
    }
    CHECK(link.Flush() == 1, "summary delivers on the live link");
    // Deliver everything except the second observation: it stays pending
    // for the restart leg below.
    for (const auto& pending : spool.Pending()) {
        if (pending.msg_id != second_id) {
            FeedAck(&link, &clock, pending.msg_id);
        }
    }
    CHECK(spool.pending_count() == 1, "only the second observation pending");

    // ---- 4) process restart: new boot, old records retained exactly -------
    const std::string old_boot = endpoint.edge_boot_id();
    CHECK(spool.pending_count() == 1, "one unacked record survives");
    // Fresh process, same flight: OS entropy mints a new boot id.
    MopEdgeEndpoint endpoint2(kFlight, [&clock] { return clock(); }, 0, "",
                              "P2-restart");
    CHECK(endpoint2.edge_boot_id() != old_boot,
          "restart with the same flight mints a new boot id");
    CHECK(endpoint2.session_commands().command_epoch == 0,
          "fresh process starts at command epoch 0");
    RunControl control2;
    RunControlOutcome quiet =
        control2.Step(runner, AdapterInputs(&endpoint2));
    CHECK(!quiet.start_requested && !quiet.stop_requested &&
              !quiet.facts_dirty,
          "fresh process replays no old intent");
    // Old spool records replay under the NEW process with ORIGINAL ids.
    DurableSpool spool2(SpoolDir("a"), 1ULL << 20);
    CHECK(spool2.ok(), "spool reopens across the restart");
    CHECK(spool2.pending_count() == 1, "unacked record retained");
    endpoint2.setSpool(&spool2);
    CHECK(endpoint2.ReplaySpool() == 1, "restart replays the pending record");
    const JsonValue* replayed = FindOutboxType(endpoint2, "OBSERVATION");
    CHECK(replayed != nullptr, "replayed observation queued");
    CHECK(replayed->Find("msg_id")->strValue() == second_id,
          "replay keeps the EXACT original msg id");
    CHECK(replayed->Find("edge_boot_id")->strValue() == old_boot,
          "replay never relabels the old boot id");

    // ---- 5) refused live apply: REJECTED, old provenance preserved --------
    {
        MopEdgeEndpoint endpoint3(kFlight, [&clock] { return clock(); });
        endpoint3.setDeferredApply(true);
        FakeTransport transport3;
        MopLink link3(&transport3, &endpoint3);
        SessionRunner runner3(RunnerCfg(), SessionCfg(), {}, {}, {},
                              Profile());
        RunControl control3;
        FeedPrepare(&link3, &clock, "p3a", bundle_a);
        FeedApply(&link3, &clock, "a3", "bundle-a", sha_a);
        RunControlOutcome install3 =
            control3.Step(runner3, AdapterInputs(&endpoint3));
        CHECK(install3.pending_outcome == "installed" &&
                  endpoint3.ConfirmPendingApply(),
              "baseline bundle installs + confirms");
        FeedSession(&link3, &clock, "SESSION_START", "s3");
        CHECK(control3.Step(runner3, AdapterInputs(&endpoint3))
                  .start_requested,
              "running before the rival apply");
        FramePacket live = MakeFrame(9, 900 * kMs);
        runner3.ProcessFrame(live, MakeCtx(live), &detector);
        const JsonValue bundle_b =
            MakeMissionBundle("bundle-b", "mis-b", "pol-b");
        const std::string sha_b =
            bundle_b.Find("bundle_sha256")->strValue();
        FeedPrepare(&link3, &clock, "p3b", bundle_b);
        FeedApply(&link3, &clock, "b3", "bundle-b", sha_b);
        CHECK(endpoint3.hasPendingApply(), "rival apply pends");
        RunControlOutcome refused =
            control3.Step(runner3, AdapterInputs(&endpoint3));
        CHECK(refused.install_refused && !refused.bundle_installed,
              "live rival apply refused");
        CHECK(endpoint3.RejectPendingApply(refused.install_reasons),
              "refusal answers the request");
        CHECK(OutboxRejectedWith(endpoint3, "reinstall_while_running"),
              "requester sees REJECTED with the installer reason");
        CHECK(CountOutboxType(endpoint3, "MISSION_APPLIED") == 1,
              "exactly one APPLIED exists: the baseline install only");
        // Old bundle + provenance untouched: the runner hash and the
        // endpoint's applied bundle still name bundle-a.
        CHECK(endpoint3.appliedBundle() != nullptr &&
                  endpoint3.appliedBundle()
                          ->Find("bundle_id")
                          ->strValue() == "bundle-a",
              "old applied bundle preserved (never relabelled)");
        CHECK(endpoint3.appliedBundleSha256() == sha_a,
              "old bundle sha preserved");
    }

    // ---- 6) no-frame health: dead stream at start degrades -----------------
    {
        SessionRunner runner6(RunnerCfg(), SessionCfg(), {}, {}, {},
                              Profile());
        // Direct runner install (transport already proven above) + start.
        CHECK(runner6
                  .ApplyRuntimeConfig({}, {}, {}, "hash-6", {})
                  .ok,
              "install for the health leg");
        runner6.RequestStart();
        runner6.HealthTick(10 * kMs);
        const auto snapshot = runner6.Snapshot();
        CHECK(snapshot.input_state.video == "stale",
              "started session with no frames reads stale");
        CHECK(snapshot.state == ksi::safety::RuntimeState::kDegraded,
              "dead stream degrades instead of staying healthy");
    }

    // ---- 7) spool capacity: failure degrades, never clean -----------------
    // A 1-byte spool fails the writable pre-check immediately: ticks are
    // refused BEFORE they fold (never unjournalled records in the
    // aggregates), the run latches DEGRADED with session_capacity_exceeded,
    // and no clean summary exists. The RC detects the gap via the facts.
    {
        StepClock clock7;
        MopEdgeEndpoint endpoint7(kFlight, [&clock7] { return clock7(); });
        endpoint7.setDeferredApply(true);
        DurableSpool tiny(SpoolDir("tiny"), 1);
        CHECK(tiny.ok(), "tiny spool opens");
        endpoint7.setSpool(&tiny);
        // Pre-check fails on the empty spool: 0 + 256KiB bound > 1 byte.
        CHECK(!endpoint7.CheckSpoolWritable(),
              "full spool is not writable");
        std::string spool_reason;
        CHECK(endpoint7.spoolFailed(&spool_reason) && !spool_reason.empty(),
              "failure latches with a truthful reason");
        CHECK(tiny.pending_count() == 0, "nothing journalled");
        // The service would skip EmitTick here; the runner degrades.
        SessionRunner runner7(RunnerCfg(), SessionCfg(), {}, {}, {},
                              Profile());
        CHECK(runner7.ApplyRuntimeConfig({}, {}, {}, "hash-7", {}).ok,
              "install for the capacity leg");
        runner7.RequestStart();
        runner7.NoteDurableFailure(spool_reason);
        const RuntimeSnapshot failed = runner7.Snapshot();
        CHECK(failed.state == ksi::safety::RuntimeState::kDegraded,
              "spool failure degrades instead of running");
        CHECK(failed.processed_frame_count == 0,
              "refused ticks never fold into the aggregates");
        bool has_capacity = false;
        for (const auto& reason : failed.degraded_reasons) {
            if (reason == "session_capacity_exceeded") has_capacity = true;
        }
        CHECK(has_capacity, "facts carry session_capacity_exceeded");
        // No summary exists over zero folded ticks: never a clean summary
        // with missing records.
        MopTickEvidence evidence7;
        JsonValue no_summary;
        CHECK(!evidence7.BuildSummary("s", &no_summary),
              "no summary without folded ticks");
        // And the facts the RC would query carry the reason (queryable).
        const MopRuntimeFacts facts7 = SnapshotToFacts(failed);
        bool facts_carry = false;
        for (const auto& reason : facts7.degraded_reasons) {
            if (reason == "session_capacity_exceeded") facts_carry = true;
        }
        CHECK(facts_carry && facts7.has_degraded_reasons,
              "MISSION_STATUS facts carry the capacity failure");
    }

    // ---- 7b) reclaim before failure: acked compacts, unacked latches ----
    // CheckSpoolWritable must attempt the allowed reclaim (acked payloads
    // -> tombstones) BEFORE declaring durable failure: a journal full of
    // DELIVERED envelopes stays writable, while a journal genuinely full
    // of UNACKED envelopes still latches honestly.
    {
        StepClock clockR;
        MopEdgeEndpoint endpointR(kFlight, [&clockR] { return clockR(); });
        FakeTransport transportR;
        MopLink linkR(&transportR, &endpointR);
        // Probe one observation envelope, then size the journal just past
        // one maximal pre-check bound: full records + 256KiB do NOT fit,
        // but tombstones + receipts + 256KiB DO.
        DurableSpool probeR(SpoolDir("reclaim-probe"), 1ULL << 20);
        CHECK(probeR.ok(), "reclaim probe opens");
        {
            JsonValue payload = JsonValue::Obj();
            payload.Set("x", JsonValue::Int(1));
            MopEdgeEndpoint probe_ep(kFlight, [&clockR] { return clockR(); });
            probe_ep.setSpool(&probeR);
            probe_ep.EmitObservation(std::move(payload));
        }
        const std::uint64_t one = probeR.bytes_used();
        CHECK(one > 100, "observation has realistic size");
        // Centered in the (deterministic) reclaim window: full records +
        // 256KiB exceeds it (pressure trips) while tombstones + receipts +
        // 256KiB fits (reclaim succeeds). UUID msg_ids make this exact.
        const std::uint64_t cap = 256ULL * 1024ULL + 657;
        DurableSpool spoolR(SpoolDir("reclaim"), cap);
        CHECK(spoolR.ok(), "reclaim spool opens");
        endpointR.setSpool(&spoolR);
        for (int i = 0; i < 2; ++i) {
            JsonValue payload = JsonValue::Obj();
            payload.Set("x", JsonValue::Int(i));
            endpointR.EmitObservation(std::move(payload));
        }
        CHECK(spoolR.pending_count() == 2, "two unacked journalled");
        const std::uint64_t used_full = spoolR.bytes_used();
        CHECK(used_full + 256ULL * 1024ULL > cap,
              "pre-check bound would trip without reclaim");
        // The RC persists both: delivered, but still occupying full bytes.
        for (const PendingMessage& pending : spoolR.Pending()) {
            FeedAck(&linkR, &clockR, pending.msg_id);
        }
        CHECK(spoolR.pending_count() == 0, "both delivered");
        const std::uint64_t used_acked = spoolR.bytes_used();
        CHECK(used_acked > used_full,
              "delivery receipts journalled (acked bytes still occupy)");
        // The gate reclaims instead of latching: still writable, no
        // failure, journal shrank, and new evidence queues.
        CHECK(endpointR.CheckSpoolWritable(), "reclaim keeps spool writable");
        std::string no_reason;
        CHECK(!endpointR.spoolFailed(&no_reason), "no failure latched");
        CHECK(spoolR.bytes_used() < used_acked, "acked payloads compacted");
        {
            JsonValue payload = JsonValue::Obj();
            payload.Set("x", JsonValue::Int(99));
            endpointR.EmitObservation(std::move(payload));
        }
        CHECK(spoolR.pending_count() == 1, "new evidence queues");
        CHECK(!endpointR.spoolFailed(&no_reason), "still no failure");
        // Counter-case: genuinely full of UNACKED data still fails
        // honestly (no room even after the reclaim attempt, nothing to
        // reclaim).
        DurableSpool tight(SpoolDir("reclaim-tight"), 2 * one);
        CHECK(tight.ok(), "tight spool opens");
        MopEdgeEndpoint endpointT(kFlight, [&clockR] { return clockR(); });
        endpointT.setSpool(&tight);
        for (int i = 0; i < 2; ++i) {
            JsonValue payload = JsonValue::Obj();
            payload.Set("y", JsonValue::Int(i));
            endpointT.EmitObservation(std::move(payload));
        }
        CHECK(tight.pending_count() == 2, "tight journal holds two unacked");
        CHECK(!endpointT.CheckSpoolWritable(),
              "unacked-full spool is not writable");
        std::string tight_reason;
        CHECK(endpointT.spoolFailed(&tight_reason) && !tight_reason.empty(),
              "genuine full latches with a truthful reason");
    }

    // ---- 8) fsync failure at Send: drop + latch, never silent ------------
    {
        StepClock clock8;
        MopEdgeEndpoint endpoint8(kFlight, [&clock8] { return clock8(); });
        // Pre-create the journal files with a healthy spool, then reopen
        // with failing fsync: open succeeds (existing files need no dir
        // sync), the first Append fsync fails — the exact Send-time path.
        {
            DurableSpool seed(SpoolDir("badfsync"), 1ULL << 20);
            CHECK(seed.ok(), "seed spool creates the journal files");
        }
        SpoolHooks failing;
        failing.fsync = [](int) { return -1; };  // every fsync fails
        DurableSpool bad_spool(SpoolDir("badfsync"), 1ULL << 20, failing);
        CHECK(bad_spool.ok(), "spool opens (failures start at Append)");
        endpoint8.setSpool(&bad_spool);
        FakeTransport transport8;
        MopLink link8(&transport8, &endpoint8);
        // An evidence envelope fails to journal: dropped (not queued),
        // counted, latched with the IO cause.
        JsonValue payload = JsonValue::Obj();
        payload.Set("x", JsonValue::Int(1));
        endpoint8.EmitObservation(std::move(payload));
        CHECK(endpoint8.outboxSize() == 0,
              "unproven envelope never queues");
        CHECK(endpoint8.spool_refused() == 1, "refusal counted");
        std::string reason;
        CHECK(endpoint8.spoolFailed(&reason) && !reason.empty(),
              "IO failure latches with the cause");
        CHECK(link8.Flush() == 0, "nothing to deliver after refusal");
    }

    // ---- 9) clean negative vs no execution --------------------------------
    // kOk ticks with zero detections ARE evidence (covered, counted) and
    // must read differently from a run that never executed anything.
    {
        SessionRunner runner9(RunnerCfg(), SessionCfg(), {}, {}, {},
                              Profile());
        CHECK(runner9.ApplyRuntimeConfig({}, {}, {}, "hash-9", {}).ok,
              "install for the negative leg");
        runner9.RequestStart();
        OkDetector detector9;
        MopTickEvidence evidence9;
        ksi::safety::MopEvidenceContext context9;
        context9.flight_id = kFlight;
        context9.edge_boot_id = "boot-9";
        context9.mission_revision_id = "mis-9";
        evidence9.SetContext(std::move(context9));
        for (int i = 0; i < 2; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(i),
                                          (100 + i) * kMs);
            const auto record =
                runner9.ProcessFrame(frame, MakeCtx(frame), &detector9);
            CHECK(record.detection_status == DetectionStatus::kOk,
                  "clean negative is detection evidence (kOk, zero dets)");
            CHECK(record.detections.empty(), "zero detections, not a gap");
            (void)evidence9.BuildTick(record);
        }
        CHECK(runner9.processed_frame_count() == 2, "two ticks processed");
        CHECK(evidence9.ticks_seen() == 2, "two ticks folded");
        JsonValue summary9;
        CHECK(evidence9.BuildSummary("session-9", &summary9),
              "summary builds over clean negatives");
        // BuildSummary returns the summary payload object itself
        // (EmitSummary wraps it under "summary" for the wire).
        const JsonValue* covered = summary9.Find("covered_frames");
        CHECK(covered != nullptr && covered->IsInt() &&
                  covered->intValue() == 2,
              "clean negatives count as covered (not missing)");
        const JsonValue* skipped = summary9.Find("skipped_frames");
        CHECK(skipped != nullptr && skipped->IsInt() &&
                  skipped->intValue() == 0,
              "zero skips: negatives are evidence, not gaps");
        // Versus no execution at all: fresh runner, nothing processed, no
        // summary exists to misread as clean.
        SessionRunner idle(RunnerCfg(), SessionCfg(), {}, {}, {}, Profile());
        CHECK(idle.processed_frame_count() == 0, "idle run processed nothing");
        MopTickEvidence idle_evidence;
        JsonValue idle_summary;
        CHECK(!idle_evidence.BuildSummary("session-idle", &idle_summary),
              "no execution means no summary (never a clean zero)");
    }

    // ---- 10) audit history: offline DEGRADED -> recover -> reconnect -----
    // Mirrors the device adapter's write-ahead order (identity -> commands
    // -> Step -> install/state audit -> claims -> close/ship) with a local
    // trail: a STOP+START pair in one poll, a dead-stream DEGRADED spell,
    // recovery, and the close must all appear with their command ids.
    {
        StepClock clock10;
        MopEdgeEndpoint endpoint10(kFlight, [&clock10] { return clock10(); });
        FakeTransport transport10;
        MopLink link10(&transport10, &endpoint10);
        endpoint10.setDeferredApply(true);
        const std::string adir = SpoolDir("audit10");
        ::mkdir(adir.c_str(), 0700);
        AuditTrail trail10;
        std::string journal_error;
        CHECK(trail10.OpenJournal(adir, "boot-10", ksi::safety::AuditHooks{},
                                            &journal_error),
              "audit journal opens on the state dir");
        SessionRunner runner10(RunnerCfg(), SessionCfg(), {}, {}, {},
                               Profile());
        RunControl control10;
        std::uint64_t cmd_seq = 0;
        auto drain10 = [&]() {
            bool overflow = false;
            const std::vector<AcceptedCommand> cmds =
                endpoint10.AcceptedCommandsSince(cmd_seq, &overflow);
            CHECK(!overflow, "no command gap in the audit drain");
            for (const auto& command : cmds) {
                CHECK(trail10.RecordCommand(command.msg_id, command.type,
                                            command.seq,
                                            endpoint10.session_commands()
                                                .command_epoch,
                                            clock10()),
                      "command journalled write-ahead of the transition");
                cmd_seq = command.seq;
            }
        };
        auto audit_state10 = [&]() {
            const RuntimeSnapshot snapshot = runner10.Snapshot();
            std::string degraded;
            for (const auto& reason : snapshot.degraded_reasons) {
                if (!degraded.empty()) degraded += ";";
                degraded += reason;
            }
            return trail10.NoteState(
                ksi::safety::ToString(snapshot.state),
                snapshot.input_state.video, snapshot.input_state.telemetry,
                snapshot.input_state.engine, degraded,
                snapshot.processed_frame_count, snapshot.config_epoch,
                clock10());
        };
        CHECK(trail10.SetIdentity(endpoint10.flight_id(),
                                  endpoint10.edge_boot_id(), clock10()),
              "identity journalled first");
        const JsonValue bundle10 =
            MakeMissionBundle("bundle-10", "mis-10", "pol-10");
        const std::string sha10 =
            bundle10.Find("bundle_sha256")->strValue();
        FeedPrepare(&link10, &clock10, "p10", bundle10);
        FeedApply(&link10, &clock10, "a10", "bundle-10", sha10);
        drain10();
        RunControlOutcome applied10 =
            control10.Step(runner10, AdapterInputs(&endpoint10));
        CHECK(applied10.pending_outcome == "installed",
              "pending install for the audit leg");
        CHECK(trail10.RecordInstall(
                  "bundle-10", sha10,
                  endpoint10.session_commands().applied_generation,
                  applied10.pending_outcome, "", clock10()),
              "install receipt journalled before the confirm claim");
        CHECK(endpoint10.ConfirmPendingApply(), "install confirmed");
        CHECK(audit_state10(), "post-install state journalled");
        // Start, then a dead stream: stale video degrades the run.
        const std::string start1_id = Uuid5(
            MopProtocol::Namespace(), "rc|flt-wf|s10a|SESSION_START");
        FeedSession(&link10, &clock10, "SESSION_START", "s10a");
        drain10();
        CHECK(control10.Step(runner10, AdapterInputs(&endpoint10))
                  .start_requested,
              "started");
        CHECK(audit_state10(), "starting state journalled");
        runner10.HealthTick(10 * kMs);
        CHECK(audit_state10(), "offline DEGRADED journalled");
        CHECK(runner10.runtime_state() == RuntimeState::kDegraded,
              "dead stream degrades while disconnected");
        // Frames resume: video recovers (run honestly stays DEGRADED on
        // absent telemetry — partial recovery, not a clean bill).
        OkDetector detector10;
        for (int i = 0; i < 2; ++i) {
            FramePacket frame = MakeFrame(static_cast<std::uint32_t>(10 + i),
                                          (100 + i) * kMs);
            runner10.ProcessFrame(frame, MakeCtx(frame), &detector10);
        }
        CHECK(audit_state10(), "video recovery journalled");
        CHECK(runner10.Snapshot().input_state.video == "ok",
              "video recovers on resumed frames");
        // STOP+START land in one poll: both transitions journalled with
        // their wire ids, final intent (started) wins.
        FeedSession(&link10, &clock10, "SESSION_STOP", "s10b");
        FeedSession(&link10, &clock10, "SESSION_START", "s10c");
        drain10();
        RunControlOutcome both10 =
            control10.Step(runner10, AdapterInputs(&endpoint10));
        CHECK(both10.stop_requested && both10.start_requested,
              "both intents honored");
        CHECK(audit_state10(), "restarted state journalled");
        // Final stop + close + ship-equivalent serialization.
        FeedSession(&link10, &clock10, "SESSION_STOP", "s10d");
        drain10();
        RunControlOutcome closed10 =
            control10.Step(runner10, AdapterInputs(&endpoint10));
        CHECK(closed10.stop_requested && closed10.summary_requested,
              "final stop closes");
        CHECK(trail10.RecordClose(closed10.summary_close_ts,
                                  runner10.processed_frame_count(),
                                  clock10()),
              "close journalled before the summary claim");
        CHECK(audit_state10(), "stopped state journalled");
        const std::string shipped = trail10.ToJsonLines();
        CHECK(AllLinesParse(shipped), "shipped audit parses as JSON lines");
        CHECK(shipped.find("edge-audit-v1") != std::string::npos,
              "artifact marker present");
        CHECK(shipped.find("SESSION_START") != std::string::npos &&
                  shipped.find("SESSION_STOP") != std::string::npos,
              "history carries both transition types");
        const std::string stop_b_id = Uuid5(
            MopProtocol::Namespace(), "rc|flt-wf|s10b|SESSION_STOP");
        const std::string stop_d_id = Uuid5(
            MopProtocol::Namespace(), "rc|flt-wf|s10d|SESSION_STOP");
        CHECK(shipped.find(start1_id) != std::string::npos &&
                  shipped.find(stop_b_id) != std::string::npos &&
                  shipped.find(stop_d_id) != std::string::npos,
              "every command wire id preserved");
        CHECK(shipped.find("DEGRADED") != std::string::npos,
              "offline DEGRADED transition preserved");
        CHECK(shipped.find("\"kind\":\"close\"") != std::string::npos,
              "close record shipped");
        CHECK(trail10.dropped() == 0, "bounded trail dropped nothing here");
        // The journal file on disk matches the in-memory trail: forensics
        // survive the process even before any ship.
        std::FILE* journal =
            std::fopen((adir + "/audit-boot-10.log").c_str(), "r");
        CHECK(journal != nullptr, "journal file readable");
        std::string disk;
        char chunk[1024];
        std::size_t n = 0;
        while ((n = std::fread(chunk, 1, sizeof(chunk), journal)) > 0) {
            disk.append(chunk, n);
        }
        std::fclose(journal);
        CHECK(AllLinesParse(disk), "disk journal parses as JSON lines");
        CHECK(static_cast<std::size_t>(CountLines(shipped)) ==
                  trail10.size() + 1,
              "shipped lines are meta plus every retained record");
        CHECK(static_cast<std::size_t>(CountLines(disk)) == trail10.size(),
              "disk journal holds every record (meta is ship-only)");
    }

    // Spool dirs are pid-suffixed scratch; leave the files (no pruning in
    // the spool contract) — the next run mints a fresh directory.
    return edge_test::failures == 0 ? 0 : 1;
}
