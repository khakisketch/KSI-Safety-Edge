# 05. KSI Safety Core 백엔드 및 API 설계

상태: 제안된 별도 `/api/v1/edge/...` surface, 기존 `ksi-platform` 배포 라우트와 **호환/중복 검토 전**. 인증 구현 및 라우트 이름은 코드베이스 대조 후 확정.

## 1. 서버 모듈

| 모듈 | 책임 | 제외 |
|---|---|---|
| Identity/AuthZ | 조직 사용자·RC 기기 등록, 현장 스코프, 액세스 감사 | DJI 조종권 대체 |
| Site Registry | Site/WorkArea/Zone immutable revisions, snapshot | 실시간 PSDK 제어 |
| Mission Registry | 임무 정의, Edge 적용 revision/digest 이력 | RC 비행 시작/조종 |
| Flight Ingest | RC upload sessions/manifest/파일 ACK/검증 | PSDK 직접 egress |
| Incident/Evidence Ledger | Observation/Event/Evidence/Annotations append-only | raw detector 결과 재작성 |
| Video Catalog | RC proxy segments/Camera SD source link/metadata | 기본 live relay/transcode |
| Coverage/Quality | 관측 유효구간, 영상/AI gaps, zone coverage estimates | 안전 100% 보증 |
| Review Queue | 인간 리뷰 이력 및 optional DGX 배치 | 실시간 경보 gate |
| Read API / MCP | 현장/비행/사건/증거와 근거 조회 | 자동 위험 확정 |
| Audit/Retention | actor action/파일 lifecycle/보존 정책 | 사용자 자료 무단 삭제 |

## 2. REST API 제안

각 endpoint는 서비스 코드와 프리픽스를 일치시킨다. 기본 `Authorization` OIDC/OAuth2 bearer 또는 등록 RC device token + user context; 모든 read/write에 org/scope 확인.

| Endpoint | 목적 | 멱등/경계 |
|---|---|---|
| `GET /api/v1/edge/sites` | 내 현장 조회/RC 동기화 delta | ETag/cursor |
| `POST /api/v1/edge/sites` | 현장 생성 | client_request_id |
| `GET /api/v1/edge/sites/{siteId}/zones` | 구역 현재·버전 목록 | `as_of` 조회 |
| `POST /api/v1/edge/sites/{siteId}/zone-revisions` | 새 immutable 위험구역 변경 | `base_revision_id`, 409 충돌 |
| `POST /api/v1/edge/missions` | 온라인 임무 설계 | content digest, site scope |
| `GET /api/v1/edge/missions/{missionId}/snapshot` | RC 사전 다운로드 | immutable, ETag |
| `POST /api/v1/edge/flights` | RC 오프라인 비행 ID 등록/업로드 선언 | idempotency key |
| `POST /api/v1/edge/ingest-sessions` | manifest 업로드 세션 | frozen manifest hash |
| `PUT /api/v1/edge/ingest-sessions/{id}/objects/{objId}` | resumable evidence upload (or presigned) | content hash+chunk range |
| `POST /api/v1/edge/ingest-sessions/{id}/complete` | 서버 검증 및 durable commit | ACK contains durable_commit_id |
| `GET /api/v1/edge/ingest-sessions/{id}` | 업로드 재개 상태 | missing files/chunks |
| `GET /api/v1/edge/flights/{id}` | 비행·coverage·upload health 조회 | partial flag |
| `GET /api/v1/edge/flights/{id}/observations` | 실제 관측 기록 | cursor paging |
| `GET /api/v1/edge/events` | 사건 목록 (site/flight/filter) | tenant isolation |
| `GET /api/v1/edge/events/{id}` | 사건+snapshot 적용 이력 | provenance links |
| `GET /api/v1/edge/events/{id}/evidence` | 이벤트별 이미지/영상 증거 | signed URL/permissions |
| `POST /api/v1/edge/events/{id}/annotations` | 운영자 오탐 의심/확인 필요 | append-only, offline client_id |
| `GET /api/v1/edge/review-jobs` | 사후 배치 분석 이력 | status independent from events |
| `POST /api/v1/edge/review-jobs` | 선택적 VLM task enqueue | async only, idempotent |
| `GET /api/v1/edge/review-jobs/{id}` | 원본/근거/미완료 구분 | never overwrite detector |

업로드 전 원격 site ID가 없는 임시 현장은 `client_site_id` namespace로 작성 후 서버에 canonical mapping을 부여한다. 충돌 및 조직 스코프 불일치는 파일 업로드 완료로 자동 해결되지 않는다.

## 3. 데이터 저장 / DB (제안)

