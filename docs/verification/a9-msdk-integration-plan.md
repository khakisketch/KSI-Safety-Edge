# A9 — DJI MSDK v5 통합 계획 + 지상 세션 절차

작성: 2026-10-11 · 상태: **착수 준비 완료(코드 착수는 장치·앱키 입력 대기)**

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

- `AppConfig` 토큰: 현재 일반 SharedPreferences → 배포 전 Android Keystore
  기반(EncryptedSharedPreferences)으로 강화. **키스토어 동작은 실기 검증 필요**.
- `AndroidManifest` 전역 평문 HTTP: 배포 전 네트워크 보안 구성으로 범위 축소
  (개발망은 제한 자격증명 사용).
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
- **미검증(실기 게이트)**: Helper 로드 → 등록 성공 → 파이프라인 연결(§4-0 시험). 실패 시
  로그/예외를 그대로 증거로 기록하고, `-provided` 패키징 전환·developer.dji.com SDK zip
  반영은 실기 결과로만 결정한다. A9-2(PSDK 측 MOP 프레이밍)·A9-3(영상 표시·녹화)는 미구현.
