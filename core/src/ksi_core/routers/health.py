from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse

router = APIRouter()


@router.get("/healthz")
def healthz() -> JSONResponse:
    return JSONResponse({"ok": True})
