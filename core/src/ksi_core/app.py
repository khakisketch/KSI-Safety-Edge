"""FastAPI application factory."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import db as db_mod
from .config import load_settings
from .routers import annotations as annotations_router
from .routers import bundles, collections, events, flights, health, ingest, missions, review, sites, ui
from .routers import responses as responses_router


def create_app() -> FastAPI:
    """Build the app, reading env config now and creating tables on startup."""
    settings = load_settings()
    engine = db_mod.make_engine(settings.db_url)
    session_factory = db_mod.make_session_factory(engine)
    # Create tables synchronously so tests hitting the ASGI app directly
    # (no lifespan run) still get a ready database.
    db_mod.create_all(engine)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        db_mod.create_all(engine)
        yield

    app = FastAPI(title="KSI Safety Edge Core", lifespan=lifespan)
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = session_factory

    prefix = "/api/v1/edge"
    app.include_router(health.router, prefix=prefix)
    app.include_router(sites.router, prefix=prefix)
    app.include_router(missions.router, prefix=prefix)
    app.include_router(bundles.router, prefix=prefix)
    app.include_router(flights.router, prefix=prefix)
    app.include_router(ingest.router, prefix=prefix)
    app.include_router(collections.router, prefix=prefix)
    app.include_router(events.router, prefix=prefix)
    app.include_router(annotations_router.router, prefix=prefix)
    app.include_router(responses_router.router, prefix=prefix)
    app.include_router(review.router, prefix=prefix)

    # Developer Core-inspection shell at /review (no prefix, no auth). It
    # drives existing read APIs with the developer's token; no records embed.
    app.include_router(ui.router)

    return app
