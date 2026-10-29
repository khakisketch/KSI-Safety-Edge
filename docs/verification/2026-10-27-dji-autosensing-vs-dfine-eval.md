# DJI AutoSensing vs Manifold D-FINE — host-only eval record (2026-10-27)

Status: **HOST-ONLY record. No device contacted, no aircraft operated, no SDK call
that enables sensing. No device comparison was run.** No automated comparison,
capture/logger, or scoring code is delivered by this record.

## 1. Compile-time API observation (host-measured, MSDK 5.18.0)

Method (host shell, no device, no SDK init): `javap` against the resolved compile stubs.

- Artifact: `com.dji:dji-sdk-v5-aircraft-provided:5.18.0`
- Jar inspected:
  `~/.gradle/caches/modules-2/files-2.1/com.dji/dji-sdk-v5-aircraft-provided/5.18.0/`
  `9e9b804f34cbc8ca97155c984d844f4ec6d38b2f/dji-sdk-v5-aircraft-provided-5.18.0.jar`
  (date stamp inside jar: 2026-05-21).
- Real-aircraft AAR coordinates in `android-rc/rc-msdk/build.gradle.kts`:
  `com.dji:dji-sdk-v5-aircraft:5.18.0` + `compileOnly ...-provided:5.18.0` +
  `com.dji:dji-sdk-v5-networkImp:5.18.0`. Host inspection used the `-provided` jar only.
- Search for `AutoSensing|autoSensing` in Android app source and build files returned
  **zero hits** at the time of this inspection: no app wiring or listener was found.

### 1a. What the stubs declare (exact signatures, not device behaviour)

`dji.v5.manager.interfaces.IIntelligentFlightManager` (interface):

```text
void init();
void destroy();
void addIntelligentFlightInfoListener(IntelligentFlightInfoListener);
void removeIntelligentFlightInfoListener(IntelligentFlightInfoListener);
void addAutoSensingInfoListener(AutoSensingInfoListener);
void removeAutoSensingInfoListener(AutoSensingInfoListener);
void startAutoSensing(CommonCallbacks.CompletionCallback);
void stopAutoSensing(CommonCallbacks.CompletionCallback);
void selectIntelligentModel(int, CommonCallbacks.CompletionCallback);
IPOIMissionManager getPOIMissionManager();
IFlyToMissionManager getFlyToMissionManager();
ISpotLightManager getSpotLightManager();
ISmartTrackMissionManager getSmartTrackMissionManager();
```

`dji.v5.manager.intelligent.IntelligentFlightManager implements IIntelligentFlightManager`
additionally exposes public `initAutoSensingSource()`.

`dji.v5.manager.intelligent.AutoSensingInfo`:

```text
List<AutoSensingTarget> getTargets();  void setTargets(List<AutoSensingTarget>);
boolean isAutoSensingEnable();          void setAutoSensingEnable(boolean);
```

No timestamp field. No frame id. No confidence field. No camera/lens field.

`dji.v5.manager.intelligent.AutoSensingTarget`:

```text
AutoSensingTargetType getTargetType();  void setTargetType(AutoSensingTargetType);
int getTargetIndex();                   void setTargetIndex(int);
DoubleRect getRect();                   void setRect(DoubleRect);
String getLabel();                      void setLabel(String);
```

No score / confidence. No timestamp. No frame id. No track-age.

`dji.v5.manager.intelligent.AutoSensingTargetType` (enum):
`UNKNOWN, PERSON, CAR, BOAT, CUSTOM` (+ `value()`, `find(int)`,
`find(MultipleTrackDetectedType)` bridge).

`dji.v5.manager.intelligent.AutoSensingInfoListener` (interface):

```text
void onAutoSensingInfoUpdate(AutoSensingInfo);
default void onTrackingTargetUpdate(AutoSensingTarget);
default void onIntelligentModelUpdate(List<IntelligentModel>);
default void onRunningIntelligentModelUpdate(int);
```

`dji.v5.manager.intelligent.IntelligentModel`:

```text
int getModelIndex();  String getModelUuid();
boolean isValid();    boolean isExpired();
long getSignedUtc();  long getExpiredUtc();
long getSignedVersion();  String getSignedName();
```

`dji.sdk.keyvalue.value.common.DoubleRect`: `Double x, y, width, height`.
Units / origin are **not** declared in the stub signature.

Low-level frame-attached path (same jar, for context — NOT the listener contract):
`dji.v5.manager.datacenter.camera.FrameExtInfoAI` carries
`version, timestamp(long), frameIdType, trackId, targetNum` and
`PerTargetInfo[] { targetId:int, targetState:int, targetType:int,
targetFiledBBoxContent, targetFiledPositionContent, }`, plus `getCounter()` map.
The compile-only jar also contains a package-private `M4AutoSensingManager`, but its
inspected methods begin with stubbed early returns; subsequent unreachable bytecode is
**not evidence of the runtime subscription or rectangle conversion**. No runtime data
path, rectangle origin, or units are inferred from that bytecode.

