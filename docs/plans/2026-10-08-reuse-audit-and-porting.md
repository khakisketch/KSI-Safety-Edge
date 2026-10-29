# KSI Safety Edge — SI 자산 재사용 감사·이식 원장 (workstream ledger)

- 작성: 2026-10-08 (Build 전환 후) · 상태: **B0·W2·W3·W4 완료** — PSDK 앱 DPK 장치 빌드 완료, 다음은 DPK 기동 세션(기체/RC)
- 문서 성격: **완료된 과거 원장(이력)**. 본문의 "다음" 서술은 당시 시점이다. **§0 불변 원칙만
  현행 참조**로 유지되며(제품화 원장이 상위 기준으로 인용), 현재 진행·상태는
  `docs/plans/2026-10-08-productization-plan.md`와 `docs/product-state.md`를 따른다.
- 사용자 지시(승인 범위): ① H1 전 SI 자산 정밀 감사 ② 재사용/독립 경계와 중복 최소화 계획 ③ 격리 환경에서 실제 빌드·실행 ④ 재사용 모듈 확정 후 Safety Edge 최소 실행 파이프라인 연결 ⑤ SI 운영 코드·제품 계약 변경 금지 ⑥ H1 없이 기체 통합 완료 주장 금지.
- 감사 정적 보고서(plan 위치): `~/.opencode/plan/2026-10-08-ksi-safety-edge-reuse-audit.md`
- 확정 개발 순서(2026-10-08 사용자 보정):
  1. 기존 코드 격리 빌드·테스트 → **완료(B0=5662f2f)**
  2. 영상·텔레메트리 수집 계층 분리(PSDK와 Replay가 공통 계약) → **W2 완료(C8)**
  3. 영상 AI 독립 실행(자세 정합 실패와 무관하게 Detection 유지) → **W2 완료(C8)**
  4. 시간 정합 품질 엔진(표본 출처·시각·오차·거절 사유) → **W2 완료(C8)**
  5. Spatial Engine(사람 발점·장비 footprint·최단거리, Replay GT) → **W3 완료(C11)**
  6. Risk Engine(위험구역·근접·Episode Lifecycle, 독립 모듈) → **W5 완료(C15)**
  7. MSDK 연동(임무·MOP·오프라인 저장·증거 확인) → **사용자 방향 보정(2026-10-08): MSDK 중심 선행 검토**
  8. H1 실장비 검증 → **장비 LAN 접속됨(사용자 통보); 능력 파악 진행 중**

## 0. 불변 원칙 (사용자 지시 6항 + 시간 3단 분리)

1. Detection은 자세·지리좌표 정합 실패와 독립적으로 실행된다.
2. **Timestamp Provenance → Temporal Matching → Attitude Interpolation**은 서로 다른 검증 단계다. 설계·구현·테스트에서 이 순서를 지킨다.
3. 실제 촬영 시각(노출 시각)을 확보하지 못했다면 nearest matching/SLERP 결과를 "실제 촬영 순간 정합" 증거로 간주하지 않는다. 수신시각 기준 결과는 `RECEIVE_*`등급에 머문다.
4. Spatial Engine 거리 계산은 실제 사람 발점·장비 footprint 기준으로 신규 구현하고, 정답 있는 Replay 시험으로 검증한다.
5. 고정 위험구역 침입·장비 근접·Episode Lifecycle은 Safety Edge 전용 독립 모듈로 구현한다(4.2m fixture·SI risk/shadow 재사용 금지).
6. SI의 서버 직업로드·비행제어·Mission 계약·Flight 실경로를 가져오지 않는다.
7. 물리 촬영 시각을 확보하지 못하면 5~6단계는 시뮬레이션·SHADOW로 계속 개발하고 실제 위치 기반 경고는 활성화하지 않는다.
8. "코드 존재 / 호스트 테스트 통과 / 실장비 검증"을 별개로 기록한다. H1 없이 기체 통합 완료를 주장하지 않는다.

## 1. B0 — 격리 빌드·테스트 실측 (2026-10-08)

