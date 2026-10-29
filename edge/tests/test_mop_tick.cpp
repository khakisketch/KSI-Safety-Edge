// Tick evidence mapping (A9-2b/C62): the REAL TickRecord -> OBSERVATION /
// DETECTION_EVENT / FLIGHT_SUMMARY payload mapping that the PSDK app uses.
// Pins: sync/geometry grade mapping, COCO-native class labels + OBSERVED
// track association (exact-box only, never guessed), CREATED-only events,
// skip/failure ticks producing NO observation, summary aggregates, and
// deterministic ids (same tick -> identical canonical bytes).
#include <cstdint>
#include <string>
#include <vector>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "ksi_safety/transport/mop_frame.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/mop_tick_evidence.hpp"
#include "support/check.hpp"

using ksi::safety::Detection;
using ksi::safety::DetectionStatus;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::EvidenceContextFromBundle;
using ksi::safety::EvidenceSummary;
using ksi::safety::JsonValue;
using ksi::safety::MopByteTransport;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopEvidenceContext;
using ksi::safety::MopFrameCodec;
using ksi::safety::MopLink;
using ksi::safety::MopProtocol;
using ksi::safety::MopReassembler;
using ksi::safety::MopTickEvidence;
using ksi::safety::ParseJson;
using ksi::safety::PoseQuality;
using ksi::safety::RuleOutput;
using ksi::safety::SpatialTickReason;
using ksi::safety::TickRecord;
using ksi::safety::Track;
using ksi::safety::TrackProvenance;
using ksi::safety::TrackRecord;
using ksi::safety::Uuid5;
using ksi::safety::WriteCanonicalJson;

namespace {

struct FakeTransport : MopByteTransport {
    std::string written;
    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        written.append(reinterpret_cast<const char*>(data), len);
        return true;
    }
};

struct StepClock {
    std::int64_t now = 1000000000LL;
    std::int64_t operator()() {
        const std::int64_t value = now;
        now += 1000000LL;
        return value;
    }
};

const std::string kFlight = "rc-flt-1";

std::string BootId() {
    return Uuid5(MopProtocol::Namespace(), "boot|" + kFlight + "|0");
}

MopEvidenceContext MakeContext() {
    MopEvidenceContext context;
    context.flight_id = kFlight;
    context.edge_boot_id = BootId();
    context.mission_revision_id = "mrev-1";
    context.mission_snapshot_hash = std::string(64, 'b');
    context.policy_revision_id = "prev-1";
    return context;
}

TickRecord MakeDetectionTick(std::uint64_t index, std::int64_t recv_ns) {
    TickRecord record;
    record.tick_index = index;
    record.frame_id = static_cast<std::uint32_t>(index);
    record.receive_mono_ns = recv_ns;
    record.model_id = "dfine-s-coco";
    record.model_digest = "sha256:" + std::string(64, 'a');
    record.exposure_verified = false;
    record.detection_status = DetectionStatus::kOk;
    record.attitude_quality = PoseQuality::kReceiveNearest;
    record.attitude_gap_ns = 4774438;  // 4.774438 ms
    record.position_quality = PoseQuality::kReceiveNearest;
    record.position_gap_ns = 4800000;  // 4.8 ms
    record.spatial_status = SpatialTickReason::kDisabled;

    Detection person;
    person.x_min = 10.0F;
    person.y_min = 20.0F;
    person.x_max = 30.5F;
    person.y_max = 40.0F;
    person.score = 0.9F;
    person.class_id = 0;
    record.detections.push_back(person);

    Detection truck;
    truck.x_min = 100.0F;
    truck.y_min = 100.0F;
    truck.x_max = 150.0F;
    truck.y_max = 160.0F;
    truck.score = 0.8F;
    truck.class_id = 7;
    record.detections.push_back(truck);

    // The person detection is tracked (measured this tick); the truck is not.
    Track observed;
    observed.track_id = 7;
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
    observed_record.since_observed_ns = 0;
    record.tracks.push_back(observed_record);

    // One proximity episode CREATED (+ an UPDATED that must NOT emit).
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
    updated.kind = EpisodeEventKind::kUpdated;
    proximity.events.push_back(updated);
    record.proximity.push_back(proximity);
    return record;
}

JsonValue MustParse(const std::string& text) {
    JsonValue value;
    std::string error;
    if (!ParseJson(text, &value, &error)) {
        CHECK(false, "fixture JSON must parse");
    }
    return value;
}

const JsonValue* Field(const JsonValue& object, const char* key) {
    const JsonValue* value = object.Find(key);
    if (value == nullptr) {
        std::fprintf(stderr, "missing field: %s\n", key);
        CHECK(false, "field must exist");
        static const JsonValue kMissing = JsonValue::Null();
        return &kMissing;
    }
    return value;
}

}  // namespace

