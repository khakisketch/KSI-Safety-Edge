> **v1.1 Spatial Override:** 물리 위험 경고를 Active로 만드는 일은 아래 Phase 4의 실측 Gate 이후이며, 사람+중장비 **탐지/Tracking·관계 Shadow 계산**은 초기 MVP에서 먼저 시작합니다. 새로운 작업분해와 우선순위 기준은 `19_WORKPACKAGES_AND_ADR.md`입니다.

# 09. 개발 로드맵 / 작업 분해 (dependency-driven)

이 문서는 일정 약속이 아니라 **의존 관계 기반의 작업 순서**다. 물리시험/SDK 호환이 불확실하므로 날짜·인력 투입량을 확정하지 않는다.

## Phase 0. 기준선/프로토콜 결정 (P0)

**성과:** 기존 KSI와 격리된 실험 repo/branch, CI, 스키마, 실제 장비 Capability Matrix.

- W00 existing `ksi-platform` baseline/AGENTS/SSOT/최근 diff 읽기, 현재 배포 확인, Cloud API rollback 보존.
- W01 실험 repo/worktree/환경분리 (dev DB/bucket, device/test aircraft IDs). 새 API prefix / feature flags.
- W02 `mission-snapshot`, `observation`, `upload-manifest`, `annotation` v1 JSON Schema, fixtures + contract tests.
- W03 DJI Matrice4/Manifold3/RC Plus2 SDK·펌웨어 가용성, MOP throughput, USB Liveview, source timestamps 실기 Spike.

**Gate:** PSDK가 필요한 영상/telemetry/frame info를 수집하는 물리 증거. 불가 시 설계를 재검토; 미증명 상태로 구현 확장하지 않음.

## Phase 1. PSDK 수집·시간정합·Shadow AI

- W10 PSDK typed adapter/mono clock/FC topics/camera lens manager.
- W11 `VideoBuffer/PoseRing/Matcher/QualityReason` 구현과 golden-vector 테스트.
- W12 YOLO person/basic PPE TensorRT path, FPS/thermal/quality logging.
- W13 `Observation/event_id/episode` builder와 짧은 edge snapshot ring/spool.
- W14 MOP protocol (session, config, ACK, seq, chunking, state recovery) 구현.

**Gate:** RC와 상호 전송하고 Edge inference frame→telemetry provenance 일치, Active 위험경보는 Shadow 실기 전 제한.

## Phase 2. MSDK 오프라인 완결성

- W20 Android MSDK RC home/site/mission/preflight/live/event/summary/sync screens (MVP).
- W21 SQLite Room WAL + local files/manifest/proxy segmented recording, crash resume.
- W22 Edge link + mission atomic apply + map/zone offline cache + live alert card.
- W23 RC local sequence-gap recovery, operator annotation/undo.
- W24 video proxy 녹화/파일회복/장시간 RC 성능 검증.

**Gate:** 네트워크 0 비행이 완주하고 flight manifests/AI events/recording gaps가 정상 보존.

## Phase 3. 서버·데이터 계보·Agent API

- W30 PostgreSQL schema/migrations, tenant/role/scopes, zone revisions, mission snapshots.
- W31 resumable ingest/manifest/cas/hash/readback/durable ACK/object lifecycle.
- W32 사건·비행·구역·증거/coverage read API 및 최소 웹 (맵 현장/구역, 사건 목록/상세, upload status).
- W33 API/MCP read-only tools, provenance-safe response, partial/unknown status.
- W34 E2E sync/권한/감사/보존 정책.

**Gate:** 웹 설정→RC 다운로드→실기 비행→RC 업로드→같은 위험구역 snapshot 및 증거 조회; loss/dup 0 within bounded test.

## Phase 4. 위치·위험 판단 Active Gate (실측 기반)

- W40 geo ray projection, lens calibration/terrain/ENU transform.
- W41 uncertainty/boundary guard, 실제 검사점과 bbox/landmark GT.
- W42 zone intrusion/proximity episode, outbox/dedup, evidence linkage.
- W43 장비 multi-person occlusion/fps/zones across changes physical test.

**Gate:** 정확도·오탐·misprojection·미관측 지표와 항목별 신뢰성이 실제 현장에서 승인될 때만 Active (운영 기준과 독립 승인 필요).

## Phase 5. 선택적 야간 재검증

- W50 프레임 샘플링/예상 비용/보안 승인.
- W51 DGX Spark offline jobs FP/FN candidates + model/version results.
- W52 사람이 검증한 GT set, holdout 검증/성능 판단.
- W53 검증 효과 있는 분석만 자동 배치 정책에 등록.

**Gate:** 실제 오탐/미탐 후보 처리 개선이 객관적으로 확인되고 원본 불변·업로드 partial 정직성 유지.

## 의존성/병렬 작업

```
W00/W01 ── W02 ─────────────── W30/W31/W32/W33
   └────── W03 ── W10/W11 ── W12/W13/W14
                       └───── W20/W21/W22/W23/W24
                       └───── W40/W41/W42/W43 (later)
W31/W32 + real media ───────── W50/W51/W52/W53
```

### 권장 에이전트/팀 책임 분리

- **Plan / Technical lead:** baseline diff, contract approval, high-risk edge tests, cross-module acceptance, phase gates.
- **Build / Orchestrator:** issues를 작업으로 분해·분산하고 API/schemas/CI/PR 통합, 테스트 evidence 제출 책임.
- **PSDK developer:** C/C++ Edge adapter/matcher/inference/MOP native package.
- **Android developer:** RC UI + DJI MSDK/API, SQLite recording/sync.
- **Backend developer:** FastAPI/DB/object store, Site/Zone/ingest/query/MCP.
- **Design workspace:** RC 화면 및 웹 정보 구조, 실제 UI 코드·시각 검수; API/업무규칙 임의 변경 금지.
- **QA/Evidence owner:** 물리시험/실측/미탐·오탐 ground truth/failure triage.

작업은 파일 기준이 아니라 책임과 입력/출력 계약 기준으로 나누고, 사람이 무한 승인을 기다리는 상태보다 명확한 pass/no-go 근거를 생성한다.

## DoD (Definition of Done)

변경 하나에 최소: 현재 code-state proof, 경계/contract, positive+negative tests, replay/real hardware 필요 여부, 로깅, migration/rollback, security/tenant, 문서/ADR 갱신, reproducible evidence, clean diff, 관련자 reviewer.

## 예상 미검증 항목·지원 의존성

GPU 수량/성능, RC 저장 여유·열, PSDK frame alignment, camera SD 회수, GIS 정확도는 사전에 가격·기능을 확정하지 말고 GATE 기반 선택. 외부 모델·지도·RTK 장비에 대한 가격/라이선스/정확도는 구입·서비스 계약 전 별도 조사.