- 방법: SI `cedeb27…`에서 `git archive`로 선택 경로만 `/tmp/opencode/ksi-audit-20261008/source/`에 전개(자격증명·모델·raw 로그 미포함). 원본 repo는 build input/수정 대상으로 쓰지 않음.
- 환경: aarch64 Ubuntu 24.04(커널 `7.0.0-1019-nvidia`), gcc/g++ 13.3.0, cmake 3.28.3, python 3.12.13(SE `.venv`), pytest 9.1.1. 20 core.
- 전개 검증: 주요 5개 파일 SHA256이 감사 보고서와 **전부 일치**(shared_state.c, telemetry.cpp, calibrated_projector.cpp, capture_frame_telemetry_timing.py, test_capture_frame_telemetry_timing.py).

| # | 시험 | 명령(요지) | 결과 | 스킵/한계 |
|---|---|---|---|---|
| B0-1 | psdk-tap 순수 로직 | `cmake -S .../psdk-tap -B build/tap -DKSI_PSDK_TAP_LOGIC_TESTS_ONLY=ON -DKSI_BUILD_TESTS=ON && build && ctest` | **2/2 Passed** (pairing, ndjson_writer) | PSDK 실행파일·DPK는 자격증명/SDK 필요로 미구성(설계) |
| B0-2 | manifold-runtime (portable) | `cmake -S .../manifold-runtime -B build/runtime -DKSI_BUILD_TESTS=ON -DKSI_ENABLE_TENSORRT=OFF && build && ctest` | **10/10 Passed**; edge_runtime_tests **246 checks passed** | **9개 그룹 스킵** — SQLite 개발 헤더 부재로 WAL/store-forward/source-media 계약 미검증(하네스가 `[SKIP]`·`[WARN]` 출력). configure 메시지: "SQLite3 development package unavailable; WAL backend disabled" |
| B0-3 | fail-closed 확인 | `-DKSI_REQUIRE_SQLITE=ON` configure | **의도된 configure 실패** — "SQLite3 개발 헤더를 찾지 못했다" | 환경 한계이며 코드 결함 아님 |
| B0-4 | ASan/UBSan (portable) | `-DCMAKE_BUILD_TYPE=Debug -DKSI_ENABLE_SANITIZERS=ON` build + ctest | **10/10 Passed** | WAL 그룹은 동일하게 미검증 |
| B0-5 | 시간 분석기(Python) | `pytest --noconftest -p no:cacheprovider tests/hil/test_capture_frame_telemetry_timing.py` (격리 사본) | **124 passed** | 실행 환경에서는 통계·판정 로직만 검증(실기 표본 아님) |
| B0-6 | TensorRT/GPU | `-DKSI_ENABLE_TENSORRT=OFF`로 구성 | GPU 어댑터 **빌드 미실행(BLOCKED)** | 이 호스트에 `NvInfer.h` 부재(표준 경로). CUDA 헤더만 존재. 설치/다운로드는 승인 범위 밖 |

- 로그 보존: `docs/verification/evidence/2026-10-08-b0/` (요약본).
- 부작용 점검: SI repo는 우리 작업으로 변경 0. (동시에 다른 workstream이 `apps/dashboard` 하위 다수 파일을 수정/삭제 중 — 우리는 열람만, 기록만 남김.)
- 제약 준수: 의존성 설치·네트워크 fetch·컨테이너 실행 없음. SE/SI 소스 수정 없음.

## 2. 재사용 판정 v1 (B0 실측 반영)

| 모듈 | 상태 | 근거 | Safety Edge 적용 |
|---|---|---|---|
| `frame_queue` | **호스트 검증됨** | B0-2/4에서 계약 시험 통과 | 최소 diff로 채택(W2) — bounded ingress |
| `telemetry`(Timeline/Align) | **호스트 검증됨** | B0-2/4, sensor/receive 분리 테스트 포함 | 패턴·타입 채택; nearest 단일 matcher는 그대로 이식하지 않음 |
| `dfine` 후처리/sigmoid/top-k | **호스트 검증됨** | B0-2 (deterministic postprocess 테스트) | W3 이후 detector adapter 후보 |
| `tensorrt` 전처리(중심 bilinear, digest) | **호스트 검증됨(전처리 한정)** | B0-2 (byte-exact digest 테스트) | 후보; GPU 실행은 B0-6에 따라 BLOCKED |
| 좌표/회전 수학(`calibrated_projector` 계열) | **호스트 검증됨(수학 한정)** | B0-2 (회전 반사 거부·정합 테스트 포함) | W4에서 수학만 추출 후 재검증 |
| `artifact_vault`/`crypto` | **호스트 검증됨** | B0-2 (해시·원자 저장 계약) | W2 증거 저장 후보 |
| `capture_quality_policy`/`event_timing` | **호스트 검증됨(portable 한정)** | B0-2 | 후보(수치·기준은 SE 자체 계약으로) |
| `store_forward`(WAL) | **미검증(BLOCKED)** | B0-2 스킵 9그룹 | sqlite3-dev 확보(CI/장비) 후 재검증 전 채택 보류 |
| `psdk-app`/`psdk-tap`(SDK 실행 경로) | **빌드 미실행** | PSDK fetch·DJI 자격증명·장비 필요 | H1/후속. 순수 로직(pairing/ndjson)만 검증됨 |
| `risk`/`shadow`/`pipeline` 오케스트레이션·SI Mission/Core sync/waypoint | **재사용 안 함** | 감사 §5 | W5/W6에서 SE 독립 구현 |

