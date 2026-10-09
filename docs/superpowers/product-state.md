# Product State — KSI Safety Edge

Updated: 2026-10-08 · Owner: Build (Primary) · Spec: `docs/superpowers/specs/2026-10-08-integrated-design.md`

## 제품 목표

인터넷이 없어도 현장에서 사람·중장비를 관측·기록하고, 연결 복구 후 **당시 설정과 증거를 재현**할 수 있는
드론 기반 안전 관측 제품. 공간 판단은 SHADOW가 기본이며 ACTIVE는 실측 검증·규칙별 승인 후에만.

## 마일스톤

| ID | 내용 | 상태 |
|---|---|---|
| M0 | 설계·계약 기준선 (스키마 13종, 도메인 검증, OpenAPI 39 ops) | **완료** (C1=050d80f, 독립 검증) |
| M1 | 재생 기반 전체 흐름 (임무 적용→RC 오프라인 저장→업로드→조회, E01~E12) | **구현·재생 검증 완료** (C3=b2366a8, 112 passed) — 사용자 end-to-end 인수 미실시 |
| H1 | 실제 장비 능력 확인 (PSDK/MSDK/영상/시간/MOP/RC 저장) | **진행 중** — 장비 접속·인벤토리 장치측 PASS + KSI 자체 코드 온보드 빌드 스모크 6/6(2026-10-08, `docs/verification/evidence/2026-10-08-h1-1/`). 기체 연결·DPK·시간 정합은 미실시. 계획(`docs/verification/h1-capability-plan.md`) |
| M2 | 실제 Edge 탐지 전체 흐름 | 진행 — W2 코어 + W4 PSDK 앱 장치 빌드·DPK 생성 + D-FINE 전처리/decode(C16) + tracker(C17) + 세션 체인(W7/C18) + detector adapter(W8/C19) + ONNX export + W9 TRT 런타임·smoke(fp16) + W10 + **R1~R4 완료(C28~C31: 공통 `SessionRunner`·실엔진 Replay·Tracking 출처·Telemetry 정합·Spatial 관측)** + **R5 RC Emulator E2E(C32: 실녹화→RC emulator→Core 업로드→조회, 동일 코드 3경로 동일성)**. GPU 추론: corpus 녹화 Replay에서 검증 — 실영상·DPK 설치(기체 세션)·MSDK E2E·WAL 미검증 |
| M3 | 공간 SHADOW (지면/거리/zone 관계, episode) | 진행 — Spatial 코어(C11) + Risk/Episode 엔진(C15) + **R4 공간 관측(발점/bottom-edge footprint, GEO_ESTIMATE; 정합 불가 시 Detection 유지·Spatial만 보류)** — 합성 정책으로 E2E 검증(에피소드·거리 기록). 실측 calibration·GT·정확도 검증은 후속. 물리 촬영 시각 미확보 시 시뮬레이션·SHADOW 유지 |
| M4 | 제한된 규칙 ACTIVE (규칙별 승인 후) | 대기 |

## 제품화 경로 (2026-10-08 정리)

**인수 사다리** (순서대로 달성):

1. **L1 관측 제품 사용 가능** — 실제 탐지·오프라인 증거 저장·재시작·재연결 복구·서버 조회.
2. **L2 현장 SHADOW 시험 가능** — 거리·구역·위험 평가를 정답과 비교, 품질 측정(경고 아님).
3. **L3 제한 위험 경고 운용** — 검증된 조건·규칙에서만 ACTIVE.

**개발 줄기(병행)**:

| 줄기 | 즉시 할 일 | 막는 외부 입력 |
|---|---|---|
| 사용 흐름 | Android RC 앱(임무→적용→증거→업로드→조회)+재생 연동 | Android SDK/adb·RC 접근 경로 |
| 실제 인식 | **R1~R5 완료** — 실녹화 Replay로 탐지·추적·공간·E2E·성능 측정 완료(corpus/합성). 대표 영상 확보 시 평가·중장비 비교 | 대표 영상 |
| 판단·검증 | 실측 calibration·GT·정책 임계 확보 시 L2 평가 | 기체 연결 세션(④단계) |

실행 상세·진행·증거: `docs/superpowers/plans/2026-10-08-productization-plan.md`.

## 현재 슬라이스

- M1 acceptance(E01~E12)는 재생 하네스에서 통과: `harness/` README에 시나리오↔테스트 매핑.
- 현재 슬라이스: **Replay 통합 R1~R5 구현·테스트 완료(원장 §8)** — R5 RC Emulator E2E(실녹화→업로드→조회) + 동일 코드 입증; 독립 검증 진행. 실장비 E2E·설치는 기체 세션 게이트 유지.
- 첫 인수 목표: **L1(관측 제품)** — 사용자 방향 수용(2026-10-08); L2 병행, L3 게이트 유지.
- 범위 밖(다음): Android 앱(빌드 환경 없음), PSDK C++ 앱(장비·SDK 환경), 제품 UI(디자인 워크스페이스), ACTIVE 경고.

## 실행 방법 (개발)

```bash
bash scripts/setup_env.sh          # 환경 (멱등)
bash scripts/run_all_tests.sh      # 전체 검증 (112 tests)
.venv/bin/python -m uvicorn ksi_core.app:create_app --factory --port 8000
```

## 원칙 (변경 금지)

1. Edge→서버 직접 업로드 금지. RC가 업로드 주체.
2. 라이브 DJI 영상과 Edge 추론 프레임을 동일 프레임으로 가정 금지.
3. VLM/LLM은 비행 후 검토만. 실시간 판단 참여 금지.
4. 원본 탐지/운영자 의견/사후 의견/검증 라벨은 분리 계층.
5. `0 incidents`는 안전 확인이 아님. 관측 공백을 함께 표현.
6. 원본 패키지(`KSI_Safety_Edge_v1.1_Spatial_Design/`)는 출처 snapshot으로 수정하지 않는다.

## 후속 입력 대기 (제품을 막지 않는 항목)

- v1.0 전체 FR 원문 (없으면 추적표 기준으로 확정) · 기존 `khakisketch/ksi-platform` 코드 대조
- 장비 SDK/펌웨어 조합·설치 환경 (H1) · 운영 결정: 권한 기간·보존 정책·현장 수 (D06)
- UI 단계 착수 시 OpenDesign 연결 확인 (D08) · E12 관련 세부 편차는 harness/README에 기록
