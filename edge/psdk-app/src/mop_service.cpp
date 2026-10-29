#include "mop_service.hpp"

#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <thread>
#include <utility>

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"

extern "C" {
#include <dji_mop_channel.h>
#include <dji_typedef.h>
}

namespace {

constexpr std::size_t kRecvBufferBytes = 64U * 1024U;
constexpr std::int64_t kNsPerMs = 1000000LL;
// Consecutive SDK read errors before the connection is torn down and the
// service re-Accepts (a zero-byte read with success is idle, not an error).
constexpr int kMaxConnErrors = 50;

std::int64_t MonotonicNowNs() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return 0;
    }
    return static_cast<std::int64_t>(value.tv_sec) * 1000000000LL +
           static_cast<std::int64_t>(value.tv_nsec);
}

bool SdkOk(T_DjiReturnCode code) {
    return code == DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

void SleepMs(std::int64_t ms) {
    if (ms <= 0) ms = 50;
    if (ms > 1000) ms = 1000;
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

// Production entropy source for the per-process boot identity. Failure is
// fatal to startup (fail closed): a pid+clock fallback is NOT unique across
// device reboots, so weak entropy must never silently masquerade as unique.
bool ReadSystemEntropy(unsigned char* out, std::size_t len) {
    const int fd = ::open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    std::size_t done = 0;
    while (done < len) {
        const ssize_t n = ::read(fd, out + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return false;
        }
        if (n == 0) {
            ::close(fd);
            return false;
        }
        done += static_cast<std::size_t>(n);
    }
    ::close(fd);
    return true;
}

}  // namespace

MopService::MopService(MopServiceConfig config)
    : config_(std::move(config)),
      endpoint_(config_.flight_id, [] { return MonotonicNowNs(); }, 0,
                config_.boot_id, ResolveBootIdentity()) {
    // The service IS the device install path: an MISSION_APPLY is only a
    // validated request until the runner really installs it (confirm) or
    // refuses it (reject). No evidence context ever binds the uninstalled.
    endpoint_.setDeferredApply(true);
    link_ = std::make_unique<ksi::safety::MopLink>(this, &endpoint_);
    evidence_ = std::make_unique<ksi::safety::MopTickEvidence>();
}

MopService::~MopService() { Stop(); }

std::string MopService::ResolveBootIdentity() {
    if (!config_.boot_id.empty()) {
        return "";  // pinned: endpoint uses config_.boot_id verbatim
    }
    std::string unique;
    if (!ResolveBootUnique(ReadSystemEntropy, &unique, &boot_init_error_)) {
        std::fprintf(stderr, "Mop: boot identity failed: %s\n",
                     boot_init_error_.c_str());
        return "";
    }
    return unique;
}

bool MopService::Start() {
    if (running_.load()) {
        return true;
    }
    {
        std::string error;
        if (!config_.Validate(&error)) {
            std::fprintf(stderr, "Mop: invalid spool config: %s\n",
                         error.c_str());
            return false;
        }
    }
    if (!boot_init_error_.empty()) {
        // Fail closed: no unique boot, no execution under a reused identity.
        std::fprintf(stderr, "Mop: cannot start: %s\n",
                     boot_init_error_.c_str());
        return false;
    }
    if (!SdkOk(DjiMopChannel_Init())) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Init failed\n");
        return false;
    }
    T_DjiMopChannelHandle listen = nullptr;
    if (!SdkOk(DjiMopChannel_Create(&listen, DJI_MOP_CHANNEL_TRANS_RELIABLE)) ||
        listen == nullptr) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Create failed\n");
        return false;
    }
    listen_handle_ = listen;
    if (!SdkOk(DjiMopChannel_Bind(listen, config_.channel_id))) {
        std::fprintf(stderr, "Mop: DjiMopChannel_Bind(%u) failed\n",
                     static_cast<unsigned>(config_.channel_id));
        CloseHandles();
        return false;
    }
    // Durable offline spool: evidence envelopes fsync-journal before they
    // queue, and replay with identical ids after reconnect/restart. Validate
    // above guarantees a non-empty absolute dir: a directory that cannot
    // open cleanly fails Start loudly — silent in-memory fallback would
    // pretend durability it lacks.
    spool_ = std::make_unique<ksi::safety::DurableSpool>(
        config_.spool_dir, config_.spool_capacity_bytes);
    if (!spool_->ok()) {
        std::fprintf(stderr, "Mop: spool %s unusable: %s\n",
                     config_.spool_dir.c_str(),
                     spool_->last_error().c_str());
        spool_.reset();
        CloseHandles();
        return false;
    }
    if (spool_->torn_repaired()) {
        std::fprintf(stderr, "Mop: spool %s repaired a torn tail\n",
                     config_.spool_dir.c_str());
    }
    endpoint_.setSpool(spool_.get());
    // Identity-partitioned audit journals (one per boot, opened on the
    // first confirmed identity). Same fail-closed policy as the spool: an
    // unusable state dir fails Start loudly instead of silently dropping
    // the offline history.
    {
        std::string error;
        if (!audits_.Open(config_.spool_dir, ksi::safety::AuditHooks{},
                          &error)) {
            std::fprintf(stderr, "Mop: audit state unusable: %s\n",
                         error.c_str());
            spool_.reset();
            endpoint_.setSpool(nullptr);
            CloseHandles();
            return false;
        }
    }
    // Prior boots' journals ship first (oldest history first): each ships
    // under its ORIGINAL flight/boot identity with a content-addressed id,
    // so restarts never relabel or duplicate history.
    RestorePriorAudits();
    running_.store(true);
    reader_ = std::thread([this] { ReaderLoop(); });
    timer_ = std::thread([this] { TimerLoop(); });
    return true;
}

