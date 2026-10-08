"""Operator annotations (append-only; revocation is a new record)."""

from __future__ import annotations

from fastapi import APIRouter, Request

from .. import models
from ..auth import canonical_hash, error, require_valid
from . import authorize, use_db

router = APIRouter()


@router.get("/events/{eventId}/annotations")
def list_annotations(eventId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        event = (
            db.query(models.DetectionEvent)
            .filter_by(org_id=principal.org_id, event_id=eventId)
            .first()
        )
        if event is None:
            return error("NOT_FOUND", "event not found", 404)
        rows = (
            db.query(models.Annotation)
            .filter_by(org_id=principal.org_id, event_id=eventId)
            .order_by(models.Annotation.id.asc())
            .all()
        )
        return {"items": [r.payload for r in rows]}
    finally:
        db.close()


@router.post("/events/{eventId}/annotations")
async def create_annotation(eventId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    bad = require_valid("operator-annotation.v1", body, check_digests=True)
    if bad is not None:
        return bad
    db = use_db(request)
    try:
        event = (
            db.query(models.DetectionEvent)
            .filter_by(org_id=principal.org_id, event_id=eventId)
            .first()
        )
        if event is None:
            return error("NOT_FOUND", "event not found", 404)
        if body.get("event_id") != eventId:
            return error("CONTRACT_INVALID", "contract validation failed", 422, ["EVENT_MISMATCH"])
        existing = db.query(models.Annotation).filter_by(annotation_id=body["annotation_id"]).first()
        if existing is not None:
            if existing.org_id != principal.org_id:
                return error("NOT_FOUND", "annotation not found", 404)
            from ..auth import same_content

            if same_content(existing.payload, body):
                return {"annotation_id": body["annotation_id"], "durable": True}
            return error("CONFLICT", "same annotation_id with different content", 409)
        db.add(
            models.Annotation(
                org_id=principal.org_id,
                event_id=eventId,
                annotation_id=body["annotation_id"],
                payload=body,
                canonical_hash=canonical_hash(body),
            )
        )
        db.commit()
        return {"annotation_id": body["annotation_id"], "durable": True}
    finally:
        db.close()
