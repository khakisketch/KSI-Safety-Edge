# 20. API/MCP additive design suggestion (not deployed)

Do not mutate v1 Observation. New endpoints may be implemented under independent `/v1/spatial/...` paths, versioned OpenAPI after review:

- `GET /v1/flights/{flight_id}/spatial-summaries` — chronological relation/quality sampling. Tenant scoped.
- `GET /v1/events/{event_id}/spatial-context` — source observation, frame, two tracks, distance interval/status, zone revision, calibration & risk policy history.
- `GET /v1/flights/{flight_id}/coverage` — valid/partial/unknown areas + frames and reasons. Do not infer 100% coverage from route.
- `GET /v1/sites/{site_id}/risk-policies` — versioned read, changed-by and hash.
- `POST /v1/sites/{site_id}/risk-policies` — compare-and-swap parent revision; offline RC reconciliation.
- `POST /v1/flights/{flight_id}/spatial-summaries:batch` — idempotent manifest ingest; accepted/durable ACK only after DB/object consistent commit.
- MCP `get_flight_spatial_summary`, `get_event_spatial_evidence`, `get_zone_at_event_time`, `list_unknown_coverage` (read-only, cite evidence IDs, preserve withheld status).

**Authorization:** org/site scope checks on every endpoint, RBAC for modifying policies, audit entries for human edits and AI review. Media through expiring signed URLs; no raw cloud link hardcoding.
**Idempotency:** `flight_id + edge_boot_id + summary_id` and canonical payload hash; duplicate same payload 200/OK; duplicate ID different payload 409 conflict with audit.
**Time:** Event and policy effective sequence immutable; server received-at never substitutes acquisition time.
