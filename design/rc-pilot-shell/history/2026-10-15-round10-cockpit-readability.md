# round10 — Cockpit readability and status hierarchy

Date: 2026-10-15 · Baseline HTML: `daa5aaeac5c543138ed7760289fd74794775c8b448748dc1e23a4b37ddf10b6f` · Final HTML: `328898dae0cd3b7b6f8443c5fefc82cfe3f3ebcf17a49f44c235a90d3f876b66` · Status: uncommitted

## Scope and intent

- Improve the RC cockpit’s status hierarchy and Korean readability while preserving its wide, video-centered layout and connected mission/review/evidence/settings workflows.
- Preserve the DJI Pilot 2 flight-control boundary; clarify that KSI’s end action closes local observation/recording, not aircraft flight.
- Use only existing KSI, link, aircraft-connectivity and video-input facts. No API, B-1 severity, permission, data, safety or business-rule changes.
- Assigned files: Android cockpit layout/activity/strings and `design/rc-pilot-shell/` artifacts only. No commit.

## Changes

- Reorganized all eight ordered B-1 facts into three compact cockpit groups: record/MOP/aircraft, internet/recorder/evidence, and observation/recent summary. All eight full facts remain in three grouped sections in the on-demand details panel.
- Problem and attention use the existing severity contract and distinct `■` / `▲` markers. A compact priority line appears for existing non-INFO states; it is absent for normal, no-active-record and unknown-only states. Detailed source facts remain available on demand.
- Added an explicit `상태 상세` control in the browser prototype and made the native operational-status control correspond to the same detail-on-demand task. Browser details can close by the same toggle or Escape; focus returns to the invoking control.
- Clarified video-input/MOP action labels, put `DJI Pilot 2 · 비행 조종` next to the live-view controls, and made `KSI 로컬 기록 종료` explicitly local-only through native confirmation/accessibility copy and prototype feedback. Prototype actions remain `[SIMULATED]` and do not change device state.
- Kept KSI’s recorded-state summary minimal in the glance strip. The video panel uses existing freshness states; timing/count and display/decoder caveats stay in the corresponding copy.

## Reference patterns: transferred and rejected

- **Transferred as mental models:** DJI Pilot 2 / RC Plus slim status zones over a dominant live view; DJI/QGroundControl compact indicators with detail-on-demand; QGroundControl’s state-driven message entry; Autel’s visually distinct problem/caution/neutral states. The implementation uses KSI’s own facts, typography and existing B-1 severity—not vendor assets or copied icons.
- **Kept as a separate workflow:** the preflight checklist stays in the mission entry flow. No checklist was added over the in-flight video or represented as a KSI flight-safety approval.
- **Rejected as unsupported or outside KSI authority:** DJI Fly’s H/D numbers and map/attitude tile, vendor mode/GNSS/RF/battery indicators, and any altitude/distance/flight-mode/failsafe values. The current Android adapter does not provide these telemetry facts; none were invented or mocked. No aircraft-control actions were added.
- **Not treated as specifications:** the ~85–92% video-share estimate was a visual estimate from manual screenshots, not a published standard, and was not used as a target. Reference touch-target measurements were unpublished; no vendor touch-size standard is claimed. No protected imagery, icons, fonts or external code were imported.

## Verification

- Final captures and checks: `captures/round10-cockpit-readability/verified-final-20261015/`.
- Twenty destination renders cover 390×844, 960×600, 1024×768, and 1280×800. Five B-1 scenarios were exercised at all four sizes; seven video-input states were exercised and captured at 960×600. The unknown aircraft stays `알 수 없음` without a fabricated alert.
- Across all 20 destination/state geometry runs, document/body horizontal overflow was false, quick-group labels and values did not clip horizontally, and the priority line did not overlap the group row. The details panel at 960×600 and 390×844 does not overlap the KSI local-record end action; the full eight facts are scrollable in their three groups.
- Browser interactions exercised: details open/close and Escape focus restoration; MOP dock and navigation drawer Escape/focus restoration; video connect/disconnect; focus mode; local-record end feedback with the displayed record state unchanged. Browser page errors and console errors: 0.
- axe-core 4.12.1: **0 violations** for 20 destination/viewport checks and final details-open checks at 390×844 and 960×600. Color contrast remains incomplete for the cockpit gradient/short symbols, not a violation. JSON is under `verified-final-20261015/checks/`.
- Android structural check: all 8 resource XML files parse; all 44 layout `Button`/`Spinner`/`EditText` controls are explicitly ≥48dp, and the programmatically dismissible error banner now has `minHeight=48dp`. Existing host test/lint/APK results were run before this final XML attribute and were not repeated; this pass was limited to XML structure as scoped.
- The Impeccable detector ran once. Its 10.56px detail-group heading warning was fixed to 11.52px and confirmed by computed-style measurements at 390×844 and 960×600. Its numbered-section advisory was retained because those labels indicate ordered user workflows.

## Boundaries and remaining validation

- These browser screenshots verify only the HTML prototype, not Android rendering or RC Plus 2. Native 7″/Android layout, hardware touch, gloves, sunlight, live video and field acceptance remain unverified and belong to A9-4.
- Browser mouse and keyboard input were exercised; physical touch/swipe was not. The prototypes’ link/video/record controls remain demonstrations, not server/device evidence.
- No battery, GPS/GNSS/RTK, flight mode, altitude, distance, aircraft RF or DJI failsafe values were added. No contracts, API, permissions, business rules or protected evidence were changed. The pre-existing untracked `.impeccable/` directory was preserved.
