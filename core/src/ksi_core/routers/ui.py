"""Core-side developer inspection shell (served without record data or auth).

`GET /review` serves the HTML shell with one safe server-side label: whether
the configured database/object-store pair matches the dedicated demo-store
names. It embeds no record data and needs no auth. The developer pastes a Core
read token into the page; the console calls EXISTING APIs with a bearer
header, so this adds no API or auth contract. Unknown stores are never labeled
as production/real merely because their origin is reachable.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

router = APIRouter()

_STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
_REVIEW_HTML = _STATIC_DIR / "review.html"
_REPO_ROOT = Path(__file__).resolve().parents[4]
_DEMO_DB = _REPO_ROOT / "core" / "var" / "review-demo.db"
_DEMO_OBJECTS = _REPO_ROOT / "core" / "var" / "review-demo-objects"


@router.get("/review", response_class=HTMLResponse, include_in_schema=False)
def review_console(request: Request) -> HTMLResponse:
    """Serve the shell and a non-sensitive, conservative data-source label."""
    if not _REVIEW_HTML.is_file():
        return HTMLResponse(
            "<!doctype html><title>KSI review</title>"
            "<p>review.html missing from the package (static/ not found)</p>",
            status_code=500,
        )

    settings = getattr(request.app.state, "settings", None)
    profile = "unverified"
    if settings and settings.db_url.startswith("sqlite:///"):
        # Only the canonical locations created by the demo seed script qualify.
        # Matching basenames elsewhere could mislabel a different data store.
        db_path = settings.db_url.removeprefix("sqlite:///").split("?", 1)[0]
        if (
            Path(db_path).absolute() == _DEMO_DB.absolute()
            and Path(settings.object_root).absolute() == _DEMO_OBJECTS.absolute()
        ):
            profile = "demo"

    html = _REVIEW_HTML.read_text(encoding="utf-8").replace("__SOURCE_PROFILE__", profile, 1)
    return HTMLResponse(html, headers={"Cache-Control": "no-store"})
