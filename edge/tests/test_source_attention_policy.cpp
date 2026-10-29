#include "ksi_safety/observation/source_attention_policy.hpp"
#include "app_config.hpp"
#include "support/check.hpp"

#include <limits>
#include <string>
#include <vector>

using namespace ksi::safety;
using namespace ksi_safety_edge_app;
int main() {
    const std::string good = "class=0,targets=64,jobs=1,bytes=524288,repeat_ms=5000,lost_ms=1000,wait_ms=2000,retry_ms=500";
    SourceAttentionPolicy p;
    std::string error;
    CHECK(ParseSourceAttentionPolicy(good, &p, &error), "explicit simulated policy parses");
    CHECK(p.enabled && p.repeat_ns == 5000000000LL && p.max_targets == 64, "values retained");
    CHECK(observation::ValidatePlannerConfig(SourceAttentionPlannerConfig(p), &error), "same planner validation");
    const std::vector<std::string> bad = {
        "", good + ",", good + ",class=1", good + ",extra=1", "," + good,
        "class=0", good + " ", std::string(1025, '1'),
        "class=-1" + good.substr(7), "class=2147483648" + good.substr(7),
        "class=4294967296" + good.substr(7), "class=+0" + good.substr(7),
        "class=0x0" + good.substr(7), "class=0.0" + good.substr(7),
        "class=0,," + good.substr(8)};
    for (const auto& text : bad) {
        CHECK(!ParseSourceAttentionPolicy(text, &p, &error) && !p.enabled && !error.empty(),
              "malformed policy rejected and output disabled");
    }
    for (const auto& pair : std::vector<std::pair<std::string, std::string>>{
            {"targets=64", "targets=4097"}, {"targets=64", "targets=0"},
            {"jobs=1", "jobs=65"}, {"jobs=1", "jobs=0"},
            {"bytes=524288", "bytes=16777217"}, {"bytes=524288", "bytes=0"},
            {"repeat_ms=5000", "repeat_ms=3600001"}, {"repeat_ms=5000", "repeat_ms=0"},
            {"wait_ms=2000", "wait_ms=9223372036854775807"},
            {"retry_ms=500", "retry_ms=18446744073709551616"}}) {
        auto text = good;
        text.replace(text.find(pair.first), pair.first.size(), pair.second);
        CHECK(!ParseSourceAttentionPolicy(text, &p, &error), "over-limit/overflow refused");
    }
    CHECK(ParseSourceAttentionPolicy("retry_ms=1,wait_ms=1,lost_ms=1,repeat_ms=3600000,bytes=1,jobs=1,targets=1,class=0", &p, &error),
          "key order independent and duration boundary valid");
    CHECK(!ParseSourceAttentionPolicy(good, nullptr, &error), "null output refused");
    p.max_targets = std::numeric_limits<std::size_t>::max();
    CHECK(!IsValidSourceAttentionPolicy(p, &error), "programmatic oversized input refused before cast");
    DeployDefaults deploy;
    deploy.source_attention_policy = good.c_str();
    AppConfig app;
    const char* argv[] = {"edge"};
    CHECK(ResolveAppConfig(1, argv, deploy, &app, &error) == ParseStatus::kError,
          "argvless policy cannot silently use volatile evidence");
    deploy.autonomy_evidence_dir = "/tmp/opencode/policy-test-only";
    CHECK(ResolveAppConfig(1, argv, deploy, &app, &error) == ParseStatus::kOk &&
          app.source_attention.enabled && !app.auto_process && !app.has_deadline,
          "explicit deploy policy preserves operational command gate");
    const char* cli[] = {"edge", "--autonomy-evidence-dir", "/tmp/opencode/policy-test-only",
                          "--source-attention-policy", good.c_str()};
    CHECK(ResolveAppConfig(5, cli, DeployDefaults{}, &app, &error) == ParseStatus::kOk,
          "CLI uses exact same parser");
    const char* zero_capacity[] = {"edge", "--autonomy-capacity-bytes", "0"};
    CHECK(ResolveAppConfig(3, zero_capacity, deploy, &app, &error) == ParseStatus::kError,
          "enabled attention cannot silently use unbounded storage");
    const char* empty_dir[] = {"edge", "--autonomy-evidence-dir", ""};
    CHECK(ResolveAppConfig(3, empty_dir, deploy, &app, &error) == ParseStatus::kError,
          "empty explicit evidence directory rejected");
    const char* missing[] = {"edge", "--source-attention-policy"};
    CHECK(ResolveAppConfig(2, missing, DeployDefaults{}, &app, &error) == ParseStatus::kError,
          "missing CLI value refused");
    return edge_test::failures == 0 ? 0 : 1;
}
