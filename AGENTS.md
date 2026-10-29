# KSI Safety Edge — 개발 정본 진입 안내

Manifold가 오프라인 우선 관측·평가·판정불가·원본 증거화를 수행하고, 사람은 비행 안전과
필요한 예외를 감독하는 제품(C++ Edge · Android RC · Python Core). 제품 개요·구성은 `README.md`.

> 공개 인계본: 실기 식별정보·현장 기록 및 일부 디자인/검증 원본은 비공개 보존한다. 기존 공개 이력의 변경을 뜻하지 않는다.

이 파일은 **문서 지도**다. 내용을 복제하지 않는다. 문서가 서로 어긋나면 아래 "현행 정본"을 따른다.
문서 지도 갱신: 2026-10-26. 표는 주제별 진입점이지 필수 독서 순서가 아니다. 제품 승인·검증일은 각 정본과 증거를 따른다.

## 현행 정본

| 주제 | 정본 | 비고 |
|---|---|---|
| 제품 목적·사용 환경·현재 범위 | `PRODUCT.md` · `docs/plans/2026-10-23-autonomous-observation-stage1.md`와 관련 2·3단계 문서 | 10-23 자동 관측/감독 개정 우선. 제안·코드·실기 승인은 별개 |
| 사용자 업무·화면 책임 | `docs/specs/2026-10-21-operator-journey.md` | S0~S6 연결·핵심 정보·행동·실패/복구, 각 소유 화면/행동 계약 연결 |
| 시스템·데이터·계약 | `docs/specs/2026-10-08-integrated-design.md` · `docs/plans/2026-10-21-manifold-role-contract.md` · `contracts/README.md` | Edge/RC/Core 책임·스키마 불변·digest |
| 실제 구현·검증·L1~L3·남은 문제 | `docs/product-state.md` | 현재 요약. 관련 코드·미커밋 변경·근거와 대조하며 제품 승인을 대신하지 않음 |
| native 화면 현황·시각 체계 | `docs/product-state.md` · `DESIGN.md` · `docs/verification/rc-design-review.md` | 구현·제한된 실측·선택 전 제안·웹 모의를 구분. 일부 원본은 별도 보존 |
| 실제 UI 구현 | `android-rc/rc-app/src/main/java/ksi/safetyedge/rc/RcWorkspaceLayout.kt` · `android-rc/rc-app/src/main/java/ksi/safetyedge/rc/MainActivity.kt` | 구성·상태 배선. 웹 `field-shell.html`은 시뮬레이션 |
| 실행·실기·보안 검증 | `docs/verification/dev-harness-runbook.md` · `docs/verification/a9-4-device-day-runbook.md` · `docs/plans/2026-10-14-production-security-gate.md` | 현재 승인 범위·입력·장비별 게이트를 확인 |
| 결정·작업 이력 | `docs/adr/` · `docs/plans/2026-10-08-productization-plan.md` | 당시 출처. 현재 상태나 다음 과업을 독자 선택하지 않음 |

실행 담당자가 변경 사실·근거를 반환하고 Build가 공유 요약을 통합한다. 승인된 목적과
구현·검증을 분리하며, 제품 사실이 바뀌지 않는 수정은 원장 행을 추가하지 않는다.

## 상태 구분 (지켜야 함)

- **구현 완료 / 재생(Replay) 검증 / 실기(장치) 검증 / 사용자 인수 / 보안 게이트**는 서로 다른
  상태다. 뭉개서 "완료"로 쓰지 않는다 — 상세는 `docs/product-state.md`.
- 실기 지상 세션(A9-4)은 외부 입력이 필요하다: RC Plus 2 USB·개발자 디버깅, DJI 개발자 앱 키,
  Manifold 접근. 앱 키는 `android-rc/local.properties`(`dji.apiKey`)에만 두고 커밋·채팅 금지.
- 정직 규칙(사건 0건≠안전, RC 수신시각 근사, 서버 상태 추측 금지)의 원문은
  `docs/product-state.md` §원칙과 `contracts/README.md`다. 실제 데이터·장비와 섞일 수 있는 앱의
  모의 링크·영상·응답은 실제 수신값처럼 보이지 않게 출처를 구분한다.

## 디자인 검토 화면과 운용 사실의 경계

- 실제 기기·운용 데이터/명령 서버와 연결하지 않는 **독립 디자인 시안**은 실제 제품처럼 검토한다.
  정적 파일이나 고정 재생 fixture를 읽는 로컬 미리보기 서버는 이 범위에서 제외되지 않는다. 시안임을 알리는
  `[SIMULATED]` 등 배지나 `시안`, `모의`, `가상`, `예시`, `실제 장비 아님` 류의 반복 설명을
  넣거나 같은 뜻의 다른 배지로 바꾸지 않는다. 과업에 필요한 상태·실패·복구 문구는 단어만 보고 지우지 않는다.
