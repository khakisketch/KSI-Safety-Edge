# A9-4 실기 하루 — 실행 런북 (RC 지상 세션)

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
| 6 | 통신 복구 | Manifold 앱 stop→start(또는 채널 단절) → 복구 | 동일 msg_id 재전송·누락 복구, ACK | logcat, `shot 06-*` |
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
  의견(업로드 영수증 vs Core 반영), 부트별 세션.
- 판정: `OK`(전부 일치) / `DISCREPANCY`(미도달·모순 있음 — 그대로 notes.md에 기록) / 실행 오류.
  pending 의견은 실패가 아니다(업로드 전 상태).
- 이 보고서는 RC 기록과 Core 응답의 일치만 뜻한다. 현장 원본의 진위·안전 판정·운영 인증은 포함하지 않는다.

### UI 실기 확인 (C67 후속 — 같은 세션에서)

- `facts`의 `wm size`/`wm density`로 **밀도 가정(1920×1200 · density 2.0) 검증**.
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

## 2. 끝난 뒤

```bash
bash scripts/rc_device_day.sh log-stop
bash scripts/rc_device_day.sh pull-store
```

1. `notes.md` 마감 — 단계 표의 결과·증거 파일 이름 채우기(실패 포함).
2. **스크럽 체크**: 로그·스크린샷에 DJI 키·Core 토큰이 없는지(키는 preflight/build에서 길이만 출력됨).
3. 증거 폴더 커밋 여부 결정(필요 시 `docs/verification/evidence/<date>-a9-4/` 그대로).
4. 남은 실패 항목은 `a9-msdk-integration-plan.md`에 잔여로 추가.

## 참고

- 이 런북은 새 계약·권한을 만들지 않는다. 앱 설치·기동과 증거 수집만 한다.
- `scripts/rc_device_day.sh`는 제품 코드를 바꾸지 않으며, 비밀 값을 출력하지 않는다.
