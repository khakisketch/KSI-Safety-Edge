# KSI Safety Edge — M0/M1 재생 슬라이스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development — 각 작업은 developer 서브에이전트가 수행하고, Primary(Build)가 통합·검증한다. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계약 기준선(M0)과 재생 기반 전체 흐름(M1: 임무 적용→RC 오프라인 저장→서버 업로드→조회, 장애 복구)을 구현하고 E01~E12를 통과한다.

**Architecture:** `contracts/`(스키마+OpenAPI+정책) + `libs/ksi_contracts`(canonical digest·도메인 검증) → `core/`(FastAPI 서버, SQLite dev, 파일 오브젝트 저장) → `harness/`(MOP 가상 전송, Edge 재생, RC 시뮬레이터, 장애 주입, E2E). 원본 패키지는 출처 snapshot으로 수정 금지.

**Tech Stack:** Python 3.12, uv, FastAPI, SQLAlchemy 2, SQLite(개발; PG 호환 지향), jsonschema+referencing, rfc8785, httpx, pytest, uvicorn.

**Spec:** `docs/superpowers/specs/2026-10-08-integrated-design.md` (통합 설계 기준본). 원본 패키지 `KSI_Safety_Edge_v1.1_Spatial_Design/`를 함께 읽는다.

## Global Constraints

- Edge→서버 직접 업로드 경로 금지. 업로드 주체는 RC 시뮬레이터.
- M1에서 ACTIVE 위험 경고를 만들지 않는다. 이벤트 mode 기본 SHADOW, 위험정책 mode SHADOW.
- 기존 v1 스키마 8개는 바이트 그대로 복사(수정 금지). 신규는 별도 파일.
- digest 필드(`content_sha256`, `manifest_sha256`, `bundle_sha256`, `zone_sha256`)는 **자기 자신을 제외한 canonical JSON**의 SHA256.
- 시간은 edge monotonic ns 정수. 서버 수신 UTC는 촬영 시각 대용 금지.
- 원본 패키지 파일은 수정·이동·삭제하지 않는다. 테스트는 결정적이어야 한다(고정 시드, 가상 시계).
- Python은 3.12, 패키지는 editable 설치(`uv pip install -e ...`). 비밀정보·실제 고객 데이터 금지.

## Review Focus (사람이 기대하는데 시험이 놓치기 쉬운 것)

1. 재전송·중복 ACK 후에도 서버/RC에 행이 정확히 1개인가 (E02·E04).
2. 크래시 후 재시작에서 WAL·임시 파일·업로드가 유실/중복되지 않는가 (E03·E06).
3. 업로드 완료 판정이 durable commit 없이 "완료" 표시되지 않는가 (E06·E07 toast-complete 금지).
4. 교차 조직 접근이 목록 필터·객체 다운로드에서도 차단되는가 (E12).
5. digest 재계산이 생성자와 검증자에서 동일한가 (golden vectors, libs 테스트).

---
## W1a: contracts/schemas + libs/ksi_contracts (Developer #1)

**Files (Create):**
- `scripts/setup_env.sh` — `uv venv .venv --python 3.12` + editable 설치(`libs/ksi_contracts`, `core`, `harness`) + 실행
- `libs/ksi_contracts/pyproject.toml` (hatchling), `src/ksi_contracts/{__init__,canonical,validators,schema_store}.py`, `tests/{test_canonical,test_validators,test_schemas}.py`
- `contracts/schemas/` — 기존 8개 바이트 복사 + 신규 5개: `mission-bundle.v1`, `mission-application.v1`, `flight-session.v1`, `detection-event.v1`, `flight-summary.v1`
- `contracts/examples/` — 기존 8개 복사 + 신규 5개 예제(digest 실제 계산값)
- `contracts/tests/test_contracts.py` — 전체 스키마×예제(스키마+도메인) + 부정 사례

