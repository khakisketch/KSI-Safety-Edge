# Product State — KSI Safety Edge

Updated: 2026-10-08 · Owner: Build (Primary) · Spec: `docs/superpowers/specs/2026-10-08-integrated-design.md`

## 제품 목표

인터넷이 없어도 현장에서 사람·중장비를 관측·기록하고, 연결 복구 후 **당시 설정과 증거를 재현**할 수 있는
드론 기반 안전 관측 제품. 공간 판단은 SHADOW가 기본이며 ACTIVE는 실측 검증·규칙별 승인 후에만.

## 마일스톤

| ID | 내용 | 상태 |
|---|---|---|
| M0 | 설계·계약 기준선 (FR 통합, 공통 계약/해시/검증 규칙) | 진행 중 |
| M1 | 재생 기반 전체 흐름 (임무 적용→RC 오프라인 저장→업로드→조회, 장애 복구 E01~E12) | 진행 중 |
| H1 | 실제 장비 능력 확인 (PSDK/MSDK/영상/시간/MOP/RC 저장) | 대기 (장비 접속·환경 확인 필요) |
| M2 | 실제 Edge 탐지 전체 흐름 | 대기 |
| M3 | 공간 SHADOW (지면/거리/zone 관계, episode) | 대기 |
| M4 | 제한된 규칙 ACTIVE (규칙별 승인 후) | 대기 |

## 현재 슬라이스 (M0+M1)

- Acceptance: `docs/superpowers/plans/2026-10-08-m0-m1-replay-slice.md`의 E01~E12가 재생 환경에서 통과.
- 범위 밖(이번): Android 앱(빌드 환경 없음), PSDK C++ 앱(장비·SDK 환경 필요), 제품 UI(디자인 워크스페이스), ACTIVE 경고.

## 원칙 (변경 금지)

1. Edge→서버 직접 업로드 금지. RC가 업로드 주체.
2. 라이브 DJI 영상과 Edge 추론 프레임을 동일 프레임으로 가정 금지.
3. VLM/LLM은 비행 후 검토만. 실시간 판단 참여 금지.
4. 원본 탐지/운영자 의견/사후 의견/검증 라벨은 분리 계층.
5. `0 incidents`는 안전 확인이 아님. 관측 공백을 함께 표현.
6. 원본 패키지(`KSI_Safety_Edge_v1.1_Spatial_Design/`)는 출처 snapshot으로 수정하지 않는다.

## 후속 입력 대기 (제품을 막지 않는 항목)

- v1.0 전체 FR 원문 (없으면 추적표 기준으로 확정)
- 기존 `khakisketch/ksi-platform` 코드 대조 (재사용 범위)
- 장비 SDK/펌웨어 조합·설치 환경 (H1)
- 운영 결정: 권한 기간·보존 정책·현장 수 (D06)
- UI/디자인 단계 착수 시 OpenDesign 연결 확인 (D08)
