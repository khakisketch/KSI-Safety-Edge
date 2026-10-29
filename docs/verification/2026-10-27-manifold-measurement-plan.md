# Manifold 3 measurement plan (partially executed 2026-10-27)

Date: 2026-10-27. **Plan + partial execution.** Inference and evidence-path
halves are measured on the real Manifold 3 — see
measurement record (실측 원본 비공개 보존) (GPU p95 25.6 ms,
eMMC steady ~5 ms, window-close ~185 ms). The live-camera run below is
**blocked on the aircraft link** (`DjiCore_Init` waits; no aircraft
connected) and has not been executed. No flight, camera actuation, service
or ACTIVE change is part of it.

## Why

The host measurement
([tick-cost finding](2026-10-27-tick-cost-finding.md)) shows the autonomy window
policy running ~10× slower than a 30 fps 1080p stream. Host storage and CPU are
not the Manifold's, so the device number must be recorded before choosing an
evidence policy or claiming any performance.

## What to record (per run, one file per session)

| Metric | Source | Why |
|---|---|---|
| Processed frames, wall duration, effective fps | closing log `samples_written`, start/stop stamps | is real-time met |
| Bounded-queue drops (`frames_dropped`) | existing counter | proves the backlog, not a guess |
| Per-tick wall time p50/p95/max | instrumented build (see below) | localize the cost |
| Store bytes written, artifact count, saturation reason | autonomy coverage artifact + closing log | rate and headroom |
| Video/engine/telemetry input grades | `MISSION_STATUS` facts on the RC | what the operator actually saw |
| Thermal, CPU, memory high-water | `tegrastats` sampled at 1 Hz | endurance |
| Frame dimensions and pixel format actually delivered | `w`/`h` in the frame log | the host assumption is 1920×1080 |

## Host tooling (ready, tested)

```sh
# Capacity floor with the exact device arithmetic (run BEFORE deploying):
python3 scripts/size_autonomy_store.py --width 1920 --height 1080 \
  --capacity 134217728
# Frame-log summary after the run (numbers only, no pass/fail):
python3 scripts/analyze_edge_frame_log.py /tmp/ksi-safety-edge.ndjson
```

Pinned by `harness/tests/test_measurement_tools.py`. The analyzer never
verdicts; the sizer mirrors `PreflightEvidenceCapacity` exactly.

**Known sizing fact:** the 8 MiB device capacity default is BELOW one P1
window at 1080p (floor 12,965,888 = 6,220,800 × 2 + 524,288). With the
default the run observes with evidence explicitly saturated from the first
tick. This default is not raised here — size the store for the deployment
with the sizer instead.

## Procedure (per approved session)

0. Size the store with the sizer for the actual frame dimensions and P1
   window (pre 0 + post 1) plus the seal reserve, and install at least the
   floor. Record the stated capacity with the run.

1. Build the PSDK target with the deploy defaults, engine path and model id
   already used on that device. Do **not** enable ACTIVE or spatial rules.
2. Install the autonomy evidence directory on the Manifold with the sized
   capacity from step 0 (never the unexamined 8 MiB default at 1080p —
   see the sizing fact above).
3. Enable the opt-in autonomy path; leave `--source-attention-policy` off for
   the first baseline run, on for the second.
4. Run a **ground** session with the aircraft unarmed, real camera feed only.
5. Record the metrics above for: (a) no autonomy, (b) autonomy windows,
   (c) autonomy + ROI attention.
6. Stop cleanly so the revision and coverage artifact are written; export the
   sealed revision read-only and keep it.
7. Compare against the same metrics from the previous session. **No target
   numbers are set in advance** — the baseline decides what the evidence policy
   must be.

## Instrumentation

The device frame log now carries, per processed tick, `tick_us` (wall time of
detection + tracking + any synchronous evidence I/O) and the cumulative
`drops` counter. Both are **diagnostic only**: that file is not evidence and
`tick_us` is not a hardware claim. Analyze a recorded run with:

```sh
python3 - <<'PY'
import json, statistics
rows = [json.loads(line) for line in open('/tmp/ksi-safety-edge.ndjson')
        if '"type":"frame"' in line]
ticks = sorted(r['tick_us'] / 1000 for r in rows)
print('frames', len(ticks),
      'p50_ms', round(ticks[len(ticks)//2], 1),
      'p95_ms', round(ticks[int((len(ticks)-1)*0.95)], 1),
      'max_ms', round(ticks[-1], 1),
      'drops', rows[-1]['drops'])
PY
```

## Remaining gaps

## Out of scope

No aircraft movement, no route or patrol change, no camera zoom/gimbal/still
command, no ACTIVE promotion, no service configuration change, no deletion of
retained evidence.