int main() {
    // --- bundle -> evidence context ----------------------------------------
    {
        MopEvidenceContext context;
        std::string error;
        const JsonValue bundle = MustParse(
            R"({"mission_snapshot":{"mission_revision_id":"mrev-1","content_sha256":"bbbb"},"risk_policy":{"revision_id":"prev-1"}})");
        CHECK(EvidenceContextFromBundle(bundle, &context, &error),
              "bundle context extracts");
        CHECK(context.mission_revision_id == "mrev-1", "revision extracted");
        CHECK(context.mission_snapshot_hash == "bbbb", "snapshot hash extracted");
        CHECK(context.policy_revision_id == "prev-1", "policy revision extracted");
        const JsonValue bad = MustParse(R"({"mission_snapshot":{}})");
        CHECK(!EvidenceContextFromBundle(bad, &context, &error),
              "missing revisions rejected");
    }

    // --- detection tick: observation mapping --------------------------------
    MopTickEvidence emitter(MakeContext());
    const TickRecord tick1 = MakeDetectionTick(1, 1050000000LL);
    const MopTickEvidence::TickPayloads payloads = emitter.BuildTick(tick1);
    CHECK(payloads.has_observation, "detection tick produces an observation");
    {
        const JsonValue& observation = payloads.observation;
        CHECK(Field(observation, "sync_quality")->strValue() == "DEGRADED",
              "receive-nearest grades -> DEGRADED");
        CHECK(Field(observation, "geometry_status")->strValue() == "WITHHELD",
              "no spatial estimate -> WITHHELD");
        CHECK(Field(observation, "flight_id")->strValue() == kFlight,
              "flight id stamped");
        CHECK(Field(observation, "mission_revision_id")->strValue() == "mrev-1",
              "mission revision stamped");
        CHECK(Field(observation, "model_digest")->strValue() ==
                  std::string(64, 'a'),
              "sha256: prefix stripped");
        CHECK(Field(observation, "edge_frame_seq")->intValue() == 1,
              "frame seq = tick index");
        CHECK(Field(observation, "observation_id")->strValue() ==
                  Uuid5(MopProtocol::Namespace(),
                        "obs|" + kFlight + "|" + BootId() + "|1"),
              "deterministic observation id");
        const JsonValue* detections = Field(observation, "detections");
        CHECK(detections->Size() == 2, "two detections");
        const JsonValue& person = detections->arrayValue()[0];
        CHECK(Field(person, "class_id")->strValue() == "person",
              "COCO-native label for class 0");
        CHECK(Field(person, "track_id")->strValue() == "trk-7",
              "observed track association");
        const JsonValue& truck = detections->arrayValue()[1];
        CHECK(Field(truck, "class_id")->strValue() == "truck",
              "COCO-native label for class 7");
        CHECK(Field(truck, "track_id")->IsNull(),
              "unmatched detection stays null (never guessed)");
        const JsonValue* telemetry = Field(observation, "telemetry_refs");
        CHECK(telemetry->Size() == 2, "attitude + position refs");
        CHECK(telemetry->arrayValue()[0].Find("sample_age_ms")->doubleValue() ==
                  4.774438,
              "gap ns -> ms");
        const JsonValue* reasons = Field(observation, "quality_reasons");
        bool has_capture = false;
        bool has_sync = false;
        for (const auto& reason : reasons->arrayValue()) {
            if (reason.strValue() == "UNVERIFIED_FRAME_CAPTURE_TIME") {
                has_capture = true;
            }
            if (reason.strValue() == "TELEMETRY_SYNC_DEGRADED") has_sync = true;
        }
        CHECK(has_capture, "capture-time honesty reason present");
        CHECK(has_sync, "sync quality reason present");
    }
    // Determinism: a fresh emitter rebuilds identical canonical bytes.
    {
        MopTickEvidence fresh(MakeContext());
        const auto rebuilt = fresh.BuildTick(MakeDetectionTick(1, 1050000000LL));
        CHECK(WriteCanonicalJson(rebuilt.observation) ==
                  WriteCanonicalJson(payloads.observation),
              "same tick -> identical observation bytes");
    }

    // --- events: CREATED only ----------------------------------------------
    {
        CHECK(payloads.events.size() == 1,
              "one CREATED event (UPDATED filtered)");
        const JsonValue& event = payloads.events[0];
        CHECK(Field(event, "event_type")->strValue() == "PROXIMITY_CANDIDATE",
              "proximity candidate type");
        CHECK(Field(event, "mode")->strValue() == "SHADOW", "shadow mode");
        CHECK(Field(*Field(event, "initial_model_output"), "distance_m")
                  ->doubleValue() == 2.57,
              "interval lower bound as distance");
        CHECK(Field(event, "policy_revision_id")->strValue() == "prev-1",
              "policy revision stamped");
        CHECK(Field(event, "observation_id")->strValue() ==
                  Field(payloads.observation, "observation_id")->strValue(),
              "event links its observation");
        CHECK(Field(event, "mission_snapshot_hash")->strValue() ==
                  std::string(64, 'b'),
              "snapshot hash stamped");
    }

    // --- cadence skip: NO observation, summary still counts it --------------
    {
        TickRecord skip = MakeDetectionTick(2, 1083333333LL);
        skip.detection_status = DetectionStatus::kNotRun;
        skip.detections.clear();
        // Realistic skip: the runner records NO rule outputs (no observation
        // evidence reached the engine); held tracks only.
        skip.proximity.clear();
        skip.zone.clear();
        const auto skip_payloads = emitter.BuildTick(skip);
        CHECK(!skip_payloads.has_observation,
              "kNotRun never becomes an empty (negative) observation");
        CHECK(skip_payloads.events.empty(), "no events on a skip");
    }

    // --- summary aggregates -------------------------------------------------
    {
        const std::string session =
            MopTickEvidence::SessionId(kFlight, BootId());
        JsonValue summary;
        CHECK(emitter.BuildSummary(session, &summary), "summary built");
        CHECK(Field(summary, "covered_frames")->intValue() == 1,
              "one covered frame");
        CHECK(Field(summary, "skipped_frames")->intValue() == 1,
              "one skipped frame");
        CHECK(Field(summary, "ai_uptime_ratio")->doubleValue() == 0.5,
              "uptime ratio 1/2");
        CHECK(Field(summary, "person_count_max")->intValue() == 1,
              "person max");
        CHECK(Field(summary, "equipment_count_max")->intValue() == 1,
              "equipment max (car/truck)");
        CHECK(Field(summary, "session_id")->strValue() == session,
              "session id = RC derivation");
        CHECK(Field(summary, "summary_id")->strValue() ==
                  Uuid5(MopProtocol::Namespace(),
                        "summary|" + kFlight + "|" + BootId()),
              "deterministic summary id");
    }

    // --- failure tick: no observation, counted as skipped -------------------
    {
        MopTickEvidence failure_emitter(MakeContext());
        TickRecord failure = MakeDetectionTick(1, 2000000000LL);
        failure.detection_status = DetectionStatus::kInferenceFailure;
        failure.detections.clear();
        failure.proximity.clear();
        failure.zone.clear();
        const auto failure_payloads = failure_emitter.BuildTick(failure);
        CHECK(!failure_payloads.has_observation,
              "kInferenceFailure never becomes an observation");
        CHECK(failure_payloads.events.empty(),
              "no events on a failure tick");
        JsonValue summary;
        CHECK(failure_emitter.BuildSummary("session-x", &summary),
              "failure-only session still summarizes");
        CHECK(Field(summary, "covered_frames")->intValue() == 0,
              "failure covers no frames");
        CHECK(Field(summary, "skipped_frames")->intValue() == 1,
              "failure counted as skipped");
    }

    // --- durable gap: a summary over unjournalled records is never clean --
    {
        MopTickEvidence gap_emitter(MakeContext());
        gap_emitter.BuildTick(MakeDetectionTick(1, 3000000000LL));
        CHECK(!gap_emitter.durableGap(), "no gap by default");
        gap_emitter.setDurableGap(true);
        CHECK(gap_emitter.durableGap(), "gap latches");
        JsonValue summary;
        CHECK(gap_emitter.BuildSummary("session-gap", &summary),
              "gapped session still summarizes (honestly)");
        const JsonValue* reasons = Field(summary, "quality_reasons");
        CHECK(reasons != nullptr && reasons->IsArray(),
              "quality reasons present");
        bool flagged = false;
        for (const auto& reason : reasons->arrayValue()) {
            if (reason.IsString() &&
                reason.strValue() == "DURABLE_EVIDENCE_GAP") {
                flagged = true;
            }
        }
        CHECK(flagged, "summary carries DURABLE_EVIDENCE_GAP, never clean");
        CHECK(Field(summary, "covered_frames")->intValue() == 1,
              "covered counts only journalled ticks");
    }

    // --- full link round trip: emit -> frames -> reassemble -> verify -------
    {
        StepClock clock;
        MopEdgeEndpoint endpoint(kFlight, [&clock] { return clock(); });
        FakeTransport transport;
        MopLink link(&transport, &endpoint);
        MopTickEvidence outbound(MakeContext());
        outbound.EmitTick(&link, MakeDetectionTick(1, 1050000000LL));
        link.EmitHeartbeat(15.0);
        CHECK(link.Flush() == 3, "observation + event + heartbeat flushed");

        MopReassembler checker;
        std::vector<std::string> messages;
        checker.Feed(reinterpret_cast<const std::uint8_t*>(transport.written.data()),
                     transport.written.size(), &messages);
        CHECK(messages.size() == 3, "obs + event + heartbeat reassemble");
        std::size_t verified = 0;
        for (const auto& message : messages) {
            JsonValue envelope;
            std::string error;
            JsonValue payload_out;
            if (ParseJson(message, &envelope, &error) &&
                ksi::safety::VerifyMopEnvelope(envelope, &payload_out, &error)) {
                ++verified;
            }
        }
        CHECK(verified == messages.size(), "every message verifies (digests)");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