void MopService::Stop() {
    if (!running_.exchange(false)) {
        CloseHandles();
        return;
    }
    // Unblock a thread parked in Accept/RecvData: closing the handles is the
    // only SDK-level wake-up (no cancel API). The reader re-checks running_
    // after every unblock, so even a platform that ignores close on Accept
    // still exits its loops on the next event — only a truly uninterruptible
    // block can delay join (same residual as before, now documented).
    CloseHandles();
    if (reader_.joinable()) reader_.join();
    if (timer_.joinable()) timer_.join();
    connected_.store(false);
    CloseHandles();
}

void MopService::CloseHandles() {
    std::lock_guard<std::mutex> guard(channel_mutex_);
    if (conn_handle_ != nullptr) {
        (void)DjiMopChannel_Close(
            static_cast<T_DjiMopChannelHandle>(conn_handle_));
        (void)DjiMopChannel_Destroy(
            static_cast<T_DjiMopChannelHandle>(conn_handle_));
        conn_handle_ = nullptr;
    }
    if (listen_handle_ != nullptr) {
        (void)DjiMopChannel_Close(
            static_cast<T_DjiMopChannelHandle>(listen_handle_));
        (void)DjiMopChannel_Destroy(
            static_cast<T_DjiMopChannelHandle>(listen_handle_));
        listen_handle_ = nullptr;
    }
}

bool MopService::WriteAll(const std::uint8_t* data, std::size_t len) {
    std::lock_guard<std::mutex> guard(write_mutex_);
    // connected_ (seq_cst) is the release/acquire edge for conn_handle_:
    // check it FIRST, then read the handle written by the reader thread.
    if (!connected_.load()) {
        return false;
    }
    void* handle = nullptr;
    {
        // Tear-down (Stop/ReaderLoop) replaces the handle under this lock;
        // never send on a destroyed handle.
        std::lock_guard<std::mutex> channel_guard(channel_mutex_);
        handle = conn_handle_;
    }
    auto* channel = static_cast<T_DjiMopChannelHandle>(handle);
    if (channel == nullptr) {
        return false;
    }
    std::size_t offset = 0;
    while (offset < len) {
        const auto remaining = static_cast<std::uint32_t>(
            len - offset > 0xFFFFFFFFU ? 0xFFFFFFFFU : len - offset);
        std::uint32_t sent = 0;
        const T_DjiReturnCode rc = DjiMopChannel_SendData(
            channel, const_cast<std::uint8_t*>(data + offset), remaining, &sent);
        if (!SdkOk(rc) || sent == 0) {
            return false;  // partial write = broken stream (link policy)
        }
        offset += sent;
        bytes_out_.fetch_add(sent);
    }
    return true;
}

bool MopService::flightIdConfirmed() const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return endpoint->flightIdConfirmed();
    });
}

std::string MopService::flightId() const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return endpoint->flight_id();
    });
}

std::string MopService::edgeBootId() const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return endpoint->edge_boot_id();
    });
}

