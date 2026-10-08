"""Shared per-request helpers for routers (auth + db lifecycle)."""

from fastapi import Request
from fastapi.responses import JSONResponse

from ..auth import Principal, auth_read, auth_write, get_db, ok


def authorize(request: Request, write: bool) -> tuple[Principal | None, JSONResponse | None]:
    principal = auth_write(request) if write else auth_read(request)
    if isinstance(principal, JSONResponse):
        return None, principal
    return principal, None


def use_db(request: Request):
    return get_db(request)


__all__ = ["authorize", "use_db", "ok"]
