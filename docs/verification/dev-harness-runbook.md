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
- 알려진 한계: Robolectric/WorkManager 6건은 aarch64에서 SKIP(사유 표기). x86_64 CI
  (`.github/workflows/ci.yml`)가 실실행한다 — CI가 정본 경로.
- aapt2는 x86_64 바이너리이므로 `QEMU_LD_PREFIX`가 필수다(스크립트가 설정).

## 정본·동기화 규칙

- 산출물 정본은 저장소. 외부 도구 사본은 이력일 뿐이며, 반영 시 해시 대조를 기록한다.
- 작업 기록: 계획·런북은 `docs/`, 디자인 산출물·영수증은 `design/`, 검증 증거는
  `docs/verification/evidence/`. `.opencode/working-state.md`는 단기 상태(≤80줄, Next ≤3).
