# A9-4 실기 하루 — 실행 런북 (RC 지상 세션)

> 공유용 스크럽본: 실제 기기 식별자·사설 IP·현장 세부·원문 로그는 제거됨.
> 생략된 값은 `[REDACTED]`로 표기하며, 실기 원본 증거는 비공개로 취급한다.
> 지시·순서·판정 기준은 유지된다.

작성: 2026-10-13 · 목적: **하루 안에 A9-4 지상 세션을 끝낸다.**
정의(단계): `docs/verification/a9-msdk-integration-plan.md` §4(10단계)·§5(입력)·§6(보안 게이트).
도구(RC 측): `scripts/rc_device_day.sh` — 빌드·설치·기록·증거 수집.
도구(Manifold 측): `docs/verification/h1-dpk-session-checklist.md` — DPK 설치·기동 그대로 사용.
안전: **프로펠러 분리** · 비행 없음 · 쓰기는 앱 설치·기동과 증거 파일에 한정.

## 필요한 입력 (사용자)

| # | 입력 | 막는 단계 |
|---|---|---|
| 1 | RC Plus 2 **USB 연결** + 개발자 모드/adb 디버깅 허용 | RC 단독 시험 전체 |
| 2 | DJI 개발자 **앱키** → `android-rc/local.properties`에 `dji.apiKey=...` (커밋 금지) | MSDK 등록(2) |
| 3 | Manifold 접근(`ssh ksi-manifold`) + DPK 설치·기동 승인 | MOP 왕복(3~6) |
| 4 | (선택) 개발망 Core 주소·토큰 | 업로드 확인(7) |

키가 없으면 앱은 KEY_MISSING으로 기동한다(정상 폴백) — 등록·채널 단계는 키 이후에만 진행된다.

### 입력 2 — DJI 앱키 발급 절차 (2026-10-21 실측 경로)

포털: `https://developer.dji.com/` (DJI 개발자 계정 로그인) → **Become a Developer** →
**Developer Center** → 좌측 **My Apps**(`/user/apps`) → **CREATE APP**.

| 폼 항목 | 값 |
|---|---|
| App Type | **Mobile SDK** |
| App Name | `KSI Safety Edge RC` |
| Software Platform | **Android** |
| Package Name | `ksi.safetyedge.rc` |
| Category | `Other` (건설현장 안전 관측) |
| Description | 앱 역할(임무 제어·관측 수신·기기 증거 보존·Core 오프라인 우선 전송) |
| 체크박스 2개 | Flight Record Parsing API License / 갱신된 약관 동의 → **사용자 확인 후** |

- 생성되면 앱 상세에 **App Key**가 나온다 → `android-rc/local.properties`에 `dji.apiKey=...` (커밋 금지).
- **키 바인딩은 패키지명이다(2026-10-21 포털 확인).** DJI의 Android Configuration 페이지:
  *"The App Identification Code must be entered when applying for a new App Key. The ID code when
  creating an Android app is the package name of the app."* → 생성 폼에도 keystore/서명 항목이 없다.
  따라서 **debug 서명과 release 서명이 달라도 같은 패키지면 같은 키를 쓸 수 있다**
  (기기 동작은 실기에서 확인한다).
- 개발자 프로필에 필수 3칸(Company ID · Contact Name · Contact Email)이 비어 있으면
  앱 생성이 막힐 수 있다 → **Developer Center → Profile → Edit Profile**에서 먼저 채운다.
