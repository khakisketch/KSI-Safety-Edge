// ground_estimator: R4 person footpoint / equipment footprint estimation
// over a verified, interpolated pose, and every withhold path.
//
// The synthetic setup is a nadir camera at ENU (0, 0, 10) with intrinsics
// fx=fy=5, cx=4, cy=3 on an 8x6 frame, so pixel (u, v) projects to
// ground (10*(u-4)/5, -10*(v-3)/5) — every expected value below is that
// closed form. Covers: person footpoint (bottom-center), equipment bottom-
// edge footprint, the person->equipment distance chain, interpolation of
// the pose at the exposure time, unmapped class, ineligible ticks (pose or
// exposure unverified), near-horizon / no-intersection / bad-pixel
// refusals, determinism, and empty input.

#include "ksi_safety/spatial/ground_estimator.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "support/check.hpp"

using ksi::safety::CameraIntrinsics;
using ksi::safety::EquipmentGround;
using ksi::safety::FrameContext;
using ksi::safety::GeometryLevel;
using ksi::safety::GroundEstimator;
using ksi::safety::GroundPlaneRevision;
using ksi::safety::GroundStatus;
using ksi::safety::GroundTrackKind;
using ksi::safety::MatchResult;
using ksi::safety::MinDistancePersonEquipment;
using ksi::safety::PersonGround;
using ksi::safety::PoseQuality;
using ksi::safety::PoseReason;
using ksi::safety::Quaternion;
using ksi::safety::Sample;
using ksi::safety::SpatialEstimate;
using ksi::safety::SpatialEstimatorConfig;
using ksi::safety::SpatialObservationRecord;
using ksi::safety::SpatialWithholdReason;
using ksi::safety::TimeSourceKind;
using ksi::safety::Track;
using ksi::safety::Vec3;

namespace {

constexpr double kPi = 3.14159265358979323846;

bool Near(double a, double b, double tol) {
    return std::fabs(a - b) <= tol;
}

// 180 deg about x: camera +z -> ENU -z (nadir).
Quaternion NadirQ() {
    Quaternion q;
    q.w = 0.0;
    q.x = 1.0;
    q.y = 0.0;
    q.z = 0.0;
    return q;
}

// 90 deg about y: camera +z -> ENU +x (horizontal ray).
Quaternion HorizonQ() {
    Quaternion q;
    q.w = std::cos(kPi / 4.0);
    q.x = 0.0;
    q.y = std::sin(kPi / 4.0);
    q.z = 0.0;
    return q;
}

Sample<Quaternion> AttSample(std::int64_t src_ns, const Quaternion& q) {
    Sample<Quaternion> sample;
    sample.sample_id = 1;
    sample.seq = 1;
    sample.received.value_ns = src_ns;
    sample.received.kind = TimeSourceKind::kHostReceive;
    sample.received.domain = "host_mono";
    sample.received.verified = true;
    sample.has_source_time = true;
    sample.source_time.kind = TimeSourceKind::kDeviceSensor;
    sample.source_time.domain = "cam0";
    sample.source_time.value_ns = src_ns;
    sample.source_time.verified = true;
    sample.value = q;
    return sample;
}

Sample<Vec3> PosSample(std::int64_t src_ns, const Vec3& v) {
    Sample<Vec3> sample;
    sample.sample_id = 1;
    sample.seq = 1;
    sample.received.value_ns = src_ns;
    sample.received.kind = TimeSourceKind::kHostReceive;
    sample.received.domain = "host_mono";
    sample.received.verified = true;
    sample.has_source_time = true;
    sample.source_time.kind = TimeSourceKind::kDeviceSensor;
    sample.source_time.domain = "cam0";
    sample.source_time.value_ns = src_ns;
    sample.source_time.verified = true;
    sample.value = v;
    return sample;
}

FrameContext Ctx(std::int64_t exposure_ns, bool verified = true) {
    FrameContext ctx;
    ctx.has_exposure = true;
    ctx.exposure.kind = TimeSourceKind::kDeviceSensor;
    ctx.exposure.domain = "cam0";
    ctx.exposure.value_ns = exposure_ns;
    ctx.exposure.verified = verified;
    return ctx;
}

SpatialEstimatorConfig Config() {
    SpatialEstimatorConfig config;
    config.classes.person_class_id = 0;
    config.classes.equipment_classes.push_back({7, "truck"});
    config.intrinsics.fx = 5.0;
    config.intrinsics.fy = 5.0;
    config.intrinsics.cx = 4.0;
    config.intrinsics.cy = 3.0;
    config.intrinsics.ref_width = 8;
    config.intrinsics.ref_height = 6;
    config.ground.up_m = 0.0;
    config.min_ray_z = 0.05;
    return config;
}

Track MakeTrack(std::uint64_t id, std::uint32_t class_id, float x0, float y0,
                float x1, float y1) {
    Track track;
    track.track_id = id;
    track.class_id = class_id;
    track.box.x_min = x0;
    track.box.y_min = y0;
    track.box.x_max = x1;
    track.box.y_max = y1;
    track.hits = 1;
    track.age = 1;
    return track;
}

// Builds a source-verified MatchResult over two local samples.
MatchResult<Quaternion> AttMatch(const Sample<Quaternion>& before,
                                 const Sample<Quaternion>& after) {
    MatchResult<Quaternion> match;
    match.quality = PoseQuality::kSourceVerified;
    match.reason = PoseReason::kOk;
    match.before = &before;
    match.after = &after;
    return match;
}

MatchResult<Vec3> PosMatch(const Sample<Vec3>& before,
                           const Sample<Vec3>& after) {
    MatchResult<Vec3> match;
    match.quality = PoseQuality::kSourceVerified;
    match.reason = PoseReason::kOk;
    match.before = &before;
    match.after = &after;
    return match;
}

}  // namespace

