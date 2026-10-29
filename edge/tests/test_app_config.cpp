// Operational startup-config tests (DJI-free, host-runnable).
//
// Exercises the exact argv decision the deployed DPK runs: argvless launch
// must start the persistent MOP service (no deadline, no auto-processing),
// while the legacy capture workflow stays available only as an explicit
// diagnostic mode. Strict numeric parsing, deploy-default plumbing
// (engine/model from the trusted installer), and the SESSION_STOP-never-
// exits invariant are pinned here so main() cannot regress them.
#include <string>
#include <vector>

#include "app_config.hpp"
#include "support/check.hpp"

using ksi_safety_edge_app::AppConfig;
using ksi_safety_edge_app::DeployDefaults;
using ksi_safety_edge_app::ParseInt64Strict;
using ksi_safety_edge_app::ParseStatus;
using ksi_safety_edge_app::ResolveAppConfig;

namespace {

// Deploy defaults mirroring the CMake cache (channel 0, 60 s diagnostic,
// no engine, no model override).
DeployDefaults TestDeploy() {
    DeployDefaults deploy;
    deploy.mop_channel = 0;
    deploy.diag_duration_sec = 60;
    deploy.engine_path = nullptr;
    deploy.model_id = nullptr;
    deploy.autonomy_evidence_dir = nullptr;
    return deploy;
}

ParseStatus Resolve(const std::vector<const char*>& args,
                    const DeployDefaults& deploy, AppConfig* config,
                    std::string* error) {
    return ResolveAppConfig(static_cast<int>(args.size()), args.data(),
                            deploy, config, error);
}

ParseStatus Resolve(const std::vector<const char*>& args, AppConfig* config,
                    std::string* error) {
    return Resolve(args, TestDeploy(), config, error);
}

}  // namespace

