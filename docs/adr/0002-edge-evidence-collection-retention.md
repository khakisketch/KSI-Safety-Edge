# ADR-0002: The Edge keeps the source bytes; collections are receipts, not ownership

- Status: **Accepted (implementation in progress, 2026-10-21)**
- Date: 2026-10-21
- Scope: Edge source-evidence store (`edge/src/evidence/`), RC review-readiness
  derivation (`android-rc/rc-app/.../ReviewReadinessModel.kt`), Core ingest
  artifact identity (`core/src/ksi_core/routers/ingest.py`)

## Context

Three related gaps kept the product from saying one honest sentence about
an observation: *"this is what we saw, here is the picture behind it, and
this is where it is stored."*

1. **The Edge had no evidence of its own.** The MOP transport spool
   (`edge/src/transport/durable_spool.cpp`) keeps *outbound envelopes* so a
   link drop loses nothing. That is a delivery guarantee. `Compact()`
   replaces acknowledged payloads with digest tombstones, and the pixels the
   detector actually consumed were never persisted anywhere. The observation
   on the RC was therefore a number with no picture behind it, and nothing on
   the Edge could re-derive it.
2. **"Ready to review" and "stored centrally" were one sentence.** The RC
   showed local artifact counts and server commit counts, but a reviewer
   could not tell "I can examine this observation right now, offline" from
   "the Core has this". Locally-present evidence was read as centrally
   stored, and un-uploaded evidence was read as "no analysis".
3. **Artifact identity was weaker than it looked.** The Core refused the
   same manifest id with different content, but the same `artifact_id` under
   a *different* manifest could carry a different digest. One id could then
   point at two different pieces of evidence, and a commit receipt of one
   flight would appear to prove bytes of another.

## Decision

1. **The Edge persists the source bytes of material observations.** A new
   module (`edge/src/evidence/store.cpp`, host-testable, PSDK-free) stores
   the exact frame bytes an observation was computed from, plus the lineage
   needed to re-derive the judgement (flight, boot, tick, frame id, receipt
   time, observation id, digest).
2. **The store is an origin, not a transport.** It has its own lifetime and
   its own directory (`<state-dir>/evidence`), separate from the MOP spool.
   Nothing in the delivery path may compact it.
3. **Writes are atomic; evidence is never overwritten.** Temp file →
   `fsync(file)` → `rename` → `fsync(dir)`. The same asset id with the same
   digest is idempotent; the same id with *different* bytes is refused
   (`CONFLICT`). A declared digest that does not match the bytes is refused
   (`INVALID`) and nothing is written.
4. **The store never evicts and never silently drops.** Capacity is an
   explicit bound; exceeding it refuses the write (`FULL`) and the run says
   so. A missing frame must be visible.
5. **The capture policy is explicit.** Only observations that actually saw
   something (a fresh detection) keep their source frame. An empty frame
   carries no review value and would fill the device in seconds.
6. **The link between bytes and judgement is a derivation, not a guess.**
   The source asset id and the MOP observation id are derived from the same
   (flight id, boot id, tick index); `MopTickEvidence::ObservationIdFor()`
   exists so the runtime and the transport cannot drift apart.
7. **A sealed revision names what a session covered.** Closing a session
   writes one immutable revision manifest (`revision_id`, asset ids, digests,
   sizes, lineage) that is written once and never edited. It is what a
   collector exports, and it is what proves completeness for that revision —
   not for the whole store.
8. **Custody is proven by receipts, never by "we have it somewhere".** The
   RC shows central storage only when a Core commit receipt
   (`durable_commit_id`) is stored locally.
9. **Two axes on the RC, never merged.** *Field-review ready* is a local
   fact derived from three axes that are not broken up: exact revision
   (mission revision and boot are identified), decodable (the values a
   reviewer needs are present), link consistent (flight/boot/frame sequence
   agree with what the RC recorded). *Centrally stored* is a separate
   server-confirmed fact. "Not confirmed" is not "not stored", and zero
   observations is "analysis result unknown", never a clean negative.
10. **Artifact identity is global in the Core.** The same `artifact_id` with
    a different digest is refused with `409 CONFLICT`
    (`ARTIFACT_ID_DIGEST_CONFLICT`); the same id with the same bytes stays
    idempotent. A refusal never touches stored evidence.
11. **Deletion is a dry-run until a retention decision is approved.**
    `COMMITTED` means "this manifest revision's required artifacts are
    committed" — not "the flight is complete" and not "permission to
    delete".

## Consequences

- The Edge can now show *what it saw* and re-derive a judgement from the
  stored bytes; a collector can export the same evidence to the Core.
- Storage is bounded and the bound is visible: on a device with a full
  store the run reports that the source frame of an observation was not
  retained. That is a reportable degradation, not a silent hole.
- The Edge's `evidence_artifact_ids` in MOP events remain empty: the v1
  observation/event wire is frozen, and the source assets travel through the
  revision manifest, not through the observation payload. The wire contract
  is not changed by this ADR.
- The capture policy (material observations only, 128 MiB bound) is a
  product decision that needs a device measurement: a real flight's frame
  size and detection rate decide whether the bound is comfortable. That
  measurement is **not** done yet.
- The RC readiness model and the Core identity check are implemented and
  unit-tested, but the Android suite could not be executed in the
  environment where this ADR was written (no Android SDK present); the new
  Kotlin files were compiled and run directly with the Kotlin compiler and
  JUnit instead.

## Rejected alternatives

- **Extend the MOP spool to keep payloads.** Rejected: the spool's job is
  delivery, its compaction policy is driven by acknowledgements, and mixing
  origins into it would make "the bytes are gone" indistinguishable from
  "the bytes were never kept".
- **Ship the source frame inside the observation payload (v1).** Rejected:
  the observation wire is frozen and a frame per observation would not fit
  the transport budget; the revision manifest is the producer-neutral
  carrier.
- **Let the store evict the oldest asset when full.** Rejected: silent loss
  of the picture behind an observation is exactly the failure this product
  exists to prevent.
- **Treat "the server has it" as proven by a successful upload.** Rejected:
  an upload that was not committed is not custody.

## Follow-ups (not decided here)

- A Core **collection receipt** surface (a `.vN` contract for the
  Edge → PC collector → Core path that carries the sealed revision) is
  still an open design item; the v1 ingest surface stays frozen.
- A retention/deletion policy beyond the dry-run (what may be removed, who
  may authorise it, and what the receipt then means).
- Whether the RC should be able to *request* a source frame on demand
  (review pull) rather than only keep material observations eagerly.