### 1b. What the stubs do NOT establish (must not be assumed)

- Whether `startAutoSensing` / callbacks function on the actual Matrice 4D unit and
  firmware present on any future device day. Compile presence ≠ device support.
- Meaning or stability of `targetIndex` across callbacks. Treat as uninterpreted
  until device evidence says otherwise; do not join observations by index.
- Any frame timestamp or confidence for listener results — both are absent from
  `AutoSensingInfo/Target`.
- `DoubleRect` semantics (centre vs corner, normalisation, lens/zoom dependence).
- `label` vocabulary for `CUSTOM` / `UNKNOWN`, per-model thresholds, or model
  list/validity on the unit.
- Exact airframe subtype / firmware / lens (10-21 user-specified airframe is Matrice 4D;
  exact subtype/firmware unconfirmed this session).

## 2. Existing Manifold / D-FINE baseline (corrected facts, no new claims)

- D-FINE preprocessing + decode (`edge/include/ksi_safety/perception/dfine.hpp`,
  `edge/src/perception/dfine.cpp`): 640×640 RGB, /255, no normalize, no pad, per-query
  sigmoid + argmax, strict `>` threshold from `DfineConfig`, NMS-free fixed queries,
  ascending query order. Host-golden-tested. No ONNX/TensorRT execution in that unit.
- Reference-input inference numbers (`docs/verification/2026-10-27-manifold-measurement.md`
  §1, Manifold 3, no camera): fp16 end-to-end mean 20.27 ms / p95 25.61 ms on the staged
  corpus. **Correction:** the engine, raw-RGB inputs, and reference expectation files are
  **pre-staged device artifacts** (`/open_app/ksi-dev/dfine-export-20261008/`), per
  `edge/README.md` §TensorRT scope — **not checked into this repository**. No
  live-camera or field accuracy is claimed.
- Evidence-path numbers in the same measurement note (§2) used a 1080p replay with a
  stub detector on real eMMC, including an isolated window-close tick (~185 ms in that
  setup). **Correction:** that duration is an isolated eMMC/stub-detector observation,
  not a live-camera result. **Live camera queue absorption and `drops` behaviour under
  live input were NOT measured** — the prior assertion that window-close ticks are
  absorbed by the bounded queue on live input is removed. The live `drops` counter
  remains the instrument that *would* show pressure once live input exists; it has not
  done so.
- **No live-camera / field accuracy measurement is established for this path.** `edge/psdk-app/src/main.cpp` `OnRgbImage`
  sets `sensor_timestamp_ns = 0` and match grades stay at `RECEIVE_*`. `DjiCore_Init`
  waits for the aircraft link — without the aircraft on the payload port there are no
  live numbers. DJI's [public PSDK header](https://github.com/dji-sdk/Payload-SDK/blob/master/psdk_lib/include/dji_liveview.h)
  marks the SEI stream APIs unavailable in that header's current version. It does not
  establish the installed device SDK version. Our inspected code has no SEI path;
  it uses `DjiLiveview_*` RGB + FC subscription provenance.
- Source statement: Manifold reference numbers come from pre-staged reference files via
  `dfine_trt_smoke` / file-paced `replay_run`; `scripts/mop_loopback_check.sh` +
  `scripts/mop_edge_probe.py` exercise the MOP contract over TCP only, not the live
  camera→D-FINE path; whether the DJI detector operates on replayed frames is **not
  established**. DJI detections, if ever observed, would arrive via the MSDK path on
  live aircraft frames (unverified on the unit).
- Manifold processes independently of the RC link: per
  `docs/plans/2026-10-21-manifold-role-contract.md` §§6-7, communication loss is not
  input loss — the Edge continues local observation on video/attitude input while MOP
  is down, spooling for later retransmit. An RC-link loss therefore must never be read
  as Manifold having stopped. DJI AutoSensing, if ever compared, would remain an
  external source — never an input to Manifold ticks, never a reason to raise time
  grades or promote SHADOW.

## 3. What is NOT delivered

- No automated comparison, capture/logger, or scoring code is delivered by this record;
  no synthetic-test result is presented as a device measurement. A separate
  [debug-only listener helper](../../android-rc/rc-msdk/AUTOSENSING_PROBE.md) has since
  been host-tested; it has no app entry point and is not runnable on a device yet.
- No device comparison was run. No JSONL logs, screens, GT files, clock-calibration
  records, or numeric comparison results exist under this task.
