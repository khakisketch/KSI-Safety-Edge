# round8 — Mission-tab readiness and ordered manual flow

Date: 2026-10-14 · Baseline: `daea3d8` / `2f3c22e100dfd36a45ea13d8cebde0d6aceb341944fa26e335de2e7fde4f602e` · Final HTML: `daa0285eac60de22852d40387d2553d1968b1d3502b2804a89fa6dbad0c49ed0` · Status: uncommitted

## Scope and intent

- Applied comparison-study recommendations 2–4 to the existing preflight mission page. This remains a KSI preparation and local-recording surface, not a flight planner or safety-approval surface.
- Added the coordinator's round8 rail-label correction: preserve Korean words at desktop rail width, without changing other page labels.
- Android copy/structure parity is limited to `strings.xml` and `MainActivity.kt`; no Android layout XML, business behavior, API, permission, or data contract changed.

## Changes

- Added a three-row factual readiness checklist in the existing mission-summary flow:
  - `동기화 준비` — `Core 미설정 · 서버 연결 없음`
  - `번들 준비` — `SIM-BUNDLE-07 선택됨 · [SIMULATED]`
  - `기록 가능 여부` — `알 수 없음 · 실제 저장 미연결`
- Added a persistent single result chip, `미확인 · 로컬 반영 · [SIMULATED]`. The help line and post-action feedback say the screen has no server connection and local reflection is unknown; no mock action is shown as success.
- Reordered separate controls to present `준비 → 적용 → 로컬 기록 시작` in sequence. Each remains an independent manual button. `재조정` remains available as an additional check after the sequence; session start/stop/end controls remain separate.
- Kept `로컬 기록 시작` as the single sequence's primary action and kept the nearby DJI Pilot 2 flight-control boundary. The existing `01 / 비행 전`, `03 / 비행 후`, and `04 / 로컬 증거` cues remain unchanged and advisory.
- Added `word-break: keep-all` to `.nav-sub`. At an actual 98px rail, `라이브 · 관측` needed slightly more label width to keep the separator with `라이브`; a rail-width container query tightens only the inflight nav item's gap/padding (type remains 12px). The measured 98px labels now render `라이브 ·` / `관측` and `로컬 ·` / `동기화` as two intact word groups; label copy is unchanged. The 116px rail keeps its existing spacing.
- Android mirrors the 3 factual readiness rows and local-only sync result vocabulary. The existing prepare/apply/start buttons were visually reordered in `MainActivity.kt`; their click handlers and all other operational behavior remain unchanged.

## Verification

- Before source: SHA-256 `2f3c22e100dfd36a45ea13d8cebde0d6aceb341944fa26e335de2e7fde4f602e`; after HTML: `daa0285eac60de22852d40387d2553d1968b1d3502b2804a89fa6dbad0c49ed0`.
- Captures and per-file SHA-256: `captures/round8-mission-tab/RECEIPT.md`.
- Axe-core 4.12.1 on mission light 1920, dark 1920, and light 390: zero violations and zero incomplete checks in all three runs.
- Measured changed-copy contrast: 25 rendered items per theme/viewport (light 1920, dark 1920, light 390), zero failures.
- Rail check at 1920: default 116px and an explicit 98px-width simulation both keep all five `.nav-sub` labels within their widths. At 98px the target labels are exactly two lines (`라이브 ·` / `관측`; `로컬 ·` / `동기화`); the status chip stays `word-break: normal`, with `scrollWidth == clientWidth`. Layout records: `checks/nav-label-layout.json`; 98px focused capture: `focused/rail-1920-98px.png`.
- Mission geometry: desktop main fits its viewport without horizontal or vertical overflow. At 390×844, `main` is the internal scroll container; it has no horizontal overflow, the three flow buttons do not intersect, and the final page footer ends above the fixed bottom navigation.
- Interaction smoke: nine mission controls remain present; Tab order proceeds prepare → apply → local record start; simulated sync feedback remains unknown and no network requests were captured. Cockpit document/body/main remain exactly 1920×1200 with no scroll after the nav-label change.
- Detector: three existing workflow-number advisories only (`01 / 비행 전`, `03 / 비행 후`, `04 / 로컬 증거`); zero warnings or tiny-body findings. Browser errors: zero.
- Android XML parsed; 156 unique string names and round8 resources checked. Targeted `android_host_tests.sh :rc-app:testBenchDebugUnitTest :rc-app:testDeviceDebugUnitTest` passed, including Kotlin compile of both app variants. Only existing DJI dependency/non-positional resource warnings and two pre-existing `FLAG_FULLSCREEN` deprecation warnings appeared. No full Gradle invocation, device render, touch/glove test, or device acceptance was performed.

## Deliberately preserved

- Study recommendations 1 and 5: the primary local-recording action and DJI Pilot 2 flight-control boundary remain; neither was demoted or removed.
- B-1 meaning and honesty: zero incidents is not safety; `[SIMULATED]` remains; RC receive-time remains approximate; server status is never inferred.
- No cockpit layout or control changes, no action merge/automation, no contract/API/permission changes, and no changes to `docs/verification/evidence/**`, product-state, or the plan ledger.
