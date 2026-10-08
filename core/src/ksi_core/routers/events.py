"""Events, evidence, object downloads."""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import Response

from .. import models
from .. import store as store_mod
from ..auth import audit, error, paginate
from . import authorize, use_db

router = APIRouter()


@router.get("/events")
def list_events(request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        q = db.query(models.DetectionEvent).filter_by(org_id=principal.org_id)
        flight_id = request.query_params.get("flight_id")
        site_id = request.query_params.get("site_id")
        if flight_id:
            q = q.filter_by(flight_id=flight_id)
        if site_id:
            flight_ids = [f.flight_id for f in db.query(models.Flight).filter_by(org_id=principal.org_id, site_id=site_id).all()]
            q = q.filter(models.DetectionEvent.flight_id.in_(flight_ids)) if flight_ids else q.filter(False)
        rows, nxt = paginate(request, q)
        return {"items": [r.payload for r in rows], "next_cursor": nxt}
    finally:
        db.close()


def _event_or_404(db, principal, event_id: str):
    return (
        db.query(models.DetectionEvent)
        .filter_by(org_id=principal.org_id, event_id=event_id)
        .first()
    )


@router.get("/events/{eventId}")
def get_event(eventId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        row = _event_or_404(db, principal, eventId)
        if row is None:
            return error("NOT_FOUND", "event not found", 404)
        count = db.query(models.Annotation).filter_by(org_id=principal.org_id, event_id=eventId).count()
        return {
            "event": row.payload,
            "evidence_artifact_ids": row.payload.get("evidence_artifact_ids", []),
            "annotations_count": count,
        }
    finally:
        db.close()


def _artifact_index(db, principal) -> dict:
    """artifact_id -> manifest artifact entry (org-scoped, any session)."""
    index: dict = {}
    sessions = db.query(models.IngestSession).filter_by(org_id=principal.org_id).all()
    for session in sessions:
        for artifact in (session.manifest or {}).get("artifacts", []):
            index.setdefault(artifact["artifact_id"], artifact)
    return index


@router.get("/events/{eventId}/evidence")
def list_evidence(eventId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        row = _event_or_404(db, principal, eventId)
        if row is None:
            return error("NOT_FOUND", "event not found", 404)
        index = _artifact_index(db, principal)
        items = []
        for aid in row.payload.get("evidence_artifact_ids", []):
            meta = index.get(aid)
            if meta is None:
                continue
            items.append(
                {
                    "artifact_id": aid,
                    "source_kind": meta.get("source_kind", "UNKNOWN"),
                    "media_type": meta.get("media_type", "application/octet-stream"),
                    "size_bytes": meta.get("size_bytes", 0),
                    "sha256": meta.get("sha256", ""),
                    "download_url": f"/api/v1/edge/objects/{aid}",
                }
            )
        return {"items": items}
    finally:
        db.close()


@router.get("/objects/{artifactId}")
def download_object(artifactId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        sessions = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, status="COMMITTED")
            .order_by(models.IngestSession.id.asc())
            .all()
        )
        found = None
        for session in sessions:
            for artifact in (session.manifest or {}).get("artifacts", []):
                if artifact["artifact_id"] == artifactId:
                    found = (session, artifact)
                    break
            if found:
                break
        if found is None:
            return error("NOT_FOUND", "object not found", 404)
        session, artifact = found
        root = request.app.state.settings.object_root
        data = store_mod.read_bytes(root, session.upload_id, artifactId)
        if data is None:
            return error("NOT_FOUND", "object not found", 404)
        audit(db, principal, "download", "object", artifactId)
        db.commit()
        return Response(
            content=data,
            media_type=artifact.get("media_type", "application/octet-stream"),
            headers={"X-Content-SHA256": artifact.get("sha256", "")},
        )
    finally:
        db.close()
