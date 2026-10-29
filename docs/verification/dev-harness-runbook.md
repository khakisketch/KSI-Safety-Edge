# 개발 하네스 런북 (이 환경)

작성: 2026-10-13 · 지침 정합화: 2026-10-25 · 제품: KSI Safety Edge (L1)
목적: 디자인·테스트 워크플로의 재현 가능한 실행 경로. 도구 사용 성공과 디자인/제품 인수를 구별한다.

## 환경 사실 (실측)

- 호스트: Linux aarch64. Android SDK `~/Android/sdk`, x86_64 qemu sysroot `~/Android/sysroot/root`.
- 브라우저 렌더: Playwright 세션 도구. **`file://` 차단** → 로컬 정적 서버로 렌더한다.
- 디자인 산출물 정본은 저장소 `design/rc-pilot-shell/`과 native
  `RcWorkspaceLayout.kt`/`MainActivity.kt`다. 웹 HTML/mockup은 시뮬레이션이며 native 정본이 아니다.
  과거 도구·라운드 기록은 `design/rc-pilot-shell/history/`의 이력이다.

## 디자인 루프

전체 화면 판단 기준은 [RC 디자인 검토](rc-design-review.md)다. 아래는 실행·출처 확인 경로다.

1. **대상 구분**: 실제 Android 구성은
   `android-rc/rc-app/src/main/java/ksi/safetyedge/rc/RcWorkspaceLayout.kt`, 상태·행동 연결은 `MainActivity.kt`다.
   `field-shell.html`과 다른 HTML은 웹 시뮬레이션 또는 캡처 검토 도구이며 native 정본이 아니다.
2. **맥락 선택**: `PRODUCT.md`는 제품 요약, `DESIGN.md`는 현행 native 체계다. 설치된 Impeccable의
   `surface-brief read <실제 대상 경로>`로 대상 브리프를 읽는다. 후보 목록만 나오거나 `primaryTarget: null`이면
   브리프 연결 실패다. 현행 `surface-brief write` 경로로 연결하며 라운드 파일을 수동으로 최신이라고 가정하지 않는다.
3. **Android 실행·캡처**: 소스 변경 후 bench/device APK를 빌드한다. 장비 접근은 해당 작업의 승인을 따르며
   이 런북이 설치·조작 권한을 부여하지 않는다. 승인된 경우 명시적 serial의 `adb -s "$RC_SERIAL"`로 대상 패키지를
   확인하고 캡처한다. native 캡처는 `exec-out screencap -p`, 계층/bounds는 `uiautomator dump`를 사용한다.
   운용 앱 자료는 백업·해시 대조하며 clear/uninstall·합성 시드·실제 명령을 시각 검증의 편의로 실행하지 않는다.
4. **Android 증거**: 실제 가용 창·density·font scale·주야간·package·설치 APK SHA·소스 리비전과
   초기 scroll0/과업 상태·상호작용 결과를 연결한다. 1920×1200px는 이 RC의 물리 캡처 크기이지 CSS 기준이 아니다.
   UI 렌더, 표시 모델, 실제 어댑터 배선, 실기 기능 검증을 따로 보고한다. 권한이 없으면 기존 증거의 버전/범위만 관찰한다.
5. **웹만 검토할 때**: `bash scripts/design_preview_server.sh` → `http://127.0.0.1:8765/field-shell.html`.
   해당 웹 대상의 viewport·원본 해시·주야간·초기 렌더·상태·콘솔 오류·실제 조작을 확인한다.
   웹 기하 검사/DOM 무스크롤/축소 fit을 native 가독성·터치·영상 연결 증거로 쓰지 않는다.
   `field-shell` 표기·상태 회귀는 아래 두 검사로 실행한다. 이 환경의 전역 Playwright 경로를 쓰며,
   기존 외부 URL 환경 변수를 지워 루프백 정적 화면만 확인한다. 렌더·인수 범위와 남은 한계는
   작업 보고 원본은 공개 인계에서 제외하고 비공개 보존한다.

   ```bash
   env -u FIELD_SHELL_URL -u FIELD_SHELL_HEADFUL NODE_PATH="$(npm root -g)/@playwright/cli/node_modules" node design/rc-pilot-shell/field-shell-verify.cjs
   NODE_PATH="$(npm root -g)/@playwright/cli/node_modules" node design/rc-pilot-shell/field-shell-visible-copy-verify.cjs
   ```

6. **소유·검토**: 현행 전역 역할 분리를 따른다. UI 판단은 Design, 기술 배선은 Engineer, 통합·수락은 Build의 책임이다.
   독립 최종 Critic은 새 세션이며, 같은 세션의 수정 확인은 독립 최종 리뷰로 부르지 않는다.
   스킬 호출 횟수·검사 수·Critic 무지적은 전체 화면의 디자인 인수를 대신하지 않는다.
7. **보존**: B-1 사실·심각도·행동·정직 문구와 필요한 기능 도달성은 유지한다.
   모든 항목을 같은 크기의 상시 블록으로 놓으라는 뜻은 아니다. 바꿀 수 있는 배치·정보 밀도와 불변 의미를 구별한다.

## 테스트 루프 (로컬 CI 미러)

- 전체: `bash scripts/run_local_verification.sh` (python → edge → android)
- 개별: `scripts/run_all_tests.sh`(python 86) · `cmake -S edge -B edge/build && ctest`(edge) ·
  `scripts/android_host_tests.sh`(android — rc-ports/rc-core/rc-msdk/rc-app bench+device)
