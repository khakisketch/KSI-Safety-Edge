# DJI AutoSensing Device-Test-Only Probe — diagnostic README

Status: **HOST-ONLY record. No device contacted, no aircraft operated, no SDK
call that enables sensing. No device observation exists.** The probe files are
**unwired debug-only helpers: they have no UI, no entry point, no caller, and
no wiring into the app; release AARs exclude them, and they are NOT runnable
yet.** Making an observation later requires a
separately approved change (an explicit operator action calling `start()` /
`stop()`) plus fresh per-action human permission on a device day. This record
approves neither.

Parent records (not modified by this task):
`docs/verification/2026-10-27-dji-autosensing-vs-dfine-eval.md` (host API
observation + scoring requirements) and
`docs/verification/2026-10-27-existing-model-rights-review.md`.

## 1. Files (new only — no existing production file was modified)

- `android-rc/rc-msdk/src/debug/java/ksi/msdk/AutoSensingProbe.kt` — probe
  core. Debug source set only; release builds exclude it (§6). No DJI type
  in its declaration.
- `android-rc/rc-msdk/src/debug/java/ksi/msdk/AutoSensingDriver.kt` —
  attach/detach seam (`AutoSensingDriver`), fixed `ProbeReason` codes, and
  the DJI driver (`DjiAutoSensingDriver`). The only file referencing
  DJI types, all inside method bodies.
- `android-rc/rc-msdk/src/testDebug/java/ksi/msdk/AutoSensingProbeTest.kt`
  — deterministic host tests for the debug variant (fake driver + fake
  clock; one real-driver fail-closed test).
- This README (`android-rc/rc-msdk/AUTOSENSING_PROBE.md`).

No new dependencies. No build-config, manifest, UI, `PlatformLinks`, or
production-source change. The working tree already contains unrelated
changes from other sessions; the probe's contribution is exactly the four
paths above (all new), verifiable with
`git status --porcelain -- android-rc/rc-msdk/src/debug android-rc/rc-msdk/src/testDebug android-rc/rc-msdk/AUTOSENSING_PROBE.md`.

## 2. Why an isolated debug helper (approach decision)

Considered and rejected for this step:

- **Instrumentation test (`androidTest`)**: requires a device to mean
  anything; cannot be verified off-device. Rejected.
- **Debug activity / entry point now**: needs manifest and activity edits
  (existing files) and still needs a device to verify. Rejected — a future,
  separately approved task may add an explicit operator button that calls
  `start()`/`stop()` on this probe.
- **Wiring into `MainActivity`/telemetry/camera sources**: rejected —
  production behaviour must not change, and results must never leak into
  assessments, contracts, or the MOP.

Chosen: **unwired debug-only helpers**. They compile against the actual 5.18
API now (debug variant), their lifecycle/fencing/honesty/bounding/codes are
proven by host tests now, and release builds exclude them (§6). Any future
opt-in entry instantiates `AutoSensingProbe` and calls `start()`/`stop()`
explicitly. Until that separate approval + device permission exists, the
probe has zero callers and zero effect.

## 3. Compile-time API observation (host-measured, MSDK 5.18.0)

Method (host shell, no device, no SDK init): `javap` against
`com.dji:dji-sdk-v5-aircraft-provided:5.18.0`.

- `IntelligentFlightManager.getInstance()` returns
  `IIntelligentFlightManager`; the driver calls ONLY
  `addAutoSensingInfoListener` / `removeAutoSensingInfoListener` on it.
- `AutoSensingInfo`: `getTargets()` / `isAutoSensingEnable()` only. No
  timestamp, no frame id, no confidence, no camera/lens field.
- `AutoSensingTarget`: the driver reads ONLY `getTargetType()` (enum name)
  and rect null-vs-present (tri-state). `getLabel()` and `getTargetIndex()`
  appear nowhere in executable code (reviewed in
  `src/debug/java/ksi/msdk/AutoSensingDriver.kt`, the single DJI-touching
  file).
