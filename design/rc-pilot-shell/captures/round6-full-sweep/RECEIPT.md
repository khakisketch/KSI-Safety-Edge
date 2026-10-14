# round6-full-sweep — capture and verification receipt

Date: 2026-10-14 · Status: uncommitted workspace revision · Surface: `field-shell.html`

## Revision and capture set

- Before HTML SHA-256: `6e483b9e790cc93ab7c0584f8d0dbe234548f7c75e47cf0a5e912f3494528108`.
- Delivered HTML SHA-256: `7b8c41241415bb8f289758ed0bedb483f94e9ca903968d16fe3b5934378d13df`.
- `before/`: 18 captures — six screens at 1920×1200 in light/dark, plus six light screens at 390×844.
- `after/`: same 18-screen matrix plus three state captures: completed-flight receive-time interval, zero-event flight, and persistent mission-sync feedback.
- `CAPTURE_SHA256SUMS.txt` contains hashes for all 39 before/after screenshots and the separate cockpit gradient sampling image.
- Renderer: `bash scripts/design_preview_server.sh` at `http://127.0.0.1:8765/field-shell.html`; captures taken with agent-browser managed Chrome at exact CSS viewports.
- The Review-pane browser screenshot endpoint refused capture because it did not consider the tab visible. The supported agent-browser renderer was used instead; the official screenshots and contact sheets were inspected. No native-device render was available.

## Visual and functional verification

- All 18 screen-matrix captures are exact-size PNGs; 3 extra states are 1920×1200.
- Cockpit scroll receipts: `checks/cockpit-scroll-1920.json` reports 1920×1200 viewport/document/body/main; `checks/cockpit-scroll-390.json` reports 390×844 throughout. Navigation drawer closed in both readings.
- Browser console and uncaught-error receipts are empty: `checks/console-messages.txt`, `checks/console-errors.txt`.
- 21 axe reports (12 desktop theme/screen pairs, 6 mobile light screens, 3 additional states): **0 violations**. Incomplete checks are limited to axe's gradient/pseudo-element background uncertainty and the horizontally scrollable mobile table being partially offscreen; the settings triangle is decorative. Details are in the per-state JSON.
- Contrast helper reports cover 18 route/viewports: 12 desktop light/dark screens and 6 mobile light screens; **0 measured failures across 1,263 text nodes**. Method script: `checks/contrast-check.js`. `checks/cockpit-gradient-contrast.txt` separately samples the rendered cockpit strip against the actual empty-video gradient: worst ratio 8.76:1 (attention copy), with all measured status text above 4.5:1. This is not a claim about bright live footage.
- Mouse navigation and keyboard behavior: drawer open/close and Escape focus restoration; page navigation/`aria-current`; review tab arrow-key selection; review evidence and main evidence table arrow-key scrolling; completed and zero-event review states; mock sync/Core-save feedback. Sync and Core-save produced no network requests; upload remained disabled without Core URL+token. See `checks/functional-summary.txt` and raw traces.
- Android source-level checks: `strings.xml` parses, four newly referenced strings exist, the duration formatter is present, and `git diff --check` passes. `minSdk=26` supports the added `java.time` use. No Gradle task was run and no commit was made.

## Known gaps and detector findings

- The mobile horizontal mouse-wheel attempt was inconsistent in the managed browser; no claim is made for wheel input. Touch/swipe, glove use, sunlight, actual RC Plus 2 rendering, and real video-frame contrast remain unverified and belong to A9-4.
- The single Impeccable detector run is preserved at `checks/detector-findings.json`: 84 findings (66 undersized-functional-text warnings, 13 tiny-body-text warnings, and 5 advisories including phase-number labels). These are not axe violations. Compact microcopy and numbered workflow phase labels remain for this pass; actual hardware readability is still a release gate, not inferred from browser captures.
- No APIs, contracts, permissions, server calls, or business rules were added or changed. All values and example feedback remain `[SIMULATED]`; server status is not inferred.
