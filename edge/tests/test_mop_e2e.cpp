// MOP end-to-end (A9-2): the Edge endpoint + link over a fake transport.
//
// Cross-language fixtures:
//  - rc-to-edge.mopv1.hex  : RC→Edge command frames (Kotlin rc-core
//    MopEndpoint, same JSON/frames the app sends).
//  - contracts/mop/edge-to-rc.mopv1.hex: historical response, reader pin.
//  - tests/fixtures/edge-to-rc-current.mopv1.hex: current C++ response plus
//    scripted evidence, consumed by Kotlin through MopEndpoint + EvidenceIngest.
//
// In-memory failure injection: partial write (teardown + resend), corrupt
// frames, duplicate delivery, retry/unrecoverable gaps, reboot reset.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_evidence.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"
#include "support/check.hpp"

using ksi::safety::Detection;
using ksi::safety::DetectionStatus;
using ksi::safety::DigestExcluding;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::EvidenceContextFromBundle;
using ksi::safety::JsonValue;
using ksi::safety::MakeDetectionEventPayload;
using ksi::safety::MakeObservationPayload;
using ksi::safety::MopByteTransport;
using ksi::safety::MopDetectionEventInput;
using ksi::safety::MopDetectionItem;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopEvidenceContext;
using ksi::safety::MopTickEvidence;
using ksi::safety::MopFrameCodec;
using ksi::safety::MopLink;
using ksi::safety::MopObservationInput;
using ksi::safety::MopProtocol;
using ksi::safety::MopReassembler;
using ksi::safety::MopTelemetryRef;
using ksi::safety::ParseJson;
using ksi::safety::PoseQuality;
using ksi::safety::RuleOutput;
using ksi::safety::Sha256Hex;
using ksi::safety::SpatialTickReason;
using ksi::safety::TickRecord;
using ksi::safety::Track;
using ksi::safety::TrackProvenance;
using ksi::safety::TrackRecord;
using ksi::safety::Uuid5;

namespace {

struct FakeTransport : MopByteTransport {
    std::string written;
    std::size_t fail_after = static_cast<std::size_t>(-1);
    bool failed_once = false;

    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        if (!failed_once && fail_after != static_cast<std::size_t>(-1) &&
            written.size() + len > fail_after) {
            failed_once = true;  // one-shot partial-write simulation
            return false;
        }
        written.append(reinterpret_cast<const char*>(data), len);
        return true;
    }
};

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;  // +1 ms per emitted envelope
        return value;
    }
};

std::string Hex(const std::string& bytes) {
    static const char kHexChars[] = "0123456789abcdef";
    std::string out;
    for (const char raw : bytes) {
        const auto b = static_cast<unsigned char>(raw);
        out.push_back(kHexChars[b >> 4]);
        out.push_back(kHexChars[b & 0x0F]);
    }
    return out;
}

std::string FramesToBytes(const std::vector<std::string>& frames) {
    std::string out;
    for (const auto& frame : frames) out += frame;
    return out;
}

std::string BytesEnvelope(const JsonValue& envelope) {
    return FramesToBytes(MopFrameCodec::Encode(
        ksi::safety::WriteCanonicalJson(envelope)));
}

bool ReadFixture(const std::string& name, std::string* bytes,
                 const std::string& directory = KSI_MOP_CONTRACTS_DIR) {
    const std::string path = directory + "/" + name;
    std::ifstream stream(path);
    if (!stream.good()) return false;
    std::string hex_line;
    std::getline(stream, hex_line);
    std::string decoded;
    if (hex_line.size() % 2 != 0) return false;
    auto nibble = [](char ch) -> int {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        return -1;
    };
    for (std::size_t i = 0; i < hex_line.size(); i += 2) {
        const int hi = nibble(hex_line[i]);
        const int lo = nibble(hex_line[i + 1]);
        if (hi < 0 || lo < 0) return false;
        decoded.push_back(static_cast<char>((hi << 4) | lo));
    }
    *bytes = decoded;
    return true;
}

const std::string kFlight = "flt-e2e";
const std::string kBundleId = Uuid5(MopProtocol::Namespace(), "bundle|flt-e2e");

