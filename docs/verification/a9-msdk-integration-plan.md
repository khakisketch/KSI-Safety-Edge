# A9 — DJI MSDK v5 통합 계획 + 지상 세션 절차

작성: 2026-10-11 · 갱신: 2026-10-14 · 상태: **A9-1~3 코드·호스트 검증 완료, A9-4 실기 세션 입력 대기**

전제: L1 공통 소프트웨어(Core·Edge·RC·Replay·앱 A2~A8·L1 마감 C55~C58)는
**동결**. A9 구현은 공용 모듈 구조로 진행한다(승인된 최소 연결부):
- `rc-ports`(순수 Kotlin): `DroneLink`/`VideoSource`/`VideoRecorder` 인터페이스+벤치 더블
- `rc-app`: `bench`/`device` 플레이버 + `PlatformLinks` 조립 지점(각 플레이버 소스셋)
- `rc-msdk`(Android 라이브러리): DJI v5 어댑터 — `device` 플레이버에만 포함

## 0. 검증된 사실 (2026-10-11/12, 공식 소스 기준)

- 공식 저장소 `dji-sdk/Mobile-SDK-Android-V5`(dev-sdk-main) README·Sample:
  - `implementation 'com.dji:dji-sdk-v5-aircraft:5.18.0'`
  - `compileOnly 'com.dji:dji-sdk-v5-aircraft-provided:5.18.0'`
  - `runtimeOnly 'com.dji:dji-sdk-v5-networkImp:5.18.0'`
  - Sample의 `REPO_MAVEN2 = https://repo1.maven.org/maven2` (Central과 동일)
  - Sample: `abiFilters 'arm64-v8a'`, app key는 `manifestPlaceholders["API_KEY"]`
- **아티팩트 내용 실측(중요)**: Central의 `aircraft-5.18.0.aar`(138MB)는 네이티브(.so)
  + 에셋(플라이세이프 DB 등) 중심이고 `classes.jar`가 약 4KB(SDK Java 클래스 없음);
  `networkImp`도 classes.jar ~13KB. `-provided` jar(10MB)가 dji.v5 API 클래스를
  담고 있으나 메서드 본문이 **스텁 패턴**(초입 즉시 반환 + 실코드 사문화)이다.
  → **공식 문서 모델 그대로도 Central만으로는 런타임 SDK 클래스가 앱에 들어가지
  않는다.** 실제 등록·동작 여부는 실기 게이트: RC에서 시험하고, NoClassDefFound
  발생 시 (a) developer.dji.com 로그인 SDK 다운로드(zip) 반영 또는 (b) `-provided`
  런타임 패키징(라이선스/스텁 의미는 실기에서) 중으로 확정한다. **이 상태를
  "연결 검증됨"으로 표기하지 않는다.**

## 1. 의존성 (Maven Central 확인 완료, 공식 좌표)

| 좌표 | 용도 |
|---|---|
| `com.dji:dji-sdk-v5-aircraft:5.18.0` | 런타임(실기기 배포용, ~90MB AAR) |
| `com.dji:dji-sdk-v5-aircraft-provided:5.18.0` | `compileOnly` 컴파일 스텁(호스트 빌드) |
| `com.dji:dji-sdk-v5-networkImp:5.18.0` | 네트워크 구현 |

- 호스트(aarch64)에서는 제공 스텁으로 컴파일 검증, 실기 배포 빌드는 네이티브
  x86_64 경로(기존 확정)에서 실제 AAR로 수행한다.
- 최신 확인: 5.18.0 (2026-10).

## 2. 어댑터 매핑 (구현 대상)

| 인터페이스(동결) | MSDK v5 구현 | 비고 |
|---|---|---|
| `DroneLink` | SDK 데이터 채널(PSDK) 양방향 바이트 스트림 + 기체 연결 상태 | MOP Envelope는 그대로; 전송 수단만 교체. 세부 API는 SDK 문서/스텁 확인 후 확정(TODO-API) |
| `DroneLink.aircraftConnected` | FlightController 연결 상태 | 링크 상태와 독립 표시(기존 계약) |
| `VideoSource` | `VideoStreamManager` liveview + DJICodecManager 디코드 | `frameMappingVerified`는 **노출-기반 검증 전까지 false 유지**(오버레이 금지) |
| `VideoRecorder` | 수신 영상 인코딩(MediaCodec/Muxer) → 세그먼트 파일 → `SegmentIngestor` | 롤오버는 `recording_policy.segment_seconds`; 사건 0건 비행도 녹화 |
| (시각) | 앵커 없음 | **시간 정합은 별도 측정**: 노출시각 vs 수신 지연 분포 측정 전에는 `RC_RECEIVE_APPROX` 유지 |