## 3. W2 — 최소 실행 파이프라인 (수집 분리·AI 독립·시간 품질) — **완료(C8)**

**범위:** SE repo에 `edge/` C++17 프로젝트 신설(호스트 빌드·ctest 가능, 외부 의존 없음).
- capture: `frame_queue` 최소 diff 채택(출처 주석·원본 sha256 기록) — bounded ingress.
- time: ① `provenance`(시각 출처 등급) ② `matching`(동일 시간축 검증·최대 gap·수신 기준 등급) ③ `interpolation`(검증된 bracket에서만 SLERP 등) — 3단 분리, 각각 거절 사유.
- perception: `DetectorPort` 추상 + 파이프라인이 **정합 실패와 무관하게 프레임당 1회 추론**, 자세 등급을 별도 필드(`kNone|kReceiveNearest|kSourceVerified`)로 부착, `spatial_eligible`은 `kSourceVerified`에서만 true. 공간 계산은 미포함.
**필수 테스트(원칙 고정, 구현·통과):** telemetry 0이어도 Detection 1회 실행 / recv-only 승격 불가 / 400Hz 근접 + 지연 프레임 시 `capture_verified=false` / rollback·동일표본 재독 구분 / bracket 없으면 보간 거부 / SLERP 수학(반각·wrap) / **cross-domain 검증 시각은 bracket 불가(`kDomainMismatch`)**.
**금지 준수:** SI namespace 직수입 없음(추출 파일 1쌍 예외+출처 명시), flight/mission/core-sync 코드 0, 의존성 추가 0, WAL 의존 없음.

## 4. Evidence Log

