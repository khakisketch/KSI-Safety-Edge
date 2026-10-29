# KSI Safety Edge — 갭 분석과 실행 계획 (확정 제품 정의 기준)

- 작성: 2026-10-20 · 상태: **분석 + 실행 계획 확정. 구현은 이 문서의 순서대로 진행하며, 실장비 검증 전 완료를 주장하지 않는다.**
- 기준 제품 정의(사용자 확정, 2026-10-20): DJI Pilot 2 수준의 필수 비행·촬영·임무 운용 경험과 KSI 고유
  AI 안전 관측·위험 판단·현장 대응·증거 기록을 **하나의 RC 앱**에서 제공하는 통합 현장 안전 운용 시스템.
  현장 작업 흐름: 현장·임무 준비 → 비행·촬영 → AI 안전 관측 → 위험 확인·대응 → 귀환·착륙 → 기록 보존 →
  Core 동기화·사후 검토.
- 상위: `docs/product-state.md` · `docs/specs/2026-10-08-integrated-design.md` ·
  `docs/plans/2026-10-17-rc-operations-screen-contract.md`(현행) · `docs/plans/2026-10-16-field-zone-designation-contract.md`.
- 이 문서는 새 제품 개념·사실 원장을 만들지 않는다. 확정된 정체성을 **기존 코드·계약에 어떻게 구현·검증할지** 정한다.

---

## 1. 재사용 가능한 구현 자산 (그대로 쓰는 것)

| 자산 | 위치 | 현재 증거 | 목표 제품에서의 역할 |
|---|---|---|---|
| 순수 상태모델 12종 | `rc-ports/.../rc/*.kt` (ControlAuthority·WarningState·ObservationValidity·ShadowCandidateSelection·TakeoverRequests·SupervisedAutoMission·ObserveView·VideoSource·VideoRecorder·FlightTelemetry(+Accumulator)·VideoFreshnessModel·SegmentNames) | `rc-ports` 단위 테스트 통과 | 명령 게이트·관측 유효성·경고 상태의 **판단 규칙**을 그대로 유지 |
| MSDK 등록·키 게이트 | `rc-msdk/.../DjiSdkBridge.kt:31-150` | 컴파일·패키징, 실기 미검증 | 기체 연결 사실과 SDK 등록 상태의 정직한 표시 |
| MOP 파이프라인(Edge↔RC) | `rc-msdk/.../MsdkDroneLink.kt:41-140` | `MopFrameCodec`·`MopCrossLanguageTest` 통과 | Edge 관측·정책 결과의 전달 경로 |
| 영상 소스·프레시니스 | `rc-msdk/.../MsdkVideoSource.kt` · `rc-ports/.../VideoFreshnessModel.kt` | 호스트 테스트 | 관측 가능 여부 판단의 입력 |
| 읽기 전용 비행 텔레메트리 | `rc-msdk/.../MsdkFlightTelemetrySource.kt` · `rc-ports/.../FlightTelemetry.kt` | `TelemetryStripPresenterTest` 통과 | 비행 필수 정보 표시(조작 아님) |
| 영상·증거 세그먼트 기록 | `rc-msdk/.../SegmentRecorderCore.kt`·`MsdkVideoRecorder.kt`·`Mp4SegmentWriter.kt` | `SegmentRotationTest` 통과 | 현장 기록 영속 |
| 임무·번들 준비/적용 | `rc-core/.../MissionController.kt`·`MissionStore.kt` | `MissionControllerTest`·`MissionSyncE2ETest` 통과 | 임무 준비 완료 근거 |
| 오프라인 저장·업로드 | `rc-core/.../FileRcStore.kt`·`EvidenceUploader.kt`·`SyncCoordinator` | `LocalFirstEvidenceTest`·`CoreUploadE2ETest` 통과 | 오프라인 보존과 복구 후 동기화 |
| Edge 탐지·추적·공간·정책·에피소드 | `edge/src/perception/*`·`edge/src/spatial/*`·`edge/src/risk/*`·`edge/src/runtime/session_runner.cpp` | R1~R5 Replay 통과 | 관측·판단 근거 생성 |
| Core 무결성·증거 조회 | `core/src/ksi_core/routers/ingest.py`·`events.py` | `core/tests/test_ingest.py` | 증거 커밋·조회·사후 검토 |
| 장애 주입 E01~E12 | `harness/` | 재생 하네스 통과 | 오프라인·복구 경로의 근거 |

