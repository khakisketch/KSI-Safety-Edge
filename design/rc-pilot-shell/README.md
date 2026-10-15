# RC 조종 화면 셸 — 디자인 프로토타입

앱(`android-rc`)의 시각 기준이 되는 단일 HTML 프로토타입. 브라우저로 열어 확인한다.

- **현재 정본(작업본)**: `field-shell.html` — 2026-10-15 round12 조종석 헤더 위계
  (KSI 관측·기록 보조, 영상 입력 상태·`[SIMULATED]`·DJI Pilot 2 비행 조종 권한을 분리). sha256
  `4b632f563e24592aa854892092fdc5269049b1c558493ac37f2e94ba6a04cc90` (**미커밋 작업본**).
- **직전 커밋 기준선**: `783ec0b`(10-14 디자인, main) · `c5f41ee`(round 4) · `ff4b5b4`(round 3, `cdb72c9b…`).
- **렌더(필수 경로)**:

  ```bash
  bash scripts/design_preview_server.sh        # → http://127.0.0.1:8765/field-shell.html
  ```

  기준 뷰포트 1920×1200(가로, RC Plus 2). `file://`은 렌더러에서 차단되므로 로컬 서버로 연다.
- **캡처**: `captures/round1-before/`, `captures/round1-refined/`, `captures/round2-cockpit/`,
  `captures/round3-review/`(9장, 1920×1200), `captures/round4-settings-ux/`(라이트/다크 10장),
  `captures/round5-field-use/`(29장 + **RECEIPT.md**),
  `captures/round6-full-sweep/`(18장 before + 18장 화면 매트릭스 after + 3개 추가 상태,
  **RECEIPT.md**·검사 JSON·캡처 해시 목록),
  `captures/round7-typography/`(전체 1920 light/dark·390 light 매트릭스, focused 비교,
  detector·axe·contrast·geometry·해시 검사, **RECEIPT.md**),
  `captures/round8-mission-tab/`(임무 1920 light/dark·390 light before/after, 모바일 액션·116/98px 레일 focused,
  detector·axe·변경 문구 contrast·스크롤/겹침 검사, **RECEIPT.md**),
  `captures/round9-usability/`(공통 내비·header before/after, 1366×768·1536×864·1920×830·390px 렌더,
  여섯 destination 화면 매트릭스, axe·contrast·geometry·keyboard·console·detector 결과, **RECEIPT.md**),
  `captures/round10-cockpit-readability/`(기존 960×600·1920×830 before/after 보존, 최종 `verified-final-20261015/`에
  390×844·960×600·1024×768·1280×800 destination matrix, 5 B-1 시나리오·7 영상 입력 상태, details-open·axe·geometry·Android XML/48dp 검사·해시, **RECEIPT.md**),
  `captures/round11-cockpit-minimal/`(동일 4 viewport의 20 destination + B-1 5상태 × 4 + 영상 입력 7상태,
  기록/해제 확인, details scroll·keyboard·axe·geometry·console·Android XML/48dp·해시·contact sheet, **RECEIPT.md**),
  `captures/round12-cockpit-heading/`(960×600·1920×1200 기본, 390×844 보조 · 입력 최근/미연결/B-1 문제,
  heading geometry·console·detector·Android XML/48dp·host tests, **RECEIPT.md**)

## 라운드 이력

