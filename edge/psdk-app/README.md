# edge/psdk-app — KSI Safety Edge PSDK app (v0)

Manifold 3 PSDK application: RGB liveview ingress + flight-control
subscription provenance, processed by the SAME `SessionRunner` the Replay
driver uses (`edge/src/runtime/session_runner.cpp`) and written as NDJSON.

## Scope (v0 — and what it is NOT)

- Capture: `DjiLiveview_StartImageStream` RGB packed frames into the shared
  `BoundedFrameQueue` (bounded, callback-safe: timestamp first, try-push
  only, no file I/O / blocking / allocation in callbacks).
- Processing: the shared product runner — detection cadence gate → detector
  → temporal matching → session step. With `--engine PATH` on a TensorRT
  build (`-DKSI_SAFETY_EDGE_ENABLE_TENSORRT=ON`) the detector is the real
  D-FINE path (`DfineDetectorPort`); without it every tick stays `NOT_RUN`.
  This is the same code path as `edge/tools/replay_run.cpp` — only the input
  source differs.
- Provenance: `host_mono` (CLOCK_MONOTONIC) is the only verified clock.
  Telemetry `T_DjiDataTimestamp` values are counted, never promoted —
  `has_source_time` is always false, domain `psdk_fc_local`.
- Matching grades: receive-nearest only (`max_receive_gap_ns` 50 ms,
  `max_bracket_interval_ns` 20 ms). Without a verified exposure time no
  frame can grade above `RECEIVE_NEAREST` — by design, not a bug.
- No capture-time claims. Position samples are stored raw (lat/lon rad,
  alt m, WGS 84) with no site-frame conversion; spatial work is R4.

## Build on device (Manifold 3)

Deployment prerequisites on the device (all trusted-installer inputs, none
auto-fetched):
- PSDK tree at `/open_app/psdk`.
- Real `config/dji_sdk_app_info.h` (copy the `.example`, fill in
  developer.dji.com values — the real file is gitignored and never
  committed). Placeholder credentials stop the app at startup (exit 2).
- Engine file on the device ONLY if real detection is required (e.g.
  `/open_app/models/dfine.engine`, baked with
  `-DKSI_SAFETY_EDGE_ENGINE_PATH=...` below or passed as `--engine` for
  dev runs). No engine means no fabricated observations: every tick stays
  `NOT_RUN` and the run reports the engine absent/degraded — never a fake
  healthy stream. No model weights are downloaded and no model is fetched
  at runtime, ever.
- An attached aircraft for real frames (and for the DPK install
  verification, see below).
- The DPK service is started by `dji_app_mgr` (payload start), NOT from
  inside an MSDK app: the installed binary runs with NO argv (DPK
  `app.json` ships `"bin"` with `"userconfig": []`), so the argvless
  defaults below ARE the operational behavior.

> **호스트 빌드 시 PSDK 경로**: 온디바이스의 `/open_app/psdk` 대신 저장소 안 로컬 고정을 쓸 수 있다
> (`third_party/dji/psdk-3.16.0`, DJI EULA상 커밋 금지). 복구·검증은 `bash scripts/dji_assets.sh`.
> 자산 채택/참조/배제 판정은 `docs/plans/2026-10-21-dji-asset-usage.md`를 따른다.

```sh
cd <staging>/edge
cmake -S psdk-app -B psdk-app/build -DKSI_PSDK_ROOT=/open_app/psdk \
  -DKSI_PSDK_APP_INFO_HEADER=$PWD/psdk-app/config/dji_sdk_app_info.h \
  -DKSI_SAFETY_EDGE_ENABLE_TENSORRT=ON \
  -DKSI_SAFETY_EDGE_DPK_USER_APP_ID=191589 \
  -DKSI_SAFETY_EDGE_ENGINE_PATH=/open_app/models/dfine.engine \
  -DKSI_SAFETY_EDGE_MODEL_ID=<real-model-id> \
  -DKSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR=/open_app/ksi-safety-edge/autonomy
cmake --build psdk-app/build -j4
cmake --build psdk-app/build --target ksi_safety_edge_dpk
```