**판정: 재사용 자산은 충분하다. “새 기능을 많이 만드는 것”이 아니라 “현존 자산을 배선·완결”이 우선이며, 이 문서의 작업 목록도 그 원칙을 따른다.**

## 2. 목표 제품에 부족한 기능 (갭)

| # | 갭 | 근거 | 상태 |
|---|---|---|---|
| G1 | **실제 MSDK 비행·카메라 명령 배선 부재** | `rc-msdk`에 `takeoff/land/RTH/짐벌/줌/촬영/카메라선택` 호출 없음. `MsdkFlightTelemetrySource`는 `KeyManager` 구독만. `ControlAuthority`·`SupervisedAutoMission`·`TakeoverRequests`는 출력 채널 없는 순수 모델 | **구현됨(2026-10-20)**: `rc-ports/AircraftCommand.kt`(요청≠확인 분리·AI는 기체 미명령·순수 `CommandGate`) + `rc-msdk/MsdkAircraftCommand.kt`·`MsdkCommandGateway.kt`(MSDK 5.18 키를 `javap`으로 확인 후 매핑). **실기 전달·거부·타이밍은 A9-4 미검증** |
| G1b | **비행 명령을 보낼 수 있는 권한 확인 경로 부재** | RC에 제어권 보고 소스가 없어 `owner`는 정직하게 `UNKNOWN`이다. 그 결과 **이륙·착륙·RTH는 지금 항상 차단**된다(카메라 명령은 기체 연결 시 가능) | **미해결(실장비 필요)**: DJI로부터의 제어권·조작 상태 확인 경로가 있어야 KSI 앱이 비행 명령을 보낼 수 있다. 그전까지는 DJI 조종 체계가 비행 권한을 갖는다 |
| G2 | **통합 운용 화면 부재** | 기존 기능별 페이지에서 비행·AI·기록의 통합 조작이 부족했음 | 네이티브 배치·후보 대응·런타임/마감 표시 반영. 최신 배치는 **사용자 채택·실기 렌더 미검증**이며 HTML 시안을 승인된 네이티브 결과로 취급하지 않는다 |
| G3 | **AI 관측 상태의 네이티브 표시 부재** | 모델만 있고 후보·보류·입력 장애·실행 사실이 화면과 분리되어 있었음 | `EdgeRuntimePanel`·`AiObservationPanel`·실제 설치 영수증/엔진 입력 기반 표시 구현. 보고 부재·분석 0건·기하 보류·통신 장애를 구분. 실제 장비 입력·가독성은 미검증 |
| G4 | **대응 이력 모델·보존 부재** | 비행 제어 요청과 운영자 대응은 다른 의미 | `ObservationResponse`·`OperatorActionLog`·후보별 입력·FLIGHT_LOG 보존 및 구조화 전송 구현. 선택한 사건의 실제 수신 영수증·boot를 사용하고 없으면 기록을 거부한다. 저장 ACK는 현장 전달·수신·조치 완료가 아님 |
| G5 | **대응 이력 전송 계약** | 기존 사후 annotation으로는 비행 중 대응·전달 상태를 표현할 수 없었음 | 기존 schema 바이트를 보존하고 **신규 `operator-response.v1` + 별도 API 확장 계약** 추가. Core 배치·조회, RC 영수증/늦은 대응 자동 sync, 실제 Core E2E와 읽기 전용 RC↔Core 대조 구현. 미검증 조회는 INCOMPLETE |
| G6 | **실장비 관측 경로 미검증** | A9-4 게이트 대기(장치·앱키·Manifold 없음). `frameMappingVerified=false` 상시, 실영상 탐지·공간 오차·관측 공백 미측정 | 실기 대기 |
| G7 | **t1→t2 재사용·재분석 연결 미완성** | Core에 `observations:batch`·`events:batch`(자료 등록), `events/{id}/annotations`(외부 검토 결과 등록·기존 결과 연결, append-only)는 있다. 그러나 `review-finding.v1` 스키마에 대응하는 **Core 읽기/쓰기 경로가 없고**, `/review-jobs`는 P1 placeholder(기록만·실행 없음)다. "변경 영향 확인"은 `detection-event.episode_id`·`model_digest`에서 유추될 뿐 노출되지 않는다 | **결정 필요** (§9) |
| G8 | **운영 보안 게이트** | 운영 인증·신원·보존·알림·서명/롤백은 별도 운영 결정 | 릴리스 평문 차단·명시 버전·bench/device APK 스크럽과 CI 반영. 이 위생 검사 통과가 운영 서명·인증·보존 정책 게이트 통과를 뜻하지 않음 |
| G9 | **RC 요청과 실제 Edge 실행·복구 연결** | 프로토콜 bool 제어·단절 기록 누락·설치 전 출처 변경 등 통합 결함을 확인했음 | 명령 순서·실제 설치·영속 spool/audit·재접속·ACK-only 압축·쓰기 실패 격리·argvless MOP 서비스 기본값 반영, 호스트 수직 시험 확인. PSDK 3.16 aarch64 **컴파일/링크 확인**(플레이스홀더, TensorRT OFF). 실제 장비/실시간성·공간 입력·인수와는 별개 |
| G10 | **추가 Platform 자산 평가** | 전체 중앙 실행 환경과 이 Core가 같다는 근거는 없음. 다만 frame_queue·PSDK 글루 등 재사용 이력은 존재 | 기존 재사용 감사·채택 코드를 보존. 추가 모델/모듈은 라이선스·의미·품질 확인 후 격리 평가하며 다른 제품의 비행/Mission/Core 계약을 통째로 반입하지 않는다. 현재 L1 수직 경로를 별도 Platform 확장으로 바꾸지 않음 |
| G11 | **오프라인 로컬 번들 구성·규칙 수의 정직한 표시** | 빈 데모/프레이밍 픽스처를 프로덕션 전체로 일반화한 이전 설명은 부정확. 서버의 실제 구역/규칙 번들 선택 경로는 기존에도 있었음 | `MissionBundleBuilder` + 네이티브 구성 조작으로 저장된 불변 구역을 RC_LOCAL·SHADOW 번들로 구성·적용. 실제 설치된 규칙·OFF/거부/보류를 구분. 새 지도 편집기·현장 좌표 변환·실측 공간 정확도는 미완료 |
| G12 | **대응 이력의 Core 조회 경로** | 바이트 보존만으로 구조화 조회를 대신할 수 없었음 | 공개 확장 API·append-only 저장·참조/중복 충돌 검증·RC 배치 전송/영수증·실제 Core 조회·대조 구현. 기존 서버 404는 pending/미검증으로 남기고 안전·전달 완료를 추정하지 않음 |