ksi_safety_edge_app::SourceIdentity MopService::SourceIdentitySnapshot() const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return ksi_safety_edge_app::SourceIdentity{endpoint->flight_id(), endpoint->edge_boot_id()};
    });
}

bool MopService::EmitTick(const ksi::safety::TickRecord& record) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    const bool confirmed =
        link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
            return endpoint->flightIdConfirmed();
        });
    if (!confirmed) {
        skipped_ticks_.fetch_add(1);
        return false;
    }
    RefreshMissionContextLocked();
    if (evidence_->context().mission_revision_id.empty()) {
        // No mission bundle applied yet: observations would lack the
        // mission provenance the evidence contract requires.
        skipped_ticks_.fetch_add(1);
        return false;
    }
    // Durability gate: never fold a tick whose exact bytes cannot be
    // journalled — the summary must stay free of records the RC can never
    // receive. The failure latches (endpoint) and degrades the run via
    // NoteDurableFailure in SyncRunControl.
    {
        std::string spool_error = "spool unwritable";
        const bool writable = link_->WithEndpoint(
            [&](ksi::safety::MopEdgeEndpoint* endpoint) {
                if (!endpoint->CheckSpoolWritable()) {
                    std::string detail;
                    if (endpoint->spoolFailed(&detail) && !detail.empty()) {
                        spool_error = detail;
                    }
                    return false;
                }
                return true;
            });
        if (!writable) {
            durable_dropped_ticks_.fetch_add(1);
            return false;
        }
    }
    // Record ALWAYS: aggregates fold and the exact envelopes queue here
    // even while the link is down (spool-journalled at queue time, flushed
    // on reconnect). A MOP drop never loses local observation — only
    // delivery waits for the transport.
    evidence_->EmitTick(link_.get(), record);
    if (!connected_.load()) {
        offline_ticks_.fetch_add(1);
    }
    // The observation envelope carries the judgement and its digest, not
    // the bytes the detector consumed. The source frame is captured by the
    // caller (evidence store) under this observation id.
    return record.detection_status == ksi::safety::DetectionStatus::kOk;
}

// Unit A closure: one sealed assessment JSON over the existing artifact
// plane (no new MOP type). Same gates as EmitTick: confirmed identity, an
// installed mission (the assessment cites it), and a journal-writable
// spool — a refused assessment is dropped and counted, never folded
// anywhere, and the caller degrades via the shared write-failure path.
bool MopService::EmitAssessmentArtifact(const std::string& asset_id,
                                        const std::string& flight_id,
                                        const std::string& edge_boot_id,
                                        const std::string& assessment_json) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    ksi::safety::AssessmentEmitGate gate;
    gate.flight_confirmed =
        link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
            return endpoint->flightIdConfirmed();
        });
    gate.mission_installed = !evidence_->context().mission_revision_id.empty();
    {
        gate.spool_writable = link_->WithEndpoint(
            [&](ksi::safety::MopEdgeEndpoint* endpoint) {
                if (!endpoint->CheckSpoolWritable()) {
                    std::string detail;
                    if (endpoint->spoolFailed(&detail) && !detail.empty()) {
                        (void)detail;
                    }
                    return false;
                }
                return true;
            });
    }
    if (!ksi::safety::AssessmentEmitAllowed(gate)) {
        // Gated/dropped is never deliverable and never folded anywhere:
        // the sealed store copy is the retention path. Counted here so
        // device health can tell "not sent" apart from "never sealed".
        if (!gate.spool_writable && gate.flight_confirmed &&
            gate.mission_installed) {
            durable_dropped_ticks_.fetch_add(1);
            dropped_assessments_.fetch_add(1);
        } else {
            skipped_ticks_.fetch_add(1);
            skipped_assessments_.fetch_add(1);
        }
        return false;
    }
    ksi::safety::EmitAssessmentArtifact(link_.get(), asset_id, flight_id,
                                        edge_boot_id, assessment_json);
    if (!connected_.load()) {
        offline_ticks_.fetch_add(1);
    }
    return true;
}

namespace {

// MOP 송신 예산 (REQ-08): 한 번의 Flush가 링크 mutex를 붙잡는 시간을
// 제한하고, 대량 대기(backlog)가 생겼을 때만 제어 차선을 켠다.
// 평상시 경로는 핀 픽스처(`contracts/mop/edge-to-rc.mopv1.hex`)와
// 바이트 동일하게 FIFO를 유지한다.
constexpr std::size_t kFlushEnvelopeBudget = 32;
constexpr std::size_t kFlushByteBudget = 256U * 1024U;
constexpr std::size_t kBacklogBeforePriorityLane = 8;

}  // namespace

