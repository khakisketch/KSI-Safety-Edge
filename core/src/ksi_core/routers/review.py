"""Review jobs (P1 placeholder — record only, no execution)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Request

from .. import models
from ..auth import error
from . import authorize, use_db

router = APIRouter()

JOB_KINDS = ("FP_REVIEW", "FN_AUDIT_SAMPLE", "COMPLEX_BEHAVIOR_REVIEW")
JOB_STATUSES = ("SUBMITTED", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "INSUFFICIENT_EVIDENCE", "SKIPPED")


def _view(row: models.ReviewJob) -> dict:
    return {
        "job_id": row.job_id,
        "flight_id": row.flight_id,
        "job_kind": row.job_kind,
        "status": row.status,
        "idempotency_key": row.idempotency_key,
    }


@router.get("/review-jobs")
def list_jobs(request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        q = db.query(models.ReviewJob).filter_by(org_id=principal.org_id)
        flight_id = request.query_params.get("flight_id")
        if flight_id:
            q = q.filter_by(flight_id=flight_id)
        rows = q.order_by(models.ReviewJob.id.asc()).all()
        return {"items": [_view(r) for r in rows]}
    finally:
        db.close()


@router.post("/review-jobs")
async def create_job(request: Request):
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    if not isinstance(body, dict):
        return error("CONTRACT_INVALID", "invalid review job body", 422, ["BODY_NOT_OBJECT"])
    flight_id = body.get("flight_id")
    job_kind = body.get("job_kind")
    key = body.get("idempotency_key")
    problems = []
    if not isinstance(flight_id, str) or not flight_id:
        problems.append("SCHEMA: /flight_id: missing")
    if job_kind not in JOB_KINDS:
        problems.append("SCHEMA: /job_kind: unknown")
    if not isinstance(key, str) or not key:
        problems.append("SCHEMA: /idempotency_key: missing")
    if problems:
        return error("CONTRACT_INVALID", "contract validation failed", 422, problems)
    db = use_db(request)
    try:
        existing = (
            db.query(models.ReviewJob)
            .filter_by(org_id=principal.org_id, idempotency_key=key)
            .first()
        )
        if existing is not None:
            if existing.flight_id == flight_id and existing.job_kind == job_kind:
                return {"job_id": existing.job_id, "status": existing.status}
            return error("CONFLICT", "same idempotency_key with different content", 409)
        job_id = str(uuid.uuid4())
        db.add(
            models.ReviewJob(
                org_id=principal.org_id,
                job_id=job_id,
                flight_id=flight_id,
                job_kind=job_kind,
                idempotency_key=key,
                status="SUBMITTED",
                payload={"flight_id": flight_id, "job_kind": job_kind, "idempotency_key": key},
            )
        )
        db.commit()
        return {"job_id": job_id, "status": "SUBMITTED"}
    finally:
        db.close()


@router.get("/review-jobs/{jobId}")
def get_job(jobId: str, request: Request):
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        row = db.query(models.ReviewJob).filter_by(org_id=principal.org_id, job_id=jobId).first()
        if row is None:
            return error("NOT_FOUND", "review job not found", 404)
        return _view(row)
    finally:
        db.close()
