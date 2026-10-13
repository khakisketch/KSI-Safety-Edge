# RC 조종 화면 셸 — 디자인 프로토타입

앱(`android-rc`)의 시각 기준이 되는 단일 HTML 프로토타입. 브라우저로 열어 확인한다.

- **현재 리비전**: `field-shell.html` — sha256 `3d9663353db4374a6a0e41344bb89f2a5d30e6b89eed1c1b9b9cdf3b2da417a7` (87,380 B, 2026-10-13)
- **렌더(필수 경로)**:

  ```bash
  bash scripts/design_preview_server.sh        # → http://127.0.0.1:8765/field-shell.html
  ```

  기준 뷰포트 1920×1200(가로, RC Plus 2). `file://`은 렌더러에서 차단되므로 로컬 서버로 연다.
- **캡처**: `captures/round1-before/`, `captures/round1-refined/`, `captures/round2-cockpit/` (각 8장, 1920×1200)

## 라운드 이력

| 라운드 | 내용 | 리비전 |
|---|---|---|
| round 1 생성 | 5영역 제품 셸(계약 준수) | `7c5d311e…` |
| round 1 리뷰/정제 | F-01 문제·주의 위계, F-02 MOP 조치 배치, F-03 텍스트 크기 | `ba6441f5…` |
| round 2 재설계(사용자 방향) | 라이브 뷰 중심 구성, 무스크롤(1200=1200), 중복 문구 정리 | `3d966335…` |

상세 기록: `history/2026-10-13-round1-review-findings.md`,
`history/2026-10-13-round2-redesign-change-note.md`

## 보존 제약 (디자인 변경 시 불변)

- **상태 계약 의미**: `docs/plans/2026-10-12-b1-operator-flow-contract.md` — 항목·심각도·행동.
- **정직 규칙**: 사건 0건≠안전 · [SIMULATED] 표기 · RC 수신시각(근사) · 미동기화 수 노출.
- **기능**: 임무 준비/링크/관측/증거·동기화/설정 전부 도달 가능 · 단일 자체완결 HTML.

## 상태

- 2026-10-13 재설계 결과 — **사용자 승인 대기**. 승인 후 `android-rc` 레이아웃 반영(구현).
- 이전 OpenDesign 프로젝트(`ksi-safety-edge-rc-pilot` / `ksi-safety-edge-rc-review`)는
  이력 보관용이며, 이후 라운드는 이 저장소에서 진행한다.