`-DKSI_SAFETY_EDGE_ENABLE_TENSORRT=ON` is optional: it lets the app run the
real D-FINE detector (deploy engine above, or `--engine` for dev). Without
`-DKSI_SAFETY_EDGE_DPK_USER_APP_ID` there is no DPK target (empty means
opt-out). The camera source is a compile-time option:
`-DKSI_SAFETY_EDGE_CAMERA_SOURCE=...` (default `M4E_VIS`).
`-DKSI_SAFETY_EDGE_MOP_CHANNEL=...` (default `0`) is the argvless
operational MOP channel; `-DKSI_SAFETY_EDGE_DIAG_DURATION_SEC=...`
(default `60`) is the finite duration for explicit diagnostic runs only —
a stale `KSI_SAFETY_EDGE_DURATION_SEC` cache entry is unset at configure
time and can never force an operational timeout. `-DKSI_SAFETY_EDGE_MODEL_ID`
binds the install to the real model (empty keeps the existing code
default; never invent an operational model name when none is provided).

`-DKSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR=...` is the trusted-installer
switch for passive SHADOW autonomy in the argvless service. Empty (the
default) keeps it OFF; no build silently enables it. When set, the same
shared `SessionRunner` ticks also produce machine assessments and sealed
event windows under that directory, separate from `--state-dir`.
Assessments never create Incidents, Alerts, human responses, camera or
flight commands. Without a real engine the ticks stay `NOT_RUN`/degraded
and are recorded honestly. This path is compile/link verified on host
aarch64 against PSDK 3.16.0; current results and device/host limits are in
the [source-attention verification](../../docs/verification/2026-10-26-source-attention.md).

Event windows are P1 policy: a window opens only for a new event (freshly
observed confirmed lineage unseen on the previous tick, or an inference
failure rising edge), keeps the trigger plus one post frame (`--autonomy-pre 0
--autonomy-post 1` defaults), and at most one window opens per
`--autonomy-window-min-interval-ms` (default 1000, 0 disables the bound;
merged re-triggers are counted in coverage, never silently dropped).
Continuous detection therefore costs one small window per event, not one
full window per frame; per-tick assessments and ROI attention continue
regardless. See [tick-cost finding](../../docs/verification/2026-10-27-tick-cost-finding.md).

### Optional bounded original-source ROI evidence

`--source-attention-policy KEY=VALUE,...` (same parser in PSDK, `replay_run`,
and `mop_server`) enables the source-only `ObservationPlanner`. The argvless
deployment equivalent is `KSI_SAFETY_EDGE_SOURCE_ATTENTION_POLICY`; empty is
OFF. An evidence directory AND finite positive capacity are required. This
does not open the APPLY/START gate or change detection cadence/SHADOW rules.

All keys are required. **SIMULATED example, not Manifold tuning:**

```text
class=0,targets=64,jobs=1,bytes=524288,repeat_ms=5000,lost_ms=1000,wait_ms=2000,retry_ms=500
```

Only fresh confirmed tracks of the configured class qualify. Boxes use
original-frame pixel coordinates; crops copy RGB24 rows without resize.
The policy bounds targets, jobs and crop bytes per tick; cooldown charges
an attempted capture even if persistence fails. Missing input expires state
through the worker's existing HealthTick. Flight/boot/stream, configured lens,
observed lens switch, model, config and frame dimensions separate identity.
Configured lens identity is **not verified lens telemetry**.

The separate `ksi.source-attention/v1` report records selection/refusal,
source/track/ROI/digest provenance and actual write outcome. New media types
are generic collection artifacts, **not** assessment v1 or full-frame media.
PPE remains `UNDETERMINED / verifier_not_configured`; no zoom, gimbal,
flight, Incident or Alert output exists. Storage refusal latches the existing
durable failure path; sealed evidence is never evicted to make room.

This opt-in feature was not installed or run on a device. Existing real
D-FINE 640×640 inference and tracker are retained; no PPE/fire classifier or
1080p→960 screening pipeline is introduced. Measure the actual Manifold
worker, storage, queue drops and thermal behavior before field enablement.

## DPK

`cmake --build psdk-app/build --target ksi_safety_edge_dpk` runs DJI
`build_dpk.sh` on the generated `dpk/app.json` (name `ksi-safety-edge`,
platform `manifold3`) and drops the `.dpk` under `psdk-app/build/dpk/out/`.

## Install / run (manual)

```sh
# argvless DPK service (the operational default): MOP channel 0, unbounded
# (no 60 s timeout), gate closed until MISSION_APPLY + SESSION_START.
./psdk-app/build/ksi_safety_edge
# Explicit diagnostic capture (legacy workflow): finite, auto-processed.
./psdk-app/build/ksi_safety_edge --standalone --duration-sec 30
./psdk-app/build/ksi_safety_edge --mop-channel -1 --duration-sec 30
# Explicit bounded operational service (diagnostic stop after N s):
./psdk-app/build/ksi_safety_edge --mop-channel 0 --duration-sec 30
# Optional real detector (TensorRT build only; overrides the deploy default):
./psdk-app/build/ksi_safety_edge --engine /path/to/dfine-s-coco.fp16.op16.engine
# Optional detection cadence (default 1 = every frame):
./psdk-app/build/ksi_safety_edge --detect-every 2
```