- PostgreSQL: `organizations, users, devices, sites, work_areas, zones, zone_revisions, missions, mission_snapshots, flights, flight_quality_windows, observations, detection_events, evidence_objects, upload_sessions, upload_chunks, operator_annotations, review_jobs, review_findings, actions, audit_log`.
- Object Storage (S3-compatible e.g. MinIO): `org/site/flight/source_kind/artifact_id/version` 경로. 업로드 시 SHA-256, size, codec, content-type, capture provenance, committed time. 경로만 ID로 쓰지 않음.
- Redis/Celery/DB queue: 배치 VLM optional, 핵심 upload ACK는 일반 DB transaction과 분리 작업을 안전하게 조율.
- 단일 Writer: 특정 `event_id`, manifest, immutable snapshot에 관해 중복 insert는 idempotent; 다른 hash면 conflict.

## 4. 영구 commit 규칙

1. ingest session UUID와 expected object manifest 고정.
2. 서버 hash/size + MIME/codec/file parse 검사(보안), S3 `HEAD` 혹은 읽기 검증.
3. DB에서 `evidence_object.status=VERIFIED`, `upload_session=COMMITTED`, `flight completeness`를 한 transaction으로 고정.
4. 응답은 `durable_commit_id`, `manifest_digest`, `received_objects`, `missing_objects`, `completeness_status`, `committed_at`.
5. retry/partial/백엔드 재시작 시 중복 저장 없이 이어감. `SYNCED`은 complete and committed에만 해당.
6. 누락된 필수 object로 일부 상태만 존재한다면 `PARTIAL` + `missing_reason`, 재시도 가능.

## 5. 사건·사후 검토 분리

```
raw_event (id, detector_event, model_digest, mission_revision, edge_frame, immutable)
   ├─ evidence_objects[] (immutable sha256)
   ├─ operator_annotations[] (오탐 의심, 확인 필요, 취소; append-only)
   ├─ review_findings[] (VLM candidate/uncertain; model version, prompt version)
   └─ adjudications[] (검증된 평가용 GT, 별도 권한/근거)
```

`operator_annotation` 또는 `review_finding`이 들어와도 `raw_event`는 수정되지 않는다. 서비스의 리스트 기본 필터는 오탐 의심 사건을 접을 수 있지만 원본 카운터/품질 지표에서는 유지한다. 고위험 사건 오탐 표시는 별도 사람이 확인하도록 업무 규칙 제공.

## 6. API/MCP Read Tools (첫 출시)

- `list_sites`, `get_site_zones(at_time|revision)`, `list_flights(site, date range)`, `get_flight_coverage(flight)`, `list_events(site, severity, review_status)`, `get_event_with_evidence(event)`, `get_upload_health(flight)`.
- 모든 결과는 `data_status=COMPLETE/PARTIAL/NOT_UPLOADED/ANALYSIS_PENDING/UNKNOWN` 포함.
- LLM이 임의로 안전 판정을 내리지 않도록 숫자/입증 가능한 사건/미관측 영역/근거 URL만 노출. `0 incidents` 문구에는 `observation_coverage`와 `quality` 같이 전달.
- `request_postflight_review`는 후속 P1/승인형 action tool로, 멱등키/비용예산/권한 필수. P0는 read-only 우선.
- 기관별 격리와 제한시간 URL 적용. 외부 모델에 얼굴/차량번호 등 영상 직접 전달 여부는 별도 사용자/보안 정책 동의 필요.

## 7. 보고서 예시(서버/에이전트)

```text
A현장 10/08 오전 비행: 업로드 완료(고화질 원본은 로컬 미회수)
관측: zone_v4 중 61% 유효 시야, 28% 미관측, 11% 품질불확실(예시 숫자)
탐지: PPE 후보 3건(조종사 오탐 의심 1건), 위험구역 1건(좌표 확정 검증 필요)
사후 VLM: 2개 검토 완료, 1개 대기 / 미탐 후보는 표본 구간만 검사
오늘 작업 전: 고위험 사건 검토 및 미관측 구역 재점검 제안
근거: mission snapshot, flight, event, evidence IDs
```

위 수치는 예시이며 실제 서비스 보고서엔 실제 근거 수치만 사용.

## 8. 스케일/자원 지표

- 파일 규모: RC proxy 30분/1~2Mbps ≈225~450MB + 사건 이미지·클립 + optional 카메라 SD 원본.
- 전송량: 10회 비행이면 단순 proxy 약 2.25~4.5GB/일(예시). 동영상 무조건 원본 저장/무기한 보존은 비용 상승.
- 객체 저장 보존/수명주기, media tiers, 미업로드 경고, 필드별 접근 감사, 만료 정책.
- 3rd-party S3/NAS 선택은 운영환경·보안·백업과 함께 결정.
