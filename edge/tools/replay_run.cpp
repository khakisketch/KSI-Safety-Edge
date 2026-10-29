// replay_run: the R1 Replay driver — loads a replay manifest, feeds frames
// and telemetry through the SAME SessionRunner the PSDK application uses
// (only the input source differs), and writes one NDJSON record per frame
// tick plus a final stat line.
//
// Real D-FINE path: build with TensorRT ON and pass --engine PATH. Without
// TRT only --null-detector (plumbing; every tick stays NOT_RUN) works.
//
// usage: replay_run --manifest PATH [--out PATH] [--null-detector]
//        [--engine PATH] [--model-id ID] [--model-digest DIGEST]
//        [--detect-every N] [--max-receive-gap-ms N] [--max-bracket-ms N]
//        [--tracker-iou F] [--tracker-max-age N] [--tracker-min-hits N]
//        [--session-gap-ms N] [--zone-envelope-m F]
//        [--flight-id ID] [--boot-id ID] [--stream-id ID]
//
// Thresholds are CLI scenario inputs (the tool states them; product logic
// hardcodes none). Exit codes: 0 ok, 1 runtime failure, 2 usage.

#include "ksi_safety/perception/dfine_detector_port.hpp"
#include "ksi_safety/evidence/recorder.hpp"
#include "ksi_safety/observation/autonomous_processor.hpp"
#include "ksi_safety/replay/frame_source.hpp"
#include "ksi_safety/replay/policy_file.hpp"
#include "ksi_safety/replay/telemetry_source.hpp"
#include "ksi_safety/runtime/session_runner.hpp"
#include "ksi_safety/transport/digest.hpp"

// The header is stub-compatible without TensorRT (Available()==false).
#include "ksi_safety/perception/tensorrt_runtime.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {

using ksi::safety::ClockRegistry;
using ksi::safety::DetectionStatus;
using ksi::safety::DetectorPort;
using ksi::safety::DfineConfig;
using ksi::safety::DfineDetectorPort;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::FrameContext;
using ksi::safety::FramePacket;
using ksi::safety::ModelProfile;
using ksi::safety::ReplayFrameSource;
using ksi::safety::ReplayTelemetrySource;
using ksi::safety::RiskRule;
using ksi::safety::RuleOutput;
using ksi::safety::RunnerConfig;
using ksi::safety::SessionConfig;
using ksi::safety::SessionRunner;
using ksi::safety::TickRecord;
using ksi::safety::TimeSourceKind;
using ksi::safety::ToString;
using ksi::safety::TensorRtRuntime;
using ksi::safety::TrackRecord;
using ksi::safety::ZoneRevision;

constexpr std::int64_t kNsPerMs = 1000000LL;

struct Args {
    std::string manifest;
    std::string out_path = "replay_run.ndjson";
    std::string engine_path;
    std::string policy_path;
    std::string model_id = "dfine-s-coco";
    std::string model_digest;
    std::string flight_id = "replay-flight";
    std::string boot_id = "replay-boot";
    std::string stream_id = "replay-stream";
    long long detect_every = 1;
    long long max_receive_gap_ms = 50;
    long long max_bracket_ms = 20;
    double score_threshold = 0.05;  // DfineConfig default (policy input)
    double tracker_iou = 0.3;
    long long tracker_max_age = 5;
    long long tracker_min_hits = 3;
    long long session_gap_ms = 5000;
    double zone_envelope_m = 1.0;
    bool null_detector = false;
    bool stage_times = false;
    // Source-evidence directory: when set, the driver keeps the source frame
    // of every MATERIAL observation and seals one immutable revision at the
    // end. Same capture code as the device app.
    std::string evidence_dir;
    // Synthetic detector: reports one detection per frame so the EVIDENCE
    // CHAIN can be exercised without an engine or real imagery. Never a
    // detection-performance claim.
    bool stub_detector = false;
    // Blank (clean negative, kOk + zero detections) every Nth stub frame
    // (0 = never blank). Lets one host run mix trigger and blank frames.
    long long stub_blank_every = 0;
    // Unit A passive autonomy (opt-in, off by default): the SAME shared
    // wiring the device app uses (ProcessSharedTick), sealing machine
    // assessments + pre/post source windows as versioned artifacts.
    // [SIMULATED] when combined with --stub-detector or --null-detector.
    std::string autonomy_evidence_dir;
    ksi::safety::SourceAttentionPolicy source_attention;
    long long autonomy_pre_frames = 0;
    long long autonomy_post_frames = 1;
    long long autonomy_window_deadline_ms = 500;
    long long autonomy_window_min_interval_ms = 1000;
    long long autonomy_capacity_bytes = 8LL * 1024 * 1024;
    std::string autonomy_lens_id = "wide";
    bool camera_manual = false;
    std::string mission_revision = "replay-mis";
    std::string policy_revision = "replay-pol";
};