void MopService::EmitFinalSummary() {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    const bool confirmed =
        link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
            return endpoint->flightIdConfirmed();
        });
    if (!confirmed) {
        return;
    }
    if (evidence_->ticks_seen() == last_summary_ticks_) {
        return;  // No new ticks since the previous summary: identical bytes.
    }
    // A latched spool failure marks the summary: never clean while the RC
    // is missing records (the refused ticks were never folded in).
    evidence_->setDurableGap(link_->WithEndpoint(
        [](ksi::safety::MopEdgeEndpoint* endpoint) {
            std::string ignored;
            return endpoint->spoolFailed(&ignored);
        }));
    std::string flight_id;
    std::string boot_id;
    link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
        flight_id = endpoint->flight_id();
        boot_id = endpoint->edge_boot_id();
    });
    const std::string session_id =
        ksi::safety::MopTickEvidence::SessionId(flight_id, boot_id);
    if (evidence_->EmitSummary(link_.get(), session_id)) {
        last_summary_ticks_ = evidence_->ticks_seen();
        link_->Flush();  // best effort: a disconnected channel drops it
    }
}

void MopService::TakeRunCommandSnapshot(MopRunCommandSnapshot* out) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    if (out == nullptr) {
        return;
    }
    // One lock, one coherent picture: intent, generation and bundle presence
    // are read together (link-serialised with the reader thread), so a
    // command landing mid-poll cannot fuse a stale START with a fresh STOP
    // (or drop either).
    link_->WithEndpoint([this, out](ksi::safety::MopEdgeEndpoint* endpoint) {
        out->session = endpoint->session_commands();
        if (out->session.applied_generation == last_run_generation_) {
            out->bundle_changed = false;
            out->bundle = ksi::safety::JsonValue();
        } else {
            last_run_generation_ = out->session.applied_generation;
            out->bundle_changed = true;
            const ksi::safety::JsonValue* bundle = endpoint->appliedBundle();
            out->bundle =
                (bundle != nullptr) ? *bundle : ksi::safety::JsonValue();
        }
        // Deferred install path: the validated-but-uninstalled request,
        // copied while present. The worker attempts the install and
        // confirms/rejects, which clears it — attempted every poll until
        // then.
        const ksi::safety::JsonValue* pending = endpoint->pendingBundle();
        out->has_pending_bundle = (pending != nullptr);
        out->pending_bundle =
            (pending != nullptr) ? *pending : ksi::safety::JsonValue();
    });
}

bool MopService::ConfirmPendingApply() {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return endpoint->ConfirmPendingApply();
    });
}
bool MopService::RejectPendingApply(
    const std::vector<std::string>& reasons) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint(
        [&](ksi::safety::MopEdgeEndpoint* endpoint) {
            return endpoint->RejectPendingApply(reasons);
        });
}

std::uint64_t MopService::spool_refused() const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
        return endpoint->spool_refused();
    });
}

bool MopService::writeStalled(std::string* reason_out) const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    if (!link_->writeStalled()) return false;
    if (reason_out != nullptr) *reason_out = link_->writeStallReason();
    return true;
}

bool MopService::spoolFailed(std::string* reason_out) const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint(
        [&](ksi::safety::MopEdgeEndpoint* endpoint) {
            return endpoint->spoolFailed(reason_out);
        });
}

std::vector<ksi::safety::AcceptedCommand>
MopService::TakeAcceptedCommands(bool* overflow) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return link_->WithEndpoint(
        [&](ksi::safety::MopEdgeEndpoint* endpoint) {
            const std::uint64_t epoch =
                endpoint->session_commands().command_epoch;
            if (!audit_epoch_seen_ || epoch != audit_command_epoch_) {
                audit_epoch_seen_ = true;
                audit_command_epoch_ = epoch;
                audit_command_seq_ = 0;
            }
            std::vector<ksi::safety::AcceptedCommand> drained =
                endpoint->AcceptedCommandsSince(audit_command_seq_,
                                                overflow);
            if (!drained.empty()) {
                audit_command_seq_ = drained.back().seq;
            }
            return drained;
        });
}