| 라운드 | 내용 | 리비전 |
|---|---|---|
| round 1 생성 | 5영역 제품 셸(계약 준수) | `7c5d311e…` |
| round 1 리뷰/정제 | F-01 문제·주의 위계, F-02 MOP 조치 배치, F-03 텍스트 크기 | `ba6441f5…` |
| round 2 재설계(사용자 방향) | 라이브 뷰 중심 구성, 무스크롤(1200=1200), 중복 문구 정리 | `3d966335…` |
| round 3 독립 렌더 리뷰/정제 | 끊김 상태 Connect CTA 대비, 관측 상태·텔레메트리 글자 크기, 정상 중립 버튼 | `cdb72c9b…` |
| round 4 설정 UX | 설정 페이지 재정비(기록자 표시 정보·Core 저장 상태·권한 안내) + 검토 페이지 오탐 표기 | `c5f41ee` |
| 2026-10-14 현장 보조 화면 정리 | 무박스 상태 띠, 영상 입력 상태(미연결/연결 중/신호 없음/프레임 미수신/지연/시각 미확인), 기록 요청≠쓰기, 비행 후 검토(0건 비행·의견 비활성) | `783ec0b` |
| round5 캡처 영수증·정합 보강 | 390 시나리오 스위치·비행없음 누수·영상 7상태 정합, Android KSI 기록 셀/문구, 캡처 29장+RECEIPT | 작업본 `6e483b9e…` (미커밋) |
| round6 전 화면 심화 | 비행 전/후 명확화, 동기화 결과의 지속 표시, 로컬 완전성·동기화 분리, 리뷰 시간/0건 상태, 모바일 증거표 키보드 스크롤, Android 문구 정합 | 작업본 `7b8c4124…` (미커밋) |
| round7 타이포그래피 | HTML 12/13/14px 역할 스케일, Android `textSize`만 대응 조정, 11.68px 본문 advisory 해소, 1920px 조종 하단 HUD 잘림 수정 | 작업본 `2f3c22e1…` (미커밋) |
| round8 임무 탭 | KSI 사실 기반 3줄 준비 상태, 로컬 반영 한정 상시 칩, 수동 준비→적용→시작 순서, 98px 레일 CJK 단어 유지; Android 문구/구조 동기화 | 작업본 `daa0285e…` (미커밋) |
| round9 셸 연속성·짧은 landscape 임무 UX | 조종석 몰입은 유지하고 모든 destination에서 같은 drawer를 사용; non-cockpit dark header와 cockpit SE mark; 1366×768/1536×864/1920×830 CTA above-fold, 390px 임무 2열 데이터 | 작업본 `8d8ce077…` (미커밋) |
| round10 조종석 가독성·상태 위계 | 기존 영상 중심 레이아웃 유지, 8개 B-1 사실의 3그룹 요약, 문제/주의의 기존 심각도 마커, 상태 상세 on demand, DJI Pilot 2 경계·로컬 기록 종료 명확화 | 작업본 `328898da…` (미커밋) |
| round11 조종석 최소화 | B-1 strip을 영상 아래로, 영상 조작을 진단으로 이동, 기록 중 영상 해제 확인, 활성 로컬 기록에 한정한 종료 | 작업본 `ed38418c…` (미커밋) |
| round12 조종석 헤더 위계 | KSI 관측·기록 보조, 영상 입력 상태, 시뮬레이션 표기, DJI Pilot 2 조종 권한을 별도 그룹으로 분리 | 작업본 `4b632f56…` (미커밋) |

상세 기록: `history/2026-10-13-round1-review-findings.md`,
`history/2026-10-13-round2-redesign-change-note.md`,
`history/2026-10-13-round3-review-findings.md`,
`history/2026-10-14-round6-full-sweep.md`,
`history/2026-10-14-round7-typography.md`,
`history/2026-10-14-round8-mission-tab.md`,
`history/2026-10-15-round9-usability.md`,
`history/2026-10-15-round10-cockpit-readability.md`,
`history/2026-10-15-round11-cockpit-minimal.md`,
`history/2026-10-15-round12-cockpit-heading.md`

## 보존 제약 (디자인 변경 시 불변)

- **상태 계약 의미**: `docs/plans/2026-10-12-b1-operator-flow-contract.md` — 항목·심각도·행동.
- **정직 규칙**: 사건 0건≠안전 · [SIMULATED] 표기 · RC 수신시각(근사) · 미동기화 수 노출.
- **기능**: 임무 준비/링크/관측/증거·동기화/설정 전부 도달 가능 · 단일 자체완결 HTML.

## 상태

- 2026-10-13 round 3·4: round-4 결과는 `android-rc` 설정 포트(`de62114`)까지 커밋·푸시됐다.
- 2026-10-14 사용자 확인(방향): 카드 최소화·영상 중심·비행 후 상세. round6 브라우저 검증에서 axe
  위반 0, 보조 대비 측정을 완료 — **실기 렌더·햇빛·장갑·실제 영상 대비는 A9-4**.
- 2026-10-14 round7: `captures/round7-typography/RECEIPT.md` 참조. HTML/Android 글자 크기만 정리했고,
  1920/1024/390 조종 레이아웃·접근성·대비를 재검증했다. 단계 번호는 업무 순서를 설명하므로 유지했다.
