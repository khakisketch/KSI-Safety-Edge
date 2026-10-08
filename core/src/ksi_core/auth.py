"""Bearer-token auth (dev static tokens) + shared HTTP helpers."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi import Depends, Request
from fastapi.responses import JSONResponse
from ksi_contracts import ContractError, canonical_bytes, validate_all
from sqlalchemy.orm import Session

from . import models


@dataclass
class Principal:
    token: str
    org_id: str
    scopes: list


def error(code: str, message: str, status: int, details: Any = None) -> JSONResponse:
    body: dict = {"code": code, "message": message}
    if details is not None:
        body["details"] = details
    return JSONResponse(status_code=status, content=body)


def contract_error_response(exc: ContractError) -> JSONResponse:
    return error("CONTRACT_INVALID", "contract validation failed", 422, list(exc.errors))


def require_valid(name: str, payload: Any, check_digests: bool = True) -> JSONResponse | None:
    """Validate ``payload``; return a 422 response on failure, else None."""
    try:
        validate_all(name, payload, check_digests=check_digests)
    except ContractError as exc:
        return contract_error_response(exc)
    return None


def canonical_hash(payload: Any) -> str:
    import hashlib

    return hashlib.sha256(canonical_bytes(payload)).hexdigest()


def same_content(a: Any, b: Any) -> bool:
    return canonical_bytes(a) == canonical_bytes(b)


def get_principal(request: Request) -> Principal | JSONResponse:
    auth = request.headers.get("authorization", "")
    scheme, _, token = auth.partition(" ")
    tokens = request.app.state.settings.dev_tokens
    entry = tokens.get(token.strip()) if scheme.lower() == "bearer" else None
    if entry is None:
        return error("UNAUTHORIZED", "missing or invalid bearer token", 401)
    return Principal(token=token.strip(), org_id=entry["org_id"], scopes=list(entry.get("scopes", [])))


def need_auth(request: Request) -> Principal | JSONResponse:
    p = get_principal(request)
    return p


def need_scope(principal: Principal, scope: str) -> JSONResponse | None:
    # "rc" implies read; "read" is read-only everywhere (M1 simplification).
    if scope == "read":
        if "read" in principal.scopes or "rc" in principal.scopes:
            return None
    elif scope == "rc":
        if "rc" in principal.scopes:
            return None
    return error("FORBIDDEN", f"insufficient scope (need {scope!r})", 403)


def auth_read(request: Request) -> Principal | JSONResponse:
    p = need_auth(request)
    if isinstance(p, JSONResponse):
        return p
    denied = need_scope(p, "read")
    return denied if denied is not None else p


def auth_write(request: Request) -> Principal | JSONResponse:
    p = need_auth(request)
    if isinstance(p, JSONResponse):
        return p
    denied = need_scope(p, "rc")
    return denied if denied is not None else p


def ok(principal_or_response) -> Principal | None:
    return None if isinstance(principal_or_response, JSONResponse) else principal_or_response


def get_db(request: Request) -> Session:
    return request.app.state.session_factory()


def audit(db: Session, principal: Principal, action: str, resource_type: str, resource_id: str) -> None:
    db.add(
        models.AuditLog(
            actor=principal.token,
            org_id=principal.org_id,
            action=action,
            resource_type=resource_type,
            resource_id=resource_id,
        )
    )


def paginate(request: Request, query):
    """Cursor pagination over insertion order (integer PK asc).

    Returns (rows, next_cursor). ``cursor`` is an opaque row-id string.
    """
    try:
        limit = int(request.query_params.get("limit", "100"))
    except ValueError:
        limit = 100
    limit = max(1, min(limit, 500))
    cursor = request.query_params.get("cursor")
    model = query.column_descriptions[0]["entity"]
    q = query.order_by(model.id.asc())
    if cursor:
        try:
            q = q.filter(model.id > int(cursor))
        except ValueError:
            pass
    rows = q.limit(limit + 1).all()
    if len(rows) > limit:
        return rows[:limit], str(rows[limit - 1].id)
    return rows, None


# Keep Depends import used for potential future wiring (avoids lint noise).
_ = Depends
