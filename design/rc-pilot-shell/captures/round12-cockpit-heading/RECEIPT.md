# round12-cockpit-heading — 검증 영수증

Date: 2026-10-15 · Status: **미커밋 작업본** · 제품 상태: UI 구현/HTML 시연 검증, 실기 인수 아님

## 대상과 범위

- 시작 HEAD: `2fbd36b` · HTML 최종 SHA-256: `237e044ac40de56c2e6891fd8ccb3e1d797148e4eb851f96f48a55e354fe0ac7`.
- 대상: `http://127.0.0.1:8765/field-shell.html` 및 동일한 의미의 Android RC 헤더 문구/레이아웃.
- HTML 헤더의 KSI 관측 정체성, 영상 입력 상태, `[SIMULATED]` provenance, DJI Pilot 2 비행 조종 권한만 구조화했다. 새 버튼/제어는 없다.
- B-1 상태 사실·순서·심각도·행동, 영상 freshness 의미, 물리 controls, API/스키마/권한/데이터/업무 규칙은 변경하지 않았다.
- 편집 전에도 960×600과 1920×1200에서 정상 최근 수신/미연결 렌더를 확인하고, round10·round11 캡처와 비교했다. 기존 한 줄은 화면 제목과 상태/권한 metadata가 구분되지 않는 형태였다.

## HTML 렌더 확인

- Native Browser는 이 세션에 연결되지 않아 Playwright CLI 세션 `heading-ux-correction`으로 확인했다. 기존 로컬 미리보기 서버의 실제 제공 HTML을 렌더했다.
- 상태 스위처는 기존 화면 내 demo/scenario handler를 Playwright에서 호출했다. `입력 최근 수신`은 시연 상태이며, 실제 카메라/디코더/기체 연결을 뜻하지 않는다.
- 캡처 (`screens/`):
  - 960×600 RC 유효 크기: `960x600-normal-recent.png`, `960x600-disconnected.png`, `960x600-problem-recent.png`.
  - 1920×1200 wide: `1920x1200-normal-recent.png`, `1920x1200-disconnected.png`, `1920x1200-problem-recent.png`.
  - 390×844 보조: `390x844-normal-recent.png`, `390x844-disconnected.png`, `390x844-problem-recent.png`.
  - 후속 좁은 폭 점검: `390x844-problem-alert-fullwidth.png` — 문제 알림을 전체 컨텐츠 폭으로 고쳐 `실패` 단어가 음절 단위로 쪼개지지 않게 했다.
- 모든 캡처에서 KSI 제목, 현재 영상 입력 상태, `[SIMULATED]`, `기체 비행 조종 / DJI Pilot 2`를 확인했다. 미연결 상태는 `미연결`로 표시되고 live input처럼 읽히지 않는다.
- 수평 overflow: 960×600 = 0px, 1920×1200 = 0px, 390×844 = 0px. 960 문제 상태에서 헤더 그룹 `[65, 11, 449, 56]`, 문제 알림 `[772, 11, 175, 28]`로 겹치지 않았다. 390 문제 상태에서는 헤더 아래 전체 컨텐츠 폭 `[63, 151, 318, 25]`에 표시해 짧은 알림도 어색하게 단어/음절 분할되지 않게 했다. 최종 상태는 실제 브라우저에서 재캡처했다.
- Focus mode spot check: 390×844에서 헤더 계층이 계속 보이고 수평 overflow가 없었다. Browser console: messages 0, errors 0, warnings 0.
- 실제 브라우저 마우스·물리 터치/스와이프는 검증하지 않았다. 시연 상태는 기존 handler를 자동화해 만들었다.
- Impeccable detector: final HTML 기준 exit 2 (quality warning/advisory 있음). round11과 같은 기존 11.2px text warning 3건 및 순서형 단계 라벨 advisory 3건이며 이번 헤더에서 추가된 warning은 없다. 상세는 `checks/impeccable-detector-final.json`.
- Axe는 이번 좁은 변경에서는 재실행하지 않았다. 이전 round11 axe 결과를 이번 HTML 변경의 검사로 간주하지 않는다.

## Android 확인

- `checks/android-xml-structure.json`: layout/strings XML 파싱 성공, 새 리소스 참조 누락 0, 기존 Button/Spinner/EditText 44개 중 48dp 미만 0. 기존 `tvCockpitSim`/`tvFlightBoundary` ID는 유지했다.
- `bash scripts/android_host_tests.sh`: `BUILD SUCCESSFUL`.
- `:rc-app:lint`와 `:rc-app:assembleDebug` 시도는 SDK 미설치로 차단됐다. `/usr/lib/android-sdk`에는 `android-34` platform이 없고 `android-rc/local.properties`도 없다. local.properties는 읽거나 만들지 않았다. 따라서 Android 실제 렌더 및 APK assemble은 검증하지 않았다.
- HTML 캡처는 Android RC 실제 화면이 아니며, RC Plus 2 실기·물리 조작·장갑·햇빛 판독성은 A9-4가 필요하다.

## 보존/재현

- 새 캡처만 `captures/round12-cockpit-heading/`에 추가했다. 이전 증거 폴더와 `.impeccable/` 사용자 데이터는 수정/삭제하지 않았다.
- `checks/capture-sha256.txt`에 최종 HTML 및 10개 PNG 캡처 해시가 있다. 변경은 커밋/푸시하지 않았다.
