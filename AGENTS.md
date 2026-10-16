# KSI Safety Edge — 개발 정본 진입 안내

인터넷 없이 현장에서 관측·기록하고, 연결 복구 후 당시 설정과 증거를 재현하는 드론 안전 관측
제품(C++ Edge · Android RC · Python Core). 제품 개요·저장소 구성은 `README.md`.

이 파일은 **문서 지도**다. 내용을 복제하지 않는다. 문서가 서로 어긋나면 아래 "현행 정본"을 따른다.
기준일: 2026-10-16.

## 현행 정본

| 주제 | 정본 | 비고 |
|---|---|---|
| 제품 상태·인수 사다리(L1~L3)·불변 원칙 | `docs/product-state.md` | 상태 판단의 최우선 기준 |
| 통합 설계 기준본 | `docs/specs/2026-10-08-integrated-design.md` | REQ·경계·E01~E12 |
| 계약 정책(스키마·OpenAPI) | `contracts/README.md` | 스키마 바이트 불변·digest 규칙 |
| 서비스 여정 | `docs/specs/2026-10-13-service-blueprint.md` | S0 확정 · S1/S2 초안(확정 금지) |
| 조종 상태 계약(B-1) | `docs/plans/2026-10-12-b1-operator-flow-contract.md` | 항목·심각도·문구·행동 |
| 현장 위험구역 지정(비행 전) | `docs/plans/2026-10-16-field-zone-designation-contract.md` | RC 지도·점 찍기 — 캐시/저장/적용 분리, SHADOW |
| 디자인 정본 | `design/rc-pilot-shell/README.md` · `field-shell.html` | 렌더: `bash scripts/design_preview_server.sh` → `:8765` |
| 실행 원장(작업 기록) | `docs/plans/2026-10-08-productization-plan.md` | 날짜별 이력 — 현재 판단은 product-state |
| 검증 런북 | `docs/verification/dev-harness-runbook.md` (전체 루프) | 목업/데모/실기 진입 구분 포함 |
| 실기(A9-4) 런북 | `docs/verification/a9-4-device-day-runbook.md` · `a9-msdk-integration-plan.md` | 장치 세션 입력 3개 필요 |
| 배포 전 보안 게이트 | `docs/plans/2026-10-14-production-security-gate.md` | G1~G7 목록·결정 필요 항목 |

## 상태 구분 (지켜야 함)

- **구현 완료 / 재생(Replay) 검증 / 실기(장치) 검증 / 사용자 인수 / 보안 게이트**는 서로 다른
  상태다. 뭉개서 "완료"로 쓰지 않는다 — 상세는 `docs/product-state.md`.
- 실기 지상 세션(A9-4)은 외부 입력이 필요하다: RC Plus 2 USB·개발자 디버깅, DJI 개발자 앱 키,
  Manifold 접근. 앱 키는 `android-rc/local.properties`(`dji.apiKey`)에만 두고 커밋·채팅 금지.
- 정직 규칙(사건 0건≠안전, [SIMULATED] 표기, RC 수신시각 근사, 서버 상태 추측 금지)의 원문은
  `product-state.md` §원칙과 `contracts/README.md`다.

## 과거 문서 (현행 실행 지시 아님)

- `docs/plans/2026-10-08-m0-m1-replay-slice.md` — M0/M1 완료 계획(이력).
- `docs/plans/2026-10-08-reuse-audit-and-porting.md` — 완료 원장.
  **§0 불변 원칙만 현행 참조**로 유지된다.
- `docs/plans/2026-10-13-a2-reviewer-annotation.md` — 완료 구현 기록(계약 사실은 현행).
- `docs/verification/reuse-assessment.md` — 2026-10-08 조사 초안.
- 이 문서들에 남은 도구 지시(예: `REQUIRED SUB-SKILL`, OpenDesign 사용)는 **당시 기록**이다.
  현재 실행 지시로 해석하지 않는다.

## 경로·이름 주의

- 정본 문서는 `docs/` 단일 체계다 — `docs/product-state.md` · `docs/specs/` · `docs/plans/` ·
  `docs/verification/`. 2026-10-16 이전의 `docs/superpowers/` 경로는 이력 기록(증거·캡처
  영수증)에만 남아 있으며, 그 기록들은 수정하지 않는다.
- `ce-code-review`, `OpenDesign` 언급은 날짜별 이력이다. OpenDesign은 은퇴 — 디자인·렌더 검증은
  Design Specialist + `docs/verification/dev-harness-runbook.md` 경로를 쓴다.

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
