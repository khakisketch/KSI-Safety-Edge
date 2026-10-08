# 18. 실기 검증/평가/출시 게이트

**목적:** Detection 성능이 좋아도 Geo/Distance 오차가 크면 위험 경고를 활성화하지 않는다. 테스트 패스는 특정 하드웨어/펌웨어/렌즈/거리/높이/환경 envelope에만 유효하다.

## 0. 환경·증거 메타데이터
기체 모델·펌웨어·SN 비밀정보 마스킹 식별자, Manifold OS/PSDK 버전, RC MSDK APK digest, 카메라/렌즈/줌/영상 profile, 모델 weights SHA, RTK/GPS 품질, 출발점/지면 고도, 날씨/조도/가림, 현장 구역 revision, 시간 보정 버전. 모든 시험 저장.

## 1. Gate G0 — 장치/SDK 실기 Capability
- [ ] Matrice 4 + Manifold 3에서 대상 카메라 PSDK 스트림 디코드, frame/session ID 기록.
- [ ] FC quaternion, gimbal angle, GNSS/고도와 PPS/time-sync 가용성/시간 도메인 실측.
- [ ] RC KSI MSDK↔Manifold MOP 연결, 원자적 설정/ACK, 30분 중단·재연결.
- [ ] RC 저장 가능한 앱 네이티브 영상/파일 압축/복구 경로 확인.

## 2. Gate G1 — detector/tracker Shadow (거리 판정 아님)
- [ ] 사람/굴착기/덤프 실제 촬영 30분 recording/replay; class별 AP/Recall, score calibration.
- [ ] 정지 호버/이동/yaw/gimbal/줌/가림/교차/여러 장비/어두움/먼지/압축 손실.
- [ ] track fragmentation, ID-switch, episode double-create, frame late-match 각각 계측.
- [ ] Soak: FPS/temp/memory/decode/infer queue/MOP backlog와 degrade 기록.

## 3. Gate G2 — Geo/Distance Golden Measurements
- [ ] independent measured control points, 지상 사람 발점 기준 마커와 굴착기/더미차량 footprint/최단거리 ground truth.
- [ ] 각기 비행 높이/짐벌 pitch/yaw/카메라 줌·고도 기준/반사광 조건에서 2D ray→ground 오차(중앙/p95/최대) 측정.
- [ ] 데이터가 같은 timestamp라고 **가정하지 않는** cross-check: 실제 물체 움직임(테스트 마커, 반응 지연)과 frame→pose skew를 실측.
- [ ] camera near-horizon/가림/지면고도 오류/GPS jump/stale/빈 표본에서 `distance_m:null`, `geo_quality downgraded`, Active 0 테스트.
- [ ] L3 승인용 오차 한계와 위험 정책 임계값의 margin이 호환되는지 평가. 보정 envelope 밖이면 자동 격하.

## 4. Gate G3 — Shadow Policy Confusion + Episode
- [ ] DISTANCE true near/true far/threshold boundary, moving apart/approach/cross/camera pan.
- [ ] Zone polygon edge/inside/outside, 중간 zone 변경, 여러 사람/여러 장비, occlusion.
- [ ] EPISODE CREATE 1 + TOUCH N + CLOSE 1 조건; missing source 이유 `OBSERVATION_LOST`와 real exit `EXIT_CONFIRMED` 구분.
- [ ] false alerts per flight hour, missed hazardous episodes, distance p95, zone classification, coverage validity 측정.
- [ ] per-class/policy Gate 승인(일괄 ALL Active 금지).

## 5. Gate G4 — RC Offline / Server
- [ ] RC 와 Manifold 각각 재시작 / RC 네트워크 0 → 로컬 manifest 및 snapshot 보존.
- [ ] MOP chunk missing/reordered/duplicate/cancel, CRC/hash, sequence gap와 retransmit.
- [ ] 뒤늦은 저장 메타데이터/스냅샷: 같은 `event_id`에 맞춰만 attach.
- [ ] 사무실 Wi-Fi → resumable upload → hash/readback durable ACK → 웹/API/MCP 조회.
- [ ] 원본 탐지, 운영자 오탐 의견, 사후 VLM은 서로 별도 수정 불가 레이어로 조회.

## 6. Negative/Fault injection matrix
| 주입 상황 | 기대 |
|---|---|
| 자세 값 동일·센서 신규 표본 | 표본 ID로 유효성 판단, 값 동일 이유 폐기 금지 |
| 캐시만 반복 조회, 새 timestamp 위조 | stale 판단, Geo/metric WITHHELD |
| high RTT / RC와 서버 clock drift | Edge metric unaffected; 업로드 시 서버 시간으로 사건 프레임 변경 금지 |
| PSDK frame dropped, telemetry arrived late | quality degraded; relation trace retained; Active 0 if invalid |
| mission hash mismatch/unknown zone rev | spatial Active off, RC reason 표시 |
| 서로 다른 edge_boot_id/flight_id | samples/episodes 혼합 0, duplicate event 0 |
| camera zoom switch/oblique angle | calibration revalidate, invalid if unsupported |
| thermal throttling/GPU OOM | graceful degrade, no spurious safe=confirmed |
| person foot occluded/equipment footprint missing | `distance_m=null`, no exact-meter risk |
| simultaneous server/RC zone edit | CAS conflict/branch, 과거 snapshot immutable |

## 7. 운영 승인 조건
Gate 문서는 반드시 `Measured / Not measured / Failed`를 분리하고 수치와 기준 영상을 제시. '테스트 green'은 물리 정확도 승인이 아님. G2+G3 없이 `PROXIMITY_ACTIVE` 불가. G2+G3 없이 `ZONE_ACTIVE` 불가. 두 승인 서로 독립. 기존 ksi-platform `AGENTS.md`의 Cloud baseline 및 rollback 회귀는 매 단계 필수.
