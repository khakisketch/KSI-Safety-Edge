# C57 evidence — WorkManager execution attempt (2026-10-10/11)

## What was built

- `rc-app/src/test/java/ksi/safetyedge/rc/SyncWorkerTest.kt` — Robolectric +
  `work-testing` tests that execute the REAL WorkManager test harness against
  the REAL app session (`RcApp` + `FileRcStore`): enqueue → constraints gated
  → `TestDriver.setAllConstraintsMet` → worker runs; owner decisions
  (unconfigured/unknown → success, unreachable → retry, already-synced →
  short-circuit, late annotation → NOT short-circuited).
- `scripts/run_workmanager_tests.sh` — runs those tests on x86_64 (the repo's
  established amd64 container path).
- `SyncWorkerDecision.kt` + `SyncWorkerDecisionTest.kt` — the worker's entire
  branching extracted into pure functions, host-tested (this is the
  executable evidence ON this host; see below for why the Robolectric tests
  cannot run here).
- `android-rc/gradle.properties`: `android.builder.sdkDownload=false` (a
  missing SDK component must fail with AGP's own message, never launch the
  auto-installer on the emulated path).

## Attempts and result (host: Linux aarch64)

1. Native aarch64 (host Gradle): **Robolectric has no Linux/aarch64 native
   runtime** — its SQLite shadow aborts with
   `The Robolectric native runtime is not supported on Linux (aarch64)`
   (verified against Robolectric 4.14.1; the 4.17 loader still rejects
   Linux/aarch64, and conscrypt-mode OFF does not change this). The tests
   therefore SKIP with that explicit reason on this host.
2. x86_64 container (qemu-user via binfmt, `docker run --platform linux/amd64
   ksi-utest-amd64`): the emulated JVM dies with
   `x86_64-binfmt-P: QEMU internal SIGSEGV {code=MAPERR, addr=0x20}` — even
   `./gradlew help` (qemu-user 8.2.2). `QEMU_RESERVED_VA=0` and disabling
   AGP SDK auto-download did not change it; a 400MB core dump was produced.

## Status

- WorkManager RUNTIME execution: **not run on this host** (platform limit,
  recorded above). Carried to: a native x86_64 CI/runner
  (`bash scripts/run_workmanager_tests.sh`) or the real RC device (A9).
- WorkManager DECISION LOGIC: host-verified via `SyncWorkerDecisionTest`
  (all pre/post branches, including the late-annotation regression).

## Resolution — CI x86_64 runner (2026-10-12, C65)

The carried item is resolved for the CI path: the GitHub Actions `Android`
job runs this suite on x86_64, where Robolectric executes for real. The
first run made the never-executed suite reveal two real defects, both fixed:

1. `DemoFeed.demoBundle` still produced the pre-A5 shape (no
   `mission_snapshot`) while `RcSession.startFlightFromBundle` requires a
   self-digested snapshot → 5/6 tests failed. Fixed (`ce7302b`) and pinned
   by the host regression `DemoBundleShapeTest`.
2. `already_synced_flight_is_skipped_via_durable_receipts` compared
   `Result.success(data)` with a bare `Result.success()` — WorkManager's
   Result equality includes `outputData`, so it could never pass. Fixed
   (`ecd2f21`): assert the `Success` class plus the returned state.

Final: CI run
[38258861942](https://github.com/khakisketch/ksi-safety-edge/actions/runs/38258861942)
green — Python, Edge C++ and Android jobs all pass; the six WorkManager
tests **execute (0 skipped)** on x86_64, including the real enqueued-worker
execution through the test driver. Device-mode execution on the real RC
remains part of A9-4.

## Regression context (2026-10-11 user review)

`ALREADY_SYNCED` previously considered only artifacts, so an operator's
"false positive" opinion added AFTER the evidence sync was never uploaded.
Fixed in the same change: completion requires synced artifacts **and** no
pending annotations; `SyncScheduler.enqueuePending` and the app's
false-positive toggle both enqueue the flight; an rc-core regression test
(`AnnotationUploadTest.annotation_queued_after_full_sync_still_reaches_the_server`)
proves the uploader delivers it.
