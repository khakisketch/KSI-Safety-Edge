# DPK 설치 검증 실패 원인 규명 (2026-10-08)

- 성격: **실기 진단 증거**. DPK 미설치 상태에서의 원인 규명이며, 설치 성공/실행 결과가 아니다.
- 배경: 우리 앱 DPK(`ksi-safety-edge_v00.01.00.00.dpk`)를 `dji_app_ctl install`로 설치 시도 → `APP INSTALL FAILURE — verify app user_app_id or version info error`.

## 1. 진행 경로

| # | 조치 | 결과 |
|---|---|---|
| 1 | 1차 설치 시도(수정 전 DPK) | 실패. `dji_app_ctl` 진행률 77%→80%까지 진행 후 검증 실패 |
| 2 | DPK 내부 구조 비교(우리 vs tap) | 구조 정상. `user_app_id`("191589")→control `Id` 변환 확인 |
| 3 | `dji_app_mgr` 문자열 분석 | `start third psdk app(appid=%s) success, start to verify app info` / `app control file version info(%02u.%02u.%02u.%02u) not match real version info(...)` / `still have retry times: %d` — **설치 시 앱을 실행해 "실제 버전 보고"와 control Version을 대조**하는 구조 |
| 4 | DJI 공식 샘플 확인 | `DjiCore_SetFirmwareVersion` 호출이 필수 보고(SI 코드에는 누락 → tap DPK도 미설치였던 이유) |
| 5 | 수정: `SetFirmwareVersion(+SetAlias)` 추가 후 2차 설치 | 여전히 실패 — 단, **앱이 검증용으로 실제 실행됨**을 확인(`/tmp/ksi-safety-edge.ndjson` meta 생성, app_temp 로그 3회 재시도) |
| 6 | 부트 로그(`/tmp/ksi-safety-edge-boot.log`) 추가 후 3차 설치 | `start → out_open → glues_begin → glues_ok → core_init_begin` **에서 멈춤** |

## 2. 결론

- `DjiCore_Init`은 **기체 링크(페이로드 포트) 응답을 기다리며 블록**한다(현재 `l4tbr0`/`rndis0` NO-CARRIER = 기체 미접속).
- 설치 검증은 앱 시작 → `DjiCore_SetFirmwareVersion` 보고 → control Version 대조 순서인데, 보고가 `DjiCore_Init` 이후에만 도달한다.
- 따라서 **기체 미접속 상태에서는 DPK 설치 검증이 통과할 수 없다.** (약 8초 간격 ×3회 재시도 후 실패 — app_mgr 동작 실측)

## 3. 의미·시사

- "설치는 기체 없이도 되고 데이터만 기체 필요"가 아니라, **설치 자체가 기체 연결 세션에서만 가능**하다.
- 필요 연결: 기체 전원 + Manifold 페이로드 포트 연결(프로펠러 분리). RC는 Pilot 표시/세션용으로 함께 켜두는 것을 권장.
- 수정 사항(`SetFirmwareVersion`/`SetAlias` + 부트 로그)은 기체 연결 세션에서 필요한 선행 수정이었다 — 다음 설치 시도는 이 빌드로 진행한다.
- 진단 자산: 부트 로그 `/tmp/ksi-safety-edge-boot.log`, SDK 콘솔 `/blackbox/system/app_temp_files/ksi-safety-edge_*.log`.