JsonValue MakeBundle() {
    JsonValue snapshot = JsonValue::Obj();
    snapshot.Set("site_id", JsonValue::Str("site-e2e"));
    JsonValue policy = JsonValue::Obj();
    policy.Set("site_id", JsonValue::Str("site-e2e"));
    JsonValue bundle = JsonValue::Obj();
    bundle.Set("bundle_id", JsonValue::Str(kBundleId));
    bundle.Set("site_id", JsonValue::Str("site-e2e"));
    bundle.Set("mission_snapshot", std::move(snapshot));
    bundle.Set("risk_policy", std::move(policy));
    bundle.Set("bundle_sha256", JsonValue::Str(DigestExcluding(bundle, "bundle_sha256")));
    return bundle;
}

const std::string kModelDigest(64, 'a');
const std::string kArtifactId =
    Uuid5(MopProtocol::Namespace(), "artifact|flt-e2e|0");
const std::string kArtifactBytes = [] {
    std::string bytes;
    for (int i = 0; i < 100; ++i) bytes.push_back(static_cast<char>(i % 251));
    return bytes;
}();

TickRecord MakeScenarioTick(std::int64_t recv_ns) {
    TickRecord record;
    record.tick_index = 1;
    record.frame_id = 1;
    record.receive_mono_ns = recv_ns;
    record.model_id = "dfine-s-coco";
    record.model_digest = kModelDigest;
    record.exposure_verified = false;
    record.detection_status = DetectionStatus::kOk;
    record.attitude_quality = PoseQuality::kReceiveNearest;
    record.attitude_gap_ns = 4774438;
    record.position_quality = PoseQuality::kReceiveNearest;
    record.position_gap_ns = 4800000;
    record.spatial_status = SpatialTickReason::kDisabled;

    Detection person;
    person.x_min = 10.0F;
    person.y_min = 20.0F;
    person.x_max = 30.5F;
    person.y_max = 40.0F;
    person.score = 0.9F;
    person.class_id = 0;
    record.detections.push_back(person);

    Track observed;
    observed.track_id = 3;
    observed.class_id = 0;
    observed.box.x_min = person.x_min;
    observed.box.y_min = person.y_min;
    observed.box.x_max = person.x_max;
    observed.box.y_max = person.y_max;
    observed.confirmed = true;
    observed.hits = 3;
    observed.age = 3;
    observed.last_seen_ns = static_cast<std::uint64_t>(recv_ns);
    observed.epoch = 1;
    TrackRecord observed_record;
    observed_record.track = observed;
    observed_record.provenance = TrackProvenance::kObserved;
    record.tracks.push_back(observed_record);

    RuleOutput proximity;
    proximity.rule_id = "prox-demo";
    proximity.is_shadow = true;
    EpisodeEvent created;
    created.kind = EpisodeEventKind::kCreated;
    created.episode_id = 1;
    created.rule_id = "prox-demo";
    created.is_shadow = true;
    created.received_mono_ns = static_cast<std::uint64_t>(recv_ns);
    created.evidence.is_proximity = true;
    created.evidence.interval_available = true;
    created.evidence.lower_m = 2.57;
    created.evidence.upper_m = 8.05;
    proximity.events.push_back(created);
    EpisodeEvent updated = created;
    updated.kind = EpisodeEventKind::kUpdated;  // must NOT emit
    proximity.events.push_back(updated);
    record.proximity.push_back(proximity);
    return record;
}

// The scenario's evidence comes from the REAL mapping chain: the mission
// provenance is read from the applied bundle that arrived over the wire
// (Kotlin-generated MISSION_PREPARE) and the tick payloads are built by
// MopTickEvidence — not hand-assembled for the fixture.
void EmitScenarioEvidence(MopLink* link, MopEdgeEndpoint* endpoint) {
    const JsonValue* bundle = endpoint->appliedBundle();
    CHECK(bundle != nullptr, "scenario: bundle applied");
    MopEvidenceContext context;
    std::string error;
    CHECK(EvidenceContextFromBundle(*bundle, &context, &error),
          "scenario: bundle supplies mission/policy provenance");
    context.flight_id = endpoint->flight_id();
    context.edge_boot_id = endpoint->edge_boot_id();
    MopTickEvidence emitter(std::move(context));
    emitter.EmitTick(link, MakeScenarioTick(1050000000LL));
    link->EmitArtifact(kArtifactId, "EDGE_SNAPSHOT", "image/jpeg", 0, true,
                       kArtifactBytes);
    emitter.EmitSummary(link, MopTickEvidence::SessionId(
                                 endpoint->flight_id(),
                                 endpoint->edge_boot_id()));
    link->EmitHeartbeat(15.0);
}

}  // namespace