## 3. 확정 방향과 충돌하는 문서·설계

| 위치 | 충돌 내용 | 처리 |
|---|---|---|
| `README.md:88-95` “RC 조종(관측) — 영상과 기록 상태 중심의 **보조 화면**”, “비행 조종 기준은 **DJI Pilot 2**” | 2026-10-17 주 운용 앱 확장과 정면 충돌 | **정정**. KSI 앱이 주 운용 화면이며 DJI는 필수 운용 경험의 기준·안정화/보호 담당으로 명시. 실장비 검증 전 완전 제품이라 쓰지 않음 |
| `design/rc-pilot-shell/*` HTML 시안 | 현장 흐름을 “현장 목록→구역 편집→기록”으로 축소. 네이티브 미반영 | **시안은 시각 참조로만 유지**. 제품 진실은 Android/Edge/Core 코드. 제품 정의·IA가 확정된 뒤 시안 갱신 |
| `docs/specs/2026-10-13-service-blueprint.md` S1·S2 | “초안(사용자 검증 전 확정 금지)” | 유지. S0(개발자 점검)만 확정 |
| `docs/product-state.md:37-41` | 10-17 주 운용 앱 개정 명시 | 현행. 이 문서의 확인된 제품 정의를 참조하도록 갱신 |
| `docs/plans/2026-10-17-rc-operations-screen-contract.md` | 4목적지(임무·운용·비행기록·설정), 행동 계약 | 현행. 통합 운용 화면은 이 계약의 조작·상태 축을 따른다 |

## 4. 현장에서 하나의 앱으로 작업을 끝내기 위한 최소 기능

`① 준비 → ② 비행·촬영 → ③ 관측·대응 → ④ 귀환·착륙 → ⑤ 기록 → ⑥ 동기화·검토` 를 **앱 전환 없이** 완결.

