"""Collections: producer-neutral central collection with a custody receipt.

WHY A SEPARATE SURFACE
    The v1 flight-upload manifest is the RC's own record: it requires
    `rc_device_id`, `rc_received_last_seq` and RC receipt times. A PC
    collector (or the Edge itself) must not invent those values — so the
    first central collection of **Edge origin evidence** needs its own
    producer-neutral manifest (`collection-manifest.v1`). v1 stays frozen and
    keeps working exactly as before.

WHAT THIS IS NOT
    A receipt is **custody proof**, not a completion claim and **not a deletion
    token**: it binds the exact required artifact set (ids + digests) that the
    Core durably holds for that collection. A collection with a missing
    required artifact gets NO receipt.

REUSED ON PURPOSE
    The byte path is the proven one: the collection opens an ingest session
    whose manifest carries the same `artifacts` shape as v1, so the existing
    resumable chunk PUT and the existing coverage+hash verification apply
    unchanged. Only the opening, the commit and the receipt are new.
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Request
from ksi_contracts import canonical_bytes

from .. import models
from ..auth import audit, error, require_valid
from . import authorize, use_db
from .flights import _missing_for_session

router = APIRouter()


def _canonical_sha256(value: dict) -> str:
    """Digest over RFC 8785 canonical bytes (the repo's digest rule)."""
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def _artifact_set_sha256(artifacts: list[dict]) -> str:
    """One digest over the required set: sorted (artifact_id, sha256) pairs.

    Changing any byte of any required artifact changes this digest, so the
    receipt fixes the whole set — not just a count.
    """
    pairs = sorted(
        (a["artifact_id"], a["sha256"]) for a in artifacts if a.get("required", True)
    )
    return _canonical_sha256({"required": [{"artifact_id": a, "sha256": s} for a, s in pairs]})


def _receipt_body(
    *,
    collection_id: str,
    upload_id: str,
    manifest_sha256: str,
    artifacts: list[dict],
    flight_id: str,
    edge_boot_id: str,
    sealed_revision_sha256: str,
    producer: dict,
) -> dict:
    body = {
        "schema_version": 1,
        "collection_id": collection_id,
        "upload_id": upload_id,
        "manifest_sha256": manifest_sha256,
        "required_artifact_ids": sorted(
            a["artifact_id"] for a in artifacts if a.get("required", True)
        ),
        "artifact_set_sha256": _artifact_set_sha256(artifacts),
        "flight_id": flight_id,
        "edge_boot_id": edge_boot_id,
        "sealed_revision_sha256": sealed_revision_sha256,
        "producer": producer,
        "issued_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    body["receipt_sha256"] = _canonical_sha256(body)
    return body


@router.post("/collections")
async def open_collection(request: Request):
    """Opens (or resumes) a collection and returns what is still missing."""
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    try:
        body = await request.json()
    except Exception:
        return error("CONTRACT_INVALID", "invalid JSON body", 422, ["BODY_NOT_JSON"])
    bad = require_valid("collection-manifest.v1", body, check_digests=True)
    if bad is not None:
        return bad

    db = use_db(request)
    try:
        collection_id = body["collection_id"]
        manifest_sha = _canonical_sha256(body)
        existing = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, collection_id=collection_id)
            .first()
        )
        if existing is not None:
            if existing.manifest_sha256 != manifest_sha:
                return error(
                    "CONFLICT",
                    "collection id presented with different content",
                    409,
                    ["COLLECTION_ID_CONTENT_MISMATCH"],
                )
            return {
                "collection_id": collection_id,
                "upload_id": existing.upload_id,
                "manifest_sha256": existing.manifest_sha256,
                "missing_artifact_ids": _missing_for_session(request, db, existing),
                "status": "RESUMING",
            }

        # Global artifact identity: one artifact id means one byte stream.
        for artifact in body.get("artifacts", []):
            clash = (
                db.query(models.IngestObject)
                .join(
                    models.IngestSession,
                    models.IngestSession.upload_id == models.IngestObject.upload_id,
                )
                .filter(
                    models.IngestSession.org_id == principal.org_id,
                    models.IngestObject.artifact_id == artifact["artifact_id"],
                    models.IngestObject.sha256 != artifact["sha256"],
                )
                .first()
            )
            if clash is not None:
                return error(
                    "CONFLICT",
                    "artifact id reused with a different digest",
                    409,
                    {
                        "reason": "ARTIFACT_ID_DIGEST_CONFLICT",
                        "artifact_ids": [artifact["artifact_id"]],
                    },
                )

        upload_id = str(uuid.uuid4())
        session = models.IngestSession(
            upload_id=upload_id,
            org_id=principal.org_id,
            flight_id=body["flight_id"],
            manifest_sha256=manifest_sha,
            manifest=body,
            status="OPEN",
            collection_id=collection_id,
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
        audit(db, principal, "open", "collection", collection_id)
        db.commit()
        return {
            "collection_id": collection_id,
            "upload_id": upload_id,
            "manifest_sha256": manifest_sha,
            "missing_artifact_ids": [a["artifact_id"] for a in body.get("artifacts", [])],
            "status": "NEW",
        }
    finally:
        db.close()


@router.post("/collections/{collection_id}/commit")
async def commit_collection(collection_id: str, request: Request):
    """Verifies the required set and issues the custody receipt.

    A missing (or damaged) required artifact means **no receipt**: the
    response names what is missing instead of claiming custody.
    """
    principal, denied = authorize(request, write=True)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        session = (
            db.query(models.IngestSession)
            .filter_by(org_id=principal.org_id, collection_id=collection_id)
            .first()
        )
        if session is None:
            return error("NOT_FOUND", "collection not found", 404)
        existing = (
            db.query(models.CollectionReceipt)
            .filter_by(org_id=principal.org_id, collection_id=collection_id)
            .first()
        )
        if existing is not None:
            # A receipt is immutable: the same collection re-committed yields
            # the same receipt, never a different one.
            return existing.receipt
        missing = _missing_for_session(request, db, session)
        if missing:
            session.status = "PARTIAL"
            db.commit()
            return error(
                "CONFLICT",
                "collection is incomplete — no receipt issued",
                409,
                {"missing_artifact_ids": missing},
            )
        body = session.manifest
        receipt = _receipt_body(
            collection_id=collection_id,
            upload_id=session.upload_id,
            manifest_sha256=session.manifest_sha256,
            artifacts=body.get("artifacts", []),
            flight_id=body["flight_id"],
            edge_boot_id=body["edge_boot_id"],
            sealed_revision_sha256=body["sealed_revision_sha256"],
            producer=body["producer"],
        )
        session.status = "COMMITTED"
        db.add(
            models.CollectionReceipt(
                org_id=principal.org_id,
                collection_id=collection_id,
                upload_id=session.upload_id,
                flight_id=body["flight_id"],
                edge_boot_id=body["edge_boot_id"],
                manifest_sha256=session.manifest_sha256,
                artifact_set_sha256=receipt["artifact_set_sha256"],
                sealed_revision_sha256=body["sealed_revision_sha256"],
                producer_type=body["producer"]["type"],
                producer_id=body["producer"]["id"],
                receipt=receipt,
                issued_at_utc=receipt["issued_at_utc"],
            )
        )
        audit(db, principal, "commit", "collection", collection_id)
        db.commit()
        return receipt
    finally:
        db.close()


@router.get("/collections")
def list_collections(request: Request):
    """The custody receipts the Core holds for a flight (or one boot).

    WHY THIS EXISTS: the RC must be able to say "the ORIGINAL evidence of this
    flight is centrally held" — and that claim needs a receipt, not a local
    belief. The Edge's own uploads (v1) and the collector's collections (v2)
    are different paths, so they are listed separately; the RC shows them as
    separate axes instead of merging them into one "보관됨".

    Org-scoped: a receipt of another organisation is never listed (no
    existence leak).
    """
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        q = db.query(models.CollectionReceipt).filter_by(org_id=principal.org_id)
        flight_id = request.query_params.get("flight_id")
        if flight_id:
            q = q.filter_by(flight_id=flight_id)
        boot_id = request.query_params.get("edge_boot_id")
        if boot_id:
            q = q.filter_by(edge_boot_id=boot_id)
        rows = q.order_by(models.CollectionReceipt.issued_at_utc.asc()).all()
        return {
            "items": [
                {
                    "collection_id": r.collection_id,
                    "upload_id": r.upload_id,
                    "flight_id": r.flight_id,
                    "edge_boot_id": r.edge_boot_id,
                    "manifest_sha256": r.manifest_sha256,
                    "artifact_set_sha256": r.artifact_set_sha256,
                    "sealed_revision_sha256": r.sealed_revision_sha256,
                    "required_artifact_count": len(r.receipt.get("required_artifact_ids", [])),
                    "producer": {
                        "type": r.producer_type,
                        "id": r.producer_id,
                    },
                    "issued_at_utc": r.issued_at_utc,
                    "receipt_sha256": r.receipt.get("receipt_sha256"),
                }
                for r in rows
            ]
        }
    finally:
        db.close()


@router.get("/collections/{collection_id}/retention-view")
def retention_view(collection_id: str, request: Request):
    """What the Core can PROVE about a collection, and what it cannot.

    WHY: the deletion question needs two kinds of facts — what the Core holds
    (provable here) and what the RC/policy hold (not visible here). The view
    keeps them apart instead of merging them into one verdict.

    The Core can prove: the custody receipt exists, the exact required asset
    set it binds, and the sealed revision it came from.
    The Core CANNOT prove: the RC's local holds (unsent operator responses,
    offline review material) and the approved retention policy. Those stay
    "unproven" — and an unproven fact blocks eligibility.
    """
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        receipt = (
            db.query(models.CollectionReceipt)
            .filter_by(org_id=principal.org_id, collection_id=collection_id)
            .first()
        )
        if receipt is None:
            return error("NOT_FOUND", "no receipt for this collection", 404)
        proven = [
            "중앙 보관 영수증 존재",
            f"필수 근거 집합 {len(receipt.receipt.get('required_artifact_ids', []))}건 고정",
            "봉인된 리비전의 다이제스트 일치",
        ]
        unproven = [
            "RC의 미전송 대응·오프라인 검토 대기 자료 (Core는 볼 수 없음)",
            "보존 기간·hold·사건 보존 조건 승인 (운영 결정)",
            "필수 원본의 읽기 전용 복구 시험 (실기·운영 승인)",
        ]
        return {
            "collection_id": collection_id,
            "flight_id": receipt.flight_id,
            "edge_boot_id": receipt.edge_boot_id,
            "receipt_sha256": receipt.receipt.get("receipt_sha256"),
            "artifact_set_sha256": receipt.artifact_set_sha256,
            "required_artifact_count": len(
                receipt.receipt.get("required_artifact_ids", [])
            ),
            "proven": proven,
            "unproven": unproven,
            # Fail-closed: an unproven fact blocks eligibility.
            "eligible": False,
            "reasons": list(unproven),
            "deleted": 0,
            "note": (
                "조회 전용 — 삭제 실행은 별도 승인 후 별도 경로에서 한다. "
                "영수증은 삭제 토큰이 아니다."
            ),
        }
    finally:
        db.close()


@router.get("/collections/{collection_id}/receipt")
def get_receipt(collection_id: str, request: Request):
    """The custody receipt, or 404 (never a guess)."""
    principal, denied = authorize(request, write=False)
    if denied is not None:
        return denied
    db = use_db(request)
    try:
        receipt = (
            db.query(models.CollectionReceipt)
            .filter_by(org_id=principal.org_id, collection_id=collection_id)
            .first()
        )
        if receipt is None:
            return error("NOT_FOUND", "no receipt for this collection", 404)
        return receipt.receipt
    finally:
        db.close()
