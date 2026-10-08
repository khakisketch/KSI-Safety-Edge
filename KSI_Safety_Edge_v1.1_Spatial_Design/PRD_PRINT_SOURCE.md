# KSI Safety Edge v1.1

**실험형 공간 관측 중심 제품 요구사항 정의서**

**문서 상태:** Draft — 실기 검증 및 운영 승인 전

**하드웨어:** DJI Matrice 4 + 기체 탑재 Manifold 3 (PSDK) + RC Plus 2 (KSI MSDK) + KSI Safety Core

**제품 방향:** 오프라인 우선 현장 운영 · 증거 중심 서버 · API/MCP 연동 · 선택적 사후 VLM

> 실험 제품 문서입니다. 기존 ksi-platform/Pilot 2/Cloud API 운영 상태를 변경하거나 기능 승인하지 않았습니다.

**아키텍처 구성도:** 별첨 `diagrams/spatial_architecture.png` 및 편집 가능한 `.dot`, `.svg` 참조.

**한 줄 흐름:** Matrice 4 / Manifold PSDK (탐지·공간관계) → RC MSDK (오프라인 기록·동기화) → KSI Safety Core (증거·API/MCP) → Agent (사후 활용).



## 상세 PRD · Spatial v1.1

**상태: Draft / 개발 설계 제안 / 물리 실험 승인 이전 (2026-10-08).**  기존 운영 KSI Safety의 배포 정책·Cloud API 롤백 경로를 대체하지 않는다.

## 1. 제품 정의와 문제
Matrice 4 + Manifold 3(PSDK)는 현장 영상·비행 상태에서 사람과 중장비를 탐지·추적하고, 관측 조건이 허용하면 실제 공간 관계를 계산한다. RC Plus 2(KSI MSDK)는 **기본 오프라인**으로 임무 설정·영상 표시·경고 확인·로컬 증거 수집을 수행하고, 인터넷 연결 후 KSI Safety Core로 업로드한다. Core는 당시 위험구역/정책/모델/품질과 증거를 영구 보존하고 API/MCP에서 근거를 제공한다. DGX Spark VLM은 선택적 **비행 후** 검토에만 사용한다.

**해결하는 문제:** 사람 탐지와 위험거리 판정 사이에 영상-자세 시각·지면 투영·오차가 빠져 있으면 숫자는 그럴듯해도 안전 판단 근거가 되지 못한다. PSDK 계산만으로 시간정합 정확도를 보장한다고 가정하면 안 된다.

## 2. 사용자 가치
- 조종사: 인터넷 없이 드론/AI 상태를 확인하고 위험 후보를 알림받는다. '오탐 의심'을 한 번에 표시할 수 있으나 모든 사건 승인을 강제하지 않는다.
- 안전관리자: 어제의 위험 사건뿐 아니라 **그 당시 위험구역, 최단거리 추정 근거, 관측 불가 구간, 오탐·미탐 후보**를 확인한다.
- 관리자/에이전트: API/MCP로 현장 간 안전 추세를 질의하고 판단 근거에 접근한다. 안전 판정/조치는 사람의 권한으로 유지한다.

## 3. 아키텍처 경계

- **PSDK+Manifold3** — 책임: Liveview/FC topic, 정합 품질, 사람·중장비 Detection/Tracking, geometry, spatial relation, rule shadow/Active, Observation, 작은 spool; 금지/비기능: 서버 직접 업로드; 장기 원본 보관; 비검증 거리 경고
- **RC Plus2 KSI MSDK** — 책임: 임무·zone/policy 사전 준비·적용 ACK, DJI 라이브 영상, 알림, 로컬 영상/증거, 오탐 의견, upload queue; 금지/비기능: 인터넷이 없다는 이유로 비행 중단; Edge 판단을 RC wall-clock으로 재정렬
- **KSI Safety Core** — 책임: 불변 미션/zone/모델·이력·증거, 무결 업로드, 조회, 최소 웹, API/MCP; 금지/비기능: 실시간 비행 경고 결정 참여; PSDK 직수신
- **DGX Spark** — 책임: 필요시 FP/FN 후보 야간 VLM·전문 CV 검토; 금지/비기능: 실시간 판정, 원본 overwrite, 자동 학습 정답 지정


