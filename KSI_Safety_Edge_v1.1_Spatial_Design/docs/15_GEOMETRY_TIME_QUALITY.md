# 15. Geometry / Time / Quality Design

## 1. 절대 원칙
- 같은 값의 자세라도 다른 **실제 표본**이면 유효할 수 있다. 캐시 `getValue()` 반복 읽기를 새 센서 샘플로 위장하면 안 된다.
- `frame_received_monotonic`은 영상 촬영/노출 시각이 아님. FC topic timestamp가 로컬 수신 시각일 수도 있어 출처를 검사해야 함.
- PPS 기반 PSDK Time Sync 기능은 존재하나, **카메라 H.264 프레임의 정확한 노출시각과 정합까지 보장한다는 뜻이 아님**. Matrice4 실기에서 시간 기준을 계측해야 함.

## 2. 좌표계/센서 입력
- `GeoJSON EPSG:4326 (longitude, latitude)`, Geo 계산은 사이트 anchor 기반 `ENU` (east, north, up, meters).
- `altitude_reference = ELLIPSOID | MSL | RELATIVE_TAKEOFF | AGL | UNKNOWN`; 변환 가능한 기준만 섞음. 지면 고도와 기체고도는 같은 수직 기준으로 환산.
- 기체 위치 `C_world`, 기체 quaternion `R_world_body`, gimbal orientation `R_body_gimbal`, 카메라 설치 offset `T_gimbal_camera`. body/ENU/optical axis 좌·우수계 및 부호는 SDK 벡터를 기준으로 golden test.
- `intrinsic K=[fx,fy,cx,cy]`, distortion D, lens ID, zoom/focal, resolution, calibration revision; 줌·렌즈 전환 시 필요 시 새 calibration/track session 시작.
- FC pose, GPS, gimbal topic은 각기 `sample_seq`, source_time?, receive_monotonic, source_kind, age, quality를 가짐.

## 3. Frame/Pose alignment
1. PSDK 디코드 영상 프레임 ID, stream ID, monotonic receive time 보관. 비디오가 제공한 PTS의 clock domain은 실기 검증 이전에 absolute capture time으로 간주 금지.
2. attitude/gimbal/position을 개별 typed buffer로 유지, 같은 `edge_boot_id/flight_id/session_id`에서만 선택.
3. 실제 측정 시간 관계가 검증된 topic에 한해 bracket interpolation: 위치 선형, quaternion SLERP, 각도 wrap 처리; 이중 보간을 금지하고 최대 interval/stale 제한을 둠.
4. 수신시각 기반만 있을 때는 `TIME_ESTIMATED` 또는 `UNVERIFIED` 결과로 내려가며 캡처 지연을 상수로 보정해 '확정'하지 않음.
5. stream restart/drop/zoom change/RC link fail은 quality event를 기록. time gate failure 시 video detection이 남아도 `geo_status=WITHHELD`.

## 4. 지면투영 알고리즘
- 사람 발 접촉점을 관측할 수 있는 경우 이미지 좌표 `p=(u,v,1)`, distortion 해제, ray `r=R_world_camera * normalize(K^-1 p)`.
- 지면고도 `z_g`와 카메라 위치 `C`가 유효하고 `abs(r_z)`가 충분히 클 때 `lambda=(z_g-C_z)/r_z`, `P=C+lambda*r`.
- 경사지·복층/가림·교량·단부는 단일 수평평면 모델 실패 가능. 검증되지 않은 현장에서는 `plane_assumption=true`, 좌표 신뢰도를 낮추고 metric warning 비활성.
- 장비는 bbox 중심이 아니라 **지면과 접하는 차체 또는 바퀴·궤도 점유 footprint**를 추정해야 한다. 실제 3D 크기·붐/버킷 접근 경계까지 bbox만으로 해결 불가.
- 사람은 발점 유실·가림 시 `footpoint_status=UNKNOWN`, 장비 footprint가 불명확하면 `footprint_status=UNKNOWN`; 거리 수치는 null.

## 5. 거리 추정/오차
- geometry가 유효한 경우 `distance = min_{p in person footprint, q in equipment footprint} ||p-q||_2` (ENU 평면 meter). 최소거리가 0이면 사람-장비 footprint 겹침 상태일 수 있으나 실제 충돌 확정 아님.
- 불확실성: 시간정합 지연, GPS 수평오차, altitude/geoid/terrain, attitude/gimbal, 카메라 calibration, 검출 발점·footprint 추정 오차를 separate components로 기록.
- `distance_interval_m=[lower,upper]`는 단순 `distance±heuristic`으로 생성 금지. 합성 오차의 커버리지 의미를 실측/리플레이에서 교정한 뒤 제공. 실측 전엔 `null`.
- 위험 임계값 `T`: `upper<T`이면 충분한 근거의 근접 후보; `lower>T+hysteresis`이면 명확한 이탈 후보; 구간이 겹치면 `UNCERTAIN`. 단위는 m이고 현장 정책 지정(전국 동일 상수 금지).
- 진짜 측정 정확도는 독립 측량 control target/실측 거리 ground truth로 class, view, altitude, zoom, motion envelope별 검증.

## 6. 품질/레벨 정책
| 수준 | 설명 | 허용 |
|---|---|---|
| L0 `PIXEL_ONLY` | 영상 detection/track만 신뢰 | 화면 후보 탐지 및 스냅샷 |
| L1 `TIME_UNVERIFIED` | 위치 계산은 가능하나 영상 시점·센서 관계 미검증 | 실측 디버깅/Shadow; 미터 경고 금지 |
| L2 `GEO_ESTIMATE` | 카메라+pose+지면이 기본 유효, 불확실 구간 검증 안 됨 | 잠정 거리 요약(실험용), Active 금지 |
| L3 `METRIC_VERIFIED` | 별도 실기 GT에서 오차 envelope 승인 + 현 시각 조건 통과 | 승인된 규칙 한정 Active 가능 |

`quality`는 각 관측 단위 속성이고 `approval`은 해당 `aircraft/firmware/lens/calibration/terrain/motion envelope`에 대한 별도 운영 승격 표다. 모델 점수가 높아도 L3을 대체하지 못함.

## 7. 경계 사례
- camera near-horizon, AGL 0/unknown, zoom change, RTK downgrade, GPS jump, 2층 비계, 인물 가림, frame drop, yaw burst, stop-hover stable pose, different gimbal topic receipt-time, stale source sample, move after timestamp source reset.
- 이런 데이터는 `WITHHELD`를 허용하며, 여전히 Edge 영상 AI 결과와 provenance는 저장.
