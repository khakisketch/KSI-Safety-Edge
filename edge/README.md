# KSI Safety Edge — minimal Edge core (`edge/`)

Minimal C++17 Edge core: bounded frame ingress (ported SI `frame_queue`),
timestamp provenance, per-topic sample rings, frame↔telemetry matching with
graded pose quality, verified-bracket pose interpolation, a perception
pipeline that keeps detection independent of alignment grades, and the R1
shared session runner + replay inputs — the PSDK application and the Replay
driver run the SAME processing path; only the input source differs.

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
| `include/ksi_safety/perception/detector.hpp`, `src/perception/detector.cpp` | `DetectorPort` + `Detection`/`DetectionStatus`/`DetectionBatch`/`ModelProfile`. `DetectionStatus` separates "no attempt" (`NOT_RUN`: cadence skip / no detector) from "attempt failed" (`INFERENCE_FAILURE`: an observation gap) from "clean negative" (`OK` with empty detections: real evidence). Consumers must never treat the first two as "no objects present". |
| `include/ksi_safety/perception/pipeline.hpp`, `src/perception/pipeline.cpp` | `PerceptionPipeline::Process()`: detector called EXACTLY ONCE per frame (null-safe); detections + model id/digest always recorded; pose from `MatchFrame()`; `spatial_eligible = (quality == kSourceVerified)`. No spatial math here. |
| `include/ksi_safety/perception/dfine.hpp`, `src/perception/dfine.cpp` | D-FINE input preprocessing + output decode (no inference): **PIL-compatible resample** to 640×640 (triangle weights over a max(1, scale) support — antialias-scaled on downscale — with PIL's uint8 intermediate quantization; golden values from actual PIL pinned in `tests/test_dfine.cpp`; identity-size fast path), no letterbox (`do_pad=false`), rescale /255, RGB→CHW float32; decode = per-query sigmoid + argmax, strict `score > threshold` from `DfineConfig`, normalized cxcywh → source-pixel xyxy, NMS-free fixed queries, ascending query order. Raw COCO label id; no ontology mapping. |
| `include/ksi_safety/perception/dfine_detector.hpp`, `src/perception/dfine_detector.cpp` | `DfineDetector` runtime adapter (injected inference only): `DfineInferenceFn` takes preprocessed 1×3×640×640 CHW float32 and fills `[queries × classes]` logits + `[queries × 4]` boxes; `Detect()` runs preprocess → inference → `DecodeDfine`, fail-closed (bad args / invalid config / empty fn / preprocess refusal / inference false / tensor size mismatch → false + empty out, never partial). Standalone by design: the thin `DetectorPort` wrapper is `dfine_detector_port.hpp` (next row), so this module stays RGB→Detection maths. |
| `include/ksi_safety/perception/dfine_detector_port.hpp`, `src/perception/dfine_detector_port.cpp` | `DfineDetectorPort`: binds `DfineDetector` to `DetectorPort` for the session runner. Success → `kOk` (empty = clean negative, still evidence); every failure path (preprocess refusal F1–F5 / runtime failure F6 / size mismatch F7 / malformed frame) → `kInferenceFailure` with no detections. Stateless bridge: no thresholds, no clock, no I/O. |
| `include/ksi_safety/runtime/session_runner.hpp`, `src/runtime/session_runner.cpp` | `SessionRunner` (R1): the product processing path shared by the PSDK app and the Replay driver — detection cadence gate (`detect_every_n_frames`; skipped ticks are `NOT_RUN`), detector called at most once per tick and never gated by telemetry, `MatchFrame` on the attitude + position rings, `SafetySession::Tick` only on `kOk` ticks (clean negatives advance tracker misses; skip/failure ticks carry state unchanged), gap `Sweep` every tick. `TickRecord` carries detection status/detections, both match grades, `spatial_eligible` (both rings `SOURCE_VERIFIED`), `TrackRecord`s with `OBSERVED`/`MISSED`/`HELD` provenance, model id/digest, and session outputs. No spatial math, no ACTIVE activation. |
| `include/ksi_safety/replay/replay_manifest.hpp`, `src/replay/replay_manifest.cpp` (+ `frame_source`, `telemetry_source`) | Replay inputs (R1): strict manifest v1 parser (frames + pose/position samples in arrival order + clock-registry `verify` lines; failures name their line), `ReplayFrameSource` (raw RGB files, exactly `w*h*3`; fills the SAME `FramePacket`/`FrameContext` the PSDK capture path produces — `has_exposure=false`, `sensor_timestamp_ns=0`), `ReplayTelemetrySource::PushDue(now)` (delivers only samples with receive time ≤ now — a frame can never see a future sample; ring verdicts counted). |
| `include/ksi_safety/perception/tracker.hpp`, `src/perception/tracker.cpp` | Deterministic per-class greedy IoU tracker v0: descending-IoU match at `iou_threshold` (ties → lower track_id, then detection order), same-class only, unmatched detections start monotonic never-reused ids, unmatched tracks coast up to `max_age_frames` then drop, re-association keeps the id, `confirmed = hits >= min_hits`, `Update()` returns every live track sorted by id, `Reset()` bumps `tracker_epoch` and drops all tracks. |
| `include/ksi_safety/geometry/coordinate.hpp`, `src/geometry/coordinate.cpp` | `Rotate` (quaternion, internally normalized) / `NedToEnu` / `EnuToNed` / `Wgs84ToLocalEnu` (equirectangular local approximation, R = 6371008.8 m). |
| `include/ksi_safety/geometry/projection.hpp`, `src/geometry/projection.cpp` | Pinhole `ProjectPixelToGround()`: pose is GIVEN in ENU (`CameraPoseEnu`; composition from raw telemetry topics is the deferred PSDK adapter's job). Accepts only `kSourceVerified` poses, else `kPoseUnverified`. |
| `include/ksi_safety/spatial/distance.hpp`, `src/spatial/distance.cpp` | 2D ground-plane kernels: point↔segment, containment (boundary = inside), point↔polygon, polygon↔polygon, polygon↔segment. |
| `include/ksi_safety/spatial/relation.hpp`, `src/spatial/relation.cpp` | Person footpoint ↔ equipment footprint minimum distance (`MinDistancePersonEquipment`); point/polygon ↔ zone relations with an uncertainty envelope (`RelatePointToZone`, `RelatePolygonToZone`). |
| `include/ksi_safety/risk/policy.hpp`, `src/risk/policy.cpp` | `RiskRule` mirroring `risk-policy.v1` rule fields (kind/mode/threshold/hysteresis/enter/exit/required geometry/class + revision filters); contract `ToString()`s. |
| `include/ksi_safety/risk/episode.hpp`, `src/risk/episode.cpp` | Episode states/events/close reasons (`EXIT_CONFIRMED` / `EXIT_NOT_OBSERVED` / `OBSERVATION_LOST` / `SESSION_END`) + compact evidence summary. |
| `include/ksi_safety/risk/engine.hpp`, `src/risk/engine.cpp` | `RiskEngine`: per-rule OFF/applicability/geometry/ACTIVE guards, interval + zone evidence classification, doc 17 §3 episode state machine per (rule, lineage) with explicit `max_gap_ms`, `Tick()` gap sweep, `EndSession()`. |
| `include/ksi_safety/session/session.hpp`, `src/session/session.cpp` | `SafetySession`: synthetic orchestrator wiring one tracker + `MinDistancePersonEquipment` / point-polygon zone relations into TWO risk engines (proximity + zone; see two-engine note below). Caller-provided ground observations per track per tick; `Tick()` / gap `Sweep()` / `EndSession()` / `ResetTracker()` (epoch split, no cross-epoch merge). |
| `include/ksi_safety/perception/tensorrt_runtime.hpp`, `src/perception/tensorrt_runtime.cpp` | `TensorRtRuntime`: serialized-engine adapter behind `DfineInferenceFn` (`Run` matches the injected-inference signature, so `DfineDetector` decodes engine outputs into `Detection`s with unchanged preprocess/decode). Validates bindings `pixel_values` 1×3×640×640 → `logits` 1×300×80 + `pred_boxes` 1×300×4 (float32 I/O); device buffers allocated once; H2D → enqueueV3 → sync → D2H; fail-closed T1–T8 (false + empty outputs, never partial, never throws). `RunTimed` (W9-FU) adds per-stage `StageTimesMs` (h2d/enqueue/sync/d2h) with sync-serialized attribution — its sum can differ from the pipelined `Run` mean (see header caveat). Stub build (`KSI_SAFETY_EDGE_ENABLE_TENSORRT=OFF`, default): `Available()==false`, no TRT/CUDA dependency. No `DetectorPort` wrapper (see `dfine_detector.hpp` rationale); no spatial/session wiring. |
| `tools/dfine_trt_smoke.cpp` | `dfine_trt_smoke` (built only with TRT ON): real-image smoke — `--engine PATH --input PATH --expected PATH [--iters N] [--breakdown] [--breakdown-iters N]`; gates expected lines with score ≥ 0.2 (top-1 always gates) vs. same-class top-12 C++ detections at IoU ≥ 0.5 and \|Δscore\| ≤ 0.05; tail lines (score < 0.2) reported only; reports mean/p95 latency, qps, VmRSS before/after; with `--breakdown` also prints a `breakdown_ms ...` per-stage line + `breakdown_hint` dominant-stage line (W9-FU); `SMOKE_RESULT PASS\|FAIL`. |
| `tools/preprocess_dump.cpp` | `preprocess_dump` (host tool, no TensorRT): `--input PATH --width W --height H [--out PATH]` runs `PreprocessRgb` on raw RGB and writes the CHW float32 tensor; used by `scripts/check_resize_fidelity.py` to compare the actual C++ output against PIL/RTDetrImageProcessor. |
| `tools/replay_run.cpp` | `replay_run` (host + device): `--manifest PATH [--out PATH] [--null-detector] [--engine PATH] [--model-id ID] [--model-digest D] [--detect-every N] [--max-receive-gap-ms N] [--max-bracket-ms N] [--tracker-iou F] [--tracker-max-age N] [--tracker-min-hits N] [--session-gap-ms N] [--zone-envelope-m F] [--flight-id ID] [--boot-id ID] [--stream-id ID]` — drives the shared `SessionRunner` from a replay manifest and writes one NDJSON tick line + a final stat line. `--engine` requires a TRT build. `scripts/make_replay_frames.py` (host, Pillow) converts images to raw RGB + manifest for it. |
| `tests/` | One `ctest` binary per group (`capture_queue`, `provenance`, `ring`, `matching`, `interpolation`, `perception`, `dfine`, `tracker`, `geometry`, `spatial`, `risk`, `session`, `chain`, `replay`, `tensorrt`), plain `CHECK(cond, msg)` style, nonzero exit on failure. |

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

## D-FINE preprocessing/decode scope / limits (honest)

- No ONNX/TensorRT execution here: `DecodeDfine()` consumes
  caller-supplied logits/boxes tensors. Decode semantics (sigmoid, per-query
  argmax, strict threshold from `DfineConfig`, cxcywh→xyxy scaling, query
  order) are validated against constructed tensors only; conformance against
  real D-FINE model outputs (byte-identical vs. the SI Python reference on
  recorded frames) comes later and is NOT claimed here.
- Resampling is PIL-compatible (W10): triangle weights over a support of
  max(1, scale) (antialias-scaled on downscale), window clipped and
  renormalized, horizontal intermediate quantized to uint8 like PIL's 8bpc
  pipeline. Golden tests pin actual PIL values;
  `scripts/check_resize_fidelity.py` (with `preprocess_dump`) measures
  end-to-end tensor/detection fidelity vs PIL on real assets (residual
  ≤ 1 uint8 level on the 2026-10-09 run).
- No letterbox: `do_pad=false` means a direct 640×640 resize that does not
  preserve aspect ratio (documented in `dfine.hpp` R7).
- Boxes are not clamped and labels are raw COCO indices (`class_name` left
  empty): clipping and ontology mapping stay out of scope (mirroring the SI
  split between `dfine.py` and `DetectionAdapter`). Fail-closed drops now
  live in `DfineDetector` (see its F1–F8 list); clipping/mapping remain
  future work.

## Tracker scope / limits (honest)

- v0 greedy IoU only: no motion model (Kalman), no appearance features, no
  global assignment optimisation. ID-switch/occlusion metrics are later work;
  this module only guarantees deterministic association semantics, not
  tracking quality.
- Synthetic-only verification. Verified here: same-id continuity, the `>=`
  threshold boundary, coast + same-id re-association, removal after
  `max_age_frames` with id non-reuse, class separation, `min_hits` gating,
  tie-break order, determinism, `Reset()` epoch semantics, empty input,
  invalid-config fail-closed, and one `DecodeDfine` → tracker integration
  case — all on constructed boxes/tensors via `ctest`, plus ASan/UBSan clean
  on this host.
- SHADOW-only context. Tracker output feeds `TrackLineage`
  (`tracker_epoch`, person/equipment ids) for the risk engine; episodes never
  merge across epochs, and nothing here is validated for real position-based
  warnings. No real-video, persistence/replay, or multi-camera behaviour is
  claimed.
- `class_name` and `score` do not participate in association and are not
  stored; only `class_id` scopes matching. Timestamps are caller data copied
  verbatim into `last_seen_ns` (no monotonicity enforcement, no clock read).

## Risk/episode scope / limits (honest)

- Synthetic inputs only. Verified here: contract enum strings, per-rule
  guards (OFF skip, equipment-class / zone-revision applicability, geometry
  gate, ACTIVE-without-grant degrades to SHADOW with zero active outputs),
  interval classification with hysteresis (`upper < threshold` near,
  `lower > threshold + hysteresis` far, straddle stays unknown), zone
  verdict mapping (`kInside`/`kOutside` near/far, `kBoundaryUncertain`
  never confirms), and the doc 17 §3 machine (single create per episode,
  enter/exit persistence with 0 = immediate, gap handling, lineage split on
  tracker reassignment, deterministic ids) — all via `ctest`, plus
  ASan/UBSan clean on this host.
- SHADOW-only. `METRIC_VERIFIED` geometry is caller-asserted, not measured
  here, so under the workstream principles nothing here may drive real
  position-based warnings; ACTIVE output additionally requires an explicit
  per-frame activation grant.
- NOT verified here: real distance-measurement accuracy, physical zone
  polygons, tracker behaviour, persistence/replay of episodes, or any
  downstream alerting. Thresholds/durations come only from the policy input
  (nothing is hardcoded); a rule misconfiguration (e.g. ACTIVE without
  `METRIC_VERIFIED`) degrades to SHADOW rather than emitting.
- Gap semantics: unknown/withheld frames never move `last_valid_evidence`,
  so a long uncertain stretch still ends in `OBSERVATION_LOST`, never an
  automatic "safe"; far evidence after a gap closes `EXIT_NOT_OBSERVED`,
  never `EXIT_CONFIRMED`. Geometry-insufficient frames step the machine as
  unknown but can never confirm or close an episode.

## TensorRT runtime + smoke (W9)

Off by default (host builds stay dependency-free):

```sh
# Host: stub build, all 14 groups incl. tensorrt run green.
cmake -S edge -B edge/build -DKSI_SAFETY_EDGE_ENABLE_SANITIZERS=OFF
cmake --build edge/build -j4
ctest --test-dir edge/build --output-on-failure

# Device (Jetson, TRT 8.5 + CUDA 11.4): real backend + smoke tool.
cmake -S . -B build -DKSI_SAFETY_EDGE_ENABLE_TENSORRT=ON
cmake --build build -j4
ctest --output-on-failure
./dfine_trt_smoke --engine /open_app/ksi-dev/dfine-export-20261008/dfine-s-coco.fp16.op16.engine \
  --input /open_app/ksi-dev/dfine-export-20261008/trt-inputs/single_person.rgb640.bin \
  --expected /open_app/ksi-dev/dfine-export-20261008/trt-inputs/single_person.expected.txt \
  --iters 200
```

`KSI_SAFETY_EDGE_ENABLE_TENSORRT=ON` finds `NvInfer.h` (hint
`/usr/include/aarch64-linux-gnu`) and `nvinfer` + `cudart` (hints
`/usr/local/cuda-11.4`, `/usr/lib/aarch64-linux-gnu`) and stops with
`FATAL_ERROR` if any is missing.

### Per-stage latency breakdown (W9-FU, user-requested measurement)

```sh
./dfine_trt_smoke --engine ...engine --input ...bin --expected ...txt \
  --iters 100 --breakdown [--breakdown-iters 50]
```

Off by default; `--breakdown-iters N` (default 50) sets the breakdown
loop count. After the normal loop the tool runs
`PreprocessRgb` → `TensorRtRuntime::RunTimed` → `DecodeDfine` and prints:

```text
breakdown_ms preprocess=X h2d=Y enqueue=Z sync=W d2h=V decode=U total=SUM
breakdown_hint dominant_stage=S note=...
```

Interpretation caveats (read before quoting numbers):

- Per-stage sync serialization: `RunTimed` serializes the stages
  (synchronous H2D, sync-after-enqueue, synchronous D2H) so each number
  is attributable to its stage, while pipelined `Run` overlaps launch
  latencies behind one terminal synchronize. The stage SUM can therefore
  differ from the `latency_ms` mean in either direction — on the W9-FU
  Jetson run it came out ~6–7% below the pipelined mean (the breakdown
  loop skips `DfineDetector` overhead and timing conditions differ run to
  run); treat the stages as attribution, not a total guarantee.
- `enqueue` vs `sync` split: `enqueue` times the `enqueueV3` submit
  call, `sync` the wait after it. Whether the submit returns immediately
  (GPU work observed in `sync`) or blocks until kernels complete (work
  observed in `enqueue`, `sync` residual) depends on backend/stream
  behavior — on the W9-FU Jetson run `enqueueV3` blocked (~20 ms with
  `sync` ~0.5 ms), so read GPU compute as `enqueue + sync` combined.
- GPU async: host-side `steady_clock` around an async call measures
  launch, not work — that is why `RunTimed` uses synchronous copies
  and an explicit sync point instead of timing the pipelined path.
- `preprocess`/`decode` are host CPU stages (bilinear resize + /255;
  sigmoid + argmax over 300×80); they move with CPU load, not the GPU.

## TensorRT scope / limits (honest)

- Engine/inputs are pre-staged device artifacts
  (`/open_app/ksi-dev/dfine-export-20261008/`): the engine
  (`dfine-s-coco.fp16.op16.engine`), the raw-RGB inputs, and the
  Python/ORT reference expectation files live there read-only. This repo
  tracks no weights, no images, no reference outputs.
- Correctness scope is reference comparison only: C++ detections vs. the
  ORT reference at IoU ≥ 0.5 and |Δscore| ≤ 0.05 on the staged corpus
  frames. Gating lines are expected detections with score ≥ 0.2 (the top-1
  reference line always gates); tail lines (score < 0.2) are reported but
  never gate. Rationale: the fp16 product engine is numerically exact at
  high confidence, but its noise floor shifts low scores and re-ranks the
  top-12 tail — gating all 12 lines would fail on precision noise, not on
  transport or decode errors. Operating thresholds are policy-driven later;
  high-confidence detections are the acceptance bar here. No
  detection-quality, mAP, or robustness claim is made.
- No spatial/session wiring in this milestone: the adapter stops at the
  `Detection` contract (`DfineDetector` → `vector<Detection>`). Feeding
  tracker/session/risk from live engine output is later work.
- No full-pipeline 60 fps claim: `--iters` measures `Detect()` (preprocess +
  engine + decode) latency/qps plus process VmRSS before/after. Thermal
  soak and long-run stability are deferred to H1-6.
- One `IExecutionContext` per `TensorRtRuntime`: concurrent `Run()` on one
  object needs external synchronization. Engine I/O must be float32
  (fp16-internal compute is fine; half I/O is rejected, not converted).

## Chain scope / limits (honest)

- Host-synthetic full chain. Verified here: scripted `DfineDetector`
  (injected fake inference, 64×48 RGB) → standalone `Tracker` id stability
  → `SafetySession::Tick` (caller-built ground observations + one
  3.0 m proximity rule) producing exactly one episode created at proximity
  and closed `EXIT_CONFIRMED` at separation, stable ids throughout, two
  identical runs → byte-identical logs; plus mid-script inference failure
  (Detect false/empty, chain coasts without crash) and tensor size-mismatch
  (false/empty) — all via `ctest`, plus ASan/UBSan clean on this host.
- Injected runtime only — no ONNX/TensorRT execution here. The fake
  inference ignores pixel content (call-counter-keyed tensors); no claim is
  made about real D-FINE weights, real frames, or detection quality. The
  real runtime adapter (ONNX-gated execution behind the same
  `DfineInferenceFn` boundary) is the next step.
- SHADOW-only. The chain inherits every limit above (synthetic ground
  truth, point-distance proximity, no pose composition, no footpoint
  estimation); nothing here may drive real position-based warnings.

## Session scope / limits (honest)

- Synthetic-only orchestration. Verified here: proximity approach → single
  `NEAR` create → separation → `EXIT_CONFIRMED`; zone inside → create,
  envelope-boundary frames never confirm, leave → `EXIT_CONFIRMED`,
  `zone_revision_ids` filtering (`NOT_APPLICABLE` + `ZoneRevisionNotListed`
  for non-listed revisions); observation loss → `OBSERVATION_LOST` (never
  `EXIT_CONFIRMED`); tracker reset → old episode lost, new-epoch evidence
  mints a new id (no merge); below-required geometry → `WITHHELD` with no
  episodes; two identical scripts → byte-identical logs — all via `ctest`,
  plus ASan/UBSan clean on this host.
- SHADOW-only. The session never grants activation, so ACTIVE rules degrade
  to effective SHADOW; nothing here may drive real position-based warnings.
- Ground points are caller-provided. Pose composition from telemetry and
  footpoint/footprint estimation from pixels are NOT included — the session
  consumes `GroundObservation`s keyed by tracker `track_id` and computes
  from them. Duplicate observations for one id in one tick resolve last-wins;
  observations for non-live ids are ignored.
- Point distance only. The proximity interval is the point distance on both
  ends (`lower_m == upper_m`); no uncertainty-interval expansion is applied.
- Two-engine composition. `RiskEngine::Evaluate` runs every rule on one
  frame, so proximity and zone rules live in separate engines; a pair-frame
  never touches a zone rule and a zone-frame never touches a proximity rule.
- Multi-revision per-tick caveat. Episode state is keyed per (rule,
  lineage), not per revision: one zone rule fed several candidate revisions
  in a tick steps a single lineage through each revision in configured zone
  order (last frame wins within the tick). Prefer one revision per zone rule
  when per-revision episodes matter.
- `Sweep()`/`EndSession()` carry no track snapshot (tracker state untouched).

## Session runner + replay (R1) scope / limits (honest)

- ONE product processing path. `SessionRunner` (cadence gate → detector →
  matching → session step) is driven by BOTH the PSDK application and the
  Replay driver; only the input source differs. `tests/test_replay.cpp`
  proves the PSDK-style `BoundedFrameQueue` path and the direct path produce
  byte-identical `TickRecord`s for identical inputs.
- Evidence semantics are pinned: `NOT_RUN` (cadence skip / no detector) and
  `INFERENCE_FAILURE` never touch the tracker — their tracks record `HELD`
  provenance (last state carried; NOT a new measurement and NOT motion
  tracking) — while a clean negative (`OK`, empty) advances misses
  (`MISSED`) and matched detections record `OBSERVED` with
  `since_observed_ns`. A control test shows the same sequence without the
  failure drops one tick earlier.
- Replay frames carry no exposure time (`has_exposure=false`,
  `sensor_timestamp_ns=0`): grades stay `RECEIVE_*` and `spatial_eligible`
  is false in replay v1 (it requires BOTH attitude and position
  `SOURCE_VERIFIED` — pinned by test; receive proximity never qualifies).
- `ReplayTelemetrySource::PushDue(now)` is the no-future guarantee: a frame
  processed at T can only see samples that arrived by T (pinned by test).
- Device evidence (2026-10-09, Jetson, fp16 engine, 6-frame corpus replay
  through `replay_run`): all frames `OK` with real D-FINE (top-1 person
  0.898 / multiple people 0.875 / truck 0.914 — consistent with the W9
  smoke), stable track ids with `MISSED`→`OBSERVED` across a scene change,
  mean tick 73 ms including non-640 PIL-compatible preprocessing. This is a
  dev measurement on staged corpus frames — not a performance or
  detection-quality claim, and no real-video tracking quality is implied.
- NOT verified here: capture-time alignment (no verified exposure exists in
  replay v1 or the PSDK RGB stream), spatial observations (R4), telemetry
  loss/delay/clock-domain scenarios beyond the pinned no-future case (R3),
  real-video tracking quality (R2/R5), or any on-aircraft behavior.
  Synthetic telemetry from `scripts/make_replay_frames.py --pose-hz` is
  plumbing material (simulated domain, clearly labelled) and can never lift
  a grade above `RECEIVE_*` by itself.
