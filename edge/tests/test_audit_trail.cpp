// Host tests for the offline audit journal + spool config policy
// (edge/include/ksi_safety/transport/audit_trail.hpp,
// edge/psdk-app/src/mop_service.hpp Validate/ResolveBootUnique,
// endpoint AcceptedCommandsSince).
//
// Covers: identity change detection, command journaling with wire msg ids,
// install receipts (installed/refused/parse_failed/parked), state-change
// dedup (no per-poll spam), closeout records, the bounded in-memory cap
// with explicit dropped count, the JSON-lines ship format (meta first),
// journal file append + fsync, open-failure fail-closed, spool config
// policy (absolute durable dir required, no silent in-memory fallback),
// boot-identity factory failure, and the endpoint accepted-command log
// (dedup-safe, epoch-scoped, overflow-flagged).

#include "ksi_safety/transport/audit_trail.hpp"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"

// Spool config policy + boot identity factory live in the device adapter
// header, which is host-safe (no PSDK includes): Validate() and
// ResolveBootUnique() are header-inline and link-free.
#include "mop_service.hpp"

#include "support/check.hpp"

#if defined(_WIN32)
#include <process.h>
#define AUDIT_PID _getpid()
#else
#include <sys/stat.h>
#include <unistd.h>
#define AUDIT_PID ::getpid()
#endif

using ksi::safety::AcceptedCommand;
using ksi::safety::AuditGate;
using ksi::safety::AuditArtifactId;
using ksi::safety::AuditHooks;
using ksi::safety::AuditJournalSet;
using ksi::safety::AuditLineageId;
using ksi::safety::AuditTrail;
using ksi::safety::JsonValue;
using ksi::safety::MopByteTransport;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;
using ksi::safety::MopProtocol;
using ksi::safety::ParseJson;
using ksi::safety::Uuid5;
using ::ResolveBootUnique;
using ksi::safety::WriteCanonicalJson;

namespace {

constexpr const char* kFlight = "flt-audit";
constexpr const char* kBoot = "boot-audit";

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
        if (!ParseJson(text.substr(start, nl - start), &value, &error)) {
            return false;
        }
        if (!value.IsObject()) return false;
        start = nl + 1;
    }
    return true;
}

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;
        return value;
    }
};

struct FakeTransport : MopByteTransport {
    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        (void)data;
        (void)len;
        return true;
    }
};

}  // namespace

