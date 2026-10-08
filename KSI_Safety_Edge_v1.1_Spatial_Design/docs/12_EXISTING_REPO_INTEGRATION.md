# 12. 기존 KSI 저장소와의 통합/전환 전략

## 1. 이번 작성에서 직접 확인한 기존 repo 문서

리포: `khakisketch/ksi-platform` (main, 2026-10-08 읽기 기준). 이번 작업은 **문서 일부 읽기**이며 전체 코드·배포·APK/DPK 동작을 감사한 것이 아니다.

- `README.md`: 기존 KSI 안전 플랫폼은 GB10 기반 실시간 추론/웹 관제/incident data를 운영하는 제품으로 설명.
- `ARCHITECTURE.md`: 기존 RC/RTMP/Cloud API/GB10 및 파이프라인 데이터 흐름의 구조적 참고.
- `AGENTS.md`: Pilot 2/Cloud API rollback baseline 유지, MSDK Shadow/gates 미통과시 운영 전환 금지.
- `docs/TELEMETRY_SOURCE_AUTHORITY.md`: `cloud_authoritative`, `msdk_compare`, `msdk_authoritative_shadow`와 run-level source isolation, BOUND 조건.
- `knowledge/PRINCIPLES.md`: 탐지/검증 분리, telemetry 우선, fail-closed 공간 판단, episode dedup, tenant isolation, physical evidence.
- `MODELS.md`: 사람/PPE/중장비 모델, recorder/replay tooling 등 **재사용 후보**.

URL: https://github.com/khakisketch/ksi-platform

## 2. 호환해야 할 기존 계약

- 기존 Cloud API 기준선과 환경변수, rollback, evidence pipeline을 **절대 임의 제거하지 않음**.
- Cloud/MSDK 자료를 같은 run의 권위 telemetry로 섞지 않음. 새 Edge에는 source_kind=`PSDK_EDGE` 별도 session/run ID 발급.
- 기존 `incidents/events/evidence` DB writer 직접 재사용 여부는 검증 전 미결정. `EdgeObservationEnvelope`를 adapter로 변환하되 Active Incident writes는 별도 승인.
- 기존 저장소의 `ZONE_INTRUSION_MODE=shadow` 및 physical BOUND gates는 실험에도 최소한의 안전 방향으로 계승하되 PSDK 새 source 구분은 명시.
- SDK 초기화/RC app UX 등에 기존 MSDK POC를 재사용할 수 있으나 설치된 RC APK/GB10 배포 호환성을 자동 가정하지 않음.

## 3. 권장 코드 분리안

```
ksi-platform (기존 운영; Cloud API/GB10/웹기존 계속)
  └─ shared/contracts (사전 검토 후 common identity/evidence model에 맞추기)

ksi-safety-edge-experiment (별도 프로젝트 또는 명시적 worktree/폴더)
  edge/                   PSDK C++ typed adapters/matcher/inference/MOP
  android-rc/             MSDK Kotlin UI/local DB/recording/sync
  core/                   FastAPI/Postgres/Object Storage/agent read API
  contracts/              OpenAPI/JSONSchema fixtures
  tests/                  unit/integration/hardware traces
  docs/                   본 패키지
```

운영 배포 서버와 실험 서버를 공유한다면 schema/bucket/token/feature flag를 분리하고 절대 legacy active incident를 건드리지 않음.

## 4. 포팅 우선순위

- **높음:** 기존 모델 학습/테스트 도구, detector class mapping, incident evidence provenance pattern, stable site/zone metadata adapter, tenant authorization concepts.
- **중간:** Postgres/MinIO infrastructure, Event/outbox pattern, Next.js 최소 웹 read views, RF-DETR 검증 모델(후속 배치).
- **낮음/현재 제외:** RTMP→GB10 real-time path, 중앙 live dashboard, server-first telemetry matcher, 웹푸시 실시간 관제, 별도 상시 GPU.

재사용 여부는 코드 inspection, license, coupling, tests, migration cost 평가 후 결정.

## 5. 기존 제품 대비 실험 목적

| 기존 후보 | Edge 실험 목표 | 평가 방법 |
|---|---|---|
| RC→network→GB10 frame/telemetry matching | Manifold에서 frame/pose/AI 통합 | timestamp/quality/misprojection/fault tests |
| 웹 중심 실시간 경고 | RC 오프라인 조종사 중심 | 조종사 이용성·비행중 경고 latency |
| 중앙 GPU 실시간 추론 | Edge inference + optional batch VLM | 평균/장비당 비용, 품질, latency, offline success |
| 사건 저장 중심 | 비행 전체/미관측+그때의 Zone snapshot | 과거 사건 원인 복구·Agent 근거 조회 |

## 6. 기존 제품과 '합치기' 위한 결정 게이트

1. 실기 검증·모델 정확도·비행 안전성 PASS.
2. 기존 Cloud API 배포/롤백 회귀 및 authority SSOT 충돌 0.
3. 신규 flight/evidence schema migration 및 과거 데이터 readback/권한 회귀.
4. 비용·운영/안전관리자 사용성 개선 측정.
5. 명시된 운영자 approval + 개별 source promotion ADR.

현재는 본 문서 패키지 작성만 수행했으며 repo 수정/배포/장비 설치/실기 검증은 수행하지 않았다.