- `AutoSensingTargetType`: `UNKNOWN, PERSON, CAR, BOAT, CUSTOM`.
- `AutoSensingInfoListener`: single abstract `onAutoSensingInfoUpdate` (+
  defaults the driver ignores).

The driver never calls `init`, `destroy`, `initAutoSensingSource`,
`startAutoSensing`, `stopAutoSensing`, `selectIntelligentModel`, mission
managers, `registerApp`, `KeyManager.setValue`/`performAction`, commands,
video, uploads, or the app-key API (reviewed in the same single file).

**Registration-return vs device support.** A future `attach()` returning
true would mean only that the SDK add-listener call returned normally — it would NOT
prove the sensor is enabled, the model is valid, callbacks will arrive, or
any value is meaningful on that unit/firmware. Those stay device-gate facts
to be established on the day, and "no callbacks" stays honestly unknown.

## 4. Lifecycle and concurrency (fenced sessions)

```
IDLE --start()--> ATTACHING --callbacks--> OBSERVED
  |                   |     \--> (none) --> ATTACHED_WAITING
  |                   v
  +--stop()--> STOPPED <--stop()-----------+
FAILED --stop()--> STOPPED ; STOPPED/FAILED --start()--> ATTACHING (fresh)
```

ATTACHING is a real, externally visible state: a concurrent second `start()`
observes it (never a stale IDLE/STOPPED) and issues no vendor call.

- Construction attaches nothing (pinned by test). `start()` is idempotent
  while attaching/attached (never double-registers).
- At most ONE vendor call (attach, detach, or cleanup) is ever in flight,
  coordinated by a probe-side operation slot plus a driver-side vendor lock,
  so two attach operations can never race and cleanup can never remove a
  newer session's registration. A `start()` issued while any prior
  attach/detach/cleanup is in flight is REFUSED (`OPERATION_IN_FLIGHT`, no
  vendor call); while any prior removal or partial registration is
  unresolved it is REFUSED (`ORPHAN_PRESENT`, no vendor call). Both refusal
  paths are pinned by deterministic latch tests (schedules A and B below).
- A session is fenced by a generation counter claimed under a short lock.
  The probe's state lock is never held across a vendor call. Driver gate
  reads (`hasUnconfirmed`, `lastReason`) are lock-free volatile reads taken
  only when no probe vendor operation is in flight, so they cannot wait on
  an in-flight SDK call; the callback path takes only the short lock and
  never re-enters the driver. No claim is made about the SDK's own
  internal blocking behaviour — only about this probe's and this driver's
  locks; the driver is single-owner (this probe instance). A
  synchronous callback emitted inside `attach()` carries the claiming
  generation and is accepted and preserved (pinned by test); callbacks from
  a previous session, late callbacks after `stop()`, and stray callbacks
  while detached are ignored (pinned by tests, including a concurrent
  emit/stop teardown test and the schedule A/B tests).
- A `stop()` issued while an attach is in flight fences the session and
  reports removal as IN_FLIGHT (pending — explicitly never confirmed while
  the add can still complete); the in-flight `start()` flow owns the single
  vendor slot and performs the cleanup removal on return, recording the
  outcome. A `stop()` issued while a detach is in flight reports the ongoing
  state without piling on a second vendor call.
- Recovery from an unconfirmed removal is an explicit `stop()` (which
  retries removal). The driver retains the listener reference until the SDK
  removal call returns normally (never cleared on the failure path), and an add exception
  keeps the candidate as unconfirmed at once. The probe additionally keeps
  its own removal record, so a restart stays refused even if a driver
  reported clean after a failed removal (pinned by test).
- All mutation is in-memory and bounded: by default the last 32 observations
  are retained and at most 64 targets converted per callback (cap validated at construction;
  driver clamps defensively). Counts are session-scoped.

## 5. Honesty rules enforced by the probe

