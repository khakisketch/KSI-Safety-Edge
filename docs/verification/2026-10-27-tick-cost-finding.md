# Tick-cost finding — autonomy window persistence is not real-time

Date: 2026-10-27. Host measurement only. **Not Manifold performance, not a
detection-accuracy claim, not device approval.**

> 10-27 P1 follow-up: the event-gated policy below (option P1) is implemented
> and measured — see [event-window policy](2026-10-27-event-window-policy.md).
> Steady-state NVMe 19.7 ms / tmpfs 1.9 ms per tick; one window per event.
> The device run in the measurement plan is still required.

## What was measured

`edge/tools/source_attention_bench.cpp` drives the real shared
`ProcessSharedTick` path with a synthetic 1920×1080 RGB24 stream (6.2 MB/frame),
64 tracks, 30 fps timestamps, one detection per frame, the **device-default
window shape** (pre 2 + tick 1 + post 2), and a stub detector so no inference is
included. Same binary, same host, two storage targets.

Reproduce:

```sh
cmake --build <edge-build> --target source_attention_bench -j8
source_attention_bench /tmp/opencode/bench-x 6      # NVMe
source_attention_bench /dev/shm/bench-x 6           # tmpfs (no device I/O)
```

## Result (p50 per tick, 6 ticks, 4 windows closed, 20 frames written)

| Variant | NVMe | tmpfs |
|---|---|---|
| Pipeline only (tracker, no autonomy) | ~0 ms | ~0 ms |
| + autonomy source windows | **313.6 ms** | **262.4 ms** |
| + original-resolution ROI capture | 313.6 ms | 262.9 ms |

Component costs measured separately: planner 0.006 ms, ROI copy 0.003 ms,
full-frame SHA-256 31.2 ms.

## What this means

- The attention feature is **not** the cost: it adds < 1 ms and 1 crop per
  selection. Scheduling is microseconds.
- The cost is the stage-1 window policy: 20 full frames written for 6 input
  ticks (each frame belongs to ~3–5 overlapping windows), ~124 MB in 6 ticks.
  Measured ~470 ms per closed window.
- A 30 fps stream needs 186 MB/s of ingress and, under this policy, roughly
  **620 MB/s of evidence writes**. The measured path runs ~10× slower than the
  stream, in the same worker thread that runs inference.
- Consequence in the field: the bounded frame queue overflows, frames are
  dropped, the operator sees video stale/lost and a degraded run — from the
  first continuous detection. It does **not** corrupt evidence; it makes
  observation lag and drop.

This is a pre-existing stage-1 limitation exposed by measurement, not introduced
by the ROI work. It also means the capacity work from 10-27 is necessary but not
sufficient: the store would saturate quickly even with a large disk.

## Conclusion

Full-frame pre/post windows at camera rate are not a feasible operational
default on this hardware class. The evidence policy has to become explicitly
bounded (per event, per second, or key-frame based). That is a product decision
about what evidence of record means, so it is not changed here.

**Unresolved risk:** with the current policy, a continuous 1080p detection
stream cannot sustain real-time observation on the device.

## Options (cost is arithmetic, not a promise)

| | Policy | Evidence bytes per event | Loss |
|---|---|---|---|
| P1 | trigger frame + one post frame, per event | ~12.4 MB | no continuous frames |
| P2 | one frame per second while the event is open, explicit sampling gap | ~6.2 MB/s | sampled, labeled |
| P3 | full pre/post windows, diagnostic runs only | ~31 MB/window | not operational |

P1 keeps every event with two verified frames and pairs naturally with the
original-resolution ROI crop; P2 bounds a long event; P3 stays available for
short explicit diagnostics. All three must record what was *not* kept.