- 2026-10-14 round8: `captures/round8-mission-tab/RECEIPT.md` 참조. 임무 페이지의 준비 사실·로컬 반영
  상태·수동 단계 순서만 정리했고, 조종 페이지 동작·레이아웃은 변경하지 않았다. 레일은 1920px에서
  두 단어 줄이 유지되는지 확인했으며, 모바일 페이지 스크롤은 마지막 콘텐츠가 하단 내비보다 위에서 끝난다.
- 2026-10-15 round9: `captures/round9-usability/RECEIPT.md` 참조. 조종석은 edge-to-edge로 유지하고
  나머지 화면은 공통 header/drawer를 사용한다. 임무 CTA는 지정 landscape 3종에서 완전히 보이고,
  390px에서는 준비 데이터 2열과 내부 스크롤을 사용한다. 20개 axe run 위반 0; 실제 touch/glove·RC Plus 2
  기기·햇빛·live video·server 동작은 검증하지 않았다. Android, API/계약, 권한·업무 규칙은 변경하지 않았다.
- 2026-10-15 round10: `captures/round10-cockpit-readability/RECEIPT.md` 참조. 넓은 영상 중심 셸을
  유지하며 8개 B-1 사실은 3개 그룹 요약으로 보이고, 전체 사실은 요청 시 연다. 최종 브라우저 검증은
  390×844·960×600·1024×768·1280×800의 5 destination matrix와 B-1 5상태·영상 입력 7상태를 포함한다.
  Android XML 파싱·48dp 구조만 이번 라운드에서 재확인했다. RC Plus 2 렌더·실제 터치/장갑·영상/야외
  시인성은 A9-4에서 확인해야 한다.
- 2026-10-15 round11: `captures/round11-cockpit-minimal/RECEIPT.md` 참조. B-1 요약은 영상 외부의 3그룹으로,
  세부는 disclosure로 유지한다. 연결/해제는 진단으로 이동하며, 활성 로컬 기록 중 영상 해제는 영상 손실
  가능성을 확인한다. KSI 기록 종료는 로컬 기록만 대상으로 한다. 브라우저 검증은 destination 20장,
  B-1 20상태, 영상 7상태, axe 0 violation, console/page error 0, 수평 overflow 0이다. Android는 XML/48dp와
  host suite만 확인했으며 기기 화면·물리 터치·햇빛 판독성은 확인하지 않았다(A9-4 필요).
- 2026-10-15 round12: `captures/round12-cockpit-heading/RECEIPT.md` 참조. 영상 상단 제목을 `KSI 관측 · 현장 기록 보조`로
  읽고, `영상 입력` 상태·`[SIMULATED]`·`기체 비행 조종 / DJI Pilot 2`를 나뉜 그룹으로 확인한다. 960×600·1920×1200에서
  정상 최근 수신·미연결·B-1 문제, 390×844에서 최근·미연결·문제를 캡처했다. HTML 시연의 상태 예시이며 실제 영상이 아니다.
  Android host suite와 XML/48dp 구조 검사는 통과했지만 lint/assemble은 SDK의 `android-34` 부재로 막혔다. Native/RC Plus 2
  렌더·실제 터치·장갑·햇빛 판독성은 A9-4에서 확인해야 한다.
- 2026-10-14 round6: `captures/round6-full-sweep/RECEIPT.md` 참조. 화면 매트릭스 18장 after와
  추가 상태 3장, axe 21개(위반 0), desktop/mobile 대비 보조 측정 18개. Detector의 타이포그래피
  warning/advisory는 영수증에 남겼으며, 실제 기기 판독성은 A9-4에서 확인해야 한다.
- 렌더 검증 규칙(영수증·독립 리뷰·보존 제약): `docs/verification/dev-harness-runbook.md`.
  디자인·렌더 검증은 Design Specialist에 위임하며, OpenDesign 도구는 은퇴(이력 보관)다.
- 이전 OpenDesign 프로젝트(`ksi-safety-edge-rc-pilot` / `ksi-safety-edge-rc-review`)는
  이력 보관용이며, 이후 라운드는 이 저장소에서 진행한다.