## 4. 릴리스 범위/우선순위
### P0 — 초기 제품으로 구현할 대상
1. 사람 + `excavator` + `dump_truck` 등 **검증된 class map**에 대한 통합 객체 탐지 및 Tracking; 물리 검증 전 일부 class는 Shadow 가능.
2. 각 객체의 영상 좌표 + 원본 영상 프레임/모델/시각/포즈 계보 보존. 가능한 객체에 대해 footpoint/footprint를 지면 좌표로 추정.
3. 사람↔장비의 후보 쌍 및 최소 지면 거리/거리 구간 계산. 공간 조건을 충족하지 못하면 **null + reason** 기록. 값 없는 거리로 사건을 생성하지 않음.
4. 고정 위험구역과 사람·장비의 포함/경계/모호 관계 계산. zone revision, policy revision, camera-calibration ID 불변 연결.
5. 근접/구역 규칙 엔진의 `OFF|SHADOW|ACTIVE` 모드와 episode 상태 머신. **초기값은 SHADOW**, 개별 실기 Gate 통과 시에만 Active 승격.
6. 성능/열/메모리 부하 분리; 인식 FPS보다 비행 조종/UI와 MOP 전송 안정성 우선. 유효하지 않은 관측 공백·품질 저하 계측.
7. RC 기본 오프라인 동작, event/snapshot/비행 영상 버퍼, 인터넷 복귀 후 서버 무결 업로드, 원본 탐지·오탐 의견 분리.
8. 서버의 비행 당시 geometry/risk 근거 조회 API 및 API/MCP. 웹은 현장·구역·사건·조치 중심; 라이브 웹 스트리밍 제외.

### P1 — 실측 확보 후 확대
PPE 병렬 고해상도 재검증 최적화, 사람/장비 접근 방향 추정 고도화, 장비별 고정 작업범위 기반 위험정책, Geo 확정/근접 Active 별도 Gate, SD 원본 매칭, 사후 VLM 오탐·미탐 후보 탐색.

### P2 — 초기 제품에서 제외
굴착기 붐/버킷 3D 회전·동적 선회반경 실측 추정, 자율 추적 비행, 실시간 서버 GPU, AI에 의한 작업자 개인 식별, 모든 프레임 영구 저장, Dock 전환(별도 어댑터 ADR).

## 5. 변경 기능 요구사항 (v1.0 대비)