void PrintUsage() {
    std::fprintf(stderr,
                 "usage: replay_run --manifest PATH [--out PATH] "
                 "[--null-detector] [--stub-detector] "
                 "[--stub-blank-every N]\n"
                 "       [--evidence-dir DIR]\n"
                 "       [--engine PATH] [--model-id ID] [--model-digest D]\n"
                 "       [--policy PATH] [--score-threshold F] [--stage-times]\n"
                 "       [--detect-every N] [--max-receive-gap-ms N] "
                 "[--max-bracket-ms N]\n"
                 "       [--tracker-iou F] [--tracker-max-age N] "
                 "[--tracker-min-hits N]\n"
                 "       [--session-gap-ms N] [--zone-envelope-m F]\n"
                 "       [--flight-id ID] [--boot-id ID] [--stream-id ID]\n"
                 "       [--autonomy-evidence-dir DIR] [--autonomy-pre N] "
                 "       [--autonomy-post N]\n"
                 "       [--autonomy-window-deadline-ms N] "
                 "       [--autonomy-window-min-interval-ms N]\n"
                 "       [--autonomy-capacity-bytes N]\n"
                  "       [--source-attention-policy KEY=VALUE,...]\n"
                  "       [--autonomy-lens-id ID] [--camera-manual] "
                 "       [--mission-revision ID] [--policy-revision ID]\n");
}

bool ParseLongLong(const char* text, long long* out) {
    char* end = nullptr;
    const long long value = std::strtoll(text, &end, 10);
    if (end == nullptr || *end != '\0' || end == text) {
        return false;
    }
    *out = value;
    return true;
}

bool ParseDoubleArg(const char* text, double* out) {
    char* end = nullptr;
    const double value = std::strtod(text, &end);
    if (end == nullptr || *end != '\0' || end == text ||
        !std::isfinite(value)) {
        return false;
    }
    *out = value;
    return true;
}

bool ParseArgs(int argc, char** argv, Args* args) {
    for (int i = 1; i < argc; ++i) {
        const std::string key = argv[i];
        const bool has_value = i + 1 < argc;
        if (key == "--manifest" && has_value) {
            args->manifest = argv[++i];
        } else if (key == "--out" && has_value) {
            args->out_path = argv[++i];
        } else if (key == "--engine" && has_value) {
            args->engine_path = argv[++i];
        } else if (key == "--policy" && has_value) {
            args->policy_path = argv[++i];
        } else if (key == "--model-id" && has_value) {
            args->model_id = argv[++i];
        } else if (key == "--model-digest" && has_value) {
            args->model_digest = argv[++i];
        } else if (key == "--flight-id" && has_value) {
            args->flight_id = argv[++i];
        } else if (key == "--boot-id" && has_value) {
            args->boot_id = argv[++i];
        } else if (key == "--stream-id" && has_value) {
            args->stream_id = argv[++i];
        } else if (key == "--detect-every" && has_value) {
            if (!ParseLongLong(argv[++i], &args->detect_every)) {
                return false;
            }
        } else if (key == "--max-receive-gap-ms" && has_value) {
            if (!ParseLongLong(argv[++i], &args->max_receive_gap_ms)) {
                return false;
            }
        } else if (key == "--max-bracket-ms" && has_value) {
            if (!ParseLongLong(argv[++i], &args->max_bracket_ms)) {
                return false;
            }
        } else if (key == "--score-threshold" && has_value) {
            if (!ParseDoubleArg(argv[++i], &args->score_threshold)) {
                return false;
            }
        } else if (key == "--tracker-iou" && has_value) {
            if (!ParseDoubleArg(argv[++i], &args->tracker_iou)) {
                return false;
            }
        } else if (key == "--tracker-max-age" && has_value) {
            if (!ParseLongLong(argv[++i], &args->tracker_max_age)) {
                return false;
            }
        } else if (key == "--tracker-min-hits" && has_value) {
            if (!ParseLongLong(argv[++i], &args->tracker_min_hits)) {
                return false;
            }
        } else if (key == "--session-gap-ms" && has_value) {
            if (!ParseLongLong(argv[++i], &args->session_gap_ms)) {
                return false;
            }
        } else if (key == "--zone-envelope-m" && has_value) {
            if (!ParseDoubleArg(argv[++i], &args->zone_envelope_m)) {
                return false;
            }
        } else if (key == "--null-detector") {
            args->null_detector = true;
        } else if (key == "--stage-times") {
            args->stage_times = true;
        } else if (key == "--stub-detector") {
            args->stub_detector = true;
        } else if (key == "--stub-blank-every" && has_value) {
            if (!ParseLongLong(argv[++i], &args->stub_blank_every)) {
                return false;
            }
        } else if (key == "--evidence-dir" && has_value) {
            args->evidence_dir = argv[++i];
        } else if (key == "--source-attention-policy" && has_value) {
            std::string error;
            if (!ksi::safety::ParseSourceAttentionPolicy(argv[++i], &args->source_attention, &error)) {
                std::fprintf(stderr, "%s\n", error.c_str());
                return false;
            }
        } else if (key == "--autonomy-evidence-dir" && has_value) {
            args->autonomy_evidence_dir = argv[++i];
        } else if (key == "--autonomy-pre" && has_value) {
            if (!ParseLongLong(argv[++i], &args->autonomy_pre_frames)) {
                return false;
            }
        } else if (key == "--autonomy-post" && has_value) {
            if (!ParseLongLong(argv[++i], &args->autonomy_post_frames)) {
                return false;
            }
        } else if (key == "--autonomy-window-deadline-ms" && has_value) {
            if (!ParseLongLong(argv[++i],
                               &args->autonomy_window_deadline_ms)) {
                return false;
            }
        } else if (key == "--autonomy-window-min-interval-ms" && has_value) {
            if (!ParseLongLong(argv[++i],
                               &args->autonomy_window_min_interval_ms) ||
                args->autonomy_window_min_interval_ms < 0 ||
                args->autonomy_window_min_interval_ms > 3600000) {
                std::fprintf(stderr,
                             "--autonomy-window-min-interval-ms must be "
                             "0..3600000 (0 = no interval bound)\n");
                return false;
            }
        } else if (key == "--autonomy-capacity-bytes" && has_value) {
            if (!ParseLongLong(argv[++i], &args->autonomy_capacity_bytes)) {
                return false;
            }
        } else if (key == "--autonomy-lens-id" && has_value) {
            args->autonomy_lens_id = argv[++i];
        } else if (key == "--camera-manual") {
            args->camera_manual = true;
        } else if (key == "--mission-revision" && has_value) {
            args->mission_revision = argv[++i];
        } else if (key == "--policy-revision" && has_value) {
            args->policy_revision = argv[++i];
        } else {
            std::fprintf(stderr, "unknown or incomplete argument: %s\n",
                         key.c_str());
            return false;
        }
    }
    if (args->manifest.empty()) {
        return false;
    }
    if (args->detect_every < 1) {
        std::fprintf(stderr, "--detect-every must be >= 1\n");
        return false;
    }
    if (args->score_threshold < 0.0 || args->score_threshold > 1.0) {
        std::fprintf(stderr, "--score-threshold must be in [0, 1]\n");
        return false;
    }
    return true;
}

