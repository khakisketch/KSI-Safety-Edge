# contracts/ — machine contracts for KSI Safety Edge (M0/M1)

- `schemas/` — 8 legacy v1 schemas (byte-identical copies of
  `KSI_Safety_Edge_v1.1_Spatial_Design/contracts/schemas/`, frozen) + 5 new
  W1a schemas (`mission-bundle/application`, `flight-session`,
  `detection-event`, `flight-summary` `.v1`).
- `examples/` — 8 legacy examples (byte-identical) + 5 new examples with
  correctly computed digests.
- `openapi.yaml` — frozen API surface, base
  `http://127.0.0.1:8000/api/v1/edge` (see below).
- `tests/` — `test_contracts.py` (schema×example, 8-probe negative matrix,
  byte-identity), `test_openapi.py` (freeze checks).

## Version policy

- Schema files are **immutable**. `additionalProperties: false` everywhere,
  so "additive" cannot mean new optional fields — additive change ships as a
  new `.vN` file alongside the old one.
- Unknown `schema_version` values are rejected at the schema layer.
- Migration between versions is explicit (adapter code + tests), never
  silent coercion. The source package under
  `KSI_Safety_Edge_v1.1_Spatial_Design/` is never modified.

## Digest rule

- Digest fields (`content_sha256`, `manifest_sha256`, `bundle_sha256`) hold
  the hex SHA256 of the **RFC 8785 canonical JSON of the object excluding
  the digest field itself**.
- Implementation: `libs/ksi_contracts/src/ksi_contracts/canonical.py`
  (`canonical_bytes`, `digest_sha256`, `digest_excluding`, `verify_digest`,
  `set_digest`). Golden vectors live in
  `libs/ksi_contracts/tests/test_canonical.py`.
- Legacy examples carry placeholder digests (`aaaa…`/`bbbb…`): they validate
  with `check_digests=False` (the default runner mode for the 8 legacy
  copies) and are *rejected* with `DIGEST_MISMATCH` in strict mode. New
  examples carry computed digests and validate strict. Never "fix" legacy
  placeholders — they are the frozen fixture.

## Validation layers

1. **Schema** — types/required/enum/conditional fields:
   `libs/ksi_contracts/src/ksi_contracts/validators.py::validate_schema`
   (schemas via `libs/ksi_contracts/src/ksi_contracts/schema_store.py`).
2. **Domain** — cross-field meaning (interval order, period order, digest
   match, uniqueness, state consistency):
   `libs/ksi_contracts/src/ksi_contracts/validators.py::validate_domain` /
   `validate_all`. Error strings carry stable `CODE:` prefixes.
3. **Reference** — tenant/site/flight/boot/observation/evidence/config
   ownership and existence: Core server (W2).
4. **Runtime guard** — sensor quality, approval scope, apply history, device
   state gating ACTIVE: Core/Edge (W2+).

## M1 scope notes

- Auth is a dev static bearer token (`KSI_DEV_TOKENS` in W2); cross-org
  access → `404`, same-org insufficient scope → `403`.
- Batch contract: `{client_batch_id, items[...]}` →
  `{accepted_ids[], duplicate_ids[], durable: true, server_batch_id}`;
  same id + same content → 200 idempotent, different content → 409.
- Ingest: open → resumable chunk PUT (`Content-Range`, `X-Content-SHA256`)
  → complete; missing/invalid artifacts → honest `PARTIAL` with null
  `durable_commit_id` (no false completion).
- Review jobs are a **P1 placeholder** (record only, no execution).
- Manifest/session model: [ADR-0001](../adr/0001-manifests-and-sessions.md).
- Design baseline:
  `docs/specs/2026-10-08-integrated-design.md`;
  implementation plan:
  `docs/plans/2026-10-08-m0-m1-replay-slice.md` (W1b).
