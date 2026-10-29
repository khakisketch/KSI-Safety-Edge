"""Operator responses (append-only; Core never infers verdicts from them).

Mirrors the batch/idempotency semantics of the flights batch endpoints
(``core/src/ksi_core/routers/flights.py::_ingest_batch``):
  - POST body ``{client_batch_id, items[]}``; all items must belong to one
    flight (else 422 FLIGHT_MISMATCH).
  - Reference integrity against the strongest factual references available:
    item ``site_id`` must equal the flight's site (422 SITE_MISMATCH);
    ``session_id``, where a flight session is known, must belong to this
    flight (422 SESSION_MISMATCH); ``candidate_id`` / ``evidence.source_id``,
    where a detection event or observation is known, must belong to this
    flight (422 *_CROSS_FLIGHT). Unknown references are accepted (offline
    FLIGHT_LOG-only observations may never have been registered); matches in
    another org are 404, never a leak.
  - Same ``client_batch_id`` + same content -> 200 idempotent replay with
    the original ``server_batch_id``.
  - Same ``client_batch_id`` + different content -> 409 CONFLICT.
  - Same ``response_id`` + same content (new batch, or twice in one batch)
    -> ``duplicate_ids``; same id + different content -> 409 CONFLICT, with
    nothing written (single commit at the end; all rejections precede it).
  - Concurrent duplicate posts race on UNIQUE rows: the loser rolls back and
    re-reads — identical batch content replays 200, anything else is 409
    (never a 500 IntegrityError).
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Request
from sqlalchemy.exc import IntegrityError

from .. import models
from ..auth import canonical_hash, error, require_valid, same_content
from . import authorize, use_db

router = APIRouter()

SCHEMA = "operator-response.v1"
ID_FIELD = "response_id"
KIND = "operator-responses"

REFERENCE_MODELS = (
    (models.DetectionEvent, "event_id"),
    (models.Observation, "observation_id"),
)


def _flight_or_404(db, principal, flight_id: str):
    return (
        db.query(models.Flight)
        .filter_by(org_id=principal.org_id, flight_id=flight_id)
        .first()
    )


def _reference_error(db, principal, flight, item):
    """422/404 for references provably outside this flight; None if OK."""
    if item.get("site_id") != flight.site_id:
        return error("CONTRACT_INVALID", "contract validation failed", 422, ["SITE_MISMATCH"])
    session = (
        db.query(models.FlightSession).filter_by(session_id=item.get("session_id")).first()
    )
    if session is not None:
        if session.org_id != principal.org_id:
            return error("NOT_FOUND", "session not found", 404)
        if session.flight_id != flight.flight_id:
            return error("CONTRACT_INVALID", "contract validation failed", 422, ["SESSION_MISMATCH"])
    for ref, code in (
        (item.get("candidate_id"), "CANDIDATE_CROSS_FLIGHT"),
        ((item.get("evidence") or {}).get("source_id"), "EVIDENCE_CROSS_FLIGHT"),
    ):
        if not isinstance(ref, str) or not ref:
            continue
        for model, column in REFERENCE_MODELS:
            row = db.query(model).filter(getattr(model, column) == ref).first()
            if row is None:
                continue
            if row.org_id != principal.org_id:
                return error("NOT_FOUND", "item not found", 404)
            if row.flight_id != flight.flight_id:
                return error("CONTRACT_INVALID", "contract validation failed", 422, [code])
    return None


@router.post(
    "/operator-responses",
    operation_id="ingestOperatorResponseBatch",
    summary="Batch-upload operator responses (idempotent by client_batch_id + payload hash).",
    tags=["responses"],
)
async def ingest_response_batch(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    if not isinstance(body, dict):
        return error("CONTRACT_INVALID", "invalid batch body", 422, ["BODY_NOT_OBJECT"])
    client_batch_id = body.get("client_batch_id")
    items = body.get("items")
    if not isinstance(client_batch_id, str) or not client_batch_id or not isinstance(items, list):
        return error("CONTRACT_INVALID", "contract validation failed", 422, ["SCHEMA: /client_batch_id|items"])
    for item in items:
        bad = require_valid(SCHEMA, item, check_digests=True)
        if bad is not None:
            return bad
    flight_ids = {item.get("flight_id") for item in items if isinstance(item, dict)}
    if len(flight_ids) != 1 or None in flight_ids:
        return error("CONTRACT_INVALID", "contract validation failed", 422, ["FLIGHT_MISMATCH"])
    flight_id = next(iter(flight_ids))
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flight_id)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        for item in items:
            bad = _reference_error(db, principal, flight, item)
            if bad is not None:
                return bad
        content_hash = canonical_hash(items)
        existing = (
            db.query(models.Batch)
            .filter_by(org_id=principal.org_id, flight_id=flight_id, kind=KIND, client_batch_id=client_batch_id)
            .first()
        )
        if existing is not None:
            if existing.content_hash == content_hash:
                return {
                    "accepted_ids": list(existing.accepted_ids),
                    "duplicate_ids": list(existing.duplicate_ids),
                    "durable": True,
                    "server_batch_id": existing.server_batch_id,
                }
            return error("CONFLICT", "same client_batch_id with different content", 409)
        accepted: list = []
        duplicates: list = []
        seen: dict = {}
        for item in items:
            iid = item[ID_FIELD]
            if iid in seen:
                if same_content(seen[iid], item):
                    if iid not in duplicates:
                        duplicates.append(iid)
                else:
                    return error("CONFLICT", f"same {ID_FIELD} with different content", 409)
                continue
            seen[iid] = item
            row = db.query(models.OperatorResponse).filter_by(response_id=iid).first()
            if row is not None:
                if row.org_id != principal.org_id:
                    return error("NOT_FOUND", "item not found", 404)
                if same_content(row.payload, item):
                    duplicates.append(iid)
                else:
                    return error("CONFLICT", f"same {ID_FIELD} with different content", 409)
            else:
                db.add(
                    models.OperatorResponse(
                        org_id=principal.org_id,
                        flight_id=flight_id,
                        response_id=iid,
                        payload=item,
                        canonical_hash=canonical_hash(item),
                    )
                )
                accepted.append(iid)
        server_batch_id = str(uuid.uuid4())
        db.add(
            models.Batch(
                org_id=principal.org_id,
                flight_id=flight_id,
                kind=KIND,
                client_batch_id=client_batch_id,
                content_hash=content_hash,
                server_batch_id=server_batch_id,
                accepted_ids=accepted,
                duplicate_ids=duplicates,
            )
        )
        try:
            db.commit()
        except IntegrityError:
            # Lost a UNIQUE race with a concurrent writer: roll back and
            # re-read. Identical batch content replays 200; anything else
            # is an honest 409, never a 500.
            db.rollback()
            raced = (
                db.query(models.Batch)
                .filter_by(org_id=principal.org_id, flight_id=flight_id, kind=KIND, client_batch_id=client_batch_id)
                .first()
            )
            if raced is not None and raced.content_hash == content_hash:
                return {
                    "accepted_ids": list(raced.accepted_ids),
                    "duplicate_ids": list(raced.duplicate_ids),
                    "durable": True,
                    "server_batch_id": raced.server_batch_id,
                }
            return error("CONFLICT", "concurrent write conflict; retry with fresh read", 409)
        return {"accepted_ids": accepted, "duplicate_ids": duplicates, "durable": True, "server_batch_id": server_batch_id}
    finally:
        db.close()


@router.get(
    "/flights/{flightId}/operator-responses",
    operation_id="listOperatorResponses",
    summary="List a flight's operator responses in insertion order.",
    tags=["responses"],
)
def list_operator_responses(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows = (
            db.query(models.OperatorResponse)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.OperatorResponse.id.asc())
            .all()
        )
        return {"items": [r.payload for r in rows]}
    finally:
        db.close()