**Interfaces (Produced):**
- `ksi_contracts.canonical`: `canonical_bytes(obj)->bytes`, `digest_sha256(obj)->str`, `digest_excluding(obj, field)->str`, `verify_digest(obj, field)->bool`
- `ksi_contracts.validators`: `validate_schema(name,obj)`, `validate_domain(name,obj)->list[str]`, `validate_all(name,obj)` (ContractError에 전체 오류 포함)
- `ksi_contracts.schema_store`: `schemas_dir()->Path` (env `KSI_CONTRACTS_DIR` 또는 상위 탐색), `validator_for(name)`, `load_schema(name)`
- 신규 스키마 필수 필드(전부 required, additionalProperties false):
  - **mission-bundle.v1**: schema_version, bundle_id, site_id, created_at_utc, origin[SERVER|RC_LOCAL], created_by(문자|null), mission_snapshot($ref), risk_policy($ref), calibration_refs[{calibration_revision_id,lens_id,sha256}], ground_model_refs[{ground_model_revision_id,kind[PLANE|DEM|UNKNOWN],sha256|null}], bundle_sha256
  - **mission-application.v1**: schema_version, application_id, flight_id, bundle_id, bundle_sha256, edge_boot_id, effective_edge_seq(int≥0), applied(bool), rejection_reasons[], excluded_items[{item_kind[ZONE|MODEL_PROFILE|RULE|CALIBRATION],item_id,reason}], applied_at_edge_monotonic_ns
  - **flight-session.v1**: schema_version, session_id, flight_id, edge_boot_id, started_at_edge_monotonic_ns, ended_at_edge_monotonic_ns|null, end_reason[COMPLETED|CRASHED|UNKNOWN]|null, mission_application_ids[], tracker_epochs[{camera_stream_id,tracker_epoch(int),lens_id,started_at,ended_at|null}]
  - **detection-event.v1**: schema_version, event_id, flight_id, observation_id, episode_id|null, event_type[DETECTED_ONLY|SPATIAL_CANDIDATE|ZONE_CANDIDATE|PROXIMITY_CANDIDATE|RISK_EVENT], mode[SHADOW|ACTIVE], detected_at_edge_monotonic_ns, zone_revision_id|null, policy_revision_id|null, evidence_artifact_ids[], initial_model_output(object, 자유 JSON), observation_quality[VALID|DEGRADED|UNKNOWN], mission_snapshot_hash
  - **flight-summary.v1**: schema_version, summary_id, flight_id, edge_boot_id, session_id, started_at_edge_monotonic_ns, ended_at_edge_monotonic_ns, ai_uptime_ratio(0~1), covered_frames, skipped_frames, gaps[{kind[VIDEO_GAP|AI_GAP|MOP_EVENT_GAP|UNKNOWN],start_ms,duration_ms,reason}], min_verified_person_equipment_distance_m(null 허용), person_count_max, equipment_count_max, quality_reasons[], created_at_edge_monotonic_ns
- **도메인 검증 규칙(validators.py, 위반 시 오류 메시지):** digest 일치(bundle/manifest/snapshot, 자기 필드 제외) · bundle.site_id==snapshot.site_id==policy.site_id · application.applied↔rejection_reasons 일관 · relation 거리 구간 순서·포함·VERIFIED⇒time_basis==CAMERA_CAPTURE_VERIFIED · summary start≤end·pair 수/최단거리 일관 · risk-policy rule_id 유일·proximity SHADOW/ACTIVE는 threshold 필수 · annotation REVOKE_PREVIOUS⇒supersedes 존재 · manifest rc_seq≤edge_seq·artifact_id 유일·프록시 segment_index 유일·manifest digest 일치 · flight-session ended≥started·end_reason↔ended 일관 · detection-event ACTIVE⇒policy_revision_id 필수 · observation bbox 순서·detection_id 유일 · mission-snapshot zone_id 유일·polygon 링 닫힘(첫=끝, ≥4점)

**Acceptance:** `scripts/setup_env.sh` 성공 후 `uv run pytest libs/ksi_contracts/tests contracts/tests -q` green. 기존 패키지의 변형 8종(역구간·UNVERIFIED+VERIFIED·역시간·쌍0+거리·미닫힘 polygon·revoke无target·중복 rule·rc>edge)이 **도메인 검증에서 거부**됨을 테스트로 증명. 원본 v1 스키마 8개의 SHA256이 원본 패키지와 동일.

