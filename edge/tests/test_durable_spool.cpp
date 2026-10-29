// Durable spool tests (DJI-free, real filesystem + restart + fault injection).
//
// Build (NOT via repo CMake; the Edge owner wires CMake integration):
//   g++ -std=c++17 -Wall -Wextra -Wpedantic -Werror
//     -I <repo>/edge/include -I <repo>/edge/tests
//     edge/tests/test_durable_spool.cpp
//     edge/src/transport/durable_spool.cpp
//     edge/src/transport/json.cpp
//     edge/src/transport/digest.cpp
//     edge/src/transport/mop_envelope.cpp
//     -o /tmp/opencode/ksi-durable-spool/test_durable_spool
// Run: /tmp/opencode/ksi-durable-spool/test_durable_spool  (exit 0 = pass)
//
// Temp state lives under /tmp/opencode/ksi-durable-spool/case-*/ only.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/durable_spool.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "support/check.hpp"

using ksi::safety::AppendResult;
using ksi::safety::CompactResult;
using ksi::safety::DurableSpool;
using ksi::safety::JsonValue;
using ksi::safety::MakeMopEnvelope;
using ksi::safety::ParseJson;
using ksi::safety::PendingMessage;
using ksi::safety::Sha256Hex;
using ksi::safety::SpoolHooks;
using ksi::safety::SpoolStatus;
using ksi::safety::VerifyMopEnvelope;
using ksi::safety::WriteCanonicalJson;

namespace {

const std::string kRoot = "/tmp/opencode/ksi-durable-spool";
int g_case = 0;

std::string CaseDir() {
    char path[256];
    std::snprintf(path, sizeof(path), "%s/case-%d-%d", kRoot.c_str(), getpid(),
                  ++g_case);
    std::string dir(path);
    std::string cmd = "mkdir -p " + kRoot;
    if (std::system(cmd.c_str()) != 0) return "";
    if (::mkdir(dir.c_str(), 0700) != 0) return "";
    return dir;
}

JsonValue TestPayload(const std::string& tag, int n) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("observation_id", JsonValue::Str("obs-" + tag));
    payload.Set("n", JsonValue::Int(n));
    payload.Set("note", JsonValue::Str("opaque-bytes-" + tag));
    return payload;
}

// Realistic verified KSI envelope; caller supplies all ids/time (no clock).
JsonValue TestEnvelope(const std::string& msg_id, const std::string& channel,
                       std::int64_t seq, const std::string& flight,
                       const std::string& boot, const std::string& tag,
                       int n) {
    std::string boot_copy = boot;
    std::int64_t seq_copy = seq;
    return MakeMopEnvelope(msg_id, channel, "OBSERVATION", flight, &boot_copy,
                           &seq_copy, nullptr, TestPayload(tag, n),
                           1000000000LL + seq);
}

bool ReadFile(const std::string& path, std::string* out) {
    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    char buf[65536];
    out->clear();
    for (;;) {
        const ssize_t n = ::read(fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return false;
        }
        if (n == 0) break;
        out->append(buf, static_cast<std::size_t>(n));
    }
    ::close(fd);
    return true;
}

bool AppendBytes(const std::string& path, const std::string& bytes) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_APPEND);
    if (fd < 0) return false;
    std::size_t done = 0;
    while (done < bytes.size()) {
        const ssize_t n =
            ::write(fd, bytes.data() + done, bytes.size() - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return false;
        }
        done += static_cast<std::size_t>(n);
    }
    ::fsync(fd);
    ::close(fd);
    return true;
}

bool TruncateFile(const std::string& path, std::uint64_t size) {
    const int fd = ::open(path.c_str(), O_WRONLY);
    if (fd < 0) return false;
    const bool ok = ::ftruncate(fd, static_cast<off_t>(size)) == 0;
    ::fsync(fd);
    ::close(fd);
    return ok;
}

std::uint64_t FileSize(const std::string& path) {
    struct stat st;
    if (::stat(path.c_str(), &st) != 0) return 0;
    return static_cast<std::uint64_t>(st.st_size);
}

void CheckEnvelopeFields(const JsonValue& env, const std::string& msg_id,
                         const std::string& channel, std::int64_t seq,
                         const char* what) {
    CHECK(env.Find("msg_id") != nullptr &&
              env.Find("msg_id")->strValue() == msg_id,
          what);
    CHECK(env.Find("channel") != nullptr &&
              env.Find("channel")->strValue() == channel,
          what);
    CHECK(env.Find("seq") != nullptr && env.Find("seq")->intValue() == seq,
          what);
    CHECK(env.Find("flight_id") != nullptr &&
              env.Find("flight_id")->strValue() == "flt-7",
          what);
    CHECK(env.Find("edge_boot_id") != nullptr &&
              env.Find("edge_boot_id")->strValue() == "boot-1",
          what);
}

}  // namespace

