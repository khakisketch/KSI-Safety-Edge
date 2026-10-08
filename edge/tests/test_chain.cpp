// chain: DfineDetector -> Tracker -> SafetySession full-chain integration.
//
// A deterministic scripted fake inference (keyed by a call counter) emits
// one person-class and one equipment-class detection whose pixel boxes
// approach each other across frames and then separate. Each frame drives
// DfineDetector::Detect -> Tracker::Update (standalone id-stability check)
// -> SafetySession::Tick (ground observations per track + one proximity
// rule). Asserts: exactly one episode created at proximity, closed
// EXIT_CONFIRMED at separation, stable track ids throughout; the whole
// script run twice yields byte-identical logs (episode ids included).
// Also covers: adapter unit failure modes, mid-script inference failure
// (Detect false/empty, chain continues), and tensor size mismatch
// (false/empty).

#include "ksi_safety/perception/dfine_detector.hpp"
#include "ksi_safety/perception/tracker.hpp"
#include "ksi_safety/session/session.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

#include "support/check.hpp"

using ksi::safety::Detection;
using ksi::safety::DfineConfig;
using ksi::safety::DfineDetector;
using ksi::safety::DfineInferenceFn;
using ksi::safety::EpisodeCloseReason;
using ksi::safety::EpisodeEvent;
using ksi::safety::EpisodeEventKind;
using ksi::safety::GeometryLevel;
using ksi::safety::GroundObservation;
using ksi::safety::GroundTrackKind;
using ksi::safety::Polygon2;
using ksi::safety::RiskRule;
using ksi::safety::RuleMode;
using ksi::safety::RuleOutput;
using ksi::safety::SafetySession;
using ksi::safety::SessionConfig;
using ksi::safety::SessionOutput;
using ksi::safety::Track;
using ksi::safety::Tracker;
using ksi::safety::TrackerConfig;
using ksi::safety::Vec2;

namespace {

constexpr int kWidth = 64;
constexpr int kHeight = 48;
constexpr int kFrames = 9;
constexpr int kFailFrame = 4;  // Mid-script injected inference failure.
constexpr std::uint32_t kPersonClass = 0;
constexpr std::uint32_t kEquipClass = 1;
constexpr int kNumClasses = 3;
constexpr int kNumQueries = 4;
constexpr std::uint64_t kMs = 1000000ULL;
constexpr std::uint64_t kT0 = 1000000ULL * 1000ULL;

// Pixel-box motion: person (12x12 at y 4..16) and equipment (12x12 at
// y 30..42) approach, hold through the failure gap, then separate. Maximum
// consecutive step is 4 px (IoU 0.5), so tracker association never breaks.
constexpr float kPersonX[kFrames] = {2.0F, 4.0F, 6.0F,  8.0F, 8.0F,
                                     10.0F, 12.0F, 8.0F, 4.0F};
constexpr float kEquipX[kFrames] = {46.0F, 44.0F, 42.0F, 40.0F, 40.0F,
                                    38.0F, 36.0F, 40.0F, 44.0F};
// Ground truth phases: far, far, NEAR x4 (incl. the failure frame, which
// emits no evidence), far, far.
constexpr bool kNear[kFrames] = {false, false, true,  true, true,
                                 true,  true,  false, false};

DfineConfig MakeDfineConfig() {
    DfineConfig config;
    config.num_classes = kNumClasses;
    config.num_queries = kNumQueries;
    return config;
}

// Scripted fake runtime: ignores pixel content, emits tensors for the
// current call index. fail_frame forces inference=false; mismatch_frame
// forces wrong tensor sizes.
struct FakeRuntime {
    int call = -1;
    int fail_frame = -1;
    int mismatch_frame = -1;

