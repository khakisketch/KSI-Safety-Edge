# round10-cockpit-readability — Verification receipt

Date: 2026-10-15 · Status: uncommitted · Product state: implementation/build verification plus browser-prototype rendering; no device acceptance

## Source and scope

- Baseline `field-shell.html`: SHA-256 `daa5aaeac5c543138ed7760289fd74794775c8b448748dc1e23a4b37ddf10b6f`.
- Final `field-shell.html`: SHA-256 `328898dae0cd3b7b6f8443c5fefc82cfe3f3ebcf17a49f44c235a90d3f876b66`.
- Android files changed: `MainActivity.kt`, `activity_main.xml`, and `strings.xml`; the dismissible error banner now has an explicit 48dp minimum height. Design scope is `design/rc-pilot-shell/`. No API, contract, permission, data, business-rule or safety-authority changes. No commit.
- The 85–92% video area was an estimate from manual screenshots, not a published spec; this round did not use it as a sizing target. No third-party imagery/icons/code were incorporated. Unpublished touch-target measurements were not presented as standards.

## What changed

- Kept the broad, video-centered cockpit. The eight ordered B-1 facts are summarized in three severity-aware groups (record/MOP/aircraft; internet/recorder/evidence; observation/recent summary); all eight full facts remain in three grouped, on-demand details sections.
- Existing KSI `PROBLEM` and `ATTENTION` states can surface with their existing `■` / `▲` meaning; the prototype hides the banner for normal, no-flight and unknown-only states. No new telemetry or safety warnings were created.
- Added/clarified video-input and MOP labels, the DJI Pilot 2 flight-control boundary, and local-record-ending copy/confirmation/accessibility description. The app-level title is now a semantic h1 without a visual change.

## Reference decisions

- Adapted slim status zoning and compact indicators over a dominant video view; detail-on-demand; state-driven problem/caution distinction. The browser uses a labeled status-details control; the native app retains its existing operational-status panel entry.
- Kept the preflight checklist as a separate mission entry gate. Did not add aircraft-control authority or vendor icons.
- Rejected H/D numeric HUD, map/attitude tile, battery, GNSS/RTK, mode, altitude, distance, aircraft RF and DJI failsafe indicators because the current adapter/contracts do not provide those facts. No placeholders or mock telemetry were added.

## Captures and rendered geometry

Source: `http://127.0.0.1:8765/field-shell.html`, Playwright CLI session `round10-verify-20261015` in CSS pixels. The Native Browser was disconnected; the requested renders were captured in a headless browser. Captures are browser-prototype evidence only.

- Main destination matrix: five destinations (`cockpit`, `mission`, `review`, `evidence`, `settings`) at **390×844, 960×600, 1024×768, and 1280×800** — 20 screenshots under `verified-final-20261015/screens/<viewport>/`.
- Settled cockpit state captures at 960×600: normal, no active record, observation-late, problem, and unknown; plus all seven video-input states (disconnected, connecting, no signal, no frames, recent, stale, and receive-time unknown).
- Details-open screenshots at 390×844 and 960×600: `screens/<viewport>/details-open-2col-11px.png`.

- The prior `before/` and `after/` captures remain preserved. The current matrix geometry and audit records are in `verified-final-20261015/checks/`.

- All 20 destination/viewport runs had one active page, no document/body horizontal overflow, and no clipping in the three quick-group labels or values. Five scenarios were exercised at all four viewports (20 geometry/state combinations); no quick-group/priority-line overlap occurred.
- Details-open at 960×600: panel x=499.2–947.2/y=184.8–526.4; the local-record end button ends at y=589.6 and does not overlap the panel. At 390×844 the panel fits x=7.2–382.8/y=368–748, with no end-button overlap. The three detail groups display two columns and scroll within the panel.
- Captures were opened and visually inspected at all four viewport sizes and for all requested status/video states.

## Accessibility and interaction

- axe-core 4.12.1: **0 violations** in 20 destination/viewport audits and the final details-open checks at 390×844 and 960×600. A color-contrast audit remains incomplete for the cockpit gradient/short glyphs; it is not a violation.
- Details opens/closes by its control; Escape closes and restores focus. MOP dock and navigation drawer also close on Escape and restore focus. Video connect/disconnect actions update the demo state. Focus mode preserves the three glance groups. KSI end feedback says the device recording/aircraft state was not changed, and the local-record status stays unchanged.
- No-flight state hides the priority line and shows `KSI 기록 없음`. Unknown state shows `기체 알 수 없음` without a fabricated warning. Observation-late attention uses `▲` and explicitly says data delay is not a safety judgment or flight warning. Problem uses `■`.
- Browser page errors and console errors: 0 (`checks/browser-errors.json`, `checks/browser-console.txt`). Mouse and keyboard were exercised; physical touch was not.

## Android checks

- Android structural check in this pass: all 8 resource XML files parse; the 44 `Button`/`Spinner`/`EditText` controls in `activity_main.xml` have explicit heights ≥48dp, and the dismissible `tvError` banner has `minHeight=48dp` (`checks/android-xml-structure.json`). No Android device render was attempted, per scope.
- Existing host test/lint/assemble results below were recorded before the final one-line `tvError` min-height adjustment and were not rerun: `bash scripts/android_host_tests.sh` reported 191 tests, 0 failures/errors, 6 documented aarch64 skips; bench/device lint and assemble passed. x86_64 CI remains the execution authority for skipped WorkManager/Robolectric tests.
- Native RC Plus 2 7″ rendering, touch/glove use, sunlight, live-video contrast and field acceptance are not verified; A9-4 remains required.

## Integrity

- `verified-final-20261015/checks/capture-sha256.txt` contains hashes for the final prototype and all final PNG captures.
- The Impeccable detector ran once. It flagged 10.56px detail-group headings; they were raised to 11.52px and computed-style-checked at 390×844 and 960×600. Its advisory about numbered section labels was retained because these labels communicate ordered mission/review workflows.
- `git diff --check`: pass. The pre-existing untracked `.impeccable/` directory was preserved. No commit was made.
