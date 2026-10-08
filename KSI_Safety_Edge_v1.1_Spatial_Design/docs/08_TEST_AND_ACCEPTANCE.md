> **v1.1 spatial physical acceptance:** `18_SPATIAL_VALIDATION_PLAN.md`에 명시된 G0~G5 및 독립 GT/후퇴 정책이 최우선.

# 08. 검증·테스트·품질 게이트

**규칙:** 컴파일/유닛테스트/시뮬레이터 PASS는 실기 운영 PASS가 아니다. 실제 기체·모델·펌웨어 및 현장 조건에서 독립 측정 후에만 Active 안전 사건 판단을 허용한다.

## 1. 시험 계층

| 레이어 | 범위 | 테스트 |
|---|---|---|
| L0 Contract | JSON Schema, OpenAPI, versions, enum | schema validation/negative fixtures |
| L1 Pure Logic | Zone/geometry/clock mapping/dedup/state machines | golden vectors/property-based/fuzz |
| L2 Component | PSDK adapter fake, MOP sender, RC WAL, Upload API | fault injection/device simulator |
| L3 Integration | Edge program + RC APK + Core docker stack | signed replay/run isolation/end-to-end |
| L4 Physical Ground | Matrice 4 + Manifold 3 + RC Plus 2 | 호버/이동/줌/짐벌, DJI 링크 및 네트워크 장애 |
| L5 Field Pilot | 실제 현장 안전 실증, 독립 GT | FP/FN/geo uncertainty/조종 UX/관측 커버리지 |

## 2. 물리 검증 게이트 (순서 고정)

- **G0 — 설치·장치 호환:** DJI SDK/OS/펌웨어/기체/렌즈 상세 버전 기재. PSDK app install/stop/start, MOP discovery, 모델 digest, RC registration/영상.
- **G1 — 입력 신뢰:** Liveview frame sequence, FC topic timestamp semantics, 동일한 자세 값 갱신, RC overlay 약속을 검증. 카메라 노출 vs pose 관계 오차분포 증거.
- **G2 — Edge 관측:** 30분 연속 frame/telemetry/AI health; 영상 품질 변화/thermal/throttling, stale/missing reject counters. 사람/PPE 기본 감지 벤치.
- **G3 — 원본/공간 근거:** 프레임 ID↔evidence, 독립 검사점과 위치 오차, boundary band, 비확실시 침입 사건 자동 확정 0.
- **G4 — 제어/통신:** mission hash apply, MOP disconnect/reconnect, ACK/replay/gap detection, Edge restart, 비행 제어 영향 없어야 함.
- **G5 — RC 오프라인:** 비행 중 인터넷 0 + 30분 proxy recording/event manifest, storage-low, 재시작 시 복구, 위험구역 오프라인 변경.
- **G6 — 서버 내구성:** 사무실 Wi-Fi 업로드, chunk interruption, 중복 retry, hash/server readback, 객체 누락 PARTIAL, `DURABLY_COMMITTED` 이전 RC 삭제 0.
- **G7 — 사용자 end-to-end:** 웹 위험구역 v3 생성→RC 다운로드→Edge 적용→오프라인 비행 이벤트→서버 저장→웹 v4 변경 후 당시 v3 조회→Agent 근거 응답.
- **G8 — 사후 분석:** DGX job 실패/지연/중복, raw event overwrite 0, 미탐 audit holdout, 성능 평가·비용.
- **G9 — 기존 제품 분리:** `ksi-platform` Cloud API 기준선 영향/롤백, Active incident write 0, 비밀값/운영 데이터 분리.

## 3. 필수 테스트 케이스 (예시 30개)