int main() {
    // --- 1. append + ordered pending, fields preserved ----------------------
    {
        const std::string dir = CaseDir();
        CHECK(!dir.empty(), "case dir");
        DurableSpool spool(dir, 1 << 20);
        CHECK(spool.ok(), "spool opens clean");
        CHECK(!spool.torn_repaired(), "no repair on fresh spool");

        AppendResult r0 =
            spool.Append(TestEnvelope("m-0", "EVENT_RELIABLE", 0, "flt-7",
                                      "boot-1", "a", 1));
        AppendResult r1 =
            spool.Append(TestEnvelope("m-1", "EVENT_RELIABLE", 1, "flt-7",
                                      "boot-1", "b", 2));
        AppendResult r2 =
            spool.Append(TestEnvelope("m-2", "EVIDENCE_TRANSFER", 0, "flt-7",
                                      "boot-1", "c", 3));
        CHECK(r0.ok() && r1.ok() && r2.ok(), "appends succeed");
        CHECK(!r0.digest_hex.empty() && r0.digest_hex.size() == 64,
              "digest recorded");
        CHECK(spool.pending_count() == 3, "3 pending");
        CHECK(spool.total_records() == 3, "3 total");
        CHECK(spool.bytes_used() > 0, "bytes counted");

        const std::vector<PendingMessage> pending = spool.Pending();
        CHECK(pending.size() == 3, "pending copies");
        CHECK(pending[0].msg_id == "m-0" && pending[1].msg_id == "m-1" &&
                  pending[2].msg_id == "m-2",
              "pending order == append order");
        // Byte-exact preservation + still-valid MOP digest after a copy.
        for (std::size_t i = 0; i < pending.size(); ++i) {
            JsonValue expect = TestEnvelope(
                pending[i].msg_id,
                i < 2 ? "EVENT_RELIABLE" : "EVIDENCE_TRANSFER",
                i < 2 ? static_cast<std::int64_t>(i) : 0, "flt-7", "boot-1",
                i == 0 ? "a" : (i == 1 ? "b" : "c"),
                static_cast<int>(i + 1));
            CHECK(WriteCanonicalJson(pending[i].envelope) ==
                      WriteCanonicalJson(expect),
                  "pending envelope byte-identical");
            CHECK(pending[i].digest_hex == Sha256Hex(WriteCanonicalJson(
                                                      pending[i].envelope)),
                  "pending digest matches bytes");
            JsonValue payload;
            std::string reason;
            CHECK(VerifyMopEnvelope(pending[i].envelope, &payload, &reason),
                  "replayed envelope still verifies");
        }
        CheckEnvelopeFields(pending[0].envelope, "m-0", "EVENT_RELIABLE", 0,
                            "fields preserved");
    }

    // --- 2. restart: fresh object replays SAME ids/order --------------------
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.ok(), "open 1");
            CHECK(spool.Append(TestEnvelope("r-0", "EVENT_RELIABLE", 10,
                                            "flt-7", "boot-1", "x", 1))
                      .ok(),
                  "append r-0");
            CHECK(spool.Append(TestEnvelope("r-1", "EVENT_RELIABLE", 11,
                                            "flt-7", "boot-1", "y", 2))
                      .ok(),
                  "append r-1");
            CHECK(spool.Acknowledge("r-0").ok(), "ack r-0");
        }  // spool destroyed: simulates process exit
        DurableSpool reopened(dir, 1 << 20);
        CHECK(reopened.ok(), "reopen clean");
        CHECK(!reopened.torn_repaired(), "no spurious repair");
        CHECK(reopened.total_records() == 2, "acked record retained");
        CHECK(reopened.acked_count() == 1, "receipt survived restart");
        CHECK(reopened.pending_count() == 1, "only unacked replays");
        const std::vector<PendingMessage> pending = reopened.Pending();
        CHECK(pending.size() == 1 && pending[0].msg_id == "r-1",
              "same msg_id replays after restart");
        CHECK(pending[0].digest_hex ==
                  Sha256Hex(WriteCanonicalJson(TestEnvelope(
                      "r-1", "EVENT_RELIABLE", 11, "flt-7", "boot-1", "y",
                      2))),
              "same digest after restart");
        // Ack durability: ack the survivor, restart again, nothing pending.
        CHECK(reopened.Acknowledge("r-1").ok(), "ack r-1");
        CHECK(reopened.pending_count() == 0, "drained");
        DurableSpool third(dir, 1 << 20);
        CHECK(third.ok() && third.pending_count() == 0, "stays drained");
        CHECK(third.total_records() == 2, "journal never pruned");
    }

    // --- 3. ack semantics: unknown, duplicate -------------------------------
    {
        const std::string dir = CaseDir();
        DurableSpool spool(dir, 1 << 20);
        CHECK(spool.Append(TestEnvelope("a-0", "EVENT_RELIABLE", 0, "flt-7",
                                        "boot-1", "a", 1))
                  .ok(),
              "append a-0");
        auto missing = spool.Acknowledge("no-such-id");
        CHECK(missing.status == SpoolStatus::kNotFound, "unknown ack rejected");
        CHECK(spool.pending_count() == 1, "failed ack changes nothing");
        CHECK(spool.Acknowledge("a-0").ok(), "ack a-0");
        auto dup = spool.Acknowledge("a-0");
        CHECK(dup.ok() && dup.status == SpoolStatus::kDuplicate &&
                  dup.duplicate,
              "re-ack is idempotent duplicate");
        CHECK(spool.pending_count() == 0, "still drained after re-ack");
        auto empty = spool.Acknowledge("");
        CHECK(empty.status == SpoolStatus::kInvalid, "empty msg_id rejected");
    }

    // --- 4. duplicates idempotent / conflicts refused -----------------------
    {
        const std::string dir = CaseDir();
        DurableSpool spool(dir, 1 << 20);
        JsonValue env = TestEnvelope("d-0", "EVENT_RELIABLE", 0, "flt-7",
                                     "boot-1", "dup", 9);
        CHECK(spool.Append(env).ok(), "first append");
        const std::uint64_t used = spool.bytes_used();
        AppendResult again = spool.Append(env);
        CHECK(again.ok() && again.status == SpoolStatus::kDuplicate &&
                  again.duplicate,
              "same id + same bytes idempotent");
        CHECK(spool.pending_count() == 1, "no phantom copy");
        CHECK(spool.bytes_used() == used, "idempotent append writes nothing");
        JsonValue altered = TestEnvelope("d-0", "EVENT_RELIABLE", 0, "flt-7",
                                         "boot-1", "CHANGED", 9);
        AppendResult conflict = spool.Append(altered);
        CHECK(conflict.status == SpoolStatus::kConflict,
              "same id + different bytes conflicts");
        CHECK(spool.pending_count() == 1, "conflict changes nothing");
        CHECK(spool.bytes_used() == used, "conflict writes nothing");
        const std::vector<PendingMessage> pending = spool.Pending();
        CHECK(pending.size() == 1 &&
                  WriteCanonicalJson(pending[0].envelope) ==
                      WriteCanonicalJson(env),
              "original bytes untouched by conflict");
        // Duplicate survives restart without double journaling.
        DurableSpool reopened(dir, 1 << 20);
        CHECK(reopened.ok() && reopened.pending_count() == 1,
              "single copy after restart");
        CHECK(reopened.Append(env).status == SpoolStatus::kDuplicate,
              "duplicate still detected after restart");
    }

    // --- 5. invalid envelopes ------------------------------------------------
    {
        const std::string dir = CaseDir();
        DurableSpool spool(dir, 1 << 20);
        CHECK(spool.Append(JsonValue::Arr()).status == SpoolStatus::kInvalid,
              "non-object rejected");
        JsonValue no_id = JsonValue::Obj();
        no_id.Set("channel", JsonValue::Str("EVENT_RELIABLE"));
        CHECK(spool.Append(no_id).status == SpoolStatus::kInvalid,
              "missing msg_id rejected");
        JsonValue empty_id = JsonValue::Obj();
        empty_id.Set("msg_id", JsonValue::Str(""));
        CHECK(spool.Append(empty_id).status == SpoolStatus::kInvalid,
              "empty msg_id rejected");
        CHECK(spool.pending_count() == 0, "invalid appends queue nothing");
    }

    // --- 6. capacity bound: clear full, never drop-oldest -------------------
    {
        const std::string dir = CaseDir();
        DurableSpool probe(dir + "-probe", 1 << 20);
        CHECK(probe.ok(), "probe opens");
        JsonValue env = TestEnvelope("c-0", "EVENT_RELIABLE", 0, "flt-7",
                                     "boot-1", "cap", 1);
        CHECK(probe.Append(env).ok(), "probe append");
        const std::uint64_t one = probe.bytes_used();
        CHECK(one > 0, "probe measured");

        const std::string dir2 = CaseDir();
        DurableSpool spool(dir2, one);  // room for exactly one record
        CHECK(spool.ok(), "tight spool opens");
        CHECK(spool.Append(env).ok(), "first fits exactly");
        AppendResult full =
            spool.Append(TestEnvelope("c-1", "EVENT_RELIABLE", 1, "flt-7",
                                      "boot-1", "cap", 2));
        CHECK(full.status == SpoolStatus::kFull, "second append is full");
        CHECK(!full.error.empty(), "full carries an error message");
        CHECK(spool.pending_count() == 1, "full never drops oldest");
        CHECK(spool.bytes_used() == one, "full writes nothing");
        const std::vector<PendingMessage> pending = spool.Pending();
        CHECK(pending.size() == 1 && pending[0].msg_id == "c-0",
              "original intact at capacity");
        // Restart preserves the bound accounting.
        DurableSpool reopened(dir2, one);
        CHECK(reopened.ok() && reopened.bytes_used() == one,
              "capacity accounting survives restart");
        CHECK(reopened.Append(TestEnvelope("c-1", "EVENT_RELIABLE", 1, "flt-7",
                                           "boot-1", "cap", 2))
                      .status == SpoolStatus::kFull,
              "still full after restart");
    }

    // --- 7. torn trailing write: repair, keep history, append after ---------
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.Append(TestEnvelope("t-0", "EVENT_RELIABLE", 0,
                                            "flt-7", "boot-1", "t", 1))
                      .ok(),
                  "t-0");
            CHECK(spool.Append(TestEnvelope("t-1", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "t", 2))
                      .ok(),
                  "t-1");
        }
        const std::string log = dir + "/spool.log";
        const std::uint64_t size_before = FileSize(log);
        CHECK(size_before > 10, "journal has bytes");
        // Simulate a crash mid-write: 3 garbage bytes (short header).
        CHECK(AppendBytes(log, std::string("ZZZ", 3)), "inject torn tail");
        DurableSpool repaired(dir, 1 << 20);
        CHECK(repaired.ok(), "torn tail still opens");
        CHECK(repaired.torn_repaired(), "torn tail flagged");
        CHECK(repaired.pending_count() == 2, "committed records preserved");
        CHECK(FileSize(log) == size_before, "tail truncated, history kept");
        // Append works after repair; order preserved end to end.
        CHECK(repaired.Append(TestEnvelope("t-2", "EVENT_RELIABLE", 2, "flt-7",
                                           "boot-1", "t", 3))
                  .ok(),
              "append after repair");
        const std::vector<PendingMessage> pending = repaired.Pending();
        CHECK(pending.size() == 3 && pending[0].msg_id == "t-0" &&
                  pending[1].msg_id == "t-1" && pending[2].msg_id == "t-2",
              "order intact across torn repair");

        // Variant: truncation inside the last committed record.
        CHECK(TruncateFile(log, FileSize(log) - 5), "cut inside last record");
        DurableSpool repaired2(dir, 1 << 20);
        CHECK(repaired2.ok() && repaired2.torn_repaired(),
              "mid-record tear repaired");
        CHECK(repaired2.pending_count() == 2, "prior records preserved");
        CHECK(repaired2.Append(TestEnvelope("t-3", "EVENT_RELIABLE", 3,
                                            "flt-7", "boot-1", "t", 4))
                  .ok(),
              "append after mid-record repair");
        CHECK(repaired2.pending_count() == 3, "journal usable again");
    }

    // --- 8. interior corruption: fail closed, never silently skip -----------
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.Append(TestEnvelope("i-0", "EVENT_RELIABLE", 0,
                                            "flt-7", "boot-1", "i", 1))
                      .ok(),
                  "i-0");
            CHECK(spool.Append(TestEnvelope("i-1", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "i", 2))
                      .ok(),
                  "i-1");
        }
        const std::string log = dir + "/spool.log";
        const std::uint64_t size_before = FileSize(log);
        std::string journal;
        CHECK(ReadFile(log, &journal), "read journal");
        CHECK(journal.size() == size_before, "full read");
        // Flip one payload byte inside the FIRST record (interior).
        std::string corrupted = journal;
        corrupted[8 + 5] = static_cast<char>(corrupted[8 + 5] ^ 0xFF);
        {
            const int fd = ::open(log.c_str(), O_WRONLY | O_TRUNC);
            CHECK(fd >= 0, "open for corrupt");
            CHECK(::write(fd, corrupted.data(), corrupted.size()) ==
                      static_cast<ssize_t>(corrupted.size()),
                  "write corruption");
            ::fsync(fd);
            ::close(fd);
        }
        DurableSpool bad(dir, 1 << 20);
        CHECK(!bad.ok(), "interior corruption fails open");
        CHECK(bad.open_status() == SpoolStatus::kCorrupt, "corrupt status");
        CHECK(!bad.last_error().empty(), "corruption exposed, not silent");
        CHECK(bad.Pending().empty(), "fail closed: no partial replay");
        CHECK(bad.pending_count() == 0, "fail closed counts");
        CHECK(bad.Append(TestEnvelope("i-2", "EVENT_RELIABLE", 2, "flt-7",
                                      "boot-1", "i", 3))
                      .status == SpoolStatus::kCorrupt,
              "append refused while corrupt");
        CHECK(bad.Acknowledge("i-0").status == SpoolStatus::kCorrupt,
              "ack refused while corrupt");
        CHECK(FileSize(log) == size_before,
              "corrupt journal NOT truncated (no silent loss)");
    }

    // --- 9. fully-present trailing record with bad checksum: fail closed ----
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.Append(TestEnvelope("e-0", "EVENT_RELIABLE", 0,
                                            "flt-7", "boot-1", "e", 1))
                      .ok(),
                  "e-0");
        }
        const std::string log = dir + "/spool.log";
        const std::uint64_t size_before = FileSize(log);
        std::string journal;
        CHECK(ReadFile(log, &journal), "read journal");
        // Corrupt the last payload byte but keep framing/commit intact.
        std::string corrupted = journal;
        const std::size_t commit_at = corrupted.size() - 4;
        corrupted[commit_at - 65] =
            static_cast<char>(corrupted[commit_at - 65] ^ 0x01);
        {
            const int fd = ::open(log.c_str(), O_WRONLY | O_TRUNC);
            CHECK(fd >= 0, "open for corrupt");
            CHECK(::write(fd, corrupted.data(), corrupted.size()) ==
                      static_cast<ssize_t>(corrupted.size()),
                  "write corruption");
            ::fsync(fd);
            ::close(fd);
        }
        DurableSpool bad(dir, 1 << 20);
        CHECK(!bad.ok(), "trailing bad checksum fails closed, not truncated");
        CHECK(FileSize(log) == size_before, "committed bytes preserved");
    }

    // --- 10. fsync failure injection: no false success, retry safe ---------
    {
        const std::string dir = CaseDir();
        bool fail_sync = false;
        SpoolHooks hooks;
        hooks.fsync = [&](int fd) -> int {
            if (fail_sync) {
                errno = EIO;
                return -1;
            }
            return ::fsync(fd);
        };
        DurableSpool spool(dir, 1 << 20, hooks);
        CHECK(spool.ok(), "opens with hooks");
        JsonValue env = TestEnvelope("f-0", "EVENT_RELIABLE", 0, "flt-7",
                                     "boot-1", "f", 1);
        CHECK(spool.Append(env).ok(), "baseline append");
        fail_sync = true;
        AppendResult failed =
            spool.Append(TestEnvelope("f-1", "EVENT_RELIABLE", 1, "flt-7",
                                      "boot-1", "f", 2));
        CHECK(failed.status == SpoolStatus::kIoError,
              "fsync failure is an error, not success");
        CHECK(!failed.error.empty(), "io error exposed");
        CHECK(spool.ok(), "transient sync failure does not brick spool");
        CHECK(spool.pending_count() == 1, "failed append not queued");
        fail_sync = false;
        AppendResult retry =
            spool.Append(TestEnvelope("f-1", "EVENT_RELIABLE", 1, "flt-7",
                                      "boot-1", "f", 2));
        CHECK(retry.ok(), "retry after sync recovery succeeds");
        CHECK(spool.pending_count() == 2, "exactly one copy after retry");

        // Ack path: failed receipt sync leaves the message pending.
        fail_sync = true;
        auto ack_fail = spool.Acknowledge("f-0");
        CHECK(ack_fail.status == SpoolStatus::kIoError,
              "ack sync failure is an error");
        CHECK(spool.pending_count() == 2, "failed ack not delivered");
        fail_sync = false;
        CHECK(spool.Acknowledge("f-0").ok(), "ack retry succeeds");
        CHECK(spool.pending_count() == 1, "one survivor pending");
        DurableSpool reopened(dir, 1 << 20);
        CHECK(reopened.ok() && reopened.pending_count() == 1,
              "restart agrees with acked state");
        CHECK(reopened.Pending()[0].msg_id == "f-1", "survivor replays");
    }

    // --- 11. torn receipt line repair ---------------------------------------
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.Append(TestEnvelope("q-0", "EVENT_RELIABLE", 0,
                                            "flt-7", "boot-1", "q", 1))
                      .ok(),
                  "q-0");
            CHECK(spool.Append(TestEnvelope("q-1", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "q", 2))
                      .ok(),
                  "q-1");
            CHECK(spool.Acknowledge("q-0").ok(), "ack q-0");
        }
        const std::string acklog = dir + "/acked.log";
        CHECK(AppendBytes(acklog, "v1 q-1 par"), "inject torn receipt");
        DurableSpool repaired(dir, 1 << 20);
        CHECK(repaired.ok() && repaired.torn_repaired(),
              "torn receipt repaired");
        CHECK(repaired.acked_count() == 1, "committed receipt kept");
        CHECK(repaired.pending_count() == 1 &&
                  repaired.Pending()[0].msg_id == "q-1",
              "unacked message still replays");
    }

    // --- 12. constructor guards ------------------------------------------------
    {
        DurableSpool empty("", 1 << 20);
        CHECK(!empty.ok() &&
                  empty.open_status() == SpoolStatus::kInvalid,
              "empty path rejected");
        DurableSpool zero(CaseDir(), 0);
        CHECK(!zero.ok() && zero.open_status() == SpoolStatus::kInvalid,
              "zero capacity rejected");
        // A regular file where the directory should be.
        const std::string dir = CaseDir();
        const std::string blocker = dir + "/blocker";
        {
            const int fd =
                ::open(blocker.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
            CHECK(fd >= 0, "blocker file");
            if (fd >= 0) {
                CHECK(::write(fd, "x", 1) == 1, "blocker write");
                ::close(fd);
            }
        }
        DurableSpool blocked(blocker + "/spool", 1 << 20);
        CHECK(!blocked.ok(), "file-as-dir rejected");
    }


    // --- 13. compaction: acked payloads -> tombstones, pending bit-exact --
    {
        const std::string dir = CaseDir();
        DurableSpool spool(dir, 1 << 20);
        CHECK(spool.ok(), "compact case opens");
        // Reference bytes for k-0, built exactly as the loop below does.
        const std::string canon0 = WriteCanonicalJson(TestEnvelope(
            "k-0", "EVENT_RELIABLE", 0, "flt-7", "boot-1", "k0", 1));
        for (int i = 0; i < 4; ++i) {
            char tag[8];
            std::snprintf(tag, sizeof(tag), "k%d", i);
            CHECK(spool.Append(TestEnvelope("k-" + std::to_string(i),
                                            "EVENT_RELIABLE", i, "flt-7",
                                            "boot-1", tag, i + 1))
                      .ok(),
                  "append k-i");
        }
        // Oldest stays unacked; the rest are delivered and reclaimable.
        CHECK(spool.Acknowledge("k-1").ok(), "ack k-1");
        CHECK(spool.Acknowledge("k-2").ok(), "ack k-2");
        CHECK(spool.Acknowledge("k-3").ok(), "ack k-3");
        CHECK(spool.reclaimable_bytes() > 0, "acked payload counts reclaim");
        const std::uint64_t used_before = spool.bytes_used();
        CompactResult compacted = spool.Compact();
        CHECK(compacted.ok(), "compact succeeds");
        CHECK(compacted.records_compacted == 3, "3 acked payloads tombstoned");
        CHECK(compacted.bytes_reclaimed > 0, "bytes reclaimed reported");
        CHECK(spool.bytes_used() < used_before, "journal shrank");
        CHECK(spool.reclaimable_bytes() == 0, "pressure counter restarts");
        CHECK(spool.pending_count() == 1, "oldest unacked NEVER pruned");
        CHECK(spool.total_records() == 4, "journal never pruned");
        CHECK(spool.acked_count() == 3, "receipts intact");
        const std::vector<PendingMessage> pending = spool.Pending();
        CHECK(pending.size() == 1 && pending[0].msg_id == "k-0",
              "oldest id/order preserved");
        CHECK(WriteCanonicalJson(pending[0].envelope) == canon0,
              "oldest bytes bit-identical after compaction");
        CHECK(pending[0].digest_hex == Sha256Hex(canon0),
              "oldest digest intact");
        CheckEnvelopeFields(pending[0].envelope, "k-0", "EVENT_RELIABLE", 0,
                            "oldest fields (seq/flight/boot) preserved");
        // Dedup vs conflict semantics are identical in tombstone form.
        JsonValue same_k1 = TestEnvelope("k-1", "EVENT_RELIABLE", 1, "flt-7",
                                         "boot-1", "k1", 2);
        CHECK(spool.Append(same_k1).status == SpoolStatus::kDuplicate,
              "same id + same bytes still duplicate after compact");
        JsonValue changed_k2 = TestEnvelope("k-2", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "CHANGED", 2);
        CHECK(spool.Append(changed_k2).status == SpoolStatus::kConflict,
              "same id + different bytes still conflict after compact");
        CHECK(spool.Acknowledge("k-1").status == SpoolStatus::kDuplicate,
              "re-ack of tombstoned id stays idempotent");
        // New mail still fits in the reclaimed room.
        CHECK(spool.Append(TestEnvelope("k-4", "EVENT_RELIABLE", 4, "flt-7",
                                        "boot-1", "k4", 5))
                  .ok(),
              "append works after compact");
        // Restart agrees: same pending bytes, same dedup/conflict rules.
        DurableSpool reopened(dir, 1 << 20);
        CHECK(reopened.ok(), "reopen after compact");
        CHECK(reopened.pending_count() == 2, "unacked replay post-compact");
        CHECK(reopened.total_records() == 5, "tombstones + records retained");
        CHECK(reopened.acked_count() == 3, "receipts survive compact+restart");
        CHECK(WriteCanonicalJson(reopened.Pending()[0].envelope) == canon0,
              "oldest bytes identical after compact+restart");
        CHECK(reopened.Append(same_k1).status == SpoolStatus::kDuplicate,
              "duplicate detected after compact+restart");
        CHECK(reopened.Append(changed_k2).status == SpoolStatus::kConflict,
              "conflict detected after compact+restart");
        // Compacting again with nothing reclaimable is a no-op success.
        CompactResult noop = reopened.Compact();
        CHECK(noop.ok() && noop.records_compacted == 0 &&
                  noop.bytes_reclaimed == 0,
              "second compact is a no-op");
        CHECK(reopened.bytes_used() == spool.bytes_used(),
              "byte accounting identical after compact+restart");
    }

    // --- 14. cumulative ACKed traffic exceeds raw capacity -----------------
    // A healthy connected session must never wedge: delivered payloads
    // compact away, so the pending queue stays usable far past the raw
    // byte cap while the oldest UNACKED record is never touched.
    {
        const std::string dir = CaseDir();
        DurableSpool probe(dir + "-probe", 1 << 20);
        JsonValue sample = TestEnvelope("p-s", "EVENT_RELIABLE", 0, "flt-7",
                                        "boot-1", "s", 1);
        CHECK(probe.Append(sample).ok(), "probe append");
        const std::uint64_t one = probe.bytes_used();
        CHECK(one > 100, "record has realistic size");

        const std::uint64_t cap = 20 * one;
        DurableSpool spool(dir, cap);
        CHECK(spool.ok(), "cycle spool opens");
        // One unacked record pinned for the whole run (the oldest).
        JsonValue pinned = TestEnvelope("pinned-0", "EVENT_RELIABLE", 0,
                                        "flt-7", "boot-1", "pin", 0);
        const std::string pinned_canon = WriteCanonicalJson(pinned);
        CHECK(spool.Append(pinned).ok(), "pinned oldest queued");
        std::uint64_t cumulative_payload = 0;
        const int kCycles = 15;
        for (int c = 0; c < kCycles; ++c) {
            for (int j = 0; j < 2; ++j) {
                char id[32];
                std::snprintf(id, sizeof(id), "p-%d-%d", c, j);
                JsonValue env = TestEnvelope(id, "EVENT_RELIABLE",
                                            c * 2 + j + 1, "flt-7", "boot-1",
                                            id, c * 2 + j + 1);
                cumulative_payload +=
                    static_cast<std::uint64_t>(WriteCanonicalJson(env).size());
                AppendResult ar = spool.Append(env);
                CHECK(ar.ok(), "cycle append fits after reclaim");
                CHECK(ar.status == SpoolStatus::kOk, "no false duplicate");
                CHECK(spool.bytes_used() <= cap, "cap never exceeded");
                CHECK(spool.Acknowledge(id).ok(), "cycle ack delivers");
            }
            CompactResult cr = spool.Compact();
            CHECK(cr.ok() && cr.records_compacted == 2,
                  "each cycle reclaims its delivered pair");
        }
        CHECK(cumulative_payload > cap,
              "cumulative delivered payload exceeded raw capacity");
        CHECK(spool.bytes_used() <= cap, "journal still within capacity");
        CHECK(spool.pending_count() == 1, "only the pinned oldest pending");
        const std::vector<PendingMessage> pending = spool.Pending();
        CHECK(pending.size() == 1 && pending[0].msg_id == "pinned-0",
              "pinned oldest NEVER pruned across cycles");
        CHECK(WriteCanonicalJson(pending[0].envelope) == pinned_canon,
              "pinned bytes bit-identical across cycles");
        // The queue is still usable for new unacked work.
        CHECK(spool.Append(TestEnvelope("pinned-1", "EVENT_RELIABLE", 999,
                                        "flt-7", "boot-1", "pin", 1))
                  .ok(),
              "queue usable after heavy cycling");
        CHECK(spool.pending_count() == 2, "new work queues behind oldest");
        // Restart preserves the cycled state exactly.
        DurableSpool reopened(dir, cap);
        CHECK(reopened.ok() && reopened.pending_count() == 2,
              "cycled state replays after restart");
        CHECK(WriteCanonicalJson(reopened.Pending()[0].envelope) ==
                  pinned_canon,
              "pinned bytes identical after restart");
        CHECK(reopened.total_records() ==
                  static_cast<std::size_t>(1 + 2 * kCycles + 1),
              "every record (incl. tombstones) retained");
    }

    // --- 15. tombstone-only journal: honest full, no silent drop ------------
    {
        const std::string dir = CaseDir();
        DurableSpool probe(dir + "-probe", 1 << 20);
        JsonValue sample = TestEnvelope("f-s", "EVENT_RELIABLE", 0, "flt-7",
                                        "boot-1", "s", 1);
        CHECK(probe.Append(sample).ok(), "probe append");
        const std::uint64_t one = probe.bytes_used();
        // Capacity for ~2 full records: ack+compact cycles leave tombstones
        // + receipts until even those fill the journal.
        DurableSpool spool(dir, 2 * one);
        CHECK(spool.ok(), "tight spool opens");
        int delivered = 0;
        SpoolStatus last = SpoolStatus::kOk;
        for (int i = 0; i < 100; ++i) {
            char id[32];
            std::snprintf(id, sizeof(id), "f-%d", i);
            AppendResult ar = spool.Append(TestEnvelope(
                id, "EVENT_RELIABLE", i, "flt-7", "boot-1", id, i));
            if (ar.status == SpoolStatus::kFull) {
                last = SpoolStatus::kFull;
                break;
            }
            CHECK(ar.ok(), "fill append");
            CHECK(spool.Acknowledge(id).ok(), "fill ack");
            CompactResult cr = spool.Compact();
            CHECK(cr.ok(), "fill compact");
            ++delivered;
        }
        CHECK(last == SpoolStatus::kFull, "tombstone growth still bounded");
        CHECK(delivered > 0, "some generations compacted before full");
        CHECK(spool.pending_count() == 0, "nothing pending at tombstone cap");
        CHECK(spool.bytes_used() <= spool.capacity_bytes(),
              "cap never exceeded even by tombstones");
        // Honest full: Compact is a no-op (nothing reclaimable) and the
        // next append still refuses instead of dropping.
        CompactResult noop = spool.Compact();
        CHECK(noop.ok() && noop.records_compacted == 0,
              "no-op compact at tombstone cap");
        CHECK(spool.Append(TestEnvelope("f-late", "EVENT_RELIABLE", 9999,
                                        "flt-7", "boot-1", "late", 1))
                      .status == SpoolStatus::kFull,
              "still full after no-op compact (no false success)");
        // Dedup needs no room: same id + same bytes is still a duplicate.
        char last_id[32];
        std::snprintf(last_id, sizeof(last_id), "f-%d", delivered - 1);
        JsonValue last_same =
            TestEnvelope(last_id, "EVENT_RELIABLE", delivered - 1, "flt-7",
                         "boot-1", last_id, delivered - 1);
        CHECK(spool.Append(last_same).status == SpoolStatus::kDuplicate,
              "dedup works at tombstone cap without space");
        // Restart: still honestly full, nothing lost, nothing pending.
        DurableSpool reopened(dir, 2 * one);
        CHECK(reopened.ok(), "tombstone-cap journal reopens");
        CHECK(reopened.pending_count() == 0, "still drained after restart");
        CHECK(reopened.Append(TestEnvelope("f-late2", "EVENT_RELIABLE", 9998,
                                           "flt-7", "boot-1", "late", 2))
                      .status == SpoolStatus::kFull,
              "still honestly full after restart");
    }

    // --- 16. compact fsync failure: no false success, state unchanged -------
    {
        const std::string dir = CaseDir();
        bool fail_sync = false;
        SpoolHooks hooks;
        hooks.fsync = [&](int fd) -> int {
            if (fail_sync) {
                errno = EIO;
                return -1;
            }
            return ::fsync(fd);
        };
        DurableSpool spool(dir, 1 << 20, hooks);
        CHECK(spool.ok(), "hooks spool opens");
        JsonValue env_a = TestEnvelope("x-a", "EVENT_RELIABLE", 0, "flt-7",
                                       "boot-1", "x", 1);
        JsonValue env_b = TestEnvelope("x-b", "EVENT_RELIABLE", 1, "flt-7",
                                       "boot-1", "x", 2);
        CHECK(spool.Append(env_a).ok(), "append x-a");
        CHECK(spool.Append(env_b).ok(), "append x-b");
        CHECK(spool.Acknowledge("x-a").ok(), "ack x-a");
        const std::string canon_b = WriteCanonicalJson(env_b);
        const std::uint64_t used_before = spool.bytes_used();
        std::string disk_before;
        CHECK(ReadFile(dir + "/spool.log", &disk_before), "snapshot disk");
        // Fail the temp-file fsync inside Compact.
        fail_sync = true;
        CompactResult failed = spool.Compact();
        CHECK(failed.status == SpoolStatus::kIoError,
              "compact fsync failure is an error, not success");
        CHECK(!failed.error.empty(), "compact IO error exposed");
        CHECK(failed.records_compacted == 0, "no partial compact reported");
        CHECK(spool.ok(), "transient compact failure does not brick spool");
        CHECK(spool.bytes_used() == used_before,
              "in-memory bytes unchanged after failed compact");
        CHECK(spool.pending_count() == 1, "pending unchanged");
        CHECK(WriteCanonicalJson(spool.Pending()[0].envelope) == canon_b,
              "pending bytes unchanged after failed compact");
        std::string disk_after;
        CHECK(ReadFile(dir + "/spool.log", &disk_after), "re-read disk");
        CHECK(disk_after == disk_before,
              "on-disk journal untouched by failed compact");
        // Recovery: a clean compact succeeds and the journal is coherent.
        fail_sync = false;
        CompactResult recovered = spool.Compact();
        CHECK(recovered.ok() && recovered.records_compacted == 1,
              "compact succeeds after sync recovery");
        CHECK(spool.pending_count() == 1, "exactly the survivor pending");
        DurableSpool reopened(dir, 1 << 20);
        CHECK(reopened.ok() && reopened.pending_count() == 1,
              "restart agrees after recovered compact");
        CHECK(WriteCanonicalJson(reopened.Pending()[0].envelope) == canon_b,
              "survivor bytes survive failed+recovered compact");
    }

    // --- 17. compact rename failure: atomic swap or nothing ------------------
    {
        const std::string dir = CaseDir();
        // NOTE: a succeeding hook must perform the real rename; install the
        // failing hook only around the Compact call (open used no hook).
        DurableSpool seed(dir, 1 << 20);
        CHECK(seed.ok(), "seed journal");
        CHECK(seed.Append(TestEnvelope("y-a", "EVENT_RELIABLE", 0, "flt-7",
                                       "boot-1", "y", 1))
                  .ok(),
              "append y-a");
        CHECK(seed.Append(TestEnvelope("y-b", "EVENT_RELIABLE", 1, "flt-7",
                                       "boot-1", "y", 2))
                  .ok(),
              "append y-b");
        CHECK(seed.Acknowledge("y-a").ok(), "ack y-a");
        const std::string canon_b =
            WriteCanonicalJson(TestEnvelope("y-b", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "y", 2));
        const std::uint64_t used_before = seed.bytes_used();
        std::string disk_before;
        CHECK(ReadFile(dir + "/spool.log", &disk_before), "snapshot disk");
        // Reopen with the failing rename hook and attempt compaction.
        // (DurableSpool has no setter; scope the hooked handle freshly.)
        {
            SpoolHooks fail_hooks;
            fail_hooks.rename = [&](const char* from,
                                    const char* to) -> int {
                (void)from;
                (void)to;
                errno = EIO;
                return -1;
            };
            DurableSpool hooked(dir, 1 << 20, fail_hooks);
            CHECK(hooked.ok(), "hooked handle replays the journal");
            CHECK(hooked.pending_count() == 1, "survivor replays");
            CompactResult failed = hooked.Compact();
            CHECK(failed.status == SpoolStatus::kIoError,
                  "rename failure is an error, not success");
            CHECK(!failed.error.empty(), "rename error exposed");
            CHECK(hooked.bytes_used() == used_before,
                  "in-memory bytes unchanged after failed swap");
            CHECK(hooked.pending_count() == 1, "pending unchanged");
            CHECK(WriteCanonicalJson(hooked.Pending()[0].envelope) ==
                      canon_b,
                  "survivor bytes unchanged after failed swap");
        }
        std::string disk_after;
        CHECK(ReadFile(dir + "/spool.log", &disk_after), "re-read disk");
        CHECK(disk_after == disk_before,
              "original journal untouched by failed atomic swap");
        // A clean handle compacts fine and the world agrees.
        DurableSpool clean(dir, 1 << 20);
        CHECK(clean.ok() && clean.pending_count() == 1, "clean replays");
        CompactResult ok = clean.Compact();
        CHECK(ok.ok() && ok.records_compacted == 1,
              "compact succeeds with a working rename");
        CHECK(WriteCanonicalJson(clean.Pending()[0].envelope) == canon_b,
              "survivor intact through failed+clean compact");
    }

    // --- 18. torn tail AFTER compaction: repair keeps both generations ------
    {
        const std::string dir = CaseDir();
        {
            DurableSpool spool(dir, 1 << 20);
            CHECK(spool.Append(TestEnvelope("z-0", "EVENT_RELIABLE", 0,
                                            "flt-7", "boot-1", "z", 1))
                      .ok(),
                  "z-0");
            CHECK(spool.Append(TestEnvelope("z-1", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "z", 2))
                      .ok(),
                  "z-1");
            CHECK(spool.Acknowledge("z-0").ok(), "ack z-0");
            CompactResult cr = spool.Compact();
            CHECK(cr.ok() && cr.records_compacted == 1, "compact z-0");
        }
        const std::string log = dir + "/spool.log";
        const std::string canon_z1 =
            WriteCanonicalJson(TestEnvelope("z-1", "EVENT_RELIABLE", 1,
                                            "flt-7", "boot-1", "z", 2));
        // Crash mid-write right after the compacted generation.
        CHECK(AppendBytes(log, std::string("ZZZ", 3)), "inject torn tail");
        DurableSpool repaired(dir, 1 << 20);
        CHECK(repaired.ok(), "compacted journal with torn tail still opens");
        CHECK(repaired.torn_repaired(), "torn tail flagged post-compact");
        CHECK(repaired.acked_count() == 1, "tombstone ack state kept");
        CHECK(repaired.pending_count() == 1, "unacked full record kept");
        CHECK(WriteCanonicalJson(repaired.Pending()[0].envelope) == canon_z1,
              "post-compact survivor bit-identical");
        CHECK(repaired.Append(TestEnvelope("z-2", "EVENT_RELIABLE", 2, "flt-7",
                                           "boot-1", "z", 3))
                  .ok(),
              "journal usable after post-compact repair");
        CHECK(repaired.pending_count() == 2, "order continues past repair");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
