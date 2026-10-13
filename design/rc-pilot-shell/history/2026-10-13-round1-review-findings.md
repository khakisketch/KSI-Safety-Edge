# Independent design review — initial findings

## Verdict

**Refine before field use.** The prototype preserves the important status meanings and honesty cues, but the problem state does not put recovery controls at the operator’s point of attention, and its urgent statuses have too little visual priority.

## Visual evidence inspected

- `01-observe-normal.png`: the eight status cells distinguish flight, MOP, aircraft, internet, recorder, evidence, AI freshness, and the event summary. The 3 unsynced items and the “사건 0건 — 정상 기록일 수 있음 / 안전 확인 아님” warning are visible. The video panel occupies most of the left column, and its detailed link controls continue below the captured viewport.
- `02-observe-problem.png`: “끊김” with an 18.4-second heartbeat and “녹화 오류” are correctly identified; AI delay is marked as attention. Their action guidance is small text in otherwise uniform cards. At the bottom edge, only the first row of the separate link-facts list begins; MOP recovery controls are farther down and are not visible.
- `03-observe-no-flight.png`: “비행 없음” and the sync → bundle → start sequence are clear. The MOP, aircraft, and internet states remain separate.
- `04-mission-prep.png`: mission identity, bundle, revision, zone, and the sync / download / prepare / apply / reconcile / flight actions are grouped coherently. Controls have generous hit areas and the simulated status is explicit.
- `05-flight-review.png`: flight and event navigation, append-only false-positive marking, local/server evidence actions, and the “0 events is not safety confirmation” rule are all visible. The evidence preview is honestly labeled as unconnected.
- `06-evidence-sync.png`: “3건” is prominent and each row says “미동기화”; Core configuration and server state remain distinct and unknown where appropriate.
- `07-settings-dev.png`: receipt-time approximation, unconverted Edge monotonic time, missing anchor, threshold caveat, and demo isolation are visible.
- `08-observe-dark.png`: the dark palette keeps the status board and three-column work area distinct. Secondary labels and telemetry remain noticeably small, matching the light-mode concern.

## What is working and must be preserved

- All five areas are reachable from the persistent navigation; source inspection found the listed mission, link, video, review, evidence, sync, Core-config, and demo-bench actions represented by controls.
- Statuses follow the fixed order and severity symbols. The problem capture does not present a dropped MOP link as normal.
- Aircraft, MOP link, and internet have separate status cells and separate fact rows.
- `[SIMULATED]`, the receipt-time approximation, unknown server/local completeness, offline-recording guidance, and the fixed zero-event honesty copy are retained across the relevant views.
- The no-flight state gives a next-step sequence; mission preparation and review actions are large and grouped by task; evidence count and per-row sync state are not hidden.

## Prioritized defects

### F-01 · P1 — Problem and attention states do not lead the scan

- **Screen/state:** Observe, problem (`02-observe-problem.png`); also attention status in the same state.
- **What is wrong:** MOP “끊김” and recorder “녹화 오류” use the same card layout as informational statuses. Severity is conveyed mainly by a small colored label; the recovery guidance sits in small copy beneath the value.
- **User impact:** During a short glance, an operator has to inspect several equal-weight cells to find the fault and its next action.
- **Desired result:** Make problem and attention cards visibly distinct, increase the hierarchy of their action guidance, and keep the contract’s exact statuses, severity, and action meanings.

### F-02 · P1 — MOP recovery controls are below the first viewport

- **Screen/state:** Observe, problem and normal (`01-observe-normal.png`, `02-observe-problem.png`, `03-observe-no-flight.png`).
- **What is wrong:** The Connect, Disconnect, and Retry unacked controls follow a large simulated video area, its notes, three link-fact rows, metrics, and guidance. In the 1200px captures the first fact row only begins at the lower edge; the actions are off-screen.
- **User impact:** In the disconnected state the operator can read “링크 연결 후 재전송 확인” but must scroll to perform it, adding a reach and a glove operation during flight.
- **Desired result:** Put the existing MOP controls immediately after the status summary and reduce the empty video-state area enough to give the operational content more room. Do not duplicate controls or add an action.

### F-03 · P2 — Small telemetry and supporting copy are hard to scan outdoors

- **Screen/state:** Observe and dark observe (`01`, `02`, `08`); smaller metadata also appears on mission, review, evidence, and settings (`04`–`07`).
- **What is wrong:** Several status details, feed timestamps, metric labels, and supporting notes use roughly 10–12px CSS text. The palette has adequate light/dark separation in the supplied captures, but these details remain visually fine for a sunlight-first, quick-glance interface.
- **User impact:** Operators may need to move closer or spend longer reading details and timestamps.
- **Desired result:** Raise the smallest operational text and strengthen the status/action hierarchy while retaining compact monospaced telemetry and the existing content.

## Refinement tracking

| Finding | Source change | Tracking |
|---|---|---|
| F-01 | Added distinct problem/attention surfaces and stronger action text. | Implemented; visual confirmation open. |
| F-02 | Moved the existing three MOP controls above the video panel and shortened the no-frame area. | Implemented; visual confirmation open. |
| F-03 | Increased small status, metadata, timestamp, and supporting text sizes. | Implemented; overflow and visual confirmation open. |

## Render evidence

No after-captures were produced. The prescribed project export command was blocked because this session cannot reach the local export service. Layout, overflow, and color changes therefore remain unverified in a rendered result.

Static post-change check: all five navigation targets remain; the 26 `data-action` controls remain unique, the Core-config form submit is still present, and the document has no duplicate `id` or `data-od-id` values. The fixed zero-event and receipt-time copy remains in the source. No browser interaction was run.