    bool operator()(const float* /*input_chw*/, std::vector<float>* logits,
                    std::vector<float>* boxes) {
        ++call;
        if (call == fail_frame) {
            return false;
        }
        const int frame = call < 0 ? 0 : (call >= kFrames ? kFrames - 1 : call);
        std::vector<float> lg(static_cast<std::size_t>(kNumQueries) *
                              static_cast<std::size_t>(kNumClasses),
                              -6.0F);
        // Query 0 -> person, query 1 -> equipment, rest stay below floor.
        lg[0 * kNumClasses + kPersonClass] = 6.0F;
        lg[1 * kNumClasses + kEquipClass] = 6.0F;
        std::vector<float> bx(static_cast<std::size_t>(kNumQueries) * 4U, 0.0F);
        auto emit = [&](int query, float x_min, float y_min, float x_max,
                        float y_max) {
            bx[static_cast<std::size_t>(query) * 4U + 0] =
                (x_min + x_max) * 0.5F / static_cast<float>(kWidth);
            bx[static_cast<std::size_t>(query) * 4U + 1] =
                (y_min + y_max) * 0.5F / static_cast<float>(kHeight);
            bx[static_cast<std::size_t>(query) * 4U + 2] =
                (x_max - x_min) / static_cast<float>(kWidth);
            bx[static_cast<std::size_t>(query) * 4U + 3] =
                (y_max - y_min) / static_cast<float>(kHeight);
        };
        emit(0, kPersonX[frame], 4.0F, kPersonX[frame] + 12.0F, 16.0F);
        emit(1, kEquipX[frame], 30.0F, kEquipX[frame] + 12.0F, 42.0F);
        if (call == mismatch_frame) {
            lg.pop_back();  // Wrong size on purpose.
        }
        *logits = lg;
        *boxes = bx;
        return true;
    }
};

Polygon2 Square(double x0, double y0, double x1, double y1) {
    Polygon2 poly;
    Vec2 a;
    a.x = x0;
    a.y = y0;
    Vec2 b;
    b.x = x1;
    b.y = y0;
    Vec2 c;
    c.x = x1;
    c.y = y1;
    Vec2 d;
    d.x = x0;
    d.y = y1;
    poly.push_back(a);
    poly.push_back(b);
    poly.push_back(c);
    poly.push_back(d);
    return poly;
}

GroundObservation MakePersonObs(std::uint64_t track_id, double x, double y) {
    GroundObservation obs;
    obs.track_id = track_id;
    obs.kind = GroundTrackKind::kPerson;
    obs.geometry_level = GeometryLevel::kGeoEstimate;
    obs.person_footpoint.x = x;
    obs.person_footpoint.y = y;
    return obs;
}

GroundObservation MakeEquipObs(std::uint64_t track_id,
                               const Polygon2& footprint) {
    GroundObservation obs;
    obs.track_id = track_id;
    obs.kind = GroundTrackKind::kEquipment;
    obs.geometry_level = GeometryLevel::kGeoEstimate;
    obs.equipment_footprint = footprint;
    obs.equipment_class = "excavator";
    return obs;
}

RiskRule MakeProxRule() {
    RiskRule rule;
    rule.rule_id = "prox";
    rule.kind = ksi::safety::RuleKind::kPersonEquipmentProximity;
    rule.mode = RuleMode::kShadow;
    rule.threshold_m = 3.0;
    rule.hysteresis_m = 0.0;
    rule.enter_duration_ms = 0;
    rule.exit_duration_ms = 0;
    rule.required_geometry_level = GeometryLevel::kPixelOnly;
    return rule;
}

std::uint64_t TrackIdFor(const std::vector<Track>& tracks,
                         std::uint32_t class_id) {
    for (std::size_t i = 0; i < tracks.size(); ++i) {
        if (tracks[i].class_id == class_id) {
            return tracks[i].track_id;
        }
    }
    return 0;
}

std::string DescribeEvent(const EpisodeEvent& e) {
    std::ostringstream ss;
    ss << ksi::safety::ToString(e.kind) << "|" << e.episode_id << "|"
       << e.rule_id << "|" << (e.is_shadow ? "S" : "A") << "|";
    if (e.has_close_reason) {
        ss << ksi::safety::ToString(e.close_reason);
    } else {
        ss << "-";
    }
    ss << "|" << e.received_mono_ns << "|" << e.evidence.lower_m << ":"
       << e.evidence.upper_m;
    return ss.str();
}

struct ChainResult {
    std::vector<std::string> log;
    std::vector<EpisodeEvent> events;
    bool ids_stable = false;
};

// Drive the full chain once. Returns the event log plus collected events.
ChainResult RunChain() {
    ChainResult result;
    FakeRuntime fake;
    fake.fail_frame = kFailFrame;
    DfineDetector detector(MakeDfineConfig(), std::ref(fake));

    TrackerConfig tracker_config;
    tracker_config.iou_threshold = 0.3F;
    tracker_config.max_age_frames = 5;
    tracker_config.min_hits = 1;
    Tracker tracker(tracker_config);

    SessionConfig session_config;
    session_config.tracker = tracker_config;
    session_config.max_gap_ms = 5000;
    session_config.zone_envelope_m = 1.0;
    std::vector<RiskRule> prox(1, MakeProxRule());
    SafetySession session(session_config, prox, std::vector<RiskRule>(),
                         std::vector<ksi::safety::ZoneRevision>());
    const Polygon2 equip = Square(10.0, 10.0, 11.0, 11.0);

    std::vector<std::uint8_t> rgb(static_cast<std::size_t>(kWidth) *
                                  static_cast<std::size_t>(kHeight) * 3U);
    std::uint64_t person_id = 0;
    std::uint64_t equip_id = 0;
    bool stable = true;

    for (int frame = 0; frame < kFrames; ++frame) {
        const std::uint64_t t =
            kT0 + static_cast<std::uint64_t>(frame) * 100ULL * kMs;
        for (std::size_t i = 0; i < rgb.size(); ++i) {
            rgb[i] = static_cast<std::uint8_t>(
                (static_cast<std::size_t>(frame) * 7U + i) % 256U);
        }
        std::vector<Detection> dets;
        const bool ok = detector.Detect(rgb.data(), kWidth, kHeight, &dets);
        if (frame == kFailFrame) {
            CHECK(!ok, "chain: injected failure -> Detect false");
            CHECK(dets.empty(), "chain: injected failure -> empty out");
        } else {
            CHECK(ok, "chain: Detect true on scripted frame");
        }

        const std::vector<Detection> fed = dets;  // Empty on failure frame.
        const std::vector<Track> tracks = tracker.Update(t, fed);
        if (frame == kFailFrame) {
            // Coast through the miss: both tracks survive with stable ids.
            CHECK(tracks.size() == 2, "chain: tracker coasts the miss");
        } else {
            CHECK(tracks.size() == 2, "chain: two live tracks");
        }
        const std::uint64_t pid = TrackIdFor(tracks, kPersonClass);
        const std::uint64_t eid = TrackIdFor(tracks, kEquipClass);
        CHECK(pid != 0 && eid != 0 && pid != eid,
              "chain: both classes tracked");
        if (frame == 0) {
            person_id = pid;
            equip_id = eid;
        } else if (pid != person_id || eid != equip_id) {
            stable = false;
        }

        std::vector<GroundObservation> obs;
        if (frame != kFailFrame && kNear[frame]) {
            obs.push_back(MakePersonObs(pid, 9.5, 10.5));  // 0.5 m away.
            obs.push_back(MakeEquipObs(eid, equip));
        } else if (frame != kFailFrame) {
            obs.push_back(MakePersonObs(pid, 0.0, 0.0));  // ~14.1 m away.
            obs.push_back(MakeEquipObs(eid, equip));
        }
        SessionOutput out = session.Tick(t, fed, obs);
        CHECK(out.tracks.size() == tracks.size(),
              "chain: session tracks match standalone tracker");
        if (out.tracks.size() == tracks.size()) {
            CHECK(TrackIdFor(out.tracks, kPersonClass) == pid &&
                      TrackIdFor(out.tracks, kEquipClass) == eid,
                  "chain: session ids match standalone ids");
        }

        std::ostringstream line;
        line << "F" << frame << " T:";
        for (std::size_t i = 0; i < out.tracks.size(); ++i) {
            line << out.tracks[i].track_id << "," << out.tracks[i].epoch
                 << "," << out.tracks[i].class_id << ";";
        }
        result.log.push_back(line.str());
        for (std::size_t i = 0; i < out.proximity.size(); ++i) {
            for (std::size_t k = 0; k < out.proximity[i].events.size();
                 ++k) {
                result.events.push_back(out.proximity[i].events[k]);
                result.log.push_back("E:" +
                                     DescribeEvent(out.proximity[i].events[k]));
            }
        }
    }
    result.ids_stable = stable && person_id != 0 && equip_id != 0;
    return result;
}

int CountKind(const std::vector<EpisodeEvent>& events,
              EpisodeEventKind kind) {
    int n = 0;
    for (std::size_t i = 0; i < events.size(); ++i) {
        if (events[i].kind == kind) {
            ++n;
        }
    }
    return n;
}

}  // namespace

