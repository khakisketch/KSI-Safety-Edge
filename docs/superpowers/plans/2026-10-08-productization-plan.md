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
| 실제 인식(AI) | **R1~R5 완료(C28~C32)** — 공통 러너·Tracking 출처·Telemetry 정합·Spatial 관측·**RC Emulator E2E·동일 코드 3경로 입증·재현성/성능(-O2 tick ~35ms)** | 실장비 E2E·DPK 설치(기체 세션)·대표 영상 평가·정책 임계·calibration | 대표 영상(녹화) 없음(개발은 corpus로 가능); Android는 별도 줄기 |

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
- 2026-10-08: **W8 완료·검증(C19)** — `DfineDetector`(주입 런타임 fn: 전처리→추론→decode, fail-closed F1~F8) + full-chain 통합(detector→tracker→session→episode, fake inference·결정성). 호스트 13/13(default)·13/13(ASan)·장치 gcc 9.4 13/13(최종 리비전)·독립 runner `ses_ee427dd4affeNJIPb9Cms7IDx7`(F→test 매핑·box/IoU hand-check·범위 정확). F5는 도달 불가(문서화), F8은 Primary 고정 테스트 추가 후 재실행. **장치 인프라 발견: root `/` read-only**(재부팅 후, `/open_app`·`/tmp`는 rw) — DPK 설치 전 재부팅/fsck 필요(사용자 확인 항목). runner의 'backend rotation'은 경로 오타(`/openapp`) 혼동으로 판명.
- 2026-10-08: **D-FINE ONNX 자체 export(설치 0)** — 로컬 LLM venv(torch 2.13.0+cu130·transformers 5.16.1) + 임시 `--target` deps(onnx 1.23.2·onnxruntime 1.30.0·pillow, `/tmp/opencode/dfine-export/deps`)로 export. opset 17·dynamo=False·640×640 static·출력 [logits,pred_boxes]·**double→float 7곳(=SI 레지스트리 기록과 일치)**. opset17 sha256 `96b8e0f3…`, **opset16 sha256 `1cf17f7c…`**(툴체인 상이로 레지스트리 digest와 다름 — manifest에 기록). 정합성: torch↔ORT max|Δlogits| ≤1.8e-3·max|Δboxes| ≤2.5e-5; smoke: single/small_person → person 0.79~0.90, non_person_car → truck 0.92·person 낮음. 장치 `/open_app/ksi-dev/dfine-export-20261008/` 복사(sha256 재확인 일치).
- 2026-10-08: **TRT 1차 빌드 실패 → opset16 재export** — TensorRT 8.5.2가 opset-17 `LayerNormalization` 미지원(8.6+ 내장 op, 플러그인 없음) 실측 → exporter가 LayerNorm을 기본 op로 분해하는 **opset 16으로 재export**(정합성 동일 확인) 후 **engine 재빌드 진행 중**(trtexec fp16, Orin NX·TRT 8.5.2).
- 2026-10-08: **GitHub 배포(검토용·비공개)** — `github.com/khakisketch/ksi-safety-edge` (C20 `ab5e229` 시점, main 브랜치). 배포 전 시크릿 스캔 통과(실제 DJI 자격증명은 `.gitignore`로 미추적 — 템플릿 `.example`만 추적, 과거 커밋 이력에도 실제 값 없음), 저장소 4.3MB/233파일. 목적: 사용자 검토. 공개 전환·collaborator 추가는 요청 시.
- 2026-10-08: **TRT engine 빌드 성공(재빌드, op16·fp16)** — `dfine-s-coco.fp16.op16.engine`(25MB, sha256 `4cf3419b…`) 생성·로드·실행 **PASSED**. 출력 `logits 1×300×80`·`pred_boxes 1×300×4`(모델 계약 일치), trtexec random-input 기준 **~62.9 qps·평균 지연 16.15ms(GPU 15.85ms)**. 주의: random 입력 — 실제 정확도는 W9 런타임 어댑터에서 Python/ORT 참조와 비교 검증 예정. 경고: enqueue-bound(옵션: CUDA graph)·compute variance 3.3%.
- 2026-10-09: **사용자 리뷰(C22 검토) 수용 — W9 진행 지시** — W9 수용 항목: ①실제 이미지 C++ 추론 성공 ②Python/ORT 참조와 클래스·점수·bbox 비교 ③전처리·후처리 일관성 ④연속 추론 FPS·지연·메모리 ⑤Detection/Tracking 계약 연결 ⑥엔진 오류 시 안전 전이. **정확도 검증 없이 공간 파이프라인에 연결 금지**. 모델: D-FINE=사람/일반차량 기반으로 사용, 중장비·PPE 모델은 실데이터 후 비교·통합(사용자 권고 — 기존 기록과 정합). 62.9qps는 engine 단독 수치 — 전체 파이프라인 60fps 검증 아님(W9에서 측정).
- 2026-10-09: **사용자 W9 중간 리뷰 지침 수용** — ①FP32 교차검증은 **진단용**: 꼬리 차이가 FP32에서도 유지되면 정밀도 외 원인 조사, FP32에서 사라져도 FP16 오류 아님. 고신뢰 검출 일치+성능 충분 시 **FP16 기본 엔진 유지**. ②W9 후 **지연 분해 측정**(전처리/TRT 추론/후처리 단계별 ms — 비동기 실행 동기화 보정). ③Replay 통합에서 30FPS 고집 불필요 — 예: **15FPS 탐지 + 프레임 간 tracking**, 단 **미관측 시간 구간·탐지 지연 기록 필수**. ④다음 단계 핵심 기준: 모델 추가가 아니라 **영상 한 구간이 들어왔을 때 KSI Safety가 어떤 객체·공간 관계를 관측했는지 설명 가능한가**(→ Replay 통합).
- 2026-10-09: **사용자 Replay 통합 원칙 추가 수용** — **Detection/Tracking/Spatial 갱신 주기를 독립 관리**. 탐지 사이 위치는 **추적·예측 결과이지 새 측정이 아님** — 출처(실측/추적/예측) 구분 없이 공간거리 계산에 사용 금지(잘못된 확신 방지). 지표 분리 측정: ①Detection=추론 FPS·지연 ②Tracking=갱신 주기·ID 연속성·객체 유실률 ③Spatial=유효 거리·위치 계산 비율·추정 오차·갱신 지연 ④Observation=출처·신뢰도. 14~16FPS는 처리량이며 **영상 입력→RC 경고 전체 지연이 아님**. **W9 후 핵심 검증: 실제 영상 구간에서 사람·차량 추적 + 텔레메트리 품질에 따른 공간 판단 수행/보류의 정확성 — 성공 시 핵심 파이프라인 실질 연결로 판단.**
- 2026-10-09: **W9 완료·검증(C23)** — C++ TensorRT 런타임(`TensorRtRuntime`, fail-closed T1~T8, TRT OFF stub 경로) + `dfine_trt_smoke`(수용기준: score≥0.2 동일클래스 IoU≥0.5·|Δscore|≤0.05, top-1 필수, tail 정보 표시). 호스트 14/14×2(stub)·장치 gcc 9.4 14/14(TRT ON)·독립 runner `ses_ee382e2f6ffer4hqUUQAyN55FZ`: fp16 smoke 양 이미지 **PASS**(top-1 0.8980/0.9161 vs 참조 0.8984/0.9158, gating 9/9·2/2, tail 3/3·10/10, ~61ms·16.3qps·RSS ~1.4GB), 실패 전이 clean exit 1×2, 범위 정확. **FP32 진단(Primary)**: 꼬리 실패 라인들이 fp32에서 dscore ≤0.0013·IoU ≥0.997로 일치 → **fp16 수치 재순위가 원인 확정, FP16 기본 유지**(fp32 engine은 진단용). 미검증: 실영상·thermal soak(H1-6)·단계별 지연 분해(→ W9-FU).
- 2026-10-09: **W9-FU 완료·검증(C24)** — 단계별 지연 분해(`RunTimed` + `--breakdown`). 장치 fp16: **preprocess ~35.3ms(지배)** · enqueue+sync ~20.5ms(GPU) · h2d 0.66 · d2h 0.12 · decode 0.39 · total ~57ms(pipelined mean ~61ms). runner `ses_ee376ded1ffe2UUpVDwONjZkVD` 전 항목 PASS(+문서 2건 정정: 'sum exceeds'→방향 중립, enqueueV2→enqueueV3). **다음: W9-FU2 preprocess 최적화**(채널-외부 루프·인덱스 재계산 제거, identity fast path; 목표 대폭 감소) → Replay 통합.
- 2026-10-09: **W9-FU2 완료·검증(C25)** — `PreprocessRgb` 재구성(열·행 계수 1회·픽셀당 인덱스 1회·3채널 단일 패스·identity fast path) + 등가 시험(3×2→5×4·7×5→4×3·1×1→4×4 @1e-6·identity 5×4). 장치 fp16: **preprocess 35.3→3.6ms(≈10×)**, **Detect mean ~61→~27–28ms(~35–37qps)**; gating·SMOKE PASS 불변; runner `ses_ee36e1452ffelo9SoTnZ8EguL0` 전 항목 PASS. **미검증: 비640 입력 일반 경로 실측 성능**(등가만 검증) — Replay 통합에서 실영상 크기로 확인. **검토 필요(Replay 전)**: 다운스케일 보간 충실도 — PIL(RTDetrImageProcessor) 안티앨리어스 지원 스케일 vs 우리 2-tap bilinear 차이(비640 입력에서만 발생; 정량화·필요 시 C++ 개선).
- 2026-10-09: **다운스케일 보간 충실도 정량화(Primary, C26)** — PIL(RTDetrImageProcessor) vs 우리 2-tap: tensor max|d| 0.022(381×680 업스케일)·**0.124(800×538 다운스케일)**·0.042(615×800)·**0.170(1080p NEAREST 최악)**. **top-1 안정**(cls 동일, |Δscore|≤0.003, IoU ≥0.96)이지만 **mid/low-confidence 검출 이동**(gating miss 0/9·**1/2**·**7/39**·**11/32**). → **C++ 리샘플을 PIL 호환(다운스케일 안티앨리어스)으로 정렬 필요 — W10**. 분석 스크립트: `scripts/check_resize_fidelity.py`.
- 2026-10-09: **W10 완료·검증(C27)** — `PreprocessRgb` **PIL 호환 리샘플**(center=(d+0.5)*scale·support=max(1,scale)·triangle 가중·창 클립·정규화·**uint8 중간 양자화**(round-half-up) — 실제 PIL과 golden 4건 일치) + identity fast path; `preprocess_dump` tool; fidelity 스크립트 C++ 출력 기준 전환. 검증: 호스트 14/14×2·장치 gcc9.4 14/14·fidelity **tensor max|d| 0.170→0.0039(1 uint8)**·gating **9/9·2/2·37/39·32/32**·smoke PASS(preprocess 3.5ms·mean ~28ms)·독립 검증 `ses_ee1a30f54ffeIcBzu8MfZ8Olw4`(5과제 PASS + 자체 PIL/C++ sanity) + DecodeDfine 무변경 확인. 잔여: person.jpg 2/39 mid-confidence miss(IoU 0.95·dscore 0.107 — 1-unit 입력차 증폭, 수용 범위).
- 2026-10-09: **R1 완료·검증(C28)** — 공통 제품 처리 경로 `SessionRunner`(주기 게이트→검출→정합→세션; `TickRecord`에 검출 상태/등급/`TrackRecord` 출처 OBSERVED·MISSED·HELD·`since_observed_ns`·model id/digest) + `DetectionStatus` 증거 의미(NOT_RUN vs INFERENCE_FAILURE vs OK-빈검출) + `DfineDetectorPort`(실 D-FINE→DetectorPort, fail-closed) + Replay 소스(manifest v1·raw RGB·`PushDue` no-future·검증 등급은 registry만) + `replay_run` 툴 + **PSDK 앱 동일 러너 전환**(콜백 Push, 워커 ProcessFrame). 검증: 호스트 15/15×2(ASan)·장치 gcc9.4 15/15·**독립 runner `ses_ee08e97ddffeH51tqaZizIGPCU`**(6과제 PASS — fresh 빌드·코드 판독·장치·재현·앱 컴파일·범위). 실엔진 Replay 6프레임 전부 OK(top-1 person 0.898·truck 0.914, 프레임5 MISSED→프레임6 OBSERVED, tick mean ~74ms — 개발 측정). 관찰(비차단): fp16 저점수 꼬리 — 0.05 바닥에서 106검출, 정책 입력 `--score-threshold` 추가(0.2에서 9/39/2) — W8 fp16 꼬리 특성과 함께 R4/R5 임계 정책에서 다룸. 파생: `Tracker::Tracks()`/`SafetySession::LiveTracks()` 접근자.
- 2026-10-09: **R2 완료·검증(C29)** — Tracking 출처·주기 분리: `SessionRunner::ResetTracker()`(epoch +1·id 비재사용) + R2 테스트 5종(주기 스킵 간 id 연속성·**held box 전 좌표 불변(예측 아님)**·소실→재획득 id 유지·epoch 분리·held≠신규측정(detector 미호출 포함)·한 detection tick 혼합 출처 OBSERVED/MISSED) + 문서(**v0 예측 없음 명시** — 미래 모션 모델은 별도 PREDICTED 출처 필요; age/misses는 detection tick 기준). 검증: 호스트 15/15×2(ASan)·장치 gcc9.4 15/15·독립 runner `ses_ee08369b3ffePE2jIUshi7b9Ft`(4과제 PASS, 무의미 통과 없음). 커버리지 갭 2건: ①held 전좌표 비교 → Primary 보강·재실행 15/15(호스트·장치), ②lineage 비합치 직접 시험은 W5 세션 시험(`ResetTracker` 후 구 episode OBSERVATION_LOST·신 epoch 새 id)으로 커버 — 추론 근거로 기록.
- 2026-10-09: **R3 완료·검증(C30)** — Telemetry Replay 시간 의미: manifest `exposure <frame_id> <domain> <source_ns>`(**시뮬레이션 검증 촬영시각** — registry verify로만 검증, 미지 frame/중복은 후처리 거부) + `ReplayFrameSource` registry 게이트(데이터는 스스로 검증 불가) + `replay_run` registry 우선 구성 + `make_replay_frames.py` 합성 타이밍 모델(프레임 지연 30ms·텔레메트리 지연 5ms·`--synthetic-exposure`; 텔레메트리가 프레임보다 먼저 도착하는 현실 순서) + 테스트 5종(검증 브래킷 SOURCE_VERIFIED·도메인 불일치·미검증 도메인·지연/no-future(NO_BRACKET→도착 후 SOURCE_VERIFIED·갭 OUTSIDE_MAX_GAP)·중복/롤백/동일값 신규표본). 검증: 호스트 15/15×2(ASan)·장치 fresh staging 15/15·실엔진 재현 **전 6프레임 SOURCE_VERIFIED/OK**·컨트롤 2종(데이터 단독 승격 불가: NONE/RECEIVE)·독립 runner `ses_ee0750513ffe2PjvHRgG77QQ83`(Task1·2 PASS + 잔여 4항목 이어서 PASS).
- 2026-10-09: **R4 완료·검증(C31)** — Spatial Observation: `GroundEstimator`(검증 브래킷을 촬영시각에 SLERP/LERP 보간 → 나디르 closed-form 검증; 발점=bbox 하단중앙, footprint=하단변 지면투영; **GEO_ESTIMATE — METRIC_VERIFIED 주장 없음**; 미지 class/투영 거부는 사유와 함께 보류) + `SafetySession` 2단계(`UpdateTracks`/`Evaluate`, Tick 등가 — 기존 시험 불변) + `replay/policy_file`(spatial/zone/rule, **SHADOW 전용**) + 러너 `enable_spatial`·`spatial_status`(DISABLED/NOT_ELIGIBLE/ESTIMATED)·**fresh-track 한정 관측** + 툴 `--policy`·공간 NDJSON + 스크립트 `--synthetic-spatial`. 검증: 호스트 16/16×2(ASan)·장치 fresh staging 16/16·독립 runner `ses_ee0529624ffeyS5wYV0KhQoe3Z`(6과제 PASS — closed-form 수계산·세션 리팩터 등가·wiring·정책 엄격성·장치·재현: **7 tick ESTIMATED·118 관측·48 episode(prox 거리 2.57/8.05m 등)·DISABLED 컨트롤에서 검출 147 동일**).
- 2026-10-09: **R5 완료·검증(C32)** — End-to-End: (a) **RC Emulator E2E** — 장치 실녹화(`docs/verification/evidence/2026-10-09-r5/`, RelWithDebInfo·fp16·tick 해시 `ec2ccf07…`)를 `harness/.../replay_evidence.py`로 M1 메시지에 매핑 → 적용→저장→업로드→Core 조회(`test_r5_e2e.py`): **실에피소드 54**·`UNVERIFIED`/`WITHHELD` 정직 표기·좌표 날조 없음; 보류 녹화(6프레임)는 0건·WITHHELD. (b) **Replay/PSDK 동일 코드 입증** — `test_replay.cpp` §8 3경로(직접/PSDK BoundedFrameQueue/Replay 소스) `TickRecord` byte-identical + 정적 계약 `test_r5_same_code.py` + PSDK 앱 TRT ON 장치 빌드. (c) **재현성** — 동일 바이너리 3회 실행 tick 스트림 SHA256 동일(양 라운드). (d) **성능** — tick mean ~35ms/p95 ~36ms, 엔진 단계 h2d 1.1/enqueue 29.1/sync 0.8/d2h 0.1ms(`enqueue` blocking — W9-FU 주의). **검증 라운드가 발견·수정**: 미설정 빌드(-O0) vs Release 간 전처리 FP 코드젠 차이(경계 1 uint8·tick 2× 느림; 검출 집합·개수 동일) — 교차실험(r5v 바이너리×r1 프레임=r5v 해시)과 `preprocess_dump` 바이트 0.03% 차이로 확정 → `edge/CMakeLists.txt` **기본 빌드 타입(RelWithDebInfo) 고정** 후 녹화물 재생성. 회귀: 호스트 16/16(기본 빌드·ASan)·장치 fresh staging 16/16·Python 115. 독립 검증: 1차(정적·녹화물 분석) `ses_ee03ae593ffepjYapUHp3KYkww` + 최종(4/4 PASS) `ses_ee031c48bffe8QlG31AZqerIQD`.
- (이전) B0~W4·C13: `2026-10-08-reuse-audit-and-porting.md`, `docs/verification/evidence/`.