bool MopService::AuditIdentity(std::int64_t now_mono_ns) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    std::string flight_id;
    std::string boot_id;
    bool confirmed = false;
    link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
        flight_id = endpoint->flight_id();
        boot_id = endpoint->edge_boot_id();
        confirmed = endpoint->flightIdConfirmed();
    });
    // Rotation (if any) ships the outgoing trail under ITS identity first;
    // the fresh journal then binds the new one. Unconfirmed flights record
    // nothing anywhere.
    ksi::safety::AuditJournalSet::Shipment outgoing =
        audits_.EnsureIdentity(flight_id, boot_id, confirmed, now_mono_ns);
    if (!outgoing.empty) {
        link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
            endpoint->EmitArtifactAs(outgoing.artifact_id, "FLIGHT_LOG",
                                     "application/x-ndjson", 0, true,
                                     outgoing.bytes, outgoing.flight_id,
                                     outgoing.edge_boot_id);
        });
    }
    return audits_.journalOk();
}

bool MopService::AuditCommand(const ksi::safety::AcceptedCommand& command,
                              std::uint64_t command_epoch,
                              std::int64_t now_mono_ns) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return audits_.RecordCommand(command.msg_id, command.type, command.seq,
                                    command_epoch, now_mono_ns);
}

bool MopService::AuditInstall(const std::string& bundle_id,
                              const std::string& bundle_sha,
                              std::uint64_t applied_generation,
                              const std::string& outcome,
                              const std::string& reason,
                              std::int64_t now_mono_ns) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return audits_.RecordInstall(bundle_id, bundle_sha, applied_generation,
                                    outcome, reason, now_mono_ns);
}

bool MopService::AuditState(const ksi::safety::RuntimeSnapshot& snapshot,
                            std::int64_t now_mono_ns) {
    std::string degraded;
    for (const std::string& reason : snapshot.degraded_reasons) {
        if (!degraded.empty()) degraded += ";";
        degraded += reason;
    }
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return audits_.NoteState(
        ksi::safety::ToString(snapshot.state), snapshot.input_state.video,
        snapshot.input_state.telemetry, snapshot.input_state.engine,
        degraded, snapshot.processed_frame_count, snapshot.config_epoch,
        now_mono_ns);
}

bool MopService::AuditClose(std::uint64_t close_ts, std::uint64_t summary_ticks,
                            std::int64_t now_mono_ns) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return audits_.RecordClose(close_ts, summary_ticks, now_mono_ns);
}

bool MopService::ShipAuditTrail() {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    return ShipAuditTrailLocked();
}

bool MopService::ShipAuditTrailLocked() {
    // Caller holds evidence_mutex_ (public wrapper or reader reconnect).
    // The set builds the shipment under ITS OWN recorded identity (never
    // the live endpoint's — a rotation in the same poll must not relabel
    // the history being shipped).
    ksi::safety::AuditJournalSet::Shipment ship = audits_.CurrentShipment();
    if (ship.empty) {
        return true;  // nothing recorded under the active identity
    }
    link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
        endpoint->EmitArtifactAs(ship.artifact_id, "FLIGHT_LOG",
                                 "application/x-ndjson", 0, true,
                                 ship.bytes, ship.flight_id,
                                 ship.edge_boot_id);
    });
    return true;
}

void MopService::RestorePriorAudits() {
    // Runs once in Start (single-threaded): replays every previous boot's
    // journal as its own content-addressed FLIGHT_LOG artifact, oldest
    // first, each under its ORIGINAL flight/boot identity (read from the
    // journal itself — never relabelled under the new boot). Unreadable
    // files are skipped loudly for forensics; they are never rewritten.
    const std::vector<std::string> boots = audits_.ListBoots();
    std::string current_boot;
    {
        std::lock_guard<std::mutex> guard(evidence_mutex_);
        current_boot = link_->WithEndpoint(
            [](ksi::safety::MopEdgeEndpoint* endpoint) {
                return endpoint->edge_boot_id();
            });
    }
    for (const std::string& boot : boots) {
        if (boot == current_boot) {
            continue;  // this boot ships live, not from restore
        }
        ksi::safety::AuditJournalSet::Shipment ship;
        bool repaired = false;
        std::string error;
        if (!audits_.ShipBootFile(boot, &ship, &repaired, &error) ||
            ship.empty) {
            std::fprintf(stderr, "Mop: prior audit %s skipped: %s\n",
                         boot.c_str(), error.c_str());
            continue;
        }
        if (repaired) {
            std::fprintf(stderr, "Mop: prior audit boot %s had a torn tail "
                         "(truncated to the last committed record)\n",
                         boot.c_str());
        }
        {
            std::lock_guard<std::mutex> guard(evidence_mutex_);
            link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
                endpoint->EmitArtifactAs(ship.artifact_id, "FLIGHT_LOG",
                                         "application/x-ndjson", 0, true,
                                         ship.bytes, ship.flight_id,
                                         ship.edge_boot_id);
            });
        }
    }
}

