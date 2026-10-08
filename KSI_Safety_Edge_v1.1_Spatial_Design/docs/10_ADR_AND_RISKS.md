# 10. Architecture Decision Records · Risk Register

## ADR 인덱스

### ADR-001 — Off-line-first RC + RC-only uplink [ACCEPTED FOR EXPERIMENT]
**Decision:** Edge Manifold는 인터넷 직접 업로드하지 않는다. PSDK↔MSDK MOP로 이벤트를 전송, RC는 오프라인 로컬 저장과 서버 업로드를 담당. 향후 Dock는 별도 adapter.
**Rationale:** 서버 전송 주체 중복/상태 불일치 방지, 현장에서는 RC가 임무와 로컬 증거를 한눈에 관리.
**Consequences:** RC 분실/파손·업로드 지연 위험 증가 → Edge bounded spool, aircraft SD, preflight RC storage, verified manifest 필요. 네트워크 연결 안 되면 서버 분석도 없음.

### ADR-002 — Live video is DJI stream + event overlay, not Edge re-encoded video [ACCEPTED, OVERLAY GATED]
**Decision:** RC는 DJI가 제공하는 원본 라이브 화면을 보여준다. PSDK는 별도의 인퍼런스 frame+telemetry로 분석 후 MOP 결과 전송. 별도 AI boxed video를 리인코딩하여 기본 스트림으로 재전송하지 않음.
**Risk:** 두 영상 경로 Frame/zoom/time 불일치. 정밀 overlay는 실기 검증 전 불활성, snapshot event card 우선.

### ADR-003 — PSDK is authoritative for real-time georeferenced AI [PROPOSED/PHYSICAL GATE]
**Decision:** Edge PSDK topic/video matching/quality gate. MSDK telemetry는 RC UI/운영보조로 사용, 동일 판단 이벤트에 혼합 X.
**Risk:** PSDK frame exposure time unknown; PPS != image sync guarantee. 실제 ground-truth에서 실패시 Active geofence 차단.

### ADR-004 — Web minimal + agent-native Core [ACCEPTED FOR EXPERIMENT]
**Decision:** 웹은 site/zone/event/action/admin 중심, 라이브 영상 중계·자체 대화 UI 제외. 핵심 제품은 데이터/API/MCP.
**Consequence:** 비개발 관리자에게 여전히 최소 웹/RC의 이해하기 쉬운 UX가 필수.

### ADR-005 — Immutable mission revision & event provenance [ACCEPTED]
**Decision:** time-specific zone revision/snapshot을 flight/event에 저장. RC offline writes use revision CAS, no last-write-wins.
**Risk:** conflict UX/스토리지 비용, complexity. 사건 판단 근거보존 가치 우선.

### ADR-006 — Human annotations not ground truth [ACCEPTED]
**Decision:** 기본값 미검토; '오탐 의심', '확인 필요'만 append-only. 원본 delete/override 금지; GT는 별도 검증.
**Consequence:** 이벤트 카운트와 UI filtered inbox 카운트를 구분.

### ADR-007 — DGX Spark optional offline batch [ACCEPTED FOR EXPERIMENT]
**Decision:** 야간 FP/FN 후보/복합 행동 분석은 유효성 검증 후 선택적, real-time alarm path에 참여 안 함. 외부 상시 GPU 구축 불필요.
**Consequence:** 결과가 다음 출근 전 준비되는 조건은 RC 업로드 성공 + worker 가용성이 전제. 미업로드는 대기, 지연 솔직하게 표시.

### ADR-008 — Single source prototype does not supersede current KSI production [MANDATORY]
**Decision:** 기존 `ksi-platform` 운영 Cloud API baseline/Shadow/rollback protected. 별도 실험 제품은 프로토타입과 별도의 data namespace에서 검증, 합병/전환은 이후 승인.
**Consequence:** 기존 개발 문서·코드와 충돌 검토 필수.

## Risk Register

| ID | 영향 | 가능성 | 대응 | Owner/Release gate |
|---|---|---|---|---|
| R-01 | Edge 영상 노출 시점과 pose 불일치 → 틀린 위험구역 확정 | 높음 | source-time provenance + 실측+uncertainty, geo fail closed | PSDK/G1,G3 |
| R-02 | RC 라이브 vs Edge frame overlay drift | 높음 | overlay 검증 전 snapshot 카드로 대체 | Android/G1 |
| R-03 | RC 오프라인 대용량 비행 영상으로 저장 공간 소진 | 높음 | 720p proxy 실측, 용량 예약, sync/retention guard | Android/G5 |
| R-04 | Edge MOP unlink/seq loss로 사건 일부 누락 | 높음 | spool+ACK+manifest/gap audit, partial state | Edge/RC/G4 |
| R-05 | RC 분실/파손 이전 업로드 못한 증거 소실 | 중간~높음 | SD 원본, 짧은 Edge buffer, RC encryption, 정기 Wi-Fi sync 경고 | 제품/G5 |
| R-06 | 웹/RC 위험구역 동시수정/시간 버전 혼란 | 높음 | immutable revision + CAS conflict + applied seq | Core/G7 |
| R-07 | Position pixel ray/AGL/CRS 오류 | 높음 | golden vectors + surveyed GT + geo no-active until proven | Geo/G3 |
| R-08 | 사람의 '오탐'이 곧 학습 label로 오염 | 중간 | annotation/adjudication 분리 + provenance audit | AI/G8 |
| R-09 | AI 야간 분석이 미업로드 사건을 안전으로 처리 | 중간 | `PARTIAL/NOT_UPLOADED` reporting + job completeness gate | Core/G8 |
| R-10 | 모델 성능/열/전원 부족 | 중간 | FPS thermal test, graceful degradation; feature/profile gates | Edge/G2 |
| R-11 | 기존 Cloud API baseline 손상 | 높음 | 별도 branch/namespace, source authority, explicit rollback | Build/G9 |
| R-12 | 개인영상/위치 유출 | 높음 | encryption/RBAC/retention/signed URLs/audit | Security/G7 |
| R-13 | DJI/Android SDK 버전 불일치 | 중간 | pinned matrix, no assumed compatibility, device gate | Edge/RC/G0 |
| R-14 | 관측 커버리지를 '점검완료'로 오해 | 높음 | coverage confidence/unseen ranges, cautious reports | Product/G7 |

## 보류 질문 (물리·조직 결정)

- 사용자 조직별 RC 사용 권한, 오프라인 인증 허용 기간, 원격 wipe 요구 여부.
- 고위험 발견 사건을 조종사가 오탐 표시했을 때 관리자 재검토 정책.
- 한 비행 안에서 구역 변경 허용 빈도/비행 영향, 지도 좌표 정확성.
- 실험 초기 모델·렌즈·고도·거리 operating envelope, cloud storage/비용 보존기간.
- camera SD 자동 수집 허용 여부, 이벤트 스냅샷의 실제 MOP 전달 한도.

**데이터 분류:** `accepted for experiment`는 설계 방향, `physical gate`는 실제 장비 시험 전 동작 보증 X, `mandatory existing`는 기존 코드/운영 보존 의무.