- 시안·합성 입력 여부, 자산 출처/변경, 렌더 조건, 검증 범위와 미검증 사항은 해당 작업 보고서나
  캡처 메타데이터에 기록한다. 저작자 표시 등은 각 라이선스의 요구를 충족한다.
- 이 규칙은 연결된 앱의 사실성 예외가 아니다. 모의 데이터와 실제 보고가 섞일 수 있는 런타임의
  출처 표시, 미수신·지연·판정불가, 요청/실제 결과 구분과 안전 게이트는 보존한다. 장치 상태·
  명령 결과·서버 보관·영상의 수신/표시를 합성값이나 ACK로 만들어내지 않는다.
- `.impeccable/surfaces/va-*`의 현재 범위만 현행 브리프다. 그 안의 이전 라운드와
  `.impeccable/critique/` 등의 당시 검토는 이력으로 읽으며, 표기 요구를 새 시안의 규칙으로
  자동 승계하지 않는다. 상세 적용은 `docs/plans/2026-10-21-screen-contract.md`를 따른다.

## 과거 문서 (현행 실행 지시 아님)

- `docs/product-state-history-2026-10-26.md`와 `docs/specs/2026-10-21-operator-journey-history-2026-10-26.md`는 분리 전 원문이며 공개 인계에서는 비공개 보존한다. 최신 렌더·장비 상태·다음 작업으로 읽지 않는다.
- `docs/plans/2026-10-08-m0-m1-replay-slice.md` — M0/M1 완료 계획(이력).
- `docs/plans/2026-10-08-reuse-audit-and-porting.md` — 완료 원장.
  **§0 불변 원칙만 현행 참조**로 유지된다.
- `docs/plans/2026-10-13-a2-reviewer-annotation.md` — 완료 구현 기록(계약 사실은 현행).
- `docs/plans/2026-10-13-rc-cockpit-implementation.md` — 당시 콕핏 구현·검증 기록. 예전 시안 표기·
  배치·장비 현황은 현재 독립 화면의 지시가 아니다.
- `docs/verification/reuse-assessment.md` — 2026-10-08 조사 초안.
- 이 문서들에 남은 도구 지시(예: `REQUIRED SUB-SKILL`, OpenDesign 사용)는 **당시 기록**이다.
  현재 실행 지시로 해석하지 않는다.

## 경로·이름 주의

- 정본 문서는 `docs/` 단일 체계다 — `docs/product-state.md` · `docs/specs/` · `docs/plans/` ·
  `docs/verification/`. 2026-10-16 이전의 `docs/superpowers/` 경로는 이력 기록(증거·캡처
  영수증)에만 남아 있으며, 그 기록들은 수정하지 않는다.
- `ce-code-review`, `OpenDesign` 및 은퇴한 역할 명칭은 이력이다. 현행 역할·위임은 전역 지침을 따르며,
  프로젝트가 모델·역할·스킬 호출 정책을 다시 정의하지 않는다. 현재 UI 검토 경로는
  `docs/verification/rc-design-review.md`와 `docs/verification/dev-harness-runbook.md`다.
- 스킬은 설치된 현행 지침을 사용한다. 과거 계획·검증 기록의 도구 지시는 실행하지 않는다.
  브리프는 도구가 실제 대상에 대해 읽는지 확인하며, 이력을 최신 브리프 대신 로드하지 않는다.
- `design/rc-pilot-shell/history/**`의 라운드 README·surface brief·이전 제안 이미지는 **이력**이다.
  현행 지침·디자인 정본으로 읽지 않는다. 활성 surface brief는 `.impeccable/surfaces/va-*` 하나다.

## 보존 (수정·이동·삭제 금지)

- `contracts/` — 승인된 계약(정책은 `contracts/README.md`).
- `docs/verification/evidence/**` — 승인·검증 증거.
- `KSI_Safety_Edge_v1.1_Spatial_Design/`, `KSI_Safety_Edge_v1.1_Spatial_Design_Package.zip`,
  `KSI_Safety_Edge_Master_PRD_v1.1.docx` — 원본 snapshot.
- 다른 세션의 미커밋 변경(작업 트리) — 임의 되돌리기·덮어쓰기 금지.

## 검증 실행 (요약 — 절차는 런북)

```bash
bash scripts/run_all_tests.sh           # Python (contracts·libs·core·harness)
bash scripts/android_host_tests.sh      # Android (rc-ports/rc-core/rc-msdk/rc-app)
bash scripts/run_local_verification.sh  # 로컬 전체 미러 (python→edge→android)
```

- WorkManager/Robolectric 6건은 aarch64 호스트에서 SKIP — `.github/workflows/ci.yml`(x86_64)이
  실실행 정본이다. 스킵을 통과로 쓰지 않는다.
- 세션 재개 스크래치: `.opencode/working-state.md`(gitignored, 정본 아님, ≤80줄).
