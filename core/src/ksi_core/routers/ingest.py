"""Ingest sessions: open (frozen manifest), resumable chunk PUT, honest complete."""

from __future__ import annotations

import re
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Request

from .. import models
from .. import store as store_mod
from ..auth import audit, error, require_valid
from . import authorize, use_db
from .flights import _missing_for_session

router = APIRouter()

RANGE_RE = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")


def _uploaded_missing(request: Request, db, session: models.IngestSession) -> tuple[list, list]:
    objs = {o.artifact_id: o for o in db.query(models.IngestObject).filter_by(upload_id=session.upload_id).all()}
    uploaded, missing = [], []
    for artifact in session.manifest.get("artifacts", []):
        aid = artifact["artifact_id"]
        obj = objs.get(aid)
        if obj is not None and obj.received_bytes >= artifact["size_bytes"]:
            uploaded.append(aid)
        else:
            missing.append(aid)
    return uploaded, missing


def _status_body(request: Request, db, session: models.IngestSession) -> dict:
    uploaded, missing = _uploaded_missing(request, db, session)
    return {
        "upload_id": session.upload_id,
        "status": session.status,
        "uploaded_artifact_ids": uploaded,
        "missing_artifact_ids": missing,
        "manifest_sha256": session.manifest_sha256,
    }


@router.post("/ingest-sessions")
async def open_session(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    bad = require_valid("flight-upload-manifest.v1", body, check_digests=True)
    if bad is not None:
        return bad
    db = use_db(request)
    try:
        manifest_sha = body["manifest_sha256"]
        existing = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, manifest_sha256=manifest_sha)
            .first()
        )
        if existing is not None:
            uploaded, missing = _uploaded_missing(request, db, existing)
            return {"upload_id": existing.upload_id, "missing_artifact_ids": missing, "status": "RESUMING"}
        upload_id = str(uuid.uuid4())
        session = models.IngestSession(
            upload_id=upload_id,
            org_id=principal.org_id,
            flight_id=body["flight_id"],
            manifest_sha256=manifest_sha,
            manifest=body,
            status="OPEN",
        )
        db.add(session)
        for artifact in body.get("artifacts", []):
            db.add(
                models.IngestObject(
                    upload_id=upload_id,
                    artifact_id=artifact["artifact_id"],
                    size_bytes=artifact["size_bytes"],
                    sha256=artifact["sha256"],
                    media_type=artifact.get("media_type", "application/octet-stream"),
                    required=bool(artifact.get("required", True)),
                    rel_path=f"{upload_id}/{artifact['artifact_id']}",
                    received_ranges=[],
                    received_bytes=0,
                )
            )
        db.commit()
        return {
            "upload_id": upload_id,
            "missing_artifact_ids": [a["artifact_id"] for a in body.get("artifacts", [])],
            "status": "NEW",
        }
    finally:
        db.close()


@router.get("/ingest-sessions/{uploadId}")
def get_session(uploadId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        session = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, upload_id=uploadId)
            .first()
        )
        if session is None:
            return error("NOT_FOUND", "ingest session not found", 404)
        return _status_body(request, db, session)
    finally:
        db.close()


@router.put("/ingest-sessions/{uploadId}/objects/{artifactId}")
async def put_chunk(uploadId: str, artifactId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        session = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, upload_id=uploadId)
            .first()
        )
        if session is None:
            return error("NOT_FOUND", "ingest session not found", 404)
        expected = {a["artifact_id"]: a for a in session.manifest.get("artifacts", [])}
        if artifactId not in expected:
            return error("NOT_FOUND", "artifact not in manifest", 404)
        spec = expected[artifactId]
        obj = (
            db.query(models.IngestObject)
            .filter_by(upload_id=uploadId, artifact_id=artifactId)
            .first()
        )
        if session.status == "COMMITTED":
            return {"received_bytes": spec["size_bytes"], "complete": True}
        raw_range = request.headers.get("content-range", "")
        match = RANGE_RE.match(raw_range.strip())
        if match is None:
            return error("CONTRACT_INVALID", "bad Content-Range", 422, ["RANGE_FORMAT"])
        start, end, total = int(match.group(1)), int(match.group(2)), int(match.group(3))
        if total != spec["size_bytes"] or end < start or end >= total:
            return error("CONTRACT_INVALID", "range does not match manifest artifact", 422, ["RANGE_MISMATCH"])
        data = await request.body()
        if len(data) != end - start + 1:
            return error("CONTRACT_INVALID", "chunk length does not match Content-Range", 422, ["RANGE_LENGTH"])
        hint = request.headers.get("x-content-sha256")
        root = request.app.state.settings.object_root
        store_mod.write_chunk(root, uploadId, artifactId, start, data, total)
        obj.received_ranges = store_mod.merge_ranges(list(obj.received_ranges or []), start, end)
        obj.received_bytes = store_mod.covered_bytes(obj.received_ranges)
        if hint:
            obj.content_sha256_hint = hint
        db.commit()
        return {"received_bytes": obj.received_bytes, "complete": obj.received_bytes >= spec["size_bytes"]}
    finally:
        db.close()


@router.post("/ingest-sessions/{uploadId}/complete")
async def complete_session(uploadId: str, request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    db = use_db(request)
    try:
        session = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, upload_id=uploadId)
            .first()
        )
        if session is None:
            return error("NOT_FOUND", "ingest session not found", 404)
        if not isinstance(body, dict) or body.get("manifest_sha256") != session.manifest_sha256:
            return error("CONFLICT", "session bound to a different manifest", 409)
        if session.status == "COMMITTED":
            return {
                "status": "COMMITTED",
                "durable_commit_id": session.durable_commit_id,
                "manifest_sha256": session.manifest_sha256,
                "missing_artifact_ids": [],
                "committed_at_utc": session.committed_at_utc,
            }
        missing = _missing_for_session(request, db, session)
        if missing:
            session.status = "PARTIAL"
            db.commit()
            return {
                "status": "PARTIAL",
                "durable_commit_id": None,
                "manifest_sha256": session.manifest_sha256,
                "missing_artifact_ids": missing,
                "committed_at_utc": None,
            }
        durable = str(uuid.uuid4())
        committed_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        session.status = "COMMITTED"
        session.durable_commit_id = durable
        session.committed_at_utc = committed_at
        audit(db, principal, "commit", "ingest_session", uploadId)
        db.commit()
        return {
            "status": "COMMITTED",
            "durable_commit_id": durable,
            "manifest_sha256": session.manifest_sha256,
            "missing_artifact_ids": [],
            "committed_at_utc": committed_at,
        }
    finally:
        db.close()