int main() {
    // ---- 1) adapter unit: success values ----
    {
        FakeRuntime fake;
        DfineDetector detector(MakeDfineConfig(), std::ref(fake));
        std::vector<std::uint8_t> rgb(static_cast<std::size_t>(kWidth) *
                                      static_cast<std::size_t>(kHeight) * 3U,
                                      128);
        std::vector<Detection> dets;
        CHECK(detector.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: scripted frame detects");
        CHECK(dets.size() == 2, "adapter: person + equipment only");
        if (dets.size() == 2) {
            CHECK(dets[0].class_id == kPersonClass &&
                      dets[1].class_id == kEquipClass,
                  "adapter: ascending query order");
            CHECK(std::fabs(dets[0].x_min - kPersonX[0]) < 1e-3F &&
                      std::fabs(dets[0].y_min - 4.0F) < 1e-3F &&
                      std::fabs(dets[0].x_max - (kPersonX[0] + 12.0F)) <
                          1e-3F &&
                      std::fabs(dets[0].y_max - 16.0F) < 1e-3F,
                  "adapter: person box matches scripted tensor");
            CHECK(std::fabs(dets[1].x_min - kEquipX[0]) < 1e-3F &&
                      std::fabs(dets[1].y_max - 42.0F) < 1e-3F,
                  "adapter: equipment box matches scripted tensor");
            CHECK(dets[0].score > 0.9F && dets[1].score > 0.9F,
                  "adapter: high scripted scores");
        }
    }
    // ---- 2) adapter unit: clean negative is success ----
    {
        DfineConfig config = MakeDfineConfig();
        DfineDetector detector(
            config,
            [](const float* /*in*/, std::vector<float>* logits,
               std::vector<float>* boxes) {
                logits->assign(static_cast<std::size_t>(kNumQueries) *
                                   static_cast<std::size_t>(kNumClasses),
                               -10.0F);
                boxes->assign(static_cast<std::size_t>(kNumQueries) * 4U,
                              0.5F);
                return true;
            });
        std::vector<std::uint8_t> rgb(static_cast<std::size_t>(kWidth) *
                                      static_cast<std::size_t>(kHeight) * 3U,
                                      7);
        std::vector<Detection> dets;
        CHECK(detector.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: clean negative still true");
        CHECK(dets.empty(), "adapter: clean negative empty");
    }
    // ---- 3) adapter unit: every failure mode ----
    {
        FakeRuntime fake;
        DfineDetector detector(MakeDfineConfig(), std::ref(fake));
        std::vector<std::uint8_t> rgb(static_cast<std::size_t>(kWidth) *
                                      static_cast<std::size_t>(kHeight) * 3U,
                                      1);
        std::vector<Detection> dets;
        dets.push_back(Detection());  // Poison: must be cleared on failure.
        CHECK(!detector.Detect(nullptr, kWidth, kHeight, &dets),
              "adapter: F1 null rgb");
        CHECK(dets.empty(), "adapter: F1 null rgb empties out");
        dets.push_back(Detection());
        CHECK(!detector.Detect(rgb.data(), kWidth, kHeight, nullptr),
              "adapter: F1 null out");
        CHECK(!detector.Detect(rgb.data(), 0, kHeight, &dets),
              "adapter: F2 zero width");
        CHECK(dets.empty(), "adapter: F2 empties out");
        CHECK(!detector.Detect(rgb.data(), kWidth, -3, &dets),
              "adapter: F2 negative height");
        CHECK(dets.empty(), "adapter: F2 negative empties out");

        DfineConfig bad = MakeDfineConfig();
        bad.num_classes = 0;
        DfineDetector bad_detector(bad, std::ref(fake));
        dets.push_back(Detection());  // Poison again: F3 must clear too.
        CHECK(!bad_detector.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F3 invalid config");
        CHECK(dets.empty(), "adapter: F3 empties out");

        DfineDetector empty_fn(MakeDfineConfig(), DfineInferenceFn());
        dets.push_back(Detection());  // Poison again: F4 must clear too.
        CHECK(!empty_fn.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F4 empty inference fn");
        CHECK(dets.empty(), "adapter: F4 empties out");

        FakeRuntime fail_fake;
        fail_fake.fail_frame = 0;
        DfineDetector fail_detector(MakeDfineConfig(), std::ref(fail_fake));
        dets.push_back(Detection());  // Poison again: F6 must clear too.
        CHECK(!fail_detector.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F6 inference false");
        CHECK(dets.empty(), "adapter: F6 empties out");

        FakeRuntime mismatch_fake;
        mismatch_fake.mismatch_frame = 0;
        DfineDetector mismatch_detector(MakeDfineConfig(),
                                       std::ref(mismatch_fake));
        dets.push_back(Detection());  // Poison again: F7 must clear too.
        CHECK(!mismatch_detector.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F7 logits size mismatch");
        CHECK(dets.empty(), "adapter: F7 empties out");

        DfineDetector short_boxes(
            MakeDfineConfig(),
            [](const float* /*in*/, std::vector<float>* logits,
               std::vector<float>* boxes) {
                logits->assign(static_cast<std::size_t>(kNumQueries) *
                                   static_cast<std::size_t>(kNumClasses),
                               6.0F);
                boxes->assign(3U, 0.5F);  // != queries * 4.
                return true;
            });
        CHECK(!short_boxes.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F7 boxes size mismatch");
        CHECK(dets.empty(), "adapter: F7 boxes empties out");

        DfineDetector empty_vectors(
            MakeDfineConfig(),
            [](const float* /*in*/, std::vector<float>* logits,
               std::vector<float>* boxes) {
                logits->clear();
                boxes->clear();
                return true;
            });
        dets.push_back(Detection());  // Poison: F8 must clear too.
        CHECK(!empty_vectors.Detect(rgb.data(), kWidth, kHeight, &dets),
              "adapter: F8 empty output vectors");
        CHECK(dets.empty(), "adapter: F8 empties out");
    }
    // ---- 4) full chain + determinism ----
    {
        const ChainResult first = RunChain();
        const ChainResult second = RunChain();
        CHECK(first.ids_stable, "chain: track ids stable across frames");
        CHECK(CountKind(first.events, EpisodeEventKind::kCreated) == 1,
              "chain: exactly one episode created");
        CHECK(CountKind(first.events, EpisodeEventKind::kClosed) == 1,
              "chain: exactly one episode closed");
        bool exit_confirmed = false;
        std::uint64_t episode = 0;
        bool single_id = true;
        for (std::size_t i = 0; i < first.events.size(); ++i) {
            if (i == 0) {
                episode = first.events[i].episode_id;
            } else if (first.events[i].episode_id != episode) {
                single_id = false;
            }
            if (first.events[i].kind == EpisodeEventKind::kClosed &&
                first.events[i].has_close_reason &&
                first.events[i].close_reason ==
                    EpisodeCloseReason::kExitConfirmed) {
                exit_confirmed = true;
            }
        }
        CHECK(single_id, "chain: single episode id throughout");
        CHECK(exit_confirmed, "chain: closed EXIT_CONFIRMED at separation");
        CHECK(!first.log.empty(), "chain: log non-empty");
        CHECK(first.log.size() == second.log.size(),
              "chain: identical log length");
        if (first.log.size() == second.log.size()) {
            for (std::size_t i = 0; i < first.log.size(); ++i) {
                CHECK(first.log[i] == second.log[i],
                      "chain: identical bytes incl. ids");
            }
        }
    }
    if (edge_test::failures == 0) {
        std::printf("chain: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