- 실기 하루(RC 지상 세션): `scripts/rc_device_day.sh` + `docs/verification/a9-4-device-day-runbook.md`
- 실기 대조(업로드 후): `scripts/rc_core_correlate.py` — RC pull 저장소 ↔ Core 읽기 응답 1:1(A9-4 런북 §업로드 후 대조)
- 알려진 한계: Robolectric/WorkManager 6건은 aarch64에서 SKIP(사유 표기). x86_64 CI
  (`.github/workflows/ci.yml`)가 실실행한다 — CI가 정본 경로.
- aapt2는 x86_64 바이너리이므로 `QEMU_LD_PREFIX`가 필수다(스크립트가 설정).
- 에뮬레이터는 aarch64 호스트에서 불가(2026-10-26 확인): Google이 linux_aarch64용
  에뮬레이터를 배포하지 않는다(x86_64·mac arm64만). 실기 렌더는 RC 지상 세션 또는
  클라우드 디바이스팜에서만 검증한다. 호스트 대체 수단으로 주장하지 않는다.

## Core 데이터 점검(/review) — 개발자용

서비스 블루프린트: `docs/specs/2026-10-13-service-blueprint.md`.
화면은 Core의 목록·상세·증거 조회를 돕는 개발자 진단 도구다. **RC 업로드나 RC→Core 전달을 실행·증명하지 않는다.**
각 조회 상태는 API 응답을 받은 사실만 뜻한다. 서버 설정 경로가 `core/var/review-demo.db`와
`core/var/review-demo-objects`의 canonical 쌍과 일치할 때만 `DEMO FIXTURE`로 표시한다.
그 외는 이름이 같더라도 `출처 미확인`이며, 이 표식도 개별 레코드의 원본을 인증하지 않는다.
Core origin만으로 실제 데이터라고 판단하지 않는다.

- 시드(데모 전용 저장소 — 기본 DB `core/var/ksi_core.db` 미접촉, 재실행 = 초기화):
  `python scripts/review_demo_seed.py` — 3개 비행: **정상**(사건·증거·의견 이력) ·
  **사건 0건** · **PARTIAL**(누락 artifact).
- 실행:

  ```bash
  KSI_DB_URL=sqlite:///$PWD/core/var/review-demo.db \
  KSI_OBJECT_ROOT=$PWD/core/var/review-demo-objects \
  .venv/bin/uvicorn ksi_core.app:create_app --factory --port 8000
  ```

- 접속: `http://127.0.0.1:8000/review` — 조회 `tok-orgA-agent` · 고급 개발 write test `tok-orgA-rc`
- 권장 점검: 목록 조회 → 정상 비행 상세에서 사건·증거 응답 확인 → PARTIAL/사건 0건 비행에서 실제 상태와
  다음 진단 확인. 증거 API 0건은 증거 확인 완료가 아니며 사건 0건은 안전 판정이 아니다.
- A-2 기록/취소는 화면 아래의 고급 개발용 write-path에 분리되어 있다. **실행하면 현재 연결된 Core에 실제
  append-only 기록을 추가한다.** 데모 DB에서만 시험하고 운영 검토 행위로 사용하지 않는다.
- 주의: dev 토큰(운영 인증 아님), tailnet 접근은 보는 기기의 Tailscale 연결 필요. 기본 저장소는 건드리지 않는다.

## 진입 경로 (목업/데모/실기 구분)

| 산출물 | 정체 | 접근 | 열기 전 확인 |
|---|---|---|---|
| `design/rc-pilot-shell/field-shell.html` | 브라우저 디자인 모형 | `:8765/field-shell.html` | 기기·서버와 분리된 화면. 입력·검증 출처는 작업 보고서 확인 |
| 과거 독립 디자인 검토 자료 | 디자인 이력 | 공개 인계 제외 | 원본·증거는 비공개 보존하며 재구성 또는 인수를 뜻하지 않음 |
| `/review` Core 데이터 점검 | Core 조회 API 동작(출처 식별은 제한적) | `:8000/review` (tailnet `:8800`) | canonical demo 경로 쌍만 DEMO FIXTURE, 나머지는 출처 미확인. 원격은 Tailscale 필요 |
| `android-rc` 앱 | 실제 native 구현; UI 렌더/실기 기능 범위는 최신 검증 기록으로 구별 | 승인된 실기/기기 | package·APK·자료 보존·해당 작업 승인·A9-4 게이트 |

- **공개 Funnel(:8443 등)에 이 화면을 매핑하지 않는다** — dev 쓰기 토큰과 Core 진단 데이터 노출 위험.
- 원격에서 안 열리면: 보는 기기 Tailscale 연결 → 아래 serve 매핑 상태 순서로 확인.

## tailnet 접속 (dev viewing — 임시)

- 디자인 셸: `https://dgx-spark-1.tailda9552.ts.net:8765/field-shell.html`
  (기존 serve 매핑 → `127.0.0.1:8765`)
- 검증 콘솔: `https://dgx-spark-1.tailda9552.ts.net:8800/review`
  (매핑: `sudo tailscale serve --bg --https=8800 http://127.0.0.1:8000`)
- **Funnel 아님 — tailnet 전용.** dev 토큰이며, 로컬 서버가 살아 있는 동안만 유효(임시).
- RC Plus 2(`dji-rc-plus-2`)가 tailnet에 있어 기기 브라우저에서 셸을 바로 열 수 있음
  (네이티브 앱 렌더가 아니라 브라우저 목업 — 실기 검증은 A9-4).

## 정본·동기화 규칙

- 문서 지도(정본 진입점): `AGENTS.md` — 현행/과거/보존 구분과 상태 용어를 따른다.
- 산출물 정본은 저장소. 외부 도구 사본은 이력일 뿐이며, 반영 시 해시 대조를 기록한다.
- 작업 기록: 계획·런북은 `docs/`, 디자인 산출물·영수증은 `design/`, 검증 증거는
  `docs/verification/evidence/`. `.opencode/working-state.md`는 단기 상태(≤80줄, Next ≤3).