| 단계 | 필요한 기능 | 현재 |
|---|---|---|
| ① 준비 | 현장·임무 번들 선택, Edge 적용 상태 확인(APPLIED 실제 확인), 관측 가능 조건 표시, 저장 공간 확인 | 임무 준비·적용은 존재. 적용 상태의 현장적 표시는 `MissionController` 결과에 국한 |
| ② 비행·촬영 | 비행 상태·배터리·고도·속도·링크, 영상, 카메라(줌·짐벌·촬영), AI 장애 시에도 유지 | **비행 상태·영상은 존재, 카메라 명령은 2026-10-20에 배선**(실기 미검증). **비행 명령(가력·착륙·RTH)은 제어권 확인 경로가 없어 현재 차단됨(G1b)** |
| ③ 관측·대응 | 후보 표시(유효성·보류 구분), 상세 근거, 재관측, 확인·보류·전달 요청, 그리고 그 사실의 기록 | **표시·대응 이력 없음** |
| ④ 귀환·착륙 | 귀환·착륙 명령, 그 사이 AI 관측 계속 | 명령 없음 |
| ⑤ 기록 | 세션·영역·공백·판정·조치 이력의 로컬 영속, 재시작 복원 | 세션·증거 영속 존재, 조치 이력 없음 |
| ⑥ 동기화·검토 | 업로드 큐·재개·대조, 현장 책임자 재검토 | 존재(개발자 점검 수준) |

## 5. RC · Edge · Core의 책임과 데이터 경계

- **Edge**: 관측 이벤트·상태·시간/위치, 공간관계, 위험 정책 평가, 근거·품질·보류 사유. **판정 결과를 만든다.**
- **RC**: Edge 결과의 **사용 적합성 확인**(지연·근거 누락·표시 프레임 대응), 표시, 조종자 대응 연결, 현장 기록. **판정을 재계산하지 않는다.**
- **Core**: 영구 보관·무결성·정책 버전·권한·사건 조회·사후 검토. **RC 로컬 보관과 서버 보존을 동일시하지 않는다.**

데이터 경계 규칙(현행 유지): ① 탐지 / ② 공간판정 / ③ 위험평가 / ④ 경고 활성화를 독립 축으로 유지. ⑤
`0 incidents ≠ 안전`, ⑥ 라이브 DJI 영상과 추론 프레임 동일시 금지, ⑦ VLM은 사후 검토 전용.

## 6. 실장비 검증에서 먼저 확인할 기술적 위험

1. MSDK 명령·상태 조회 실기 동작(키·등록·기체 연결·명령 응답) — 현재 전부 미검증
2. 영상 프레임 ↔ 표시·추론 프레임 대응 (`frameMappingVerified`가 항상 false)
3. 관측 유효성·공간판정 보류 상태의 실측 빈도
4. 오프라인 구간·앱 재시작 후 복원·공백 재현
5. DJI Pilot 2 동시 실행·전환 동작(복구 대책으로 가정 금지)
6. RC Plus 2 실외 가독성·장갑 터치·실제 화면 크기
7. Edge 중단 시 기본 비행·촬영 유지

## 7. 개발 순서와 완료 기준

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| S0 | 갭 분석·문서 정합(본 문서, README·product-state·AGENTS·원장 정정) | **완료(2026-10-20)**. 코드/계약 변경 없음 |
| S1 | **비행·카메라 명령 포트 + 명령 게이트 + MSDK 배선** (rc-ports 순수 모델·rc-msdk 구현) | **구현 완료(2026-10-20)**. 호스트 테스트 통과. 명령 결과가 성공/거부/미확인/권한없음/기체없음으로 구분. **실기 동작은 미검증으로 명시** |
| S2 | **통합 운용 화면** (rc-app) | **구현 완료(2026-10-20)**. 비행 필수 정보 띠가 AI UI에 가려지지 않음. 카메라 명령·AI 후보(유효/보류 구분)·기록 상태 공존. 기존 레이아웃 계약 테스트 유지. **실기 렌더·햇빛·장갑 미검증** |
| S3 | **대응 이력 모델 + 로컬 영속 + 비행과 함께 보존·업로드** (rc-ports·rc-core) | **구현 완료(2026-10-20)**. 확인/보류/전달요청이 후보·관측·정책·시각과 연결되어 로컬에 남고, `FLIGHT_LOG` 아티팩트로 비행과 함께 업로드된다(계약 변경 0). **Core 조회는 §9-1 결정 대기. 대응 이력 작성 UI는 미구현** |
| S4 | **Core 재사용 흐름(t1→t2) 연결** | 계약 결정(§9-2) 후 착수. 그 전까지 `review-finding` 경로 없음이 정직한 한계 |
| S5 | **짧은 실장비 경로** (A9-4) | 실제 영상 입력 → Edge 탐지·품질 → RC 표시·영속 저장 → 단절·재시작·복원 → Core 업로드·대조. 실기 없이는 완료 판정 금지. **외부 입력 대기** |
| S6 | **ACTIVE 경고 활성화** (별도 게이트) | 규칙별 실제 정확도·오경보·지연·대응 검증 통과 전 금지 |
| S7 | **제어권 확인 경로(G1b)** | DJI로부터의 제어권·조작 상태 확인이 없으면 KSI 앱은 비행 명령을 보낼 수 없다. 실기 확정·구현 후 S1의 비행 명령이 실제 사용 가능해진다 |