void WriteOutputs(std::FILE* out, const std::vector<RuleOutput>& outputs) {
    std::fputc('[', out);
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        const RuleOutput& rule_output = outputs[i];
        if (i != 0U) {
            std::fputc(',', out);
        }
        std::fprintf(out,
                     "{\"rule\":\"%s\",\"status\":\"%s\",\"detail\":\"%s\","
                     "\"mode\":\"%s\",\"shadow\":%s,\"events\":[",
                     rule_output.rule_id.c_str(),
                     ToString(rule_output.status),
                     ToString(rule_output.detail),
                     ToString(rule_output.effective_mode),
                     rule_output.is_shadow ? "true" : "false");
        for (std::size_t j = 0; j < rule_output.events.size(); ++j) {
            const EpisodeEvent& event = rule_output.events[j];
            if (j != 0U) {
                std::fputc(',', out);
            }
            std::fprintf(out,
                         "{\"kind\":\"%s\",\"episode\":%llu,"
                         "\"reason\":\"%s\",\"recv_ns\":%llu,"
                         "\"lower_m\":%g,\"upper_m\":%g}",
                         ToString(event.kind),
                         static_cast<unsigned long long>(event.episode_id),
                         event.has_close_reason ? ToString(event.close_reason)
                                               : "-",
                         static_cast<unsigned long long>(
                             event.received_mono_ns),
                         event.evidence.lower_m, event.evidence.upper_m);
        }
        std::fputs("]}", out);
    }
    std::fputc(']', out);
}

