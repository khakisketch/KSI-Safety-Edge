// MopService (A9-2, DEVICE GATE): the PSDK-side MOP channel adapter.
//
// All message logic (framing, envelope verification, missions, evidence
// emission, retry/gap bookkeeping) lives in the host-tested transport core
// (edge/src/transport + edge/tests/test_mop_*.cpp). This file only moves
// bytes over the real DJI MOP channel:
//
//   DjiMopChannel_Init -> Create(RELIABLE) -> Bind(channelId) -> Accept
//   reader thread : DjiMopChannel_RecvData -> MopLink::Feed
//   timer thread  : MopLink::Flush + RetryOutstanding + periodic HEARTBEAT
//
// The channel id must match the MSDK side (`PipelineManager.connectPipeline`
// id — the RC app uses 0, `MSDK_DEFAULT_PIPELINE_ID`).
//
// DEVICE GATE: this file is compiled only inside the psdk-app target (PSDK
// headers required) and its behavior is verified on the RC ground session;
// host verification covers the transport core it drives.
#ifndef KSI_PSDK_APP_MOP_SERVICE_HPP
#define KSI_PSDK_APP_MOP_SERVICE_HPP

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ksi_safety/transport/audit_trail.hpp"
#include "ksi_safety/transport/durable_spool.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"
#include "source_context.hpp"

// Reads exactly len bytes of system entropy into out. Production passes a
// /dev/urandom reader; tests inject fakes.
using EntropyReader = std::function<bool(unsigned char*, std::size_t)>;

// Resolves the per-process boot identity from system entropy (128-bit hex).
// Failure is fatal to startup (returns false with a reason): a pid+clock
// fallback is NOT unique across device reboots, so weak entropy must never
// silently masquerade as a unique boot. Host-testable (no PSDK, no IO).
inline bool ResolveBootUnique(const EntropyReader& entropy,
                              std::string* unique_out, std::string* error_out) {
    unsigned char bytes[16];
    if (!entropy || !entropy(bytes, sizeof(bytes))) {
        if (error_out != nullptr) {
            *error_out = "system entropy unavailable for boot identity";
        }
        return false;
    }
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(32);
    for (unsigned char byte : bytes) {
        out.push_back(kHex[byte >> 4]);
        out.push_back(kHex[byte & 0xF]);
    }
    if (unique_out != nullptr) *unique_out = out;
    return true;
}

struct MopServiceConfig {
    // Flight id carried in every envelope. EMPTY means "learn it from the
    // wire": the RC creates the per-flight UUID (MissionStore) and the Edge
    // adopts it from the first RC envelope before emitting anything.
    std::string flight_id = "";
    // Must match the MSDK pipeline id on the RC side.
    std::uint16_t channel_id = 0;
    double heartbeat_fps = 15.0;
    int retry_max_attempts = 3;
    std::int64_t heartbeat_period_ms = 5000;
    std::int64_t flush_period_ms = 200;
    // Durable offline spool: evidence envelopes are fsync-journalled before
    // queueing and replayed with identical ids after reconnect/restart.
    // Operational mode REQUIRES a durable state dir (default: app-owned
    // /open_app/ksi-safety-edge/state, survives reboots; override with
    // --state-dir). Empty is rejected — silent in-memory fallback is
    // forbidden (it would pretend durability it lacks).
    std::string spool_dir = "/open_app/ksi-safety-edge/state";
    std::uint64_t spool_capacity_bytes = 64ULL * 1024ULL * 1024ULL;
    // Pins the boot identity (tests/diagnostics). Empty (default) means
    // fresh OS entropy per process — a restart with the same flight id
    // still gets a new boot id and runtime epoch, never ID reuse.
    std::string boot_id = "";

