# Original-source attention — implementation / host verification

Date: 2026-10-26–27. Scope: hardware-appropriate bounded observation work approved
in the current development task. **Not device deployment, field acceptance,
model validation, ACTIVE approval or authorization to actuate anything.**

## Implementation

- `edge/src/observation/planner.cpp`: caller-clock, data-only scheduling;
  fresh confirmed tracks only, explicit class selection in the parent,
  routine priority for current hardware inputs (no invented per-target risk).
- `source_roi.cpp`: finite/clipped source-pixel xyxy, floor/ceil bounds,
  exact RGB24 length, pre-allocation byte guard and byte-exact original rows.
- `source_attention_policy.cpp`: strict required numeric keys; no profiles,
  unknown/duplicate keys, signed numbers, overflow or partial defaults.
  Engineering caps: 4096 targets, 64 jobs/tick, 16 MiB crop bytes/tick,
  positive durations ≤1 hour, jobs≤targets. These are **not field calibration**.
- `source_attention.cpp` + `autonomous_processor.cpp`: actual shared
  `ProcessSharedTick` path used by PSDK, replay and host MOP. Existing
  D-FINE preprocessing/decode, tracking, assessment v1, context windows,
  transport and store are reused. No parallel detector or service added.
- `--source-attention-policy` / argvless CMake deployment string is explicit,
  default OFF; requires durable directory and finite positive store capacity.
  APPLY/START/STOP gates and SHADOW remain unchanged.
- State loss is evaluated before current presence; held/unconfirmed inputs
  cannot extend freshness. Starvation wait excludes cooldown, so a shorter
  wait deadline cannot bypass repeat suppression. Expiry quarantines attempts;
  HealthTick closes loss/wait state without waiting for a new frame.
- Flight/boot/stream, model/digest, config epoch/hash/revisions, configured lens,
  observed lens switch and dimensions invalidate identity. Lens metadata is
  explicitly `configured_not_telemetry_verified`, not a claimed DJI feed.
- Device worker now uses the DJI-free `source_context.hpp` constructor with
  actual MOP flight/boot copied under one endpoint lock and the configured
  port/source stream label. The previous worker left those context fields
  empty; a host fixture with manually filled fields alone would miss this.
  No camera timestamp is promoted to verified exposure time.
- Scheduling uses the latest caller health/receive clock, while source receive
  order and queue age remain separate. Thus HealthTick-before-pop does not
  reject every delayed frame as clock rollback, and an old queued frame
  (including the first frame) cannot bypass the explicit `lost_ns` age budget.
- New internal report `ksi.source-attention/v1` and crop media are distinct
  from assessment v1/full-frame media. Existing producer-neutral collection
  treats both as `EDGE_EVIDENCE_ARTIFACT`; frozen contracts were not edited.
  Reports preserve original frame hash, crop coordinates/track lineage,
  byte digest, write outcome, receive-only timing when exposure is unavailable,
  simulated marker, unavailable PPE verification and zero actuation outputs.
- Store capacity/I/O refusals remain sticky and reach the shared runner's
  durable health. Crops and reports are individually atomic, **not a multi-file
  transaction**: a later report refusal can leave an unassociated crop in the
  append-only store. No success report or sealed-data eviction is invented.
  Capacity itself is now a non-fatal saturated gap — see
  [evidence capacity](2026-10-27-evidence-capacity.md).

## Reproduction / evidence

Local scratch logs below are retained under `/tmp/opencode`, not protected
historical device evidence. All tests use synthetic inputs/local files only.

```sh
cmake -S edge -B /tmp/opencode/source-attention-build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build /tmp/opencode/source-attention-build -j8
ctest --test-dir /tmp/opencode/source-attention-build --output-on-failure

cmake -S edge -B /tmp/opencode/source-attention-asan -DCMAKE_BUILD_TYPE=Debug \
  -DKSI_SAFETY_EDGE_ENABLE_SANITIZERS=ON
cmake --build /tmp/opencode/source-attention-asan -j8
ctest --test-dir /tmp/opencode/source-attention-asan --output-on-failure

bash scripts/run_all_tests.sh
```

Tests cover explicit policy and deploy defaults, malformed/overflow/duplicate
input, source byte/ROI clipping, scheduling determinism and state pressure,
cooldown/loss/quarantine/frameless expiry, disabled compatibility, immutable
hash/seal, crop I/O/capacity failure, shared command gate and **existing D-FINE
640×640 decode → tracker → original-resolution ROI** with injected model outputs.
Injected outputs are not a trained-model accuracy measurement.

Final results:

| Check | Result / local log |
| --- | --- |
| Edge RelWithDebInfo | **40/40 passed** · `capacity-ctest.log` (post-fix rebuild) |
| Edge ASAN + UBSAN Debug | **40/40 passed** · `capacity-asan-ctest.log` (141 s) |
| Python contracts/libs/Core/harness | **224 passed** · `capacity-python.log` |
| Host CLI prerequisite/malformed policy | both entry points reject with exit 2 before starting work/listening |
| Actual PSDK target compile/link | passed · `capacity-psdk-build.log` |