void WriteTick(std::FILE* out, const TickRecord& record) {
    std::fprintf(
        out,
        "{\"type\":\"tick\",\"i\":%llu,\"frame_id\":%u,\"stream\":\"%s\","
        "\"recv_mono_ns\":%lld,\"model\":\"%s\",\"model_digest\":\"%s\","
        "\"exposure_verified\":%s,"
        "\"detect\":\"%s\",\"dets\":%zu,"
        "\"att\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
        "\"pos\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
        "\"spatial_eligible\":%s,",
        static_cast<unsigned long long>(record.tick_index), record.frame_id,
        record.stream_id.c_str(),
        static_cast<long long>(record.receive_mono_ns),
        record.model_id.c_str(), record.model_digest.c_str(),
        record.exposure_verified ? "true" : "false",
        ToString(record.detection_status), record.detections.size(),
        ToString(record.attitude_quality), ToString(record.attitude_reason),
        static_cast<long long>(record.attitude_gap_ns),
        ToString(record.position_quality), ToString(record.position_reason),
        static_cast<long long>(record.position_gap_ns),
        record.spatial_eligible ? "true" : "false");
    std::fputs("\"det\":[", out);
    for (std::size_t i = 0; i < record.detections.size(); ++i) {
        const auto& det = record.detections[i];
        if (i != 0U) {
            std::fputc(',', out);
        }
        std::fprintf(out,
                     "{\"c\":%u,\"s\":%.6f,\"box\":[%.3f,%.3f,%.3f,%.3f]}",
                     det.class_id, static_cast<double>(det.score),
                     static_cast<double>(det.x_min),
                     static_cast<double>(det.y_min),
                     static_cast<double>(det.x_max),
                     static_cast<double>(det.y_max));
    }
    std::fputs("],\"tracks\":[", out);
    for (std::size_t i = 0; i < record.tracks.size(); ++i) {
        const TrackRecord& track_record = record.tracks[i];
        const auto& track = track_record.track;
        if (i != 0U) {
            std::fputc(',', out);
        }
        std::fprintf(
            out,
            "{\"id\":%llu,\"epoch\":%llu,\"class\":%u,\"prov\":\"%s\","
            "\"since_ns\":%lld,\"hits\":%d,\"age\":%d,\"confirmed\":%s,"
            "\"last_seen_ns\":%llu,\"box\":[%.3f,%.3f,%.3f,%.3f]}",
            static_cast<unsigned long long>(track.track_id),
            static_cast<unsigned long long>(track.epoch), track.class_id,
            ToString(track_record.provenance),
            static_cast<long long>(track_record.since_observed_ns),
            track.hits, track.age, track.confirmed ? "true" : "false",
            static_cast<unsigned long long>(track.last_seen_ns),
            static_cast<double>(track.box.x_min),
            static_cast<double>(track.box.y_min),
            static_cast<double>(track.box.x_max),
            static_cast<double>(track.box.y_max));
    }
    std::fputs("],\"spatial_status\":\"", out);
    std::fputs(ToString(record.spatial_status), out);
    std::fputs("\",\"spatial\":[", out);
    for (std::size_t i = 0; i < record.spatial.size(); ++i) {
        const ksi::safety::SpatialObservationRecord& spatial =
            record.spatial[i];
        if (i != 0U) {
            std::fputc(',', out);
        }
        std::fprintf(out, "{\"track\":%llu,\"class\":%u,\"ok\":%s,\"reason\":\"%s\"",
                     static_cast<unsigned long long>(spatial.track_id),
                     spatial.class_id, spatial.has_observation ? "true" : "false",
                     ToString(spatial.reason));
        if (spatial.has_observation) {
            if (spatial.observation.kind == ksi::safety::GroundTrackKind::kPerson) {
                std::fprintf(out, ",\"foot\":[%g,%g]",
                             spatial.observation.person_footpoint.x,
                             spatial.observation.person_footpoint.y);
            } else {
                std::fputs(",\"poly\":[", out);
                for (std::size_t p = 0;
                     p < spatial.observation.equipment_footprint.size(); ++p) {
                    if (p != 0U) {
                        std::fputc(',', out);
                    }
                    std::fprintf(out, "[%g,%g]",
                                 spatial.observation.equipment_footprint[p].x,
                                 spatial.observation.equipment_footprint[p].y);
                }
                std::fprintf(out, "],\"name\":\"%s\"",
                             spatial.observation.equipment_class.c_str());
            }
        }
        std::fputc('}', out);
    }
    std::fputs("],\"prox\":", out);
    WriteOutputs(out, record.proximity);
    std::fputs(",\"zone\":", out);
    WriteOutputs(out, record.zone);
    std::fputs("}\n", out);
}

double Percentile(std::vector<double> values, double fraction) {
    if (values.empty()) {
        return 0.0;
    }
    std::sort(values.begin(), values.end());
    std::size_t index = static_cast<std::size_t>(
        std::ceil(fraction * static_cast<double>(values.size())));
    if (index == 0U) {
        index = 1U;
    }
    if (index > values.size()) {
        index = values.size();
    }
    return values[index - 1U];
}

struct Stats {
    std::size_t frames = 0;
    std::size_t ok_ticks = 0;
    std::size_t failure_ticks = 0;
    std::size_t notrun_ticks = 0;
    std::size_t detections_total = 0;
    std::size_t observations_total = 0;
    std::size_t spatial_estimated_ticks = 0;
    std::size_t spatial_noteligible_ticks = 0;
    std::size_t episodes_created = 0;
    std::size_t episodes_closed = 0;
    std::size_t poses_pushed = 0;
    std::size_t positions_pushed = 0;
    std::size_t duplicates = 0;
    std::size_t rollbacks = 0;
    std::vector<double> tick_ms;
};

