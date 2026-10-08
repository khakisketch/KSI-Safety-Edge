# edge/psdk-app — KSI Safety Edge PSDK app (v0)

Manifold 3 PSDK application: RGB liveview ingress + flight-control
subscription provenance + receive-grade temporal matching, written as NDJSON.

## Scope (v0 — and what it is NOT)

- Capture: `DjiLiveview_StartImageStream` RGB packed frames into the shared
  `BoundedFrameQueue` (bounded, callback-safe: timestamp first, try-push
  only, no file I/O / blocking / allocation in callbacks).
- Provenance: `host_mono` (CLOCK_MONOTONIC) is the only verified clock.
  Telemetry `T_DjiDataTimestamp` values are counted, never promoted —
  `has_source_time` is always false, domain `psdk_fc_local`.
- Matching grades: receive-nearest only (`max_receive_gap_ns` 50 ms,
  `max_bracket_interval_ns` 20 ms). Without a verified exposure time no
  frame can grade above `RECEIVE_NEAREST` — by design, not a bug.
- No detector. No capture-time claims. Position samples are stored raw
  (lat/lon rad, alt m, WGS 84) with no site-frame conversion.

## Build on device (Manifold 3)

Prerequisites on the device: PSDK tree at `/open_app/psdk`, real
`config/dji_sdk_app_info.h` (copy the `.example`, fill in
developer.dji.com values — the real file is gitignored and never
committed), an attached aircraft for real frames.

```sh
cd <staging>/edge
cmake -S psdk-app -B psdk-app/build -DKSI_PSDK_ROOT=/open_app/psdk \
  -DKSI_PSDK_APP_INFO_HEADER=$PWD/psdk-app/config/dji_sdk_app_info.h \
  -DKSI_SAFETY_EDGE_DPK_USER_APP_ID=191589
cmake --build psdk-app/build -j4
cmake --build psdk-app/build --target ksi_safety_edge_dpk
```

Without `-DKSI_SAFETY_EDGE_DPK_USER_APP_ID` there is no DPK target (empty
means opt-out). The camera source and default duration are compile-time
options: `-DKSI_SAFETY_EDGE_CAMERA_SOURCE=...`,
`-DKSI_SAFETY_EDGE_DURATION_SEC=...` (default `M4E_VIS`, 60 s).

## DPK

`cmake --build psdk-app/build --target ksi_safety_edge_dpk` runs DJI
`build_dpk.sh` on the generated `dpk/app.json` (name `ksi-safety-edge`,
platform `manifold3`) and drops the `.dpk` under `psdk-app/build/dpk/out/`.

## Install / run (manual)

```sh
# argv-less DPK service defaults: out /tmp/ksi-safety-edge.ndjson, 60 s.
# Manual runs may override:
./psdk-app/build/ksi_safety_edge --out /tmp/edge.ndjson --duration-sec 30
```

NDJSON contract: a `meta` line first (version, `started_at_utc`, clock
anchor mono+wall, `capabilities{frame_sensor_ts:false,
telemetry_sensor_ts:false}`), one `frame` line per popped frame with
`att`/`pos` match grades (`q`/`r` from the core `ToString` helpers), and
a final `stat` line (frame counters, queue stats, per-topic counts,
`samples_written`, `stop_reason`). SIGINT/SIGTERM stops gracefully
(duration loop polls every 200 ms), then teardown unsubscribes the image
stream and FC topics in FIFO subscription order, joins the worker
(draining the queue), and closes the file.

## Known open items

- USB bulk channel/interface map (bulk4/5) unresolved — carried over from
  the glue comments; needs hardware verification.
- `developerAccount` in the credential header is empty (allowed, not fatal).
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
