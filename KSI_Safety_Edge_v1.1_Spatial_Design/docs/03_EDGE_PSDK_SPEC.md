# 03. Manifold 3 / PSDK Edge 명세

상태: target design. Manifold 3는 드론 상단 탑재 컴퓨터. PSDK는 DJI 데이터/제어 SDK; 실제 AI는 KSI 프로세스/모델이 수행한다.

## 1. 서비스 모듈

| 모듈 | 입력 → 출력 | 실패 동작 |
|---|---|---|
| `dji_adapter` | PSDK Liveview, FC subscription, lens state → typed samples | 개별 stream availability 표시 |
| `timebase` | local monotonic, DJI topic timestamp, optional sync reference | time domain 오염 차단 |
| `video_buffer` | decoded frame + ID + receive time + lens ID → bounded ring | capacity 초과 시 oldest drop 계측 |
| `pose_buffer` | GPS/RTK/attitude/gimbal/altitude topic samples → immutable topic ring | stale age 이유 기록 |
| `matcher` | frame + per-topic time/age → matched observation candidates | untrusted → geometry disabled |
| `perception` | image + model profile → person/PPE/equipment detection | model health 노출/재시작 제한 |
| `tracker` | detection + camera motion hints → track episode | reconnect/session마다 tracker reset |
| `geo_engine` | pose + camera calibration + terrain → point/footprint + uncertainty | unknown/borderline → geo undecided |
| `rule_engine` | mission snapshot + detections + geo → alert candidates | unknown never promoted to confirmed |
| `evidence` | event + edge-frame → snapshot + clip ring and manifest | no safe evidence → evidence_incomplete |
| `mop_gateway` | reliable command/event/snapshot transfer → RC | bounded retry/spool, no internet API |
| `health` | temp/fps/storage/versions/gaps → heartbeat | degraded mode counters |

## 2. 센서 입력 계약

- video: source camera/lens index, zoom/focal/thermal mode, frame dims, `frame_id` if SDK supplies, `edge_receive_monotonic_ns`.
- flight: latitude/longitude, altitude reference(WGS84/relative/AGL 명시), aircraft quaternion, velocity, angular rate, GNSS/RTK quality, flight mode.
- gimbal: yaw/pitch/roll or quaternion in documented coordinate convention; look direction/body/world frames must be converted explicitly.
- camera calibration: intrinsics `fx/fy/cx/cy`, distortion, lens model, orientation, rolling shutter/pipeline latency uncertainty when known.
- time quality: topic timestamp interpretation and source. GPS 위치와 gimbal 값의 샘플은 제각기 다를 수 있으므로 표본별 `sample_age_ms` 보존.

**절대 조건:** 변화하지 않는 자세값이라고 버리지 않는다. 단지 cached read만 발생했다면 `cache_read_at`만 기록하고, 원래의 source sample time을 조작하지 않는다.

## 3. 분석 Pipeline

```
PSDK Frame → Buffer
PSDK Pose/Zone Config → Typed Ring → Matcher
Matcher → ObservationCandidate + valid/degraded/unknown quality
ObservationCandidate → Detector/Tracker
Detector/Tracker + Geometry + Zone → RuleEngine
RuleEngine → DETECTED_ONLY | GEO_VERIFIED | RISK_CANDIDATE
RISK_CANDIDATE → Event/Episode + Evidence manifest → MOP RC
```

v1.1 P0: `person` + 검증된 `excavator`/`dump_truck` 통합 탐지·추적 및 공간 관계 계산. PPE는 임무 프로파일/품질/성능 예산에 따라 선택 실행. `SpatialRelation` 및 `RiskPolicy`는 상세 14~19 문서 참조. 실제 장비 클래스맵은 별도 실험 GT로 검증.

## 4. Spatial/Zone 판정

1. 가장 그럴듯한 foot-point/bbox support point 결정(대상 종류별).
2. 카메라 ray→지면 모델과 교차; 지면 모델 없으면 `assumed_plane` 여부를 명시하고 공간 등급 낮춤.
3. ENU 또는 지구 고정 좌표에서 WGS84 Polygon과의 관계 계산, 원본 좌표/좌표계 기록.
4. `position_uncertainty_radius_m` 추정에 대해 boundary band 적용: 명확히 내부/외부/경계불확실 상태 분리.
5. 동일 객체 사건의 진입/이탈은 temporal debounce(작업별 상태기계)로 안정화.
6. 사람-장비 거리 규칙은 둘 모두 공간 신뢰성과 관측 시간 일치가 충족된 경우에만 real-meter risk 결정.

