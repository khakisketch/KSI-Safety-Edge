// Operational startup configuration for the PSDK app (DJI-free).
//
// Deployment contract (DPK app.json ships `"bin"` with `"userconfig": []`):
// the installed app starts with NO argv, so the argvless defaults ARE the
// operational behavior. They must start the MOP channel service (channel 0
// unless the deploy default says otherwise) with NO deadline and NO
// automatic processing: the gate opens only on an actual MISSION_APPLY
// install plus an explicit SESSION_START from the RC, and SESSION_STOP
// stops analysis while the service stays ready for the next session.
//
// The legacy capture workflow stays available as an EXPLICIT diagnostic
// mode (--standalone, or --mop-channel -1 with a finite --duration-sec).
// There is no silent auto-analysis in operational mode.
//
// This header is deliberately PSDK-free so host tests
// (edge/tests/test_app_config.cpp) exercise the exact argv decision the
// device runs. main.cpp fills DeployDefaults from the CMake deployment
// cache (trusted installer: engine path / model id) and CLI flags override
// the deploy defaults for development.
#pragma once

#include <cstdint>
#include <string>

#include "ksi_safety/observation/source_attention_policy.hpp"

namespace ksi_safety_edge_app {

// Deployment-time defaults (CMake cache; trusted installer input).
struct DeployDefaults {
    // argvless operational MOP channel (RC DEFAULT_PIPELINE_ID = 0).
    int mop_channel = 0;
    // Standalone diagnostic default: finite seconds (60 s legacy capture).
    long long diag_duration_sec = 60;
    // Trusted-installer engine path; null/empty = engine absent (no
    // fabricated observations — ticks stay NOT_RUN / degraded as today).
    const char* engine_path = nullptr;
    // Trusted-installer model id; null/empty = keep the existing code
    // default (never invent an operational model name here).
    const char* model_id = nullptr;
    // Trusted-installer autonomy evidence directory. Non-empty enables the
    // passive SHADOW assessment/evidence path for the argvless operational
    // service (the DPK ships no argv). Null/empty = disabled: autonomy is
    // never silently auto-enabled on the device.
    const char* autonomy_evidence_dir = nullptr;
    const char* source_attention_policy = nullptr;
};

// Resolved startup configuration (single decision point for main()).
struct AppConfig {
    // True: start the MOP channel service and learn the RC flight id.
    bool mop_service = true;
    // MOP channel id (meaningful only when mop_service is true).
    int mop_channel = 0;
    // Legacy automatic processing from boot (standalone mode ONLY;
    // operational mode never auto-processes).
    bool auto_process = false;
    // True: stop with diagnostics after duration_sec (standalone default,
    // or an explicit operational bound). False: run until signal.
    bool has_deadline = false;
    long long duration_sec = 0;  // meaningful only when has_deadline
    // Effective engine path: CLI --engine wins, else the deploy default.
    // Null = engine absent.
    const char* engine_path = nullptr;
    // Effective model id: CLI --model-id wins, else the deploy default.
    // Null = keep the existing code default in main().
    const char* model_id = nullptr;
    const char* out_path = "/tmp/ksi-safety-edge.ndjson";
    long long detect_every = 1;
    // Empty: learn the RC's per-flight UUID from the wire (adoption).
    const char* flight_id = "";
    const char* state_dir = "/open_app/ksi-safety-edge/state";
    // Invariant, pinned for tests: SESSION_STOP ends analysis (gate
    // closes) but NEVER exits the service process. The main loop only
    // leaves on its deadline (when bounded) or a termination signal.
    bool exit_on_session_stop = false;
    // Unit A passive autonomy (opt-in, off by default): machine
    // assessments + pre/post source windows sealed as versioned artifacts.
    // Null = disabled (never silently auto-enabled on the device).
    const char* autonomy_evidence_dir = nullptr;
    long long autonomy_pre_frames = 0;
    long long autonomy_post_frames = 1;
    long long autonomy_window_deadline_ms = 500;
    // P1 event evidence: at most one small trigger+post window per interval.
    long long autonomy_window_min_interval_ms = 1000;
    long long autonomy_capacity_bytes = 8LL * 1024 * 1024;
    // A human holds the camera: spatial conclusions withheld, analysis
    // continues (validity of the manual input affects spatial only).
    bool camera_manual = false;
    ksi::safety::SourceAttentionPolicy source_attention;
};

enum class ParseStatus {
    kOk,    // config resolved, run the app
    kHelp,  // --help: usage printed, exit 0
    kError  // invalid flags: *error carries the reason, exit 2
};

// Strict base-10 integer: non-empty, optional leading '-', ASCII digits
// only, fully consumed, no errno/overflow. Never atoll (junk must not
// silently become 0).
inline bool ParseInt64Strict(const char* text, long long* value) {
    if (text == nullptr || *text == '\0') return false;
    const char* p = text;
    if (*p == '-') {
        ++p;
        if (*p == '\0') return false;
    } else if (*p == '+') {
        ++p;
        if (*p == '\0') return false;
    }
    int digits = 0;
    for (const char* q = p; *q != '\0'; ++q) {
        if (*q < '0' || *q > '9') return false;
        ++digits;
    }
    // At most 18 digits < 10^18 < LLONG_MAX (9.2e18): the manual
    // accumulate below cannot overflow. Longer inputs are rejected.
    if (digits > 18) return false;
    long long parsed = 0;
    const bool negative = (*text == '-');
    for (const char* q = p; *q != '\0'; ++q) {
        parsed = parsed * 10 + (*q - '0');
    }
    *value = negative ? -parsed : parsed;
    return true;
}

// Largest finite --duration-sec that cannot overflow deadline_ns arithmetic:
// deadline = now_ns + duration_sec * 1e9 must stay within int64 for any
// realistic monotonic clock (4e9 s is ~126 years: 4e18 ns plus uptime).
inline long long MaxDurationSec() {
    return 4000000000LL;
}

inline ParseStatus ResolveAppConfig(int argc, const char* const* argv,
                                    const DeployDefaults& deploy,
                                    AppConfig* out, std::string* error) {
    AppConfig config;
    config.mop_channel = deploy.mop_channel;
    config.engine_path =
        (deploy.engine_path != nullptr && deploy.engine_path[0] != '\0')
            ? deploy.engine_path
            : nullptr;
    config.model_id =
        (deploy.model_id != nullptr && deploy.model_id[0] != '\0')
            ? deploy.model_id
            : nullptr;
    config.autonomy_evidence_dir =
        (deploy.autonomy_evidence_dir != nullptr &&
         deploy.autonomy_evidence_dir[0] != '\0')
            ? deploy.autonomy_evidence_dir
             : nullptr;
    if (deploy.source_attention_policy != nullptr && deploy.source_attention_policy[0] != '\0' &&
        !ksi::safety::ParseSourceAttentionPolicy(deploy.source_attention_policy, &config.source_attention, error)) {
        return ParseStatus::kError;
    }
    bool mop_channel_given = false;
    bool standalone_given = false;
    bool duration_given = false;
    long long duration_value = 0;

    for (int i = 1; i < argc; ++i) {
        const std::string flag = argv[i] != nullptr ? argv[i] : "";
        std::string value;
        const auto need_value = [&](const char* name) -> bool {
            if (i + 1 >= argc || argv[i + 1] == nullptr) {
                if (error != nullptr) {
                    *error = std::string(name) + " requires a value";
                }
                return false;
            }
            value = argv[++i];
            return true;
        };
        long long number = 0;
        const auto need_number = [&](const char* name) -> bool {
            if (!need_value(name)) return false;
            if (!ParseInt64Strict(value.c_str(), &number)) {
                if (error != nullptr) {
                    *error = std::string(name) +
                             " must be a base-10 integer, got '" + value +
                             "'";
                }
                return false;
            }
            return true;
        };
        if (flag == "--out") {
            if (!need_value("--out")) return ParseStatus::kError;
            config.out_path = argv[i];
        } else if (flag == "--duration-sec") {
            if (!need_number("--duration-sec")) return ParseStatus::kError;
            duration_given = true;
            duration_value = number;
        } else if (flag == "--engine") {
            if (!need_value("--engine")) return ParseStatus::kError;
            if (value.empty()) {
                if (error != nullptr) {
                    *error = "--engine must be non-empty (omit the flag "
                             "when the engine is absent)";
                }
                return ParseStatus::kError;
            }
            config.engine_path = argv[i];
        } else if (flag == "--model-id") {
            if (!need_value("--model-id")) return ParseStatus::kError;
            if (!value.empty()) config.model_id = argv[i];
        } else if (flag == "--detect-every") {
            if (!need_number("--detect-every")) return ParseStatus::kError;
            config.detect_every = number;
        } else if (flag == "--mop-channel") {
            if (!need_number("--mop-channel")) return ParseStatus::kError;
            mop_channel_given = true;
            if (number < -1 || number > 65535) {
                if (error != nullptr) {
                    *error = "--mop-channel must be -1 (diagnostic) or "
                             "0..65535, got '" +
                             value + "'";
                }
                return ParseStatus::kError;
            }
            config.mop_channel = static_cast<int>(number);
        } else if (flag == "--standalone") {
            standalone_given = true;
        } else if (flag == "--flight-id") {
            if (!need_value("--flight-id")) return ParseStatus::kError;
            config.flight_id = argv[i];
        } else if (flag == "--state-dir") {
            if (!need_value("--state-dir")) return ParseStatus::kError;
            config.state_dir = argv[i];
        } else if (flag == "--autonomy-evidence-dir") {
            if (!need_value("--autonomy-evidence-dir")) {
                return ParseStatus::kError;
            }
            if (value.empty()) {
                if (error != nullptr) {
                    *error = "--autonomy-evidence-dir must be non-empty "
                             "(omit the flag to keep autonomy off)";
                }
                return ParseStatus::kError;
            }
            config.autonomy_evidence_dir = argv[i];
        } else if (flag == "--autonomy-pre") {
            if (!need_number("--autonomy-pre")) return ParseStatus::kError;
            config.autonomy_pre_frames = number;
        } else if (flag == "--autonomy-post") {
            if (!need_number("--autonomy-post")) return ParseStatus::kError;
            config.autonomy_post_frames = number;
        } else if (flag == "--autonomy-window-deadline-ms") {
            if (!need_number("--autonomy-window-deadline-ms")) {
                return ParseStatus::kError;
            }
            config.autonomy_window_deadline_ms = number;
        } else if (flag == "--autonomy-window-min-interval-ms") {
            if (!need_number("--autonomy-window-min-interval-ms")) {
                return ParseStatus::kError;
            }
            if (number < 0 || number > 3600000) {
                if (error != nullptr) {
                    *error = "--autonomy-window-min-interval-ms must be "
                             "0..3600000 (0 = no interval bound)";
                }
                return ParseStatus::kError;
            }
            config.autonomy_window_min_interval_ms = number;
        } else if (flag == "--autonomy-capacity-bytes") {
            if (!need_number("--autonomy-capacity-bytes")) {
                return ParseStatus::kError;
            }
            config.autonomy_capacity_bytes = number;
        } else if (flag == "--source-attention-policy") {
            if (!need_value("--source-attention-policy") ||
                !ksi::safety::ParseSourceAttentionPolicy(value, &config.source_attention, error)) {
                return ParseStatus::kError;
            }
        } else if (flag == "--camera-manual") {
            config.camera_manual = true;
        } else if (flag == "--help") {
            return ParseStatus::kHelp;
        } else {
            if (error != nullptr) *error = "unknown argument: " + flag;
            return ParseStatus::kError;
        }
    }

    if (standalone_given && mop_channel_given && config.mop_channel != -1) {
        if (error != nullptr) {
            *error = "--standalone conflicts with --mop-channel " +
                     std::to_string(config.mop_channel);
        }
        return ParseStatus::kError;
    }
    const bool standalone =
        standalone_given || (mop_channel_given && config.mop_channel == -1);
    if (!standalone && !mop_channel_given &&
        (deploy.mop_channel < 0 || deploy.mop_channel > 65535)) {
        if (error != nullptr) {
            *error = "deploy default MOP channel out of range 0..65535";
        }
        return ParseStatus::kError;
    }

    if (standalone) {
        // Explicit diagnostic mode: the legacy capture workflow (automatic
        // processing, finite run). MOP stays off; nothing is commanded.
        config.mop_service = false;
        config.auto_process = true;
        config.has_deadline = true;
        if (duration_given) {
            if (duration_value <= 0 ||
                duration_value > MaxDurationSec()) {
                if (error != nullptr) {
                    *error = "--duration-sec in --standalone mode must be a "
                             "finite 1.." +
                             std::to_string(MaxDurationSec()) + " (0 means "
                             "unbounded service, operational mode only)";
                }
                return ParseStatus::kError;
            }
            config.duration_sec = duration_value;
        } else {
            if (deploy.diag_duration_sec <= 0 ||
                deploy.diag_duration_sec > MaxDurationSec()) {
                if (error != nullptr) {
                    *error = "deploy diagnostic duration out of range 1.." +
                             std::to_string(MaxDurationSec());
                }
                return ParseStatus::kError;
            }
            config.duration_sec = deploy.diag_duration_sec;
        }
    } else {
        // Operational service: MOP on (argvless default), gate closed until
        // an actual APPLY plus explicit SESSION_START. No deadline unless
        // the operator bounded this run explicitly.
        config.mop_service = true;
        config.auto_process = false;
        if (duration_given) {
            // 0 = unbounded service (the operational default, spelled out).
            if (duration_value < 0 ||
                duration_value > MaxDurationSec()) {
                if (error != nullptr) {
                    *error = "--duration-sec must be 0 (unbounded service) "
                             "or 1.." +
                             std::to_string(MaxDurationSec());
                }
                return ParseStatus::kError;
            }
            config.has_deadline = (duration_value > 0);
            config.duration_sec = duration_value;
        } else {
            config.has_deadline = false;
            config.duration_sec = 0;
        }
    }

    if (config.out_path == nullptr || config.out_path[0] == '\0') {
        if (error != nullptr) *error = "--out must be non-empty";
        return ParseStatus::kError;
    }
    if (config.detect_every < 1) {
        if (error != nullptr) *error = "--detect-every must be >= 1";
        return ParseStatus::kError;
    }
    if (config.state_dir == nullptr || config.state_dir[0] == '\0') {
        if (error != nullptr) {
            *error = "--state-dir must be non-empty (durable spool + audit "
                     "log live there; silent in-memory fallback is "
                     "forbidden)";
        }
        return ParseStatus::kError;
    }
    if (config.source_attention.enabled &&
        (config.autonomy_evidence_dir == nullptr || config.autonomy_evidence_dir[0] == '\0' ||
         config.autonomy_capacity_bytes <= 0)) {
        if (error != nullptr) *error = "source attention requires non-empty --autonomy-evidence-dir and finite positive capacity";
        return ParseStatus::kError;
    }
    // SESSION_STOP closes the analysis gate; it never exits the service.
    config.exit_on_session_stop = false;

    if (out != nullptr) *out = config;
    return ParseStatus::kOk;
}

}  // namespace ksi_safety_edge_app