- **App Type 선택표 (2026-10-21 포털 확인)** — 우리는 **두 개**가 필요하다.

  | App Type | 대상 | 우리 |
  |---|---|---|
  | **Mobile SDK** | RC Plus 2(Android)에서 MSDK v5 앱 | ✅ 필요 (위 폼) |
  | **Payload SDK-Manifold 3** | Manifold 3에서 도는 PSDK 앱 | ✅ 필요 (우리 `edge/psdk-app`) |
  | Payload SDK | 일반 페이로드(Manifold 2 계열) | △ 기존 `[REDACTED — app name]`(`[REDACTED — APP ID]`)가 이 타입 |
  | Onboard SDK | 기체(OSDK) 앱 | ✗ |
  | **Edge SDK** | **DJI Dock**용 데이터 허브(Linux) | ✗ — 우리 "KSI Safety Edge"는 Dock가 아니다 |
  | Cloud API / Open API | 클라우드 | ✗ |

  - 우리 `edge/psdk-app`은 `dji_mop_channel.h`(`DjiMopChannel_*`) + `dji_core.h` +
    `dji_liveview.h`를 쓴다 → **Manifold 3용 Payload SDK** 계열.
  - 순서: ① RC용 Mobile SDK 앱부터(지금 폼) ② 그 다음 Manifold 3용 앱.
    단 ②는 **기존 `[REDACTED — APP ID]`(Payload SDK)로 먼저 DPK 빌드·설치를 시도**해보고
    Manifold 3에서 거부되면 그때 `Payload SDK-Manifold 3` 타입 앱을 받는 게 빠르다.
  - **주의**: 2026-10-08 DPK 설치 검증 증거상 **기체 미접속 상태에서는 DPK 설치 검증이
    통과할 수 없다**(앱이 `DjiCore_Init`에서 기체 링크를 기다리며 블록) → Manifold를
    기체 페이로드 포트에 물려야 그 단계가 진행된다.
  - `edge/psdk-app/config/dji_sdk_app_info.h`(gitignore 대상)에
    `USER_APP_NAME`/`USER_APP_ID`/`USER_APP_KEY`/`USER_APP_LICENSE` 4개를 채우면 DPK 빌드가 된다.
    **2026-10-21 상태: 후보 자격증명으로 채움**(상세 값 `[REDACTED — PSDK app credentials]`).
    → **aarch64 빌드 검증 통과**: PSDK 정적 라이브러리(호스트 경로 `[REDACTED — host path]`)로
    `ksi_safety_edge`가 aarch64 ELF로 **컴파일·링크**된다(크기 `[REDACTED]`). placeholder 게이트( startup exit 2)도 해제.
    → 남은 건 **DPK 패키징**(`build_dpk.sh`는 온디바이스 `/open_app/psdk/tools/`에 있음)과 **설치**.
    → 주의(2026-10-21 정정): 이 앱은 `sdk_slug=payload`(classic Payload SDK)이고 설명은
      *"intelligent payload for DJI enterprise drones — edge AI, RTK, cameras, communication modules"*
      즉 **하드웨어 페이로드 제품의 등록**이다. 우리 DPK는 `app.json`의 platform이 `manifold3`이고
      `dji_mop_channel.h`를 쓰므로 **정식으로는 `Payload SDK-Manifold 3` 타입 앱**이 맞을 수 있다.
      → `[REDACTED — candidate APP ID]` 자격증명은 **실기 설치 단계에서 검증 전 후보**로 취급한다
      (DJI Manifold3 "Build DPK Application": "user_app_id … will be verified during application
      installation"). 거부되면 `Payload SDK-Manifold 3` 타입 앱을 새로 발급받아야 하며,
      그 생성도 현재 계정 101로 막혀 있다.
    → 이 앱의 `Apply Status`·심사 상태·바인딩 payload 수는 `[REDACTED — portal status]`. 미검증 앱은 배포 대수 제한이 있다
      (보유 대수는 `[REDACTED — fleet size]`).
    → **"Manage Payload"는 포털에서 손으로 등록하는 곳이 아니다** (2026-10-21 실측):
      `GET /api/web/payloadsdk_bind_info` → `{"status":0,"message":"not found","data":[],"total_count":0}`,
      "Register" CTA는 `account.dji.com/register?appId=dji_sdk`(계정 생성)로 가고,
      바인딩용 write API는 없다(`lockMount` = `payloadsdk_bind_info/:id/status` PUT 뿐).
      → 즉 **기기가 스스로(DPK 설치/등록) 바인딩**한다. 포털 입력으로 해결할 항목이 아니다.
    → **Manifold SN: `[REDACTED — device serial]`** (사용자 제공, 본체 라벨). H1-1 때 eth0 주소는 `[REDACTED — private IP]`였으므로 전원 ON + LAN 배선이면 그대로 SSH된다(주소는 비공개 증거로 확인).

### 입력 2 — DJI 지원 회신(2026-10-21 수신) 후 조치

DJI 지원이 101 계열의 일반 원인을 안내했다: **App ID/App Key가 DJI 서버에서 승인되지 않았거나,
인터넷 연결 문제로 활성화 요청이 거부(Server Access Refused)됨.** 해결 수단은
**최신 DJI Assistant 2로 기기 활성화 재시도 + PC 인터넷 확인 + App ID/패키지명 일치 재확인**.
→ **우리의 101은 앱 실행(SDK registration)도, Assistant 2도 아니고 "개발자 콘솔 앱 생성 API"에서
발생한다.** 답변 초안·질문 4개·실기 체크리스트: `docs/plans/2026-10-21-dji-support-reply-draft.md`.

실기 세션 체크리스트(순서):
1. Manifold 3 전원 ON + LAN → SSH 확인
2. **Manifold → DJI 서버 인터넷 경로 확인**(없으면 활성화는 구조적으로 실패 → 게이트웨이/NAT 또는
   인터넷 있는 선 연결)
3. **Windows PC + 최신 DJI Assistant 2** 준비 → Manifold를 USB로 PC에 연결 → 활성화(후보 App ID `[REDACTED]`)
4. `app.json` ↔ 포털 App ID 일치 확인(`user_app_id` = `USER_APP_ID` = `[REDACTED — APP ID]`, 코드와 동일)
5. `dji_app_ctl install`(DPK) → 설치 검증(기체 미접속 시 통과 불가 — `DjiCore_Init`가 기체 링크 대기)
6. 거부되면 → `Payload SDK-Manifold 3` 유형 앱 필요(문의 2번 답 대기)

### 입력 2 — 등록 상태 요약 (2026-10-21)

| 등록 | 상태 | 근거 |
|---|---|---|
| **PSDK (Manifold 3)** | ⚠️ **후보 자격증명만 확보** | 기존 앱 `[REDACTED — app name]`(`[REDACTED — APP ID]`)의 App Key·License를 `dji_sdk_app_info.h`에 넣고 **aarch64 빌드 통과**. 단 이 앱은 `sdk_slug=payload`(classic)이고 설명은 하드웨어 페이로드 제품 → **Manifold 3 런타임이 받아들이는지 실기 설치 단계에서 검증해야 최종 판정**(거부 시 `Payload SDK-Manifold 3` 타입 앱 필요 — 현재 계정 101로 생성 불가) |
| **MSDK (RC Plus 2)** | ❌ **포털이 101로 차단** | 아래 |

### 입력 2 — 포털 앱 생성이 `parameter invalid or blank`로 실패할 때 (2026-10-21 실측)

- 폼 제출 → `POST https://dev.dji.com/api/web/apps` → `{"code":101,"message":"parameter invalid or blank"}`.
- 폼이 보내는 값 중 `app[bundle_id]`·`app[industry]`·`app[useCase]`·`app[keyFeatures]`·`app[date]`가 비고,
  `app[agree]=false`다. **Payload SDK-Manifold 3 폼은 이 필수 항목들을 렌더링하지 않는다**(포털 측 누락).
- **선행 조치**: Developer Center → Profile → Edit Profile에서 **Company ID · Contact Name · Contact Email**
  3칸(현재 미입력)을 먼저 채운다. 앱 생성 API가 개발자 프로필을 검증하기 때문이다.
- 그 다음 My Apps → CREATE APP → App Type `Mobile SDK` → Package Name `ksi.safetyedge.rc`로 생성.

**최종 진단(2026-10-21)**: 포털의 폼 검증(`validateCreateForm`)은 통과했고
(name·bundle_id·os_type·category·other_category·description·industry·useCase·keyFeatures·date·agree 전부 채움),
`POST /api/web/apps`도 정상 전송됐는데도 서버가 101을 반환한다.
→ **계정 측 문제**. 개발자 레코드에서 다음이 남아 있다:
`real_name_status: 0`(실명 인증 미완료) · `approved_at: null`(개발자 승인 미완료) ·
`company_size: 0`(미입력) · `level/target_level: 1`(Payload 앱은 level 2로 생성됨).
→ **조치**: DJI 개발자 콘솔에서 실명 인증과 계정 정보(Company ID·Contact Name·Contact Email·기업 규모)를
확정해 저장하거나, `dev@dji.com`에 앱 생성 101을 문의한다. 계정 권한 문제이므로 우회 불가.
→ **키 없이도 되는 것**: RC 앱의 관측·기록·업로드 경로와 사내 LAN TCP 대역(가상/실 Manifold) 전부 동작한다.
   키가 있어야 열리는 것은 **DJI 측 경로(기체 명령·영상·무선 MOP 중계)**뿐이다.
  - 기존 앱 `[REDACTED — app name]`는 APP ID `[REDACTED]` · App Key·App Basic License **발급됨**
    (우리 DPK 빌드 옵션 `-DKSI_SAFETY_EDGE_DPK_USER_APP_ID=[REDACTED]`와 일치). 상태는
    `[REDACTED — portal activation/binding status]` → **Manifold 기기 연결 등록**이 따로 필요할 수 있다(포털 확인).

## 0. 준비 (15분)

```bash
bash scripts/rc_device_day.sh init        # 오늘 날짜 증거 폴더 생성
bash scripts/rc_device_day.sh preflight   # 호스트·장치·키·Manifold 점검 → preflight.txt
bash scripts/rc_device_day.sh build       # device APK + "DJI 키 주입: 있음" 확인
```

- `build`가 "DJI 키 주입: 없음"이면 멈추고 입력 2부터 처리한다.
- APK sha256·크기는 build 출력 그대로 notes.md에 기록한다.

## 1. 세션 실행 (A9 계획 §4의 10단계)

각 단계: **실행 → 화면/로그에서 볼 것 → 증거**. 실패는 고치지 말고 그대로 기록한다.

| # | 단계 | 실행 | 볼 것 (기대) | 증거 |
|---|---|---|---|---|
| 1 | 설치·기동 | `install` → `launch` → `log-start` → `facts` → `shot 01-launch` | 크래시 없음, 설정·개발 영역에 상태 표시 | preflight.txt, device-facts.txt, shot 01, logcat |
| 1b | 최소 SDK 초기화 | 앱: 설정·개발 → SDK 상태 | `MsdkPlatform`/`DjiBridgeState`가 실패 시에도 **사유 표시**(NoClassDefFound/VerifyError 원문) | `shot 01b-sdk-state`, logcat |
| 2 | MSDK 등록 | 등록 대기 → 상태 확인 | `REGISTERED` + 연결 제품 수. 실패면 오류 코드 원문 | `shot 02-registered`, logcat |
| 3 | RC↔Manifold | Manifold: `dji_app_ctl start ksi-safety-edge`(psdk-app `--mop-channel 0` 빌드) → 앱: 조종 화면 링크 연결 | 데이터 채널 open, HEARTBEAT 수신·신선도 | logcat(양쪽), `shot 03-link` |
| 4 | 임무 적용 | 앱: 임무 준비 → 동기화 → Start flight → Apply | PREPARE/APPLY 왕복·ACK, digest 검증 표시 | `shot 04-*.png` |
| 5 | 로컬 저장·재시작 | 관측 저장 확인 → `stop` → `launch` → `pull-store` | 재시작 복원, `rc-store` 존재·증가 | rc-store.tar, logcat |
| 6 | 통신 복구·프로세스 복구 분리 | 분석 프로세스는 유지하고 MOP 채널만 단절/복구. 별도 시험에서 Manifold 앱 stop→start | 전자는 로컬 처리 지속·영속 큐 회수, 후자는 새 boot/실행 세대·과거 기록 회수·과거 RUNNING 자동 복원 금지 | 양쪽 원문 로그, 영속 큐 readback, `shot 06-*` |
| 7 | 업로드(WorkManager) | 비행 종료 → 동기화 예약 | 상태 표시; Core 설정 시 COMMITTED | `shot 07-*`, Core 조회 결과, **RC→Core 대조(아래)** |
| 8 | 시계 기록 | `facts`의 date/UTC + `manifold` | RC 시계 편차를 **값 그대로** 기록(보정 전후 혼합 금지) | device-facts.txt |
| 9 | 실패 주입(선택) | `stop` 후 재기동(프로세스 사망 재현) | 복원 또는 정직한 실패 표시 | logcat |
| 10 | 마감 | `log-stop` → `pull-store` → notes.md 마감 | — | 전체 |

**토큰 저장(Keystore) 확인** — 설정·개발에서 Core 토큰 저장 → `stop` → `launch` → 필드에 그대로 복원되는지 확인.
실패 시 화면에 "보안 저장소 오류"가 표시된다(정상 = 표시 없음). 조종사 이름/소속도 같은 방식으로 재시작 후 복원을 확인한다.

**권한(첫 실행)** — 앱이 SDK 선언 위험 권한(위치·전화상태·저장소 등)을 첫 실행에서 자동 요청한다.
승인/거부 결과는 상태줄에 표시된다 — 거부 시 일부 SDK 기능이 제한될 수 있으니 결과를 캡처한다.

### 업로드 후 RC→Core 대조 (필수 증거)

"업로드 완료" 표시만으로는 RC→Core 전달을 주장하지 않는다. `pull-store`로 회수한 RC 저장소와
Core 응답을 1:1로 대조해 증거 파일로 남긴다(읽기 전용, 업로드 실행 없음).

```bash
bash scripts/rc_device_day.sh pull-store
python scripts/rc_core_correlate.py \
  --store docs/verification/evidence/<date>-a9-4/rc-store.tar \
  --core-url http://127.0.0.1:8000 --token <읽기 토큰> \
  --out docs/verification/evidence/<date>-a9-4/08-rc-core-correlation.txt
```

- `--core-url`은 서버 루트 또는 `/api/v1/edge` 전체 주소 모두 허용한다(루트만 주면 보정; 앱 설정과 동일 API).

- 대조 항목: 비행 존재·업로드 상태, 증거(존재/committed/sha256), 관측·사건 ID, 사건-증거 연결,
  의견·구조화 대응(업로드 영수증 vs Core 원문·digest·현장/비행/출처/전달 상태), 부트별 세션.
- 판정: `OK`(조회 가능한 항목 일치) / `DISCREPANCY`(미도달·모순, exit 2) /
  `INCOMPLETE`(필요 조회가 미검증·구형 Core의 대응 경로 404 등, exit 1) / 실행 오류(exit 1).
  `UNKNOWN`은 통과로 보지 않는다. pending 의견·대응은 명시적으로 표시하는 업로드 전 상태이며
  전송·수신·조치 완료를 뜻하지 않는다. 영수증이 수락을 주장하는 대응이 Core에 없으면 불일치다.
- 이 보고서는 RC 기록과 Core 응답의 일치만 뜻한다. 현장 원본의 진위·안전 판정·운영 인증은 포함하지 않는다.

### UI 실기 확인 (C67 후속 — 같은 세션에서)

- `facts`의 `wm size`/`wm density`와 시스템 inset·글꼴 배율을 **실제 값으로 기록**한다.
  1920×1200 웹 캔버스에서 Android density 2.0 또는 48px=48dp를 도출하지 않는다.
- 콕핏: 무스크롤, 글자 크기, 장갑 터치(≥48dp), 햇빛 가독성 — 스크린샷으로 남긴다.
- 문제는 notes.md "관찰"에 적는다(수정은 세션 후 — 세션 중 변경 금지).

### 영상·기록 상태 확인 (2026-10-14 후속)

- 채널 연결만으로 영상 수신/표시를 완료로 판정하지 않는다. 입력 프레임 수·마지막
  RC 수신시각은 **인코딩 입력의 사실**이며, 화면 디코딩/표시와 세그먼트 재생은 따로 확인한다.
- 입력 미수신·신호 없음·수신 지연(참고 표시 5초)을 각각 확인한다. 프레임 유입을
  멈췄을 때 상태가 1초 갱신으로 지연 표시가 되는지, 재유입 시 실제 수신으로 복구되는지 캡처한다.
  RC 수신 경과시간은 촬영시각이나 end-to-end 지연 측정이 아니다.
- 녹화 요청 직후 저장된 샘플이 0이면 **녹화 입력 대기**다. 첫 SPS/PPS 보유 키프레임
  이후에 쓰기를 시작하고, 모든 회전 세그먼트도 키프레임부터 시작하는지 재생으로 확인한다.
- 기록 중 실패한 구간이 있어도 다음 구간 기록은 재시도된다. **일부 구간 저장 실패**를
  감추지 않는지, 실패 `.tmp`가 완료 증거에 들어가지 않는지 함께 확인한다.
- 비행 후 검토에서 20건보다 많은 사건도 모두 탐색 가능한지, 원본과 의견이
  append-only로 분리되는지 확인한다. 의견에 표시하는 이름은 신원 인증이 아니다.

### 텔레메트리 스트립 확인 (2026-10-17 추가)

- 관측 화면 상단 스트립(제어 주체·비행 모드·배터리·MOP·기체 링크·위치·홈)이 실제 수신으로
  갱신되는지 확인한다. 수신이 없으면 `미수신`, 갱신이 멈추면 `지연 N초`(초 올림)로 표시되어야
  하며 0·정상값으로 대체되면 안 된다.
- 키 콜백 실수신·갱신 주기, 고도 기준(이륙점/ASL), 원시 비행 모드명, 배터리 단위·반올림을
  기록한다(`a9-msdk-integration-plan.md` §12 키 매트릭스).
- 제어 주체는 **출처 미연결 시 `미확인`이 정상**이다. 멀티컨트롤 권한 보고(단일 RC)의 존재
  여부와 값 구조만 기록하고, 추정으로 소유자를 바꾸지 않는다.

### G9/L1 통합 보강 후 필수 확인

이 표는 호스트 시험과 별개의 **실제 장비 인수 조건**이다. 실제 앱 키·장치 연결·DPK 승인 없이
모의 결과로 채우지 않는다. 모든 시험은 위 프로펠러 분리 지상 범위 안에서 수행한다.

| 확인 | 필수 관찰 | 오판 방지 |
|---|---|---|
| 실제 설치 | 선택한 RC_LOCAL 번들·불변 구역 리비전·SHADOW 규칙 수와 실제 엔진 설치 영수증 대조 | PREPARE/ACK만으로 APPLIED·분석 중 판정 금지 |
| 명시 시작 | 적용 후 시작 전 처리 상태, 시작 후 실제 입력·추론·처리 수 증가 확인 | 영상 수신이나 틱 시도 수를 성공 추론으로 대체 금지 |
| 명령 경합 | 같은 처리 주기 사이 START→STOP, STOP→START, APPLY→START 및 중복 START | 마지막 요청 의도와 실행 상태 일치; 중복 요청으로 새 작업 생성 금지 |
| MOP만 단절 | 기체·Manifold 전원과 로컬 영상 입력은 유지. 순서 있는 관측·상태 원문이 영속 큐에 증가하는지 확인 | 프로세스를 껐다 켜는 시험으로 단절 중 관측 지속을 입증하지 않음 |
| MOP 재접속 | 대기 원문의 msg_id·boot·채널·seq·digest와 RC 영속 저장 ACK 대조 | 재접속 후 새 ID로 다시 만든 결과를 과거 회수라고 하지 않음 |
| Manifold 프로세스 재시작 | 새 boot/runtime 세대, 이전 큐의 원래 ID/출처 보존, 새 명시 시작 전 미실행 | 호스트의 명시적 Reboot() 함수 시험과 구분 |
| 입력 장애 | 지원되는 별도 입력 제어 절차로 영상 미수신·추론 실패·회복을 각각 기록. 제어 절차가 없으면 미수행 | RC 표시 영상 해제가 Manifold 영상 입력 단절과 같다고 가정하지 않음; 0건≠분석 미수신 |
| 실제 모델 | 실제 로드 엔진 digest·모델 ID/프로필·관측 원문의 digest 대조 | 빈 digest나 시험 상수로 실제 추론 출처를 대체하지 않음 |
| 마감 | 실제 STOPPED 확인 또는 명시적 RC 로컬 마감/원격 중지 미확인, 영상 마감·구간 연계·마감 원장 저장 후 업로드 | 미확인 Edge 세션이 남은 채 새 현장·정책으로 자동 전환 금지 |
| 보존 대조 | 번들 원문·실행/장애 이력·관측 및 공백·대응(0건 마감 포함)·영상/증거의 ID와 해시·커밋 영수증 대조 | manifest에 나열된 파일 COMMITTED만으로 5묶음·L1 성공을 추정하지 않음 |
| 늦은 대응 | 증거 COMMITTED 뒤 확인/보류/전달 요청 추가 → 자동 sync → Core 비행별 대응 조회 및 RC 영수증 저장 | 서버 보존을 현장 담당자 전달/수신/조치 완료로 표시하지 않음 |
| 저장소 장애 격리 | 격리된 시험 저장소에서만 counter/쓰기 장애를 주입해 저장 불가 배너·새 KSI 작업/ACK 금지와 기본 영상·카메라 화면 접근을 별도 확인 | 운영 counter·원본 기록을 고치거나 지우지 않는다. 격리 절차가 없으면 실기 미수행으로 남기고 호스트 실패 주입 시험과 구분 |

장비 원문·캡처·실행 버전과 함께 PASS/FAIL/미수행을 기록한다. 임의의 지도 위치·거리 경고를 켜거나
SHADOW 결과를 ACTIVE로 올리는 시험은 이 지상 세션에 포함하지 않는다.

### 자동 관측(1단계) 실기 확인 — 2026-10-23 추가

호스트 종단(12틱 → 평가 12건 → 원본·평가 67개 봉인 → Core 영수증 → 감독 입력)은 확인됐다.
아래는 **기체·Manifold에서 같은 코드가 실제로 도는지**를 확인하는 항목이며, 아직 수행 전이다.
호스트 aarch64에서 PSDK 3.16.0 정적 라이브러리로 컴파일·링크와 34/34 시험은 통과했지만,
이는 실기 실행 증거가 아니다.

빌드 시 신뢰 설치자 입력(비밀 아님):

```sh
-DKSI_SAFETY_EDGE_AUTONOMY_EVIDENCE_DIR=/open_app/ksi-safety-edge/autonomy
```

- 비워 두면 자동 관측은 **꺼진 상태**다. DPK는 무인자로 시작하므로 이 값이 없으면 실기에서 켜지지 않는다.
  켜져 있어도 평가는 SHADOW이며 Incident/Alert/사람 응답/카메라/비행 명령을 만들지 않는다.
- 증거 디렉터리는 `--state-dir`와 분리한다. 봉인 revision·평가·전후 원본은 이쪽에만 쌓인다.

| 확인 | 필수 관찰 | 오판 방지 |
|---|---|---|
| 시작 전 | APPLY+START 전에는 자동 관측 틱·평가·봉인이 0. 게이트 닫힘 유지 | 프로세스 기동만으로 관측 시작으로 표시하지 않음 |
| 실제 처리 | START 후 `processed_frame_count` 증가와 입력 상태(영상·텔레메트리·엔진)를 실제 수신 기준으로 기록 | 틱 시도 수를 성공 추론으로 대체하지 않음 |
| 평가 | 마감 revision에 SHADOW 평가 artifact가 1건 이상. ASSESSED 또는 UNDETERMINED+사유가 원문과 일치 | 평가 완료를 위험 확정·경보로 읽지 않음 |
| 전후 원본 | 트리거 전후 프레임과 무검출 프레임이 원본 바이트로 보존되고, 공백은 `prehistory_gap`·`input_gap`·`deadline_exceeded_post_incomplete`·`session_stop_post_incomplete`로 표시 | 0건 검출을 안전으로 표시하지 않음 |
| 수동 카메라 | 사람이 카메라를 잡아도 분석·보존 지속. `camera_manual_override=true`, 공간 결론은 보류 | 자동 조절 권한 상실을 분석 중단으로 표시하지 않음 |
| 저장 실패 | 저장 거부가 시스템 경고·DEGRADED로 드러나고 원본 유실을 숨기지 않음 | 위험 경보 ACTIVE와 시스템 경고를 같은 승인으로 취급하지 않음 |
| 보존 분리 | 로컬 SEALED, RC 수신, Core COMMITTED를 각각 실제 영수증으로만 표시 | 로컬 봉인을 RC 수신·중앙 보관으로 추정하지 않음 |

장비 원문·캡처·실행 버전과 함께 PASS/FAIL/미수행을 기록한다. 이 표의 어떤 항목도
규칙별 ACTIVE 경보 승인이나 위험 판정 정확도 인수를 대신하지 않는다.

## 2. 끝난 뒤

```bash
bash scripts/rc_device_day.sh log-stop
bash scripts/rc_device_day.sh pull-store
```

1. `notes.md` 마감 — 단계 표의 결과·증거 파일 이름 채우기(실패 포함).
2. **스크럽 체크**: 로그·스크린샷에 DJI 키·Core 토큰·기기 식별자·사설 IP가 없는지(키는 preflight/build에서 길이만 출력됨).
3. 증거 폴더 커밋 여부 결정(필요 시 `docs/verification/evidence/<date>-a9-4/` 그대로).
   실제 기기 원문·로그·캡처가 포함된 증거는 **비공개(nonpublic)** 로 취급하며, 공유본에는 파일명·판정만 남긴다.
4. 남은 실패 항목은 `a9-msdk-integration-plan.md`에 잔여로 추가.

## 참고

- 이 런북은 새 계약·권한을 만들지 않는다. 앱 설치·기동과 증거 수집만 한다.
- `scripts/rc_device_day.sh`는 제품 코드를 바꾸지 않으며, 비밀 값을 출력하지 않는다.