- [ ] W1a-1 setup_env.sh + libs 패키지 골격, canonical(golden vectors 포함: rfc8785 사용, NaN/Infinity 거부, 자릿수 케이스)
- [ ] W1a-2 스키마 복사(8) + 신규 5 작성 + 예제 생성(digest 계산 도구 포함)
- [ ] W1a-3 validators 구현 + 전 규칙 단위 테스트 + 변형 8종 거부 테스트
- [ ] W1a-4 contracts/tests 러너(스키마×예제, 부정 사례) green, `uv run pytest` 보고

## W1b: OpenAPI freeze (Developer #1 — W1a 계속, 같은 세션)

**Files:** `contracts/openapi.yaml`, `contracts/tests/test_openapi.py`, `contracts/README.md`

**Interfaces (Produced):** base `http://127.0.0.1:8000/api/v1/edge`, bearerAuth(개발용 정적 토큰), Error{code,message,request_id?,details?}. 오퍼레이션(전부 openapi에 존재해야 함):
`GET /healthz`(no auth) · `GET/POST /sites` · `GET/POST /sites/{siteId}/zone-revisions` · `GET/POST /sites/{siteId}/risk-policies` · `POST /missions` · `GET /missions/{missionId}/snapshot` · `GET/POST /mission-bundles` · `GET /mission-bundles/{bundleId}` · `POST /flights` · `GET /flights` · `GET /flights/{flightId}` · `GET/POST /flights/{flightId}/mission-applications` · `GET/POST /flights/{flightId}/flight-sessions` · `POST/GET /flights/{flightId}/observations:batch`·관찰목록은 `GET /flights/{flightId}/observations` · `POST /flights/{flightId}/events:batch` · `POST/GET /flights/{flightId}/spatial-summaries(:batch)` · `POST/GET /flights/{flightId}/flight-summaries(:batch)` · `GET /flights/{flightId}/coverage` · `GET /events` · `GET /events/{eventId}` · `GET /events/{eventId}/evidence` · `GET /objects/{artifactId}` · `GET/POST /events/{eventId}/annotations` · `POST/GET /ingest-sessions`, `GET /ingest-sessions/{uploadId}` · `PUT /ingest-sessions/{uploadId}/objects/{artifactId}`(Content-Range, X-Content-SHA256) · `POST /ingest-sessions/{uploadId}/complete` → IngestCompleteResult{status[COMMITTED|PARTIAL], durable_commit_id|null, manifest_sha256, missing_artifact_ids[], committed_at_utc|null} · `GET/POST /review-jobs`, `GET /review-jobs/{jobId}`(P1 placeholder 기록)
- Batch 규약: 요청 `{client_batch_id, items[...]}` → 응답 `{accepted_ids[], duplicate_ids[], durable:true}`; 같은 `client_batch_id`+같은 내용 200 멱등, 다른 내용 409.
- 항목 스키마 `$ref`는 `./schemas/*.schema.json` 상대 참조. Flight 응답: `{flight_id, site_id, org_id, rc_device_id, mission_id, mission_revision_id, bundle_sha256, started_at_utc, ended_at_utc|null, upload_state[LOCAL_ONLY|UPLOADING|PARTIAL|COMPLETE|UNKNOWN], completeness{manifests_total, manifests_committed, missing_artifact_count}}`

**Acceptance:** `uv run pytest contracts/tests -q` green — YAML 파싱, 모든 $ref 해소, 위 오퍼레이션 존재, 항목 스키마 참조가 개별 schema 파일을 가리킴. `contracts/README.md`에 버전 정책·digest 제외 규칙·검증 4계층(schema/domain/reference/runtime)·ADR-0001(manifest는 flight별 불변 개정, 다중 세션 허용) 기록.

## W2: Core 서버 (Developer #2)

**Files:** `core/pyproject.toml`, `core/src/ksi_core/{__init__,config,db,models,auth,store,seed,app}.py`, `core/src/ksi_core/routers/{health,sites,missions,bundles,flights,ingest,events,annotations,review}.py`, `core/tests/{conftest,test_sites_missions,test_bundles_flights,test_ingest,test_events_annotations,test_tenant}.py`, `core/README.md`

