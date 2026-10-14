# round7 — Typography and cockpit HUD fit

Date: 2026-10-14 · Baseline HTML: `7b8c4124…` · Final HTML: `2f3c22e1…` · Status: uncommitted

## User work and intent

The operator needs labels, controls, status text, evidence details and field hints to remain readable while checking mission preparation, observing a flight, and reviewing local records. This round kept the round6 product language and screen flow, raised the compact typography roles, and mirrored only corresponding Android text sizes.

## Changes

- Added a compact CSS role scale at 12/13/14px and applied it to navigation, controls, compact labels, status/support text, table/detail rows, cockpit text, footnotes and hints. The final 11.68px body-text advisory was resolved by bringing the remaining scenario, mission-detail and settings-state labels onto the 12px caption role.
- Mirrored the associated font-size mapping in `activity_main.xml`: 53 `android:textSize` values changed; the XML tree, text and all other attributes are unchanged. The `@string/rail_note` label remains 10sp per the settled mapping.
- The final cockpit geometry pass exposed a pre-existing wide-screen clipping defect: the bottom HUD retained the centered preview transform at 1920px, placing its first label outside the viewport. A wide-cockpit-only CSS override now restores in-viewport insets and auto width; no behavior, copy, API or state semantics changed.
- Kept `01 / 비행 전`, `03 / 비행 후` and `04 / 로컬 증거`: these are workflow sequence cues that help operators distinguish preflight, postflight and local evidence, not decorative section numbering.

## Verification

- Baseline source served from `git show HEAD` (SHA-256 `7b8c41241415bb8f289758ed0bedb483f94e9ca903968d16fe3b5934378d13df`). Final source SHA-256: `2f3c22e100dfd36a45ea13d8cebde0d6aceb341944fa26e335de2e7fde4f602e`.
- Re-rendered before/after matrices: 1920×1200 light and dark, 390×844 light; 1024×768 cockpit; nine focused before/after comparisons. Screenshot checksums are in `captures/round7-typography/checks/capture-sha256.txt`.
- Cockpit geometry is recorded at 1920×1200, 1024×768 and 390×844: document/body/main stay at viewport dimensions; no scroll, horizontal overflow or measured overlay intersections.
- Detector baseline: 84 findings (66 undersized-functional-text warnings, 13 tiny-body-text warnings and 5 advisories). Final: 3 numbered-workflow advisories only; zero warnings and zero tiny-body findings.
- Axe 4.12.1, WCAG 2 A/AA tags: 18 screen/theme runs, zero violations. The incomplete automated checks are contrast/background determinations for short glyphs, gradients and partially obscured horizontally scrollable table cells.
- The companion contrast checker measured 1,250 rendered text nodes across the same matrix with zero measured failures. It does not certify contrast over actual camera footage.
- Browser console errors: 0. XML parsing and `git diff --check` pass. Full Gradle and device validation were not in scope.

Full receipts, per-screen results, geometry JSON and remaining limitations: `captures/round7-typography/RECEIPT.md`.
