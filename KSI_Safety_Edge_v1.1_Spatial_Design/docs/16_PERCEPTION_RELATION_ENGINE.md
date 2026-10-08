# 16. Perception / Tracking / Spatial Relations

## A. 공통 탐지 (MVP)
기본 객체 클래스: `person`, `excavator`, `dump_truck`를 필수 A/B 시험 클래스로 제안. 추가 `loader`, `forklift`, `crane`, `truck`, `helmet`, `vest`는 모델별 검증 이후 활성. class는 taxonomy file/version으로 통합; 실제 장비 이름과 모델 출력 class를 몰래 혼합하지 않는다.

**모델 선택:** person + equipment를 가능한 한 한 번의 detector에서 처리하여 중복 디코딩/모델 실행 비용을 줄임. PPE는 사람 crop + min-pixel 품질 기준을 둬 선택 추론. 최초 YOLO weight는 기존 KSI 모델을 재사용할 후보일 뿐 Manifold/TensorRT 엔진 호환·INT8 정량화 정확도는 재측정.

## B. Tracking/Session
- `track_id` 유효 범위는 `(aircraft_id, edge_boot_id, flight_id, camera_stream_id, tracker_epoch)`이며 영구 ID가 아니다.
- same person/equipment 도중 잠깐 가려지면 `OCCLUDED/LOST_POSSIBLE`; 다른 객체로 재연결한 경우 과거 episode와 자동 병합하지 않음.
- `camera_motion_hint`(aircraft attitude/gimbal/angular velocity) 기반 global motion 보정은 Tracking 도움일 뿐 위험결정의 근거를 대신하지 않음.

## C. Relation Candidate Phase
- frame의 전체 box 모든 쌍을 영구 관계로 저장하지 않는다. 인접 가능성 있는 person-equipment pair, zone adjacency candidate에 대해서만 상세 geometry.
- Broad phase: 화면 근접, 투영 위치가 유효할 때 ENU spatial index, relation TTL. `N_person×N_equipment` 전체 연산은 상한이 작을 때만 허용.
- 관계 시간 정합: 두 object's geometry가 서로 다른 image timestamp/stream session에서 계산된 경우 실제 동일 순간 거리라고 주장하지 않는다.
- 거리 기준: `person_ground_contact` ↔ `equipment_ground_footprint` 최단경계. 장비 bbox center↔person bbox center 금지. 지면 추정 상태가 invalid이면 `distance_m=null`.
- equipment mode: 주행/작업/정지 상태는 보조 데이터 없으면 영상 관찰 기반 **추정**이지 기체가 이를 직접 알고 있다는 뜻 아님. 선회 붐·작업반경은 v1.1 P2.

## D. 데이터 발생/전송 전략
| 빈도 | 생성/저장 | RC 전달 |
|---|---|---|
| every inference frame | 탐지/tracker/geometry 임시 인메모리 | 기본 전송하지 않음 |
| change/low-rate sample | object count, spatial min-distance/zone relation, valid coverage, health | 작은 `SpatialSummary`, 압축/최대크기 제한 |
| Risk Candidate/Event | event ID, source frame, relation/policy/quality, snapshot | 필수 이벤트 메타데이터 우선; 증거는 chunk 별도 |
| end of flight | sample totals/gaps, minimum verified distances, config/model digest | `FlightSummary`+manifest ACK/reconcile |

MOP 상태 링크 자체가 끊기면 기체/Manifold 임시 spool을 상한 내 기록하며, RC 쪽 확인되는 즉시 재전송. 수집 전체 Frame을 Manifold에 영구 저장하지 않는다.

## E. 우선순위 / 리소스 예산
- 우선순위: 안전 비행/DJI 기능 > 통신/관측 완결성 > person+equipment > tracker/relation > 선택 PPE > analytics/preview.
- 구체 수치는 960px 기반 1 detector부터 물리 벤치마크 후 확정. `capture_fps`, `decode_fps`, `infer_fps`, `observe_rate`, `event_latency_p95`, `temp`, `memory_peak`, `mop_backlog`, `frame_skips`를 동시 측정.
- 과열시 프로파일 변경은 deterministic `DEGRADED_PROFILE` 이벤트와 영향 구역/시간을 남김. 단순 FPS 저하가 안전하지 않은 거리 계산을 허용해서는 안 됨.

## F. 모델 정확도 시험 데이터셋
- `person/equipment/PPE` per class precision/recall(AP)은 학습데이터 외 실제 촬영 고도/광학줌/역광/먼지/가림 포함.
- pixel footing/footprint GT 정확도, actual object surface distance GT와 별도 비교.
- `GT` 라벨은 사람 검토를 통과한 자료만 사용. 조종사의 오탐 의심, VLM 의견 자동 GT 승격 금지.