## 7. 대기 중 결정 (비차단)

- 첫 현장시험 범위 확정(§4) — 사용자.
- 모델: D-FINE-S(coco) dev/평가 후보 — **ONNX는 자체 export로 확보**(`96b8e0f3…`). 현장용 class map 최종 모델·대표 영상(녹화) — 사용자.
- Android: **JDK17+cmdline-tools 설치 승인 여부**·RC 접근 경로 — 사용자.

## 8. R1~R5 Replay 통합 (사용자 승인 2026-10-09, 새 goal)

- 원칙: **Replay 전용 제품 로직 금지** — PSDK와 Replay는 동일한 공통 제품 처리 코드(공통 Frame/Telemetry 입력 → 독립 주기 Detection/Tracking/Spatial → SHADOW Episode·증거)를 사용하고 입력 소스만 교체한다. R5에서 테스트로 입증한다.
- 보존: W10 완료 기준선(C27=`5cea6e4`)·기존 계약·검증 기록. W5~W10 재수행 금지, 승인 범위 임의 확장 금지. ACTIVE 비활성 유지. 기체 비행·운영 환경 변경·별도 승인 장비 작업은 기존 게이트.

| 단계 | 구현 | 통과 기준 | 상태 |
|---|---|---|---|
| R1 | 공통 Frame/Telemetry 입력 계약(`SessionRunner`) + Replay 프레임/텔레메트리 소스 + `DfineDetectorPort`(실 D-FINE) + PSDK 앱 동일 러너 사용 | 프레임 ID·수신시각·출처 보존; 정상 무검출 vs 추론 실패 vs 주기 생략 구분; 장치 실엔진 Replay 추론 | **완료·검증(C28)** |
| R2 | Tracking 출처(관측/미관측/유지) 기록·주기 분리 | ID 연속성·소실·재획득·epoch 분리; IoU 마지막 bbox 유지를 새 측정/움직임 추적으로 기록하지 않음 | **완료·검증(C29)** |
| R3 | Telemetry Replay(도착 순서·미래 미참조) | 누락·지연·시계 불일치·동일값 재수신 구분; 검증 등급 상향 금지 | **완료·검증(C30)** |
| R4 | Spatial Observation(발점/footprint 후보 → 기존 투영·거리·구역) | 조건 부족 시 Detection 유지·Spatial만 보류; 합성 GT는 평가에만 | **완료·검증(C31)** |
| R5 | End-to-End(사건·증거·재현성·성능) + Replay/PSDK 동일 코드 입증 | RC Emulator E2E vs 실장비 E2E 구분; 실장비 전용 항목은 미검증으로 명시 | **완료·검증(C32)** |

- 각 단계: 구현 → 테스트(호스트 14그룹+신규, ASan, 장치) → 독립 검증 → 커밋(C28~) → 원장 증거 기록.
