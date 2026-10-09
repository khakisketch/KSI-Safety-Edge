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
#include "ksi_safety/replay/frame_source.hpp"
#include "ksi_safety/replay/policy_file.hpp"
#include "ksi_safety/replay/telemetry_source.hpp"
#include "ksi_safety/runtime/session_runner.hpp"

#if defined(KSI_SAFETY_EDGE_HAS_TENSORRT)
#include "ksi_safety/perception/tensorrt_runtime.hpp"
#endif

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
using ksi::safety::TrackRecord;
using ksi::safety::ZoneRevision;
#if defined(KSI_SAFETY_EDGE_HAS_TENSORRT)
using ksi::safety::TensorRtRuntime;
#endif

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
};

void PrintUsage() {
    std::fprintf(stderr,
                 "usage: replay_run --manifest PATH [--out PATH] "
                 "[--null-detector]\n"
                 "       [--engine PATH] [--model-id ID] [--model-digest D]\n"
                 "       [--policy PATH] [--score-threshold F]\n"
                 "       [--detect-every N] [--max-receive-gap-ms N] "
                 "[--max-bracket-ms N]\n"
                 "       [--tracker-iou F] [--tracker-max-age N] "
                 "[--tracker-min-hits N]\n"
                 "       [--session-gap-ms N] [--zone-envelope-m F]\n"
                 "       [--flight-id ID] [--boot-id ID] [--stream-id ID]\n");
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
        "\"recv_mono_ns\":%lld,\"model\":\"%s\",\"exposure_verified\":%s,"
        "\"detect\":\"%s\",\"dets\":%zu,"
        "\"att\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
        "\"pos\":{\"q\":\"%s\",\"r\":\"%s\",\"gap_ns\":%lld},"
        "\"spatial_eligible\":%s,",
        static_cast<unsigned long long>(record.tick_index), record.frame_id,
        record.stream_id.c_str(),
        static_cast<long long>(record.receive_mono_ns),
        record.model_id.c_str(), record.exposure_verified ? "true" : "false",
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
            [&runtime](const float* input, std::vector<float>* logits,
                       std::vector<float>* boxes) {
                return runtime->Run(input, logits, boxes);
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

    std::FILE* out = std::fopen(args.out_path.c_str(), "w");
    if (out == nullptr) {
        std::fprintf(stderr, "cannot open output: %s\n", args.out_path.c_str());
        return 1;
    }

    Stats stats;
    for (std::size_t i = 0; i < frames.frame_count(); ++i) {
        FramePacket packet;
        FrameContext ctx;
        if (!frames.ReadFrame(i, &packet, &ctx)) {
            std::fprintf(stderr, "frame %zu unreadable (see manifest)\n", i);
            std::fclose(out);
            return 1;
        }
        const ReplayTelemetrySource::PushResult pushed =
            telemetry.PushDue(ctx.receive_monotonic_ns, runner);
        stats.poses_pushed += pushed.poses_pushed;
        stats.positions_pushed += pushed.positions_pushed;
        stats.duplicates += pushed.duplicates;
        stats.rollbacks += pushed.rollbacks;

        const auto start = std::chrono::steady_clock::now();
        const TickRecord record =
            runner.ProcessFrame(packet, ctx, detector);
        const auto end = std::chrono::steady_clock::now();
        stats.tick_ms.push_back(
            std::chrono::duration<double, std::milli>(end - start).count());

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
        "\"tick_ms_p95\":%.3f,\"detect_every\":%lld}\n",
        stats.frames, stats.ok_ticks, stats.failure_ticks, stats.notrun_ticks,
        stats.detections_total, stats.observations_total,
        stats.spatial_estimated_ticks, stats.spatial_noteligible_ticks,
        stats.episodes_created, stats.episodes_closed, stats.poses_pushed,
        stats.positions_pushed, stats.duplicates, stats.rollbacks,
        telemetry.pending_count(), tick_mean, Percentile(stats.tick_ms, 0.95),
        args.detect_every);
    std::fclose(out);
    std::printf("replay_run: %zu frames -> %s\n", stats.frames,
                args.out_path.c_str());
    return 0;
}