- 2026-10-08: 정적 감사(산출 보고서)·독립 검증(ses_ee5420310ffe3KrAfzIdKh6LbP, ses_ee536573fffeVH1pLaV2gDgnAo) 완료. 사용자 순서 보정 반영.
- 2026-10-08: **B0 완료(C7=5662f2f)** — B0-1~B0-5 실측, 로그 `docs/verification/evidence/2026-10-08-b0/`. WAL 9그룹·GPU·PSDK 실행 경로는 BLOCKED로 명시.
- 2026-10-08: **W2 완료(C8)** — `edge/` 최소 코어 구현(developer) + Primary 수정(cross-domain bracket 금지 `kDomainMismatch`, 테스트 e4 추가). 호스트 검증: 기본/ASan-UBSan ctest **6/6 ×2**, 저장소 회귀 **112 passed**. 독립 test-runner `ses_ee521787affeS0RHz1LG0OEFux`: 빌드 6/6 ×2, 의미론 4건 인코딩 확인, 추출 diff·sha256 일치, 격리 점검, 승격 금지 경로 확인 — **mismatch 없음**. 실장비/PSDK/GPU/WAL은 여전히 미검증.
- 2026-10-08: Manifold 3 사내 LAN 접속·전원 ON(사용자 통보) → H1 접속·능력 파악 착수. 사용자 방향: **MSDK(가시) 중심 선행 + 이후 PSDK** 검토.
- 장치측 과거 관찰·접속 환경 상세는 공개 인계에서 생략하고 비공개 보존한다.
- 2026-10-08: **DPK 세션 패키지 준비** — `docs/verification/h1-dpk-session-checklist.md`. 기존 빌드 DPK(`ksi-psdk-tap` v00.01.00.02, 무인자 기본 out=/tmp/ksi-psdk-tap.ndjson·60초)로 H1-3/4/5 1차 raw 수집 가능. 실행은 사용자 승인(설치) + 기체/RC 연결 필요.
- 2026-10-08: **W3 Spatial Engine 착수**(백그라운드 developer): 좌표·투영·footprint 거리·zone 관계 + Replay GT, SHADOW 전용.
- 2026-10-08: **W3 Spatial Engine 완료(C11)** — 좌표(NED/ENU·국소 ENU)·투영(자세 등급 gate·near-horizon·교차)·footprint 거리(point-to-polygon)·zone 관계(내부/외부/BOUNDARY_UNCERTAIN/revision 고정). 호스트 8/8 ×2(기본/ASan), **장치 gcc 9.4/cmake 3.16.3 8/8**, 독립 test-runner `ses_ee4e01454ffeXNhwri8GSxT6Zi`(의미론 4건·격리·이식성 확인, mismatch 0). Primary 보강: 자세 gate 우선순위 테스트·polygon revision 단언·include 정리(재통과). 정확도·실기 검증은 미실시(합성 GT, SHADOW 전용).
- 2026-10-08: **W4 PSDK 앱 "KSI AI Payload"(ksi-safety-edge) 완료·장치 빌드(C12)** — 우리 코어(프레임큐·provenance·matching)를 실제 PSDK 콜백에 연결한 v0 앱. 장치(gcc 9.4/cmake 3.16.3)에서 바이너리(2.78MB) + **DPK 587KB** 생성. Manifold 적응 글루 8파일을 출처·sha와 함께 추출(NOTICE). **센서 시각 fail-closed**(has_source_time=false 고정, recv 등급만). 자격증명은 gitignore 경로에만 저장(커밋 0 확인 — `git grep` 시크릿 0건). 독립 test-runner `ses_ee49b57b9ffeURavMylHEovfzq`: 자격증명 위생·호스트 8/8·장치 신규 빌드·DPK·의미론·글루 충실성 전부 통과, mismatch 0. 발견·수정: 자격증명 템플릿 주석의 `*/` 조기 종료 버그(Primary 수정 후 장치 재빌드 성공).
- 2026-10-08: **DPK 설치 검증 요건 규명 + 수정(C13)** — 설치 검증은 앱을 잠깐 실행해 `DjiCore_SetFirmwareVersion` 보고를 control Version과 대조하는 구조(app_mgr 문자열 실측). 수정: `SetFirmwareVersion`/`SetAlias` 호출 + 부트 로그(`/tmp/ksi-safety-edge-boot.log`). 기체 미접속 3차 시도에서 부트 로그가 `core_init_begin`에서 멈춤 → **`DjiCore_Init`이 기체 링크를 대기하며, 설치 자체가 기체 연결 세션에서만 가능**함을 확인. 증거: `docs/verification/evidence/2026-10-08-dpk-install-verification.md`.
- (다음) 기체/RC 연결 세션에서 우리 DPK 설치·기동 → NDJSON 회수 → H1-3/4/5 1차.
- 이후 제품화 6단계 실행·진행은 `docs/plans/2026-10-08-productization-plan.md`(신규 원장)에서 기록한다. 본 원장은 SI 재사용 판정·B0~W4 이력을 보존한다.

## 5. 후속 결정 대기(제품을 막지 않음)

- **개발 순서(2026-10-08 논의 중)**: MSDK 가시성 우선 vs PSDK 증거 우선 — 사용자 결정 대기. 권고: PSDK 주도 + MSDK 병행(§ 사용자 보고 참조).
- DJI 자격증명(`dji_sdk_app_info.h`/user_app_id) 보유·발급 — PSDK 실행/DPK의 선행 조건.
- DPK 설치·기동 승인(propeller-off·기체/RC 연결 세션) — H1-3~6 게이트.
- 기체·RC를 Manifold/작업대에 연결할 세션 일정(현재 l4tbr0 NO-CARRIER).
- 장치 시계 정비(NTP/수동, RTC 1970) — H1-5 신뢰성에 직접 영향.
- MSDK 개발 환경 위치(이 DGX vs 별도 PC) + RC Plus 2 adb 연결 경로 — MSDK 트랙 착수 조건.
- sqlite3-dev 확보 경로(CI 컨테이너/장비 빌드 중 택일) — WAL 재검증에 필요.
- NvInfer/TensorRT 헤더·런타임 확보(장비 이미지) — GPU 어댑터 검증에 필요.
- 모델 후보(기존 Safety person/equipment 산출물 vs D-FINE) 자료 확보 — W3 이후.
