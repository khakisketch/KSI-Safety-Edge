# round8 Mission-tab — Verification receipt

Date: 2026-10-14 · Status: uncommitted · Product state: prototype/browser verification only

## Source and scope

- Baseline HTML from `HEAD` (`daea3d8`): SHA-256 `2f3c22e100dfd36a45ea13d8cebde0d6aceb341944fa26e335de2e7fde4f602e`.
- Final `field-shell.html`: SHA-256 `daa0285eac60de22852d40387d2553d1968b1d3502b2804a89fa6dbad0c49ed0`.
- Applied comparison recommendations 2–4 and the coordinator's rail-label correction. The cockpit page layout and controls, mission action behavior, B-1 semantics, API/contracts, permissions and data rules were not changed.
- Android parity is restricted to `android-rc/rc-app/src/main/res/values/strings.xml` and `MainActivity.kt`. No layout XML changed; button handlers/operations remain intact.

## Changes observed

- Mission page now has three factual rows: `동기화 준비 · Core 미설정 · 서버 연결 없음`, `번들 준비 · SIM-BUNDLE-07 선택됨 · [SIMULATED]`, and `기록 가능 여부 · 알 수 없음 · 실제 저장 미연결`.
- One always-visible chip reads `미확인 · 로컬 반영 · [SIMULATED]`. The screen and post-action copy explicitly state that no server request or local reflection is confirmed. The browser mock cannot demonstrate the failure or successful-local-reflection outcomes; it keeps the chip at `미확인` rather than inventing either state.
- Separate controls are visually sequenced `준비 → 적용 → 로컬 기록 시작`. `재조정` remains a separate additional check. The DJI Pilot 2 flight-control boundary remains adjacent to the local-record-start action.
- `.nav-sub` has `word-break: keep-all`. At 1920×1200 with the rail explicitly set to 98px for the narrow-rail check, `라이브 · 관측` renders as `라이브 ·` / `관측` and `로컬 · 동기화` as `로컬 ·` / `동기화`, each exactly two lines. The rail-width container query tightens only the inflight nav item at that narrow width; the default 116px rail is unchanged. The mission status chip remains `word-break: normal` and does not overflow.

## Render captures

Captured with the local `design_preview_server.sh` route using managed Chromium (`agent-browser`). Before images were captured from the baseline source before editing; after images are from the final HTML hash above. The focused 390px image is scrolled to show the checklist, chip and complete sequence; the focused rail image isolates the desktop navigation.

The desktop Review-pane browser was disconnected, so captures were rendered and inspected through the supported managed-Chromium path instead.

| State | File | Viewport |
|---|---|---:|
| Before, light | `before/light-1920-mission.png` | 1920×1200 |
| Before, dark | `before/dark-1920-mission.png` | 1920×1200 |
| Before, mobile light | `before/light-390-mission.png` | 390×844 |
| After, light | `after/light-1920-mission.png` | 1920×1200 |
| After, dark | `after/dark-1920-mission.png` | 1920×1200 |
| After, mobile light | `after/light-390-mission.png` | 390×844 |
| Focused mobile sequence | `focused/light-390-mission-actions.png` | 390×844 |
| Desktop rail | `focused/rail-1920.png` | 116×1200 crop |
| Narrow desktop rail | `focused/rail-1920-98px.png` | 98×1200 crop (verification width override) |

SHA-256 for source and every PNG: `checks/capture-sha256.txt`.

## Checks

- **Axe 4.12.1, WCAG 2 A/AA:** mission subtree at light 1920, dark 1920 and light 390. All three runs: 0 violations, 0 incomplete checks. Reports: `checks/axe-mission-*.json`. A full-page audit with the 98px rail verification override also had 0 violations (`checks/axe-full-light-1920-98px.json`).
- **Changed-copy contrast:** `checks/contrast-changed-copy.js` measured 25 rendered items in each state (light 1920, dark 1920, light 390). 0 failures in every run; minimum measured ratios were 7.95:1 light and 6.72:1 dark. Reports: `checks/contrast-*.json`. These checks measure rendered UI text backgrounds, not live camera imagery.
- **Rail line integrity:** `checks/nav-label-layout.json` records all five nav sublabels at the default 116px rail and the 98px verification width. The two target labels each have two intact word groups; all labels have `scrollWidth == clientWidth`. The sync chip remains normal word breaking and also has `scrollWidth == clientWidth`.
- **Mission scrolling and overlap:** `checks/geometry-desktop.json` shows desktop document/body/main at 1920×1200 with main scroll size equal to client size and no horizontal overflow. At 390×844, mission content is internally scrollable (`main` 2229px content / 734px client), with no horizontal overflow; the three flow buttons do not intersect. At maximum scroll, the page footer ends at y=778, above the fixed navigation at y=785. See `checks/geometry-mobile.json`.
- **Manual-flow interaction:** `checks/interaction-smoke.txt` records nine distinct mission controls, keyboard Tab order prepare → apply → local record start, persistent unknown local-reflection feedback after mock sync clicks, and no captured network requests. The cockpit remains 1920×1200 with body/main scroll size equal to the viewport after the shared rail-label CSS change (`checks/cockpit-no-scroll-1920.json`).
- **Text detector:** `checks/detector-findings.json` has only the three intentionally preserved advisory phase labels `01 / 비행 전`, `03 / 비행 후`, `04 / 로컬 증거`; no warnings or tiny-body findings. Browser error and console reports are empty.
- **Android static checks:** `strings.xml` and existing `activity_main.xml` parse; all 156 string names are unique and round8 references exist. `activity_main.xml` is unchanged from `HEAD`.
- **Targeted Android tests:** `bash scripts/android_host_tests.sh :rc-app:testBenchDebugUnitTest :rc-app:testDeviceDebugUnitTest` passed (both app variants compiled, unit tests passed; 62 actionable tasks, 22 executed). Existing warnings came from DJI dependency merged resources and two unrelated `FLAG_FULLSCREEN` deprecations. This did not invoke a full Gradle build.
- `git diff --check`: pass. No device render or touch/glove test was run per coordinator gate.

## Preserved boundaries and remaining gaps

- Kept the existing primary/secondary action styling and `비행 조종은 DJI Pilot 2에서` boundary. Buttons were not combined or automated. Existing `01 / 03 / 04` phase labels remain advisory.
- Kept B-1 meaning and honesty rules: `0 incidents ≠ safety`, `[SIMULATED]`, approximate RC receive-time and no server-state inference.
- No cockpit layout/control change, API/contract/permission change, or edit to `docs/verification/evidence/**`, product-state or the plan ledger.
- The HTML simulation cannot prove real server responses, local storage, recording, or success/failure sync outcomes. Android UI was not rendered or built on-device; full Gradle, touch, gloves, daylight, RC Plus 2 rendering and field acceptance remain untested.