Logs above are under `/tmp/opencode/`. The first foreground sanitizer run hit
the shell's 120 s timeout; the completed background suite above is the result,
not that incomplete invocation. The suite is **40 tests** after the 10-27
capacity regression (`edge/tests/test_evidence_saturation.cpp`); see
[evidence capacity](2026-10-27-evidence-capacity.md). Regression cases also include the actual
device receive-context helper, initial/continuing queued-frame age, genuine
source-order rollback and HealthTick-before-pop ordering.

PSDK build: local aarch64 host links the actual 3.16.0 `libpayloadsdk.a` and
existing credential header without printing it. Target `ksi_safety_edge`,
`/tmp/opencode/source-attention-psdk/`; **TensorRT OFF**, no DPK packaging,
installation or executable invocation. libusb development headers are absent:
USB host-mode stays the existing NONSUPPORT stub; FunctionFS path is unchanged.

Isolated integration:

```sh
.venv/bin/python scripts/run_autonomous_observation_workflow.py \
  --mop-server /tmp/opencode/source-attention-build/mop_server \
  --edge-arg=--source-attention-policy \
  --edge-arg=class=0,targets=64,jobs=1,bytes=524288,repeat_ms=5000,lost_ms=1000,wait_ms=2000,retry_ms=500
```

This launches a **new task-local Core DB/object store**, uses a virtual MOP
probe and 12 synthetic 64×64 frames, and reads back a custody receipt. It does
not use an actual RC, shared Core service, DJI transport or real camera.
The host stub now emits **source-pixel** boxes, consistent with D-FINE/TrackBox,
not fractions mislabeled as pixels. Crop bytes are independently compared to
the source corpus's rows, not only to their own stored hash.

Final isolated run: `/tmp/opencode/ksi-autonomous-mtctqv16/` (after the 10-27
report-cadence change, **before the 10-27 P1 window policy**). **72 artifacts**:
assessment12, full-frame54, attention report4 (three decision changes + stop),
original ROI1, coverage1. The P1 policy supersedes these counts (2 full frames
per event instead of a window per frame) — the current-shape run
(`ksi-autonomous-v8b074h5`, 20 artifacts, full-frame2, same receipt check) is
reported in [event-window policy](2026-10-27-event-window-policy.md).
Decision changes: unconfirmed → selected → repeat-suppressed; identical
steady-state ticks are counted instead of written. The 32×32 RGB crop
(3072 bytes, x/y=16/16) matches the original corpus exactly, as does its
same-tick full-source hash/reference. Receipt required ids exactly match the
sealed revision. Receipt digest
`9175ea8fbae19bea54a8ac689051fc4ca83612917428ebed231a8ddf0dc5d872`.
The pre-cadence run (`ksi-autonomous-71ixn69j`, 81 artifacts) is retained
locally as history, not as the current shape.

## Bounded overhead measurement (host, not Manifold performance)

`source_attention_bench` measures constructed ticks, no inference. Same local
aarch64 host/GCC13.3/RelWithDebInfo, synthetic 1920×1080 RGB (6,220,800 bytes),
64 tracks, one 192×256 crop per tick, 12 end-to-end samples per variant;
planner/copy 500 samples and source hashing 20. Existing context full-frame
writes/fsync are included in end-to-end timing. Files are task-local and not
deleted. Small sample counts and host I/O variance do not establish a field p95.

| Host p50 | Before digest reuse | After digest reuse (two runs) |
| --- | --- | --- |
| Existing autonomy, attention OFF | 89.05 ms | 88.82 / 91.71 ms |
| Autonomy + source attention | 142.54 ms | 113.29 / 110.61 ms |
| Extra full-source hash alone | 31.21 ms | avoided **only when same-tick source already committed** |

The repeated full-frame hash was measured at ~31 ms (about 22% of the
142.54 ms new path). One change reuses only the digest of the exact current
frame already written by a closed context window; an open window hashes
current bytes directly. No old-frame cache, relaxed hashing, async custody or
changed source bytes. Tests check both branches and a changed-frame transition.
The earlier first run was noisier (106.67/171.93 ms OFF/ON); raw local records:
`source-attention-benchmark-before{,-2}.json`,
`source-attention-benchmark-after{,-2}.json` under `/tmp/opencode/`.

## Limits

No device commands, install, flight/camera changes, service settings, operational
data deletion, commits or pushes. Real Manifold TensorRT/runtime budget, frame
queue pressure, thermal/storage endurance and sustained latency remain unmeasured.
No PPE/fire/smoke verifier, actual equipment-specialist model, spatial calibration,
automatic camera/route controller, new alert delivery or RC crop viewer exists.
An available crop means **source pixels captured**, never sufficient PPE detail,
negative PPE/fire evidence, safe status or successful verification.
