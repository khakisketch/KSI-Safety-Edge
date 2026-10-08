"""Mission snapshots."""

from __future__ import annotations

from fastapi import APIRouter, Request

from .. import models
from ..auth import audit, canonical_hash, error, require_valid, same_content
from . import authorize, use_db

router = APIRouter()


@router.post("/missions")
async def create_mission(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    bad = require_valid("mission-snapshot.v1", body, check_digests=True)
    if bad is not None:
        return bad
    db = use_db(request)
    try:
        existing = db.query(models.Mission).filter_by(mission_revision_id=body["mission_revision_id"]).first()
        if existing is not None:
            if existing.org_id != principal.org_id:
                return error("NOT_FOUND", "mission not found", 404)
            if same_content(existing.payload, body):
                return {
                    "mission_id": existing.mission_id,
                    "mission_revision_id": existing.mission_revision_id,
                    "content_sha256": existing.content_sha256,
                    "site_id": existing.site_id,
                }
            return error("CONFLICT", "same mission_revision_id with different content", 409)
        row = models.Mission(
            org_id=principal.org_id,
            mission_id=body["mission_id"],
            mission_revision_id=body["mission_revision_id"],
            site_id=body["site_id"],
            content_sha256=body["content_sha256"],
            payload=body,
            canonical_hash=canonical_hash(body),
        )
        db.add(row)
        audit(db, principal, "create", "mission", body["mission_revision_id"])
        db.commit()
        return {
            "mission_id": body["mission_id"],
            "mission_revision_id": body["mission_revision_id"],
            "content_sha256": body["content_sha256"],
            "site_id": body["site_id"],
        }
    finally:
        db.close()


@router.get("/missions/{missionId}/snapshot")
def get_mission_snapshot(missionId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        row = (
            db.query(models.Mission)
            .filter_by(org_id=principal.org_id, mission_id=missionId)
            .order_by(models.Mission.id.desc())
            .first()
        )
        if row is None:
            return error("NOT_FOUND", "mission not found", 404)
        from fastapi.responses import JSONResponse

        return JSONResponse(row.payload)
    finally:
        db.close()