    // Operational policy check (host-testable, no PSDK, no IO). Fails when
    // the spool dir is empty or relative, or the capacity is zero.
    bool Validate(std::string* error) const {
        if (spool_dir.empty()) {
            if (error != nullptr) {
                *error =
                    "spool_dir required: pass --state-dir (silent in-memory "
                    "spool is forbidden in operational mode)";
            }
            return false;
        }
        if (spool_dir[0] != '/') {
            if (error != nullptr) {
                *error = "spool_dir must be absolute: " + spool_dir;
            }
            return false;
        }
        if (spool_capacity_bytes == 0) {
            if (error != nullptr) {
                *error = "spool_capacity_bytes must be > 0";
            }
            return false;
        }
        return true;
    }
};

class MopService : public ksi::safety::MopByteTransport {
 public:
    explicit MopService(MopServiceConfig config);
    ~MopService() override;

    MopService(const MopService&) = delete;
    MopService& operator=(const MopService&) = delete;

    // Creates/binds the channel and starts the reader (which blocks in
    // Accept until the RC connects) + timer threads. Non-blocking.
    bool Start();
    void Stop();

    // MopByteTransport: writes one whole frame, looping over the SDK's
    // partial writes. False = transport broken (the link tears down and
    // resends after a fresh connection).
    bool WriteAll(const std::uint8_t* data, std::size_t len) override;

    // Emission surface for the app loop (only emit while connected()).
    ksi::safety::MopLink* link() { return link_.get(); }

    // --- Run-control surface for the app loop (Unit A / contract G9) ------
    // The endpoint owns the command facts (desired intent, monotonic
    // revision/order/identity, applied generation); the runner owns
    // execution. The app loop polls ONE coherent snapshot per iteration and
    // drives the runner — see SyncRunControl in main.cpp. Never combine the
    // legacy separately sampled counters into one poll: a command landing
    // between the samples fuses contradictory facts. This method copies the
    // intent, the generation and (on a generation move) the bundle under a
    // SINGLE evidence_mutex_ acquisition, so they can never contradict.
    // All methods take evidence_mutex_ (same contract as
    // flightIdConfirmed).
    struct MopRunCommandSnapshot {
        // Coherent intent + generation + bundle presence, one lock.
        ksi::safety::MopSessionCommands session;
        // True when the applied generation moved since the previous snapshot
        // (tracked inside the service — exactly one consumer polls).
        bool bundle_changed = false;
        // Fresh bundle copy iff bundle_changed && session.has_bundle.
        ksi::safety::JsonValue bundle;
        // Deferred device path: a validated-but-uninstalled MISSION_APPLY
        // awaiting the real install receipt. Attempted (install +
        // confirm/reject) every poll while present.
        bool has_pending_bundle = false;
        ksi::safety::JsonValue pending_bundle;
    };
    void TakeRunCommandSnapshot(MopRunCommandSnapshot* out);
    // Write-ahead audit drain: accepted commands since the last drain
    // (msg ids the worker has not consumed yet). The adapter journals
    // these BEFORE RunControl acts on them. Cursor resets on epoch change.
    std::vector<ksi::safety::AcceptedCommand> TakeAcceptedCommands(
        bool* overflow);
    // Audit records (in-memory trail + fsync-journalled file under the
    // state dir). False on journal failure (sticky; the adapter latches the
    // durable failure and skips the matching claim). Claims (confirm /
    // facts / summary / ship) always come after the matching audit record.
    bool AuditIdentity(std::int64_t now_mono_ns);
    bool AuditCommand(const ksi::safety::AcceptedCommand& command,
                      std::uint64_t command_epoch, std::int64_t now_mono_ns);
    bool AuditInstall(const std::string& bundle_id,
                      const std::string& bundle_sha,
                      std::uint64_t applied_generation,
                      const std::string& outcome, const std::string& reason,
                      std::int64_t now_mono_ns);
    bool AuditState(const ksi::safety::RuntimeSnapshot& snapshot,
                    std::int64_t now_mono_ns);
    bool AuditClose(std::uint64_t close_ts, std::uint64_t summary_ticks,
                    std::int64_t now_mono_ns);
    // Ships the trail as a required FLIGHT_LOG artifact (existing pipeline;
    // identical ids per boot, RC keeps the latest). Call at close and on
    // reconnect; no-op when the trail is empty.
    bool ShipAuditTrail();
    bool auditFailed(std::string* reason_out) const;
    // Deferred-apply install receipt (device path). Confirm switches the
    // bundle and answers MISSION_APPLIED; Reject answers REJECTED with the
    // installer's reasons. False when nothing was pending (or the identity
    // changed first — the caller parks instead).
    bool ConfirmPendingApply();
    bool RejectPendingApply(const std::vector<std::string>& reasons);
    void PublishRuntimeFacts(const ksi::safety::MopRuntimeFacts& facts);

