# KSI Safety Edge — 제품화 실행 계획 (workstream ledger)

- 작성: 2026-10-08 · 상태: **실행 중 — W5 완료·검증(C15). AI/RC 줄기 준비 조사 완료**
- 사용자 지시(2026-10-08): "그렇다면 문서 등 반영하고 장기적으로 개발해줘 제품을 완성하기 위해" — 6단계 경로·인수 사다리·개발 줄기를 문서로 반영하고 장기 개발을 진행.
- 상위 기준: `docs/superpowers/specs/2026-10-08-integrated-design.md`(설계 기준본) · `docs/superpowers/plans/2026-10-08-reuse-audit-and-porting.md` §0(불변 원칙) · `docs/superpowers/product-state.md`(마일스톤).
- 본 원장은 제품화 ①~⑥ 진행·증거·게이트를 기록한다. SI 재사용 판정·B0~W4 이력은 기존 원장에 보존.

## 1. 인수 사다리 (달성 순서)

| 단계 | 의미 | 판정 증거(계획) |
|---|---|---|
| **L1 관측 제품** | 실제 탐지·오프라인 증거 저장·복구·서버 조회 | 실제 장비 1세션: 탐지→RC 영속→업로드 commit→조회 + 관측 공백 정직 표시 |
| **L2 현장 SHADOW** | 거리·구역·위험 평가를 정답과 비교 | V-GEOMETRY·V-POLICY 데이터와 미검증 조건 목록 |
| **L3 제한 ACTIVE** | 검증된 조건·규칙에서만 경고 | 규칙별 승인·격하·롤백 증거(H1·holdout) |

- 첫 인수 목표는 **L1**(2026-10-08 사용자 방향 수용). L2는 병행 개발, L3는 실측 게이트 유지.

## 2. 6단계 개발 경로와 완료 기준

| 단계 | 내용 | 완료 기준 | 선행 조건 |
|---|---|---|---|
| ① | 첫 현장시험 범위 고정 | 범위 문서(현장·구역·장비·렌즈·SHADOW) + 실패 정의 확정 | 사용자 확인 |
| ② | RC 사용 흐름(Android RC 앱) | 재생 입력으로 현장→임무→적용→관측→증거→요약→업로드→조회 시연 + 재시작 복구 | Android SDK/adb·RC 접근 |
| ③ | 실제 AI 파이프라인 | 실제 모델 결과+추론 프레임 원본 증거 확인(기체 불요) | 모델 산출물·대표 영상 |
| ④ | 실제 장비 연결 | 지상 연결(설치·기동·영상/텔레메트리)→MOP 실통신→오프라인→업로드→조회 실기 E2E | 기체/RC 연결 세션 승인 |
| ⑤ | 공간·위험 검증 | 규칙별 ACTIVE 가능/실패 조건 측정(holdout) | 실제 표본·GT·L2 |
| ⑥ | 운영 패키지 | 운영자 반복 사용(개발자 개입 0)·백업/복원·버전/롤백 | ①~⑤ |

- 유지 게이트: 기체 연결(설치·기동)·ACTIVE 규칙 승인·외부 배포. 문서화·무차단 코드 개발은 게이트가 아님.

## 3. 3개 줄기 상태 (2026-10-08)

| 줄기 | 상태 | 다음 | 블로커 |
|---|---|---|---|
| 판단·검증 | **W5 완료·검증(C15)** — 순수 로직·합성 GT | 실데이터 연결은 기체/GPS GT 확보 후 | 없음(실데이터만 대기) |
| 사용 흐름(RC) | 미착수 — 환경 조사 완료(2026-10-08): JDK 8(부족)·adb/sdkmanager/gradle 부재, dl.google.com 접근 가능, 디스크 2.9T 여유 | JDK17+cmdline-tools 설치 승인 시 착수 | 설치 승인·RC 접근 경로 |
| 실제 인식(AI) | 조사 완료 + W6-A(C16)·W6-B(C17)·**W7 session 체인 완료·검증(C18)** — 전처리/decode·tracker·합성 세션(호스트 12/12×2·장치 12/12·runner 검증) | **W8 detector adapter(런타임 주입) 착수** → ONNX 확보/재export→장치 engine→smoke | ONNX 산출물(sha256 `1becd2d3…`) 위치 미확인·export 툴체인 미설치; 대표 영상 없음 |