bool MopService::auditFailed(std::string* reason_out) const {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    if (audits_.journalOk()) {
        return false;
    }
    if (reason_out != nullptr) {
        *reason_out = audits_.lastError();
    }
    return true;
}

void MopService::PublishRuntimeFacts(
    const ksi::safety::MopRuntimeFacts& facts) {
    std::lock_guard<std::mutex> guard(evidence_mutex_);
    link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
        endpoint->PublishRuntimeFacts(facts);
    });
}

void MopService::RefreshMissionContextLocked() {
    // One coherent read under link serialization: identity + bundle move
    // together (a command landing mid-read cannot mix flights). The evidence
    // context itself is service-owned (evidence_mutex_ already held).
    std::string flight_id;
    std::string boot_id;
    ksi::safety::JsonValue bundle_copy;
    bool has_bundle = false;
    link_->WithEndpoint([&](ksi::safety::MopEdgeEndpoint* endpoint) {
        flight_id = endpoint->flight_id();
        boot_id = endpoint->edge_boot_id();
        const ksi::safety::JsonValue* bundle = endpoint->appliedBundle();
        if (bundle != nullptr) {
            bundle_copy = *bundle;
            has_bundle = true;
        }
    });
    if (!has_bundle) {
        // Adoption/reboot without an applied bundle yet: the OLD context
        // (previous flight/boot) must not stamp new ticks — review fix
        // (F2): reset to the current identity with no mission, so EmitTick
        // gates as skipped instead of misattributing evidence.
        if (evidence_->context().flight_id != flight_id ||
            evidence_->context().edge_boot_id != boot_id) {
            ksi::safety::MopEvidenceContext context;
            context.flight_id = flight_id;
            context.edge_boot_id = boot_id;
            evidence_->SetContext(std::move(context));
            last_context_key_.clear();
        }
        return;
    }
    const ksi::safety::JsonValue* id = bundle_copy.Find("bundle_id");
    const std::string bundle_id =
        id != nullptr && id->IsString() ? id->strValue() : std::string();
    const std::string key = bundle_id + "|" + flight_id + "|" + boot_id;
    if (key == last_context_key_) {
        return;
    }
    ksi::safety::MopEvidenceContext context;
    std::string error;
    if (!ksi::safety::EvidenceContextFromBundle(bundle_copy, &context,
                                                &error)) {
        std::fprintf(stderr, "Mop: evidence context rejected: %s\n",
                     error.c_str());
        return;
    }
    context.flight_id = flight_id;
    context.edge_boot_id = boot_id;
    evidence_->SetContext(std::move(context));
    last_context_key_ = key;
    std::fprintf(stderr, "Mop: evidence context set (bundle %s)\n",
                 bundle_id.c_str());
}