- No privacy enforcement is claimed. No sanitizer, allowlist store, retention system,
  or consent record is delivered. Any future acquisition involving human subjects,
  credentials, paths, or imagery would need its own bounded allowlist, retention bound,
  and consent — none of which exist here.
- No frame pairing and no numeric metrics are performed by any delivered code. Any
  mention below of recall, false positives, IoU, or thresholds is a *requirement note
  for future work*, not a result and not a validated method.

## 4. Requirements for any future scoring (not a method, not validated)

If a future, separately approved diagnostic step ever produces paired observations, a
credible comparison would at minimum require — otherwise the honest result is
**unscorable**:

- **Independent image correspondence:** each scored detection must be traceable to the
  same image (same exposure / same frame provenance) or to an independently proven
  fixed static scene with recorded lens/zoom/geometry. Calibrated receive-time
  proximity **alone is NOT sufficient** to claim two detections saw the same thing.
- **Exposure/frame provenance or honest unscorable:** verified exposure/frame identity
  where claimed; where it is absent (as in the current PSDK liveview path and the DJI
  listener path, both without verified capture timestamps), the comparison must stay
  unscorable unless an independent static-scene correspondence is established. Do not
  interpolate a joint track from receive times.
- **Independent ground truth for that image/scene:** human-labelled boxes on the saved
  frames (or a pre-registered static layout proven fixed for the session), with method,
  labeller, frame ids, lens/zoom, and file hash recorded. Neither detector's output may
  serve as GT. Scope here is limited to whatever GT a future session explicitly creates;
  no claim is made about GT existing or not existing elsewhere.
- **Verified geometry before any box comparison:** rect units/origin validated on the
  same lens/zoom; otherwise boxes stay opaque and unscored. No invented normalisation,
  no fixed IoU gate presented as validated.
- **Verified time basis with stated uncertainty, without overclaiming:** any receive-time
  statement must name the clock domains, calibration method, and bounded uncertainty,
  and must be labelled receive-time proximity only — never frame/exposure alignment.
  Unbounded or unverified uncertainty fails closed to uncalibrated/unscored.
- **Detector-specific gap attribution:** communication, video-input, restart, and model
  states recorded per side from contemporaneous status/logs; an RC-side gap is RC-side
  only given independent Manifold processing above.

No fixed thresholds, gains, or procedures are endorsed here. Any numbers appearing in a
future proposal must be labelled provisional and justified by device evidence, not by
this record.

## 5. Diagnostic preparation and future device step

The [debug-only helper](../../android-rc/rc-msdk/AUTOSENSING_PROBE.md) can attach
`addAutoSensingInfoListener` when called. It does not call `startAutoSensing`,
`selectIntelligentModel`, or camera/aircraft commands, and retains only bounded
in-memory summaries labelled with receive time. It remains separate from the
independent Manifold path. It has no app entry point, so a runnable diagnostic and
any associated GT, calibration, retention and consent work are **not yet available**.
Any future device installation and connection require explicit permission, propellers
removed, and no flight.

Safe stops that would apply to any such future step: missing/withdrawn permission,
unexpected credential/purchase/activation prompt, any aircraft-movement suggestion,
overheat/battery/physical hazard, propellers not confirmed removed, model
`expired/!valid`, absent callbacks with scene in frame, unconfirmed geometry,
lens/zoom drift, or clock/calibration doubt.

## 6. Host verification for this record

- `javap` observations in §1a re-checked against the resolved 5.18.0 `-provided` jar
  (compile-time signatures only; no device claim). The Android-source AutoSensing grep
  was re-checked and found no app wiring at the time.
- `edge/README.md` §§TensorRT scope/limits, chain/session scope, and core rule
  re-read for the §2 corrections (pre-staged artifacts; no live-queue absorption claim).
- `docs/product-state.md`, `docs/plans/2026-10-21-manifold-role-contract.md`,
  `docs/verification/2026-10-27-manifold-measurement.md`,
  `docs/verification/a9-4-device-day-runbook.md`, `dev-harness-runbook.md` consulted as
  standing context.
- `bash scripts/run_all_tests.sh` ran **228 tests, all passing** (contracts · libs ·
  core · harness). No device activity, contract/runtime change, or field-data upload
  was performed.

## 7. Remaining blockers

- Exact Matrice 4D subtype / firmware / lens unconfirmed.
- DJI on-unit availability, index meaning, rect semantics, label vocabulary,
  model list/validity — all unobserved.
- No runnable diagnostic entry point or log/scoring implementation, no GT for this
  comparison, no clock-calibration procedure, no retention/consent approvals.
- No live PSDK-camera numbers without the aircraft on the payload port; live queue
  pressure and field accuracy unmeasured.
- Per-action explicit permissions outstanding for any future device step.