void CountEvents(const std::vector<RuleOutput>& outputs, Stats* stats) {
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        for (std::size_t j = 0; j < outputs[i].events.size(); ++j) {
            if (outputs[i].events[j].kind == EpisodeEventKind::kCreated) {
                ++stats->episodes_created;
            } else if (outputs[i].events[j].kind ==
                       EpisodeEventKind::kClosed) {
                ++stats->episodes_closed;
            }
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    Args args;
    if (!ParseArgs(argc, argv, &args)) {
        PrintUsage();
        return 2;
    }
    if (args.source_attention.enabled &&
        (args.autonomy_evidence_dir.empty() || args.autonomy_capacity_bytes <= 0)) {
        std::fprintf(stderr, "source attention requires non-empty --autonomy-evidence-dir and finite positive capacity\n");
        return 2;
    }

    ClockRegistry clocks;
    clocks.Verify("host_mono", TimeSourceKind::kHostReceive,
                  "replay harness clock");

    ReplayFrameSource frames(args.manifest, args.flight_id, args.boot_id,
                             args.stream_id, &clocks);
    if (!frames.ok()) {
        std::fprintf(stderr, "manifest error: %s\n", frames.error().c_str());
        return 1;
    }
    if (frames.frame_count() == 0U) {
        std::fprintf(stderr, "manifest has no frames: %s\n",
                     args.manifest.c_str());
        return 1;
    }
    ksi::safety::ApplyManifestVerifications(frames.manifest(), &clocks);

    DetectorPort* detector = nullptr;
    std::unique_ptr<DfineDetectorPort> dfine_port;
    // Synthetic detector: one detection per frame, so the evidence chain
    // (frame -> observation -> source asset -> revision -> collection ->
    // receipt) can be exercised with no engine and no real imagery.
    // [SIMULATED] — never a detection-performance claim.
    class StubDetector : public DetectorPort {
     public:
      explicit StubDetector(long long blank_every)
          : blank_every_(blank_every) {}
      ksi::safety::DetectionBatch Detect(const ksi::safety::FramePacket& frame) override {
        ksi::safety::DetectionBatch batch;
        batch.status = ksi::safety::DetectionStatus::kOk;
        ++frames_;
        // Clean negatives mixed into the synthetic stream: kOk with zero
        // detections (evidence), never a gap.
        if (blank_every_ > 0 && frames_ % blank_every_ == 0) return batch;
        ksi::safety::Detection detection;
        detection.class_id = 0;  // person
        detection.score = 0.99f;
        detection.x_min = 0.25F * frame.metadata.width;
        detection.y_min = 0.25F * frame.metadata.height;
        detection.x_max = 0.75F * frame.metadata.width;
        detection.y_max = 0.75F * frame.metadata.height;
        batch.detections.push_back(detection);
        return batch;
      }
     private:
      long long blank_every_ = 0;
      long long frames_ = 0;
    };
    std::unique_ptr<StubDetector> stub_port;
    if (args.stub_detector) {
        std::fprintf(stderr,
                     "[SIMULATED] stub detector: detections are synthetic — "
                     "not a detection-performance claim\n");
        stub_port = std::make_unique<StubDetector>(args.stub_blank_every);
        detector = stub_port.get();
    }
    TensorRtRuntime::StageTimesMs engine_totals{};
    long long engine_runs = 0;
#if defined(KSI_SAFETY_EDGE_HAS_TENSORRT)
    std::unique_ptr<TensorRtRuntime> runtime;
    if (!args.engine_path.empty()) {
        runtime = std::make_unique<TensorRtRuntime>(args.engine_path);
        if (!runtime->ok()) {
            std::fprintf(stderr, "engine load failed: %s\n",
                         args.engine_path.c_str());
            return 1;
        }
        DfineConfig dfine_config;
        dfine_config.score_threshold = static_cast<float>(args.score_threshold);
        dfine_port = std::make_unique<DfineDetectorPort>(
            dfine_config,
            [&runtime, &args, &engine_totals,
             &engine_runs](const float* input, std::vector<float>* logits,
                           std::vector<float>* boxes) {
                if (!args.stage_times) {
                    return runtime->Run(input, logits, boxes);
                }
                TensorRtRuntime::StageTimesMs times;
                const bool ok = runtime->RunTimed(input, logits, boxes, &times);
                if (ok) {
                    engine_totals.h2d_ms += times.h2d_ms;
                    engine_totals.enqueue_ms += times.enqueue_ms;
                    engine_totals.sync_ms += times.sync_ms;
                    engine_totals.d2h_ms += times.d2h_ms;
                    ++engine_runs;
                }
                return ok;
            });
        detector = dfine_port.get();
    }
#else
    if (!args.engine_path.empty()) {
        std::fprintf(stderr,
                     "built without TensorRT; use --null-detector or a TRT "
                     "build (KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON)\n");
        return 2;
    }
#endif
    if (args.null_detector) {
        detector = nullptr;
    }

    // Source-evidence recorder: the same capture code the device app uses.
    std::unique_ptr<ksi::safety::SourceEvidenceRecorder> recorder;
    if (!args.evidence_dir.empty()) {
        recorder = std::make_unique<ksi::safety::SourceEvidenceRecorder>(
            args.evidence_dir, 0 /* unbounded: the host has the space */);
        if (!recorder->ok()) {
            std::fprintf(stderr, "evidence store failed to open: %s\n",
                         recorder->last_error().c_str());
            return 1;
        }
    }

    ksi::safety::ReplayPolicy policy;
    if (!args.policy_path.empty()) {
        std::string policy_error;
        if (!ksi::safety::LoadReplayPolicy(args.policy_path, &policy,
                                           &policy_error)) {
            std::fprintf(stderr, "policy error: %s\n", policy_error.c_str());
            return 1;
        }
    }

    RunnerConfig runner_config;
    runner_config.match.max_receive_gap_ns =
        args.max_receive_gap_ms * kNsPerMs;
    runner_config.match.max_bracket_interval_ns = args.max_bracket_ms * kNsPerMs;
    runner_config.detect_every_n_frames =
        static_cast<std::uint64_t>(args.detect_every);
    runner_config.enable_spatial = policy.has_spatial;
    runner_config.spatial = policy.spatial;
    SessionConfig session_config;
    session_config.tracker.iou_threshold =
        static_cast<float>(args.tracker_iou);
    session_config.tracker.max_age_frames =
        static_cast<int>(args.tracker_max_age);
    session_config.tracker.min_hits = static_cast<int>(args.tracker_min_hits);
    session_config.max_gap_ms = static_cast<std::uint64_t>(args.session_gap_ms);
    session_config.zone_envelope_m = args.zone_envelope_m;
    ModelProfile profile;
    profile.model_id = args.model_id;
    profile.model_digest = args.model_digest;
    profile.backend = "tensorrt";

    SessionRunner runner(runner_config, session_config, policy.proximity_rules,
                         policy.zone_rules, policy.zones, profile);
    ReplayTelemetrySource telemetry(frames.manifest(), "host_mono", clocks);

    // Unit A passive autonomy (opt-in only; default off). Explicit
    // APPLY/START on the shared runner, then every tick goes through the
    // SAME ProcessSharedTick wiring the device app uses.
    std::unique_ptr<ksi::safety::AutonomousProcessor> autonomy;
    if (!args.autonomy_evidence_dir.empty()) {
        std::string hash_error;
        std::string config_hash = "no-policy";
        if (!args.policy_path.empty()) {
            const std::string hashed =
                ksi::safety::Sha256HexOfFile(args.policy_path.c_str(),
                                             &hash_error);
            if (!hashed.empty()) config_hash = "sha256:" + hashed;
        }
        if (!runner.ApplyRuntimeConfig(policy.proximity_rules,
                                       policy.zone_rules, policy.zones,
                                       config_hash, {})
                     .ok) {
            std::fprintf(stderr, "autonomy install refused\n");
            return 1;
        }
        runner.RequestStart();
        ksi::safety::AutonomousConfig autonomy_config;
        autonomy_config.enabled = true;
        autonomy_config.source_attention = args.source_attention;
        autonomy_config.ring.max_frames = 64;
        autonomy_config.ring.max_bytes = 64ULL * 1024 * 1024;
        autonomy_config.ring.pre_frames =
            static_cast<std::size_t>(args.autonomy_pre_frames);
        autonomy_config.ring.post_frames =
            static_cast<std::size_t>(args.autonomy_post_frames);
        autonomy_config.ring.window_deadline_ns =
            args.autonomy_window_deadline_ms * kNsPerMs;
        autonomy_config.window_min_interval_ns =
            args.autonomy_window_min_interval_ms * kNsPerMs;
        autonomy_config.store_capacity_bytes =
            static_cast<std::uint64_t>(args.autonomy_capacity_bytes);
        autonomy_config.evidence_dir = args.autonomy_evidence_dir;
        autonomy_config.flight_id = args.flight_id;
        autonomy_config.edge_boot_id = args.boot_id;
        autonomy_config.stream_id = args.stream_id;
        autonomy_config.mission_revision_id = args.mission_revision;
        autonomy_config.policy_revision_id = args.policy_revision;
        autonomy_config.effective_config_hash =
            runner.effective_config_hash();
        autonomy_config.config_epoch = runner.config_epoch();
        autonomy_config.lens_id = args.autonomy_lens_id;
        autonomy_config.camera_manual_override = args.camera_manual;
        autonomy_config.simulated_input =
            args.stub_detector || args.null_detector ||
            args.engine_path.empty();
        std::string autonomy_error;
        if (!IsValidAutonomousConfig(autonomy_config, &autonomy_error)) {
            std::fprintf(stderr, "bad autonomy config: %s\n",
                         autonomy_error.c_str());
            return 2;
        }
        autonomy = std::make_unique<ksi::safety::AutonomousProcessor>(
            autonomy_config);
        if (!autonomy->valid()) {
            std::fprintf(stderr, "autonomy failed: %s\n",
                         autonomy->error().c_str());
            return 1;
        }
        std::fprintf(stderr,
                     "[SIMULATED] autonomy on: passive assessment only "
                     "(SHADOW, no incidents/alerts/human/camera/flight "
                     "outputs)%s\n",
                     autonomy_config.simulated_input
                         ? " — synthetic input, not a performance claim"
                         : "");
    }

    std::FILE* out = std::fopen(args.out_path.c_str(), "w");
    if (out == nullptr) {
        std::fprintf(stderr, "cannot open output: %s\n", args.out_path.c_str());
        return 1;
    }

    Stats stats;
    std::int64_t last_recv_ns = 0;
    for (std::size_t i = 0; i < frames.frame_count(); ++i) {
        FramePacket packet;
        FrameContext ctx;
        if (!frames.ReadFrame(i, &packet, &ctx)) {
            std::fprintf(stderr, "frame %zu unreadable (see manifest)\n", i);
            std::fclose(out);
            return 1;
        }
        last_recv_ns = ctx.receive_monotonic_ns;
        const ReplayTelemetrySource::PushResult pushed =
            telemetry.PushDue(ctx.receive_monotonic_ns, runner);
        stats.poses_pushed += pushed.poses_pushed;
        stats.positions_pushed += pushed.positions_pushed;
        stats.duplicates += pushed.duplicates;
        stats.rollbacks += pushed.rollbacks;

        const auto start = std::chrono::steady_clock::now();
        TickRecord record;
        if (autonomy != nullptr) {
            const ksi::safety::SharedTickResult shared =
                ksi::safety::ProcessSharedTick(
                    runner, *autonomy, packet, ctx, detector,
                    packet.rgb.data(), packet.rgb.size());
            record = shared.record;
        } else {
            record = runner.ProcessFrame(packet, ctx, detector);
        }
        const auto end = std::chrono::steady_clock::now();
        stats.tick_ms.push_back(
            std::chrono::duration<double, std::milli>(end - start).count());

        if (recorder != nullptr) {
            ksi::safety::SourceFrame source;
            source.flight_id = args.flight_id;
            source.edge_boot_id = args.boot_id;
            source.frame_id = record.frame_id;
            source.received_monotonic_ns = record.receive_mono_ns;
            source.rgb = packet.rgb.data();
            source.rgb_size = packet.rgb.size();
            const ksi::safety::EvidenceStatus status =
                recorder->Capture(record, source);
            if (status == ksi::safety::EvidenceStatus::kFull) {
                std::fprintf(stderr,
                             "evidence store full: the source frame of this "
                             "observation is NOT retained\n");
            }
        }
        ++stats.frames;
        switch (record.detection_status) {
            case DetectionStatus::kOk:
                ++stats.ok_ticks;
                break;
            case DetectionStatus::kInferenceFailure:
                ++stats.failure_ticks;
                break;
            case DetectionStatus::kNotRun:
                ++stats.notrun_ticks;
                break;
        }
        stats.detections_total += record.detections.size();
        for (const ksi::safety::SpatialObservationRecord& spatial :
             record.spatial) {
            if (spatial.has_observation) {
                ++stats.observations_total;
            }
        }
        if (record.spatial_status == ksi::safety::SpatialTickReason::kEstimated) {
            ++stats.spatial_estimated_ticks;
        } else if (record.spatial_status ==
                   ksi::safety::SpatialTickReason::kNotEligible) {
            ++stats.spatial_noteligible_ticks;
        }
        CountEvents(record.proximity, &stats);
        CountEvents(record.zone, &stats);
        WriteTick(out, record);
    }

    // Seal the session's source frames into one immutable revision: the
    // collector exports exactly this (and nothing else).
    if (recorder != nullptr) {        std::string revision_id;
        std::string manifest;
        std::string error;
        if (recorder->Seal(args.flight_id, args.boot_id, 0, &revision_id, &manifest,
                           &error)) {
            std::fprintf(stderr,
                         "evidence revision sealed: %s (%zu source frames)\n",
                         revision_id.c_str(), recorder->captured_count());
        } else if (error.rfind("no source frames", 0) != 0) {
            std::fprintf(stderr, "evidence revision NOT sealed: %s\n", error.c_str());
        }
    }

    // Unit A closeout: stop closes open windows (named incomplete), then one
    // immutable revision covers every assessment + window-frame artifact.
    if (autonomy != nullptr) {
        autonomy->RequestStop(last_recv_ns);
        const ksi::safety::AutonomousCoverage& coverage =
            autonomy->coverage();
        std::fprintf(stderr,
                     "autonomy: ticks=%llu assessed=%llu undetermined=%llu "
                     "windows=%llu complete=%llu partial=%llu "
                     "window_frames=%llu writes_refused=%llu conflicts=%llu "
                     "duplicates=%llu\n",
                     static_cast<unsigned long long>(
                         coverage.processed_ticks),
                     static_cast<unsigned long long>(
                         coverage.assessments_assessed),
                     static_cast<unsigned long long>(
                         coverage.assessments_undetermined),
                     static_cast<unsigned long long>(
                         coverage.windows_opened),
                     static_cast<unsigned long long>(
                         coverage.windows_complete),
                     static_cast<unsigned long long>(
                         coverage.windows_partial),
                     static_cast<unsigned long long>(
                         coverage.window_frames_written),
                     static_cast<unsigned long long>(
                         coverage.writes_refused),
                     static_cast<unsigned long long>(
                         coverage.digest_conflicts),
                     static_cast<unsigned long long>(
                         coverage.idempotent_duplicates));
        if (!coverage.write_failure.empty()) {
            std::fprintf(stderr, "autonomy write failure (sticky): %s\n",
                         coverage.write_failure.c_str());
        }
        if (coverage.evidence_saturated) {
            std::fprintf(stderr,
                         "autonomy evidence SATURATED (%s): observation "
                         "continued, %llu artifacts refused\n",
                         coverage.saturation_reason.c_str(),
                         static_cast<unsigned long long>(
                             coverage.evidence_refusals));
        }
        if (coverage.windows_merged > 0) {
            std::fprintf(stderr,
                         "autonomy windows merged by interval bound: %llu\n",
                         static_cast<unsigned long long>(
                             coverage.windows_merged));
        }
        std::string revision_id;
        std::string manifest;
        std::string error;
        if (autonomy->Seal(&revision_id, &manifest, &error)) {
            std::fprintf(stderr, "autonomy revision sealed: %s (%zu artifacts)\n",
                          revision_id.c_str(), autonomy->artifacts().size());
        } else {
            std::fprintf(stderr, "autonomy revision NOT sealed: %s\n",
                         error.c_str());
        }
    }

    const double tick_mean =
        stats.tick_ms.empty()
            ? 0.0
            : [&stats] {
                  double sum = 0.0;
                  for (double value : stats.tick_ms) {
                      sum += value;
                  }
                  return sum / static_cast<double>(stats.tick_ms.size());
              }();
    const double engine_denominator =
        engine_runs > 0 ? static_cast<double>(engine_runs) : 1.0;
    std::fprintf(
        out,
        "{\"type\":\"stat\",\"frames\":%zu,\"ok_ticks\":%zu,"
        "\"failure_ticks\":%zu,\"notrun_ticks\":%zu,"
        "\"detections_total\":%zu,\"observations_total\":%zu,"
        "\"spatial_estimated_ticks\":%zu,\"spatial_noteligible_ticks\":%zu,"
        "\"episodes_created\":%zu,"
        "\"episodes_closed\":%zu,\"poses_pushed\":%zu,"
        "\"positions_pushed\":%zu,\"duplicates\":%zu,\"rollbacks\":%zu,"
        "\"telemetry_pending_end\":%zu,\"tick_ms_mean\":%.3f,"
        "\"tick_ms_p95\":%.3f,\"detect_every\":%lld,"
        "\"engine_runs\":%lld,\"engine_h2d_ms_mean\":%.3f,"
        "\"engine_enqueue_ms_mean\":%.3f,\"engine_sync_ms_mean\":%.3f,"
        "\"engine_d2h_ms_mean\":%.3f}\n",
        stats.frames, stats.ok_ticks, stats.failure_ticks, stats.notrun_ticks,
        stats.detections_total, stats.observations_total,
        stats.spatial_estimated_ticks, stats.spatial_noteligible_ticks,
        stats.episodes_created, stats.episodes_closed, stats.poses_pushed,
        stats.positions_pushed, stats.duplicates, stats.rollbacks,
        telemetry.pending_count(), tick_mean, Percentile(stats.tick_ms, 0.95),
        args.detect_every, engine_runs,
        engine_totals.h2d_ms / engine_denominator,
        engine_totals.enqueue_ms / engine_denominator,
        engine_totals.sync_ms / engine_denominator,
        engine_totals.d2h_ms / engine_denominator);
    std::fclose(out);
    std::printf("replay_run: %zu frames -> %s\n", stats.frames,
                args.out_path.c_str());
    return 0;
}
