# Product State — KSI Safety Edge

Updated: 2026-10-14 · Owner: Build (Primary) · Spec: `docs/specs/2026-10-08-integrated-design.md` · 문서 지도: `AGENTS.md`

## 제품 목표

인터넷이 없어도 현장에서 사람·중장비를 관측·기록하고, 연결 복구 후 **당시 설정과 증거를 재현**할 수 있는
드론 기반 안전 관측 제품. 공간 판단은 SHADOW가 기본이며 ACTIVE는 실측 검증·규칙별 승인 후에만.

## 마일스톤

| ID | 내용 | 상태 |
|---|---|---|
| M0 | 설계·계약 기준선 (스키마 13종, 도메인 검증, OpenAPI 39 ops) | **완료** (C1=050d80f, 독립 검증) |
| M1 | 재생 기반 전체 흐름 (임무 적용→RC 오프라인 저장→업로드→조회, E01~E12) | **구현·재생 검증 완료** (C3=b2366a8, 112 passed) — 사용자 실제 장비 end-to-end 인수 미실시 |
| H1 / A9-4 | 실제 장비 능력 확인 (PSDK/MSDK/영상/시간/MOP/RC 저장) | **실기 게이트 대기** — 10/13 사전 점검 및 10/14 재확인에서 RC USB 장치 없음, DJI 앱 키 없음, Manifold 접속 불가 확인(`docs/verification/evidence/2026-10-13-a9-4/preflight.txt`; 10/14 연결 확인은 read-only). 장비 접속·인벤토리 PASS 및 KSI 온보드 빌드 스모크 6/6(2026-10-08)은 별도 이전 증거이며 실기 통합 PASS를 뜻하지 않음. 계획: `docs/verification/a9-4-device-day-runbook.md`, `docs/verification/a9-msdk-integration-plan.md` |
| M2 | 실제 Edge 탐지 전체 흐름 | **코드·Replay 검증 완료, 실기 미검증** — D-FINE·Tracking·공통 `SessionRunner`·Telemetry 정합·Spatial·R5 RC Emulator E2E(C28~C32; corpus/합성). 실영상 대표 표본·DPK 현장 설치·MSDK 전송/영상 연결 및 성능은 장비 세션 전까지 미검증 |
| M3 | 공간 SHADOW (지면/거리/zone 관계, episode) | **합성·Replay 경로 검증, 실측 정확도 미검증** — GEO_ESTIMATE, 정합이 부족하면 Detection만 유지하고 Spatial은 보류. 현장 GT·calibration·holdout 평가 전까지 SHADOW 유지 |
| M4 | 제한된 규칙 ACTIVE (규칙별 승인 후) | 대기 |

## 제품화 경로 (2026-10-08 정리)

**인수 사다리** (순서대로 달성):

1. **L1 관측 제품 사용 가능** — 실제 탐지·오프라인 증거 저장·재시작·재연결 복구·서버 조회.
2. **L2 현장 SHADOW 시험 가능** — 거리·구역·위험 평가를 정답과 비교, 품질 측정(경고 아님).
3. **L3 제한 위험 경고 운용** — 검증된 조건·규칙에서만 ACTIVE.

**개발 줄기(병행)**:

| 줄기 | 즉시 할 일 | 막는 외부 입력 |
|---|---|---|
| 사용 흐름 | **소프트웨어 구현 완료, 실기 인수 대기** — Android 임무·관측·증거·동기화 여정(A2~A9-3), Cockpit UI(C67), Core E2E 및 빌드/테스트 보유. 다음은 RC Plus 2에서 A9-4 지상 검증 | RC USB/개발자 디버깅, DJI 앱 키, Manifold 접근; 선택: 개발 Core |
| 실제 인식 | **R1~R5 완료** — corpus/합성 Replay로 탐지·추적·공간·E2E·성능 검증. 실제 현장 대표 영상/중장비 비교는 미실시 | 대표 영상 |
| 판단·검증 | 합성 경로 완료, L2 실측 평가 대기. **ACTIVE는 비활성** | 기체 연결 세션, 실측 calibration·독립 GT·정책 승인 |

실행 상세·진행·증거: `docs/plans/2026-10-08-productization-plan.md`.

## 현재 상태와 L1 완료 기준

- **코드상 L1 흐름과 재생 검증은 갖춰졌으나, 제품 인수는 미완료.** M1 E01~E12 재생 통과 및 Android/Core 시뮬레이션 경로는 실제 RC 지상 세션을 대체하지 않는다.
- L1 인수 증거는 실제 장비 1회에서 한 흐름으로 확보해야 한다: **임무·번들 적용 → 실제 관측/영상 기록 → 오프라인 저장 → 앱 재시작 후 복원 → 링크 단절/복구 및 동일 메시지 재전송 → 연결 복구 후 RC 업로드 COMMITTED → Core 조회 + RC↔Core 대조(`scripts/rc_core_correlate.py`)**. 관측 공백·시계 출처·세그먼트 유효성도 함께 확인한다.
- 현재 다음 단계: **A9-4 프로펠러 분리 지상 세션**. 2026-10-13 preflight는 실행 환경 준비만 확인했고, 2026-10-14 재확인에서도 장치/키/Manifold가 없어 세션 단계는 미수행이다. 세션 런북의 증거(장치 사실·화면·로그·rc-store)를 확보하고 실패를 그대로 기록한다.
- A9-4 이후에는 관찰된 실제 렌더·글자 크기·햇빛·장갑 터치 문제를 수정하고 같은 장비 흐름을 재시험한다. 이어서 사용자 인수 및 첫 시험 범위(현장·장비·GT·실패 정의)를 확정한다.
- L1 이후: **L2**는 독립 GT와 calibration/holdout으로 SHADOW 오차를 평가한다. **L3 ACTIVE**는 별도 규칙별 검증·승인·롤백 게이트가 통과하기 전에는 진행하지 않는다.

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

## 외부 입력 대기 (실기 검증을 막는 항목)

- RC Plus 2 USB/개발자 디버깅 연결 · DJI 개발자 앱 키 · Manifold SSH/DPK 설치·기동 승인 — A9-4 런북 §입력 요건
- (선택) 제한된 개발망 Core 주소·토큰 — 세션 중 서버 COMMITTED 확인에 필요
- 현장 파일럿 확정 시: 현장·날짜·장비/렌즈·독립 GT 확보 방식 및 관측/증거 보존 기준
- 운영 배포 전 별도 결정: 인증·조직 권한·Keystore/네트워크 보안·보존/백업 정책
