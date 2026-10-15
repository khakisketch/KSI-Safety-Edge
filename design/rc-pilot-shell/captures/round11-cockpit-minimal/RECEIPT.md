# round11-cockpit-minimal — 검증 영수증

Date: 2026-10-15 · Status: **미커밋 작업본** · 제품 상태: UI 구현/브라우저·호스트 검증, 실기 인수 아님

## 기준·범위

- 최종 `design/rc-pilot-shell/field-shell.html` SHA-256: `ed38418c460bf80df235bf680b92ec11832662ec8d2da3e4a47fdc9f17176390`.
- 변경 범위: HTML 프로토타입, `android-rc`의 `MainActivity.kt`, `activity_main.xml`, `strings.xml`, 라운드 기록과 검증 증거.
- B-1 상태 사실·순서·심각도·조치, 스키마/API/권한/데이터 계약, DJI 비행 조종 경계를 변경하지 않았다. 신규 telemetry 또는 기체 비행 제어 없음.

## 변경 결과

- 조종 화면의 영상은 edge-to-edge를 유지한다. 상단은 `라이브 뷰`, 입력 상태, `[SIMULATED]`, `DJI Pilot 2 · 조종`, 문제/주의 필만 보여 주며 조작 버튼은 없다.
- 기존 B-1 8개 사실을 순서대로 3개 그룹으로 영상 바깥에 표시한다. 전체 사실·출처·조치는 하단 disclosure로 연다. 문제 `■`와 주의 `▲` 구분은 유지했다.
- 영상 연결/해제는 시스템 진단으로 옮겼다. 영상 해제 확인에는 이후 프레임을 받지 못해 자료가 불완전해질 수 있고 KSI 로컬 기록·기체 비행 조종은 계속된다는 점을 쓴다.
- 초점 보기와 상태 상세는 항상 보이는 영상 조작부에서 분리했다. KSI 로컬 기록 종료 버튼은 활성 기록이 있을 때만 표시하며, 기록이 없으면 종료할 세션이 없음을 설명한다. 확인 문구와 시연 피드백은 기체 비행을 끝내지 않음을 분명히 한다.
- Android RC 화면도 같은 정보 구조·문구를 적용했다. 영상 해제는 기록 중 확인을 받고, 기록 종료 버튼은 활성 로컬 세션이 없으면 숨긴다.

## 브라우저 검증

- 대상: `http://127.0.0.1:8765/field-shell.html`, Playwright CLI 세션 `round11-cockpit-minimal`. Native Browser는 연결되지 않아 Playwright CLI를 사용했다.
- 최종 destination matrix: `screens/`에 5개 화면 × 4 viewport = **20장** (`390×844`, `960×600`, `1024×768`, `1280×800`). 각 실행에서 활성 화면 1개, 수평 overflow 0.
- B-1 상태: `scenarios/`에 5개 (`normal`, `no-flight`, `observation-late`, `problem`, `unknown`) × 4 viewport = **20장**. 8개 사실 순서 `0..7`과 문제/주의 표식 유지. 390px에서는 5상태 모두 빠른 그룹 라벨/값의 client 영역 잘림 0.
- 영상 입력 상태: `videos/`에 미연결·연결 중·신호 없음·프레임 미수신·최근·지연·수신 시각 미확인 **7장**(960×600). 영상 위 조작 컨트롤은 0.
- Axe-core **4.12.1**, 총 **51회**(destination 20 + B-1 20 + 영상 7 + 확인창 2 + 상세 열림 2): **위반 0**. 자동 대비 판정은 cockpit gradient 때문에 `color-contrast` incomplete로 남았으며, 실패로 계산하지 않았다. 세부는 `checks/axe-incomplete-review.json`.
- Browser console errors 0, page errors 0; destination/state/details 화면 수평 overflow 0. 검사 원본은 `checks/browser-verification.json`.
- 동작 확인: 기록 종료 취소/확인 모두 실제 세션·기체 상태를 바꾸지 않는 시뮬레이션 피드백. 비행 없는 상태에서 cockpit 종료 버튼은 숨겨지고 임무 종료 버튼은 비활성. 기록 중 영상 해제 취소는 최근 입력 상태를 유지하고, 확인은 입력만 미연결 예시로 바꾸며 KSI 기록은 유지.
- 초점 보기 on/off, disclosure의 8개 사실·순서, 키보드 Escape 및 포커스 복귀, Enter/Space 조작을 확인했다. `checks/keyboard-interactions.json` 참조.
- 상세 disclosure는 `960×600`에서 455px 높이의 내부 스크롤로 동작했다. 마우스 휠로 아래까지 이동해 마지막 B-1 사실을 확인했고, 960×600·390×844 모두 기록 종료 버튼과 겹치지 않으며 수평 overflow가 없다. `checks/details-scroll.json` 참조.
- 최종 PNG와 contact sheet를 열어 육안 검토했다. `screens/`, `scenarios/`, `videos/`, `interactions/`, `visuals/matrix-*.png` 및 `visuals/scenarios-*.png` 참조. `visuals/initial-960x600.png`는 초기 반복용 캡처이며 최종 화면 증거가 아니다.

## Android 검증

- `checks/android-xml-structure.json`: 리소스 XML 8개 파싱 오류 0, `activity_main.xml`의 Button/Spinner/EditText 44개 중 48dp 미만 0, 오류 배너 minHeight 48dp.
- `bash scripts/android_host_tests.sh`: 최종 Android XML 수정 후 `BUILD SUCCESSFUL`. XML 결과는 48개 suite 파일, 214 test executions, 실패 0·오류 0. aarch64에서 WorkManager/Robolectric 6개 케이스가 bench/device 각 variant에서 skip(12 executions); x86_64 CI가 해당 테스트의 실행 정본이다. 상세 `checks/android-host-tests.txt`.
- Android 실제 기기 렌더링·물리 터치/장갑·햇빛 판독성은 확인하지 않았다. A9-4 실기 검증 필요.

## 잔여 품질 참고·무결성

- 최종 Impeccable detector 출력은 `checks/impeccable-detector.json`; exit 2는 실패가 아니라 경고/권고가 남았음을 뜻한다. 11.2px compact mobile text 경고 3건과 업무 순서를 설명하는 번호형 phase label 권고 3건이 남았다. 번호 표기는 임무/검토 순서를 전달하므로 유지했다. 390px에서의 물리 판독성은 실기에서 확인해야 한다.
- `checks/capture-sha256.txt`는 이 라운드 PNG와 최종 HTML의 해시 목록이다. `git diff --check` 통과. 커밋하지 않았다.
