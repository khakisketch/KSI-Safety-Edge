# KSI Safety Edge — minimal Edge core (`edge/`)

Minimal C++17 Edge core: bounded frame ingress (ported SI `frame_queue`),
timestamp provenance, per-topic sample rings, frame↔telemetry matching with
graded pose quality, verified-bracket pose interpolation, and a perception
pipeline that keeps detection independent of alignment grades.

No external dependencies beyond the C++ standard library + Threads.
No network, no GPU, no PSDK, no database.

## Build / run

Requires CMake ≥ 3.16 and a C++17 compiler (tested: GCC 13.3, CMake 3.28).

```sh
cmake -S edge -B edge/build -DKSI_SAFETY_EDGE_ENABLE_SANITIZERS=OFF
cmake --build edge/build -j4
ctest --test-dir edge/build --output-on-failure

# Sanitizer build (ASan + UBSan, -fno-sanitize-recover=all, Debug):
cmake -S edge -B edge/build-asan -DCMAKE_BUILD_TYPE=Debug -DKSI_SAFETY_EDGE_ENABLE_SANITIZERS=ON
cmake --build edge/build-asan -j4
ctest --test-dir edge/build-asan --output-on-failure
```

Build dirs (`edge/build*/`) are git-ignored.

## Layout

| Path | Contents |
|---|---|
| `include/ksi_safety/capture/frame_queue.hpp`, `src/capture/frame_queue.cpp` | Bounded RGB ingress, extracted from SI `manifold-runtime` with minimal diffs (namespace `ksi::edge` → `ksi::safety`, include path, guard) + provenance header. `FrameMetadata` fields unchanged. |
| `include/ksi_safety/time/provenance.hpp`, `src/time/provenance.cpp` | `ClockRegistry`: explicit per-(domain, kind) verification; `Make()` never upgrades kind. |
| `include/ksi_safety/time/topic_ring.hpp` (+ `src/time/topic_ring.cpp` compile-check TU) | `TopicRing<T>`: duplicate `sample_id` → `kDuplicate`; `received` strictly decreasing → `kClockRollback`; capacity overwrite drops oldest; receive-nearest (ties → newest); source-time bracket only when both sides exist. |
| `include/ksi_safety/time/matching.hpp`, `src/time/matching.cpp` | `MatchFrame()` ordered policy: `kNone` → `kReceiveNearest` → `kSourceVerified`. A receive-proximity result is NEVER `kSourceVerified`. |
| `include/ksi_safety/time/interpolation.hpp`, `src/time/interpolation.cpp` | SLERP/LERP/`WrapDegrees`; `InterpolatePose()` refuses unless both samples have verified source times in the SAME domain, ordered bracket, interval ≤ max, target inside. |
| `include/ksi_safety/perception/detector.hpp` | `DetectorPort` + `Detection`/`ModelProfile` types. |
| `include/ksi_safety/perception/pipeline.hpp`, `src/perception/pipeline.cpp` | `PerceptionPipeline::Process()`: detector called EXACTLY ONCE per frame (null-safe); detections + model id/digest always recorded; pose from `MatchFrame()`; `spatial_eligible = (quality == kSourceVerified)`. No spatial math here. |
| `include/ksi_safety/geometry/coordinate.hpp`, `src/geometry/coordinate.cpp` | `Rotate` (quaternion, internally normalized) / `NedToEnu` / `EnuToNed` / `Wgs84ToLocalEnu` (equirectangular local approximation, R = 6371008.8 m). |
| `include/ksi_safety/geometry/projection.hpp`, `src/geometry/projection.cpp` | Pinhole `ProjectPixelToGround()`: pose is GIVEN in ENU (`CameraPoseEnu`; composition from raw telemetry topics is the deferred PSDK adapter's job). Accepts only `kSourceVerified` poses, else `kPoseUnverified`. |
| `include/ksi_safety/spatial/distance.hpp`, `src/spatial/distance.cpp` | 2D ground-plane kernels: point↔segment, containment (boundary = inside), point↔polygon, polygon↔polygon, polygon↔segment. |
| `include/ksi_safety/spatial/relation.hpp`, `src/spatial/relation.cpp` | Person footpoint ↔ equipment footprint minimum distance (`MinDistancePersonEquipment`); point/polygon ↔ zone relations with an uncertainty envelope (`RelatePointToZone`, `RelatePolygonToZone`). |
| `tests/` | One `ctest` binary per group (`capture_queue`, `provenance`, `ring`, `matching`, `interpolation`, `perception`, `geometry`, `spatial`), plain `CHECK(cond, msg)` style, nonzero exit on failure. |

## The core rule (why this exists)

Receive-time proximity is not capture-time alignment. A frame with no
verified exposure timestamp grades at most `kReceiveNearest /
kCaptureTimeUnverified`, no matter how dense the telemetry ring is or how
small the receive gap is (see the 400 Hz regression test in
`tests/test_matching.cpp`). Only a verified exposure bracketed by verified
source times earns `kSourceVerified`, and only that grade sets
`spatial_eligible`.

## Scope / limits (honest)

- Host-logic only. Verified here: bounded-queue contract, provenance gating,
  ring/matching/interpolation math, pipeline binding — all on synthetic data
  via `ctest`, plus ASan/UBSan clean on this host.
- NOT verified here: any real device clock, PSDK capture path, TensorRT/GPU
  inference, WAL/store-forward persistence, flight/mission integration, or
  on-aircraft behavior. No such claim is made by this code or its tests.
- `TopicRing<PoseValue>` is a single-pose-type stand-in. Per-topic rings (one
  ring per telemetry topic) replace it when the PSDK adapter lands; the
  `MatchFrame<T>` template already supports that shape.
- `try_lock` contention (`kQueueBusy`) paths are contract-documented but not
  stress-tested (tests are single-threaded).
- `MatchFrame()` brackets only by verified source times **in the frame
  exposure's own clock domain**; verified samples from another domain yield
  `kReceiveNearest / kDomainMismatch`, and `InterpolatePose()` repeats the
  same-domain requirement one stage later. Two individually verified clocks
  are not a common time axis.
- `FrameMetadata.sensor_timestamp_ns == 0` still means "no sensor evidence"
  (inherited SI semantics) and never counts toward verification.

## Geometry / spatial scope / limits (honest)

- Synthetic ground truth only. Verified here: rotation/frame conversions,
  pinhole-to-flat-plane projection math, and 2D distance/zone relations — all
  on analytic synthetic data via `ctest` (nadir/oblique closed-form hits,
  corner/edge distances, envelope boundaries), plus ASan/UBSan clean on this
  host.
- SHADOW-only. No physical capture-time verification exists yet, so under the
  workstream principles nothing here may drive real position-based warnings:
  projection refuses anything below `kSourceVerified`, and relation outputs
  carry no warning semantics on their own.
- No lens distortion, single flat ground plane (`GroundPlaneRevision.up_m`),
  no multi-level terrain, no uncertainty-interval calibration — the interval
  stays null until measured.
- `Wgs84ToLocalEnu()` is a local approximation for offsets ≤ ~1 km around the
  anchor (error grows with distance; see header). Wider areas need a proper
  projection (deferred).
- Pose composition from raw telemetry topics into `CameraPoseEnu` is deferred
  to the PSDK adapter; here the ENU pose is an input, not a derivation.
