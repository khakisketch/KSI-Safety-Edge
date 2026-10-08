# KSI Safety Edge — Spatial Observation v1.1

**상태:** DRAFT / 설계 승인·실기 검증 전. **작성:** 2026-10-08. **기준:** 이전 KSI Safety Edge v1.0 문서 패키지의 변경 설계. 기존 `khakisketch/ksi-platform` 저장소나 실제 기체/RC/서버를 수정하지 않음.

## 읽기 순서
1. `docs/01_MASTER_PRD.md` — 제품 목표, 범위, FR, 합격조건(최상위 제품 계약)
2. `docs/14_SPATIAL_SYSTEM_ARCHITECTURE.md` — 처리 파이프라인과 런타임 책임
3. `docs/15_GEOMETRY_TIME_QUALITY.md` — 좌표·카메라·시간·불확실성
4. `docs/16_PERCEPTION_RELATION_ENGINE.md` — 탐지/Tracking/거리 관계/리소스 제어
5. `docs/17_RISK_POLICY_AND_EPISODES.md` — 위험 규칙·임무·생명주기
6. `docs/18_SPATIAL_VALIDATION_PLAN.md` — 실기 시험·Shadow→Active 승인
7. `docs/19_WORKPACKAGES_AND_ADR.md` — 작업 분해·결정·미해결 조건
8. `contracts/schemas/` + `examples/` + `tests/` — 기계 검사 가능한 계약

## 바뀐 핵심 사항
- `FR-007`: `person` + `excavator`/`dump_truck` 등 사람·중장비 공동 검출/Tracking을 Edge P0으로 정의. PPE는 품질/성능에 따라 스케줄링.
- `FR-020`: 통째 P1에서 3분할. **A: 공간 관계 계산 P0**, **B: 실제 위험 경고는 실기 Gate 완료 전 Shadow**, **C: 붐·버킷 등 동적 작업영역 P1/P2**.
- 신규 `FR-025/026/027/028`: 카메라 기하·오차, Edge 리소스 예산, 거리 관계 물리 검증, 관측·경고 데이터 계약.
- `MOP→RC→서버`만 저장/업로드의 기본 경로. Edge→서버 직접 업로드 **사용하지 않음**.
- LLM/VLM/DGX Spark는 비행 후 선택 검토만 담당. Live 안전 판정 참여 금지.
- 원본 탐지/현장 오탐 의심/VLM 제안/검증된 정답은 서로 다른 계층. 비행 중 모든 사건의 일괄 승인 강제 없음.
- 새로운 `SpatialRelation v1`, `SpatialSummary v1`, `RiskPolicy v1` 스키마는 기존 `Observation v1`을 **변경하지 않고 보완**.

## 비행을 안전하게 다루는 핵심 문장
**거리 추정값은 실제 측량값이 아니다.** 검증된 시각·캘리브레이션·지면 모델이 없으면 근접 미터 경고/위험구역 자동 확정을 하지 않는다. 사람이 영상에서 발견된 사실은 별도로 기록·표시한다. `0 incidents ≠ 0 hazards`.

## 이전 문서와 우선순위
`docs/01_MASTER_PRD.md` + `docs/14~19`가 Spatial v1.1 개정분의 기준. 기존 `docs/02~13` 중 모순이 있으면 명시된 v1.1 범위에 한해 이 문서가 우선하되, 기존 운영 `AGENTS.md`/Cloud API baseline은 절대 덮어쓰지 않는다.

## 공식 기술 참조
- PSDK Liveview: https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/advanced-function/liveview.html
- PSDK FC Data Subscription: https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/basic-function/fc-subscription.html
- PSDK Time Synchronization: https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/basic-function/time-syn.html
- PSDK Liveview AI (Matrice 400+H30 제한): https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/advanced-function/liveview-ai.html
- PSDK Release Notes: https://developer.dji.com/doc/payload-sdk-tutorial/en/
