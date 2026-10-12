"""Review console (A-1): a static, data-free human page for L1 post-flight review.

`GET /review` serves a single HTML file that embeds NO data and needs no
auth. The operator pastes their Core read token into the page; the console
then calls the EXISTING read APIs with a bearer header — so this adds no new
server auth surface and no new contract. Production authentication
(user accounts, sessions, TLS) remains the recorded pre-deployment gate.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter()

_STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
_REVIEW_HTML = _STATIC_DIR / "review.html"


@router.get("/review", response_class=HTMLResponse, include_in_schema=False)
def review_console() -> HTMLResponse:
    """Serve the static review console shell (no embedded data)."""
    if not _REVIEW_HTML.is_file():
        return HTMLResponse(
            "<!doctype html><title>KSI review</title>"
            "<p>review.html missing from the package (static/ not found)</p>",
            status_code=500,
        )
    return HTMLResponse(_REVIEW_HTML.read_text(encoding="utf-8"))
