# 2026-10-20 — 확정 제품 정의 반영과 개발 배선 (갭 분석 S0~S3)

## 시작: 사용자 지적

사용자: “솔직히 이건 우리 프로젝트를 제대로 이해 모ㅓㅅ하고 그냥 디자인만 만든 느낌이야.”
이전 작업은 HTML 시안의 조작 연결에 집중했고, **프로젝트의 업무 구조**(관측·운용·증거)와 **실제 Android/Edge/Core 구현 상태**를 따로 대조하지 않았다.
사용자는 제품 정의를 재확인했다: **DJI Pilot 2 수준의 필수 비행·촬영·임무 운용 경험과 KSI 고유의 AI 안전 관측·위험 판단·현장 대응·증거 기록을 하나의 RC 앱에서 제공하는 통합 현장 안전 운용 시스템.**
프로덕트 정의·책임 경계·대응 범위·UX 원칙·개발 방향·이번 작업 요구사항이 그대로 확정 문구가 되었고, 제품 정의 재논의를 반복하지 말라고 명시했다.

## 이번 라운드의 결과

### S0 갭 분석과 문서 정합 (완료)

- `docs/plans/2026-10-20-product-gap-analysis.md` 신규: 재사용 자산 11종, 갭 8건(G1~G8), 충돌 문서 4건, 현장 완결 최소 기능 6단계,
  RC·Edge·Core 책임 경계, 실장비 위험 7건, MVP→인수 개발 순서 S0~S6과 완료 기준.
- `README.md`: “RC 조종(관측) — 영상과 기록 상태 중심의 보조 화면”, “비행 조종 기준은 DJI Pilot 2”를 현행 정의에 맞게 정정.
- `docs/product-state.md`: 확정 제품 정의를 제품 목표 절에 명시하고 갭 분석 문서를 참조.
- `AGENTS.md`: 정본 지도에 갭 분석 문서 행 추가.

### S1 비행·카메라 명령 배선 (구현, 실기 미검증)

- `rc-ports/.../AircraftCommand.kt` 신규 — 명령 포트(`AircraftCommandPort`), 결과 종류, 지원 확인 수준, 순수 게이트(`CommandGate`), 벤치 더블.
  - **요청과 확인을 분리**한다. `DELIVERED`는 “요청이 전달됨”이고 실행 완료가 아니다. 실행은 기체 상태로만 확인한다.
  - **AI 스택은 기체를 명령하지 않는다.** 비행 명령은 조종자 발동뿐이며, 게이트가 권한·링크·전이 상태를 막는다.
  - 카메라 명령은 AI 장애와 무관하게 유지된다(관측 불가가 조종 카메라를 막지 않음).
- `rc-msdk/.../MsdkAircraftCommand.kt` · `MsdkCommandGateway.kt` 신규 — 실제 MSDK 어댑터.
  - API 이름은 추측하지 않고 `dji-sdk-v5-aircraft-provided:5.18.0`을 `javap`으로 확인해 기록했다(이륙/착륙/RTH, 짐벌 회전, 연속 광학 줌, 촬영·녹화, 카메라 모드).
  - 게이트가 막은 명령은 SDK에 보내지 않는다. SDK 오류→`REJECTED`, 제품 없음→`UNAVAILABLE`, 판단 불가→`UNKNOWN`.
  - 지원 확인 수준은 전부 `UNVERIFIED`가 기본이며, 실기 증거가 생길 때 코드 변경 없이 갱신된다.
  - **실장비 게이트**: 키→SDK 홉은 호스트에서 검증 불가(provided 스텁 바이트코드 로드 불가), 실제 전달·거부·타이밍은 A9-4 대상.

### S2 통합 운용 화면 (구현, 실기 미검증)