## 4. 첫 현장시험 범위 (추천안 — 사용자 확정 대기)

- 범위: 현장 1곳, 측정 가능한 평탄 구역, 고정 렌즈·줌, 사람 + 지원 장비 클래스, 기체 4E/4T 1대.
- 흐름: 인터넷 없이 관측·기록 → 착륙/종료 → 인터넷 복구 → 업로드 → 조회. 위험 판단은 SHADOW(기록만).
- 실패 정의: 관측 공백을 0건/안전으로 표시 · 미업로드를 완료로 표시 · 조건 부족 프레임에 거리/판정 강행.
- 확정 필요: 현장·일자·장비 조합·독립 GT 확보 방식(보조 관측).

## 5. W5 — Risk/Episode 엔진 (범위·Primary 결정)

- 범위: `edge/` C++17 순수 로직 — `risk-policy.v1` 필드와 계약 정합, 규칙별 mode(OFF/SHADOW/ACTIVE)·geometry level 게이트, 거리 interval 판정, `17 §3` episode 상태기계, 이벤트(CREATE 1회/UPDATE/CLOSE+사유), 미승인 ACTIVE 0. 영속·MOP·전달은 후속.
- Primary 결정(2026-10-08):
  - 거리 입력은 interval{lower,upper} — "completely below / beyond" 문구를 불확실성과 함께 그대로 표현.
  - geometry level은 호출자 제공. METRIC_VERIFIED는 물리 실측 전 어떤 경로도 주장 불가(기본 SHADOW).
  - close 사유: EXIT_CONFIRMED / EXIT_NOT_OBSERVED(갭 후 FAR — EXIT_CONFIRMED 아님) / OBSERVATION_LOST(max gap 초과) / SESSION_END.
  - lineage=(tracker_epoch, person_track, equipment_track) — tracker 재할당은 새 lineage, 합치지 않음.
  - 임계·시간 값은 정책 입력만(하드코딩 금지).
- 시험(관찰 가능): 계약 enum 문자열 정합 · 1회 생성·중복 없음 · hysteresis/enter/exit 지속 · 경계불확실 NEAR 금지 · 관측 유실=OBSERVATION_LOST(자동 안전 종료 금지) · epoch 비합치 · geometry 보류 사유 · ACTIVE guard 양방향 · OFF 무평가 · 결정성.
- **구현·검증 완료(C15)**: 호스트 9/9(default·ASan sanitizer-clean)·장치 gcc9.4 9/9·독립 test-runner `ses_ee45f8e2cffesWYHkfGMe2UgqU`(불변식 3종·범위 정확·하드코딩 없음). 후속(비차단): EpisodeState/EventKind ToString 직접 단언, 열린 episode 중 withheld 유지 전용 시험.

## 6. Evidence Log

