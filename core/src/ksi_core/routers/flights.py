"""Flights, applications, sessions, batches, coverage."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from .. import models
from ..auth import audit, canonical_hash, error, paginate, require_valid, same_content
from ..store import covered_bytes
from . import authorize, use_db

router = APIRouter()

REQUIRED_FLIGHT_FIELDS = [
    "flight_id", "site_id", "rc_device_id", "mission_id",
    "mission_revision_id", "bundle_sha256", "started_at_utc", "ended_at_utc",
]

BATCH_KINDS = {
    "observations": ("observation.v1", "observation_id", models.Observation),
    "events": ("detection-event.v1", "event_id", models.DetectionEvent),
    "spatial": ("spatial-summary.v1", "summary_id", models.SpatialSummary),
    "flight": ("flight-summary.v1", "summary_id", models.FlightSummary),
}


def _missing_for_session(request: Request, db, session: models.IngestSession) -> list:
    from .. import store as store_mod

    root = request.app.state.settings.object_root
    missing: list = []
    objs = {o.artifact_id: o for o in db.query(models.IngestObject).filter_by(upload_id=session.upload_id).all()}
    for artifact in session.manifest.get("artifacts", []):
        if not artifact.get("required", True):
            continue
        aid = artifact["artifact_id"]
        obj = objs.get(aid)
        ok = False
        if obj is not None and obj.received_bytes >= artifact["size_bytes"]:
            actual = store_mod.sha256_file(root, session.upload_id, aid)
            ok = actual == artifact["sha256"]
        if not ok:
            missing.append(aid)
    return missing


def flight_view(request: Request, db, flight: models.Flight) -> dict:
    sessions = (
        db.query(models.IngestSession)
        .filter_by(org_id=flight.org_id, flight_id=flight.flight_id)
        .order_by(models.IngestSession.id.asc())
        .all()
    )
    total = len(sessions)
    committed = sum(1 for s in sessions if s.status == "COMMITTED")
    if total == 0:
        state = "LOCAL_ONLY"
    elif committed == total:
        state = "COMPLETE"
    elif any(s.status == "PARTIAL" for s in sessions) or committed > 0:
        state = "PARTIAL"
    else:
        state = "UPLOADING"
    missing_union: set = set()
    for s in sessions:
        if s.status != "COMMITTED":
            missing_union.update(_missing_for_session(request, db, s))
    return {
        "flight_id": flight.flight_id,
        "site_id": flight.site_id,
        "org_id": flight.org_id,
        "rc_device_id": flight.rc_device_id,
        "mission_id": flight.mission_id,
        "mission_revision_id": flight.mission_revision_id,
        "bundle_sha256": flight.bundle_sha256,
        "started_at_utc": flight.started_at_utc,
        "ended_at_utc": flight.ended_at_utc,
        "upload_state": state,
        "completeness": {
            "manifests_total": total,
            "manifests_committed": committed,
            "missing_artifact_count": len(missing_union),
        },
    }


def _flight_or_404(db, principal, flight_id: str):
    flight = db.query(models.Flight).filter_by(org_id=principal.org_id, flight_id=flight_id).first()
    return flight


@router.get("/flights")
def list_flights(request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        q = db.query(models.Flight).filter_by(org_id=principal.org_id)
        site_id = request.query_params.get("site_id")
        if site_id:
            q = q.filter_by(site_id=site_id)
        rows, nxt = paginate(request, q)
        return {"items": [flight_view(request, db, r) for r in rows], "next_cursor": nxt}
    finally:
        db.close()


@router.post("/flights")
async def register_flight(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    if not isinstance(body, dict):
        return error("CONTRACT_INVALID", "invalid flight body", 422, ["BODY_NOT_OBJECT"])
    missing = [f for f in REQUIRED_FLIGHT_FIELDS if f not in body]
    if missing:
        return error("CONTRACT_INVALID", "contract validation failed", 422, [f"SCHEMA: /{f}: missing" for f in missing])
    db = use_db(request)
    try:
        existing = db.query(models.Flight).filter_by(org_id=principal.org_id, flight_id=body["flight_id"]).first()
        if existing is not None:
            if same_content(existing.payload, body):
                return flight_view(request, db, existing)
            return error("CONFLICT", "same flight_id with different content", 409)
        row = models.Flight(
            org_id=principal.org_id,
            flight_id=body["flight_id"],
            site_id=body["site_id"],
            rc_device_id=body["rc_device_id"],
            mission_id=body["mission_id"],
            mission_revision_id=body["mission_revision_id"],
            bundle_sha256=body["bundle_sha256"],
            started_at_utc=body["started_at_utc"],
            ended_at_utc=body["ended_at_utc"],
            payload=body,
            canonical_hash=canonical_hash(body),
        )
        db.add(row)
        audit(db, principal, "create", "flight", body["flight_id"])
        db.commit()
        return flight_view(request, db, row)
    finally:
        db.close()


@router.get("/flights/{flightId}")
def get_flight(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flightId)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        return flight_view(request, db, flight)
    finally:
        db.close()


def _record_simple(request: Request, db, principal, flight, body, schema: str, model, id_field: str, kind: str):
    bad = require_valid(schema, body, check_digests=True)
    if bad is not None:
        return bad
    if body.get("flight_id") != flight.flight_id:
        return error("CONTRACT_INVALID", "contract validation failed", 422, ["FLIGHT_MISMATCH"])
    existing = db.query(model).filter(getattr(model, id_field) == body[id_field]).first()
    if existing is not None:
        if existing.org_id != principal.org_id:
            return error("NOT_FOUND", f"{kind} not found", 404)
        if same_content(existing.payload, body):
            return {id_field: body[id_field], "durable": True}
        return error("CONFLICT", f"same {id_field} with different content", 409)
    db.add(model(org_id=principal.org_id, flight_id=flight.flight_id, **{id_field: body[id_field]}, payload=body, canonical_hash=canonical_hash(body)))
    db.commit()
    return {id_field: body[id_field], "durable": True}


@router.get("/flights/{flightId}/mission-applications")
def list_applications(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows = (
            db.query(models.MissionApplication)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.MissionApplication.id.asc())
            .all()
        )
        return {"items": [r.payload for r in rows]}
    finally:
        db.close()


@router.post("/flights/{flightId}/mission-applications")
async def record_application(flightId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flightId)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        return _record_simple(request, db, principal, flight, body, "mission-application.v1", models.MissionApplication, "application_id", "application")
    finally:
        db.close()


@router.get("/flights/{flightId}/flight-sessions")
def list_flight_sessions(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows = (
            db.query(models.FlightSession)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.FlightSession.id.asc())
            .all()
        )
        return {"items": [r.payload for r in rows]}
    finally:
        db.close()


@router.post("/flights/{flightId}/flight-sessions")
async def record_flight_session(flightId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flightId)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        return _record_simple(request, db, principal, flight, body, "flight-session.v1", models.FlightSession, "session_id", "session")
    finally:
        db.close()


def _ingest_batch(request: Request, db, principal, flight, body, schema: str, id_field: str, model, kind: str):
    if not isinstance(body, dict):
        return error("CONTRACT_INVALID", "invalid batch body", 422, ["BODY_NOT_OBJECT"])
    client_batch_id = body.get("client_batch_id")
    items = body.get("items")
    if not isinstance(client_batch_id, str) or not client_batch_id or not isinstance(items, list):
        return error("CONTRACT_INVALID", "contract validation failed", 422, ["SCHEMA: /client_batch_id|items"])
    for item in items:
        bad = require_valid(schema, item, check_digests=True)
        if bad is not None:
            return bad
        if item.get("flight_id") != flight.flight_id:
            return error("CONTRACT_INVALID", "contract validation failed", 422, ["FLIGHT_MISMATCH"])
    content_hash = canonical_hash(items)
    existing = (
        db.query(models.Batch)
        .filter_by(org_id=principal.org_id, flight_id=flight.flight_id, kind=kind, client_batch_id=client_batch_id)
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
    seen: set = set()
    for item in items:
        iid = item[id_field]
        if iid in seen:
            if iid not in duplicates and iid not in accepted:
                duplicates.append(iid)
            continue
        seen.add(iid)
        row = db.query(model).filter(getattr(model, id_field) == iid).first()
        if row is not None:
            if row.org_id != principal.org_id:
                return error("NOT_FOUND", "item not found", 404)
            if same_content(row.payload, item):
                duplicates.append(iid)
            else:
                return error("CONFLICT", f"same {id_field} with different content", 409)
        else:
            db.add(model(org_id=principal.org_id, flight_id=flight.flight_id, **{id_field: iid}, payload=item, canonical_hash=canonical_hash(item)))
            accepted.append(iid)
    server_batch_id = str(uuid.uuid4())
    db.add(
        models.Batch(
            org_id=principal.org_id,
            flight_id=flight.flight_id,
            kind=kind,
            client_batch_id=client_batch_id,
            content_hash=content_hash,
            server_batch_id=server_batch_id,
            accepted_ids=accepted,
            duplicate_ids=duplicates,
        )
    )
    db.commit()
    return {"accepted_ids": accepted, "duplicate_ids": duplicates, "durable": True, "server_batch_id": server_batch_id}


async def _batch_endpoint(request: Request, flightId: str, kind: str):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flightId)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        schema, id_field, model = BATCH_KINDS[kind]
        return _ingest_batch(request, db, principal, flight, body, schema, id_field, model, kind)
    finally:
        db.close()


@router.post("/flights/{flightId}/observations:batch")
async def batch_observations(flightId: str, request: Request):
    return await _batch_endpoint(request, flightId, "observations")


@router.get("/flights/{flightId}/observations")
def list_observations(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows, nxt = paginate(request, db.query(models.Observation).filter_by(org_id=principal.org_id, flight_id=flightId))
        return {"items": [r.payload for r in rows], "next_cursor": nxt}
    finally:
        db.close()


@router.post("/flights/{flightId}/events:batch")
async def batch_events(flightId: str, request: Request):
    return await _batch_endpoint(request, flightId, "events")


@router.post("/flights/{flightId}/spatial-summaries:batch")
async def batch_spatial(flightId: str, request: Request):
    return await _batch_endpoint(request, flightId, "spatial")


@router.get("/flights/{flightId}/spatial-summaries")
def list_spatial(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows = (
            db.query(models.SpatialSummary)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.SpatialSummary.id.asc())
            .all()
        )
        return {"items": [r.payload for r in rows], "complete": True}
    finally:
        db.close()


@router.post("/flights/{flightId}/flight-summaries:batch")
async def batch_flight_summaries(flightId: str, request: Request):
    return await _batch_endpoint(request, flightId, "flight")


@router.get("/flights/{flightId}/coverage")
def get_coverage(flightId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        flight = _flight_or_404(db, principal, flightId)
        if flight is None:
            return error("NOT_FOUND", "flight not found", 404)
        rows = (
            db.query(models.FlightSummary)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.FlightSummary.id.asc())
            .all()
        )
        summaries = [r.payload for r in rows]
        view = flight_view(request, db, flight)
        if not summaries:
            completeness = "UNKNOWN"
        elif view["upload_state"] == "COMPLETE":
            completeness = "COMPLETE"
        else:
            completeness = "PARTIAL"
        return {"flight_id": flightId, "summaries": summaries, "data_completeness": completeness}
    finally:
        db.close()


@router.get("/flights/{flightId}/artifacts")
def list_flight_artifacts(flightId: str, request: Request):
    """All artifacts declared by the flight's ingest sessions (org-scoped).

    Additive READ endpoint for the L1 review journey: a zero-event flight's
    evidence (e.g. RC_PROXY_SEGMENT recordings) must be openable, so this
    listing does not depend on event links. `committed` says whether the
    bytes are downloadable now (/objects serves COMMITTED sessions only).
    """
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        if _flight_or_404(db, principal, flightId) is None:
            return error("NOT_FOUND", "flight not found", 404)
        sessions = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, flight_id=flightId)
            .order_by(models.IngestSession.id.asc())
            .all()
        )
        items = []
        for session in sessions:
            committed = session.status == "COMMITTED"
            for artifact in (session.manifest or {}).get("artifacts", []):
                items.append(
                    {
                        "artifact_id": artifact["artifact_id"],
                        "source_kind": artifact.get("source_kind", "UNKNOWN"),
                        "media_type": artifact.get(
                            "media_type", "application/octet-stream"
                        ),
                        "size_bytes": artifact.get("size_bytes", 0),
                        "sha256": artifact.get("sha256", ""),
                        "required": artifact.get("required", True),
                        "segment_index": artifact.get("segment_index"),
                        "edge_boot_id": (session.manifest or {}).get("edge_boot_id"),
                        "upload_id": session.upload_id,
                        "session_status": session.status,
                        "committed": committed,
                        "download_url": f"/api/v1/edge/objects/{artifact['artifact_id']}",
                    }
                )
        return {"items": items}
    finally:
        db.close()


# Silence unused-import lint for covered_bytes (kept for range accounting API).
_ = covered_bytes
