# Event-gated evidence windows (P1) — implementation / host verification

Date: 2026-10-27. Scope: the P1 row of the
[tick-cost finding](2026-10-27-tick-cost-finding.md), implemented on the shared
Edge path. **Not device deployment, field acceptance, model validation, ACTIVE
approval or authorization to actuate anything.**

## What changed and why

The finding showed the stage-1 window policy running ~10× slower than a 30 fps
1080p stream. Two compounding causes, both addressed:

1. **Every detection tick opened a window.** Continuous detection of the same
   targets opened N windows for N ticks. Now a window opens only for a NEW
   event: a confirmed track freshly observed this tick whose lineage was not
   fresh on the previous tick (new, returning, or tracker-epoch-changed
   target), or an inference-failure rising edge. Continuous detection opens
   nothing new. (The old third trigger — withheld spatial verdicts on a
   detection tick — was subsumed by the first condition and is removed; no
   behavior is lost.)
2. **Overlapping windows rewrote the same frames.** With the old pre2/post2
   default, 6 consecutive detection ticks wrote 20 full frames. The default
   window is now trigger + one post frame (`--autonomy-pre 0`,
   `--autonomy-post 1`), and at most one window opens per
   `--autonomy-window-min-interval-ms` (default 1000 ms, 0 disables the
   bound). Re-triggers inside the interval are counted as
   `coverage.windows_merged`, never silently dropped.

What did NOT change: per-tick assessments (cheap JSON, still every processed
tick), ROI attention (still per-tick, still cooldown-bounded), the ring,
the store, the saturation split, APPLY/START/STOP gates, SHADOW-only.

## Measured effect (same synthetic 1080p/30 fps host bench, 6 ticks)

| Variant | Before (pre2/post2, per-tick) | After P1 (NVMe) | After P1 (tmpfs) |
|---|---|---|---|
| Autonomy windows, p50/tick | 313.6 ms | **19.7 ms** | **1.9 ms** |
| + ROI attention, p50/tick | 313.6 ms | 22.7 ms | 1.9 ms |
| Closed windows / 6 ticks | 4 | 1 | 1 |
| Full frames written / 6 ticks | 20 | 2 | 2 |

Single window-close ticks cost more (one-off ~130–180 ms for hashing and
writing 2 × 6.2 MB frames) but occur once per event, not once per frame.
Steady-state ticks carry only the assessment write (~20 ms on this host NVMe,
~2 ms on tmpfs — fsync-dominated; inference not included).

## Verification

```text
ctest (RelWithDebInfo)            40/40 passed      /tmp/opencode/p1-ctest.log
ctest (ASAN+UBSAN, Debug)         40/40 passed      /tmp/opencode/p1-asan-ctest.log
bash scripts/run_all_tests.sh     224 passed        /tmp/opencode/p1-python.log
PSDK 3.16 aarch64 target build    passed            (rebuilt with the P1 wiring)
```

`edge/tests/test_autonomy_stage1.cpp` T17 pins: one window for six continuous
detection ticks, a new window on return after a gap, failure rising-edge opens
once, interval-bound merging is counted, post-interval re-trigger opens again.
Existing trigger-shape legs run with the interval bound at 0 so they test
event gating alone. `edge/tests/test_evidence_saturation.cpp` now drives a new
lineage per tick (alternating halves, IoU 0) so each tick is genuinely its own
event; the attention-cadence leg uses a steady box for one continuous lineage.

## Workflow re-verification (P1 shape)Isolated run `/tmp/opencode/ksi-autonomous-v8b074h5/` with the same command as
the source-attention verification: **20 artifacts** — assessment12,
full-frame**2**, attention report4, original ROI1, coverage1. Decision changes
(unconfirmed → selected → repeat-suppressed) unchanged; the ROI crop still
matches the original corpus. Receipt required ids exactly match the sealed
revision (`aea850f0dca94233725c4290c161f0bd5b83901db51e4bff0c66b2f82d99ce43`).
Full-frame evidence dropped 54 → 2 with no change to the attention path.

## RC visibility (the gap reaches the operator)

The saturation fact travels on the existing `degraded_reasons` array — no MOP
contract change. Verified end to end on the RC side:

- `RuntimeStatus` passes reason codes through opaquely (unknown codes are
  never dropped at parse).
- `MissionController` latches `durableEvidenceFailure` (which forces a FAILED
  verdict) **only** on `session_capacity_exceeded`. `autonomy_evidence_saturated`
  leaves it false — the verdict path is untouched. Pinned by
  `MissionRuntimeTest.autonomy_evidence_saturation_is_not_a_durable_evidence_failure`.
- `EdgeRuntimePanel` renders one dedicated details line for the known code —
  "자동 기록 포화 · 관측 계속, 일부 증거 미보존" — while the headline keeps
  the verbatim contract wording with the raw code. Unknown future codes keep
  rendering raw with no invented line. Pinned by `EdgeRuntimePanelTest`.
- `OperatorStatus` is untouched: DEGRADED already yields ATTENTION severity
  there and the raw codes remain visible; no new item, no severity change.

## Limits

- No device run: whether ~20 ms + real inference fits 33 ms on the Manifold is
  still decided by the [device run](2026-10-27-manifold-measurement-plan.md),
  which now has per-tick `tick_us` + drop counters in the frame log.
- `window_min_interval_ns` default 1000 ms is an engineering admission bound,
  not a calibrated safety threshold. Deployments state their own interval.
- Unconfirmed tracks open no window (confirmation needs `min_hits` observations);
  their ticks are still assessed every frame. A flickering one-frame object
  therefore leaves assessments but no full frames — an explicit policy choice,
  recorded here.
- Merged re-triggers prove only that an event *continued*, not what it looked
  like mid-interval; the coverage count and the per-tick assessments carry that
  fact.

## Final tree baseline (10-27, all suites on the final tree)

- Edge (RelWithDebInfo): **41/41** — `readability-ctest.log` (40 + readability gate math)
- Edge (ASAN+UBSAN, Debug): **40/40** — `p1-asan-ctest.log` (no Edge code changed after)
- Python (contracts/libs/Core/harness, incl. 4 measurement-tool tests): **228 passed** — `final-python.log`
- Android (host): **1670 passed, 0 failed, 6 pre-existing SKIP** — `rc-tests3.log`

No Edge source changed after the P1 verification; the RC additions since
(prepare journal, elapsed units) are covered by the Android count above.