실기 독립 검사점/RTK/height 오류를 평가하기 전까지 geo layer는 **shadow-only** 권장. GPS 근거가 불충분할 때 2D pixel 기준으로 '실제 제한구역 진입' 판정은 금지.

## 5. Mission 적용

- RC로부터 `MissionSnapshot` 전체 수신(선택 model IDs, zone versions, checksum, thresholds, valid range, flight ID).
- geometry/self-intersection, 좌표 순서(Lon,Lat), Polygon ring closure, max payload size, 지원 model 목록, revision schema 검사.
- `PREPARED`에서 `APPLIED`로 원자적 전환. `START` 이후 적용 중 변경은 `effective_edge_seq`를 부여해 사후 증거에 연결.
- edge_boot_id가 바뀌면 RC는 구 적용 ACK를 재사용하지 않고 재적용.

## 6. MOP 프로토콜과 상태

```text
DISCONNECTED → LINKED → CONFIG_PREPARED → CONFIG_APPLIED → READY → RUNNING
                 ↘ INCOMPATIBLE           ↘ DEGRADED (sensor/model/storage)
RUNNING → PAUSED → RUNNING | STOPPING → STOPPED → DRAINING → CLOSED
```

- `P0` control msg idempotent, 8~24KB/s DJI 공식 예시 uplink가 있으므로 config payload는 수십 KB 내로 설계·압축/청크 분할; 실제 값은 실기 측정.
- Edge→RC observation 패킷은 ID 순서, CRC/sha256 및 ACK 필요. video snapshot은 별도 chunk stream과 우선순위 낮게.
- 비행/임무 control messages 우선, bulk image transfer는 비행 제어/영상 출력 방해 시 즉시 throttling.
- 로컬에 저장하는 것은 **전송 보장을 위한 짧고 상한 있는 ring/spool**뿐. 자료 부족 시 중요한 event metadata→snapshot→short clip 순서로 우선 보관. 임계치 도달 시 health warn.

## 7. 모델 실행·업데이트

- 모델 profile에 `weights_digest`, TensorRT runtime, input tensor dims, preprocessing version, class map, threshold, expected thermal envelope 보존.
- 실시간 Target FPS는 실기에서 1모델/2모델 복수 조합을 테스트해 명시. 배치 YOLO/VLM은 Edge에 무작정 추가하지 않음.
- 업데이트는 RC에서 Signed DPK/모델 artifact 관리가 가능할지 검증 후 별도 ADR; v1은 빌드와 체크섬 고정, 임무 시작 전 모델 ID 일치 검사.
- 자가 watchdog/circuit breaker는 단말 안전을 우선: AI 과열/오류 시 조종·원영상 유지, 'AI 중단/일부 구간 미감시'를 RC에 전달.

## 8. 증거 생성

`edge_snapshot`(추론 프레임) + `frame_id/timebase` + `topic sample reference/quality` + `bbox/model` + `mission hash`가 하나의 event에 연결되어야 한다. `edge_clip` -3s/+5s는 후보이며 저장량/실측 능력에 따라 P1로 미룰 수 있다. RC 지속 low-proxy 영상과 별개 출처로 관리한다.

## 9. 실측 필수 실험

- DPK 설치/재시작/SDK lifecycle, Liveview lens stream availability, 30분 hover/move thermal/fps.
- 비변화 자세 반복값, yaw·gimbal burst, timestamp skew, frame drop, telemetry gap, RTK lost.
- MOP saturate/RC remote control coexistence, mission retransmit, Edge reboot with no duplicate, RC reconnect sequence recovery.
- 지면표본·실제 검사점 기반 geo error, 경계 밴드, lens zoom·thermal switch alignment.
- 가짜/유효하지 않은 새 timestamp를 넣으면 시스템이 `quality=unknown`으로 떨어지는가(negative test).

## 10. 관련 DJI 근거

- https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/advanced-function/liveview.html
- https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/basic-function/fc-subscription.html
- https://developer.dji.com/doc/payload-sdk-tutorial/en/function-overview/basic-function/time-syn.html
- https://developer.dji.com/doc/payload-sdk-tutorial/en/