Operator scope: the deployed app is commanded entirely by the RC
(MISSION_PREPARE / MISSION_APPLY / SESSION_START / SESSION_STOP over the
MOP channel). SESSION_STOP ends analysis; the service stays up for the
next session — there is no app-switch or relaunch between sessions.
`--standalone` is a bench/diagnostic tool, never the operational path.

NDJSON contract: a `meta` line first (version, `started_at_utc`, clock
anchor mono+wall, `capabilities{frame_sensor_ts:false,
telemetry_sensor_ts:false}`), one `frame` line per popped frame with
`detect`/`dets` (detection status + fresh detection count), `att`/`pos`
match grades (`q`/`r` from the core `ToString` helpers), `spatial_eligible`
and live track count, and a final `stat` line (frame counters, queue stats,
per-topic counts incl. `att_rejected`/`pos_rejected`, `detector`,
`samples_written`, `stop_reason`). SIGINT/SIGTERM stops gracefully
(duration loop polls every 200 ms), then teardown unsubscribes the image
stream and FC topics in FIFO subscription order, joins the worker
(draining the queue), and closes the file.

## MOP channel (operational transport)

Byte transport to the RC (DJI MOP channel; the MSDK side connects with
`PipelineManager.connectPipeline` — the channel id must match; the RC app
uses `DEFAULT_PIPELINE_ID = 0`). This is the argvless operational default
(channel `KSI_SAFETY_EDGE_MOP_CHANNEL`, `0`), not an opt-in: the service
learns the RC's per-flight UUID from the wire (only pass `--flight-id`
when it is pre-agreed) and the processing gate stays closed until an
actual MISSION_APPLY install plus an explicit SESSION_START:

```sh
./psdk-app/build/ksi_safety_edge --mop-channel 0 --flight-id <id>
```

- Message logic (framing, envelope verification, mission handling, evidence
  emission, retry/gap bookkeeping) is the host-tested transport core in
  `edge/src/transport/` (tests `mop_frame` / `mop_json` / `mop_e2e` plus the
  cross-language fixtures in `contracts/mop/`).
- `src/mop_service.cpp` only moves bytes: `DjiMopChannel_Init/Create/Bind`,
  `Accept` (reader thread), `RecvData` polling, `SendData` partial-write
  loop; a timer flushes the outbox, retries unacked messages and emits the
  periodic HEARTBEAT.
- DEVICE GATE: this path compiles only here (PSDK headers required) and is
  verified on the RC ground session. Evidence emission from live ticks
  (TickRecord → message payloads) and the real flight-id handoff from the
  RC are the next step.

## Known open items
- USB bulk channel/interface map (bulk4/5) unresolved — carried over from
  the glue comments; needs hardware verification.
- `developerAccount` in the credential header is empty (allowed, not fatal).
- The DPK payload starts outside any MSDK app (`dji_app_mgr` launch, no
  argv): operators do not switch apps or relaunch between sessions; the RC
  commands the persistent service over MOP.
- A retired `KSI_SAFETY_EDGE_DURATION_SEC` cache entry in an old build dir
  is unset at configure time; diagnostic duration now lives in
  `KSI_SAFETY_EDGE_DIAG_DURATION_SEC` and never bounds the service.
- FC source timestamps (`T_DjiDataTimestamp`) are unverified by design;
  unit/domain meaning is not established, so they are counters only.
- **An aircraft must be attached for the DPK install verification as well as
  for real frames.** dji_app_mgr briefly starts the app and compares the
  package control Version with the version the app reports via
  `DjiCore_SetFirmwareVersion` — reachable only after `DjiCore_Init`
  completes, which waits for the aircraft link. Measured 2026-10-08 (no
  aircraft): boot log stops at `core_init_begin` and install fails with
  "verify app user_app_id or version info error". Connect the aircraft for
  the install session.
- Boot diagnostics: startup breadcrumbs are written to
  `/tmp/ksi-safety-edge-boot.log` (dev-only; survives the ~8 s
  verification start dji_app_mgr performs).
- Host builds do not cover this tree (PSDK headers + credential required);
  device build + core regression are the gates.
