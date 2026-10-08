# KSI Safety Edge — 제품화 실행 계획 (workstream ledger)

- 작성: 2026-10-08 · 상태: **실행 중 — W5 Risk/Episode 개발 착수**
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
| 판단·검증 | **착수 — W5 Risk/Episode**(developer 진행 중) | 엔진+재생 GT 시험→독립 검증 | 없음 |
| 사용 흐름(RC) | 미착수 | Android 환경 확보(승인 시 설치) | Android SDK/adb·RC 경로 미확보(부재 확인) |
| 실제 인식(AI) | 미착수(코어·어댑터만) | 모델 산출물·대표 영상 확보 | 모델 파일 위치 미확인(제한 검색에서 미발견) |

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

## 6. Evidence Log

- 2026-10-08: 제품화 계획·줄기 문서화(본 파일·product-state·README). 무차단 조사: Android 도구(adb/sdkmanager/gradle) 부재, 안전 검출 모델 파일 미발견 → RC/AI 줄기 외부 입력 목록화. W5 착수(developer).
- (이전) B0~W4·C13: `2026-10-08-reuse-audit-and-porting.md`, `docs/verification/evidence/`.

## 7. 대기 중 결정 (비차단)

- 첫 현장시험 범위 확정(§4) — 사용자.
- 모델 산출물·대표 영상 위치 — 사용자.
- Android 개발 환경 설치 위치·승인, RC 접근 경로 — 사용자.