- 2026-10-08: 제품화 계획·줄기 문서화(본 파일·product-state·README). 무차단 조사: Android 도구(adb/sdkmanager/gradle) 부재, 안전 검출 모델 파일 미발견 → RC/AI 줄기 외부 입력 목록화. W5 착수(developer).
- 2026-10-08: **W5 완료·검증(C15)** — Risk/Episode 엔진(순수 로직). 호스트 9/9(default)·9/9(ASan, sanitizer-clean)·장치 gcc 9.4 9/9·독립 runner `ses_ee45f8e2cffesWYHkfGMe2UgqU`: 불변식 3종(EXIT_CONFIRMED 유일 경로·갭/불확실 종료 정직성·비-shadow 출력 guard) 성립, 하드코딩 임계 없음, git 범위 정확. 후속(비차단) 2건 기록.
- 2026-10-08: **AI/RC 줄기 준비 조사** — ① 장치: TensorRT 8.5.2+CUDA 11.4 개발 패키지·헤더·`trtexec`(/usr/src/tensorrt/bin) 존재, 저장공간 94G 여유. ② SI 등록 모델 `ksi-person-detector-dfine-s-coco`(Apache-2.0, production=BLOCKED_LEGAL_REVIEW) 로컬 가중치 sha256 `a144baff…`=MODEL_REGISTRY 일치; export 레시피(opset 17·float64→float32)·ONNX sha256 `1becd2d3…` 기록됨 — **ONNX 산출물 위치 미확인**(재export 필요 가능성; torch/transformers 미설치). ③ 대표 영상 없음. ④ RC: JDK 8·Android 도구 부재, dl.google.com 접근 가능, ffmpeg/ffprobe 존재. ⑤ 부수: `KSI-CCTV/models`에 yolo26 계열 onnx(ppe/fire) 존재 — 별도 프로젝트, 참고만.
- 2026-10-08: **W6-A 착수(developer)** — D-FINE 입력 전처리(640×640·/255·무정규화) + 출력 decode(sigmoid·argmax·threshold·cxcywh→xyxy·NMS-free) C++ 모듈. ONNX/TensorRT 경로 공용, host 시험. SI Python semantics(`archive .../dfine.py`) 기준, provenance 기록.
- 2026-10-08: **W6-A 완료·검증(C16)** — 호스트 10/10(default)·10/10(ASan sanitizer-clean)·장치 gcc 9.4 10/10·독립 runner `ses_ee4497b70ffeYmAj45ifZLvcb0`(R1~R8 매핑·전처리/decode hand-check 일치·범위 정확). runner 갭(동점→최저 index 미고정) → Primary 테스트 보강·재실행(10/10×2). 부수: shadow C++ `dfine.cpp`는 global top-k·`<=`로 감사된 Python semantics와 상이 — 우리는 Python 기준 채택.
- 2026-10-08: **W6-B 완료·검증(C17)** — 결정적 IoU tracker v0(per-class greedy·coast/re-assoc 동일 id·id 비재사용·epoch reset·min_hits·결정성). 호스트 11/11(default)·11/11(ASan)·장치 gcc 9.4 11/11·독립 runner `ses_ee439a42affe33Ztam6BoOFbkU`(A1~A10 PASS; g1~g3 shipped-test 갭은 독립 probe로 확인). Primary가 g1~g3 고정 테스트 보강·재실행(11/11×2).
- 2026-10-08: **W7 완료·검증(C18)** — 합성 안전 세션 오케스트레이터(two-engine proximity/zone, pair lineage=(epoch,person,equipment)·zone lineage=(epoch,track,0), min-geometry, Sweep/EndSession/ResetTracker). 호스트 12/12(default)·12/12(ASan)·장치 gcc 9.4 12/12·독립 runner `ses_ee43101b2ffeJ5FDnSsRXYAzQn`(6 시나리오 매핑·거리 hand-check 0.5m/14.14m 일치·범위 정확) — **갭 없음**.
- (이전) B0~W4·C13: `2026-10-08-reuse-audit-and-porting.md`, `docs/verification/evidence/`.

## 7. 대기 중 결정 (비차단)

- 첫 현장시험 범위 확정(§4) — 사용자.
- 모델: D-FINE-S(coco)는 dev/평가 후보 확인(로컬 가중치 sha256=레지스트리). 현장용 class map 최종 모델·**ONNX 산출물 위치**·대표 영상(녹화) — 사용자.
- Android: **JDK17+cmdline-tools 설치 승인 여부**·RC 접근 경로 — 사용자.