int main() {
    // --- in-memory: partial write teardown + resend -------------------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        endpoint.EmitHeartbeat(15.0);
        const std::size_t queued = endpoint.outboxSize();
        transport.fail_after = 7;  // fail inside the first frame
        CHECK(link.Flush() == 0, "flush reports 0 on write failure");
        CHECK(endpoint.outboxSize() == 1, "failed envelope stays queued");
        CHECK(static_cast<std::size_t>(queued) == endpoint.outboxSize(),
              "queue unchanged by failure");
        // Reconnect policy: fresh reassembler; resend the same envelope.
        link.ResetReassembly();
        transport.fail_after = static_cast<std::size_t>(-1);
        CHECK(link.Flush() == 1, "resend succeeds after failure");
        CHECK(endpoint.outboxSize() == 0, "queue drains after resend");
    }

    // --- in-memory: corrupt frame + duplicate + retry/gaps + reboot ---------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);

        // Corrupt frame: counted, no message, link keeps working after reset.
        const std::string corrupt("\x00\x01\x00\x01\x00", 5);
        link.Feed(reinterpret_cast<const std::uint8_t*>(corrupt.data()),
                  corrupt.size());
        CHECK(link.framing_errors() == 1, "corrupt frame counted");
        CHECK(link.messages_received() == 0, "corrupt frame is not a message");

        // Duplicate command delivery: first handled, second deduped with the
        // stored response re-queued (ACK loss recovery).
        JsonValue bundle = MakeBundle();
        JsonValue prepare_payload = JsonValue::Obj();
        prepare_payload.Set("bundle", bundle);
        const std::string prepare_id =
            Uuid5(MopProtocol::Namespace(), "rc|flt-e2e|1|MISSION_PREPARE");
        JsonValue prepare_env = ksi::safety::MakeMopEnvelope(
            prepare_id, "CONTROL_RELIABLE", "MISSION_PREPARE", kFlight,
            nullptr, nullptr, nullptr, std::move(prepare_payload), 1234LL);
        const std::string prepare_bytes = BytesEnvelope(prepare_env);
        link.Feed(reinterpret_cast<const std::uint8_t*>(prepare_bytes.data()),
                  prepare_bytes.size());
        CHECK(link.messages_received() == 1, "prepare delivered");
        const std::size_t outbox_after_prepare = endpoint.outboxSize();
        CHECK(outbox_after_prepare == 2,  // MISSION_PREPARED + ACK
              "prepare queues response + ACK");
        link.Feed(reinterpret_cast<const std::uint8_t*>(prepare_bytes.data()),
                  prepare_bytes.size());
        CHECK(link.messages_received() == 2, "duplicate still counts a message");
        CHECK(endpoint.outboxSize() == outbox_after_prepare + 1,
              "duplicate re-queues the stored response only");
        CHECK(link.Flush() == outbox_after_prepare + 1, "flush after prepare");

        // Digest-valid bundle WITHOUT bundle_id: harness parity = transport
        // error, no response, no ACK (only a buggy sender reaches this).
        {
            JsonValue broken = MakeBundle();
            broken.Erase("bundle_id");
            broken.Set("bundle_sha256",
                       JsonValue::Str(DigestExcluding(broken, "bundle_sha256")));
            JsonValue broken_payload = JsonValue::Obj();
            broken_payload.Set("bundle", std::move(broken));
            const std::string malformed_id = Uuid5(
                MopProtocol::Namespace(), "rc|flt-e2e|x|MISSION_PREPARE");
            JsonValue malformed_env = ksi::safety::MakeMopEnvelope(
                malformed_id, "CONTROL_RELIABLE", "MISSION_PREPARE", kFlight,
                nullptr, nullptr, nullptr, std::move(broken_payload), 999LL);
            const std::string malformed_bytes = BytesEnvelope(malformed_env);
            const std::size_t errors_before = link.receive_errors();
            const std::size_t outbox_before = endpoint.outboxSize();
            link.Feed(
                reinterpret_cast<const std::uint8_t*>(malformed_bytes.data()),
                malformed_bytes.size());
            CHECK(link.receive_errors() == errors_before + 1,
                  "malformed prepare counted as a transport error");
            CHECK(endpoint.outboxSize() == outbox_before,
                  "malformed prepare: no response, no ACK");
        }

        // Observation emission: unacked until ACK; retry then gap declaration.
        // (The MISSION_PREPARED response from above is still unacked — it
        // never received an ACK — so measure relative to that baseline.)
        const std::size_t unacked_before = endpoint.unackedCount();
        endpoint.EmitObservation(MakeObservationPayload([] {
            MopObservationInput input;
            input.observation_id = "obs-retry";
            input.flight_id = kFlight;
            input.sync_quality = "VALID";
            input.geometry_status = "WITHHELD";
            return input;
        }()));
        CHECK(endpoint.unackedCount() == unacked_before + 1,
              "observation tracked unacked");
        link.RetryOutstanding(2);  // first pass: retire nothing, requeue all
        CHECK(endpoint.unackedCount() == unacked_before + 1,
              "still unacked after first retry");
        link.RetryOutstanding(2);  // second pass: retire the DATA seq
        CHECK(endpoint.unackedCount() == unacked_before,
              "observation retired after failed retry");
        endpoint.EmitHeartbeat(15.0);
        // A heartbeat is PROTOCOL CHATTER: it is regenerated every second and
        // must NOT be tracked as "awaiting an ACK". Tracking it made the Edge
        // re-send the same heartbeat forever, so the RC saw only duplicates
        // and its freshness fact never advanced.
        const std::size_t unacked_before_heartbeat = endpoint.unackedCount();
        endpoint.EmitHeartbeat(1.0);
        CHECK(endpoint.unackedCount() == unacked_before_heartbeat,
              "a heartbeat is not tracked as awaiting an ACK");
        const JsonValue& heartbeat_env = endpoint.outbox().back();
        const JsonValue* heartbeat_payload = heartbeat_env.Find("payload");
        CHECK(heartbeat_payload != nullptr &&
                  heartbeat_payload->Find("unrecoverable_gaps") != nullptr &&
                  heartbeat_payload->Find("unrecoverable_gaps")->Size() == 1,
              "heartbeat declares the EVENT_RELIABLE gap");
        link.Flush();

        // Reboot: new boot id, seqs reset, applied state cleared.
        const std::string old_boot = endpoint.edge_boot_id();
        const std::string new_boot = endpoint.Reboot();
        CHECK(new_boot != old_boot, "reboot rotates the boot id");
        CHECK(endpoint.seq("EVENT_RELIABLE") == 0, "seqs reset on reboot");
        CHECK(!endpoint.hasAppliedBundle(), "applied state cleared");
    }

    // --- flight-id adoption (the RC creates the per-flight UUID) -----------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint("", [&clock] { return clock(); });
        CHECK(!endpoint.flightIdConfirmed(), "empty id starts unconfirmed");
        FakeTransport transport;
        MopLink link(&transport, &endpoint);

        JsonValue bundle = MakeBundle();
        JsonValue prepare_payload = JsonValue::Obj();
        prepare_payload.Set("bundle", std::move(bundle));
        const std::string prepare_id = Uuid5(
            MopProtocol::Namespace(), "rc|rc-flight-9|1|MISSION_PREPARE");
        JsonValue prepare_env = ksi::safety::MakeMopEnvelope(
            prepare_id, "CONTROL_RELIABLE", "MISSION_PREPARE", "rc-flight-9",
            nullptr, nullptr, nullptr, std::move(prepare_payload), 4321LL);
        const std::string prepare_bytes = BytesEnvelope(prepare_env);
        link.Feed(reinterpret_cast<const std::uint8_t*>(prepare_bytes.data()),
                  prepare_bytes.size());
        CHECK(endpoint.flightIdConfirmed(), "confirmed after adoption");
        CHECK(endpoint.adoptions() == 1, "adoption counted");
        CHECK(endpoint.flight_id() == "rc-flight-9", "flight id adopted");
        CHECK(endpoint.edge_boot_id() ==
                  Uuid5(MopProtocol::Namespace(), "boot|rc-flight-9|0"),
              "boot rotated to the adopted flight");
        CHECK(endpoint.outboxSize() == 2, "prepared + ack queued");
        CHECK(endpoint.unackedCount() == 1,
              "only the new PREPARED response is unacked");
        const JsonValue& ack = endpoint.outbox().back();
        CHECK(ack.Find("type") != nullptr &&
                  ack.Find("type")->strValue() == "ACK",
              "last queued message is the ACK");
        CHECK(ack.Find("flight_id")->strValue() == "rc-flight-9",
              "ACK carries the adopted identity");

        // A second flight cycle against the same Edge process re-adopts and
        // resets the session scope (seqs never span flights).
        JsonValue bundle2 = MakeBundle();
        JsonValue second_payload = JsonValue::Obj();
        second_payload.Set("bundle", std::move(bundle2));
        const std::string second_id = Uuid5(
            MopProtocol::Namespace(), "rc|rc-flight-10|1|MISSION_PREPARE");
        JsonValue second_env = ksi::safety::MakeMopEnvelope(
            second_id, "CONTROL_RELIABLE", "MISSION_PREPARE", "rc-flight-10",
            nullptr, nullptr, nullptr, std::move(second_payload), 8765LL);
        const std::string second_bytes = BytesEnvelope(second_env);
        link.Feed(reinterpret_cast<const std::uint8_t*>(second_bytes.data()),
                  second_bytes.size());
        CHECK(endpoint.adoptions() == 2, "second flight re-adopts");
        CHECK(endpoint.flight_id() == "rc-flight-10", "new flight adopted");
        CHECK(endpoint.edge_boot_id() ==
                  Uuid5(MopProtocol::Namespace(), "boot|rc-flight-10|0"),
              "boot follows the new flight");
        CHECK(endpoint.seq("CONTROL_RELIABLE") == 1,
              "seq reset: only the new PREPARED response is numbered");
        CHECK(endpoint.outboxSize() == 2,
              "old-identity queue dropped; new response + ACK remain");
        CHECK(endpoint.unackedCount() == 1,
              "unacked reset to the new response only");

        // Re-delivery of the (already processed) prepare: dedup re-queues
        // the stored response, no new unacked entry.
        link.Feed(reinterpret_cast<const std::uint8_t*>(second_bytes.data()),
                  second_bytes.size());
        CHECK(endpoint.outboxSize() == 3,
              "re-delivered prepare dedups and re-queues its response");
        CHECK(endpoint.unackedCount() == 1,
              "dedup adds no unacked entry");
    }

    // --- identity guards: matching id never re-adopts; cross-flight
    // commands are terminal-ACKed without state change ----------------------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        const std::string start_id = Uuid5(
            MopProtocol::Namespace(), "rc|flt-e2e|9|SESSION_START");
        JsonValue start_env = ksi::safety::MakeMopEnvelope(
            start_id, "CONTROL_RELIABLE", "SESSION_START", kFlight, nullptr,
            nullptr, nullptr, JsonValue::Obj(), 111LL);
        const std::string start_bytes = BytesEnvelope(start_env);
        link.Feed(reinterpret_cast<const std::uint8_t*>(start_bytes.data()),
                  start_bytes.size());
        CHECK(endpoint.adoptions() == 0,
              "a matching flight id never re-adopts");
        CHECK(endpoint.running(), "session start processed normally");

        const std::string stop_id = Uuid5(
            MopProtocol::Namespace(), "rc|flt-other|1|SESSION_STOP");
        JsonValue stop_env = ksi::safety::MakeMopEnvelope(
            stop_id, "CONTROL_RELIABLE", "SESSION_STOP", "flt-other", nullptr,
            nullptr, nullptr, JsonValue::Obj(), 222LL);
        const std::string stop_bytes = BytesEnvelope(stop_env);
        const std::size_t errors_before = link.receive_errors();
        const std::size_t outbox_before = endpoint.outboxSize();
        link.Feed(reinterpret_cast<const std::uint8_t*>(stop_bytes.data()),
                  stop_bytes.size());
        CHECK(link.receive_errors() == errors_before + 1,
              "cross-flight command counted as an error");
        CHECK(endpoint.outboxSize() == outbox_before + 1,
              "cross-flight command is terminal-ACKed");
        CHECK(endpoint.running(),
              "cross-flight command did not change session state");
    }

    // --- cross-language fixtures -------------------------------------------
    std::string rc_to_edge;
    if (!ReadFixture("rc-to-edge.mopv1.hex", &rc_to_edge)) {
        CHECK(false,
              "GENERATE rc-to-edge.mopv1.hex: run the rc-msdk Kotlin test "
              "MopCrossLanguageTest (it prints the hex) and commit it");
        return edge_test::failures == 0 ? 0 : 1;
    }
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);

        link.Feed(reinterpret_cast<const std::uint8_t*>(rc_to_edge.data()),
                  rc_to_edge.size());
        EmitScenarioEvidence(&link, &endpoint);
        const std::size_t sent = link.Flush();
        CHECK(sent > 0, "scenario flush wrote envelopes");
        CHECK(endpoint.outboxSize() == 0, "scenario outbox drained");

        const std::string produced = transport.written;
        std::string fixture;
        if (!ReadFixture("edge-to-rc-current.mopv1.hex", &fixture,
                         KSI_MOP_TEST_FIXTURES_DIR)) {
            std::printf("produced (%zu bytes):\n%s\n", produced.size(), Hex(produced).c_str());
            CHECK(false, "current producer fixture missing");
        } else {
            CHECK(produced == fixture,
                  "edge-to-rc bytes match the pinned fixture");
            if (produced != fixture) {
                std::printf("produced len=%zu fixture len=%zu\n",
                            produced.size(), fixture.size());
            }
        }

        // The produced stream must still verify as a whole.
        MopReassembler checker;
        std::vector<std::string> messages;
        checker.Feed(reinterpret_cast<const std::uint8_t*>(produced.data()),
                     produced.size(), &messages);
        CHECK(checker.errors() == 0, "produced stream reassembles cleanly");
        std::size_t verified = 0;
        for (const auto& message : messages) {
            JsonValue envelope;
            std::string error;
            JsonValue payload;
            if (ParseJson(message, &envelope, &error) &&
                ksi::safety::VerifyMopEnvelope(envelope, &payload, &error)) {
                ++verified;
            }
        }
        CHECK(verified == messages.size(),
              "every produced envelope verifies (digest included)");

        // The historical contract fixture is a reader-compatibility pin, not
        // an assertion that today's producer repeats its obsolete bool.
        std::string historical;
        CHECK(ReadFixture("edge-to-rc.mopv1.hex", &historical), "historical fixture present");
        std::ifstream historical_file(std::string(KSI_MOP_CONTRACTS_DIR) + "/edge-to-rc.mopv1.hex", std::ios::binary);
        const std::string historical_text((std::istreambuf_iterator<char>(historical_file)), std::istreambuf_iterator<char>());
        CHECK(Sha256Hex(historical_text) == "963f19b614bf613244745035168b24e21ce298a0e30e535819e7c20a7e0a634a",
              "historical fixture file bytes remain frozen");
        CHECK(Sha256Hex(historical) == "ea0992f55c4d4ffa123cda3a3f6ad03130b07c3bb80f75684ebf4692a4ba5e3a",
              "historical decoded bytes remain frozen");
        MopReassembler old_checker;
        std::vector<std::string> old_messages;
        old_checker.Feed(reinterpret_cast<const std::uint8_t*>(historical.data()), historical.size(), &old_messages);
        CHECK(old_checker.errors() == 0 && old_messages.size() == messages.size(), "historical frames reassemble");
        std::size_t changed = 0;
        if (old_messages.size() == messages.size()) {
            for (std::size_t i = 0; i < messages.size(); ++i) {
                JsonValue old_env, new_env, old_payload, new_payload;
                std::string error;
                const bool valid = ParseJson(old_messages[i], &old_env, &error) &&
                    ksi::safety::VerifyMopEnvelope(old_env, &old_payload, &error) &&
                    ParseJson(messages[i], &new_env, &error) &&
                    ksi::safety::VerifyMopEnvelope(new_env, &new_payload, &error);
                CHECK(valid, "both historical and current envelopes verify");
                if (!valid || old_messages[i] == messages[i]) continue;
                ++changed;
                CHECK(old_env.Find("type") && old_env.Find("type")->strValue() == "MISSION_STATUS", "only status differs");
                const JsonValue* old_running = old_payload.Find("running");
                const JsonValue* new_running = new_payload.Find("running");
                CHECK(old_running && old_running->IsBool() && old_running->boolValue() &&
                      new_running && new_running->IsBool() && !new_running->boolValue(), "only old status says running");
                old_payload.Set("running", JsonValue::Bool(false));
                old_env.Set("payload", old_payload);
                old_env.Set("payload_sha256", *new_env.Find("payload_sha256"));
                CHECK(ksi::safety::WriteCanonicalJson(old_env) == ksi::safety::WriteCanonicalJson(new_env),
                      "status differs only in running and its digest");
            }
        }
        CHECK(changed == 1, "exactly one envelope differs");
    }

    // Artifact chunking sanity for a multi-chunk artifact (not in fixture).
    {
        const std::string big(1200, 'x');
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        endpoint.EmitArtifact("art-big", "EDGE_SNAPSHOT", "image/jpeg", 1, true,
                              big);
        // announce + ceil(1200/512)=3 chunks
        CHECK(endpoint.outboxSize() == 4, "announce + 3 chunks for 1200 bytes");
        const JsonValue& announce = endpoint.outbox().front();
        const JsonValue* payload = announce.Find("payload");
        CHECK(payload != nullptr &&
                  payload->Find("sha256") != nullptr &&
                  payload->Find("sha256")->strValue() == Sha256Hex(big),
              "announce sha256 matches the real bytes");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