S1~S3은 호스트 검증 가능 항목이고, S5·S6은 외부 입력(장치·앱키·Manifold·현장 GT)이 필요하다. **S1~S3의 통과를 실장비 통과로 취급하지 않는다.**

## 8. 이 작업에서 하지 않는 것

- `contracts/` 스키마·OpenAPI 임의 변경 (불변 원칙)
- 실장비 검증 없는 ACTIVE 경고·자동임무·AI 자동 재관측
- Pilot 2 전환을 검증된 장애 복구 대책으로 가정
- 기존 동작·테스트·증거 파괴, 다른 세션 미커밋 작업 덮어쓰기
- HTML 시안을 제품 진실로 취급

## 9. 계약 결정 요청 (구현 착수 전, Core 측 소유)

대응 이력(확인·보류·전달 요청)과 그 전달 상태를 Core로 올릴 통로가 없다. RC가 임의로 정하지 않고
결정을 요청한다.

**배경.** `operator-annotation.v1`은 사후 검토 의견 채널이고 `annotation_type`이 3종으로 고정되어 있다.
비행 중 조작자 대응과 `요청/전송/수신확인/조치완료` 4단 상태는 이 enum에 들어가지 않는다. 자유 텍스트
`reason`에 밀어 넣으면 조회·검증이 불가능하고 정직하지 않다. `detection-event.v1`이 이미
`zone_revision_id·policy_revision_id·evidence_artifact_ids·observation_quality·mission_snapshot_hash`를
보유하므로 **연결 기준은 충분**하고, 부족한 것은 대응 행위·전달 상태의 표현뿐이다.

**선택지.**

| 선택지 | 내용 | 대가 |
|---|---|---|
| A | `operator-annotation.v1`에 대응 행위 3종 + 구조화된 전달 상태 필드를 추가(버전 bump 또는 v2 신설) | 기존 사후 검토 의견과 비행 중 대응이 한 계약에 섞임. enum 확장은 기존 소비자 호환성 검토 필요 |
| B | `operator-response.v1` 신설 + batch 경로·멱등 규칙(`client_batch_id` + payload hash) | 계약·서버·RC 3곳 작업. 사후 검토 계약과 분리되어 의미가 분명 |
| C | 대응 이력은 RC 로컬 전용 유지, Core는事后 검토에서 별도 입력 | 전송 경로 없음. "현장 대응 이력의 사후 재현" 목표와 어긋남 |

**권고: B.** A는 사후 검토 의견과 비행 중 대응이 한 종류로 합쳐져 "무엇이 언제 있었던 기록"의 해석이
흔들린다. 전달 상태 전이(`REQUESTED→SENT→RECEIVED→COMPLETED`)는 `REVOKE_PREVIOUS`와 같은
append-only 기록으로 올리는 것이 현행 패턴과 일치한다.

이 결정이 나기 전까지 대응 이력은 **의도적으로 로컬 전용**이며, 화면은 "전달 요청만 저장됨 · 전송 완료
아님"으로만 말한다.

### 9-1. 대응 이력의 Core 조회 경로

**보존은 2026-10-20에 끝났다.** 대응 이력은 `flight-upload-manifest.v1`이 이미 허용하는 `FLIGHT_LOG`
`source_kind`로 **계약 변경 없이** 비행과 함께 업로드된다(부트별 스냅샷 1개, sha256·size 검증 가능,
응답이 없으면 아티팩트 자체를 만들지 않아 완전성 판정이 바뀌지 않는다, 본문 바이트가 없으면 `PARTIAL`이지
`COMPLETE`가 아니다).