- `rc-app` 관측 페이지에 **운용 데크**를 추가했다. 구조는 `비행 필수 정보 띠(최상단) → 영상 → 운용 데크(영상 아래, 가림 없음) → 기존 사실 스트립`.
  - 영상/지도 전환은 표시만 바꾸며, 지도는 소스가 없으면 "지도 미연결 · 영상 입력은 유지됩니다"로 보고한다(억지로 지도 그림을 만들지 않는다).
  - 비행 명령(이륙·착륙·RTH)과 카메라 조작(짐벌·줌·사진·기체 녹화) 48dp. **게이트가 막은 명령은 SDK에 보내지 않고 사유를 표시**한다.
  - AI 패널은 **이미 존재하는 데이터**(관측·사건 카드, 관측 품질, 영상 프레임 대응 검증 플래그)만 읽는다. 관측이 없으면 "관측 미수신 — 분석 결과 알 수 없음 · 사건 0건과 다릅니다 · 안전 확인 아님"으로 구분한다. 프레임 대응이 검증되지 않으면 실시간 박스를 표시하지 않는다. SHADOW/ACTIVE/객체 관측을 구분하고, 지도 관계 보류는 탐지 사실을 지우지 않는다.
  - 결과 문구는 요청에 대한 것이다: "요청 전달됨 · 실행 결과는 기체 상태로 확인". 전달/거부/미확인/차단/기체 없음이 각각 다른 문구를 갖는다.
  - 새 순수 모델 `AircraftCommandUi`·`AiObservationPanel`(호스트 테스트 가능)과 레이아웃 계약 테스트 6건으로 "비행 정보가 AI에 가려지지 않음"을 고정했다.
- **중요한 한계(G1b)**: RC에 제어권 보고 소스가 없어 `owner`는 정직하게 `UNKNOWN`이고, **이륙·착륙·RTH는 현재 항상 차단된다.** 카메라 조작만 기체 연결 시 가능하다. KSI 앱이 진짜 주 운용 앱이 되려면 DJI로부터의 제어권·조작 상태 확인 경로가 필요하다.

### S3 대응 이력 모델과 로컬 영속 (완료)

- `rc-ports/.../ObservationResponse.kt` 신규 — 조종자 대응(확인/보류/전달 요청)과 전달 상태
  (`LOCAL_ONLY/REQUESTED/SENT_CONFIRMED/RECEIVED_CONFIRMED/COMPLETED_CONFIRMED/UNKNOWN`)를 분리.
  - 증빙 없는 전송·수신·완료 주장은 자동으로 `UNKNOWN`으로 내려간다. "저장됨"을 "전송됨"으로 읽지 못하게 한다.
  - 대응 기록은 위험 해소를 뜻하지 않으며, SHADOW 후보 확인은 ACTIVE 승인·활성화를 뜻하지 않는다.
- `rc-core/.../OperatorActionLog.kt` 신규 — append-only 로컬 로그(`FileRcStore`와 같은 패턴). 응답 id 멱등 재기록은 무시,
  손상 줄은 복구하며 개수만 집계한다(위험 주장 없음).

### 계약 결정 요청 (갭 분석 §9)

- 대응 이력과 재분석 결과의 Core 전송 경로가 없다. `operator-annotation.v1`의 `annotation_type`은 3종 고정이라
  `확인/보류/전달 요청`과 전달 상태를 담을 수 없고, `review-finding.v1`에 대응하는 엔드포인트도 없다.
  계약은 불변이므로 임의로 만들지 않고 **선택지·권고(신규 스키마 B, 재분석 결과는 append-only)**를 문서에 기록했다.
  결정 전까지 대응 이력은 의도적으로 로컬 전용이며 화면도 “전달 요청만 저장됨 · 전송 완료 아님”으로만 말한다.

### S4 대응 이력의 실제 보존 경로 (구현, 계약 변경 0)

- `rc-core/OperatorActionArtifacts.kt` 신규 — 대응 이력 NDJSON을 **부트별 스냅샷 1개**로 만들어
  `flight-upload-manifest.v1`이 이미 허용하는 `source_kind=FLIGHT_LOG` 아티팩트로 올린다.
  아티팩트 id는 내용에서 파생(`uuid5`), sha256·size는 **실제 전송 바이트** 기준이라 대조 도구로 검증된다.
- 정직 규칙: **응답이 없으면 아티팩트를 아예 만들지 않는다**(빈 필수 아티팩트로 모든 비행이 PARTIAL이 되지 않게).
  응답이 있는데 바이트가 없으면 `COMPLETE`가 아니라 `PARTIAL`. 재업로드 시 멱등(청크 재전송 0, 인제스트 세션 1개).
- 계약(`contracts/`), Core 서버(`core/`), Edge, harness는 **한 줄도 건드리지 않았다.**
- 남은 것: ① Core에서 대응 이력을 **조회**하는 경로(갭 분석 §9-1 계약 결정 대기) ② 조종자가 실제로 기록을 남기는
  **작성 UI**(미구현 — 배선 지점만 표시) ③ 실장비 전송 검증.

## UI/UX 네이티브 구현 (round40 후속, 2026-10-20)

