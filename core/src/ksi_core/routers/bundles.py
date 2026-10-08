"""Mission bundles (digest-verified, idempotent by bundle_id)."""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from .. import models
from ..auth import audit, canonical_hash, error, paginate, require_valid, same_content
from . import authorize, use_db

router = APIRouter()


@router.get("/mission-bundles")
def list_bundles(request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        rows, nxt = paginate(request, db.query(models.MissionBundle).filter_by(org_id=principal.org_id))
        return {"items": [r.payload for r in rows], "next_cursor": nxt}
    finally:
        db.close()


@router.post("/mission-bundles")
async def create_bundle(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    bad = require_valid("mission-bundle.v1", body, check_digests=True)
    if bad is not None:
        return bad
    db = use_db(request)
    try:
        existing = (
            db.query(models.MissionBundle)
            .filter_by(org_id=principal.org_id, bundle_id=body["bundle_id"])
            .first()
        )
        if existing is not None:
            if same_content(existing.payload, body):
                return {"bundle_id": existing.bundle_id, "bundle_sha256": existing.bundle_sha256, "status": "EXISTS"}
            return error("CONFLICT", "same bundle_id with different content", 409)
        db.add(
            models.MissionBundle(
                org_id=principal.org_id,
                bundle_id=body["bundle_id"],
                bundle_sha256=body["bundle_sha256"],
                payload=body,
                canonical_hash=canonical_hash(body),
            )
        )
        audit(db, principal, "create", "mission_bundle", body["bundle_id"])
        db.commit()
        return {"bundle_id": body["bundle_id"], "bundle_sha256": body["bundle_sha256"], "status": "CREATED"}
    finally:
        db.close()


@router.get("/mission-bundles/{bundleId}")
def get_bundle(bundleId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        row = (
            db.query(models.MissionBundle)
            .filter_by(org_id=principal.org_id, bundle_id=bundleId)
            .first()
        )
        if row is None:
            return error("NOT_FOUND", "bundle not found", 404)
        return JSONResponse(row.payload)
    finally:
        db.close()
