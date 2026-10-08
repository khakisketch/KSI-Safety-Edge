# 기존 로컬 자산 재사용 평가 (초안)

- 작성: 2026-10-08 · 방법: Primary의 **읽기 전용** 유한 조사(별도 explore 서브에이전트는 모델 가용성 오류로 사용 불가 — 환경 문제).
- 대상(참조 전용, 수정 금지):
  - `~/Desktop/KSI-Projects/KSI-Site-Intelligence-Platform` (git, Track A 실기 절차·분석 도구)
  - `~/Desktop/KSI-Projects/KSI-Site-Intelligence-Platform-manifold3-shadow` (비-git 작업공간: PSDK 수집기·Shadow DPK·C++ 런타임)
- 성격: **후보 식별**이다. 코드 채택 전 별도 감사(라이선스·의존성·품질·계약 충돌)가 필요하다.

## 1. 확인된 자산 (읽기로 확인)

| 자산 | 위치 | 내용 | 상태 구분 |
|---|---|---|---|
| PSDK 콜백 수집기(C) | `…-manifold3-shadow/edge/psdk-tap/` (`liveview_tap.c`, `telemetry_tap.c`, `clock_util.c`, `shared_state.c`, `pairing.c`, `ndjson_writer.c`) | `DjiLiveview_StartH264Stream`(H264 콜백), `DjiFcSubscription_SubscribeTopic(HARD_SYNC…)`, NDJSON writer(meta: psdk_version·started_at·clock anchor·frame_timestamp_provenance·capabilities flags) | 소스 확인 |
| Shadow DPK | `…-manifold3-shadow/edge/psdk-app/` (`src/main.cpp`) | PSDK 3.16.0 기반, 비행제어 심볼 경계 게이트 사용 | README·파일 확인 |
| C++17 Edge 런타임 | `…-manifold3-shadow/edge/manifold-runtime/` (`src/`: `frame_queue`, `sync`, `calibrated_projector`, `risk`, `evidence_builder`, `store_forward`, `event_timing`, `tensorrt/dfine`, `shadow`, `activation` …) | 프레임큐·정합·지면투영·위험엔진·증거·store-and-forward·서명 정책 게이트 등 | 파일·README 확인 (품질 감사 미실시) |
| PSDK 계보 고정 | `…-manifold3-shadow/edge/third_party/PSDK_LOCK.yaml` | PSDK 핀 `9af08536…`(3.16.0), fetch-not-commit, MIT 표기 | 파일 확인 |
| 시간 정합 분석 도구(Python) | `KSI-Site-Intelligence-Platform/tools/hil/capture_frame_telemetry_timing.py` | p50/p95/max·표본수 게이트·MEASURED/acceptance 2축·무결성 보호 | 소스 확인 |
| 실기 세션 로그 | `KSI-Site-Intelligence-Platform/hil_raw_logs/SESS-2026…` (7개) | 2026-08 링크 확인 세션 포함 | 디렉터리 확인 |
| 절차·수용 기준 | `…/docs/dji/SPIKE_PROCEDURE.md`, `…/docs/hil/HARDWARE_SESSION_PLAYBOOK.md`, `CAPABILITY_ACCEPTANCE.md` | 8단계 절차, 증거 규율(11필드), p95 50ms 목표(그들 PRD) | 문서 확인 |

## 2. 재사용 후보 (H1/M2 관점, 우선순위)

| 순위 | 후보 | 용도 | 근거 | 리스크·확인 필요 |
|---|---|---|---|---|
| 1 | psdk-tap 수집기(패턴+코드 후보) | H1-3/4/5 수집(C) + NDJSON | 실제 PSDK 콜백 사용, 수집/판정 분리 | SI 타이밍 도구와 NDJSON 스키마 호환 확인; 필요한 토픽(quaternion/gimbal/position/height) 추가 필요 여부 |
| 2 | `capture_frame_telemetry_timing.py` | H1-5 분석(PC) | p50/p95·표본 게이트·조작 방지 | 입력 포맷 일치 확인; 목표값(50ms)은 **우리 기준 아님**(참고만) |
| 3 | PSDK lock/fetch 패턴 + 경계 게이트 패턴 | M2 빌드 재현성·안전 심볼 검사 | 핀 고정, 실물 비커밋 | 도구 위치가 README와 불일치(현재 미발견) — 소재 확인 필요 |
| 4 | manifold-runtime 모듈별 후보 | M2/M3: frame_queue·sync·calibrated_projector·store_forward | 개념 일치(정합·지면·증거·WAL) | SI 제품 계약(서명 handoff·policy pack)에 강결합 — **통째 재사용 금지**, 모듈 단위 감사 후 |
| 5 | 절차·수용 기준 서식(SPIKE/HIL/evidence) | H1 기록 규율 정렬 | 코드 강제 증거 패턴 | 목표 수치·capability ID 체계는 우리 것으로 대체 |

## 3. 재사용 금지 (명시)

- SI-Platform의 Core 서비스·capability registry·Mission Contract/서명 handoff **코드** — 다른 제품 권위 모델.
- 그들의 목표 수치(p95 50ms 등)·PRD 조항 — Safety Edge는 자체 envelope을 현장 실측으로 확정.
- M4D 전제, Cloud API/Dock 관련 코드 경로.
- 비행 제어(FC) 관련 어떤 코드 경로도 Safety Edge에 반입 금지(설계 §4.2 경계 유지).
- 자격증명 파일(`dji_sdk_app_info.h` 실물) — 예시(`.example`)만 참고, 발급은 별도.

## 4. 미확인·다음 조사

- `manifold-runtime` 테스트/빌드 상태, 라이선스 헤더, SI 계약 결합도(깊은 감사 필요).
- `fetch_psdk.py`·`check_shadow_flight_boundary.py` 실제 소재(README 경로와 현재 트리 불일치).
- tap NDJSON ↔ 분석 도구 스키마 정합.
- psdk-tap이 수집하는 토픽 목록(현재 HARD_SYNC만 확인) — quaternion/gimbal/position/height 추가 필요 여부.

다음 조사는 재사용 확정 전 별도 작업(M2 준비)으로 수행하고, 결과를 이 문서에 갱신한다.
