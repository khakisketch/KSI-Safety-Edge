# RC 조종 화면 셸 — 디자인 프로토타입

앱(`android-rc`)의 시각 기준이 되는 단일 HTML 프로토타입. 브라우저로 열어 확인한다.

- **현재 정본(작업본)**: `field-shell.html` — 2026-10-14 현장 보조 화면 정리
  (무박스 상태 띠·영상 입력 상태 구분·기록 요청/실제 쓰기 분리·비행 후 검토). sha256 `73dd0303…`
  (178,725 B) — **미커밋 작업본**, 커밋 전에는 수치가 변동될 수 있다.
- **직전 커밋 기준선**: `c5f41ee`(round 4) · `ff4b5b4`(round 3, `cdb72c9b…`).
- **렌더(필수 경로)**:

  ```bash
  bash scripts/design_preview_server.sh        # → http://127.0.0.1:8765/field-shell.html
  ```

  기준 뷰포트 1920×1200(가로, RC Plus 2). `file://`은 렌더러에서 차단되므로 로컬 서버로 연다.
- **캡처**: `captures/round1-before/`, `captures/round1-refined/`, `captures/round2-cockpit/`,
  `captures/round3-review/`(9장, 1920×1200), `captures/round4-settings-ux/`(라이트/다크 10장)

## 라운드 이력

| 라운드 | 내용 | 리비전 |
|---|---|---|
| round 1 생성 | 5영역 제품 셸(계약 준수) | `7c5d311e…` |
| round 1 리뷰/정제 | F-01 문제·주의 위계, F-02 MOP 조치 배치, F-03 텍스트 크기 | `ba6441f5…` |
| round 2 재설계(사용자 방향) | 라이브 뷰 중심 구성, 무스크롤(1200=1200), 중복 문구 정리 | `3d966335…` |
| round 3 독립 렌더 리뷰/정제 | 끊김 상태 Connect CTA 대비, 관측 상태·텔레메트리 글자 크기, 정상 중립 버튼 | `cdb72c9b…` |
| round 4 설정 UX | 설정 페이지 재정비(기록자 표시 정보·Core 저장 상태·권한 안내) + 검토 페이지 오탐 표기 | `c5f41ee` |
| 2026-10-14 현장 보조 화면 정리 | 무박스 상태 띠, 영상 입력 상태(미연결/연결 중/신호 없음/프레임 미수신/지연/시각 미확인), 기록 요청≠쓰기, 비행 후 검토(0건 비행·의견 비활성) | 작업본 `73dd0303…` (미커밋) |

상세 기록: `history/2026-10-13-round1-review-findings.md`,
`history/2026-10-13-round2-redesign-change-note.md`,
`history/2026-10-13-round3-review-findings.md`

## 보존 제약 (디자인 변경 시 불변)

- **상태 계약 의미**: `docs/plans/2026-10-12-b1-operator-flow-contract.md` — 항목·심각도·행동.
- **정직 규칙**: 사건 0건≠안전 · [SIMULATED] 표기 · RC 수신시각(근사) · 미동기화 수 노출.
- **기능**: 임무 준비/링크/관측/증거·동기화/설정 전부 도달 가능 · 단일 자체완결 HTML.

## 상태

- 2026-10-13 round 3·4: round-4 결과는 `android-rc` 설정 포트(`de62114`)까지 커밋·푸시됐다.
- 2026-10-14 사용자 확인(방향): 카드 최소화·영상 중심·비행 후 상세. 이후 세부 정리는 브라우저
  렌더 검증(axe 위반 0, 일부 대비 수동 확인 미완) 완료 — **실기 렌더·햇빛·장갑 검증은 A9-4**.
- 렌더 검증 규칙(영수증·독립 리뷰·보존 제약): `docs/verification/dev-harness-runbook.md`.
  디자인·렌더 검증은 Design Specialist에 위임하며, OpenDesign 도구는 은퇴(이력 보관)다.
- 이전 OpenDesign 프로젝트(`ksi-safety-edge-rc-pilot` / `ksi-safety-edge-rc-review`)는
  이력 보관용이며, 이후 라운드는 이 저장소에서 진행한다.