- **Receive-time ONLY.** Each record carries the injected receive clock
  (default `SystemClock.elapsedRealtime()`; null when the clock itself was
  unreadable). No capture-time, frame-sync, or confidence claim exists.
- **Target index opaque.** Never read; nothing is joined, ordered, or
  identified by index.
- **Rect unverified/opaque.** Counts only: confirmed-present,
  confirmed-absent (implicit), unreadable. No numbers retained or scored.
- **Labels never read by the probe.** `getLabel()` is never called and the
  probe does not copy or log those strings; this makes no claim about what
  the DJI SDK itself holds in memory.
- **Unreadable ≠ empty.** Null payload, null list, or list/getter failure
  yields an `Unreadable` record with a fixed code (`PAYLOAD_NULL`,
  `TARGETS_UNREADABLE`); `targetCount` stays null (unknown). A confirmed
  non-null empty list yields `targetCount = 0` — a payload fact, not a scene
  proof. Zero callbacks is `ATTACHED_WAITING` ("no callbacks observed —
  unknown, not no detections").
- **Truncation and skips counted.** The driver converts at most the
  negotiated cap (bounded loop, no full-list materialization);
  `totalReported` preserves the reported size, `truncated`/`skippedEntries`
  mark the rest. (Each state above has a direct host test; driver-side DJI
  conversion itself is device-gate, covered on-host by the bounding helpers
  `boundTake`/`effectiveCap` and by contract tests through the seam.)
- **Fixed codes only.** Failures surface as `ProbeReason` codes with fixed
  display text. Exception messages — which may carry paths or identifiers —
  are caught at the boundary, mapped to a code, and never stored. `Status`
  carries no free-text failure field (pinned by hostile-text tests).
  Refusals distinguish a busy slot (`OPERATION_IN_FLIGHT`: another
  attach/detach/cleanup is running) from an unconfirmed past
  (`ORPHAN_PRESENT`: a prior removal or partial registration is unresolved);
  a held registration denies a second attach (`ATTACH_DENIED`).
- **Attach success is local only.** A successful `start()`
  (ATTACHED_WAITING/OBSERVED, `attached` true) means the local SDK
  add-listener call returned normally for that session — explicitly NOT
  proof of device support or callback operation. Registration itself is
  reported as `SDK_REGISTRATION_UNCONFIRMED` when the flag is unreadable or
  negative — never a definitive claim about the SDK.
- **Removal honesty.** `Status.removalState` records only the probe's own
  removal-call history, reset on every claimed `start()` so a stale success
  can never be misread as current cleanup: NOT_ATTEMPTED (no removal call
  tried — including cleanup after a cleanly failed attach, where nothing
  had registered), IN_FLIGHT (a removal/cleanup call is running, or cleanup
  is pending after a fenced attach — nothing established), CALL_RETURNED
  (the DJI removal call returned normally), CALL_FAILED (the last attempt
  threw/failed or remains unconfirmed). CALL_RETURNED is explicitly NOT
  device-verified removal and NOT proof that callback delivery stopped —
  DJI declares the call void, and on-device behaviour is unknown and
  unclaimed. A detach against nothing held returns NOTHING_HELD and maps to
  NOT_ATTEMPTED, never to a completed removal.
- **Key secrecy basis.** The driver never references the key API at all:
  the single gate is a guarded direct read of the SDK registration flag
  (unreadable and negative both report unconfirmed — never proof of an
  unregistered SDK), reviewable in the one DJI-touching file — not a grep
  guarantee. The probe does not read or retain credentials, device identifiers,
  or imagery, and does not log or upload its summaries. Counts and target types
  may still be sensitive operational data.

## 6. Host verification (this task — no device, no network, no SDK init)

Recorded from the repo root; exact outputs are in the session log:

1. `bash scripts/android_host_tests.sh :rc-msdk:testDebugUnitTest` —
   **106/106 green** (65 pre-existing + 41 new `AutoSensingProbeTest` cases:
   lifecycle, idempotence, cap validation, ATTACHING visibility,
   sync-callback acceptance, schedule A (stop-during-attach + racing start
   refused + single cleanup + fresh session), clean-attach-failure cleanup
   (NOT_ATTEMPTED), partial-registration cleanup to confirmed, schedule B
   (start-during-detach refused + fresh session), concurrent teardown,
   old-session fencing, per-session removal reset after success, stale-error reset, denied
   second attach, detach failure + retry, orphan refusal + recovery,
   confirmed-empty vs unreadable payload/list/enable/rect/clock, allowlist,
   skip counts, window bounding, huge totals, hostile text, real-driver host
   fail-closed).
2. `:rc-msdk:testReleaseUnitTest` runs the 65 pre-existing tests with **no
   AutoSensing cases** — the debug-only tests do not enter release.
3. `bundleDebugAar` contains `ksi/msdk/AutoSensing*.class`;
   `bundleReleaseAar` does not (release exclusion evidence).
4. `javap` re-check of the §3 signatures against the resolved 5.18.0
   `-provided` jar (compile-time signatures only, no device claim).
5. `bash scripts/android_host_tests.sh :rc-app:compileDeviceDebugKotlin --offline`
   completed successfully on the host. It did not install or launch an app.
6. `git status --porcelain` scoped to the four diagnostic paths identifies the
   new files. The wider working tree contains pre-existing changes.

## 7. What remains before any device observation (proposal only)

No approval is implied. Each of install / connection / sensor enabling /
permission grant / key use / session change needs fresh, explicit human
authorization; propellers removed, no flight, aircraft secured. The probe
performs NONE of those; a future entry only calls `start()` / `stop()`.

Still unimplemented: the entry point itself (e.g. an explicit
operator-initiated debug action — no manual runtime access exists from this
task), any display of `status()`, and any recording procedure. On the day,
the operator would: attach after registration → present the scene for a
bounded, pre-declared window → read `status()` → detach and check
`removalState`: proceed only on CALL_RETURNED (understood as "the SDK call
returned normally", not as verified removal) or NOT_ATTEMPTED (no removal
was attempted because nothing had registered); on CALL_FAILED or IN_FLIGHT,
record the residual risk and restart the app before any rerun. Record by hand: receive-clock domain, `callbackCount`,
`seq`, `enableReported`, `targetCount`, allowlisted `types`,
`rectPresentCount`/`rectUnknownCount`, `skippedEntries`, `truncated`, or
"no callbacks — unknown". Never record labels, rect numbers, indices,
credentials, device identifiers, imagery, or personal content. Nothing
uploads; nothing is written to disk by the probe.

Stop conditions (any one ends the attempt): missing/withdrawn permission,
unexpected credential/purchase/activation prompt, any aircraft-movement
suggestion, overheat/battery/physical hazard, model expired/invalid, no
callbacks with ambiguity left unresolved, or clock doubt. The numbers are
receive-time presence evidence only — unscored, unpaired, and never inputs
to Manifold ticks, assessments, or contracts.

## 8. Residual constraints / non-goals

- Whether callbacks require manager initialisation from elsewhere on the
  device is unobserved; the probe deliberately never calls `init()` (a state
  change of its own), so a no-callbacks result stays honestly unknown.
- Unit support (subtype/firmware), enable-flag meaning, index stability,
  rect units/origin, label vocabulary, model list/validity: all unobserved.
- Host tests prove logic, not device behaviour: real-driver attach success
  and live-object conversion are DEVICE GATE and execute only on the RC
  after explicit permission.
- Build note: explicit `object : AutoSensingInfoListener { override … }`
  is rejected by Kotlin 2.0.21/K2 ("overrides nothing") while the
  SAM-constructor form compiles against identical stubs (javac
  cross-checked). The shipped form is intentional.
- No frame pairing, geometry validation, clock calibration, GT, retention,
  or consent artifacts exist — any future comparison must satisfy the
  scoring requirements in the parent eval record first, or stay unscorable.