int main() {
    const GroundEstimator estimator(Config());

    // ---- 1) person footpoint + equipment footprint + distance chain ----
    {
        const Sample<Quaternion> att_before = AttSample(1000, NadirQ());
        const Sample<Quaternion> att_after = AttSample(2000, NadirQ());
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 10.0});
        const MatchResult<Quaternion> attitude = AttMatch(att_before, att_after);
        const MatchResult<Vec3> position = PosMatch(pos_before, pos_after);
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 5.0F));  // person
        tracks.push_back(MakeTrack(2, 7, 3.0F, 1.0F, 7.0F, 5.0F));  // truck
        const SpatialEstimate estimate = estimator.Estimate(
            tracks, attitude, position, Ctx(1500), 8, 6);
        CHECK(estimate.eligible, "verified pose is eligible");
        CHECK(estimate.records.size() == 2, "one record per track");
        if (estimate.records.size() == 2) {
            const SpatialObservationRecord& person = estimate.records[0];
            CHECK(person.has_observation &&
                      person.reason == SpatialWithholdReason::kOk,
                  "person observed");
            CHECK(person.observation.kind == GroundTrackKind::kPerson &&
                      person.observation.geometry_level ==
                          GeometryLevel::kGeoEstimate,
                  "person kind + GEO_ESTIMATE (never metric verified)");
            // Bottom-center (3, 5) -> (-2, -4).
            CHECK(Near(person.observation.person_footpoint.x, -2.0, 1e-9) &&
                      Near(person.observation.person_footpoint.y, -4.0, 1e-9),
                  "person footpoint closed-form");
            const SpatialObservationRecord& equipment = estimate.records[1];
            CHECK(equipment.has_observation &&
                      equipment.observation.kind ==
                          GroundTrackKind::kEquipment &&
                      equipment.observation.equipment_class == "truck",
                  "equipment observed with class name");
            CHECK(equipment.observation.equipment_footprint.size() == 3U,
                  "footprint has three points");
            if (equipment.observation.equipment_footprint.size() == 3U) {
                const auto& points =
                    equipment.observation.equipment_footprint;
                CHECK(Near(points[0].x, -2.0, 1e-9) &&
                          Near(points[1].x, 2.0, 1e-9) &&
                          Near(points[2].x, 6.0, 1e-9) &&
                          Near(points[0].y, -4.0, 1e-9),
                      "footprint bottom edge closed-form");
            }
            // Distance chain through the existing spatial kernel.
            PersonGround person_ground;
            person_ground.status = GroundStatus::kObserved;
            person_ground.footpoint = person.observation.person_footpoint;
            EquipmentGround equipment_ground;
            equipment_ground.status = GroundStatus::kObserved;
            equipment_ground.footprint =
                equipment.observation.equipment_footprint;
            const auto relation =
                MinDistancePersonEquipment(person_ground, equipment_ground);
            CHECK(relation.has_distance && Near(relation.distance_m, 0.0, 1e-9),
                  "person on the footprint edge -> distance 0");
        }
    }
    // ---- 2) interpolation at the exposure time (position) ----
    {
        const Sample<Quaternion> att_before = AttSample(1000, NadirQ());
        const Sample<Quaternion> att_after = AttSample(2000, NadirQ());
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 20.0});
        const MatchResult<Quaternion> attitude = AttMatch(att_before, att_after);
        const MatchResult<Vec3> position = PosMatch(pos_before, pos_after);
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 5.0F));
        const SpatialEstimate estimate = estimator.Estimate(
            tracks, attitude, position, Ctx(1500), 8, 6);
        CHECK(estimate.eligible && estimate.records.size() == 1,
              "mid-bracket estimate");
        if (estimate.records.size() == 1) {
            // z = 15 at t=0.5 -> (-3, -6).
            const auto& footpoint = estimate.records[0].observation.person_footpoint;
            CHECK(Near(footpoint.x, -3.0, 1e-9) &&
                      Near(footpoint.y, -6.0, 1e-9),
                  "pose interpolated at the exposure time");
        }
    }
    // ---- 3) withhold paths: eligibility ----
    {
        const Sample<Quaternion> att_before = AttSample(1000, NadirQ());
        const Sample<Quaternion> att_after = AttSample(2000, NadirQ());
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 10.0});
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 5.0F));

        MatchResult<Quaternion> attitude = AttMatch(att_before, att_after);
        attitude.quality = PoseQuality::kReceiveNearest;
        attitude.reason = PoseReason::kCaptureTimeUnverified;
        const MatchResult<Vec3> position = PosMatch(pos_before, pos_after);
        SpatialEstimate estimate = estimator.Estimate(
            tracks, attitude, position, Ctx(1500), 8, 6);
        CHECK(!estimate.eligible && estimate.records.empty(),
              "receive-grade pose -> ineligible, no records");

        attitude = AttMatch(att_before, att_after);
        estimate = estimator.Estimate(tracks, attitude, position,
                                      Ctx(1500, /*verified=*/false), 8, 6);
        CHECK(!estimate.eligible && estimate.records.empty(),
              "unverified exposure -> ineligible");
    }
    // ---- 4) unmapped class ----
    {
        const Sample<Quaternion> att_before = AttSample(1000, NadirQ());
        const Sample<Quaternion> att_after = AttSample(2000, NadirQ());
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 10.0});
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 99, 1.0F, 1.0F, 5.0F, 5.0F));
        const SpatialEstimate estimate =
            estimator.Estimate(tracks, AttMatch(att_before, att_after),
                               PosMatch(pos_before, pos_after), Ctx(1500), 8, 6);
        CHECK(estimate.eligible && estimate.records.size() == 1 &&
                  !estimate.records[0].has_observation &&
                  estimate.records[0].reason ==
                      SpatialWithholdReason::kUnmappedClass,
              "unmapped class withheld, not fabricated");
    }
    // ---- 5) projection refusals: near horizon, no intersection, bad pixel ----
    {
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 10.0});
        const MatchResult<Vec3> position = PosMatch(pos_before, pos_after);
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 5.0F));

        const Sample<Quaternion> horizon = AttSample(1000, HorizonQ());
        const Sample<Quaternion> horizon2 = AttSample(2000, HorizonQ());
        SpatialEstimate estimate =
            estimator.Estimate(tracks, AttMatch(horizon, horizon2), position,
                               Ctx(1500), 8, 6);
        CHECK(estimate.records.size() == 1 &&
                  !estimate.records[0].has_observation &&
                  estimate.records[0].reason ==
                      SpatialWithholdReason::kProjectionRefused,
              "near-horizon ray refused");

        const Sample<Quaternion> up = AttSample(1000, Quaternion{});
        const Sample<Quaternion> up2 = AttSample(2000, Quaternion{});
        estimate = estimator.Estimate(tracks, AttMatch(up, up2), position,
                                      Ctx(1500), 8, 6);
        CHECK(estimate.records.size() == 1 &&
                  !estimate.records[0].has_observation,
              "upward ray refused (no ground intersection)");

        const Sample<Quaternion> nadir = AttSample(1000, NadirQ());
        const Sample<Quaternion> nadir2 = AttSample(2000, NadirQ());
        std::vector<Track> edge_track;
        edge_track.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 6.0F));
        estimate = estimator.Estimate(edge_track, AttMatch(nadir, nadir2),
                                      position, Ctx(1500), 8, 6);
        CHECK(estimate.records.size() == 1 &&
                  !estimate.records[0].has_observation &&
                  estimate.records[0].reason ==
                      SpatialWithholdReason::kProjectionRefused,
              "box touching the exact bottom edge refused (strict pixel rule)");
    }
    // ---- 6) determinism + empty input ----
    {
        const Sample<Quaternion> att_before = AttSample(1000, NadirQ());
        const Sample<Quaternion> att_after = AttSample(2000, NadirQ());
        const Sample<Vec3> pos_before = PosSample(1000, Vec3{0.0, 0.0, 10.0});
        const Sample<Vec3> pos_after = PosSample(2000, Vec3{0.0, 0.0, 10.0});
        std::vector<Track> tracks;
        tracks.push_back(MakeTrack(1, 0, 1.0F, 1.0F, 5.0F, 5.0F));
        const SpatialEstimate first =
            estimator.Estimate(tracks, AttMatch(att_before, att_after),
                               PosMatch(pos_before, pos_after), Ctx(1500), 8, 6);
        const SpatialEstimate second =
            estimator.Estimate(tracks, AttMatch(att_before, att_after),
                               PosMatch(pos_before, pos_after), Ctx(1500), 8, 6);
        CHECK(first.records.size() == second.records.size() &&
                  first.records.size() == 1,
              "deterministic record count");
        if (first.records.size() == 1 && second.records.size() == 1) {
            CHECK(first.records[0].observation.person_footpoint.x ==
                      second.records[0].observation.person_footpoint.x &&
                      first.records[0].observation.person_footpoint.y ==
                          second.records[0].observation.person_footpoint.y,
                  "deterministic footpoint");
        }
        const SpatialEstimate empty =
            estimator.Estimate(std::vector<Track>(),
                               AttMatch(att_before, att_after),
                               PosMatch(pos_before, pos_after), Ctx(1500), 8, 6);
        CHECK(empty.eligible && empty.records.empty(),
              "empty tracks -> eligible, no records");
    }
    if (edge_test::failures == 0) {
        std::printf("ground_estimator: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