int main() {
    // --- strict integer parsing: no atoll junk->0 --------------------------
    {
        long long value = 0;
        CHECK(ParseInt64Strict("30", &value) && value == 30, "plain number");
        CHECK(ParseInt64Strict("0", &value) && value == 0, "zero parses");
        CHECK(ParseInt64Strict("-1", &value) && value == -1, "negative -1");
        CHECK(!ParseInt64Strict("", &value), "empty rejected");
        CHECK(!ParseInt64Strict("abc", &value), "junk rejected");
        CHECK(!ParseInt64Strict("12abc", &value), "trailing junk rejected");
        CHECK(!ParseInt64Strict(" 12", &value), "leading space rejected");
        CHECK(!ParseInt64Strict("12 ", &value), "trailing space rejected");
        CHECK(!ParseInt64Strict("0x10", &value), "hex rejected");
        CHECK(!ParseInt64Strict("--5", &value), "double sign rejected");
        CHECK(!ParseInt64Strict("-", &value), "bare sign rejected");
        CHECK(!ParseInt64Strict("99999999999999999999999", &value),
              "overflow rejected");
        CHECK(!ParseInt64Strict("12345678901234567890123", &value),
              "long overflow rejected");
        CHECK(ParseInt64Strict("999999999999999999", &value) &&
                  value == 999999999999999999LL,
              "18-digit input parses (still < LLONG_MAX)");
        // 19 digits can exceed int64: rejected at the parse layer (every
        // flag's valid range is far smaller; MaxDurationSec is 13 digits).
        CHECK(!ParseInt64Strict("9223372036854775807", &value),
              "19-digit input rejected (overflow guard)");
    }

    // --- argvless DPK launch: operational MOP service ----------------------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kOk,
              "argvless resolves");
        CHECK(error.empty(), "no error text on success");
        CHECK(config.mop_service, "argvless starts the MOP service");
        CHECK(config.mop_channel == 0, "argvless MOP channel is 0");
        CHECK(!config.has_deadline, "argvless service has NO deadline");
        CHECK(!config.auto_process,
              "argvless never auto-processes (gate closed until APPLY+START)");
        CHECK(!config.exit_on_session_stop,
              "SESSION_STOP never exits the service");
        CHECK(config.engine_path == nullptr, "no engine by default");
        CHECK(config.model_id == nullptr, "no model override by default");
        CHECK(config.autonomy_evidence_dir == nullptr,
              "autonomy OFF by default (never silently enabled)");
        CHECK(config.detect_every == 1, "detect cadence default");
    }

    // --- deploy defaults flow through (trusted installer) -------------------
    {
        DeployDefaults deploy = TestDeploy();
        deploy.engine_path = "/open_app/models/dfine.engine";
        deploy.model_id = "dfine-s-coco-fp16";
        deploy.mop_channel = 2;
        deploy.autonomy_evidence_dir =
            "/open_app/ksi-safety-edge/autonomy";
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge"};
        CHECK(Resolve(argv, deploy, &config, &error) == ParseStatus::kOk,
              "deploy defaults resolve");
        CHECK(config.mop_service && config.mop_channel == 2,
              "deploy MOP channel honored argvless");
        CHECK(config.engine_path != nullptr &&
                  std::string(config.engine_path) ==
                      "/open_app/models/dfine.engine",
              "deploy engine path honored");
        CHECK(config.model_id != nullptr &&
                  std::string(config.model_id) == "dfine-s-coco-fp16",
              "deploy model id honored");
        CHECK(config.autonomy_evidence_dir != nullptr &&
                  std::string(config.autonomy_evidence_dir) ==
                      "/open_app/ksi-safety-edge/autonomy",
              "deploy autonomy dir honored argvless");
        CHECK(!config.has_deadline, "deploy default stays unbounded");
    }

    // --- CLI overrides deploy (development) ---------------------------------
    {
        DeployDefaults deploy = TestDeploy();
        deploy.engine_path = "/open_app/models/dfine.engine";
        deploy.model_id = "dfine-s-coco-fp16";
        deploy.autonomy_evidence_dir = "/open_app/default-autonomy";
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {
            "ksi_safety_edge", "--engine", "/tmp/dev.engine", "--model-id",
            "dev-model", "--mop-channel", "3", "--autonomy-evidence-dir",
            "/tmp/dev-autonomy"};
        CHECK(Resolve(argv, deploy, &config, &error) == ParseStatus::kOk,
              "CLI overrides resolve");
        CHECK(std::string(config.engine_path) == "/tmp/dev.engine",
              "CLI engine wins over deploy");
        CHECK(std::string(config.model_id) == "dev-model",
              "CLI model id wins over deploy");
        CHECK(std::string(config.autonomy_evidence_dir) ==
                  "/tmp/dev-autonomy",
              "CLI autonomy dir wins over deploy");
        CHECK(config.mop_channel == 3, "CLI channel wins over deploy");
        CHECK(config.mop_service && !config.has_deadline,
              "still an unbounded operational service");
    }

    // --- explicit operational bound ------------------------------------------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge",
                                               "--mop-channel", "0",
                                               "--duration-sec", "30"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kOk,
              "explicit operational bound resolves");
        CHECK(config.mop_service && config.mop_channel == 0,
              "operational on channel 0");
        CHECK(config.has_deadline && config.duration_sec == 30,
              "explicit finite duration honored");
        CHECK(!config.auto_process, "still no auto-processing");
        CHECK(!config.exit_on_session_stop, "stop still never exits");
    }

    // --- --duration-sec 0 means unbounded service (operational only) ---------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge",
                                               "--duration-sec", "0"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kOk,
              "duration 0 resolves in operational mode");
        CHECK(config.mop_service && !config.has_deadline,
              "duration 0 is unbounded service, not a zero-second run");
    }

    // --- --standalone: explicit diagnostic, finite, auto-processing ----------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge",
                                               "--standalone"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kOk,
              "standalone resolves");
        CHECK(!config.mop_service, "standalone runs no MOP service");
        CHECK(config.auto_process, "standalone keeps legacy auto-processing");
        CHECK(config.has_deadline && config.duration_sec == 60,
              "standalone defaults to the finite diagnostic duration");
        CHECK(!config.exit_on_session_stop, "stop still never exits");
    }

    // --- legacy spelling: --mop-channel -1 with finite duration --------------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge",
                                               "--mop-channel", "-1",
                                               "--duration-sec", "30"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kOk,
              "legacy diagnostic spelling resolves");
        CHECK(!config.mop_service && config.auto_process,
              "channel -1 is diagnostic");
        CHECK(config.has_deadline && config.duration_sec == 30,
              "explicit finite duration honored");
    }

    // --- invalid inputs all fail closed ---------------------------------------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> junk = {"ksi_safety_edge",
                                               "--duration-sec", "abc"};
        CHECK(Resolve(junk, &config, &error) == ParseStatus::kError,
              "junk duration rejected");
        CHECK(!error.empty(), "junk duration explains itself");
        const std::vector<const char*> neg = {"ksi_safety_edge",
                                              "--duration-sec", "-5"};
        CHECK(Resolve(neg, &config, &error) == ParseStatus::kError,
              "negative duration rejected");
        const std::vector<const char*> huge = {
            "ksi_safety_edge", "--duration-sec", "99999999999999999999999"};
        CHECK(Resolve(huge, &config, &error) == ParseStatus::kError,
              "overflowing duration rejected");
        const std::vector<const char*> huge_op = {"ksi_safety_edge",
                                                  "--mop-channel", "0",
                                                  "--duration-sec",
                                                  "9999999999999999999"};
        CHECK(Resolve(huge_op, &config, &error) == ParseStatus::kError,
              "deadline-overflowing duration rejected");
        const std::vector<const char*> bad_ch = {"ksi_safety_edge",
                                                 "--mop-channel", "65536"};
        CHECK(Resolve(bad_ch, &config, &error) == ParseStatus::kError,
              "channel > 65535 rejected");
        const std::vector<const char*> neg_ch = {"ksi_safety_edge",
                                                 "--mop-channel", "-2"};
        CHECK(Resolve(neg_ch, &config, &error) == ParseStatus::kError,
              "channel < -1 rejected");
        const std::vector<const char*> zero_cad = {"ksi_safety_edge",
                                                   "--standalone",
                                                   "--detect-every", "0"};
        CHECK(Resolve(zero_cad, &config, &error) == ParseStatus::kError,
              "detect-every 0 rejected");
        const std::vector<const char*> unknown = {"ksi_safety_edge",
                                                  "--turbo"};
        CHECK(Resolve(unknown, &config, &error) == ParseStatus::kError,
              "unknown flag rejected");
        const std::vector<const char*> missing = {"ksi_safety_edge",
                                                  "--duration-sec"};
        CHECK(Resolve(missing, &config, &error) == ParseStatus::kError,
              "missing value rejected");
        const std::vector<const char*> empty_out = {"ksi_safety_edge",
                                                    "--standalone", "--out",
                                                    ""};
        CHECK(Resolve(empty_out, &config, &error) == ParseStatus::kError,
              "empty --out rejected");
        const std::vector<const char*> conflict = {"ksi_safety_edge",
                                                   "--standalone",
                                                   "--mop-channel", "0"};
        CHECK(Resolve(conflict, &config, &error) == ParseStatus::kError,
              "standalone + MOP channel conflict rejected");
        const std::vector<const char*> standalone_zero = {
            "ksi_safety_edge", "--standalone", "--duration-sec", "0"};
        CHECK(Resolve(standalone_zero, &config, &error) ==
                  ParseStatus::kError,
              "duration 0 rejected in standalone (unbounded is service-only)");
        const std::vector<const char*> over_max = {"ksi_safety_edge",
                                                     "--mop-channel", "0",
                                                     "--duration-sec",
                                                     "5000000000"};
        CHECK(Resolve(over_max, &config, &error) == ParseStatus::kError,
              "duration past the overflow-safe bound rejected");
        const std::vector<const char*> empty_engine = {"ksi_safety_edge",
                                                       "--engine", ""};
        CHECK(Resolve(empty_engine, &config, &error) == ParseStatus::kError,
              "empty --engine rejected (fail closed, not silent absent)");
        const std::vector<const char*> empty_model = {"ksi_safety_edge",
                                                      "--model-id", ""};
        CHECK(Resolve(empty_model, &config, &error) == ParseStatus::kOk &&
                  config.model_id == nullptr,
              "empty --model-id keeps the default identity");
        const std::vector<const char*> bad_deploy_arg = {
            "ksi_safety_edge", "--mop-channel", "1", "--duration-sec", "-0"};
        // "-0" parses as 0: unbounded operational service, still valid.
        CHECK(Resolve(bad_deploy_arg, &config, &error) == ParseStatus::kOk &&
                  !config.has_deadline,
              "-0 duration is the unbounded spelling, not an error");
    }

    // --- bad deploy diagnostic default fails closed when used ------------------
    {
        DeployDefaults deploy = TestDeploy();
        deploy.diag_duration_sec = 0;
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge",
                                               "--standalone"};
        CHECK(Resolve(argv, deploy, &config, &error) == ParseStatus::kError,
              "non-positive deploy diagnostic duration rejected");
        // Unused by the operational path: argvless still resolves.
        const std::vector<const char*> op = {"ksi_safety_edge"};
        CHECK(Resolve(op, deploy, &config, &error) == ParseStatus::kOk,
              "unused bad diagnostic default does not block the service");
    }

    // --- bad deploy default fails closed, never silently ----------------------
    {
        DeployDefaults deploy = TestDeploy();
        deploy.mop_channel = 70000;
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge"};
        CHECK(Resolve(argv, deploy, &config, &error) == ParseStatus::kError,
              "out-of-range deploy channel rejected");
    }

    // --- --help ----------------------------------------------------------------
    {
        AppConfig config;
        std::string error;
        const std::vector<const char*> argv = {"ksi_safety_edge", "--help"};
        CHECK(Resolve(argv, &config, &error) == ParseStatus::kHelp,
              "--help reports help");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
