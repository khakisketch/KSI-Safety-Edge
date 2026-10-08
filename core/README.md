# core/ — KSI Safety Edge core server (M1 replay slice)

FastAPI + SQLAlchemy 2 + SQLite (dev) + filesystem object store.
Implements the frozen surface in `contracts/openapi.yaml` (base
`/api/v1/edge`). Validation goes through `ksi_contracts.validate_all`;
no schema logic is reimplemented here.

## Run

```bash
bash scripts/setup_env.sh          # venv + editable installs + dev deps
.venv/bin/python -m pytest core/tests -q
KSI_DB_URL="sqlite:///$(pwd)/core/var/ksi_core.db" \
KSI_OBJECT_ROOT="$(pwd)/core/var/objects" \
  .venv/bin/uvicorn ksi_core.app:create_app --factory --port 8000
curl localhost:8000/api/v1/edge/healthz   # {"ok": true}, no auth
```

## Env vars (read once, at `create_app()` time)

| var | default | meaning |
|---|---|---|
| `KSI_DB_URL` | `sqlite:///<repo>/core/var/ksi_core.db` | SQLAlchemy URL |
| `KSI_OBJECT_ROOT` | `<repo>/core/var/objects` | chunk/object file root (`<upload_id>/<artifact_id>`) |
| `KSI_DEV_TOKENS` | JSON (see below) | static bearer tokens, dev only |

Default tokens:

```json
{"tok-orgA-rc": {"org_id": "org-a", "scopes": ["rc", "read"]},
 "tok-orgA-agent": {"org_id": "org-a", "scopes": ["read"]},
 "tok-orgB-read": {"org_id": "org-b", "scopes": ["read"]}}
```

## Semantics

- **Auth**: missing/invalid bearer → `401`. Reads need `read` (or `rc`);
  **all writes need `rc`** (`read` is read-only everywhere, M1
  simplification). Cross-org access by id → `404` (never `403`, no
  existence leak); same-org insufficient scope → `403`.
- **Payload validation**: every schema-mapped body goes through
  `validate_all(name, payload)` with digest checks on; failure → `422`
  `{code: "CONTRACT_INVALID", details: [...]}`. Tampered
  `bundle_sha256`/manifest digests surface as `DIGEST_MISMATCH` details.
- **Idempotency/conflicts**: same id + same canonical bytes → `200`
  same response (batches return the SAME `server_batch_id`; re-`complete`
  returns the same `durable_commit_id`); same id + different content →
  `409 {code: "CONFLICT"}`. Batch items already stored with identical
  content land in `duplicate_ids`; changed content → `409`.
- **Ingest** (`flight-upload-manifest.v1` is the POST body, digest-verified):
  open returns `{upload_id, status: NEW|RESUMING}` — reposting the same
  `manifest_sha256` resumes the same session. The manifest is frozen per
  session: `complete` with another sha → `409`. Chunks arrive via
  `PUT …/objects/{artifactId}` with `Content-Range: bytes s-e/total`
  (must match the manifest size); `X-Content-SHA256` is accepted as a hint
  but the server always recomputes size+SHA256 at `complete`. Received
  ranges are stored as merged intervals, so out-of-order/resumed writes and
  whole-object re-uploads (overwrite) work. `complete` returns
  `COMMITTED + durable_commit_id` only when every **required** artifact is
  fully received with matching SHA256; otherwise honest `200 PARTIAL`
  (`durable_commit_id: null`, `missing_artifact_ids` = incomplete or
  hash-mismatched **required** artifacts). Optional artifacts never block
  COMMITTED and never appear in `missing_artifact_ids`.
- **Flight `upload_state`** (derived over all of the flight's sessions):
  no sessions → `LOCAL_ONLY`; all `COMMITTED` (≥1) → `COMPLETE`; any
  `PARTIAL`, or a mix of committed and not → `PARTIAL`; any `OPEN` (none
  partial/committed) → `UPLOADING`. `completeness` =
  `{manifests_total, manifests_committed, missing_artifact_count}` where the
  count is the union of required-missing across non-committed sessions.
  Multiple manifests per flight are allowed (ADR-0001); late evidence is a
  new manifest revision, never a rewrite.
- **Coverage**: summaries ≥1 + flight `COMPLETE` → `COMPLETE`;
  summaries ≥1 → `PARTIAL`; none → `UNKNOWN`.
- **Zone/policy CAS**: first revision needs `base_revision_id: null` /
  `expected_parent_revision_id: null`; later writes must name the current
  tip, else `409`.
- **Objects**: `GET /objects/{artifactId}` serves bytes of artifacts in
  `COMMITTED` sessions only (`Content-Type` from the manifest,
  `X-Content-SHA256` header), else `404`; every download is audit-logged.
- **Annotations** are append-only; a revocation is a second record with
  `annotation_type: REVOKE_PREVIOUS` + `supersedes_annotation_id`.
- **Review jobs** are a P1 placeholder (record only, idempotent by
  `idempotency_key`).
- **Lists**: insertion order, `limit` default 100 / max 500, `cursor` is an
  opaque row-id string (`next_cursor` null when done).

## Limits (dev)

SQLite with `create_all` on startup — no Alembic migrations yet.
Dev static tokens only (no expiry/rotation). Single worker assumed
(SQLite file + local files). No network calls; deterministic tests use
`tmp_path` DB/object roots via env overrides.

## Tests

```bash
.venv/bin/python -m pytest core/tests -q
```

`test_sites_missions` (auth matrix, zone CAS, snapshots, bundles),
`test_ingest` (state machine LOCAL_ONLY→UPLOADING→PARTIAL→COMPLETE,
corrupt→fix, frozen sessions, two-manifest ADR-0001),
`test_batches_events` (batch idempotency/conflicts, all four batch kinds,
evidence/download/annotations, coverage, review, **openapi parity**,
audit log).
