"""Sites, zone revisions (CAS), risk-policy revisions (CAS)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from .. import models
from ..auth import audit, error, paginate, require_valid
from . import authorize, use_db

router = APIRouter()


def _ring_closed(coordinates) -> bool:
    try:
        for ring in coordinates:
            if not isinstance(ring, list) or len(ring) < 4 or ring[0] != ring[-1]:
                return False
        return True
    except TypeError:
        return False


@router.get("/sites")
def list_sites(request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        rows, nxt = paginate(request, db.query(models.Site).filter_by(org_id=principal.org_id))
        items = [{"site_id": r.site_id, "org_id": r.org_id, "name": r.name} for r in rows]
        return {"items": items, "next_cursor": nxt}
    finally:
        db.close()


@router.post("/sites")
async def create_site(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    if not isinstance(body, dict):
        return error("CONTRACT_INVALID", "invalid site body", 422, ["BODY_NOT_OBJECT"])
    org_id = body.get("org_id")
    name = body.get("name")
    if org_id != principal.org_id:
        return error("FORBIDDEN", "org_id does not match caller org", 403)
    if not isinstance(name, str) or not name:
        return error("CONTRACT_INVALID", "invalid site body", 422, ["SCHEMA: /name: empty or missing"])
    db = use_db(request)
    try:
        existing = db.query(models.Site).filter_by(org_id=principal.org_id, name=name).first()
        if existing is not None:
            return {"site_id": existing.site_id, "org_id": existing.org_id, "name": existing.name}
        site_id = str(uuid.uuid4())
        db.add(models.Site(org_id=principal.org_id, site_id=site_id, name=name))
        audit(db, principal, "create", "site", site_id)
        db.commit()
        return {"site_id": site_id, "org_id": principal.org_id, "name": name}
    finally:
        db.close()


def _site_visible(db, principal, site_id: str):
    return db.query(models.Site).filter_by(org_id=principal.org_id, site_id=site_id).first()


@router.get("/sites/{siteId}/zone-revisions")
def list_zone_revisions(siteId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _site_visible(db, principal, siteId) is None:
            return error("NOT_FOUND", "site not found", 404)
        rows, _nxt = paginate(
            request,
            db.query(models.ZoneRevision).filter_by(org_id=principal.org_id, site_id=siteId),
        )
        return {"items": [r.payload for r in rows]}
    finally:
        db.close()


@router.post("/sites/{siteId}/zone-revisions")
async def create_zone_revision(siteId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        if _site_visible(db, principal, siteId) is None:
            return error("NOT_FOUND", "site not found", 404)
        if not isinstance(body, dict):
            return error("CONTRACT_INVALID", "invalid zone revision body", 422, ["BODY_NOT_OBJECT"])
        zone_id = body.get("zone_id")
        base = body.get("base_revision_id")
        geometry = body.get("geometry_wgs84")
        rule_kind = body.get("rule_kind")
        problems = []
        if not isinstance(zone_id, str) or not zone_id:
            problems.append("SCHEMA: /zone_id: missing")
        if rule_kind not in ("PERSON_ENTRY", "EQUIPMENT_ENTRY", "PPE_REQUIRED", "RESTRICTED_AREA"):
            problems.append("SCHEMA: /rule_kind: unknown")
        coords = geometry.get("coordinates") if isinstance(geometry, dict) else None
        if not isinstance(geometry, dict) or geometry.get("type") != "Polygon" or not _ring_closed(coords):
            problems.append("POLYGON_RING_OPEN: ring must have >=4 positions with first==last")
        if problems:
            return error("CONTRACT_INVALID", "contract validation failed", 422, problems)
        latest = (
            db.query(models.ZoneRevision)
            .filter_by(org_id=principal.org_id, site_id=siteId, zone_id=zone_id)
            .order_by(models.ZoneRevision.id.desc())
            .first()
        )
        expected = latest.revision_id if latest else None
        if base != expected:
            return error("CONFLICT", "base_revision_id is stale", 409, [f"expected {expected}"])
        revision_id = str(uuid.uuid4())
        payload = {
            "zone_id": zone_id,
            "revision_id": revision_id,
            "base_revision_id": base,
            "site_id": siteId,
            "geometry_wgs84": geometry,
            "rule_kind": rule_kind,
        }
        db.add(
            models.ZoneRevision(
                org_id=principal.org_id,
                site_id=siteId,
                zone_id=zone_id,
                revision_id=revision_id,
                base_revision_id=base,
                payload=payload,
            )
        )
        audit(db, principal, "create", "zone_revision", revision_id)
        db.commit()
        return {"zone_id": zone_id, "revision_id": revision_id, "durable": True}
    finally:
        db.close()


@router.get("/sites/{siteId}/risk-policies")
def list_risk_policies(siteId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _site_visible(db, principal, siteId) is None:
            return error("NOT_FOUND", "site not found", 404)
        rows, nxt = paginate(
            request,
            db.query(models.RiskPolicyRevision).filter_by(org_id=principal.org_id, site_id=siteId),
        )
        return {"items": [r.payload for r in rows], "next_cursor": nxt}
    finally:
        db.close()


@router.post("/sites/{siteId}/risk-policies")
async def create_risk_policy(siteId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        if _site_visible(db, principal, siteId) is None:
            return error("NOT_FOUND", "site not found", 404)
        if not isinstance(body, dict):
            return error("CONTRACT_INVALID", "invalid risk policy body", 422, ["BODY_NOT_OBJECT"])
        parent = body.get("expected_parent_revision_id")
        policy = body.get("policy")
        if not isinstance(policy, dict):
            return error("CONTRACT_INVALID", "contract validation failed", 422, ["SCHEMA: /policy: missing"])
        bad = require_valid("risk-policy.v1", policy, check_digests=True)
        if bad is not None:
            return bad
        if policy.get("site_id") != siteId:
            return error("CONTRACT_INVALID", "contract validation failed", 422, ["POLICY_SITE_MISMATCH"])
        policy_id = policy.get("policy_id")
        revision_id = policy.get("revision_id")
        latest = (
            db.query(models.RiskPolicyRevision)
            .filter_by(org_id=principal.org_id, site_id=siteId, policy_id=policy_id)
            .order_by(models.RiskPolicyRevision.id.desc())
            .first()
        )
        expected = latest.revision_id if latest else None
        if parent != expected:
            return error("CONFLICT", "expected_parent_revision_id is stale", 409, [f"expected {expected}"])
        if latest is not None and latest.revision_id == revision_id:
            return {"revision_id": revision_id, "durable": True}
        db.add(
            models.RiskPolicyRevision(
                org_id=principal.org_id,
                site_id=siteId,
                policy_id=policy_id,
                revision_id=revision_id,
                parent_revision_id=parent,
                payload=policy,
            )
        )
        audit(db, principal, "create", "risk_policy", revision_id)
        db.commit()
        return {"revision_id": revision_id, "durable": True}
    finally:
        db.close()
