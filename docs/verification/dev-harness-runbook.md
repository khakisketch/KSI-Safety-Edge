# 개발 하네스 런북 (이 환경)

작성: 2026-10-13 · 제품: KSI Safety Edge (L1) · 목적: 디자인·테스트 워크플로의 재현 가능한 실행 경로

## 환경 사실 (실측)

- 호스트: Linux aarch64. Android SDK `~/Android/sdk`, x86_64 qemu sysroot `~/Android/sysroot/root`.
- 브라우저 렌더: Playwright 세션 도구. **`file://` 차단** → 로컬 정적 서버로 렌더한다.
- OpenDesign MCP 도구는 이 하네스에서 더 이상 사용하지 않는다. 디자인 산출물 정본은 저장소
  `design/rc-pilot-shell/`이며, 과거 OpenDesign 프로젝트는 이력 보관용이다.

## 디자인 루프

1. **정본**: `design/rc-pilot-shell/field-shell.html` (단일 자체완결 HTML).
2. **렌더**: `bash scripts/design_preview_server.sh` → `http://127.0.0.1:8765/field-shell.html`.
3. **캡처(필수 절차)**: 뷰포트 1920×1200(css scale), 상태 —
   관측 정상/문제/비행없음 + 임무 준비·비행 기록·증거·동기화·설정 + dark.
   - 저장: `design/rc-pilot-shell/captures/<round>/` (Playwright `browser_take_screenshot`).
   - **영수증**: 리비전 sha256 + 뷰포트 + 캡처 해시 + 무스크롤 실측(`scrollHeight == 1200`) +
     콘솔 오류 0. 시각 변경은 이 영수증 없이 "확정"으로 표기하지 않는다.
4. **소유**: UI/UX 디자인·프론트 구현·렌더 검증은 Design Specialist에 위임(기본). Build는
   기능/계약/데이터/권한 검증과 Git을 소유한다.
5. **독립 리뷰**: 새 세션(새 컨텍스트)에서만 인정한다. 같은 세션 재개는 독립이 아니다
   (과거 OpenDesign에서 생성 세션 resume로 독립성이 깨진 사례 실측).
6. **보존 제약**: 상태 계약(`docs/plans/2026-10-12-b1-operator-flow-contract.md`)·정직 문구·
   기능 도달성 — 디자인 변경에서도 불변.

## 테스트 루프 (로컬 CI 미러)

- 전체: `bash scripts/run_local_verification.sh` (python → edge → android)
- 개별: `scripts/run_all_tests.sh`(python 86) · `cmake -S edge -B edge/build && ctest`(edge) ·
  `scripts/android_host_tests.sh`(android — rc-ports/rc-core/rc-msdk/rc-app bench+device)
- 실기 하루(RC 지상 세션): `scripts/rc_device_day.sh` + `docs/verification/a9-4-device-day-runbook.md`
- 실기 대조(업로드 후): `scripts/rc_core_correlate.py` — RC pull 저장소 ↔ Core 읽기 응답 1:1(A9-4 런북 §업로드 후 대조)
- 알려진 한계: Robolectric/WorkManager 6건은 aarch64에서 SKIP(사유 표기). x86_64 CI
  (`.github/workflows/ci.yml`)가 실실행한다 — CI가 정본 경로.
- aapt2는 x86_64 바이너리이므로 `QEMU_LD_PREFIX`가 필수다(스크립트가 설정).

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
| `design/rc-pilot-shell/field-shell.html` | 목업([SIMULATED]) | `:8765/field-shell.html` | 브라우저 목업 — 실제 데이터 아님 |
| `/review` Core 데이터 점검 | Core 조회 API 동작(출처 식별은 제한적) | `:8000/review` (tailnet `:8800`) | canonical demo 경로 쌍만 DEMO FIXTURE, 나머지는 출처 미확인. 원격은 Tailscale 필요 |
| `android-rc` 앱 | 실제 구현(실기 미검증) | 실기/기기 | A9-4 게이트 |

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
