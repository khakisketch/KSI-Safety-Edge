# ADR-0001: Flight manifests are immutable per-flight revisions; sessions document boots

- Status: **Accepted for M1**
- Date: 2026-10-08
- Scope: `contracts/` (FlightUploadManifest v1, FlightSession v1), Core ingest (W2), Harness E2E (W3)

## Context

The source package (`KSI_Safety_Edge_v1.1_Spatial_Design/`, frozen) models one
manifest per flight and treats same-flight/different-manifest as a conflict
(T25). In real operation three things break that assumption:

1. An Edge reboot mid-flight starts a new boot (`edge_boot_id`) while the
   flight continues — provenance and sequence continuity must be recorded
   per boot, not per flight.
2. Mid-flight config changes (new bundle application) split one flight into
   several apply-intervals; events keep the settings that were effective
   when they were produced.
3. Late-retrieved evidence (e.g. camera SD originals) arrives after the
   first upload commit and must attach to the same flight without rewriting
   history.

## Decision

1. A manifest revision is **immutable**: once committed, its bytes, hashes
   and artifact list never change.
2. **Multiple manifest revisions per flight are allowed.** Flight
   completeness is derived from *all* of a flight's manifests, not from a
   single row.
3. `409 Conflict` is returned only for true content conflicts:
   - the same ingest session bound to a *different* manifest, or
   - the same manifest/session/batch id presented with *different* content
     (same id + same content is 200-idempotent).
4. `FlightSession v1` documents Edge boots and tracker epochs inside a
   flight, including which mission applications were effective.
5. A future FlightUploadManifest v2 *may* bundle revision sets
   (superset/lineage pointers); v1 stays frozen — additive change means a
   new `.vN` file, never new optional fields (see `contracts/README.md`).

## Consequences

- The Core server (W2) must aggregate manifests per flight for
  `upload_state`/`completeness` instead of reading a single row.
- Tests must cover: two manifests for one flight, and two boots in one
  flight session (Harness E2E).
- Late SD originals become a *new* manifest revision on the same flight;
  the earlier commit and its `durable_commit_id` are untouched.