| ID | 시나리오 | PASS 기대 |
|---|---|---|
| T01 | 30초 동안 정지 호버하고 quaternion 동일 반복 | 실제 새 topic 표본은 허용, 캐시값 새 측정시간 위조 0 |
| T02 | FC/짐벌 Topic 독립 지연 500ms/1s | per-topic age/quality 분리, geo 검증 보류 |
| T03 | Linux/Android wall-clock 임의 ±30s | Edge local matching/AI unaffected; UTC estimate uncertain 표시 |
| T04 | Frame out-of-order/duplicate | frame mapping idempotent, no cross-session artifact |
| T05 | 줌/렌즈 전환 | camera intrinsics 변경 버전 연결, overlay correct or disabled |
| T06 | gimbal 급변 | frame-pose timing skew metric, geometry unreliable 처리 |
| T07 | GNSS/RTK 불량 | geo confirmed 0; image-only detection retained |
| T08 | 구역 경계에서 위치 오차밴드 겹침 | `BOUNDARY_UNCERTAIN`, 침입 확정 금지 |
| T09 | GeoJSON lat/lon 뒤바뀜 | geometry validation 실패 |
| T10 | self-intersect polygon | zone apply reject with reason |
| T11 | 웹 v4 업데이트, RC v3 오프라인 비행 | 당시 snapshot v3 사건 연결, 서버 최신 v4와 구분 |
| T12 | 웹/RC 동일 base 수정 | 409 conflict, silent overwrite 0 |
| T13 | MOP config transmission 중 단절 | `APPLIED` 없으면 EDGE_READY 아님 |
| T14 | Edge reboot after apply | 새로운 boot ID, 재동기화 필요 |
| T15 | Edge event seq 7 누락 | RC gap 표시, Edge 재전송 또는 unrecoverable_gap 기록 |
| T16 | RC가 ACK 전 다운 | Edge 재전송, RC idempotent 저장 |
| T17 | RC ACK 직후 링크 다운 | 재전송에도 중복 event 0 |
| T18 | 인터넷 없는 비행/RC restart | 로컬 기록/업로드 큐 복원 |
| T19 | RC 저장 공간 임계 | 사전 경고, 미업로드 증거 GC 0 |
| T20 | Proxy 영상 5분 파일 파손 | 직전 chunk salvage, segment incomplete 명시 |
| T21 | 모바일 핫스팟 켜짐/꺼짐 반복 | resumable upload, duplicate object 0 |
| T22 | 업로드 object 중 1개 hash mismatch | `PARTIAL`/reject, SYNCED false |
| T23 | 서버 DB 기록 성공, blob 저장 실패 | durable complete 금지 |
| T24 | 서버 blob 쓰기 성공, DB 응답 timeout | idempotent resume, 중복 commit 0 |
| T25 | 서버에 동일 flight ID, 다른 manifest | 409 conflict |
| T26 | 임무 사건 0건, AI 40% 다운 | 0건을 안전 판정으로 미표시, downtime 계측 |
| T27 | 사용자가 오탐 의심 표시 후 취소 | append-only annotations, 원본 변경 0 |
| T28 | VLM이 오탐이라 판정, 사용자 반대 | 충돌 상태+재검토, raw immutable |
| T29 | VLM job 없음/실패/밤샘 대기 | Agent에서 ANALYSIS_PENDING/FAILED 구분 |
| T30 | 권한 없는 조직의 이벤트 파일 URL 시도 | 403/404 및 audit, URL 만료 |

## 4. 수용 기준 수치 정책

현재 **실측 전 목표 수치를 상용 성능 보장으로 쓰지 않는다**. 예비 후보는 다음과 같다.

- Edge→RC 사건 알림 p95 ≤3초(30분 실기 측정, RC 컨트롤 영향 없는 상태). 실제 기준은 Pilot 비행 별 도출.
- 30분 비행 중 인터넷 0에서 manifest/영상/에러 로그 보존, API 단절이 PSDK AI를 중단시키지 않음.
- 최소 모든 `event_id`가 동일 source/edge_frame/mission_snapshot/model_digest/evidence SHA에 연결.
- flight upload 최종 검증 뒤 UI는 `complete`, 그 전엔 `local-only`, `partial`, `uploading` 중 하나.
- 이미지-telemetry 정합은 허용 오차 숫자를 임의로 정하지 않고 **비행 조건별 실측 오차·불확실성**과 사용 목적(영역 진입, 근접거리)에 맞춰 gate를 결정.
- Video proxy 품질/파일 크기와 RC 발열·CPU는 현장 검사·다양한 lens에서 측정.
- AI Precision/Recall은 ground-truth 기반 class별 test set에 대해 비교, 정량 목표는 검증 후 확정.

## 5. 필수 검증 산출물

`test_run_id`, 하드웨어/SDK/펌웨어/모델 digest, scenario, start/end, captures(이미지/비디오), metrics CSV/JSON, 수기 GT, FAIL/NO-GO 사유, rollback test, sign-off 담당자.

출시 승인은 검증된 Evidence Bundle과 일치하는 소스 commit/APK hash/DPK hash/서버 image digest만 허용한다. 개발 문서 PASS ≠ 배포 승인.