    // The app path: one processed tick -> observation/event evidence. The
    // evidence is ALWAYS recorded (BuildTick folds aggregates) once the
    // flight identity is confirmed and a mission is installed — link state
    // never gates recording. Delivery happens in TimerLoop::Flush when
    // connected; exact envelopes are spool-journalled at queue time and
    // replayed after reconnect/restart.
    // Returns true when this tick produced an observation envelope (the
    // caller needs that signal to keep the source frame of the very
    // observation that went out).
    bool EmitTick(const ksi::safety::TickRecord& record);
    // Unit A closure: one sealed assessment JSON over the existing
    // artifact plane (ANNOUNCE + CHUNKs, source_kind EDGE_ASSESSMENT,
    // required=false). Same gates as EmitTick (see AssessmentEmitAllowed);
    // false = gated/dropped (counted below), never silently lost and never
    // read as delivered — the sealed store copy is the retention path.
    bool EmitAssessmentArtifact(const std::string& asset_id,
                                const std::string& flight_id,
                                const std::string& edge_boot_id,
                                const std::string& assessment_json);
    // Gated assessments (identity/mission gate shut) and spool-dropped
    // assessments (journal unwritable). Device health reads these; the
    // host suite pins the gate truth table (not-counted-as-deliverable).
    std::uint64_t skipped_assessments() const {
        return skipped_assessments_.load();
    }
    std::uint64_t dropped_assessments() const {
        return dropped_assessments_.load();
    }
    // Shutdown path: FLIGHT_SUMMARY for the session (no-op without ticks).
    void EmitFinalSummary();
    ksi::safety::MopTickEvidence* evidence() { return evidence_.get(); }

    bool connected() const { return connected_.load(); }
    bool flightIdConfirmed() const;
    // Runtime identity of the current flight/boot (learned from the wire).
    // The source-evidence store links a stored frame to the observation it
    // produced, so it needs the same identity the MOP envelope carries.
    std::string flightId() const;
    std::string edgeBootId() const;
    // Flight + boot from ONE endpoint lock; never fuse two adoption epochs.
    ksi_safety_edge_app::SourceIdentity SourceIdentitySnapshot() const;
    std::uint64_t skipped_evidence_ticks() const {
        return skipped_ticks_.load();
    }
    // Recorded while disconnected (aggregates folded, envelopes queued +
    // spool-journalled, delivery on reconnect). Never counted as skipped.
    std::uint64_t offline_recorded_ticks() const {
        return offline_ticks_.load();
    }
    // Ticks refused a durable record (spool unwritable). Never folded.
    std::uint64_t durable_dropped_ticks() const {
        return durable_dropped_ticks_.load();
    }
    // Sticky journal failure with the truthful IO cause. When true the
    // caller must degrade the run and stop accepting new observation work.
    bool spoolFailed(std::string* reason_out) const;

    /**
     * The outbound link stalled (the peer stopped reading). The run must
     * degrade instead of claiming progress: a blocked write freezes the
     * whole analysis loop (measured on the Manifold 3).
     */
    bool writeStalled(std::string* reason_out) const;
    std::uint64_t spool_refused() const;
    std::uint64_t bytes_in() const { return bytes_in_.load(); }
    std::uint64_t bytes_out() const { return bytes_out_.load(); }

