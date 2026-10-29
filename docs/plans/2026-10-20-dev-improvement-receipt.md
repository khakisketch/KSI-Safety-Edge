# RECEIPT — 2026-10-20 확정 제품 정의 반영 + 갭 분석 + 명령 배선 + 통합 운용 화면 + 대응 이력

## 범위

사용자가 확정한 제품 정의(DJI Pilot 2 수준의 필수 비행·촬영·임무 운용 경험 + KSI 고유 AI 안전
관측·위험 판단·현장 대응·증거 기록을 하나의 RC 앱에서 제공하는 통합 현장 안전 운용 시스템)를 기준으로
기존 코드·계약을 검토하고, 실제 구현으로 연결한 1차 결과다.

- 정본 갭 분석: `docs/plans/2026-10-20-product-gap-analysis.md`
+- 실행 원장: `docs/plans/2026-10-08-productization-plan.md` 2026-10-20 항목
+- 라운드 기록: `docs/plans/2026-10-20-productization-plan-entry.md`

## 변경 파일

문서: `AGENTS.md`(정본 지도) · `README.md`(‘보조 화면/Pilot 2 기준’ 표현 정정) ·
`docs/product-state.md`(확정 제품 정의 기입) · `docs/plans/2026-10-20-product-gap-analysis.md`(신규) ·
`docs/plans/2026-10-20-productization-plan-entry.md`(신규) · `docs/plans/2026-10-08-productization-plan.md`(원장).

Android:
- 신규 `rc-ports/.../AircraftCommand.kt` — 명령 포트·결과 종류·지원 확인 수준·순수 `CommandGate`·벤치 더블
- 신규 `rc-ports/.../ObservationResponse.kt` — 조종자 대응(확인/보류/전달 요청) + 전달 상태(요청≠전송≠수신≠완료)
- 신규 `rc-ports/src/test/.../AircraftCommandGateTest.kt`(11) · `ObservationResponseTest.kt`(4)
- 신규 `rc-msdk/.../MsdkAircraftCommand.kt` · `MsdkCommandGateway.kt` — 실제 MSDK 어댑터(게이트 → 준비 → 제품 → 단일 SDK 호출)
- 신규 `rc-msdk/src/test/.../MsdkAircraftCommandTest.kt`(19)
- 신규 `rc-core/.../OperatorActionLog.kt` — append-only 로컬 로그(멱등·손상 줄 복구·위험 주장 없음) + 테스트(6)
- 신규 `rc-core/.../OperatorActionArtifacts.kt` — 대응 이력을 `FLIGHT_LOG` 아티팩트로 만들어 비행과 함께 업로드
  (기존 허용 `source_kind` — **계약 변경 0**, `contracts/`·Core 서버·Edge 미변경) + 테스트(6)
- 신규 `rc-app/.../AircraftCommandUi.kt` · `AiObservationPanel.kt` — 명령 결과 문구·AI 관측 상태(순수 Kotlin)
- 신규 `rc-app/src/device/.../StubAircraftCommand.kt` — 실기 어댑터 미연결 시 `UNAVAILABLE` 정직 보고
- 수정 `rc-app/.../MainActivity.kt` · `RcApp.kt` · `RcSession.kt` · `activity_main.xml` · `strings.xml` · bench/device `PlatformLinks.kt`
- 수정 `rc-core/.../UploadBuilders.kt` · `EvidenceUploader.kt` — `FLIGHT_LOG` 아티팩트 담기(최신 스냅샷 1개)
- 신규 `rc-app/src/test/.../AircraftCommandUiTest.kt`(8) · `AiObservationPanelTest.kt`(10) · `IntegratedOpsLayoutTest.kt`(6)

## 실행 증거

