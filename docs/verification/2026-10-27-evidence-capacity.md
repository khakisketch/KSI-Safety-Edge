# Evidence capacity — saturation must not stop observation

Date: 2026-10-27. Scope: defect found in the just-added source-attention work and
fixed on the same shared Edge path. **Not device deployment, field acceptance or
authorization to delete retained evidence.**

## Defect (reproduced, not inferred)

`ProcessSharedTick` forwarded *every* autonomy write refusal to
`SessionRunner::NoteDurableFailure`, and the runner gate stops processing while a
durable failure is latched. A bounded evidence store reaching its declared
capacity is an expected end state, not lost flight evidence — but it was treated
as one.

Arithmetic that made it certain on the device: a 1080p RGB frame is ~6.2 MB, the
default capture window is pre 2 + during 1 + post 2 = 5 frames (~31 MB), and the
device autonomy capacity default was **8 MiB**. The first closed window therefore
saturates the store.

Reproduced with the pre-fix build (320×180 frames, 300 KiB store — one closed
window already exceeds it):

```text
tick=1 processed=1 runner_processed=1 det_calls=1 store_bytes=990
tick=2 processed=2 runner_processed=1 det_calls=2 store_bytes=174780 duty_failure=1
tick=3 processed=2 runner_processed=0 det_calls=2 store_bytes=174780
tick=4 processed=2 runner_processed=0 det_calls=2 store_bytes=174780
degraded: telemetry_absent session_capacity_exceeded
```

Detection, tracking and episodes stopped for the rest of the flight and never
auto-resumed, and the reported reason was indistinguishable from a lost MOP
journal — a required-record failure.

After the fix the same 300 KiB configuration is caught by the first-tick
preflight (`evidence_capacity_below_one_window`), so the mid-flight reproduction
uses 600 KiB (fits the preflight, not a second window):

## Changes

1. **Severity split.** Integrity/write faults (`kConflict`, `kCorrupt`,
   `kIoError`, unknown status, missing store) keep the existing fail-closed pause
   and `session_capacity_exceeded`. Capacity (`kFull`) is a sticky,
   **non-fatal** `autonomy_evidence_saturated` fact: observation, detection,
   assessment and live MOP delivery continue, and the gap is explicit.
2. **No retry storm.** Once saturated, `WriteArtifact` performs no filesystem
   work; refusals are counted in `coverage.evidence_refusals`. Saturation is
   sticky (no silent auto-resume), and sealed bytes are never evicted — retention
   deletion remains outside this change (ADR-0002).
3. **Seal reserve.** Admission now stops at `capacity - seal_reserve_bytes`
   (default 512 KiB, engineering reserve — not a calibrated threshold) so the
   closing revision can still be written. A full store that cannot seal turns
   kept bytes into unexportable files.
4. **Store seal accounting fixed.** `EvidenceStore::SealRevision` charged the
   covered asset bytes a *second* time (`CapacityAllows(covered + 512)`), so a
   store at ~half capacity could not seal at all. It now charges the manifest
   bytes it actually writes; the bound still refuses a manifest that does not
   fit.
5. **First-tick preflight.** A stated capacity below one window plus the reserve
   (`evidence_capacity_below_one_window`) is reported on the first processed
   tick instead of being discovered mid-flight. Observation continues.
6. **Attention report cadence.** Reports were written every frame. Now a report
   is always written for an attempted capture (crop provenance never separates
   from the crop), plus on decision change and a bounded heartbeat
   (`attention_report_interval_ns`, default 10 s, 0 = events only). Identical
   steady-state ticks are counted in `coverage.attention_reports_suppressed`.
7. **The gap names its cause.** The RC-facing code is the stable
   `autonomy_evidence_saturated`; the concrete `saturation_reason`
   (`store_capacity_exhausted`, `store_capacity_reserved_headroom_reached`,
   `evidence_capacity_below_one_window`) is logged once on the device and
   printed at host closeout, so an installer can size the store instead of
   reading an opaque code.

## Verification

```text
ctest (RelWithDebInfo)            40/40 passed      /tmp/opencode/capacity-ctest.log
ctest (ASAN+UBSAN, Debug)         40/40 passed      /tmp/opencode/capacity-asan-ctest.log
bash scripts/run_all_tests.sh     224 passed        /tmp/opencode/capacity-python.log
PSDK 3.16 aarch64 target build    passed            /tmp/opencode/capacity-psdk-build.log
```

Post-fix reproduction of the same scenario:

```text
tick=3 processed=3 runner_processed=1 det_calls=3 store_bytes=521370
tick=4 processed=4 runner_processed=1 det_calls=4 store_bytes=521370
tick=6 processed=6 runner_processed=1 det_calls=6 store_bytes=521370
degraded: telemetry_absent autonomy_evidence_saturated
```

`edge/tests/test_evidence_saturation.cpp` pins: the 8 MiB/1080p sizing fact,
mid-flight saturation continuing observation with frozen store bytes, the
sticky gap on run facts, integrity faults still pausing, the below-one-window
preflight, and bounded report cadence. `edge/tests/test_evidence_store.cpp`
pins that a near-bound store can still seal and that a manifest that does not fit
is still refused.

## Limits

- No device run: real frame sizes, sustained checkpoints, thermal behaviour and
  storage endurance on Manifold 3 remain unmeasured.
- The capacity default (8 MiB) was **not** raised; a deployment must now state a
  capacity that covers its window plus reserve, or it runs with observation on
  and evidence explicitly saturated.
- Saturation means later artifacts are absent, not sampled: a sealed revision
  proves only what it lists. The coverage artifact itself cannot be written on a
  saturated store; the live run facts and the closing log carry that fact.
- Retention/rotation of old evidence (the way to restore capacity) is a separate
  approved decision and is not implemented here.
- No model, PPE verification, spatial verdict, camera or flight output changed.
