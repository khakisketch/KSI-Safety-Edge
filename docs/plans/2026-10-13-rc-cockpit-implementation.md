# RC 콕핏 UI 구현 — 매핑·범위 기록 (2026-10-13)

승인된 디자인(`design/rc-pilot-shell/field-shell.html`, sha `cdb72c9b…`)을
`android-rc` 앱에 반영하는 첫 증분(C67)의 기록.

## 범위

- **5영역 내비게이션**(좌측 레일): 조종(관측) · 임무 준비 · 비행 기록(검토) · 증거·동기화 · 설정·개발.
- **조종(관측) = 무스크롤 콕핏**:
  - 좌: 운용 상태 레일 — B-1 계약 8항목, 심각도 색(■ 문제/▲ 주의/· 정보)+굵은 라벨
  - 중앙: 라이브 뷰(SurfaceView) + 상단 영상 컨트롤(연결/해제) + 하단 MOP 링크 조치 도크(연결/해제/재전송)
  - 우: MOP 전송 · AI 관측 피드 · 사건 목록
- **끊김 상태 Connect 강조**(디자인 round-3 규칙): 링크 `DISCONNECTED`일 때만 문제색,
  그 외 중립.
- 데모 벤치·시간 고지는 설정·개발 영역으로 이동(격리 유지). 기존 섹션 제목의 번호 접두는 사용 중단.

## 디자인 → Android 매핑 규칙

| 항목 | 규칙 | 근거 |
|---|---|---|
| 기하(폭·높이·패딩) | design px ÷ 2 → dp | RC 패널 1920×1200·density 2.0 가정 → 960×600dp |
| 글자 | design px 값 ≈ sp 유지 | 실기 가독성 우선(행 높이에 여유) — 기기에서 재보정 가능 |
| 색 | OKLCH → sRGB 변환 | `res/values/colors.xml` (problem/attention/info/bg/surface/border 등) |
| 터치 | 주요 조작 ≥ 48dp | 장갑 조작 |

## 구현 파일

- `res/values/colors.xml`(신규) · `res/values/strings.xml`(추가: nav/page/cockpit)
- `res/layout/activity_main.xml` — 전면 재작성. **기존 id 전부 보존**(재배치만) →
  MainActivity 와이어링 무변경.
- `MainActivity.kt` — `navTargets`/`showPage`(영역 전환) · `refreshTopBar`(SIMULATED 태그,
  숨김 금지) · `refreshConnectEmphasis`(round-3 규칙) · `tvOpsStatus` 스팬 렌더(심각도 색+굵게).

불변: 정직 문구, 상태 계약 의미, 기존 리스너/동작, 새 권한·계약 없음.

## 검증

- 호스트: compile(실기/벤치) + 유닛 테스트 — WM 6건 aarch64 스킵(CI x86_64가 실실행).
- **실기 게이트(A9-4)**: RC Plus 2 실제 렌더(밀도 가정·무스크롤), 햇빛 가독성, 장갑 터치.
  이 환경에는 Android 렌더 경로가 없다(에뮬레이터 없음) — 시각 판정 불가.
- 미구현(의도): 다크 모드(디자인은 지원), 영상 표시 검증은 실기.

## 후속

1. 실기 확인에서 밀도/글자 크기 재보정(한 곳: dimens/strings).
2. 다크 모드(디자인 토큰 이미 변환 가능).
3. 디자인 쪽에서 이 매핑(특히 글자 px→sp 유지) 검토 필요 시 Design Specialist 라운드.