**Interfaces:**
- 환경변수: `KSI_DB_URL`(기본 sqlite:///.../var/ksi_core.db), `KSI_OBJECT_ROOT`(기본 core/var/objects), `KSI_DEV_TOKENS`(JSON: `{"token": {"org_id": "...", "scopes": ["rc","read"]}}`; seed 기본 2개 org + agent read 토큰)
- Ingest 완료 규칙: 시작 시 전 객체 존재/크기/SHA256 재계산 검증 → 전 필수 충족 시 DB 트랜잭션으로 세션 COMMITTED + durable_commit_id 발급; 부족/불일치 시 200 PARTIAL(durable_commit_id null, missing 목록) — **거짓 완료 금지**. 같은 manifest_sha256 재호출 200 멱등. 같은 ingest 세션에 다른 manifest 409.
- Flight upload_state: 해당 flight의 모든 manifest 개정 committed → COMPLETE; 일부 open/failed 또는 미검증 아티팩트 → UPLOADING/PARTIAL; 로컬만 존재(등록만 됨) → LOCAL_ONLY.
- 배치 ingest: `client_batch_id`+canonical hash로 멱등/409.
- 교차 조직: 목록·상세·객체 모두 org 필터; 타 org 접근 404(존재 노출 금지), 같은 org 권한 부족 403.
- GET /objects/{artifactId}: 저장 파일 반환(Content-Type, sha256 헤더), audit_log 기록.
- GET /flights/{flightId}/coverage: flight-summaries로부터 `{summaries[], data_completeness[COMPLETE|PARTIAL|UNKNOWN]}` 계산(sample 있으면 저장 payload 반환).
- SQLAlchemy 모델: sites, zone_revisions, risk_policies(개정), missions(스냅샷 행), mission_bundles, flights, mission_applications, flight_sessions, ingest_sessions, ingest_objects, batches(멱등), observations, detection_events, spatial_summaries, flight_summaries, annotations, review_jobs, audit_log. payload는 검증 후 JSON 컬럼+색인 키 컬럼. schemas 검증은 `ksi_contracts.validate_all` 사용.

**Acceptance:** `uv run pytest core/tests -q` green + `uv run uvicorn ksi_core.app:create_app --factory` 스모크(healthz 200). 서버는 계약 위반 payload(도메인 오류)를 422로 거부. core/README.md에 실행법·한계(SQLite dev, 마이그레이션 후속).

## W3: Harness — MOP·RC 시뮬레이터·E2E (Developer #3)

**Files:** `harness/pyproject.toml`, `harness/src/ksi_harness/{__init__,clock,mop,edge_replay,rc_store,rc_sim,core_client,fixtures}.py`, `harness/tests/{test_mop,test_offline_store,test_apply,test_e2e}.py`, `harness/README.md`

**Interfaces:**
- MOP envelope(JSON 직렬화 강제): `{protocol_version:1, msg_id, channel[CONTROL_RELIABLE|EVENT_RELIABLE|EVIDENCE_TRANSFER|HEARTBEAT], type, flight_id, edge_boot_id|null, seq|null, ack_of|null, payload{...}, payload_sha256, sent_monotonic_ns}`. 수신측은 **영속 저장 후** ACK(ack_of=msg_id). sender 재전송은 동일 msg_id. 중복 수신은 (msg_id)로 무시. 채널·세션별 contiguous seq 추적, gap은 pending→(재전송 또는 `UNRECOVERABLE_GAP` 기록).
- 메시지 type: RC→Edge `MISSION_PREPARE{bundle}` `MISSION_APPLY{bundle_id,bundle_sha256}` `MISSION_STATUS_QUERY{}` `SESSION_START/STOP` / Edge→RC `MISSION_PREPARED{bundle_sha256,supported,exclusions[]}` `MISSION_APPLIED{application_id,effective_edge_seq}` `MISSION_APPLY_REJECTED{reasons[]}` `MISSION_STATUS{applied_bundle_sha256|null,edge_boot_id,running}` `OBSERVATION{observation}` `DETECTION_EVENT{event}` `FLIGHT_SUMMARY{summary}` `ARTIFACT_ANNOUNCE{artifact meta}` `ARTIFACT_CHUNK{artifact_id,offset,data_b64}` `HEARTBEAT{...}` `ACK{ack_of}`.
- `mop.FaultTransport`: in-proc 결정적(seeded) — drop/duplicate/reorder/delay/disconnect, `report_lost`로 복구 불가 선언.
- `rc_sim.RCStore`(SQLite WAL): messages(UNIQUE(edge_boot_id,channel,seq)), artifacts(파일+sha256+state), upload_queue, applications, annotations_outbox, gaps, flights. **ACK 응답 전 commit**.
- `rc_sim.RCSimulator`(가상 시계): 미션 캐시·PREPARE/APPLY 멱등·STATUS 재합의·재시작 복원(`reopen()`)·업로더(체크포인트 저장, GET status로 재개, complete 확인 후 SYNCED; 미완료는 LOCAL_PROTECTED).
- `edge_replay.EdgeReplay`: fixture 번들 로드 → 결정적 순서 메시지 방출(세션 재시작/설정 변경/드롭 시나리오 지원).
- `fixtures.build(bundle_sha256 등)`: flight-A(관측 60, 이벤트 4, 아티팩트 3, AI_GAP 1), flight-B(0건+AI 40% gap), flight-C(부팅 2회·비행 중 bundle v2). 생성은 결정적(시드 고정), 테스트는 tmp 디렉터리 사용.
- `core_client.CoreClient`: httpx — 위 openapi 오퍼레이션 호출, 배치/업로드/다운로드.
- E2E(pytest, core를 uvicorn 서브프로세스로 기동 + temp DB/스토어): **E01~E12** (아래). 각 시나리오는 서버 상태·RC DB 상태·파일을 직접 assert.

**E01~E12 요약** (상세 기대는 본 계획 Review Focus + 스펙):
E01 bundle PREPARE/APPLY digest 일치·적용 이력 서버 조회 / E02 APPLY ACK 유실→STATUS_UNKNOWN→STATUS_QUERY 합의, Edge 재시작 시 이전 ACK 재사용 금지·재적용 / E03 완전 오프라인 기록(코어 미기동)→ACK 후 RC 재시작 복원, 미전송 유지 / E04 중복·역순·지연 주입→중복 0, gap 감지 / E05 회복 불가 드롭→UNRECOVERABLE_GAP이 manifest gaps로 업로드 / E06 업로드 중단→GET status 재개→완료, 같은 manifest 2회 완료 멱등(durable_commit_id 동일) / E07 아티팩트 hash 손상→PARTIAL·durable 없음·SYNCED 아님→정정 재업로드→COMMITTED / E08 비행 중 bundle v2 적용→이벤트별 effective 설정 구분 조회 / E09 비행 후 zone v2 생성→과거 이벤트는 v1 유지 / E10 이벤트 0+AI gap→업로드 COMPLETE·이벤트 목록 0·gap 표현 동시 정확 / E11 annotation 추가·취소(append-only, 원본 불변) / E12 타 org 404·객체 다운로드 거부.

**Acceptance:** `uv run pytest harness/tests -q` green(E2E 포함). harness/README.md에 시나리오 ID↔테스트 매핑과 실행법.

## Verification

- 각 W 종료 시 해당 pytest 실행 + 리뷰(개발자 보고 + Primary diff 점검).
- 전체 통합 후 **test-runner 독립 실행**: `scripts/run_all_tests.sh` 전체 green + E2E 재실행. 결과를 본 원장에 기록.
- 커밋: C0 골격 → C1 contracts → C2 core → C3 harness(+E2E) → C4 정비. 커밋 메시지에 작업 범위·검증 명령 기록.

## 진행 기록 (Evidence Log)

- 2026-10-08: 저장소 골격 + 원본 snapshot + 설계 기준본 반영 (C0 예정).
