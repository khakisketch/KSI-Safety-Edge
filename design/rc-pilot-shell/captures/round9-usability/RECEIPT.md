# round9-usability — Verification receipt

Date: 2026-10-15 · Status: uncommitted · Product state: prototype/browser verification only

## Source and scope

- Round8 baseline `field-shell.html`: SHA-256 `daa0285eac60de22852d40387d2553d1968b1d3502b2804a89fa6dbad0c49ed0`.
- Final `field-shell.html`: SHA-256 `8d8ce077f0e5025c1c924042a7f13d153f362b2ec4d03cb62cdeb89feda52277`.
- The CSS and navigation state changes are limited to `design/rc-pilot-shell/field-shell.html`. This round did not change Android sources, APIs/contracts, permissions, data or business rules.
- Preserved the cockpit and round8 mission content, local-record primary action, DJI Pilot 2 flight-control boundary, `[SIMULATED]` labels, unknown/server-state wording, B-1 status meanings and 12/13/14px text roles.

## What changed

- One on-demand hamburger/drawer is now the navigation pattern across cockpit, mission, postflight review, evidence, settings and diagnostics. The cockpit remains immersive and edge-to-edge; other routes share dark identity chrome.
- The cockpit live-view heading now carries the SE mark. Short landscape mission spacing protects the entire local-record-start action above the fold without removing checklist data or secondary controls.
- At 390px, mission facts remain in two columns and the page scrolls inside `main`; it switches to one column at ≤360px. The CTA is intentionally below the initial mobile viewport, but reachable by scrolling.

## Render captures

Captured from `http://127.0.0.1:8765/field-shell.html`, served by the existing local preview server, with visible Playwright Chromium at exact CSS-pixel viewport sizes. The saved dark-theme screenshot was recaptured after the 140ms CSS color transitions settled; the earlier transient in-transition capture was removed.

| View | Files | Size/state |
|---|---|---:|
| Cockpit, drawer closed/open — before and after | `before/cockpit-light-1920x830.png`, `after/cockpit-light-1920x830.png`, `before/cockpit-nav-open-light-1920x830.png`, `after/cockpit-nav-open-light-1920x830.png` | 1920×830 |
| Mission — before/after, compact landscape | `before/mission-light-1920x830.png`, `after/mission-light-1920x830.png`, `after/mission-light-1366x768.png`, `after/mission-light-1536x864.png` | 1920×830, 1366×768, 1536×864 |
| Mission — theme/mobile | `before/mission-light-1920x1200.png`, `before/mission-dark-1920x1200.png`, `before/mission-light-390x844.png`, `after/mission-light-1920x1200.png`, `after/mission-dark-1920x1200.png`, `after/mission-light-390x844.png` | 1920×1200 light/dark, 390×844 light |
| Mission — mobile readiness and primary action | `after/mission-light-390-readiness.png`, `after/mission-light-390-actions.png`, `after/mission-light-390-scrolled-800.png` | 390×844 at initial/focused scroll positions |
| Review, evidence and settings — before/after | matching `before/*-light-1920x830.png` and `after/*-light-1920x830.png` files | 1920×830 |
| Routes — after matrix | `after/{cockpit,review,evidence,settings,diagnostics}-light-1536x864.png`, matching `*-light-390x844.png`; diagnostics also at 1920×830 | 1536×864 and 390×844 (diagnostics 1920×830 also) |

`checks/capture-sha256.txt` records the SHA-256 of the final source and all before/after PNG captures.

## Geometry and responsive behavior

- Mission CTA geometry: 1366×768 y=701.3–745.3; 1536×864 y=699.4–743.4; 1920×830 y=701–745. The full 44px primary button is visible in each viewport.
- At each desktop size the document remains exactly viewport-sized with no horizontal overflow. Long content scrolls within `main`: 827px content / 713px client at 1366×768; 826 / 809 at 1536×864; 827 / 775 at 1920×830.
- At 390×844 the document is 390×844 and `main` is 390×791 with 1925px mission content; the CTA begins at y=1142.6 before scrolling, and is fully visible at y=342.6–390.6 after `scrollTop=800`. At maximum scroll the page footer ends at y=834.8, inside the main viewport. There is no fixed bottom navigation to obscure the content.
- Mission has 8 data cells in two columns; other mobile main scroll heights are review 1676px, evidence 1512px, settings 2126px and diagnostics 1773px. No route has document-level horizontal overflow. The evidence table retains its own labeled horizontal scroll region.
- Detailed measurements are in `checks/geometry-desktop.json` and `checks/geometry-mobile.json`.

## Accessibility and contrast

- Axe-core 4.12.1, WCAG 2 A/AA: 20 runs — six destinations in light and dark at 1536×864, the open drawer in both themes at 1536×864, and all six destinations in light at 390×844. **0 violations, 479 passes**.
- Six runs contain axe `incomplete` color-contrast checks, not violations: the cockpit/drawer checks cannot infer contrast on the cockpit gradient or very short decorative marks, and the mobile evidence table has partially obscured horizontally scrollable cells. Full reports are `checks/axe-*.json`.
- The round9 rendered-copy contrast helper measured 123 elements across five states (closed chrome light/dark desktop and light mobile; open drawer light/dark desktop): **0 failures**. Minimum ratios were 8.79:1 (light chrome), 7.30:1 (dark chrome), 7.95:1 (light drawer) and 6.72:1 (dark drawer). Reports and method are `checks/contrast-*.json` and `checks/contrast-round9.js`.
- Drawer keyboard behavior: opening moves focus to Close; Tab/Shift+Tab are contained and wrap at both ends; Escape closes, restores focus to the hamburger and makes the drawer inert. Route changes close the drawer, update `aria-current`, focus the destination heading and reset scroll to 0.
- Mission keyboard order remains sync → bundle → prepare → apply → local-record start → reconcile → observation-session controls. Review tab ArrowRight/Home behavior remains functional. Full interaction evidence: `checks/keyboard-focus.json` and `checks/interaction-smoke.txt`.

## Functional smoke, console, network and detector

- Clicked sync, bundle, prepare, apply, start local recording, start/stop observation and end recording. Mock feedback continued to say no server call/file download/real device-state change; the persistent local-reflection chip remained `미확인 · 로컬 반영 · [SIMULATED]`.
- Evidence upload stayed disabled with Core unconfigured. No non-static/API/fetch/XHR request was observed during the mission action sequence; one static document request was noted.
- Browser console: 0 errors, 0 warnings. See `checks/console-network.json`.
- Impeccable detector: only three advisory workflow-phase labels remain (`01 / 비행 전`, `03 / 비행 후`, `04 / 로컬 증거`). They intentionally encode actual task phases. No warnings or tiny-body-text findings. Full output: `checks/detector-findings.json`.
- `git diff --check`: pass.

## Boundaries and untested conditions

- This is still a self-contained local HTML prototype. It cannot demonstrate actual Core/server connectivity, local persistence, recording, file download, upload/sync result or physical flight state. Zero incidents remains distinct from safety; server state is not inferred.
- Browser mouse and keyboard input were exercised. Touch/swipe, gloves, sunlight, live-video contrast, RC Plus 2/device rendering and field acceptance remain untested and belong to A9-4. No Android build/device validation was run because this round changed no Android files.
- No commit was made; the unrelated working-tree state was preserved.
