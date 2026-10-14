# 2026-10-14 round5 — 현장 보조 화면 정리 + 캡처 영수증

이 라운드는 10-14 "현장 보조 화면 정리"의 후속 완성분이다: 전 상태 매트릭스에서 발견한 결함 수정,
영상 7상태 정합, Android 정합 보강, 캡처 영수증(라이트/다크·3뷰포트) 생성.

## 이번 라운드 수정 (발견 → 조치)

| # | 발견 | 조치 |
|---|---|---|
| 1 | 390px에서 시나리오 전환 버튼이 한 줄에 눌려 깨짐 | `.scenario-switch` 세로 배치 + 5열 그리드(모바일) |
| 2 | 비행 없음 시나리오에서 임무 페이지 오프라인 패널이 이전 `SIM-FLT-024`를 계속 표시 | `mission-offline-flight-id` 동적화(`없음 · 활성 기록 없음`) |
| 3 | 브라우저 영상 예시가 6상태(프레임 미수신 누락)·"신호 없음"과 "미수신" 혼동 | 7상태 정렬(미연결/연결 중/신호 없음/프레임 미수신/최근/지연/시각 미확인) + 문구 분리 |
| 4 | Android 조종 상태 띠에 "KSI 기록" 셀 없음 | `tvKsiRecordStatus` + `cockpit_ksi_record/record_active/record_none` |
| 5 | Android 기기 정보가 `Build.MODEL` 단독 | `app_info_device_format` ("%1$s · Android") |
| 6 | Android 사후 검토 "선택된 사건 없음" 하드코딩 | `postflight_no_event_detail/action` 문자열 + 버튼 비활성 유지 |
| 7 | 1차 캡처에 내비 드로어·토스트가 섞임 | 드로어 닫힘·토스트 소거 후 29장 재생성 |

## 캡처 영수증

- 경로: `captures/round5-field-use/` (RECEIPT.md 참조)
- 브라우저: 1920×1200(라이트 11·다크 7), 390×844(6), 1024×768(3)
- 무스크롤: 5목적지+진단 모두 실측 통과(1920), 조종 390/1024 라이트/다크 통과
- axe: 위반 0 · 자동 불완전 2건은 수동 측정으로 해소(조종 상태 띠 최악 7.89:1, 장식 ▲ 5.96:1)
- 콘솔 오류 0

## Android ↔ 브라우저 정합 (구조 감사 — 렌더 아님)

| 항목 | 브라우저 정본 | Android |
|---|---|---|
| 조종 상태 띠 첫 셀 | `KSI 기록 · KSI 로컬 기록 중/기록 없음` | `tvKsiRecordStatus` + `cockpit_ksi_record/record_active/record_none` |
| 영상 입력 7상태 | 미연결/연결 중/신호 없음/프레임 미수신/최근/지연/시각 미확인 | `VideoFreshnessModel` 7상태(동일 어휘) |
| 레코더 | 입력 대기(저장 샘플 0)/저장 상태 미확인/이전 구간 저장 오류 | `OperatorStatus`: 녹화 입력 대기/영상 녹화 중/녹화 오류 + `writtenFrameCount`/`hasWriteFailure` |
| 사후 검토 0건 | "이 비행에는 기록된 사건이 없습니다" + 의견 비활성 | `postflight_no_event_detail/action`, 버튼 비활성 |
| 기기 정보 | `DJI RC Plus 2 · Android` | `app_info_device_format`(`Build.MODEL`) |
| 시각 규율 | Edge 원시 시각 · RC 시각으로 변환하지 않음 | 수신시각(RC_RECEIVE_APPROX) 원칙 동일 |

- Android는 컴파일·문자열 참조 수준만 확인했다. **렌더·터치 정합은 A9-4 실기에서만 판정**한다.

## 미검증 (기기 전용)

- Android 실제 렌더(밀도·글자 크기·겹침), 터치(장갑), 햇빛 가독성
- 실제 DJI 영상 프레임 위 상태 띠 대비(밝은 장면) — RECEIPT caveat