**남은 것은 조회뿐이다.** Core는 바이트를 받아 digest로 검증할 수 있지만, 대응 행위를 이벤트와 연결해
**조회·재검토**할 수는 없다. `operator-annotation.v1`의 `annotation_type`은 3종 고정이라
`확인/보류/전달 요청`과 전달 상태를 담을 수 없고, 전달 상태 전이(`REQUESTED→SENT→RECEIVED→COMPLETED`)를
기록하는 형태도 없다.

| 선택지 | 내용 | 대가 |
|---|---|---|
| A | `operator-annotation.v1`에 대응 행위 3종 + 구조화된 전달 상태 필드 추가(버전 bump 또는 v2 신설) | 사후 검토 의견과 비행 중 대응이 한 계약에 섞임. enum 확장은 기존 소비자 호환성 검토 필요 |
| B | `operator-response.v1` 신설 + batch 경로·멱등 규칙(`client_batch_id` + payload hash) | 계약·서버·RC 3곳 작업. 사후 검토 계약과 분리되어 의미가 분명 |
| C | `FLIGHT_LOG` 바이트만 두고 Core 조회는 나중 | 지금은 "보존됨"까지만 가능. 사후 재검토 목표까지 닿지 않음 |

**권고: B.** A는 두 기록의 해석이 섞인다. C는 보존이 이미 된 만큼 추가 비용 없이 조회만 붙일 수 있으나
"대응 이력 조회"라는 제품 목표에는 미달한다.

### 9-2. 재분석(결과 갱신) 경로

`review-finding.v1` 스키마는 있으나 Core에 대응 엔드포인트가 없다(`/review-jobs`는 P1 기록 전용).
따라서 "t1 → t2: 새 자료 등록 → 변경 영향 확인 → 재분석 → 결과 갱신"은 다음 두 가지를 함께 정해야
완성된다.

1. **재분석 결과의 저장·조회 경로** — `review-finding.v1`을 쓰는 엔드포인트(flight/event 기준)와
   멱등 규칙. `detection-event`와 같은 event를 가리키되 `model_digest`가 다른 finding이 "갱신"의
   표현이 되므로, **이전 finding을 덮어쓰지 않고 append-only로 쌓는 방식**이 현행 패턴과 맞는다.
2. **변경 영향의 정의** — 새 관측이 어떤 episode/finding에 영향을 주는지(zone_revision·policy_revision
   변경, 새 zone 진입, 모델 변경). 현행 `detection-event.episode_id`·`mission_snapshot_hash`로 유추는 것과
   명시적으로 계산해 노출하는 것은 다르다. 계산 규칙은 Edge/분석 책임이며 Core가 임의로 정하지 않는다.

**권고: 재분석 결과 경로는 계약 신설(엔드포인트 + 멱등 규칙)로 다루되, "변경 영향 계산"은 Edge 쪽 분석
책임으로 남긴다.** Core는 결과를 쌓고 조회할 뿐, "이게 어떤 영향을 주는지"를 추정해 말하지 않는다.

## 10. RC 앱으로 Manifold 작업을 운용하는 목표와 실제 연결 갭 (2026-10-20)

사용자 재확인: PSDK를 사용하는 Manifold 3가 기체에 탑재되고 MSDK 앱은 RC Plus 2에서 운용한다.
현장 데이터 통신은 RC↔기체 링크를 통해야 하므로, RC 앱이 Manifold의 작업을 설정·요청·확인하는 것이
제품의 핵심이다. 별도 현장 인터넷·Edge→Core 직접 전송을 전제로 바꾸지 않는다.

- 논리 경로: `RC KSI 앱 → MSDK MOP → 기체↔RC 전송 경로 → PSDK MOP → Manifold KSI Edge 서비스`.
  MSDK가 원격 PSDK 함수를 임의로 실행하는 구조가 아니라, KSI 명령을 Manifold 서비스가 검증·실행·응답한다.
- 비행 임무(이동 경로·기체/카메라 명령)와 관측 임무(구역·분석 정책·세션)는 같은 현장 운용에 속하지만
  실행 주체·권한·실패 처리가 다르다. 본 확인은 AI 자동 기체 제어·PSDK 비행 명령으로의 전환 승인이 아니다.
