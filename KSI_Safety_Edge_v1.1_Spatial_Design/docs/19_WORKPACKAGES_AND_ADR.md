# 19. 의존성 중심 개발 순서 / ADR / 미해결 질문

## A. 개발 작업 패키지
| Task | 담당 책임 | 산출물 | 블로커 / Gate |
|---|---|---|---|
| S00 | Tech Lead | repo code/current deployment truth, 기존 Cloud rollback 고정 | 이전 AGENTS.md 보존 |
| S01 | PSDK Engineer | capability spike: frame/pose/zoom/time domains | G0 실기 |
| S02 | Backend / Schema | spatial-relation/summary/policy contracts + migration strategy | S01 없이도 병렬 개발 |
| S03 | AI Engineer | person/equipment class map, TensorRT benchmark, tracker | model accuracy + G1 |
| S04 | PSDK Engineer | camera frame/FC buffers, matcher, quality gates | S01 |
| S05 | Geometry Engineer | camera calibration, ground ray + uncertainty + GT tests | S01/S04/G2 |
| S06 | AI+Geometry | footprint anchors, pair candidate + spatial summary | S03/S05 |
| S07 | Domain/Backend | policy modes, episode lifecycle, zone revision | S02/S06 |
| S08 | Android/MSDK | mission policy download, MOP config ACK, live incident/quality UI | S02/S07 |
| S09 | Android/Backend | local recording, upload queue, durable ACK, query API | 독립 병렬, G4 |
| S10 | QA/Field | multi-altitude GT, negative injection, shadow episode | S03~09 G1/G2/G3/G4 |
| S11 | Lead/Owner | Active rule-by-rule approval or hold, ADR | 독립 G2/G3 pass |
| S12 | Data/Agent | APIs/MCP spatial evidence queries, optional night VLM | first complete flight |

## B. ADR
- **ADR-S01 ACCEPTED AS DESIGN:** Manifold 3 handles detection+tracking+spatial relations. RC handles offline record, upload, UI. No direct PSDK→server route.
- **ADR-S02 ACCEPTED AS DESIGN:** Core keeps original observation immutable. SpatialRelation/SpatialSummary are additive versioned events.
- **ADR-S03 ACCEPTED AS DESIGN:** always compute valid spatial relations, but enable warnings only after verified geometry+policy Gate.
- **ADR-S04 ACCEPTED AS DESIGN:** dynamic boom/swing radius and personalized worker tracking out of P0.
- **ADR-S05 ACCEPTED AS DESIGN:** Agent reads evidence via API/MCP, nightly DGX optional, neither controls live flight/safety warning.
- **ADR-S06 PROPOSED:** single detector for person/equipment vs per-class detection; choose by Manifold FPS/accuracy A/B evidence.
- **ADR-S07 PROPOSED:** all-frame server archiving rejected; edge only event/summarized relation and RC low-proxy plus selective snapshots.

## C. 아직 결정·실측이 필요한 사항
1. **Lens & calibration:** Matrice4E/4T wide/tele/thermal source per mission. intrinsics/zoom/distortion 캘리브레이션 제공 범위와 외부 표적 기반 보정.
2. **Time:** PSDK Liveview source PTS/frame ID의 의미와 FC topic sample time, PPS domain, 실제 촬영 순간 대응 오차.
3. **Ground:** 평탄 지면만 P0으로 제한할지, 현장 정사영상/DSM 지면고도를 입력받을지.
4. **Equipment footprint:** bbox→지면접점 정확도; segmentation/keypoint 필요 여부. 장비 dynamic posture 이해는 별도 scope.
5. **Risk thresholds:** 장비 종류·현장 작업방식별 정책 작성 주체, 임계값, uncertainty margin, 사람이 승인하는 Active 구역.
6. **Resource budget:** Manifold 3 대상 해상도/입력 FPS, CPU/GPU/DLA 사용량, heat throttle, concurrent PPE count.
7. **RC media:** proxy 동영상 인코딩과 실제 storage space, 기체 SD 원본 다운로드 시점, 오프라인 authentication lifecycle.
8. **Version transport:** MOP real throughput, offline mission 변경, setup hash, resume after Edge restart.
9. **Rollout:** Shadow/Active 승인 책임자, severity와 비행 조작 UI의 우선순위, 법적 안전 판단 책임.

## D. 개발 완료 증거
feature PR에는 config/model hash, test data/commands, golden vectors, negative tests, RC/Edge 실제 동작 증거 또는 미실측 경고, 모듈 health & fallbacks, migration/rollback, reviewer sign-off 포함.

## E. 현장 실험을 위한 MVP 단계
- **MVP-A**: PSDK person+excavator 탐지/Tracking + local RC events (거리 출력 없음).
- **MVP-B**: geometry shadow: 사람/장비 ground anchor + 거리 interval + zone relation (경고 off).
- **MVP-C**: 독립 실측거리·zone Gate pass인 환경에서 선택적 rule Active.
- **MVP-D**: Core+Agent 증거 조회 + 필요시 DGX 사후 VLM.