사용자 지시 “UI UX 중심으로 개발 개선을 해줘 실제 구조에 맞게”에 따라 승인된 화면 계약을 **실제 Android 앱**에 반영했다.

- **운용 페이지 구조**: `pageObserve` = `telemetryStrip`(전체 폭) → `cockpitPriorityRow`(전체 폭) → `observeWorkRow`(가로) → `cockpitRecordActionRow`.
  `observeWorkRow` = `observeLeftColumn`(weight 1: `videoFrame` → `cockpitStatusStrip` 6칸 전체 폭) + `opsDeckScroll`(weight 0.35: 영상/지도 전환 → 기체 명령 → 카메라 → AI 패널 → 명령 결과). 가림 없음·48dp.
  기존 view id 160개 전부 resolve(중복 0), 동작(보이는 view·버튼 호출·상태 스트립 렌더) 불변. 색상·문구는 `colors.xml`·`strings.xml`만 사용.
- **AI 후보 선택**: `aiCandidateList` 탭 → `ShadowCandidateSelection.select` 연결(선택 행만 초록, 상세는 종류·품질·증거·수신시각·박스보류·정책). **제어·정책·세션·기록 불변**(AT-02).
- **대응 입력**: AI 영역 inline `responsePanel` = 후보 요약 + 확인/보류/전달요청(48dp) + 메모(≤280자) + 결과 문구 + 불변 고지.
  기록은 `RcSession.operatorActions`(= 업로더가 `FLIGHT_LOG`으로 ship하는 동일 로그)에 활성 세션 id·관측 근거(출처·수신시각·정책)로 1건 append. 전달 문구는 `operatorClaim` verbatim(“전달 요청만 저장됨 · 전송 완료 아님” 등), 증빙 없으면 상위 주장 금지.
  거부(기록 없음): 세션·현장·후보·수신시각·부트 부재 시 사유 표시 후 종료, **무기록**.
- 검증: `android_host_tests.sh` **500 tests / 0 failures**(기준선 472 → +28) · lint 0 errors · 3개 flavor 컴파일 green.
  기존 assertion은 유지했고, 구조 변경으로 정합적으로 달라진 레이아웃 가정 1건만 새 계약에 맞게 갱신했다.
  **미검증: 실기 렌더(에뮬레이터·장치 없음)** — 레이아웃은 계약 테스트(XML 파싱)로만 검증.

## 검증

- Python `scripts/run_all_tests.sh`: 134 passed (변경 없음 기준선 → 변경 후에도 134 passed).
- Android `scripts/android_host_tests.sh`: **472 tests, 0 failures, 12 skipped**(aarch64 Robolectric 스킵).
  변경 전 기준선 326 → S1~S4 반영 후 472.
  - rc-msdk 104(신규 19 = MSDK 매핑·결과 분류·게이트 순서) · rc-ports 76(신규 11 = 게이트 규칙)
  - rc-core 79(신규 12 = 로컬 로그 6 + 업로드 경로 6) · rc-app 213(신규 24 = 명령 UI 8·AI 패널 10·레이아웃 계약 6).
  - 기존 계약 테스트(`MissionReadinessLayoutTest`·`CockpitSourceFactsContractTest`·`TelemetryStripPresenterTest`·`ObservationModelTest`·`CockpitStatusPresenterTest`·`DemoBundleShapeTest`)는 약화 없이 통과.
- 상태: **구현·호스트 검증 완료. 실장비 검증·사용자 인수·보안 게이트는 미완료.** 호스트 테스트 통과를 실장비 통과로 취급하지 않는다.

## 다음

- S4 조회 경로(대응 이력)는 계약 결정(갭 분석 §9-1, 권고 `operator-response.v1` 신설) 후 착수. **보존 경로는 이미 동작한다.**
- S4-2 대응 이력 **작성 UI**(조종자가 후보에 대해 확인/보류/전달 요청을 남기는 조작): 현재는 배선 지점만 있고 조작 UI가 없다. AI 후보 목록과 함께 붙이는 것이 다음 UI 작업.
- S7 제어권 확인 경로: DJI로부터의 제어권·조작 상태를 RC가 확인할 수 있어야 KSI 앱이 비행 명령을 보낼 수 있다(A9-4 실기 세션에서 확인할 항목).
- S5 짧은 실장비 경로(실영상 입력 → Edge 탐지·품질 → RC 표시·영속 → 단절·재시작·복원 → Core 업로드·대조)는 A9-4 외부 입력 대기.
- S6 ACTIVE 경고 활성화는 규칙별 실측 검증 통과 전 금지.