 private:
    void ReaderLoop();
    void TimerLoop();
    void CloseHandles();
    // ShipAuditTrail without taking evidence_mutex_ (caller already holds
    // it: the public wrapper, or the reader reconnect path).
    bool ShipAuditTrailLocked();
    // Replays every previous boot's journal as its own content-addressed
    // FLIGHT_LOG artifact (oldest first), each under its ORIGINAL
    // flight/boot identity. Called once in Start, before threads exist.
    void RestorePriorAudits();
    // Pulls mission/policy provenance from the applied bundle into the
    // evidence context, and clears the context when the flight identity
    // changed without one (no stale-flight evidence). Caller MUST hold
    // [evidence_mutex_].
    void RefreshMissionContextLocked();

    MopServiceConfig config_;
    // Boot identity resolution failure (entropy unavailable and no pinned
    // id): Start fails closed instead of running under a reused boot.
    // Declared before endpoint_: the endpoint is constructed from it.
    std::string boot_init_error_;
    ksi::safety::MopEdgeEndpoint endpoint_;
    std::unique_ptr<ksi::safety::MopLink> link_;
    std::unique_ptr<ksi::safety::MopTickEvidence> evidence_;
    // Identity-partitioned audit journals (one per boot) with
    // write-ahead fsync. Shipped as FLIGHT_LOG artifacts at close (current
    // trail) and reconnect (current + restored priors) so offline input
    // outages stay reconstructible without frame copies.
    ksi::safety::AuditJournalSet audits_;
    // Accepted-command drain cursor (endpoint seq). Reset on epoch change
    // (adoption/reboot reset the endpoint log alongside).
    std::uint64_t audit_command_seq_ = 0;
    std::uint64_t audit_command_epoch_ = 0;
    bool audit_epoch_seen_ = false;
    // Durable offline spool (owned here, borrowed by the endpoint for
    // journal-at-Send, ACK delivery and reconnect replay). Created in Start
    // so a bad directory fails Start loudly.
    std::unique_ptr<ksi::safety::DurableSpool> spool_;
    std::string ResolveBootIdentity();
    std::string last_context_key_;
    // Generation reported by the last TakeRunCommandSnapshot: the bundle is
    // copied only on a move, so the hot poll never copies it. Exactly one
    // consumer (the worker loop) polls, so reported == consumed.
    std::uint64_t last_run_generation_ = 0;
    // FLIGHT_SUMMARY idempotence: EmitFinalSummary is called on every
    // SESSION_STOP and at shutdown; it emits only when new ticks arrived
    // since the previous emission (same aggregates => same summary).
    std::uint64_t last_summary_ticks_ = 0;
    // Locking: the endpoint object is NOT thread-safe. Every cross-thread
    // touch goes through MopLink (a MopLink method or WithEndpoint), which
    // serialises with its internal mutex. Service methods hold evidence_
    // first and the link second — never the reverse; leaf locks (write_,
    // channel_) never take anything else. No cycles by construction:
    // evidence -> link -> channel/spool.
    mutable std::mutex evidence_mutex_;
    std::atomic<std::uint64_t> skipped_ticks_{0};
    std::atomic<std::uint64_t> offline_ticks_{0};
    // Ticks refused a durable record: spool unwritable (latched DEGRADED).
    // Never folded into aggregates — the summary stays free of records the
    // RC can never receive.
    std::atomic<std::uint64_t> durable_dropped_ticks_{0};
    // Sealed assessments gated (identity/mission) or spool-dropped: never
    // deliverable, never counted as delivered. The sealed store copy is
    // the retention path.
    std::atomic<std::uint64_t> skipped_assessments_{0};
    std::atomic<std::uint64_t> dropped_assessments_{0};
    std::thread reader_;
    std::thread timer_;
    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::atomic<std::uint64_t> bytes_in_{0};
    std::atomic<std::uint64_t> bytes_out_{0};
    std::mutex write_mutex_;
    // Guards the raw channel handles: open/teardown only. Leaf lock —
    // never take another lock while holding it (link -> write -> channel
    // order holds everywhere else).
    std::mutex channel_mutex_;
    void* listen_handle_ = nullptr;  // T_DjiMopChannelHandle
    void* conn_handle_ = nullptr;    // T_DjiMopChannelHandle
};

#endif  // KSI_PSDK_APP_MOP_SERVICE_HPP