void MopService::ReaderLoop() {
    // Accept blocks until the RC (MSDK pipeline) connects to our channel id.
    // Bounded re-accept: after a connection drops (or Accept fails) the
    // loop tears the handles down and waits for the next connection while
    // the app runs. Endpoint/link/spool state (ids, seqs, outbox, journal)
    // is preserved across reconnects by design — only partial-frame
    // reassembly resets. Spool replay (identical ids) happens on every
    // (re)connect below.
    while (running_.load()) {
        // Snapshot the listen handle without holding the lock across the
        // blocking Accept (Stop must be able to close it concurrently).
        void* listen_copy = nullptr;
        {
            std::lock_guard<std::mutex> guard(channel_mutex_);
            if (!running_.load()) {
                return;
            }
            listen_copy = listen_handle_;
        }
        T_DjiMopChannelHandle accepted = nullptr;
        if (!SdkOk(DjiMopChannel_Accept(
                static_cast<T_DjiMopChannelHandle>(listen_copy),
                &accepted)) ||
            accepted == nullptr) {
            // RC absent (or transient failure): back off, keep waiting.
            SleepMs(500);
            continue;
        }
        {
            std::lock_guard<std::mutex> guard(channel_mutex_);
            if (!running_.load()) {
                // Stopped while accepting: drop the fresh handle.
                (void)DjiMopChannel_Close(accepted);
                (void)DjiMopChannel_Destroy(accepted);
                return;
            }
            conn_handle_ = accepted;
        }
        connected_.store(true);
        std::fprintf(stderr, "Mop: channel %u connected\n",
                     static_cast<unsigned>(config_.channel_id));
        // Replay spooled-but-unacked envelopes with identical ids/seqs
        // before fresh traffic flows (no-op without a spool or backlog).
        // Serialized with every other endpoint touch (link mutex, inside
        // the evidence lock — the global evidence -> link order).
        {
            std::lock_guard<std::mutex> guard(evidence_mutex_);
            const std::size_t replayed =
                link_->WithEndpoint([](ksi::safety::MopEdgeEndpoint* endpoint) {
                    return endpoint->ReplaySpool();
                });
            if (replayed > 0) {
                std::fprintf(stderr, "Mop: replayed %zu spooled envelopes\n",
                             replayed);
            }
            // Reconnect ships the offline history (events recorded while
            // disconnected) as a FLIGHT_LOG artifact — same id per boot,
            // the RC keeps the latest per lineage.
            ShipAuditTrailLocked();
        }
        link_->ResetReassembly();

        auto* handle = static_cast<T_DjiMopChannelHandle>(accepted);
        int errors = 0;
        while (running_.load()) {
            std::uint8_t buffer[kRecvBufferBytes];
            std::uint32_t received = 0;
            const T_DjiReturnCode rc = DjiMopChannel_RecvData(
                handle, buffer, static_cast<std::uint32_t>(sizeof(buffer)),
                &received);
            if (SdkOk(rc) && received > 0) {
                errors = 0;
                bytes_in_.fetch_add(received);
                link_->Feed(buffer, received);
            } else if (!SdkOk(rc)) {
                // SDK read error: a few are tolerated (transient), then the
                // connection is torn down and re-Accepted. Zero-byte success
                // reads are idle, never errors.
                if (++errors > kMaxConnErrors) {
                    break;
                }
                SleepMs(5);
            } else {
                errors = 0;
                SleepMs(5);
            }
        }
        // Teardown this connection; the next outer-loop pass re-Accepts.
        {
            std::lock_guard<std::mutex> guard(channel_mutex_);
            if (conn_handle_ != nullptr) {
                (void)DjiMopChannel_Close(
                    static_cast<T_DjiMopChannelHandle>(conn_handle_));
                (void)DjiMopChannel_Destroy(
                    static_cast<T_DjiMopChannelHandle>(conn_handle_));
                conn_handle_ = nullptr;
            }
        }
        connected_.store(false);
        link_->ResetReassembly();
    }
}

void MopService::TimerLoop() {
    std::int64_t last_heartbeat_ms = 0;
    while (running_.load()) {
        SleepMs(config_.flush_period_ms);
        if (!connected_.load()) {
            continue;
        }
        {
            std::lock_guard<std::mutex> guard(evidence_mutex_);
            const bool confirmed = link_->WithEndpoint(
                [](ksi::safety::MopEdgeEndpoint* endpoint) {
                    return endpoint->flightIdConfirmed();
                });
            if (!confirmed) {
                // Nothing may be sent before the RC's flight identity is
                // known: emitting would stamp envelopes with a provisional id.
                continue;
            }
            RefreshMissionContextLocked();
        }
        const std::int64_t now_ms = MonotonicNowNs() / kNsPerMs;
        if (now_ms - last_heartbeat_ms >= config_.heartbeat_period_ms) {
            last_heartbeat_ms = now_ms;
            link_->EmitHeartbeat(config_.heartbeat_fps);
        }
        link_->RetryOutstanding(config_.retry_max_attempts);
        // 대량이 쌓였을 때만 제어(명령·상태·하트비트)를 앞세운다.
        // 평상시에는 FIFO — 계약 픽스처의 바이트 순서를 그대로 지킨다.
        link_->Flush(kFlushEnvelopeBudget, kFlushByteBudget,
                     link_->backlog() > kBacklogBeforePriorityLane);
    }
}
