> **v1.1 work plan:** `19_WORKPACKAGES_AND_ADR.md`에서 spatial 작업 책임, 순서, Gate 확인. 제품 동작을 문서가 아니라 실기 시험으로 승인.

# 13. Build / Plan Agent Handoff — 개발 착수 지시

이 문서는 기존 KSI 프로젝트에 바로 실행 가능한 수정 명령이 아니라, **독립 실험 제품을 단계별로 구현하기 위한 작업 계약**이다. 기존 `ksi-platform`의 Cloud API 정상 운영, Active 금지, SDK 통신 경로를 변경하지 않는 것이 최상위 제한이다.

## A. 실행 전 읽기 순서

1. 기존 저장소의 현재 코드/테스트, 최근 Git 기록, `AGENTS.md`, `docs/TELEMETRY_SOURCE_AUTHORITY.md`, `knowledge/PRINCIPLES.md` 읽기.
2. 본 패키지 `README`, `00_DECISIONS_AND_SCOPE`, `01_MASTER_PRD`, `02_ARCHITECTURE`, 계약 스키마, `08_TEST_AND_ACCEPTANCE` 읽기.
3. 설치된 RC APK / Manifold DPK / 서버 digest 및 버전 증거를 수집. 실행환경과 문서 차이 보고.
4. 기존 코드와 신규 설계를 분류: 구현·검증됨/구현됐지만 미검증/설계만 존재/계약 충돌/추가 필요.
5. 하나의 test plan과 work package를 작업 계약으로 고정. 여러 에이전트 병렬 시 interface 변경을 우선 조율.

## B. Work Package Format

```yaml
work_package_id: W11
owner: edge_developer
scope: PSDK Video+Pose Matcher
inputs: [EdgeVideoFrame, FcTopicSample, LensState, MissionSnapshot]
outputs: [ObservationCandidate, MatchQualityReason, TestMetrics]
preconditions: [W03 physical SDK capability proof, schema v1]
non_goals: [Server upload, Active incident write, Flight control]
acceptance:
  - valid identical-value attitude samples retained
  - cached reads not misrepresented as sensor timestamp
  - unknown exposure timestamp reported explicitly
  - cross-session frame/telemetry mixing rejected
required_evidence: [unit_tests, physical_capture, golden_vectors, known_limitations]
rollback: disable EDGE_GEO_ACTIVE and return image-only shadow
```

## C. Build 작업 운영

- Build = 작업을 끝까지 통합하고 계약/증거/리포트를 책임지는 오케스트레이터. 변경량이 작아도 분리 가능한 구현은 적절한 Developer에 위임할 수 있다.
- SDK/Edge, Android, Core, 디자인 작업은 output contract/ownership로 분리; 파일 하나를 여러 에이전트가 동시에 편집할 때는 순차 인계.
- UI 담당은 시각적 작업과 실제 화면 코드 수정까지 가능하되, protocol/DB/권한·안전 경계를 임의 수정하지 않는다.
- 레포를 읽지 않고 이미 구현됐다고 주장하거나 unit green을 physical pass로 제시하지 않는다.
- 운영 장비에 임의 배포/Active source 승격/실제 비행 명령/운영 데이터 수정은 별도 인간 승인 필요.

## D. 우선 구현 작업 순서

1. Contract schemas/OpenAPI validation and fake transport + tests.
2. Edge time/pose/video source semantics spike(실기), 관측·정합 품질 API.
3. RC MOP mission hash apply/observation ACK/recovery.
4. RC local WAL + proxy recorder + crash recovery; Core idempotent upload/readback.
5. Site/Zone snapshot and merge conflict UI/API, read-only Agent tools.
6. Shadow physical flight+geolocation gates; test artifacts.
7. Optional DGX overnight review experiments only after real evidence accumulated.

## E. 매 작업 종료 보고 양식

- 실제 변경 범위/코드 commit, 테스트 명령·실패 항목, 인수조건 PASS/FAIL/NOT_RUN.
- SDK/장치/버전과 실제 물리 테스트 로그 및 SHA.
- 기존 Cloud API 동작/원격 배포 무변경 보장(또는 불가하면 위험 명시).
- 남은 리스크와 다음 단계의 유일한 blocker; 배포/비행 승격 권고는 별도 근거.

## F. 금지사항

- 근거 없는 `fully operational`, `synchronized`, `zero false negative`, `legally compliant` 주장.
- 신규 리비전 미승인 상태로 과거 zone/event 수정.
- RC↔Edge two distinct clock timestamp의 직접 비교 또는 +20ms 같은 하드코딩.
- 조종사 오탐 표시 → raw_event 삭제 또는 학습 레이블 자동 확정.
- PSDK→Core 직접 업로드 경로 추가, 상시 외부 GPU 실시간 판정 도입.
- 실험을 기존 KSI production source로 교체하거나 `Pilot2/Cloud API` 롤백 삭제.
