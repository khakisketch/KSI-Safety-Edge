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
| 7 | 업로드(WorkManager) | 비행 종료 → 동기화 예약 | 상태 표시; Core 설정 시 COMMITTED | `shot 07-*`, Core 조회 결과 |
| 8 | 시계 기록 | `facts`의 date/UTC + `manifold` | RC 시계 편차를 **값 그대로** 기록(보정 전후 혼합 금지) | device-facts.txt |
| 9 | 실패 주입(선택) | `stop` 후 재기동(프로세스 사망 재현) | 복원 또는 정직한 실패 표시 | logcat |
| 10 | 마감 | `log-stop` → `pull-store` → notes.md 마감 | — | 전체 |

### UI 실기 확인 (C67 후속 — 같은 세션에서)

- `facts`의 `wm size`/`wm density`로 **밀도 가정(1920×1200 · density 2.0) 검증**.
- 콕핏: 무스크롤, 글자 크기, 장갑 터치(≥48dp), 햇빛 가독성 — 스크린샷으로 남긴다.
- 문제는 notes.md "관찰"에 적는다(수정은 세션 후 — 세션 중 변경 금지).

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
