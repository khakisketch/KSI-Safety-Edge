# round7 Typography — Verification Receipt

Date: 2026-10-14 · Status: uncommitted · Product state: prototype render verification, not device acceptance

## Scope and source

- Baseline: `git show HEAD:design/rc-pilot-shell/field-shell.html`, SHA-256 `7b8c41241415bb8f289758ed0bedb483f94e9ca903968d16fe3b5934378d13df`.
- Final HTML: SHA-256 `2f3c22e100dfd36a45ea13d8cebde0d6aceb341944fa26e335de2e7fde4f602e`.
- Android layout: SHA-256 `1d8f7fb1d1a859f2a7182854095f465dd1514a2b44ff473a356ebdd18b4c017d`.
- HTML changes are typography-only except for the narrow wide-cockpit HUD fit correction described below. Android changes are font-size-only. Contracts, APIs, permissions, copy, business rules and B-1 status semantics were not changed.

## Implementation

- Introduced CSS typography roles at 12px, 13px and 14px for compact labels, UI/support content and body/table/detail content, then applied them to navigation, cockpit/status controls, fields, detail rows, tables, footnotes and hints.
- Raised the remaining 11.68px scenario, mission-detail and settings-state text to the 12px caption role. `rail_note` remains 10sp in Android.
- Preserved the phase cues `01 / 비행 전`, `03 / 비행 후` and `04 / 로컬 증거`: each communicates workflow sequence (preflight → postflight → local evidence) and supports quick orientation. The final detector therefore retains three section-number advisories.
- Fixed a wide-screen clipping defect found during geometry verification. At 1920px, a preview breakpoint's centered transform/width was inherited by the full-screen cockpit bottom HUD; the first label was outside the viewport. The full-screen wide-cockpit rule now uses in-viewport inline insets, automatic width and no transform. This changes presentation only.

## Rendered screen evidence

Rendered with managed Chromium using `agent-browser`; baseline screens were served from the pinned HEAD source, and after screens from the final working tree.

- `before/` and `after/`: 19 PNGs each — six 1920×1200 light screens, six 1920×1200 dark screens, six 390×844 light screens and one 1024×768 cockpit screen.
- `focused/`: nine paired comparison captures for compact cockpit controls/status, mission/detail labels, review actions/footnotes, evidence table, field hints and phase/rail labels.
- Dimensions and SHA-256 checksums: `checks/capture-sha256.txt`.
- The visible-tab screenshot endpoint could not capture its tab in this environment (“Screenshot needs a visible tab”). The supported managed-Chromium capture path was used instead; representative final images were inspected directly.

## Verification results

### Typography detector

- Fresh pre-edit baseline: 84 findings — 66 undersized-functional-text warnings, 13 tiny-body-text warnings and 5 advisories.
- Final detector: 3 advisories, all for the intentionally retained workflow phase numbers. Zero functional-size warnings and zero tiny-body findings.
- JSON output: `checks/detector-findings-before.json`, `checks/detector-findings-after.json`; stderr: `checks/detector-stderr-after.txt`.

### Axe and contrast

- Axe-core 4.12.1 with `wcag2a,wcag2aa` tags: 18 reports (12 light: six desktop + six mobile; six dark desktop), **0 violations**.
- Incomplete automated contrast checks concern short/non-text glyphs, gradient backgrounds and cells partially obscured by the horizontally scrollable mobile evidence table. These are not violations; the details are in `checks/axe-*.json`.
- Contrast helper: 18 screen/theme reports, **1,250 rendered text nodes measured, 0 measured failures**. Results and the unmodified checker are in `checks/contrast-*.json` and `checks/contrast-check.js`. The helper does not certify contrast over real live video frames.

### Cockpit geometry

All three checks were recorded with the cockpit active and the navigation drawer closed. `document`, `body` and `main` scroll dimensions equal the viewport; measured overflow and overlap lists are empty.

| Viewport | Document/body | Main client/scroll | No scroll | No horizontal overflow | No measured overlap |
|---|---:|---:|---|---|---|
| 1920×1200 | 1920×1200 | 1920×1200 | Pass | Pass | Pass |
| 1024×768 | 1024×768 | 1024×768 | Pass | Pass | Pass |
| 390×844 | 390×844 | 390×844 | Pass | Pass | Pass |

Per-viewport JSON: `checks/cockpit-geometry-*.json`.

### Other checks

- Browser console errors: 0 on the final HTML route.
- Android XML parses successfully. Structural comparison against HEAD confirms the element tree, text and non-font attributes are unchanged; only 53 `android:textSize` values differ. `@string/rail_note` remains 10sp. Receipt: `checks/android-static-checks.txt`.
- `git diff --check`: pass.
- No Gradle build, Android host tests, device render or commit was performed.

## Remaining limits

- Touch/swipe, gloves, daylight, RC Plus 2 hardware rendering, live-camera frame contrast and DJI/Manifold integration remain untested and belong to A9-4. Browser captures do not prove flight safety, recording, storage, internet access or upload; `[SIMULATED]`, approximate RC receive-time language and “0 incidents ≠ safety” wording remain unchanged.
- This receipt records implementation and browser verification only; it does not claim replay/device verification, user acceptance or security-gate completion.