- 기존 전송 자산: RC `MissionController.prepare/apply/startSession/stopSession/reconcile`,
  `MsdkDroneLink`와 Edge `MopService`·`MopEdgeEndpoint`, 관측 수신·RC 영속 저장·업로드.
  **실기 MOP 통과 증거는 아직 없다.**

### 실제 런타임 갭 — 프로토콜 상태와 혼동 금지

1. `edge/src/transport/mop_endpoint.cpp:200-275`: APPLY 시 `running_=true`; SESSION_START/STOP은
   bool 변경과 ACK. `MISSION_STATUS.running`은 이 bool이며 실제 worker 실행 측정값이 아니다.
2. `edge/psdk-app/src/main.cpp:335-378`: 프레임을 꺼내 항상 `SessionRunner::ProcessFrame` 후 `EmitTick`.
   `:501-518`에서 규칙·구역을 빈 벡터로 주입하며, 전달받은 번들의 실제 분석 설정 설치 경로가 없다.
3. `edge/psdk-app/src/mop_service.cpp:140-154`: 연결·flight identity·적용 번들 출처만 검사한다.
   SESSION_STOP 이후에도 실제 처리/결과 전달이 중단됐다는 증거가 되지 않는다.
4. RC의 ACK≠실행 확인 원칙은 유지하지만, Edge가 상태 bool을 보고하는 것만으로 실제 실행 확인이 충족되지는 않는다.

### 다음 구현 단위와 판정 기준

기존 wire 계약을 보존하면서 **RC 설정 요청 → Edge 검증 → 실제 런타임 설정 설치 → 명시 시작 → 실제 처리
상태 응답 → 관측 수신·RC 보존 → 명시 중지·마감 → 재연결 상태 대조**를 하나의 수직 경로로 연결한다.
APPLY와 시작은 독립 조작이어야 하며, 단순히 `EmitTick`에 bool gate를 추가해 추론 제어가 완성됐다고 하지 않는다.
세션 중지 시 추론·결과 발행·잔여 버퍼·최종 요약의 범위, 진행 중 설정 변경의 적용 경계는 기존 계약에
맞춰 명시하고 필요한 정책 변경은 별도로 결정한다. heartbeat는 관측 중지 후에도 연결/서비스 상태 확인용으로
구분한다. 구역/정책 변경이 실제 계산에 반영되는지, STOP 후 실행·발행 범위가 지켜지는지 직접 검사해야 한다.

이 절은 확인된 갭과 구현 방향을 기록하며, SDK 조합별 지원·대역폭·장치 실행·사용자 인수를 확정하지 않는다.

### 사용자 확정 결정 (2026-10-21) — 구현 계약

| 결정 | 확정 내용 | 계약 위치 |
|---|---|---|
| 첫 위험 | **사람–중장비 접근**. 초기 판정은 지정 작업구역 침입(ZONE_INTRUSION) 중심. 절대거리 기준은 두지 않는다. 동적 접근·거리 추정은 L2 | `docs/plans/2026-10-21-edge-operation-contract.md` §1·§4 |
| 링크 단절 | 이미 시작된 Manifold 관측은 **계속**한다. MOP 통신 장애와 영상 입력 장애는 다른 사건. 원격 명령 실행·상태 변경은 재연결·재검증 후에만 | 같은 문서 §4.3·§5 |
| `COMMITTED` | **증거의 영속 보존** 기준. 업로드 시도·파일 수신만으로는 아니다. RC는 커밋 영수증을 로컬 저장한 뒤에만 완료 표시 | 같은 문서 §6 |
| 세션 결과 | 보존 성공과 분리. `SUCCESS`/`FAILED`/`INTERRUPTED`/`UNKNOWN`. 장애 기록까지 보존된 실패 세션도 `COMMITTED`될 수 있으나 L1 성공 집계에는 넣지 않는다 | 같은 문서 §6 |
| L1 필수 기록 | 세션·설정 / 실행 이력 / 관측 결과(0건이면 0건이라는 사실) / 운영자 대응(0건이면 그 사실) / 영상·증거 5묶음 | 같은 문서 §6 |

구현은 `docs/plans/2026-10-21-edge-operation-contract.md`를 따른다. 단위 A(edge 런타임 제어)·B(RC 상태 모델)·
C(RC 화면)·D(하네스·Core)로 나누어 진행하며, L2 정확도와 L3 경고 운용은 범위 밖이다.