- **FR-007-R1** — 우선: P0; 테스트할 시스템 계약: person/equipment 통합 class map; detector+tracker 세션 소유; frame/model provenance
- **FR-020A** — 우선: P0; 테스트할 시스템 계약: 사람/장비 지면 점·점유영역 추정 및 pairwise 거리 구간; 품질 부족 시 null/unknown
- **FR-020B** — 우선: P0 구현·Active는 Gate; 테스트할 시스템 계약: zone/proximity rules는 `OFF
- **FR-020C** — 우선: P2; 테스트할 시스템 계약: 붐·버킷 관절 가변 작업반경은 v1.1 Active 아님
- **FR-025** — 우선: P0; 테스트할 시스템 계약: 좌표계, 고도 기준, lens/zoom/calibration, frame-time 및 source time provenance, ground model revision 저장
- **FR-026** — 우선: P0; 테스트할 시스템 계약: CPU/GPU/FPS/thermal/buffer governor, 처리 축소 때 coverage/audit gap 기록
- **FR-027** — 우선: P0; 테스트할 시스템 계약: 실제 측량 GT와 시간·거리·구역 confusion matrix 측정, Gate별 허용 envelope 명시
- **FR-028** — 우선: P0; 테스트할 시스템 계약: `SpatialRelation/SpatialSummary/RiskPolicy` v1 계약, mission/zone/policy 변경 시 versioned ACK 및 lineage
- **FR-029** — 우선: P0; 테스트할 시스템 계약: MOP 누락·중복·재연결·reset 시 안정된 episode ownership, RC/server idempotent ingest


기존 FR-001~006, 008~019, 021~024는 v1.0대로 유지하되 본 개정의 품질 게이트를 우선 적용한다. FR-020 항목은 단일 P1 정의를 폐기하고 상기 3개로 대체한다.

## 6. 작업 흐름
1. 웹/RC에서 현장·위험구역·rule policy 버전 생성. RC의 오프라인 캐시에 저장.
2. RC→Edge MOP로 `MissionSnapshot + RulePolicy + model profile` 전달. Edge는 digest/지원 여부 검사 후 **원자적 APPLIED ACK**. 비행 중 변경은 `effective_edge_seq`로 적용 시점 기록.
3. Edge가 영상/FC sample 별도 수집. 각 sensor의 원시 측정시간 또는 로컬 수신시간을 provenance로 유지; 영상 노출시각은 별도 검증 없는 한 '추정'이라고 표시.
4. Detector→Tracker→Geometry→SpatialRelation→RiskPolicy를 실행. 품질 부족 시 `DETECTED_ONLY/UNDETERMINED`으로 격하.
5. 후보 사건(별도 원본 프레임 포함)을 MOP로 RC에 전송. DJI 라이브 영상은 별도 경로이며 완벽한 동일 프레임 UI 오버레이는 실기 검증 시 활성화.
6. RC 로컬 기록; 사무실 Wi-Fi 등으로 연결되면 upload queue가 서버로 전송, hash/readback ACK 후 완료.
7. KSI Core는 원본 탐지/운영자 의견/VLM 의견을 분리해 조회 API/MCP에 제공. GPU는 옵션.

## 7. 승인·거절의 원칙
- 사람/장비 bbox는 영상 검출로 기록 가능하지만 **위치/거리 경보는** 카메라 기하 + 지면 + 시간 표본 정합 검증 없으면 금지.
- `estimated_distance_m`만으로 작업 안전을 확정하지 않으며, 거리 불확실성 구간이 정책 임계값을 걸치면 `UNCERTAIN`으로 반환.
- 각 episode는 세션/track lineage를 가진다. 짧은 가림이나 stream gap은 `UNKNOWN/HOLD`이지 '안전하게 이탈'이 아니다.
- 서버에 사건이 0건이어도 분석 성공·관측 범위·촬영 품질을 확인할 때까지 '안전 확인'으로 표현하지 않는다.

## 8. 성능/품질 지표 (잠정; 실기 측정 후 수치 확정)
- Detector AP/Recall 클래스별·고도/거리/조도/가림별 측정. 추적 ID-switch 및 episode 중복률 별도.
- Frame→Pose 정합: p50/p95 분포 및 yaw/짐벌 변화 시 오차, source timestamp 신뢰성 등급.
- 공간: 지면 위치 MAE/p95, 사람↔장비 표면거리 절대오차/p95, zone 안팎 판정 혼동행렬, 불확실 판단의 적절성.
- 런타임: capture-to-RC-alert p50/p95, 유효 frame 비율, infer/drop rate, RAM/thermal, MOP backlog, RC 보존/서버 업로드 완결.
- 합격 임계치는 카메라·비행 높이·조명·대상 크기·정합 오차 envelope별로 별도 수립. **3m/5m를 전국 공통 경보 기준으로 하드코딩하지 않는다.**

## 9. 핵심 Acceptance Gates
- G0 장비/SDK/카메라·포즈 데이터 이용 가능성이 실제 Matrice 4+Manifold3에서 입증됨.
- G1 사람·장비 detection + tracking이 30분 실기에서 해시·세션 기준 재생 가능.
- G2 지면/렌즈/시간 검증: 독립 GT 거리·좌표 시험, 불확실하면 GEO/PROXIMITY Active 0.
- G3 위험구역/관계 Shadow: 정상/경계/가림/정지 자세/재연결/다인 장면에서 오탐·미탐·episode 중복 수치.
- G4 RC offline→Wi-Fi upload→server readback→Agent API 한 여정 검증; loss/duplicate detection, unresolved gap explicit.
- G5 Active는 **zone**과 **proximity**를 각각 따로 승인. Cloud API 기존 운영 기준선/rollback 유지.

## 10. 기술 세부 링크
`14_SPATIAL_SYSTEM_ARCHITECTURE.md`, `15_GEOMETRY_TIME_QUALITY.md`, `16_PERCEPTION_RELATION_ENGINE.md`, `17_RISK_POLICY_AND_EPISODES.md`, `18_SPATIAL_VALIDATION_PLAN.md`, `19_WORKPACKAGES_AND_ADR.md`가 구현 지시의 근거다. 새 기계 계약은 `contracts/schemas/spatial-relation.v1.schema.json`, `spatial-summary.v1.schema.json`, `risk-policy.v1.schema.json`.

## 상세 설계 참조

`14_SPATIAL_SYSTEM_ARCHITECTURE.md` ~ `20_API_ADDITION_SUGGESTION.md`, 계약 JSON Schema 및 검증 스크립트는 함께 제공한 전체 개발 패키지에 포함됩니다.
