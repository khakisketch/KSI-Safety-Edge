# round9 — Shell continuity and landscape mission usability

Date: 2026-10-15 · Baseline HTML: `daa0285eac60de22852d40387d2553d1968b1d3502b2804a89fa6dbad0c49ed0` · Final HTML: `8d8ce077f0e5025c1c924042a7f13d153f362b2ec4d03cb62cdeb89feda52277` · Status: uncommitted

## Scope and intent

- Reduce the abrupt visual/context change between the immersive cockpit and the mission, review, evidence, diagnostics and settings destinations.
- Preserve the existing cockpit and round8 mission content, DJI Pilot 2 boundary, one primary mission action, status meanings, `[SIMULATED]`/unknown language, typography roles, functionality, permissions, API/contracts and business rules.
- Work was limited to `design/rc-pilot-shell/`.

## Changes

- Reused one on-demand off-canvas navigation drawer and hamburger control across destinations. Removed the persistent desktop rail and mobile bottom-navigation presentation from non-cockpit screens; the cockpit remains edge-to-edge.
- Kept the non-cockpit header dark and shared; added a compact SE mark next to the cockpit live-view title to reinforce the same product identity without changing cockpit controls or layout.
- On short landscape viewports, compacted mission spacing while retaining every round8 data row and independent action. At 1366×768, 1536×864 and 1920×830 the complete `로컬 기록 시작` control is visible above the fold.
- At 390px, mission facts use two columns (one column at ≤360px). The mobile page continues to scroll internally; no facts or actions were removed.
- Drawer behavior was extended to the additional destinations with `aria-expanded`, inert/hidden states, Escape close, focus restoration, tab containment and heading focus on route changes.

## Verification

- Full render, geometry, interaction, accessibility, contrast, console/network and detector results: `captures/round9-usability/RECEIPT.md`.
- Visible Playwright Chromium renders: 1366×768, 1536×864, 1920×830 and 390×844. Desktop route renders include cockpit, mission, postflight review, evidence, settings and diagnostics. Before/after captures use matching 1920×830 sizes for shell, mission, review, evidence and settings; round8 mission light/dark 1920×1200 and 390×844 baselines were also retained.
- Mission primary action is fully visible at y=701.3–745.3 (1366×768), y=699.4–743.4 (1536×864), and y=701–745 (1920×830). At 390×844 it is below the initial viewport at y=1142.6–1190.6, but fully reachable by scrolling; the 8 mission data cells remain in a two-column grid.
- 20 axe-core 4.12.1 WCAG 2 A/AA runs: 0 violations. Six runs have incomplete contrast checks only for the cockpit gradient/short glyph marks or the partially visible horizontally-scrollable evidence table.
- Round9 contrast helper: zero measured failures in 5 states (closed shared chrome light/dark at 1536 and light at 390; open drawer light/dark at 1536). Measured minima: 8.79:1 light chrome, 7.30:1 dark chrome, 7.95:1 light drawer, 6.72:1 dark drawer.
- Keyboard smoke: drawer focus/Tab/Shift+Tab wrap/Escape, route-heading focus, `aria-current`, mission action tab order and review arrow-key tabs all behaved as expected. No non-static/API requests were observed during mission action smoke; console errors/warnings were 0.
- Impeccable detector reports the same three advisory workflow labels (`01 / 비행 전`, `03 / 비행 후`, `04 / 로컬 증거`). These numbers communicate actual workflow phases and remain intentionally preserved; no warnings or tiny-body-text findings were returned.

## Deliberately preserved and remaining limits

- The preflight checklist and local-reflection chip remain factual/unknown, and the manual sequence remains `준비 → 적용 → 로컬 기록 시작`. No button was merged, automated, or relabeled as a safety approval.
- The browser prototype remains local-only and `[SIMULATED]`; it cannot prove Core/server connectivity, local recording, file download, successful synchronization, or physical device state.
- No Android implementation, API or contract, permission, data rule, product-state document, security gate, or verification evidence was changed. No commit was made.
- Browser mouse and keyboard behavior were tested. Touch/swipe, gloves, sunlight, live video contrast, the RC Plus 2 screen and field acceptance remain untested and belong to the existing A9-4 device gate.