- Python `bash scripts/run_all_tests.sh` → **134 passed** (변경 전과 동일).
- Android `bash scripts/android_host_tests.sh` → **BUILD SUCCESSFUL, 472 tests / 0 failures / 0 errors / 12 skipped**
  (aarch64 Robolectric 스킵). 모듈별: rc-msdk 104 · rc-ports 76 · rc-core 79 · rc-app 213.
  변경 전 기준선 326 → +146.
- 기존 계약 테스트(`MissionReadinessLayoutTest` 3 · `CockpitSourceFactsContractTest` 7 · `TelemetryStripPresenterTest` 6 ·
  `ObservationModelTest` 7 · `CockpitStatusPresenterTest` 13)는 삭제·약화 없이 통과.
- MSDK API 이름은 `dji-sdk-v5-aircraft-provided:5.18.0`을 `javap`으로 확인해 기록했으며 추측하지 않았다.

## UI/UX 네이티브 반영 (2026-10-20 후속)

- `rc-app` 관측 페이지를 승인된 화면 계약대로 재배치: `telemetryStrip`(전체 폭) → `cockpitPriorityRow`(전체 폭) → `observeWorkRow`(가로: 영상 좌측 + 운용 데크 우측) → `cockpitRecordActionRow`. 6칸 상태 스트립은 영상 아래 왼쪽 컬럼 전체 폭.
- AI 후보 선택(`ShadowCandidateSelection`) 연결 — 선택은 표시 전용(AT-02).
- 대응 입력 UI(`responsePanel`): 확인·보류·전달요청 + ≤280자 메모 → `RcSession.operatorActions` 1건 append(활성 세션 id·관측 근거 포함), 전달 문구는 `operatorClaim` verbatim, 증빙 없는 상위 주장 금지, 세션 없으면 거부·무기록.
- 검증: Android host **500 tests 0 failures**(472→+28), lint 0 errors, 3개 flavor 컴파일. 기존 assertion 유지, 구조 변경으로 달라진 레이아웃 가정 1건만 계약에 맞게 갱신.
- **미검증: 실기 렌더**(에뮬레이터·장치 없음) — 레이아웃은 계약 테스트로만 검증.

## 상태 구분(반드시 유지)

- **구현·호스트 검증 완료**: 명령 포트·게이트·MSDK 매핑·통제 UI·AI 패널·대응 이력 모델·로컬 로그.
- **미검증(실장비 게이트)**: MSDK 키→SDK 홉(호스트 로드 불가), 실제 명령 전달·거부·타이밍, 짐벌/줌 파라미터,
  영상 프레임 대응, 제어권 확인 경로, Android 실기 렌더·햇빛·장갑, 실영상 탐지·공간 오차, 재시작 복원, Core 대조.
- **미완료**: 사용자 인수, ACTIVE 경고 활성화(규칙별 실측 게이트), 운영 보안 게이트 G2·G3·G5·G7, 재분석 결과 경로,
  Core 측 대응 이력 **조회** 경로(보존은 완료), 대응 이력 **작성 UI**, 제어권 확인 경로(S7).

## 정직 규칙 준수

- 명령 결과는 **요청**에 대한 것이다. `DELIVERED`는 "요청 전달됨 · 실행 결과는 기체 상태로 확인"이며
  실행 완료를 뜻하지 않는다.
- **AI는 기체를 명령하지 않는다.** 비행 명령은 게이트를 통과한 조종자 요청만, 그리고 현재는 제어권 확인
  경로가 없어 이륙·착륙·RTH는 항상 차단된다(카메라 조작만 기체 연결 시 가능).
- 관측 미수신·사건 0건·판정 보류는 서로 다른 상태로 표시하며, 어느 것도 안전 확인으로 읽히지 않는다.
- 대응 이력의 전달 상태는 증빙이 없으면 `UNKNOWN`으로 내려간다. "저장됨"을 "전송됨"으로 말하지 않는다.
- 이벤트 주장은 **시연·단위 테스트 범위**이며 실장비·실기 인수·전체 제품 완료를 주장하지 않는다.