## 3. 등록/키 처리

- DJI 개발자 계정의 **앱 키**(RC/기체 등록) 필요 — 사용자 입력.
- 키는 `local.properties`(미커밋) → `BuildConfig` 필드로 주입. 저장소에는 절대
  넣지 않는다.
- 키가 없으면 SDK를 **초기화하지 않고** 벤치 모드로 기동(기존 앱 동작 유지 —
  동결 위반 없음).

## 4. 지상 세션(프로펠러 분리) 체크리스트 — 1차 실기

순서대로, 각 항목의 증거(스크린 캡처/로그)를 `docs/verification/evidence/`에
기록한다.

1. **설치·기동**: APK 설치 → 실행 → 크래시 없음 → `rc-store` 생성 확인.
   **동시에 최소 SDK 초기화 확인(C60 후 첫 실기 항목)**: Helper 로드/등록 결과
   (`MsdkPlatform.state`·`DjiBridgeState`) — 실패 시 NoClassDefFound/VerifyError
   로그를 그대로 기록(upstream #809·#671 계열). 키 미설정이면 KEY_MISSING 표시가 정상.
2. **MSDK 초기화**: 등록 성공 로그, RC 화면에 연결 상태 표시(인터넷/MOP/기체 독립)
   + `--mop-channel 0` 실기동: HEARTBEAT 수신·MISSION_PREPARE/APPLY 왕복·ACK 확인
   (**flight id 핸드오프 확정 필요** — RC가 만드는 비행 id와 Edge envelope flight_id 일치)
3. **RC↔Manifold 연결**: 데이터 채널 open, MOP HEARTBEAT 수신(채널 신선도 표시)
4. **임무 적용**: 실 스냅샷/번들 다운로드 → PREPARE/APPLY → digest 검증 표시
5. **로컬 저장**: 관측/사건/세그먼트 저장 → **앱 강제 종료 → 재시작 → 복원** 확인
6. **통신 복구**: 채널 단절 → 재개 → 재전송(동일 msg_id)·누락 복구 확인
7. **WorkManager 실기동**: 비행 종료 → 업로드 예약 → (개발망 Core) COMMITTED 확인
   → **여기서 Robolectric 대체 실기 실행 검증이 완료됨**
8. **시계 기록**: 장치 시계 편차 기록(정합 측정 전/후를 섞지 않음)
9. 저장공간 부족·프로세스 종료 등 실패 주입 1회씩
10. 통과 후 → 프로펠러 장착·현장 비행 검증(별도 절차)

## 5. 입력 요건 (사용자/현장)

| # | 입력 | 막는 단계 |
|---|---|---|
| ① | Manifold 콘솔 관리자 접근(복구용, 비밀번호는 비채팅) | H1 복구(재부팅/fs/시계) |
| ② | RC Plus 2 USB 연결·디버깅 허용 | RC 단독 시험 전체 |
| ③ | DJI 개발자 앱키(RC/기체 등록) | MSDK 초기화 |
| ④ | (선택) 개발망 Core 접속 정보·제한 자격증명 | 동기화 실기 확인 |

## 6. 배포 전 보안 게이트 (동결 중 기록만)

- **통합 게이트 목록: `docs/plans/2026-10-14-production-security-gate.md`** (G1~G7·결정 필요·불변 원칙).
- `AppConfig` 토큰: 현재 `CoreTokenStore`의 **Android Keystore 기반 암호화 봉투**로
  저장하며, 이전 평문 설정 마이그레이션과 보안 저장소 오류 표시가 구현돼 있다.
  **실기 저장·재시작·오류 경로 검증은 아직 필요**하다. 구현됨과 실기 통과를 구분한다.
- AndroidManifest 전역 평문 HTTP: **2026-10-14 부분 이행** — 릴리스 빌드는
  `network_security_config`로 평문 금지(디버그 빌드는 개발·실기용으로 허용, 빌드 산출물
  dump로 확인). 운영 엔드포인트 HTTPS 확정은 게이트 G1로 유지.
- 두 항목 모두 실제 운영 배포 전 필수, 실기 검증 항목과 함께 처리.

## 7. A9-1 구현 기록 — C60 (2026-10-12, 장치 전)

점검 기록·공식 Sample(dev-sdk-main) 대조 후, 장치 없이 가능한 기동 안정화를 구현했다.

- **공식 순서 확인**: `DJIAircraftApplication.attachBaseContext →
  com.cySdkyc.clx.Helper.install(this)`; `MSDKManagerVM`은 `SDKManager.init(...)` 후
  **`onInitProcess(INITIALIZE_COMPLETE)`에서만 `registerApp()`** 을 호출한다.
- **APK dex 실측(C59 빌드, 독립 재확인)**: `dji/v5/manager/SDKManager`(및
  `mop/PipelineManager`, `datacenter/video/VideoStreamManager`)는 어느 `classes*.dex`에도
  **정의되어 있지 않다**. `com/cySdkyc/clx/Helper`는 aircraft AAR `classes.jar`(9개 항목)로
  **APK에 포함**되어 있다. Helper의 `native attach(Application, Context)`·
  `makeInMemoryDexElements(...)`가 런타임 클래스 로더다 → SDK Java 클래스는 Helper 설치
  이후 런타임에 로드된다. 따라서 공식 `compileOnly -provided` 스코프는 의도와 일치하며,
  `-provided`를 `implementation`으로 바꾸는 것(upstream #809의 VerifyError 경로)은 하지 않는다.
- **구현(C60)**:
  - `MsdkPlatform.install(application)` — `attachBaseContext`에서 1회 `Helper.install`;
    실패는 `FAILED`+사유 기록(은폐 없음). bench 플레이버는 no-op.
  - `DjiSdkBridge` — 키 게이트 → `init(callback)` → **INITIALIZE_COMPLETE에서 `registerApp` 1회**;
    `LinkageError`/`Exception`은 `FAILED`로 표기(크래시 대신 정직한 상태).
  - `MsdkDroneLink.connect()` — 앱키 확인 **선행**, 파이프라인은 **REGISTERED 이후에만** 접촉.
- **검증(호스트)**: rc-core 67/67 · rc-msdk 8/8 · rc-app bench 23(6 스킵) · device 17/17 ·
  device APK 재빌드(166.9MB) — `MsdkPlatform` 정의·Helper 호출 참조 확인. 커밋 `a810572`.
- **미검증(실기 게이트)**: Helper 로드 → 등록 성공 → 파이프라인 연결(**§4-1** 설치·기동
  단계의 최소 SDK 초기화 확인). 실패 시 로그/예외를 그대로 증거로 기록하고, `-provided`
  패키징 전환·developer.dji.com SDK zip 반영은 실기 결과로만 결정한다.

## 8. A9-2 구현 기록 — MOP 전송 계층 (C61, 2026-10-12, 장치 전)

기존 MOP 봉투 계약(Python harness·rc-core와 동일 — 새 의미 계약 없음) 위에 실제
양방향 전송 경로를 구현했다. 메시지 로직은 호스트 시험 대상, **채널 I/O만 장치 게이트**다.

- **C++ 전송 코어**(`edge/src/transport/`): `digest`(SHA-1/256·uuid5·base64) ·
  `json`(엄격 파서 + JCS/ES6 직렬화) · `mop_frame`(u32be+flags 프레이밍·재조립,
  64 KiB 청크 가드, 손상 시 버퍼 폐기+계수) · `mop_envelope`(봉투 생성/검증 —
  `payload_sha256`은 payload 단독의 JCS digest, Python/Kotlin과 동일) ·
  `mop_evidence`(OBSERVATION/EVENT/FLIGHT_SUMMARY/HEARTBEAT/ARTIFACT 페이로드
  빌더 — 기존 생성 경계 재사용, 좌표/촬영시각 날조 없음) · `mop_endpoint`
  (`edge_replay.py` 의미 이식: 채널별 seq·uuid5 msg id·unacked/재전송/unrecoverable
  gap·mission PREPARE/APPLY/STATUS/SESSION·reboot) · `mop_link`(`MopByteTransport`
  포트 위 flush/feed/retry/재조립 리셋, mutex 직렬화).
- **Kotlin 하드닝**(사용자 지적 2건 + C60 리뷰 D1~D5 반영): `MopFrameWriter`(동시
  송신 프레임 직렬화 — UI 스레드+재전송 타이머), 부분 write 시 연결 재수립(이미 전달된
  바이트는 되돌릴 수 없음 — 재연결로 양 끝 재조립 리셋, 봉투는 같은 msg_id 재전송),
  desync(청크 가드 초과) 시 계수+리셋+연결 종료(리싱크 마커 없음), connect()/disconnect()
  재조립 버퍼 초기화(세션 간 조각 혼입 금지); D1~D5: disconnect/영상 `LinkageError`
  캐치, FAILED 재표기(INITIALIZING 위장 제거)+`retryRegistration`, `@Volatile` 상태,
  문서 정정.
- **양방향 골든 고정**(`contracts/mop/` + README): rc→edge는 Kotlin `MopEndpoint`가
  생성하고 테스트가 동일 바이트를 재생산(고정); edge→rc는 **실제 C++ 엔드포인트+링크가
  생성**하고 **Kotlin 테스트가 실제 `MopEndpoint`+`EvidenceIngest`(+FileRcStore) 경로로
  소비**(관측/사건/요약 저장·artifact 조립/해시 일치·seq 부기·commit-before-ACK 확인).
- **PSDK 애플리케이션 연결**(장치 게이트): `src/mop_service.cpp` — 채널
  Init/Create/Bind/Accept(리더 스레드)·RecvData 폴링·SendData 부분 write 루프 +
  flush/재전송/heartbeat 타이머; `main.cpp` `--mop-channel N`(`--flight-id`) 옵션
  (미지정 시 기존 동작 불변).
- **검증(호스트)**: edge **19/19**(신규 `mop_frame`·`mop_json`·`mop_e2e`) ·
  rc-msdk **15/15**(프레이밍 골든·동시성·양방향 크로스랭귀지) · rc-core 67/67(불변) ·
  rc-app bench 23(6 스킵)·device 17/17 · device APK 재빌드. 커밋 `76b8642`(C61).
  독립 검토 반영: desync 재연결 경로 리더 누수 수정 · `Encode(0)` 거부 ·
  malformed PREPARE harness parity(error, ACK 없음) · MopService 핸들 순서.
- **미검증(실기/H1)**: 실제 MOP 채널 연결·처리량/지연, MISSION 왕복, WorkManager 실기동.
  **다음(A9-3 전)**: tick→evidence payload 매핑(psdk-app) · flight id 핸드오프 확정 ·
  개발 host에서 psdk-app 컴파일 불가(PSDK 헤더) — 첫 장치 빌드에서 컴파일 검증.

## 9. C62 구현 기록 — 실제 처리 결과 전달(tick→증거) + 비행 ID 핸드오프 (2026-10-12, 장치 전)

제품 기준(“Manifold에서 생성한 관측·증거가 RC에 저장되고 서버에서 조회”)에 맞춰, 고정 입력이
아니라 **실제 처리 결과**가 증거로 흐르는 경로를 연결했다.

- **tick→증거 매핑**(`edge/…/mop_tick_evidence.*`, 호스트 시험): `TickRecord` →
  OBSERVATION/DETECTION_EVENT/FLIGHT_SUMMARY — 검증된 브리지(`replay_evidence.py`) 의미 이식.
  결정적 uuid5 id · COCO 원명 라벨(0 person/2 car/7 truck, 그 외 coco-N) · OBSERVED 트랙은
  정확 박스 일치로만 연결(추측 없음) · CREATED 이벤트만 전송 · **kNotRun/추론실패는 관측을
  만들지 않음**(빈 관측=부정 증거 금지) · 촬영시각 미검증 사유·sync 등급 정직 표기 ·
  `position_wgs84` null. 요약은 covered/skipped·uptime·person/equipment 최대·세션 id
  (RC 유도 `uuid5 session|flight|boot`).
- **비행 ID 핸드오프**: RC가 비행마다 새 UUID를 만들므로(MissionStore) Edge는 **첫 RC 봉투의
  flight_id를 채택**(adoption) — 부트 회전(`boot|flight|idx`)·seq/unacked/prepared/seen 초기화
  (seq·재전송이 비행을 넘지 않음); MISSION_PREPARE(다른 비행)는 재채택; **채택 이전에는 송신하지
  않음**(하트비트 포함 — 잠정 id로 봉투를 찍지 않는다). 채택 봉투의 ACK부터 새 신원 사용.
- **미션 컨텍스트**: 적용된 번들(APPLY)에서 mission_revision_id·snapshot hash·policy revision을
  `EvidenceContextFromBundle`로 추출(호스트 시험) → 관측/사건에 박제. 적용 전 관측은 송신하지
  않고 skip 계수로 보고(은폐 없음).
- **PSDK 앱 연결**: `MopService::EmitTick`(워커 루프, 3중 게이트: 연결·채택·적용) ·
  `EmitFinalSummary`(종료 시) · 타이머 하트비트는 채택 이후에만; `--mop-channel` 미지정 시 기존
  동작 불변. 소스는 여전히 장치 게이트(호스트 컴파일 불가 — 첫 장치 빌드에서 검증).
- **검증(호스트)**: edge **20/20**(신규 `mop_tick`) · rc-msdk **15/15**(골든 재고정 + 매핑
  충실도 단언: class_id/track_id/sync_quality/frame_seq/mission_revision · event_type/mode/
  distance · summary session/coverage — **C++ 매핑 산출물을 Kotlin 실제 ingest 경로로 소비**).
  이번 증분은 rc-app/rc-msdk 제품 코드를 변경하지 않는다(테스트·fixture만). 커밋 `dbb1d46`(C62).
- **브리지와의 의도적 차이(검토 확인, 정직 방향)**: id 파트는 제품 스킴(`obs|flight|boot|tick`,
  `det|…|index`, `event|…|rule|episode`, `summary|flight|boot` — 하네스 fixture 태그와 다름) ·
  `class_name`이 채워진 검출은 모델 라벨 우선 · 트랙 연결은 class+정확 박스로 더 엄격 · zone
  이벤트는 실제 revision id, mode는 `is_shadow` 반영, observation_quality는 틱 등급, artifact
  목록은 빈 목록(정직) · 요약 created_at=마지막 tick · person/equipment 최대는 kOk 틱만.
  **adoption·교차 비행 terminal-ACK은 하네스에 없는 제품 필수 전송 동작**이다(메시지 shape는
  동일 — "새 의미 계약 없음"은 메시지 의미에 한함).
- **독립 검토(HIGH 2·MEDIUM 2·LOW 2) 반영**: F1 `MopService` — `endpoint_`/`evidence_` 직접
  접근을 `evidence_mutex_`로 단일화(락 순서 evidence→link→write) · F2 비행 전환 후 적용 번들
  미도착 창에서 이전 컨텍스트로 관측을 찍던 결함 → 컨텍스트 리셋(해당 틱은 skip) · F3 채택 시
  미플러시 outbox 폐기(구 신원 봉투 유출 차단) · F4 `--flight-id` 기본값 `""`(learn-from-wire
  실제화 — 하트비트 잠정 id 송신 제거) · F5 테스트 보강(kInferenceFailure·매칭 id 무재채택·
  교차 비행 terminal-ACK·dedup 재큐·unacked 초기화) · F6 서식. 재검증: edge 20/20·rc-msdk 15/15.
- **미검증(실기)**: 실제 MOP 채널 위 tick→RC 저장(장치 세션). 비디오/스냅샷 아티팩트는 A9-3,
  spatial·이벤트는 시간 정렬(H1) 후 조건 충족 시에만 — 현 상태에서 이벤트는 정직하게 비어 있다.

## 10. A9-3 구현 기록 — 영상 표시·MP4 세그먼트 (C63, 2026-10-12, 장치 전)

RC 조종 화면의 실제 영상과 증거 녹화를 연결했다. 표시와 녹화는 같은 스트림의 **독립 소비자**다.

- **표시**: `MsdkVideoSource`가 `VideoStreamManager` 프레임을 `H264SurfaceDecoder`(MediaCodec→Surface)로
  그린다. surface 수명주기는 `PlatformLinks.attachVideoSurface`(device)로 연결하고 bench는 no-op.
  생성 실패는 **1초 백오프**로 재시도(콜백 스레드 보호) — `frameMappingVerified=false` 유지(오버레이 금지).
- **녹화**: `EncodedVideoFrame`/`VideoFrameSink`(프레임 팬아웃) → `SegmentRecorderCore`(순수·호스트 시험)
  → `Mp4SegmentWriter`(MediaMuxer, 첫 CSD 보유 키프레임에서 시작). `SegmentNames`(rc-ports)로 파일명
  계약 단일화(`seg-NNNNN.mp4` ↔ parseIndex). 종료 시 `SegmentIngestor`가 표준 RC_PROXY_SEGMENT
  증거로 인제스트(Edge 부트 없으면 로컬 보관·정직 보고, 날조 없음). **증거 규율**: `*.mp4.tmp`는
  finalize 성공+무결 프레임일 때만 rename — 프로세스 사망·먹서 실패·프레임 신뢰 실패는 증거가 되지 않음.
- **앱 연결**: 비행 시작 시 bundle `recording_policy.segment_seconds`(1..3600 밖이면 기본 300)로 녹화
  시작; 비행 종료 시 **백그라운드 스레드**에서 마감·인제스트(UI 스레드 차단 방지) 후 동기화 예약.
- **독립 CE 리뷰(ce-code-review: 5 리뷰어 + 검증자; 교차모델 생략 사유 기록)**: P1 실결함 수정 —
  ①CSD 없는 시작 프레임이 세그먼트 전체 폐기 → **대기(첫 CSD 키프레임에서 시작)** ②③콜백·UI 스레드
  동시 접근 → **코어 전 메서드 동기화+@Volatile+싱크 예외 보호** ④회전 open 실패 wedge → **다음 경계
  재시도(+wedge 고정 호스트 테스트)** ⑤`write()==false` 계약 위반 → 신뢰 실패 세그먼트 발행 금지
  (+테스트) ⑧먹서 시작 실패 fd 누수 → 즉시 release ⑪디코더 생성 매 프레임 재시도 → 1초 백오프.
  검증: rc-ports 2/2(파일명 계약)·rc-msdk **24/24**(신규 3종 포함)·bench 23(6 스킵)·device 17/17·APK 재빌드.
  커밋 `e31fad8`(C63).
- **미검증(실기)**: PTS 단위·CSD 형식·재생 가능 파일 여부, 실제 표시/녹화 동시 동작, 재시작 시 녹화
  재개(알려진 잔여), 세그먼트 실측(재생·해시) — RC 지상 세션 항목.

## 11. C64 증분 — A9-3 리뷰 잔여 보강 (2026-10-12, 장치 전)

C63 리뷰가 남긴 잔여 항목을 마감했다(리뷰 파생 수정 + 호스트 시험; 별도 CE 라운드 없음).

- **재시작 시 녹화 재개**: `SegmentRecorderCore.start`가 비행 디렉터리의 기존 파일(확정 `.mp4`·잔여
  `.tmp`)을 스캔해 **다음 빈 번호**에서 시작(덮어쓰기 금지 — 재시작·동일 비행 재녹화 안전). 앱 시작 시
  활성 비행이 있으면 녹화를 재개하고 “녹화 재개: 이전 구간 일부 미저장”을 표시(정직).
- **재생 가능성 sanity 게이트**: `Mp4Probe`(rc-app, 순수) — `ftyp` 박스 + `moov`/`mdat` 존재(스캔 윈도
  경계 carry 포함)로 구조 검사; 통과 못 한 세그먼트는 **증거로 제출하지 않고 로컬 보관**(사유 UI 표시).
  재생 자체는 실기 세션 항목(PTS 단위)임을 명시 — 이 게이트는 명백한 불량만 거른다.
- **동시성 잔여**: `MsdkVideoSource` 디코더 교체/피드를 `decoderLock`으로 직렬화(표면 해제가 피드와 겹치지
  않음); `MsdkVideoRecorder.segmentSeconds`는 RECORDING 중 변경 무시(정책은 시작 시 스냅샷).
- **UI 스레드 정리**: 세그먼트 인제스트 결과(문제 목록 포함)를 백그라운드에서 계산해 UI 스레드에서 표시.
- **검증(호스트)**: rc-ports 2/2 · rc-msdk **25/25**(+재시작 번호 연속 시험, 잔여물 시험 갱신) ·
  rc-app bench 20·device 20(+`Mp4ProbeTest` 3) · APK 재빌드. 커밋 `8bba589`(C64).
- **미검증(실기)**: 실제 재시작 중 녹화 재개, 세그먼트 재생(ftyp/moov 존재 이상의 확인), 디코더 교체
  타이밍 — RC 지상 세션.

## 12. 주 운용 앱 텔레메트리 소스 (2026-10-17 추가, DEVICE GATE)

범위: 읽기 전용. 비행 명령·권한 요청 기능 없음. 구현: `android-rc/rc-msdk`
`MsdkFlightTelemetrySource`·`FlightTelemetryAccumulator`(순수) + 호스트 테스트
(`FlightTelemetryAccumulatorTest`·`GeoDistanceTest`). 키 이름·리스너 시그니처는 MSDK 5.18.0 문서와
provided 스텁 jar(`javap`)로 대조했다.

| 항목 | 키 / 근거 | 상태 |
|---|---|---|
| 기체 위치·고도 | `FlightControllerKey.KeyAircraftLocation3D` | 문서·컴파일 확인 — 고도 기준(이륙점/ASL) 실기 확인 |
| 홈 위치 | `FlightControllerKey.KeyHomeLocation` | 문서·컴파일 확인 |
| 홈 거리 | 직접 키 없음 → 위치 2키 조합(haversine) 계산 | 계산값 — 실기 대조 필요 |
| 속도 | `FlightControllerKey.KeyAircraftVelocity`(Velocity3D, NED) | 수평=√(N²+E²), 수직=−D 규약 |
| 방향 | `FlightControllerKey.KeyCompassHeading` | 문서·컴파일 확인 |
| 배터리 | `BatteryKey.KeyChargeRemainingInPercent` | 문서·컴파일 확인 |
| 비행 모드 | `FlightControllerKey.KeyFlightMode` | 원시 모드명 보관 — 표시 문구 매핑 후속 |
| 비행 중 여부 | `FlightControllerKey.KeyIsFlying` | 증거 채널 |
| 페일세이프 | `FlightControllerKey.KeyIsFailSafe` | 증거 채널 — `ControlAuthorityModel` 입력 후보 |
| GPS 레벨·위성수 | `KeyGPSSignalLevel`·`KeyGPSSatelliteCount` | 증거 채널 |
| 위치 고정(fix) 판정 | — | **결정 필요**: 단정 기준 없음 → `positionFix=null`(추정 금지) |
| 멀티컨트롤 지원 | `RemoteControllerKey.KeyMultiControlIsSupported` | 증거 채널 |
| 제어권 소유자 | `RemoteControllerKey.KeyMultiControlFlightControlAuthorityOwner` | 존재 확인 — 값 구조·단일 RC 보고 여부 실기 확인(원시 문자열 보관) |
| 리스너 해제 | `KeyManager.cancelListen(holder)` | javap 실측(`removeAllListeners` 아님) |
| 지원 판정 API | `isKeySupported` 계열 | 문서에서 미확인 — 실기 확인, 미지원 표시 경로는 누적기에 준비 |

- 신선도 4상태(미수신/유효/지연/미지원)는 누적기가 **필드별로** 관리하며, 마지막 확인값은 지연 상태로만
  보존된다(운용 계약 §5 · AT-03 신선도 절반). `Snapshot.timestampMs`는 최신 수신 시각이지 현재 주장이 아니다.
- A9-4에서 확인할 항목: 위 키 콜백 실수신·주기, 고도 기준, 멀티컨트롤 권한 보고(단일 RC), fix 판정 근거.