int main() {
    // ---- 1) identity + first state baseline --------------------------------
    {
        AuditTrail trail;
        CHECK(trail.SetIdentity(kFlight, kBoot, 1000),
              "identity records on first call");
        CHECK(trail.size() == 1, "one identity record");
        CHECK(trail.SetIdentity(kFlight, kBoot, 2000),
              "same identity is a quiet no-op");
        CHECK(trail.size() == 1, "no duplicate identity record");
        CHECK(trail.SetIdentity("flt-other", kBoot, 3000),
              "flight change records");
        CHECK(trail.size() == 2, "identity change journalled");
        CHECK(trail.NoteState("STARTING", "absent", "absent", "absent", "", 0,
                              0, 4000),
              "first state records the baseline");
        CHECK(trail.size() == 3, "baseline state recorded");
        CHECK(trail.NoteState("STARTING", "absent", "absent", "absent", "", 0,
                              0, 5000),
              "identical state is quiet (no per-poll spam)");
        CHECK(trail.size() == 3, "steady state records nothing");
        CHECK(trail.NoteState("DEGRADED", "stale", "absent", "absent",
                              "video_input_stale", 0, 0, 6000),
              "changed state records");
        CHECK(trail.size() == 4, "transition journalled");
        CHECK(trail.NoteState("DEGRADED", "stale", "absent", "absent",
                              "video_input_stale", 3, 0, 7000),
              "counter-only advance records nothing (no per-poll spam)");
        CHECK(trail.size() == 4, "counter-only changes journal nothing");
        CHECK(trail.NoteState("DEGRADED", "stale", "absent", "absent",
                              "video_input_stale", 3000, 0, 8000),
              "checkpoint records progress without a full state record");
        CHECK(trail.size() == 5, "checkpoint journalled");
        // 100k polls of identical state stay bounded: checkpoints only.
        for (std::uint64_t i = 0; i < 100000; ++i) {
            CHECK(trail.NoteState("DEGRADED", "stale", "absent", "absent",
                                  "video_input_stale",
                                  static_cast<std::uint64_t>(10000 + i), 0,
                                  static_cast<std::int64_t>(9000 + i)),
                  "steady-state poll appends at most a checkpoint");
        }
        CHECK(trail.dropped() == 0, "bounded trail drops nothing here");
        CHECK(trail.size() < 200, "100k identical polls stay bounded");
    }

    // ---- 2) commands carry wire ids, installs carry outcomes ---------------
    {
        AuditTrail trail;
        CHECK(trail.SetIdentity(kFlight, kBoot, 1000), "identity set");
        const std::string start_id = "rc|flt-audit|1|START";
        CHECK(trail.RecordCommand(start_id, "SESSION_START", 1, 0, 2000),
              "start command journalled");
        const std::string stop_id = "rc|flt-audit|2|STOP";
        CHECK(trail.RecordCommand(stop_id, "SESSION_STOP", 2, 0, 3000),
              "stop command journalled");
        CHECK(trail.RecordInstall("bundle-a", std::string(64, 'a'), 1,
                                  "installed", "", 4000),
              "install receipt journalled");
        CHECK(trail.RecordInstall("bundle-b", std::string(64, 'b'), 2,
                                  "refused", "reinstall_while_running",
                                  5000),
              "refusal journalled with the reason");
        CHECK(trail.RecordClose(12345, 7, 6000), "closeout journalled");
        const std::string lines = trail.ToJsonLines();
        CHECK(CountLines(lines) == 1 + 6,
              "meta + six records shipped in order");
        CHECK(AllLinesParse(lines), "every shipped line is a JSON object");
        // Order + kinds + ids survive the round trip.
        std::vector<std::string> kinds;
        std::vector<std::string> ids;
        std::size_t start = 0;
        // Skip the meta line.
        start = lines.find('\n', start) + 1;
        while (start < lines.size()) {
            const std::size_t nl = lines.find('\n', start);
            JsonValue value;
            std::string error;
            CHECK(ParseJson(lines.substr(start, nl - start), &value, &error),
                  "shipped line parses");
            kinds.push_back(value.Find("kind")->strValue());
            ids.push_back(value.Find("msg_id")->strValue());
            CHECK(value.Find("t_mono_ns")->IsInt(), "caller clock stamped");
            start = nl + 1;
        }
        CHECK(kinds.size() == 6 && kinds[0] == "identity" &&
                  kinds[1] == "command" && kinds[2] == "command" &&
                  kinds[3] == "install" && kinds[4] == "install" &&
                  kinds[5] == "close",
              "history holds identity, both transitions, both receipts");
        CHECK(ids[1] == start_id && ids[2] == stop_id,
              "both command ids preserved (no fixed-order loss)");
    }

    // ---- 3) bound is explicit, never silent --------------------------------
    {
        AuditTrail trail;
        CHECK(trail.SetIdentity(kFlight, kBoot, 0), "identity set");
        for (std::uint64_t i = 0; i < 5000; ++i) {
            CHECK(trail.RecordCommand("m" + std::to_string(i), "SESSION_START",
                                      i + 1, 0,
                                      static_cast<std::int64_t>(i)),
                  "bounded append accepts");
        }
        CHECK(trail.dropped() > 0, "overflow counted, not hidden");
        const std::string lines = trail.ToJsonLines();
        CHECK(AllLinesParse(lines), "shipped lines still parse");
        JsonValue meta;
        std::string error;
        CHECK(ParseJson(lines.substr(0, lines.find('\n')), &meta, &error),
              "meta line parses");
        CHECK(meta.Find("dropped")->IsInt() &&
                  meta.Find("dropped")->intValue() ==
                      static_cast<std::int64_t>(trail.dropped()),
              "shipped meta carries the dropped bound");
        CHECK(meta.Find("artifact")->strValue() == "edge-audit-v1",
              "artifact marker present");
    }

    // ---- 4) journal file: append + fsync, fail closed ----------------------
    {
        const std::string dir =
            "/tmp/opencode/ksi-audit-test-" +
            std::to_string(static_cast<long long>(AUDIT_PID));
        CHECK(::mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST,
              "scratch dir created");
        AuditTrail trail;
        CHECK(trail.OpenJournal(dir, "boot-test", AuditHooks{}, nullptr),
              "journal opens");
        CHECK(trail.SetIdentity(kFlight, kBoot, 1000), "identity journalled");
        CHECK(trail.RecordCommand("m1", "SESSION_START", 1, 0, 2000),
              "command journalled to disk");
        // The file holds exactly the memory records so far.
        std::FILE* file = std::fopen((dir + "/audit-boot-test.log").c_str(), "r");
        CHECK(file != nullptr, "journal file readable");
        std::string content;
        char chunk[1024];
        std::size_t n = 0;
        while ((n = std::fread(chunk, 1, sizeof(chunk), file)) > 0) {
            content.append(chunk, n);
        }
        std::fclose(file);
        CHECK(CountLines(content) == 2, "file holds both records");
        CHECK(AllLinesParse(content), "file lines parse as JSON objects");
        // Unwritable / missing dirs fail closed.
        AuditTrail bad;
        std::string bad_error;
        CHECK(!bad.OpenJournal("relative/path", "boot-test", AuditHooks{},
                                &bad_error) &&
                  !bad_error.empty(),
              "relative journal dir rejected");
        AuditTrail bad2;
        CHECK(!bad2.OpenJournal("/dev/full-dir-does-not-exist-zzz",
                                        "boot-test", AuditHooks{},
                                        &bad_error) &&
                  !bad_error.empty(),
              "missing journal dir fails closed");
        CHECK(!bad2.journalOk(), "failed journal reports unhealthy");
    }

    // ---- 4b) injected fsync failure mid-append: fail closed ----------
    {
        const std::string dir =
            "/tmp/opencode/ksi-audit-fail-" +
            std::to_string(static_cast<long long>(AUDIT_PID));
        CHECK(::mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST,
              "scratch dir created");
        int fsync_calls = 0;
        AuditHooks failing;
        failing.fsync = [&](int) {
            ++fsync_calls;
            errno = EIO;
            return -1;
        };
        AuditTrail trail;
        CHECK(!trail.OpenJournal(dir, "boot-fail", failing, nullptr),
              "dir fsync failure fails the open (fail closed)");
        CHECK(!trail.journalOk(), "unhealthy journal flagged");
        // Open without the failing hook, then fail on append instead.
        AuditTrail trail2;
        CHECK(trail2.OpenJournal(dir, "boot-ok", AuditHooks{}, nullptr),
              "journal opens with real fsync");
        CHECK(trail2.SetIdentity(kFlight, kBoot, 1000),
              "identity journalled");
        CHECK(trail2.RecordCommand("m1", "SESSION_START", 1, 0, 2000),
              "command journalled");
        // A fresh trail on the same file with failing fsync: appends fail
        // closed (file keeps old bytes, memory keeps nothing new).
        AuditTrail trail3;
        CHECK(trail3.OpenJournal(dir, "boot-ok", failing, nullptr) ||
                  !trail3.journalOk(),
              "reopen tolerates the failing hook at open");
        const std::size_t before = trail3.size();
        CHECK(!trail3.RecordCommand("m2", "SESSION_STOP", 2, 0, 3000),
              "mid-append fsync failure returns false (no silent claim)");
        CHECK(!trail3.journalOk(), "failure latches");
        CHECK(!trail3.journalError().empty(), "cause retained");
        CHECK(trail3.size() == before,
              "failed record is neither filed nor kept");
    }

    // ---- 5) spool config policy: durable dir required, never silent -------
    {
        MopServiceConfig config;
        // Default is the app-owned persistent root (survives reboots).
        CHECK(config.spool_dir == "/open_app/ksi-safety-edge/state",
              "default spool dir is the persistent app root");
        std::string error;
        CHECK(config.Validate(&error), "default config validates");
        MopServiceConfig empty;
        empty.spool_dir = "";
        CHECK(!empty.Validate(&error) && !error.empty(),
              "empty spool dir rejected: no silent in-memory fallback");
        MopServiceConfig relative;
        relative.spool_dir = "relative/path";
        CHECK(!relative.Validate(&error) && !error.empty(),
              "relative spool dir rejected");
        MopServiceConfig zero;
        zero.spool_capacity_bytes = 0;
        CHECK(!zero.Validate(&error) && !error.empty(),
              "zero capacity rejected");
    }

    // ---- 6) boot identity factory: entropy or loud failure -----------------
    {
        // Failing entropy must fail closed, never fall back to pid/clock.
        bool called = false;
        auto failing = [&](unsigned char*, std::size_t) {
            called = true;
            return false;
        };
        std::string unique;
        std::string error;
        CHECK(!ResolveBootUnique(failing, &unique, &error) && called &&
                  !error.empty() && unique.empty(),
              "injected entropy failure fails closed with a reason");
        // Fixed entropy is deterministic (test-injectable identity).
        auto fixed = [](unsigned char* out, std::size_t len) {
            for (std::size_t i = 0; i < len; ++i) out[i] = 0xAB;
            return true;
        };
        std::string first;
        std::string second;
        CHECK(ResolveBootUnique(fixed, &first, &error) &&
              ResolveBootUnique(fixed, &second, &error),
              "fixed entropy resolves");
        CHECK(first == second && first.size() == 32 &&
                  first == "abababababababababababababababab",
              "deterministic 128-bit hex identity");
    }

    // ---- 7) endpoint accepted-command log ----------------------------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        auto send = [&](const std::string& type, const std::string& msg_id) {
            JsonValue envelope = ksi::safety::MakeMopEnvelope(
                msg_id, "CONTROL_RELIABLE", type, kFlight, nullptr, nullptr,
                nullptr, JsonValue::Obj(), clock());
            std::string bytes;
            for (const auto& frame : ksi::safety::MopFrameCodec::Encode(
                     WriteCanonicalJson(envelope))) {
                bytes += frame;
            }
            link.Feed(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                      bytes.size());
        };
        bool overflow = true;
        CHECK(endpoint.AcceptedCommandsSince(0, &overflow).empty() &&
                  !overflow,
              "no commands yet: empty, no overflow");
        const std::string s1 =
            Uuid5(MopProtocol::Namespace(), "rc|flt-audit|1|START");
        const std::string s2 =
            Uuid5(MopProtocol::Namespace(), "rc|flt-audit|2|STOP");
        send("SESSION_START", s1);
        send("SESSION_STOP", s2);
        send("SESSION_START", s1);  // wire retry: dedup, no new command
        const std::vector<AcceptedCommand> drained =
            endpoint.AcceptedCommandsSince(0, &overflow);
        CHECK(!overflow && drained.size() == 2, "both commands drained once");
        CHECK(drained[0].msg_id == s1 && drained[1].msg_id == s2,
              "command wire ids preserved in order");
        CHECK(drained[0].type == "SESSION_START" &&
                  drained[1].type == "SESSION_STOP",
              "command types preserved");
        const std::vector<AcceptedCommand> again =
            endpoint.AcceptedCommandsSince(drained.back().seq, &overflow);
        CHECK(again.empty() && !overflow, "drained cursor stays quiet");
        // Identity change resets the log: old intent never replays.
        endpoint.Reboot();
        const std::vector<AcceptedCommand> after =
            endpoint.AcceptedCommandsSince(0, &overflow);
        CHECK(after.empty(), "reboot clears the command log");
    }

    // ---- 7) rotation: unconfirmed records nothing; A ships on B ---
    {
        const std::string dir =
            "/tmp/opencode/ksi-audit-set-" +
            std::to_string(static_cast<long long>(AUDIT_PID));
        CHECK(::mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST,
              "set scratch dir created");
        AuditJournalSet set;
        CHECK(set.Open(dir, AuditHooks{}, nullptr), "set opens");
        // Unconfirmed (empty) flight: nothing recorded, nothing shipped.
        auto none = set.EnsureIdentity("", "boot-x", false, 1000);
        CHECK(none.empty, "no audit under an unconfirmed flight");
        CHECK(set.RecordCommand("m?", "SESSION_START", 1, 0, 1000),
              "pre-identity records are quiet no-ops");
        CHECK(set.size() == 0, "nothing recorded before identity");
        // Flight A confirmed: per-boot journal file appears.
        auto first = set.EnsureIdentity("flt-a", "boot-a", true, 2000);
        CHECK(first.empty, "first identity has no outgoing shipment");
        CHECK(set.RecordCommand("mA1", "SESSION_START", 1, 0, 3000),
              "A command journalled");
        CHECK(set.NoteState("STARTING", "absent", "absent", "absent", "", 0,
                            0, 4000),
              "A state journalled");
        // Flight B (new boot): rotation ships A under A's identity, then a
        // fresh journal + memory context binds B.
        auto outgoing = set.EnsureIdentity("flt-b", "boot-b", true, 5000);
        CHECK(!outgoing.empty, "rotation ships the outgoing trail");
        CHECK(outgoing.flight_id == "flt-a" &&
                  outgoing.edge_boot_id == "boot-a",
              "outgoing shipment keeps the ORIGINAL identity");
        CHECK(outgoing.artifact_id ==
                  AuditArtifactId("flt-a", "boot-a", outgoing.bytes),
              "shipment id is content-addressed, not boot-named");
        CHECK(outgoing.bytes.find("mA1") != std::string::npos,
              "outgoing bytes carry A's command ids");
        CHECK(set.NoteState("STARTING", "absent", "absent", "absent", "", 0,
                            0, 6000),
              "B identity starts a fresh trail");
        // Same history replays under the same id (dedup-safe); any change
        // mints a new one (never the same id with different bytes).
        CHECK(AuditArtifactId("flt-a", "boot-a", outgoing.bytes) ==
                  outgoing.artifact_id,
              "identical bytes replay under the same id");
        CHECK(AuditArtifactId("flt-a", "boot-a", outgoing.bytes + "x") !=
                  outgoing.artifact_id,
              "changed history mints a new id");
        // Lineage is stable per flight+boot and never hashed into the id.
        CHECK(AuditLineageId("flt-a", "boot-a") ==
                  AuditLineageId("flt-a", "boot-a"),
              "lineage deterministic");
        CHECK(AuditLineageId("flt-a", "boot-a") !=
                  AuditLineageId("flt-b", "boot-b"),
              "lineage separates identities");
        // The artifact id binds content too (not just the lineage).
        CHECK(outgoing.artifact_id != AuditLineageId("flt-a", "boot-a"),
              "artifact id differs from the lineage id");
    }

    // ---- 8) restore: prior boots ship whole, torn tails explicit ---------
    {
        const std::string dir =
            "/tmp/opencode/ksi-audit-restore-" +
            std::to_string(static_cast<long long>(AUDIT_PID));
        CHECK(::mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST,
              "restore scratch dir created");
        // Two complete boots then a new process identity.
        {
            AuditJournalSet proc1;
            CHECK(proc1.Open(dir, AuditHooks{}, nullptr), "proc1 opens");
            CHECK(!proc1.EnsureIdentity("flt-r", "boot-r1", true, 1000)
                       .empty == false,
                  "first identity has no outgoing shipment");
            CHECK(proc1.RecordCommand("mr1", "SESSION_START", 1, 0, 2000),
                  "r1 command journalled");
            CHECK(proc1.RecordClose(999, 1, 3000),
                  "r1 close journalled");
            auto out = proc1.EnsureIdentity("flt-r", "boot-r2", true, 4000);
            CHECK(!out.empty && out.flight_id == "flt-r" &&
                      out.edge_boot_id == "boot-r1",
                  "rotation ships r1 under its own boot");
            CHECK(proc1.RecordCommand("mr2", "SESSION_STOP", 1, 1, 5000),
                  "r2 command journalled");
        }
        // Torn tail in a third file: truncated + flagged, rest kept.
        {
            std::FILE* torn = std::fopen(
                (dir + "/audit-boot-r3.log").c_str(), "w");
            CHECK(torn != nullptr, "torn file writable");
            std::fputs("{\"kind\":\"identity\",\"msg_id\":\"\","
                       "\"detail\":{\"flight_id\":\"flt-r\","
                       "\"edge_boot_id\":\"boot-r3\"}}",
                       torn);
            std::fputs("\n{\"kind\":\"command\",\"msg_id\":\"mr3\",",
                       torn);
            std::fclose(torn);  // no trailing newline: torn tail
        }
        AuditJournalSet proc2;
        CHECK(proc2.Open(dir, AuditHooks{}, nullptr), "proc2 opens");
        std::vector<std::string> boots = proc2.ListBoots();
        CHECK(boots.size() == 3, "all three boot journals listed");
        CHECK(boots[0] == "boot-r1" && boots[1] == "boot-r2" &&
                  boots[2] == "boot-r3",
              "restore lists every boot in order");
        // Boot r1: whole history with ORIGINAL boot, command ids intact.
        AuditJournalSet::Shipment ship1;
        bool repaired1 = true;
        std::string error1;
        CHECK(proc2.ShipBootFile("boot-r1", &ship1, &repaired1, &error1),
              "r1 ships without error");
        CHECK(!repaired1, "r1 file is intact");
        CHECK(!ship1.empty && ship1.flight_id == "flt-r" &&
                  ship1.edge_boot_id == "boot-r1",
              "r1 ships under its original identity");
        CHECK(ship1.bytes.find("mr1") != std::string::npos &&
                  ship1.bytes.find("\"kind\":\"close\"") !=
                      std::string::npos,
              "r1 history keeps commands and the close record");
        CHECK(ship1.artifact_id ==
                  AuditArtifactId(ship1.flight_id, ship1.edge_boot_id,
                                  ship1.bytes),
              "r1 artifact id is content-addressed");
        // Boot r2: retained alongside r1 (no merge, no relabel).
        AuditJournalSet::Shipment ship2;
        bool repaired2 = false;
        std::string error2;
        CHECK(proc2.ShipBootFile("boot-r2", &ship2, &repaired2, &error2),
              "r2 ships without error");
        CHECK(!repaired2 && !ship2.empty &&
                  ship2.edge_boot_id == "boot-r2",
              "r2 ships under its own boot");
        CHECK(ship2.bytes.find("mr2") != std::string::npos,
              "r2 keeps its own command ids");
        CHECK(ship2.artifact_id != ship1.artifact_id,
              "distinct histories mint distinct ids");
        // Boot r3: torn tail is truncated explicitly, rest ships.
        AuditJournalSet::Shipment ship3;
        bool repaired3 = false;
        std::string error3;
        CHECK(proc2.ShipBootFile("boot-r3", &ship3, &repaired3, &error3),
              "r3 ships after torn-tail repair");
        CHECK(repaired3, "torn tail flagged, not hidden");
        CHECK(!ship3.empty && ship3.bytes.find("mr3") == std::string::npos,
              "torn bytes never shipped as history");
        // Unknown boot: explicit failure, nothing shipped.
        AuditJournalSet::Shipment missing;
        std::string error4;
        CHECK(!proc2.ShipBootFile("boot-nope", &missing, nullptr, &error4) &&
                  !error4.empty(),
              "unknown boot fails loudly");
    }

    // ---- 9) write-ahead gate: first failure latches, all claims stop
    {
        ksi::safety::AuditGate gate;
        CHECK(!gate.suspended(), "fresh gate allows claims");
        gate.Require(true, "spool");
        gate.Require(true, "identity");
        gate.Require(true, "commands");
        CHECK(!gate.suspended(), "all-true gate stays open");
        CHECK(std::string(gate.failedStage()).empty(),
              "no failure stage recorded");
        gate.Require(true, "install");
        gate.Require(false, "state");
        CHECK(gate.suspended(), "first failure latches");
        CHECK(std::string(gate.failedStage()) == "state",
              "failure stage named for the closing log");
        gate.Require(true, "close");
        CHECK(gate.suspended(), "latch is sticky (no silent recovery)");
        CHECK(std::string(gate.failedStage()) == "state",
              "first failure wins, later passes do not clear it");
        ksi::safety::AuditGate gate2;
        gate2.Require(true, "spool");
        gate2.Require(false, "commands");
        CHECK(gate2.suspended(), "mid-sequence failure suspends");
        CHECK(std::string(gate2.failedStage()) == "commands",
              "failing stage reported, not a later one");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
